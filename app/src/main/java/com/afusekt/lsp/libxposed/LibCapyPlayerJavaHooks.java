package com.afusekt.lsp.libxposed;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;
import com.afusekt.lsp.hook.CapyPlayerEntitlementSupport;
import com.afusekt.lsp.hook.CapyPlayerSubscriptionPatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;

/**
 * CapyPlayer lifetime Pro unlock via libxposed (LSPosed API 102+).
 * Replaces legacy {@code CapyPlayerHooks} / XposedBridge path.
 */
public final class LibCapyPlayerJavaHooks {

    private static final String TAG = ZoeIds.TAG + ":CapyJava";
    private static final String PRO_PRODUCT = CapyPlayerEntitlementSupport.PRO_PRODUCT;
    private static final String SUBSCRIPTION_STATE_JSON =
            CapyPlayerEntitlementSupport.SUBSCRIPTION_STATE_JSON;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean MDK_TRIM_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean TRIM_CALLBACK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean BACKUP_IO_HOOKED = new AtomicBoolean(false);
    /** Video disk cache mmap'd into RSS (~2GB) makes createBackupBytes OOM. */
    private static final long MDK_CACHE_TRIM_BYTES = 512L * 1024L * 1024L;
    private static final long IMAGE_CACHE_TRIM_BYTES = 16L * 1024L * 1024L;
    private static final AtomicLong LAST_MEMORY_RELEASE_ELAPSED = new AtomicLong(0L);
    private static volatile WeakReference<Object> flutterJniRef = new WeakReference<>(null);
    private static final AtomicBoolean DATASTORE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean DATASTORE_GAVE_UP = new AtomicBoolean(false);
    private static final AtomicBoolean JAVA_STORAGE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean JAVA_STORAGE_GAVE_UP = new AtomicBoolean(false);
    private static final AtomicBoolean BILLING_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean FLUTTER_JNI_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean OKHTTP_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean BILLING_QUERY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean PB_GUARD_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean POLLING_STARTED = new AtomicBoolean(false);

    private static ClassLoader appClassLoader;
    private static Context appContext;
    private static ZoeModule activeModule;

    private static final Map<FileOutputStream, String> STREAM_PATHS = new WeakHashMap<>();
    private static final Set<String> GUARDED_PATHS = ConcurrentHashMap.newKeySet();

    private LibCapyPlayerJavaHooks() {
    }

    private static final AtomicBoolean SP_BACKEND_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean SP_WRITE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean I95_READ_HOOKED = new AtomicBoolean(false);

