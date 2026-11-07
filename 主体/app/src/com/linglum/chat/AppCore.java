package com.linglum.chat;

import android.content.Intent;
import android.os.Bundle;

/**
 * 壳 & 实现之间的唯一接口（热更新用）。
 *
 * 为什么要接口：补丁 dex 里的 ChatApp 由 DexClassLoader 加载，它的
 * `com.linglum.chat.ChatApp` 和 APK 自带的是**两个不同的类**（不同 ClassLoader），
 * 所以壳里不能强转成 ChatApp，只能转成这个两边共用的接口（接口由 APK 的 ClassLoader 提供）。
 */
public interface AppCore {
    void create(Bundle savedInstanceState);
    void resume();
    void newIntent(Intent intent);
    void destroy();
    void back();
    void activityResult(int req, int result, Intent data);
}
