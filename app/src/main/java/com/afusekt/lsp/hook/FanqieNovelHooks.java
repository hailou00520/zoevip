package com.afusekt.lsp.hook;

import android.app.Application;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Legacy hooks for 番茄免费小说 / 红果免费短剧
 * ({@code com.dragon.read} / {@code com.phoenix.read} / {@code com.kylin.read}).
 * Target 7.2.9.32 — VIP gate: {@code PrivilegeManager} + {@code NsVipImpl}.
 */
public final class FanqieNovelHooks {

    private static final String TAG = MainHook.TAG + ":FanqieNovel";

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
    private static final String PROFILE_SOCIAL_LAYOUT =
            "com.dragon.read.social.ui.ProfileSocialRecordLayout";
    private static final String COMMENT_USER_STR_INFO =
            "com.dragon.read.rpc.model.CommentUserStrInfo";

    /**
     * 5555-05-20 00:00:00 UTC ≈ 我爱你 forever.
     * Always force this expire — banner hides duration when expire parses to 0.
     */
    private static final long VIP_EXPIRE_SECONDS = 113143651200L;

    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);

    private FanqieNovelHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        int hooks = 0;
        hooks += hookApplicationLog(cl);
        hooks += hookIllegalAccessShield(cl);
        hooks += hookErrorCode110(cl);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isVip");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isAnyVip");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "canShowVipRelational");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasNoAdPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasNoAdFollAllScene");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasNoAdForShortSeries");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasNoAdReadConsumptionPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isForeverNoAd");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "canReadShortStory");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasVipShortSeriesPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "adVipAvailable");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasReadPaidBookPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasOfflineReadingPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasAutoPagePrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasInspireBookPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasTtsPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasTtsConsumptionPrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasTtsNaturePrivilege");
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasBookDownloadPrivilege", String.class);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "hasPrivilege", String.class);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isNoAd", String.class);
        hooks += hookReturnInt(cl, PRIVILEGE_MANAGER, "isBookAdFree", String.class, 1);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "showPayVipEntranceInChapterEnd", false);
        hooks += hookReturnBoolean(cl, PRIVILEGE_MANAGER, "isFakeVipActive");
        hooks += hookSubtypeVip(cl);
        hooks += hookGetVipInfo(cl);
        hooks += hookGetAllVipInfo(cl);
        hooks += hookUpdateVipInfo(cl);
        hooks += hookUpdateVipInfoList(cl);
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "isVipEnable");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "isAnyVip");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "canShowMulVip");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "canShowVipCenter");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "canShowVipEntranceInAd");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "willShowNativeBanner");
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "canReadPaidBookEnhance", boolean.class);
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "canListenPaidBook", boolean.class);
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "isDisableVipInGoogle", false);
        // 红果「我的」头像旁会员标：HongguoMineFragmentV2 看 isAnyVip；其它入口看 needShowVipIcon
        hooks += hookReturnBoolean(cl, NS_VIP_IMPL, "needShowVipIcon", boolean.class);
        hooks += hookReturnInt(cl, NS_VIP_IMPL, "getShowVipIconVisibility", boolean.class, 0);
        hooks += hookNsVipIsVip(cl);
        hooks += hookVipEntrance(cl);
        hooks += hookVariantMineVipEntrance(cl);
        hooks += hookVipEntranceAb(cl);
        hooks += hookReturnBoolean(cl, ACCT_MANAGER, "adVipAvailable");
        hooks += hookReturnBoolean(cl, ACCT_MANAGER, "isOfficial", false);
        hooks += hookGsonVipParse(cl);
        hooks += hookShortSeriesPayUnlock(cl);
        hooks += hookProfileStats(cl);
        XposedBridge.log(TAG + ": hooks installed (" + hooks + ")");
    }

    private static int hookApplicationLog(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    String pkg = app.getPackageName();
                    if (MainHook.isDragonReadFamily(pkg)) {
                        XposedBridge.log(TAG + ": active in " + pkg);
                        FanqieNovelSafeHooks.onApplication(app);
                        clearChapterBlacklist(cl);
                        showInjectToast(app);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void showInjectToast(Application app) {
        try {
            String pkg = app.getPackageName();
            String process = app.getApplicationInfo().processName;
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
            if (MainHook.KYLIN_PACKAGE.equals(pkg)) {
                tip = "ZoeVIP 已注入红果漫剧";
            } else if (MainHook.HONGGUO_PACKAGE.equals(pkg)) {
                tip = "ZoeVIP 已注入红果短剧";
            } else {
                tip = "ZoeVIP 已注入番茄小说";
            }
            android.widget.Toast.makeText(app, tip, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static int hookIllegalAccessShield(ClassLoader cl) {
        int count = 0;
        count += hookVoidNoop(cl, NET_DEPEND_IMPL, "assertIllegalAccess");
        count += hookVoidNoop(cl, NET_DEPEND_FACADE, "assertIllegalAccess");
        count += hookRewriteIllegalAccessCode(cl);
        count += hookUnsafeToastFilter(cl);
        return count;
    }

    private static int hookVoidNoop(ClassLoader cl, String className, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.DO_NOTHING);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookRewriteIllegalAccessCode(ClassLoader cl) {
        int count = 0;
        try {
            XposedHelpers.findAndHookMethod(
                    NET_REQ_UTIL, cl, "analyseCode",
                    Throwable.class, int.class, Object.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof Integer && (Integer) result == 110) {
                                neutralizeResponseCode(param.args[2]);
                                param.setResult(0);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    NET_REQ_UTIL, cl, "parseResponseCode", Object.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (result instanceof Integer && (Integer) result == 110) {
                                neutralizeResponseCode(param.args[0]);
                                param.setResult(0);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookUnsafeToastFilter(ClassLoader cl) {
        int count = 0;
        // Resource-id toast used by assertIllegalAccess → showCommonToastSafely(R.string.xxx, 1)
        try {
            XposedHelpers.findAndHookMethod(
                    TOAST_UTILS, cl, "showCommonToastSafely", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isUnsafeToastRes(cl, param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    TOAST_UTILS, cl, "showCommonToast", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isUnsafeToastRes(cl, param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    TOAST_UTILS, cl, "showCommonToast", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isUnsafeToast(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    TOAST_UTILS, cl, "showCommonToastSafely", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isUnsafeToast(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    TOAST_UTILS, cl, "showCommonToast", String.class, int.class,
                    XposedHelpers.findClass("com.dragon.read.util.l", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isUnsafeToast(param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static boolean isUnsafeToastRes(ClassLoader cl, Object resId) {
        if (!(resId instanceof Integer)) {
            return false;
        }
        try {
            android.content.Context ctx =
                    (android.content.Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.dragon.read.base.util.AppUtils", cl),
                            "context");
            if (ctx == null) {
                return false;
            }
            return isUnsafeToast(ctx.getString((Integer) resId));
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

    @SuppressWarnings("unchecked")
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
                        Object value = XposedHelpers.callMethod(constant, "getValue");
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

    private static void clearChapterBlacklist(ClassLoader cl) {
        try {
            Class<?> service = XposedHelpers.findClass(
                    "com.dragon.read.reader.services.t", cl);
            Object helper = XposedHelpers.getStaticObjectField(service, "f204726b");
            if (helper == null) {
                return;
            }
            // Replace final blacklist with a set that never traps chapters as "unsafe".
            HashSet<String> noop = new HashSet<String>() {
                @Override
                public boolean add(String s) {
                    return false;
                }

                @Override
                public boolean contains(Object o) {
                    return false;
                }
            };
            try {
                Field setField = helper.getClass().getDeclaredField("f204964a");
                setField.setAccessible(true);
                setField.set(helper, noop);
            } catch (Throwable t) {
                Object set = XposedHelpers.getObjectField(helper, "f204964a");
                if (set instanceof HashSet) {
                    ((HashSet<?>) set).clear();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Neutralize ErrorCodeException(110) so chapter/bookstore flows don't abort as "unsafe". */
    private static int hookErrorCode110(ClassLoader cl) {
        int count = 0;
        String[] classes = {
                "com.dragon.read.base.http.exception.ErrorCodeException",
                "com.dragon.read.network.ErrorCodeException",
        };
        for (String name : classes) {
            try {
                Class<?> cls = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Field codeField = cls.getDeclaredField("code");
                            codeField.setAccessible(true);
                            Object code = codeField.get(param.thisObject);
                            if (code instanceof Integer && (Integer) code == 110) {
                                Field mods = Field.class.getDeclaredField("modifiers");
                                mods.setAccessible(true);
                                mods.setInt(codeField, codeField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                                codeField.setInt(param.thisObject, 0);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.findAndHookMethod(name, cl, "getCode", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (result instanceof Integer && (Integer) result == 110) {
                            param.setResult(0);
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
            }
        }
        return count;
    }

    private static int hookReturnBoolean(ClassLoader cl, String className, String methodName) {
        return hookReturnBoolean(cl, className, methodName, true);
    }

    private static int hookReturnBoolean(
            ClassLoader cl, String className, String methodName, boolean value
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturnBoolean(
            ClassLoader cl, String className, String methodName, Class<?> paramType
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, paramType,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookReturnInt(
            ClassLoader cl, String className, String methodName, Class<?> paramType, int value
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    className, cl, methodName, paramType,
                    XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookVipEntrance(ClassLoader cl) {
        try {
            Class<?> entrance = XposedHelpers.findClass(VIP_ENTRANCE, cl);
            XposedHelpers.findAndHookMethod(
                    NS_VIP_IMPL, cl, "canShowVipEntranceHere", entrance,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 我的页 VIP 入口 / 变体入口（红果 Variant / HongguoMine）。 */
    private static int hookVariantMineVipEntrance(ClassLoader cl) {
        try {
            Class<?> entryType = XposedHelpers.findClass(
                    "com.dragon.read.rpc.model.MineVipEntryType", cl);
            XposedHelpers.findAndHookMethod(
                    NS_VIP_IMPL, cl, "canShowVariantMineVipEntrance", entryType,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** ey3.m4.a(VipEntrance) — AB/隐藏位，false 会挡会员标与入口。 */
    private static int hookVipEntranceAb(ClassLoader cl) {
        try {
            Class<?> entrance = XposedHelpers.findClass(VIP_ENTRANCE, cl);
            XposedHelpers.findAndHookMethod(
                    "ey3.m4", cl, "a", entrance,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookSubtypeVip(ClassLoader cl) {
        try {
            Class<?> subType = XposedHelpers.findClass(VIP_SUB_TYPE, cl);
            XposedHelpers.findAndHookMethod(
                    PRIVILEGE_MANAGER, cl, "b", subType,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookNsVipIsVip(ClassLoader cl) {
        try {
            Class<?> subType = XposedHelpers.findClass(VIP_SUB_TYPE, cl);
            XposedHelpers.findAndHookMethod(
                    NS_VIP_IMPL, cl, "isVip", subType,
                    XC_MethodReplacement.returnConstant(true));
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGetVipInfo(ClassLoader cl) {
        int count = 0;
        try {
            XposedHelpers.findAndHookMethod(PRIVILEGE_MANAGER, cl, "getVipInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(forgeVipInfo(cl, param.getResult(), null));
                }
            });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> subType = XposedHelpers.findClass(VIP_SUB_TYPE, cl);
            XposedHelpers.findAndHookMethod(
                    PRIVILEGE_MANAGER, cl, "getVipInfo", subType, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(forgeVipInfo(cl, param.getResult(), param.args[0]));
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookGetAllVipInfo(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    PRIVILEGE_MANAGER, cl, "getAllVipInfo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            List<Object> list = new ArrayList<>();
                            for (Object subtype : allSubtypes(cl)) {
                                Object forged = forgeVipInfo(cl, null, subtype);
                                if (forged != null) {
                                    list.add(forged);
                                }
                            }
                            if (!list.isEmpty()) {
                                param.setResult(list);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookUpdateVipInfo(ClassLoader cl) {
        try {
            Class<?> vipCls = XposedHelpers.findClass(VIP_INFO_MODEL, cl);
            XposedHelpers.findAndHookMethod(
                    PRIVILEGE_MANAGER, cl, "updateVipInfo", vipCls, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = forgeVipInfo(cl, param.args[0], null);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookUpdateVipInfoList(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    PRIVILEGE_MANAGER, cl, "updateVipInfoList", List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object arg = param.args[0];
                            if (!(arg instanceof List<?>)) {
                                return;
                            }
                            String expire = String.valueOf(VIP_EXPIRE_SECONDS);
                            long leftSeconds = Math.max(
                                    1L, VIP_EXPIRE_SECONDS - (System.currentTimeMillis() / 1000));
                            String left = String.valueOf(leftSeconds);
                            for (Object item : (List<?>) arg) {
                                if (item == null) {
                                    continue;
                                }
                                try {
                                    XposedHelpers.setObjectField(item, "expireTime", expire);
                                    XposedHelpers.setObjectField(item, "isVip", "1");
                                    XposedHelpers.setObjectField(item, "leftTime", left);
                                    XposedHelpers.setBooleanField(item, "isAdVip", true);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<Object> allSubtypes(ClassLoader cl) {
        List<Object> subtypes = new ArrayList<>();
        for (Object constant : XposedHelpers.findClass(VIP_SUB_TYPE, cl).getEnumConstants()) {
            subtypes.add(constant);
        }
        return subtypes;
    }

    private static int hookGsonVipParse(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> gson = XposedHelpers.findClass("com.google.gson.Gson", cl);
            Class<?> vipInfoModel = XposedHelpers.findClass(VIP_INFO_MODEL, cl);
            Class<?> bookPayDetail = optionalClass(cl, "com.dragon.read.rpc.model.BookPayDetail");
            Class<?> bookPayDetailData = optionalClass(cl, "com.dragon.read.rpc.model.BookPayDetailData");
            Class<?> episodeInfo = optionalClass(cl, "com.dragon.read.rpc.model.EpisodeInfo");
            for (java.lang.reflect.Method method : gson.getDeclaredMethods()) {
                if (!"fromJson".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                if (method.getParameterTypes()[1] != Class.class) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        Object typeArg = param.args[1];
                        if (!(typeArg instanceof Class<?> type) || result == null) {
                            return;
                        }
                        if (vipInfoModel.isAssignableFrom(type)) {
                            param.setResult(forgeVipInfo(cl, result, null));
                        } else if (bookPayDetail != null && bookPayDetail.isAssignableFrom(type)) {
                            unlockBookPayDetail(result);
                        } else if (bookPayDetailData != null && bookPayDetailData.isAssignableFrom(type)) {
                            unlockBookPayDetailData(result);
                        } else if (episodeInfo != null && episodeInfo.isAssignableFrom(type)) {
                            unlockNeedUnlockFlags(result);
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

    private static int hookShortSeriesPayUnlock(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> cfg = XposedHelpers.findClass(
                    "com.dragon.read.component.shortvideo.impl.config.PaySeriesLockConfig", cl);
            Object unlocked = XposedHelpers.newInstance(cfg, false, false);
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.shortvideo.impl.config.PaySeriesLockConfig$a",
                    cl, "a", XC_MethodReplacement.returnConstant(unlocked));
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.shortvideo.impl.paycontent.model.SeriesPayContentServiceImpl",
                    cl, "f", String.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            unlockSeriesPayDetailWrapper(param.getResult());
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> bookPay = XposedHelpers.findClass("com.dragon.read.rpc.model.BookPayDetail", cl);
            XposedHelpers.findAndHookMethod(
                    "wq4.c", cl, "f", bookPay, XC_MethodReplacement.returnConstant(false));
            count++;
            XposedHelpers.findAndHookMethod(
                    "wq4.c", cl, "c", bookPay, XC_MethodReplacement.returnConstant("1"));
            count++;
            XposedHelpers.findAndHookMethod(
                    "wq4.c", cl, "b", bookPay, String.class,
                    XC_MethodReplacement.returnConstant("0"));
            count++;
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static Class<?> optionalClass(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void unlockSeriesPayDetailWrapper(Object wrapper) {
        if (wrapper == null) {
            return;
        }
        try {
            unlockBookPayDetail(XposedHelpers.getObjectField(wrapper, "f376875a"));
        } catch (Throwable ignored) {
        }
    }

    private static void unlockBookPayDetailData(Object data) {
        if (data == null) {
            return;
        }
        try {
            Object list = XposedHelpers.getObjectField(data, "payDetail");
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
            XposedHelpers.setBooleanField(detail, "paid", true);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setIntField(detail, "needPay", 0);
        } catch (Throwable ignored) {
        }
    }

    private static void unlockNeedUnlockFlags(Object model) {
        if (model == null) {
            return;
        }
        try {
            XposedHelpers.setBooleanField(model, "needUnlock", false);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setBooleanField(model, "disablePlay", false);
        } catch (Throwable ignored) {
        }
    }

    /** 关注520万 / 粉丝1314万 / 获赞999万 — patch the mine & profile stats view data. */
    private static int hookProfileStats(ClassLoader cl) {
        try {
            Class<?> infoCls = XposedHelpers.findClass(COMMENT_USER_STR_INFO, cl);
            XposedHelpers.findAndHookMethod(
                    PROFILE_SOCIAL_LAYOUT, cl, "setUserInfo", infoCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object info = param.args[0];
                            if (info == null) {
                                return;
                            }
                            try {
                                XposedHelpers.setIntField(info, "followUserNum", 5200000);
                                XposedHelpers.setIntField(info, "fansNum", 13140000);
                                XposedHelpers.setLongField(info, "recvDiggNum", 9990000L);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": profile stats hook failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * Forge a VipInfoModel: isVip="1", expire=5555-05-20.
     * Must always set a future expireTime — banner binder hides duration when expire parses to 0.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object forgeVipInfo(ClassLoader cl, Object existing, Object overrideSubType) {
        try {
            Class<?> vipCls = XposedHelpers.findClass(VIP_INFO_MODEL, cl);
            Class<?> subTypeCls = XposedHelpers.findClass(VIP_SUB_TYPE, cl);
            Object subType = overrideSubType;
            String expire = String.valueOf(VIP_EXPIRE_SECONDS);
            long leftSeconds = Math.max(1L, VIP_EXPIRE_SECONDS - (System.currentTimeMillis() / 1000));
            String left = String.valueOf(leftSeconds);
            boolean isUnionVip = false;
            int unionSource = 1;
            boolean isAdVip = true;
            boolean isAutoCharge = true;
            if (existing != null && vipCls.isInstance(existing)) {
                try {
                    if (subType == null) {
                        Object st = XposedHelpers.getObjectField(existing, "subType");
                        if (st != null) {
                            subType = st;
                        }
                    }
                    isUnionVip = XposedHelpers.getBooleanField(existing, "isUnionVip");
                    int existingSource = XposedHelpers.getIntField(existing, "unionSource");
                    if (existingSource != 0 && existingSource != 1967) {
                        unionSource = existingSource;
                    }
                    isAutoCharge = XposedHelpers.getBooleanField(existing, "isAutoCharge");
                } catch (Throwable ignored) {
                }
            }
            if (subType == null) {
                subType = Enum.valueOf(
                        (Class<? extends Enum>) subTypeCls.asSubclass(Enum.class), "Default");
            }
            return XposedHelpers.newInstance(
                    vipCls,
                    expire,
                    "1",
                    left,
                    isAutoCharge,
                    isUnionVip,
                    unionSource,
                    isAdVip,
                    subType
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": forgeVipInfo failed: " + t.getMessage());
            return existing;
        }
    }
}
