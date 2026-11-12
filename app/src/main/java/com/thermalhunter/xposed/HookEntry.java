package com.thermalhunter.xposed;

import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 * 作用域：
 *   - android (system_server)：统计每个应用的 PARTIAL 唤醒锁持有时长与唤醒型闹钟次数，
 *     周期性写入 /data/system/thermalhunter/stats.json，由 root 端读取。
 *   - com.thermalhunter：让 App 知道模块已被 LSPosed 激活。
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
