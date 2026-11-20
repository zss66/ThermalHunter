package com.thermalhunter.core;

import com.thermalhunter.root.T;
import com.thermalhunter.R;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 长时耗电统计：解析系统自带的 batterystats（自上次充满以来的累计数据）并判定异常。
 *
 * 短时间的功率读数只能反映当下，看不出「一直在后台偷偷耗电」的应用；系统本身持续记录了
 * 每个 uid 的耗电（mAh）、唤醒锁、唤醒闹钟、定位、CPU 时间以及熄屏掉电和深睡情况，
 * 读取它不需要本工具在后台常驻采样，也就不会额外耗电。
 *
 * 数据来源：{@code dumpsys batterystats -c}（checkin 格式，逗号分隔，字段顺序稳定）。
 * 注意不能用 {@code --checkin}：它会读走并删除系统为 Google 签入保存的上一周期文件，
 * 而且此时输出的是旧周期数据。
 *
 * root 端（RootMain 的 DRAIN 命令）和 App 端（后台定时检查）共用本类。
 */
public final class DrainStats {

    public static final int OK = 0, WARN = 1, BAD = 2;

    private static final long MIN = 60_000L, HOUR = 3_600_000L;

    /** batterystats 中各耗电组件的 checkin 标签 → 名称（root 端解析，存为文字令牌，App 端再翻译）。 */
    private static final Map<String, String> COMPONENTS = new HashMap<>();

    static {
        String[] kv = {
                "scrn", "comp_screen", "blue", "comp_bt", "camera", "comp_camera", "cam", "comp_camera",
                "audio", "comp_audio", "video", "comp_video", "flashlight", "comp_flashlight", "flash", "comp_flashlight",
                "cell", "comp_cell", "radio", "comp_cell", "sensors", "comp_sensors", "gnss", "comp_location",
                "gps", "comp_location", "wifi", "comp_wifi", "memory", "comp_memory", "phone", "comp_phone", "ambi", "comp_aod",
                "idle", "comp_idle", "wakelock", "comp_wakelock", "user", "comp_other_users", "unacc", "comp_unaccounted",
                "over", "comp_overcounted",
        };
        COMPONENTS.put("cpu", "CPU");
        for (int i = 0; i < kv.length; i += 2) COMPONENTS.put(kv[i], T.k(kv[i + 1]));
    }

    private static final class Uid {
        final int uid;
        double mah, screenMah;
        long cpuMs, wlMs, bgWlMs, alarms, topMs, fgsMs, fgMs, bgMs, cachedMs, gpsMs;
        final Map<String, Long> wakelocks = new HashMap<>();
        final Map<String, Long> alarmTags = new HashMap<>();

        Uid(int uid) {
            this.uid = uid;
        }
    }

    private DrainStats() {
    }

    // ------------------------------------------------------------------ 解析

