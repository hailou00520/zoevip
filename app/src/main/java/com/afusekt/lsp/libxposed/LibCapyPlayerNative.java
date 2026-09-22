package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Native libapp + network intercept for CapyPlayer (libxposed path). */
final class LibCapyPlayerNative {

    private static final String TAG = ZoeIds.TAG + ":CapyNative";
    /** CapyPlayer 1.1.5 fixed patch table size in libzoevippatch. */
    /** Prefer 1.1.6 (4) or 1.1.5 (23) fixed tables. */
    private static final int CAPY115_PATCH_COUNT = 4;
    private static final AtomicBoolean LIB_LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean PATCHED = new AtomicBoolean(false);
    private static final AtomicBoolean LOAD_LIBRARY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean NETWORK_HOOKS_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicInteger LAST_PATCH_COUNT = new AtomicInteger(0);

    private LibCapyPlayerNative() {
    }

    static boolean isPatched() {
        return PATCHED.get();
    }

    static void ensureLoaded(ZoeModule module) {
        if (LIB_LOADED.get()) {
            return;
        }
        synchronized (LibCapyPlayerNative.class) {
            if (LIB_LOADED.get()) {
                return;
            }
            try {
                System.loadLibrary("zoevippatch");
                LIB_LOADED.set(true);
                module.log(4, TAG, "libzoevippatch loaded");
                // Install DNS block ASAP so subscription sync cannot reset lifetime seed.
                try {
                    nativeInstallNetworkHooks();
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                module.log(5, TAG, "libzoevippatch load failed: " + t.getMessage());
            }
        }
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
                        if (libArg != null) {
                            String lib = libArg.toString();
                            if (isLibApp(lib)) {
                                onLibAppMapped(module, resolveAppClassLoader(chain));
                            }
                            if (isFlutterLib(lib)) {
                                ensureLoaded(module);
                                try {
                                    nativeInstallNetworkHooks();
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                        return result;
                    });
            module.log(4, TAG, "Runtime.loadLibrary0 hooked");
            hookFlutterJniLoadLibrary(module);
        } catch (Throwable t) {
            LOAD_LIBRARY_HOOKED.set(false);
            module.log(5, TAG, "loadLibrary hook failed: " + t.getMessage());
        }
    }

    private static void hookFlutterJniLoadLibrary(ZoeModule module) {
        try {
            Class<?> flutterJni = Class.forName("io.flutter.embedding.engine.FlutterJNI");
            Method loadLibrary = flutterJni.getDeclaredMethod(
                    "loadLibrary", android.content.Context.class);
            module.hook(loadLibrary)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        onLibAppMapped(module, null);
                        return result;
                    });
            module.log(4, TAG, "FlutterJNI.loadLibrary hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "FlutterJNI.loadLibrary hook skipped: " + t.getMessage());
        }
    }

    static void onLibAppLoaded(ZoeModule module, ClassLoader cl) {
        onLibAppMapped(module, cl);
    }

    private static void onLibAppMapped(ZoeModule module, ClassLoader cl) {
        PATCHED.set(false);
        PATCH_RETRY_STARTED.set(false);
        LAST_PATCH_COUNT.set(0);
        if (cl != null) {
            LibCapyPlayerJavaHooks.onLibAppLoaded(module, cl);
        }
        ensurePatchesApplied(module, true);
        startPatchRetry(module);
    }

    static void applyWhenReady(ZoeModule module) {
        ensurePatchesApplied(module, false);
    }

    static void ensurePatchesApplied(ZoeModule module, boolean forceLog) {
        ensureLoaded(module);
        if (!LIB_LOADED.get()) {
            return;
        }
        try {
            int count = nativeApplyLibAppPatchCount();
            int previous = LAST_PATCH_COUNT.getAndSet(count);
            if (count >= CAPY115_PATCH_COUNT) {
                if (!PATCHED.getAndSet(true)) {
                    module.log(4, TAG, "libapp patches complete (" + count + "/" + CAPY115_PATCH_COUNT + ")");
                    scheduleNetworkHooks(module);
                }
            } else if (forceLog || count != previous) {
                module.log(4, TAG, "libapp partial patches " + count + "/" + CAPY115_PATCH_COUNT);
                if (count < previous) {
                    PATCHED.set(false);
                }
                startPatchRetry(module);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "native patch failed: " + t.getMessage());
            startPatchRetry(module);
        }
    }

