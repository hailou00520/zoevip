package com.afusekt.lsp.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CapyPlayer lifetime Pro unlock.
 *
 * Storage uses shared_preferences_android + DataStore (FlutterSharedPreferences.preferences_pb).
 * Legacy XML SharedPreferences hooks alone are not enough; we patch DataStore + pigeon replies.
 */
public final class CapyPlayerHooks {

    private static final String TAG = MainHook.TAG + ":CapyPlayer";
    private static final String PREFS_NAME = "FlutterSharedPreferences";
    /** ZoT uses year SKU; lifetime also accepted by entitlement logic. */
    private static final String PRO_PRODUCT = "capyplayer.pro.year";
    private static final String LIFETIME_PRODUCT = "capyplayer.pro.lifetime";
    private static final String FAKE_ORDER_ID = "GPA.1337-7331-CAPY-0001";

    private static ClassLoader appClassLoader;

    private static final AtomicBoolean DATASTORE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean DATASTORE_GAVE_UP = new AtomicBoolean(false);
    private static final AtomicBoolean JAVA_STORAGE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean JAVA_STORAGE_GAVE_UP = new AtomicBoolean(false);
    private static final AtomicBoolean BILLING_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean FLUTTER_JNI_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean OKHTTP_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean POLLING_STARTED = new AtomicBoolean(false);

    private static Context appContext;

    private static final String SUBSCRIPTION_STATE_JSON =
            CapyPlayerSubscriptionPatcher.LIFETIME_STATE_JSON;

    private static final String SUBSCRIPTION_STATUS_JSON =
            CapyPlayerSubscriptionPatcher.LIFETIME_API_JSON;

