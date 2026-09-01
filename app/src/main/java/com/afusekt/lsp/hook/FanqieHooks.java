package com.afusekt.lsp.hook;

import android.app.Application;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy XposedBridge hooks for 番茄畅听 ({@code com.xs.fm}).
 * Tested target: 6.6.0.32 — VIP gate lives in {@code PrivilegeManager} (native) + {@code AdImpl}.
 */
public final class FanqieHooks {

    private static final String TAG = MainHook.TAG + ":Fanqie";

    private static final String PRIVILEGE_MANAGER =
            "com.dragon.read.admodule.adfm.pay.PrivilegeManager";
    private static final String AD_IMPL = "com.xs.fm.ad.impl.AdImpl";
    private static final String VIP_INFO_MODEL =
            "com.dragon.read.admodule.adfm.vip.VipInfoModel";
    private static final String RPC_VIP_INFO = "com.xs.fm.rpc.model.VipInfo";
    private static final String INTERRUPT_STRATEGY =
            "com.dragon.read.reader.speech.ad.listen.strategy.InterruptStrategy";
    private static final String WHOLE_DAY_MANAGER =
            "com.dragon.read.admodule.adfm.unlocktime.wholeday.q0";
    private static final String INSPIRE_LISTENER = "r02.k";
    private static final String ACCT_MANAGER = "com.dragon.read.user.AcctManager";
    private static final String MINE_IMPL = "com.xs.fm.mine.impl.MineImpl";
    private static final String USER_VIP_TAG = "com.xs.fm.rpc.model.UserVipTag";

    /** Unix seconds — year 2099. */
    private static final String VIP_EXPIRE_SECONDS = "4102444800";
    private static final String VIP_LABEL = "VIP会员";
    private static final String VIP_MSG = "尊贵会员";

