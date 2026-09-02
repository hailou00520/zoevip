package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.os.Build;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import org.json.JSONTokener;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hills ({@code com.mountains.hills}) Pro unlock — Zot-equivalent path only.
 * Player JSON hooks + single libapp copyWith patch (no IAP / prefs extras).
 */
public final class LibHillsHooks {

    private static final String TAG = ZoeIds.TAG + ":Hills";
    private static final String PLAYER_CONFIG = "com.mountains.player.models.PlayerConfig";
    private static final String PLAYER_DATA_BINDER = "com.mountains.player.models.PlayerDataBinder";
    private static final String[] PLAYER_PLUGIN_CANDIDATES = {"ei.d", "yh.d"};

    private static final Set<Integer> HOOKED_LOADERS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean CLASS_LOADER_MONITOR = new AtomicBoolean(false);
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);

    private LibHillsHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        LibHillsNative.installLoadMonitor(module);
        installClassLoaderMonitor(module);
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        LibHillsNative.installLoadMonitor(module);
        LibHillsNative.applyWhenReady(module);
        LibHillsNative.scheduleRetry(module);
        tryInstall(module, param.getClassLoader(), "package-ready");
        hookApplicationOnCreate(module);
        scheduleRetry(module, param.getClassLoader());
    }

    private static void scheduleRetry(ZoeModule module, ClassLoader cl) {
        for (long delay : new long[]{500L, 2000L, 5000L, 12000L}) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                tryInstall(module, cl, "retry@" + delay);
            }, "ZoeVIP-hills-" + delay);
            t.setDaemon(true);
            t.start();
        }
    }

    private static void hookApplicationOnCreate(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Application app
                                && ZoeIds.HILLS_PACKAGE.equals(app.getPackageName())) {
                            LibHillsNative.applyWhenReady(module);
                            tryInstall(module, app.getClassLoader(), "application");
                            showToastOnce(app);
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, TAG, "Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void showToastOnce(Application app) {
        if (!TOAST_SHOWN.compareAndSet(false, true)) {
            return;
        }
        try {
            android.widget.Toast.makeText(
                    app, "ZoeVIP 已注入 Hills Pro", android.widget.Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable ignored) {
        }
    }

    private static void installClassLoaderMonitor(ZoeModule module) {
        if (!CLASS_LOADER_MONITOR.compareAndSet(false, true)) {
            return;
        }
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            module.hook(loadClass)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object nameObj = chain.getArg(0);
                        String name = nameObj instanceof String ? (String) nameObj : null;
                        if (name != null && name.startsWith("com.mountains.player")) {
                            tryInstall(module, (ClassLoader) chain.getThisObject(),
                                    "loadClass:" + name);
                        }
                        return result;
                    });
            module.log(4, TAG, "ClassLoader.loadClass monitor installed");
        } catch (Throwable t) {
            CLASS_LOADER_MONITOR.set(false);
            module.log(5, TAG, "ClassLoader monitor failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        int count = installPlayerHooks(module, cl);
        if (count > 0) {
            module.log(4, TAG, "hooks total=" + count + " (" + source + ")");
        }
    }

    private static int installPlayerHooks(ZoeModule module, ClassLoader cl) {
        try {
            Class.forName(PLAYER_CONFIG, false, cl);
            Class.forName(PLAYER_DATA_BINDER, false, cl);
        } catch (Throwable t) {
            return 0;
        }
        int loaderKey = System.identityHashCode(cl);
        if (!HOOKED_LOADERS.add(loaderKey)) {
            return 0;
        }
        if (!"arm64-v8a".equals(Build.SUPPORTED_ABIS[0])) {
            module.log(4, TAG, "native patch skipped outside arm64");
        }
        int count = 0;
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Class<?> plugin = resolvePlayerPlugin(cl);
            module.log(4, TAG, "player plugin="
                    + (plugin != null ? plugin.getName() : "missing")
                    + " cl=" + Integer.toHexString(loaderKey));
            count += hookPlayerConfig(module, cl, mode);
            count += hookPlayerDataBinder(module, cl, mode);
            count += hookPlayerLauncher(module, plugin, mode);
            module.log(4, TAG, "player hooks=" + count);
        } catch (Throwable t) {
            HOOKED_LOADERS.remove(loaderKey);
            module.log(5, TAG, "player hooks failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookPlayerConfig(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> cls = Class.forName(PLAYER_CONFIG, false, cl);
        int count = 0;
        for (String name : new String[]{"isPro", "getIsPro"}) {
            Method m = findNoArgBoolean(cls, name);
            if (m == null) {
                continue;
            }
            module.hook(m).setExceptionMode(mode).intercept(chain -> Boolean.TRUE);
            count++;
            module.log(4, TAG, name + "() hooked");
        }
        return count;
    }

    private static int hookPlayerDataBinder(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> cls = Class.forName(PLAYER_DATA_BINDER, false, cl);
        int count = 0;
        for (String name : new String[]{"getPlayerConfig", "component1"}) {
            Method m = findNoArgString(cls, name);
            if (m == null) {
                continue;
            }
            module.hook(m).setExceptionMode(mode).intercept(chain -> {
                Object raw = chain.proceed();
                return injectProJson(raw instanceof String ? (String) raw : null);
            });
            count++;
            module.log(4, TAG, name + "() hooked");
        }
        Method copy = findMethod(cls, "copy", String.class, String.class, String.class);
        if (copy != null) {
            module.hook(copy).setExceptionMode(mode).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length > 0) {
                    args[0] = injectProJson(args[0] instanceof String ? (String) args[0] : null);
                }
                return chain.proceed(args);
            });
            count++;
            module.log(4, TAG, "copy() hooked");
        }
        Constructor<?> ctor = findStringCtor(cls, 3);
        if (ctor != null) {
            ctor.setAccessible(true);
            module.hook(ctor).setExceptionMode(mode).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length > 0) {
                    args[0] = injectProJson(args[0] instanceof String ? (String) args[0] : null);
                }
                return chain.proceed(args);
            });
            count++;
            module.log(4, TAG, "<init> hooked");
        }
        return count;
    }

    private static int hookPlayerLauncher(
            ZoeModule module, Class<?> plugin, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        if (plugin == null) {
            return 0;
        }
        Method launch = findLaunchMethod(plugin);
        if (launch == null) {
            return 0;
        }
        launch.setAccessible(true);
        module.hook(launch).setExceptionMode(mode).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length > 0) {
                args[0] = injectProJson(args[0] instanceof String ? (String) args[0] : null);
            }
            return chain.proceed(args);
        });
        module.log(4, TAG, plugin.getName() + "." + launch.getName() + "() hooked");
        return 1;
    }

    /** Same logic as Zot {@code h10.M}. */
    static String injectProJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "{\"isPro\":true}";
        }
        try {
            JSONTokener tokener = new JSONTokener(raw);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject obj) || tokener.nextClean() != 0) {
                return raw;
            }
            String key = "isPro";
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if ("isPro".equalsIgnoreCase(k)) {
                    key = k;
                    break;
                }
            }
            if (!obj.optBoolean(key, false)) {
                obj.put(key, true);
                return obj.toString();
            }
        } catch (Throwable ignored) {
        }
        return raw;
    }

    private static Class<?> resolvePlayerPlugin(ClassLoader cl) {
        for (String name : PLAYER_PLUGIN_CANDIDATES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                if (findLaunchMethod(cls) != null) {
                    return cls;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findLaunchMethod(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4
                    && p[0] == String.class
                    && p[1] == int.class
                    && p[2] == String.class
                    && p[3] == String.class) {
                return m;
            }
        }
        return null;
    }

    private static Method findNoArgBoolean(Class<?> cls, String name) {
        Method m = findMethod(cls, name);
        if (m == null || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
            return null;
        }
        return m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class ? m : null;
    }

    private static Method findNoArgString(Class<?> cls, String name) {
        Method m = findMethod(cls, name);
        if (m == null || Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) {
            return null;
        }
        return m.getReturnType() == String.class ? m : null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Constructor<?> findStringCtor(Class<?> cls, int stringParams) {
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != stringParams) {
                continue;
            }
            boolean ok = true;
            for (Class<?> t : p) {
                if (t != String.class) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return c;
            }
        }
        return null;
    }
}
