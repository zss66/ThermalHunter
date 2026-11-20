package com.thermalhunter.ui;

import com.thermalhunter.core.L;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import com.thermalhunter.R;
import com.thermalhunter.core.Analyzer;
import com.thermalhunter.core.Analyzer.Entity;
import com.thermalhunter.core.ModuleStatus;
import com.thermalhunter.core.Monitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Monitor.Listener {

    private static final int[] TABS = {R.string.tab_heat, R.string.tab_ksu, R.string.tab_lsp, R.string.tab_wake};
    private static final int[] MODE_NAMES = {R.string.mode_auto, R.string.mode_root, R.string.mode_shizuku};
    private static final int[] MODE_DESCS = {R.string.mode_auto_desc, R.string.mode_root_desc, R.string.mode_shizuku_desc};

    /** 底部导航：监控 / 耗电 / 工具 / 设置。 */
    static final String EXTRA_PAGE = "page";
    static final int PAGE_MONITOR = 0, PAGE_DRAIN = 1, PAGE_TOOLS = 2, PAGE_SETTINGS = 3;
    private static final int[] PAGE_NAMES = {R.string.page_monitor, R.string.page_drain, R.string.page_tools, R.string.page_settings};
    private static final int[] PAGE_ICONS = {R.drawable.ic_monitor, R.drawable.ic_battery, R.drawable.ic_tools, R.drawable.ic_tune};

    private Monitor mon;
    private SharedPreferences prefs;
    private Ui u;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Analyzer.Result result;
    /** 监控页列表的分段：热源 / KSU / LSP / 唤醒。 */
    private int tab;
    private int page = -1;
    private boolean pendingFloat;
    private String connMode = "";

    private TextView headDevice, headKernel, modeChip, banner, emptyText, loadSummary, snack;
    private TextView heroLabel, heroTemp, heroTempSub, heroPowerLabel, heroPower, heroPowerSub;
    private LinearLayout hero, clusterRow;
    private ImageView floatBtn;
    private TextView profStatus;
    private final int[] tabCounts = new int[TABS.length];
    // 只在状态变化时重设背景，避免每次刷新都重绘
    private int[] lastGrad;
    private int lastCleanState = -1;
    private final TextView[] tabViews = new TextView[TABS.length];
    private ListView list;
    // 监控页的异常 / 清理卡片
    private LinearLayout cleanCard;
    private ImageView cleanIcon;
    private TextView cleanTitle, cleanSub, cleanAction;
    // 底部导航与各页
    private final View[] pages = new View[PAGE_NAMES.length];
    private final View[][] navItems = new View[PAGE_NAMES.length][]; // {item, pill, icon, label, dot}
    private DrainPage drainPage;
    private LinearLayout settingsBody;
    private TextView toolVpnDesc, toolCompatDesc, toolShotDesc, toolFloatDesc, toolRefreshDesc;
    private final EntityAdapter adapter = new EntityAdapter();
    private final Runnable hideSnack = () -> snack.animate().alpha(0f).translationY(20f).setDuration(200)
            .withEndAction(() -> snack.setVisibility(View.GONE)).start();

    // ------------------------------------------------------------------ 生命周期

    /** 按设置里选的语言显示界面。 */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(L.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        u = new Ui(this);
        getWindow().setStatusBarColor(u.bg);
        getWindow().setNavigationBarColor(u.card); // 与底部导航栏同色
        mon = Monitor.get(this);
        setContentView(buildLayout());
        int p = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_PAGE, PAGE_MONITOR)
                : getIntent().getIntExtra(EXTRA_PAGE, PAGE_MONITOR);
        selectPage(p);
        syncVpnHideConfig(); // 把名单写到 system 可读的配置，VPN 隐身统一由 system_server 层按名单完成
        syncScreenshotConfig(); // 截图限制解除名单同理
        DrainWatcher.schedule(this); // 后台耗电监测（按设置开关）
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra(EXTRA_PAGE)) selectPage(intent.getIntExtra(EXTRA_PAGE, PAGE_MONITOR));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_PAGE, page);
    }

    /** 只在界面可见时订阅采样；悬浮窗开启时由悬浮窗单独订阅。 */
    @Override
    protected void onResume() {
        super.onResume();
        FloatService.stateListener = this::updateFloatBtn;
        updateFloatBtn();
        mon.addListener(this);
        if (pendingFloat) {
            pendingFloat = false;
            if (Settings.canDrawOverlays(this)) startFloat();
            else snack(L.s(R.string.float_perm_denied), false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mon.removeListener(this);
        FloatService.stateListener = null;
    }

    @Override
    public void onUpdate(Analyzer.Result r) {
        render(r);
    }

    @Override
    public void onStatus(String msg, boolean error) {
        banner(msg, error ? u.bad : u.warn);
        if (error) chip("");
    }

    /** 不在监控页时放慢采样（5 秒），只用于保持连接状态与导航提示点。 */
    @Override
    public boolean wantsFast() {
        return page == PAGE_MONITOR;
    }

    /** 只有主界面需要 LSP 模块自动归因；离开界面（只剩悬浮窗）后 root 端会停止采样。 */
    @Override
    public boolean wantsProfiling() {
        return true;
    }

    Monitor mon() {
        return mon;
    }

    /** 当前连接方式："root" / "shell" / ""（未连接）。 */
    String connMode() {
        return connMode;
    }

    // ------------------------------------------------------------------ 布局

    private View buildLayout() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(u.bg);
        frame.setFitsSystemWindows(true);
        LinearLayout rootView = u.col();
        frame.addView(rootView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout pageHost = new FrameLayout(this);
        rootView.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[PAGE_MONITOR] = buildMonitorPage();
        drainPage = new DrainPage(this, u);
        pages[PAGE_DRAIN] = drainPage.view;
        pages[PAGE_TOOLS] = buildToolsPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        for (View v : pages) {
            v.setVisibility(View.GONE);
            pageHost.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        rootView.addView(buildNav());

        // 轻提示（替代系统 Toast），位于底部导航上方
        snack = u.text("", 13, Color.WHITE, false);
        snack.setPadding(u.dp(16), u.dp(12), u.dp(16), u.dp(12));
        snack.setLineSpacing(u.dp(2), 1f);
        snack.setElevation(u.dp(6));
        snack.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(u.dp(16), 0, u.dp(16), u.dp(80));
        frame.addView(snack, sp);
        return frame;
    }

    // ------------------------------------------------------------------ 底部导航

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(u.card);
        nav.setPadding(u.dp(8), u.dp(6), u.dp(8), u.dp(6));
        nav.setElevation(u.dp(8));
        for (int i = 0; i < PAGE_NAMES.length; i++) {
            final int idx = i;
            LinearLayout item = u.col();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, u.dp(4), 0, u.dp(4));
            item.setBackground(u.ripple(Ui.round(Color.TRANSPARENT, u.dp(16))));
            FrameLayout pill = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            icon.setImageResource(PAGE_ICONS[i]);
            pill.addView(icon, new FrameLayout.LayoutParams(u.dp(22), u.dp(22), Gravity.CENTER));
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(u.bad);
            dd.setStroke(u.dp(1.5f), u.card);
            dot.setBackground(dd);
            dot.setVisibility(View.GONE);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(u.dp(9), u.dp(9), Gravity.TOP | Gravity.END);
            dp.setMargins(0, u.dp(3), u.dp(14), 0);
            pill.addView(dot, dp);
            item.addView(pill, new LinearLayout.LayoutParams(u.dp(60), u.dp(30)));
            TextView label = u.text(L.s(PAGE_NAMES[i]), 11, u.dim, true);
            item.addView(label, Ui.lp(-2, -2, 0, u.dp(3), 0, 0));
            item.setContentDescription(L.s(PAGE_NAMES[i]));
            item.setOnClickListener(v -> selectPage(idx));
            navItems[i] = new View[]{item, pill, icon, label, dot};
            nav.addView(item, Ui.weight(1));
        }
        return nav;
    }

    private void selectPage(int p) {
        if (p < 0 || p >= pages.length) p = PAGE_MONITOR;
        if (p == page) {
            // 再次点击当前页：回到顶部
            if (p == PAGE_MONITOR && list != null) list.smoothScrollToPosition(0);
            else if (pages[p] instanceof android.widget.ScrollView) ((android.widget.ScrollView) pages[p]).smoothScrollTo(0, 0);
            return;
        }
        int prev = page;
        page = p;
        for (int i = 0; i < pages.length; i++) {
            boolean sel = i == p;
            if (sel) {
                pages[i].setVisibility(View.VISIBLE);
                if (prev >= 0) {
                    pages[i].setAlpha(0f);
                    pages[i].setTranslationY(u.dp(8));
                    pages[i].animate().alpha(1f).translationY(0).setDuration(160).start();
                }
            } else {
                pages[i].setVisibility(View.GONE);
            }
            View[] n = navItems[i];
            n[1].setBackground(sel ? Ui.round(Ui.tint(u.accent), u.dp(15)) : null);
            ((ImageView) n[2]).setColorFilter(sel ? u.accent : u.dim);
            ((TextView) n[3]).setTextColor(sel ? u.accent : u.dim);
        }
        if (p == PAGE_DRAIN) drainPage.onShown();
        if (p == PAGE_SETTINGS) fillSettings(); // 每次进入重建，开关状态与其他页保持一致
        if (p == PAGE_TOOLS) refreshToolDescs();
        mon.kick(); // 采样间隔可能变化
    }

    private void navDot(int p, boolean on) {
        navItems[p][4].setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (page != PAGE_MONITOR) selectPage(PAGE_MONITOR);
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ 监控页

    private View buildMonitorPage() {
        // 头部（随列表一起滚动）
        LinearLayout header = u.col();
        header.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(6));

        // 顶栏：标题 / 设备 / 模式标签 / 悬浮窗
        LinearLayout top = u.row();
        LinearLayout titleCol = u.col();
        LinearLayout titleRow = u.row();
        titleRow.addView(u.text(L.s(R.string.app_name), 24, u.fg, true));
        modeChip = u.chip("", u.dim);
        titleRow.addView(modeChip, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));
        titleCol.addView(titleRow);
        headDevice = u.text(L.s(R.string.connecting), 12, u.dim, false);
        headDevice.setSingleLine(true);
        headDevice.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headDevice, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        headKernel = u.text("", 11, u.dim, false);
        headKernel.setSingleLine(true);
        headKernel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(headKernel);
        top.addView(titleCol, Ui.weight(1));
        floatBtn = u.iconButton(R.drawable.ic_float, L.s(R.string.float_channel), v -> toggleFloat());
        top.addView(floatBtn, Ui.lp(u.dp(42), u.dp(42), u.dp(8), 0, 0, 0));
        header.addView(top);
        chip("");

        // 温度 / 功耗卡片
        hero = u.row();
        hero.setPadding(u.dp(18), u.dp(16), u.dp(18), u.dp(18));
        hero.setBackground(Ui.gradient(Ui.GRAD_NONE, u.dp(24)));
        hero.setElevation(u.dp(2));
        LinearLayout hl = u.col();
        heroLabel = u.text(L.s(R.string.temp_soc), 12, 0xDDFFFFFF, false);
        heroTemp = u.text("--", 40, Color.WHITE, true);
        heroTemp.setFontFeatureSettings("tnum");
        heroTempSub = u.text("", 12, 0xDDFFFFFF, false);
        hl.addView(heroLabel);
        hl.addView(heroTemp);
        hl.addView(heroTempSub);
        hero.addView(hl, Ui.weight(1));
        LinearLayout hr = u.col();
        hr.setGravity(Gravity.END);
        heroPowerLabel = u.text(L.s(R.string.power_discharge), 12, 0xDDFFFFFF, false);
        heroPower = u.text("--", 26, Color.WHITE, true);
        heroPower.setFontFeatureSettings("tnum");
        heroPowerSub = u.text("", 12, 0xDDFFFFFF, false);
        hr.addView(heroPowerLabel);
        hr.addView(heroPower);
        hr.addView(heroPowerSub);
        hero.addView(hr, Ui.lp(-2, -2, u.dp(12), 0, 0, 0));
        header.addView(hero, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));

        // 负载卡片：CPU 各簇频率 + GPU
        LinearLayout load = u.col();
        load.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(14));
        load.setBackground(u.card(u.dp(20)));
        LinearLayout lt = u.row();
        lt.addView(u.text(L.s(R.string.realtime_load), 14, u.fg, true), Ui.weight(1));
        loadSummary = u.text("", 12, u.dim, false);
        loadSummary.setFontFeatureSettings("tnum");
        lt.addView(loadSummary);
        load.addView(lt);
        clusterRow = new LinearLayout(this);
        load.addView(clusterRow, Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
        header.addView(load, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));

        // 异常 / 一键清理卡片（取代原来固定在底部的大按钮）
        cleanCard = u.row();
        cleanCard.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        FrameLayout ib = new FrameLayout(this);
        cleanIcon = new ImageView(this);
        cleanIcon.setImageResource(R.drawable.ic_clean);
        ib.addView(cleanIcon, new FrameLayout.LayoutParams(u.dp(22), u.dp(22), Gravity.CENTER));
        cleanCard.addView(ib, Ui.lp(u.dp(40), u.dp(40), 0, 0, u.dp(12), 0));
        LinearLayout cc = u.col();
        cleanTitle = u.text(L.s(R.string.detecting), 15, u.fg, true);
        cleanSub = u.text("", 12, u.dim, false);
        cleanSub.setSingleLine(true);
        cleanSub.setEllipsize(TextUtils.TruncateAt.END);
        cc.addView(cleanTitle);
        cc.addView(cleanSub, Ui.lp(-1, -2, 0, u.dp(1), 0, 0));
        cleanCard.addView(cc, Ui.weight(1));
        cleanAction = u.text(L.s(R.string.clean_action), 13, Color.WHITE, true);
        cleanAction.setPadding(u.dp(14), u.dp(8), u.dp(14), u.dp(8));
        cleanCard.addView(cleanAction, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        cleanCard.setOnClickListener(v -> confirmClean());
        header.addView(cleanCard, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        paintCleanCard(0);

        // 提示条
        banner = u.note("", u.warn);
        banner.setTextSize(12);
        banner.setVisibility(View.GONE);
        header.addView(banner, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));

        // 分段式标签
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(u.dp(4), u.dp(4), u.dp(4), u.dp(4));
        tabs.setBackground(u.card(u.dp(16)));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = u.text(L.s(TABS[i]), 13, u.dim, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, u.dp(9), 0, u.dp(9));
            t.setOnClickListener(v -> {
                if (tab == idx) return;
                tab = idx;
                updateTabs();
                if (result != null) render(result);
            });
            tabViews[i] = t;
            tabs.addView(t, Ui.weight(1));
        }
        header.addView(tabs, Ui.lp(-1, -2, 0, u.dp(16), 0, u.dp(4)));
        updateTabs();

        // 自动归因状态（仅 LSP 模块页、root 模式）
        profStatus = u.note("", u.dim);
        profStatus.setTextSize(12);
        profStatus.setVisibility(View.GONE);
        header.addView(profStatus, Ui.lp(-1, -2, 0, u.dp(8), 0, u.dp(4)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT)); // 卡片自带水波纹
        list.setPadding(0, 0, 0, u.dp(12));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.addHeaderView(header, null, false);
        LinearLayout footer = new LinearLayout(this);
        emptyText = u.text(L.s(R.string.collecting), 13, u.dim, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(u.dp(24), u.dp(36), u.dp(24), u.dp(36));
        footer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof Entity) showDetail((Entity) o);
        });
        return list;
    }

    /** 异常卡片配色：有异常为红色，否则为绿色的「状态良好」。 */
    private void paintCleanCard(int bad) {
        int state = bad > 0 ? 1 : 0;
        if (state == lastCleanState) return;
        lastCleanState = state;
        int c = bad > 0 ? u.bad : u.good;
        GradientDrawable bgd = Ui.round(bad > 0 ? u.badCard : u.card, u.dp(20));
        bgd.setStroke(u.dp(1), bad > 0 ? Ui.alpha(u.bad, 0x55) : u.stroke);
        cleanCard.setBackground(u.ripple(bgd));
        ((View) cleanIcon.getParent()).setBackground(Ui.round(Ui.tint(c), u.dp(14)));
        cleanIcon.setColorFilter(c);
        cleanAction.setTextColor(bad > 0 ? Color.WHITE : u.fg);
        cleanAction.setBackground(Ui.round(bad > 0 ? u.bad : u.sunken, u.dp(16)));
    }

    // ------------------------------------------------------------------ 工具页

    private View buildToolsPage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = u.col();
        col.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(24));
        col.addView(u.text(L.s(R.string.page_tools), 24, u.fg, true));
        col.addView(u.text(L.s(R.string.tools_sub), 12, u.dim, false), Ui.lp(-1, -2, 0, u.dp(2), 0, 0));

        col.addView(u.sectionTitle(L.s(R.string.sec_app_compat)), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
        toolVpnDesc = u.text(vpnHideSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_vpn, u.good, L.s(R.string.vpn_hide), toolVpnDesc, this::openVpnHidePicker),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolCompatDesc = u.text(L.s(R.string.compat_checking), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_compat, 0xFFF59E0B, L.s(R.string.compat_title), toolCompatDesc, this::openCompatSheet),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolShotDesc = u.text(shotSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_shot, 0xFF10B981, L.s(R.string.shot_title), toolShotDesc, this::openShotPicker),
                Ui.lp(-1, -2, 0, 0, 0, 0));

        col.addView(u.sectionTitle(L.s(R.string.sec_display)), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
        toolRefreshDesc = u.text(refreshSummary(), 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_refresh, 0xFF8B5CF6, L.s(R.string.refresh_title), toolRefreshDesc, this::openRefreshSheet),
                Ui.lp(-1, -2, 0, 0, 0, u.dp(10)));
        toolFloatDesc = u.text("", 12, u.dim, false);
        col.addView(menuCard(R.drawable.ic_float, 0xFF0EA5E9, L.s(R.string.float_channel), toolFloatDesc, this::toggleFloat),
                Ui.lp(-1, -2, 0, 0, 0, 0));

        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private void refreshToolDescs() {
        toolVpnDesc.setText(vpnHideSummary());
        toolShotDesc.setText(shotSummary());
        toolRefreshDesc.setText(refreshSummary());
        updateFloatBtn();
        CompatFix.query((ok, msg, st) -> toolCompatDesc.setText(ok ? CompatFix.desc(st) : L.s(R.string.need_root_tap)));
    }

    private String refreshSummary() {
        int fps = prefs.getInt("lock_fps", 0);
        return fps > 0 ? L.s(R.string.refresh_locked_sum, fps) : L.s(R.string.refresh_auto_sum);
    }

    /** 菜单卡片：左侧彩色图标 + 标题 + 描述 + 右箭头。desc 由调用方传入以便动态更新。 */
    private View menuCard(int icon, int tint, String title, TextView desc, Runnable onClick) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(16), u.dp(15), u.dp(16), u.dp(15));
        r.setBackground(u.ripple(u.card(u.dp(18))));
        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(Ui.round(Ui.tint(tint), u.dp(14)));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(tint);
        ib.addView(iv, new FrameLayout.LayoutParams(u.dp(24), u.dp(24), Gravity.CENTER));
        r.addView(ib, Ui.lp(u.dp(46), u.dp(46), 0, 0, u.dp(14), 0));
        LinearLayout c = u.col();
        c.addView(u.text(title, 16, u.fg, true));
        desc.setLineSpacing(u.dp(1), 1f);
        c.addView(desc, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        r.addView(c, Ui.weight(1));
        r.addView(u.text("›", 22, u.dim, true), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setText(tabCounts[i] > 0 ? L.s(TABS[i]) + " " + tabCounts[i] : L.s(TABS[i]));
            tabViews[i].setTextColor(sel ? Color.WHITE : u.dim);
            tabViews[i].setBackground(sel ? Ui.round(u.accent, u.dp(12)) : null);
        }
    }

    private void banner(String msg, int color) {
        banner.setText(msg);
        banner.setTextColor(color);
        banner.setBackground(Ui.round(Ui.tint(color), u.dp(14)));
        banner.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 顶栏模式标签：root / shell / 未连接。 */
    private void chip(String mode) {
        int col = "root".equals(mode) ? u.good : "shell".equals(mode) ? u.accent : u.dim;
        modeChip.setText("root".equals(mode) ? "ROOT" : "shell".equals(mode) ? "SHIZUKU" : L.s(R.string.not_connected));
        modeChip.setTextColor(col);
        modeChip.setBackground(Ui.round(Ui.tint(col), u.dp(10)));
    }

    void snack(String msg, boolean ok) {
        snack.setText(msg);
        snack.setBackground(Ui.round(ok ? (u.night ? 0xFF2A2F36 : 0xFF1F2328) : u.bad, u.dp(14)));
        ui.removeCallbacks(hideSnack);
        snack.animate().cancel();
        snack.setVisibility(View.VISIBLE);
        snack.setAlpha(0f);
        snack.setTranslationY(20f);
        snack.animate().alpha(1f).translationY(0f).setDuration(200).start();
        ui.postDelayed(hideSnack, 2800);
    }

    // ------------------------------------------------------------------ 渲染

    private void render(Analyzer.Result r) {
        result = r;
        JSONObject s = r.snap;
        String mode = s.optString("mode");
        connMode = mode;
        boolean shell = "shell".equals(mode);
        chip(mode);
        JSONObject dev = s.optJSONObject("device");
        if (dev != null) {
            headDevice.setText(dev.optString("model") + " · " + dev.optString("soc") + " · Android " + dev.optString("android"));
            headKernel.setText(L.s(R.string.kernel_label, dev.optString("kernel")) + (shell ? "" : " · " + s.optString("rootImpl")));
        }

        // 温度卡片
        Object[] mt = Ui.mainTemp(r);
        double mainTemp = (double) mt[0];
        boolean hasSoc = r.maxTemp > -999, hasSkin = r.skinTemp > -999;
        heroLabel.setText((String) mt[1]);
        heroTemp.setText(mainTemp > -999 ? Analyzer.fmt(mainTemp) + "°C" : "--");
        List<String> tsub = new ArrayList<>();
        if (hasSoc && hasSkin) tsub.add(L.s(R.string.temp_skin_short, Analyzer.fmt(r.skinTemp)));
        if ((hasSoc || hasSkin) && r.batteryTemp > -999) tsub.add(L.s(R.string.temp_bat_short, Analyzer.fmt(r.batteryTemp)));
        heroTempSub.setText(TextUtils.join("  ·  ", tsub));
        double hot = Math.max(r.maxTemp, r.skinTemp);
        if (hot <= -999) hot = r.batteryTemp;
        int[] grad = Ui.heatGradient(hot);
        if (grad != lastGrad) {
            lastGrad = grad;
            hero.setBackground(Ui.gradient(grad, u.dp(24)));
        }
        JSONObject bat = s.optJSONObject("battery");
        if (bat != null) {
            long ma = Math.abs(bat.optLong("currentMa"));
            long lv = bat.optLong("level", -1);
            String lvs = lv >= 0 ? lv + "%" : "--";
            if (r.discharging) {
                heroPowerLabel.setText(L.s(R.string.power_discharge));
                heroPower.setText(String.format(Locale.US, "%.2f W", bat.optLong("powerMw") / 1000.0));
                heroPowerSub.setText(ma + " mA · " + L.s(R.string.battery_level, lvs));
            } else {
                String st = bat.optString("status");
                heroPowerLabel.setText(L.s("Full".equalsIgnoreCase(st) ? R.string.bat_full : st.isEmpty() ? R.string.bat_battery : R.string.bat_charging));
                heroPower.setText(ma > 0 ? ma + " mA" : "--");
                heroPowerSub.setText(L.s(R.string.battery_level, lvs));
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
            if (gpu.has("mhz")) g += " " + Ui.freq(gpu.optLong("mhz"));
            ls.add(g);
        }
        loadSummary.setText(TextUtils.join("  ·  ", ls));
        renderClusters(cpu == null ? null : cpu.optJSONArray("policies"));

        List<String> notes = new ArrayList<>();
        if (shell) {
            notes.add(L.s(R.string.note_shizuku));
        } else if (!ModuleStatus.isActive()) {
            notes.add(L.s(R.string.note_lsp_inactive));
        } else if (!r.hookOk) {
            notes.add(r.hookAgeSec < 0 ? L.s(R.string.note_hook_inactive) : L.s(R.string.note_hook_stale, r.hookAgeSec));
        }
        JSONObject lsp = s.optJSONObject("lsp");
        if (!shell && lsp != null && !lsp.optBoolean("available") && lsp.has("error")) {
            notes.add(L.s(R.string.note_lsposed, lsp.optString("error")));
        }
        if (!shell && r.prof != null && !r.prof.optBoolean("ok") && mon.autoProfEnabled()) {
            notes.add(L.s(R.string.note_prof_unavail, r.prof.optString("error")));
        }
        if (notes.isEmpty()) banner.setVisibility(View.GONE);
        else banner(TextUtils.join("\n", notes), shell ? u.accent : u.warn);

        paintCleanCard(r.badCount);
        if (r.badCount > 0) {
            cleanTitle.setText(L.s(R.string.clean_found, r.badCount));
            cleanSub.setText(cleanPreview(r));
        } else {
            cleanTitle.setText(L.s(R.string.clean_none));
            cleanSub.setText(L.s(R.string.clean_none_sub));
        }
        navDot(PAGE_MONITOR, r.badCount > 0);
        navDot(PAGE_DRAIN, drainPage.hasBad());
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
        profStatus.setVisibility(tab == 2 && !shell ? View.VISIBLE : View.GONE);
        if (tab == 2 && !shell) profStatus.setText(profStatusText(r));
        emptyText.setText(emptyMessage(shell));
        emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 异常卡片的副标题：列出前几个异常对象的名称。 */
    private String cleanPreview(Analyzer.Result r) {
        List<String> names = new ArrayList<>();
        for (List<Entity> l : java.util.Arrays.asList(r.heat, r.ksu, r.lsp)) {
            for (Entity e : l) {
                if (e.level == Analyzer.BAD && names.size() < 3 && !names.contains(u.label(e))) names.add(u.label(e));
            }
        }
        if (names.isEmpty()) return L.s(R.string.clean_tap);
        String joined = TextUtils.join(L.s(R.string.list_sep), names);
        return r.badCount > names.size() ? L.s(R.string.name_and_more, joined) : joined;
    }

    private static int flagged(List<Entity> l) {
        int n = 0;
        for (Entity e : l) if (e.level != Analyzer.OK) n++;
        return n;
    }

    private String emptyMessage(boolean shell) {
        switch (tab) {
            case 1: return L.s(shell ? R.string.empty_ksu_shell : R.string.empty_ksu);
            case 2: return L.s(shell ? R.string.empty_lsp_shell : R.string.empty_lsp);
            case 3: return L.s(shell ? R.string.empty_wake_shell : R.string.empty_wake);
            default: return L.s(R.string.empty_heat);
        }
    }

    /** CPU 各簇：当前频率、频率条、限频提示。 */
    private void renderClusters(JSONArray pol) {
        int n = pol == null ? 0 : pol.length();
        if (clusterRow.getChildCount() != n) {
            clusterRow.removeAllViews();
            for (int i = 0; i < n; i++) {
                LinearLayout t = u.col();
                t.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(9));
                t.setBackground(Ui.round(u.sunken, u.dp(12)));
                TextView name = u.text("", 11, u.dim, false);
                name.setSingleLine(true);
                TextView val = u.text("", 15, u.fg, true);
                val.setFontFeatureSettings("tnum");
                val.setSingleLine(true);
                Ui.Bar bar = new Ui.Bar(this, u.track);
                TextView lim = u.text("", 10, u.dim, false);
                lim.setSingleLine(true);
                t.addView(name);
                t.addView(val);
                t.addView(bar, Ui.lp(-1, u.dp(4), 0, u.dp(6), 0, u.dp(5)));
                t.addView(lim);
                t.setTag(new Object[]{name, val, bar, lim});
                LinearLayout.LayoutParams lp = Ui.weight(1);
                if (i > 0) lp.leftMargin = u.dp(8);
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
            ((TextView) v[0]).setText("CPU " + Ui.cpuRange(o.optString("cpus")));
            ((TextView) v[1]).setText(Ui.freq(cur));
            ((Ui.Bar) v[2]).set(max > 0 ? cur / (float) max : 0, throttled ? u.warn : u.accent);
            TextView lim = (TextView) v[3];
            lim.setText(throttled ? L.s(R.string.cpu_throttled, Ui.freq(limit)) : L.s(R.string.cpu_max, Ui.freq(max)));
            lim.setTextColor(throttled ? u.warn : u.dim);
        }
    }

    /** 实测明细里按进程的分布，如 "system_server 3.1% · 微信 0.4%"。 */
    private String measuredWhere(Entity e, int max) {
        JSONObject procs = e.measured == null ? null : e.measured.optJSONObject("procs");
        if (procs == null || procs.length() == 0) return "";
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = procs.keys(); it.hasNext(); ) keys.add(it.next());
        java.util.Collections.sort(keys, (a, b) -> Double.compare(procs.optDouble(b), procs.optDouble(a)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < keys.size() && i < max; i++) {
            out.add(procName(keys.get(i)) + " " + Analyzer.fmt(procs.optDouble(keys.get(i))) + "%");
        }
        return TextUtils.join(" · ", out);
    }

    /** 进程名转成可读名称：应用主进程用应用名。 */
    private String procName(String proc) {
        String pkg = proc.contains(":") ? proc.substring(0, proc.indexOf(':')) : proc;
        Object[] info = u.appInfo(pkg);
        if (info[0] == null) return proc;
        return proc.contains(":") ? info[0] + proc.substring(proc.indexOf(':')) : (String) info[0];
    }

    /** LSP 模块的作用域里是否有正在运行的普通应用（不含 system_server）。 */
    private static boolean hasAppHost(Entity e) {
        return e.scopeRunning > (e.injectsSystem && e.systemCpu >= 0 ? 1 : 0);
    }

    /** 列表卡片的次要信息（主数值已在右侧大字显示）。 */
    private String metrics(Entity e) {
        if (tab == 3) {
            String s = L.s(R.string.m_wake_total, Ui.dur(e.wlHeldTotalMs), e.alarmsTotal);
            if (e.wlTag != null && !e.wlTag.isEmpty()) s += "\n" + L.s(R.string.m_lock, e.wlTag);
            return s;
        }
        List<String> p = new ArrayList<>();
        if (e.type == Analyzer.LSP) {
            p.add(e.sub);
            String where = measuredWhere(e, 2);
            if (!where.isEmpty()) p.add(where);
            if (!e.pids.isEmpty()) p.add(L.s(R.string.m_self_cpu, Analyzer.fmt(e.cpuAvg)));
            return TextUtils.join(" · ", p);
        }
        if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
            p.add(L.s(R.string.m_injected, Analyzer.fmt(e.measuredCpu), measuredWhere(e, 2)));
        }
        if (e.powerMw > 0) p.add("≈" + e.powerMw + "mW");
        if (e.rssKb > 0) p.add(L.s(R.string.m_mem, e.rssKb / 1024));
        if (e.type == Analyzer.KSU) p.add(L.s(R.string.m_procs, e.pids.size()));
        else if (e.pids.size() > 1) p.add(L.s(R.string.m_n_procs, e.pids.size()));
        else if (e.pids.size() == 1) p.add("pid " + e.pids.get(0));
        if (e.type == Analyzer.APP && e.wlFrac > 0.05) p.add(L.s(R.string.m_wl, Math.round(e.wlFrac * 100)));
        if (e.type == Analyzer.APP && e.minAdj <= 1000) p.add(Ui.adjName(e.minAdj));
        return TextUtils.join(" · ", p);
    }

    // ------------------------------------------------------------------ 详情与操作

    private void showDetail(Entity e) {
        Sheet s = new Sheet(this, u);
        String name = u.label(e);

        // 头部：头像 + 名称 + 类型 + 状态
        LinearLayout head = u.row();
        FrameLayout av = u.avatar(52);
        u.bindAvatar(av, e, name);
        head.addView(av, Ui.lp(u.dp(52), u.dp(52), 0, 0, u.dp(14), 0));
        LinearLayout tc = u.col();
        TextView title = u.text(name, 19, u.fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        tc.addView(title);
        TextView sub = u.text(Ui.typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title), 12, u.dim, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tc.addView(sub, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        LinearLayout chips = u.row();
        if (e.level != Analyzer.OK) chips.addView(u.chip(L.s(e.level == Analyzer.BAD ? R.string.level_bad : R.string.level_warn), u.levelColor(e.level)));
        if (!e.enabled) chips.addView(u.chip(L.s(R.string.tag_disabled), u.dim), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (e.isProtected) chips.addView(u.chip(L.s(R.string.chip_protected), u.accent), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (chips.getChildCount() > 0) tc.addView(chips, Ui.lp(-2, -2, 0, u.dp(6), 0, 0));
        head.addView(tc, Ui.weight(1));
        s.add(head, Ui.lp(-1, -2, 0, 0, 0, 0));

        // 数据块
        s.add(statGrid(stats(e)), Ui.lp(-1, -2, 0, u.dp(16), 0, 0));

        if (!e.reasons.isEmpty() || !e.warnings.isEmpty()) {
            s.section(L.s(R.string.sec_diagnosis));
            for (String r : e.reasons) s.add(u.note(r, u.bad));
            for (String w : e.warnings) s.add(u.note(w, u.warn));
        }
        if (!e.tags.isEmpty()) {
            s.section(L.s(R.string.sec_tags));
            s.add(u.text(TextUtils.join(" · ", e.tags), 13, u.fg, false));
        }
        if (tab == 3 && e.wlTag != null && !e.wlTag.isEmpty()) {
            s.section(L.s(R.string.sec_wakelock));
            s.add(u.text(e.wlTag, 13, u.fg, false));
        }
        if (e.type == Analyzer.KSU && e.raw != null) {
            s.section(L.s(R.string.sec_module_info));
            String desc = e.raw.optString("desc");
            if (!desc.isEmpty()) s.add(u.text(desc, 13, u.fg, false));
            List<String> scripts = new ArrayList<>();
            if (e.raw.optBoolean("service")) scripts.add("service.sh");
            if (e.raw.optBoolean("postfs")) scripts.add("post-fs-data.sh");
            if (e.raw.optBoolean("sysprop")) scripts.add("system.prop");
            if (!scripts.isEmpty()) s.add(u.text(L.s(R.string.scripts_label, TextUtils.join(L.s(R.string.list_sep), scripts)), 13, u.dim, false));
        }
        if ((e.type == Analyzer.LSP || e.type == Analyzer.KSU) && e.measured != null) {
            JSONObject procs = e.measured.optJSONObject("procs");
            if (procs != null && procs.length() > 0) {
                s.section(L.s(R.string.sec_measured_dist));
                JSONArray arr = new JSONArray();
                for (java.util.Iterator<String> it = procs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    try {
                        arr.put(new JSONObject().put("name", procName(k)).put("cpu", procs.optDouble(k)));
                    } catch (Exception ignored) {
                    }
                }
                s.add(listBox(arr, 6, "cpu"));
            }
            JSONArray methods = e.measured.optJSONArray("methods");
            if (methods != null && methods.length() > 0) {
                s.section(L.s(R.string.sec_top_methods));
                s.add(listBox(methods, 5, "cpu"));
            }
        }
        if (e.type == Analyzer.LSP) {
            s.add(u.note(L.s(e.measuredCpu >= 0 ? R.string.note_measured : R.string.note_no_measured), u.dim),
                    Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        }
        if (e.type == Analyzer.LSP && !e.scopes.isEmpty()) {
            s.section(L.s(R.string.sec_scope, e.scopes.size()));
            String sc = TextUtils.join(L.s(R.string.list_sep), e.scopes.subList(0, Math.min(40, e.scopes.size())));
            s.add(u.text(e.scopes.size() > 40 ? sc + " …" : sc, 13, u.fg, false));
        }
        if (!e.pids.isEmpty()) {
            s.section(L.s(R.string.sec_processes, e.pids.size()));
            LinearLayout box = u.col();
            box.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8));
            box.setBackground(Ui.round(u.sunken, u.dp(14)));
            for (int i = 0; i < e.pids.size() && i < 20; i++) {
                LinearLayout r = u.row();
                TextView pid = u.text(String.valueOf(e.pids.get(i)), 12, u.dim, false);
                pid.setTypeface(Typeface.MONOSPACE);
                r.addView(pid, Ui.lp(u.dp(56), -2, 0, 0, 0, 0));
                TextView pn = u.text(e.pidNames.get(i), 12, u.fg, false);
                pn.setSingleLine(true);
                pn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                r.addView(pn, Ui.weight(1));
                box.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(4) : 0, 0, 0));
            }
            s.add(box);
        }
        if (e.isProtected) s.add(u.note(L.s(R.string.note_protected), u.accent),
                Ui.lp(-1, -2, 0, u.dp(14), 0, 0));

        // 操作
        if (!e.isProtected && !e.isSelf) {
            switch (e.type) {
                case Analyzer.APP:
                    s.action(L.s(R.string.act_force_stop), Sheet.PRIMARY, () -> run("FORCESTOP " + e.pkg + " " + e.user));
                    s.action(L.s(R.string.act_disable_app), Sheet.DANGER, () -> confirm(L.s(R.string.confirm_disable_title, name),
                            L.s(R.string.disable_app_msg), L.s(R.string.act_disable),
                            () -> run("APP_DISABLE " + e.pkg + " " + e.user)));
                    break;
                case Analyzer.NATIVE:
                    s.action(L.s(R.string.act_kill_proc), Sheet.DANGER, () -> runAll(killCmds(e)));
                    break;
                case Analyzer.KSU:
                    if (!e.pids.isEmpty()) {
                        s.action(L.s(R.string.act_kill_module_procs), Sheet.PRIMARY, () -> run("KSU_KILL " + e.ksuId));
                    }
                    if (e.enabled) {
                        s.action(L.s(R.string.act_disable_module), Sheet.DANGER, () -> confirm(L.s(R.string.confirm_disable_ksu, e.title),
                                L.s(R.string.disable_ksu_msg), L.s(R.string.act_disable),
                                () -> run("KSU_DISABLE " + e.ksuId)));
                    } else {
                        s.action(L.s(R.string.act_reenable_module), Sheet.PRIMARY, () -> run("KSU_ENABLE " + e.ksuId));
                    }
                    break;
                case Analyzer.LSP:
                    if (e.enabled) {
                        s.action(L.s(R.string.act_lsp_disable), Sheet.DANGER, () -> confirm(L.s(R.string.confirm_disable_lsp, name),
                                L.s(R.string.disable_lsp_msg), L.s(R.string.act_disable),
                                () -> run("LSP_DISABLE " + e.lspPkg)));
                    } else {
                        s.action(L.s(R.string.act_lsp_enable), Sheet.PRIMARY, () -> run("LSP_ENABLE " + e.lspPkg));
                    }
                    if (!e.pids.isEmpty()) {
                        s.action(L.s(R.string.act_stop_module_app), Sheet.NORMAL, () -> run("FORCESTOP " + e.lspPkg + " 0"));
                    }
                    break;
            }
        }
        if (e.type == Analyzer.APP) {
            s.action(L.s(R.string.act_reenable_app), Sheet.NORMAL, () -> run("APP_ENABLE " + e.pkg + " " + e.user));
        }
        s.action(L.s(R.string.close), Sheet.NORMAL, null);
        s.show();
    }

    /** 详情页的数据块：{标题, 数值, 颜色}。 */
    private List<Object[]> stats(Entity e) {
        List<Object[]> l = new ArrayList<>();
        int lc = e.level == Analyzer.OK ? u.fg : u.levelColor(e.level);
        if (tab == 3) {
            l.add(new Object[]{L.s(R.string.st_wl_2min), e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", lc});
            l.add(new Object[]{L.s(R.string.st_alarms), e.alarmsPerMin >= 0 ? L.s(R.string.per_min, Analyzer.fmt(e.alarmsPerMin)) : "--", u.fg});
            l.add(new Object[]{L.s(R.string.st_wl_cum), Ui.dur(e.wlHeldTotalMs), u.fg});
            l.add(new Object[]{L.s(R.string.st_alarms_cum), L.s(R.string.times, e.alarmsTotal), u.fg});
            return l;
        }
        if (e.type == Analyzer.LSP) {
            if (e.measuredCpu >= 0) l.add(new Object[]{L.s(R.string.st_module_measured), Analyzer.fmt(e.measuredCpu) + "%", lc});
            l.add(new Object[]{L.s(R.string.st_host_cpu), hasAppHost(e) ? Analyzer.fmt(e.scopeCpu) + "%" : "--", u.fg});
            l.add(new Object[]{"system_server", e.systemCpu >= 0 ? Analyzer.fmt(e.systemCpu) + "%" : "--", u.dim});
            l.add(new Object[]{L.s(R.string.st_module_app), e.pids.isEmpty() ? L.s(R.string.not_running) : Analyzer.fmt(e.cpuAvg) + "%", u.fg});
            return l;
        }
        if (e.type == Analyzer.KSU && e.measuredCpu >= 0) {
            l.add(new Object[]{L.s(R.string.st_injected_measured), Analyzer.fmt(e.measuredCpu) + "%", lc});
            if (e.pids.isEmpty()) {
                // 只有注入代码、没有自己进程的模块（如 Zygisk 模块），进程相关的数据块没有意义
                l.add(new Object[]{L.s(R.string.st_module_own_proc), L.s(R.string.not_running), u.fg});
                return l;
            }
        }
        boolean ownCpuFlagged = e.type != Analyzer.KSU || e.cpuAvg >= 5;
        l.add(new Object[]{L.s(e.type == Analyzer.KSU ? R.string.st_module_proc_cpu : R.string.st_cpu_15s), Analyzer.fmt(e.cpuAvg) + "%",
                ownCpuFlagged ? lc : u.fg});
        l.add(new Object[]{L.s(R.string.st_est_power), e.powerMw > 0 ? e.powerMw + " mW" : "--", u.fg});
        l.add(new Object[]{L.s(R.string.comp_memory), e.rssKb > 0 ? e.rssKb / 1024 + " MB" : "--", u.fg});
        l.add(new Object[]{L.s(R.string.st_proc_count), String.valueOf(e.pids.size()), u.fg});
        if (e.type == Analyzer.APP) {
            l.add(new Object[]{L.s(R.string.st_state), e.minAdj <= 1000 ? Ui.adjName(e.minAdj) : "--", u.fg});
            l.add(new Object[]{L.s(R.string.st_wakelock), e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", u.fg});
        }
        return l;
    }

    /** 每行最多 3 个数据块。 */
    View statGrid(List<Object[]> stats) {
        LinearLayout grid = u.col();
        LinearLayout row = null;
        for (int i = 0; i < stats.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                grid.addView(row, Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            }
            Object[] st = stats.get(i);
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i % 3 > 0) lp.leftMargin = u.dp(8);
            row.addView(u.tile((String) st[0], (String) st[1], (int) st[2]), lp);
        }
        // 补齐最后一行，保持宽度一致
        while (row != null && row.getChildCount() < 3) {
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.leftMargin = u.dp(8);
            row.addView(new View(this), lp);
        }
        return grid;
    }

    private static List<String> killCmds(Entity e) {
        List<String> cmds = new ArrayList<>();
        for (int i = 0; i < e.pids.size(); i++) {
            String n = e.pidNames.get(i).split(" ")[0];
            cmds.add("KILL " + e.pids.get(i) + " " + n);
        }
        return cmds;
    }

    void confirm(String title, String msg, String okLabel, Runnable ok) {
        new Sheet(this, u).title(title, msg)
                .action(okLabel, Sheet.DANGER_FILLED, ok)
                .action(L.s(R.string.cancel), Sheet.NORMAL, null)
                .show();
    }

    void run(String cmd) {
        List<String> l = new ArrayList<>();
        l.add(cmd);
        runAll(l);
    }

    private void runAll(List<String> cmds) {
        mon.runAll(cmds, (msgs, allOk) -> {
            String joined = TextUtils.join("\n", msgs);
            // 短消息用轻提示；多条或较长的错误（如需截图反馈的信息）用面板，不会自动消失
            if (msgs.size() <= 2 && (allOk || (joined.length() < 60 && !joined.contains("\n")))) {
                snack(joined, allOk);
                return;
            }
            int okN = 0;
            for (String m : msgs) if (m.startsWith("✓")) okN++;
            Sheet s = new Sheet(this, u).title(L.s(R.string.result_title), L.s(R.string.result_sub, okN, msgs.size() - okN));
            LinearLayout box = u.col();
            box.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            box.setBackground(Ui.round(u.sunken, u.dp(14)));
            for (int i = 0; i < msgs.size(); i++) {
                String m = msgs.get(i);
                TextView t = u.text(m, 13, m.startsWith("✓") ? u.good : u.bad, false);
                box.addView(t, Ui.lp(-1, -2, 0, i > 0 ? u.dp(6) : 0, 0, 0));
            }
            s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
            s.action(L.s(R.string.ok), Sheet.PRIMARY, null).show();
        });
    }

    // ------------------------------------------------------------------ 深度分析

    /** LSP 页顶部的自动归因状态说明。 */
    private String profStatusText(Analyzer.Result r) {
        if (!mon.autoProfEnabled()) return L.s(R.string.prof_off);
        JSONObject p = r.prof;
        if (p == null) return L.s(R.string.prof_preparing);
        if (!p.optBoolean("ok")) return L.s(R.string.prof_unavailable);
        long age = Math.max(0, (r.snap.optLong("t") - p.optLong("time")) / 1000);
        String when = age < 5 ? L.s(R.string.just_updated) : L.s(R.string.secs_ago_updated, age);
        if (p.has("idle")) return L.s(R.string.prof_idle, p.optString("idle"), when);
        JSONArray t = p.optJSONArray("targets");
        List<String> names = new ArrayList<>();
        if (t != null) for (int i = 0; i < t.length(); i++) names.add(t.optString(i));
        return L.s(R.string.prof_sampling, TextUtils.join(L.s(R.string.list_sep), names), when, p.optInt("period", 30));
    }

    /** {name, valueKey} 列表，数值显示为百分比。 */
    private View listBox(JSONArray arr, int max, String valueKey) {
        LinearLayout box = u.col();
        box.setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8));
        box.setBackground(Ui.round(u.sunken, u.dp(14)));
        for (int i = 0; i < arr.length() && i < max; i++) {
            JSONObject o = arr.optJSONObject(i);
            LinearLayout r = u.row();
            TextView n = u.text(o.optString("name"), 12, u.fg, false);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.START);
            r.addView(n, Ui.weight(1));
            TextView v = u.text(Analyzer.fmt(o.optDouble(valueKey)) + "%", 12, u.dim, false);
            v.setFontFeatureSettings("tnum");
            r.addView(v, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            box.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(5) : 0, 0, 0));
        }
        return box;
    }

    // ------------------------------------------------------------------ 一键清理

    private void confirmClean() {
        if (result == null) return;
        boolean includeFg = prefs.getBoolean("clean_foreground", false);
        boolean disableModules = prefs.getBoolean("clean_disable_modules", false);
        List<Entity> targets = Analyzer.cleanTargets(result, includeFg);
        boolean shell = "shell".equals(result.snap.optString("mode"));
        if (targets.isEmpty()) {
            snack(L.s(R.string.clean_nothing), true);
            return;
        }
        List<String> cmds = new ArrayList<>();
        LinearLayout box = u.col();
        for (Entity e : targets) {
            String act;
            int actColor;
            switch (e.type) {
                case Analyzer.APP:
                    act = L.s(R.string.act_force_stop);
                    actColor = u.accent;
                    cmds.add("FORCESTOP " + e.pkg + " " + e.user);
                    break;
                case Analyzer.NATIVE:
                    if (shell) {
                        act = L.s(R.string.act_skip);
                        actColor = u.dim;
                    } else {
                        act = L.s(R.string.act_kill_proc);
                        actColor = u.bad;
                        cmds.addAll(killCmds(e));
                    }
                    break;
                case Analyzer.KSU:
                    act = L.s(disableModules ? R.string.act_disable_module_short : R.string.act_kill_proc);
                    actColor = u.bad;
                    cmds.add(disableModules ? "KSU_DISABLE " + e.ksuId : "KSU_KILL " + e.ksuId);
                    break;
                default:
                    act = L.s(disableModules ? R.string.act_disable_module_short : R.string.act_stop_proc);
                    actColor = disableModules ? u.bad : u.accent;
                    cmds.add(disableModules ? "LSP_DISABLE " + e.lspPkg : "FORCESTOP " + e.lspPkg + " 0");
            }
            String name = u.label(e);
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            FrameLayout av = u.avatar(36);
            u.bindAvatar(av, e, name);
            r.addView(av, Ui.lp(u.dp(36), u.dp(36), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView n = u.text(name, 14, u.fg, true);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(n);
            String why = e.reasons.isEmpty() ? Ui.typeName(e) : e.reasons.get(0);
            TextView w = u.text(why, 12, u.dim, false);
            w.setSingleLine(true);
            w.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(w);
            r.addView(c, Ui.weight(1));
            r.addView(u.chip(act, actColor), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            box.addView(r, Ui.lp(-1, -2, 0, box.getChildCount() > 0 ? u.dp(8) : 0, 0, 0));
        }
        String sub = L.s(shell ? R.string.clean_sub_shell : R.string.clean_sub);
        Sheet s = new Sheet(this, u).title(L.s(R.string.clean_title, targets.size()), sub);
        s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
        if (!cmds.isEmpty()) s.action(L.s(R.string.clean_now), Sheet.DANGER_FILLED, () -> runAll(cmds));
        s.action(L.s(R.string.cancel), Sheet.NORMAL, null).show();
    }

    // ------------------------------------------------------------------ 设置

    private View buildSettingsPage() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = u.col();
        col.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(24));
        col.addView(u.text(L.s(R.string.page_settings), 24, u.fg, true));
        settingsBody = u.col();
        col.addView(settingsBody, new LinearLayout.LayoutParams(-1, -2));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        return sv;
    }

    private void settingsSection(String title) {
        settingsBody.addView(u.sectionTitle(title), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
    }

    /** 重建设置页内容（每次进入设置页时调用）。 */
    private void fillSettings() {
        LinearLayout s = settingsBody;
        s.removeAllViews();

        settingsSection(L.s(R.string.set_language));
        s.addView(languageRow());

        settingsSection(L.s(R.string.set_work_mode));
        String conn = L.s("root".equals(connMode) ? R.string.conn_root : "shell".equals(connMode) ? R.string.conn_shizuku : R.string.conn_none);
        List<View[]> opts = new ArrayList<>();
        for (int i = 0; i < Monitor.MODES.length; i++) {
            final String mode = Monitor.MODES[i];
            LinearLayout r = u.row();
            r.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
            View ind = new View(this);
            r.addView(ind, Ui.lp(u.dp(20), u.dp(20), 0, 0, u.dp(12), 0));
            LinearLayout c = u.col();
            c.addView(u.text(L.s(MODE_NAMES[i]), 15, u.fg, true));
            TextView d = u.text(L.s(MODE_DESCS[i]), 12, u.dim, false);
            d.setLineSpacing(u.dp(1), 1f);
            c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
            r.addView(c, Ui.weight(1));
            opts.add(new View[]{r, ind});
            s.addView(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            r.setTag(mode);
        }
        Runnable paint = () -> {
            String m = prefs.getString("work_mode", "auto");
            for (View[] o : opts) {
                boolean sel = m.equals(o[0].getTag());
                GradientDrawable g = sel ? Ui.round(Ui.tint(u.accent), u.dp(16)) : u.card(u.dp(16));
                if (sel) g.setStroke(u.dp(1), Ui.alpha(u.accent, 0x99));
                o[0].setBackground(u.ripple(g));
                o[1].setBackground(radio(sel));
            }
        };
        for (View[] o : opts) {
            o[0].setOnClickListener(v -> {
                String m = (String) v.getTag();
                if (m.equals(prefs.getString("work_mode", "auto"))) return;
                prefs.edit().putString("work_mode", m).apply();
                paint.run();
                mon.reconnect(); // 关闭当前会话，按新模式重连
                snack(L.s(R.string.mode_switched, L.s(MODE_NAMES[java.util.Arrays.asList(Monitor.MODES).indexOf(m)])), true);
            });
        }
        paint.run();
        s.addView(u.text(conn, 12, u.dim, false), Ui.lp(-1, -2, u.dp(4), u.dp(8), 0, 0));

        settingsSection(L.s(R.string.dp_title));
        s.addView(switchRow(L.s(R.string.watch_title), L.s(R.string.set_watch_desc),
                DrainWatcher.enabled(this), on -> DrainWatcher.setEnabled(this, on)));

        settingsSection(L.s(R.string.set_sec_lsp));
        s.addView(switchRow(L.s(R.string.set_autoprof), L.s(R.string.set_autoprof_desc),
                mon.autoProfEnabled(), on -> mon.setAutoProf(on)));

        settingsSection(L.s(R.string.clean_action));
        s.addView(switchRow(L.s(R.string.set_clean_fg), L.s(R.string.set_clean_fg_desc),
                prefs.getBoolean("clean_foreground", false),
                on -> prefs.edit().putBoolean("clean_foreground", on).apply()));
        s.addView(switchRow(L.s(R.string.set_clean_disable), L.s(R.string.set_clean_disable_desc),
                prefs.getBoolean("clean_disable_modules", false),
                on -> prefs.edit().putBoolean("clean_disable_modules", on).apply()), Ui.lp(-1, -2, 0, u.dp(8), 0, 0));

        settingsSection(L.s(R.string.set_sec_rules));
        s.addView(u.note(L.s(R.string.rules_text), u.dim));

        settingsSection(L.s(R.string.set_sec_system));
        TextView reboot = u.text(L.s(R.string.reboot_phone), 15, u.bad, true);
        reboot.setGravity(Gravity.CENTER);
        reboot.setPadding(0, u.dp(13), 0, u.dp(13));
        reboot.setBackground(u.ripple(Ui.round(Ui.tint(u.bad), u.dp(16))));
        reboot.setOnClickListener(v -> confirm(L.s(R.string.reboot_confirm),
                L.s(R.string.reboot_msg), L.s(R.string.reboot), () -> run("REBOOT")));
        s.addView(reboot);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = u.text(L.s(R.string.about_text, ver), 12, u.dim, false);
        about.setGravity(Gravity.CENTER);
        about.setLineSpacing(u.dp(2), 1f);
        s.addView(about, Ui.lp(-1, -2, 0, u.dp(24), 0, 0));
    }

    // ------------------------------------------------------------------ 语言

    /** 设置页的语言行：显示当前语言，点击弹出选择面板。 */
    private View languageRow() {
        LinearLayout r = u.row();
        r.setPadding(u.dp(14), u.dp(14), u.dp(14), u.dp(14));
        r.setBackground(u.ripple(u.card(u.dp(16))));
        r.addView(u.text(languageName(L.tag(this)), 15, u.fg, true), Ui.weight(1));
        r.addView(u.text("›", 20, u.dim, true), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> openLanguageSheet());
        return r;
    }

    private static String languageName(String tag) {
        int i = java.util.Arrays.asList(L.TAGS).indexOf(tag);
        return i <= 0 ? L.s(R.string.lang_follow_system) : L.NAMES[i];
    }

    private void openLanguageSheet() {
        String cur = L.tag(this);
        Sheet s = new Sheet(this, u).title(L.s(R.string.set_language), null);
        for (String tag : L.TAGS) {
            boolean sel = tag.equals(cur);
            s.action(sel ? "✓  " + languageName(tag) : languageName(tag), sel ? Sheet.PRIMARY : Sheet.NORMAL,
                    () -> applyLanguage(tag));
        }
        s.action(L.s(R.string.cancel), Sheet.NORMAL, null).show();
    }

    /** 保存语言并重建界面；悬浮窗开着的话重启一下，让它也换成新语言。 */
    private void applyLanguage(String tag) {
        if (tag.equals(L.tag(this))) return;
        L.set(this, tag);
        appCache = null; // 应用名排序规则随语言变化
        if (FloatService.isRunning()) {
            android.content.Context app = getApplicationContext();
            FloatService.stop(app);
            ui.postDelayed(() -> FloatService.start(app), 300);
        }
        recreate();
    }

    /** 单选指示器：选中为实心圆点 + 外环。 */
    private android.graphics.drawable.Drawable radio(boolean sel) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(u.dp(2), sel ? u.accent : u.dim);
        if (!sel) return ring;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(u.accent);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{ring, dot});
        int in = u.dp(5);
        ld.setLayerInset(1, in, in, in, in);
        return ld;
    }

    interface OnToggle {
        void onToggle(boolean on);
    }

    View switchRow(String title, String desc, boolean checked, OnToggle l) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(14), u.dp(12), u.dp(10), u.dp(12));
        r.setBackground(u.ripple(u.card(u.dp(16))));
        LinearLayout c = u.col();
        c.addView(u.text(title, 15, u.fg, true));
        TextView d = u.text(desc, 12, u.dim, false);
        d.setLineSpacing(u.dp(1), 1f);
        c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        r.addView(c, Ui.weight(1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> l.onToggle(on));
        r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    // ------------------------------------------------------------------ 刷新率

    private void openRefreshSheet() {
        snack(L.s(R.string.refresh_reading), true);
        mon.call("REFRESH_INFO", r -> {
            ui.removeCallbacks(hideSnack);
            snack.setVisibility(View.GONE);
            JSONArray rates = r.optJSONArray("rates");
            if (!r.optBoolean("ok") || rates == null || rates.length() == 0) {
                snack(r.has("error") ? L.s(R.string.label_colon, L.s(R.string.refresh_unreadable), r.optString("error")) : L.s(R.string.refresh_unreadable), false);
                return;
            }
            int max = rates.optInt(rates.length() - 1);
            StringBuilder rs = new StringBuilder();
            for (int i = 0; i < rates.length(); i++) rs.append(i > 0 ? "/" : "").append(rates.optInt(i));
            Sheet s = new Sheet(this, u).title(L.s(R.string.refresh_title),
                    L.s(R.string.refresh_sheet_sub, rs.toString(), r.optInt("current", -1)));
            for (int i = rates.length() - 1; i >= 0; i--) {
                int fps = rates.optInt(i);
                s.action(L.s(fps == max ? R.string.refresh_lock_max : R.string.refresh_lock, fps),
                        fps == max ? Sheet.PRIMARY : Sheet.NORMAL, () -> applyRefresh(fps));
            }
            s.action(L.s(R.string.refresh_release), Sheet.NORMAL, () -> applyRefresh(0));
            s.action(L.s(R.string.cancel), Sheet.NORMAL, null);
            s.show();
        });
    }

    private void applyRefresh(int fps) {
        prefs.edit().putInt("lock_fps", fps).apply(); // 记住选择，App 运行时自动保持
        toolRefreshDesc.setText(refreshSummary());
        mon.call("REFRESH_SET " + fps, r ->
                snack(r.optBoolean("ok") ? r.optString("msg") : (r.has("error") ? r.optString("error") : L.s(R.string.set_failed)),
                        r.optBoolean("ok")));
    }

    // ------------------------------------------------------------------ 加固应用兼容修复

    private void openCompatSheet() {
        snack(L.s(R.string.compat_checking_fix), true);
        CompatFix.query((ok, msg, st) -> {
            snack.setVisibility(View.GONE);
            if (!ok || st == null) {
                snack(msg, false);
                toolCompatDesc.setText(L.s(R.string.need_root_tap));
                return;
            }
            Sheet s = new Sheet(this, u).title(L.s(R.string.compat_title),
                    L.s(st.active ? R.string.compat_cur_on : R.string.compat_cur_off));
            s.add(u.note(L.s(R.string.compat_explain), u.dim));
            if (st.active) {
                s.action(L.s(R.string.compat_disable), Sheet.DANGER, () -> runCompat(false));
                s.action(L.s(R.string.done), Sheet.NORMAL, null);
            } else {
                s.action(L.s(R.string.compat_enable), Sheet.PRIMARY, () -> runCompat(true));
                s.action(L.s(R.string.cancel), Sheet.NORMAL, null);
            }
            s.show();
        });
    }

    private void runCompat(boolean enable) {
        snack(L.s(enable ? R.string.enabling : R.string.restoring), true);
        CompatFix.set(enable, (ok, msg, st) -> {
            snack(msg, ok);
            if (st != null) toolCompatDesc.setText(CompatFix.desc(st));
            else toolCompatDesc.setText(ok ? CompatFix.desc(null) : L.s(R.string.need_root_tap));
        });
    }

    // ------------------------------------------------------------------ VPN 隐身

    /** 本地名单（UI 的事实来源）。旧 MODE_WORLD_READABLE 重定向在 targetSdk 35 上不可靠，弃用。 */
    private SharedPreferences hidePrefs() {
        return getSharedPreferences("hide", MODE_PRIVATE);
    }

    /**
     * 把名单写到 /data/system/thermalhunter/vpnhide.json（system 所有、system_server 可读），
     * VPN 隐身的唯一依据：system_server 层按名单对目标应用抹掉 VPN 标志，无需逐个配置 LSPosed 作用域。
     * 旧「私有 prefs 改世界可读」在本机行不通（0660 + SELinux 跨应用隔离），故改走 root 写系统目录。
     */
    private void syncVpnHideConfig() {
        final java.util.Set<String> pkgs = vpnHidePkgs();
        new Thread(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String p : pkgs) arr.put(p);
                String b64 = android.util.Base64.encodeToString(
                        arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                // 目录必须归 system 所有：Stats.flush 以 uid 1000 写 stats.json，
                // 若 fresh install 时本进程(root)先建目录而不 chown，system 写不进 → 归因统计静默失效。
                String cmd = "mkdir -p /data/system/thermalhunter; "
                        + "chown system:system /data/system/thermalhunter; "
                        + "chmod 700 /data/system/thermalhunter; echo '" + b64
                        + "' | base64 -d > /data/system/thermalhunter/vpnhide.json; "
                        + "chmod 644 /data/system/thermalhunter/vpnhide.json";
                Process pr = new ProcessBuilder("su", "-c", cmd).start();
                if (!pr.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }, "vpnhide-sync").start();
    }

    private java.util.Set<String> vpnHidePkgs() {
        return new java.util.HashSet<>(hidePrefs().getStringSet("vpn_pkgs", java.util.Collections.emptySet()));
    }

    private String vpnHideSummary() {
        int n = vpnHidePkgs().size();
        return n == 0 ? L.s(R.string.none_selected) : L.s(R.string.vpn_enabled_n, n);
    }

    private void openVpnHidePicker() {
        openAppPicker(L.s(R.string.vpn_hide), L.s(R.string.vpn_sub), L.s(R.string.vpn_note),
                vpnHidePkgs(), working -> {
                    hidePrefs().edit().putStringSet("vpn_pkgs", working).commit();
                    syncVpnHideConfig();
                    toolVpnDesc.setText(vpnHideSummary());
                    snack(working.isEmpty() ? L.s(R.string.vpn_cleared) : L.s(R.string.vpn_saved, working.size()), true);
                });
    }

    // ------------------------------------------------------------------ 应用选择列表（VPN 隐身 / 截图解除共用）

    /** 一个可选应用。名称只读取一次；图标在列表显示后由后台线程逐个加载，加载过的会缓存。 */
    private static final class AppItem {
        final ApplicationInfo ai;
        final String pkg, label, search;
        final java.text.CollationKey sortKey;
        volatile android.graphics.drawable.Drawable icon;

        AppItem(ApplicationInfo ai, String label, java.text.Collator coll) {
            this.ai = ai;
            this.pkg = ai.packageName;
            this.label = label;
            this.search = (label + " " + pkg).toLowerCase(Locale.ROOT);
            this.sortKey = coll.getCollationKey(label);
        }
    }

    interface OnPicked {
        void onPicked(java.util.Set<String> pkgs);
    }

    private static final long APP_CACHE_MS = 5 * 60_000;
    private List<AppItem> appCache;
    private long appCacheTime;
    private boolean appsLoading;

    /** 读取应用列表（有缓存时直接用），然后显示可勾选、可搜索的列表，已勾选的排在最前。 */
    private void openAppPicker(String title, String sub, String note, java.util.Set<String> selected, OnPicked onSave) {
        if (appCache != null && android.os.SystemClock.elapsedRealtime() - appCacheTime < APP_CACHE_MS) {
            showAppPicker(appCache, title, sub, note, selected, onSave);
            return;
        }
        if (appsLoading) return;
        appsLoading = true;
        snack(L.s(R.string.reading_apps), true);
        new Thread(() -> {
            List<AppItem> apps = loadApps();
            ui.post(() -> {
                appsLoading = false;
                appCache = apps;
                appCacheTime = android.os.SystemClock.elapsedRealtime();
                ui.removeCallbacks(hideSnack);
                snack.setVisibility(View.GONE);
                if (!isFinishing() && !isDestroyed()) showAppPicker(apps, title, sub, note, selected, onSave);
            });
        }, "app-list").start();
    }

    /** 后台线程：第三方应用 + 更新过的系统应用（排除自己）。每个应用的名称只读一次。 */
    private List<AppItem> loadApps() {
        PackageManager pm = getPackageManager();
        java.text.Collator coll = java.text.Collator.getInstance();
        List<AppItem> apps = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (getPackageName().equals(ai.packageName)) continue;
            if (system && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
            String label;
            try {
                label = ai.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = ai.packageName;
            }
            apps.add(new AppItem(ai, label, coll));
        }
        return apps;
    }

    private void showAppPicker(List<AppItem> all, String title, String sub, String note,
                               java.util.Set<String> selected, OnPicked onSave) {
        java.util.Set<String> working = new java.util.HashSet<>(selected);
        // 已勾选的排在最前，其余按名称排序；只在打开时排一次，勾选过程中不跳动
        List<AppItem> apps = new ArrayList<>(all);
        apps.sort((a, b) -> {
            boolean sa = selected.contains(a.pkg), sb = selected.contains(b.pkg);
            if (sa != sb) return sa ? -1 : 1;
            return a.sortKey.compareTo(b.sortKey);
        });
        Sheet s = new Sheet(this, u).title(title, sub);

        // 固定在顶部的搜索框
        EditText search = new EditText(this);
        search.setHint(L.s(R.string.search_apps));
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(u.fg);
        search.setHintTextColor(u.dim);
        search.setBackground(Ui.round(u.sunken, u.dp(12)));
        search.setPadding(u.dp(14), u.dp(11), u.dp(14), u.dp(11));

        LinearLayout list = u.col();
        final List<View> rows = new ArrayList<>();
        final List<ImageView> icons = new ArrayList<>();
        for (AppItem app : apps) {
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            ImageView icon = new ImageView(this);
            if (app.icon != null) icon.setImageDrawable(app.icon);
            r.addView(icon, Ui.lp(u.dp(34), u.dp(34), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView t = u.text(app.label, 14, u.fg, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(t);
            TextView pt = u.text(app.pkg, 11, u.dim, false);
            pt.setSingleLine(true);
            pt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            c.addView(pt);
            r.addView(c, Ui.weight(1));
            Switch sw = new Switch(this);
            sw.setChecked(working.contains(app.pkg));
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on) working.add(app.pkg);
                else working.remove(app.pkg);
            });
            r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            r.setOnClickListener(v -> sw.toggle());
            list.addView(r, Ui.lp(-1, -2, 0, rows.isEmpty() ? 0 : u.dp(8), 0, 0));
            rows.add(r);
            icons.add(icon);
        }
        TextView empty = u.text(L.s(R.string.no_match), 13, u.dim, false);
        empty.setPadding(0, u.dp(16), 0, 0);
        empty.setVisibility(View.GONE);
        list.addView(empty);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            public void onTextChanged(CharSequence x, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString().trim().toLowerCase(Locale.ROOT);
                int shown = 0;
                for (int i = 0; i < rows.size(); i++) {
                    boolean match = q.isEmpty() || apps.get(i).search.contains(q);
                    rows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                    if (match) shown++;
                }
                empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
            }
        });

        s.pin(search);
        s.add(list);
        s.add(u.note(note, u.dim), Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        s.action(L.s(R.string.save), Sheet.PRIMARY, () -> onSave.onPicked(working));
        s.action(L.s(R.string.cancel), Sheet.NORMAL, null);

        // 图标按列表顺序在后台加载，面板关闭即停止
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        s.onDismiss(() -> closed.set(true));
        s.show();
        PackageManager pm = getPackageManager();
        new Thread(() -> {
            for (int i = 0; i < apps.size() && !closed.get(); i++) {
                AppItem app = apps.get(i);
                if (app.icon == null) {
                    try {
                        app.icon = app.ai.loadIcon(pm);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    ImageView iv = icons.get(i);
                    android.graphics.drawable.Drawable d = app.icon;
                    ui.post(() -> iv.setImageDrawable(d));
                }
            }
        }, "app-icons").start();
    }

    // ------------------------------------------------------------------ 截图限制解除

    /** 截图解锁名单的本地存储（UI 事实来源）。 */
    private SharedPreferences shotPrefs() {
        return getSharedPreferences("screenshot", MODE_PRIVATE);
    }

    /** 把名单写到 /data/system/thermalhunter/screenshot.json，system_server 层按名单解除 FLAG_SECURE。 */
    private void syncScreenshotConfig() {
        final java.util.Set<String> pkgs = shotPkgs();
        new Thread(() -> {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String p : pkgs) arr.put(p);
                String b64 = android.util.Base64.encodeToString(
                        arr.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        android.util.Base64.NO_WRAP);
                String cmd = "mkdir -p /data/system/thermalhunter; "
                        + "chown system:system /data/system/thermalhunter; "
                        + "chmod 700 /data/system/thermalhunter; echo '" + b64
                        + "' | base64 -d > /data/system/thermalhunter/screenshot.json; "
                        + "chmod 644 /data/system/thermalhunter/screenshot.json";
                Process pr = new ProcessBuilder("su", "-c", cmd).start();
                if (!pr.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) pr.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }, "shot-sync").start();
    }

    private java.util.Set<String> shotPkgs() {
        return new java.util.HashSet<>(shotPrefs().getStringSet("pkgs", java.util.Collections.emptySet()));
    }

    private String shotSummary() {
        int n = shotPkgs().size();
        return n == 0 ? L.s(R.string.none_selected) : L.s(R.string.shot_enabled_n, n);
    }

    private void openShotPicker() {
        openAppPicker(L.s(R.string.shot_title), L.s(R.string.shot_sub), L.s(R.string.shot_note),
                shotPkgs(), working -> {
                    shotPrefs().edit().putStringSet("pkgs", working).commit();
                    syncScreenshotConfig();
                    toolShotDesc.setText(shotSummary());
                    snack(working.isEmpty() ? L.s(R.string.shot_cleared) : L.s(R.string.shot_saved, working.size()), true);
                });
    }

    // ------------------------------------------------------------------ 悬浮窗

    private void toggleFloat() {
        if (FloatService.isRunning()) {
            FloatService.stop(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingFloat = true;
            snack(L.s(R.string.float_allow_overlay), true);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                pendingFloat = false;
                snack(L.s(R.string.float_open_settings_failed), false);
            }
            return;
        }
        startFloat();
    }

    private void startFloat() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FloatService.start(this);
    }

    private void updateFloatBtn() {
        boolean on = FloatService.isRunning();
        floatBtn.setColorFilter(on ? Color.WHITE : u.fg);
        floatBtn.setBackground(u.ripple(on ? Ui.round(u.accent, u.dp(21)) : u.card(u.dp(21))));
        if (toolFloatDesc != null) toolFloatDesc.setText(L.s(on ? R.string.float_on_desc : R.string.float_off_desc));
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
                row.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
                row.setDuplicateParentStateEnabled(true); // 让卡片水波纹响应列表点击

                h.avatar = u.avatar(42);
                row.addView(h.avatar, Ui.lp(u.dp(42), u.dp(42), 0, 0, u.dp(12), 0));

                LinearLayout colv = u.col();
                LinearLayout top = u.row();
                h.title = u.text("", 15, u.fg, true);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                top.addView(h.title, Ui.weight(1));
                h.badge = u.chip("", u.bad);
                h.badge.setTextSize(10);
                top.addView(h.badge, Ui.lp(-2, -2, u.dp(6), 0, 0, 0));
                colv.addView(top);
                h.sub = u.text("", 11, u.dim, false);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                colv.addView(h.sub, Ui.lp(-1, -2, 0, u.dp(1), 0, 0));
                h.metrics = u.text("", 12, u.dim, false);
                colv.addView(h.metrics, Ui.lp(-1, -2, 0, u.dp(4), 0, 0));
                h.bar = new Ui.Bar(MainActivity.this, u.track);
                colv.addView(h.bar, Ui.lp(-1, u.dp(3), 0, u.dp(8), 0, 0));
                h.reasons = u.text("", 12, u.bad, false);
                h.reasons.setLineSpacing(u.dp(1), 1f);
                colv.addView(h.reasons, Ui.lp(-1, -2, 0, u.dp(6), 0, 0));
                row.addView(colv, Ui.weight(1));

                LinearLayout valCol = u.col();
                valCol.setGravity(Gravity.END);
                h.value = u.text("", 17, u.fg, true);
                h.value.setFontFeatureSettings("tnum");
                h.caption = u.text("", 10, u.dim, false);
                valCol.addView(h.value);
                valCol.addView(h.caption);
                row.addView(valCol, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));

                LinearLayout wrap = new LinearLayout(MainActivity.this);
                wrap.setPadding(u.dp(16), u.dp(5), u.dp(16), u.dp(5));
                wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                h.card = row;
                wrap.setTag(h);
                convert = wrap;
            } else {
                h = (Holder) convert.getTag();
            }
            Entity e = items.get(pos);
            String name = u.label(e);
            h.title.setText(name);
            String sub = Ui.typeName(e) + " · " + (e.type == Analyzer.KSU ? e.sub : e.title);
            if (!e.enabled) sub = L.s(R.string.tag_disabled) + " · " + sub;
            h.sub.setText(sub);
            String m = metrics(e);
            h.metrics.setText(m);
            h.metrics.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);

            // 右侧主数值 + 占用条
            double frac;
            if (tab == 3) {
                h.value.setText(e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--");
                h.caption.setText(L.s(R.string.cap_wl));
                frac = Math.max(0, e.wlFrac);
            } else if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
                double v = e.cpuAvg + e.measuredCpu;
                h.value.setText(Analyzer.fmt(v) + "%");
                h.caption.setText(L.s(R.string.cap_cpu_injected));
                frac = v / 100;
            } else if (e.type == Analyzer.LSP && e.measuredCpu >= 0) {
                h.value.setText(Analyzer.fmt(e.measuredCpu) + "%");
                h.caption.setText(L.s(R.string.cap_measured));
                frac = e.measuredCpu / 100;
            } else if (e.type == Analyzer.LSP) {
                // 没有实测数据：显示宿主进程的占用并注明
                if (hasAppHost(e)) {
                    h.value.setText(Analyzer.fmt(e.scopeCpu) + "%");
                    h.caption.setText(L.s(R.string.st_host_cpu));
                    frac = e.scopeCpu / 100;
                } else if (e.injectsSystem && e.systemCpu >= 0) {
                    h.value.setText(Analyzer.fmt(e.systemCpu) + "%");
                    h.caption.setText(L.s(R.string.cap_shared));
                    frac = 0;
                } else {
                    h.value.setText("--");
                    h.caption.setText(L.s(R.string.cap_host_idle));
                    frac = 0;
                }
            } else {
                h.value.setText(Analyzer.fmt(e.cpuAvg) + "%");
                h.caption.setText("CPU");
                frac = e.cpuAvg / 100;
            }
            int lc = u.levelColor(e.level);
            boolean sharedOnly = e.type == Analyzer.LSP && e.measuredCpu < 0 && !hasAppHost(e);
            h.value.setTextColor(sharedOnly ? u.dim : e.level == Analyzer.OK ? u.fg : lc);
            h.bar.set((float) Math.min(1, frac), lc);

            StringBuilder rs = new StringBuilder();
            for (String r : e.reasons) rs.append(rs.length() > 0 ? "\n" : "").append("❗ ").append(r);
            for (String w : e.warnings) rs.append(rs.length() > 0 ? "\n" : "").append("⚠ ").append(w);
            List<String> tags = new ArrayList<>(e.tags);
            if (e.isProtected) tags.add(L.s(R.string.chip_protected));
            if (!tags.isEmpty()) rs.append(rs.length() > 0 ? "\n" : "").append("🏷 ").append(TextUtils.join(" · ", tags));
            h.reasons.setText(rs);
            h.reasons.setTextColor(e.level == Analyzer.BAD ? u.bad : e.level == Analyzer.WARN ? u.warn : u.dim);
            h.reasons.setVisibility(rs.length() == 0 ? View.GONE : View.VISIBLE);
            if (e.level != Analyzer.OK) {
                int bc = u.levelColor(e.level);
                h.badge.setText(L.s(e.level == Analyzer.BAD ? R.string.level_bad : R.string.level_warn));
                h.badge.setTextColor(bc);
                h.badge.setBackground(Ui.round(Ui.tint(bc), u.dp(10)));
                h.badge.setVisibility(View.VISIBLE);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            u.bindAvatar(h.avatar, e, name);

            boolean badCard = e.level == Analyzer.BAD;
            if (h.badBg == null || h.badBg != badCard) {
                h.badBg = badCard;
                GradientDrawable bgd = Ui.round(badCard ? u.badCard : u.card, u.dp(18));
                bgd.setStroke(u.dp(1), badCard ? Ui.alpha(u.bad, 0x55) : u.stroke);
                h.card.setBackground(u.ripple(bgd));
            }
            h.card.setAlpha(e.enabled ? 1f : 0.55f);
            return convert;
        }
    }

    private static final class Holder {
        LinearLayout card;
        FrameLayout avatar;
        Ui.Bar bar;
        TextView title, sub, metrics, reasons, badge, value, caption;
        Boolean badBg;
    }
}
