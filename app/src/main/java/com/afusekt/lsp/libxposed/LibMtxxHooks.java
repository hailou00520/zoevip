package com.afusekt.lsp.libxposed;

import android.app.Application;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Meitu Xiuxiu ({@code com.mt.mtxx.mtxx}) VIP unlock via libxposed.
 * Tested target: 12.17.0 (121700).
 */
public final class LibMtxxHooks {

    private static final String TAG = ZoeIds.TAG + ":Mtxx";

    /** 1 = 粉钻 VIP (app internal vip_type). */
    private static final int VIP_TYPE = 1;
    private static final int VALID_FLAG = 1;
    private static final long VIP_VALID_TIME = 4_102_444_800_000L;
    private static final Integer VIP_TYPE_BOXED = VIP_TYPE;
    private static final Integer VALID_USER_BOXED = VALID_FLAG;

    private static final String MODULE_VIP_API = "com.meitu.module.ModuleVipApiImpl";
    private static final String MATERIAL_KIT_API = "com.meitu.module.MaterialKitApiImpl";
    private static final String USER_MEMBER_INFO = "com.meitu.account.UserMemberInfo";
    private static final String VIP_INFO_BEAN = "com.meitu.vip.resp.bean.VipInfoBean";
    private static final String VIP_INFO_RESP = "com.meitu.vip.resp.VipInfoResp";
    private static final String USER_INFO_BEAN = "com.meitu.vip.resp.bean.UserInfoBean";
    private static final String RIGHTS_COMP_BEAN = "com.meitu.vip.resp.bean.RightsCompBean";
    private static final String USER_BEAN = "com.meitu.mtcommunity.common.bean.UserBean";
    private static final String ACCOUNT_SSO_BEAN =
            "com.meitu.library.account.bean.AccountSdkLoginSsoCheckBean$DataBean";
    private static final String ACCOUNT_HISTORY_BEAN =
            "com.meitu.library.account.bean.AccountSdkUserHistoryBean";
    private static final String VIP_MATERIAL_INFO = "com.meitu.vip.resp.bean.VipMaterialInfo";
    private static final String XX_VIP_UTIL = "com.meitu.vip.util.XXVipUtil";
    private static final String VIP_MODULE_S = "com.meitu.vip.module.s";
    private static final String VIP_MODULE_E = "com.meitu.vip.module.e$q";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LibMtxxHooks() {
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
                                && ZoeIds.MTXX_PACKAGE.equals(application.getPackageName())) {
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
            Class.forName(MODULE_VIP_API, false, cl);
            Class.forName(XX_VIP_UTIL, false, cl);
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
        count += hookReturn(module, cl, MODULE_VIP_API, "isVip", true, mode);
        count += hookReturn(module, cl, MODULE_VIP_API, "isVipInit", true, mode);
        count += hookReturn(module, cl, MODULE_VIP_API, "getVipType", VIP_TYPE, mode);
        count += hookBooleanMethods(module, cl, MODULE_VIP_API, "canCopy", mode);
        count += hookBooleanMethods(module, cl, MODULE_VIP_API, "canPreviewMaterial", mode);
        count += hookBooleanMethods(module, cl, MODULE_VIP_API, "hasLeftTimes", mode);

        count += hookReturn(module, cl, MATERIAL_KIT_API, "isVip", true, mode);
        count += hookBooleanMethods(module, cl, MATERIAL_KIT_API, "isPassedByVipUserData", mode);

        count += hookReturn(module, cl, USER_MEMBER_INFO, "getIsVip", true, mode);

        count += hookReturn(module, cl, VIP_INFO_BEAN, "isVip", true, mode);
        count += hookReturn(module, cl, VIP_INFO_BEAN, "isGoogleSubVip", true, mode);
        count += hookReturn(module, cl, VIP_INFO_BEAN, "getVip_type", VIP_TYPE_BOXED, mode);
        count += hookReturn(module, cl, VIP_INFO_BEAN, "is_valid_user", VALID_USER_BOXED, mode);
        count += hookReturn(module, cl, VIP_INFO_BEAN, "getValid_time", VIP_VALID_TIME, mode);

        count += hookReturn(module, cl, USER_INFO_BEAN, "isVip", true, mode);
        count += hookReturn(module, cl, USER_INFO_BEAN, "isSvip", true, mode);
        count += hookReturn(module, cl, USER_INFO_BEAN, "getVipType", VIP_TYPE, mode);
        count += hookReturn(module, cl, USER_INFO_BEAN, "isValidVip", VALID_FLAG, mode);

        count += hookReturn(module, cl, RIGHTS_COMP_BEAN, "isVipValid", true, mode);
        count += hookReturn(module, cl, RIGHTS_COMP_BEAN, "isSvipValid", true, mode);

        count += hookReturn(module, cl, USER_BEAN, "isVip", true, mode);
        count += hookReturn(module, cl, USER_BEAN, "getVip_type", VIP_TYPE, mode);

        count += hookReturn(module, cl, ACCOUNT_SSO_BEAN, "isVip", true, mode);
        count += hookReturn(module, cl, ACCOUNT_HISTORY_BEAN, "isVip", true, mode);

        count += hookReturn(module, cl, VIP_MATERIAL_INFO, "isSVipMaterial", true, mode);

        count += hookReturn(module, cl, VIP_MODULE_S, "isVip", true, mode);
        count += hookReturn(module, cl, VIP_MODULE_E, "isVip", true, mode);
        count += hookReturn(module, cl, VIP_MODULE_S, "getVipType", VIP_TYPE, mode);
        count += hookReturn(module, cl, VIP_MODULE_E, "getVipType", VIP_TYPE, mode);

        count += hookVipInfoRespGetData(module, cl, mode);
        count += hookXxVipUtilBooleanGetters(module, cl, mode);
        return count;
    }

