package com.thermalhunter.root;

import android.system.Os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * root 端常驻进程入口，由 App 通过以下方式启动：
 *   su -c 'CLASSPATH=<apk> exec app_process /system/bin com.thermalhunter.root.RootMain'
 * 也可通过 Shizuku 以 shell 身份（uid 2000）启动，此时模块相关操作不可用。
 * 协议：stdin 每行一条命令，stdout 每行一个以 "@@" 开头的 JSON 响应。
 * stdin 关闭（App 退出）时进程自动结束，不会残留后台耗电。
 */
public final class RootMain {

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
        // 防止框架代码向 stdout 打印杂讯污染协议
        System.setOut(new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
            }
        }));
        Scanner scanner = new Scanner();
        Actions actions = new Actions(scanner);
        // 自动归因只在 root 下可用（shell 无权采样其他进程）
        autoProfiler = Os.getuid() == 0 ? new AutoProfiler(scanner) : null;
        if (autoProfiler != null) autoProfiler.start();
        out.println("@@READY");
        out.flush();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                JSONObject resp;
                try {
                    resp = handle(line.split(" "), scanner, actions);
                } catch (Throwable e) {
                    resp = new JSONObject();
                    try {
                        resp.put("ok", false);
                        resp.put("error", String.valueOf(e));
                    } catch (Exception ignored) {
                    }
                }
                if (resp == null) break;
                out.println("@@" + resp);
                out.flush();
            }
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    private static int intArg(String[] a, int i, int def) {
        try {
            return a.length > i ? Integer.parseInt(a[i]) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String strArg(String[] a, int i) {
        return a.length > i ? a[i] : "";
    }

    private static AutoProfiler autoProfiler;

    /** 以 shell 身份运行时（Shizuku 模式）没有权限执行的命令前缀。 */
    private static boolean needsRoot(String cmd) {
        return cmd.startsWith("KSU_") || cmd.startsWith("LSP_") || cmd.equals("AUTOPROF");
    }

    private static JSONObject handle(String[] a, Scanner s, Actions act) throws Exception {
        if (Os.getuid() != 0 && needsRoot(a[0])) {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", "Shizuku 模式下不可用（需要 root）");
            return o;
        }
        switch (a[0]) {
            case "SNAPSHOT": {
                JSONObject snap = s.snapshot();
                JSONObject prof = autoProfiler == null ? null : autoProfiler.poll();
                if (prof != null) snap.put("prof", prof);
                return snap;
            }
            case "KILL":
                return act.kill(intArg(a, 1, -1), strArg(a, 2));
            case "FORCESTOP":
                return act.forceStop(strArg(a, 1), intArg(a, 2, 0));
            case "APP_DISABLE":
                return act.setAppEnabled(strArg(a, 1), intArg(a, 2, 0), false);
            case "APP_ENABLE":
                return act.setAppEnabled(strArg(a, 1), intArg(a, 2, 0), true);
            case "KSU_KILL":
                return act.killKsuProcs(strArg(a, 1));
            case "KSU_DISABLE":
                return act.setKsuEnabled(strArg(a, 1), false);
            case "KSU_ENABLE":
                return act.setKsuEnabled(strArg(a, 1), true);
            case "LSP_DISABLE":
                return act.setLspEnabled(strArg(a, 1), false);
            case "LSP_ENABLE":
                return act.setLspEnabled(strArg(a, 1), true);
            case "AUTOPROF": { // AUTOPROF <1|0>：开关模块自动归因
                autoProfiler.setEnabled(intArg(a, 1, 1) == 1);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return o;
            }
            case "REBOOT":
                return act.reboot();
            case "REFRESH_INFO":
                return Display.info();
            case "REFRESH_SET": // REFRESH_SET <min> <peak>
                return Display.set(Double.parseDouble(strArg(a, 1)), Double.parseDouble(strArg(a, 2)));
            case "PING": {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                return o;
            }
            case "EXIT":
                return null;
            default: {
                JSONObject o = new JSONObject();
                o.put("ok", false);
                o.put("error", "unknown command " + a[0]);
                return o;
            }
        }
    }
}
