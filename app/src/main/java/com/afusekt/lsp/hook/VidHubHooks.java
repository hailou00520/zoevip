package com.afusekt.lsp.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.view.View;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** VidHub / Media Hub lifetime VIP unlock (com.oumi.utility.media.hub). */
public final class VidHubHooks {

    private static final String PKG = MainHook.VIDHUB_PACKAGE;
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
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);

    private static final long INSTALL_DELAY_MS = 8000L;

    private VidHubHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        XposedBridge.log(MainHook.TAG + ": VidHub PRO in " + INSTALL_DELAY_MS + "ms (background)");
        new Thread(() -> {
            try {
                Thread.sleep(INSTALL_DELAY_MS);
                tryInstall(cl, "background");
            } catch (InterruptedException ignored) {
                SCHEDULED.set(false);
            } catch (Throwable t) {
                SCHEDULED.set(false);
                XposedBridge.log(MainHook.TAG + ": VidHub background install failed: "
                        + t.getMessage());
            }
        }, "ZoeVIP-VidHub").start();
    }

    private static void tryInstall(ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(LICENSES_HELPER, false, cl);
            Class.forName(MC_SETTINGS, false, cl);
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": VidHub hooks deferred (" + source + ")");
            SCHEDULED.set(false);
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedBridge.log(MainHook.TAG + ": VidHub hooks installing (" + source + ")");
        try {
            installHooks(cl);
            XposedBridge.log(MainHook.TAG + ": VidHub hooks installed");
        } catch (Throwable t) {
            INSTALLED.set(false);
            SCHEDULED.set(false);
            XposedBridge.log(MainHook.TAG + ": VidHub hooks failed: " + t.getMessage());
        }
    }

    private static void installHooks(ClassLoader cl) throws Throwable {
        List<Object> placeholderLicenses = placeholderLicenses(cl);

        hookLicenses(cl, placeholderLicenses);
        hookLoginState(cl);
        hookAccountManager(cl);
        hookSettingsUi(cl);
        hookVideoLimits(cl);
        hookPlayableItem(cl);
        hookPurchasedActivity(cl, placeholderLicenses);
        hookPurchaseRedirect(cl);
        hookVideoPlayFinish(cl);
    }

    private static void hookLicenses(ClassLoader cl, List<Object> placeholderLicenses) throws Throwable {
        Class<?> helper = Class.forName(LICENSES_HELPER, false, cl);

        for (Method method : helper.getDeclaredMethods()) {
            if (!"isVip".equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
                continue;
            }
            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true));
            logHook(method);
        }

        hookZeroArgReturn(cl, LICENSES_KEY_HELPER, "validateKeys", true);

        hookZeroArgReturnList(cl, helper, "getLicenses", placeholderLicenses);
        hookZeroArgReturnList(cl, helper, "getValidLicenses", placeholderLicenses);

        Method setLicenses = findMethod(helper, "setLicenses", 1);
        if (setLicenses != null && List.class.isAssignableFrom(setLicenses.getParameterTypes()[0])) {
            XposedBridge.hookMethod(setLicenses, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object arg = param.args[0];
                    if (!(arg instanceof List) || ((List<?>) arg).isEmpty()) {
                        param.args[0] = placeholderLicenses;
                    }
                }
            });
            logHook(setLicenses);
        }

        for (Method method : helper.getDeclaredMethods()) {
            if (!"getLicenses".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].getName().contains("Continuation")) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    resumeContinuation(cl, param.args[0], placeholderLicenses);
                    param.setResult(coroutineSuspended(cl));
                }
            });
            logHook(method);
        }
    }

    private static void hookLoginState(ClassLoader cl) throws Throwable {
        hookZeroArgReturn(cl, SHARED_UTILS, "isLogon", true);
        hookZeroArgReturn(cl, SHARED_UTILS, "getLoginUserId", FAKE_USER_ID);

        Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
        hookZeroArgReturn(cl, settings, "getLoginUserAccessToken", FAKE_ACCESS_TOKEN);
        hookZeroArgReturn(cl, settings, "getLoginUserRefreshToken", FAKE_REFRESH_TOKEN);
        hookZeroArgReturn(cl, settings, "getLoginUserAccessTokenExpiredAt", FAKE_TOKEN_EXPIRES_AT);
        hookZeroArgReturn(cl, settings, "getLastLoginPlatform", 2);

        blockTokenClear(settings, "setLoginUserAccessToken", String.class);
        blockTokenClear(settings, "setLoginUserRefreshToken", String.class);
        blockTokenClear(settings, "setLoginUserAccessTokenExpiredAt", long.class);
    }

    private static void hookAccountManager(ClassLoader cl) throws Throwable {
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
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    resumeContinuation(cl, param.args[0], null);
                    param.setResult(coroutineSuspended(cl));
                }
            });
            logHook(method);
        }
    }

    private static void hookSettingsUi(ClassLoader cl) throws Throwable {
        Class<?> fragment = Class.forName(SETTINGS_FRAGMENT, false, cl);

        Method updateLogin = findMethod(fragment, "updateLoginStatus", 0);
        if (updateLogin != null) {
            XposedBridge.hookMethod(updateLogin, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setViewVisibility(param.thisObject, "bottomSignInView", View.GONE);
                    setViewVisibility(param.thisObject, "signOutView", View.VISIBLE);
                }
            });
            logHook(updateLogin);
        }

        Method updatePurchase = findMethod(fragment, "updatePurchaseStatus", 0);
        if (updatePurchase != null) {
            XposedBridge.hookMethod(updatePurchase, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setViewVisibility(param.thisObject, "purchaseStateView", View.GONE);
                    setViewVisibility(param.thisObject, "vipStateView", View.VISIBLE);
                }
            });
            logHook(updatePurchase);
        }
    }

    private static void hookVideoLimits(ClassLoader cl) throws Throwable {
        Class<?> settings = Class.forName(MC_SETTINGS, false, cl);
        hookZeroArgReturn(cl, settings, "getVideoPlayLimitCount", 999_999);
        hookZeroArgReturn(cl, settings, "getPlayedMediaCount", 0);

        Method setLimit = findMethod(settings, "setVideoPlayLimitCount", 1);
        if (setLimit != null && setLimit.getParameterTypes()[0] == int.class) {
            XposedBridge.hookMethod(setLimit, XC_MethodReplacement.DO_NOTHING);
            logHook(setLimit);
        }
    }

    private static void hookPlayableItem(ClassLoader cl) throws Throwable {
        hookZeroArgReturn(cl, PLAYABLE_ITEM, "isVipLimitedVideoExtension", false);
    }

    private static void hookPurchasedActivity(ClassLoader cl, List<Object> placeholderLicenses)
            throws Throwable {
        Class<?> activity = Class.forName(PURCHASED_ACTIVITY, false, cl);
        Method updateViews = findMethod(activity, "updateViewsWithLicenses", 1);
        if (updateViews == null || !List.class.isAssignableFrom(updateViews.getParameterTypes()[0])) {
            return;
        }
        XposedBridge.hookMethod(updateViews, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object arg = param.args[0];
                if (!(arg instanceof List) || ((List<?>) arg).isEmpty()) {
                    param.args[0] = placeholderLicenses;
                }
            }
        });
        logHook(updateViews);
    }

    private static void hookPurchaseRedirect(ClassLoader cl) throws Throwable {
        Class<?> purchased = Class.forName(PURCHASED_ACTIVITY, false, cl);
        hookStartActivityRedirect(Activity.class, purchased, cl);
        hookStartActivityRedirect(ContextWrapper.class, purchased, cl);
    }

    private static void hookStartActivityRedirect(Class<?> host, Class<?> purchased, ClassLoader cl) {
        for (Method method : host.getDeclaredMethods()) {
            if (!"startActivity".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != Intent.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if (intent == null) {
                        return;
                    }
                    ComponentName component = intent.getComponent();
                    String className = component != null ? component.getClassName() : null;
                    if (className == null || className.isEmpty()) {
                        return;
                    }
                    if (!PURCHASE_ACTIVITY.equals(className) && !className.endsWith("PurchaseActivity")) {
                        return;
                    }
                    Context context = resolveContext(param.thisObject);
                    if (context == null) {
                        return;
                    }
                    Intent redirect = new Intent(intent);
                    redirect.setClass(context, purchased);
                    param.args[0] = redirect;
                    XposedBridge.log(MainHook.TAG + ": VidHub redirect purchase → purchased");
                }
            });
            logHook(method);
        }
    }

    private static void hookVideoPlayFinish(ClassLoader cl) throws Throwable {
        Class<?> activity = Class.forName(VIDEO_PLAY_ACTIVITY, false, cl);
        java.lang.reflect.Field needPurchase = activity.getDeclaredField("needShowPurchase");
        needPurchase.setAccessible(true);

        Method finish = findMethod(activity, "finish", 0);
        if (finish == null) {
            return;
        }
        XposedBridge.hookMethod(finish, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws IllegalAccessException {
                needPurchase.setBoolean(param.thisObject, false);
            }
        });
        logHook(finish);
    }

    private static List<Object> placeholderLicenses(ClassLoader cl) throws Throwable {
        Class<?> licenseClass = Class.forName(VH_LICENSE, false, cl);
        Object license = licenseClass
                .getDeclaredConstructor(int.class, String.class)
                .newInstance(0, null);
        return Collections.singletonList(license);
    }

    private static void hookZeroArgReturn(
            ClassLoader cl,
            String className,
            String methodName,
            Object value
    ) throws Throwable {
        Class<?> cls = Class.forName(className, false, cl);
        hookZeroArgReturn(cl, cls, methodName, value);
    }

    private static void hookZeroArgReturn(ClassLoader cl, Class<?> cls, String methodName, Object value) {
        Method method = findMethod(cls, methodName, 0);
        if (method == null) {
            XposedBridge.log(MainHook.TAG + ": VidHub missing " + cls.getSimpleName() + "." + methodName);
            return;
        }
        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value));
        logHook(method);
    }

    private static void hookZeroArgReturnList(
            ClassLoader cl,
            Class<?> cls,
            String methodName,
            List<Object> value
    ) {
        Method method = findMethod(cls, methodName, 0);
        if (method == null) {
            XposedBridge.log(MainHook.TAG + ": VidHub missing " + cls.getSimpleName() + "." + methodName);
            return;
        }
        if (!List.class.isAssignableFrom(method.getReturnType())) {
            return;
        }
        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value));
        logHook(method);
    }

    private static void blockTokenClear(Class<?> cls, String methodName, Class<?> paramType) {
        Method method = findMethod(cls, methodName, 1);
        if (method == null || method.getParameterTypes()[0] != paramType) {
            return;
        }
        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
        logHook(method);
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
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object view = field.get(owner);
            if (view instanceof View) {
                ((View) view).setVisibility(visibility);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logHook(Method method) {
        XposedBridge.log(MainHook.TAG + ": VidHub hook "
                + method.getDeclaringClass().getName() + "." + method.getName());
    }
}
