package com.thermalhunter.core;

/** 被 LSPosed 激活时，HookEntry 会把 isActive() 改为返回 true。 */
public final class ModuleStatus {
    private ModuleStatus() {}

    public static boolean isActive() {
        // 防止被编译器内联常量
        return Boolean.parseBoolean(System.getProperty("thermalhunter.never", "false"));
    }
}
