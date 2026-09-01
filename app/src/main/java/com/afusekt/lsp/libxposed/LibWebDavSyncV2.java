package com.afusekt.lsp.libxposed;

import android.content.Context;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;
import com.afusekt.lsp.hook.WebDavSyncHooks;
import com.afusekt.lsp.sync.WebDavLibrarySync;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * Afusekt 3.2.x WebDAV sync (libxposed).
 *
 * <p>3.2.x has multiple sync entry points:</p>
 * <ul>
 *   <li>Resource-library refresh: {@code t88.r(VideoSource)} official permission check</li>
 *   <li>Top-right sync button: {@code kn4} d=1 bulk upload to official cloud</li>
 *   <li>Pull/download: {@code ej5} d=0x1a and {@code t88.p(list, false)}</li>
 *   <li>Background service: {@code VideoDataSyncService.a/o}</li>
 * </ul>
 */
public final class LibWebDavSyncV2 {

    private static final String TAG = ZoeIds.TAG + ":WebDavV2";

    private static final String SYNC_SERVICE =
            "com.attempt.afusekt.service.VideoDataSyncService";
    private static final String VIDEO_SOURCE =
            "com.attempt.afusekt.liveData.VideoSource";

    /** v3.2.5 resource-library fragment (obfuscated). */
    private static final String[] LIBRARY_FRAGMENT = {"t88"};

    /** v3.2.5 sync coroutine (obfuscated). */
    private static final String[] SYNC_COROUTINE = {"kn4"};

    /** v3.2.5 pull/download coroutine (obfuscated). */
    private static final String[] PULL_COROUTINE = {"ej5"};
    private static final int EJ5_PULL_VARIANT = 0x1a;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private LibWebDavSyncV2() {
    }

