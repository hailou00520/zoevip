package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Avoid double-hooking VidHub when Zot is also scoped. */
public final class VidHubScopeGuard {

    private static final String[] ZOT_MARKERS = {
            "defpackage.f91",
            "top.obsidian.zot.Entry",
    };

    private VidHubScopeGuard() {
    }

    public static boolean isZotScoped(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader[] loaders = {
                lpparam.classLoader,
                Thread.currentThread().getContextClassLoader(),
                VidHubScopeGuard.class.getClassLoader(),
        };
        for (String marker : ZOT_MARKERS) {
            for (ClassLoader loader : loaders) {
                if (loader == null) {
                    continue;
                }
                try {
                    Class.forName(marker, false, loader);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    public static void logZotConflict() {
        XposedBridge.log(MainHook.TAG + ": Zot also scoped to VidHub — disable ZoeVIP scope for VidHub");
    }
}
