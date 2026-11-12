package com.thermalhunter.root;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * 深度分析：用系统自带的 simpleperf 对目标进程按 CPU 时间采样，再按调用栈把 CPU 分到具体的 LSPosed 模块。
 *
 * 归属规则：从正在执行的方法往外逐层查看调用栈，第一个属于某模块（按模块 APK 内 dex 的类名判断）的方法
 * 就是该样本的归属；若该类被多个模块共有（如都打包了 kotlin 标准库），继续向外取交集直到能区分。
 * 系统分区里的代码（/system、/apex 等）一律不算模块，避免同名类误判。
 *
 * 局限：hook 导致的间接开销（如被 hook 的方法不能再被内联）无法统计，结果会略微低估模块的影响。
 */
final class Profiler {
    private Profiler() {}

    private static final String SIMPLEPERF = "/system/bin/simpleperf";
    private static final String DATA = "/data/local/tmp/thermalhunter_perf.data";
    private static final String TEXT = "/data/local/tmp/thermalhunter_perf.txt";
    private static final String[] SYSTEM_PREFIXES = {"/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/"};
    private static final String[] BRIDGE_MARKS = {"de.robv.android.xposed.", "io.github.libxposed.", "org.lsposed.",
            "LSPHooker", "lsplant", "LSPlant"};

    /** apk 路径 + 修改时间 → 类名集合，避免每次都重新解析。 */
    private static final Map<String, Set<String>> DEX_CACHE = new HashMap<>();

    static JSONObject run(String target, int seconds) throws Exception {
        JSONObject o = new JSONObject();
        if (!new File(SIMPLEPERF).exists()) return fail("系统没有自带 simpleperf（" + SIMPLEPERF + "），无法进行深度分析");
        int pid = resolvePid(target);
        if (pid <= 0) return fail("找不到进程: " + target);
        String procName = procName(pid);
        seconds = Math.max(5, Math.min(30, seconds));

        // 1. 按 CPU 时间采样（cpu-clock 为软件事件，不依赖硬件计数器）
        new File(DATA).delete();
        new File(TEXT).delete();
        long t0 = procTicks(pid), w0 = SystemClock.elapsedRealtime();
        Object[] r = Sys.exec(SIMPLEPERF, "record", "-p", String.valueOf(pid), "-e", "cpu-clock", "-f", "200",
                "--call-graph", "dwarf,32768", "--duration", String.valueOf(seconds), "-o", DATA);
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            // 旧版本可能不支持自定义栈大小，退回默认 DWARF 调用栈
            r = Sys.exec(SIMPLEPERF, "record", "-p", String.valueOf(pid), "-e", "cpu-clock", "-f", "200",
                    "-g", "--duration", String.valueOf(seconds), "-o", DATA);
        }
        long t1 = procTicks(pid), w1 = SystemClock.elapsedRealtime();
        if ((int) r[0] != 0 || !new File(DATA).exists()) {
            return fail("simpleperf 采样失败：" + tail(String.valueOf(r[1])));
        }

        // 2. 输出每个样本的调用栈
        Object[] rep = Sys.execToFile(new File(TEXT), SIMPLEPERF, "report-sample", "-i", DATA, "--show-callchain");
        if ((int) rep[0] != 0 || !new File(TEXT).exists()) {
            return fail("simpleperf 解析失败：" + tail(String.valueOf(rep[1])));
        }

        // 3. 模块类名表
        Map<String, Set<String>> classToModules = new HashMap<>();
        Map<String, String> moduleNames = new HashMap<>();
        for (LspModules.Module m : LspModules.get().modules) {
            moduleNames.put(m.pkg, m.pkg);
            for (String cls : moduleClasses(m.apkPath)) {
                Set<String> s = classToModules.get(cls);
                if (s == null) classToModules.put(cls, s = new HashSet<>(2));
                s.add(m.pkg);
            }
        }

