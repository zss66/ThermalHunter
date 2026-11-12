package com.thermalhunter.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.thermalhunter.R;
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
    private TextView headDevice, modeChip, banner, emptyText, loadSummary;
    private TextView heroLabel, heroTemp, heroTempSub, heroPowerLabel, heroPower, heroPowerSub;
    private LinearLayout hero, clusterRow;
    private final int[] tabCounts = new int[TABS.length];

    // 温度卡片渐变：无数据 / 正常 / 偏热 / 过热
    private static final int[] GRAD_NONE = {0xFF64748B, 0xFF94A3B8};
    private static final int[] GRAD_COOL = {0xFF2563EB, 0xFF06B6D4};
    private static final int[] GRAD_WARM = {0xFFF59E0B, 0xFFF97316};
    private static final int[] GRAD_HOT = {0xFFEF4444, 0xFFDB2777};
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
                ui.post(() -> {
                    banner(err, c.bad);
                    chip("");
                });
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

        // 头部（随列表一起滚动）
        LinearLayout header = col();
        header.setPadding(dp(16), dp(14), dp(16), dp(6));

        // 顶栏：标题 / 设备 / 模式标签 / 设置
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = col();
        titleCol.addView(text("热源猎手", 24, c.fg, true));
        headDevice = text("正在连接…", 12, c.dim, false);
        headDevice.setMaxLines(2);
        headDevice.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headDevice);
        top.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));
        modeChip = text("", 11, c.dim, true);
        modeChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(modeChip, margins(-2, -2, dp(8), 0, 0, 0));
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_tune);
        gear.setColorFilter(c.fg);
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setBackground(ripple(card(dp(20))));
        gear.setContentDescription("设置");
        gear.setOnClickListener(v -> showSettings());
        top.addView(gear, margins(dp(42), dp(42), dp(8), 0, 0, 0));
        header.addView(top);
        chip("");

        // 温度 / 功耗卡片
        hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(16), dp(18), dp(18));
        hero.setBackground(gradient(GRAD_NONE, dp(24)));
        hero.setElevation(dp(2));
        LinearLayout hl = col();
        heroLabel = text("SoC 温度", 12, 0xDDFFFFFF, false);
        heroTemp = text("--", 40, Color.WHITE, true);
        heroTemp.setFontFeatureSettings("tnum");
        heroTempSub = text("", 12, 0xDDFFFFFF, false);
        hl.addView(heroLabel);
        hl.addView(heroTemp);
        hl.addView(heroTempSub);
        hero.addView(hl, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout hr = col();
        hr.setGravity(Gravity.END);
        heroPowerLabel = text("放电功耗", 12, 0xDDFFFFFF, false);
        heroPower = text("--", 26, Color.WHITE, true);
        heroPower.setFontFeatureSettings("tnum");
        heroPowerSub = text("", 12, 0xDDFFFFFF, false);
        hr.addView(heroPowerLabel);
        hr.addView(heroPower);
        hr.addView(heroPowerSub);
        hero.addView(hr, margins(-2, -2, dp(12), 0, 0, 0));
        header.addView(hero, margins(-1, -2, 0, dp(14), 0, 0));

        // 负载卡片：CPU 各簇频率 + GPU
        LinearLayout load = col();
        load.setPadding(dp(14), dp(12), dp(14), dp(14));
        load.setBackground(card(dp(20)));
        LinearLayout lt = new LinearLayout(this);
        lt.setGravity(Gravity.CENTER_VERTICAL);
        lt.addView(text("实时负载", 14, c.fg, true), new LinearLayout.LayoutParams(0, -2, 1));
        loadSummary = text("", 12, c.dim, false);
        loadSummary.setFontFeatureSettings("tnum");
        lt.addView(loadSummary);
        load.addView(lt);
        clusterRow = new LinearLayout(this);
        load.addView(clusterRow, margins(-1, -2, 0, dp(10), 0, 0));
        header.addView(load, margins(-1, -2, 0, dp(12), 0, 0));

        // 提示条
        banner = text("", 12, c.warn, false);
        banner.setVisibility(View.GONE);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setLineSpacing(dp(2), 1f);
        header.addView(banner, margins(-1, -2, 0, dp(12), 0, 0));

        // 分段式标签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackground(card(dp(16)));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 13, c.dim, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(9), 0, dp(9));
            t.setOnClickListener(v -> {
                if (tab == idx) return;
                tab = idx;
                updateTabs();
                if (result != null) render(result);
            });
            tabViews[i] = t;
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        header.addView(tabs, margins(-1, -2, 0, dp(14), 0, dp(4)));
        updateTabs();

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT)); // 卡片自带水波纹
        list.setPadding(0, 0, 0, dp(8));
        list.setClipToPadding(false);
        list.addHeaderView(header, null, false);
        LinearLayout footer = new LinearLayout(this);
        emptyText = text("正在采集…", 13, c.dim, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(24), dp(36), dp(24), dp(36));
        footer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof Entity) showDetail((Entity) o);
        });
        rootView.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(16), dp(8), dp(16), dp(14));
        cleanBtn = new Button(this);
        cleanBtn.setText("一键清理异常");
        cleanBtn.setTextColor(Color.WHITE);
        cleanBtn.setTextSize(16);
        cleanBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cleanBtn.setAllCaps(false);
        cleanBtn.setStateListAnimator(null);
        cleanBtn.setElevation(dp(3));
        cleanBtn.setBackground(ripple(round(c.accent, dp(26))));
        cleanBtn.setOnClickListener(v -> confirmClean());
        bottom.addView(cleanBtn, new LinearLayout.LayoutParams(-1, dp(52)));
        rootView.addView(bottom);
        return rootView;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setText(tabCounts[i] > 0 ? TABS[i] + " " + tabCounts[i] : TABS[i]);
            tabViews[i].setTextColor(sel ? Color.WHITE : c.dim);
            tabViews[i].setBackground(sel ? round(c.accent, dp(12)) : null);
        }
    }

    private void banner(String msg, int color) {
        banner.setText(msg);
        banner.setTextColor(color);
        banner.setBackground(round(tint(color), dp(14)));
        banner.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 顶栏模式标签：root / shell / 未连接。 */
    private void chip(String mode) {
        int col = "root".equals(mode) ? c.good : "shell".equals(mode) ? c.accent : c.dim;
        modeChip.setText("root".equals(mode) ? "ROOT" : "shell".equals(mode) ? "SHIZUKU" : "未连接");
        modeChip.setTextColor(col);
        modeChip.setBackground(round(tint(col), dp(12)));
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Analyzer.Result r) {
        result = r;
        JSONObject s = r.snap;
        String mode = s.optString("mode");
        boolean shell = "shell".equals(mode);
        chip(mode);
        JSONObject dev = s.optJSONObject("device");
        if (dev != null) {
            headDevice.setText(dev.optString("model") + " · " + dev.optString("soc") + " · Android "
                    + dev.optString("android") + "\n内核 " + dev.optString("kernel")
                    + (shell ? "" : " · " + s.optString("rootImpl")));
        }

        // 温度卡片
        boolean hasSoc = r.maxTemp > -999;
        double mainTemp = hasSoc ? r.maxTemp : r.skinTemp;
        heroLabel.setText(hasSoc ? "SoC 温度" : "机身温度");
        heroTemp.setText(mainTemp > -999 ? Analyzer.fmt(mainTemp) + "°C" : "--");
        List<String> tsub = new ArrayList<>();
        if (hasSoc && r.skinTemp > -999) tsub.add("机身 " + Analyzer.fmt(r.skinTemp) + "°");
        if (r.batteryTemp > -999) tsub.add("电池 " + Analyzer.fmt(r.batteryTemp) + "°");
        heroTempSub.setText(TextUtils.join("  ·  ", tsub));
        double hot = Math.max(r.maxTemp, r.skinTemp);
        hero.setBackground(gradient(hot <= -999 ? GRAD_NONE : hot >= 50 ? GRAD_HOT : hot >= 42 ? GRAD_WARM : GRAD_COOL, dp(24)));
        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            long ma = Math.abs(bat.optLong("currentMa"));
            if (r.discharging) {
                heroPowerLabel.setText("放电功耗");
                heroPower.setText(String.format(Locale.US, "%.2f W", bat.optLong("powerMw") / 1000.0));
                heroPowerSub.setText(ma + " mA · 电量 " + bat.optLong("level") + "%");
            } else {
                heroPowerLabel.setText("充电中");
                heroPower.setText(ma + " mA");
                heroPowerSub.setText("电量 " + bat.optLong("level") + "%");
            }
        }

        // 负载卡片
        List<String> ls = new ArrayList<>();
        JSONObject cpu = s.optJSONObject("cpu");
        if (cpu != null) ls.add("CPU " + Analyzer.fmt(cpu.optDouble("usage")) + "%");
        JSONObject gpu = s.optJSONObject("gpu");
        if (gpu != null && (gpu.has("util") || gpu.has("mhz"))) {
            String g = "GPU";
            if (gpu.has("util")) g += " " + gpu.optLong("util") + "%";
            if (gpu.has("mhz")) g += " " + freq(gpu.optLong("mhz"));
            ls.add(g);
        }
        loadSummary.setText(TextUtils.join("  ·  ", ls));
        renderClusters(cpu == null ? null : cpu.optJSONArray("policies"));

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
        if (!shell && lsp != null && !lsp.optBoolean("available") && lsp.has("error")) notes.add("LSPosed：" + lsp.optString("error"));
        if (notes.isEmpty()) banner.setVisibility(View.GONE);
        else banner(TextUtils.join("\n", notes), shell ? c.accent : c.warn);

        cleanBtn.setText(r.badCount > 0 ? "一键清理异常（" + r.badCount + "）" : "一键清理异常");
        cleanBtn.setBackground(ripple(round(r.badCount > 0 ? c.bad : c.accent, dp(26))));
        tabCounts[0] = flagged(r.heat);
        tabCounts[1] = flagged(r.ksu);
        tabCounts[2] = flagged(r.lsp);
        tabCounts[3] = flagged(r.wake);
        updateTabs();

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
        emptyText.setText(emptyMessage(shell));
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static int flagged(List<Entity> l) {
        int n = 0;
        for (Entity e : l) if (e.level != Analyzer.OK) n++;
        return n;
    }

    private String emptyMessage(boolean shell) {
        switch (tab) {
            case 1: return shell ? "Shizuku 模式无法读取 KSU 模块（需要 root）" : "没有发现 KSU 模块";
            case 2: return shell ? "Shizuku 模式无法读取 LSPosed 模块（需要 root）" : "没有发现 LSPosed 模块";
            case 3: return shell ? "Shizuku 模式无法统计应用唤醒（需要 root + LSPosed）"
                    : "暂无唤醒数据（需要在 LSPosed 中激活本模块）";
            default: return "没有明显的热源，手机状态良好 👍";
        }
    }

    /** CPU 各簇：当前频率、频率条、限频提示。 */
    private void renderClusters(JSONArray pol) {
        int n = pol == null ? 0 : pol.length();
        if (clusterRow.getChildCount() != n) {
            clusterRow.removeAllViews();
            for (int i = 0; i < n; i++) {
                LinearLayout t = col();
                t.setPadding(dp(10), dp(8), dp(10), dp(9));
                t.setBackground(round(c.sunken, dp(12)));
                TextView name = text("", 11, c.dim, false);
                name.setSingleLine(true);
                TextView val = text("", 15, c.fg, true);
                val.setFontFeatureSettings("tnum");
                val.setSingleLine(true);
                Bar bar = new Bar(this, c.track);
                TextView lim = text("", 10, c.dim, false);
                lim.setSingleLine(true);
                t.addView(name);
                t.addView(val);
                t.addView(bar, margins(-1, dp(4), 0, dp(6), 0, dp(5)));
                t.addView(lim);
                t.setTag(new Object[]{name, val, bar, lim});
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) lp.leftMargin = dp(8);
                clusterRow.addView(t, lp);
            }
        }
        clusterRow.setVisibility(n == 0 ? View.GONE : View.VISIBLE);
        for (int i = 0; i < n; i++) {
            JSONObject o = pol.optJSONObject(i);
            if (o == null) continue;
            Object[] v = (Object[]) clusterRow.getChildAt(i).getTag();
            long cur = o.optLong("cur"), max = o.optLong("max"), limit = o.optLong("limit");
            boolean throttled = limit > 0 && max > 0 && limit < max;
            ((TextView) v[0]).setText("CPU " + cpuRange(o.optString("cpus")));
            ((TextView) v[1]).setText(freq(cur));
            ((Bar) v[2]).set(max > 0 ? cur / (float) max : 0, throttled ? c.warn : c.accent);
            TextView lim = (TextView) v[3];
            lim.setText(throttled ? "限频 " + freq(limit) : "最高 " + freq(max));
            lim.setTextColor(throttled ? c.warn : c.dim);
        }
    }

    /** "4 5 6" → "4-6"；不连续时用逗号。 */
    private static String cpuRange(String cpus) {
        String[] f = cpus.trim().split("[\\s,]+");
        try {
            int a = Integer.parseInt(f[0]), b = Integer.parseInt(f[f.length - 1]);
            if (f.length == 1) return String.valueOf(a);
            if (b - a + 1 == f.length) return a + "-" + b;
        } catch (NumberFormatException ignored) {
        }
        return TextUtils.join(",", f);
    }

    private static String freq(long mhz) {
        if (mhz <= 0) return "--";
        return mhz >= 1000 ? String.format(Locale.US, "%.2fG", mhz / 1000.0) : mhz + "M";
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

    /** @param full 详情页显示全部；列表卡片中 CPU 已在右侧大字显示，故省略 */
    private String metrics(Entity e, boolean full) {
        if (tab == 3) {
            StringBuilder sb = new StringBuilder();
            sb.append("累计持锁 ").append(dur(e.wlHeldTotalMs)).append(" · 唤醒闹钟 ").append(e.alarmsTotal).append(" 次");
            if (e.wlFrac >= 0) sb.append("\n近2分钟：持锁 ").append(Math.round(e.wlFrac * 100)).append("% · 闹钟 ")
                    .append(Analyzer.fmt(e.alarmsPerMin)).append("/分");
            if (e.wlTag != null && !e.wlTag.isEmpty()) sb.append("\n锁: ").append(e.wlTag);
            return sb.toString();
        }
        List<String> p = new ArrayList<>();
        if (e.type == Analyzer.LSP) {
            p.add(e.sub);
            if (full) p.add("作用域 CPU " + Analyzer.fmt(e.scopeCpu) + "%");
            if (!e.pids.isEmpty()) p.add("自身 CPU " + Analyzer.fmt(e.cpuAvg) + "%");
            return TextUtils.join(" · ", p);
        }
        if (full) p.add("CPU " + Analyzer.fmt(e.cpuAvg) + "%");
        if (e.powerMw > 0) p.add("≈" + e.powerMw + "mW");
        if (e.rssKb > 0) p.add("内存 " + e.rssKb / 1024 + "MB");
        if (e.type == Analyzer.KSU) p.add("进程 " + e.pids.size());
        else if (e.pids.size() > 1) p.add(e.pids.size() + " 个进程");
        else if (e.pids.size() == 1) p.add("pid " + e.pids.get(0));
        if (e.type == Analyzer.APP && e.wlFrac > 0.05) p.add("持锁 " + Math.round(e.wlFrac * 100) + "%");
        if (e.type == Analyzer.APP && e.minAdj <= 1000) p.add(adjName(e.minAdj));
        return TextUtils.join(" · ", p);
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
        sb.append(metrics(e, true)).append("\n");
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
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                row.setDuplicateParentStateEnabled(true); // 让卡片水波纹响应列表点击

                FrameLayout iconBox = new FrameLayout(MainActivity.this);
                h.icon = new ImageView(MainActivity.this);
                iconBox.addView(h.icon, new FrameLayout.LayoutParams(-1, -1));
                h.letter = text("", 17, Color.WHITE, true);
                h.letter.setGravity(Gravity.CENTER);
                iconBox.addView(h.letter, new FrameLayout.LayoutParams(-1, -1));
                row.addView(iconBox, margins(dp(42), dp(42), 0, 0, dp(12), 0));

                LinearLayout colv = col();
                LinearLayout top = new LinearLayout(MainActivity.this);
                top.setGravity(Gravity.CENTER_VERTICAL);
                h.title = text("", 15, c.fg, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(h.title, new LinearLayout.LayoutParams(0, -2, 1));
                h.badge = text("", 10, c.bad, true);
                h.badge.setPadding(dp(8), dp(2), dp(8), dp(2));
                top.addView(h.badge, margins(-2, -2, dp(6), 0, 0, 0));
                colv.addView(top);
                h.sub = text("", 11, c.dim, false);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                colv.addView(h.sub, margins(-1, -2, 0, dp(1), 0, 0));
                h.metrics = text("", 12, c.dim, false);
                colv.addView(h.metrics, margins(-1, -2, 0, dp(4), 0, 0));
                h.bar = new Bar(MainActivity.this, c.track);
                colv.addView(h.bar, margins(-1, dp(3), 0, dp(8), 0, 0));
                h.reasons = text("", 12, c.bad, false);
                h.reasons.setLineSpacing(dp(1), 1f);
                colv.addView(h.reasons, margins(-1, -2, 0, dp(6), 0, 0));
                row.addView(colv, new LinearLayout.LayoutParams(0, -2, 1));

                LinearLayout valCol = col();
                valCol.setGravity(Gravity.END);
                h.value = text("", 17, c.fg, true);
                h.value.setFontFeatureSettings("tnum");
                h.caption = text("", 10, c.dim, false);
                valCol.addView(h.value);
                valCol.addView(h.caption);
                row.addView(valCol, margins(-2, -2, dp(10), 0, 0, 0));

                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setPadding(dp(16), dp(5), dp(16), dp(5));
                wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                h.card = row;
                wrap.setTag(h);
                convert = wrap;
            } else {
                h = (Holder) convert.getTag();
            }
            Entity e = items.get(pos);
            String name = label(e);
            h.title.setText(name);
            String sub = typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title);
            if (!e.enabled) sub = "已禁用 · " + sub;
            h.sub.setText(sub);
            String m = metrics(e, false);
            h.metrics.setText(m);
            h.metrics.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);

            // 右侧主数值 + 占用条
            double frac;
            if (tab == 3) {
                h.value.setText(e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--");
                h.caption.setText("持锁");
                frac = Math.max(0, e.wlFrac);
            } else if (e.type == Analyzer.LSP) {
                h.value.setText(Analyzer.fmt(e.scopeCpu) + "%");
                h.caption.setText("作用域 CPU");
                frac = e.scopeCpu / 100;
            } else {
                h.value.setText(Analyzer.fmt(e.cpuAvg) + "%");
                h.caption.setText("CPU");
                frac = e.cpuAvg / 100;
            }
            int lc = e.level == Analyzer.BAD ? c.bad : e.level == Analyzer.WARN ? c.warn : c.accent;
            h.value.setTextColor(e.level == Analyzer.OK ? c.fg : lc);
            h.bar.set((float) Math.min(1, frac), lc);

            StringBuilder rs = new StringBuilder();
            for (String r : e.reasons) rs.append(rs.length() > 0 ? "\n" : "").append("❗ ").append(r);
            for (String w : e.warnings) rs.append(rs.length() > 0 ? "\n" : "").append("⚠ ").append(w);
            List<String> tags = new ArrayList<>(e.tags);
            if (e.isProtected) tags.add("受保护");
            if (!tags.isEmpty()) rs.append(rs.length() > 0 ? "\n" : "").append("🏷 ").append(TextUtils.join(" · ", tags));
            h.reasons.setText(rs);
            h.reasons.setTextColor(e.level == Analyzer.BAD ? c.bad : e.level == Analyzer.WARN ? c.warn : c.dim);
            h.reasons.setVisibility(rs.length() == 0 ? View.GONE : View.VISIBLE);
            if (e.level != Analyzer.OK) {
                int bc = e.level == Analyzer.BAD ? c.bad : c.warn;
                h.badge.setText(e.level == Analyzer.BAD ? "异常" : "可疑");
                h.badge.setTextColor(bc);
                h.badge.setBackground(round(tint(bc), dp(10)));
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }

            Drawable icon = null;
            if (e.type == Analyzer.APP || e.type == Analyzer.LSP) icon = (Drawable) appInfo(e.pkg)[1];
            if (icon != null) {
                h.icon.setImageDrawable(icon);
                h.icon.setBackground(null);
                h.letter.setVisibility(View.GONE);
            } else {
                h.icon.setImageDrawable(null);
                h.icon.setBackground(round(typeColor(e), dp(12)));
                h.letter.setText(initial(name));
                h.letter.setVisibility(View.VISIBLE);
            }

            GradientDrawable bgd = round(e.level == Analyzer.BAD ? c.badCard : c.card, dp(18));
            bgd.setStroke(dp(1), e.level == Analyzer.BAD ? alpha(c.bad, 0x55) : c.stroke);
            h.card.setBackground(ripple(bgd));
            h.card.setAlpha(e.enabled ? 1f : 0.55f);
            return convert;
        }
    }

    private static final class Holder {
        LinearLayout card;
        ImageView icon;
        Bar bar;
        TextView title, sub, metrics, reasons, badge, letter, value, caption;
    }

    private static int typeColor(Entity e) {
        switch (e.type) {
            case Analyzer.KSU: return 0xFF8B5CF6;
            case Analyzer.KERNEL: return 0xFF64748B;
            case Analyzer.NATIVE: return e.ksuId != null ? 0xFF8B5CF6 : 0xFF0EA5E9;
            case Analyzer.LSP: return 0xFFF59E0B;
            default: return 0xFF10B981;
        }
    }

    /** 名称里第一个字母/数字/汉字，用作无图标对象的头像。 */
    private static String initial(String name) {
        if (name != null) {
            for (int i = 0; i < name.length(); ) {
                int cp = name.codePointAt(i);
                if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
                i += Character.charCount(cp);
            }
        }
        return "?";
    }

    /** 圆角细进度条。 */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float frac;

        Bar(Context ctx, int trackColor) {
            super(ctx);
            track.setColor(trackColor);
        }

        void set(float f, int color) {
            frac = Math.max(0f, Math.min(1f, f));
            fill.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, track);
            if (frac > 0) {
                rect.set(0, 0, Math.max(h, w * frac), h);
                cv.drawRoundRect(rect, r, r, fill);
            }
        }
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

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    /** 带细描边的卡片背景。 */
    private GradientDrawable card(int radius) {
        GradientDrawable g = round(c.card, radius);
        g.setStroke(dp(1), c.stroke);
        return g;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(c.fg, 0x1F)), content, null);
    }

    private static GradientDrawable gradient(int[] colors, int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        g.setCornerRadius(radius);
        return g;
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** 同色系浅底（标签、提示条）。 */
    private static int tint(int color) {
        return alpha(color, 0x24);
    }

    private static final class Palette {
        final int bg, card, stroke, sunken, track, fg, dim, accent, good, warn, bad, badCard;

        Palette(boolean night) {
            if (night) {
                bg = 0xFF0E1013; card = 0xFF181B20; stroke = 0xFF262A31; sunken = 0xFF21252B; track = 0xFF2E333B;
                fg = 0xFFE8EAED; dim = 0xFF9AA0A6; accent = 0xFF5B8CFF;
                good = 0xFF4ADE80; warn = 0xFFFBBF24; bad = 0xFFF87171; badCard = 0xFF24181B;
            } else {
                bg = 0xFFF4F5F9; card = 0xFFFFFFFF; stroke = 0xFFE7E9F0; sunken = 0xFFF4F5F9; track = 0xFFE4E7EE;
                fg = 0xFF111827; dim = 0xFF6B7280; accent = 0xFF3B6EF5;
                good = 0xFF16A34A; warn = 0xFFD97706; bad = 0xFFDC2626; badCard = 0xFFFFF6F6;
            }
        }
    }
}