    public static void install(ZoeModule module, ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            refreshContextAndSeed(module);
            return;
        }
        activeModule = module;
        appClassLoader = cl;
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        module.log(4, TAG, "installing Java hooks");
        installApplicationSeed(module, cl, mode);
        installSharedPreferences(module, mode);
        installSubscriptionDnsBlock(module, mode);
        // ClassLoader.loadClass global hook removed: it stalled Flutter first-frame (white screen).
        maybeHookFlutterJni(module, cl, mode);
        installBackupMemoryGuard(module, mode);
        // Only tracks FlutterSharedPreferences.preferences_pb paths (not every write).
        installPbWriteGuard(module, mode);
        installMainActivityHooks(module, cl, mode);
        LibCapyPlayerPurchaseHooks.install(module, cl);
        installDeferredHooks(module, cl);
        // BillingClient PurchasesResponseListener absent in this APK (Pigeon IAP only).
        startPolling(module, cl);
        startMemoryWatchdog();
        refreshContextAndSeed(module);
        LibCapyPlayerNative.applyWhenReady(module);
        LibCapyPlayerNative.startPatchRetry(module);
        module.log(4, TAG, "Java hooks installed");
    }

    private static void refreshContextAndSeed(ZoeModule module) {
        Context context = resolveAppContext();
        if (context != null) {
            appContext = context;
            CapyPlayerEntitlementSupport.seedProSubscription(context);
            // Avoid forceReseed here — rebuilds Flutter widgets and refills ImageCache (~2GB RSS).
            scheduleEntitlementPush(context, 1);
            String msg = "seeded entitlement via " + context.getClass().getSimpleName();
            module.log(4, TAG, msg);
            Log.i(ZoeIds.TAG, msg);
        }
    }

    private static Context resolveAppContext() {
        if (appContext != null) {
            return appContext;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof Context context) {
                appContext = context;
                return context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void onLibAppLoaded(ZoeModule module, ClassLoader cl) {
        if (activeModule == null) {
            activeModule = module;
        }
        if (appClassLoader == null) {
            appClassLoader = cl;
        }
        installDeferredHooks(module, cl);
        LibCapyPlayerPurchaseHooks.install(module, cl);
    }

    private static void installSubscriptionDnsBlock(
            ZoeModule module, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method getAll = InetAddress.class.getDeclaredMethod("getAllByName", String.class);
            hookMethod(module, getAll, mode, chain -> {
                String host = stringArg(chain, 0);
                if (host != null && (host.contains("api-capyplayer.feifeiduck.cn")
                        || host.contains("blocked.subscription.invalid"))) {
                    log(4, "blocked subscription DNS " + host);
                    throw new java.net.UnknownHostException(host);
                }
                return chain.proceed();
            });
            Method getByName = InetAddress.class.getDeclaredMethod("getByName", String.class);
            hookMethod(module, getByName, mode, chain -> {
                String host = stringArg(chain, 0);
                if (host != null && (host.contains("api-capyplayer.feifeiduck.cn")
                        || host.contains("blocked.subscription.invalid"))) {
                    log(4, "blocked subscription DNS(getByName) " + host);
                    throw new java.net.UnknownHostException(host);
                }
                return chain.proceed();
            });
            module.log(4, TAG, "subscription DNS block hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "subscription DNS block skipped: " + t.getMessage());
        }
    }

    private static void installApplicationSeed(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            module.hook(attach).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                appContext = (Context) chain.getArg(0);
                onAppContextReady(module, cl, appContext);
                return result;
            });
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Application application) {
                    appContext = application;
                    onAppContextReady(module, cl, application);
                }
                return result;
            });
            module.log(4, TAG, "Application attach/onCreate hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "Application seed skipped: " + t.getMessage());
        }
    }

    private static void onAppContextReady(ZoeModule module, ClassLoader cl, Context context) {
        if (context == null) {
            return;
        }
        CapyPlayerEntitlementSupport.seedProSubscription(context);
        scheduleEntitlementPush(context, 1);
        LibCapyPlayerNative.applyWhenReady(module);
        scheduleMdkCacheTrim(context);
        registerTrimMemoryCallback(context);
        // Soft trim shortly after launch so ImageCache does not sit at multi-GB.
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> releaseMemoryForBackup(context, false), 2500L);
    }

    /**
     * Ask Flutter to drop ImageCache / Skia bitmaps, then trim on-disk video/image caches.
     * createBackupBytes is tiny (~0.1–0.6MB) but OOMs when RSS already ~2GB from posters/player.
     */
    private static void releaseMemoryForBackup(Context context, boolean aggressiveDisk) {
        long now = SystemClock.elapsedRealtime();
        long prev = LAST_MEMORY_RELEASE_ELAPSED.get();
        long rssNow = readRssAnonKb();
        long minGap = (aggressiveDisk || rssNow >= 1_200_000L) ? 5_000L : 15_000L;
        if (now - prev < minGap) {
            return;
        }
        if (!LAST_MEMORY_RELEASE_ELAPSED.compareAndSet(prev, now)) {
            return;
        }
        try {
            Object jni = flutterJniRef != null ? flutterJniRef.get() : null;
            if (jni != null) {
                try {
                    Method notify = jni.getClass().getMethod("notifyLowMemoryWarning");
                    notify.invoke(jni);
                    log(4, "FlutterJNI.notifyLowMemoryWarning");
                } catch (NoSuchMethodException ignored) {
                    // Older embeddings — fall through to Application callbacks.
                }
            }
            Context appCtx = context != null ? context.getApplicationContext() : resolveAppContext();
            if (appCtx instanceof Application application) {
                application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                application.onLowMemory();
            }
            if (aggressiveDisk && appCtx != null) {
                MDK_TRIM_SCHEDULED.set(false);
                scheduleMdkCacheTrim(appCtx);
                trimImageDiskCaches(appCtx);
            }
            Runtime.getRuntime().gc();
            log(4, "released memory ahead of backup/WebDAV rssAnonKb=" + readRssAnonKb());
            Log.i(ZoeIds.TAG, "CapyJava released memory rssAnonKb=" + readRssAnonKb());
        } catch (Throwable t) {
            log(5, "memory release failed: " + t.getMessage());
            Log.e(ZoeIds.TAG, "CapyJava memory release failed", t);
        }
    }

    /** Anonymous RSS in KB; createBackup OOMs around ~2_000_000. */
    private static long readRssAnonKb() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/status"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("RssAnon:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            return Long.parseLong(parts[1]);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static void maybeReleaseIfRssHigh(Context context) {
        long rss = readRssAnonKb();
        // ~700MB anon already unsafe for createBackupBytes on this device.
        if (rss >= 700_000L) {
            log(4, "high RssAnonKb=" + rss + " — preemptive memory release");
            Log.i(ZoeIds.TAG, "CapyJava high RssAnonKb=" + rss);
            releaseMemoryForBackup(context, true);
        }
    }

    private static void trimImageDiskCaches(Context context) {
        new Thread(() -> {
            try {
                File cache = context.getCacheDir();
                if (cache == null) {
                    return;
                }
                String[] names = {
                        "cached_network_image_ce", "image_cache", "tmdb_image_cache", "mdk"
                };
                long freed = 0L;
                for (String name : names) {
                    File dir = new File(cache, name);
                    if (!dir.isDirectory()) {
                        continue;
                    }
                    long size = directorySize(dir);
                    if (size < IMAGE_CACHE_TRIM_BYTES && !"mdk".equals(name)) {
                        continue;
                    }
                    if ("mdk".equals(name) && size < MDK_CACHE_TRIM_BYTES) {
                        continue;
                    }
                    freed += deleteRecursively(dir);
                }
                if (freed > 0L) {
                    log(4, "trimmed image/mdk disk caches freed≈"
                            + (freed / (1024 * 1024)) + "MB");
                }
            } catch (Throwable t) {
                log(5, "image cache trim failed: " + t.getMessage());
            }
        }, "zoevip-img-trim").start();
    }

    private static void installBackupMemoryGuard(
            ZoeModule module, XposedInterface.ExceptionMode mode
    ) {
        if (!BACKUP_IO_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Constructor<?> ctor : FileOutputStream.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length >= 1 && params[0] == File.class) {
                    hookConstructor(module, ctor, mode, chain -> {
                        Object fileArg = chain.getArg(0);
                        if (fileArg instanceof File file) {
                            String path = file.getAbsolutePath();
                            if (isBackupRelatedPath(path)) {
                                releaseMemoryForBackup(resolveAppContext(), true);
                                schedulePostBackupProReseed();
                            } else if (isPrefsRestorePath(path)) {
                                schedulePostBackupProReseed();
                            }
                        }
                        return chain.proceed();
                    });
                } else if (params.length >= 1 && params[0] == String.class) {
                    hookConstructor(module, ctor, mode, chain -> {
                        Object pathArg = chain.getArg(0);
                        if (pathArg instanceof String path) {
                            if (isBackupRelatedPath(path)) {
                                releaseMemoryForBackup(resolveAppContext(), true);
                                schedulePostBackupProReseed();
                            } else if (isPrefsRestorePath(path)) {
                                schedulePostBackupProReseed();
                            }
                        }
                        return chain.proceed();
                    });
                }
            }
            module.log(4, TAG, "backup memory guard installed");
        } catch (Throwable t) {
            BACKUP_IO_HOOKED.set(false);
            module.log(5, TAG, "backup memory guard skipped: " + t.getMessage());
        }
    }

    private static boolean isBackupRelatedPath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("capyplayer_backup")
                || lower.contains("sync_logs")
                || lower.contains("file_picker")
                || lower.contains("webdav")
                || lower.endsWith("backup.json")
                || lower.contains("/backup");
    }

    private static boolean isPrefsRestorePath(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("fluttersharedpreferences")
                || lower.endsWith("preferences_pb")
                || lower.endsWith("preferences_pb.tmp");
    }

    private static final AtomicBoolean POST_BACKUP_RESEED_SCHEDULED = new AtomicBoolean(false);

    /** Pull/restore can dump free tier into XML/pb; reseed once after IO settles. */
    private static void schedulePostBackupProReseed() {
        if (!POST_BACKUP_RESEED_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                Context ctx = resolveAppContext();
                if (ctx != null && !CapyPlayerEntitlementSupport.diskLooksLifetimePro(ctx)) {
                    CapyPlayerEntitlementSupport.forceReseedProSubscription(ctx);
                    log(4, "post-backup/restore Pro reseed");
                }
            } finally {
                POST_BACKUP_RESEED_SCHEDULED.set(false);
            }
        }, 2500L);
    }

    private static void registerTrimMemoryCallback(Context context) {
        if (!(context instanceof Application application)
                || !TRIM_CALLBACK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            application.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        MDK_TRIM_SCHEDULED.set(false);
                        scheduleMdkCacheTrim(application);
                    }
                }

                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                }

                @Override
                public void onLowMemory() {
                    MDK_TRIM_SCHEDULED.set(false);
                    scheduleMdkCacheTrim(application);
                }
            });
        } catch (Throwable t) {
            log(5, "trim callback skipped: " + t.getMessage());
        }
    }

    private static void scheduleMdkCacheTrim(Context context) {
        if (context == null || !MDK_TRIM_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                File mdk = new File(app.getCacheDir(), "mdk");
                if (!mdk.isDirectory()) {
                    return;
                }
                long size = directorySize(mdk);
                if (size < MDK_CACHE_TRIM_BYTES) {
                    return;
                }
                long freed = deleteRecursively(mdk);
                log(4, "trimmed mdk cache was=" + (size / (1024 * 1024))
                        + "MB freed≈" + (freed / (1024 * 1024)) + "MB (backup OOM guard)");
            } catch (Throwable t) {
                log(5, "mdk trim failed: " + t.getMessage());
            }
        }, "zoevip-mdk-trim").start();
    }

    private static long directorySize(File dir) {
        long total = 0L;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            if (child.isFile()) {
                total += Math.max(0L, child.length());
            } else if (child.isDirectory()) {
                total += directorySize(child);
            }
        }
        return total;
    }

    private static long deleteRecursively(File file) {
        long freed = 0L;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    freed += deleteRecursively(child);
                }
            }
        }
        long len = file.isFile() ? Math.max(0L, file.length()) : 0L;
        if (file.delete()) {
            freed += len;
        }
        return freed;
    }

    private static void scheduleEntitlementPush(final Context context, int times) {
        if (context == null || times <= 0) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < times; i++) {
            final int attempt = i;
            handler.postDelayed(() -> {
                // seedPro only — forceReseed rebuilds Flutter and refills ImageCache → backup OOM.
                CapyPlayerEntitlementSupport.seedProSubscription(context);
                log(4, "scheduled entitlement push (" + (attempt + 1) + "/" + times + ")");
            }, 800L + attempt * 2200L);
        }
    }

    private static void installSharedPreferences(
            ZoeModule module, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> impl = Class.forName("android.app.SharedPreferencesImpl");
            hookMethod(module, findMethod(impl, "getString", String.class, String.class), mode, chain -> {
                Object result = chain.proceed();
                String key = stringArg(chain, 0);
                if (key != null && result instanceof String) {
                    String overridden = CapyPlayerEntitlementSupport.overrideString(key, (String) result);
                    if (overridden != null) {
                        return overridden;
                    }
                }
                return result;
            });
            hookMethod(module, findMethod(impl, "getBoolean", String.class, boolean.class), mode, chain -> {
                Object result = chain.proceed();
                String key = stringArg(chain, 0);
                if (key != null && CapyPlayerEntitlementSupport.shouldForceTrue(key)) {
                    return true;
                }
                return result;
            });
            hookMethod(module, findMethod(impl, "getInt", String.class, int.class), mode, chain -> {
                Object result = chain.proceed();
                String key = stringArg(chain, 0);
                if (key != null && CapyPlayerEntitlementSupport.isQuotaKey(key)) {
                    return Integer.MAX_VALUE;
                }
                return result;
            });
            Class<?> editor = Class.forName("android.app.SharedPreferencesImpl$EditorImpl");
            hookMethod(module, findMethod(editor, "putString", String.class, String.class), mode, chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length >= 2 && args[0] instanceof String key && args[1] instanceof String value) {
                    if (shouldBlockOrReplaceWrite(key, value)) {
                        String preview = value.length() > 180 ? value.substring(0, 180) + "…" : value;
                        log(4, "Editor replaced subscription write " + key + " was=" + preview);
                        args[1] = CapyPlayerEntitlementSupport.isPurchaseCredentialKey(key)
                                ? ""
                                : CapyPlayerEntitlementSupport.replacementForKey(key);
                        onDowngradeWriteObserved();
                    } else if (isAuthSessionKey(key) && value != null && !value.isEmpty()) {
                        // Login/token refresh triggers server sync that clears Pro in memory.
                        schedulePostLoginReseed();
                    } else if (CapyPlayerEntitlementSupport.isEntitlementKey(key)
                            && !"true".equalsIgnoreCase(value)) {
                        args[1] = "true";
                    } else if (CapyPlayerEntitlementSupport.isQuotaKey(key)) {
                        args[1] = String.valueOf(Integer.MAX_VALUE);
                    }
                }
                return chain.proceed(args);
            });
            module.log(4, TAG, "SharedPreferencesImpl hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "SharedPreferences hook skipped: " + t.getMessage());
        }
    }

    private static void installClassLoaderMonitor(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            module.hook(loadClass).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                ClassLoader loader = (ClassLoader) chain.getThisObject();
                if (appClassLoader != null
                        && loader != appClassLoader
                        && !isDescendantOf(loader, appClassLoader)) {
                    return result;
                }
                String name = stringArg(chain, 0);
                if (name == null) {
                    return result;
                }
                if (name.startsWith("io.flutter.embedding.engine.FlutterJNI")) {
                    maybeHookFlutterJni(module, cl, mode);
                }
                if (name.startsWith("androidx.datastore.preferences.core.")) {
                    maybeHookDataStore(module, cl, mode);
                }
                if (name.contains("JavaDataStorage")
                        || name.contains("sharedpreferences.SharedPreferences")) {
                    maybeHookJavaDataStorage(module, cl, mode);
                }
                if (name.startsWith("com.android.billingclient.api.Purchase")) {
                    maybeHookBilling(module, cl, mode);
                }
                if ("okhttp3.ResponseBody".equals(name)) {
                    maybeHookOkHttp(module, cl, mode);
                }
                if ("y95".equals(name) || name.contains("sharedpreferences")) {
                    maybeHookSharedPreferencesBackend(module, cl, mode);
                }
                if ("cg3".equals(name) || "v7.h".equals(name)) {
                    LibCapyPlayerPurchaseHooks.install(module, cl);
                }
                return result;
            });
            module.log(4, TAG, "ClassLoader.loadClass hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "ClassLoader hook skipped: " + t.getMessage());
        }
    }

    private static void maybeHookFlutterJni(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (!FLUTTER_JNI_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> flutterJni = Class.forName("io.flutter.embedding.engine.FlutterJNI", false, cl);
            Method target = null;
            for (Method method : flutterJni.getDeclaredMethods()) {
                if (!"invokePlatformMessageResponseCallback".equals(method.getName())
                        || method.getParameterCount() != 3) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params[1] == ByteBuffer.class) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                FLUTTER_JNI_HOOKED.set(false);
                module.log(5, TAG, "FlutterJNI response callback not found");
                return;
            }
            hookMethod(module, target, mode, chain -> {
                Object self = chain.getThisObject();
                if (self != null) {
                    flutterJniRef = new WeakReference<>(self);
                }
                List<Object> args = chain.getArgs();
                ByteBuffer message = null;
                int position = 0;
                for (int i = 0; i < args.size(); i++) {
                    Object arg = args.get(i);
                    if (arg instanceof ByteBuffer buffer) {
                        message = buffer;
                    } else if (message != null && arg instanceof Integer pos) {
                        position = pos;
                    }
                }
                if (message == null) {
                    return chain.proceed();
                }
                if (CapyPlayerEntitlementSupport.patchPlatformMessageInPlace(
                        message, position) >= 0) {
                    log(4, "patched Flutter platform response");
                }
                return chain.proceed();
            });
            // Capture JNI instance early via attachToNative if present.
            try {
                Method attachNative = flutterJni.getDeclaredMethod("attachToNative");
                hookMethod(module, attachNative, mode, chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self != null) {
                        flutterJniRef = new WeakReference<>(self);
                    }
                    return result;
                });
            } catch (NoSuchMethodException ignored) {
            }
            module.log(4, TAG, "FlutterJNI hooked");
        } catch (Throwable t) {
            FLUTTER_JNI_HOOKED.set(false);
            module.log(5, TAG, "FlutterJNI hook failed: " + t.getMessage());
        }
    }

    private static void maybeHookSharedPreferencesBackend(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (SP_BACKEND_HOOKED.get()) {
            return;
        }
        try {
            Class<?> backend = Class.forName("y95", false, cl);
            Method readString = findMethodByArity(backend, "r", 2);
            Method readBool = findMethodByArity(backend, "f", 2);
            Method readMap = findMethodByArity(backend, "h", 2);
            if (readString == null && readBool == null && readMap == null) {
                return;
            }
            if (!SP_BACKEND_HOOKED.compareAndSet(false, true)) {
                return;
            }
            hookMethod(module, readString, mode, chain -> {
                Object result = chain.proceed();
                String key = stringArg(chain, 0);
                if (key != null && result instanceof String text) {
                    String overridden = CapyPlayerEntitlementSupport.overrideString(key, text);
                    if (overridden != null) {
                        log(4, "SP backend read " + key);
                        return overridden;
                    }
                }
                return result;
            });
            hookMethod(module, readBool, mode, chain -> {
                String key = stringArg(chain, 0);
                if (key != null && CapyPlayerEntitlementSupport.shouldForceTrue(key)) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });
            hookMethod(module, readMap, mode, chain -> {
                Object result = chain.proceed();
                if (result instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> writable = (Map<String, Object>) map;
                    if (CapyPlayerEntitlementSupport.patchPreferenceMap(writable)) {
                        log(4, "SP backend patched map");
                    }
                    return writable;
                }
                return result;
            });
            hookSharedPreferencesWrites(module, backend, mode);
            maybeHookV95Write(module, backend.getClassLoader(), mode);
            module.log(4, TAG, "SharedPreferences backend hooked");
        } catch (Throwable t) {
            SP_BACKEND_HOOKED.set(false);
            SP_WRITE_HOOKED.set(false);
            module.log(5, TAG, "SharedPreferences backend skipped: " + t.getMessage());
        }
    }

    private static final AtomicBoolean V95_HOOKED = new AtomicBoolean(false);

    /** Catch async DataStore string writes even if y95.s/a signature drift. */
    private static void maybeHookV95Write(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (V95_HOOKED.get()) {
            return;
        }
        try {
            Class<?> v95 = Class.forName("v95", false, cl);
            Constructor<?> ctor = null;
            for (Constructor<?> c : v95.getDeclaredConstructors()) {
                if (c.getParameterCount() == 5) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                return;
            }
            if (!V95_HOOKED.compareAndSet(false, true)) {
                return;
            }
            java.lang.reflect.Field keyField = v95.getDeclaredField("U");
            java.lang.reflect.Field valueField = v95.getDeclaredField("V");
            keyField.setAccessible(true);
            valueField.setAccessible(true);
            hookConstructor(module, ctor, mode, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String key = (String) keyField.get(self);
                    Object value = valueField.get(self);
                    if (key != null && value instanceof String text
                            && shouldBlockOrReplaceWrite(key, text)) {
                        String replacement = CapyPlayerEntitlementSupport.isPurchaseCredentialKey(key)
                                ? ""
                                : CapyPlayerEntitlementSupport.replacementForKey(key);
                        valueField.set(self, replacement);
                        onDowngradeWriteObserved();
                        log(4, "v95 write patched " + key);
                    } else if (key != null && value instanceof String text
                            && isAuthSessionKey(key) && !text.isEmpty()) {
                        schedulePostLoginReseed();
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.log(4, TAG, "v95 write coroutine hooked");
        } catch (Throwable t) {
            V95_HOOKED.set(false);
            module.log(5, TAG, "v95 write hook skipped: " + t.getMessage());
        }
    }

    private static void hookSharedPreferencesWrites(
            ZoeModule module, Class<?> backend, XposedInterface.ExceptionMode mode
    ) {
        if (!SP_WRITE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        Method writeString = findMethodByArity(backend, "s", 3);
        Method writeBool = findMethodByArity(backend, "m", 3);
        Method writeAlt = findMethodByArity(backend, "a", 3);
        hookMethod(module, writeString, mode, chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length >= 2 && args[0] instanceof String key && args[1] instanceof String value) {
                if (shouldBlockOrReplaceWrite(key, value)) {
                    String preview = value.length() > 180 ? value.substring(0, 180) + "…" : value;
                    log(4, "SP backend blocked downgrade write " + key + " was=" + preview);
                    args[1] = CapyPlayerEntitlementSupport.isPurchaseCredentialKey(key)
                            ? ""
                            : CapyPlayerEntitlementSupport.replacementForKey(key);
                    onDowngradeWriteObserved();
                } else if (isAuthSessionKey(key) && value != null && !value.isEmpty()) {
                    schedulePostLoginReseed();
                }
            }
            return chain.proceed(args);
        });
        hookMethod(module, writeAlt, mode, chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length >= 2 && args[0] instanceof String key && args[1] instanceof String value) {
                if (shouldBlockOrReplaceWrite(key, value)) {
                    String preview = value.length() > 180 ? value.substring(0, 180) + "…" : value;
                    log(4, "SP backend blocked alt downgrade write " + key + " was=" + preview);
                    args[1] = CapyPlayerEntitlementSupport.isPurchaseCredentialKey(key)
                            ? ""
                            : CapyPlayerEntitlementSupport.replacementForKey(key);
                    onDowngradeWriteObserved();
                } else if (isAuthSessionKey(key) && value != null && !value.isEmpty()) {
                    schedulePostLoginReseed();
                }
            }
            return chain.proceed(args);
        });
        hookMethod(module, writeBool, mode, chain -> {
            Object[] args = chain.getArgs().toArray();
            if (args.length >= 2 && args[0] instanceof String key && args[1] instanceof Boolean value) {
                if (!value && CapyPlayerEntitlementSupport.shouldForceTrue(key)) {
                    args[1] = Boolean.TRUE;
                    log(4, "SP backend forced true write " + key);
                }
            }
            return chain.proceed(args);
        });
        maybeHookI95Read(module, backend.getClassLoader(), mode);
    }

    private static boolean shouldBlockOrReplaceWrite(String key, String value) {
        if (CapyPlayerEntitlementSupport.isPurchaseCredentialKey(key)) {
            return value != null && !value.isEmpty();
        }
        // AuthNotifier clears subscription blobs to "" / "{}" on login.
        if (CapyPlayerEntitlementSupport.isSubscriptionKey(key)
                && (value == null || value.isEmpty() || "{}".equals(value.trim()))) {
            return true;
        }
        if (CapyPlayerEntitlementSupport.isDowngradeSubscriptionJson(value)) {
            return true;
        }
        // Only rewrite subscription blobs that are not already lifetime — rewriting
        // lifetime→lifetime floods SharedPreferences listeners and flashes images.
        if (CapyPlayerEntitlementSupport.isSubscriptionKey(key)
                && !CapyPlayerSubscriptionPatcher.isLifetimePayload(value)) {
            return true;
        }
        return false;
    }

    private static boolean isAuthSessionKey(String key) {
        if (key == null) {
            return false;
        }
        String n = key.toLowerCase(Locale.ROOT);
        if (n.startsWith("flutter.")) {
            n = n.substring("flutter.".length());
        }
        return n.contains("access_token")
                || n.contains("accesstoken")
                || n.equals("user_json")
                || n.equals("userjson")
                || n.contains("auth_token")
                || n.contains("id_token")
                || n.contains("idtoken")
                || n.contains("refresh_token")
                || n.contains("refreshtoken")
                || n.equals("token")
                || n.contains("auth_session")
                || n.contains("logged_in")
                || n.contains("is_logged");
    }

    private static final AtomicBoolean POST_LOGIN_RESEED_SCHEDULED = new AtomicBoolean(false);

    private static void onDowngradeWriteObserved() {
        CapyPlayerEntitlementSupport.markNeedsReseed();
        Context ctx = resolveAppContext();
        if (ctx == null) {
            return;
        }
        // Delayed single reseed — immediate force on every free write storms the UI.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            CapyPlayerEntitlementSupport.forceReseedProSubscription(ctx);
            scheduleEntitlementPush(ctx, 3);
            log(4, "reseed after downgrade write");
        }, 200L);
    }

    private static void schedulePostLoginReseed() {
        if (!POST_LOGIN_RESEED_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Context ctx = resolveAppContext();
        if (ctx == null) {
            POST_LOGIN_RESEED_SCHEDULED.set(false);
            return;
        }
        log(4, "auth session write — scheduling post-login Pro reseed");
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            CapyPlayerEntitlementSupport.forceReseedProSubscription(ctx);
            scheduleEntitlementPush(ctx, 4);
            if (activeModule != null && appClassLoader != null) {
                LibCapyPlayerPurchaseHooks.redeliverAfterLogin(activeModule, appClassLoader);
            }
            POST_LOGIN_RESEED_SCHEDULED.set(false);
            log(4, "post-login Pro reseed done");
        }, 1500L);
        handler.postDelayed(() -> {
            CapyPlayerEntitlementSupport.forceReseedProSubscription(ctx);
            if (activeModule != null && appClassLoader != null) {
                LibCapyPlayerPurchaseHooks.redeliverAfterLogin(activeModule, appClassLoader);
            }
            log(4, "post-login Pro reseed wave2");
        }, 5000L);
    }

    private static void maybeHookI95Read(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (I95_READ_HOOKED.get()) {
            return;
        }
        try {
            Class<?> i95 = Class.forName("i95", false, cl);
            Method resume = findMethod(i95, "p", Object.class);
            if (resume == null) {
                return;
            }
            if (!I95_READ_HOOKED.compareAndSet(false, true)) {
                return;
            }
            java.lang.reflect.Field keyField = i95.getDeclaredField("U");
            java.lang.reflect.Field outField = i95.getDeclaredField("W");
            keyField.setAccessible(true);
            outField.setAccessible(true);
            hookMethod(module, resume, mode, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String key = (String) keyField.get(self);
                    Object out = outField.get(self);
                    if (key != null && out != null) {
                        patchSp4Result(out, key);
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            module.log(4, TAG, "i95 read coroutine hooked");
        } catch (Throwable t) {
            I95_READ_HOOKED.set(false);
            module.log(5, TAG, "i95 read hook skipped: " + t.getMessage());
        }
    }

    private static void patchSp4Result(Object box, String key) throws Exception {
        java.lang.reflect.Field valueField = box.getClass().getDeclaredField("s");
        valueField.setAccessible(true);
        Object current = valueField.get(box);
        if (current instanceof String text) {
            String overridden = CapyPlayerEntitlementSupport.overrideString(key, text);
            if (overridden != null && !overridden.equals(text)) {
                valueField.set(box, overridden);
                log(4, "i95 patched read " + key);
            }
            return;
        }
        if (current instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> writable = (Map<String, Object>) map;
            if (CapyPlayerEntitlementSupport.patchPreferenceMap(writable)) {
                log(4, "i95 patched map read");
            }
            return;
        }
        if (current == null) {
            Object overridden = CapyPlayerEntitlementSupport.overrideDataStoreValue(key);
            if (overridden != null) {
                valueField.set(box, overridden);
                log(4, "i95 injected read " + key);
            }
        }
    }

    private static void maybeHookDataStore(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (DATASTORE_GAVE_UP.get() || !DATASTORE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> prefsClass = Class.forName(
                    "androidx.datastore.preferences.core.Preferences", false, cl);
            Class<?> mutableClass = Class.forName(
                    "androidx.datastore.preferences.core.MutablePreferences", false, cl);
            for (Method method : prefsClass.getDeclaredMethods()) {
                if ("get".equals(method.getName())) {
                    hookDataStoreGet(module, method, mode);
                }
            }
            for (Method method : mutableClass.getDeclaredMethods()) {
                if ("get".equals(method.getName())) {
                    hookDataStoreGet(module, method, mode);
                }
            }
            module.log(4, TAG, "DataStore hooked (read-only)");
        } catch (Throwable t) {
            DATASTORE_HOOKED.set(false);
            DATASTORE_GAVE_UP.set(true);
            module.log(5, TAG, "DataStore unavailable: " + t.getMessage());
        }
    }

    private static void hookDataStoreGet(
            ZoeModule module, Method method, XposedInterface.ExceptionMode mode
    ) {
        hookMethod(module, method, mode, chain -> {
            Object result = chain.proceed();
            String name = keyName(chain.getArg(0));
            if (name == null) {
                return result;
            }
            Object overridden = CapyPlayerEntitlementSupport.overrideDataStoreValue(name);
            if (overridden != null) {
                log(4, "DataStore read " + name);
                return overridden;
            }
            return result;
        });
    }

    private static void maybeHookJavaDataStorage(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (JAVA_STORAGE_GAVE_UP.get() || !JAVA_STORAGE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> storage = Class.forName(
                    "io.flutter.plugins.sharedpreferences.JavaDataStorage", false, cl);
            for (Method method : storage.getDeclaredMethods()) {
                String n = method.getName();
                if (n.startsWith("get")) {
                    hookMethod(module, method, mode, chain -> {
                        Object result = chain.proceed();
                        if (chain.getArgs().isEmpty()
                                || !(chain.getArg(0) instanceof String key)) {
                            return result;
                        }
                        if (result instanceof String) {
                            String overridden = CapyPlayerEntitlementSupport.overrideString(
                                    key, (String) result);
                            if (overridden != null) {
                                log(4, "JavaDataStorage read " + key);
                                return overridden;
                            }
                        } else if (result instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = (Map<String, Object>) result;
                            CapyPlayerEntitlementSupport.patchPreferenceMap(map);
                            return map;
                        }
                        return result;
                    });
                }
            }
            module.log(4, TAG, "JavaDataStorage hooked (read-only)");
        } catch (Throwable t) {
            JAVA_STORAGE_HOOKED.set(false);
            JAVA_STORAGE_GAVE_UP.set(true);
        }
    }

    private static void maybeHookOkHttp(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (!OKHTTP_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> body = Class.forName("okhttp3.ResponseBody", false, cl);
            Method stringMethod = findMethod(body, "string");
            if (stringMethod != null) {
                hookMethod(module, stringMethod, mode, chain -> {
                    Object result = chain.proceed();
                    if (result instanceof String text) {
                        // Skip large bodies (WebDAV backup JSON) — toLowerCase OOMs.
                        if (text.length() > 65536) {
                            return result;
                        }
                        String patched = CapyPlayerEntitlementSupport.patchHttpText(text);
                        if (!patched.equals(text)) {
                            log(4, "replaced OkHttp subscription body");
                            return patched;
                        }
                    }
                    return result;
                });
            }
            module.log(4, TAG, "OkHttp hooked");
        } catch (Throwable t) {
            OKHTTP_HOOKED.set(false);
        }
    }

    private static void maybeHookBilling(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (!BILLING_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> purchase = Class.forName(
                    "com.android.billingclient.api.Purchase", false, cl);
            for (Method method : purchase.getDeclaredMethods()) {
                if ("getPurchaseState".equals(method.getName())) {
                    hookMethod(module, method, mode, chain -> 1);
                } else if ("isAcknowledged".equals(method.getName())) {
                    hookMethod(module, method, mode, chain -> true);
                } else if ("getProducts".equals(method.getName())) {
                    hookMethod(module, method, mode, chain -> {
                        Object result = chain.proceed();
                        if (result instanceof List<?> list && !list.isEmpty()) {
                            return result;
                        }
                        return Collections.singletonList(PRO_PRODUCT);
                    });
                }
            }
            module.log(4, TAG, "Billing Purchase hooked");
        } catch (Throwable t) {
            BILLING_HOOKED.set(false);
        }
    }

    private static void installBillingQueryHook(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        if (!BILLING_QUERY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> listener = Class.forName(
                    "com.android.billingclient.api.PurchasesResponseListener", false, cl);
            for (Method method : listener.getDeclaredMethods()) {
                if (!"onQueryPurchasesResponse".equals(method.getName())) {
                    continue;
                }
                hookMethod(module, method, mode, chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (args.length >= 2) {
                        try {
                            args[1] = Collections.singletonList(
                                    newLifetimePurchase(cl, PRO_PRODUCT));
                            log(4, "queryPurchasesAsync -> injected lifetime purchase");
                        } catch (Throwable t) {
                            log(5, "queryPurchases inject failed: " + t.getMessage());
                        }
                    }
                    return chain.proceed(args);
                });
            }
            module.log(4, TAG, "PurchasesResponseListener hooked");
        } catch (Throwable t) {
            BILLING_QUERY_HOOKED.set(false);
            module.log(5, TAG, "PurchasesResponseListener skipped: " + t.getMessage());
        }
    }

    private static Object newLifetimePurchase(ClassLoader cl, String productId) throws Exception {
        long now = System.currentTimeMillis();
        String json = "{\"orderId\":\"GPA.1337-7331-CAPY-0001\","
                + "\"packageName\":\"com.feifeiduck.capyplayer\","
                + "\"productId\":\"" + productId + "\","
                + "\"purchaseTime\":" + now + ","
                + "\"purchaseState\":1,"
                + "\"purchaseToken\":\"zoevip.pro.token\","
                + "\"acknowledged\":true,"
                + "\"autoRenewing\":false}";
        Class<?> purchaseClass = Class.forName(
                "com.android.billingclient.api.Purchase", false, cl);
        Constructor<?> ctor = purchaseClass.getConstructor(String.class, String.class);
        return ctor.newInstance(json, "zoevip");
    }

    private static void installPbWriteGuard(
            ZoeModule module, XposedInterface.ExceptionMode mode
    ) {
        if (!PB_GUARD_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Constructor<?> ctor : FileOutputStream.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0] == File.class) {
                    hookConstructor(module, ctor, mode, chain -> {
                        Object result = chain.proceed();
                        trackPbStream(chain.getArg(0), chain.getThisObject());
                        return result;
                    });
                } else if (params.length == 2 && params[0] == File.class && params[1] == boolean.class) {
                    hookConstructor(module, ctor, mode, chain -> {
                        Object result = chain.proceed();
                        trackPbStream(chain.getArg(0), chain.getThisObject());
                        return result;
                    });
                }
            }
            for (Method method : FileOutputStream.class.getDeclaredMethods()) {
                if (!"write".equals(method.getName())) {
                    continue;
                }
                hookMethod(module, method, mode, chain -> {
                    if (!(chain.getThisObject() instanceof FileOutputStream stream)) {
                        return chain.proceed();
                    }
                    String path = STREAM_PATHS.get(stream);
                    if (path == null || !GUARDED_PATHS.contains(path)) {
                        return chain.proceed();
                    }
                    byte[] payload = extractWriteBytes(chain.getArgs());
                    if (payload != null && containsPbDowngrade(payload)) {
                        log(4, "blocked pb downgrade write");
                        return null;
                    }
                    return chain.proceed();
                });
            }
            module.log(4, TAG, "preferences_pb write guard installed");
        } catch (Throwable t) {
            PB_GUARD_HOOKED.set(false);
            module.log(5, TAG, "pb write guard skipped: " + t.getMessage());
        }
    }

    private static void trackPbStream(Object fileArg, Object streamArg) {
        if (!(fileArg instanceof File file) || !(streamArg instanceof FileOutputStream stream)) {
            return;
        }
        if (CapyPlayerEntitlementSupport.isPreferencesPbPath(file.getAbsolutePath())) {
            STREAM_PATHS.put(stream, file.getAbsolutePath());
            GUARDED_PATHS.add(file.getAbsolutePath());
        }
    }

    private static byte[] extractWriteBytes(List<Object> args) {
        if (args.size() == 1 && args.get(0) instanceof byte[] bytes) {
            return bytes;
        }
        if (args.size() == 3 && args.get(0) instanceof byte[] buffer
                && args.get(1) instanceof Integer offset
                && args.get(2) instanceof Integer length) {
            byte[] slice = new byte[length];
            System.arraycopy(buffer, offset, slice, 0, length);
            return slice;
        }
        return null;
    }

    private static boolean containsPbDowngrade(byte[] payload) {
        String lower = new String(payload, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (lower.contains("\"tier\":\"free\"") || lower.contains("rejectedreceipt")) {
            return true;
        }
        return lower.contains("hassubscription")
                && (lower.contains(":false") || lower.contains("\"tier\":\"free\""));
    }

    private static void captureFlutterJniFromEngine(Object engine) {
        if (engine == null) {
            return;
        }
        try {
            Method getter = null;
            for (Method method : engine.getClass().getMethods()) {
                if ("getFlutterJNI".equals(method.getName())
                        && method.getParameterCount() == 0) {
                    getter = method;
                    break;
                }
            }
            if (getter != null) {
                Object jni = getter.invoke(engine);
                if (jni != null) {
                    flutterJniRef = new WeakReference<>(jni);
                    log(4, "captured FlutterJNI from engine");
                    return;
                }
            }
            for (java.lang.reflect.Field field : engine.getClass().getDeclaredFields()) {
                if (!field.getType().getName().contains("FlutterJNI")) {
                    continue;
                }
                field.setAccessible(true);
                Object jni = field.get(engine);
                if (jni != null) {
                    flutterJniRef = new WeakReference<>(jni);
                    log(4, "captured FlutterJNI field " + field.getName());
                    return;
                }
            }
        } catch (Throwable t) {
            log(5, "FlutterJNI capture skipped: " + t.getMessage());
        }
    }

    private static void installMainActivityHooks(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> mainActivity = Class.forName(
                    "com.feifeiduck.capyplayer.MainActivity", false, cl);
            Method onCreate = findMethod(mainActivity, "onCreate", Bundle.class);
            if (onCreate != null) {
                hookMethod(module, onCreate, mode, chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() instanceof Activity activity) {
                        Context app = activity.getApplicationContext();
                        CapyPlayerEntitlementSupport.seedProSubscription(app);
                        releaseMemoryForBackup(app, false);
                        installDeferredHooks(module, cl);
                    }
                    return result;
                });
            }
            // onResume seeding removed: caused repeated main-thread work and jank.
            try {
                Class<?> engineClass = Class.forName(
                        "io.flutter.embedding.engine.FlutterEngine", false, cl);
                Method configure = findMethod(mainActivity, "configureFlutterEngine", engineClass);
                if (configure != null) {
                    hookMethod(module, configure, mode, chain -> {
                        Object result = chain.proceed();
                        captureFlutterJniFromEngine(chain.getArg(0));
                        log(4, "FlutterEngine ready");
                        installDeferredHooks(module, cl);
                        return result;
                    });
                }
            } catch (Throwable ignored) {
            }
            module.log(4, TAG, "MainActivity lifecycle hooked");
        } catch (Throwable t) {
            module.log(5, TAG, "MainActivity hook skipped: " + t.getMessage());
        }
    }

    private static final AtomicBoolean MEMORY_WATCH_STARTED = new AtomicBoolean(false);

    private static void startMemoryWatchdog() {
        if (!MEMORY_WATCH_STARTED.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable task = new Runnable() {
            @Override
            public void run() {
                Context ctx = resolveAppContext();
                if (ctx != null) {
                    maybeReleaseIfRssHigh(ctx);
                }
                handler.postDelayed(this, 20_000L);
            }
        };
        handler.postDelayed(task, 3_000L);
    }

    private static void startPolling(ZoeModule module, ClassLoader cl) {
        if (!POLLING_STARTED.compareAndSet(false, true)) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable task = new Runnable() {
            int attempts;

            @Override
            public void run() {
                // Keep probing for late-loaded y95/i95 without a global ClassLoader hook.
                maybeHookSharedPreferencesBackend(module, cl,
                        XposedInterface.ExceptionMode.PROTECTIVE);
                maybeHookDataStore(module, cl, XposedInterface.ExceptionMode.PROTECTIVE);
                maybeHookJavaDataStorage(module, cl, XposedInterface.ExceptionMode.PROTECTIVE);
                maybeHookOkHttp(module, cl, XposedInterface.ExceptionMode.PROTECTIVE);
                maybeHookBilling(module, cl, XposedInterface.ExceptionMode.PROTECTIVE);
                if (!LibCapyPlayerNative.isPatched()) {
                    LibCapyPlayerNative.ensurePatchesApplied(module, false);
                }
                Context ctx = resolveAppContext();
                if (ctx != null && (attempts % 8) == 0) {
                    // seedPro rewrites only when disk shows free (WebDAV restore / sync).
                    CapyPlayerEntitlementSupport.seedProSubscription(ctx);
                    maybeReleaseIfRssHigh(ctx);
                }
                boolean storageReady = SP_BACKEND_HOOKED.get() || I95_READ_HOOKED.get();
                boolean patchesReady = LibCapyPlayerNative.isPatched();
                if (++attempts < (storageReady && patchesReady ? 4 : 12)) {
                    handler.postDelayed(this, storageReady ? 2000L : 800L);
                }
            }
        };
        handler.postDelayed(task, 400);
    }

    private static void installDeferredHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        maybeHookFlutterJni(module, cl, mode);
        maybeHookDataStore(module, cl, mode);
        maybeHookJavaDataStorage(module, cl, mode);
        maybeHookSharedPreferencesBackend(module, cl, mode);
        maybeHookBilling(module, cl, mode);
        maybeHookOkHttp(module, cl, mode);
        LibCapyPlayerPurchaseHooks.install(module, cl);
    }

    private static String keyName(Object arg) {
        if (arg == null) {
            return null;
        }
        try {
            Method getName = arg.getClass().getMethod("getName");
            return String.valueOf(getName.invoke(arg));
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDescendantOf(ClassLoader loader, ClassLoader root) {
        ClassLoader current = loader;
        while (current != null) {
            if (current == root) {
                return true;
            }
            current = current.getParent();
        }
        return loader == root;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethodByArity(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static void hookMethod(
            ZoeModule module,
            Method method,
            XposedInterface.ExceptionMode mode,
            XposedInterface.Hooker hooker
    ) {
        if (method == null) {
            return;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(hooker);
        } catch (Throwable t) {
            module.log(5, TAG, "hook failed " + method + ": " + t.getMessage());
        }
    }

    private static void hookConstructor(
            ZoeModule module,
            Constructor<?> ctor,
            XposedInterface.ExceptionMode mode,
            XposedInterface.Hooker hooker
    ) {
        try {
            ctor.setAccessible(true);
            module.hook(ctor).setExceptionMode(mode).intercept(hooker);
        } catch (Throwable t) {
            module.log(5, TAG, "ctor hook failed " + ctor + ": " + t.getMessage());
        }
    }

    private static String stringArg(XposedInterface.Chain chain, int index) {
        Object arg = chain.getArg(index);
        return arg instanceof String ? (String) arg : null;
    }

    private static void log(int level, String message) {
        if (activeModule != null) {
            activeModule.log(level, TAG, message);
        }
    }
}