        // 4. 逐个样本归属
        Agg agg = new Agg();
        try (BufferedReader br = new BufferedReader(new FileReader(TEXT), 1 << 16)) {
            List<String> syms = new ArrayList<>(), files = new ArrayList<>();
            String thread = null;
            long count = 1;
            boolean in = false;
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.equals("sample:")) {
                    if (in) agg.add(syms, files, thread, count, classToModules);
                    syms.clear();
                    files.clear();
                    thread = null;
                    count = 1;
                    in = true;
                } else if (t.startsWith("event_count:")) {
                    try {
                        count = Long.parseLong(t.substring(12).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (t.startsWith("thread_name:")) {
                    thread = t.substring(12).trim();
                } else if (t.startsWith("file:")) {
                    files.add(t.substring(5).trim());
                } else if (t.startsWith("symbol:")) {
                    syms.add(t.substring(7).trim());
                }
            }
            if (in) agg.add(syms, files, thread, count, classToModules);
        } finally {
            new File(DATA).delete();
            new File(TEXT).delete();
        }
        if (agg.samples == 0) return fail("采样期间目标进程几乎没有占用 CPU，没有可分析的数据");

        double wallSec = Math.max(1, w1 - w0) / 1000.0;
        double procCpu = t1 >= t0 && t0 >= 0 ? (t1 - t0) * 10.0 / 1000.0 / wallSec * 100 : -1; // CLK_TCK=100

        o.put("ok", true);
        o.put("pid", pid);
        o.put("process", procName);
        o.put("seconds", seconds);
        o.put("samples", agg.samples);
        o.put("procCpu", Math.round(procCpu * 10) / 10.0);
        o.put("javaFrames", agg.javaSamples * 100.0 / agg.samples);

        JSONArray mods = new JSONArray();
        List<Map.Entry<String, Long>> ml = sorted(agg.module);
        for (Map.Entry<String, Long> e : ml) {
            JSONObject mo = new JSONObject();
            mo.put("pkg", e.getKey());
            mo.put("pct", pct(e.getValue(), agg.total));
            JSONArray top = new JSONArray();
            Map<String, Long> methods = agg.moduleMethods.get(e.getKey());
            if (methods != null) for (Map.Entry<String, Long> me : head(sorted(methods), 5)) {
                top.put(new JSONObject().put("name", me.getKey()).put("pct", pct(me.getValue(), agg.total)));
            }
            mo.put("methods", top);
            mods.put(mo);
        }
        o.put("modules", mods);
        o.put("bridge", pct(agg.bridge, agg.total));
        o.put("ambiguous", pct(agg.ambiguous, agg.total));
        JSONArray amb = new JSONArray();
        for (Map.Entry<String, Long> e : head(sorted(agg.ambiguousSets), 3)) {
            amb.put(new JSONObject().put("modules", e.getKey()).put("pct", pct(e.getValue(), agg.total)));
        }
        o.put("ambiguousDetail", amb);
        o.put("system", pct(agg.system, agg.total));
        JSONArray sys = new JSONArray();
        for (Map.Entry<String, Long> e : head(sorted(agg.systemMethods), 8)) {
            sys.put(new JSONObject().put("name", e.getKey()).put("pct", pct(e.getValue(), agg.total)));
        }
        o.put("systemMethods", sys);
        JSONArray th = new JSONArray();
        for (Map.Entry<String, Long> e : head(sorted(agg.threads), 6)) {
            th.put(new JSONObject().put("name", e.getKey()).put("pct", pct(e.getValue(), agg.total)));
        }
        o.put("threads", th);
        return o;
    }

    /** 样本汇总。所有计数按 cpu-clock 事件量（纳秒）加权。 */
    private static final class Agg {
        long samples, javaSamples, total, bridge, ambiguous, system;
        final Map<String, Long> module = new HashMap<>(), threads = new HashMap<>(),
                systemMethods = new HashMap<>(), ambiguousSets = new HashMap<>();
        final Map<String, Map<String, Long>> moduleMethods = new HashMap<>();

