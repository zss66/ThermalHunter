package com.thermalhunter.core;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * 全局采样器：主界面和悬浮窗共用同一个后台会话（root 或 Shizuku）和同一个 Analyzer。
 * 只有存在订阅者（界面可见 / 悬浮窗开启且亮屏）时才采样；没有订阅者 60 秒后关闭后台进程，
 * 因此本工具自身不会在后台持续耗电。
 */
public final class Monitor {

    public interface Listener {
        /** 新的分析结果（主线程）。 */
        void onUpdate(Analyzer.Result r);

        /** 连接 / 采集状态（主线程）。error=false 表示进行中的提示。 */
        void onStatus(String msg, boolean error);
    }

    public interface Done {
        void onDone(List<String> msgs, boolean allOk);
    }

    // 工作模式：auto = 先试 root，失败再用 Shizuku；root = 仅 root；shizuku = 仅 Shizuku（ADB）
    public static final String[] MODES = {"auto", "root", "shizuku"};

    private static final long INTERVAL_MS = 3000, RETRY_MS = 5000, IDLE_CLOSE_MS = 60_000;
    private static Monitor instance;

    public static synchronized Monitor get(Context ctx) {
        if (instance == null) instance = new Monitor(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final RootSession root = new RootSession();
    private final Analyzer analyzer = new Analyzer();
    private final Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile Analyzer.Result last;
    private volatile boolean rootFailed;   // auto 模式下 root 已失败，不再反复申请
    private volatile boolean shizukuAsked; // 已弹过 Shizuku 授权
    private final Runnable tickR = this::tick;
    private final Runnable idleClose = () -> {
        if (listeners.isEmpty()) root.close();
    };

    private Monitor(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        HandlerThread t = new HandlerThread("monitor");
        t.start();
        bg = new Handler(t.getLooper());
        // Shizuku 就绪或授权结果返回时立即重连，不等重试间隔
        Shizuku.addBinderReceivedListenerSticky(this::kick);
        Shizuku.addRequestPermissionResultListener((code, res) -> {
            if (code == ShizukuShell.REQUEST_CODE) kick();
        });
    }

    /** 主线程调用。第一个订阅者加入时开始采样。 */
    public void addListener(Listener l) {
        if (listeners.contains(l)) return;
        boolean first = listeners.isEmpty();
        listeners.add(l);
        Analyzer.Result r = last;
        if (r != null) l.onUpdate(r);
        if (first) {
            shizukuAsked = false;
            bg.removeCallbacks(idleClose);
            kick();
        }
    }

    /** 主线程调用。最后一个订阅者离开时停止采样，闲置一段时间后关闭后台进程。 */
    public void removeListener(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {
            bg.removeCallbacks(tickR);
            bg.removeCallbacks(idleClose);
            bg.postDelayed(idleClose, IDLE_CLOSE_MS);
        }
    }

    public Analyzer.Result last() {
        return last;
    }

    /** 立即采样一次。 */
    public void kick() {
        if (listeners.isEmpty()) return;
        bg.removeCallbacks(tickR);
        bg.post(tickR);
    }

    /** 切换工作模式后调用：关闭当前会话，按新模式重连。 */
    public void reconnect() {
        bg.post(() -> {
            rootFailed = false;
            shizukuAsked = false;
            root.close();
        });
        kick();
    }

    /** 依次执行命令，结果回到主线程。 */
    public void runAll(List<String> cmds, Done done) {
        bg.post(() -> {
            List<String> msgs = new ArrayList<>();
            boolean allOk = true;
            for (String cmd : cmds) {
                JSONObject r = root.safeCall(cmd);
                boolean ok = r.optBoolean("ok");
                allOk &= ok;
                msgs.add(ok ? "✓ " + r.optString("msg") : "✗ " + r.optString("error"));
            }
            final boolean fOk = allOk;
            ui.post(() -> done.onDone(msgs, fOk));
            kick();
        });
    }

    private void status(String msg, boolean error) {
        ui.post(() -> {
            for (Listener l : listeners) l.onStatus(msg, error);
        });
    }

    private void tick() {
        if (listeners.isEmpty()) return;
        if (!root.isAlive()) {
            String err = connect();
            if (err != null) {
                status(err, true);
                bg.postDelayed(tickR, RETRY_MS);
                return;
            }
        }
        JSONObject snap = root.safeCall("SNAPSHOT");
        if (snap.optBoolean("ok")) {
            fillBattery(snap);
            Analyzer.Result r = analyzer.update(snap);
            last = r;
            ui.post(() -> {
                for (Listener l : listeners) l.onUpdate(r);
            });
        } else {
            status("采集失败：" + snap.optString("error"), true);
        }
        if (!listeners.isEmpty()) bg.postDelayed(tickR, INTERVAL_MS);
    }

    /** 按工作模式建立后台会话；成功返回 null，失败返回给用户看的说明。在 bg 线程执行。 */
    private String connect() {
        String mode = prefs.getString("work_mode", "auto");
        String rootErr = null;
        if (mode.equals("root") || (mode.equals("auto") && !rootFailed)) {
            status("正在获取 root 权限…", false);
            if (root.start(app, false)) return null;
            rootErr = root.lastError();
            if (mode.equals("root")) {
                return "未获得 root：" + rootErr + "\n请在 KernelSU 管理器 → 超级用户 中允许「热源猎手」，然后重新打开。";
            }
            rootFailed = true;
        } else if (mode.equals("auto")) {
            rootErr = "未授予";
        }
        String why = ShizukuShell.unavailableReason();
        if (why == null && !ShizukuShell.granted()) {
            if (ShizukuShell.permanentlyDenied()) {
                why = "Shizuku 授权已被拒绝：请在 Shizuku → 授权应用管理 中允许「热源猎手」";
            } else {
                if (!shizukuAsked) {
                    shizukuAsked = true;
                    ui.post(ShizukuShell::request);
                }
                why = "请在弹出的 Shizuku 对话框中允许「热源猎手」";
            }
        }
        if (why == null) {
            status("正在通过 Shizuku 启动…", false);
            if (root.start(app, true)) return null;
            why = "Shizuku 启动采集进程失败：" + root.lastError();
        }
        if (rootErr != null) return "未获得 root：" + rootErr + "\n" + why;
        return why;
    }

    /**
     * shell 身份（Shizuku 模式）读不到 /sys/class/power_supply，
     * 用应用自身的 BatteryManager 补齐电量、电流、电压、温度和充电状态。
     */
    private void fillBattery(JSONObject snap) {
        JSONObject b = snap.optJSONObject("battery");
        if (b != null && b.optLong("level", -1) >= 0 && !b.optString("status").isEmpty()) return;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = Build.VERSION.SDK_INT >= 33
                    ? app.registerReceiver(null, f, Context.RECEIVER_EXPORTED)
                    : app.registerReceiver(null, f);
            BatteryManager bm = app.getSystemService(BatteryManager.class);
            JSONObject o = new JSONObject();
            int level = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i == null ? 100 : i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            o.put("level", level >= 0 ? level * 100 / Math.max(1, scale)
                    : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            int st = i == null ? -1 : i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            o.put("status", st == BatteryManager.BATTERY_STATUS_CHARGING ? "Charging"
                    : st == BatteryManager.BATTERY_STATUS_DISCHARGING ? "Discharging"
                    : st == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "Not charging"
                    : st == BatteryManager.BATTERY_STATUS_FULL ? "Full" : "");
            int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW); // 一般为 µA
            double ma = cur == Integer.MIN_VALUE ? 0 : Math.abs(cur) > 20000 ? cur / 1000.0 : cur;
            int mv = i == null ? 0 : i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            o.put("currentMa", Math.round(ma));
            o.put("voltageMv", mv);
            o.put("powerMw", Math.round(Math.abs(ma) * mv / 1000.0));
            int t = i == null ? Integer.MIN_VALUE : i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (t != Integer.MIN_VALUE) o.put("tempC", t / 10.0);
            snap.put("battery", o);
        } catch (Throwable ignored) {
        }
    }
}
