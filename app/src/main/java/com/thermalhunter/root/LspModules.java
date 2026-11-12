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
 * 新分支把启用状态拆到单独的表，并按用户记录:
 *   modules(module_pkg_name, apk_path) + modules_state(module_pkg_name, user_id, enabled, ...)
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
            if (sc.state != null) {
                // 启用状态在单独的表里：优先取主用户 (0)，否则任一用户启用即视为启用；没有记录视为未启用
                Map<String, Boolean> user0 = new HashMap<>(), any = new HashMap<>();
                String q = "SELECT " + sc.stPkg + ", " + (sc.stUser == null ? "0" : sc.stUser) + ", " + sc.stEnabled
                        + " FROM " + sc.state;
                try (Cursor c = db.rawQuery(q, null)) {
                    while (c.moveToNext()) {
                        String pkg = c.getString(0);
                        boolean en = c.getInt(2) != 0;
                        if (c.getInt(1) == 0) user0.put(pkg, en);
                        any.put(pkg, en || Boolean.TRUE.equals(any.get(pkg)));
                    }
                }
                for (Module m : r.modules) {
                    Boolean v = user0.get(m.pkg);
                    if (v == null) v = any.get(m.pkg);
                    m.enabled = v != null && v;
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
            if (sc.mEnabled != null) {
                ContentValues cv = new ContentValues();
                cv.put(sc.mEnabled, enabled ? 1 : 0);
                int n = db.update(sc.modules, cv, sc.mPkg + " = ?", new String[]{pkg});
                if (n == 0) return "数据库中没有该模块: " + pkg;
            } else if (sc.state != null) {
                // 按用户记录的启用状态：更新该模块所有用户的记录；启用时若没有记录则为主用户新建一条
                ContentValues cv = new ContentValues();
                cv.put(sc.stEnabled, enabled ? 1 : 0);
                int n = db.update(sc.state, cv, sc.stPkg + " = ?", new String[]{pkg});
                if (n == 0) {
                    if (!enabled) return null; // 没有记录本来就是未启用
                    ContentValues ins = new ContentValues();
                    ins.put(sc.stPkg, pkg);
                    if (sc.stUser != null) ins.put(sc.stUser, 0);
                    ins.put(sc.stEnabled, 1);
                    if (db.insert(sc.state, null, ins) < 0) return "写入 LSPosed 启用状态失败";
                }
            } else {
                return "暂不支持这个 LSPosed 版本的禁用操作（找不到启用状态列）。请把这条信息截图反馈：\n" + describe(db);
            }
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
        String state, stPkg, stUser, stEnabled; // 独立的启用状态表（可为空）
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

    /** 列出所有表及其列，用于反馈未适配的数据库结构。 */
    private static String describe(SQLiteDatabase db) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables(db)) {
            if (t.startsWith("sqlite_") || t.equals("android_metadata")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t).append(columns(db, t));
        }
        return sb.toString();
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
            s.error = "无法识别的 LSPosed 数据库：没有 modules 表。表结构：\n" + describe(db);
            return s;
        }
        List<String> mc = columns(db, s.modules);
        s.mPkg = pick(mc, "module_pkg_name", "pkg_name", "package_name", "module_package_name", "pkg");
        s.mApk = pick(mc, "apk_path", "path", "module_apk_path");
        s.mEnabled = pick(mc, "enabled", "enable", "is_enabled");
        if (s.mPkg == null) {
            s.error = "无法识别的 LSPosed 数据库。表结构：\n" + describe(db);
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
        if (s.mEnabled == null) {
            // 在其他表中寻找「包名 + enabled」形式的启用状态表，如 modules_state
            for (String t : tables) {
                if (t.equals(s.modules) || t.equals(s.scope)) continue;
                List<String> cols = columns(db, t);
                String en = pick(cols, "enabled", "enable", "is_enabled");
                String pk = pick(cols, "module_pkg_name", "module_package_name", "pkg_name", "package_name");
                if (en != null && pk != null) {
                    s.state = t;
                    s.stEnabled = en;
                    s.stPkg = pk;
                    s.stUser = pick(cols, "user_id", "userid", "user");
                    break;
                }
            }
        }
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
