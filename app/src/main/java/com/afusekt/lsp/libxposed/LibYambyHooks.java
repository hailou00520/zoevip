package com.afusekt.lsp.libxposed;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Base64;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Yamby ({@code com.hush.yamby}) Lifetime Pro unlock.
 * Strategy ported from Zot: MMKV pro-key spoof, BillingClient fake purchases,
 * ProFragment native hooks, time-bomb defuse, proxy billing suppression.
 */
public final class LibYambyHooks {

    private static final String TAG = ZoeIds.TAG + ":Yamby";

    private static final Pattern PRO_KEY = Pattern.compile(
            "(?i)pro|vip|premium|subscrib|purchase|unlock|upgrade|license|entitled|paid|lifetime|member");
    private static final Pattern NEG_KEY = Pattern.compile(
            "(?i)failedTime|failed|failCount|retry|errorTime|lastError|expired?|invalid|cancel|unauthoriz|notPro|noPro");

    private static final List<String> PRODUCT_IDS = Arrays.asList(
            "com.hush.yamby.pro.lifetime",
            "lifetime_pro",
            "lifetime",
            "pro_lifetime",
            "yamby_pro_lifetime",
            "yamby.pro"
    );

    /** Stable obfuscated names across Yamby 2.x (verified on 2.0.5.5). */
    private static final String CLS_PRO_FRAGMENT = "cd.ۦۨۤ۟ۡ";
    private static final String CLS_BILLING_CLIENT = "x4.ۥۖۗ۬۠ۤۜ";
    private static final String CLS_BILLING_RESULT = "x4.ۥۖۙ۟ۙ";
    private static final String CLS_SC_EVENT = "sc.ۥۖۘۘۖ۫";
    private static final String CLS_BILLING_LISTENER = "sc.ۥۖ۬ۥۛۦ";
    private static final String CLS_TIME_BOMB = "pc.ۥۘ۬ۤۚۧ";

    private static final String FAKE_SIGNATURE = Base64.encodeToString(
            "zoe-yamby-fake-signature".getBytes(), Base64.NO_WRAP);

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PRO_FRAGMENT_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean TIME_BOMB_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean PROXY_BILLING_LOGGED = new AtomicBoolean(false);

