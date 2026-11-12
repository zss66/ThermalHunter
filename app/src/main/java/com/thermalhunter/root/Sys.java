package com.thermalhunter.root;

import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** root 进程内的文件/命令工具。全部容错：读不到返回 null / 默认值。 */
final class Sys {
    private Sys() {}

    private static final ThreadLocal<byte[]> BUF = ThreadLocal.withInitial(() -> new byte[16384]);

    static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = BUF.get();
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    static String readTrim(String path) {
        String s = read(path);
        return s == null ? null : s.trim();
    }

    /** 读取较大的文件（如 packages.list）。 */
    static String readAll(String path, int max) {
        try (InputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && bos.size() < max) bos.write(b, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    static long readLong(String path, long def) {
        String s = readTrim(path);
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 取文本里出现的第一个整数。 */
    static long firstNumber(String s, long def) {
        if (s == null) return def;
        int i = 0, n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') i++;
        int j = i + 1;
        while (j < n && Character.isDigit(s.charAt(j))) j++;
        try {
            return Long.parseLong(s.substring(i, Math.min(j, n)));
        } catch (Throwable e) {
            return def;
        }
    }

    static String readlink(String path) {
        try {
            return Os.readlink(path);
        } catch (Throwable e) {
            return null;
        }
    }

    static String[] list(String dir) {
        String[] l = new File(dir).list();
        return l == null ? new String[0] : l;
    }

    static boolean exists(String path) {
        return new File(path).exists();
    }

    /** 直接 exec（不经过 shell，杜绝注入）。返回 [exitCode, output]。 */
    static Object[] exec(String... argv) {
        return execMax(65536, argv);
    }

    /** 同 exec，但允许更长的输出（如 pm list packages）。 */
    static Object[] execMax(int max, String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) if (bos.size() < max) bos.write(b, 0, n);
            int code = p.waitFor();
            return new Object[]{code, bos.toString("UTF-8").trim()};
        } catch (Throwable e) {
            return new Object[]{-1, String.valueOf(e)};
        }
    }

    /** exec 并把标准输出写入文件（输出很大时使用），标准错误另行收集。返回 [exitCode, stderr]。 */
    static Object[] execToFile(File out, String... argv) {
        try {
            Process p = new ProcessBuilder(argv).redirectOutput(out).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = p.getErrorStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) if (bos.size() < 65536) bos.write(b, 0, n);
            int code = p.waitFor();
            return new Object[]{code, bos.toString("UTF-8").trim()};
        } catch (Throwable e) {
            return new Object[]{-1, String.valueOf(e)};
        }
    }

    static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable e) {
            return "";
        }
    }

    static List<String> walk(File root, int maxFiles, int maxDepth) {
        List<String> out = new ArrayList<>();
        walk(root, root.getAbsolutePath().length(), out, maxFiles, maxDepth);
        return out;
    }

    private static void walk(File f, int prefix, List<String> out, int maxFiles, int depth) {
        if (out.size() >= maxFiles || depth < 0) return;
        File[] children = f.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (out.size() >= maxFiles) return;
            if (c.isDirectory()) walk(c, prefix, out, maxFiles, depth - 1);
            else out.add(c.getAbsolutePath().substring(prefix));
        }
    }

    static boolean isSafeToken(String s) {
        return s != null && !s.isEmpty() && s.length() < 256 && s.matches("[A-Za-z0-9._\\-]+");
    }
}
