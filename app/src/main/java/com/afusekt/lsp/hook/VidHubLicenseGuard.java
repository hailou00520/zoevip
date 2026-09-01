package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Prevent server sync from clearing persisted VidHub licenses. */
public final class VidHubLicenseGuard {

    private static final String MC_SETTINGS = "com.mac.utility.media.hub.settings.MCSettings";

    private VidHubLicenseGuard() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        install(lpparam.classLoader);
    }

    public static void install(ClassLoader cl) {
        try {
            Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
            hookRemoveLicenses(settings, cl);
            hookSaveLicenses(settings, cl);
            XposedBridge.log(MainHook.TAG + ": VidHub license guard installed");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": VidHub license guard skipped: " + t.getMessage());
        }
    }

    private static void hookRemoveLicenses(Class<?> settings, ClassLoader cl) {
        Method remove = findMethod(settings, "removeLicenses", 0);
        if (remove == null) {
            return;
        }
        XposedBridge.hookMethod(remove, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                List<Object> saved = VidHubLicenseSeeder.loadFromKeystore(cl);
                if (saved != null && !saved.isEmpty()) {
                    param.setResult(null);
                    XposedBridge.log(MainHook.TAG + ":VidHubGuard: blocked removeLicenses");
                }
            }
        });
    }

    private static void hookSaveLicenses(Class<?> settings, ClassLoader cl) {
        Method save = findMethod(settings, "saveLicensesToKeystore", 1);
        if (save == null || !List.class.isAssignableFrom(save.getParameterTypes()[0])) {
            return;
        }
        XposedBridge.hookMethod(save, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object arg = param.args[0];
                if (arg instanceof List && ((List<?>) arg).isEmpty()) {
                    List<Object> saved = VidHubLicenseSeeder.loadFromKeystore(cl);
                    if (saved != null && !saved.isEmpty()) {
                        param.args[0] = saved;
                        XposedBridge.log(MainHook.TAG + ":VidHubGuard: blocked empty keystore write");
                    }
                }
            }
        });
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }
}