    private LibYambyHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        tryInstall(module, param.getClassLoader(), "package-ready");
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName("com.tencent.mmkv.MMKV", false, cl);
        } catch (Throwable t) {
            module.log(5, TAG, "hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, TAG, "hooks installing (" + source + ")");
        try {
            installHooks(module, cl);
            module.log(4, TAG, "hooks applied");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, TAG, "install failed: " + t.getMessage(), t);
        }
    }

    private static void installHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        hookProxyBilling(module, mode);
        hookMmkv(module, cl, mode);
        hookBillingClient(module, cl, mode);
        hookBillingListener(module, cl, mode);
        hookProFragment(module, cl, mode);
        hookTimeBomb(module, cl, mode);
        hookScEventConstructor(module, cl, mode);
    }

    private static void hookMmkv(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> mmkv = findClass(cl, "com.tencent.mmkv.MMKV");
        if (mmkv == null) {
            return;
        }
        for (Method method : mmkv.getDeclaredMethods()) {
            if (!isDecodeMethod(method)) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class && method.getParameterCount() == 3
                    && method.getParameterTypes()[2] == boolean.class) {
                hookMethod(module, method, mode, chain -> {
                    String key = stringArg(chain, 1);
                    boolean original = false;
                    try {
                        Object result = chain.proceed();
                        if (result instanceof Boolean b) {
                            original = b;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (key != null && PRO_KEY.matcher(key).find()) {
                        module.log(4, TAG, "MMKV.decodeBool('" + key + "') -> true (was " + original + ")");
                        return true;
                    }
                    return original;
                });
            } else if (returnType == int.class && method.getParameterCount() == 3
                    && method.getParameterTypes()[2] == int.class) {
                hookMethod(module, method, mode, chain -> {
                    String key = stringArg(chain, 1);
                    int original = 0;
                    try {
                        Object result = chain.proceed();
                        if (result instanceof Integer i) {
                            original = i;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (key != null && PRO_KEY.matcher(key).find()) {
                        module.log(4, TAG, "MMKV.decodeInt('" + key + "') -> 1 (was " + original + ")");
                        return 1;
                    }
                    return original;
                });
            } else if (returnType == long.class && method.getParameterCount() == 3
                    && method.getParameterTypes()[2] == long.class) {
                hookMethod(module, method, mode, chain -> {
                    String key = stringArg(chain, 1);
                    long original = 0L;
                    try {
                        Object result = chain.proceed();
                        if (result instanceof Long l) {
                            original = l;
                        }
                    } catch (Throwable ignored) {
                    }
                    if (key != null && NEG_KEY.matcher(key).find()) {
                        module.log(4, TAG, "MMKV.decodeLong('" + key + "') -> 0 (was " + original + ")");
                        return 0L;
                    }
                    return original;
                });
            }
        }
        for (Method method : mmkv.getDeclaredMethods()) {
            if (!isDecodeMethod(method)) {
                continue;
            }
            if (method.getReturnType() != boolean.class) {
                continue;
            }
            int pc = method.getParameterCount();
            if (pc < 3 || pc > 4) {
                continue;
            }
            hookMethod(module, method, mode, chain -> {
                String key = stringArg(chain, 1);
                boolean original = false;
                try {
                    Object result = chain.proceed();
                    if (result instanceof Boolean b) {
                        original = b;
                    }
                } catch (Throwable ignored) {
                }
                if (key != null && !original && PRO_KEY.matcher(key).find()) {
                    module.log(4, TAG, "MMKV." + method.getName() + "('" + key + "') forced contains=true");
                    return true;
                }
                return original;
            });
        }
        for (Method method : mmkv.getDeclaredMethods()) {
            if (!isDecodeMethod(method)) {
                continue;
            }
            if (method.getReturnType() == void.class && method.getParameterCount() >= 2) {
                hookMethod(module, method, mode, chain -> {
                    String key = stringArg(chain, 1);
                    if (key != null && PRO_KEY.matcher(key).find()) {
                        module.log(5, TAG, "MMKV." + method.getName() + "('" + key + "') BLOCKED");
                        return null;
                    }
                    return chain.proceed();
                });
            }
        }
    }

    private static void hookBillingClient(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> purchaseClass = findClass(cl, "com.android.billingclient.api.Purchase");
        Class<?> billingClient = findClass(cl, CLS_BILLING_CLIENT);
        if (purchaseClass == null || billingClient == null) {
            module.log(5, TAG, "BillingClient impl not found; queryPurchases hook skipped");
            return;
        }
        for (Method method : billingClient.getDeclaredMethods()) {
            if (method.getParameterCount() != 2
                    || method.getParameterTypes()[0] != String.class
                    || method.getReturnType() != void.class
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?> listenerType = method.getParameterTypes()[1];
            Package pkg = listenerType.getPackage();
            String pkgName = pkg != null ? pkg.getName() : "";
            if (!pkgName.startsWith("sc")) {
                boolean hasNative = false;
                for (Method m : listenerType.getDeclaredMethods()) {
                    if (Modifier.isNative(m.getModifiers())) {
                        hasNative = true;
                        break;
                    }
                }
                if (!hasNative) {
                    continue;
                }
            }
            hookMethod(module, method, mode, chain -> {
                String type = stringArg(chain, 0);
                Object listener = chain.getArg(1);
                module.log(4, TAG, "BillingClient." + method.getName()
                        + "(type=" + type + ", listener=" + listenerType.getName() + ")");
                chain.proceed();
                spoofBillingListener(module, listener, purchaseClass);
                return null;
            });
            break;
        }
    }

    private static void hookBillingListener(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> purchaseClass = findClass(cl, "com.android.billingclient.api.Purchase");
        Class<?> billingResult = findClass(cl, CLS_BILLING_RESULT);
        Class<?> listenerClass = findClass(cl, CLS_BILLING_LISTENER);
        if (purchaseClass == null || billingResult == null || listenerClass == null) {
            return;
        }
        for (Method method : listenerClass.getDeclaredMethods()) {
            if (!Modifier.isNative(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 2
                    || method.getReturnType() != void.class
                    || !method.getParameterTypes()[0].isAssignableFrom(billingResult)
                    || method.getParameterTypes()[1] != List.class) {
                continue;
            }
            hookMethod(module, method, mode, chain -> {
                Object billingResultArg = chain.getArg(0);
                Object listArg = chain.getArg(1);
                List<?> list = listArg instanceof List<?> l ? l : null;
                patchBillingResultFields(billingResultArg);
                if (list == null || list.isEmpty()) {
                    List<Object> fake = buildPurchases(purchaseClass);
                    if (!fake.isEmpty()) {
                        list = fake;
                    } else if (list == null) {
                        list = Collections.emptyList();
                    }
                }
                module.log(4, TAG, listenerClass.getSimpleName() + "." + method.getName()
                        + ": forced SUCCESS, list=" + list.size());
                return chain.proceed(new Object[]{billingResultArg, list});
            });
        }
    }

    private static void hookProFragment(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> proFragment = findClass(cl, CLS_PRO_FRAGMENT);
        if (proFragment == null) {
            module.log(5, TAG, "ProFragment not found");
            return;
        }
        for (Method method : proFragment.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == boolean.class) {
                hookMethod(module, method, mode, chain -> {
                    if (PRO_FRAGMENT_LOGGED.compareAndSet(false, true)) {
                        module.log(4, TAG, "ProFragment." + method.getName() + "() -> true");
                    }
                    return true;
                });
            }
        }
        Method staticUpdater = null;
        for (Method method : proFragment.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && Modifier.isNative(method.getModifiers())
                    && method.getParameterCount() == 3
                    && method.getParameterTypes()[0].isAssignableFrom(proFragment)
                    && method.getParameterTypes()[1] == boolean.class
                    && method.getParameterTypes()[2] == int.class
                    && method.getReturnType() == void.class) {
                staticUpdater = method;
                break;
            }
        }
        if (staticUpdater != null) {
            final Method updater = staticUpdater;
            hookMethod(module, updater, mode, chain -> {
                Object frag = chain.getArg(0);
                boolean isPro = booleanArg(chain, 1);
                int state = intArg(chain, 2, 0);
                int newState = state == 3 ? 2 : state;
                if (isPro && state == newState) {
                    return chain.proceed();
                }
                module.log(4, TAG, "ProFragment." + updater.getName()
                        + "(isPro: " + isPro + " -> true, state: " + state + " -> " + newState + ")");
                return chain.proceed(new Object[]{frag, true, newState});
            });
        } else {
            module.log(5, TAG, "ProFragment static updater not found");
        }
        for (Method method : proFragment.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getReturnType() == void.class
                    && !method.getParameterTypes()[0].isPrimitive()) {
                hookMethod(module, method, mode, chain -> {
                    module.log(4, TAG, "ProFragment." + method.getName() + "(event)");
                    return chain.proceed();
                });
            }
        }
        for (Method method : proFragment.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class
                    && method.getReturnType() == void.class) {
                hookMethod(module, method, mode, chain -> {
                    int arg = intArg(chain, 0, -1);
                    module.log(4, TAG, "ProFragment." + method.getName() + "(" + arg + ")");
                    return chain.proceed();
                });
            }
        }
        if (PRO_FRAGMENT_LOGGED.compareAndSet(false, true)) {
            module.log(4, TAG, "ProFragment hooks installed");
        }
    }

    private static void hookTimeBomb(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> timeBomb = findClass(cl, CLS_TIME_BOMB);
        if (timeBomb == null) {
            module.log(5, TAG, "time-bomb class not found");
            return;
        }
        for (Method method : timeBomb.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == long.class) {
                hookMethod(module, method, mode, chain -> {
                    if (TIME_BOMB_LOGGED.compareAndSet(false, true)) {
                        module.log(4, TAG, timeBomb.getSimpleName() + "." + method.getName()
                                + "() long native -> 0 (time bomb defused)");
                    }
                    return 0L;
                });
            }
        }
        for (Method method : timeBomb.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == long.class
                    && method.getReturnType() == void.class) {
                hookMethod(module, method, mode, chain -> null);
            }
        }
    }

    private static void hookScEventConstructor(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        Class<?> scEvent = findClass(cl, CLS_SC_EVENT);
        if (scEvent == null) {
            return;
        }
        for (Constructor<?> ctor : scEvent.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == boolean.class) {
                ctor.setAccessible(true);
                module.hook(ctor).setExceptionMode(mode).intercept(chain -> {
                    chain.proceed();
                    Object instance = chain.getThisObject();
                    if (instance != null) {
                        setBooleanField(instance, true);
                    }
                    return null;
                });
            }
        }
    }

    private static void hookProxyBilling(ZoeModule module, XposedInterface.ExceptionMode mode) {
        for (Method method : Activity.class.getDeclaredMethods()) {
            if (!"startIntentSenderForResult".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != IntentSender.class) {
                continue;
            }
            hookMethod(module, method, mode, chain -> {
                Object self = chain.getThisObject();
                if (self instanceof Activity activity && isProxyBillingActivity(activity)) {
                    spoofProxyBillingSuccess(module, activity);
                    return null;
                }
                return chain.proceed();
            });
        }
        for (String activityName : new String[]{
                "com.android.billingclient.api.ProxyBillingActivity",
                "com.android.billingclient.api.ProxyBillingActivityV2"
        }) {
            try {
                Class<?> proxy = Class.forName(activityName);
                Method onDestroy = proxy.getDeclaredMethod("onDestroy");
                hookMethod(module, onDestroy, mode, chain -> {
                    Object self = chain.getThisObject();
                    if (self != null) {
                        clearBooleanFields(self);
                    }
                    return chain.proceed();
                });
                Method onActivityResult = proxy.getDeclaredMethod(
                        "onActivityResult", int.class, int.class, Intent.class);
                hookMethod(module, onActivityResult, mode, chain -> {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity activity) {
                        spoofProxyBillingSuccess(module, activity);
                        return null;
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void spoofProxyBillingSuccess(ZoeModule module, Activity activity) {
        try {
            clearBooleanFields(activity);
            sendResultReceiverSuccess(activity);
            sendPurchaseBroadcast(activity);
            activity.finish();
            if (PROXY_BILLING_LOGGED.compareAndSet(false, true)) {
                module.log(4, TAG, "proxy billing suppressed & spoofed success");
            }
        } catch (Throwable t) {
            module.log(6, TAG, "spoofProxyBillingSuccess failed: " + t.getMessage());
        }
    }

    private static void sendPurchaseBroadcast(Activity activity) {
        Intent intent = new Intent("com.android.vending.billing.LOCAL_BROADCAST_PURCHASES_UPDATED");
        intent.setPackage(activity.getPackageName());
        intent.putExtra("RESPONSE_CODE", 0);
        intent.putExtra("DEBUG_MESSAGE", "");
        intent.putStringArrayListExtra("INAPP_PURCHASE_DATA_LIST", purchaseJsonList());
        intent.putStringArrayListExtra("INAPP_DATA_SIGNATURE_LIST", signatureList());
        intent.putStringArrayListExtra("INAPP_PURCHASE_ITEM_LIST", new ArrayList<>(PRODUCT_IDS));
        intent.putExtra("INTENT_SOURCE", "LAUNCH_BILLING_FLOW");
        try {
            activity.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void sendResultReceiverSuccess(Activity activity) {
        Bundle bundle = new Bundle();
        bundle.putInt("RESPONSE_CODE", 0);
        bundle.putString("DEBUG_MESSAGE", "");
        bundle.putStringArrayList("INAPP_PURCHASE_DATA_LIST", purchaseJsonList());
        bundle.putStringArrayList("INAPP_DATA_SIGNATURE_LIST", signatureList());
        bundle.putStringArrayList("INAPP_PURCHASE_ITEM_LIST", new ArrayList<>(PRODUCT_IDS));
        for (Field field : activity.getClass().getDeclaredFields()) {
            if (field.getType() != ResultReceiver.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof ResultReceiver receiver) {
                    receiver.send(-1, bundle);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void spoofBillingListener(ZoeModule module, Object listener, Class<?> purchaseClass) {
        if (listener == null) {
            return;
        }
        Class<?> listenerClass = listener.getClass();
        Method nativeCallback = null;
        for (Method method : listenerClass.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())
                    && method.getParameterCount() == 2
                    && method.getReturnType() == void.class) {
                nativeCallback = method;
                break;
            }
        }
        if (nativeCallback == null) {
            return;
        }
        Class<?> billingResultType = nativeCallback.getParameterTypes()[0];
        Object billingResult = buildBillingResult(billingResultType);
        List<Object> purchases = buildPurchases(purchaseClass);
        try {
            nativeCallback.setAccessible(true);
            nativeCallback.invoke(listener, billingResult, purchases);
            module.log(4, TAG, "spoofed listener " + listenerClass.getName()
                    + "." + nativeCallback.getName() + " with SUCCESS + " + purchases.size() + " purchases");
        } catch (Throwable t) {
            module.log(5, TAG, "listener spoof failed: " + t.getMessage());
        }
    }

    private static Object buildBillingResult(Class<?> billingResultType) {
        try {
            for (Constructor<?> ctor : billingResultType.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    args[i] = defaultValue(params[i]);
                }
                Object result = ctor.newInstance(args);
                patchBillingResultFields(result);
                return result;
            }
        } catch (Throwable ignored) {
        }
        for (Method method : billingResultType.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(null);
                if (billingResultType.isInstance(value)) {
                    patchBillingResultFields(value);
                    return value;
                }
                if (value != null) {
                    for (Method build : value.getClass().getDeclaredMethods()) {
                        if (build.getParameterCount() == 0
                                && billingResultType.isAssignableFrom(build.getReturnType())) {
                            build.setAccessible(true);
                            Object result = build.invoke(value);
                            patchBillingResultFields(result);
                            return result;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void patchBillingResultFields(Object billingResult) {
        if (billingResult == null) {
            return;
        }
        for (Field field : billingResult.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                if (field.getType() == int.class) {
                    field.setInt(billingResult, 0);
                } else if (field.getType() == String.class) {
                    field.set(billingResult, "");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<Object> buildPurchases(Class<?> purchaseClass) {
        Constructor<?> ctor = null;
        for (Constructor<?> candidate : purchaseClass.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 2) {
                Class<?>[] params = candidate.getParameterTypes();
                if (params[0] == String.class && params[1] == String.class) {
                    ctor = candidate;
                    break;
                }
            }
        }
        if (ctor == null) {
            return Collections.emptyList();
        }
        ctor.setAccessible(true);
        List<Object> purchases = new ArrayList<>();
        for (String productId : PRODUCT_IDS) {
            try {
                purchases.add(ctor.newInstance(buildPurchaseJson(productId), FAKE_SIGNATURE));
            } catch (Throwable ignored) {
            }
        }
        return purchases;
    }

    private static String buildPurchaseJson(String productId) {
        try {
            String token = "zoe-yamby-token-" + productId.hashCode();
            JSONObject json = new JSONObject();
            json.put("orderId", "GPA.1337-7331-0000-0001");
            json.put("packageName", ZoeIds.YAMBY_PACKAGE);
            json.put("productId", productId);
            json.put("productIds", new JSONArray().put(productId));
            json.put("purchaseToken", token);
            json.put("token", token);
            json.put("purchaseTime", System.currentTimeMillis());
            json.put("purchaseState", 0);
            json.put("acknowledged", true);
            json.put("autoRenewing", false);
            json.put("quantity", 1);
            json.put("developerPayload", "zoe");
            json.put("obfuscatedAccountId", "zoe");
            json.put("obfuscatedProfileId", "yamby");
            return json.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static ArrayList<String> purchaseJsonList() {
        ArrayList<String> list = new ArrayList<>();
        for (String productId : PRODUCT_IDS) {
            list.add(buildPurchaseJson(productId));
        }
        return list;
    }

    private static ArrayList<String> signatureList() {
        ArrayList<String> list = new ArrayList<>();
        for (int i = 0; i < PRODUCT_IDS.size(); i++) {
            list.add(FAKE_SIGNATURE);
        }
        return list;
    }

    private static boolean isProxyBillingActivity(Activity activity) {
        String name = activity.getClass().getName();
        return "com.android.billingclient.api.ProxyBillingActivity".equals(name)
                || "com.android.billingclient.api.ProxyBillingActivityV2".equals(name);
    }

    private static void clearBooleanFields(Object target) {
        for (Field field : target.getClass().getDeclaredFields()) {
            if (field.getType() != boolean.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.setBoolean(target, false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void setBooleanField(Object target, boolean value) {
        for (Field field : target.getClass().getDeclaredFields()) {
            if (field.getType() != boolean.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.setBoolean(target, value);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isDecodeMethod(Method method) {
        return method.getName().startsWith("decode");
    }

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static String stringArg(XposedInterface.Chain chain, int index) {
        Object arg = chain.getArg(index);
        return arg instanceof String ? (String) arg : null;
    }

    private static boolean booleanArg(XposedInterface.Chain chain, int index) {
        Object arg = chain.getArg(index);
        return arg instanceof Boolean ? (Boolean) arg : false;
    }

    private static int intArg(XposedInterface.Chain chain, int index, int fallback) {
        Object arg = chain.getArg(index);
        return arg instanceof Integer ? (Integer) arg : fallback;
    }

    private static void hookMethod(
            ZoeModule module,
            Method method,
            XposedInterface.ExceptionMode mode,
            XposedInterface.Hooker hooker
    ) {
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(hooker);
        } catch (Throwable t) {
            module.log(5, TAG, "hook failed " + method + ": " + t.getMessage());
        }
    }
}
