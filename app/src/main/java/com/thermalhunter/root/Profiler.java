package com.thermalhunter.root;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 用系统自带的 simpleperf 对目标进程按 CPU 时间（cpu-clock）采样，再按调用栈把 CPU 分到具体的注入来源：
 * LSPosed 模块（键为包名）、带 Java 代码的 KernelSU / Zygisk 模块（键为 "ksu:模块id"，如 HMA-OSS），
 * LSPosed 框架自身（键为 FRAMEWORK，计入「框架转发」）。
 *
 * 归属规则（真机验证后的版本）：
 * 1. 只看非系统分区的帧（/system、/apex 等里的代码一律不算模块，避免同名类误判）；
 * 2. 优先采用「强证据」：类名未混淆且只属于一个来源的帧，取离栈顶最近的那个；
 *    混淆过的短类名（如 tk.a、mb）在各模块间经常撞名，甚至与厂商系统代码撞名，只作弱证据；
 * 3. 没有强证据时，用弱证据逐层取交集；仍有多个候选时，参考同一窗口内的统计关联：
 *    某个混淆类若在「已由强证据确定归属」的样本里总与某来源同时出现，就按该来源归属；
 *    还不能确定的才记为「无法区分」。
 * 每个样本的 event_count 就是它代表的 CPU 纳秒数，因此结果直接是各模块消耗的 CPU 时间。
 *
 * 局限：hook 导致的间接开销（如被 hook 的方法不能再被内联）无法统计，结果会略微低估模块的影响。
 */
final class Profiler {
    private Profiler() {}

    static final String SIMPLEPERF = "/system/bin/simpleperf";
    /** LSPosed 框架自身代码的来源键（归入「框架转发」）。 */
    static final String FRAMEWORK = "framework";
    private static final String DATA = "/data/local/tmp/thermalhunter_perf.data";
    private static final String TEXT = "/data/local/tmp/thermalhunter_perf.txt";
    private static final String[] SYSTEM_PREFIXES = {"/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/"};
    private static final String[] BRIDGE_MARKS = {"de.robv.android.xposed.", "io.github.libxposed.", "org.lsposed.",
            "LSPHooker", "lsplant", "LSPlant"};

    /** apk 路径 + 修改时间 → 类名集合，避免每次都重新解析。 */
    private static final Map<String, Set<String>> DEX_CACHE = new HashMap<>();

    /** 一个采样窗口的结果。所有数值都是 CPU 纳秒。 */
    static final class Window {
        long wallNs, samples, javaSamples, totalNs, bridgeNs, ambiguousNs;
        final Map<String, Long> moduleNs = new HashMap<>();
        final Map<String, Map<String, Long>> moduleProcNs = new HashMap<>();   // 模块 → 进程名 → ns
        final Map<String, Map<String, Long>> moduleMethodNs = new HashMap<>(); // 模块 → 方法 → ns
        /** 混淆类 → 与它同栈出现过的强证据来源 → ns（用于第二遍消歧）。 */
        final Map<String, Map<String, Long>> learned = new HashMap<>();
        /** 第一遍无法区分的样本，解析结束后再尝试消歧。 */
        final List<Pending> pending = new ArrayList<>();
    }

    private static final class Pending {
        Set<String> cand;
        List<String> weak;
        String proc, hit;
        long ns;
    }

    static boolean available() {
        return new File(SIMPLEPERF).exists();
    }

