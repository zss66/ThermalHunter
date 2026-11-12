package com.thermalhunter.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** App 端与 root 端共用的保护名单：误杀这些会导致死机、重启、掉 root 或失去 LSPosed。 */
public final class Protect {
    private Protect() {}

    /** 绝不允许杀死的进程（comm 或 cmdline 首段/文件名）。 */
    public static final Set<String> PROCS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "init", "kthreadd", "ueventd", "system_server", "zygote", "zygote64", "webview_zygote",
            "app_process", "app_process64", "surfaceflinger", "servicemanager", "hwservicemanager",
            "vndservicemanager", "lmkd", "logd", "vold", "netd", "installd", "keystore2", "adbd",
            "ksud", "magiskd", "lspd", "apd", "tombstoned", "statsd", "healthd", "storaged",
            "audioserver", "cameraserver", "media.swcodec", "mediaserver", "gatekeeperd", "credstore",
            "update_engine", "rild", "thermal_core", "thermald", "thermal-hal",
            "android.hardware.health-service.mediatek",
            "android.hardware.thermal-service.mediatek",
            "android.hardware.power-service.mediatek-libperfmgr",
            "thermalhunter_root",
            "com.android.phone", "com.android.systemui", "com.thermalhunter")));

    /** 不允许强行停止/停用的应用包（含 vivo/iQOO OriginOS 关键组件与 root/LSPosed 管理器）。 */
    public static final Set<String> PKGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "android", "system", "com.android.systemui", "com.android.phone", "com.android.settings",
            "com.android.providers.settings", "com.android.providers.media", "com.android.providers.telephony",
            "com.android.permissioncontroller", "com.android.shell", "com.android.networkstack",
            "com.google.android.networkstack", "com.android.se", "com.android.bluetooth", "com.android.nfc",
            "com.bbk.launcher2", "com.vivo.abe", "com.vivo.pem", "com.vivo.daemonService", "com.vivo.sps",
            "com.iqoo.secure", "com.vivo.permissionmanager", "com.vivo.upslide", "com.vivo.smartmultiwindow",
            "me.weishu.kernelsu", "com.rifsxd.ksunext", "com.sukisu.ultra", "io.github.lsposed.manager",
            "org.lsposed.manager", "com.topjohnwu.magisk", "me.bmax.apatch",
            "com.thermalhunter")));

    public static boolean isProtectedProcName(String name) {
        if (name == null) return false;
        String first = name.split(" ")[0];
        String base = first.contains("/") ? first.substring(first.lastIndexOf('/') + 1) : first;
        if (PROCS.contains(base) || PROCS.contains(first)) return true;
        int colon = first.indexOf(':');
        return PKGS.contains(colon > 0 ? first.substring(0, colon) : first);
    }
}
