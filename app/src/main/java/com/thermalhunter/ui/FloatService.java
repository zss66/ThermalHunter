package com.thermalhunter.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.thermalhunter.R;
import com.thermalhunter.core.Analyzer;
import com.thermalhunter.core.Analyzer.Entity;
import com.thermalhunter.core.Monitor;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 悬浮窗：在其他应用上方实时显示温度、功耗和发热最高的进程/应用。
 * 以前台服务运行（通知栏常驻，可一键关闭）；熄屏时暂停采样，亮屏恢复。
 */
public class FloatService extends Service implements Monitor.Listener {

    static final String ACTION_STOP = "com.thermalhunter.action.STOP_FLOAT";
    private static final String CHANNEL = "float";
    private static final int NOTIFY_ID = 1;
    private static final int TOP_N = 5;

    private static volatile boolean running;
    /** 主界面用来同步悬浮窗按钮状态（主线程）。 */
    static Runnable stateListener;

    static boolean isRunning() {
        return running;
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, FloatService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, FloatService.class));
    }

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout view, rows;
    private final View[][] rowViews = new View[TOP_N][]; // {row, dot, name, value}
    private TextView emptyRow;
    private TextView temp, power, status;
    private Ui u;
    private SharedPreferences prefs;
    private boolean collapsed, listening;
    private Analyzer.Result last;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) listen(false);
            else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) listen(true);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // startForegroundService 之后必须尽快 startForeground，否则系统会判定崩溃
        goForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        collapsed = prefs.getBoolean("float_collapsed", false);
        u = new Ui(this, true); // 悬浮窗固定深色，叠在任何界面上都清晰
        wm = getSystemService(WindowManager.class);
        buildView();
        wm.addView(view, lp);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(screen, f);
        listen(true);
        running = true;
        notifyState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        listen(false);
        try {
            unregisterReceiver(screen);
        } catch (Throwable ignored) {
        }
        if (view != null && view.isAttachedToWindow()) wm.removeView(view);
        running = false;
        notifyState();
    }

    private void listen(boolean on) {
        if (on == listening) return;
        listening = on;
        if (on) Monitor.get(this).addListener(this);
        else Monitor.get(this).removeListener(this);
    }

    private void notifyState() {
        Runnable r = stateListener;
        if (r != null) r.run();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "悬浮窗", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, FloatService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("热源猎手悬浮窗运行中")
                .setContentText("熄屏时自动暂停采样")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "关闭悬浮窗", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    // ------------------------------------------------------------------ 视图

    private void buildView() {
        view = u.col();
        view.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
        GradientDrawable bgd = Ui.round(0xE6121418, u.dp(16));
        bgd.setStroke(u.dp(1), 0x33FFFFFF);
        view.setBackground(bgd);

        LinearLayout head = u.row();
        temp = u.text("--", 18, u.fg, true);
        temp.setFontFeatureSettings("tnum");
        head.addView(temp);
        power = u.text("", 12, u.dim, false);
        power.setFontFeatureSettings("tnum");
        power.setSingleLine(true);
        head.addView(power, Ui.lp(0, -2, u.dp(8), 0, 0, 0));
        ((LinearLayout.LayoutParams) power.getLayoutParams()).weight = 1;
        TextView close = u.text("✕", 14, u.dim, false);
        close.setPadding(u.dp(8), u.dp(2), u.dp(2), u.dp(2));
        close.setOnClickListener(v -> stopSelf());
        head.addView(close);
        view.addView(head);

        status = u.text("", 11, u.warn, false);
        status.setMaxLines(3);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(View.GONE);
        view.addView(status, Ui.lp(-1, -2, 0, u.dp(4), 0, 0));

        rows = u.col();
        view.addView(rows, Ui.lp(-1, -2, 0, u.dp(6), 0, 0));
        // 行视图只创建一次，每次刷新只改文字和颜色，避免反复重排悬浮窗
        for (int i = 0; i < TOP_N; i++) {
            rowViews[i] = row();
            rows.addView(rowViews[i][0], Ui.lp(-1, -2, 0, i > 0 ? u.dp(4) : 0, 0, 0));
        }
        emptyRow = u.text("没有明显的热源 👍", 12, u.dim, false);
        rows.addView(emptyRow);
        rows.setVisibility(collapsed ? View.GONE : View.VISIBLE);

        lp = new WindowManager.LayoutParams(u.dp(216), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = prefs.getInt("float_x", u.dp(12));
        lp.y = prefs.getInt("float_y", u.dp(140));

        // 拖动移动位置；轻点展开/收起进程列表
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                        if (moved) {
                            lp.x = startX + Math.round(dx);
                            lp.y = startY + Math.round(dy);
                            wm.updateViewLayout(view, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            prefs.edit().putInt("float_x", lp.x).putInt("float_y", lp.y).apply();
                        } else {
                            collapsed = !collapsed;
                            prefs.edit().putBoolean("float_collapsed", collapsed).apply();
                            rows.setVisibility(collapsed ? View.GONE : View.VISIBLE);
                            if (last != null) onUpdate(last);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    @Override
    public void onUpdate(Analyzer.Result r) {
        if (view == null) return;
        last = r;
        status.setVisibility(View.GONE);
        Object[] mt = Ui.mainTemp(r);
        double t = (double) mt[0];
        temp.setText(t > -999 ? Analyzer.fmt(t) + "°C" : "--");
        temp.setTextColor(u.heatColor(Math.max(t, Math.max(r.maxTemp, r.skinTemp))));
        JSONObject bat = r.snap.optJSONObject("battery");
        if (bat != null) {
            power.setText(r.discharging
                    ? String.format(Locale.US, "%.2f W", bat.optLong("powerMw") / 1000.0)
                    : "充电 " + Math.abs(bat.optLong("currentMa")) + " mA");
        }
        if (collapsed) return;
        // 进程 / 应用按 CPU，LSP 模块按自动归因的实测 CPU，一起排序取前几名
        List<Entity> items = new ArrayList<>();
        for (Entity e : r.heat) if (e.cpuAvg >= 0.5 || e.level != Analyzer.OK) items.add(e);
        for (Entity e : r.lsp) if (e.measuredCpu >= 0.5) items.add(e);
        for (Entity e : r.ksu) if (e.measuredCpu >= 0.5) items.add(e);
        Collections.sort(items, (a, b) -> Double.compare(value(b), value(a)));
        int n = 0;
        for (Entity e : items) {
            if (n >= TOP_N) break;
            bindRow(rowViews[n], e);
            n++;
        }
        for (int i = 0; i < TOP_N; i++) rowViews[i][0].setVisibility(i < n ? View.VISIBLE : View.GONE);
        emptyRow.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
    }

    private View[] row() {
        LinearLayout row = u.row();
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        dot.setBackground(d);
        row.addView(dot, Ui.lp(u.dp(7), u.dp(7), 0, 0, u.dp(8), 0));
        TextView name = u.text("", 12, u.fg, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, Ui.weight(1));
        TextView val = u.text("", 12, u.fg, true);
        val.setFontFeatureSettings("tnum");
        row.addView(val, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        return new View[]{row, dot, name, val};
    }

    private static double value(Entity e) {
        if (e.type == Analyzer.LSP) return e.measuredCpu;
        if (e.type == Analyzer.KSU) return e.cpuAvg + Math.max(0, e.measuredCpu);
        return e.cpuAvg;
    }

    private void bindRow(View[] v, Entity e) {
        ((GradientDrawable) v[1].getBackground())
                .setColor(e.level == Analyzer.OK ? Ui.typeColor(e) : u.levelColor(e.level));
        ((TextView) v[2]).setText(e.type == Analyzer.LSP || e.type == Analyzer.KSU ? u.label(e) + "（模块）" : u.label(e));
        TextView val = (TextView) v[3];
        val.setText(Analyzer.fmt(value(e)) + "%");
        val.setTextColor(e.level == Analyzer.OK ? u.fg : u.levelColor(e.level));
    }

    @Override
    public boolean wantsFast() {
        return false;
    }

    @Override
    public void onStatus(String msg, boolean error) {
        if (view == null || !error) return;
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
    }
}
