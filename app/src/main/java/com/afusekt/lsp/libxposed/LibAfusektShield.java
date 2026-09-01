package com.afusekt.lsp.libxposed;

import android.annotation.SuppressLint;
import android.os.Process;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Afusekt shield: block {@code libnative-lib.so} (HookBridge random-crash),
 * then unlock PRO with pure libxposed Java/native stubs — never call XposedBridge.
 */
public final class LibAfusektShield {

    private static final String AES_CRYPT = "com.attempt.afusekt.networkOffical.AesCryptNative";
    private static final AtomicBoolean LOAD_LIBRARY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean KILL_GUARD_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean EARLY_HOOKS_INSTALLED = new AtomicBoolean(false);

    private LibAfusektShield() {
    }

    public static void installEarly(
            ZoeModule module,
            XposedModuleInterface.PackageLoadedParam param
    ) {
        ClassLoader cl = param.getDefaultClassLoader();
        installLoadLibraryBlock(module);
        installKillGuard(module);
        if (EARLY_HOOKS_INSTALLED.compareAndSet(false, true)) {
            LibAfusektHooks.installBlockedMode(module, cl);
            module.log(4, ZoeIds.TAG, "Afusekt blocked-mode VIP hooks installed (onPackageLoaded)");
        }
    }

    /**
     * v3.2.5: native-lib.so now carries the sync encryption/decryption (AES-GCM key
     * material from nativeGetKeyMaterial). Blocking it broke sync ("Encryption failed").
     * We now ALLOW the library to load and instead neutralize its anti-hook integrity
     * check (checkMemoryIntegrityNative -> true via AesCryptNative.g hook) and keep the
     * exit/kill guards. JNI_OnLoad itself contains no trusted-environment abort here.
     */
    @SuppressLint("BlockedPrivateApi")
    private static void installLoadLibraryBlock(ZoeModule module) {
        if (!LOAD_LIBRARY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, ZoeIds.TAG, "Afusekt native-lib allowed (v3.2.5 sync needs real key)");
    }

    /** Only block crash-path / anti-hook suicide codes — never block clean exit(0). */
    private static void installKillGuard(ZoeModule module) {
        if (!KILL_GUARD_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Method systemExit = System.class.getDeclaredMethod("exit", int.class);
            module.hook(systemExit).setExceptionMode(mode).intercept(chain -> {
                int code = (Integer) chain.getArg(0);
                if (code != 0) {
                    module.log(4, ZoeIds.TAG, "blocked System.exit(" + code + ")");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Afusekt System.exit hook failed: " + t.getMessage());
        }
        try {
            Method runtimeExit = Runtime.class.getDeclaredMethod("exit", int.class);
            module.hook(runtimeExit).setExceptionMode(mode).intercept(chain -> {
                int code = (Integer) chain.getArg(0);
                if (code != 0) {
                    module.log(4, ZoeIds.TAG, "blocked Runtime.exit(" + code + ")");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Afusekt Runtime.exit hook failed: " + t.getMessage());
        }
        try {
            Method kill = Process.class.getDeclaredMethod("killProcess", int.class);
            module.hook(kill).setExceptionMode(mode).intercept(chain -> {
                int pid = (Integer) chain.getArg(0);
                if (pid == Process.myPid()) {
                    module.log(4, ZoeIds.TAG, "blocked Process.killProcess(self)");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Afusekt killProcess hook failed: " + t.getMessage());
        }
    }

    private static boolean isAesCryptNativeCaller(Object caller) {
        return caller instanceof Class && AES_CRYPT.equals(((Class<?>) caller).getName());
    }

    private static boolean isNativeLib(Object libraryName) {
        if (libraryName == null) {
            return false;
        }
        String name = String.valueOf(libraryName);
        return "native-lib".equals(name)
                || "libnative-lib.so".equals(name)
                || name.endsWith("/libnative-lib.so");
    }
}
