package com.linglum.chat;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 薄壳（不参与热更新）：只做两件事 ——
 *   ① 让 HotLoader 决定用「补丁里的 ChatApp」还是「包里自带的 ChatApp」；
 *   ② 把 Activity 生命周期原样转发过去。
 * 真正的界面/业务全在 ChatApp 里（补丁可以整体替换它）。
 */
public class MainActivity extends Activity {

    private AppCore app;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = HotLoader.load(this);
        try {
            app.create(savedInstanceState);
            HotLoader.markBootOk(this);
        } catch (Throwable t) {
            // 补丁把启动搞崩了：丢掉补丁，用包里自带的那份再来一次
            HotLoader.discardPatch(this, "启动异常: " + t);
            app = HotLoader.loadBundled(this);
            app.create(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app != null) app.resume();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (app != null) app.newIntent(intent);
    }

    @Override
    protected void onDestroy() {
        if (app != null) app.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (app != null) {
            app.back();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (app != null) app.activityResult(req, result, data);
    }
}
