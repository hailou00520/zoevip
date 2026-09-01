package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 番茄免费小说 / 红果免费短剧 VIP unlock via libxposed.
 * Target 7.2.9.32 — {@code PrivilegeManager} native gates + {@code NsVipImpl}.
 */
public final class LibFanqieNovelHooks {

    private static final String TAG = ZoeIds.TAG + ":FanqieNovel";

    private static final String PRIVILEGE_MANAGER =
            "com.dragon.read.component.biz.impl.privilege.PrivilegeManager";
    private static final String NS_VIP_IMPL =
            "com.dragon.read.component.biz.impl.NsVipImpl";
    private static final String VIP_INFO_MODEL =
            "com.dragon.read.user.model.VipInfoModel";
    private static final String VIP_SUB_TYPE =
            "com.dragon.read.rpc.model.VipCommonSubType";
    private static final String VIP_ENTRANCE =
            "com.dragon.read.component.biz.api.VipEntrance";
    private static final String ACCT_MANAGER = "com.dragon.read.user.AcctManager";
    private static final String NET_REQ_UTIL = "com.dragon.read.util.NetReqUtil";
    private static final String NET_DEPEND_IMPL =
            "com.dragon.read.component.base.NsBaseNetworkDependImpl";
    private static final String NET_DEPEND_FACADE =
            "com.dragon.read.base.depend.NsBaseNetworkDependImpl";
    private static final String TOAST_UTILS = "com.dragon.read.util.ToastUtils";
    private static final String READER_SERVICE_T = "com.dragon.read.reader.services.t";
    private static final String PROFILE_SOCIAL_LAYOUT =
            "com.dragon.read.social.ui.ProfileSocialRecordLayout";
    private static final String COMMENT_USER_STR_INFO =
            "com.dragon.read.rpc.model.CommentUserStrInfo";

