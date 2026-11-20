package com.thermalhunter.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 界面语言。设置里可选「跟随系统」或指定语言，保存在 settings/lang（BCP 47 标签，空串 = 跟随系统）。
 * 文字放在标准的 res/values-xx/strings.xml；代码里用 {@code L.s(R.string.名称)} 取当前语言的文字。
 *
 * root 端进程（app_process）读不到 App 的资源，它返回的提示写成「文字令牌」
 * \u0001key\u0002参数…\u0003（见 root/T.java），由 {@link #tr} 在 App 端按当前语言换成文字。
 */
public final class L {

    /** 设置里可选的语言，"" 为跟随系统。 */
    public static final String[] TAGS = {"", "zh-CN", "zh-TW", "en", "ja", "ko", "ru", "de", "fr"};
    /** 各语言用自己的文字显示名称（第 0 项「跟随系统」由界面按当前语言显示）。 */
    public static final String[] NAMES = {null, "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский", "Deutsch", "Français"};

    private static final String PREF = "lang";
    private static Context app;
    private static volatile Resources override; // 指定了语言时的资源；跟随系统时为 null
    private static Locale systemDefault;
    private static final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private static final Pattern TOKEN = Pattern.compile("\u0001([^\u0001\u0003]*)\u0003");

    private L() {
    }

    /** 进程启动时调用一次（App.onCreate）。 */
    public static synchronized void init(Context c) {
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (systemDefault == null) systemDefault = Locale.getDefault();
        String tag = tag(app);
        if (tag.isEmpty()) {
            override = null;
            Locale.setDefault(systemDefault);
        } else {
            Locale loc = Locale.forLanguageTag(tag);
            override = app.createConfigurationContext(config(app, loc)).getResources();
            Locale.setDefault(loc);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** 当前设置的语言标签，"" 为跟随系统。 */
    public static String tag(Context c) {
        return prefs(c).getString(PREF, "");
    }

    public static void set(Context c, String tag) {
        prefs(c).edit().putString(PREF, tag == null ? "" : tag).commit();
        init(c);
    }

    private static Configuration config(Context c, Locale loc) {
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.setLocales(new LocaleList(loc));
        return cfg;
    }

    /** Activity / Service 的 attachBaseContext 里用：让界面组件（系统控件、排版方向等）也用所选语言。 */
    public static Context wrap(Context base) {
        String tag = tag(base);
        if (tag.isEmpty()) return base;
        return base.createConfigurationContext(config(base, Locale.forLanguageTag(tag)));
    }

    public static Resources res() {
        Resources r = override;
        return r != null ? r : app.getResources();
    }

    public static String s(int id) {
        return res().getString(id);
    }

    public static String s(int id, Object... args) {
        return res().getString(id, args);
    }

    // ------------------------------------------------------------------ root 端文字令牌

    /** 把字符串里的文字令牌换成当前语言的文字；没有令牌时原样返回。 */
    public static String tr(String s) {
        if (s == null || s.indexOf('\u0001') < 0) return s;
        Matcher m = TOKEN.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(token(m.group(1))));
        m.appendTail(out);
        return out.toString();
    }

    private static String token(String body) {
        String[] p = body.split("\u0002", -1);
        Integer id = ids.get(p[0]);
        if (id == null) {
            id = res().getIdentifier(p[0], "string", app.getPackageName());
            ids.put(p[0], id);
        }
        if (id == 0) return body.replace('\u0002', ' '); // 不认识的令牌（版本不一致）：至少显示参数
        if (p.length == 1) return s(id);
        Object[] args = new Object[p.length - 1];
        System.arraycopy(p, 1, args, 0, args.length);
        try {
            return s(id, args);
        } catch (Exception e) {
            return body.replace('\u0002', ' '); // 参数个数与译文不符（版本不一致）
        }
    }

    /** 递归替换 JSON 里所有字符串值中的文字令牌（root 端响应在这里统一翻译）。 */
    public static void trJson(Object o) {
        try {
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    Object v = j.opt(k);
                    if (v instanceof String) {
                        if (((String) v).indexOf('\u0001') >= 0) j.put(k, tr((String) v));
                    } else {
                        trJson(v);
                    }
                }
            } else if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v instanceof String) {
                        if (((String) v).indexOf('\u0001') >= 0) a.put(i, tr((String) v));
                    } else {
                        trJson(v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
