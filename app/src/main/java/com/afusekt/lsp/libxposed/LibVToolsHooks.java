package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.content.SharedPreferences;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Scene / VTools PRO unlock (libxposed).
 * Strategy matches Zot "modern" path: hook license getters only, never touch
 * user_name / random_id2 (E0 writes failed codes into random_id2 and breaks login).
 */
public final class LibVToolsHooks {

    private static final String ACTIVATED_STATE = "com.omarea.model.ActivatedStateModel";
    private static final String ACTIVATION_CODE = "com.omarea.model.ActivationCodeResponse";

    private static final String REPO = "a.mp0";
    private static final String PREFS = "a.h11";
    private static final String DISPLAY_SERIAL = "a.qo0";

    private static final String FAKE_SERIAL = "ZOE00000000000000";
    private static final String EXPIRE_VALUE = "success@4102444800";
    private static final long EXPIRE_EPOCH = 4102444800L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LibVToolsHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        hookAttachBaseContext(module);
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        tryInstall(module, param.getClassLoader(), "package-ready");
        hookApplicationOnCreate(module);
    }

    private static void hookAttachBaseContext(ZoeModule module) {
        try {
            Method attach = android.content.ContextWrapper.class.getDeclaredMethod(
                    "attachBaseContext", android.content.Context.class);
            module.hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object ctx = chain.getThisObject();
                        if (ctx instanceof Application application
                                && ZoeIds.VTOOLS_PACKAGE.equals(application.getPackageName())) {
                            tryInstall(module, application.getClassLoader(), "attach-base");
                            seedLicensePrefsIfMissing(application);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools attachBaseContext hook failed: " + t.getMessage());
        }
    }

    private static void hookApplicationOnCreate(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getThisObject();
                        if (app instanceof Application application
                                && ZoeIds.VTOOLS_PACKAGE.equals(application.getPackageName())) {
                            seedLicensePrefsIfMissing(application);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(ACTIVATED_STATE, false, cl);
            Class.forName(REPO, false, cl);
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, ZoeIds.TAG, "VTools hooks installing (" + source + ")");
        try {
            installHooks(module, cl);
            module.log(4, ZoeIds.TAG, "VTools hooks installed");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, ZoeIds.TAG, "VTools hooks failed: " + t.getMessage(), t);
        }
    }

    private static void installHooks(ZoeModule module, ClassLoader cl) throws Throwable {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = 0;
        count += hookActivatedStateModel(module, cl, mode);
        count += hookActivationCodeGetters(module, cl, mode);
        count += hookLicenseTypePrefs(module, cl, mode);
        count += hookDisplaySerial(module, cl, mode);
        count += hookActivationToast(module, mode);
        count += hookXposedCheck(module, cl, mode);
        module.log(4, ZoeIds.TAG, "VTools hook count: " + count);
    }

    private static int hookActivatedStateModel(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        count += hookReturn(module, cl, ACTIVATED_STATE, "getActivated", true, mode);
        count += hookReturn(module, cl, ACTIVATED_STATE, "getPermanent", true, mode);
        count += hookReturn(module, cl, ACTIVATED_STATE, "getType", "perpetual", mode);
        count += hookReturn(module, cl, ACTIVATED_STATE, "getTypeName", "永久", mode);
        count += hookReturn(module, cl, ACTIVATED_STATE, "getText", "永久有效", mode);
        return count;
    }

    private static int hookActivationCodeGetters(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        count += hookReturn(module, cl, ACTIVATION_CODE, "getPass", true, mode);
        count += hookReturn(module, cl, ACTIVATION_CODE, "getExpired", false, mode);
        count += hookReturn(module, cl, ACTIVATION_CODE, "getType", "perpetual", mode);
        count += hookReturn(module, cl, ACTIVATION_CODE, "getExpiry_time", EXPIRE_EPOCH, mode);
        return count;
    }

    /** Only license type reads; never random_id2 / user_name. */
    private static int hookLicenseTypePrefs(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> prefs = Class.forName(PREFS, false, cl);
            count += hookReturn(module, prefs, "b", "perpetual", mode);
            Method b0 = findMethod(prefs, "B0", 2);
            if (b0 != null) {
                count += installLicenseReadHook(module, b0, mode);
            }
            Method e0 = findMethod(prefs, "E0", 1);
            if (e0 != null && e0.getParameterTypes()[0] == String.class) {
                count += installRandomIdGuardHook(module, e0, mode);
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools license prefs hooks failed: " + t.getMessage());
        }
        return count;
    }

    private static int installLicenseReadHook(
            ZoeModule module,
            Method method,
            XposedInterface.ExceptionMode mode
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                String key = (String) chain.getArg(0);
                if ("activate_v2_type".equals(key)) {
                    return "perpetual";
                }
                if ("pro_key_expire_date".equals(key)) {
                    return EXPIRE_VALUE;
                }
                return chain.proceed();
            });
            module.log(4, ZoeIds.TAG, "VTools hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, ZoeIds.TAG, "VTools hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    /** Block failed activation from overwriting random_id2 (account/device binding). */
    private static int installRandomIdGuardHook(
            ZoeModule module,
            Method method,
            XposedInterface.ExceptionMode mode
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                String value = (String) chain.getArg(0);
                if (value != null && (value.contains("|ZOE") || value.contains("|ZOT"))) {
                    module.log(4, ZoeIds.TAG, "VTools blocked random_id2 overwrite: " + value);
                    return null;
                }
                return chain.proceed();
            });
            module.log(4, ZoeIds.TAG, "VTools hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, ZoeIds.TAG, "VTools hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static int hookDisplaySerial(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> display = Class.forName(DISPLAY_SERIAL, false, cl);
            Method a = findMethod(display, "a", 0);
            if (a != null && a.getReturnType() == String.class) {
                count += hookReturn(module, a, FAKE_SERIAL, mode);
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools display serial hook failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookActivationToast(ZoeModule module, XposedInterface.ExceptionMode mode) {
        try {
            Method makeText = android.widget.Toast.class.getMethod(
                    "makeText", android.content.Context.class, CharSequence.class, int.class);
            String id = hookId(makeText);
            if (!HOOKED.add(id)) {
                return 0;
            }
            module.hook(makeText).setExceptionMode(mode).intercept(chain -> {
                CharSequence text = (CharSequence) chain.getArg(1);
                if (text != null) {
                    String msg = text.toString();
                    if (msg.contains("激活失败") || msg.contains("激活失敗")) {
                        return chain.proceed(new Object[]{chain.getArg(0), "", chain.getArg(2)});
                    }
                }
                return chain.proceed();
            });
            module.log(4, ZoeIds.TAG, "VTools hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools toast hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookXposedCheck(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> check = Class.forName("com.omarea.xposed.XposedCheck", false, cl);
            Method running = findMethod(check, "xposedIsRunning", 0);
            if (running != null && running.getReturnType() == boolean.class) {
                count += hookReturn(module, running, false, mode);
            }
            for (Method method : check.getDeclaredMethods()) {
                if (method.getReturnType() == boolean.class && method.getParameterTypes().length == 0) {
                    count += hookReturn(module, method, false, mode);
                }
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VTools XposedCheck hooks skipped: " + t.getMessage());
        }
        return count;
    }

    private static void seedLicensePrefsIfMissing(Application app) {
        for (String name : new String[]{"scene", "vtools", "omarea"}) {
            try {
                SharedPreferences prefs = app.getSharedPreferences(name, 0);
                SharedPreferences.Editor edit = prefs.edit();
                boolean changed = false;
                if (!prefs.contains("activate_v2_type")) {
                    edit.putString("activate_v2_type", "perpetual");
                    changed = true;
                }
                if (!prefs.contains("pro_key_expire_date")) {
                    edit.putString("pro_key_expire_date", EXPIRE_VALUE);
                    changed = true;
                }
                if (changed) {
                    edit.apply();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static int hookReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            return hookReturn(module, cls, methodName, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, methodName, 0);
        return method != null ? hookReturn(module, method, value, mode) : 0;
    }

    private static int hookReturn(
            ZoeModule module,
            Method method,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        return installConstantHook(module, method, mode, value);
    }

    private static int installConstantHook(
            ZoeModule module,
            Method method,
            XposedInterface.ExceptionMode mode,
            Object value
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) {
                boolean bool = value instanceof Boolean ? (Boolean) value : Boolean.TRUE.equals(value);
                module.hook(method).setExceptionMode(mode).intercept(chain -> bool);
            } else if (ret == long.class && value instanceof Number) {
                long number = ((Number) value).longValue();
                module.hook(method).setExceptionMode(mode).intercept(chain -> number);
            } else {
                module.hook(method).setExceptionMode(mode).intercept(chain -> value);
            }
            module.log(4, ZoeIds.TAG, "VTools hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, ZoeIds.TAG, "VTools hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static String hookId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        for (Method method : cls.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }
}