    private static int hookVipInfoRespGetData(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(loadClass(cl, VIP_INFO_RESP), "getData", 0);
        if (method == null) {
            return 0;
        }
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                patchVipInfoBean(module, result);
                return result;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, TAG, "hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static void patchVipInfoBean(ZoeModule module, Object bean) {
        if (bean == null) {
            return;
        }
        safeCall(module, bean, "setVip_type", VIP_TYPE_BOXED);
        safeCall(module, bean, "set_valid_user", VALID_USER_BOXED);
        safeCall(module, bean, "setValid_time", VIP_VALID_TIME);
    }

    private static void safeCall(ZoeModule module, Object target, String methodName, Object arg) {
        try {
            Method method = findMethod(target.getClass(), methodName, 1);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, arg);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "patch " + methodName + " failed: " + t.getMessage());
        }
    }

    private static int hookBooleanMethods(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> cls = Class.forName(className, false, cl);
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                count += hookReturn(module, method, true, mode);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "boolean hooks skipped " + className + "#" + methodName
                    + ": " + t.getMessage());
        }
        return count;
    }

    /** XXVipUtil Kotlin object: hook all no-arg boolean getters (static + instance). */
    private static int hookXxVipUtilBooleanGetters(
            ZoeModule module,
            ClassLoader cl,
            XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> xxVipUtil = Class.forName(XX_VIP_UTIL, false, cl);
            for (Method method : xxVipUtil.getDeclaredMethods()) {
                if (method.getReturnType() != boolean.class) {
                    continue;
                }
                if (method.getParameterTypes().length != 0) {
                    continue;
                }
                count += hookReturn(module, method, true, mode);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "XXVipUtil hooks skipped: " + t.getMessage());
        }
        return count;
    }

    private static Class<?> loadClass(ClassLoader cl, String className) {
        try {
            return Class.forName(className, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            return hookReturn(module, cls, methodName, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findMethod(cls, methodName, 0);
        return method != null ? hookReturn(module, method, value, mode) : 0;
    }

    private static int hookReturn(
            ZoeModule module,
            Method method,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
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
            } else if (ret == int.class && value instanceof Number) {
                int number = ((Number) value).intValue();
                module.hook(method).setExceptionMode(mode).intercept(chain -> number);
            } else if (ret == long.class && value instanceof Number) {
                long number = ((Number) value).longValue();
                module.hook(method).setExceptionMode(mode).intercept(chain -> number);
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

    private static String hookId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        if (cls == null) {
            return null;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }
}
