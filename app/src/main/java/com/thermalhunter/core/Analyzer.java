package com.thermalhunter.core;

import com.thermalhunter.R;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 root 端的原始快照转换成"嫌疑实体"并打分。
 * CPU 取最近 WINDOW 次采样的平均值（约 15 秒），避免瞬时尖峰误判；
 * 唤醒锁 / 闹钟来自 LSPosed hook 数据，按最近约 2 分钟的增量计算。
 */
public final class Analyzer {

    public static final int APP = 0, NATIVE = 1, KERNEL = 2, KSU = 3, LSP = 4;
    public static final int OK = 0, WARN = 1, BAD = 2;

    // ---- 判定阈值（CPU 为单核百分比，与 top 一致） ----
    static final int WINDOW = 5;
    static final double CPU_HIGH = 35;        // 任何进程持续高于此值
    static final double CPU_BG = 10;          // 后台(adj>=200)进程
    static final double CPU_CACHED = 3;       // 缓存进程(adj>=900)理应几乎不占 CPU
    static final double CPU_NATIVE = 20;      // 非系统关键的 native 进程
    static final double CPU_KSU = 5;          // KSU 模块进程合计
    static final double CPU_LSP_SELF = 8;     // LSP 模块自身进程
    static final double LSP_MEASURED_BAD = 5;  // 自动归因：模块代码实测占用（单核%）
    static final double LSP_MEASURED_WARN = 2;
    static final double CPU_KERNEL_WARN = 30; // 内核线程(只提示，不可杀)
    static final double WL_FRAC_BAD = 0.5;    // 后台应用持锁时间占比
    static final double ALARM_PER_MIN_BAD = 6;

    public static final class Entity {
        public int type;
        public String key, title, sub = "";
        public String pkg = "";
        public int user;
        public String ksuId, lspPkg;
        public final List<Integer> pids = new ArrayList<>();
        public final List<String> pidNames = new ArrayList<>();
        public double cpuNow, cpuAvg;
        public long rssKb;
        public int minAdj = 1001;
        public boolean foreground, isProtected, enabled = true, isSelf;
        public double wlFrac = -1, alarmsPerMin = -1;
        public long wlHeldTotalMs, alarmsTotal;
        public String wlTag;
        public long powerMw = -1;
        public double heat;
        public int level = OK;
        public final List<String> reasons = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> tags = new ArrayList<>();
        public JSONObject raw;
        public final List<String> scopes = new ArrayList<>();
        /** 作用域内普通应用（宿主）进程的 CPU 合计，不含 system_server；这是宿主的总占用，不是模块自身的开销。 */
        public double scopeCpu;
        public int scopeRunning;
        /** 注入系统框架时 system_server 的 CPU；所有这类模块共用同一个进程，无法拆分到单个模块。-1 表示不适用。 */
        public double systemCpu = -1;
        public boolean injectsSystem;
        /** 自动归因实测的模块代码 CPU（单核 %），-1 表示没有实测数据。 */
        public double measuredCpu = -1;
        /** 实测明细：{cpu, procs:{进程:cpu}, methods:[{name,cpu}]}。 */
        public JSONObject measured;
    }

    public static final class Result {
        public JSONObject snap;
        public final List<Entity> heat = new ArrayList<>();   // 应用/进程/内核线程
        public final List<Entity> ksu = new ArrayList<>();
        public final List<Entity> lsp = new ArrayList<>();
        public final List<Entity> wake = new ArrayList<>();   // 有唤醒数据的应用
        public double maxTemp = -999, batteryTemp = -999, skinTemp = -999;
        public String maxTempZone = "";
        public long powerMw = -1;
        public boolean discharging;
        public boolean hookOk;
        public long hookAgeSec = -1;
        public int badCount;
        /** root 进程返回的自动归因状态（可能为 null）。 */
        public JSONObject prof;
    }

    private final ArrayDeque<Map<String, Double>> cpuHistory = new ArrayDeque<>();
    private final ArrayDeque<Object[]> hookHistory = new ArrayDeque<>(); // {elapsedMs, pkgs JSONObject}

