package com.afusekt.lsp.hook;

import android.os.Handler;
import android.os.Looper;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * ZoT-style Pigeon billing inject for CapyPlayer 1.1.3 (obfuscated v7.*).
 */
final class CapyPlayerPurchaseHooks {

    private static final String TAG = MainHook.TAG + ":CapyPlayer";
    private static final String PRO_PRODUCT = "capyplayer.pro.year";
    private static final String FAKE_ORDER_ID = "GPA.1337-7331-CAPY-0001";
    private static final String PACKAGE_NAME = "com.feifeiduck.capyplayer";
    private static final long[] RETRY_DELAYS_MS = {250L, 500L, 1000L, 2000L, 4000L, 8000L};

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CLASS_LOAD_PROBE = new AtomicBoolean(false);
    private static final AtomicBoolean RETRY_STARTED = new AtomicBoolean(false);
    private static final Pattern SKU_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,}");

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CapyPlayerPurchaseHooks() {
    }

    static void install(ClassLoader cl) {
        hookClassLoaderProbe(cl);
        if (tryInstallPurchaseHook(cl)) {
            return;
        }
        startRetryThread(cl);
    }

    private static void hookClassLoaderProbe(final ClassLoader cl) {
        if (!CLASS_LOAD_PROBE.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null) {
                                return;
                            }
                            if ("v7.h".equals(param.args[0])) {
                                tryInstallPurchaseHook(cl);
                            }
                        }
                    });
        } catch (Throwable t) {
            CLASS_LOAD_PROBE.set(false);
            log("ClassLoader probe skipped: " + t.getMessage());
        }
    }

    private static void startRetryThread(final ClassLoader cl) {
        if (!RETRY_STARTED.compareAndSet(false, true)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (long delay : RETRY_DELAYS_MS) {
                    if (HOOK_INSTALLED.get()) {
                        return;
                    }
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    if (tryInstallPurchaseHook(cl)) {
                        return;
                    }
                }
                log("Pigeon purchase API not found after retries");
            }
        }, "ZoeVIP-CapyIapRetry").start();
    }

    private static boolean tryInstallPurchaseHook(ClassLoader cl) {
        if (HOOK_INSTALLED.get()) {
            return true;
        }
        Class<?> apiClass = loadClass(cl, "v7.h");
        if (apiClass == null) {
            return false;
        }
        Method launchBillingFlow = findLaunchBillingFlowMethod(apiClass, cl);
        if (launchBillingFlow == null) {
            log("Pigeon launchBillingFlow bridge not found on " + apiClass.getName());
            return false;
        }
        launchBillingFlow.setAccessible(true);
        XposedBridge.hookMethod(launchBillingFlow, new LaunchBillingFlowHook(cl));
        hookApiConstructor(cl, apiClass);
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            log("CapyPlayer 1.1.3 Pigeon purchase hook installed");
        }
        return true;
    }

    private static Method findLaunchBillingFlowMethod(Class<?> apiClass, ClassLoader cl) {
        Class<?> billingResultClass = loadClass(cl, "v7.p");
        if (billingResultClass == null) {
            return null;
        }
        for (Method method : apiClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 1
                    && billingResultClass.equals(method.getReturnType())
                    && "c".equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private static void hookApiConstructor(final ClassLoader cl, Class<?> apiClass) {
        try {
            XposedBridge.hookAllConstructors(apiClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    schedulePurchaseDelivery(cl, param.thisObject, PRO_PRODUCT, 800L);
                }
            });
        } catch (Throwable t) {
            log("purchase API constructor hook skipped: " + t.getMessage());
        }
    }

    private static final class LaunchBillingFlowHook extends XC_MethodHook {
        private final ClassLoader classLoader;

        LaunchBillingFlowHook(ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String productId = extractProductId(param.args[0]);
            Object billingResult = buildOkBillingResult(classLoader);
            if (billingResult == null) {
                log("unable to build billing result; using original flow");
                return;
            }
            schedulePurchaseDelivery(classLoader, param.thisObject, productId, 150L);
            param.setResult(billingResult);
            log("Pigeon launchBillingFlow('" + productId + "') -> OK");
        }
    }

    private static String extractProductId(Object request) {
        if (request == null) {
            return PRO_PRODUCT;
        }
        try {
            for (Field field : request.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(request);
                if (!(value instanceof String)) {
                    continue;
                }
                String str = (String) value;
                if (!str.isEmpty() && SKU_PATTERN.matcher(str).matches()) {
                    return str;
                }
            }
        } catch (Throwable ignored) {
        }
        return PRO_PRODUCT;
    }

    private static void schedulePurchaseDelivery(final ClassLoader cl, final Object apiInstance,
            final String productId, long delayMs) {
        if (apiInstance == null) {
            return;
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    deliverPurchaseUpdate(cl, apiInstance, productId);
                    log("delivered purchase stream update for '" + productId + "'");
                } catch (Throwable t) {
                    log("purchase stream delivery failed: " + t.getMessage());
                }
            }
        }, delayMs);
    }

    private static void deliverPurchaseUpdate(ClassLoader cl, Object apiInstance, String productId)
            throws Exception {
        Object callbackApi = getFieldByName(apiInstance, "f19943P");
        if (callbackApi == null) {
            callbackApi = findFieldByTypeName(apiInstance, "v7.e");
        }
        if (callbackApi == null) {
            throw new IllegalStateException("callback API holder missing");
        }

        Class<?> callbackClass = loadClass(cl, "v7.e");
        if (callbackClass == null || !callbackClass.isInstance(callbackApi)) {
            throw new IllegalStateException("unexpected callback API: " + callbackApi.getClass().getName());
        }

        Object messenger = getFieldByName(callbackApi, "a");
        if (messenger == null) {
            messenger = findFieldByTypeName(callbackApi, "io.flutter.plugin.common.BinaryMessenger");
        }
        if (messenger == null) {
            throw new IllegalStateException("BinaryMessenger missing");
        }

        Class<?> purchaseClass = loadClass(cl, "v7.A");
        Class<?> responseClass = loadClass(cl, "v7.E");
        Class<?> stateClass = loadClass(cl, "v7.D");
        Class<?> accountClass = loadClass(cl, "v7.i");
        if (purchaseClass == null || responseClass == null || stateClass == null || accountClass == null) {
            throw new IllegalStateException("purchase pigeon types missing");
        }

        Object purchasedState = findEnumConstant(stateClass, "PURCHASED");
        if (purchasedState == null) {
            throw new IllegalStateException("PURCHASED enum missing");
        }

        long now = System.currentTimeMillis();
        String token = "zoevip-capy-" + productId + "-" + now;
        String json = "{\"productId\":\"" + productId + "\",\"purchaseToken\":\"" + token
                + "\",\"purchaseState\":0,\"acknowledged\":true}";
        Object accountIds = newInstanceMatching(accountClass, null, null);
        Object purchase = newInstanceMatching(purchaseClass,
                FAKE_ORDER_ID,
                PACKAGE_NAME,
                now,
                token,
                "zoevip",
                Collections.singletonList(productId),
                Boolean.FALSE,
                json,
                "zoevip",
                Boolean.TRUE,
                1L,
                purchasedState,
                accountIds,
                null);
        if (purchase == null) {
            throw new IllegalStateException("failed to build PlatformPurchase");
        }

        Object billingResult = buildOkBillingResult(cl);
        Object response = newInstanceMatching(responseClass, billingResult,
                Collections.singletonList(purchase));
        if (response == null) {
            throw new IllegalStateException("failed to build PlatformPurchasesResponse");
        }

        Class<?> codecHostClass = loadClass(cl, "v7.b");
        if (codecHostClass == null) {
            throw new IllegalStateException("v7.b codec host missing");
        }
        Method codecMethod = codecHostClass.getDeclaredMethod("a");
        codecMethod.setAccessible(true);
        Object codec = codecMethod.invoke(null);

        Class<?> channelClass = loadClass(cl, "e9.i");
        if (channelClass == null) {
            throw new IllegalStateException("BasicMessageChannel class missing");
        }

        Constructor<?> channelCtor = findChannelConstructor(channelClass, messenger.getClass());
        if (channelCtor == null) {
            throw new IllegalStateException("BasicMessageChannel constructor missing");
        }
        channelCtor.setAccessible(true);
        Object channel = channelCtor.newInstance(
                messenger,
                "dev.flutter.pigeon.in_app_purchase_android.InAppPurchaseCallbackApi.onPurchasesUpdated",
                codec,
                null,
                15);

        Method sendMethod = findSendMethod(channel.getClass());
        if (sendMethod == null) {
            throw new IllegalStateException("BasicMessageChannel send method missing");
        }
        sendMethod.setAccessible(true);
        Class<?> replyClass = sendMethod.getParameterTypes()[1];
        Object replyProxy = Proxy.newProxyInstance(replyClass.getClassLoader(),
                new Class<?>[]{replyClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        sendMethod.invoke(channel, Collections.singletonList(response), replyProxy);
    }

    private static Object buildOkBillingResult(ClassLoader cl) {
        Class<?> resultClass = loadClass(cl, "v7.p");
        Class<?> codeClass = loadClass(cl, "v7.o");
        if (resultClass == null || codeClass == null) {
            return null;
        }
        Object ok = findEnumConstant(codeClass, "OK");
        if (ok == null) {
            return null;
        }
        return newInstanceMatching(resultClass, ok, "", 0L);
    }

    private static Constructor<?> findChannelConstructor(Class<?> channelClass, Class<?> messengerClass) {
        for (Constructor<?> ctor : channelClass.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 5 && ctor.getParameterTypes()[0].isAssignableFrom(messengerClass)) {
                return ctor;
            }
        }
        return null;
    }

    private static Method findSendMethod(Class<?> channelClass) {
        for (Method method : channelClass.getMethods()) {
            if ("y".equals(method.getName()) && method.getParameterCount() == 2) {
                Class<?>[] params = method.getParameterTypes();
                if (params[1].isInterface()) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object findEnumConstant(Class<?> enumClass, String name) {
        Object[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Object constant : constants) {
            if (constant instanceof Enum && name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return null;
    }

    private static Object newInstanceMatching(Class<?> cls, Object... args) {
        Constructor<?> match = null;
        outer:
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (ctor.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] paramTypes = ctor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                Class<?> paramType = paramTypes[i];
                if (arg == null) {
                    if (paramType.isPrimitive()) {
                        continue outer;
                    }
                    continue;
                }
                Class<?> argClass = arg.getClass();
                if (paramType.isPrimitive()) {
                    if (!wrap(paramType).isInstance(arg)) {
                        continue outer;
                    }
                } else if (!paramType.isAssignableFrom(argClass)) {
                    continue outer;
                }
            }
            match = ctor;
            break;
        }
        if (match == null) {
            return null;
        }
        try {
            match.setAccessible(true);
            return match.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> wrap(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return primitive;
    }

    private static Class<?> loadClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getFieldByName(Object obj, String name) {
        try {
            for (Field field : obj.getClass().getDeclaredFields()) {
                if (name.equals(field.getName())) {
                    field.setAccessible(true);
                    return field.get(obj);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findFieldByTypeName(Object obj, String typeName) {
        try {
            ClassLoader cl = obj.getClass().getClassLoader();
            Class<?> type = Class.forName(typeName, false, cl);
            for (Field field : obj.getClass().getDeclaredFields()) {
                if (type.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field.get(obj);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
