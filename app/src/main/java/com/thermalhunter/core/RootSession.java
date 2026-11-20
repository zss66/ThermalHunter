package com.thermalhunter.core;

import com.thermalhunter.R;
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
    /** 单次请求（含启动握手）的最长等待；超时后中止会话并强杀后台进程，避免 monitor 线程永久阻塞。 */
    private static final long CALL_TIMEOUT_MS = 30_000;

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
            long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS;
            String line;
            while ((line = readLineTimeout(deadline)) != null) {
                if (line.equals("@@READY")) return true;
            }
            if (lastError.isEmpty()) lastError = L.s(shizuku ? R.string.err_bg_not_started : R.string.err_su_denied);
            close();
            return false;
        } catch (IOException e) {
            lastError = L.s(shizuku ? R.string.err_shizuku_launch : R.string.err_su_exec, e.getMessage());
            close();
            return false;
        }
    }

    public synchronized JSONObject call(String cmd) throws IOException {
        if (writer == null) throw new IOException(L.s(R.string.err_proc_not_started));
        writer.write(cmd);
        writer.write('\n');
        writer.flush();
        long deadline = System.currentTimeMillis() + CALL_TIMEOUT_MS;
        String line;
        while ((line = readLineTimeout(deadline)) != null) {
            if (line.startsWith("@@")) {
                try {
                    JSONObject o = new JSONObject(line.substring(2));
                    // root 端的提示是文字令牌（\u0001…），在这里按当前语言统一换成文字
                    if (line.contains("\\u0001")) L.trJson(o);
                    return o;
                } catch (Exception e) {
                    throw new IOException("bad response: " + e.getMessage());
                }
            }
        }
        close();
        throw new IOException(L.s(R.string.err_proc_exited, lastError));
    }

    /**
     * 带超时地读一行。用轮询而非阻塞 read：后台进程卡死后强杀父进程也不一定能拿到 EOF
     * （子进程可能仍持有 stdout 管道），必须让调用线程自己能退出。
     * 超时则中止会话并返回 null；正常 EOF 也返回 null，由 lastError 区分。
     */
    private String readLineTimeout(long deadline) throws IOException {
        while (true) {
            if (reader.ready()) return reader.readLine();
            // ready() 在 EOF 上恒为 false（EOF 不算"就绪"）。进程已退出时直接 readLine
            // 立即取 EOF/残余数据，保留真实错误原因（如 su 拒绝），避免空转到超时。
            if (!isAlive()) return reader.readLine();
            if (System.currentTimeMillis() > deadline) {
                abortTimeout();
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(L.s(R.string.err_interrupted), e);
            }
        }
    }

    /** 超时中止：close 关闭 stdin 后孤儿 app_process 会自行退出，再强杀确保清理。 */
    private void abortTimeout() {
        lastError = L.s(R.string.err_timeout, CALL_TIMEOUT_MS / 1000);
        Process p = process;
        close();
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
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
