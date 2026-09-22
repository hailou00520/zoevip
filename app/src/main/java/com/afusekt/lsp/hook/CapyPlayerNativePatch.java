package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XposedBridge;

import java.util.concurrent.atomic.AtomicBoolean;

final class CapyPlayerNativePatch {

    private static final String TAG = MainHook.TAG + ":CapyPlayer";
    private static final AtomicBoolean LIB_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean PATCHED = new AtomicBoolean(false);

    private CapyPlayerNativePatch() {
    }

    static void applyWhenLibAppLoaded() {
        if (PATCHED.get()) {
            return;
        }
        ensureNativeLoaded();
        if (!LIB_LOADED.get()) {
            return;
        }
        try {
            if (nativeApplyLibAppPatches()) {
                PATCHED.set(true);
                log("native PaywallGuard patch applied");
            }
        } catch (Throwable t) {
            log("native patch failed: " + t.getMessage());
        }
    }

    static void scheduleRetry() {
        if (PATCHED.get()) {
            return;
        }
        ensureNativeLoaded();
        if (!LIB_LOADED.get()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (nativeWaitAndApply(8000)) {
                        PATCHED.set(true);
                        log("native PaywallGuard patch applied (retry)");
                    }
                } catch (Throwable t) {
                    log("native patch retry failed: " + t.getMessage());
                }
            }
        }, "ZoeVIP-CapyNativePatch").start();
    }

    private static void ensureNativeLoaded() {
        if (LIB_LOADED.get()) {
            return;
        }
        synchronized (CapyPlayerNativePatch.class) {
            if (LIB_LOADED.get()) {
                return;
            }
            try {
                System.loadLibrary("zoevippatch");
                LIB_LOADED.set(true);
                log("libzoevippatch loaded");
            } catch (Throwable t) {
                log("libzoevippatch load failed: " + t.getMessage());
            }
        }
    }

    private static native void nativeInstallNetworkHooks();

    private static native boolean nativeApplyLibAppPatches();

    private static native boolean nativeWaitAndApply(int timeoutMs);

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
