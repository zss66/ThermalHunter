package com.thermalhunter.core;

import com.thermalhunter.R;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku（ADB / 无线调试）模式：借助 Shizuku 服务以 shell 身份（uid 2000）启动进程。
 * shell 权限小于 root：读不到 /data/adb、LSPosed 配置库，也不能杀死其他应用的进程。
 */
public final class ShizukuShell {
    private ShizukuShell() {}

    public static final int REQUEST_CODE = 0x5A1;

    /** Shizuku 服务不可用的原因；可用时返回 null。 */
    public static String unavailableReason() {
        try {
            if (!Shizuku.pingBinder()) {
                return L.s(R.string.err_shizuku_not_running);
            }
            if (Shizuku.isPreV11()) return L.s(R.string.err_shizuku_old);
            return null;
        } catch (Throwable e) {
            return L.s(R.string.err_shizuku_unavail, String.valueOf(e));
        }
    }

    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 用户选择了「拒绝且不再询问」，只能去 Shizuku 管理器里手动授权。 */
    public static boolean permanentlyDenied() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 弹出 Shizuku 授权对话框（主线程调用）。 */
    public static void request() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 以 shell 身份启动进程。Shizuku API 13 把 newProcess 设为 private（推荐改用 UserService），
     * 但这里只需要一个 stdin/stdout 管道，沿用与 su 相同的行协议最简单，故反射调用。
     */
    static Process newProcess(String... cmd) throws IOException {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, cmd, null, null);
        } catch (InvocationTargetException e) {
            throw new IOException(String.valueOf(e.getCause()), e.getCause());
        } catch (Throwable e) {
            throw new IOException(L.s(R.string.err_shizuku_unavail, "newProcess " + e), e);
        }
    }
}
