package com.thermalhunter.ui;

import com.thermalhunter.core.L;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.SystemClock;

import com.thermalhunter.R;
import com.thermalhunter.core.DrainStats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 后台耗电异常监测。
 *
 * 用不唤醒设备的不精确闹钟（ELAPSED_REALTIME + setInexactRepeating）大约每小时检查一次：
 * 只在手机本来就醒着时才触发，不会为了检查而额外唤醒手机。每次检查通过 su 读取一次
 * batterystats（系统自己持续统计的数据，本工具无需常驻采样），发现严重异常时发通知；
 * 同一个充电周期内同一项异常只提醒一次。
 *
 * 同时负责记录历史：发现 batterystats 已重置（充满后拔下电源开始新周期）时，
 * 把上一次保存的报告归档为一个「放电周期」。
 */
public final class DrainWatcher extends BroadcastReceiver {

    static final String ACTION_CHECK = "com.thermalhunter.action.DRAIN_CHECK";
    static final String PREF_ENABLED = "drain_watch";
    private static final String CHANNEL = "drain_alert";
    private static final int NOTIFY_ID = 2;
    private static final int MAX_HISTORY = 20;
    private static final long HOUR = 3_600_000L;

    // ------------------------------------------------------------------ 调度

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(PREF_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ENABLED, on).apply();
        schedule(c);
    }

    /** 按开关注册 / 取消每小时检查。重复调用安全（同一个 PendingIntent 会被替换）。 */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, DrainWatcher.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (!enabled(c)) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        Context c = ctx.getApplicationContext();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(c);
            return;
        }
        if (!ACTION_CHECK.equals(a) || !enabled(c)) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                check(c);
            } catch (Throwable ignored) {
            } finally {
                pr.finish();
            }
        }, "drain-check").start();
    }

    private static void check(Context c) throws Exception {
        JSONObject rep = readViaSu();
        if (rep == null || !rep.optBoolean("ok")) return;
        L.trJson(rep); // 组件名等是文字令牌（解析代码与 root 端共用）
        DrainStats.analyze(rep, c.getPackageName());
        record(c, rep);
        if (!isCharging(c)) notifyIssues(c, rep);
    }

    /** 后台没有常驻会话，直接用一次性 su 读取；带超时，避免卡住广播。 */
    private static JSONObject readViaSu() throws Exception {
        Process p = new ProcessBuilder("su", "-c", "dumpsys batterystats -c").redirectErrorStream(true).start();
        p.getOutputStream().close();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(40_000);
                p.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        });
        killer.setDaemon(true);
        killer.start();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            return DrainStats.parse(in);
        } finally {
            killer.interrupt();
            p.destroy();
        }
    }

    private static boolean isCharging(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return false;
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    // ------------------------------------------------------------------ 提醒

    private static void notifyIssues(Context c, JSONObject rep) {
        long cycle = rep.optLong("startClock");
        SharedPreferences p = prefs(c);
        Set<String> done = p.getLong("drain_notified_cycle", -1) == cycle
                ? new HashSet<>(p.getStringSet("drain_notified", new HashSet<>())) : new HashSet<>();
        List<String> lines = new ArrayList<>();
        JSONArray issues = rep.optJSONArray("issues");
        for (int i = 0; issues != null && i < issues.length(); i++) {
            JSONObject o = issues.optJSONObject(i);
            if (o.optInt("level") != DrainStats.BAD) continue;
            if (done.add(o.optString("key"))) lines.add(o.optString("msg"));
        }
        JSONArray apps = rep.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject o = apps.optJSONObject(i);
            if (o.optInt("level") != DrainStats.BAD) continue;
            if (!done.add("uid:" + o.optInt("uid"))) continue;
            JSONArray r = o.optJSONArray("reasons");
            lines.add(L.s(R.string.label_colon, label(c, o), r != null && r.length() > 0 ? r.optString(0) : L.s(R.string.drain_bg_abnormal)));
        }
        p.edit().putLong("drain_notified_cycle", cycle).putStringSet("drain_notified", done).apply();
        if (lines.isEmpty()) return;

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, L.s(R.string.drain_channel), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 2,
                new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_DRAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = String.join("\n", lines);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(lines.size() == 1 ? L.s(R.string.drain_found_one) : L.s(R.string.drain_found_n, lines.size()))
                .setContentText(lines.get(0))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFY_ID, n);
        } catch (SecurityException ignored) { // 未授予通知权限
        }
    }

    // ------------------------------------------------------------------ 历史

    /**
     * 保存最新报告；若 batterystats 已开始新周期，把上一份报告归档进历史。
     * 界面每次读取报告后也会调用。线程安全（文件读写串行）。
     */
    static synchronized void record(Context c, JSONObject rep) {
        try {
            JSONObject last = readJson(new File(c.getFilesDir(), "drain_last.json"));
            if (last != null && isNewCycle(last, rep) && last.optLong("onBatteryMs") >= HOUR) {
                JSONArray hist = history(c);
                long start = last.optLong("startClock");
                boolean dup = false;
                for (int i = 0; i < hist.length(); i++) {
                    if (Math.abs(hist.optJSONObject(i).optLong("start") - start) < 30 * 60_000L) dup = true;
                }
                if (!dup) {
                    JSONArray out = new JSONArray();
                    out.put(summary(c, last));
                    for (int i = 0; i < hist.length() && out.length() < MAX_HISTORY; i++) out.put(hist.get(i));
                    writeJson(new File(c.getFilesDir(), "drain_history.json"), out.toString());
                }
            }
            writeJson(new File(c.getFilesDir(), "drain_last.json"), rep.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 统计被重置：放电时长倒退，或周期起点明显不同。 */
    private static boolean isNewCycle(JSONObject last, JSONObject rep) {
        return rep.optLong("onBatteryMs") < last.optLong("onBatteryMs") - 60_000L
                || Math.abs(rep.optLong("startClock") - last.optLong("startClock")) > 30 * 60_000L;
    }

    static JSONArray history(Context c) {
        try {
            String s = readText(new File(c.getFilesDir(), "drain_history.json"));
            if (s != null) return new JSONArray(s);
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    /** 最近一次保存的完整报告（界面在连接建立前先展示它）。 */
    static JSONObject lastReport(Context c) {
        return readJson(new File(c.getFilesDir(), "drain_last.json"));
    }

    private static JSONObject summary(Context c, JSONObject r) throws Exception {
        JSONObject s = new JSONObject();
        s.put("start", r.optLong("startClock"));
        s.put("end", r.optLong("t"));
        s.put("onBatteryMs", r.optLong("onBatteryMs"));
        s.put("screenOnMs", r.optLong("screenOnMs"));
        s.put("screenOffMs", r.optLong("screenOffMs"));
        s.put("dischargePct", r.optLong("dischargePct"));
        s.put("standbyRate", r.optDouble("standbyRate", -1));
        s.put("activeRate", r.optDouble("activeRate", -1));
        s.put("awakeRatio", r.optDouble("awakeRatio", -1));
        s.put("badApps", r.optInt("badApps"));
        JSONArray top = new JSONArray();
        JSONArray apps = r.optJSONArray("apps");
        for (int i = 0; apps != null && i < apps.length() && top.length() < 3; i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a.optInt("uid") % 100000 < 10000) continue;
            top.put(new JSONObject().put("name", label(c, a)).put("mah", a.optDouble("mah")));
        }
        s.put("top", top);
        return s;
    }

    // ------------------------------------------------------------------ 名称

    /** uid → 可读名称：普通应用用应用名（共享 uid 加「等」），系统 uid 用固定名称。 */
    static String label(Context c, JSONObject app) {
        int uid = app.optInt("uid");
        int appId = uid % 100000;
        if (appId < 10000) {
            switch (appId) {
                case 0: return L.s(R.string.uid_kernel);
                case 1000: return L.s(R.string.uid_android);
                case 1001: return L.s(R.string.uid_phone);
                case 1002: return L.s(R.string.uid_bt);
                case 1010: return "WLAN";
                case 1013: return L.s(R.string.uid_media);
                case 1021: return L.s(R.string.uid_gps);
                case 1027: return "NFC";
                case 1041: return L.s(R.string.uid_audio);
                case 1047: return L.s(R.string.uid_camera);
                case 1066: return L.s(R.string.uid_stats);
                case 2000: return "Shell";
                default: return L.s(R.string.uid_system_proc, appId);
            }
        }
        JSONArray pk = app.optJSONArray("pkgs");
        if (pk == null || pk.length() == 0) return L.s(R.string.uid_uninstalled, uid);
        String first = pk.optString(0);
        String name = first;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(first, 0);
            name = pm.getApplicationLabel(ai).toString();
        } catch (Throwable ignored) {
        }
        return pk.length() > 1 ? L.s(R.string.name_and_more, name) : name;
    }

    // ------------------------------------------------------------------ 文件

    private static JSONObject readJson(File f) {
        try {
            String s = readText(f);
            return s == null ? null : new JSONObject(s);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String readText(File f) throws Exception {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename failed");
    }
}
