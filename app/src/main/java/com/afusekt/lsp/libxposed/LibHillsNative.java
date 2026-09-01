package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native libapp patch for Hills (Zot-equivalent single copyWith site). */
final class LibHillsNative {

    private static final String TAG = ZoeIds.TAG + ":HillsNative";
    private static final AtomicBoolean LIB_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean PATCHED = new AtomicBoolean(false);
    private static final AtomicBoolean LOAD_LIBRARY_HOOKED = new AtomicBoolean(false);

    private LibHillsNative() {
    }

    static void installLoadMonitor(ZoeModule module) {
        if (!LOAD_LIBRARY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Method loadLibrary = resolveLoadLibraryMethod();
            if (loadLibrary == null) {
                LOAD_LIBRARY_HOOKED.set(false);
                module.log(5, TAG, "Runtime.loadLibrary0 not found");
                return;
            }
            loadLibrary.setAccessible(true);
            module.hook(loadLibrary)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object libArg = chain.getArgs().get(chain.getArgs().size() - 1);
                        if (libArg != null && isLibApp(libArg.toString())) {
                            applyWhenReady(module);
                            scheduleRetry(module);
                        }
                        return result;
                    });
            module.log(4, TAG, "Runtime.loadLibrary0 hooked");
        } catch (Throwable t) {
            LOAD_LIBRARY_HOOKED.set(false);
            module.log(5, TAG, "loadLibrary hook failed: " + t.getMessage());
        }
    }

    private static Method resolveLoadLibraryMethod() {
        try {
            return Runtime.class.getDeclaredMethod(
                    "loadLibrary0", ClassLoader.class, String.class);
        } catch (Throwable ignored) {
        }
        try {
            return Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLibApp(String lib) {
        return "libapp.so".equals(lib) || lib.endsWith("/libapp.so");
    }

    static void applyWhenReady(ZoeModule module) {
        if (PATCHED.get()) {
            return;
        }
        if (!ensureLoaded(module)) {
            return;
        }
        try {
            if (nativeApplyLibAppPatches()) {
                PATCHED.set(true);
                module.log(4, TAG, "libapp entitlement patch applied");
            }
        } catch (Throwable t) {
            module.log(5, TAG, "native patch failed: " + t.getMessage());
        }
    }

    static void scheduleRetry(ZoeModule module) {
        if (PATCHED.get() || !ensureLoaded(module)) {
            return;
        }
        long[] delays = {300L, 800L, 1500L, 3000L, 5000L, 8000L, 12000L, 20000L};
        for (long delay : delays) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (PATCHED.get()) {
                    return;
                }
                try {
                    if (nativeApplyLibAppPatches()) {
                        PATCHED.set(true);
                        module.log(4, TAG, "libapp entitlement patch applied (retry@" + delay + ")");
                    }
                } catch (Throwable ignored) {
                }
            }, "ZoeVIP-hills-native-" + delay);
            t.setDaemon(true);
            t.start();
        }
    }

    private static boolean ensureLoaded(ZoeModule module) {
        if (LIB_LOADED.get()) {
            return true;
        }
        synchronized (LibHillsNative.class) {
            if (LIB_LOADED.get()) {
                return true;
            }
            try {
                System.loadLibrary("zoevippatch");
                LIB_LOADED.set(true);
                module.log(4, TAG, "libzoevippatch loaded");
                return true;
            } catch (Throwable t) {
                module.log(5, TAG, "libzoevippatch load failed: " + t.getMessage());
                return false;
            }
        }
    }

    private static native boolean nativeApplyLibAppPatches();
}