    static void startPatchRetry(ZoeModule module) {
        scheduleDeferredPatch(module);
    }

    private static final AtomicBoolean PATCH_RETRY_STARTED = new AtomicBoolean(false);

    private static void scheduleDeferredPatch(ZoeModule module) {
        if (PATCHED.get()) {
            return;
        }
        if (!PATCH_RETRY_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            long[] delays = {100L, 250L, 500L, 1000L, 1500L, 3000L, 5000L, 8000L, 12000L, 20000L, 30000L};
            for (long delay : delays) {
                if (PATCHED.get()) {
                    return;
                }
                try {
                    Thread.sleep(delay);
                    ensureLoaded(module);
                    if (!LIB_LOADED.get()) {
                        continue;
                    }
                    int count = nativeApplyLibAppPatchCount();
                    LAST_PATCH_COUNT.set(count);
                    if (count >= CAPY115_PATCH_COUNT) {
                        PATCHED.set(true);
                        module.log(4, TAG, "libapp patches complete (@" + delay + "ms, " + count + ")");
                        scheduleNetworkHooks(module);
                        return;
                    }
                    if (nativeWaitAndApply(500)) {
                        count = nativeApplyLibAppPatchCount();
                        LAST_PATCH_COUNT.set(count);
                        if (count >= CAPY115_PATCH_COUNT) {
                            PATCHED.set(true);
                            module.log(4, TAG, "libapp patches complete (wait @" + delay + "ms, " + count + ")");
                            scheduleNetworkHooks(module);
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            module.log(5, TAG, "libapp patch incomplete after retries (" + LAST_PATCH_COUNT.get() + "/"
                    + CAPY115_PATCH_COUNT + ")");
            PATCH_RETRY_STARTED.set(false);
        }, "ZoeVIP-CapyNative");
        t.setDaemon(true);
        t.start();
    }

    private static Method resolveLoadLibraryMethod() {
        try {
            return Runtime.class.getDeclaredMethod(
                    "loadLibrary0", ClassLoader.class, Class.class, String.class);
        } catch (Throwable ignored) {
        }
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
        return "libapp.so".equals(lib) || lib.endsWith("/libapp.so") || "app".equals(lib);
    }

    private static boolean isFlutterLib(String lib) {
        return "libflutter.so".equals(lib) || lib.endsWith("/libflutter.so")
                || "flutter".equals(lib);
    }

    private static ClassLoader resolveAppClassLoader(io.github.libxposed.api.XposedInterface.Chain chain) {
        for (Object arg : chain.getArgs()) {
            if (arg instanceof ClassLoader cl) {
                return cl;
            }
            if (arg instanceof Class<?> cls && cls.getClassLoader() != null) {
                return cls.getClassLoader();
            }
        }
        return ClassLoader.getSystemClassLoader();
    }

    private static void scheduleNetworkHooks(ZoeModule module) {
        if (!NETWORK_HOOKS_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            long[] delays = {0L, 50L, 100L, 200L, 400L, 800L, 1500L, 3000L, 6000L, 12000L};
            for (long delay : delays) {
                try {
                    Thread.sleep(delay);
                    ensureLoaded(module);
                    if (!LIB_LOADED.get()) {
                        continue;
                    }
                    nativeInstallNetworkHooks();
                    module.log(4, TAG, "network hook attempt @" + delay + "ms");
                } catch (Throwable e) {
                    module.log(5, TAG, "network hooks retry failed: " + e.getMessage());
                }
            }
        }, "ZoeVIP-CapyNet");
        t.setDaemon(true);
        t.start();
    }

    private static native void nativeInstallNetworkHooks();

    private static native int nativeApplyLibAppPatchCount();

    private static native boolean nativeWaitAndApply(int timeoutMs);
}
