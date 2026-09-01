package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;

/** Native fopen filter for proc maps (NIS scans this in native code). */
public final class VidHubNativeGuard {

    private static final AtomicBoolean LOADED = new AtomicBoolean(false);

    static {
        try {
            System.loadLibrary("zoevippatch");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": zoevippatch load failed: " + t.getMessage());
        }
    }

    private VidHubNativeGuard() {
    }

    public static void install() {
        if (!LOADED.compareAndSet(false, true)) {
            return;
        }
        try {
            if (nativeInstallMapsFilter()) {
                XposedBridge.log(MainHook.TAG + ": native /proc/maps filter installed");
            } else {
                XposedBridge.log(MainHook.TAG + ": native /proc/maps filter not installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": native maps filter failed: " + t.getMessage());
        }
    }

    private static native boolean nativeInstallMapsFilter();
}
