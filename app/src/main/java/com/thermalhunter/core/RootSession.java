package com.thermalhunter.core;

import android.content.Context;

import org.json.JSONObject;

import rikka.shizuku.ShizukuRemoteProcess;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 通过 su（root 模式）或 Shizuku（ADB 模式，shell 身份）启动常驻进程（app_process 运行本 APK 内的 RootMain），
 * 后续所有采集/操作都通过 stdin/stdout 的行协议完成，避免每次都 fork shell。
 * 所有调用都必须在后台线程。
 */
public final class RootSession {
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile String lastError = "";
    private volatile boolean viaShizuku;

    public synchronized boolean isAlive() {
        if (process == null) return false;
        if (process instanceof ShizukuRemoteProcess) {
            try {
                return ((ShizukuRemoteProcess) process).alive();
            } catch (RuntimeException e) {
                return false; // Shizuku 服务已断开
            }
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** 当前会话是否通过 Shizuku（shell 权限）建立。 */
    public boolean isShizuku() {
        return viaShizuku;
    }

    public String lastError() {
        return lastError;
    }

    /** @param shizuku true 时通过 Shizuku 以 shell 身份启动，否则通过 su 以 root 启动 */
    public synchronized boolean start(Context ctx, boolean shizuku) {
        close();
        viaShizuku = shizuku;
        lastError = "";
        String apk = ctx.getApplicationInfo().sourceDir;
        try {
            process = shizuku ? ShizukuShell.newProcess("sh") : new ProcessBuilder("su").start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            final Process p = process;
            Thread err = new Thread(() -> {
                try (BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String l;
                    while ((l = er.readLine()) != null) lastError = l;
                } catch (IOException ignored) {
                }
            }, "root-stderr");
            err.setDaemon(true);
            err.start();

            writer.write("export CLASSPATH='" + apk.replace("'", "") + "'\n");
            writer.write("exec /system/bin/app_process /system/bin --nice-name=thermalhunter_root "
                    + "com.thermalhunter.root.RootMain\n");
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("@@READY")) return true;
            }
            if (lastError.isEmpty()) lastError = shizuku ? "后台进程没有启动"
                    : "su 被拒绝或不可用（请在 KernelSU 管理器中授予本应用超级用户权限）";
            close();
            return false;
        } catch (IOException e) {
            lastError = (shizuku ? "无法通过 Shizuku 启动: " : "无法执行 su: ") + e.getMessage();
            close();
            return false;
        }
    }

    public synchronized JSONObject call(String cmd) throws IOException {
        if (writer == null) throw new IOException("root 进程未启动");
        writer.write(cmd);
        writer.write('\n');
        writer.flush();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("@@")) {
                try {
                    return new JSONObject(line.substring(2));
                } catch (Exception e) {
                    throw new IOException("bad response: " + e.getMessage());
                }
            }
        }
        close();
        throw new IOException("root 进程已退出 " + lastError);
    }

    /** 调用并把异常转成 {ok:false} */
    public JSONObject safeCall(String cmd) {
        try {
            return call(cmd);
        } catch (Exception e) {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", false);
                o.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public synchronized void close() {
        if (process == null) return;
        try {
            writer.write("EXIT\n");
            writer.flush();
        } catch (Exception ignored) {
        }
        try {
            writer.close();
        } catch (Exception ignored) {
        }
        try {
            process.destroy();
        } catch (Exception ignored) {
        }
        process = null;
        writer = null;
        reader = null;
    }
}
