package com.thermalhunter.root;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 屏幕刷新率：读取支持的刷新率并强制全局 min/peak（绕过系统按应用限频的白名单）。 */
final class Display {
    private Display() {}

    private static final Pattern FPS = Pattern.compile("fps=([0-9]+)");

    static JSONObject info() throws Exception {
        JSONObject o = new JSONObject();
        List<Integer> rates = new ArrayList<>();
        Object[] r = Sys.execMax(1 << 20, "dumpsys", "display");
        if ((int) r[0] == 0) {
            Matcher m = FPS.matcher(String.valueOf(r[1]));
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= 24 && v <= 480 && !rates.contains(v)) rates.add(v);
            }
        }
        Collections.sort(rates);
        o.put("ok", true);
        o.put("rates", new JSONArray(rates));
        o.put("min", getSetting("min_refresh_rate"));
        o.put("peak", getSetting("peak_refresh_rate"));
        return o;
    }

    /** 固定全局刷新率区间。min==peak 即锁定某一档，对所有应用统一生效。 */
    static JSONObject set(double min, double peak) throws Exception {
        put("min_refresh_rate", min);
        put("peak_refresh_rate", peak);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("msg", "已设置刷新率 " + fmt(min) + "–" + fmt(peak) + "Hz（对所有应用生效）");
        o.put("min", getSetting("min_refresh_rate"));
        o.put("peak", getSetting("peak_refresh_rate"));
        return o;
    }

    private static void put(String key, double v) {
        // 整数档位用整数写，避免某些 ROM 不接受小数
        String s = v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
        Sys.exec("settings", "put", "system", key, s);
    }

    private static String getSetting(String key) {
        Object[] r = Sys.exec("settings", "get", "system", key);
        String v = (int) r[0] == 0 ? String.valueOf(r[1]).trim() : "";
        return v.isEmpty() || v.equals("null") ? "" : v;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
