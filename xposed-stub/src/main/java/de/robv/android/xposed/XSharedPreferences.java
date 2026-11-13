package de.robv.android.xposed;

import java.io.File;
import java.util.Set;

/**
 * 编译用存根：真实实现由 LSPosed 提供。
 * 用于在被 hook 的应用进程里读取本模块写出的（世界可读的）SharedPreferences。
 */
public class XSharedPreferences {
    public XSharedPreferences(String packageName) {
        throw new UnsupportedOperationException("stub");
    }

    public XSharedPreferences(String packageName, String prefFileName) {
        throw new UnsupportedOperationException("stub");
    }

    public XSharedPreferences(File prefFile) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean makeWorldReadable() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean hasFileChanged() {
        throw new UnsupportedOperationException("stub");
    }

    public void reload() {
        throw new UnsupportedOperationException("stub");
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        throw new UnsupportedOperationException("stub");
    }

    public String getString(String key, String defValue) {
        throw new UnsupportedOperationException("stub");
    }

    public boolean getBoolean(String key, boolean defValue) {
        throw new UnsupportedOperationException("stub");
    }
}
