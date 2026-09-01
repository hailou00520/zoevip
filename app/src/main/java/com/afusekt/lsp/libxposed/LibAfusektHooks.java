package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Afusekt PRO unlock via pure libxposed (no XposedBridge).
 *
 * <p>HookBridge inside {@code libnative-lib.so} triggers "random crash" when it
 * detects hooks after the SO is loaded. Blocked-mode therefore:</p>
 * <ol>
 *   <li>Never loads the SO (see {@link LibAfusektShield})</li>
 *   <li>Replaces RoleValue + AesCryptNative Java wrappers + native stubs so
 *       callers never hit {@code UnsatisfiedLinkError}</li>
 * </ol>
 */
public final class LibAfusektHooks {

    private static final String ROLE_VALUE = "com.attempt.afusekt.tools.RoleValue";
    private static final String AES_CRYPT = "com.attempt.afusekt.networkOffical.AesCryptNative";
    private static final String MY_APP_CONFIG = "com.attempt.afusekt.MyAppConfig";
    private static final String BASE_ACTIVITY = "com.attempt.afusekt.base.BaseActivity";
    private static final String BASE_FRAGMENT = "com.attempt.afusekt.base.BaseFragment";
    private static final String VIP_GATE = "pb6";   // v3.2.x unified VIP gate

    /** Only documented PRO flags; never touch other booleans (home/backdrop/nav UI state). */
    private static final String[] PRO_FLAG_FIELDS = {"e", "q"};

    private static final AtomicBoolean BLOCKED_MODE_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean LATE_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean NATIVE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean MY_APP_CONFIG_HOOKED = new AtomicBoolean(false);

    private LibAfusektHooks() {
    }

    /**
     * Primary path: SO blocked. Hook everything needed before any VIP call.
     */
    public static void installBlockedMode(ZoeModule module, ClassLoader cl) {
        if (!BLOCKED_MODE_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            hookVipGate(module, cl, mode);
            hookRoleValue(module, cl, mode);
            hookAesCryptFully(module, cl, mode);
            hookMyAppConfig(module, cl, mode);
            module.log(4, ZoeIds.TAG, "Afusekt blocked-mode hooks OK (libxposed)");
        } catch (Throwable t) {
            BLOCKED_MODE_INSTALLED.set(false);
            module.log(6, ZoeIds.TAG, "Afusekt blocked-mode hooks failed: " + t.getMessage(), t);
        }
    }

    /** Kept for compatibility if SO somehow loads. */
    public static void onNativeLibLoaded(ZoeModule module, ClassLoader cl) {
        module.log(5, ZoeIds.TAG, "native-lib loaded unexpectedly — reinforcing hooks");
        installBlockedMode(module, cl);
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        if (NATIVE_HOOKED.compareAndSet(false, true)) {
            hookAesCryptNativesOnly(module, cl, mode);
        }
    }

    /** Legacy name used by older shield — maps to blocked mode. */
    public static void installEarly(ZoeModule module, ClassLoader cl) {
        installBlockedMode(module, cl);
    }

    public static void installLate(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        installBlockedMode(module, cl);
        if (LATE_INSTALLED.compareAndSet(false, true)) {
            XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
            hookProDialogs(module, cl, mode);
            hookPermissionToast(module, cl, mode);
            try {
                LibAfusektWebDav.installLate(module, param);
            } catch (Throwable t) {
                module.log(5, ZoeIds.TAG, "WebDAV late install skipped: " + t.getMessage());
            }
            try {
                LibWebDavSyncV2.onPackageReady(module, cl);
            } catch (Throwable t) {
                module.log(5, ZoeIds.TAG, "WebDAV v2 sync install skipped: " + t.getMessage());
            }
            module.log(4, ZoeIds.TAG, "Afusekt libxposed late hooks installed");
        }
    }

    /**
     * Patch only PRO booleans when config is created/read. No timers, no UI hooks —
     * keeps Afusekt home/library backdrop behavior native.
     */
    private static void hookMyAppConfig(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        if (!MY_APP_CONFIG_HOOKED.compareAndSet(false, true)) {
            return;
        }
        Class<?> config = findClass(module, MY_APP_CONFIG, cl);
        if (config != null) {
            try {
                Constructor<?> ctor = config.getDeclaredConstructor();
                module.hook(ctor)
                        .setExceptionMode(mode)
                        .intercept(chain -> {
                            chain.proceed();
                            setProFlag(chain.getThisObject());
                            return null;
                        });
            } catch (Throwable t) {
                module.log(5, ZoeIds.TAG, "MyAppConfig ctor hook failed: " + t.getMessage());
            }
        }
        Class<?> companion = findClass(module, MY_APP_CONFIG + "$Companion", cl);
        if (companion == null) {
            return;
        }
        Method get = findMethod(companion, "a", 0);
        if (get == null) {
            return;
        }
        module.hook(get)
                .setExceptionMode(mode)
                .intercept(chain -> {
                    Object instance = chain.proceed();
                    setProFlag(instance);
                    return instance;
                });
        module.log(4, ZoeIds.TAG, "MyAppConfig getter hooked (e/q only)");
    }

    /**
     * v3.2.x unified VIP gate: pb6.a–f all drive PRO/VIP UI. Forcing them true
     * bypasses both the {@code mk9.a} signature gate and native flag checks.
     */
    private static void hookVipGate(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> gate = findClass(module, VIP_GATE, cl);
        if (gate == null) {
            module.log(5, ZoeIds.TAG, "pb6 VIP gate missing (old version)");
            return;
        }
        for (String name : new String[]{"a", "b", "c", "d", "e", "f"}) {
            Method method = findMethod(gate, name, 0);
            if (method == null) {
                module.log(5, ZoeIds.TAG, "missing pb6." + name);
                continue;
            }
            module.hook(method).setExceptionMode(mode).intercept(chain -> true);
            logHook(module, method);
        }
        module.log(4, ZoeIds.TAG, "pb6 VIP gate hooked (a–f -> true)");
    }

