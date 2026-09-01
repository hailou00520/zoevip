package com.afusekt.lsp.hook;

import android.app.Application;
import android.content.pm.ApplicationInfo;

import com.afusekt.lsp.MainHook;

import dalvik.system.DexFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scene / VTools ({@code com.omarea.vtools}) professional unlock.
 * Ported from Zot Scene hooks — stable {@code com.omarea.model.*} plus dex scan for obfuscated helpers.
 */
public final class VToolsHooks {

    private static final String TAG = MainHook.TAG + ":VTools";

    private static final String ACTIVATED_STATE = "com.omarea.model.ActivatedStateModel";
    private static final String ACTIVATION_CODE = "com.omarea.model.ActivationCodeResponse";
    private static final String LOGIN_RESPONSE = "com.omarea.model.LoginResponse";
    private static final String ACCOUNT_POINTS = "com.omarea.model.AccountPointsResponse";

    private static final String FAKE_CODE = "ZOE00000000000000|ZOE";
    private static final String FAKE_SERIAL = "ZOE00000000000000";
    private static final String EXPIRE_VALUE = "success@4102444800";
    private static final long EXPIRE_EPOCH = 4102444800L;

    private static ClassLoader appClassLoader;
    private static final AtomicBoolean SCANNED = new AtomicBoolean(false);
    private static final Set<String> HOOKED_METHODS = new HashSet<>();

    private VToolsHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        appClassLoader = lpparam.classLoader;
        ClassLoader cl = lpparam.classLoader;

        int hooks = 0;
        hooks += hookActivatedStateModel(cl);
        hooks += hookActivationCodeResponse(cl);
        hooks += hookLoginResponse(cl);
        hooks += hookAccountPointsResponse(cl);
        hooks += hookSharedPreferencesStorage();
        hooks += hookXposedCheck(cl);

