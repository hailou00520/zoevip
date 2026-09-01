package com.afusekt.lsp.libxposed;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/** VidHub PRO unlock via libxposed API (bypasses NetEase NIS legacy Xposed detection). */
public final class LibVidHubHooks {

    private static final long[] RETRY_DELAYS_MS = {0L, 800L, 2_000L, 5_000L, 10_000L};

    private static final String HUB = "com.mac.utility.media.hub";
    private static final String LICENSES_HELPER = HUB + ".core.licenses.LicensesHelper";
    private static final String LICENSES_KEY_HELPER = HUB + ".core.licenses.LicensesKeyHelper";
    private static final String VH_LICENSE = HUB + ".core.licenses.VHLicense";
    private static final String MC_SETTINGS = HUB + ".settings.MCSettings";
    private static final String SHARED_UTILS = HUB + ".util.SharedUtils";
    private static final String SETTINGS_FRAGMENT = HUB + ".ui.settings.SettingsFragment";
    private static final String PURCHASED_ACTIVITY = HUB + ".ui.settings.purchase.PurchasedActivity";
    private static final String PURCHASE_ACTIVITY = HUB + ".ui.purchase.activity.PurchaseActivity";
    private static final String PLAYABLE_ITEM = HUB + ".data.model.PlayableMediaItem";
    private static final String VIDEO_PLAY_ACTIVITY = HUB + ".ui.videoplay.activity.VideoPlayActivity";

    private static final String FAKE_USER_ID = "zoevip";
    private static final String FAKE_ACCESS_TOKEN =
            "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJ1c2VySWQiOiJ6b2V2aXAiLCJleHAiOjQxMDI0NDQ4MDB9.";
    private static final String FAKE_REFRESH_TOKEN = "zoevip-refresh";
    private static final long FAKE_TOKEN_EXPIRES_AT = 4_102_444_800_000L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean APPLICATION_HOOKED = new AtomicBoolean(false);

