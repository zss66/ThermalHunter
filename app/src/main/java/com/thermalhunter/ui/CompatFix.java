package com.thermalhunter.ui;

import com.thermalhunter.core.L;
import com.thermalhunter.R;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 加固应用兼容修复（app 侧；与 VPN 隐身一致，经一次性 su 通道执行 root 操作）。
 *
 * 背景：部分加固应用（SecNeo/梆梆系，如数字人民币 dcep）在 root 设备上启动即闪退。
 * 反汇编与运行时实验证实其加固自检读取 /sys/fs/selinux/enforce 的内容——
 * 读到 1（Enforcing）且检出 root 信号时触发防篡改自杀（故意跳转到非法地址）；
 * 读到 0（Permissive）则当作测试环境放行。
 *
 * 修复：把内容为 0 的影子文件 bind-mount 盖住 /sys/fs/selinux/enforce，
 * 让加固读到 0 而不自杀。真实 SELinux 状态与访问控制完全不变（getenforce 等
 * 工具只是被影子文件误导显示 Permissive）。即时生效、无需重启；
 * 重启 / 软重启后挂载丢失需重新启用；umount 一步即可完整还原。
 *
 * 必须用 su -M（mount-master，全局挂载命名空间）执行：普通 app 进程各自持有
 * 独立挂载命名空间，默认 su 只会挂进本应用的命名空间，其他应用不可见。
 */
final class CompatFix {

    static final String SRC = "/data/local/tmp/thermalhunter_compat_enforce";
    static final String TARGET = "/sys/fs/selinux/enforce";

    /** 一次读回：第 1 行 enforce 文件内容（真实文件无换行符，必须用 echo 包裹补齐），第 2 行影子文件是否存在（1/0）。 */
    private static final String QUERY = "echo \"$(cat " + TARGET + " 2>/dev/null)\"; [ -f " + SRC + " ] && echo 1 || echo 0";

    /** 状态：active = 修复生效中（影子存在且内容为 0）；value = enforce 文件当前内容。 */
    static final class Status {
        final boolean active;
        final String value;
        Status(boolean active, String value) {
            this.active = active;
            this.value = value;
        }
    }

    interface Reply {
        /** ok=false 时 msg 为错误说明（如需要 root）；st 可能为 null。 */
        void onResult(boolean ok, String msg, Status st);
    }

    /** 查询修复状态。 */
    static void query(Reply cb) {
        runAsync(() -> {
            try {
                Status st = readStatus();
                deliver(cb, true, L.s(st.active ? R.string.compat_on : R.string.compat_off), st);
            } catch (Throwable e) {
                deliver(cb, false, L.s(R.string.compat_need_root), null);
            }
        });
    }

    /** enable=true 挂载影子；false umount 还原（影子文件保留作下次挂载源）。完成后带回最新状态。 */
    static void set(boolean enable, Reply cb) {
        runAsync(() -> {
            try {
                if (enable) {
                    su("printf '0\\n' > " + SRC + " && chmod 644 " + SRC
                            + " && mount --bind " + SRC + " " + TARGET);
                    Status st = readStatus();
                    if (st.active) {
                        deliver(cb, true, L.s(R.string.compat_enabled_msg), st);
                    } else {
                        deliver(cb, false, L.s(R.string.compat_mount_fail, String.valueOf(st.value)), st);
                    }
                } else {
                    // 双重 umount 兜底叠加挂载；保留影子文件（16 字节，重启用作下次挂载源）
                    su("umount " + TARGET + " 2>/dev/null; umount " + TARGET + " 2>/dev/null");
                    Status st = readStatus();
                    if (!st.active) {
                        deliver(cb, true, L.s(R.string.compat_restored), st);
                    } else {
                        deliver(cb, false, L.s(R.string.compat_umount_fail), st);
                    }
                }
            } catch (Throwable e) {
                deliver(cb, false, L.s(R.string.op_failed, e.getMessage()), null);
            }
        });
    }

    /** 菜单卡片描述文案。 */
    static String desc(Status st) {
        if (st == null) return L.s(R.string.compat_checking);
        return L.s(st.active ? R.string.compat_desc_on : R.string.compat_desc_off);
    }

    /** 生效判定：影子文件存在且 enforce 读回 0。 */
    private static Status readStatus() throws Exception {
        String[] p = su(QUERY).trim().split("\n");
        String value = p.length > 0 ? p[0].trim() : "";
        boolean src = p.length > 1 && "1".equals(p[1].trim());
        return new Status(src && "0".equals(value), value);
    }

    private static void deliver(Reply cb, boolean ok, String msg, Status st) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.onResult(ok, msg, st));
    }

    private static void runAsync(Runnable r) {
        new Thread(r, "compat-fix").start();
    }

    /** 与 syncVpnHideConfig 相同的一次性 su 通道，但必须 -M 进全局挂载命名空间；合并 stderr 避免管道堵塞。 */
    private static String su(String cmd) throws Exception {
        Process pr = new ProcessBuilder("su", "-M", "-c", cmd)
                .redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader rd = new BufferedReader(
                new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = rd.readLine()) != null) sb.append(l).append('\n');
        }
        if (!pr.waitFor(10, TimeUnit.SECONDS)) {
            pr.destroyForcibly();
            throw new IllegalStateException(L.s(R.string.su_timeout));
        }
        return sb.toString();
    }
}