        hookApplicationScan(lpparam);
        XposedBridge.log(TAG + ": core hooks installed (" + hooks + ")");
    }

    private static void hookApplicationScan(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!SCANNED.compareAndSet(false, true)) {
                                return;
                            }
                            Application app = (Application) param.thisObject;
                            int extra = scanDexClasses(app, lpparam.appInfo);
                            XposedBridge.log(TAG + ": dex scan hooks installed (" + extra + ")");
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.onCreate scan hook failed: " + t.getMessage());
            if (SCANNED.compareAndSet(false, true)) {
                int extra = scanDexClasses(null, lpparam.appInfo);
                XposedBridge.log(TAG + ": fallback dex scan hooks installed (" + extra + ")");
            }
        }
    }

    private static int hookActivatedStateModel(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(ACTIVATED_STATE, false, cl);
            int count = 0;
            count += hookReturnConstant(model, "getActivated", Boolean.TRUE);
            count += hookReturnConstant(model, "getPermanent", Boolean.TRUE);
            count += hookReturnConstant(model, "getType", "perpetual");
            count += hookReturnConstant(model, "getTypeName", "永久");
            count += hookReturnConstant(model, "getText", "永久有效");
            return count;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ActivatedStateModel hooks failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookActivationCodeResponse(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(ACTIVATION_CODE, false, cl);
            int count = 0;
            count += hookReturnConstant(model, "getPass", Boolean.TRUE);
            count += hookReturnConstant(model, "getExpired", Boolean.FALSE);
            count += hookReturnConstant(model, "getExpiry_time", EXPIRE_EPOCH);
            count += hookReturnConstant(model, "getType", "perpetual");
            count += hookReturnConstant(model, "getCodeStr", FAKE_CODE);
            return count;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ActivationCodeResponse hooks failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookLoginResponse(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(LOGIN_RESPONSE, false, cl);
            int count = 0;
            count += hookReturnConstant(model, "getPass", Boolean.TRUE);
            count += hookReturnConstant(model, "getActivated", Boolean.TRUE);
            return count;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LoginResponse hooks failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookAccountPointsResponse(ClassLoader cl) {
        try {
            Class<?> model = Class.forName(ACCOUNT_POINTS, false, cl);
            return hookReturnConstant(model, "getUnbind", Boolean.FALSE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AccountPointsResponse hooks failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookSharedPreferencesStorage() {
        int count = 0;
        try {
            Class<?> impl = Class.forName("android.app.SharedPreferencesImpl");
            Method getString = XposedHelpers.findMethodExact(
                    impl, "getString", String.class, String.class);
            XposedBridge.hookMethod(getString, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (key == null) {
                        return;
                    }
                    switch (key) {
                        case "pro_key_expire_date":
                            param.setResult(EXPIRE_VALUE);
                            break;
                        case "activate_v2_type":
                            param.setResult("perpetual");
                            break;
                        case "random_id2":
                            param.setResult(FAKE_CODE);
                            break;
                        default:
                            break;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ("user_name".equals(param.args[0]) && param.getResult() == null) {
                        param.setResult("ZoeVIP");
                    }
                }
            });
            count++;

            Method putString = XposedHelpers.findMethodExact(
                    impl, "putString", String.class, String.class);
            XposedBridge.hookMethod(putString, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if ("pro_key_expire_date".equals(param.args[0])) {
                        param.args[1] = EXPIRE_VALUE;
                    } else if ("activate_v2_type".equals(param.args[0]) && param.args[1] == null) {
                        param.args[1] = "perpetual";
                    } else if ("random_id2".equals(param.args[0]) && param.args[1] == null) {
                        param.args[1] = FAKE_CODE;
                    }
                }
            });
            count++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": SharedPreferences hooks failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookXposedCheck(ClassLoader cl) {
        try {
            Class<?> check = Class.forName("com.omarea.xposed.XposedCheck", false, cl);
            int count = 0;
            for (Method method : check.getDeclaredMethods()) {
                Class<?> ret = method.getReturnType();
                if (ret == boolean.class || ret == Boolean.class) {
                    if (hookMethodOnce(method, XC_MethodReplacement.returnConstant(false))) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": XposedCheck hooks skipped: " + t.getMessage());
            return 0;
        }
    }

    private static int scanDexClasses(Application app, ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.sourceDir == null) {
            return 0;
        }
        int count = 0;
        try {
            DexFile dexFile = new DexFile(appInfo.sourceDir);
            Enumeration<String> entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (name == null || name.startsWith("android.") || name.startsWith("java.")) {
                    continue;
                }
                Class<?> cls;
                try {
                    cls = Class.forName(name, false, appClassLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                count += hookPrefsLikeClass(cls);
                count += hookActivationRepository(cls);
                count += hookNetworkResponses(cls);
                count += hookActivationCaches(cls);
                count += hookCloudSync(cls);
                count += hookExchangeGates(cls);
                count += hookDeviceSerial(cls);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dex scan failed: " + t.getMessage());
        }
        if (app != null) {
            seedActivationStorage(app);
        }
        return count;
    }

    private static void seedActivationStorage(Application app) {
        try {
            app.getSharedPreferences("scene", 0)
                    .edit()
                    .putString("pro_key_expire_date", EXPIRE_VALUE)
                    .putString("activate_v2_type", "perpetual")
                    .putString("random_id2", FAKE_CODE)
                    .putString("user_name", "ZoeVIP")
                    .apply();
        } catch (Throwable ignored) {
            // prefs name may differ between versions
        }
        for (String name : new String[]{"scene", "vtools", "omarea", "login", "activate"}) {
            try {
                app.getSharedPreferences(name, 0)
                        .edit()
                        .putString("pro_key_expire_date", EXPIRE_VALUE)
                        .putString("activate_v2_type", "perpetual")
                        .apply();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int hookPrefsLikeClass(Class<?> cls) {
        int count = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            if (method.getReturnType() != String.class) {
                continue;
            }
            String sig = method.toString();
            if (sig.contains("activate_v2_type")) {
                count += hookReturnConstant(method, "perpetual") ? 1 : 0;
            } else if (sig.contains("random_id2")) {
                count += hookReturnConstant(method, FAKE_CODE) ? 1 : 0;
            } else if (sig.contains("user_name")) {
                count += hookReturnConstant(method, "ZoeVIP") ? 1 : 0;
                count += hookNonNullString(method);
            }
        }
        return count;
    }

    private static int hookActivationRepository(Class<?> cls) {
        int count = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (!ACTIVATED_STATE.equals(method.getReturnType().getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 0 && !(params.length == 1 && params[0] == String.class)) {
                continue;
            }
            if (hookMethodOnce(method, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return newActivatedState();
                }
            })) {
                count++;
            }
        }
        return count;
    }

    private static int hookNetworkResponses(Class<?> cls) {
        int count = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            String ret = method.getReturnType().getName();
            if (ACTIVATION_CODE.equals(ret)) {
                if (hookMethodOnce(method, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return newActivationCodeResponse();
                    }
                })) {
                    count++;
                }
            } else if (LOGIN_RESPONSE.equals(ret)) {
                if (hookMethodOnce(method, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return newLoginResponse();
                    }
                })) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int hookActivationCaches(Class<?> cls) {
        String name = cls.getName();
        if (!name.startsWith("a.")) {
            return 0;
        }
        if ("a.lp0".equals(name) || "a.h32".equals(name)) {
            return 0;
        }
        boolean cacheLike = name.endsWith("sn0") || name.endsWith("zv1")
                || (hasStringMethod(cls, "b") && hasStringMethod(cls, "d"));
        if (!cacheLike) {
            return 0;
        }
        int count = 0;
        for (String methodName : new String[]{"toString", "b", "c", "d", "e", "f"}) {
            try {
                Method method = cls.getDeclaredMethod(methodName);
                if (method.getReturnType() == String.class) {
                    count += hookReturnConstant(method, EXPIRE_VALUE) ? 1 : 0;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            Method prefs = cls.getDeclaredMethod("h", android.content.SharedPreferences.class);
            count += hookNonNullString(prefs);
        } catch (NoSuchMethodException ignored) {
        }
        return count;
    }

    private static boolean hasStringMethod(Class<?> cls, String name) {
        try {
            Method method = cls.getDeclaredMethod(name);
            return method.getReturnType() == String.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static int hookCloudSync(Class<?> cls) {
        if (!cls.getName().startsWith("a.")) {
            return 0;
        }
        int matches = 0;
        for (String methodName : new String[]{"A", "B", "G", "v"}) {
            try {
                Method method = cls.getDeclaredMethod(methodName);
                if (method.getReturnType() == boolean.class && method.getParameterTypes().length == 0) {
                    matches++;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (matches < 3) {
            return 0;
        }
        int count = 0;
        for (String methodName : new String[]{"A", "B", "G", "v"}) {
            try {
                Method method = cls.getDeclaredMethod(methodName);
                if (method.getReturnType() == boolean.class) {
                    count += hookReturnConstant(method, Boolean.TRUE) ? 1 : 0;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return count;
    }

    private static int hookExchangeGates(Class<?> cls) {
        int count = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }
            Class<?> ret = method.getReturnType();
            if (ret != boolean.class && ret != Boolean.class) {
                continue;
            }
            String sig = method.toString();
            if (!sig.contains("exchange") && !sig.contains("Exchange")) {
                continue;
            }
            count += hookReturnConstant(method, Boolean.TRUE) ? 1 : 0;
        }
        return count;
    }

    private static int hookDeviceSerial(Class<?> cls) {
        int count = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != String.class) {
                continue;
            }
            String sig = method.toString();
            if (!sig.contains("serial") && !sig.contains("Serial") && !sig.contains("device")) {
                continue;
            }
            count += hookReturnConstant(method, FAKE_SERIAL) ? 1 : 0;
        }
        return count;
    }

    private static Object newActivatedState() {
        try {
            Class<?> model = Class.forName(ACTIVATED_STATE, false, appClassLoader);
            Object instance = model.getDeclaredConstructor().newInstance();
            setIfPresent(model, instance, "setActivated", boolean.class, true);
            setIfPresent(model, instance, "setPermanent", boolean.class, true);
            setIfPresent(model, instance, "setType", String.class, "perpetual");
            setIfPresent(model, instance, "setTypeName", String.class, "永久");
            setIfPresent(model, instance, "setText", String.class, "永久有效");
            return instance;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": newActivatedState failed: " + t.getMessage());
            return null;
        }
    }

    private static Object newActivationCodeResponse() {
        try {
            Class<?> model = Class.forName(ACTIVATION_CODE, false, appClassLoader);
            Object instance = model.getDeclaredConstructor().newInstance();
            setIfPresent(model, instance, "setPass", boolean.class, true);
            setIfPresent(model, instance, "setExpired", boolean.class, false);
            setIfPresent(model, instance, "setExpiry_time", long.class, EXPIRE_EPOCH);
            setIfPresent(model, instance, "setType", String.class, "perpetual");
            setIfPresent(model, instance, "setCode", String.class, FAKE_SERIAL);
            setIfPresent(model, instance, "setSign", String.class, "ZOE");
            setIfPresent(model, instance, "setAccount", String.class, "ZoeVIP");
            return instance;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": newActivationCodeResponse failed: " + t.getMessage());
            return null;
        }
    }

    private static Object newLoginResponse() {
        try {
            Class<?> model = Class.forName(LOGIN_RESPONSE, false, appClassLoader);
            Object instance = model.getDeclaredConstructor().newInstance();
            setIfPresent(model, instance, "setPass", boolean.class, true);
            setIfPresent(model, instance, "setActivated", boolean.class, true);
            return instance;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": newLoginResponse failed: " + t.getMessage());
            return null;
        }
    }

    private static void setIfPresent(Class<?> cls, Object target, String name, Class<?> type, Object value) {
        try {
            Method setter = cls.getDeclaredMethod(name, type);
            setter.setAccessible(true);
            setter.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static int hookReturnConstant(Class<?> cls, String methodName, Object value) {
        try {
            Method method = cls.getDeclaredMethod(methodName);
            return hookReturnConstant(method, value) ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean hookReturnConstant(Method method, Object value) {
        return hookMethodOnce(method, XC_MethodReplacement.returnConstant(value));
    }

    private static int hookNonNullString(Method method) {
        if (!hookMethodOnce(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() == null) {
                    param.setResult("ZoeVIP");
                }
            }
        })) {
            return 0;
        }
        return 1;
    }

    private static boolean hookMethodOnce(Method method, XC_MethodHook callback) {
        String id = method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, callback);
            return true;
        } catch (Throwable t) {
            HOOKED_METHODS.remove(id);
            return false;
        }
    }
}