    private LibVidHubHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        scheduleInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        scheduleInstall(module, param.getClassLoader(), "package-ready");
        hookApplicationOnCreate(module, param);
    }

    public static void scheduleInstall(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        onPackageReady(module, param);
    }

    private static void scheduleInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        module.log(4, ZoeIds.TAG, "VidHub PRO hooks scheduling (" + source + ")");
        for (long delay : RETRY_DELAYS_MS) {
            Thread delayThread = new Thread(() -> {
                if (INSTALLED.get()) {
                    return;
                }
                try {
                    if (delay > 0L) {
                        Thread.sleep(delay);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!INSTALLED.get()) {
                    tryInstall(module, cl, source + "@" + delay + "ms");
                }
            }, "ZoeVIP-vidhub-" + delay);
            delayThread.setDaemon(true);
            delayThread.start();
        }
    }

    private static void hookApplicationOnCreate(
            ZoeModule module,
            XposedModuleInterface.PackageReadyParam param
    ) {
        if (!APPLICATION_HOOKED.compareAndSet(false, true)) {
            return;
        }
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof Application application
                                && ZoeIds.VIDHUB_PACKAGE.equals(application.getPackageName())) {
                            scheduleInstall(module, application.getClassLoader(), "application");
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VidHub Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(LICENSES_HELPER, false, cl);
            Class.forName(MC_SETTINGS, false, cl);
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "VidHub hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, ZoeIds.TAG, "VidHub hooks installing (" + source + ")");
        try {
            installHooks(module, cl);
            module.log(4, ZoeIds.TAG, "VidHub hooks installed");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, ZoeIds.TAG, "VidHub hooks failed: " + t.getMessage(), t);
        }
    }

    private static void installHooks(ZoeModule module, ClassLoader cl) throws Throwable {
        List<Object> placeholderLicenses = placeholderLicenses(cl);
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;

        hookLicenses(module, cl, placeholderLicenses, mode);
        hookLoginState(module, cl, mode);
        hookAccountManager(module, cl, mode);
        hookSettingsUi(module, cl, mode);
        hookVideoLimits(module, cl, mode);
        hookPlayableItem(module, cl, mode);
        hookPurchasedActivity(module, cl, placeholderLicenses, mode);
        hookPurchaseRedirect(module, cl, mode);
        hookVideoPlayFinish(module, cl, mode);
        hookBooleanProbes(module, cl, mode);
    }

    private static void hookBooleanProbes(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        String[] classes = {
                LICENSES_HELPER,
                MC_SETTINGS,
                SHARED_UTILS,
                HUB + ".core.account.AccountManager",
        };
        String[] names = {
                "isVip", "isPro", "isPremium", "hasPro", "hasVip",
                "hasActiveSubscription", "isSubscriptionActive",
        };
        for (String className : classes) {
            try {
                Class<?> cls = Class.forName(className, false, cl);
                for (Method method : cls.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0) {
                        continue;
                    }
                    if (method.getReturnType() != boolean.class
                            && method.getReturnType() != Boolean.class) {
                        continue;
                    }
                    boolean match = false;
                    for (String name : names) {
                        if (name.equals(method.getName())) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    module.hook(method).setExceptionMode(mode).intercept(chain -> true);
                    logHook(module, method);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookLicenses(
            ZoeModule module,
            ClassLoader cl,
            List<Object> placeholderLicenses,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> helper = Class.forName(LICENSES_HELPER, false, cl);

        for (Method method : helper.getDeclaredMethods()) {
            if (!"isVip".equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
                continue;
            }
            module.hook(method).setExceptionMode(mode).intercept(chain -> true);
            logHook(module, method);
        }

        hookZeroArgReturn(module, cl, LICENSES_KEY_HELPER, "validateKeys", true, mode);
        hookZeroArgReturnList(module, helper, "getLicenses", placeholderLicenses, mode);
        hookZeroArgReturnList(module, helper, "getValidLicenses", placeholderLicenses, mode);

        Method setLicenses = findMethod(helper, "setLicenses", 1);
        if (setLicenses != null && List.class.isAssignableFrom(setLicenses.getParameterTypes()[0])) {
            module.hook(setLicenses).setExceptionMode(mode).intercept(chain -> {
                Object arg = chain.getArg(0);
                if (!(arg instanceof List) || ((List<?>) arg).isEmpty()) {
                    return chain.proceed(new Object[]{placeholderLicenses});
                }
                return chain.proceed();
            });
            logHook(module, setLicenses);
        }

        for (Method method : helper.getDeclaredMethods()) {
            if (!"getLicenses".equals(method.getName()) && !"getValidLicenses".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].getName().contains("Continuation")) {
                continue;
            }
            module.hook(method)
                    .setExceptionMode(mode)
                    .intercept(chain -> {
                        resumeContinuation(cl, chain.getArg(0), placeholderLicenses);
                        return coroutineSuspended(cl);
                    });
            logHook(module, method);
        }

        for (Method method : helper.getDeclaredMethods()) {
            if (!"isVip".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].getName().contains("Continuation")) {
                continue;
            }
            module.hook(method)
                    .setExceptionMode(mode)
                    .intercept(chain -> {
                        resumeContinuation(cl, chain.getArg(0), Boolean.TRUE);
                        return coroutineSuspended(cl);
                    });
            logHook(module, method);
        }
    }

    private static void hookLoginState(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        hookZeroArgReturn(module, cl, SHARED_UTILS, "isLogon", true, mode);
        hookZeroArgReturn(module, cl, SHARED_UTILS, "getLoginUserId", FAKE_USER_ID, mode);

        Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
        hookZeroArgReturn(module, settings, "getLoginUserAccessToken", FAKE_ACCESS_TOKEN, mode);
        hookZeroArgReturn(module, settings, "getLoginUserRefreshToken", FAKE_REFRESH_TOKEN, mode);
        hookZeroArgReturn(module, settings, "getLoginUserAccessTokenExpiredAt", FAKE_TOKEN_EXPIRES_AT, mode);
        hookZeroArgReturn(module, settings, "getLastLoginPlatform", 2, mode);

        blockTokenClear(module, settings, "setLoginUserAccessToken", String.class, mode);
        blockTokenClear(module, settings, "setLoginUserRefreshToken", String.class, mode);
        blockTokenClear(module, settings, "setLoginUserAccessTokenExpiredAt", long.class, mode);
    }

    private static void hookAccountManager(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> account = Class.forName(HUB + ".core.account.AccountManager", false, cl);
        for (Method method : account.getDeclaredMethods()) {
            String name = method.getName();
            if (!"refreshLoginAccessToken".equals(name)
                    && !"logout".equals(name)
                    && !"deleteAccount".equals(name)) {
                continue;
            }
            if (method.getParameterTypes().length != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].getName().contains("Continuation")) {
                continue;
            }
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                resumeContinuation(cl, chain.getArg(0), null);
                return coroutineSuspended(cl);
            });
            logHook(module, method);
        }
    }

    private static void hookSettingsUi(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> fragment = Class.forName(SETTINGS_FRAGMENT, false, cl);

        Method updateLogin = findMethod(fragment, "updateLoginStatus", 0);
        if (updateLogin != null) {
            module.hook(updateLogin).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                setViewVisibility(chain.getThisObject(), "bottomSignInView", View.GONE);
                setViewVisibility(chain.getThisObject(), "signOutView", View.VISIBLE);
                return result;
            });
            logHook(module, updateLogin);
        }

        Method updatePurchase = findMethod(fragment, "updatePurchaseStatus", 0);
        if (updatePurchase != null) {
            module.hook(updatePurchase).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                setViewVisibility(chain.getThisObject(), "purchaseStateView", View.GONE);
                setViewVisibility(chain.getThisObject(), "vipStateView", View.VISIBLE);
                return result;
            });
            logHook(module, updatePurchase);
        }
    }

    private static void hookVideoLimits(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
        hookZeroArgReturn(module, settings, "getVideoPlayLimitCount", 999_999, mode);
        hookZeroArgReturn(module, settings, "getPlayedMediaCount", 0, mode);

        Method setLimit = findMethod(settings, "setVideoPlayLimitCount", 1);
        if (setLimit != null && setLimit.getParameterTypes()[0] == int.class) {
            module.hook(setLimit).setExceptionMode(mode).intercept(chain -> null);
            logHook(module, setLimit);
        }
    }

    private static void hookPlayableItem(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        hookZeroArgReturn(module, cl, PLAYABLE_ITEM, "isVipLimitedVideoExtension", false, mode);
    }

    private static void hookPurchasedActivity(
            ZoeModule module,
            ClassLoader cl,
            List<Object> placeholderLicenses,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> activity = Class.forName(PURCHASED_ACTIVITY, false, cl);
        Method updateViews = findMethod(activity, "updateViewsWithLicenses", 1);
        if (updateViews == null || !List.class.isAssignableFrom(updateViews.getParameterTypes()[0])) {
            return;
        }
        module.hook(updateViews).setExceptionMode(mode).intercept(chain -> {
            Object arg = chain.getArg(0);
            if (!(arg instanceof List) || ((List<?>) arg).isEmpty()) {
                return chain.proceed(new Object[]{placeholderLicenses});
            }
            return chain.proceed();
        });
        logHook(module, updateViews);
    }

    private static void hookPurchaseRedirect(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> purchased = Class.forName(PURCHASED_ACTIVITY, false, cl);
        hookStartActivityRedirect(module, Activity.class, purchased, mode);
        hookStartActivityRedirect(module, ContextWrapper.class, purchased, mode);
    }

    private static void hookStartActivityRedirect(
            ZoeModule module,
            Class<?> host,
            Class<?> purchased,
            XposedInterface.ExceptionMode mode
    ) {
        for (Method method : host.getDeclaredMethods()) {
            if (!"startActivity".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != Intent.class) {
                continue;
            }
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Intent intent = (Intent) chain.getArg(0);
                if (intent == null) {
                    return chain.proceed();
                }
                ComponentName component = intent.getComponent();
                String className = component != null ? component.getClassName() : null;
                if (className == null || className.isEmpty()) {
                    return chain.proceed();
                }
                if (!PURCHASE_ACTIVITY.equals(className) && !className.endsWith("PurchaseActivity")) {
                    return chain.proceed();
                }
                Context context = resolveContext(chain.getThisObject());
                if (context == null) {
                    return chain.proceed();
                }
                Intent redirect = new Intent(intent);
                redirect.setClass(context, purchased);
                module.log(4, ZoeIds.TAG, "VidHub redirect purchase → purchased");
                return chain.proceed(new Object[]{redirect});
            });
            logHook(module, method);
        }
    }

    private static void hookVideoPlayFinish(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> activity = Class.forName(VIDEO_PLAY_ACTIVITY, false, cl);
        Field needPurchase = activity.getDeclaredField("needShowPurchase");
        needPurchase.setAccessible(true);

        Method finish = findMethod(activity, "finish", 0);
        if (finish == null) {
            return;
        }
        module.hook(finish).setExceptionMode(mode).intercept(chain -> {
            try {
                needPurchase.setBoolean(chain.getThisObject(), false);
            } catch (IllegalAccessException ignored) {
            }
            return chain.proceed();
        });
        logHook(module, finish);
    }

    private static List<Object> placeholderLicenses(ClassLoader cl) throws Throwable {
        Class<?> licenseClass = Class.forName(VH_LICENSE, false, cl);
        Object license = licenseClass
                .getDeclaredConstructor(int.class, String.class)
                .newInstance(0, null);
        return Collections.singletonList(license);
    }

    private static void hookZeroArgReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> cls = Class.forName(className, false, cl);
        hookZeroArgReturn(module, cls, methodName, value, mode);
    }

    private static void hookZeroArgReturn(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, methodName, 0);
        if (method == null) {
            module.log(5, ZoeIds.TAG, "VidHub missing " + cls.getSimpleName() + "." + methodName);
            return;
        }
        module.hook(method).setExceptionMode(mode).intercept(chain -> value);
        logHook(module, method);
    }

    private static void hookZeroArgReturnList(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            List<Object> value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, methodName, 0);
        if (method == null) {
            module.log(5, ZoeIds.TAG, "VidHub missing " + cls.getSimpleName() + "." + methodName);
            return;
        }
        if (!List.class.isAssignableFrom(method.getReturnType())) {
            return;
        }
        module.hook(method).setExceptionMode(mode).intercept(chain -> value);
        logHook(module, method);
    }

    private static void blockTokenClear(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            Class<?> paramType,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, methodName, 1);
        if (method == null || method.getParameterTypes()[0] != paramType) {
            return;
        }
        module.hook(method).setExceptionMode(mode).intercept(chain -> null);
        logHook(module, method);
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static void resumeContinuation(ClassLoader cl, Object continuation, Object value)
            throws Throwable {
        if (continuation == null) {
            return;
        }
        Class<?> resultClass = XposedHelpers.findClass("kotlin.Result", cl);
        Object boxed = XposedHelpers.callStaticMethod(
                resultClass,
                "constructor-impl",
                value
        );
        XposedHelpers.callMethod(continuation, "resumeWith", boxed);
    }

    private static Object coroutineSuspended(ClassLoader cl) {
        return XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("kotlin.coroutines.intrinsics.IntrinsicsKt", cl),
                "COROUTINE_SUSPENDED"
        );
    }

    private static Context resolveContext(Object source) {
        Context context = source instanceof Context ? (Context) source : null;
        Object cursor = source;
        for (int i = 0; i < 16 && cursor != null; i++) {
            if (cursor instanceof Activity) {
                return (Activity) cursor;
            }
            if (cursor instanceof ContextWrapper) {
                cursor = ((ContextWrapper) cursor).getBaseContext();
                if (cursor instanceof Context) {
                    context = (Context) cursor;
                }
            } else {
                break;
            }
        }
        return context;
    }

    private static void setViewVisibility(Object owner, String fieldName, int visibility) {
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object view = field.get(owner);
            if (view instanceof View) {
                ((View) view).setVisibility(visibility);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logHook(ZoeModule module, Method method) {
        module.log(4, ZoeIds.TAG, "VidHub hook "
                + method.getDeclaringClass().getName() + "." + method.getName());
    }
}
