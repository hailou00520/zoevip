package com.afusekt.lsp.libxposed;

import android.app.Application;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 番茄畅听 ({@code com.xs.fm}) VIP unlock via libxposed.
 * Tested target: 6.6.0.32 — {@code PrivilegeManager} native gates + {@code AdImpl} facade.
 */
public final class LibFanqieHooks {

    private static final String TAG = ZoeIds.TAG + ":Fanqie";

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

    private static final String VIP_EXPIRE_SECONDS = "4102444800";
    private static final String VIP_LABEL = "VIP会员";
    private static final String VIP_MSG = "尊贵会员";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LibFanqieHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        tryInstall(module, param.getClassLoader(), "package-ready");
        hookApplicationOnCreate(module);
    }

    private static void hookApplicationOnCreate(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getThisObject();
                        if (app instanceof Application application
                                && ZoeIds.FANQIE_PACKAGE.equals(application.getPackageName())) {
                            tryInstall(module, application.getClassLoader(), "application");
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, TAG, "Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(PRIVILEGE_MANAGER, false, cl);
            Class.forName(AD_IMPL, false, cl);
        } catch (Throwable t) {
            module.log(5, TAG, "hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, TAG, "hooks installing (" + source + ")");
        try {
            int count = installHooks(module, cl);
            module.log(4, TAG, "hooks installed (" + count + ")");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, TAG, "hooks failed: " + t.getMessage(), t);
        }
    }

    private static int installHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = 0;
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isVip", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isVipOrInAbtest", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasNoAudioAdPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasPrivilege", String.class, true, mode);
        count += hookAfterPatch(module, cl, PRIVILEGE_MANAGER, "v", mode);
        count += hookBeforePatch(module, cl, PRIVILEGE_MANAGER, "M", mode);
        count += hookReturn(module, cl, AD_IMPL, "isVip", true, mode);
        count += hookReturn(module, cl, AD_IMPL, "isVipOrInAbtest", true, mode);
        count += hookReturn(module, cl, AD_IMPL, "isVipExpire", false, mode);
        count += hookReturn(module, cl, AD_IMPL, "hasNoAudioAdPrivilege", true, mode);
        count += hookReturn(module, cl, AD_IMPL, "hasPrivilege", String.class, true, mode);
        count += hookAfterPatch(module, cl, AD_IMPL, "getVipInfo", mode);
        count += hookStaticReturn(module, cl, INTERRUPT_STRATEGY, "K", true, mode);
        count += hookGsonVipParse(module, cl, mode);
        count += installAdHooks(module, cl, mode);
        return count;
    }

    private static int installAdHooks(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        count += hookReturn(module, cl, AD_IMPL, "isNoAd", String.class, true, mode);
        count += hookReturn(module, cl, AD_IMPL, "isListenWholeDay", true, mode);
        // 亮会员标：必须 true，否则「我的」页直接隐藏 VIP 卡片
        count += hookReturn(module, cl, AD_IMPL, "canShowVipRelational", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "canShowVipRelational", true, mode);
        count += hookReturn(module, cl, AD_IMPL, "isVipInvertExper", false, mode);
        count += hookReturn(module, cl, AD_IMPL, "isFakeVipActive", true, mode);
        count += installVipBadgeHooks(module, cl, mode);
        count += hookStaticReturn(module, cl, WHOLE_DAY_MANAGER, "C", true, mode);
        count += hookInspireRewardVerify(module, cl, mode);
        count += hookBooleanMethodsByNames(module, cl, AD_IMPL, AD_STRIP_FALSE, false, mode);
        count += hookBooleanMethodsByNames(module, cl, AD_IMPL, AD_STRIP_TRUE, true, mode);
        count += hookVoidMethodsByNames(module, cl, AD_IMPL, AD_STRIP_VOID, mode);
        count += hookReturn(module, cl, PATCH_ADAPTER, "checkPatchAdAvailable", String.class, String.class, false, mode);
        count += hookStaticReturn(module, cl, PATCH_CONFIG, "G", false, mode);
        return count;
    }

    private static int installVipBadgeHooks(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        count += hookReturn(module, cl, ACCT_MANAGER, "getIsUserNeedWeakenVip", false, mode);
        count += hookReturn(module, cl, ACCT_MANAGER, "vipReverse", false, mode);
        count += hookReturn(module, cl, MINE_IMPL, "getIsUserNeedWeakenVip", false, mode);
        count += hookReturn(module, cl, MINE_IMPL, "getIsUserNeedWeakenVipOnly", false, mode);
        count += hookReturn(module, cl, MINE_IMPL, "vipReverseEnable", false, mode);
        count += hookReturn(module, cl, MINE_IMPL, "isVipRemind", true, mode);
        count += hookUserVipTag(module, cl, mode);
        return count;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int hookUserVipTag(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> tagCls = Class.forName(USER_VIP_TAG, false, cl);
            Object vipTag = Enum.valueOf((Class) tagCls, "TagVIPUser");
            int count = 0;
            count += hookReturn(module, findMethod(Class.forName(ACCT_MANAGER, false, cl), "getUserVipTag", 0),
                    vipTag, mode);
            count += hookReturn(module, findMethod(Class.forName(MINE_IMPL, false, cl), "getUserVipTag", 0),
                    vipTag, mode);
            return count;
        } catch (Throwable t) {
            module.log(5, TAG, "UserVipTag hook failed: " + t.getMessage());
            return 0;
        }
    }

    /** AdImpl getters that should deny showing ads. */
    private static final String[] AD_STRIP_FALSE = {
            "isShowSplashFmAd",
            "isShowHotSplash",
            "canShowColdSplashAdForFrequency",
            "checkPatchAdAvailable",
            "getPatchAdEnable",
            "checkInspireAdAvailable",
            "canShowAdUnlockTimeDialog",
            "canShowAdUnlockTimeDialogNewScene",
            "isAdInterceptPlay",
            "adPlayIntercept",
            "isPatchAdAttachWindow",
            "isDownloadInspireEnable",
            "canShowDownloadHintIcon",
            "isMustOutRewardUnlockReadTime",
            "hasMineTabHeadWithUnlockEntrance",
            "canAutoShowListenWholeDayDialog",
            "unlockWholeDayShow",
            "canShowNightPrivilegeAfterUnlock",
            "canShowUnlockReadTaskProgressBar",
            "canUnlockInAdvance",
            "canContinueUnlockNightPrivilege",
            "isPatchClickInspireFeqCtrlEnable",
            "isNeedFreshForBookMallAd",
            "enableRewardUnlockReadTime",
            "isInfoFlowAdAtView",
            "isCommonAdValid",
            "isShowAdInfo",
            "isInAdUnlockGuideTest",
    };

    /** AdImpl flags that enable ad-free / revert modes. */
    private static final String[] AD_STRIP_TRUE = {
            "getNeedNotShowPatch",
            "isRequestNoAd",
            "isRequestingNoAd",
            "isInAllAdRevert",
            "isInTimeAdRevert",
            "isReversePlayerPageAd",
            "isReverseReadPageAd",
            "musicPatchAdBlockEnable",
            "musicPatchAdBlockShow",
            "musicPatchAdBlockRequest",
    };

    /** Skip ad load / unlock UI entry points. */
    private static final String[] AD_STRIP_VOID = {
            "getAdSplash",
            "getSplashAdView",
            "preloadInspireAd",
            "showUnlockAdTimeDialog",
            "showUnlockDownloadInspireDialog",
            "showAdUnlockGuideTips",
    };

    private static final String PATCH_ADAPTER =
            "com.dragon.read.reader.speech.ad.patch.PatchAndInfoFlowAdConfigAdapter";
    private static final String PATCH_CONFIG = "w02.c";

    private static int hookBooleanMethodsByNames(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String[] methodNames,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Set<String> targets = Set.of(methodNames);
            for (Method method : cls.getDeclaredMethods()) {
                if (!targets.contains(method.getName())) {
                    continue;
                }
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                count += hookReturn(module, method, value, mode);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "boolean batch " + className + " failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookVoidMethodsByNames(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String[] methodNames,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Set<String> targets = Set.of(methodNames);
            for (Method method : cls.getDeclaredMethods()) {
                if (!targets.contains(method.getName())) {
                    continue;
                }
                if (method.getReturnType() != void.class) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                String id = hookId(method);
                if (!HOOKED.add(id)) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method).setExceptionMode(mode).intercept(chain -> null);
                module.log(4, TAG, "hooked void " + id);
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "void batch " + className + " failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            Class<?> p1,
            Class<?> p2,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = findMethod(cls, methodName, 2, p1, p2);
            return method != null ? hookReturn(module, method, value, mode) : 0;
        } catch (Throwable t) {
            module.log(5, TAG, className + "#" + methodName + "(2) failed: " + t.getMessage());
            return 0;
        }
    }

    /** Force canReward=true on inspire ad verify ({@code r02.k#f}). */
    private static int hookInspireRewardVerify(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> listener = Class.forName(INSPIRE_LISTENER, false, cl);
            Method method = findMethod(
                    listener,
                    "f",
                    4,
                    int.class,
                    int.class,
                    boolean.class,
                    String.class
            );
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object canReward = chain.getArgs().get(0);
                if (canReward instanceof Integer && (Integer) canReward == 0) {
                    chain.getArgs().set(0, 1);
                    module.log(4, TAG, "inspire reward forced success");
                }
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, TAG, "inspire reward hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = findMethod(cls, methodName, 0);
            return method != null ? hookReturn(module, method, value, mode) : 0;
        } catch (Throwable t) {
            module.log(5, TAG, className + "#" + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            Class<?> paramType,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = findMethod(cls, methodName, 1, paramType);
            return method != null ? hookReturn(module, method, value, mode) : 0;
        } catch (Throwable t) {
            module.log(5, TAG, className + "#" + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookStaticReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = findMethod(cls, methodName, 0);
            if (method == null || !Modifier.isStatic(method.getModifiers())) {
                return 0;
            }
            return hookReturn(module, method, value, mode);
        } catch (Throwable t) {
            module.log(5, TAG, className + "#" + methodName + " static failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            Method method,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        return hookReturn(module, method, (Object) value, mode);
    }

    private static int hookReturn(
            ZoeModule module,
            Method method,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        if (method == null) {
            return 0;
        }
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            if (Modifier.isAbstract(method.getModifiers())) {
                HOOKED.remove(id);
                return 0;
            }
            Class<?> ret = method.getReturnType();
            if (ret == boolean.class) {
                boolean bool = value instanceof Boolean ? (Boolean) value : Boolean.TRUE.equals(value);
                module.hook(method).setExceptionMode(mode).intercept(chain -> bool);
            } else {
                module.hook(method).setExceptionMode(mode).intercept(chain -> value);
            }
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, TAG, "hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static int hookAfterPatch(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = findMethod(cls, methodName, 0);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                if (result == null) {
                    try {
                        result = Class.forName(VIP_INFO_MODEL, false, cl)
                                .getDeclaredConstructor()
                                .newInstance();
                    } catch (Throwable ignored) {
                    }
                }
                patchVipInfoModel(module, result);
                patchRpcVipInfo(module, result);
                return result;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, TAG, "after " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookBeforePatch(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Class<?> vipInfoModel = Class.forName(VIP_INFO_MODEL, false, cl);
            Method method = findMethod(cls, methodName, 1, vipInfoModel);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                patchVipInfoModel(module, chain.getArgs().get(0));
                patchRpcVipInfo(module, chain.getArgs().get(0));
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, TAG, "before " + className + "#" + methodName + " failed: "
                    + t.getMessage());
            return 0;
        }
    }

    private static int hookGsonVipParse(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> gson = Class.forName("com.google.gson.Gson", false, cl);
            Class<?> vipInfoModel = Class.forName(VIP_INFO_MODEL, false, cl);
            Class<?> rpcVipInfo = Class.forName(RPC_VIP_INFO, false, cl);
            for (Method method : gson.getDeclaredMethods()) {
                if (!"fromJson".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                if (method.getParameterTypes()[1] != Class.class) {
                    continue;
                }
                String id = hookId(method);
                if (!HOOKED.add(id)) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    Object typeArg = chain.getArgs().get(1);
                    if (typeArg instanceof Class<?> type
                            && (vipInfoModel.isAssignableFrom(type)
                            || rpcVipInfo.isAssignableFrom(type))) {
                        patchVipInfoModel(module, result);
                        patchRpcVipInfo(module, result);
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "Gson hook failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static void patchVipInfoModel(ZoeModule module, Object model) {
        if (model == null || !VIP_INFO_MODEL.equals(model.getClass().getName())) {
            return;
        }
        try {
            setBooleanField(model, "isVip", true);
            setBooleanField(model, "isContinuousVip", true);
            setBooleanField(model, "hasBeenContinuousVip", true);
            setField(model, "expireTime", VIP_EXPIRE_SECONDS);
            setField(model, "leftTime", "99999");
            setField(model, "label", VIP_LABEL);
            setField(model, "msg", VIP_MSG);
        } catch (Throwable t) {
            module.log(5, TAG, "patch VipInfoModel: " + t.getMessage());
        }
    }

    private static void patchRpcVipInfo(ZoeModule module, Object model) {
        if (model == null || !RPC_VIP_INFO.equals(model.getClass().getName())) {
            return;
        }
        try {
            setBooleanField(model, "isVip", true);
            setBooleanField(model, "isContinuousVip", true);
            setBooleanField(model, "hasBeenContinuousVip", true);
            setBooleanField(model, "isVIPOffline", false);
            setField(model, "expireTime", VIP_EXPIRE_SECONDS);
            setField(model, "leftTime", "99999");
            setField(model, "label", VIP_LABEL);
            setField(model, "msg", VIP_MSG);
        } catch (Throwable t) {
            module.log(5, TAG, "patch VipInfo: " + t.getMessage());
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getField(name);
        field.setBoolean(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getField(name);
        field.set(target, value);
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount, Class<?>... paramTypes) {
        for (Method method : cls.getDeclaredMethods()) {
            if (!matches(method, name, paramCount, paramTypes)) {
                continue;
            }
            return method;
        }
        for (Method method : cls.getMethods()) {
            if (!matches(method, name, paramCount, paramTypes)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private static boolean matches(
            Method method,
            String name,
            int paramCount,
            Class<?>... paramTypes
    ) {
        if (!name.equals(method.getName())) {
            return false;
        }
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != paramCount) {
            return false;
        }
        if (paramCount == 0) {
            return true;
        }
        if (paramTypes.length != paramCount) {
            return paramTypes.length == 1 && actual[0].isAssignableFrom(paramTypes[0]);
        }
        for (int i = 0; i < paramCount; i++) {
            if (!actual[i].isAssignableFrom(paramTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static String hookId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }
}
