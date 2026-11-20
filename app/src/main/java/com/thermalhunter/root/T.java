package com.thermalhunter.root;

/**
 * root 端的文字令牌。root 进程（app_process）读不到 App 的语言资源，
 * 所以提示文字写成 \u0001key\u0002参数…\u0003，App 收到响应后由 core.L.tr 按当前语言换成 strings.xml 里的文字。
 * key 就是 strings.xml 里的 name。令牌可以和普通文字拼接，但不能嵌套（参数里不能再放令牌）。
 */
public final class T {
    private T() {
    }

    public static String k(String key, Object... args) {
        StringBuilder b = new StringBuilder().append('\u0001').append(key);
        for (Object a : args) b.append('\u0002').append(a);
        return b.append('\u0003').toString();
    }
}