    public Result update(JSONObject s) {
        Result r = new Result();
        r.snap = s;
        int selfPid = s.optInt("selfPid", -1);

        // ---------------- 温度与功耗
        JSONArray th = s.optJSONArray("thermal");
        if (th != null) {
            for (int i = 0; i < th.length(); i++) {
                JSONObject z = th.optJSONObject(i);
                String type = z.optString("type").toLowerCase();
                double t = z.optDouble("temp");
                if (isSocZone(type) && t > r.maxTemp) {
                    r.maxTemp = t;
                    r.maxTempZone = z.optString("type");
                }
                if ((type.contains("shell") || type.contains("skin") || type.contains("board") || type.contains("ap_ntc"))
                        && t > r.skinTemp) r.skinTemp = t;
            }
        }
        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            r.batteryTemp = bat.optDouble("tempC", -999);
            r.discharging = "Discharging".equalsIgnoreCase(bat.optString("status"))
                    || "Not charging".equalsIgnoreCase(bat.optString("status"));
            r.powerMw = bat.optLong("powerMw", -1);
        }

        // ---------------- 进程 → 实体
        Map<String, Entity> heat = new LinkedHashMap<>();
        Map<String, Double> cpuNow = new HashMap<>();
        Map<String, List<Entity>> byPkg = new HashMap<>();
        JSONArray procs = s.optJSONArray("procs");
        Entity systemServer = null;
        if (procs != null) {
            for (int i = 0; i < procs.length(); i++) {
                JSONObject p = procs.optJSONObject(i);
                String kind = p.optString("kind");
                int pid = p.optInt("pid");
                int uid = p.optInt("uid");
                String pkg = p.optString("pkg");
                String name = p.optString("name");
                String key;
                int type;
                if ("app".equals(kind) && !pkg.isEmpty()) {
                    key = "app:" + pkg + ":" + (uid / 100000);
                    type = APP;
                } else if ("kernel".equals(kind)) {
                    key = "k:" + pid;
                    type = KERNEL;
                } else {
                    key = "n:" + pid + ":" + name;
                    type = NATIVE;
                }
                Entity e = heat.get(key);
                if (e == null) {
                    e = new Entity();
                    e.type = type;
                    e.key = key;
                    e.pkg = pkg;
                    e.user = uid / 100000;
                    e.title = type == APP ? pkg : name;
                    e.ksuId = p.has("ksu") ? p.optString("ksu") : null;
                    e.raw = p;
                    heat.put(key, e);
                    if (type == APP) {
                        List<Entity> l = byPkg.get(pkg);
                        if (l == null) byPkg.put(pkg, l = new ArrayList<>());
                        l.add(e);
                    }
                }
                e.pids.add(pid);
                e.pidNames.add(type == APP ? p.optString("cmd").split(" ")[0] : name);
                e.cpuNow += p.optDouble("cpu");
                e.rssKb += p.optLong("rss");
                e.minAdj = Math.min(e.minAdj, p.optInt("adj"));
                if (pid == selfPid || Protect.isProtectedProcName(p.optString("cmd")) || Protect.isProtectedProcName(name)
                        || (type == APP && Protect.PKGS.contains(pkg))) {
                    e.isProtected = true;
                }
                if (pid == selfPid || "com.thermalhunter".equals(pkg)) e.isSelf = true;
                if ("system_server".equals(name)) systemServer = e;
            }
        }
        for (Entity e : heat.values()) cpuNow.put(e.key, e.cpuNow);
        cpuHistory.addLast(cpuNow);
        while (cpuHistory.size() > WINDOW) cpuHistory.removeFirst();
        double totalCpu = 0;
        for (Entity e : heat.values()) {
            double sum = 0;
            for (Map<String, Double> h : cpuHistory) {
                Double v = h.get(e.key);
                if (v != null) sum += v;
            }
            e.cpuAvg = sum / cpuHistory.size();
            e.foreground = e.minAdj == 0;
            totalCpu += e.cpuAvg;
        }

