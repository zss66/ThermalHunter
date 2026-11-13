package com.thermalhunter.root;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 屏幕刷新率。
 * 部分 ROM（如 vivo）不理会 AOSP 的 min/peak_refresh_rate，而是用自己的策略按应用限频。
 * 因此改用 SurfaceFlinger 的 1035 事务直接锁定面板模式——这会无视系统的按应用限频，对所有界面统一生效。
 * 注意：此覆盖通常在熄屏/亮屏后被系统策略重置，需要时重新设置。
 */
final class Display {
    private Display() {}

    /** 当前锁定的 SurfaceFlinger 模式索引；-1 表示未锁定。由 set() 维护，hold() 周期性重应用。 */
    private static volatile int lockedIdx = -1;

    // DisplayMode{id=0, width=1260, height=2800, ... peakRefreshRate=144.00002 ...}
    private static final Pattern MODE = Pattern.compile(
            "DisplayMode\\{id=(\\d+), width=(\\d+), height=(\\d+),[^}]*?peakRefreshRate=([0-9.]+)");

    /** 当前分辨率下的 SurfaceFlinger 模式：刷新率(取整) → 模式索引。 */
    private static Map<Integer, Integer> sfModes() {
        String dump = String.valueOf(Sys.execMax(1 << 20, "dumpsys", "display")[1]);
        // 找出面板最大分辨率（当前物理分辨率）
        long bestRes = 0;
        Matcher m = MODE.matcher(dump);
        while (m.find()) {
            long res = Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
            if (res > bestRes) bestRes = res;
        }
        Map<Integer, Integer> map = new LinkedHashMap<>();
        m = MODE.matcher(dump);
        while (m.find()) {
            long res = Long.parseLong(m.group(2)) * Long.parseLong(m.group(3));
            if (res != bestRes) continue;
            int fps = Math.round(Float.parseFloat(m.group(4)));
            map.put(fps, Integer.parseInt(m.group(1)));
        }
        return map;
    }

    static JSONObject info() throws Exception {
        Map<Integer, Integer> modes = sfModes();
        List<Integer> rates = new ArrayList<>(modes.keySet());
        Collections.sort(rates);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("rates", new JSONArray(rates));
        o.put("current", currentFps());
        return o;
    }

    /** 锁定某一刷新率（对所有应用生效）。fps<=0 表示交还系统（解除锁定）。 */
    static JSONObject set(int fps) throws Exception {
        JSONObject o = new JSONObject();
        if (fps <= 0) {
            lockedIdx = -1;
            // 交还系统：清掉 AOSP 覆盖；SurfaceFlinger 的锁定会在下次熄/亮屏后自动释放
            Sys.exec("settings", "delete", "system", "min_refresh_rate");
            Sys.exec("settings", "delete", "system", "peak_refresh_rate");
            o.put("ok", true);
            o.put("msg", "已交还系统控制（锁定会在熄屏/亮屏后释放）");
            o.put("current", currentFps());
            return o;
        }
        Map<Integer, Integer> modes = sfModes();
        Integer idx = modes.get(fps);
        if (idx == null) {
            o.put("ok", false);
            o.put("error", "该屏幕不支持 " + fps + "Hz");
            return o;
        }
        // AOSP 提示 + SurfaceFlinger 强制
        Sys.exec("settings", "put", "system", "min_refresh_rate", String.valueOf(fps));
        Sys.exec("settings", "put", "system", "peak_refresh_rate", String.valueOf(fps));
        lockedIdx = idx;
        Object[] r = Sys.exec("service", "call", "SurfaceFlinger", "1035", "i32", String.valueOf(idx));
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
        int got = currentFps();
        boolean ok = (int) r[0] == 0 && (got == fps || got < 0);
        o.put("ok", ok);
        if (ok) o.put("msg", "已锁定 " + fps + "Hz（对所有应用生效；热源猎手开启时自动保持，完全退出后交还系统）");
        else o.put("error", "设置未生效（当前 " + got + "Hz）；可能需在系统设置里关闭「智能切换刷新率」");
        o.put("current", got);
        return o;
    }

    private static final Pattern DM_MODE = Pattern.compile("\\{id=(\\d+), width=\\d+, height=\\d+, fps=([0-9.]+)");

    /** 当前锁定的面板模式刷新率（取整）。读 mActiveModeId 而非 renderFrameRate，后者随画面内容变化。 */
    /** 重新应用已锁定的刷新率（搭 SNAPSHOT 每 3 秒一次；未锁定时为空操作，仅一次廉价的 binder 调用）。 */
    static void hold() {
        int idx = lockedIdx;
        if (idx < 0) return;
        try {
            Sys.exec("service", "call", "SurfaceFlinger", "1035", "i32", String.valueOf(idx));
        } catch (Throwable ignored) {
        }
    }

    private static int currentFps() {
        String dump = String.valueOf(Sys.execMax(1 << 20, "dumpsys", "display")[1]);
        Matcher a = Pattern.compile("mActiveModeId=(\\d+)").matcher(dump);
        if (!a.find()) return -1;
        String active = a.group(1);
        Matcher m = DM_MODE.matcher(dump);
        while (m.find()) {
            if (m.group(1).equals(active)) return Math.round(Float.parseFloat(m.group(2)));
        }
        return -1;
    }
}
