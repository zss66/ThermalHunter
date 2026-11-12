package com.thermalhunter.root;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读写 LSPosed 的模块配置库 /data/adb/lspd/config/modules_config.db。
 * 表结构（LSPosed / JingMatrix 分支一致）:
 *   modules(mid, module_pkg_name, apk_path, enabled, ...)
 *   scope(mid, app_pkg_name, user_id)        —— 系统框架在库中记为 "system"
 * 直接改库后 lspd 的内存缓存不会刷新，需重启手机才能完全生效。
 */
final class LspModules {
    static final String[] DB_PATHS = {
            "/data/adb/lspd/config/modules_config.db",
    };

    static final class Scope {
        String pkg;
        int user;
    }

    static final class Module {
        String pkg;
        String apkPath;
        boolean enabled;
        final List<Scope> scopes = new ArrayList<>();
    }

    static final class Result {
        boolean available;
        String error;
        String dbPath;
        final List<Module> modules = new ArrayList<>();
    }

    private static Result cache;
    private static long cacheTime;

    static synchronized Result get() {
        long now = System.currentTimeMillis();
        if (cache == null || now - cacheTime > 20_000) {
            cache = load();
            cacheTime = now;
        }
        return cache;
    }

    static synchronized void invalidate() {
        cacheTime = 0;
    }

    static String dbPath() {
        for (String p : DB_PATHS) if (Sys.exists(p)) return p;
        return null;
    }

    private static Result load() {
        Result r = new Result();
        String path = dbPath();
        if (path == null) {
            r.error = Sys.exists("/data/adb/lspd") ? "找到 /data/adb/lspd 但没有 modules_config.db" : "未安装 LSPosed";
            return r;
        }
        r.dbPath = path;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Map<Integer, Module> byMid = new HashMap<>();
            try (Cursor c = db.rawQuery("SELECT mid, module_pkg_name, apk_path, enabled FROM modules", null)) {
                while (c.moveToNext()) {
                    Module m = new Module();
                    m.pkg = c.getString(1);
                    m.apkPath = c.getString(2);
                    m.enabled = c.getInt(3) != 0;
                    byMid.put(c.getInt(0), m);
                    r.modules.add(m);
                }
            }
            try (Cursor c = db.rawQuery("SELECT mid, app_pkg_name, user_id FROM scope", null)) {
                while (c.moveToNext()) {
                    Module m = byMid.get(c.getInt(0));
                    if (m == null) continue;
                    Scope s = new Scope();
                    s.pkg = c.getString(1);
                    s.user = c.getInt(2);
                    m.scopes.add(s);
                }
            }
            r.available = true;
        } catch (Throwable e) {
            r.error = "读取 LSPosed 数据库失败: " + e;
        } finally {
            if (db != null) db.close();
        }
        return r;
    }

    /** 启用/禁用模块。返回 null 表示成功，否则为错误信息。 */
    static String setEnabled(String pkg, boolean enabled) {
        String path = dbPath();
        if (path == null) return "未找到 LSPosed 数据库";
        String ctx = fileContext(path);
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            ContentValues cv = new ContentValues();
            cv.put("enabled", enabled ? 1 : 0);
            int n = db.update("modules", cv, "module_pkg_name = ?", new String[]{pkg});
            if (n == 0) return "数据库中没有该模块: " + pkg;
        } catch (Throwable e) {
            return "写入 LSPosed 数据库失败: " + e;
        } finally {
            if (db != null) db.close();
            // 保持 SELinux 上下文与原库一致（lspd 需要能读写）
            if (ctx != null) {
                for (String suf : new String[]{"", "-wal", "-shm", "-journal"}) {
                    if (Sys.exists(path + suf)) Sys.exec("chcon", ctx, path + suf);
                }
            }
            invalidate();
        }
        return null;
    }

    private static String fileContext(String path) {
        Object[] r = Sys.exec("ls", "-Z", path);
        if ((int) r[0] != 0) return null;
        String out = ((String) r[1]).trim();
        String first = out.split("\\s+")[0];
        return first.startsWith("u:") ? first : null;
    }
}