    private FanqieHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        int hooks = 0;
        hooks += hookApplicationLog(cl);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isVip");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isVipOrInAbtest");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasNoAudioAdPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasPrivilege", String.class);
        hooks += hookAfterPatch(cl, PRIVILEGE_MANAGER, "v", VIP_INFO_MODEL);
        hooks += hookBeforePatch(cl, PRIVILEGE_MANAGER, "M", VIP_INFO_MODEL);
        hooks += hookReturnBoolean(cl, AD_IMPL, "isVip");
        hooks += hookReturnBoolean(cl, AD_IMPL, "isVipOrInAbtest");
        hooks += hookReturnBoolean(cl, AD_IMPL, "isVipExpire", false);
        hooks += hookReturnBoolean(cl, AD_IMPL, "hasNoAudioAdPrivilege");
        hooks += hookReturnBoolean(cl, AD_IMPL, "hasPrivilege", String.class);
        hooks += hookAfterPatch(cl, AD_IMPL, "getVipInfo", VIP_INFO_MODEL);
        hooks += hookStaticReturnBoolean(cl, INTERRUPT_STRATEGY, "K");
        hooks += hookGsonVipParse(cl);
        hooks += hookReturnBoolean(cl, AD_IMPL, "isNoAd", String.class);
        hooks += hookReturnBoolean(cl, AD_IMPL, "isListenWholeDay");
        // 亮会员标：必须 true，否则「我的」页直接隐藏 VIP 卡片
        hooks += hookReturnBoolean(cl, AD_IMPL, "canShowVipRelational");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "canShowVipRelational");
        hooks += hookReturnBoolean(cl, AD_IMPL, "isVipInvertExper", false);
        hooks += hookReturnBoolean(cl, AD_IMPL, "isFakeVipActive");
        hooks += hookVipBadge(cl);
        hooks += hookStaticReturnBoolean(cl, WHOLE_DAY_MANAGER, "C");
        hooks += hookInspireRewardVerify(cl);
        hooks += hookAdStripBooleans(cl, AD_STRIP_FALSE, false);
        hooks += hookAdStripBooleans(cl, AD_STRIP_TRUE, true);
        hooks += hookAdStripVoid(cl, AD_STRIP_VOID);
        hooks += hookStaticReturnBoolean(cl, PATCH_CONFIG, "G", false);
        XposedBridge.log(TAG + ": hooks installed (" + hooks + ")");
    }

    private static final String PATCH_CONFIG = "w02.c";

    private static final String[] AD_STRIP_FALSE = {
            "isShowSplashFmAd", "isShowHotSplash", "canShowColdSplashAdForFrequency",
            "checkPatchAdAvailable", "getPatchAdEnable", "checkInspireAdAvailable",
            "canShowAdUnlockTimeDialog", "canShowAdUnlockTimeDialogNewScene",
            "isAdInterceptPlay", "adPlayIntercept", "isPatchAdAttachWindow",
            "isDownloadInspireEnable", "canShowDownloadHintIcon",
            "isMustOutRewardUnlockReadTime", "hasMineTabHeadWithUnlockEntrance",
            "canAutoShowListenWholeDayDialog", "unlockWholeDayShow",
            "canShowNightPrivilegeAfterUnlock", "canShowUnlockReadTaskProgressBar",
            "canUnlockInAdvance", "canContinueUnlockNightPrivilege",
            "isPatchClickInspireFeqCtrlEnable", "isNeedFreshForBookMallAd",
            "enableRewardUnlockReadTime", "isInfoFlowAdAtView", "isCommonAdValid",
            "isShowAdInfo", "isInAdUnlockGuideTest",
    };

    private static final String[] AD_STRIP_TRUE = {
            "getNeedNotShowPatch", "isRequestNoAd", "isRequestingNoAd",
            "isInAllAdRevert", "isInTimeAdRevert", "isReversePlayerPageAd",
            "isReverseReadPageAd", "musicPatchAdBlockEnable", "musicPatchAdBlockShow",
            "musicPatchAdBlockRequest",
    };

    private static final String[] AD_STRIP_VOID = {
            "getAdSplash", "getSplashAdView", "preloadInspireAd",
            "showUnlockAdTimeDialog", "showUnlockDownloadInspireDialog", "showAdUnlockGuideTips",
    };

    private static int hookAdStripBooleans(ClassLoader cl, String[] names, boolean value) {
        int count = 0;
        try {
            Class<?> cls = XposedHelpers.findClass(AD_IMPL, cl);
            java.util.Set<String> targets = java.util.Set.of(names);
            for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                if (!targets.contains(method.getName())) {
                    continue;
                }
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value));
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ad strip booleans failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static int hookAdStripVoid(ClassLoader cl, String[] names) {
        int count = 0;
        try {
            Class<?> cls = XposedHelpers.findClass(AD_IMPL, cl);
            java.util.Set<String> targets = java.util.Set.of(names);
            for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                if (!targets.contains(method.getName())) {
                    continue;
                }
                if (method.getReturnType() != void.class) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING);
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ad strip void failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static int hookStaticReturnBoolean(
            ClassLoader cl,
            String className,
            String methodName,
            boolean value
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookApplicationLog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (MainHook.FANQIE_PACKAGE.equals(app.getPackageName())) {
                        XposedBridge.log(TAG + ": active in " + app.getPackageName());
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturnBoolean(ClassLoader cl, String className, String methodName) {
        return hookReturnBoolean(cl, className, methodName, true);
    }

    private static int hookReturnBoolean(
            ClassLoader cl,
            String className,
            String methodName,
            boolean value
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookReturnBoolean(
            ClassLoader cl,
            String className,
            String methodName,
            Class<?> paramType
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    cl,
                    methodName,
                    paramType,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookStaticReturnBoolean(ClassLoader cl, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookVipBadge(ClassLoader cl) {
        int count = 0;
        count += hookReturnBoolean(cl, ACCT_MANAGER, "getIsUserNeedWeakenVip", false);
        count += hookReturnBoolean(cl, ACCT_MANAGER, "vipReverse", false);
        count += hookReturnBoolean(cl, MINE_IMPL, "getIsUserNeedWeakenVip", false);
        count += hookReturnBoolean(cl, MINE_IMPL, "getIsUserNeedWeakenVipOnly", false);
        count += hookReturnBoolean(cl, MINE_IMPL, "vipReverseEnable", false);
        count += hookReturnBoolean(cl, MINE_IMPL, "isVipRemind");
        count += hookUserVipTag(cl);
        return count;
    }

    private static int hookUserVipTag(ClassLoader cl) {
        try {
            Class<?> tagCls = XposedHelpers.findClass(USER_VIP_TAG, cl);
            Object vipTag = Enum.valueOf((Class<? extends Enum>) tagCls.asSubclass(Enum.class), "TagVIPUser");
            XposedHelpers.findAndHookMethod(
                    ACCT_MANAGER, cl, "getUserVipTag", XC_MethodReplacement.returnConstant(vipTag));
            XposedHelpers.findAndHookMethod(
                    MINE_IMPL, cl, "getUserVipTag", XC_MethodReplacement.returnConstant(vipTag));
            return 2;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": UserVipTag hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookAfterPatch(
            ClassLoader cl,
            String className,
            String methodName,
            String resultType
    ) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null && VIP_INFO_MODEL.equals(resultType)) {
                        try {
                            result = XposedHelpers.newInstance(
                                    XposedHelpers.findClass(VIP_INFO_MODEL, cl));
                            param.setResult(result);
                        } catch (Throwable ignored) {
                        }
                    }
                    patchVipInfoModel(result);
                    patchRpcVipInfo(result);
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": after " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookBeforePatch(
            ClassLoader cl,
            String className,
            String methodName,
            String argType
    ) {
        try {
            Class<?> argCls = XposedHelpers.findClass(argType, cl);
            XposedHelpers.findAndHookMethod(className, cl, methodName, argCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    patchVipInfoModel(param.args[0]);
                    patchRpcVipInfo(param.args[0]);
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": before " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookInspireRewardVerify(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    INSPIRE_LISTENER,
                    cl,
                    "f",
                    int.class,
                    int.class,
                    boolean.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Integer && (Integer) param.args[0] == 0) {
                                param.args[0] = 1;
                            }
                        }
                    }
            );
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": inspire reward hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookGsonVipParse(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> gson = XposedHelpers.findClass("com.google.gson.Gson", cl);
            Class<?> vipInfoModel = XposedHelpers.findClass(VIP_INFO_MODEL, cl);
            Class<?> rpcVipInfo = XposedHelpers.findClass(RPC_VIP_INFO, cl);
            for (java.lang.reflect.Method method : gson.getDeclaredMethods()) {
                if (!"fromJson".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                Class<?> second = method.getParameterTypes()[1];
                if (second != Class.class) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object typeArg = param.args[1];
                        if (!(typeArg instanceof Class<?>)) {
                            return;
                        }
                        Class<?> type = (Class<?>) typeArg;
                        if (vipInfoModel.isAssignableFrom(type) || rpcVipInfo.isAssignableFrom(type)) {
                            patchVipInfoModel(param.getResult());
                            patchRpcVipInfo(param.getResult());
                        }
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Gson hook failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static void patchVipInfoModel(Object model) {
        if (model == null) {
            return;
        }
        if (!VIP_INFO_MODEL.equals(model.getClass().getName())) {
            return;
        }
        try {
            XposedHelpers.setBooleanField(model, "isVip", true);
            XposedHelpers.setBooleanField(model, "isContinuousVip", true);
            XposedHelpers.setBooleanField(model, "hasBeenContinuousVip", true);
            XposedHelpers.setObjectField(model, "expireTime", VIP_EXPIRE_SECONDS);
            XposedHelpers.setObjectField(model, "leftTime", "99999");
            XposedHelpers.setObjectField(model, "label", VIP_LABEL);
            XposedHelpers.setObjectField(model, "msg", VIP_MSG);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": patch VipInfoModel failed: " + t.getMessage());
        }
    }

    private static void patchRpcVipInfo(Object model) {
        if (model == null) {
            return;
        }
        if (!RPC_VIP_INFO.equals(model.getClass().getName())) {
            return;
        }
        try {
            XposedHelpers.setBooleanField(model, "isVip", true);
            XposedHelpers.setBooleanField(model, "isContinuousVip", true);
            XposedHelpers.setBooleanField(model, "hasBeenContinuousVip", true);
            XposedHelpers.setBooleanField(model, "isVIPOffline", false);
            XposedHelpers.setObjectField(model, "expireTime", VIP_EXPIRE_SECONDS);
            XposedHelpers.setObjectField(model, "leftTime", "99999");
            XposedHelpers.setObjectField(model, "label", VIP_LABEL);
            XposedHelpers.setObjectField(model, "msg", VIP_MSG);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": patch VipInfo failed: " + t.getMessage());
        }
    }
}
