package com.thermalhunter.ui;

import com.thermalhunter.core.L;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.thermalhunter.R;
import com.thermalhunter.core.DrainStats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「耗电」页：自上次充满电以来的长时耗电统计（数据来自系统 batterystats）。
 * 只在进入本页或手动刷新时读取一次，不参与 3 秒一次的实时采样。
 */
final class DrainPage {

    private static final long MIN = 60_000L, HOUR = 3_600_000L;
    /** 距上次读取超过这个时间，再次进入本页时自动刷新。 */
    private static final long STALE_MS = 2 * MIN;

    private final MainActivity a;
    private final Ui u;
    final ScrollView view;
    private final LinearLayout body;
    private final TextView sub;
    private JSONObject report;
    private long fetchedAt;
    private boolean loading, askedNotify;

    DrainPage(MainActivity a, Ui u) {
        this.a = a;
        this.u = u;
        view = new ScrollView(a);
        view.setVerticalScrollBarEnabled(false);
        view.setFillViewport(true);
        LinearLayout col = u.col();
        col.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(24));

        LinearLayout top = u.row();
        LinearLayout tc = u.col();
        tc.addView(u.text(L.s(R.string.dp_title), 24, u.fg, true));
        sub = u.text(L.s(R.string.dp_sub_default), 12, u.dim, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        tc.addView(sub);
        top.addView(tc, Ui.weight(1));
        top.addView(u.iconButton(R.drawable.ic_refresh, L.s(R.string.refresh), v -> refresh()), Ui.lp(u.dp(42), u.dp(42), u.dp(8), 0, 0, 0));
        col.addView(top);

        body = u.col();
        col.addView(body, Ui.lp(-1, -2, 0, u.dp(14), 0, 0));
        view.addView(col, new ViewGroup.LayoutParams(-1, -2));

        // 先展示上次保存的报告，读取完成后替换
        JSONObject last = DrainWatcher.lastReport(a);
        if (last != null && last.optBoolean("ok")) {
            try {
                DrainStats.analyze(last, a.getPackageName());
            } catch (Exception ignored) {
            }
            report = last;
        }
        render();
    }

