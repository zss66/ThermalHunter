package com.thermalhunter.root;

import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 所有破坏性操作集中在这里，并带有保护名单，防止误杀导致死机/重启/掉 root。 */
final class Actions {

    static final java.util.Set<String> PROTECTED_PROCS = com.thermalhunter.core.Protect.PROCS;
    static final java.util.Set<String> PROTECTED_PKGS = com.thermalhunter.core.Protect.PKGS;

    private final Scanner scanner;

    Actions(Scanner scanner) {
        this.scanner = scanner;
    }

    private static JSONObject ok(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("msg", msg);
        return o;
    }

    private static JSONObject fail(String msg) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }

    private static String procName(int pid) {
        String cl = Sys.read("/proc/" + pid + "/cmdline");
        if (cl != null && !cl.isEmpty()) {
            String first = cl.split("\0")[0];
            return first;
        }
        String stat = Sys.read("/proc/" + pid + "/stat");
        if (stat == null) return null;
        int lp = stat.indexOf('('), rp = stat.lastIndexOf(')');
        return lp >= 0 && rp > lp ? stat.substring(lp + 1, rp) : "";
    }

    static boolean isProtectedProc(int pid) {
        if (pid <= 2 || pid == Os.getpid()) return true;
        String stat = Sys.read("/proc/" + pid + "/stat");
        if (stat != null) {
            int rp = stat.lastIndexOf(')');
            String[] f = stat.substring(rp + 2).split(" ");
            if ("2".equals(f[1])) return true; // 内核线程
            int lp = stat.indexOf('(');
            if (PROTECTED_PROCS.contains(stat.substring(lp + 1, rp))) return true;
        }
        String name = procName(pid);
        if (name == null) return false;
        String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
        if (PROTECTED_PROCS.contains(base) || PROTECTED_PROCS.contains(name)) return true;
        int colon = name.indexOf(':');
        String pkg = colon > 0 ? name.substring(0, colon) : name;
        return PROTECTED_PKGS.contains(pkg);
    }

    /** KILL <pid> [期望的进程名]：校验进程名，防止 pid 复用误杀。 */
    JSONObject kill(int pid, String expect) throws Exception {
        String name = procName(pid);
        if (name == null) return ok("进程已不存在");
        if (expect != null && !expect.isEmpty() && !name.startsWith(expect) && !name.endsWith(expect)
                && !expect.startsWith(name)) {
            return fail("pid " + pid + " 已变为 " + name + "，已取消");
        }
        if (isProtectedProc(pid)) return fail(name + " 是受保护的系统关键进程");
        try {
            Os.kill(pid, 9);
            return ok("已杀死 " + name + " (" + pid + ")");
        } catch (Throwable e) {
            return fail("kill 失败: " + e);
        }
    }

    JSONObject forceStop(String pkg, int user) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("非法包名");
        if (PROTECTED_PKGS.contains(pkg)) return fail(pkg + " 受保护，不能强行停止");
        Object[] r = Sys.exec("am", "force-stop", "--user", String.valueOf(user), pkg);
        return (int) r[0] == 0 ? ok("已强行停止 " + pkg) : fail("force-stop 失败: " + r[1]);
    }

    JSONObject setAppEnabled(String pkg, int user, boolean enable) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("非法包名");
        if (!enable && PROTECTED_PKGS.contains(pkg)) return fail(pkg + " 受保护，不能停用");
        Object[] r = enable
                ? Sys.exec("pm", "enable", "--user", String.valueOf(user), pkg)
                : Sys.exec("pm", "disable-user", "--user", String.valueOf(user), pkg);
        return (int) r[0] == 0 ? ok((enable ? "已启用 " : "已停用(冻结) ") + pkg) : fail(String.valueOf(r[1]));
    }

    /** 杀死归属于某 KSU 模块的所有进程。 */
    JSONObject killKsuProcs(String id) throws Exception {
        if (!Sys.isSafeToken(id)) return fail("非法模块 id");
        List<Integer> targets = new ArrayList<>();
        synchronized (scanner.lastProcs) {
            for (Scanner.Proc p : scanner.lastProcs) if (id.equals(p.ksu)) targets.add(p.pid);
        }
        int killed = 0;
        JSONArray skipped = new JSONArray();
        // 父进程(脚本循环)与子进程一起 SIGKILL，避免子进程被重新拉起
        for (int pid : targets) {
            if (isProtectedProc(pid)) {
                skipped.put(pid);
                continue;
            }
            try {
                Os.kill(pid, 9);
                killed++;
            } catch (Throwable ignored) {
            }
        }
        JSONObject o = ok("模块 " + id + "：已杀死 " + killed + " 个进程");
        o.put("skipped", skipped);
        return o;
    }

    JSONObject setKsuEnabled(String id, boolean enable) throws Exception {
        if (!Sys.isSafeToken(id)) return fail("非法模块 id");
        String dir = KsuModules.MODULES_DIR + "/" + id;
        if (!new File(dir).isDirectory()) return fail("模块不存在: " + id);
        String ksud = Sys.exists("/data/adb/ksud") ? "/data/adb/ksud" : null;
        boolean done = false;
        String detail = "";
        if (ksud != null) {
            Object[] r = Sys.exec(ksud, "module", enable ? "enable" : "disable", id);
            done = (int) r[0] == 0;
            detail = String.valueOf(r[1]);
        }
        if (!done) {
            File flag = new File(dir, "disable");
            try {
                if (enable) done = !flag.exists() || flag.delete();
                else done = flag.exists() || flag.createNewFile();
            } catch (Throwable e) {
                detail = String.valueOf(e);
            }
        }
        KsuModules.invalidate();
        if (!done) return fail("操作失败: " + detail);
        if (!enable) {
            JSONObject k = killKsuProcs(id);
            return ok("已禁用模块 " + id + "（重启后完全生效）；" + k.optString("msg"));
        }
        return ok("已启用模块 " + id + "（重启后生效）");
    }

    JSONObject setLspEnabled(String pkg, boolean enable) throws Exception {
        if (!Sys.isSafeToken(pkg)) return fail("非法包名");
        String err = LspModules.setEnabled(pkg, enable);
        if (err != null) return fail(err);
        String msg = (enable ? "已在 LSPosed 中启用 " : "已在 LSPosed 中禁用 ") + pkg + "（重启手机后生效）";
        if (!enable && !PROTECTED_PKGS.contains(pkg)) {
            // 同时结束模块自身的进程（若它在跑后台服务）
            Sys.exec("am", "force-stop", pkg);
        }
        return ok(msg);
    }

    JSONObject reboot() throws Exception {
        Sys.exec("svc", "power", "reboot", "thermalhunter");
        Object[] r = Sys.exec("reboot");
        return (int) r[0] == 0 ? ok("正在重启") : fail(String.valueOf(r[1]));
    }
}
