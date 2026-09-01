package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import com.afusekt.lsp.hook.WebDavSyncHooks;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runtime hooks for Afusekt (new Compose architecture, v3.2.x).
 *
 * <p>v3.2.5 reverse engineering notes:</p>
 * <ul>
 *   <li>VIP state is centralized in {@code pb6} (methods a–f, all boolean gates
 *       composed of {@code mk9.a} signature check + {@code AesCryptNative} native flags).</li>
 *   <li>Old classes {@code RoleValue} / {@code MyAppConfig} / {@code BaseActivity} /
 *       {@code BaseFragment} are gone (R8 + Compose rewrite). Hooks for them are
 *       kept as no-op fallbacks for older versions.</li>
 *   <li>WebDAV sync moved from {@code VideoLibraryFragment} to the Coroutine service
 *       {@code com.attempt.afusekt.service.VideoDataSyncService} (adapted separately).</li>
 * </ul>
 */
public final class AfusektHooks {

    private static final String ROLE_VALUE = "com.attempt.afusekt.tools.RoleValue";
    private static final String AES_CRYPT = "com.attempt.afusekt.networkOffical.AesCryptNative";
    private static final String MY_APP_CONFIG = "com.attempt.afusekt.MyAppConfig";
    private static final String VIP_GATE = "pb6";   // v3.2.5 unified VIP gate (a–f -> true)

    private static final String[] PRO_FLAG_FIELDS = {"e", "q"};

    private static final AtomicBoolean EARLY_VIP_HOOKED = new AtomicBoolean(false);

    private AfusektHooks() {
    }

    /**
     * VIP + anti-hook hooks that must run in {@code onPackageLoaded}, before
     * {@code AesCryptNative} static init loads {@code libnative-lib.so}.
     */
    public static void applyEarly(ClassLoader cl) {
        AfusektAntiHookBypass.install(cl);
        if (!EARLY_VIP_HOOKED.compareAndSet(false, true)) {
            return;
        }
        hookVipGate(cl);
        hookRoleValue(cl);
        hookAesCryptNative(cl);
        hookMyAppConfig(cl);
        XposedBridge.log(MainHook.TAG + ": Afusekt early VIP hooks installed");
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        applyEarly(cl);
        hookProDialogs(cl);
        WebDavSyncHooks.apply(lpparam);
        WebDavSettingsUiHooks.apply(cl);
    }

    /**
     * v3.2.x unified VIP gate: pb6.a–f all drive PRO/VIP UI. Forcing them true
     * bypasses both the {@code mk9.a} signature gate and native flag checks.
     */
    private static void hookVipGate(ClassLoader cl) {
        try {
            Class<?> gate = XposedHelpers.findClass(VIP_GATE, cl);
            for (String name : new String[]{"a", "b", "c", "d", "e", "f"}) {
                try {
                    XposedHelpers.findAndHookMethod(gate, name, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return true;
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(MainHook.TAG + ": pb6." + name + " hook failed: " + t.getMessage());
                }
            }
            XposedBridge.log(MainHook.TAG + ": pb6 VIP gate hooked (a–f -> true)");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": pb6 VIP gate missing (old version): " + t.getMessage());
        }
    }

    /**
     * RoleValue.a = forever, b = free, c = month, d = pro, e = paid.
     * Kept for older versions where this class exists.
     */
    private static void hookRoleValue(ClassLoader cl) {
        Class<?> roleValue = findClass(ROLE_VALUE, cl);
        if (roleValue == null) {
            return;
        }

        hookBooleanStatic(roleValue, "a", true);
        hookBooleanStatic(roleValue, "b", false);
        hookBooleanStatic(roleValue, "c", true);
        hookBooleanStatic(roleValue, "d", true);
        hookBooleanStatic(roleValue, "e", true);

        XposedBridge.log(MainHook.TAG + ": RoleValue hooked");
    }