    /** 切到本页时调用：数据过旧则自动刷新。 */
    void onShown() {
        if (System.currentTimeMillis() - fetchedAt > STALE_MS) refresh();
        // 后台提醒需要通知权限：第一次进入本页时申请一次
        if (!askedNotify && DrainWatcher.enabled(a) && Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotify = true;
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    void refresh() {
        if (loading) return;
        loading = true;
        sub.setText(L.s(R.string.dp_loading_sub));
        a.mon().call("DRAIN", r -> {
            loading = false;
            fetchedAt = System.currentTimeMillis();
            if (!r.optBoolean("ok")) {
                sub.setText(L.s(R.string.dp_read_failed));
                a.snack(L.s(R.string.dp_read_failed_msg, r.optString("error")), false);
                if (report == null) render();
                return;
            }
            try {
                DrainStats.analyze(r, a.getPackageName());
            } catch (Exception ignored) {
            }
            report = r;
            render();
            final android.content.Context app = a.getApplicationContext();
            new Thread(() -> DrainWatcher.record(app, r), "drain-record").start();
        });
    }

    /** 底部导航上的提示点：有严重异常时为 true。 */
    boolean hasBad() {
        if (report == null) return false;
        if (report.optInt("badApps") > 0) return true;
        JSONArray is = report.optJSONArray("issues");
        for (int i = 0; is != null && i < is.length(); i++) {
            if (is.optJSONObject(i).optInt("level") == DrainStats.BAD) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        body.removeAllViews();
        JSONObject r = report;
        if (r == null) {
            TextView t = u.text(L.s(loading ? R.string.loading : R.string.dp_no_data), 13, u.dim, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, u.dp(48), 0, u.dp(48));
            body.addView(t, new LinearLayout.LayoutParams(-1, -2));
            body.addView(watchRow(), Ui.lp(-1, -2, 0, u.dp(8), 0, 0));
            return;
        }
        long age = System.currentTimeMillis() - r.optLong("t");
        if (!loading) {
            sub.setText(L.s(R.string.dp_sub_updated, age < MIN ? L.s(R.string.just_updated) : L.s(R.string.updated_ago, ago(age))));
        }

        body.addView(summaryCard(r));

        long onBattery = r.optLong("onBatteryMs");
        if (onBattery < 30 * MIN) {
            body.addView(u.note(L.s(R.string.dp_too_short, DrainStats.dur(onBattery)), u.accent), Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
        }
        JSONArray issues = r.optJSONArray("issues");
        for (int i = 0; issues != null && i < issues.length(); i++) {
            JSONObject o = issues.optJSONObject(i);
            body.addView(u.note(o.optString("msg"), o.optInt("level") == DrainStats.BAD ? u.bad : u.warn),
                    Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
        }

        // 应用：异常在前，其余按耗电排序
        JSONArray apps = r.optJSONArray("apps");
        List<JSONObject> flagged = new ArrayList<>(), rest = new ArrayList<>();
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject o = apps.optJSONObject(i);
            (o.optInt("level") != DrainStats.OK ? flagged : rest).add(o);
        }
        flagged.sort((x, y) -> Integer.compare(y.optInt("level"), x.optInt("level")));
        double total = Math.max(1, r.optDouble("uidMah", 0));
        if (!flagged.isEmpty()) {
            section(L.s(R.string.dp_sec_flagged, flagged.size()));
            for (JSONObject o : flagged) body.addView(appCard(o, total), Ui.lp(-1, -2, 0, 0, 0, u.dp(8)));
        }
        section(L.s(R.string.dp_sec_ranking));
        int shown = 0;
        for (JSONObject o : rest) {
            if (shown >= 25) break;
            body.addView(appCard(o, total), Ui.lp(-1, -2, 0, 0, 0, u.dp(8)));
            shown++;
        }
        if (shown == 0) body.addView(u.text(L.s(flagged.isEmpty() ? R.string.dp_no_app_data : R.string.dp_rest_low), 13, u.dim, false),
                Ui.lp(-1, -2, u.dp(4), 0, 0, 0));

        JSONArray comps = r.optJSONArray("components");
        if (comps != null && comps.length() > 0) {
            section(L.s(R.string.dp_sec_components));
            body.addView(barBox(comps, "mah", " mAh", 6));
        }
        JSONArray reasons = r.optJSONArray("wakeReasons");
        if (reasons != null && reasons.length() > 0) {
            section(L.s(R.string.dp_sec_wakeup_src));
            body.addView(timeBox(reasons));
        }
        JSONArray kwl = r.optJSONArray("kernelWakelocks");
        if (kwl != null && kwl.length() > 0) {
            section(L.s(R.string.dp_sec_kernel_wl));
            body.addView(timeBox(kwl));
        }

        JSONArray hist = DrainWatcher.history(a);
        if (hist.length() > 0) {
            section(L.s(R.string.dp_sec_history));
            for (int i = 0; i < hist.length() && i < 10; i++) {
                body.addView(historyCard(hist.optJSONObject(i)), Ui.lp(-1, -2, 0, 0, 0, u.dp(8)));
            }
        }

        section(L.s(R.string.dp_sec_watch));
        body.addView(watchRow());
        body.addView(u.note(L.s(R.string.dp_note), u.dim), Ui.lp(-1, -2, 0, u.dp(10), 0, 0));
    }

    private void section(String s) {
        body.addView(u.sectionTitle(s), Ui.lp(-1, -2, 0, u.dp(20), 0, u.dp(8)));
    }

    private View summaryCard(JSONObject r) {
        LinearLayout c = u.col();
        c.setPadding(u.dp(18), u.dp(16), u.dp(18), u.dp(16));
        int level = overallLevel(r);
        int[] grad = level == DrainStats.BAD ? Ui.GRAD_HOT : level == DrainStats.WARN ? Ui.GRAD_WARM : Ui.GRAD_COOL;
        c.setBackground(Ui.gradient(grad, u.dp(24)));
        c.setElevation(u.dp(2));

        LinearLayout top = u.row();
        LinearLayout l = u.col();
        l.addView(u.text(L.s(R.string.dp_used), 12, 0xDDFFFFFF, false));
        long pct = r.optLong("dischargePct");
        TextView big = u.text(pct + "%", 38, Color.WHITE, true);
        big.setFontFeatureSettings("tnum");
        l.addView(big);
        String mah = r.optLong("dischargeMah") > 0 ? L.s(R.string.dp_approx_mah, r.optLong("dischargeMah")) : "";
        l.addView(u.text(L.s(R.string.dp_on_battery, DrainStats.dur(r.optLong("onBatteryMs")), mah), 12, 0xDDFFFFFF, false));
        top.addView(l, Ui.weight(1));
        TextView verdict = u.text(L.s(level == DrainStats.BAD ? R.string.dp_verdict_bad : level == DrainStats.WARN ? R.string.dp_verdict_warn : R.string.dp_verdict_ok),
                12, Color.WHITE, true);
        verdict.setPadding(u.dp(10), u.dp(4), u.dp(10), u.dp(4));
        verdict.setBackground(Ui.round(0x33FFFFFF, u.dp(12)));
        LinearLayout.LayoutParams vp = Ui.lp(-2, -2, u.dp(8), 0, 0, 0);
        vp.gravity = Gravity.TOP;
        top.addView(verdict, vp);
        c.addView(top);

        // 亮屏 / 熄屏 时长比例条
        long on = r.optLong("screenOnMs"), off = r.optLong("screenOffMs");
        if (on + off > 0) {
            Ui.Bar bar = new Ui.Bar(a, 0x40FFFFFF);
            bar.set(on / (float) (on + off), Color.WHITE);
            c.addView(bar, Ui.lp(-1, u.dp(5), 0, u.dp(12), 0, u.dp(6)));
            LinearLayout lg = u.row();
            lg.addView(u.text(L.s(R.string.dp_screen_on, DrainStats.dur(on)), 11, 0xDDFFFFFF, false), Ui.weight(1));
            lg.addView(u.text(L.s(R.string.dp_screen_off, DrainStats.dur(off)), 11, 0xDDFFFFFF, false));
            c.addView(lg);
        }

        // 三个核心指标
        LinearLayout tiles = new LinearLayout(a);
        double active = r.optDouble("activeRate", -1), standby = r.optDouble("standbyRate", -1);
        double awake = r.optDouble("awakeRatio", -1);
        tiles.addView(heroTile(L.s(R.string.dp_tile_active), active >= 0 ? fmtRate(active) : "--"), Ui.weight(1));
        LinearLayout.LayoutParams p2 = Ui.weight(1);
        p2.leftMargin = u.dp(8);
        tiles.addView(heroTile(L.s(R.string.dp_tile_standby), standby >= 0 ? fmtRate(standby) : "--"), p2);
        LinearLayout.LayoutParams p3 = Ui.weight(1);
        p3.leftMargin = u.dp(8);
        tiles.addView(heroTile(L.s(R.string.dp_tile_deep), awake >= 0 ? Math.round((1 - awake) * 100) + "%" : "--"), p3);
        c.addView(tiles, Ui.lp(-1, -2, 0, u.dp(12), 0, 0));
        return c;
    }

    private View heroTile(String label, String value) {
        LinearLayout t = u.col();
        t.setPadding(u.dp(10), u.dp(8), u.dp(10), u.dp(9));
        t.setBackground(Ui.round(0x26FFFFFF, u.dp(14)));
        TextView l = u.text(label, 11, 0xDDFFFFFF, false);
        l.setSingleLine(true);
        TextView v = u.text(value, 15, Color.WHITE, true);
        v.setFontFeatureSettings("tnum");
        v.setSingleLine(true);
        t.addView(l);
        t.addView(v, Ui.lp(-2, -2, 0, u.dp(2), 0, 0));
        return t;
    }

    private static int overallLevel(JSONObject r) {
        int lv = r.optInt("badApps") > 0 ? DrainStats.BAD : r.optInt("warnApps") > 0 ? DrainStats.WARN : DrainStats.OK;
        JSONArray is = r.optJSONArray("issues");
        for (int i = 0; is != null && i < is.length(); i++) lv = Math.max(lv, is.optJSONObject(i).optInt("level"));
        return lv;
    }

    // ------------------------------------------------------------------ 应用卡片与详情

    private View appCard(JSONObject o, double total) {
        String name = DrainWatcher.label(a, o);
        int level = o.optInt("level");
        LinearLayout row = u.row();
        row.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        row.setGravity(Gravity.TOP);
        boolean badCard = level == DrainStats.BAD;
        GradientDrawable bgd = Ui.round(badCard ? u.badCard : u.card, u.dp(18));
        bgd.setStroke(u.dp(1), badCard ? Ui.alpha(u.bad, 0x55) : u.stroke);
        row.setBackground(u.ripple(bgd));

        FrameLayout av = u.avatar(40);
        u.bindAvatar(av, icon(o), name, o.optInt("uid") % 100000 < 10000 ? 0xFF0EA5E9 : 0xFF10B981);
        row.addView(av, Ui.lp(u.dp(40), u.dp(40), 0, 0, u.dp(12), 0));

        LinearLayout c = u.col();
        LinearLayout tr = u.row();
        TextView t = u.text(name, 15, u.fg, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        tr.addView(t, Ui.weight(1));
        if (level != DrainStats.OK) {
            int lc = level == DrainStats.BAD ? u.bad : u.warn;
            TextView badge = u.chip(L.s(level == DrainStats.BAD ? R.string.level_bad : R.string.level_warn), lc);
            badge.setTextSize(10);
            tr.addView(badge, Ui.lp(-2, -2, u.dp(6), 0, 0, 0));
        }
        c.addView(tr);
        TextView m = u.text(metrics(o), 12, u.dim, false);
        m.setLineSpacing(u.dp(1), 1f);
        c.addView(m, Ui.lp(-1, -2, 0, u.dp(3), 0, 0));
        double mah = o.optDouble("mah", 0);
        Ui.Bar bar = new Ui.Bar(a, u.track);
        bar.set((float) (mah / total), level == DrainStats.OK ? u.accent : level == DrainStats.BAD ? u.bad : u.warn);
        c.addView(bar, Ui.lp(-1, u.dp(3), 0, u.dp(8), 0, 0));
        String rs = reasonsText(o);
        if (!rs.isEmpty()) {
            TextView rv = u.text(rs, 12, level == DrainStats.BAD ? u.bad : u.warn, false);
            rv.setLineSpacing(u.dp(1), 1f);
            c.addView(rv, Ui.lp(-1, -2, 0, u.dp(6), 0, 0));
        }
        row.addView(c, Ui.weight(1));

        LinearLayout vc = u.col();
        vc.setGravity(Gravity.END);
        TextView v = u.text(mah >= 10 ? String.valueOf(Math.round(mah)) : String.format(Locale.US, "%.1f", mah),
                17, level == DrainStats.OK ? u.fg : level == DrainStats.BAD ? u.bad : u.warn, true);
        v.setFontFeatureSettings("tnum");
        vc.addView(v);
        vc.addView(u.text("mAh · " + Math.round(mah / total * 100) + "%", 10, u.dim, false));
        row.addView(vc, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));

        row.setOnClickListener(x -> showDetail(o, name, total));
        return row;
    }

    private Drawable icon(JSONObject o) {
        JSONArray pk = o.optJSONArray("pkgs");
        if (o.optInt("uid") % 100000 < 10000 || pk == null || pk.length() == 0) return null;
        return (Drawable) u.appInfo(pk.optString(0))[1];
    }

    private static String metrics(JSONObject o) {
        List<String> p = new ArrayList<>();
        p.add(L.s(R.string.dp_m_fg, DrainStats.dur(o.optLong("topMs"))));
        long bgWl = o.optLong("bgWlMs") > 0 ? o.optLong("bgWlMs") : o.optLong("wlMs");
        if (bgWl >= MIN) p.add(L.s(o.optLong("bgWlMs") > 0 ? R.string.dp_m_bg_wl : R.string.dp_m_wl, DrainStats.dur(bgWl)));
        if (o.optLong("alarms") > 0) p.add(L.s(R.string.dp_m_wakeups, o.optLong("alarms")));
        if (o.optLong("cpuMs") >= MIN) p.add("CPU " + DrainStats.dur(o.optLong("cpuMs")));
        if (o.optLong("gpsMs") >= MIN) p.add(L.s(R.string.dp_m_gps, DrainStats.dur(o.optLong("gpsMs"))));
        return TextUtils.join(" · ", p);
    }

    private static String reasonsText(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        JSONArray r = o.optJSONArray("reasons"), w = o.optJSONArray("warnings");
        for (int i = 0; r != null && i < r.length(); i++) sb.append(sb.length() > 0 ? "\n" : "").append("❗ ").append(r.optString(i));
        for (int i = 0; w != null && i < w.length(); i++) sb.append(sb.length() > 0 ? "\n" : "").append("⚠ ").append(w.optString(i));
        return sb.toString();
    }

    private void showDetail(JSONObject o, String name, double total) {
        Sheet s = new Sheet(a, u);
        int uid = o.optInt("uid");
        JSONArray pk = o.optJSONArray("pkgs");

        LinearLayout head = u.row();
        FrameLayout av = u.avatar(52);
        u.bindAvatar(av, icon(o), name, uid % 100000 < 10000 ? 0xFF0EA5E9 : 0xFF10B981);
        head.addView(av, Ui.lp(u.dp(52), u.dp(52), 0, 0, u.dp(14), 0));
        LinearLayout tc = u.col();
        TextView title = u.text(name, 19, u.fg, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        tc.addView(title);
        String pkgs = pk == null || pk.length() == 0 ? "" : (pk.length() > 1 ? L.s(R.string.dp_pkgs_more, pk.optString(0), pk.length()) : pk.optString(0));
        TextView st = u.text("uid " + uid + (pkgs.isEmpty() ? "" : " · " + pkgs), 12, u.dim, false);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tc.addView(st, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        head.addView(tc, Ui.weight(1));
        s.add(head, Ui.lp(-1, -2, 0, 0, 0, 0));

        double mah = o.optDouble("mah", 0);
        int lc = o.optInt("level") == DrainStats.BAD ? u.bad : o.optInt("level") == DrainStats.WARN ? u.warn : u.fg;
        List<Object[]> stats = new ArrayList<>();
        stats.add(new Object[]{L.s(R.string.st_est_drain), String.format(Locale.US, "%.1f mAh", mah), lc});
        stats.add(new Object[]{L.s(R.string.st_share), Math.round(mah / total * 100) + "%", u.fg});
        stats.add(new Object[]{L.s(R.string.st_fg_use), DrainStats.dur(o.optLong("topMs")), u.fg});
        stats.add(new Object[]{L.s(R.string.st_bg_wl), o.optLong("bgWlMs") > 0 ? DrainStats.dur(o.optLong("bgWlMs")) : "--", u.fg});
        stats.add(new Object[]{L.s(R.string.st_wl_total), DrainStats.dur(o.optLong("wlMs")), u.fg});
        stats.add(new Object[]{L.s(R.string.st_alarms), L.s(R.string.times, o.optLong("alarms")), u.fg});
        stats.add(new Object[]{L.s(R.string.st_cpu_time), DrainStats.dur(o.optLong("cpuMs")), u.fg});
        stats.add(new Object[]{L.s(R.string.comp_location), o.optLong("gpsMs") > 0 ? DrainStats.dur(o.optLong("gpsMs")) : "--", u.fg});
        stats.add(new Object[]{L.s(R.string.st_bg_run), DrainStats.dur(o.optLong("bgMs") + o.optLong("fgsMs")), u.fg});
        s.add(a.statGrid(stats), Ui.lp(-1, -2, 0, u.dp(16), 0, 0));

        JSONArray r = o.optJSONArray("reasons"), w = o.optJSONArray("warnings");
        if ((r != null && r.length() > 0) || (w != null && w.length() > 0)) {
            s.section(L.s(R.string.sec_diagnosis));
            for (int i = 0; r != null && i < r.length(); i++) s.add(u.note(r.optString(i), u.bad));
            for (int i = 0; w != null && i < w.length(); i++) s.add(u.note(w.optString(i), u.warn));
        }
        JSONArray wl = o.optJSONArray("wakelocks");
        if (wl != null && wl.length() > 0) {
            s.section(L.s(R.string.dp_sec_wakelocks));
            s.add(timeBox(wl));
        }
        JSONArray al = o.optJSONArray("alarmTags");
        if (al != null && al.length() > 0) {
            s.section(L.s(R.string.dp_sec_alarms));
            LinearLayout box = box();
            for (int i = 0; i < al.length(); i++) {
                JSONObject x = al.optJSONObject(i);
                box.addView(kvRow(x.optString("name"), L.s(R.string.times, x.optLong("n"))), Ui.lp(-1, -2, 0, i > 0 ? u.dp(5) : 0, 0, 0));
            }
            s.add(box);
        }

        // 操作：仅限单一包名的普通应用
        if (uid % 100000 >= 10000 && pk != null && pk.length() == 1 && !pk.optString(0).equals(a.getPackageName())) {
            String pkg = pk.optString(0);
            int user = uid / 100000;
            s.action(L.s(R.string.act_force_stop), Sheet.PRIMARY, () -> a.run("FORCESTOP " + pkg + " " + user));
            s.action(L.s(R.string.act_disable_app), Sheet.DANGER, () -> a.confirm(L.s(R.string.confirm_disable_title, name),
                    L.s(R.string.dp_disable_msg), L.s(R.string.act_disable),
                    () -> a.run("APP_DISABLE " + pkg + " " + user)));
        }
        s.action(L.s(R.string.close), Sheet.NORMAL, null);
        s.show();
    }

    // ------------------------------------------------------------------ 其他区块

    private View historyCard(JSONObject h) {
        LinearLayout c = u.col();
        c.setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12));
        c.setBackground(u.card(u.dp(18)));
        LinearLayout tr = u.row();
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd HH:mm");
        String when = DateFormat.format(pattern, new Date(h.optLong("start"))) + " → "
                + DateFormat.format(pattern, new Date(h.optLong("end")));
        tr.addView(u.text(when, 13, u.fg, true), Ui.weight(1));
        if (h.optInt("badApps") > 0) tr.addView(u.chip(L.s(R.string.dp_bad_count, h.optInt("badApps")), u.bad));
        c.addView(tr);
        List<String> p = new ArrayList<>();
        p.add(L.s(R.string.dp_h_drain, h.optLong("dischargePct"), DrainStats.dur(h.optLong("onBatteryMs"))));
        double sb = h.optDouble("standbyRate", -1), ac = h.optDouble("activeRate", -1), aw = h.optDouble("awakeRatio", -1);
        if (ac >= 0) p.add(L.s(R.string.dp_screen_on, fmtRate(ac)));
        if (sb >= 0) p.add(L.s(R.string.dp_h_standby, fmtRate(sb)));
        if (aw >= 0) p.add(L.s(R.string.dp_h_deep, Math.round((1 - aw) * 100) + "%"));
        TextView m = u.text(TextUtils.join(" · ", p), 12, sb >= 3 ? u.bad : sb >= 1.5 ? u.warn : u.dim, false);
        c.addView(m, Ui.lp(-1, -2, 0, u.dp(3), 0, 0));
        JSONArray top = h.optJSONArray("top");
        if (top != null && top.length() > 0) {
            List<String> t = new ArrayList<>();
            for (int i = 0; i < top.length(); i++) {
                JSONObject x = top.optJSONObject(i);
                t.add(x.optString("name") + " " + Math.round(x.optDouble("mah")) + "mAh");
            }
            TextView tv = u.text(L.s(R.string.dp_h_top3, TextUtils.join(L.s(R.string.list_sep), t)), 12, u.dim, false);
            c.addView(tv, Ui.lp(-1, -2, 0, u.dp(2), 0, 0));
        }
        return c;
    }

    private View watchRow() {
        boolean root = !"shell".equals(a.connMode());
        String desc = L.s(R.string.watch_desc) + (root ? "" : L.s(R.string.watch_need_root));
        return a.switchRow(L.s(R.string.watch_title), desc, DrainWatcher.enabled(a), on -> {
            DrainWatcher.setEnabled(a, on);
            a.snack(L.s(on ? R.string.watch_on : R.string.watch_off), true);
        });
    }

    private LinearLayout box() {
        LinearLayout box = u.col();
        box.setPadding(u.dp(14), u.dp(10), u.dp(14), u.dp(10));
        box.setBackground(u.card(u.dp(16)));
        return box;
    }

    private View kvRow(String k, String v) {
        LinearLayout r = u.row();
        TextView n = u.text(k, 12, u.fg, false);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(n, Ui.weight(1));
        TextView vv = u.text(v, 12, u.dim, false);
        vv.setFontFeatureSettings("tnum");
        r.addView(vv, Ui.lp(-2, -2, u.dp(10), 0, 0, 0));
        return r;
    }

    /** {name, ms, n?} 列表：名称 + 时长（+ 次数）。 */
    private View timeBox(JSONArray arr) {
        LinearLayout box = box();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            String v = DrainStats.dur(x.optLong("ms")) + (x.has("n") ? " · " + L.s(R.string.times, x.optLong("n")) : "");
            box.addView(kvRow(x.optString("name"), v), Ui.lp(-1, -2, 0, i > 0 ? u.dp(5) : 0, 0, 0));
        }
        return box;
    }

    /** 名称 + 数值 + 占比条。 */
    private View barBox(JSONArray arr, String key, String unit, int max) {
        LinearLayout box = box();
        double top = 0;
        for (int i = 0; i < arr.length(); i++) top = Math.max(top, arr.optJSONObject(i).optDouble(key));
        for (int i = 0; i < arr.length() && i < max; i++) {
            JSONObject x = arr.optJSONObject(i);
            double v = x.optDouble(key);
            box.addView(kvRow(x.optString("name"), Math.round(v) + unit), Ui.lp(-1, -2, 0, i > 0 ? u.dp(8) : 0, 0, 0));
            Ui.Bar b = new Ui.Bar(a, u.track);
            b.set(top > 0 ? (float) (v / top) : 0, u.accent);
            box.addView(b, Ui.lp(-1, u.dp(3), 0, u.dp(4), 0, 0));
        }
        return box;
    }

    private static String fmtRate(double r) {
        return L.s(R.string.rate_h, String.format(Locale.US, r >= 10 ? "%.0f" : "%.1f", r));
    }

    private static String ago(long ms) {
        if (ms < HOUR) return L.s(R.string.ago_min, ms / MIN);
        if (ms < 24 * HOUR) return L.s(R.string.ago_hour, ms / HOUR);
        return L.s(R.string.ago_day, ms / (24 * HOUR));
    }
}