    /** 流式解析 {@code dumpsys batterystats -c} 的输出，跳过体积很大的历史记录行。 */
    public static JSONObject parse(BufferedReader in) throws IOException, JSONException {
        Map<Integer, Uid> uids = new HashMap<>();
        Map<Integer, List<String>> pkgs = new HashMap<>();
        Map<String, Double> components = new HashMap<>();
        List<Object[]> kernelWl = new ArrayList<>(), wakeReasons = new ArrayList<>();
        long[] bt = null, dc = null;
        double capacity = 0, consumed = 0;
        long screenOnMs = -1;
        boolean any = false;

        String line;
        while ((line = in.readLine()) != null) {
            // 历史行 "9,h,..."，以及其他非数据行
            if (line.length() < 8 || line.startsWith("9,h,") || !line.startsWith("9,")) continue;
            String[] a = line.split(",", -1);
            if (a.length < 5) continue;
            String cat = a[2], type = a[3];
            if (cat.equals("i")) {
                if (type.equals("uid") && a.length >= 6) {
                    int uid = toInt(a[4]);
                    List<String> l = pkgs.get(uid);
                    if (l == null) pkgs.put(uid, l = new ArrayList<>());
                    if (!l.contains(a[5])) l.add(a[5]);
                }
                continue;
            }
            if (!cat.equals("l")) continue;
            any = true;
            int uid = toInt(a[1]);
            switch (type) {
                case "bt":
                    bt = longs(a, 4, 9);
                    break;
                case "dc":
                    dc = longs(a, 4, 8);
                    break;
                case "m":
                    screenOnMs = toLong(a[4]);
                    break;
                case "pws":
                    if (a.length >= 6) {
                        capacity = toDouble(a[4]);
                        consumed = toDouble(a[5]);
                    }
                    break;
                case "pwi": {
                    if (a.length < 6) break;
                    double mah = toDouble(a[5]);
                    if (a[4].equals("uid")) {
                        Uid u = uid(uids, uid);
                        u.mah += mah;
                        if (a.length >= 8) u.screenMah += toDouble(a[7]);
                    } else if (uid == 0 && mah > 0) {
                        components.merge(a[4], mah, Double::sum);
                    }
                    break;
                }
                case "cpu":
                    if (a.length >= 6) {
                        Uid u = uid(uids, uid);
                        u.cpuMs += toLong(a[4]) + toLong(a[5]);
                    }
                    break;
                case "wl": {
                    // 9,uid,l,wl,<name>,<fMs>,f,<n>,..,<pMs>,p,<n>,..,<bpMs>,bp,<n>,..,<wMs>,w,..
                    long p = 0, bp = 0;
                    for (int i = 6; i < a.length; i++) {
                        if (a[i].equals("p")) p = toLong(a[i - 1]);
                        else if (a[i].equals("bp")) bp = toLong(a[i - 1]);
                    }
                    if (p <= 0 && bp <= 0) break;
                    Uid u = uid(uids, uid);
                    u.wlMs += p;
                    u.bgWlMs += bp;
                    u.wakelocks.merge(a[4], p, Long::sum);
                    break;
                }
                case "wua":
                    if (a.length >= 6) {
                        Uid u = uid(uids, uid);
                        long n = toLong(a[5]);
                        u.alarms += n;
                        if (n > 0) u.alarmTags.merge(a[4], n, Long::sum);
                    }
                    break;
                case "st":
                    // 进程状态时长：前台(top)、前台服务、前台、后台、顶层休眠、重量级、缓存
                    if (a.length >= 11) {
                        Uid u = uid(uids, uid);
                        u.topMs += toLong(a[4]);
                        u.fgsMs += toLong(a[5]);
                        u.fgMs += toLong(a[6]);
                        u.bgMs += toLong(a[7]);
                        u.cachedMs += toLong(a[10]);
                    }
                    break;
                case "sr":
                    // 传感器：句柄 -10000 为 GPS；第 6 个字段为累计时长
                    if (a.length >= 6 && a[4].equals("-10000")) uid(uids, uid).gpsMs += toLong(a[5]);
                    break;
                case "kwl":
                case "wr": {
                    Object[] nt = quotedNameTime(a);
                    if (nt != null && (long) nt[1] > 0) (type.equals("kwl") ? kernelWl : wakeReasons).add(nt);
                    break;
                }
                default:
                    break;
            }
        }

        JSONObject o = new JSONObject();
        if (!any || bt == null) {
            o.put("ok", false);
            o.put("error", T.k("ds_no_data"));
            return o;
        }
        o.put("ok", true);
        o.put("t", System.currentTimeMillis());
        long onBattery = bt[1];
        long screenOff = bt[6], screenOffAwake = bt[7];
        o.put("startClock", bt[5]);
        o.put("onBatteryMs", onBattery);
        o.put("awakeMs", bt[2]);
        o.put("screenOffMs", screenOff);
        o.put("screenOffAwakeMs", screenOffAwake);
        o.put("screenOnMs", screenOnMs >= 0 ? screenOnMs : Math.max(0, onBattery - screenOff));
        if (capacity <= 0 && bt.length > 8) capacity = bt[8];
        o.put("capacity", capacity);
        o.put("consumedMah", consumed);
        if (dc != null) {
            o.put("dischargePct", Math.max(dc[0], dc[1]));
            o.put("screenOnPct", dc[2]);
            o.put("screenOffPct", dc[3]);
            o.put("dischargeMah", dc[4]);
            o.put("screenOffMah", dc[5]);
        }

        // 各组件耗电
        List<Map.Entry<String, Double>> comps = new ArrayList<>(components.entrySet());
        comps.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        JSONArray ca = new JSONArray();
        for (Map.Entry<String, Double> e : comps) {
            if (ca.length() >= 8) break;
            String name = COMPONENTS.get(e.getKey());
            ca.put(new JSONObject().put("name", name != null ? name : e.getKey()).put("mah", round1(e.getValue())));
        }
        o.put("components", ca);

        // 各 uid
        double uidTotal = 0;
        for (Uid u : uids.values()) uidTotal += Math.max(0, u.mah);
        o.put("uidMah", round1(uidTotal));
        List<Uid> list = new ArrayList<>(uids.values());
        list.sort((x, y) -> {
            int c = Double.compare(y.mah, x.mah);
            return c != 0 ? c : Long.compare(y.cpuMs + y.wlMs, x.cpuMs + x.wlMs);
        });
        JSONArray apps = new JSONArray();
        for (Uid u : list) {
            if (apps.length() >= 80) break;
            if (u.mah < 0.05 && u.wlMs < MIN && u.cpuMs < MIN && u.alarms < 10) continue;
            JSONObject j = new JSONObject();
            j.put("uid", u.uid);
            JSONArray pa = new JSONArray();
            List<String> pl = pkgs.get(u.uid);
            if (pl != null) for (int i = 0; i < pl.size() && i < 4; i++) pa.put(pl.get(i));
            j.put("pkgs", pa);
            j.put("mah", round1(u.mah));
            j.put("screenMah", round1(u.screenMah));
            j.put("cpuMs", u.cpuMs);
            j.put("wlMs", u.wlMs);
            j.put("bgWlMs", u.bgWlMs);
            j.put("alarms", u.alarms);
            j.put("topMs", u.topMs);
            j.put("fgsMs", u.fgsMs);
            j.put("fgMs", u.fgMs);
            j.put("bgMs", u.bgMs);
            j.put("cachedMs", u.cachedMs);
            j.put("gpsMs", u.gpsMs);
            j.put("wakelocks", topOf(u.wakelocks, 4, "ms"));
            j.put("alarmTags", topOf(u.alarmTags, 4, "n"));
            apps.put(j);
        }
        o.put("apps", apps);
        o.put("kernelWakelocks", topNameTime(kernelWl, 6));
        o.put("wakeReasons", topNameTime(wakeReasons, 6));
        analyze(o, null);
        return o;
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 在报告上标注异常：整体（待机掉电速度、熄屏深睡）写入 issues，每个应用写入 level / reasons。
     * @param selfPkg 本应用包名（不判定自己）；可为 null
     */
    public static void analyze(JSONObject o, String selfPkg) throws JSONException {
        long onBattery = o.optLong("onBatteryMs");
        long screenOff = o.optLong("screenOffMs");
        long screenOn = o.optLong("screenOnMs");
        double cap = o.optDouble("capacity", 0);
        JSONArray issues = new JSONArray();

        // 待机（熄屏）掉电速度：优先按 mAh / 容量算，比整数百分比精确
        double offRate = rate(o.optDouble("screenOffMah", 0), o.optLong("screenOffPct", 0), cap, screenOff);
        double onRate = rate(o.optDouble("dischargeMah", 0) - o.optDouble("screenOffMah", 0),
                o.optLong("screenOnPct", 0), cap, screenOn);
        o.put("standbyRate", offRate);
        o.put("activeRate", onRate);
        double awake = screenOff > 0 ? o.optLong("screenOffAwakeMs") / (double) screenOff : -1;
        o.put("awakeRatio", awake);

        if (screenOff >= HOUR && offRate >= 0) {
            if (offRate >= 3) {
                issues.put(issue(BAD, "standby", L.s(R.string.ds_standby_bad, String.format(Locale.US, "%.1f", offRate))));
            } else if (offRate >= 1.5) {
                issues.put(issue(WARN, "standby", L.s(R.string.ds_standby_warn, String.format(Locale.US, "%.1f", offRate))));
            }
        }
        if (screenOff >= HOUR && awake >= 0) {
            int pct = (int) Math.round(awake * 100);
            if (awake >= 0.4) {
                issues.put(issue(BAD, "awake", L.s(R.string.ds_awake_bad, pct)));
            } else if (awake >= 0.15) {
                issues.put(issue(WARN, "awake", L.s(R.string.ds_awake_warn, pct)));
            }
        }

        double uidTotal = Math.max(1, o.optDouble("uidMah", 0));
        double hours = onBattery / (double) HOUR;
        JSONArray apps = o.optJSONArray("apps");
        int bad = 0, warn = 0;
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                List<String> reasons = new ArrayList<>(), warns = new ArrayList<>();
                int uid = a.optInt("uid");
                JSONArray pk = a.optJSONArray("pkgs");
                boolean self = selfPkg != null && pk != null && pk.toString().contains("\"" + selfPkg + "\"");
                // 只判定普通应用；系统 uid（<10000）展示但不标异常，避免误伤
                if (uid % 100000 >= 10000 && !self && onBattery >= 30 * MIN) {
                    judgeApp(a, onBattery, hours, uidTotal, reasons, warns);
                }
                int level = !reasons.isEmpty() ? BAD : !warns.isEmpty() ? WARN : OK;
                if (level == BAD) bad++;
                else if (level == WARN) warn++;
                a.put("level", level);
                JSONArray r = new JSONArray();
                for (String s : reasons) r.put(s);
                a.put("reasons", r);
                JSONArray w = new JSONArray();
                for (String s : warns) w.put(s);
                a.put("warnings", w);
            }
        }
        o.put("badApps", bad);
        o.put("warnApps", warn);
        o.put("issues", issues);
    }

    private static void judgeApp(JSONObject a, long onBattery, double hours, double uidTotal,
                                 List<String> bad, List<String> warn) {
        long top = a.optLong("topMs");
        long fgs = a.optLong("fgsMs");
        double mah = a.optDouble("mah", 0);
        double share = mah / uidTotal;
        // 前台服务（音乐、导航、录音等）在后台运行是用户预期的：相关项最多标为「可疑」
        String fgsNote = fgs >= 30 * MIN ? L.s(R.string.ds_fgs_note, dur(fgs)) : "";

        // 后台唤醒锁：优先用系统统计的后台持锁时长；没有该字段时，前台很少却持锁很久也算
        long bgWl = a.optLong("bgWlMs");
        if (bgWl <= 0 && a.optLong("wlMs") > top * 2) bgWl = a.optLong("wlMs") - top;
        double wlFrac = bgWl / (double) onBattery;
        if (bgWl >= 20 * MIN && wlFrac >= 0.15) {
            if (fgs >= bgWl * 0.8) warn.add(L.s(R.string.ds_bg_wl, dur(bgWl)) + fgsNote);
            else bad.add(L.s(R.string.ds_bg_wl_bad, dur(bgWl), Math.round(wlFrac * 100)));
        } else if (bgWl >= 10 * MIN && wlFrac >= 0.05) {
            warn.add(L.s(R.string.ds_bg_wl_warn, dur(bgWl), Math.round(wlFrac * 100)));
        }

        long alarms = a.optLong("alarms");
        double perHour = hours > 0 ? alarms / hours : 0;
        if (alarms >= 30 && perHour >= 30) {
            bad.add(L.s(R.string.ds_alarms_bad, alarms, String.format(Locale.US, "%.0f", perHour)));
        } else if (alarms >= 20 && perHour >= 10) {
            warn.add(L.s(R.string.ds_alarms_warn, alarms, String.format(Locale.US, "%.0f", perHour)));
        }

        long gps = a.optLong("gpsMs");
        if (gps >= 30 * MIN && gps > top * 2) {
            (gps >= 2 * HOUR ? bad : warn).add(L.s(R.string.ds_bg_gps, dur(gps)));
        }

        long cpu = a.optLong("cpuMs");
        if (cpu > top + fgs + 30 * MIN) {
            long bgCpu = cpu - top - fgs;
            (bgCpu >= 90 * MIN ? bad : warn).add(L.s(R.string.ds_bg_cpu, dur(cpu), dur(top)));
        }

        if (top < 15 * MIN && mah >= 30 && share >= 0.08) {
            (fgsNote.isEmpty() ? bad : warn).add(L.s(R.string.ds_unused_drain,
                    dur(top), String.format(Locale.US, "%.0f", mah), Math.round(share * 100)) + fgsNote);
        } else if (top < 15 * MIN && mah >= 15 && share >= 0.04) {
            warn.add(L.s(R.string.ds_low_fg_drain,
                    dur(top), String.format(Locale.US, "%.0f", mah), Math.round(share * 100)));
        }
    }

    /** 每小时掉电百分比；数据不足时返回 -1。 */
    private static double rate(double mah, long pct, double cap, long ms) {
        if (ms < 10 * MIN) return -1;
        double h = ms / (double) HOUR;
        double p = cap > 0 && mah > 0 ? mah / cap * 100 : pct;
        if (p < 0) return -1;
        return Math.round(p / h * 100) / 100.0;
    }

    private static JSONObject issue(int level, String key, String msg) throws JSONException {
        return new JSONObject().put("level", level).put("key", key).put("msg", msg);
    }

    // ------------------------------------------------------------------ 工具

    public static String dur(long ms) {
        long m = ms / MIN;
        if (m < 1) return L.s(R.string.dur_s, ms / 1000);
        if (m < 60) return L.s(R.string.dur_min, m);
        return L.s(R.string.dur_hm, m / 60, m % 60);
    }

    private static Uid uid(Map<Integer, Uid> m, int uid) {
        Uid u = m.get(uid);
        if (u == null) m.put(uid, u = new Uid(uid));
        return u;
    }

    /** kwl / wr 行：名称带引号且可能含逗号，名称之后依次为 时长ms、次数。 */
    private static Object[] quotedNameTime(String[] a) {
        StringBuilder name = new StringBuilder();
        int i = 4;
        for (; i < a.length; i++) {
            if (name.length() > 0) name.append(',');
            name.append(a[i]);
            if (a[i].endsWith("\"") && name.length() > 1) break;
        }
        if (i + 2 >= a.length) return null;
        String n = name.toString();
        if (n.startsWith("\"")) n = n.substring(1);
        if (n.endsWith("\"")) n = n.substring(0, n.length() - 1);
        return new Object[]{n, toLong(a[i + 1]), toLong(a[i + 2])};
    }

    private static JSONArray topNameTime(List<Object[]> l, int max) throws JSONException {
        l.sort((x, y) -> Long.compare((long) y[1], (long) x[1]));
        JSONArray r = new JSONArray();
        for (int i = 0; i < l.size() && i < max; i++) {
            Object[] e = l.get(i);
            r.put(new JSONObject().put("name", e[0]).put("ms", e[1]).put("n", e[2]));
        }
        return r;
    }

    private static JSONArray topOf(Map<String, Long> m, int max, String key) throws JSONException {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        JSONArray r = new JSONArray();
        for (int i = 0; i < l.size() && i < max; i++) {
            r.put(new JSONObject().put("name", l.get(i).getKey()).put(key, l.get(i).getValue()));
        }
        return r;
    }

    private static long[] longs(String[] a, int from, int minLen) {
        int n = Math.max(minLen, a.length - from);
        long[] r = new long[n];
        for (int i = 0; i < n && from + i < a.length; i++) r[i] = toLong(a[from + i]);
        return r;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static long toLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static double toDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
