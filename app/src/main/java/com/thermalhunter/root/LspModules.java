package com.thermalhunter.root;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 读写 LSPosed 的模块配置库 /data/adb/lspd/config/modules_config.db。
 * 典型表结构（官方 LSPosed）:
 *   modules(mid, module_pkg_name, apk_path, enabled, ...)
 *   scope(mid, app_pkg_name, user_id)        —— 系统框架在库中记为 "system"
 * 各分支列名不完全一致（例如没有 mid 列），因此先读表结构再按实际列名查询。
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
            Schema sc = schema(db);
            if (sc.error != null) {
                r.error = sc.error;
                return r;
            }
            Map<String, Module> byKey = new HashMap<>();
            String sql = "SELECT " + sc.mKey + ", " + sc.mPkg + ", " + (sc.mApk == null ? "''" : sc.mApk) + ", "
                    + (sc.mEnabled == null ? "1" : sc.mEnabled) + " FROM " + sc.modules;
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    Module m = new Module();
                    m.pkg = c.getString(1);
                    m.apkPath = c.getString(2);
                    m.enabled = c.getInt(3) != 0;
                    byKey.put(c.getString(0), m);
                    r.modules.add(m);
                }
            }
            if (sc.scope != null) {
                String ssql = "SELECT " + sc.sKey + ", " + sc.sApp + ", " + (sc.sUser == null ? "0" : sc.sUser)
                        + " FROM " + sc.scope;
                try (Cursor c = db.rawQuery(ssql, null)) {
                    while (c.moveToNext()) {
                        Module m = byKey.get(c.getString(0));
                        if (m == null) continue;
                        Scope s = new Scope();
                        s.pkg = c.getString(1);
                        s.user = c.getInt(2);
                        m.scopes.add(s);
                    }
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
            Schema sc = schema(db);
            if (sc.error != null) return sc.error;
            if (sc.mEnabled == null) return "该 LSPosed 版本的数据库没有 enabled 列，无法修改";
            ContentValues cv = new ContentValues();
            cv.put(sc.mEnabled, enabled ? 1 : 0);
            int n = db.update(sc.modules, cv, sc.mPkg + " = ?", new String[]{pkg});
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

    /** 按实际表结构确定的表名/列名。 */
    private static final class Schema {
        String error;
        String modules, mKey, mPkg, mApk, mEnabled;
        String scope, sKey, sApp, sUser;
    }

    private static List<String> tables(SQLiteDatabase db) {
        List<String> l = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (c.moveToNext()) l.add(c.getString(0));
        }
        return l;
    }

    private static List<String> columns(SQLiteDatabase db, String table) {
        List<String> l = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) l.add(c.getString(idx).toLowerCase(Locale.ROOT));
        }
        return l;
    }

    private static String pick(List<String> have, String... want) {
        for (String w : want) if (have.contains(w)) return w;
        return null;
    }

    private static Schema schema(SQLiteDatabase db) {
        Schema s = new Schema();
        List<String> tables = tables(db);
        s.modules = pick(tables, "modules", "module");
        s.scope = pick(tables, "scope", "scopes", "module_scope");
        if (s.modules == null) {
            s.error = "无法识别的 LSPosed 数据库：没有 modules 表（现有表: " + tables + "）";
            return s;
        }
        List<String> mc = columns(db, s.modules);
        s.mPkg = pick(mc, "module_pkg_name", "pkg_name", "package_name", "module_package_name", "pkg");
        s.mApk = pick(mc, "apk_path", "path", "module_apk_path");
        s.mEnabled = pick(mc, "enabled", "enable", "is_enabled");
        if (s.mPkg == null) {
            s.error = "无法识别的 LSPosed 数据库：" + s.modules + " 表的列为 " + mc;
            return s;
        }
        String mId = pick(mc, "mid", "module_id", "id", "_id");
        if (s.scope != null) {
            List<String> sc = columns(db, s.scope);
            s.sApp = pick(sc, "app_pkg_name", "pkg_name", "package_name", "app_package_name", "app");
            s.sUser = pick(sc, "user_id", "userid", "user");
            String sId = pick(sc, "mid", "module_id");
            String sPkg = pick(sc, "module_pkg_name", "module_package_name", "module");
            if (s.sApp == null) {
                s.scope = null; // 作用域表无法识别时只显示模块列表
            } else if (sId != null) {
                // 作用域以模块 id 关联；模块表没有 id 列时用 rowid（INTEGER PRIMARY KEY 即 rowid 别名）
                s.sKey = sId;
                s.mKey = mId != null ? mId : "rowid";
            } else if (sPkg != null) {
                s.sKey = sPkg;
                s.mKey = s.mPkg;
            } else {
                s.scope = null;
            }
        }
        if (s.mKey == null) s.mKey = mId != null ? mId : "rowid";
        return s;
    }

    private static String fileContext(String path) {
        Object[] r = Sys.exec("ls", "-Z", path);
        if ((int) r[0] != 0) return null;
        String out = ((String) r[1]).trim();
        String first = out.split("\\s+")[0];
        return first.startsWith("u:") ? first : null;
    }
}
