package com.afusekt.lsp.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ZoT-style triggers: when libapp.so loads, push entitlement repeatedly and
 * ensure billing query returns a lifetime purchase.
 */
final class CapyPlayerLoadHooks {

    private static final String TAG = MainHook.TAG + ":CapyPlayer";
    private static final AtomicBoolean LOAD_LIBRARY_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean BILLING_QUERY_HOOKED = new AtomicBoolean(false);

    private static final String FAKE_ORDER_ID = "GPA.1337-7331-CAPY-0001";
    private static final String PRO_PRODUCT = "capyplayer.pro.year";

    private CapyPlayerLoadHooks() {
    }

    static String fakePurchaseBase64() {
        long now = System.currentTimeMillis();
        String json = "{\"orderId\":\"" + FAKE_ORDER_ID + "\","
                + "\"packageName\":\"com.feifeiduck.capyplayer\","
                + "\"productId\":\"" + PRO_PRODUCT + "\","
                + "\"purchaseTime\":" + now + ","
                + "\"purchaseState\":1,"
                + "\"purchaseToken\":\"zoevip.pro.token\","
                + "\"acknowledged\":true,"
                + "\"autoRenewing\":false}";
        return android.util.Base64.encodeToString(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
    }

    static void installRuntimeLoadHook(final Context appContext, final ClassLoader cl) {
        if (!LOAD_LIBRARY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Runtime.class,
                    "loadLibrary0",
                    ClassLoader.class,
                    Class.class,
                    String.class,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object libArg = param.args[2];
                            if (libArg == null) {
                                return;
                            }
                            String lib = String.valueOf(libArg);
                            if (!"libapp.so".equals(lib) && !lib.endsWith("/libapp.so")) {
                                return;
                            }
                            log("libapp.so loaded -> scheduling entitlement push");
                            scheduleEntitlementPush(appContext, 6);
                            CapyPlayerNativePatch.applyWhenLibAppLoaded();
                            CapyPlayerNativePatch.scheduleRetry();
                            CapyPlayerHooks.onLibAppLoaded(cl);
                        }
                    }
            );
            log("Runtime.loadLibrary0 hooked");
        } catch (Throwable t) {
            LOAD_LIBRARY_HOOKED.set(false);
            log("Runtime.loadLibrary0 hook skipped: " + t.getMessage());
        }
    }

    static void scheduleEntitlementPush(final Context context, int times) {
        if (context == null || times <= 0) {
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < times; i++) {
            final int attempt = i;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    CapyPlayerHooks.seedProSubscription(context);
                    log("scheduled entitlement DataStore push (" + (attempt + 1) + "/" + times + ")");
                }
            }, 500L + attempt * 800L);
        }
    }

    static void installBillingQueryHook(ClassLoader cl, String lifetimeProduct) {
        if (!BILLING_QUERY_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.billingclient.api.PurchasesResponseListener", cl);
            XposedBridge.hookAllMethods(listener, "onQueryPurchasesResponse",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length < 2) {
                                return;
                            }
                            try {
                                Object purchase = newLifetimePurchase(cl, lifetimeProduct);
                                param.args[1] = Collections.singletonList(purchase);
                                log("queryPurchasesAsync -> injected lifetime purchase");
                            } catch (Throwable t) {
                                log("queryPurchasesAsync inject failed: " + t.getMessage());
                            }
                        }
                    });
            log("PurchasesResponseListener hooked");
        } catch (Throwable t) {
            BILLING_QUERY_HOOKED.set(false);
            log("PurchasesResponseListener hook skipped: " + t.getMessage());
        }
    }

    private static Object newLifetimePurchase(ClassLoader cl, String productId) throws Exception {
        long now = System.currentTimeMillis();
        String json = "{\"orderId\":\"" + FAKE_ORDER_ID + "\","
                + "\"packageName\":\"com.feifeiduck.capyplayer\","
                + "\"productId\":\"" + productId + "\","
                + "\"purchaseTime\":" + now + ","
                + "\"purchaseState\":1,"
                + "\"purchaseToken\":\"zoevip.pro.token\","
                + "\"acknowledged\":true,"
                + "\"autoRenewing\":false}";
        Class<?> purchaseClass = XposedHelpers.findClass(
                "com.android.billingclient.api.Purchase", cl);
        Constructor<?> ctor = purchaseClass.getConstructor(String.class, String.class);
        return ctor.newInstance(json, "zoevip");
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
