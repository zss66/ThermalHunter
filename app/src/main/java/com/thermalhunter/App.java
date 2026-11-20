package com.thermalhunter;

import android.app.Application;

import com.thermalhunter.core.L;

/** 只负责尽早初始化界面语言，让界面、悬浮窗、后台监测通知都使用设置里选的语言。 */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        L.init(this);
    }
}
