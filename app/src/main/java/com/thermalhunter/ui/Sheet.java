package com.thermalhunter.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.thermalhunter.R;

/** 统一风格的底部弹出面板，替代系统 AlertDialog。 */
final class Sheet {
    static final int NORMAL = 0, PRIMARY = 1, DANGER = 2, DANGER_FILLED = 3;

    private final Ui u;
    private final Dialog dialog;
    private final LinearLayout body, actions;

    Sheet(Context ctx, Ui u) {
        this.u = u;
        dialog = new Dialog(ctx, R.style.SheetTheme);
        LinearLayout panel = u.col();
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(u.card);
        float r = u.dp(26);
        bgd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bgd);
        panel.setPadding(u.dp(20), u.dp(10), u.dp(20), u.dp(20));

        View handle = new View(ctx);
        handle.setBackground(Ui.round(u.track, u.dp(2)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(u.dp(36), u.dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = u.dp(14);
        panel.addView(handle, hl);

        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        body = u.col();
        sv.addView(body, new ViewGroup.LayoutParams(-1, -2));
        // 内容过长时滚动区收缩，按钮始终可见
        panel.addView(sv, new LinearLayout.LayoutParams(-1, -2, 1f));
        actions = u.col();
        panel.addView(actions, Ui.lp(-1, -2, 0, u.dp(16), 0, 0));

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
    }

    Sheet title(String title, String sub) {
        body.addView(u.text(title, 20, u.fg, true));
        if (sub != null && !sub.isEmpty()) {
            TextView s = u.text(sub, 13, u.dim, false);
            s.setLineSpacing(u.dp(2), 1f);
            body.addView(s, Ui.lp(-1, -2, 0, u.dp(4), 0, 0));
        }
        return this;
    }

    Sheet section(String title) {
        body.addView(u.text(title, 13, u.dim, true), Ui.lp(-1, -2, 0, u.dp(18), 0, u.dp(8)));
        return this;
    }

    Sheet message(String msg) {
        TextView t = u.text(msg, 14, u.fg, false);
        t.setLineSpacing(u.dp(3), 1f);
        body.addView(t, Ui.lp(-1, -2, 0, u.dp(8), 0, 0));
        return this;
    }

    Sheet add(View v) {
        return add(v, Ui.lp(-1, -2, 0, u.dp(8), 0, 0));
    }

    Sheet add(View v, LinearLayout.LayoutParams lp) {
        body.addView(v, lp);
        return this;
    }

    Sheet action(String label, int style, Runnable r) {
        TextView b = u.text(label, 15, u.fg, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, u.dp(13), 0, u.dp(13));
        int radius = u.dp(16);
        switch (style) {
            case PRIMARY:
                b.setTextColor(Color.WHITE);
                b.setBackground(u.ripple(Ui.round(u.accent, radius)));
                break;
            case DANGER_FILLED:
                b.setTextColor(Color.WHITE);
                b.setBackground(u.ripple(Ui.round(u.bad, radius)));
                break;
            case DANGER:
                b.setTextColor(u.bad);
                b.setBackground(u.ripple(Ui.round(Ui.tint(u.bad), radius)));
                break;
            default:
                b.setBackground(u.ripple(Ui.round(u.sunken, radius)));
        }
        b.setOnClickListener(v -> {
            dialog.dismiss();
            if (r != null) r.run();
        });
        actions.addView(b, Ui.lp(-1, -2, 0, actions.getChildCount() > 0 ? u.dp(8) : 0, 0, 0));
        return this;
    }

    void show() {
        actions.setVisibility(actions.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }
}
