package com.thermalhunter.ui;

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

    private static final String[] TABS = {"热源排行", "KSU模块", "LSP模块", "唤醒耗电"};
    private static final String[] MODE_NAMES = {"自动", "仅 root", "仅 Shizuku"};
    private static final String[] MODE_DESCS = {
            "优先使用 root，没有 root 时自动改用 Shizuku",
            "通过 KernelSU 等获取超级用户权限，功能最完整",
            "通过无线调试 / ADB 以 shell 权限运行，无需 root（模块管理与杀进程不可用）"};

    private Monitor mon;
    private SharedPreferences prefs;
    private Ui u;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Analyzer.Result result;
    private int tab;
    private boolean pendingFloat;

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
    private Button cleanBtn;
    private final EntityAdapter adapter = new EntityAdapter();
    private final Runnable hideSnack = () -> snack.animate().alpha(0f).translationY(20f).setDuration(200)
            .withEndAction(() -> snack.setVisibility(View.GONE)).start();

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        u = new Ui(this);
        getWindow().setStatusBarColor(u.bg);
        getWindow().setNavigationBarColor(u.bg);
        setContentView(buildLayout());
        mon = Monitor.get(this);
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
            else snack("未授予悬浮窗权限", false);
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

    // ------------------------------------------------------------------ 布局

    private View buildLayout() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(u.bg);
        frame.setFitsSystemWindows(true);
        LinearLayout rootView = u.col();
        frame.addView(rootView, new FrameLayout.LayoutParams(-1, -1));

        // 头部（随列表一起滚动）
        LinearLayout header = u.col();
        header.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(6));

        // 顶栏：标题 / 设备 / 模式标签 / 悬浮窗 / 设置
        LinearLayout top = u.row();
        LinearLayout titleCol = u.col();
        titleCol.addView(u.text("热源猎手", 24, u.fg, true));
        headDevice = u.text("正在连接…", 12, u.dim, false);
        headDevice.setSingleLine(true);
        headDevice.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(headDevice);
        headKernel = u.text("", 11, u.dim, false);
        headKernel.setSingleLine(true);
        headKernel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(headKernel);
        top.addView(titleCol, Ui.weight(1));
        modeChip = u.chip("", u.dim);
        top.addView(modeChip, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        floatBtn = iconButton(R.drawable.ic_float, "悬浮窗", v -> toggleFloat());
        top.addView(floatBtn, Ui.lp(u.dp(42), u.dp(42), u.dp(8), 0, 0, 0));
        top.addView(iconButton(R.drawable.ic_tune, "设置", v -> showSettings()),
                Ui.lp(u.dp(42), u.dp(42), u.dp(8), 0, 0, 0));
        header.addView(top);
        chip("");

        // 温度 / 功耗卡片
        hero = u.row();
        hero.setPadding(u.dp(18), u.dp(16), u.dp(18), u.dp(18));
        hero.setBackground(Ui.gradient(Ui.GRAD_NONE, u.dp(24)));
        hero.setElevation(u.dp(2));
        LinearLayout hl = u.col();
        heroLabel = u.text("SoC 温度", 12, 0xDDFFFFFF, false);
        heroTemp = u.text("--", 40, Color.WHITE, true);
        heroTemp.setFontFeatureSettings("tnum");
        heroTempSub = u.text("", 12, 0xDDFFFFFF, false);
        hl.addView(heroLabel);
        hl.addView(heroTemp);
        hl.addView(heroTempSub);
        hero.addView(hl, Ui.weight(1));
        LinearLayout hr = u.col();
        hr.setGravity(Gravity.END);
        heroPowerLabel = u.text("放电功耗", 12, 0xDDFFFFFF, false);
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
        lt.addView(u.text("实时负载", 14, u.fg, true), Ui.weight(1));
        loadSummary = u.text("", 12, u.dim, false);
        loadSummary.setFontFeatureSettings("tnum");
        lt.addView(loadSummary);
        load.addView(lt);
        clusterRow = new LinearLayout(this);
        load.addView(clusterRow, Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
        header.addView(load, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));

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
            TextView t = u.text(TABS[i], 13, u.dim, true);
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
        header.addView(tabs, Ui.lp(-1, -2, 0, u.dp(14), 0, u.dp(4)));
        updateTabs();

        // 自动归因状态（仅 LSP 模块页、root 模式）
        profStatus = u.note("", u.dim);
        profStatus.setTextSize(12);
        profStatus.setVisibility(View.GONE);
        header.addView(profStatus, Ui.lp(-1, -2, 0, u.dp(8), 0, u.dp(4)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT)); // 卡片自带水波纹
        list.setPadding(0, 0, 0, u.dp(8));
        list.setClipToPadding(false);
        list.addHeaderView(header, null, false);
        LinearLayout footer = new LinearLayout(this);
        emptyText = u.text("正在采集…", 13, u.dim, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(u.dp(24), u.dp(36), u.dp(24), u.dp(36));
        footer.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
        list.addFooterView(footer, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof Entity) showDetail((Entity) o);
        });
        rootView.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(u.dp(16), u.dp(8), u.dp(16), u.dp(14));
        cleanBtn = new Button(this);
        cleanBtn.setText("一键清理异常");
        cleanBtn.setTextColor(Color.WHITE);
        cleanBtn.setTextSize(16);
        cleanBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cleanBtn.setAllCaps(false);
        cleanBtn.setStateListAnimator(null);
        cleanBtn.setElevation(u.dp(3));
        cleanBtn.setBackground(u.ripple(Ui.round(u.accent, u.dp(26))));
        cleanBtn.setOnClickListener(v -> confirmClean());
        bottom.addView(cleanBtn, new LinearLayout.LayoutParams(-1, u.dp(52)));
        rootView.addView(bottom);

        // 轻提示（替代系统 Toast）
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

    private ImageView iconButton(int icon, String desc, View.OnClickListener l) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(u.fg);
        b.setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(10));
        b.setBackground(u.ripple(u.card(u.dp(21))));
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        return b;
    }

    private void updateTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean sel = i == tab;
            tabViews[i].setText(tabCounts[i] > 0 ? TABS[i] + " " + tabCounts[i] : TABS[i]);
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
        modeChip.setText("root".equals(mode) ? "ROOT" : "shell".equals(mode) ? "SHIZUKU" : "未连接");
        modeChip.setTextColor(col);
        modeChip.setBackground(Ui.round(Ui.tint(col), u.dp(10)));
    }

    private void snack(String msg, boolean ok) {
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
        boolean shell = "shell".equals(mode);
        chip(mode);
        JSONObject dev = s.optJSONObject("device");
        if (dev != null) {
            headDevice.setText(dev.optString("model") + " · " + dev.optString("soc") + " · Android " + dev.optString("android"));
            headKernel.setText("内核 " + dev.optString("kernel") + (shell ? "" : " · " + s.optString("rootImpl")));
        }

        // 温度卡片
        Object[] mt = Ui.mainTemp(r);
        double mainTemp = (double) mt[0];
        boolean hasSoc = r.maxTemp > -999, hasSkin = r.skinTemp > -999;
        heroLabel.setText((String) mt[1]);
        heroTemp.setText(mainTemp > -999 ? Analyzer.fmt(mainTemp) + "°C" : "--");
        List<String> tsub = new ArrayList<>();
        if (hasSoc && hasSkin) tsub.add("机身 " + Analyzer.fmt(r.skinTemp) + "°");
        if ((hasSoc || hasSkin) && r.batteryTemp > -999) tsub.add("电池 " + Analyzer.fmt(r.batteryTemp) + "°");
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
                heroPowerLabel.setText("放电功耗");
                heroPower.setText(String.format(Locale.US, "%.2f W", bat.optLong("powerMw") / 1000.0));
                heroPowerSub.setText(ma + " mA · 电量 " + lvs);
            } else {
                String st = bat.optString("status");
                heroPowerLabel.setText("Full".equalsIgnoreCase(st) ? "已充满" : st.isEmpty() ? "电池" : "充电中");
                heroPower.setText(ma > 0 ? ma + " mA" : "--");
                heroPowerSub.setText("电量 " + lvs);
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
            notes.add("Shizuku 模式：可查看温度、功耗、进程排行，可强行停止 / 停用应用；"
                    + "KSU / LSP 模块、应用唤醒统计、杀死其他进程需要 root。");
        } else if (!ModuleStatus.isActive()) {
            notes.add("LSPosed 模块未激活：在 LSPosed 中启用本模块并勾选「系统框架」后重启，才能统计应用唤醒锁/闹钟。");
        } else if (!r.hookOk) {
            notes.add(r.hookAgeSec < 0 ? "模块已激活，但系统框架 hook 未生效（作用域需勾选「系统框架」并重启）"
                    : "唤醒统计数据已过期 " + r.hookAgeSec + " 秒");
        }
        JSONObject lsp = s.optJSONObject("lsp");
        if (!shell && lsp != null && !lsp.optBoolean("available") && lsp.has("error")) {
            notes.add("LSPosed：" + lsp.optString("error"));
        }
        if (!shell && r.prof != null && !r.prof.optBoolean("ok") && mon.autoProfEnabled()) {
            notes.add("LSP 模块自动归因不可用：" + r.prof.optString("error"));
        }
        if (notes.isEmpty()) banner.setVisibility(View.GONE);
        else banner(TextUtils.join("\n", notes), shell ? u.accent : u.warn);

        cleanBtn.setText(r.badCount > 0 ? "一键清理异常（" + r.badCount + "）" : "一键清理异常");
        int cleanState = r.badCount > 0 ? 1 : 0;
        if (cleanState != lastCleanState) {
            lastCleanState = cleanState;
            cleanBtn.setBackground(u.ripple(Ui.round(cleanState == 1 ? u.bad : u.accent, u.dp(26))));
        }
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
            lim.setText(throttled ? "限频 " + Ui.freq(limit) : "最高 " + Ui.freq(max));
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
            String s = "累计持锁 " + Ui.dur(e.wlHeldTotalMs) + " · 唤醒闹钟 " + e.alarmsTotal + " 次";
            if (e.wlTag != null && !e.wlTag.isEmpty()) s += "\n锁: " + e.wlTag;
            return s;
        }
        List<String> p = new ArrayList<>();
        if (e.type == Analyzer.LSP) {
            p.add(e.sub);
            String where = measuredWhere(e, 2);
            if (!where.isEmpty()) p.add(where);
            if (!e.pids.isEmpty()) p.add("自身 CPU " + Analyzer.fmt(e.cpuAvg) + "%");
            return TextUtils.join(" · ", p);
        }
        if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
            p.add("注入实测 " + Analyzer.fmt(e.measuredCpu) + "%（" + measuredWhere(e, 2) + "）");
        }
        if (e.powerMw > 0) p.add("≈" + e.powerMw + "mW");
        if (e.rssKb > 0) p.add("内存 " + e.rssKb / 1024 + "MB");
        if (e.type == Analyzer.KSU) p.add("进程 " + e.pids.size());
        else if (e.pids.size() > 1) p.add(e.pids.size() + " 个进程");
        else if (e.pids.size() == 1) p.add("pid " + e.pids.get(0));
        if (e.type == Analyzer.APP && e.wlFrac > 0.05) p.add("持锁 " + Math.round(e.wlFrac * 100) + "%");
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
        if (e.level != Analyzer.OK) chips.addView(u.chip(e.level == Analyzer.BAD ? "异常" : "可疑", u.levelColor(e.level)));
        if (!e.enabled) chips.addView(u.chip("已禁用", u.dim), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (e.isProtected) chips.addView(u.chip("受保护", u.accent), Ui.lp(-2, -2, chips.getChildCount() > 0 ? u.dp(6) : 0, 0, 0, 0));
        if (chips.getChildCount() > 0) tc.addView(chips, Ui.lp(-2, -2, 0, u.dp(6), 0, 0));
        head.addView(tc, Ui.weight(1));
        s.add(head, Ui.lp(-1, -2, 0, 0, 0, 0));

        // 数据块
        s.add(statGrid(stats(e)), Ui.lp(-1, -2, 0, u.dp(16), 0, 0));

        if (!e.reasons.isEmpty() || !e.warnings.isEmpty()) {
            s.section("诊断");
            for (String r : e.reasons) s.add(u.note(r, u.bad));
            for (String w : e.warnings) s.add(u.note(w, u.warn));
        }
        if (!e.tags.isEmpty()) {
            s.section("标签");
            s.add(u.text(TextUtils.join(" · ", e.tags), 13, u.fg, false));
        }
        if (tab == 3 && e.wlTag != null && !e.wlTag.isEmpty()) {
            s.section("唤醒锁");
            s.add(u.text(e.wlTag, 13, u.fg, false));
        }
        if (e.type == Analyzer.KSU && e.raw != null) {
            s.section("模块信息");
            String desc = e.raw.optString("desc");
            if (!desc.isEmpty()) s.add(u.text(desc, 13, u.fg, false));
            List<String> scripts = new ArrayList<>();
            if (e.raw.optBoolean("service")) scripts.add("service.sh");
            if (e.raw.optBoolean("postfs")) scripts.add("post-fs-data.sh");
            if (e.raw.optBoolean("sysprop")) scripts.add("system.prop");
            if (!scripts.isEmpty()) s.add(u.text("脚本：" + TextUtils.join("、", scripts), 13, u.dim, false));
        }
        if ((e.type == Analyzer.LSP || e.type == Analyzer.KSU) && e.measured != null) {
            JSONObject procs = e.measured.optJSONObject("procs");
            if (procs != null && procs.length() > 0) {
                s.section("实测占用分布（单核 %）");
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
                s.section("最耗时的模块方法（单核 %）");
                s.add(listBox(methods, 5, "cpu"));
            }
        }
        if (e.type == Analyzer.LSP) {
            s.add(u.note(e.measuredCpu >= 0
                    ? "「模块代码实测」来自自动归因：低频采样宿主进程，按调用栈把 CPU 归到模块。"
                    + "hook 带来的间接开销（如被 hook 的方法无法内联）统计不到，实际影响可能略高。"
                    : "暂无实测数据：上面的「宿主进程 CPU」是宿主的总占用，system_server 由所有注入系统框架的模块共用。"
                    + "开启自动归因（root）后会显示模块代码的实测占用。", u.dim),
                    Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        }
        if (e.type == Analyzer.LSP && !e.scopes.isEmpty()) {
            s.section("作用域（" + e.scopes.size() + "）");
            String sc = TextUtils.join("、", e.scopes.subList(0, Math.min(40, e.scopes.size())));
            s.add(u.text(e.scopes.size() > 40 ? sc + " …" : sc, 13, u.fg, false));
        }
        if (!e.pids.isEmpty()) {
            s.section("进程（" + e.pids.size() + "）");
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
        if (e.isProtected) s.add(u.note("受保护对象：为防止死机 / 重启，不允许杀死或停用。", u.accent),
                Ui.lp(-1, -2, 0, u.dp(14), 0, 0));

        // 操作
        if (!e.isProtected && !e.isSelf) {
            switch (e.type) {
                case Analyzer.APP:
                    s.action("强行停止", Sheet.PRIMARY, () -> run("FORCESTOP " + e.pkg + " " + e.user));
                    s.action("停用（冻结）应用", Sheet.DANGER, () -> confirm("停用「" + name + "」？",
                            "停用后应用图标消失、不会再启动，可在本应用的详情里重新启用。", "停用",
                            () -> run("APP_DISABLE " + e.pkg + " " + e.user)));
                    break;
                case Analyzer.NATIVE:
                    s.action("杀死进程", Sheet.DANGER, () -> runAll(killCmds(e)));
                    break;
                case Analyzer.KSU:
                    if (!e.pids.isEmpty()) {
                        s.action("杀死模块的全部进程", Sheet.PRIMARY, () -> run("KSU_KILL " + e.ksuId));
                    }
                    if (e.enabled) {
                        s.action("禁用此模块", Sheet.DANGER, () -> confirm("禁用 KSU 模块「" + e.title + "」？",
                                "进程会立即结束，模块的挂载 / 系统属性需重启后才会撤销。", "禁用",
                                () -> run("KSU_DISABLE " + e.ksuId)));
                    } else {
                        s.action("重新启用此模块", Sheet.PRIMARY, () -> run("KSU_ENABLE " + e.ksuId));
                    }
                    break;
                case Analyzer.LSP:
                    if (e.enabled) {
                        s.action("在 LSPosed 中禁用", Sheet.DANGER, () -> confirm("禁用 LSPosed 模块「" + name + "」？",
                                "LSPosed 会缓存配置，需要重启手机才会停止注入。", "禁用",
                                () -> run("LSP_DISABLE " + e.lspPkg)));
                    } else {
                        s.action("在 LSPosed 中重新启用", Sheet.PRIMARY, () -> run("LSP_ENABLE " + e.lspPkg));
                    }
                    if (!e.pids.isEmpty()) {
                        s.action("强行停止模块应用本身", Sheet.NORMAL, () -> run("FORCESTOP " + e.lspPkg + " 0"));
                    }
                    break;
            }
        }
        if (e.type == Analyzer.APP) {
            s.action("重新启用（解冻）应用", Sheet.NORMAL, () -> run("APP_ENABLE " + e.pkg + " " + e.user));
        }
        s.action("关闭", Sheet.NORMAL, null);
        s.show();
    }

    /** 详情页的数据块：{标题, 数值, 颜色}。 */
    private List<Object[]> stats(Entity e) {
        List<Object[]> l = new ArrayList<>();
        int lc = e.level == Analyzer.OK ? u.fg : u.levelColor(e.level);
        if (tab == 3) {
            l.add(new Object[]{"近2分钟持锁", e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", lc});
            l.add(new Object[]{"唤醒闹钟", e.alarmsPerMin >= 0 ? Analyzer.fmt(e.alarmsPerMin) + "/分" : "--", u.fg});
            l.add(new Object[]{"累计持锁", Ui.dur(e.wlHeldTotalMs), u.fg});
            l.add(new Object[]{"累计闹钟", e.alarmsTotal + " 次", u.fg});
            return l;
        }
        if (e.type == Analyzer.LSP) {
            if (e.measuredCpu >= 0) l.add(new Object[]{"模块代码实测", Analyzer.fmt(e.measuredCpu) + "%", lc});
            l.add(new Object[]{"宿主进程 CPU", hasAppHost(e) ? Analyzer.fmt(e.scopeCpu) + "%" : "--", u.fg});
            l.add(new Object[]{"system_server", e.systemCpu >= 0 ? Analyzer.fmt(e.systemCpu) + "%" : "--", u.dim});
            l.add(new Object[]{"模块应用自身", e.pids.isEmpty() ? "未运行" : Analyzer.fmt(e.cpuAvg) + "%", u.fg});
            return l;
        }
        if (e.type == Analyzer.KSU && e.measuredCpu >= 0) {
            l.add(new Object[]{"注入代码实测", Analyzer.fmt(e.measuredCpu) + "%", lc});
            if (e.pids.isEmpty()) {
                // 只有注入代码、没有自己进程的模块（如 Zygisk 模块），进程相关的数据块没有意义
                l.add(new Object[]{"模块自身进程", "未运行", u.fg});
                return l;
            }
        }
        boolean ownCpuFlagged = e.type != Analyzer.KSU || e.cpuAvg >= 5;
        l.add(new Object[]{e.type == Analyzer.KSU ? "模块进程 CPU" : "CPU（15秒均值）", Analyzer.fmt(e.cpuAvg) + "%",
                ownCpuFlagged ? lc : u.fg});
        l.add(new Object[]{"估算功耗", e.powerMw > 0 ? e.powerMw + " mW" : "--", u.fg});
        l.add(new Object[]{"内存", e.rssKb > 0 ? e.rssKb / 1024 + " MB" : "--", u.fg});
        l.add(new Object[]{"进程数", String.valueOf(e.pids.size()), u.fg});
        if (e.type == Analyzer.APP) {
            l.add(new Object[]{"状态", e.minAdj <= 1000 ? Ui.adjName(e.minAdj) : "--", u.fg});
            l.add(new Object[]{"持唤醒锁", e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--", u.fg});
        }
        return l;
    }

    /** 每行最多 3 个数据块。 */
    private View statGrid(List<Object[]> stats) {
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

    private void confirm(String title, String msg, String okLabel, Runnable ok) {
        new Sheet(this, u).title(title, msg)
                .action(okLabel, Sheet.DANGER_FILLED, ok)
                .action("取消", Sheet.NORMAL, null)
                .show();
    }

    private void run(String cmd) {
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
            Sheet s = new Sheet(this, u).title("执行结果", "成功 " + okN + " · 失败 " + (msgs.size() - okN));
            LinearLayout box = u.col();
            box.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            box.setBackground(Ui.round(u.sunken, u.dp(14)));
            for (int i = 0; i < msgs.size(); i++) {
                String m = msgs.get(i);
                TextView t = u.text(m, 13, m.startsWith("✓") ? u.good : u.bad, false);
                box.addView(t, Ui.lp(-1, -2, 0, i > 0 ? u.dp(6) : 0, 0, 0));
            }
            s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
            s.action("好", Sheet.PRIMARY, null).show();
        });
    }

    // ------------------------------------------------------------------ 深度分析

    /** LSP 页顶部的自动归因状态说明。 */
    private String profStatusText(Analyzer.Result r) {
        if (!mon.autoProfEnabled()) return "自动归因已关闭（可在设置中开启）。下面显示的是宿主进程的占用，不是模块自身的开销。";
        JSONObject p = r.prof;
        if (p == null) return "自动归因：正在准备第一轮采样（约 15 秒后出结果）…";
        if (!p.optBoolean("ok")) return "自动归因不可用，下面显示的是宿主进程的占用。";
        long age = Math.max(0, (r.snap.optLong("t") - p.optLong("time")) / 1000);
        String when = age < 5 ? "刚刚更新" : age + " 秒前更新";
        if (p.has("idle")) return "自动归因：" + p.optString("idle") + "，暂不采样 · " + when;
        JSONArray t = p.optJSONArray("targets");
        List<String> names = new ArrayList<>();
        if (t != null) for (int i = 0; i < t.length(); i++) names.add(t.optString(i));
        return "自动归因：实测采样 " + TextUtils.join("、", names) + " · " + when + " · 每 "
                + p.optInt("period", 30) + " 秒一次";
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
            snack("当前没有检测到异常对象 👍", true);
            return;
        }
        List<String> cmds = new ArrayList<>();
        LinearLayout box = u.col();
        for (Entity e : targets) {
            String act;
            int actColor;
            switch (e.type) {
                case Analyzer.APP:
                    act = "强行停止";
                    actColor = u.accent;
                    cmds.add("FORCESTOP " + e.pkg + " " + e.user);
                    break;
                case Analyzer.NATIVE:
                    if (shell) {
                        act = "跳过";
                        actColor = u.dim;
                    } else {
                        act = "杀死进程";
                        actColor = u.bad;
                        cmds.addAll(killCmds(e));
                    }
                    break;
                case Analyzer.KSU:
                    act = disableModules ? "禁用模块" : "杀死进程";
                    actColor = u.bad;
                    cmds.add(disableModules ? "KSU_DISABLE " + e.ksuId : "KSU_KILL " + e.ksuId);
                    break;
                default:
                    act = disableModules ? "禁用模块" : "停止进程";
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
        String sub = shell ? "Shizuku 模式无权杀死系统进程，这些对象会被跳过" : "将按下列方式处理检测到的异常对象";
        Sheet s = new Sheet(this, u).title("清理 " + targets.size() + " 个异常对象", sub);
        s.add(box, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
        if (!cmds.isEmpty()) s.action("立即清理", Sheet.DANGER_FILLED, () -> runAll(cmds));
        s.action("取消", Sheet.NORMAL, null).show();
    }

    // ------------------------------------------------------------------ 设置

    private void showSettings() {
        Sheet s = new Sheet(this, u).title("设置", null);

        s.section("工作模式");
        String cur = prefs.getString("work_mode", "auto");
        List<View[]> opts = new ArrayList<>();
        for (int i = 0; i < Monitor.MODES.length; i++) {
            final String mode = Monitor.MODES[i];
            LinearLayout r = u.row();
            r.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
            View ind = new View(this);
            r.addView(ind, Ui.lp(u.dp(20), u.dp(20), 0, 0, u.dp(12), 0));
            LinearLayout c = u.col();
            c.addView(u.text(MODE_NAMES[i], 15, u.fg, true));
            TextView d = u.text(MODE_DESCS[i], 12, u.dim, false);
            d.setLineSpacing(u.dp(1), 1f);
            c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
            r.addView(c, Ui.weight(1));
            opts.add(new View[]{r, ind});
            s.add(r, Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            r.setTag(mode);
        }
        Runnable paint = () -> {
            String m = prefs.getString("work_mode", "auto");
            for (View[] o : opts) {
                boolean sel = m.equals(o[0].getTag());
                GradientDrawable g = Ui.round(sel ? Ui.tint(u.accent) : u.sunken, u.dp(14));
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
            });
        }
        paint.run();

        s.section("悬浮窗");
        s.add(switchRow("显示悬浮窗", "在其他应用上方实时显示温度、功耗和发热最高的进程；可拖动，轻点展开 / 收起",
                FloatService.isRunning(), on -> {
                    s.dismiss();
                    if (on != FloatService.isRunning()) toggleFloat();
                }));

        s.section("LSP 模块");
        s.add(switchRow("自动归因模块占用（root）",
                "用系统自带的 simpleperf 每 30 秒低频采样一次注入模块的进程，按调用栈算出每个模块实际消耗的 CPU；"
                        + "会额外占用少量 CPU，只在界面或悬浮窗开启时运行",
                mon.autoProfEnabled(), on -> mon.setAutoProf(on)));

        s.section("屏幕刷新率");
        s.add(navRow("调整全局刷新率", "锁定高刷后对所有应用统一生效，不再被系统按应用限频", () -> {
            s.dismiss();
            openRefreshSheet();
        }));

        s.section("VPN 隐身（对所选应用隐藏 VPN 状态）");
        s.add(navRow("选择要隐身的应用…", vpnHideSummary(), () -> {
            s.dismiss();
            openVpnHidePicker();
        }));
        s.add(u.note("原理：在所选应用进程里让 VPN / 代理检测 API 报告「无 VPN」。"
                + "需要在 LSPosed 管理器里把这些应用加入本模块的作用域，并勾选后重启该应用或手机。"
                + "内核层的 vpnhide 模块只隐藏网络接口，挡不住框架 API 检测，本功能补这一层。", u.dim));

        s.section("一键清理");
        s.add(switchRow("同时处理前台应用", "默认只处理后台的异常应用",
                prefs.getBoolean("clean_foreground", false),
                on -> prefs.edit().putBoolean("clean_foreground", on).apply()));
        s.add(switchRow("直接禁用异常的 KSU / LSP 模块", "关闭时只杀死模块进程；禁用需重启手机才能完全生效",
                prefs.getBoolean("clean_disable_modules", false),
                on -> prefs.edit().putBoolean("clean_disable_modules", on).apply()));

        s.section("判定规则（单核 CPU%，约 15 秒平均）");
        s.add(u.note("· 任意应用 ≥35%；后台 ≥10%；缓存进程 ≥3%\n"
                + "· KSU 模块进程合计 ≥5%；其他 native 进程 ≥20%\n"
                + "· 后台持唤醒锁 ≥50% 时间，或唤醒闹钟 ≥6 次/分\n"
                + "· LSP 模块：自身后台 ≥8%；作用域内有异常应用时提示嫌疑", u.dim));

        s.action("重启手机", Sheet.DANGER, () -> confirm("立即重启手机？",
                "模块禁用需要重启才能完全生效。", "重启", () -> run("REBOOT")));
        s.action("完成", Sheet.PRIMARY, null);
        s.show();
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

    private interface OnToggle {
        void onToggle(boolean on);
    }

    private View switchRow(String title, String desc, boolean checked, OnToggle l) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(14), u.dp(12), u.dp(10), u.dp(12));
        r.setBackground(Ui.round(u.sunken, u.dp(14)));
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
        snack("正在读取刷新率…", true);
        mon.call("REFRESH_INFO", r -> {
            ui.removeCallbacks(hideSnack);
            snack.setVisibility(View.GONE);
            JSONArray rates = r.optJSONArray("rates");
            if (!r.optBoolean("ok") || rates == null || rates.length() == 0) {
                snack("读取不到支持的刷新率" + (r.has("error") ? "：" + r.optString("error") : ""), false);
                return;
            }
            int max = rates.optInt(rates.length() - 1);
            StringBuilder rs = new StringBuilder();
            for (int i = 0; i < rates.length(); i++) rs.append(i > 0 ? "/" : "").append(rates.optInt(i));
            Sheet s = new Sheet(this, u).title("屏幕刷新率",
                    "支持 " + rs + "Hz · 当前 " + r.optInt("current", -1) + "Hz。锁定后忽略系统的按应用限频；熄屏后可能需重设。");
            for (int i = rates.length() - 1; i >= 0; i--) {
                int fps = rates.optInt(i);
                s.action("锁定 " + fps + "Hz" + (fps == max ? "（最高）" : ""),
                        fps == max ? Sheet.PRIMARY : Sheet.NORMAL, () -> applyRefresh(fps));
            }
            s.action("交还系统（自动）", Sheet.NORMAL, () -> applyRefresh(0));
            s.action("取消", Sheet.NORMAL, null);
            s.show();
        });
    }

    private void applyRefresh(int fps) {
        mon.call("REFRESH_SET " + fps, r ->
                snack(r.optBoolean("ok") ? r.optString("msg") : (r.has("error") ? r.optString("error") : "设置失败"),
                        r.optBoolean("ok")));
    }

    // ------------------------------------------------------------------ VPN 隐身

    private SharedPreferences hidePrefs() {
        return getSharedPreferences("hide", MODE_PRIVATE);
    }

    private java.util.Set<String> vpnHidePkgs() {
        return new java.util.HashSet<>(hidePrefs().getStringSet("vpn_pkgs", java.util.Collections.emptySet()));
    }

    private String vpnHideSummary() {
        int n = vpnHidePkgs().size();
        return n == 0 ? "尚未选择任何应用" : "已对 " + n + " 个应用启用";
    }

    /** 可点击的导航行（右侧箭头），用于打开二级面板。 */
    private View navRow(String title, String desc, Runnable onClick) {
        LinearLayout r = u.row();
        r.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        r.setBackground(u.ripple(Ui.round(u.sunken, u.dp(14))));
        LinearLayout c = u.col();
        c.addView(u.text(title, 15, u.fg, true));
        TextView d = u.text(desc, 12, u.dim, false);
        d.setLineSpacing(u.dp(1), 1f);
        c.addView(d, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        r.addView(c, Ui.weight(1));
        r.addView(u.text("›", 20, u.dim, true), Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    private void openVpnHidePicker() {
        snack("正在读取应用列表…", true);
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                // 显示第三方应用 + 有图标的已更新系统应用；排除自己
                if ("com.thermalhunter".equals(ai.packageName)) continue;
                if (system && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                apps.add(ai);
            }
            apps.sort((a, b) -> pm.getApplicationLabel(a).toString()
                    .compareToIgnoreCase(pm.getApplicationLabel(b).toString()));
            java.util.Set<String> selected = vpnHidePkgs();
            ui.post(() -> showVpnHidePicker(apps, selected));
        }, "app-list").start();
    }

    private void showVpnHidePicker(List<ApplicationInfo> apps, java.util.Set<String> selected) {
        ui.removeCallbacks(hideSnack);
        snack.setVisibility(View.GONE);
        PackageManager pm = getPackageManager();
        java.util.Set<String> working = new java.util.HashSet<>(selected);
        Sheet s = new Sheet(this, u).title("VPN 隐身", "勾选的应用在运行时将检测不到 VPN（仍需在 LSPosed 作用域里包含它们）");

        // 固定在顶部的搜索框
        EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(u.fg);
        search.setHintTextColor(u.dim);
        search.setBackground(Ui.round(u.sunken, u.dp(12)));
        search.setPadding(u.dp(14), u.dp(11), u.dp(14), u.dp(11));

        LinearLayout list = u.col();
        final List<View> rows = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            String pkg = ai.packageName;
            String label = pm.getApplicationLabel(ai).toString();
            LinearLayout r = u.row();
            r.setPadding(u.dp(12), u.dp(10), u.dp(12), u.dp(10));
            r.setBackground(Ui.round(u.sunken, u.dp(14)));
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Throwable ignored) {
            }
            r.addView(icon, Ui.lp(u.dp(34), u.dp(34), 0, 0, u.dp(10), 0));
            LinearLayout c = u.col();
            TextView t = u.text(label, 14, u.fg, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            c.addView(t);
            TextView pt = u.text(pkg, 11, u.dim, false);
            pt.setSingleLine(true);
            pt.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            c.addView(pt);
            r.addView(c, Ui.weight(1));
            Switch sw = new Switch(this);
            sw.setChecked(working.contains(pkg));
            sw.setOnCheckedChangeListener((b, on) -> {
                if (on) working.add(pkg);
                else working.remove(pkg);
            });
            r.addView(sw, Ui.lp(-2, -2, u.dp(8), 0, 0, 0));
            r.setOnClickListener(v -> sw.toggle());
            list.addView(r, Ui.lp(-1, -2, 0, rows.isEmpty() ? 0 : u.dp(8), 0, 0));
            rows.add(r);
            keys.add((label + " " + pkg).toLowerCase(Locale.ROOT));
        }
        TextView empty = u.text("没有匹配的应用", 13, u.dim, false);
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
                    boolean match = q.isEmpty() || keys.get(i).contains(q);
                    rows.get(i).setVisibility(match ? View.VISIBLE : View.GONE);
                    if (match) shown++;
                }
                empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
            }
        });

        s.pin(search);
        s.add(list);
        s.action("保存", Sheet.PRIMARY, () -> {
            hidePrefs().edit().putStringSet("vpn_pkgs", working).apply();
            snack(working.isEmpty() ? "已清空 VPN 隐身列表"
                    : "已对 " + working.size() + " 个应用启用，请在 LSPosed 作用域里包含它们并重启该应用", true);
        });
        s.action("取消", Sheet.NORMAL, null);
        s.show();
    }

    // ------------------------------------------------------------------ 悬浮窗

    private void toggleFloat() {
        if (FloatService.isRunning()) {
            FloatService.stop(this);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            pendingFloat = true;
            snack("请允许「热源猎手」显示在其他应用上层", true);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                pendingFloat = false;
                snack("无法打开悬浮窗权限设置，请在系统设置中手动允许", false);
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
            if (!e.enabled) sub = "已禁用 · " + sub;
            h.sub.setText(sub);
            String m = metrics(e);
            h.metrics.setText(m);
            h.metrics.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);

            // 右侧主数值 + 占用条
            double frac;
            if (tab == 3) {
                h.value.setText(e.wlFrac >= 0 ? Math.round(e.wlFrac * 100) + "%" : "--");
                h.caption.setText("持锁");
                frac = Math.max(0, e.wlFrac);
            } else if (e.type == Analyzer.KSU && e.measuredCpu > 0.05) {
                double v = e.cpuAvg + e.measuredCpu;
                h.value.setText(Analyzer.fmt(v) + "%");
                h.caption.setText("CPU（含注入）");
                frac = v / 100;
            } else if (e.type == Analyzer.LSP && e.measuredCpu >= 0) {
                h.value.setText(Analyzer.fmt(e.measuredCpu) + "%");
                h.caption.setText("实测 CPU");
                frac = e.measuredCpu / 100;
            } else if (e.type == Analyzer.LSP) {
                // 没有实测数据：显示宿主进程的占用并注明
                if (hasAppHost(e)) {
                    h.value.setText(Analyzer.fmt(e.scopeCpu) + "%");
                    h.caption.setText("宿主进程 CPU");
                    frac = e.scopeCpu / 100;
                } else if (e.injectsSystem && e.systemCpu >= 0) {
                    h.value.setText(Analyzer.fmt(e.systemCpu) + "%");
                    h.caption.setText("system_server·共用");
                    frac = 0;
                } else {
                    h.value.setText("--");
                    h.caption.setText("宿主未运行");
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
            if (e.isProtected) tags.add("受保护");
            if (!tags.isEmpty()) rs.append(rs.length() > 0 ? "\n" : "").append("🏷 ").append(TextUtils.join(" · ", tags));
            h.reasons.setText(rs);
            h.reasons.setTextColor(e.level == Analyzer.BAD ? u.bad : e.level == Analyzer.WARN ? u.warn : u.dim);
            h.reasons.setVisibility(rs.length() == 0 ? View.GONE : View.VISIBLE);
            if (e.level != Analyzer.OK) {
                int bc = u.levelColor(e.level);
                h.badge.setText(e.level == Analyzer.BAD ? "异常" : "可疑");
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
