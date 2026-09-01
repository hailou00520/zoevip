package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class VidHubNisGuard {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private VidHubNisGuard() {
    }

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> jni = Class.forName("com.netease.nis.wrapper.MyJni", false, classLoader);
            XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(jni, "cp"),
                    XC_MethodReplacement.DO_NOTHING
            );
            XposedBridge.log(MainHook.TAG + ": MyJni.cp blocked");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": MyJni.cp hook failed: " + t.getMessage());
        }
    }
}