    /**
     * 5555-05-20 00:00:00 UTC ≈ 我爱你 forever.
     * Always force this expire — banner hides duration when expire parses to 0.
     */
    private static final long VIP_EXPIRE_SECONDS = 113143651200L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean PAY_UNLOCK_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean BADGE_FORCE_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean APP_CREATE_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LibFanqieNovelHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        tryInstall(module, cl, "package-ready");
        hookApplicationOnCreate(module, cl);
        scheduleDeferredUnlocks(module, cl);
    }

    private static void hookApplicationOnCreate(ZoeModule module, ClassLoader cl) {
        if (!APP_CREATE_HOOKED.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        // Tinker/MuteApplication 覆盖了 Application.onCreate，必须直接 hook 子类
        String[] targets = {
                "com.tencent.tinker.loader.MuteApplication",
                "com.dragon.read.app.AbsApplication",
                "android.app.Application"
        };
        for (String name : targets) {
            try {
                Class<?> clazz = Class.forName(name, false, cl);
                Method onCreate = clazz.getDeclaredMethod("onCreate");
                String id = hookId(onCreate) + "#zoe-app";
                if (!HOOKED.add(id)) {
                    continue;
                }
                onCreate.setAccessible(true);
                module.hook(onCreate).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    Object app = chain.getThisObject();
                    if (app instanceof Application application
                            && ZoeIds.isDragonReadFamily(application.getPackageName())) {
                        ClassLoader appCl = application.getClassLoader();
                        tryInstall(module, appCl, "application");
                        scheduleDeferredUnlocks(module, appCl);
                        showInjectToast(application);
                    }
                    return result;
                });
                module.log(4, TAG, "hooked " + id);
            } catch (Throwable t) {
                module.log(5, TAG, "app-onCreate " + name + ": " + t.getMessage());
            }
        }
    }

    private static void showInjectToast(Application application) {
        try {
            String pkg = application.getPackageName();
            // 子进程不弹，避免多进程重复提醒
            String process = application.getApplicationInfo().processName;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    process = Application.getProcessName();
                }
            } catch (Throwable ignored) {
            }
            if (process != null && !pkg.equals(process)) {
                return;
            }
            if (!TOAST_SHOWN.compareAndSet(false, true)) {
                return;
            }
            String tip;
            if (ZoeIds.KYLIN_PACKAGE.equals(pkg)) {
                tip = "ZoeVIP 已注入红果漫剧";
            } else if (ZoeIds.HONGGUO_PACKAGE.equals(pkg)) {
                tip = "ZoeVIP 已注入红果短剧";
            } else {
                tip = "ZoeVIP 已注入番茄小说";
            }
            android.widget.Toast.makeText(
                    application, tip, android.widget.Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleDeferredUnlocks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        Runnable retry = () -> {
            if (!PAY_UNLOCK_DONE.get()) {
                int n = hookShortSeriesPayUnlock(module, cl, mode);
                if (n > 0) {
                    PAY_UNLOCK_DONE.set(true);
                    module.log(4, TAG, "pay-unlock retry ok (" + n + ")");
                }
            }
            if (!BADGE_FORCE_DONE.get()) {
                int n = hookMineVipBadgeForce(module, cl, mode);
                if (n > 0) {
                    BADGE_FORCE_DONE.set(true);
                    module.log(4, TAG, "vip-badge retry ok (" + n + ")");
                }
            }
        };
        try {
            retry.run();
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(retry, 1500L);
            handler.postDelayed(retry, 4000L);
            handler.postDelayed(retry, 10000L);
            handler.postDelayed(retry, 25000L);
        } catch (Throwable t) {
            module.log(5, TAG, "deferred unlock schedule failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(PRIVILEGE_MANAGER, false, cl);
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
            clearChapterBlacklist(cl);
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, TAG, "hooks failed: " + t.getMessage(), t);
        }
    }

    private static int installHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = 0;
        count += hookIllegalAccessShield(module, cl, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isVip", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isAnyVip", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "canShowVipRelational", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasNoAdPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasNoAdFollAllScene", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasNoAdForShortSeries", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasNoAdReadConsumptionPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isForeverNoAd", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "canReadShortStory", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasVipShortSeriesPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "adVipAvailable", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasReadPaidBookPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasOfflineReadingPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasAutoPagePrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasInspireBookPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasTtsPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasTtsConsumptionPrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasTtsNaturePrivilege", true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasBookDownloadPrivilege", String.class, true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "hasPrivilege", String.class, true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isNoAd", String.class, true, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isBookAdFree", String.class, 1, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "showPayVipEntranceInChapterEnd", false, mode);
        count += hookReturn(module, cl, PRIVILEGE_MANAGER, "isFakeVipActive", true, mode);
        count += hookSubtypeBoolean(module, cl, PRIVILEGE_MANAGER, "b", mode);
        count += hookGetVipInfo(module, cl, mode);
        count += hookGetVipInfoBySubtype(module, cl, mode);
        count += hookGetAllVipInfo(module, cl, mode);
        count += hookUpdateVipInfo(module, cl, mode);
        count += hookUpdateVipInfoList(module, cl, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "isVipEnable", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "isAnyVip", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "canShowMulVip", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "canShowVipCenter", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "canShowVipEntranceInAd", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "willShowNativeBanner", true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "canReadPaidBookEnhance", boolean.class, true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "canListenPaidBook", boolean.class, true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "isDisableVipInGoogle", false, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "needShowVipIcon", boolean.class, true, mode);
        count += hookReturn(module, cl, NS_VIP_IMPL, "getShowVipIconVisibility", boolean.class, 0, mode);
        count += hookSubtypeBoolean(module, cl, NS_VIP_IMPL, "isVip", mode);
        count += hookVipEntrance(module, cl, mode);
        count += hookVariantMineVipEntrance(module, cl, mode);
        count += hookVipEntranceAb(module, cl, mode);
        count += hookReturn(module, cl, ACCT_MANAGER, "adVipAvailable", true, mode);
        count += hookReturn(module, cl, ACCT_MANAGER, "isOfficial", false, mode);
        count += hookGsonVipParse(module, cl, mode);
        int pay = hookShortSeriesPayUnlock(module, cl, mode);
        count += pay;
        if (pay > 0) {
            PAY_UNLOCK_DONE.set(true);
        }
        int badge = hookMineVipBadgeForce(module, cl, mode);
        count += badge;
        if (badge > 0) {
            BADGE_FORCE_DONE.set(true);
        }
        count += hookProvideVipIcon(module, cl, mode);
        count += hookProfileStats(module, cl, mode);
        // 插件/Tinker 晚加载时再补挂
        scheduleDeferredUnlocks(module, cl);
        return count;
    }

    private static int hookIllegalAccessShield(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        count += hookVoidNoop(module, cl, NET_DEPEND_IMPL, "assertIllegalAccess", mode);
        count += hookVoidNoop(module, cl, NET_DEPEND_FACADE, "assertIllegalAccess", mode);
        count += hookRewriteIllegalAccessCode(module, cl, mode);
        count += hookUnsafeToastFilter(module, cl, mode);
        return count;
    }

    private static int hookVoidNoop(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(Class.forName(className, false, cl), methodName, 0);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> null);
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookRewriteIllegalAccessCode(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Method analyse = findMethod(
                    Class.forName(NET_REQ_UTIL, false, cl),
                    "analyseCode", 3, Throwable.class, int.class, Object.class);
            if (analyse != null && HOOKED.add(hookId(analyse))) {
                analyse.setAccessible(true);
                module.hook(analyse).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Integer && (Integer) result == 110) {
                        neutralizeResponseCode(chain.getArgs().get(2));
                        return 0;
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method parse = findMethod(
                    Class.forName(NET_REQ_UTIL, false, cl),
                    "parseResponseCode", 1, Object.class);
            if (parse != null && HOOKED.add(hookId(parse))) {
                parse.setAccessible(true);
                module.hook(parse).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Integer && (Integer) result == 110) {
                        neutralizeResponseCode(chain.getArgs().get(0));
                        return 0;
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookUnsafeToastFilter(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> toastUtils = Class.forName(TOAST_UTILS, false, cl);
            for (Method method : toastUtils.getDeclaredMethods()) {
                if (!method.getName().startsWith("showCommonToast")) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1) {
                    continue;
                }
                if (params[0] != String.class && params[0] != int.class) {
                    continue;
                }
                String id = hookId(method);
                if (!HOOKED.add(id)) {
                    continue;
                }
                method.setAccessible(true);
                final boolean resId = params[0] == int.class;
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object first = chain.getArgs().get(0);
                    if (resId) {
                        if (isUnsafeToastRes(cl, first)) {
                            return null;
                        }
                    } else if (isUnsafeToast(first)) {
                        return null;
                    }
                    return chain.proceed();
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "toast filter failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static boolean isUnsafeToastRes(ClassLoader cl, Object resId) {
        if (!(resId instanceof Integer)) {
            return false;
        }
        try {
            Object ctx = Class.forName("com.dragon.read.base.util.AppUtils", false, cl)
                    .getMethod("context")
                    .invoke(null);
            if (!(ctx instanceof android.content.Context)) {
                return false;
            }
            return isUnsafeToast(((android.content.Context) ctx).getString((Integer) resId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isUnsafeToast(Object message) {
        if (!(message instanceof CharSequence)) {
            return false;
        }
        String text = message.toString();
        return text.contains("版本不安全")
                || text.contains("正规应用市场")
                || text.contains("当前版本不安全");
    }

    private static void neutralizeResponseCode(Object response) {
        if (response == null) {
            return;
        }
        try {
            Field codeField = response.getClass().getField("code");
            Object code = codeField.get(response);
            if (code instanceof Integer) {
                if ((Integer) code == 110) {
                    codeField.set(response, 0);
                }
                return;
            }
            if (code instanceof Enum<?>) {
                Object success = null;
                for (Object constant : code.getClass().getEnumConstants()) {
                    Enum<?> e = (Enum<?>) constant;
                    if ("SUCCESS".equals(e.name())) {
                        success = constant;
                        break;
                    }
                    try {
                        Method getValue = constant.getClass().getMethod("getValue");
                        Object value = getValue.invoke(constant);
                        if (value instanceof Integer && (Integer) value == 0) {
                            success = constant;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (success != null) {
                    codeField.set(response, success);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearChapterBlacklist(ClassLoader cl) {
        try {
            Class<?> service = Class.forName(READER_SERVICE_T, false, cl);
            Field helperField = service.getDeclaredField("f204726b");
            helperField.setAccessible(true);
            Object helper = helperField.get(null);
            if (helper == null) {
                return;
            }
            Field setField = helper.getClass().getDeclaredField("f204964a");
            setField.setAccessible(true);
            Object set = setField.get(helper);
            if (set instanceof HashSet) {
                ((HashSet<?>) set).clear();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int hookVipEntrance(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> entrance = Class.forName(VIP_ENTRANCE, false, cl);
            Method method = findMethod(
                    Class.forName(NS_VIP_IMPL, false, cl), "canShowVipEntranceHere", 1, entrance);
            return hookReturn(module, method, true, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookVariantMineVipEntrance(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> entryType = Class.forName(
                    "com.dragon.read.rpc.model.MineVipEntryType", false, cl);
            Method method = findMethod(
                    Class.forName(NS_VIP_IMPL, false, cl),
                    "canShowVariantMineVipEntrance", 1, entryType);
            return hookReturn(module, method, true, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookVipEntranceAb(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> entrance = Class.forName(VIP_ENTRANCE, false, cl);
            Method method = findMethod(Class.forName("ey3.m4", false, cl), "a", 1, entrance);
            return hookReturn(module, method, true, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            boolean value, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(Class.forName(className, false, cl), methodName, 0);
            return hookReturn(module, method, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            Class<?> paramType, boolean value, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(Class.forName(className, false, cl), methodName, 1, paramType);
            return hookReturn(module, method, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            Class<?> paramType, int value, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(Class.forName(className, false, cl), methodName, 1, paramType);
            return hookReturn(module, method, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookSubtypeBoolean(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> subType = Class.forName(VIP_SUB_TYPE, false, cl);
            Method method = findMethod(Class.forName(className, false, cl), methodName, 1, subType);
            return hookReturn(module, method, true, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module, Method method, Object value, XposedInterface.ExceptionMode mode
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
            } else if (ret == int.class && value instanceof Number) {
                int number = ((Number) value).intValue();
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

    private static int hookGetVipInfo(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        try {
            Method method = findMethod(Class.forName(PRIVILEGE_MANAGER, false, cl), "getVipInfo", 0);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain ->
                    forgeVipInfo(module, cl, chain.proceed(), null));
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGetVipInfoBySubtype(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> subType = Class.forName(VIP_SUB_TYPE, false, cl);
            Method method = findMethod(
                    Class.forName(PRIVILEGE_MANAGER, false, cl), "getVipInfo", 1, subType);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain ->
                    forgeVipInfo(module, cl, chain.proceed(), chain.getArgs().get(0)));
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGetAllVipInfo(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(Class.forName(PRIVILEGE_MANAGER, false, cl), "getAllVipInfo", 0);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            Object[] subtypes = allSubtypes(cl);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                if (subtypes == null) {
                    return chain.proceed();
                }
                ArrayList<Object> list = new ArrayList<>();
                for (Object subtype : subtypes) {
                    Object forged = forgeVipInfo(module, cl, null, subtype);
                    if (forged != null) {
                        list.add(forged);
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookUpdateVipInfo(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> vipCls = Class.forName(VIP_INFO_MODEL, false, cl);
            Method method = findMethod(
                    Class.forName(PRIVILEGE_MANAGER, false, cl),
                    "updateVipInfo", 2, vipCls, boolean.class);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                chain.getArgs().set(0, forgeVipInfo(module, cl, chain.getArgs().get(0), null));
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Patch RPC VipInfo list before it is converted into VipInfoModel cache. */
    private static int hookUpdateVipInfoList(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Method method = findMethod(
                    Class.forName(PRIVILEGE_MANAGER, false, cl), "updateVipInfoList", 1, List.class);
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            String expire = String.valueOf(VIP_EXPIRE_SECONDS);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object arg = chain.getArgs().get(0);
                if (arg instanceof List<?> list) {
                    long leftSeconds = Math.max(1L, VIP_EXPIRE_SECONDS - (System.currentTimeMillis() / 1000));
                    String left = String.valueOf(leftSeconds);
                    for (Object item : list) {
                        if (item == null) {
                            continue;
                        }
                        try {
                            Field expireField = item.getClass().getField("expireTime");
                            Field isVipField = item.getClass().getField("isVip");
                            Field leftField = item.getClass().getField("leftTime");
                            Field adVipField = item.getClass().getField("isAdVip");
                            expireField.set(item, expire);
                            isVipField.set(item, "1");
                            leftField.set(item, left);
                            adVipField.setBoolean(item, true);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGsonVipParse(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> gson = Class.forName("com.google.gson.Gson", false, cl);
            Class<?> vipInfoModel = Class.forName(VIP_INFO_MODEL, false, cl);
            Class<?> bookPayDetail = optionalClass(cl, "com.dragon.read.rpc.model.BookPayDetail");
            Class<?> bookPayDetailData = optionalClass(cl, "com.dragon.read.rpc.model.BookPayDetailData");
            Class<?> episodeInfo = optionalClass(cl, "com.dragon.read.rpc.model.EpisodeInfo");
            Class<?> saasDirItem = optionalClass(
                    cl, "com.dragon.read.component.shortvideo.data.saas.rpcmodel.SaasVideoDirectoryItem");
            Class<?> videoDirItem = optionalClass(cl, "com.dragon.read.rpc.model.VideoDirectoryItem");
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
                    if (!(typeArg instanceof Class<?> type) || result == null) {
                        return result;
                    }
                    if (vipInfoModel.isAssignableFrom(type)) {
                        return forgeVipInfo(module, cl, result, null);
                    }
                    if (bookPayDetail != null && bookPayDetail.isAssignableFrom(type)) {
                        unlockBookPayDetail(result);
                        return result;
                    }
                    if (bookPayDetailData != null && bookPayDetailData.isAssignableFrom(type)) {
                        unlockBookPayDetailData(result);
                        return result;
                    }
                    if (episodeInfo != null && episodeInfo.isAssignableFrom(type)) {
                        unlockNeedUnlockFlags(result);
                        return result;
                    }
                    if ((saasDirItem != null && saasDirItem.isAssignableFrom(type))
                            || (videoDirItem != null && videoDirItem.isAssignableFrom(type))) {
                        unlockNeedUnlockFlags(result);
                        return result;
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

    /**
     * 红果短剧付费锁：BookPayDetail.paid / needUnlock / PaySeriesLockConfig / wq4 判断。
     */
    private static int hookShortSeriesPayUnlock(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        count += hookPaySeriesLockViaSsConfig(module, cl, mode);
        // PaySeriesLockConfig companion.a() → enablePaySeriesLock=false
        try {
            Class<?> cfg = Class.forName(
                    "com.dragon.read.component.shortvideo.impl.config.PaySeriesLockConfig", false, cl);
            Class<?> holder = resolvePaySeriesHolder(cfg, cl);
            if (holder != null) {
                Method a = findMethod(holder, "a", 0);
                if (a != null) {
                    String id = hookId(a);
                    if (!HOOKED.contains(id)) {
                        a.setAccessible(true);
                        Object unlocked = newUnlockedPaySeriesConfig(cfg);
                        if (unlocked != null && HOOKED.add(id)) {
                            Object unlockedCfg = unlocked;
                            module.hook(a).setExceptionMode(mode).intercept(chain -> unlockedCfg);
                            module.log(4, TAG, "hooked " + id);
                            count++;
                        }
                    } else {
                        count++; // already hooked
                    }
                }
            }
        } catch (Throwable t) {
            module.log(5, TAG, "PaySeriesLockConfig hook failed: " + t.getMessage());
        }
        // SeriesPayContentServiceImpl.f(String,boolean) → patch BookPayDetail
        try {
            Class<?> svc = Class.forName(
                    "com.dragon.read.component.shortvideo.impl.paycontent.model.SeriesPayContentServiceImpl",
                    false, cl);
            Method f = findMethod(svc, "f", 2, String.class, boolean.class);
            if (f == null) {
                f = findMethod(svc, "b", 2, String.class, boolean.class);
            }
            if (f != null) {
                String id = hookId(f);
                if (HOOKED.add(id)) {
                    f.setAccessible(true);
                    module.hook(f).setExceptionMode(mode).intercept(chain -> {
                        Object result = chain.proceed();
                        unlockSeriesPayDetailWrapper(result);
                        return result;
                    });
                    module.log(4, TAG, "hooked " + id);
                    count++;
                } else {
                    count++;
                }
            }
        } catch (Throwable t) {
            // class often late-loaded via Mira; deferred retry handles it
        }
        // wq4.c.f(BookPayDetail) → false (非付费锁剧)
        count += hookWq4PayCheck(module, cl, "f", false, mode);
        // wq4.c.b(BookPayDetail, String) → "0" (当前集未锁)
        count += hookWq4PayString(module, cl, "b", "0", mode);
        // wq4.c.c(BookPayDetail) → "1" (已购买)
        count += hookWq4PayString(module, cl, "c", "1", mode);
        return count;
    }

    private static Class<?> resolvePaySeriesHolder(Class<?> cfg, ClassLoader cl) {
        String[] names = {
                cfg.getName() + "$a",
                cfg.getName() + "$Companion",
                cfg.getName() + "$A"
        };
        for (String name : names) {
            try {
                Class<?> holder = Class.forName(name, false, cl);
                if (findMethod(holder, "a", 0) != null) {
                    return holder;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            for (Class<?> nested : cfg.getDeclaredClasses()) {
                if (findMethod(nested, "a", 0) != null) {
                    return nested;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            for (Field field : cfg.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type != null
                        && type.getName().startsWith(cfg.getName() + "$")
                        && findMethod(type, "a", 0) != null) {
                    return type;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object newUnlockedPaySeriesConfig(Class<?> cfg) {
        try {
            return cfg.getDeclaredConstructor(boolean.class, boolean.class)
                    .newInstance(false, false);
        } catch (Throwable ignored) {
        }
        try {
            Object unlocked = cfg.getDeclaredConstructor().newInstance();
            try {
                Field f = cfg.getDeclaredField("enablePaySeriesLock");
                f.setAccessible(true);
                f.setBoolean(unlocked, false);
            } catch (Throwable ignored) {
            }
            return unlocked;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** SsConfigMgr AB：pay_series_lock_config_* → 关闭付费锁。 */
    private static int hookPaySeriesLockViaSsConfig(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> ss = Class.forName("com.dragon.read.base.ssconfig.SsConfigMgr", false, cl);
            Class<?> cfg = optionalClass(cl,
                    "com.dragon.read.component.shortvideo.impl.config.PaySeriesLockConfig");
            Method getAb = null;
            for (Method m : ss.getDeclaredMethods()) {
                if (!"getABValue".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 2 && pts[0] == String.class) {
                    getAb = m;
                    break;
                }
            }
            if (getAb == null) {
                return 0;
            }
            String id = hookId(getAb) + "#pay-series";
            if (!HOOKED.add(id)) {
                return 1;
            }
            getAb.setAccessible(true);
            Object unlocked = cfg != null ? newUnlockedPaySeriesConfig(cfg) : null;
            module.hook(getAb).setExceptionMode(mode).intercept(chain -> {
                Object key = chain.getArgs().get(0);
                if (key instanceof String s
                        && s.contains("pay_series_lock_config")) {
                    if (unlocked != null) {
                        return unlocked;
                    }
                    Object fallback = newUnlockedPaySeriesConfig(
                            optionalClass(cl,
                                    "com.dragon.read.component.shortvideo.impl.config.PaySeriesLockConfig"));
                    if (fallback != null) {
                        return fallback;
                    }
                }
                Object result = chain.proceed();
                if (result != null
                        && result.getClass().getName().contains("PaySeriesLockConfig")) {
                    try {
                        Field f = result.getClass().getDeclaredField("enablePaySeriesLock");
                        f.setAccessible(true);
                        f.setBoolean(result, false);
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookWq4PayCheck(
            ZoeModule module, ClassLoader cl, String name, boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> bookPay = Class.forName("com.dragon.read.rpc.model.BookPayDetail", false, cl);
            Method method = findMethod(Class.forName("wq4.c", false, cl), name, 1, bookPay);
            return hookReturn(module, method, value, mode);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookWq4PayString(
            ZoeModule module, ClassLoader cl, String name, String value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> wq4 = Class.forName("wq4.c", false, cl);
            Class<?> bookPay = Class.forName("com.dragon.read.rpc.model.BookPayDetail", false, cl);
            Method method = null;
            for (Method m : wq4.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0].isAssignableFrom(bookPay)) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                return 0;
            }
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> value);
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Class<?> optionalClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void unlockSeriesPayDetailWrapper(Object wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            Field field = wrapper.getClass().getDeclaredField("f376875a");
            field.setAccessible(true);
            unlockBookPayDetail(field.get(wrapper));
        } catch (Throwable ignored) {
            try {
                for (Field field : wrapper.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(wrapper);
                    if (value != null
                            && "com.dragon.read.rpc.model.BookPayDetail"
                            .equals(value.getClass().getName())) {
                        unlockBookPayDetail(value);
                    }
                }
            } catch (Throwable ignored2) {
            }
        }
    }

    private static void unlockBookPayDetailData(Object data) {
        if (data == null) {
            return;
        }
        try {
            Field listField = data.getClass().getField("payDetail");
            Object list = listField.get(data);
            if (list instanceof List<?> payList) {
                for (Object item : payList) {
                    unlockBookPayDetail(item);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void unlockBookPayDetail(Object detail) {
        if (detail == null) {
            return;
        }
        try {
            detail.getClass().getField("paid").setBoolean(detail, true);
        } catch (Throwable ignored) {
        }
        try {
            detail.getClass().getField("needPay").setInt(detail, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void unlockNeedUnlockFlags(Object model) {
        if (model == null) {
            return;
        }
        try {
            Field needUnlock = model.getClass().getField("needUnlock");
            needUnlock.setBoolean(model, false);
        } catch (Throwable ignored) {
        }
        try {
            Field disablePlay = model.getClass().getField("disablePlay");
            disablePlay.setBoolean(model, false);
        } catch (Throwable ignored) {
        }
    }

    /** 强制「我的」页会员标可见（VariantMine / HongguoMine）。 */
    private static int hookMineVipBadgeForce(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        // 方法名在漫剧包内会被混淆，改走 Fragment.onResume 统一强显
        count += hookMineFragmentResumeForce(module, cl, mode);
        // 兼容未混淆旧包（失败时静默，resume 强显已覆盖）
        count += forceVipImageVisible(
                module, cl,
                "com.dragon.read.component.biz.impl.mine.VariantMineFragment",
                "Cf", new String[]{"E"}, mode);
        count += forceVipImageVisible(
                module, cl,
                "com.dragon.read.component.biz.impl.mine.VariantMineFragmentV2",
                "pf", new String[]{"f146725o"}, mode);
        count += forceVipImageVisible(
                module, cl,
                "com.dragon.read.component.biz.impl.mine.HongguoMineFragmentV2",
                "kh", new String[]{"G", "U"}, mode);
        return count;
    }

    private static int hookMineFragmentResumeForce(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> fragmentCls = null;
            for (String name : new String[]{
                    "androidx.fragment.app.Fragment",
                    "android.app.Fragment"
            }) {
                try {
                    fragmentCls = Class.forName(name, false, cl);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (fragmentCls == null) {
                fragmentCls = Class.forName("androidx.fragment.app.Fragment", false, cl);
            }
            Method onResume = findMethod(fragmentCls, "onResume", 0);
            if (onResume == null) {
                module.log(4, TAG, "vip-badge no Fragment.onResume");
                return 0;
            }
            String id = hookId(onResume) + "#mine-vip";
            if (!HOOKED.add(id)) {
                return 0;
            }
            onResume.setAccessible(true);
            module.hook(onResume).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self == null) {
                    return result;
                }
                String cn = self.getClass().getName();
                if (!(cn.contains("VariantMineFragment")
                        || cn.contains("HongguoMineFragment")
                        || cn.contains("MineTabFragment"))) {
                    return result;
                }
                forceShowVipBadgeFields(self, cl);
                return result;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(4, TAG, "vip-badge resume hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static void forceShowVipBadgeFields(Object fragment, ClassLoader cl) {
        String[] names = {"E", "G", "U", "f146725o", "f146726p"};
        for (String fieldName : names) {
            try {
                Field field = findField(fragment.getClass(), fieldName);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                Object view = field.get(fragment);
                if (view instanceof ImageView imageView) {
                    imageView.setVisibility(View.VISIBLE);
                    int resId = resolveVipIconRes(cl);
                    if (resId != 0) {
                        try {
                            imageView.setImageResource(resId);
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (view instanceof View v) {
                    v.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {
            }
        }
        // 再扫一遍 ImageView 字段：资源 id 名含 vip / 已有 drawable 的略过，仅放开 gone 的小图标
        try {
            Class<?> c = fragment.getClass();
            while (c != null && c != Object.class) {
                for (Field field : c.getDeclaredFields()) {
                    if (!ImageView.class.isAssignableFrom(field.getType())
                            && !View.class.equals(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object view = field.get(fragment);
                    if (!(view instanceof ImageView imageView)) {
                        continue;
                    }
                    String fn = field.getName().toLowerCase();
                    if (fn.contains("vip") || "e".equals(fn) || "g".equals(fn) || "u".equals(fn)) {
                        imageView.setVisibility(View.VISIBLE);
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
    }

    private static int forceVipImageVisible(
            ZoeModule module, ClassLoader cl, String className, String methodName,
            String[] fieldNames, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            Method method = findMethod(clazz, methodName, 0);
            if (method == null) {
                return 0;
            }
            String id = hookId(method) + "#vip-badge";
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self == null) {
                    return result;
                }
                for (String fieldName : fieldNames) {
                    try {
                        Field field = findField(self.getClass(), fieldName);
                        if (field == null) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object view = field.get(self);
                        if (view instanceof ImageView imageView) {
                            imageView.setVisibility(View.VISIBLE);
                            int resId = resolveVipIconRes(cl);
                            if (resId != 0) {
                                imageView.setImageResource(resId);
                            }
                        } else if (view instanceof View v) {
                            v.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Field findField(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static int resolveVipIconRes(ClassLoader cl) {
        try {
            Class<?> api = Class.forName(
                    "com.dragon.read.component.biz.api.NsVipApi", false, cl);
            Object impl = api.getField("IMPL").get(null);
            if (impl == null) {
                return 2130848304;
            }
            Method icon = findMethod(
                    impl.getClass(), "provideVipIcon", 3,
                    boolean.class, boolean.class, boolean.class);
            if (icon == null) {
                return 2130848304;
            }
            Object rid = icon.invoke(impl, false, false, false);
            if (rid instanceof Integer value && value != 0) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return 2130848304;
    }

    /** provideVipIcon → 保证返回非 0 资源 id。 */
    private static int hookProvideVipIcon(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(NS_VIP_IMPL, false, cl);
            Method method = findMethod(cls, "provideVipIcon", 3,
                    boolean.class, boolean.class, boolean.class);
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
                if (result instanceof Integer rid && rid != 0) {
                    return rid;
                }
                return 2130848304;
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 关注520万 / 粉丝1314万 / 获赞999万 — patch the mine & profile stats view data. */
    private static int hookProfileStats(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> infoCls = Class.forName(COMMENT_USER_STR_INFO, false, cl);
            Class<?> layoutCls = Class.forName(PROFILE_SOCIAL_LAYOUT, false, cl);
            Method setUserInfo = null;
            for (Method method : layoutCls.getDeclaredMethods()) {
                if ("setUserInfo".equals(method.getName())
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(infoCls)) {
                    setUserInfo = method;
                    break;
                }
            }
            if (setUserInfo == null) {
                return 0;
            }
            String id = hookId(setUserInfo);
            if (!HOOKED.add(id)) {
                return 0;
            }
            setUserInfo.setAccessible(true);
            module.hook(setUserInfo).setExceptionMode(mode).intercept(chain -> {
                Object info = chain.getArgs().get(0);
                if (info != null) {
                    try {
                        info.getClass().getField("followUserNum").setInt(info, 5200000);
                        info.getClass().getField("fansNum").setInt(info, 13140000);
                        info.getClass().getField("recvDiggNum").setLong(info, 9990000L);
                    } catch (Throwable ignored) {
                    }
                }
                return chain.proceed();
            });
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, TAG, "profile stats hook failed: " + t.getMessage());
            return 0;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object forgeVipInfo(ZoeModule module, ClassLoader cl, Object existing, Object overrideSubType) {
        try {
            Class<?> vipCls = Class.forName(VIP_INFO_MODEL, false, cl);
            Class<?> subTypeCls = Class.forName(VIP_SUB_TYPE, false, cl);
            Object subType = overrideSubType;
            String expire = String.valueOf(VIP_EXPIRE_SECONDS);
            long leftSeconds = Math.max(1L, VIP_EXPIRE_SECONDS - (System.currentTimeMillis() / 1000));
            String left = String.valueOf(leftSeconds);
            boolean isAutoCharge = true;
            boolean isUnionVip = false;
            int unionSource = 1;
            boolean isAdVip = true;
            if (existing != null && vipCls.isInstance(existing)) {
                try {
                    if (subType == null) {
                        Object st = vipCls.getField("subType").get(existing);
                        if (st != null) {
                            subType = st;
                        }
                    }
                    isUnionVip = vipCls.getField("isUnionVip").getBoolean(existing);
                    int existingSource = vipCls.getField("unionSource").getInt(existing);
                    if (existingSource != 0 && existingSource != 1967) {
                        unionSource = existingSource;
                    }
                    isAutoCharge = vipCls.getField("isAutoCharge").getBoolean(existing);
                } catch (Throwable ignored) {
                }
            }
            if (subType == null) {
                subType = Enum.valueOf((Class) subTypeCls, "Default");
            }
            Constructor<?> ctor = vipCls.getDeclaredConstructor(
                    String.class, String.class, String.class,
                    boolean.class, boolean.class, int.class, boolean.class, subTypeCls);
            ctor.setAccessible(true);
            return ctor.newInstance(
                    expire, "1", left, isAutoCharge, isUnionVip, unionSource, isAdVip, subType);
        } catch (Throwable t) {
            module.log(5, TAG, "forgeVipInfo failed: " + t.getMessage());
            return existing;
        }
    }

    private static Object[] allSubtypes(ClassLoader cl) {
        try {
            return Class.forName(VIP_SUB_TYPE, false, cl).getEnumConstants();
        } catch (Throwable t) {
            return null;
        }
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

    private static boolean matches(Method method, String name, int paramCount, Class<?>... paramTypes) {
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
