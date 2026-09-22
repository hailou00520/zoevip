package com.afusekt.lsp.libxposed;

import android.os.Handler;
import android.os.Looper;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;

/**
 * ZoT-style Pigeon billing inject for CapyPlayer (libxposed path).
 */
final class LibCapyPlayerPurchaseHooks {

    private static final String TAG = ZoeIds.TAG + ":CapyIap";
    private static final String PRO_PRODUCT = "capyplayer.pro.lifetime";
    private static final String FAKE_ORDER_ID = "GPA.1337-7331-CAPY-0001";
    private static final String PACKAGE_NAME = "com.feifeiduck.capyplayer";
    private static final long[] RETRY_DELAYS_MS = {250L, 500L, 1000L, 2000L, 4000L, 8000L};
    private static final Pattern SKU_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,}");

    private static final AtomicBoolean HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean RETRY_STARTED = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Set<Object> API_INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private LibCapyPlayerPurchaseHooks() {
    }

    static void install(ZoeModule module, ClassLoader cl) {
        if (tryInstallPurchaseHook(module, cl)) {
            return;
        }
        startRetryThread(module, cl);
    }

    private static void startRetryThread(ZoeModule module, ClassLoader cl) {
        if (!RETRY_STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            for (long delay : RETRY_DELAYS_MS) {
                if (HOOK_INSTALLED.get()) {
                    return;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (tryInstallPurchaseHook(module, cl)) {
                    return;
                }
            }
            module.log(5, TAG, "Pigeon purchase API not found after retries");
        }, "ZoeVIP-CapyIapRetry");
        t.setDaemon(true);
        t.start();
    }

    private static boolean tryInstallPurchaseHook(ZoeModule module, ClassLoader cl) {
        if (HOOK_INSTALLED.get()) {
            return true;
        }
        PigeonProfile profile = detectProfile(cl);
        if (profile == null) {
            return false;
        }
        Class<?> apiClass = loadClass(cl, profile.apiClass);
        if (apiClass == null) {
            return false;
        }
        Method launchBillingFlow = findLaunchBillingFlowMethod(apiClass, cl, profile);
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        if (launchBillingFlow != null) {
            hookMethod(module, launchBillingFlow, mode, chain -> {
                String productId = extractProductId(chain.getArg(0));
                Object billingResult = buildOkBillingResult(cl, profile);
                if (billingResult == null) {
                    module.log(5, TAG, "unable to build billing result; using original flow");
                    return chain.proceed();
                }
                schedulePurchaseDelivery(module, cl, chain.getThisObject(), profile, productId, 150L);
                module.log(4, TAG, "Pigeon launchBillingFlow('" + productId + "') -> OK");
                return billingResult;
            });
        } else {
            module.log(5, TAG, "launchBillingFlow bridge not found on " + apiClass.getName()
                    + "; continuing with queryPurchases hooks");
        }
        int queryHooks = hookQueryPurchasesMethods(module, cl, apiClass, profile, mode);
        /* Unlock does not depend on IAP inject (fake tokens → rejectedReceipt).
         * Mark installed once cg3 is present so retries stop spamming. */
        if (launchBillingFlow == null && queryHooks <= 0) {
            if (HOOK_INSTALLED.compareAndSet(false, true)) {
                module.log(5, TAG, profile.label
                        + " Pigeon present but billing bridges missing; skip IAP inject");
            }
            return true;
        }
        hookConstructors(module, apiClass, mode, chain -> {
            Object result = chain.proceed();
            Object instance = chain.getThisObject();
            if (instance != null) {
                API_INSTANCES.add(instance);
            }
            return result;
        });
        if (HOOK_INSTALLED.compareAndSet(false, true)) {
            module.log(4, TAG, profile.label + " Pigeon purchase hook installed (query="
                    + queryHooks + ")");
        }
        return true;
    }

    /** Return fake lifetime purchase from InAppPurchaseApi.queryPurchasesAsync. */
    private static int hookQueryPurchasesMethods(
            ZoeModule module,
            ClassLoader cl,
            Class<?> apiClass,
            PigeonProfile profile,
            XposedInterface.ExceptionMode mode
    ) {
        Class<?> responseClass = loadClass(cl, profile.responseClass);
        if (responseClass == null) {
            return 0;
        }
        int hooked = 0;
        for (Method method : apiClass.getDeclaredMethods()) {
            Class<?> returnType = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();
            boolean returnsResponse = responseClass.equals(returnType);
            boolean resultCallback = false;
            if (!returnsResponse && params.length >= 1 && returnType == void.class) {
                Class<?> last = params[params.length - 1];
                if (last.isInterface()) {
                    // Pigeon Result<PlatformPurchasesResponse> callback
                    for (Method m : last.getMethods()) {
                        if ("success".equals(m.getName()) && m.getParameterCount() == 1
                                && responseClass.isAssignableFrom(m.getParameterTypes()[0])) {
                            resultCallback = true;
                            break;
                        }
                    }
                }
            }
            if (!returnsResponse && !resultCallback) {
                continue;
            }
            if (params.length > 2) {
                continue;
            }
            final boolean useCallback = resultCallback;
            hookMethod(module, method, mode, chain -> {
                try {
                    Object response = buildPurchasesResponse(cl, profile, PRO_PRODUCT);
                    if (response != null) {
                        if (useCallback) {
                            Object[] args = chain.getArgs().toArray();
                            Object result = args[args.length - 1];
                            Method success = findSuccessMethod(result.getClass(), responseClass);
                            if (success != null) {
                                success.invoke(result, response);
                                module.log(4, TAG, "Pigeon " + method.getName()
                                        + " callback -> injected lifetime purchase");
                                return null;
                            }
                        } else {
                            module.log(4, TAG, "Pigeon " + method.getName()
                                    + " -> injected lifetime purchase");
                            return response;
                        }
                    }
                } catch (Throwable t) {
                    module.log(5, TAG, "queryPurchases inject failed: " + t.getMessage());
                }
                return chain.proceed();
            });
            hooked++;
        }
        return hooked;
    }

    private static Method findSuccessMethod(Class<?> resultClass, Class<?> responseClass) {
        for (Method m : resultClass.getMethods()) {
            if (!"success".equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            if (m.getParameterTypes()[0].isAssignableFrom(responseClass)
                    || responseClass.isAssignableFrom(m.getParameterTypes()[0])) {
                return m;
            }
        }
        return null;
    }

    private static Object buildPurchasesResponse(
            ClassLoader cl, PigeonProfile profile, String productId
    ) throws Exception {
        Class<?> purchaseClass = loadClass(cl, profile.purchaseClass);
        Class<?> responseClass = loadClass(cl, profile.responseClass);
        Class<?> stateClass = loadClass(cl, profile.stateClass);
        Class<?> accountClass = loadClass(cl, profile.accountClass);
        if (purchaseClass == null || responseClass == null
                || stateClass == null || accountClass == null) {
            return null;
        }
        Object purchasedState = findEnumConstant(stateClass, "PURCHASED");
        if (purchasedState == null) {
            return null;
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
            return null;
        }
        Object billingResult = buildOkBillingResult(cl, profile);
        return newInstanceMatching(responseClass, billingResult,
                Collections.singletonList(purchase));
    }

    /** After login: reseed only; do not re-inject fake tokens. */
    static void redeliverAfterLogin(ZoeModule module, ClassLoader cl) {
        module.log(4, TAG, "post-login: skip purchase stream (avoid rejectedReceipt)");
    }

    private static PigeonProfile detectProfile(ClassLoader cl) {
        if (loadClass(cl, "cg3") != null) {
            return PigeonProfile.v115();
        }
        if (loadClass(cl, "v7.h") != null) {
            return PigeonProfile.v113();
        }
        return null;
    }

    private static Method findLaunchBillingFlowMethod(
            Class<?> apiClass, ClassLoader cl, PigeonProfile profile
    ) {
        Class<?> billingResultClass = loadClass(cl, profile.billingResultClass);
        if (billingResultClass == null) {
            return null;
        }
        Method fallback = null;
        for (Method method : apiClass.getDeclaredMethods()) {
            if (method.getParameterCount() != 1
                    || !billingResultClass.equals(method.getReturnType())) {
                continue;
            }
            if ("c".equals(method.getName())) {
                return method;
            }
            // Obfuscation may rename the bridge; keep first 1-arg billingResult method.
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static void schedulePurchaseDelivery(
            ZoeModule module,
            ClassLoader cl,
            Object apiInstance,
            PigeonProfile profile,
            String productId,
            long delayMs
    ) {
        if (apiInstance == null) {
            return;
        }
        MAIN_HANDLER.postDelayed(() -> {
            try {
                deliverPurchaseUpdate(module, cl, apiInstance, profile, productId);
                module.log(4, TAG, "delivered purchase stream update for '" + productId + "'");
            } catch (Throwable t) {
                module.log(5, TAG, "purchase stream delivery failed: " + t.getMessage());
            }
        }, delayMs);
    }

    private static String extractProductId(Object request) {
        if (request == null) {
            return PRO_PRODUCT;
        }
        try {
            for (Field field : request.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(request);
                if (!(value instanceof String str) || str.isEmpty()) {
                    continue;
                }
                if (SKU_PATTERN.matcher(str).matches()) {
                    return str;
                }
            }
        } catch (Throwable ignored) {
        }
        return PRO_PRODUCT;
    }

    private static void deliverPurchaseUpdate(
            ZoeModule module,
            ClassLoader cl,
            Object apiInstance,
            PigeonProfile profile,
            String productId
    ) throws Exception {
        Object messenger = resolveMessenger(cl, apiInstance, profile);
        if (messenger == null) {
            throw new IllegalStateException("BinaryMessenger missing");
        }

        Class<?> purchaseClass = loadClass(cl, profile.purchaseClass);
        Class<?> responseClass = loadClass(cl, profile.responseClass);
        Class<?> stateClass = loadClass(cl, profile.stateClass);
        Class<?> accountClass = loadClass(cl, profile.accountClass);
        if (purchaseClass == null || responseClass == null
                || stateClass == null || accountClass == null) {
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

        Object billingResult = buildOkBillingResult(cl, profile);
        Object response = newInstanceMatching(responseClass, billingResult,
                Collections.singletonList(purchase));
        if (response == null) {
            throw new IllegalStateException("failed to build PlatformPurchasesResponse");
        }

        Class<?> codecHostClass = loadClass(cl, profile.codecHostClass);
        if (codecHostClass == null) {
            throw new IllegalStateException(profile.codecHostClass + " codec host missing");
        }
        Method codecMethod = codecHostClass.getDeclaredMethod("a");
        codecMethod.setAccessible(true);
        Object codec = codecMethod.invoke(null);

        Class<?> channelClass = loadClass(cl, profile.channelClass);
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
                profile.channelKindArg);

        Method sendMethod = findSendMethod(channel.getClass(), profile.sendMethodName);
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

    private static Object resolveMessenger(ClassLoader cl, Object apiInstance, PigeonProfile profile) {
        if (profile.callbackFieldName != null) {
            Object callbackApi = getFieldByName(apiInstance, profile.callbackFieldName);
            if (callbackApi == null && profile.callbackHolderClass != null) {
                callbackApi = findFieldByTypeName(apiInstance, profile.callbackHolderClass);
            }
            if (callbackApi != null) {
                Object messenger = getFieldByName(callbackApi, "a");
                if (messenger == null) {
                    messenger = findFieldByTypeName(callbackApi,
                            "io.flutter.plugin.common.BinaryMessenger");
                }
                if (messenger != null) {
                    return messenger;
                }
            }
        }
        return findFieldByTypeName(apiInstance, "io.flutter.plugin.common.BinaryMessenger");
    }

    private static Object buildOkBillingResult(ClassLoader cl, PigeonProfile profile) {
        Class<?> resultClass = loadClass(cl, profile.billingResultClass);
        Class<?> codeClass = loadClass(cl, profile.billingCodeClass);
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
            if (ctor.getParameterCount() == 5
                    && ctor.getParameterTypes()[0].isAssignableFrom(messengerClass)) {
                return ctor;
            }
        }
        return null;
    }

    private static Method findSendMethod(Class<?> channelClass, String... names) {
        for (String name : names) {
            for (Method method : channelClass.getMethods()) {
                if (name.equals(method.getName()) && method.getParameterCount() == 2) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params[1].isInterface()) {
                        return method;
                    }
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
            if (constant instanceof Enum<?> e && name.equals(e.name())) {
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

    private static void hookConstructors(
            ZoeModule module,
            Class<?> cls,
            XposedInterface.ExceptionMode mode,
            XposedInterface.Hooker hooker
    ) {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            try {
                ctor.setAccessible(true);
                module.hook(ctor).setExceptionMode(mode).intercept(hooker);
            } catch (Throwable t) {
                module.log(5, TAG, "ctor hook failed: " + t.getMessage());
            }
        }
    }

    private static final class PigeonProfile {
        final String label;
        final String apiClass;
        final String billingResultClass;
        final String billingCodeClass;
        final String purchaseClass;
        final String responseClass;
        final String stateClass;
        final String accountClass;
        final String codecHostClass;
        final String channelClass;
        final String callbackHolderClass;
        final String callbackFieldName;
        final String sendMethodName;
        final int channelKindArg;

        private PigeonProfile(String label, String apiClass, String billingResultClass,
                String billingCodeClass, String purchaseClass, String responseClass,
                String stateClass, String accountClass, String codecHostClass, String channelClass,
                String callbackHolderClass, String callbackFieldName, String sendMethodName,
                int channelKindArg) {
            this.label = label;
            this.apiClass = apiClass;
            this.billingResultClass = billingResultClass;
            this.billingCodeClass = billingCodeClass;
            this.purchaseClass = purchaseClass;
            this.responseClass = responseClass;
            this.stateClass = stateClass;
            this.accountClass = accountClass;
            this.codecHostClass = codecHostClass;
            this.channelClass = channelClass;
            this.callbackHolderClass = callbackHolderClass;
            this.callbackFieldName = callbackFieldName;
            this.sendMethodName = sendMethodName;
            this.channelKindArg = channelKindArg;
        }

        static PigeonProfile v115() {
            return new PigeonProfile(
                    "1.1.5",
                    "cg3",
                    "n04",
                    "m04",
                    "m14",
                    "q14",
                    "p14",
                    "g04",
                    "ji2",
                    "kt4",
                    "mi2",
                    "R",
                    "w",
                    5);
        }

        static PigeonProfile v113() {
            return new PigeonProfile(
                    "1.1.3",
                    "v7.h",
                    "v7.p",
                    "v7.o",
                    "v7.A",
                    "v7.E",
                    "v7.D",
                    "v7.i",
                    "v7.b",
                    "e9.i",
                    "v7.e",
                    "f19943P",
                    "y",
                    15);
        }
    }
}