        void add(List<String> syms, List<String> files, String thread, long count,
                 Map<String, Set<String>> classToModules) {
            samples++;
            total += count;
            if (thread != null) inc(threads, thread, count);
            Set<String> cand = null;
            String hit = null, firstJava = null;
            boolean bridgeNearLeaf = false;
            for (int i = 0; i < syms.size(); i++) {
                String sym = syms.get(i);
                String file = i < files.size() ? files.get(i) : "";
                String cls = classOf(sym);
                if (cls != null && firstJava == null) firstJava = methodOf(sym);
                if (cand == null && i < 3 && isBridge(sym)) bridgeNearLeaf = true;
                if (cls == null || isSystemFile(file)) continue;
                Set<String> mods = classToModules.get(cls);
                if (mods == null) continue;
                if (cand == null) {
                    cand = new HashSet<>(mods);
                    hit = methodOf(sym);
                } else {
                    Set<String> inter = new HashSet<>(cand);
                    inter.retainAll(mods);
                    if (!inter.isEmpty()) cand = inter;
                }
                if (cand.size() == 1) break;
            }
            if (firstJava != null) javaSamples++;
            if (cand != null && cand.size() == 1) {
                String m = cand.iterator().next();
                inc(module, m, count);
                Map<String, Long> mm = moduleMethods.get(m);
                if (mm == null) moduleMethods.put(m, mm = new HashMap<>());
                inc(mm, hit, count);
            } else if (cand != null) {
                ambiguous += count;
                List<String> l = new ArrayList<>(cand);
                Collections.sort(l);
                inc(ambiguousSets, String.join(" / ", l), count);
            } else if (bridgeNearLeaf) {
                bridge += count;
            } else {
                system += count;
                inc(systemMethods, firstJava != null ? firstJava : (syms.isEmpty() ? "unknown" : syms.get(0)), count);
            }
        }
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
    private static String methodOf(String sym) {
        int p = sym.indexOf('(');
        String s = p >= 0 ? sym.substring(0, p) : sym;
        int sp = s.lastIndexOf(' ');
        if (sp >= 0) s = s.substring(sp + 1);
        return s.indexOf('.') > 0 ? s : null;
    }

    private static String classOf(String sym) {
        String m = methodOf(sym);
        if (m == null) return null;
        int dot = m.lastIndexOf('.');
        return dot > 0 ? m.substring(0, dot) : null;
    }

    // ------------------------------------------------------------------ dex 类名

    /** 读取 APK 内所有 classesN.dex 的类名（Java 形式，如 com.foo.Bar$1）。 */
    private static Set<String> moduleClasses(String apk) {
        if (apk == null || apk.isEmpty()) return Collections.emptySet();
        File f = new File(apk);
        String key = apk + "@" + f.lastModified();
        synchronized (DEX_CACHE) {
            Set<String> cached = DEX_CACHE.get(key);
            if (cached != null) return cached;
        }
        Set<String> out = new HashSet<>();
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.matches("classes\\d*\\.dex")) continue;
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

    private static int resolvePid(String target) {
        try {
            int pid = Integer.parseInt(target);
            return new File("/proc/" + pid).exists() ? pid : -1;
        } catch (NumberFormatException ignored) {
        }
        for (String n : Sys.list("/proc")) {
            if (n.isEmpty() || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
            String cl = Sys.read("/proc/" + n + "/cmdline");
            if (cl == null) continue;
            String first = cl.split("\0")[0];
            if (first.equals(target)) return Integer.parseInt(n);
        }
        return -1;
    }

    private static String procName(int pid) {
        String cl = Sys.read("/proc/" + pid + "/cmdline");
        return cl == null || cl.isEmpty() ? String.valueOf(pid) : cl.split("\0")[0];
    }

    private static long procTicks(int pid) {
        String s = Sys.read("/proc/" + pid + "/stat");
        if (s == null) return -1;
        try {
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (Throwable e) {
            return -1;
        }
    }

    private static void inc(Map<String, Long> m, String k, long v) {
        Long old = m.get(k);
        m.put(k, old == null ? v : old + v);
    }

    private static List<Map.Entry<String, Long>> sorted(Map<String, Long> m) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        return l;
    }

    private static <T> List<T> head(List<T> l, int n) {
        return l.subList(0, Math.min(n, l.size()));
    }

    private static double pct(long v, long total) {
        return total <= 0 ? 0 : Math.round(v * 1000.0 / total) / 10.0;
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
    }

    private static JSONObject fail(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }
}
