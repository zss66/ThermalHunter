package com.thermalhunter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.thermalhunter.core.Analyzer;
import com.thermalhunter.core.Analyzer.Entity;
import com.thermalhunter.core.ModuleStatus;
import com.thermalhunter.core.RootSession;
import com.thermalhunter.core.ShizukuShell;

import org.json.JSONArray;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final long INTERVAL_MS = 3000;
    private static final String[] TABS = {"热源排行", "KSU模块", "LSP模块", "唤醒耗电"};

    private final RootSession root = new RootSession();
    private final Analyzer analyzer = new Analyzer();
    private HandlerThread worker;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Analyzer.Result result;
    private int tab;

    // 工作模式：auto = 先试 root，失败再用 Shizuku；root = 仅 root；shizuku = 仅 Shizuku（ADB）
    private static final String[] MODES = {"auto", "root", "shizuku"};
    private static final String[] MODE_NAMES = {"自动（优先 root，没有则用 Shizuku）", "仅 root（KernelSU）", "仅 Shizuku（无线调试 / ADB）"};
    private volatile boolean rootFailed;   // auto 模式下 root 已失败，本次不再反复申请
    private volatile boolean shizukuAsked; // 本次可见期间已弹过 Shizuku 授权
    private final Runnable tickR = this::tick;
    private final Shizuku.OnBinderReceivedListener binderListener = this::kick;
    private final Shizuku.OnRequestPermissionResultListener permListener = (code, res) -> {
        if (code == ShizukuShell.REQUEST_CODE) kick();
    };

    private SharedPreferences prefs;
    private Palette c;
    private TextView headDevice, headTemp, headPower, headLoad, banner;
    private final TextView[] tabViews = new TextView[TABS.length];
    private ListView list;
    private Button cleanBtn;
    private final EntityAdapter adapter = new EntityAdapter();
    private final Map<String, Object[]> appInfoCache = new HashMap<>(); // pkg -> {label, icon}

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        c = new Palette(night);
        getWindow().setStatusBarColor(c.bg);
        getWindow().setNavigationBarColor(c.bg);
        setContentView(buildLayout());
        worker = new HandlerThread("root-worker");
        worker.start();
        bg = new Handler(worker.getLooper());
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addRequestPermissionResultListener(permListener);
    }

    /** Shizuku 就绪或授权结果返回时立即重连，不等 5 秒重试。 */
    private void kick() {
        if (!running) return;
        bg.removeCallbacks(tickR);
        bg.post(tickR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        shizukuAsked = false;
        bg.post(tickR);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        bg.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeRequestPermissionResultListener(permListener);
        bg.post(() -> {
            root.close();
            worker.quitSafely();
        });
    }

    /** 只在界面可见时采样：本工具自身不在后台耗电。 */
    private void tick() {
        if (!running) return;
        if (!root.isAlive()) {
            String err = connect();
            if (err != null) {
                ui.post(() -> banner(err, c.bad));
                bg.postDelayed(tickR, 5000);
                return;
            }
        }
        JSONObject snap = root.safeCall("SNAPSHOT");
        if (snap.optBoolean("ok")) {
            Analyzer.Result r = analyzer.update(snap);
            ui.post(() -> render(r));
        } else {
            String err = snap.optString("error");
            ui.post(() -> banner("采集失败：" + err, c.bad));
        }
        if (running) bg.postDelayed(tickR, INTERVAL_MS);
    }

    /** 按工作模式建立后台会话；成功返回 null，失败返回给用户看的说明。在 bg 线程执行。 */
    private String connect() {
        String mode = prefs.getString("work_mode", "auto");
        String rootErr = null;
        if (mode.equals("root") || (mode.equals("auto") && !rootFailed)) {
            ui.post(() -> banner("正在获取 root 权限…", c.warn));
            if (root.start(this, false)) return null;
            rootErr = root.lastError();
            if (mode.equals("root")) {
                return "❌ 未获得 root：" + rootErr + "\n请在 KernelSU 管理器 → 超级用户 中允许「热源猎手」，然后重新打开。";
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
            ui.post(() -> banner("正在通过 Shizuku 启动…", c.warn));
            if (root.start(this, true)) return null;
            why = "Shizuku 启动采集进程失败：" + root.lastError();
        }
        if (rootErr != null) return "❌ 未获得 root：" + rootErr + "\n❌ " + why;
        return "❌ " + why;
    }

    // ------------------------------------------------------------------ 布局

    private View buildLayout() {
        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(c.bg);
        rootView.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(12));
        header.setBackground(round(c.card, 0));
        TextView title = text("热源猎手", 22, c.fg, true);
        header.addView(title);
        headDevice = text("", 12, c.dim, false);
        header.addView(headDevice);
        headTemp = text("—", 15, c.fg, true);
        headTemp.setPadding(0, dp(8), 0, 0);
        header.addView(headTemp);
        headPower = text("", 13, c.fg, false);
        header.addView(headPower);
        headLoad = text("", 12, c.dim, false);
        header.addView(headLoad);
        banner = text("", 12, c.warn, false);
        banner.setVisibility(View.GONE);
        banner.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(8);
        header.addView(banner, blp);
        rootView.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), dp(6), dp(8), dp(6));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 14, c.fg, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(8), 0, dp(8));
            t.setOnClickListener(v -> {
                tab = idx;
                updateTabs();
                if (result != null) render(result);
                list.setSelection(0);
            });
            tabViews[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(t, lp);
        }
        rootView.addView(tabs);
        updateTabs();

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(dp(10), 0, dp(10), dp(8));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showDetail(adapter.items.get(pos)));
        rootView.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(8), dp(12), dp(12));
        cleanBtn = new Button(this);
        cleanBtn.setText("一键清理异常");
        cleanBtn.setTextColor(Color.WHITE);
        cleanBtn.setTextSize(16);
        cleanBtn.setAllCaps(false);
        cleanBtn.setBackground(round(c.accent, dp(24)));
        cleanBtn.setOnClickListener(v -> confirmClean());
        bottom.addView(cleanBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button settings = new Button(this);
        settings.setText("设置");
        settings.setAllCaps(false);
        settings.setTextColor(c.fg);
        settings.setBackground(round(c.card, dp(24)));
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(80), dp(48));
        slp.leftMargin = dp(10);
        bottom.addView(settings, slp);
        rootView.addView(bottom);
        return rootView;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setTextColor(sel ? Color.WHITE : c.fg);
            tabViews[i].setBackground(round(sel ? c.accent : c.card, dp(18)));
        }
    }

    private void banner(String msg, int color) {
        banner.setText(msg);
        banner.setTextColor(color);
        banner.setBackground(round(c.bannerBg, dp(8)));
        banner.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Analyzer.Result r) {
        result = r;
        JSONObject s = r.snap;
        JSONObject dev = s.optJSONObject("device");
        if (dev != null) {
            headDevice.setText(dev.optString("model") + " · " + dev.optString("soc") + " · Android "
                    + dev.optString("android") + "\n内核 " + dev.optString("kernel") + " · "
                    + ("shell".equals(s.optString("mode")) ? "Shizuku（shell 权限）" : s.optString("rootImpl")));
        }
        StringBuilder t = new StringBuilder();
        if (r.maxTemp > -999) t.append("SoC ").append(Analyzer.fmt(r.maxTemp)).append("°C");
        if (r.skinTemp > -999) t.append("   机身 ").append(Analyzer.fmt(r.skinTemp)).append("°C");
        if (r.batteryTemp > -999) t.append("   电池 ").append(Analyzer.fmt(r.batteryTemp)).append("°C");
        headTemp.setText(t.length() == 0 ? "温度不可读" : t.toString());
        double hot = Math.max(r.maxTemp, r.skinTemp);
        headTemp.setTextColor(hot >= 50 ? c.bad : hot >= 42 ? c.warn : c.good);

        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            String p = r.discharging
                    ? String.format(Locale.US, "放电功耗 %.2f W（%d mA）", bat.optLong("powerMw") / 1000.0, Math.abs(bat.optLong("currentMa")))
                    : "充电中 " + bat.optLong("currentMa") + " mA（充电时无法估算功耗）";
            headPower.setText("电量 " + bat.optLong("level") + "%  ·  " + p);
        }
        StringBuilder load = new StringBuilder();
        JSONObject cpu = s.optJSONObject("cpu");
        if (cpu != null) {
            load.append("CPU ").append(Analyzer.fmt(cpu.optDouble("usage"))).append("%  ");
            JSONArray pol = cpu.optJSONArray("policies");
            if (pol != null) for (int i = 0; i < pol.length(); i++) {
                JSONObject o = pol.optJSONObject(i);
                load.append(o.optString("cpus").replace(" ", ",")).append(":").append(o.optLong("cur"))
                        .append("/").append(o.optLong("limit")).append("MHz  ");
            }
        }
        JSONObject gpu = s.optJSONObject("gpu");
        if (gpu != null && gpu.length() > 0) {
            load.append("\nGPU ");
            if (gpu.has("util")) load.append(gpu.optLong("util")).append("%  ");
            if (gpu.has("mhz")) load.append(gpu.optLong("mhz")).append("MHz");
        }
        headLoad.setText(load.toString().trim());

        List<String> notes = new ArrayList<>();
        if ("shell".equals(s.optString("mode"))) {
            notes.add("ℹ Shizuku 模式：可查看温度、功耗、进程排行，可强行停止 / 停用应用；"
                    + "KSU / LSP 模块、应用唤醒统计、杀死其他进程需要 root。");
        } else if (!ModuleStatus.isActive()) {
            notes.add("⚠ LSPosed 模块未激活：在 LSPosed 中启用本模块并勾选「系统框架」后重启，才能统计应用唤醒锁/闹钟。");
        } else if (!r.hookOk) {
            notes.add(r.hookAgeSec < 0 ? "⚠ 模块已激活，但系统框架 hook 未生效（作用域需勾选「系统框架」并重启）"
                    : "⚠ 唤醒统计数据已过期 " + r.hookAgeSec + " 秒");
        }
        JSONObject lsp = s.optJSONObject("lsp");
        if (!"shell".equals(s.optString("mode")) && lsp != null && !lsp.optBoolean("available") && lsp.has("error")) notes.add("LSPosed：" + lsp.optString("error"));
        if (notes.isEmpty()) banner.setVisibility(View.GONE);
        else banner(TextUtils.join("\n", notes), c.warn);

        cleanBtn.setText(r.badCount > 0 ? "一键清理异常（" + r.badCount + "）" : "一键清理异常");
        cleanBtn.setBackground(round(r.badCount > 0 ? c.bad : c.accent, dp(24)));

        List<Entity> items;
        switch (tab) {
            case 1: items = r.ksu; break;
            case 2: items = r.lsp; break;
            case 3: items = r.wake; break;
            default:
                items = new ArrayList<>();
                for (Entity e : r.heat) {
                    if (items.size() >= 60) break;
                    if (e.cpuAvg >= 0.3 || e.level != Analyzer.OK) items.add(e);
                }
        }
        adapter.set(items);
    }

    private Object[] appInfo(String pkg) {
        Object[] cached = appInfoCache.get(pkg);
        if (cached != null) return cached;
        Object[] v = new Object[]{null, null};
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            v[0] = pm.getApplicationLabel(ai).toString();
            v[1] = pm.getApplicationIcon(ai);
        } catch (Throwable ignored) {
        }
        appInfoCache.put(pkg, v);
        return v;
    }

    private String label(Entity e) {
        if (e.type == Analyzer.APP || e.type == Analyzer.LSP) {
            Object[] info = appInfo(e.pkg);
            if (info[0] != null) return (String) info[0];
        }
        return e.title;
    }

    private static String typeName(Entity e) {
        switch (e.type) {
            case Analyzer.APP: return "应用";
            case Analyzer.NATIVE: return e.ksuId != null ? "模块进程" : "系统进程";
            case Analyzer.KERNEL: return "内核线程";
            case Analyzer.KSU: return "KSU模块";
            default: return "LSP模块";
        }
    }

    private String metrics(Entity e) {
        StringBuilder sb = new StringBuilder();
        if (tab == 3) {
            sb.append("累计持锁 ").append(dur(e.wlHeldTotalMs)).append(" · 唤醒闹钟 ").append(e.alarmsTotal).append(" 次");
            if (e.wlFrac >= 0) sb.append("\n近2分钟：持锁 ").append(Math.round(e.wlFrac * 100)).append("% · 闹钟 ")
                    .append(Analyzer.fmt(e.alarmsPerMin)).append("/分");
            if (e.wlTag != null && !e.wlTag.isEmpty()) sb.append("\n锁: ").append(e.wlTag);
            return sb.toString();
        }
        if (e.type == Analyzer.LSP) {
            sb.append(e.sub).append(" · 作用域 CPU ").append(Analyzer.fmt(e.scopeCpu)).append("%");
            if (!e.pids.isEmpty()) sb.append(" · 自身 CPU ").append(Analyzer.fmt(e.cpuAvg)).append("%");
            return sb.toString();
        }
        sb.append("CPU ").append(Analyzer.fmt(e.cpuAvg)).append("%");
        if (e.powerMw > 0) sb.append(" · ≈").append(e.powerMw).append("mW");
        if (e.rssKb > 0) sb.append(" · 内存 ").append(e.rssKb / 1024).append("MB");
        if (e.type == Analyzer.KSU) sb.append(" · 进程 ").append(e.pids.size());
        else if (e.pids.size() > 1) sb.append(" · ").append(e.pids.size()).append(" 个进程");
        else if (e.pids.size() == 1) sb.append(" · pid ").append(e.pids.get(0));
        if (e.type == Analyzer.APP && e.wlFrac > 0.05) sb.append(" · 持锁 ").append(Math.round(e.wlFrac * 100)).append("%");
        if (e.type == Analyzer.APP && e.minAdj <= 1000) sb.append(" · ").append(adjName(e.minAdj));
        return sb.toString();
    }

    private static String adjName(int adj) {
        if (adj < 0) return "系统常驻";
        if (adj == 0) return "前台";
        if (adj < 200) return "可见";
        if (adj < 300) return "前台服务";
        if (adj < 900) return "后台服务";
        return "缓存";
    }

    private static String dur(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "秒";
        if (s < 3600) return (s / 60) + "分" + (s % 60) + "秒";
        return (s / 3600) + "时" + (s % 3600 / 60) + "分";
    }

    // ------------------------------------------------------------------ 详情与操作

    private void showDetail(Entity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(typeName(e)).append("：").append(e.title).append('\n');
        if (!e.sub.isEmpty()) sb.append(e.sub).append('\n');
        sb.append(metrics(e)).append("\n");
        for (String r : e.reasons) sb.append("\n❗ ").append(r);
        for (String w : e.warnings) sb.append("\n⚠ ").append(w);
        if (!e.tags.isEmpty()) sb.append("\n标签：").append(TextUtils.join("、", e.tags));
        if (e.type == Analyzer.KSU && e.raw != null) {
            sb.append("\n\n").append(e.raw.optString("desc"));
            sb.append("\n脚本：").append(e.raw.optBoolean("service") ? "service.sh " : "")
                    .append(e.raw.optBoolean("postfs") ? "post-fs-data.sh " : "")
                    .append(e.raw.optBoolean("sysprop") ? "system.prop " : "");
        }
        if (e.type == Analyzer.LSP && !e.scopes.isEmpty()) {
            sb.append("\n\n作用域：").append(TextUtils.join("、", e.scopes.subList(0, Math.min(40, e.scopes.size()))));
            if (e.scopes.size() > 40) sb.append(" …");
        }
        if (!e.pids.isEmpty()) {
            sb.append("\n\n进程：");
            for (int i = 0; i < e.pids.size() && i < 20; i++) {
                sb.append("\n  ").append(e.pids.get(i)).append("  ").append(e.pidNames.get(i));
            }
        }
        if (e.isProtected) sb.append("\n\n🛡 受保护对象：为防止死机/重启，不允许杀死或停用。");

        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (!e.isProtected && !e.isSelf) {
            switch (e.type) {
                case Analyzer.APP:
                    labels.add("强行停止");
                    acts.add(() -> run("FORCESTOP " + e.pkg + " " + e.user));
                    labels.add("停用（冻结）应用");
                    acts.add(() -> confirm("停用 " + label(e) + "？\n停用后应用图标消失、不会再启动，可在本应用详情里重新启用。",
                            () -> run("APP_DISABLE " + e.pkg + " " + e.user)));
                    break;
                case Analyzer.NATIVE:
                    labels.add("杀死进程");
                    acts.add(() -> runAll(killCmds(e)));
                    break;
                case Analyzer.KSU:
                    if (!e.pids.isEmpty()) {
                        labels.add("杀死模块的全部进程");
                        acts.add(() -> run("KSU_KILL " + e.ksuId));
                    }
                    if (e.enabled) {
                        labels.add("禁用此模块（并杀死进程）");
                        acts.add(() -> confirm("禁用 KSU 模块「" + e.title + "」？\n进程会立即结束，模块的挂载/系统属性需重启后才会撤销。",
                                () -> run("KSU_DISABLE " + e.ksuId)));
                    } else {
                        labels.add("重新启用此模块");
                        acts.add(() -> run("KSU_ENABLE " + e.ksuId));
                    }
                    break;
                case Analyzer.LSP:
                    if (e.enabled) {
                        labels.add("在 LSPosed 中禁用此模块");
                        acts.add(() -> confirm("禁用 LSPosed 模块「" + label(e) + "」？\nLSPosed 会缓存配置，需要重启手机才会停止注入。",
                                () -> run("LSP_DISABLE " + e.lspPkg)));
                    } else {
                        labels.add("在 LSPosed 中重新启用");
                        acts.add(() -> run("LSP_ENABLE " + e.lspPkg));
                    }
                    if (!e.pids.isEmpty()) {
                        labels.add("强行停止模块应用本身");
                        acts.add(() -> run("FORCESTOP " + e.lspPkg + " 0"));
                    }
                    break;
            }
        }
        if (e.type == Analyzer.APP) {
            labels.add("重新启用（解冻）应用");
            acts.add(() -> run("APP_ENABLE " + e.pkg + " " + e.user));
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(label(e)).setMessage(sb.toString());
        if (!labels.isEmpty()) {
            // 消息与列表不能共存，改用多个按钮/二级菜单
            b.setPositiveButton("操作…", (d, w) -> new AlertDialog.Builder(this)
                    .setTitle(label(e))
                    .setItems(labels.toArray(new String[0]), (d2, which) -> acts.get(which).run())
                    .show());
        }
        b.setNegativeButton("关闭", null).show();
    }

    private static List<String> killCmds(Entity e) {
        List<String> cmds = new ArrayList<>();
        for (int i = 0; i < e.pids.size(); i++) {
            String n = e.pidNames.get(i).split(" ")[0];
            cmds.add("KILL " + e.pids.get(i) + " " + n);
        }
        return cmds;
    }

    private void confirm(String msg, Runnable ok) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("确定", (d, w) -> ok.run())
                .setNegativeButton("取消", null).show();
    }

    private void run(String cmd) {
        List<String> l = new ArrayList<>();
        l.add(cmd);
        runAll(l);
    }

    private void runAll(List<String> cmds) {
        bg.post(() -> {
            List<String> msgs = new ArrayList<>();
            for (String cmd : cmds) {
                JSONObject r = root.safeCall(cmd);
                msgs.add(r.optBoolean("ok") ? "✓ " + r.optString("msg") : "✗ " + r.optString("error"));
            }
            ui.post(() -> {
                if (msgs.size() <= 2) Toast.makeText(this, TextUtils.join("\n", msgs), Toast.LENGTH_LONG).show();
                else new AlertDialog.Builder(this).setTitle("执行结果").setMessage(TextUtils.join("\n", msgs))
                        .setPositiveButton("好", null).show();
            });
        });
    }

    // ------------------------------------------------------------------ 一键清理

    private void confirmClean() {
        if (result == null) return;
        boolean includeFg = prefs.getBoolean("clean_foreground", false);
        boolean disableModules = prefs.getBoolean("clean_disable_modules", false);
        List<Entity> targets = Analyzer.cleanTargets(result, includeFg);
        boolean shell = "shell".equals(result.snap.optString("mode"));
        if (targets.isEmpty()) {
            Toast.makeText(this, "当前没有检测到异常对象 👍", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        List<String> cmds = new ArrayList<>();
        for (Entity e : targets) {
            sb.append("• ").append(typeName(e)).append(" ").append(label(e));
            if (!e.reasons.isEmpty()) sb.append("：").append(e.reasons.get(0));
            sb.append(" → ");
            switch (e.type) {
                case Analyzer.APP:
                    sb.append("强行停止");
                    cmds.add("FORCESTOP " + e.pkg + " " + e.user);
                    break;
                case Analyzer.NATIVE:
                    if (shell) {
                        sb.append("跳过（Shizuku 模式无权杀死，需要 root）");
                    } else {
                        sb.append("杀死进程");
                        cmds.addAll(killCmds(e));
                    }
                    break;
                case Analyzer.KSU:
                    if (disableModules) {
                        sb.append("禁用模块");
                        cmds.add("KSU_DISABLE " + e.ksuId);
                    } else {
                        sb.append("杀死模块进程");
                        cmds.add("KSU_KILL " + e.ksuId);
                    }
                    break;
                case Analyzer.LSP:
                    if (disableModules) {
                        sb.append("在 LSPosed 中禁用（重启生效）");
                        cmds.add("LSP_DISABLE " + e.lspPkg);
                    } else {
                        sb.append("停止模块进程");
                        cmds.add("FORCESTOP " + e.lspPkg + " 0");
                    }
                    break;
            }
            sb.append('\n');
        }
        new AlertDialog.Builder(this).setTitle("将清理 " + targets.size() + " 个异常对象")
                .setMessage(sb.toString().trim())
                .setPositiveButton("立即清理", (d, w) -> runAll(cmds))
                .setNegativeButton("取消", null).show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        CheckBox fg = new CheckBox(this);
        fg.setText("一键清理时也处理前台应用");
        fg.setChecked(prefs.getBoolean("clean_foreground", false));
        CheckBox dm = new CheckBox(this);
        dm.setText("一键清理时直接禁用异常的 KSU / LSP 模块（否则只杀进程）");
        dm.setChecked(prefs.getBoolean("clean_disable_modules", false));
        TextView modeTitle = text("工作模式", 14, c.fg, true);
        box.addView(modeTitle);
        RadioGroup modeGroup = new RadioGroup(this);
        String curMode = prefs.getString("work_mode", "auto");
        for (int i = 0; i < MODES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(MODE_NAMES[i]);
            rb.setChecked(MODES[i].equals(curMode));
            modeGroup.addView(rb);
        }
        box.addView(modeGroup);
        TextView modeNote = text("Shizuku 模式无需 root：先安装 Shizuku 并通过「无线调试」启动它，"
                + "本应用会以 shell 权限工作（模块管理与杀进程不可用）。", 12, c.dim, false);
        modeNote.setPadding(0, 0, 0, dp(8));
        box.addView(modeNote);
        box.addView(fg);
        box.addView(dm);
        TextView note = text("判定规则（单核 CPU%，约 15 秒平均）：\n"
                + "· 任意应用 ≥35%；后台 ≥10%；缓存进程 ≥3%\n"
                + "· KSU 模块进程合计 ≥5%；其他 native 进程 ≥20%\n"
                + "· 后台持唤醒锁 ≥50% 时间，或唤醒闹钟 ≥6 次/分\n"
                + "· LSP 模块：自身后台 ≥8%；作用域内有异常应用时提示嫌疑", 12, c.dim, false);
        note.setPadding(0, dp(12), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this).setTitle("设置").setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    int idx = modeGroup.getCheckedRadioButtonId() - 1;
                    String newMode = idx >= 0 && idx < MODES.length ? MODES[idx] : curMode;
                    prefs.edit()
                            .putBoolean("clean_foreground", fg.isChecked())
                            .putBoolean("clean_disable_modules", dm.isChecked())
                            .putString("work_mode", newMode).apply();
                    if (!newMode.equals(curMode)) {
                        // 切换模式：关闭当前会话，下次采样按新模式重连
                        bg.post(() -> {
                            rootFailed = false;
                            shizukuAsked = false;
                            root.close();
                        });
                        kick();
                    }
                })
                .setNeutralButton("重启手机", (d, w) -> confirm("立即重启手机？（模块禁用需重启才能完全生效）", () -> run("REBOOT")))
                .setNegativeButton("取消", null).show();
    }

    // ------------------------------------------------------------------ 列表

    private final class EntityAdapter extends BaseAdapter {
        final List<Entity> items = new ArrayList<>();

        void set(List<Entity> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                h = new Holder();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                h.icon = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(36), dp(36));
                ilp.rightMargin = dp(12);
                ilp.topMargin = dp(2);
                row.addView(h.icon, ilp);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout top = new LinearLayout(MainActivity.this);
                top.setGravity(Gravity.CENTER_VERTICAL);
                h.title = text("", 15, c.fg, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(h.title, new LinearLayout.LayoutParams(0, -2, 1));
                h.badge = text("", 11, Color.WHITE, true);
                h.badge.setPadding(dp(7), dp(2), dp(7), dp(2));
                top.addView(h.badge);
                col.addView(top);
                h.sub = text("", 11, c.dim, false);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(h.sub);
                h.metrics = text("", 12, c.fg, false);
                col.addView(h.metrics);
                h.reasons = text("", 12, c.bad, false);
                col.addView(h.reasons);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setPadding(0, dp(4), 0, dp(4));
                wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                h.card = row;
                wrap.setTag(h);
                convert = wrap;
            } else {
                h = (Holder) convert.getTag();
            }
            Entity e = items.get(pos);
            h.title.setText(label(e));
            String sub = typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title);
            if (!e.enabled) sub = "已禁用 · " + sub;
            h.sub.setText(sub);
            h.metrics.setText(metrics(e));
            StringBuilder rs = new StringBuilder();
            for (String r : e.reasons) rs.append(rs.length() > 0 ? "\n" : "").append("❗ ").append(r);
            for (String w : e.warnings) rs.append(rs.length() > 0 ? "\n" : "").append("⚠ ").append(w);
            List<String> tags = new ArrayList<>(e.tags);
            if (e.isProtected) tags.add("受保护");
            if (!tags.isEmpty()) rs.append(rs.length() > 0 ? "\n" : "").append("🏷 ").append(TextUtils.join(" · ", tags));
            h.reasons.setText(rs);
            h.reasons.setTextColor(e.level == Analyzer.BAD ? c.bad : e.level == Analyzer.WARN ? c.warn : c.dim);
            h.reasons.setVisibility(rs.length() == 0 ? View.GONE : View.VISIBLE);
            if (e.level == Analyzer.BAD) {
                h.badge.setText("异常");
                h.badge.setBackground(round(c.bad, dp(10)));
                h.badge.setVisibility(View.VISIBLE);
            } else if (e.level == Analyzer.WARN) {
                h.badge.setText("可疑");
                h.badge.setBackground(round(c.warn, dp(10)));
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            Drawable icon = null;
            if (e.type == Analyzer.APP || e.type == Analyzer.LSP) icon = (Drawable) appInfo(e.pkg)[1];
            if (icon != null) h.icon.setImageDrawable(icon);
            else h.icon.setImageDrawable(letterIcon(e));
            h.card.setBackground(round(e.level == Analyzer.BAD ? c.badCard : c.card, dp(14)));
            h.card.setAlpha(e.enabled ? 1f : 0.55f);
            return convert;
        }
    }

    private static final class Holder {
        LinearLayout card;
        ImageView icon;
        TextView title, sub, metrics, reasons, badge;
    }

    private Drawable letterIcon(Entity e) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        int col;
        switch (e.type) {
            case Analyzer.KSU: col = 0xFF7E57C2; break;
            case Analyzer.KERNEL: col = 0xFF78909C; break;
            case Analyzer.NATIVE: col = e.ksuId != null ? 0xFF7E57C2 : 0xFF546E7A; break;
            default: col = 0xFF26A69A;
        }
        g.setColor(col);
        return g;
    }

    // ------------------------------------------------------------------ 小工具

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static final class Palette {
        final int bg, card, fg, dim, accent, good, warn, bad, badCard, bannerBg;

        Palette(boolean night) {
            if (night) {
                bg = 0xFF101214; card = 0xFF1C1F23; fg = 0xFFE8EAED; dim = 0xFF9AA0A6;
                accent = 0xFF3D7BFD; good = 0xFF5BD18B; warn = 0xFFF2A93B; bad = 0xFFFF5C5C;
                badCard = 0xFF3A1F22; bannerBg = 0xFF2A2620;
            } else {
                bg = 0xFFF3F4F6; card = 0xFFFFFFFF; fg = 0xFF1F2328; dim = 0xFF6B7280;
                accent = 0xFF2563EB; good = 0xFF16A34A; warn = 0xFFD97706; bad = 0xFFDC2626;
                badCard = 0xFFFDECEC; bannerBg = 0xFFFFF7E6;
            }
        }
    }
}
