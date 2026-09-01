package com.afusekt.lsp.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Ximalaya ({@code com.ximalaya.ting.android}) — skip reward-video ads and grant rewards.
 * Tested against 9.5.1.3: listen-earn / welfare / free-listen flows.
 */
public final class XimalayaHooks {

    private static final String TAG = MainHook.TAG + ":Ximalaya";

    private static final String AD_SDK = "com.ximalaya.ting.android.adsdk.AdSDK";
    private static final String REWARD_VIDEO_MGR =
            "com.ximalaya.ting.android.adsdk.aggregationsdk.rewardvideoad.RewardVideoAdManager";
    private static final String REWARD_LISTENER =
            "com.ximalaya.ting.android.adsdk.external.IRewardVideoAdListener";
    private static final String AD_GOLD_COIN_DATA =
            "com.ximalaya.ting.android.host.data.model.ad.AdGoldCoinResponseData";
    private static final String INCENTIVE_REWARD_RESPONSE =
            "com.ximalaya.ting.android.host.model.ad.IncentiveRewardResponse";
    private static final String INCENTIVE_REWARD_DATA =
            "com.ximalaya.ting.android.host.model.ad.IncentiveRewardResponse$Data";
    private static final String INCENTIVE_RESPONSE_DATA =
            "com.ximalaya.ting.android.opensdk.model.advertis.IncentiveResponse$Data";
    private static final String VIDEO_UNLOCK_RESULT =
            "com.ximalaya.ting.android.host.model.ad.VideoUnLockResult";