    /**
     * Native VIP flags exposed as Boolean wrappers.
     */
    private static void hookAesCryptNative(ClassLoader cl) {
        Class<?> aes = findClass(AES_CRYPT, cl);
        if (aes == null) {
            return;
        }

        hookBooleanWrapper(aes, "a", true);
        hookBooleanWrapper(aes, "b", false);
        hookBooleanWrapper(aes, "c", true);
        hookBooleanWrapper(aes, "d", true);
        hookBooleanWrapper(aes, "e", true);
        hookBooleanWrapper(aes, "f", true);

        try {
            // g() == checkMemoryIntegrityNative: neutralize so's anti-hook check so the
            // real library loads and provides the true sync encryption key.
            XposedHelpers.findAndHookMethod(aes, "g", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }

        // h(String)/i(int)/k(byte[]) run the REAL native implementations (so is allowed
        // to load now). Do NOT fake them, otherwise AES-GCM sync fails ("Encryption failed").

        XposedBridge.log(MainHook.TAG + ": AesCryptNative hooked");
    }

    /**
     * MyAppConfig.e / q PRO flags — only exists in older versions.
     */
    private static void hookMyAppConfig(ClassLoader cl) {
        Class<?> config = findClass(MY_APP_CONFIG, cl);
        if (config == null) {
            return;
        }

        try {
            XposedHelpers.findAndHookConstructor(config, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setProFlag(param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": MyAppConfig constructor hook failed: " + t.getMessage());
        }

        Class<?> companion = findClass(MY_APP_CONFIG + "$Companion", cl);
        if (companion != null) {
            try {
                XposedHelpers.findAndHookMethod(companion, "a", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object cached = param.getResult();
                            setProFlag(cached);
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        setProFlag(param.getResult());
                    }
                });
                Object instance = XposedHelpers.callStaticMethod(companion, "a");
                setProFlag(instance);
            } catch (Throwable t) {
                XposedBridge.log(MainHook.TAG + ": MyAppConfig companion hook failed: " + t.getMessage());
            }
        }

        XposedBridge.log(MainHook.TAG + ": MyAppConfig hooked");
    }

    static void ensureProFlags(ClassLoader cl) {
        try {
            Class<?> companion = XposedHelpers.findClass(MY_APP_CONFIG + "$Companion", cl);
            Object config = XposedHelpers.callStaticMethod(companion, "a");
            setProFlag(config);
        } catch (Throwable ignored) {
        }
    }

    private static void setProFlag(Object configInstance) {
        if (configInstance == null) {
            return;
        }
        for (String field : PRO_FLAG_FIELDS) {
            try {
                XposedHelpers.setBooleanField(configInstance, field, true);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookProDialogs(ClassLoader cl) {
        try {
            Class<?> baseActivity = findClass("com.attempt.afusekt.base.BaseActivity", cl);
            if (baseActivity == null) {
                return;
            }
            XposedHelpers.findAndHookMethod(baseActivity, "X", String.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof String && isLocalProGate((String) param.args[0])) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(MainHook.TAG + ": BaseActivity PRO dialog hooked");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": BaseActivity hook skipped: " + t.getMessage());
        }

        try {
            Class<?> baseFragment = findClass("com.attempt.afusekt.base.BaseFragment", cl);
            if (baseFragment == null) {
                return;
            }
            XposedHelpers.findAndHookMethod(baseFragment, "showToast", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof String && isPermissionToast((String) param.args[0])) {
                        XposedBridge.log(MainHook.TAG + ": suppress permission toast");
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(MainHook.TAG + ": BaseFragment permission toast hooked");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": BaseFragment toast hook skipped: " + t.getMessage());
        }
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

    private static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": missing class " + name + ": " + t.getMessage());
            return null;
        }
    }

    private static void hookBooleanStatic(Class<?> clazz, String methodName, boolean value) {
        if (clazz == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return value;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": hook " + methodName + " failed: " + t.getMessage());
        }
    }

    private static void hookBooleanWrapper(Class<?> clazz, String methodName, boolean value) {
        if (clazz == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return Boolean.valueOf(value);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": hook " + methodName + " failed: " + t.getMessage());
        }
    }
}
