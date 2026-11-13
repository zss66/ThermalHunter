package com.thermalhunter.xposed;

import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 * 作用域：
 *   - android (system_server)：统计每个应用的 PARTIAL 唤醒锁持有时长与唤醒型闹钟次数，
 *     周期性写入 /data/system/thermalhunter/stats.json，由 root 端读取。
 *   - com.thermalhunter：让 App 知道模块已被 LSPosed 激活。
 *   - 用户在设置里选择的应用：对其隐藏 VPN 状态（读本模块的世界可读配置决定是否启用）。
 *
 * 原则：任何异常都吞掉，绝不能让 system_server 崩溃。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "ThermalHunter: ";
    static final String STATS_DIR = "/data/system/thermalhunter";
    static final String STATS_FILE = STATS_DIR + "/stats.json";
    private static final int PARTIAL_WAKE_LOCK = 0x00000001;
    private static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.thermalhunter".equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod("com.thermalhunter.core.ModuleStatus", lpparam.classLoader,
                        "isActive", XC_MethodReplacement.returnConstant(true));
            } catch (Throwable t) {
                XposedBridge.log(TAG + "self hook failed: " + t);
            }
            return;
        }
        // LSPosed 只会在 system_server 中以 "android" 回调
        if ("android".equals(lpparam.packageName)) {
            Stats.start();
            hookWakeLocks(lpparam.classLoader);
            hookAlarms(lpparam.classLoader);
            hookConnectivityVpn(lpparam.classLoader);
            return;
        }
        // system_server 里还托管着 providers.settings / telecom / location.fused 等包，LSPosed 会用它们的包名
        // 再回调一次。绝不能在系统 uid 进程里装应用级隐身 hook：那会让 ConnectivityService 把 VPN 网络当成
        // 普通网络，5 秒后当作「无人需要」拆掉，所有 VPN/代理软件都会失效。
        if (android.os.Process.myUid() % 100000 < 10000) return;
        // 普通应用：若用户为它开启了「VPN 隐身」，则对其隐藏 VPN 相关信号
        if (vpnHideEnabledFor(lpparam.packageName)) {
            hookVpnHide();
        }
    }

    // ---------------------------------------------------------------- VPN 隐身

    /** 银行 / 金融类应用的代理检测属于风控，不对其生效。 */
    private static final Set<String> VPN_HIDE_DENY = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.citiccard.mobilebank", "com.pingan.smt"));

    /** 读取本模块的配置（LSPosed 远程偏好），判断某应用是否启用了 VPN 隐身。读不到即视为未启用。 */
    private static boolean vpnHideEnabledFor(String pkg) {
        if (VPN_HIDE_DENY.contains(pkg)) return false;
        try {
            XSharedPreferences prefs = new XSharedPreferences("com.thermalhunter", "hide");
            prefs.reload();
            Set<String> set = prefs.getStringSet("vpn_pkgs", null);
            return set != null && set.contains(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int TRANSPORT_VPN = 4;
    private static final int NET_CAPABILITY_NOT_VPN = 15;

    // ---------------------------------------------------------------- system_server 级 VPN 隐身

    /** 目标应用的 appId 集合（名单里包名解析出的 uid），周期性刷新。 */
    private static volatile java.util.Set<Integer> sVpnHideAppIds = java.util.Collections.emptySet();
    private static volatile long sVpnHideAt;

    /**
     * 在 system_server 里 hook 「按调用方脱敏 NetworkCapabilities」的总闸：
     * 对隐身名单里的应用，把交给它的 NetworkCapabilities 克隆一份、抹掉 VPN 标志。
     * 拉取和回调两条路都经过此方法，native 调用方拿到的也是改过的 parcel。
     * 任何异常都吞掉，绝不能让 system_server 崩。
     */
    private static void hookConnectivityVpn(ClassLoader cl) {
        Class<?> cs = XposedHelpers.findClassIfExists(
                "android.net.connectivity.com.android.server.ConnectivityService", cl);
        if (cs == null) cs = XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl);
        if (cs == null) {
            XposedBridge.log(TAG + "ConnectivityService class not found; VPN source-hide disabled");
            return;
        }
        try {
            XposedBridge.hookAllMethods(cs, "createWithLocationInfoSanitizedIfNecessaryWhenParceled",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object nc = param.getResult();
                                if (nc == null || param.args.length < 4) return;
                                Object uidArg = param.args[3];
                                if (!(uidArg instanceof Integer)) return;
                                int appId = ((Integer) uidArg) % 100000; // UserHandle.getAppId
                                if (!vpnHideAppIds().contains(appId)) return;
                                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(nc, "hasTransport", TRANSPORT_VPN))) {
                                    return;
                                }
                                // 克隆后改造，绝不动原对象
                                Object copy = nc.getClass().getConstructor(nc.getClass()).newInstance(nc);
                                XposedHelpers.callMethod(copy, "removeTransportType", TRANSPORT_VPN);
                                try {
                                    XposedHelpers.callMethod(copy, "addCapability", NET_CAPABILITY_NOT_VPN);
                                } catch (Throwable ignored) {
                                }
                                param.setResult(copy);
                            } catch (Throwable t) {
                                // 吞掉：绝不影响 system_server
                            }
                        }
                    });
            XposedBridge.log(TAG + "ConnectivityService VPN source-hide installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "CS hook failed: " + t);
        }
    }

    /** 名单包名 → appId 集合；每 30 秒刷新一次。读本模块配置 + /data/system/packages.list。 */
    private static java.util.Set<Integer> vpnHideAppIds() {
        long now = SystemClock.elapsedRealtime();
        if (now - sVpnHideAt < 30_000) return sVpnHideAppIds;
        sVpnHideAt = now;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try {
            XSharedPreferences prefs = new XSharedPreferences("com.thermalhunter", "hide");
            prefs.reload();
            Set<String> pkgs = prefs.getStringSet("vpn_pkgs", null);
            if (pkgs != null && !pkgs.isEmpty()) {
                try (BufferedReader br = new BufferedReader(new FileReader("/data/system/packages.list"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        int sp = line.indexOf(' ');
                        if (sp <= 0) continue;
                        String pkg = line.substring(0, sp);
                        if (!pkgs.contains(pkg) || VPN_HIDE_DENY.contains(pkg)) continue;
                        int sp2 = line.indexOf(' ', sp + 1);
                        if (sp2 <= sp) continue;
                        try {
                            ids.add(Integer.parseInt(line.substring(sp + 1, sp2)) % 100000);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        sVpnHideAppIds = ids;
        return ids;
    }

    /**
     * 在应用进程内，让常见的 VPN / 代理检测 API 报告「没有 VPN」：
     *   1. NetworkCapabilities.hasTransport(TRANSPORT_VPN) -> false
     *   2. NetworkCapabilities.hasCapability(NET_CAPABILITY_NOT_VPN) -> true
     *   3. NetworkInterface.getNetworkInterfaces() 过滤掉 tun/ppp/tap 接口
     * 全部容错，任何失败都不影响应用运行。网络类由 boot ClassLoader 加载，故传 null。
     */
    private static void hookVpnHide() {
        try {
            XposedHelpers.findAndHookMethod("android.net.NetworkCapabilities", null, "hasTransport",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ((int) param.args[0] == TRANSPORT_VPN) param.setResult(false);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hasTransport hook failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.net.NetworkCapabilities", null, "hasCapability",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if ((int) param.args[0] == NET_CAPABILITY_NOT_VPN) param.setResult(true);
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod("java.net.NetworkInterface", null, "getNetworkInterfaces",
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object res = param.getResult();
                            if (!(res instanceof Enumeration)) return;
                            ArrayList<Object> kept = new ArrayList<>();
                            Enumeration<Object> en = (Enumeration<Object>) res;
                            while (en.hasMoreElements()) {
                                Object ni = en.nextElement();
                                String name = "";
                                try {
                                    name = String.valueOf(ni.getClass().getMethod("getName").invoke(ni));
                                } catch (Throwable ignored) {
                                }
                                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap")) continue;
                                kept.add(ni);
                            }
                            param.setResult(Collections.enumeration(kept));
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            // 直接按名查接口（如 getByName("tun0")）：命中 VPN 接口名则返回 null
            XposedHelpers.findAndHookMethod("java.net.NetworkInterface", null, "getByName",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String n = (String) param.args[0];
                            if (n != null && (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("tap"))) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- wakelock

    private static void hookWakeLocks(ClassLoader cl) {
        try {
            Class<?> pms = XposedHelpers.findClass("com.android.server.power.PowerManagerService", cl);
            // Android 15: acquireWakeLockInternal(IBinder lock, int displayId, int flags, String tag,
            //     String packageName, WorkSource ws, String historyTag, int uid, int pid, IWakeLockCallback cb)
            // 按类型解析参数，兼容厂商/版本改动。
            XposedBridge.hookAllMethods(pms, "acquireWakeLockInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.getThrowable() != null) return;
                        IBinder lock = null;
                        String tag = null, pkg = null;
                        int flags = -1;
                        for (Object a : param.args) {
                            if (a instanceof IBinder && lock == null) lock = (IBinder) a;
                            else if (a instanceof String) {
                                if (tag == null) tag = (String) a;
                                else if (pkg == null) pkg = (String) a;
                            } else if (a instanceof Integer && lock != null && tag == null) {
                                // displayId、flags 都在 tag 之前，flags 是 tag 前的最后一个 int
                                flags = (Integer) a;
                            }
                        }
                        if (lock == null || pkg == null) return;
                        if (flags != -1 && (flags & WAKE_LOCK_LEVEL_MASK) != PARTIAL_WAKE_LOCK) return;
                        Stats.onAcquire(lock, pkg, tag);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(pms, "releaseWakeLockInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Object a : param.args) {
                            if (a instanceof IBinder) {
                                Stats.onRelease((IBinder) a);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            // 进程死亡导致的释放
            XposedBridge.hookAllMethods(pms, "handleWakeLockDeath", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object wl = param.args[0];
                        Object lock = XposedHelpers.getObjectField(wl, "mLock");
                        if (lock instanceof IBinder) Stats.onRelease((IBinder) lock);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "wakelock hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "wakelock hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- alarm

    private static void hookAlarms(ClassLoader cl) {
        try {
            Class<?> ams = XposedHelpers.findClassIfExists("com.android.server.alarm.AlarmManagerService", cl);
            if (ams == null) ams = XposedHelpers.findClass("com.android.server.AlarmManagerService", cl);
            XposedBridge.hookAllMethods(ams, "deliverAlarmsLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object list = param.args[0];
                        if (!(list instanceof java.util.List)) return;
                        for (Object alarm : (java.util.List<?>) list) {
                            boolean wakeup;
                            try {
                                wakeup = XposedHelpers.getBooleanField(alarm, "wakeup");
                            } catch (Throwable e) {
                                wakeup = true;
                            }
                            if (!wakeup) continue;
                            String pkg = null;
                            try {
                                pkg = (String) XposedHelpers.getObjectField(alarm, "sourcePackage");
                            } catch (Throwable ignored) {
                            }
                            if (pkg == null) {
                                try {
                                    pkg = (String) XposedHelpers.getObjectField(alarm, "packageName");
                                } catch (Throwable ignored) {
                                }
                            }
                            if (pkg != null) Stats.onAlarm(pkg);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + "alarm hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "alarm hook failed: " + t);
        }
    }

    // ---------------------------------------------------------------- stats store

    static final class Stats {
        static final class Held {
            final String pkg;
            final String tag;
            final long since;
            Held(String pkg, String tag, long since) { this.pkg = pkg; this.tag = tag; this.since = since; }
        }

        static final class PkgStat {
            long wlCount;
            long wlHeldMs;   // 已释放部分的累计时长
            long alarmWakeups;
            String lastTag;
        }

        private static final Object LOCK = new Object();
        private static final Map<IBinder, Held> HELD = new HashMap<>();
        private static final Map<String, PkgStat> PKGS = new HashMap<>();
        private static long startElapsed;
        private static boolean started;

        static void start() {
            // 同一进程可能被回调多次，只启动一次
            synchronized (LOCK) {
                if (started) return;
                started = true;
                startElapsed = SystemClock.elapsedRealtime();
            }
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(15_000);
                        flush();
                    } catch (Throwable ignored) {
                    }
                }
            }, "ThermalHunterStats");
            t.setDaemon(true);
            t.start();
        }

        private static PkgStat get(String pkg) {
            PkgStat s = PKGS.get(pkg);
            if (s == null) {
                s = new PkgStat();
                PKGS.put(pkg, s);
            }
            return s;
        }

        static void onAcquire(IBinder lock, String pkg, String tag) {
            synchronized (LOCK) {
                if (HELD.containsKey(lock)) return; // 更新已有锁，不重复计数
                HELD.put(lock, new Held(pkg, tag, SystemClock.elapsedRealtime()));
                PkgStat s = get(pkg);
                s.wlCount++;
                s.lastTag = tag;
            }
        }

        static void onRelease(IBinder lock) {
            synchronized (LOCK) {
                Held h = HELD.remove(lock);
                if (h == null) return;
                get(h.pkg).wlHeldMs += SystemClock.elapsedRealtime() - h.since;
            }
        }

        static void onAlarm(String pkg) {
            synchronized (LOCK) {
                get(pkg).alarmWakeups++;
            }
        }

        static void flush() throws Exception {
            JSONObject root = new JSONObject();
            long now = SystemClock.elapsedRealtime();
            JSONObject pkgs = new JSONObject();
            synchronized (LOCK) {
                // 清理死掉的 binder（防止漏掉释放导致时长无限增长）
                Iterator<Map.Entry<IBinder, Held>> it = HELD.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<IBinder, Held> e = it.next();
                    if (!e.getKey().isBinderAlive()) {
                        get(e.getValue().pkg).wlHeldMs += now - e.getValue().since;
                        it.remove();
                    }
                }
                Map<String, long[]> active = new HashMap<>(); // pkg -> {activeCount, activeMs}
                Map<String, String> activeTag = new HashMap<>();
                for (Held h : HELD.values()) {
                    long[] a = active.get(h.pkg);
                    if (a == null) active.put(h.pkg, a = new long[2]);
                    a[0]++;
                    long dur = now - h.since;
                    if (dur > a[1]) activeTag.put(h.pkg, h.tag);
                    a[1] += dur;
                }
                for (Map.Entry<String, PkgStat> e : PKGS.entrySet()) {
                    PkgStat s = e.getValue();
                    long[] a = active.get(e.getKey());
                    JSONObject o = new JSONObject();
                    o.put("wlCount", s.wlCount);
                    o.put("wlHeldMs", s.wlHeldMs + (a == null ? 0 : a[1]));
                    o.put("wlActive", a == null ? 0 : a[0]);
                    o.put("alarms", s.alarmWakeups);
                    String tag = activeTag.get(e.getKey());
                    o.put("tag", tag != null ? tag : s.lastTag);
                    pkgs.put(e.getKey(), o);
                }
            }
            root.put("elapsed", now);
            root.put("since", startElapsed);
            root.put("pkgs", pkgs);

            File dir = new File(STATS_DIR);
            if (!dir.exists()) dir.mkdirs();
            File tmp = new File(STATS_DIR, "stats.json.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(new File(STATS_FILE));
        }
    }
}