    private static void hookRoleValue(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> role = findClass(module, ROLE_VALUE, cl);
        if (role == null) {
            return;
        }
        hookZeroArg(module, role, "a", true, mode);
        hookZeroArg(module, role, "b", false, mode);
        hookZeroArg(module, role, "c", true, mode);
        hookZeroArg(module, role, "d", true, mode);
        hookZeroArg(module, role, "e", true, mode);
        module.log(4, ZoeIds.TAG, "RoleValue hooked (libxposed)");
    }

    /**
     * Hook Java wrappers a–h and native stubs so blocked SO never causes ULE.
     */
    private static void hookAesCryptFully(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        // Force class load: static init will hit our loadLibrary0 block.
        Class<?> aes = findClass(module, AES_CRYPT, cl);
        if (aes == null) {
            return;
        }
        try {
            Class.forName(AES_CRYPT, true, cl);
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "AesCryptNative init: " + t.getMessage());
        }

        hookZeroArg(module, aes, "a", Boolean.TRUE, mode);
        hookZeroArg(module, aes, "b", Boolean.FALSE, mode);
        hookZeroArg(module, aes, "c", Boolean.TRUE, mode);
        hookZeroArg(module, aes, "d", Boolean.TRUE, mode);
        hookZeroArg(module, aes, "e", Boolean.TRUE, mode);
        hookZeroArg(module, aes, "f", Boolean.TRUE, mode);

        Method g = findMethod(aes, "g", 0);
        if (g != null) {
            // g() == checkMemoryIntegrityNative: neutralize the so's anti-hook check
            // so the real library can load and provide the true sync encryption key.
            module.hook(g).setExceptionMode(mode).intercept(chain -> true);
            logHook(module, g);
        }
        // h(String)/i(int)/k(byte[]) now run the REAL native implementations —
        // the library is allowed to load (see LibAfusektShield). Do NOT fake them,
        // otherwise AES-GCM sync encryption fails ("Encryption failed: 安全错误").

        hookAesCryptNativesOnly(module, cl, mode);
        module.log(4, ZoeIds.TAG, "AesCryptNative wrappers+natives hooked (allowed mode)");
    }

    private static void hookAesCryptNativesOnly(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> aes = findClass(module, AES_CRYPT, cl);
        if (aes == null) {
            return;
        }
        // VIP flags come from pb6 (hooked true). The native VIP getters here are left
        // untouched so the real library behaves natively; faking nativeDecrypt /
        // nativeParseStatusMessage would corrupt sync encryption/decryption.
        module.log(4, ZoeIds.TAG, "AesCryptNative native methods left real (allowed mode)");
    }

    private static void hookNativeBool(
            ZoeModule module,
            Class<?> cls,
            String name,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, name, 0);
        if (method == null) {
            module.log(5, ZoeIds.TAG, "missing " + cls.getSimpleName() + "." + name);
            return;
        }
        module.hook(method).setExceptionMode(mode).intercept(chain -> value);
        logHook(module, method);
    }

    private static void setProFlag(Object configInstance) {
        if (configInstance == null) {
            return;
        }
        for (String name : PRO_FLAG_FIELDS) {
            try {
                Field field = configInstance.getClass().getDeclaredField(name);
                field.setAccessible(true);
                if (field.getType() == boolean.class) {
                    field.setBoolean(configInstance, true);
                } else if (field.getType() == Boolean.class) {
                    field.set(configInstance, Boolean.TRUE);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookProDialogs(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> activity = findClass(module, BASE_ACTIVITY, cl);
        if (activity == null) {
            return;
        }
        Method dialog = findMethod(activity, "X", 2);
        if (dialog == null) {
            return;
        }
        module.hook(dialog).setExceptionMode(mode).intercept(chain -> {
            Object arg0 = chain.getArg(0);
            if (arg0 instanceof String && isLocalProGate((String) arg0)) {
                return null;
            }
            return chain.proceed();
        });
        logHook(module, dialog);
    }

    private static void hookPermissionToast(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> fragment = findClass(module, BASE_FRAGMENT, cl);
        if (fragment == null) {
            return;
        }
        Method toast = findMethod(fragment, "showToast", 1);
        if (toast == null) {
            return;
        }
        module.hook(toast).setExceptionMode(mode).intercept(chain -> {
            Object arg0 = chain.getArg(0);
            if (arg0 instanceof String && isPermissionToast((String) arg0)) {
                return null;
            }
            return chain.proceed();
        });
        logHook(module, toast);
    }

    private static boolean isPermissionToast(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return message.contains("无权限")
                || message.contains("無權限")
                || message.equalsIgnoreCase("No Permission");
    }

    private static boolean isLocalProGate(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("仅为pro") || lower.contains("仅为订阅");
    }

    private static void hookZeroArg(
            ZoeModule module,
            Class<?> cls,
            String name,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, name, 0);
        if (method == null) {
            module.log(5, ZoeIds.TAG, "missing " + cls.getSimpleName() + "." + name);
            return;
        }
        module.hook(method).setExceptionMode(mode).intercept(chain -> value);
        logHook(module, method);
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        if (cls == null) {
            return null;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static Class<?> findClass(ZoeModule module, String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "missing class " + name + ": " + t.getMessage());
            return null;
        }
    }

    private static void logHook(ZoeModule module, Method method) {
        module.log(4, ZoeIds.TAG, "Afusekt hook "
                + method.getDeclaringClass().getName() + "." + method.getName());
    }
}
