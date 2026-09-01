package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.util.concurrent.atomic.AtomicBoolean;

/** Native libnesec _exit(28) guard for VidHub. */
public final class LibVidHubNative {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static {
        try {
            System.loadLibrary("zoevippatch");
        } catch (Throwable ignored) {
        }
    }

    private LibVidHubNative() {
    }

    private static final AtomicBoolean MAPS_FILTER_STARTED = new AtomicBoolean(false);

    public static void startMapsFilter(ZoeModule module) {
        if (!MAPS_FILTER_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            if (nativeInstallMapsFilter()) {
                module.log(4, ZoeIds.TAG, "native /proc/maps filter installed");
            } else {
                module.log(5, ZoeIds.TAG, "native /proc/maps filter not installed");
            }
        } catch (Throwable t) {
            MAPS_FILTER_STARTED.set(false);
            module.log(5, ZoeIds.TAG, "native maps filter failed: " + t.getMessage());
        }
    }

    public static void startExitGuard(ZoeModule module) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            nativeStartNesecExitGuard();
            module.log(4, ZoeIds.TAG, "native nesec exit guard started");
        } catch (Throwable t) {
            STARTED.set(false);
            module.log(5, ZoeIds.TAG, "native nesec exit guard failed: " + t.getMessage());
        }
    }

    private static native boolean nativeInstallMapsFilter();

    private static native void nativeStartNesecExitGuard();
}