        // ---------------- LSPosed hook 数据（唤醒锁/闹钟）
        JSONObject hook = s.optJSONObject("hook");
        Map<String, double[]> wakeRates = new HashMap<>(); // pkg -> {wlFrac, alarmsPerMin}
        JSONObject hookPkgs = null;
        if (hook != null && hook.optBoolean("present")) {
            r.hookAgeSec = hook.optLong("ageSec", -1);
            r.hookOk = r.hookAgeSec >= 0 && r.hookAgeSec < 90;
            hookPkgs = hook.optJSONObject("pkgs");
            long el = hook.optLong("elapsed");
            if (hookPkgs != null && (hookHistory.isEmpty() || (long) hookHistory.peekLast()[0] != el)) {
                hookHistory.addLast(new Object[]{el, hookPkgs});
            }
            while (hookHistory.size() > 2 && el - (long) hookHistory.peekFirst()[0] > 150_000) hookHistory.removeFirst();
            if (hookHistory.size() >= 2) {
                Object[] first = hookHistory.peekFirst();
                Object[] last = hookHistory.peekLast();
                double dt = (long) last[0] - (long) first[0];
                if (dt >= 14_000) {
                    JSONObject a = (JSONObject) first[1], b = (JSONObject) last[1];
                    for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                        String pkg = it.next();
                        JSONObject nb = b.optJSONObject(pkg), na = a.optJSONObject(pkg);
                        long held = nb.optLong("wlHeldMs") - (na == null ? 0 : na.optLong("wlHeldMs"));
                        long al = nb.optLong("alarms") - (na == null ? 0 : na.optLong("alarms"));
                        wakeRates.put(pkg, new double[]{Math.min(1.0, held / dt), al * 60_000.0 / dt});
                    }
                }
            }
        }

        // 把 hook 数据合并进应用实体；对没有进程但仍在持锁的应用也建实体
        if (hookPkgs != null) {
            for (Iterator<String> it = hookPkgs.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject o = hookPkgs.optJSONObject(pkg);
                // hook 统计按包名聚合、不区分用户：优先主用户实体，否则取任一在跑的用户实体，
                // 否则分身 / 工作资料（user>0）的应用会关联不上唤醒数据
                Entity e = null;
                List<Entity> users = byPkg.get(pkg);
                if (users != null) {
                    for (Entity u : users) if (u.user == 0) { e = u; break; }
                    if (e == null) e = users.get(0);
                }
                double[] rate = wakeRates.get(pkg);
                boolean significant = rate != null && (rate[0] >= 0.2 || rate[1] >= 2);
                if (e == null && significant) {
                    e = new Entity();
                    e.type = APP;
                    e.key = "app:" + pkg + ":0";
                    e.pkg = pkg;
                    e.title = pkg;
                    e.isProtected = Protect.PKGS.contains(pkg);
                    heat.put(e.key, e);
                }
                Entity w = e != null ? e : new Entity();
                if (e == null) {
                    w.type = APP;
                    w.key = "wake:" + pkg;
                    w.pkg = pkg;
                    w.title = pkg;
                    w.isProtected = Protect.PKGS.contains(pkg);
                }
                w.wlHeldTotalMs = o.optLong("wlHeldMs");
                w.alarmsTotal = o.optLong("alarms");
                w.wlTag = o.optString("tag", null);
                if (rate != null) {
                    w.wlFrac = rate[0];
                    w.alarmsPerMin = rate[1];
                }
                if (w.wlHeldTotalMs > 60_000 || w.alarmsTotal > 20 || significant) r.wake.add(w);
            }
            Collections.sort(r.wake, (x, y) -> Long.compare(y.wlHeldTotalMs, x.wlHeldTotalMs));
        }

        // ---------------- 规则判定
        for (Entity e : heat.values()) {
            if (r.discharging && r.powerMw > 0 && totalCpu > 0) {
                e.powerMw = Math.round(r.powerMw * 0.7 * e.cpuAvg / totalCpu); // 约 70% 功耗归于 CPU 负载分摊
            }
            String c = fmt(e.cpuAvg);
            switch (e.type) {
                case APP:
                    if (e.cpuAvg >= CPU_HIGH) bad(e, L.s(R.string.an_high_cpu, c));
                    else if (e.minAdj >= 900 && e.cpuAvg >= CPU_CACHED) bad(e, L.s(R.string.an_cached_cpu, c));
                    else if (e.minAdj >= 200 && e.cpuAvg >= CPU_BG) bad(e, L.s(R.string.an_bg_cpu, c));
                    if (r.discharging && !e.foreground && e.wlFrac >= WL_FRAC_BAD) bad(e, L.s(R.string.an_bg_wl, Math.round(e.wlFrac * 100)));
                    if (e.alarmsPerMin >= ALARM_PER_MIN_BAD) bad(e, L.s(R.string.an_alarms, fmt(e.alarmsPerMin)));
                    break;
                case NATIVE:
                    if (e.ksuId != null) {
                        e.tags.add(L.s(R.string.an_tag_ksu, e.ksuId));
                        if (e.cpuAvg >= CPU_KSU) bad(e, L.s(R.string.an_ksu_proc_cpu, c));
                    } else if (e.cpuAvg >= CPU_NATIVE) {
                        bad(e, L.s(R.string.an_native_cpu, c));
                    }
                    break;
                case KERNEL:
                    if (e.cpuAvg >= CPU_KERNEL_WARN) warn(e, L.s(R.string.an_kernel_load, c));
                    break;
            }
            if (e.foreground && e.level == BAD) e.tags.add(L.s(R.string.adj_fg));
            e.heat = e.cpuAvg + Math.max(0, e.wlFrac) * 20 + Math.min(30, Math.max(0, e.alarmsPerMin)) * 0.5
                    + (e.level == BAD ? 1000 : 0);
        }
        r.heat.addAll(heat.values());
        r.heat.removeIf(e -> e.type == KERNEL && e.cpuAvg < 1 && e.level == OK);
        Collections.sort(r.heat, (x, y) -> Double.compare(y.heat, x.heat));

        // ---------------- 自动归因（root 进程 simpleperf 采样的实测结果）
        JSONObject prof = s.optJSONObject("prof");
        r.prof = prof;
        boolean profOk = prof != null && prof.optBoolean("ok");
        JSONObject profMods = profOk ? prof.optJSONObject("modules") : null;

        // ---------------- KSU 模块
        double maxT = Math.max(r.maxTemp, r.skinTemp);
        JSONArray ksu = s.optJSONArray("ksu");
        if (ksu != null) {
            for (int i = 0; i < ksu.length(); i++) {
                JSONObject m = ksu.optJSONObject(i);
                Entity e = new Entity();
                e.type = KSU;
                e.ksuId = m.optString("id");
                e.key = "ksu:" + e.ksuId;
                e.title = m.optString("name");
                e.sub = m.optString("id") + "  " + m.optString("version") + "  " + m.optString("author");
                e.enabled = m.optBoolean("enabled");
                e.raw = m;
                // 用应用侧 CPU 历史平均，避免瞬时值
                for (Entity h : heat.values()) {
                    if (e.ksuId.equals(h.ksuId)) {
                        e.cpuAvg += h.cpuAvg;
                        e.cpuNow += h.cpuNow;
                        e.pids.addAll(h.pids);
                        e.pidNames.addAll(h.pidNames);
                        e.rssKb += h.rssKb;
                        if (h.powerMw > 0) e.powerMw = Math.max(0, e.powerMw) + h.powerMw;
                    }
                }
                JSONArray tags = m.optJSONArray("tags");
                if (tags != null) for (int t = 0; t < tags.length(); t++) e.tags.add(tags.optString(t));
                if (!e.enabled) e.tags.add(0, L.s(R.string.tag_disabled));
                if (m.optBoolean("remove")) e.tags.add(0, L.s(R.string.tag_pending_removal));
                if (e.enabled && e.cpuAvg >= CPU_KSU) bad(e, L.s(R.string.an_module_cpu, fmt(e.cpuAvg)));
                // Zygisk 等方式注入到其他进程里的 Java 代码（如 HMA-OSS），由自动归因实测
                e.measured = profMods == null ? null : profMods.optJSONObject("ksu:" + e.ksuId);
                if (e.measured != null) {
                    e.measuredCpu = e.measured.optDouble("cpu", 0);
                    String where = topProc(e.measured);
                    if (e.enabled && e.measuredCpu >= LSP_MEASURED_BAD) {
                        bad(e, L.s(R.string.an_injected_measured, fmt(e.measuredCpu), where));
                    } else if (e.enabled && e.measuredCpu >= LSP_MEASURED_WARN) {
                        warn(e, L.s(R.string.an_injected_measured, fmt(e.measuredCpu), where));
                    }
                }
                if (e.enabled && maxT >= 45 && (e.tags.contains(L.s(R.string.ksu_tag_thermal_replace)) || e.tags.contains(L.s(R.string.ksu_tag_script_thermal)))) {
                    warn(e, L.s(R.string.an_thermal_mod, fmt(maxT)));
                }
                if (e.enabled && e.tags.contains(L.s(R.string.ksu_tag_wakelock))) warn(e, L.s(R.string.an_wakelock_script));
                e.heat = e.cpuAvg + Math.max(0, e.measuredCpu) + (e.level == BAD ? 1000 : e.level == WARN ? 100 : 0) + (e.enabled ? 1 : 0);
                r.ksu.add(e);
            }
            Collections.sort(r.ksu, (x, y) -> Double.compare(y.heat, x.heat));
        }

        // ---------------- LSPosed 模块
        JSONObject lsp = s.optJSONObject("lsp");
        if (lsp != null) {
            JSONArray mods = lsp.optJSONArray("modules");
            if (mods != null) {
                for (int i = 0; i < mods.length(); i++) {
                    JSONObject m = mods.optJSONObject(i);
                    Entity e = new Entity();
                    e.type = LSP;
                    e.lspPkg = m.optString("pkg");
                    e.pkg = e.lspPkg;
                    e.key = "lsp:" + e.lspPkg;
                    e.title = e.lspPkg;
                    e.enabled = m.optBoolean("enabled");
                    e.raw = m;
                    e.isProtected = "com.thermalhunter".equals(e.lspPkg);
                    e.isSelf = e.isProtected;
                    List<Entity> own = byPkg.get(e.lspPkg);
                    if (own != null) for (Entity o : own) {
                        e.cpuAvg += o.cpuAvg;
                        e.pids.addAll(o.pids);
                        e.pidNames.addAll(o.pidNames);
                        e.minAdj = Math.min(e.minAdj, o.minAdj);
                    }
                    boolean injectsSystem = false;
                    List<String> badScoped = new ArrayList<>();
                    JSONArray sc = m.optJSONArray("scope");
                    if (sc != null) for (int k = 0; k < sc.length(); k++) {
                        JSONObject so = sc.optJSONObject(k);
                        String sp = so.optString("pkg");
                        e.scopes.add(so.optInt("user") != 0 ? L.s(R.string.an_scope_user, sp, so.optInt("user")) : sp);
                        if ("system".equals(sp) || "android".equals(sp)) {
                            // system_server 由所有注入系统框架的模块共享，不能把它的 CPU 算到某一个模块头上
                            injectsSystem = true;
                            if (systemServer != null) {
                                e.systemCpu = systemServer.cpuAvg;
                                e.scopeRunning++;
                            }
                            continue;
                        }
                        List<Entity> running = byPkg.get(sp);
                        if (running != null) for (Entity se : running) {
                            if (se.user != so.optInt("user")) continue;
                            e.scopeCpu += se.cpuAvg;
                            e.scopeRunning++;
                            if (se.level == BAD) badScoped.add(sp);
                        }
                    }
                    e.injectsSystem = injectsSystem;
                    if (profOk) {
                        // 有实测数据时：没出现在结果里的模块即实测为 0（宿主空闲或模块代码没在运行）
                        e.measured = profMods == null ? null : profMods.optJSONObject(e.lspPkg);
                        e.measuredCpu = e.measured == null ? 0 : e.measured.optDouble("cpu", 0);
                    }
                    e.sub = L.s(R.string.an_scope_sub, e.scopes.size(), e.scopeRunning);
                    if (!e.enabled) e.tags.add(L.s(R.string.tag_disabled));
                    if (injectsSystem) e.tags.add(L.s(R.string.tag_injects_system));
                    if (e.scopes.size() >= 30) e.tags.add(L.s(R.string.tag_scope_too_large));
                    if (e.enabled && !e.isSelf) {
                        if (e.cpuAvg >= CPU_LSP_SELF && e.minAdj > 0) bad(e, L.s(R.string.an_lsp_self_cpu, fmt(e.cpuAvg)));
                        if (e.measuredCpu >= 0) {
                            // 实测：按调用栈归属到本模块的 CPU
                            String where = topProc(e.measured);
                            if (e.measuredCpu >= LSP_MEASURED_BAD) {
                                bad(e, L.s(R.string.an_lsp_measured, fmt(e.measuredCpu), where));
                            } else if (e.measuredCpu >= LSP_MEASURED_WARN) {
                                warn(e, L.s(R.string.an_lsp_measured, fmt(e.measuredCpu), where));
                            }
                        } else if (!badScoped.isEmpty()) {
                            warn(e, L.s(R.string.an_scope_bad_apps, join(badScoped)));
                        }
                        if (e.measuredCpu < 0 && injectsSystem && systemServer != null && systemServer.cpuAvg >= 15) {
                            // 只作提示，不标记可疑：无法确定是哪一个模块造成的
                            e.warnings.add(L.s(R.string.an_system_server_shared, fmt(systemServer.cpuAvg)));
                        }
                    }
                    e.heat = (e.measuredCpu >= 0 ? e.measuredCpu * 10 : e.scopeCpu * 0.3) + e.cpuAvg
                            + (e.level == BAD ? 1000 : e.level == WARN ? 100 : 0) + (e.enabled ? 1 : 0);
                    r.lsp.add(e);
                }
                Collections.sort(r.lsp, (x, y) -> Double.compare(y.heat, x.heat));
            }
        }

        for (Entity e : r.heat) if (e.level == BAD && !e.isProtected && !e.isSelf) r.badCount++;
        for (Entity e : r.ksu) if (e.level == BAD) r.badCount++;
        for (Entity e : r.lsp) if (e.level == BAD) r.badCount++;
        return r;
    }

    /** 一键清理的目标：异常、非受保护、非自身；默认不动前台应用。 */
    public static List<Entity> cleanTargets(Result r, boolean includeForeground) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : r.heat) {
            if (e.level != BAD || e.isProtected || e.isSelf || e.type == KERNEL) continue;
            if (e.foreground && !includeForeground) continue;
            if (e.pids.isEmpty() && e.type != APP) continue;
            out.add(e);
        }
        for (Entity e : r.ksu) if (e.level == BAD && !e.pids.isEmpty()) out.add(e);
        for (Entity e : r.lsp) if (e.level == BAD && !e.isProtected) out.add(e);
        return out;
    }

    /** 实测明细里占用最多的宿主进程，如 "（主要在 system_server）"。 */
    private static String topProc(JSONObject measured) {
        JSONObject procs = measured == null ? null : measured.optJSONObject("procs");
        if (procs == null) return "";
        String best = null;
        double v = -1;
        for (Iterator<String> it = procs.keys(); it.hasNext(); ) {
            String k = it.next();
            if (procs.optDouble(k) > v) {
                v = procs.optDouble(k);
                best = k;
            }
        }
        return best == null ? "" : L.s(R.string.an_where, best);
    }

    static boolean isSocZone(String t) {
        return t.contains("cpu") || t.contains("soc") || t.contains("mtktscpu") || t.contains("gpu")
                || t.contains("apu") || t.contains("tsens") || t.contains("core");
    }

    private static void bad(Entity e, String reason) {
        e.reasons.add(reason);
        e.level = BAD;
    }

    private static void warn(Entity e, String w) {
        e.warnings.add(w);
        if (e.level < WARN) e.level = WARN;
    }

    public static String fmt(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size() && i < 4; i++) {
            if (i > 0) sb.append(L.s(R.string.list_sep));
            sb.append(l.get(i));
        }
        return l.size() > 4 ? L.s(R.string.list_more, sb, l.size()) : sb.toString();
    }
}