    private XimalayaHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        int hooks = 0;
        hooks += hookApplicationLog(cl);
        hooks += hookAdSdkLoadReward(cl);
        hooks += hookRewardVideoManagerLoad(cl);
        hooks += hookRewardListenerOnReward(cl);
        hooks += hookGsonRewardParse(cl);
        hooks += hookGoldCoinHandlers(cl);
        hooks += hookBooleanGetter(cl, INCENTIVE_REWARD_DATA, "isSuccess");
        hooks += hookBooleanGetter(cl, INCENTIVE_REWARD_DATA, "isRetry", false);
        hooks += hookBooleanGetter(cl, INCENTIVE_RESPONSE_DATA, "isSuccess");
        hooks += hookBooleanGetter(cl, VIDEO_UNLOCK_RESULT, "isSuccess");
        hooks += hookIncentiveRewardDataSetters(cl);
        XposedBridge.log(TAG + ": hooks installed (" + hooks + ")");
    }

    private static int hookApplicationLog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (MainHook.XIMALAYA_PACKAGE.equals(app.getPackageName())) {
                        XposedBridge.log(TAG + ": active in " + app.getPackageName());
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookAdSdkLoadReward(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    AD_SDK,
                    cl,
                    "loadRewardVideoAd",
                    Activity.class,
                    Context.class,
                    "com.ximalaya.ting.android.adsdk.external.XmLoadAdParams",
                    "com.ximalaya.ting.android.adsdk.external.XmRewardExtraParam",
                    REWARD_LISTENER,
                    skipRewardReplacement()
            );
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AdSDK.loadRewardVideoAd hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookRewardVideoManagerLoad(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    REWARD_VIDEO_MGR,
                    cl,
                    "loadRewardVideoAd",
                    Activity.class,
                    "com.ximalaya.ting.android.adsdk.external.XmLoadAdParams",
                    "com.ximalaya.ting.android.adsdk.external.XmRewardExtraParam",
                    REWARD_LISTENER,
                    java.util.List.class,
                    skipRewardReplacement()
            );
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RewardVideoAdManager hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static XC_MethodReplacement skipRewardReplacement() {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                for (Object arg : param.args) {
                    if (arg != null && implementsRewardListener(arg.getClass())) {
                        dispatchInstantReward(arg);
                        break;
                    }
                }
                return null;
            }
        };
    }

    private static boolean implementsRewardListener(Class<?> cls) {
        for (Class<?> iface : cls.getInterfaces()) {
            if (REWARD_LISTENER.equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void dispatchInstantReward(Object listener) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> safeCall(listener, "onAdLoad", new Object[]{null}));
        handler.postDelayed(() -> {
            safeCall(listener, "onAdPlayStart");
            safeCall(listener, "onReward", true);
            safeCall(listener, "onVideoComplete");
            handler.postDelayed(() -> safeCall(listener, "onAdClose"), 80L);
        }, 200L);
    }

    private static void safeCall(Object target, String method, Object... args) {
        try {
            if (args.length == 0) {
                XposedHelpers.callMethod(target, method);
            } else {
                XposedHelpers.callMethod(target, method, args);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + method + " failed: " + t.getMessage());
        }
    }

    private static int hookRewardListenerOnReward(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    REWARD_LISTENER,
                    cl,
                    "onReward",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = true;
                        }
                    }
            );
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": onReward hook failed: " + t.getMessage());
            return 0;
        }
    }

    /** Patch JSON parse results before app reads success/retry flags. */
    private static int hookGsonRewardParse(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> gson = XposedHelpers.findClass("com.google.gson.Gson", cl);
            for (Method method : gson.getDeclaredMethods()) {
                if (!"fromJson".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                Class<?> second = method.getParameterTypes()[1];
                if (second != String.class && second != java.lang.reflect.Type.class
                        && !Class.class.isAssignableFrom(second)) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        patchRewardObject(param.getResult());
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Gson hook failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    /** Scan BusinessModule inner classes for AdGoldCoinResponseData handlers. */
    private static int hookGoldCoinHandlers(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> businessModule = XposedHelpers.findClass(
                    "com.ximalaya.ting.android.reactnative.modules.BusinessModule", cl);
            Class<?> goldCoin = XposedHelpers.findClass(AD_GOLD_COIN_DATA, cl);
            for (Class<?> inner : businessModule.getDeclaredClasses()) {
                for (Method method : inner.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1 || params[0] != goldCoin) {
                        continue;
                    }
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            patchGoldCoinData(param.args[0]);
                        }
                    });
                    count++;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": gold coin handler scan failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static void patchRewardObject(Object result) {
        if (result == null) {
            return;
        }
        String name = result.getClass().getName();
        if (AD_GOLD_COIN_DATA.equals(name)) {
            patchGoldCoinData(result);
        } else if (INCENTIVE_REWARD_RESPONSE.equals(name)) {
            patchIncentiveResponse(result);
        }
    }

    private static void patchGoldCoinData(Object data) {
        if (data == null) {
            return;
        }
        try {
            XposedHelpers.setBooleanField(data, "success", true);
            XposedHelpers.setBooleanField(data, "retry", false);
            int coins = XposedHelpers.getIntField(data, "coins");
            if (coins <= 0) {
                XposedHelpers.setIntField(data, "coins", 1);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": patchGoldCoinData failed: " + t.getMessage());
        }
    }

    private static void patchIncentiveResponse(Object response) {
        try {
            Object data = XposedHelpers.callMethod(response, "getData");
            if (data == null) {
                Class<?> dataCls = XposedHelpers.findClass(INCENTIVE_REWARD_DATA, response.getClass().getClassLoader());
                data = dataCls.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(response, "setData", data);
            }
            XposedHelpers.callMethod(data, "setSuccess", true);
            XposedHelpers.callMethod(data, "setRetry", false);
            try {
                XposedHelpers.setObjectField(data, "toast", "领取成功");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": patchIncentiveResponse failed: " + t.getMessage());
        }
    }

    private static int hookIncentiveRewardDataSetters(ClassLoader cl) {
        int count = 0;
        try {
            XposedHelpers.findAndHookMethod(
                    INCENTIVE_REWARD_DATA, cl, "setSuccess", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = true;
                        }
                    }
            );
            count++;
            XposedHelpers.findAndHookMethod(
                    INCENTIVE_REWARD_DATA, cl, "setRetry", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = false;
                        }
                    }
            );
            count++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": incentive setters hook failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookBooleanGetter(ClassLoader cl, String className, String methodName) {
        return hookBooleanGetter(cl, className, methodName, true);
    }

    private static int hookBooleanGetter(
            ClassLoader cl,
            String className,
            String methodName,
            boolean value
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    methodName,
                    XC_MethodReplacement.returnConstant(value)
            );
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + "#" + methodName + " hook failed: "
                    + t.getMessage());
            return 0;
        }
    }
}