    /**
     * 采样一个窗口。
     * @param targets pid → 进程名
     */
    static Window sample(Map<Integer, String> targets, int seconds, int freq) throws Exception {
        if (!available()) throw new Exception("系统没有自带 simpleperf（" + SIMPLEPERF + "）");
        StringBuilder pids = new StringBuilder();
        for (int pid : targets.keySet()) pids.append(pids.length() > 0 ? "," : "").append(pid);
        new File(DATA).delete();
        new File(TEXT).delete();
        long w0 = System.nanoTime();
        Object[] r = Sys.exec(SIMPLEPERF, "record", "-p", pids.toString(), "-e", "cpu-clock",
                "-f", String.valueOf(freq), "--call-graph", "dwarf,16384",
                "--duration", String.valueOf(seconds), "-o", DATA);
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            // 旧版本可能不支持自定义栈大小，退回默认 DWARF 调用栈
            r = Sys.exec(SIMPLEPERF, "record", "-p", pids.toString(), "-e", "cpu-clock",
                    "-f", String.valueOf(freq), "-g", "--duration", String.valueOf(seconds), "-o", DATA);
        }
        long w1 = System.nanoTime();
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            throw new Exception("simpleperf 采样失败：" + tail(String.valueOf(r[1])));
        }
        // 线程 → 所属进程（report-sample 只给线程号）
        Map<Integer, String> tidToProc = new HashMap<>();
        for (Map.Entry<Integer, String> t : targets.entrySet()) {
            for (String tid : Sys.list("/proc/" + t.getKey() + "/task")) {
                try {
                    tidToProc.put(Integer.parseInt(tid), t.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
            tidToProc.put(t.getKey(), t.getValue());
        }
        try {
            Object[] rep = Sys.execToFile(new File(TEXT), SIMPLEPERF, "report-sample", "-i", DATA, "--show-callchain");
            if ((int) rep[0] != 0 || !new File(TEXT).exists()) {
                throw new Exception("simpleperf 解析失败：" + tail(String.valueOf(rep[1])));
            }
            Map<String, Set<String>> classToModules = classMap();
            Window w = new Window();
            w.wallNs = w1 - w0;
            parse(new File(TEXT), w, classToModules, tidToProc);
            return w;
        } finally {
            new File(DATA).delete();
            new File(TEXT).delete();
        }
    }

    /** 所有注入来源的「类名 → 来源键」表。 */
    private static Map<String, Set<String>> classMap() {
        Map<String, Set<String>> map = new HashMap<>();
        for (LspModules.Module m : LspModules.get().modules) {
            addClasses(map, moduleClasses(resolveApk(m.pkg, m.apkPath)), m.pkg);
        }
        // KernelSU 模块自带的 Java 代码（Zygisk 注入，如 HMA-OSS；LSPosed 框架本身）
        for (String id : Sys.list(KsuModules.MODULES_DIR)) {
            String key = id.toLowerCase().contains("lsposed") ? FRAMEWORK : "ksu:" + id;
            for (String f : payloads(KsuModules.MODULES_DIR + "/" + id)) addClasses(map, moduleClasses(f), key);
        }
        return map;
    }

    private static void addClasses(Map<String, Set<String>> map, Set<String> classes, String key) {
        for (String cls : classes) {
            Set<String> s = map.get(cls);
            if (s == null) map.put(cls, s = new HashSet<>(2));
            s.add(key);
        }
    }

    /** 模块目录（两层以内，不含覆盖进系统分区的 system/ 目录）里的 .dex / .jar / .apk。 */
    private static List<String> payloads(String dir) {
        List<String> out = new ArrayList<>();
        for (String rel : Sys.walk(new File(dir), 200, 1)) {
            if (rel.startsWith("/system/") || rel.startsWith("/vendor/") || rel.startsWith("/product/")) continue;
            if (rel.endsWith(".dex") || rel.endsWith(".jar") || rel.endsWith(".apk")) out.add(dir + rel);
        }
        return out;
    }

    /** LSPosed 数据库里的 APK 路径在模块更新后可能失效，此时按包名在 /data/app 下重新查找。 */
    static String resolveApk(String pkg, String dbPath) {
        if (dbPath != null && new File(dbPath).exists()) return dbPath;
        for (String a : Sys.list("/data/app")) {
            String base = "/data/app/" + a;
            if (a.startsWith(pkg + "-")) {
                if (new File(base + "/base.apk").exists()) return base + "/base.apk";
                continue;
            }
            for (String b : Sys.list(base)) {
                if (b.startsWith(pkg + "-") && new File(base + "/" + b + "/base.apk").exists()) {
                    return base + "/" + b + "/base.apk";
                }
            }
        }
        return dbPath;
    }

    private static void parse(File text, Window w, Map<String, Set<String>> classToModules,
                              Map<Integer, String> tidToProc) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(text), 1 << 16)) {
            List<String> syms = new ArrayList<>(), files = new ArrayList<>();
            int tid = -1;
            long count = 0;
            boolean in = false;
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.equals("sample:")) {
                    if (in) add(w, syms, files, tidToProc.get(tid), count, classToModules);
                    syms.clear();
                    files.clear();
                    tid = -1;
                    count = 0;
                    in = true;
                } else if (t.startsWith("event_count:")) {
                    count = parseLong(t.substring(12));
                } else if (t.startsWith("thread_id:")) {
                    tid = (int) parseLong(t.substring(10));
                } else if (t.startsWith("file:")) {
                    files.add(t.substring(5).trim());
                } else if (t.startsWith("symbol:")) {
                    syms.add(t.substring(7).trim());
                }
            }
            if (in) add(w, syms, files, tidToProc.get(tid), count, classToModules);
        }
        resolvePending(w);
    }

    /** 第二遍：用强证据样本里学到的「混淆类 → 来源」关联，给无法区分的样本消歧。 */
    private static void resolvePending(Window w) {
        for (Pending p : w.pending) {
            String best = null;
            long bestScore = 0;
            boolean tie = false;
            for (String m : p.cand) {
                long score = 0;
                for (String cls : p.weak) {
                    Map<String, Long> lm = w.learned.get(cls);
                    Long v = lm == null ? null : lm.get(m);
                    if (v != null) score += v;
                }
                if (score > bestScore) {
                    best = m;
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore && score > 0) {
                    tie = true;
                }
            }
            if (best != null && !tie) {
                credit(w, best, p.proc, p.hit, p.ns);
            } else {
                w.ambiguousNs += p.ns;
            }
        }
        w.pending.clear();
    }

    private static void credit(Window w, String m, String proc, String hit, long ns) {
        if (FRAMEWORK.equals(m)) {
            w.bridgeNs += ns;
            return;
        }
        inc(w.moduleNs, m, ns);
        inc(sub(w.moduleProcNs, m), proc, ns);
        inc(sub(w.moduleMethodNs, m), hit, ns);
    }

    private static void add(Window w, List<String> syms, List<String> files, String proc, long ns,
                            Map<String, Set<String>> classToModules) {
        if (ns <= 0) ns = 1;
        if (proc == null) proc = "?";
        w.samples++;
        w.totalNs += ns;
        Set<String> cand = null;
        String hit = null, strong = null;
        List<String> weak = new ArrayList<>();
        boolean java = false, bridgeNearLeaf = false;
        for (int i = 0; i < syms.size(); i++) {
            String sym = syms.get(i);
            String file = i < files.size() ? files.get(i) : "";
            String cls = classOf(sym);
            if (cls != null) java = true;
            if (cand == null && strong == null && i < 3 && isBridge(sym)) bridgeNearLeaf = true;
            if (cls == null || isSystemFile(file)) continue;
            Set<String> mods = classToModules.get(cls);
            if (mods == null) continue;
            if (hit == null) hit = methodOf(sym); // 离栈顶最近的注入代码方法
            if (mods.size() == 1 && !obfuscated(cls)) {
                // 强证据：未混淆且唯一，采用离栈顶最近的一个（继续扫描以收集弱证据用于学习）
                if (strong == null) strong = mods.iterator().next();
                continue;
            }
            weak.add(cls);
            if (cand == null) {
                cand = new HashSet<>(mods);
            } else {
                Set<String> inter = new HashSet<>(cand);
                inter.retainAll(mods);
                if (!inter.isEmpty()) cand = inter;
            }
        }
        if (java) w.javaSamples++;
        if (strong != null) {
            // 学习：这些混淆类与该来源同栈出现
            for (String cls : weak) inc(sub(w.learned, cls), strong, ns);
            credit(w, strong, proc, hit, ns);
        } else if (cand != null && cand.size() == 1) {
            credit(w, cand.iterator().next(), proc, hit, ns);
        } else if (cand != null) {
            Pending p = new Pending();
            p.cand = cand;
            p.weak = weak;
            p.proc = proc;
            p.hit = hit;
            p.ns = ns;
            w.pending.add(p);
        } else if (bridgeNearLeaf) {
            w.bridgeNs += ns;
        }
    }

    private static Map<String, Long> sub(Map<String, Map<String, Long>> m, String k) {
        Map<String, Long> s = m.get(k);
        if (s == null) m.put(k, s = new HashMap<>());
        return s;
    }

    /** 混淆后的类名：顶层类名与各级包名都不超过 3 个字符，如 tk.a、mb、fo$Transformer。 */
    static boolean obfuscated(String cls) {
        String top = cls;
        int d = top.indexOf('$');
        if (d > 0) top = top.substring(0, d);
        for (String seg : top.split("\\.")) if (seg.length() > 3) return false;
        return true;
    }

    private static boolean isBridge(String sym) {
        for (String b : BRIDGE_MARKS) if (sym.contains(b)) return true;
        return false;
    }

    private static boolean isSystemFile(String f) {
        if (f.contains("kallsyms") || f.endsWith(".so")) return true;
        for (String p : SYSTEM_PREFIXES) if (f.startsWith(p)) return true;
        return false;
    }

    /** "void com.foo.Bar.baz(int)" / "com.foo.Bar.baz" → "com.foo.Bar.baz"；不像 Java 方法时返回 null。 */
    static String methodOf(String sym) {
        int p = sym.indexOf('(');
        String s = p >= 0 ? sym.substring(0, p) : sym;
        int sp = s.lastIndexOf(' ');
        if (sp >= 0) s = s.substring(sp + 1);
        return s.indexOf('.') > 0 ? s : null;
    }

    static String classOf(String sym) {
        String m = methodOf(sym);
        if (m == null) return null;
        int dot = m.lastIndexOf('.');
        return dot > 0 ? m.substring(0, dot) : null;
    }

    // ------------------------------------------------------------------ dex 类名

    /** 读取 APK 内所有 classesN.dex 的类名（Java 形式，如 com.foo.Bar$1）。 */
    static Set<String> moduleClasses(String apk) {
        if (apk == null || apk.isEmpty()) return Collections.emptySet();
        File f = new File(apk);
        String key = apk + "@" + f.lastModified();
        synchronized (DEX_CACHE) {
            Set<String> cached = DEX_CACHE.get(key);
            if (cached != null) return cached;
        }
        Set<String> out = new HashSet<>();
        if (apk.endsWith(".dex")) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                parseDex(readAll(in), out);
            } catch (Throwable ignored) {
            }
            synchronized (DEX_CACHE) {
                DEX_CACHE.put(key, out);
            }
            return out;
        }
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes\\d*\\.dex")) continue;
                try (InputStream in = z.getInputStream(e)) {
                    parseDex(readAll(in), out);
                }
            }
        } catch (Throwable ignored) {
        }
        synchronized (DEX_CACHE) {
            DEX_CACHE.put(key, out);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    /** 只解析 header → class_defs → type_ids → string_ids，取出每个类的描述符。 */
    private static void parseDex(byte[] d, Set<String> out) {
        if (d.length < 0x70 || d[0] != 'd' || d[1] != 'e' || d[2] != 'x') return;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsOff = b.getInt(0x3C);
        int typeIdsOff = b.getInt(0x44);
        int classDefsSize = b.getInt(0x60), classDefsOff = b.getInt(0x64);
        for (int i = 0; i < classDefsSize; i++) {
            try {
                int typeIdx = b.getInt(classDefsOff + i * 32);
                int strIdx = b.getInt(typeIdsOff + typeIdx * 4);
                int off = b.getInt(stringIdsOff + strIdx * 4);
                while ((d[off] & 0x80) != 0) off++; // 跳过 uleb128（UTF-16 长度）
                off++;
                int end = off;
                while (end < d.length && d[end] != 0) end++;
                String desc = new String(d, off, end - off, StandardCharsets.UTF_8);
                if (desc.length() > 2 && desc.charAt(0) == 'L' && desc.endsWith(";")) {
                    out.add(desc.substring(1, desc.length() - 1).replace('/', '.'));
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void inc(Map<String, Long> m, String k, long v) {
        Long old = m.get(k);
        m.put(k, old == null ? v : old + v);
    }

    static String tail(String s) {
        s = s.trim();
        return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
    }
}
