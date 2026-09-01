package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Afusekt ships anti-hook code in {@code libnative-lib.so} (log tag HookBridge).
 * Only {@code AesCryptNative} loads this library. VIP checks are handled by Java hooks.
 *
 * <p>Only intercept {@code Runtime.loadLibrary0} when the caller is {@code AesCryptNative}.
 * Do not hook {@link System#loadLibrary(String)} globally — that breaks framework JNI.
 */
public final class AfusektAntiHookBypass {

    private static final String AES_CRYPT = "com.attempt.afusekt.networkOffical.AesCryptNative";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AfusektAntiHookBypass() {
    }

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookRuntimeLoadLibrary0();
        XposedBridge.log(MainHook.TAG + ": Afusekt anti-hook bypass installed");
    }

    private static void hookRuntimeLoadLibrary0() {
        try {
            XposedHelpers.findAndHookMethod(
                    Runtime.class,
                    "loadLibrary0",
                    ClassLoader.class,
                    Class.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isAesCryptNativeCaller(param.args[1])) {
                                return;
                            }
                            if (isBlockedNativeLib(param.args[2])) {
                                XposedBridge.log(MainHook.TAG + ": blocked AesCryptNative native-lib load");
                                param.setResult(null);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Runtime.loadLibrary0 bypass failed: " + t.getMessage());
        }
    }

    private static boolean isAesCryptNativeCaller(Object caller) {
        if (!(caller instanceof Class)) {
            return false;
        }
        return AES_CRYPT.equals(((Class<?>) caller).getName());
    }

    private static boolean isBlockedNativeLib(Object libraryName) {
        if (libraryName == null) {
            return false;
        }
        String name = String.valueOf(libraryName);
        return "native-lib".equals(name)
                || "libnative-lib.so".equals(name)
                || name.endsWith("/libnative-lib.so");
    }
}
