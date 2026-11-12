package com.thermalhunter.root;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描 /data/adb/modules（KernelSU / Magisk / APatch 通用目录结构）。
 * 结果缓存 30 秒；进程归属由 Scanner 根据 exe / cmdline / 覆盖文件 / 父进程链判断。
 */
final class KsuModules {
    static final String MODULES_DIR = "/data/adb/modules";

    static final class Module {
        String id, name = "", version = "", author = "", description = "";
        boolean disabled, remove, update, skipMount;
        boolean hasService, hasPostFs, hasBootCompleted, hasZygisk, hasSystemProp, hasWebroot, hasSepolicy;
        final Set<String> tags = new LinkedHashSet<>();
    }

    private static List<Module> cache = new ArrayList<>();
    /** 模块覆盖进系统分区的文件 (/system/bin/xxx) -> 模块 id */
    private static Map<String, String> overlay = new HashMap<>();
    private static long cacheTime;

    static synchronized List<Module> get() {
        long now = System.currentTimeMillis();
        if (now - cacheTime > 30_000 || cache.isEmpty()) {
            rescan();
            cacheTime = now;
        }
        return cache;
    }

    static synchronized Map<String, String> overlay() {
        get();
        return overlay;
    }

    static synchronized void invalidate() {
        cacheTime = 0;
    }

    static String rootImpl() {
        if (Sys.exists("/data/adb/ksud") || Sys.exists("/data/adb/ksu")) return "KernelSU";
        if (Sys.exists("/data/adb/ap") || Sys.exists("/data/adb/apd")) return "APatch";
        if (Sys.exists("/data/adb/magisk")) return "Magisk";
        return "unknown";
    }

    private static void rescan() {
        List<Module> list = new ArrayList<>();
        Map<String, String> ov = new HashMap<>();
        for (String id : Sys.list(MODULES_DIR)) {
            String base = MODULES_DIR + "/" + id;
            if (!new File(base).isDirectory()) continue;
            Module m = new Module();
            m.id = id;
            String prop = Sys.read(base + "/module.prop");
            if (prop != null) {
                for (String line : prop.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    switch (k) {
                        case "name": m.name = v; break;
                        case "version": m.version = v; break;
                        case "author": m.author = v; break;
                        case "description": m.description = v; break;
                    }
                }
            }
            m.disabled = Sys.exists(base + "/disable");
            m.remove = Sys.exists(base + "/remove");
            m.update = Sys.exists(base + "/update");
            m.skipMount = Sys.exists(base + "/skip_mount");
            m.hasService = Sys.exists(base + "/service.sh");
            m.hasPostFs = Sys.exists(base + "/post-fs-data.sh");
            m.hasBootCompleted = Sys.exists(base + "/boot-completed.sh");
            m.hasZygisk = new File(base + "/zygisk").isDirectory();
            m.hasSystemProp = Sys.exists(base + "/system.prop");
            m.hasWebroot = new File(base + "/webroot").isDirectory();
            m.hasSepolicy = Sys.exists(base + "/sepolicy.rule");

            // 覆盖文件表 + 温控配置替换检测
            for (String part : new String[]{"system", "vendor", "product", "system_ext", "odm"}) {
                File dir = new File(base, part);
                if (!dir.isDirectory()) continue;
                String mountPrefix = part.equals("system") ? "/system" : "/" + part;
                for (String rel : Sys.walk(dir, 3000, 8)) {
                    String target = mountPrefix + rel;
                    // 模块 system/vendor/xx 实际挂载到 /vendor/xx
                    if (target.startsWith("/system/vendor/")) target = target.substring(7);
                    if (target.startsWith("/system/product/")) target = target.substring(7);
                    if (target.startsWith("/system/system_ext/")) target = target.substring(7);
                    if (!m.disabled && !m.skipMount) ov.put(target, id);
                    String low = rel.toLowerCase();
                    if (low.contains("thermal") || low.contains("thermal-engine") || low.contains("mtk_thermal")
                            || low.endsWith(".mtc") || low.contains("/thermal/")) {
                        m.tags.add("替换温控配置");
                    }
                    if (low.contains("powerhal") || low.contains("perfmgr") || low.contains("powerscntbl")
                            || low.contains("perf_config") || low.contains("fpsgo")) {
                        m.tags.add("修改性能/调度配置");
                    }
                }
            }
            // 脚本关键字启发式
            StringBuilder scripts = new StringBuilder();
            for (String s : new String[]{"service.sh", "post-fs-data.sh", "boot-completed.sh", "post-mount.sh", "system.prop"}) {
                String c = Sys.readAll(base + "/" + s, 256 * 1024);
                if (c != null) scripts.append(c).append('\n');
            }
            String sc = scripts.toString();
            if (sc.contains("thermal") || sc.contains("/sys/class/thermal") || sc.contains("thermal_core")) m.tags.add("脚本操作温控");
            if (sc.contains("scaling_min_freq") || sc.contains("scaling_governor") || sc.contains("cpu_boost")
                    || sc.contains("ged_kpi") || sc.contains("/proc/ppm") || sc.contains("cpuset") || sc.contains("uclamp")) {
                m.tags.add("脚本修改调度/频率");
            }
            if ((sc.contains("while true") || sc.contains("while :") || sc.contains("while [ 1 ]") || sc.contains("until false"))
                    && sc.contains("sleep")) {
                m.tags.add("常驻循环脚本");
            }
            if (sc.contains("/sys/power/wake_lock")) m.tags.add("持有内核唤醒锁");
            if (m.hasZygisk) m.tags.add("Zygisk注入所有应用");
            list.add(m);
        }
        cache = list;
        overlay = ov;
    }

    static Module find(String id) {
        for (Module m : get()) if (m.id.equals(id)) return m;
        return null;
    }
}
