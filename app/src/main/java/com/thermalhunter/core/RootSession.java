package com.thermalhunter.core;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 通过 su 启动常驻 root 进程（app_process 运行本 APK 内的 RootMain），
 * 后续所有采集/操作都通过 stdin/stdout 的行协议完成，避免每次都 fork shell。
 * 所有调用都必须在后台线程。
 */
public final class RootSession {
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile String lastError = "";

    public synchronized boolean isAlive() {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public String lastError() {
        return lastError;
    }

    public synchronized boolean start(Context ctx) {
        close();
        String apk = ctx.getApplicationInfo().sourceDir;
        try {
            process = new ProcessBuilder("su").start();
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
            if (lastError.isEmpty()) lastError = "su 被拒绝或不可用（请在 KernelSU 管理器中授予本应用超级用户权限）";
            close();
            return false;
        } catch (IOException e) {
            lastError = "无法执行 su: " + e.getMessage();
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
        process.destroy();
        process = null;
        writer = null;
        reader = null;
    }
}
