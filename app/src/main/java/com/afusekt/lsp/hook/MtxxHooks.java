package com.afusekt.lsp.hook;

import android.app.Application;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy XposedBridge hooks for Meitu Xiuxiu ({@code com.mt.mtxx.mtxx}).
 */
public final class MtxxHooks {

    private static final String TAG = MainHook.TAG + ":Mtxx";
    private static final int VIP_TYPE = 1;
    private static final int VALID_FLAG = 1;
    private static final long VIP_VALID_TIME = 4_102_444_800_000L;
    private static final Integer VIP_TYPE_BOXED = VIP_TYPE;
    private static final Integer VALID_USER_BOXED = VALID_FLAG;

    private MtxxHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        int hooks = 0;
        hooks += hookApplicationLog(cl);
        hooks += hookBoolean(cl, "com.meitu.module.ModuleVipApiImpl", "isVip");
        hooks += hookBoolean(cl, "com.meitu.module.ModuleVipApiImpl", "isVipInit");
        hooks += hookBooleanMethods(cl, "com.meitu.module.ModuleVipApiImpl", "canCopy");
        hooks += hookBooleanMethods(cl, "com.meitu.module.ModuleVipApiImpl", "canPreviewMaterial");
        hooks += hookBooleanMethods(cl, "com.meitu.module.ModuleVipApiImpl", "hasLeftTimes");
        hooks += hookInt(cl, "com.meitu.module.ModuleVipApiImpl", "getVipType", VIP_TYPE);
        hooks += hookBoolean(cl, "com.meitu.module.MaterialKitApiImpl", "isVip");
        hooks += hookBooleanMethods(cl, "com.meitu.module.MaterialKitApiImpl", "isPassedByVipUserData");
        hooks += hookBoolean(cl, "com.meitu.account.UserMemberInfo", "getIsVip");
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.VipInfoBean", "isVip");
        hooks += hookObject(cl, "com.meitu.vip.resp.bean.VipInfoBean", "getVip_type", VIP_TYPE_BOXED);
        hooks += hookObject(cl, "com.meitu.vip.resp.bean.VipInfoBean", "is_valid_user", VALID_USER_BOXED);
        hooks += hookLong(cl, "com.meitu.vip.resp.bean.VipInfoBean", "getValid_time", VIP_VALID_TIME);
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.UserInfoBean", "isVip");
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.UserInfoBean", "isSvip");
        hooks += hookInt(cl, "com.meitu.vip.resp.bean.UserInfoBean", "getVipType", VIP_TYPE);
        hooks += hookInt(cl, "com.meitu.vip.resp.bean.UserInfoBean", "isValidVip", VALID_FLAG);
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.RightsCompBean", "isVipValid");
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.RightsCompBean", "isSvipValid");
        hooks += hookBoolean(cl, "com.meitu.mtcommunity.common.bean.UserBean", "isVip");
        hooks += hookInt(cl, "com.meitu.mtcommunity.common.bean.UserBean", "getVip_type", VIP_TYPE);
        hooks += hookBoolean(cl, "com.meitu.library.account.bean.AccountSdkLoginSsoCheckBean$DataBean", "isVip");
        hooks += hookBoolean(cl, "com.meitu.library.account.bean.AccountSdkUserHistoryBean", "isVip");
        hooks += hookBoolean(cl, "com.meitu.vip.resp.bean.VipMaterialInfo", "isSVipMaterial");
        hooks += hookBoolean(cl, "com.meitu.vip.module.s", "isVip");
        hooks += hookBoolean(cl, "com.meitu.vip.module.e$q", "isVip");
        hooks += hookVipInfoRespGetData(cl);
        hooks += hookXxVipUtil(cl);
        XposedBridge.log(TAG + ": hooks installed (" + hooks + ")");
    }

    private static int hookApplicationLog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (MainHook.MTXX_PACKAGE.equals(app.getPackageName())) {
                        XposedBridge.log(TAG + ": active in " + app.getPackageName());
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookVipInfoRespGetData(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.meitu.vip.resp.VipInfoResp", cl, "getData", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            patchVipInfoBean(param.getResult());
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void patchVipInfoBean(Object bean) {
        if (bean == null) {
            return;
        }
        try {
            XposedHelpers.callMethod(bean, "setVip_type", VIP_TYPE_BOXED);
            XposedHelpers.callMethod(bean, "set_valid_user", VALID_USER_BOXED);
            XposedHelpers.callMethod(bean, "setValid_time", VIP_VALID_TIME);
        } catch (Throwable ignored) {
        }
    }

    private static int hookXxVipUtil(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> xxVipUtil = XposedHelpers.findClass("com.meitu.vip.util.XXVipUtil", cl);
            for (java.lang.reflect.Method method : xxVipUtil.getDeclaredMethods()) {
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true));
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": XXVipUtil hooks skipped: " + t.getMessage());
        }
        return count;
    }

    private static int hookBooleanMethods(ClassLoader cl, String className, String methodName) {
        int count = 0;
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            for (java.lang.reflect.Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(true));
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookBooleanMethods failed " + className + "#" + methodName
                    + ": " + t.getMessage());
        }
        return count;
    }

    private static int hookBoolean(ClassLoader cl, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, methodName, XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookInt(ClassLoader cl, String className, String methodName, int value) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookLong(ClassLoader cl, String className, String methodName, long value) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookObject(ClassLoader cl, String className, String methodName, Object value) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