    private CapyPlayerHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        appClassLoader = lpparam.classLoader;
        ClassLoader cl = lpparam.classLoader;
        hookApplicationSeed(cl);
        hookMainActivityLifecycle(cl);
        hookSharedPreferencesImpl();
        hookSharedPreferencesEditorImpl();
        hookFlutterJni(cl);
        hookDeferredClassLoad(cl);
        PreferencesPbWriteGuard.install();
        CapyPlayerNetworkHooks.install(cl);
        CapyPlayerPurchaseHooks.install(cl);
        CapyPlayerLoadHooks.installBillingQueryHook(cl, PRO_PRODUCT);
        startPollingInstall(cl);
        log(TAG + ": Pro hooks installed (ZoT-style)");
    }

    private static void hookApplicationSeed(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    appContext = (Context) param.args[0];
                    CapyPlayerNativePatch.scheduleRetry();
                    seedProSubscription(appContext);
                    CapyPlayerLoadHooks.installRuntimeLoadHook(appContext, cl);
                    CapyPlayerLoadHooks.scheduleEntitlementPush(appContext, 6);
                }
            });
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    appContext = (Context) param.thisObject;
                    seedProSubscription(appContext);
                    CapyPlayerLoadHooks.installRuntimeLoadHook(appContext, cl);
                    CapyPlayerLoadHooks.scheduleEntitlementPush(appContext, 6);
                    installDeferredHooks(cl);
                }
            });
            log(TAG + ": Application attach/onCreate hooked");
        } catch (Throwable t) {
            log(TAG + ": Application seed skipped: " + t.getMessage());
        }
    }

    private static void hookMainActivityLifecycle(ClassLoader cl) {
        try {
            XC_MethodHook lifecycleHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    seedProSubscription(((Activity) param.thisObject).getApplicationContext());
                    installDeferredHooks(cl);
                }
            };
            XC_MethodHook engineHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    log(TAG + ": FlutterEngine ready");
                    installDeferredHooks(cl);
                }
            };
            XposedHelpers.findAndHookMethod(
                    "com.feifeiduck.capyplayer.MainActivity",
                    cl,
                    "onCreate",
                    Bundle.class,
                    lifecycleHook
            );
            XposedHelpers.findAndHookMethod(
                    "com.feifeiduck.capyplayer.MainActivity",
                    cl,
                    "onResume",
                    lifecycleHook
            );
            try {
                XposedHelpers.findAndHookMethod(
                        "com.feifeiduck.capyplayer.MainActivity",
                        cl,
                        "configureFlutterEngine",
                        "io.flutter.embedding.engine.FlutterEngine",
                        engineHook
                );
                log(TAG + ": MainActivity.configureFlutterEngine hooked");
            } catch (Throwable t) {
                log(TAG + ": configureFlutterEngine hook skipped: " + t.getMessage());
            }
            log(TAG + ": MainActivity lifecycle hooked");
        } catch (Throwable t) {
            log(TAG + ": MainActivity lifecycle skipped: " + t.getMessage());
        }
    }

    private static void startPollingInstall(final ClassLoader cl) {
        if (!POLLING_STARTED.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            int attempts;

            @Override
            public void run() {
                installDeferredHooks(cl);
                if (appContext != null && attempts % 3 == 0) {
                    seedProSubscription(appContext);
                }
                if (++attempts < 60) {
                    new Handler(Looper.getMainLooper()).postDelayed(this, 1000);
                }
            }
        }, 500);
    }

    private static void installDeferredHooks(ClassLoader cl) {
        maybeHookFlutterJni(cl);
        maybeHookDataStore(cl);
        maybeHookJavaDataStorage(cl);
        maybeHookBilling(cl);
        maybeHookOkHttp(cl);
    }

    private static void hookFlutterJni(ClassLoader cl) {
        maybeHookFlutterJni(cl);
    }

    private static void maybeHookFlutterJni(ClassLoader cl) {
        if (!FLUTTER_JNI_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> flutterJni = XposedHelpers.findClass("io.flutter.embedding.engine.FlutterJNI", cl);
            XC_MethodHook responseHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 3 || param.args[1] == null) {
                        return;
                    }
                    java.nio.ByteBuffer message = (java.nio.ByteBuffer) param.args[1];
                    int position = (Integer) param.args[2];
                    java.nio.ByteBuffer patched = PlatformMessagePatcher.patch(message, position);
                    if (patched != null) {
                        param.args[1] = patched;
                        param.args[2] = 0;
                        log(TAG + ": patched Flutter platform response");
                    }
                }
            };
            XposedBridge.hookAllMethods(flutterJni, "invokePlatformMessageResponseCallback", responseHook);
            for (java.lang.reflect.Method method : flutterJni.getDeclaredMethods()) {
                String name = method.getName();
                if (name.contains("PlatformMessage") && name.contains("Response")) {
                    try {
                        XposedBridge.hookMethod(method, responseHook);
                    } catch (Throwable ignored) {
                    }
                }
            }
            log(TAG + ": FlutterJNI hooked");
        } catch (Throwable t) {
            FLUTTER_JNI_HOOKED.set(false);
            log(TAG + ": FlutterJNI hook failed: " + t.getMessage());
        }
    }

    /** Called by purchase hooks. */
    static Object patchPlatformValue(Object value) {
        return CapyPlayerEntitlementSupport.patchPlatformValue(value);
    }

    static void onLibAppLoaded(ClassLoader cl) {
        installDeferredHooks(cl);
        CapyPlayerPurchaseHooks.install(cl);
    }

    static void seedProSubscription(Context context) {
        CapyPlayerEntitlementSupport.seedProSubscription(context);
    }

    private static void hookSharedPreferencesImpl() {
        try {
            XC_MethodHook readHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (key == null) {
                        return;
                    }
                    String method = param.method.getName();
                    if ("getString".equals(method)) {
                        String overridden = overrideString(key, (String) param.getResult());
                        if (overridden != null) {
                            param.setResult(overridden);
                        }
                    } else if ("getBoolean".equals(method) && shouldForceTrue(key)) {
                        param.setResult(true);
                    } else if ("getInt".equals(method) && isQuotaKey(key)) {
                        param.setResult(Integer.MAX_VALUE);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl", null,
                    "getString", String.class, String.class, readHook);
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl", null,
                    "getBoolean", String.class, boolean.class, readHook);
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl", null,
                    "getInt", String.class, int.class, readHook);
            log(TAG + ": SharedPreferencesImpl hooked");
        } catch (Throwable t) {
            log(TAG + ": SharedPreferencesImpl hook skipped: " + t.getMessage());
        }
    }

    private static void hookSharedPreferencesEditorImpl() {
        try {
            XC_MethodHook writeHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    String value = (String) param.args[1];
                    if (isSubscriptionKey(key)) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                    } else if (isQuotaKey(key)) {
                        param.args[1] = String.valueOf(Integer.MAX_VALUE);
                    } else if (isEntitlementKey(key)) {
                        param.args[1] = "true";
                    } else if (value != null && isDowngradeSubscriptionJson(value)) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                        log(TAG + ": blocked downgrade write " + key);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl$EditorImpl", null,
                    "putString", String.class, String.class, writeHook);
            log(TAG + ": SharedPreferencesImpl.Editor hooked");
        } catch (Throwable t) {
            log(TAG + ": Editor hook skipped: " + t.getMessage());
        }
    }

    private static void hookDeferredClassLoad(ClassLoader appCl) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                return;
                            }
                            ClassLoader loader = (ClassLoader) param.thisObject;
                            if (appClassLoader != null
                                    && loader != appClassLoader
                                    && !isDescendantOf(loader, appClassLoader)) {
                                return;
                            }
                            String name = (String) param.args[0];
                            if (name.startsWith("io.flutter.embedding.engine.FlutterJNI")) {
                                maybeHookFlutterJni(appCl);
                            }
                            if (name.startsWith("androidx.datastore.preferences.core.")) {
                                maybeHookDataStore(appCl);
                            }
                            if (name.contains("JavaDataStorage")
                                    || name.contains("sharedpreferences.SharedPreferences")) {
                                maybeHookJavaDataStorage(appCl);
                            }
                            if (name.startsWith("com.android.billingclient.api.Purchase")) {
                                maybeHookBilling(appCl);
                            }
                            if (name.equals("okhttp3.ResponseBody")) {
                                maybeHookOkHttp(appCl);
                            }
                        }
                    });
            log(TAG + ": ClassLoader.loadClass hooked");
        } catch (Throwable t) {
            log(TAG + ": ClassLoader hook skipped: " + t.getMessage());
        }
    }

    private static void maybeHookDataStore(ClassLoader cl) {
        if (DATASTORE_GAVE_UP.get() || !DATASTORE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            XC_MethodHook readHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String name = keyName(param.args);
                    if (name == null) {
                        return;
                    }
                    Object overridden = overrideDataStoreValue(name);
                    if (overridden != null) {
                        param.setResult(overridden);
                        log(TAG + ": DataStore read " + name);
                    }
                }
            };
            XC_MethodHook writeHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String name = keyName(param.args);
                    if (name == null) {
                        return;
                    }
                    if (isSubscriptionKey(name)) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                    } else if (isQuotaKey(name)) {
                        param.args[1] = String.valueOf(Integer.MAX_VALUE);
                    } else if (param.args.length > 1
                            && param.args[1] instanceof String
                            && isDowngradeSubscriptionJson((String) param.args[1])) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                        log(TAG + ": DataStore blocked downgrade " + name);
                    }
                }
            };

            Class<?> prefsClass = XposedHelpers.findClass(
                    "androidx.datastore.preferences.core.Preferences", cl);
            XposedBridge.hookAllMethods(prefsClass, "get", readHook);

            Class<?> mutableClass = XposedHelpers.findClass(
                    "androidx.datastore.preferences.core.MutablePreferences", cl);
            XposedBridge.hookAllMethods(mutableClass, "get", readHook);
            XposedBridge.hookAllMethods(mutableClass, "set", writeHook);

            log(TAG + ": DataStore hooked");
        } catch (Throwable t) {
            DATASTORE_HOOKED.set(false);
            DATASTORE_GAVE_UP.set(true);
            log(TAG + ": DataStore unavailable (obfuscated), using FlutterJNI patch");
        }
    }

    private static void maybeHookJavaDataStorage(ClassLoader cl) {
        if (JAVA_STORAGE_GAVE_UP.get() || !JAVA_STORAGE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> storage = XposedHelpers.findClass(
                    "io.flutter.plugins.sharedpreferences.JavaDataStorage", cl);
            XC_MethodHook readHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if (param.getResult() instanceof String) {
                        String overridden = overrideString(key, (String) param.getResult());
                        if (overridden != null) {
                            param.setResult(overridden);
                            log(TAG + ": JavaDataStorage read " + key);
                        }
                    } else if (param.getResult() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) param.getResult();
                        patchPreferenceMap(map);
                        param.setResult(map);
                    }
                }
            };
            XC_MethodHook writeHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    String key = (String) param.args[0];
                    if (isSubscriptionKey(key)) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                    } else if (isQuotaKey(key)) {
                        param.args[1] = String.valueOf(Integer.MAX_VALUE);
                    } else if (param.args[1] instanceof String
                            && isDowngradeSubscriptionJson((String) param.args[1])) {
                        param.args[1] = SUBSCRIPTION_STATE_JSON;
                    }
                }
            };
            for (Method method : storage.getDeclaredMethods()) {
                String n = method.getName();
                if (n.startsWith("get")) {
                    XposedBridge.hookMethod(method, readHook);
                } else if (n.startsWith("set") || n.startsWith("put")) {
                    XposedBridge.hookMethod(method, writeHook);
                }
            }
            log(TAG + ": JavaDataStorage hooked");
        } catch (Throwable t) {
            JAVA_STORAGE_HOOKED.set(false);
            JAVA_STORAGE_GAVE_UP.set(true);
        }
    }

    private static void maybeHookOkHttp(ClassLoader cl) {
        if (!OKHTTP_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> body = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            XposedHelpers.findAndHookMethod(body, "string", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof String) {
                        String patched = CapyPlayerSubscriptionPatcher.patchText((String) param.getResult());
                        if (!patched.equals(param.getResult())) {
                            param.setResult(patched);
                            log(TAG + ": replaced OkHttp subscription body");
                        }
                    }
                }
            });
            log(TAG + ": OkHttp hooked");
        } catch (Throwable t) {
            OKHTTP_HOOKED.set(false);
        }
    }

    private static void maybeHookBilling(ClassLoader cl) {
        if (!BILLING_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> purchase = XposedHelpers.findClass(
                    "com.android.billingclient.api.Purchase", cl);
            XposedBridge.hookAllMethods(purchase, "getPurchaseState", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return 1;
                }
            });
            XposedBridge.hookAllMethods(purchase, "isAcknowledged", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return true;
                }
            });
            XposedBridge.hookAllMethods(purchase, "getProducts", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof List && !((List<?>) param.getResult()).isEmpty()) {
                        return;
                    }
                    param.setResult(Collections.singletonList(PRO_PRODUCT));
                }
            });
            log(TAG + ": Billing hooked");
        } catch (Throwable t) {
            BILLING_HOOKED.set(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object patchReplyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String s = (String) value;
            if (isDowngradeSubscriptionJson(s)) {
                log(TAG + ": patched pigeon string reply");
                return SUBSCRIPTION_STATE_JSON;
            }
            if (PlatformMessagePatcher.looksLikeSubscriptionPayload(s)
                    && s.trim().startsWith("{")
                    && !s.contains("\"lifetime\":true")
                    && !s.contains("\"tier\":\"lifetime\"")) {
                return SUBSCRIPTION_STATE_JSON;
            }
            return value;
        }
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            if (patchPreferenceMap(map)) {
                log(TAG + ": patched pigeon map reply");
            }
            return map;
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object inner = patchReplyValue(list.get(i));
                if (inner != list.get(i)) {
                    list.set(i, inner);
                }
            }
            return list;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static boolean patchPreferenceMap(Map<?, ?> map) {
        boolean changed = false;
        Map<Object, Object> writable = (Map<Object, Object>) map;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (isSubscriptionKey(k) || (v instanceof String && isDowngradeSubscriptionJson((String) v))) {
                writable.put(e.getKey(), SUBSCRIPTION_STATE_JSON);
                changed = true;
            } else if (isQuotaKey(k)) {
                writable.put(e.getKey(), String.valueOf(Integer.MAX_VALUE));
                changed = true;
            } else if (isEntitlementKey(k)) {
                writable.put(e.getKey(), "true");
                changed = true;
            } else if (v != null) {
                Object inner = patchReplyValue(v);
                if (inner != v) {
                    writable.put(e.getKey(), inner);
                    changed = true;
                }
            }
        }
        if (looksLikeSharedPreferenceMap(map)) {
            changed |= ensureProKeys(writable);
        }
        return changed;
    }

    private static boolean looksLikeSharedPreferenceMap(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (String.valueOf(key).startsWith("flutter.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean ensureProKeys(Map<Object, Object> map) {
        boolean changed = false;
        changed |= putIfDifferent(map, "flutter.subscription_state", SUBSCRIPTION_STATE_JSON);
        changed |= putIfDifferent(map, "flutter.desktop_subscription_state", SUBSCRIPTION_STATE_JSON);
        changed |= putIfDifferent(map, "flutter.add_resource_quota", String.valueOf(Integer.MAX_VALUE));
        changed |= putIfDifferent(map, "flutter.isPro", "true");
        changed |= putIfDifferent(map, "flutter.synced_purchase_ids",
                "[\"" + PRO_PRODUCT + "\",\"" + LIFETIME_PRODUCT + "\"]");
        changed |= putIfDifferent(map, "flutter.subscriptionId", PRO_PRODUCT);
        return changed;
    }

    private static boolean putIfDifferent(Map<Object, Object> map, String key, String value) {
        Object current = map.get(key);
        if (value.equals(String.valueOf(current))) {
            return false;
        }
        map.put(key, value);
        return true;
    }

    private static String keyName(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return null;
        }
        try {
            return String.valueOf(XposedHelpers.callMethod(args[0], "getName"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object overrideDataStoreValue(String name) {
        if (isSubscriptionKey(name)) {
            return SUBSCRIPTION_STATE_JSON;
        }
        if (isQuotaKey(name)) {
            return String.valueOf(Integer.MAX_VALUE);
        }
        if (isEntitlementKey(name) || normalizedKey(name).equals("ispro")) {
            return "true";
        }
        if (normalizedKey(name).contains("synced_purchase_ids")) {
            return "[\"" + PRO_PRODUCT + "\",\"" + LIFETIME_PRODUCT + "\"]";
        }
        return null;
    }

    private static String overrideString(String key, String current) {
        if (isSubscriptionKey(key)) {
            return SUBSCRIPTION_STATE_JSON;
        }
        if (isQuotaKey(key)) {
            return String.valueOf(Integer.MAX_VALUE);
        }
        if (isEntitlementKey(key) || normalizedKey(key).equals("ispro")) {
            return "true";
        }
        if (current != null && isDowngradeSubscriptionJson(current)) {
            return SUBSCRIPTION_STATE_JSON;
        }
        return null;
    }

    private static boolean isSubscriptionKey(String key) {
        String n = normalizedKey(key);
        return n.contains("subscription_state")
                || n.equals("subscription_data")
                || n.contains("subscription_status");
    }

    private static boolean isEntitlementKey(String key) {
        String n = normalizedKey(key);
        return n.contains("entitlement") || n.equals("is_pro") || n.equals("ispro")
                || n.contains("hide_subscription");
    }

    private static boolean isQuotaKey(String key) {
        String n = normalizedKey(key);
        return n.equals("add_resource_quota") || n.contains("resource_quota");
    }

    private static boolean shouldForceTrue(String key) {
        return isEntitlementKey(key)
                || normalizedKey(key).contains("has_subscription")
                || normalizedKey(key).contains("subscription_active");
    }

    private static boolean isDowngradeSubscriptionJson(String value) {
        if (value == null || !value.trim().startsWith("{")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("\"hassubscription\":false")
                || lower.contains("\"has_subscription\":false")
                || lower.contains("\"isactive\":false")
                || lower.contains("\"ispro\":false")
                || lower.contains("\"status\":\"inactive\"")
                || lower.contains("\"status\":\"expired\"")
                || lower.contains("\"tier\":\"free\"")
                || lower.contains("\"plan\":\"free\"");
    }

    private static String normalizedKey(String key) {
        if (key == null) {
            return "";
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("flutter.")) {
            return lower.substring("flutter.".length());
        }
        return lower;
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

    private static void log(String message) {
        Log.i(MainHook.TAG, message);
        XposedBridge.log(message);
    }
}
