package com.afusekt.lsp.hook;

import android.content.Context;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Persist VidHub licenses to MCSettings keystore for offline Pro without module. */
public final class VidHubLicenseSeeder {

    private static final String HUB = "com.mac.utility.media.hub";
    private static final String LICENSES_HELPER = HUB + ".core.licenses.LicensesHelper";
    private static final String VH_LICENSE = HUB + ".core.licenses.VHLicense";
    private static final String MC_SETTINGS = HUB + ".settings.MCSettings";

    private VidHubLicenseSeeder() {
    }

    public static void seedIfNeeded(Context context, ClassLoader cl) {
        try {
            List<Object> saved = loadFromKeystore(cl);
            if (saved != null && !saved.isEmpty()) {
                applyInMemory(cl, saved);
                log("keystore licenses present (" + saved.size() + ")");
                return;
            }
            List<Object> lifetime = createLifetimeLicenses(cl);
            persist(cl, lifetime);
            applyInMemory(cl, lifetime);
            log("seeded lifetime license to keystore");
        } catch (Throwable t) {
            log("seed skipped: " + t.getMessage());
        }
    }

    public static List<Object> loadOrCreate(ClassLoader cl) throws Throwable {
        List<Object> saved = loadFromKeystore(cl);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        return createLifetimeLicenses(cl);
    }

    public static List<Object> createLifetimeLicenses(ClassLoader cl) throws Throwable {
        Class<?> licenseClass = Class.forName(VH_LICENSE, false, cl);
        Object license = licenseClass
                .getDeclaredConstructor(int.class, String.class)
                .newInstance(0, null);
        return Collections.singletonList(license);
    }

  @SuppressWarnings("unchecked")
    public static List<Object> loadFromKeystore(ClassLoader cl) {
        try {
            Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
            Method load = findMethod(settings, "loadLicensesFromKeystore", 0);
            if (load == null) {
                return null;
            }
            Object result = XposedHelpers.callStaticMethod(settings, "loadLicensesFromKeystore");
            if (result instanceof List) {
                return (List<Object>) result;
            }
        } catch (Throwable t) {
            log("loadFromKeystore failed: " + t.getMessage());
        }
        return null;
    }

    public static void persist(ClassLoader cl, List<?> licenses) {
        if (licenses == null || licenses.isEmpty()) {
            return;
        }
        try {
            Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
            Method save = findMethod(settings, "saveLicensesToKeystore", 1);
            if (save == null) {
                log("saveLicensesToKeystore missing");
                return;
            }
            XposedHelpers.callStaticMethod(settings, "saveLicensesToKeystore", licenses);
            log("persisted " + licenses.size() + " license(s) to keystore");
        } catch (Throwable t) {
            log("persist failed: " + t.getMessage());
        }
    }

    public static void applyInMemory(ClassLoader cl, List<?> licenses) {
        if (licenses == null || licenses.isEmpty()) {
            return;
        }
        try {
            Class<?> helper = Class.forName(LICENSES_HELPER, false, cl);
            Method setLicenses = findMethod(helper, "setLicenses", 1);
            if (setLicenses == null) {
                return;
            }
            if (XposedHelpers.callStaticMethod(helper, "setLicenses", licenses) != null) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> helper = Class.forName(LICENSES_HELPER, false, cl);
            Object instance = XposedHelpers.getStaticObjectField(helper, "INSTANCE");
            if (instance != null) {
                XposedHelpers.callMethod(instance, "setLicenses", licenses);
            }
        } catch (Throwable t) {
            log("applyInMemory failed: " + t.getMessage());
        }
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(MainHook.TAG + ":VidHubSeed: " + message);
    }
}