    public static void onPackageReady(ZoeModule module, ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            android.util.Log.e(TAG, "onPackageReady: already installed (skipped)");
            return;
        }
        android.util.Log.e(TAG, "onPackageReady: installing...");
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int hooked = 0;
        try {
            Class<?> service = Class.forName(SYNC_SERVICE, false, cl);
            Class<?> source = Class.forName(VIDEO_SOURCE, false, cl);
            hooked += hookServiceEntry(module, service, source, "d", false, cl, mode);
            hooked += hookServiceEntry(module, service, source, "a", true, cl, mode);
            hooked += hookServiceEntry(module, service, source, "o", true, cl, mode);
            hooked += hookLibraryPermissionCheck(module, cl, source, mode);
            hooked += hookBulkUploadCoroutine(module, cl, mode);
            hooked += hookPullDownload(module, cl, mode);
            android.util.Log.e(TAG, "WebDAV sync hooks installed (" + hooked + ")");
            if (hooked == 0) {
                INSTALLED.set(false);
            }
        } catch (Throwable t) {
            INSTALLED.set(false);
            android.util.Log.e(TAG, "install FAILED: " + t, t);
        }
    }

    private static int hookServiceEntry(
            ZoeModule module,
            Class<?> service,
            Class<?> source,
            String name,
            boolean instance,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findTwoArgMethod(service, name, source);
            if (method == null) {
                android.util.Log.e(TAG, "method " + name + "(VideoSource, *) not found");
                return 0;
            }
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object videoSource = chain.getArg(0);
                Context context = instance
                        ? (Context) chain.getThisObject()
                        : resolveAppContext(cl);
                boolean cfg = context != null && shouldRedirect(context);
                android.util.Log.e(TAG, ">>> " + name + "() fired, source="
                        + callSourceName(videoSource) + " cfg=" + cfg);
                if (context == null || !cfg) {
                    return chain.proceed();
                }
                if ("d".equals(name)) {
                    if (videoSource != null) {
                        android.util.Log.e(TAG, ">>> d() -> WebDAV upload");
                        syncToWebDav(context, cl, videoSource);
                        return null;
                    }
                    return chain.proceed();
                }
                if ("a".equals(name)) {
                    android.util.Log.e(TAG, ">>> a() -> skip official check");
                    return Boolean.TRUE;
                }
                if (videoSource == null) {
                    return chain.proceed();
                }
                android.util.Log.e(TAG, ">>> o() -> WebDAV upload");
                syncToWebDav(context, cl, videoSource);
                return kotlinUnit();
            });
            android.util.Log.e(TAG, "hooked VideoDataSyncService." + name + "() OK");
            return 1;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "hook " + name + "() FAILED: " + t);
            return 0;
        }
    }

    /** Per-library refresh permission check in resource-library fragment. */
    private static int hookLibraryPermissionCheck(
            ZoeModule module,
            ClassLoader cl,
            Class<?> source,
            XposedInterface.ExceptionMode mode
    ) {
        for (String name : LIBRARY_FRAGMENT) {
            try {
                Class<?> fragment = Class.forName(name, false, cl);
                Method method = findTwoArgMethod(fragment, "r", source);
                if (method == null) {
                    continue;
                }
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object videoSource = chain.getArg(0);
                    Context context = fragmentContext(chain.getThisObject());
                    boolean cfg = context != null && shouldRedirect(context);
                    android.util.Log.e(TAG, ">>> fragment.r() fired, source="
                            + callSourceName(videoSource) + " cfg=" + cfg);
                    if (videoSource == null || context == null || !cfg) {
                        return chain.proceed();
                    }
                    android.util.Log.e(TAG, ">>> fragment.r() -> WebDAV upload");
                    syncToWebDav(context, cl, videoSource);
                    return Boolean.TRUE;
                });
                android.util.Log.e(TAG, "hooked " + name + ".r() OK");
                return 1;
            } catch (Throwable t) {
                android.util.Log.e(TAG, "hook " + name + ".r() FAILED: " + t);
            }
        }
        android.util.Log.e(TAG, "library fragment r() hook not found");
        return 0;
    }

    /** Top-right sync button: kn4 d=1 bulk upload to official cloud. */
    private static int hookBulkUploadCoroutine(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        for (String name : SYNC_COROUTINE) {
            try {
                Class<?> kn4 = Class.forName(name, false, cl);
                Method invokeSuspend = kn4.getDeclaredMethod("invokeSuspend", Object.class);
                invokeSuspend.setAccessible(true);
                module.hook(invokeSuspend).setExceptionMode(mode).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (readIntField(self, "d") != 1) {
                        return chain.proceed();
                    }
                    Context context = (Context) readField(self, "p");
                    if (context == null || !shouldRedirect(context)) {
                        return chain.proceed();
                    }
                    Object sources = readField(self, "q");
                    if (!(sources instanceof List<?> list) || list.isEmpty()) {
                        return chain.proceed();
                    }
                    Object dialog = readField(self, "n");
                    android.util.Log.e(TAG, ">>> kn4 bulk upload -> WebDAV (" + list.size() + ")");
                    WebDavLibrarySync.upload(context, list, dialog, cl);
                    return kotlinUnit();
                });
                android.util.Log.e(TAG, "hooked " + name + ".invokeSuspend(d=1) OK");
                return 1;
            } catch (Throwable t) {
                android.util.Log.e(TAG, "hook " + name + " FAILED: " + t);
            }
        }
        return 0;
    }

    /** Bottom-sheet 获取 / dialog pull: t88.p(list, false) and ej5(d=0x1a). */
    private static int hookPullDownload(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int hooked = hookFragmentPull(module, cl, mode);
        hooked += hookEj5Pull(module, cl, mode);
        return hooked;
    }

    private static int hookFragmentPull(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        for (String name : LIBRARY_FRAGMENT) {
            try {
                Class<?> fragment = Class.forName(name, false, cl);
                Method method = findPullMethod(fragment);
                if (method == null) {
                    continue;
                }
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object uploadFlag = chain.getArg(1);
                    boolean upload = uploadFlag instanceof Boolean && (Boolean) uploadFlag;
                    if (upload) {
                        return chain.proceed();
                    }
                    Context context = fragmentContext(chain.getThisObject());
                    if (context == null || !shouldRedirect(context)) {
                        return chain.proceed();
                    }
                    android.util.Log.e(TAG, ">>> fragment.p(false) -> WebDAV download");
                    WebDavLibrarySync.download(chain.getThisObject(), cl);
                    return Boolean.TRUE;
                });
                android.util.Log.e(TAG, "hooked " + name + ".p(false) OK");
                return 1;
            } catch (Throwable t) {
                android.util.Log.e(TAG, "hook " + name + ".p(false) FAILED: " + t);
            }
        }
        return 0;
    }

    private static int hookEj5Pull(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        for (String name : PULL_COROUTINE) {
            try {
                Class<?> ej5 = Class.forName(name, false, cl);
                Method invokeSuspend = ej5.getDeclaredMethod("invokeSuspend", Object.class);
                invokeSuspend.setAccessible(true);
                module.hook(invokeSuspend).setExceptionMode(mode).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (readIntField(self, "d") != EJ5_PULL_VARIANT) {
                        return chain.proceed();
                    }
                    Object fragment = readField(self, "p");
                    Context context = fragmentContext(fragment);
                    if (context == null || !shouldRedirect(context)) {
                        return chain.proceed();
                    }
                    android.util.Log.e(TAG, ">>> ej5 pull -> WebDAV download");
                    WebDavLibrarySync.download(fragment, cl);
                    return kotlinUnit();
                });
                android.util.Log.e(TAG, "hooked " + name + ".invokeSuspend(pull) OK");
                return 1;
            } catch (Throwable t) {
                android.util.Log.e(TAG, "hook " + name + " pull FAILED: " + t);
            }
        }
        return 0;
    }

    private static Method findPullMethod(Class<?> fragment) {
        for (Method method : fragment.getDeclaredMethods()) {
            if (!"p".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) {
                continue;
            }
            if (params[1] != boolean.class && params[1] != Boolean.TYPE) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static boolean shouldRedirect(Context context) {
        WebDavSyncConfig.refreshForSync(context);
        boolean configured = WebDavSyncConfig.resolveForSync(context);
        WebDavSyncHooks.WEBDAV_CONFIGURED.set(configured);
        if (!configured) {
            WebDavSyncConfig.logConfigSnapshot(context);
        }
        return configured;
    }

    private static void syncToWebDav(Context context, ClassLoader cl, Object videoSource) {
        WebDavLibrarySync.upload(context, Collections.singletonList(videoSource), null, cl);
    }

    private static String callSourceName(Object source) {
        try {
            Object v = source.getClass().getMethod("getName").invoke(source);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Method findTwoArgMethod(Class<?> cls, String name, Class<?> firstParam) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != firstParam) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static Object kotlinUnit() {
        try {
            return Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context fragmentContext(Object fragment) {
        if (fragment == null) {
            return null;
        }
        try {
            Object ctx = fragment.getClass().getMethod("requireContext").invoke(fragment);
            if (ctx instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object ctx = fragment.getClass().getMethod("getContext").invoke(fragment);
            if (ctx instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context resolveAppContext(ClassLoader cl) {
        try {
            Class<?> appClass = Class.forName("com.attempt.afusekt.MyApplication", false, cl);
            Object app = appClass.getMethod("getInstance").invoke(null);
            if (app instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object activityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            Object app = activityThread.getClass().getMethod("getApplication")
                    .invoke(activityThread);
            if (app instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readIntField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return -1;
        }
    }

}
