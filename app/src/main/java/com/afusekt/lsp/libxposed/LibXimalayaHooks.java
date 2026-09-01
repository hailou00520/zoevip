package com.afusekt.lsp.libxposed;

import android.app.Activity;
import android.content.Context;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;
import com.afusekt.lsp.prefs.XimalayaPrefs;
import com.afusekt.lsp.sync.WebDavSyncConfig;
import com.afusekt.lsp.ui.XimalayaBurstPicker;

import de.robv.android.xposed.XSharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Ximalaya reward-ad bypass (libxposed). Legacy XposedBridge is unavailable in scoped processes.
 */
public final class LibXimalayaHooks {

    private static final String TAG = ZoeIds.TAG + ":Ximalaya";

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
    private static final String VIDEO_AD_MGR_K =
            "com.ximalaya.ting.android.host.manager.ad.videoad.k";
    private static final String[] GOLD_COIN_HANDLERS = {
            "com.ximalaya.ting.android.reactnative.modules.BusinessModule$16",
            "com.ximalaya.ting.android.reactnative.modules.BusinessModule$9$3",
    };
    private static final String VIDEO_AD_CALLBACK =
            "com.ximalaya.ting.android.host.manager.ad.videoad.g";
    private static final String VIDEO_AD_CALLBACK_H =
            "com.ximalaya.ting.android.host.manager.ad.videoad.h";
    private static final String ADVERTIS =
            "com.ximalaya.ting.android.opensdk.model.advertis.Advertis";
    private static final String REWARD_EXTRA_PARAMS =
            "com.ximalaya.ting.android.host.model.ad.RewardExtraParams";
    private static final String BUSINESS_MODULE_9 =
            "com.ximalaya.ting.android.reactnative.modules.BusinessModule$9";
    private static final String VIDEO_UNLOCK_RESULT =
            "com.ximalaya.ting.android.host.model.ad.VideoUnLockResult";
    private static final String AD_MANAGER_B =
            "com.ximalaya.ting.android.host.manager.ad.AdManager$b";
    private static final String UNLOCKPAID_C =
            "com.ximalaya.ting.android.host.manager.ad.unlockpaid.c";
    private static final String UNLOCKPAID_D =
            "com.ximalaya.ting.android.host.manager.ad.unlockpaid.d";
    private static final String UNLOCKPAID_B =
            "com.ximalaya.ting.android.host.manager.ad.unlockpaid.b";
    private static final String UNLOCKPAID_B_CALLBACK =
            "com.ximalaya.ting.android.host.manager.ad.unlockpaid.b$b";
    private static final String UNLOCK_RESULT =
            "com.ximalaya.ting.android.host.model.free.UnlockResult";
    private static final String BASE_RESPONSE =
            "com.ximalaya.ting.android.loginservice.BaseResponse";
    private static final String UNLOCKPAID_CALLBACK =
            "com.ximalaya.ting.android.host.manager.ad.unlockpaid.c$a";
    private static final String INCENTIVE_HANDLER_9_2 =
            "com.ximalaya.ting.android.reactnative.modules.BusinessModule$9$2";
    private static final String VIDEO_AD_ADFLOW_A =
            "com.ximalaya.ting.android.host.manager.ad.videoad.adflow.a";
    private static final String AD_UNLOCK_TRACK =
            "com.ximalaya.ting.android.host.model.ad.AdUnLockVipTrackAdvertis";

    private static final long UNLOCK_B_BURST_DELAY_MS = 200L;
    private static final long UNLOCK_B_SUCCESS_DEBOUNCE_MS = 400L;
    private static final long UNLOCK_B_BURST_LIFECYCLE_DELAY_MS = 50L;
    private static final long UNLOCK_B_BURST_SUCCESS_DELAY_MS = 150L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();
    private static final Set<Integer> UNLOCK_B_REWARD_GATE = new HashSet<>();
    private static final Object UNLOCK_B_REWARD_GATE_LOCK = new Object();
    private static final Object UNLOCK_B_BURST_LOCK = new Object();
    private static UnlockPaidBBurstSession UNLOCK_B_BURST;
    private static final ThreadLocal<Boolean> BYPASS_UNLOCK_B_ENTRY =
            ThreadLocal.withInitial(() -> false);

    private static final class UnlockPaidBBurstSession {
        int totalClaims;
        int remaining;
        boolean autoRepeat;
        boolean fourParam;
        int unlockType;
        Object rewardExtra;
        String source;
        Object callback;
        long lastSuccessMs;
    }

    private LibXimalayaHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        tryInstall(module, cl, "package-loaded");
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        tryInstall(module, param.getClassLoader(), "package-ready");
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(AD_SDK, false, cl);
        } catch (Throwable t) {
            module.log(5, TAG, "deferred (" + source + "): AdSDK not in " + cl);
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, TAG, "installing (" + source + ")");
        Log.i(TAG, "installing (" + source + ")");
        try {
            int hooks = installHooks(module, cl);
            module.log(4, TAG, "hooks installed (" + hooks + ")");
            Log.i(TAG, "hooks installed (" + hooks + ")");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, TAG, "install failed: " + t.getMessage(), t);
        }
    }

    private static int installHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = 0;
        count += safeHook(module, () -> hookAdSdkLoadReward(module, cl, mode));
        count += safeHook(module, () -> hookRewardVideoManagerLoad(module, cl, mode));
        count += safeHook(module, () -> hookVideoAdManagerK(module, cl, mode));
        count += safeHook(module, () -> hookVideoAdManagerKWithG(module, cl, mode));
        count += safeHook(module, () -> hookVideoAdManagerKWithH(module, cl, mode));
        count += safeHook(module, () -> hookVideoAdAdflowA(module, cl, mode));
        count += safeHook(module, () -> hookBusinessModule9FailRedirect(module, cl, mode));
        count += safeHook(module, () -> hookIncentiveAdsResponse(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidEntry(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidTrackEntry(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidBEntry(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidBLoadingShow(module, cl, mode));
        count += safeHook(module, () -> hookFreeListenAccumulatedTime(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidBErrorToast(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidBRewardSuccess(module, cl, mode));
        count += safeHook(module, () -> hookUnlockPaidBRewardNotify(module, cl, mode));
        count += safeHook(module, () -> hookGsonRewardParse(module, cl, mode));
        count += safeHook(module, () -> hookExplicitGoldCoinHandlers(module, cl, mode));
        count += safeHook(module, () -> hookGoldCoinHandlers(module, cl, mode));
        count += safeHook(module, () -> hookBooleanGetter(module, cl, INCENTIVE_REWARD_DATA, "isSuccess", true, mode));
        count += safeHook(module, () -> hookBooleanGetter(module, cl, INCENTIVE_REWARD_DATA, "isRetry", false, mode));
        count += safeHook(module, () -> hookBooleanGetter(module, cl, INCENTIVE_RESPONSE_DATA, "isSuccess", true, mode));
        count += safeHook(module, () -> hookBooleanGetter(module, cl, VIDEO_UNLOCK_RESULT, "isSuccess", true, mode));
        count += safeHook(module, () -> hookIncentiveRewardDataSetters(module, cl, mode));
        return count;
    }

    private static int safeHook(ZoeModule module, HookTask task) {
        try {
            return task.run();
        } catch (Throwable t) {
            module.log(5, TAG, "hook step failed: " + t.getMessage());
            return 0;
        }
    }

    @FunctionalInterface
    private interface HookTask {
        int run() throws Throwable;
    }

    private static int hookAdSdkLoadReward(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode)
            throws Throwable {
        Class<?> adSdk = Class.forName(AD_SDK, false, cl);
        Class<?> xmLoad = Class.forName(
                "com.ximalaya.ting.android.adsdk.external.XmLoadAdParams", false, cl);
        Class<?> xmExtra = Class.forName(
                "com.ximalaya.ting.android.adsdk.external.XmRewardExtraParam", false, cl);
        Class<?> listener = Class.forName(REWARD_LISTENER, false, cl);
        Method method = adSdk.getDeclaredMethod(
                "loadRewardVideoAd", Activity.class, Context.class, xmLoad, xmExtra, listener);
        return hookSkipReward(module, method, mode);
    }

    private static int hookRewardVideoManagerLoad(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> mgr = Class.forName(REWARD_VIDEO_MGR, false, cl);
        Class<?> xmLoad = Class.forName(
                "com.ximalaya.ting.android.adsdk.external.XmLoadAdParams", false, cl);
        Class<?> xmExtra = Class.forName(
                "com.ximalaya.ting.android.adsdk.external.XmRewardExtraParam", false, cl);
        Class<?> listener = Class.forName(REWARD_LISTENER, false, cl);
        Method method = mgr.getDeclaredMethod(
                "loadRewardVideoAd", Activity.class, xmLoad, xmExtra, listener, java.util.List.class);
        return hookSkipReward(module, method, mode);
    }

    private static int hookSkipReward(ZoeModule module, Method method, XposedInterface.ExceptionMode mode) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            for (Object arg : chain.getArgs()) {
                if (arg != null && implementsRewardListener(arg.getClass())) {
                    module.log(4, TAG, "skip ad -> instant reward");
                    dispatchInstantReward(module, arg);
                    break;
                }
            }
            return null;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookVideoAdManagerK(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> mgr = Class.forName(VIDEO_AD_MGR_K, false, cl);
        Class<?> xmExtra = Class.forName(
                "com.ximalaya.ting.android.adsdk.external.XmRewardExtraParam", false, cl);
        Class<?> adapter = Class.forName(
                "com.ximalaya.ting.android.adsdk.bridge.inner.model.AdSDKAdapterModel", false, cl);
        Class<?> listener = Class.forName(REWARD_LISTENER, false, cl);
        Method method = mgr.getDeclaredMethod(
                "a", Activity.class, String.class, xmExtra, adapter, listener);
        return hookSkipReward(module, method, mode);
    }

    private static int hookVideoAdManagerKWithG(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> mgr = Class.forName(VIDEO_AD_MGR_K, false, cl);
        Class<?> advertis = Class.forName(ADVERTIS, false, cl);
        Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
        Class<?> videoAdG = Class.forName(VIDEO_AD_CALLBACK, false, cl);
        Class<?> thirdAd = Class.forName(
                "com.ximalaya.ting.android.ad.model.thirdad.a.a", false, cl);
        int count = 0;
        count += hookSkipVideoAdG(module, cl, mgr.getDeclaredMethod(
                "a", Activity.class, advertis, String.class, int.class, int.class,
                rewardExtra, videoAdG), mode);
        count += hookSkipVideoAdG(module, cl, mgr.getDeclaredMethod(
                "a", Activity.class, advertis, String.class, int.class, rewardExtra, videoAdG), mode);
        count += hookSkipVideoAdG(module, cl, mgr.getDeclaredMethod(
                "a", thirdAd, Activity.class, rewardExtra, videoAdG), mode);
        return count > 0 ? 1 : 0;
    }

    private static int hookVideoAdManagerKWithH(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> mgr = Class.forName(VIDEO_AD_MGR_K, false, cl);
        Class<?> kCallback = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.videoad.k$b", false, cl);
        Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
        Class<?> videoAdH = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.videoad.h", false, cl);
        Method method = mgr.getDeclaredMethod(
                "a", java.util.List.class, kCallback, int.class, rewardExtra, videoAdH);
        return hookSkipVideoAdG(module, cl, method, mode);
    }

    private static int hookVideoAdAdflowA(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> adflow = Class.forName(VIDEO_AD_ADFLOW_A, false, cl);
        Class<?> kCallback = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.videoad.k$b", false, cl);
        Class<?> bu = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.bu", false, cl);
        Class<?> videoAdH = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.videoad.h", false, cl);
        Method method = adflow.getDeclaredMethod(
                "a", java.util.List.class, bu, kCallback, videoAdH);
        return hookSkipVideoAdG(module, cl, method, mode);
    }

    private static int hookSkipVideoAdG(
            ZoeModule module, ClassLoader cl, Method method, XposedInterface.ExceptionMode mode
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object callback = findVideoAdCallback(cl, chain.getArgs());
            if (callback == null) {
                return chain.proceed();
            }
            Object unlockB = resolveUnlockPaidBFromCallback(callback);
            Object thirdAd = findThirdAd(chain.getArgs());
            if (unlockB != null && thirdAd != null) {
                injectUnlockPaidBAdFromThirdAd(module, unlockB, thirdAd);
            }
            if (unlockB != null && getField(unlockB, "c") == null) {
                ensureUnlockPaidBAdSelected(module, cl, unlockB);
            }
            if (unlockB != null && getField(unlockB, "c") == null) {
                module.log(4, TAG, "videoad skip deferred (ad not ready)");
                return chain.proceed();
            }
            module.log(4, TAG, "skip videoad -> instant reward ("
                    + callback.getClass().getSimpleName() + ")");
            Log.i(TAG, "skip videoad -> instant reward ("
                    + callback.getClass().getSimpleName() + ")");
            prepareUnlockPaidBFromArgs(module, cl, callback, chain.getArgs());
            dispatchVideoAdReward(module, cl, callback);
            return null;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookIncentiveAdsResponse(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> handler = Class.forName(INCENTIVE_HANDLER_9_2, false, cl);
        Method method = handler.getDeclaredMethod("a", String.class);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object json = chain.getArgs().get(0);
            if (json instanceof String) {
                module.log(4, TAG, "patch ads reward response");
                chain.getArgs().set(0, patchIncentiveJson(module, cl, (String) json));
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidEntry(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_C, false, cl);
        Class<?> callback = Class.forName(UNLOCKPAID_CALLBACK, false, cl);
        Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
        Method method = unlock.getDeclaredMethod(
                "a", int.class, String.class, callback, rewardExtra);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object listener = chain.getArgs().get(2);
            if (listener != null && BUSINESS_MODULE_9.equals(listener.getClass().getName())) {
                module.log(4, TAG, "unlockpaid bypass -> instant grant");
                new Handler(Looper.getMainLooper()).post(() -> grantBusinessModule9(module, listener));
                return null;
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidTrackEntry(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_D, false, cl);
        Class<?> callback = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.unlockpaid.d$a", false, cl);
        Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
        Method method = unlock.getDeclaredMethod(
                "a", int.class, long.class, long.class, String.class, String.class,
                callback, rewardExtra, Activity.class);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            module.log(4, TAG, "unlockpaid/d entry");
            chain.proceed();
            Object unlockD = chain.getThisObject();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> fastGrantUnlockPaidTrackIfPending(module, cl, unlockD), 800L);
            return null;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidBEntry(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_B, false, cl);
        Class<?> callback = Class.forName(UNLOCKPAID_B_CALLBACK, false, cl);
        Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
        int count = 0;
        count += hookUnlockPaidBEntryMethod(module, cl, mode, unlock.getDeclaredMethod(
                "a", int.class, String.class, callback));
        count += hookUnlockPaidBEntryMethod(module, cl, mode, unlock.getDeclaredMethod(
                "a", int.class, rewardExtra, String.class, callback));
        return count;
    }

    private static int hookUnlockPaidBEntryMethod(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode, Method method
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            if (Boolean.TRUE.equals(BYPASS_UNLOCK_B_ENTRY.get())) {
                return chain.proceed();
            }
            String source = extractUnlockPaidBSource(method, chain.getArgs().toArray());
            module.log(4, TAG, "unlockpaid/b entry (" + source + ")");
            Log.i(TAG, "unlockpaid/b entry (" + source + ")");
            Object unlockB = chain.getThisObject();
            Object[] args = chain.getArgs().toArray();
            synchronized (UNLOCK_B_BURST_LOCK) {
                if (UNLOCK_B_BURST != null && UNLOCK_B_BURST.autoRepeat) {
                    UNLOCK_B_BURST.autoRepeat = false;
                    resetUnlockPaidBRewardGate(unlockB);
                    chain.proceed();
                    scheduleSilentBurstMaintenance(module, cl, unlockB);
                    return null;
                }
            }
            showBurstCountPicker(module, cl, method, args, unlockB);
            return null;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidBLoadingShow(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_B, false, cl);
        Method method = unlock.getDeclaredMethod("m");
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            if (isUnlockPaidBBurstActive()) {
                module.log(4, TAG, "unlockpaid/b loading suppressed (burst)");
                return null;
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static boolean isUnlockPaidBBurstActive() {
        synchronized (UNLOCK_B_BURST_LOCK) {
            return UNLOCK_B_BURST != null;
        }
    }

    private static void scheduleSilentBurstMaintenance(
            ZoeModule module, ClassLoader cl, Object unlockB
    ) {
        if (!isUnlockPaidBBurstActive()) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable dismiss = () -> {
            if (isUnlockPaidBBurstActive()) {
                dismissUnlockPaidBLoading(module, unlockB);
            }
        };
        handler.post(dismiss);
        handler.postDelayed(dismiss, 80L);
        handler.postDelayed(dismiss, 250L);
        handler.postDelayed(dismiss, 600L);
    }

    private static void showBurstCountPicker(
            ZoeModule module,
            ClassLoader cl,
            Method method,
            Object[] args,
            Object unlockB
    ) {
        int defaultCount = readDefaultBurstCount();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            Activity activity = resolveHostActivity(cl);
            Runnable start = () -> {
                resetUnlockPaidBRewardGate(unlockB);
                invokeUnlockPaidBEntry(module, unlockB, method, args);
                scheduleSilentBurstMaintenance(module, cl, unlockB);
            };
            if (activity == null || activity.isFinishing()) {
                synchronized (UNLOCK_B_BURST_LOCK) {
                    beginUnlockPaidBBurst(method, args, defaultCount);
                    module.log(4, TAG, "unlockpaid/b burst armed (" + defaultCount + ")");
                    Log.i(TAG, "unlockpaid/b burst armed (" + defaultCount + ")");
                }
                start.run();
                return;
            }
            XimalayaBurstPicker.show(activity, defaultCount, new XimalayaBurstPicker.Listener() {
                @Override
                public void onSelected(int count) {
                    synchronized (UNLOCK_B_BURST_LOCK) {
                        beginUnlockPaidBBurst(method, args, count);
                        module.log(4, TAG, "unlockpaid/b burst armed (" + count + ")");
                        Log.i(TAG, "unlockpaid/b burst armed (" + count + ")");
                    }
                    start.run();
                }

                @Override
                public void onCancelled() {
                    module.log(4, TAG, "unlockpaid/b burst cancelled");
                    Log.i(TAG, "unlockpaid/b burst cancelled");
                }
            });
        });
    }

    private static void invokeUnlockPaidBEntry(
            ZoeModule module, Object unlockB, Method method, Object[] args
    ) {
        BYPASS_UNLOCK_B_ENTRY.set(true);
        try {
            method.invoke(unlockB, args);
        } catch (Throwable t) {
            clearUnlockPaidBBurst("invoke failed");
            module.log(5, TAG, "unlockpaid/b invoke: " + t.getMessage());
            Log.w(TAG, "unlockpaid/b invoke: " + t.getMessage());
        } finally {
            BYPASS_UNLOCK_B_ENTRY.set(false);
        }
    }

    private static int readDefaultBurstCount() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(
                    WebDavSyncConfig.MODULE_PACKAGE, XimalayaPrefs.PREFS);
            prefs.makeWorldReadable();
            prefs.reload();
            return XimalayaPrefs.getBurstCount(prefs);
        } catch (Throwable t) {
            return XimalayaPrefs.DEFAULT_BURST_COUNT;
        }
    }

    private static boolean readBypassDailyLimit() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(
                    WebDavSyncConfig.MODULE_PACKAGE, XimalayaPrefs.PREFS);
            prefs.makeWorldReadable();
            prefs.reload();
            return XimalayaPrefs.isBypassDailyLimit(prefs);
        } catch (Throwable t) {
            return XimalayaPrefs.DEFAULT_BYPASS_DAILY_LIMIT;
        }
    }

    private static int hookFreeListenAccumulatedTime(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> playJ = Class.forName(
                "com.ximalaya.ting.android.host.manager.play.j", false, cl);
        Method method = playJ.getMethod("a", Context.class);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            if (readBypassDailyLimit()) {
                module.log(4, TAG, "bypass daily limit: accumulated time -> 0");
                return 0;
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static Activity resolveHostActivity(ClassLoader cl) {
        try {
            Class<?> app = Class.forName(
                    "com.ximalaya.ting.android.host.MainApplication", false, cl);
            Object activity = app.getMethod("getMainActivity").invoke(null);
            if (activity instanceof Activity) {
                return (Activity) activity;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void resetUnlockPaidBRewardGate(Object unlockB) {
        synchronized (UNLOCK_B_REWARD_GATE_LOCK) {
            UNLOCK_B_REWARD_GATE.remove(System.identityHashCode(unlockB));
        }
    }

    private static int hookUnlockPaidBErrorToast(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_B, false, cl);
        Method method = unlock.getDeclaredMethod("a", String.class, int.class);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object message = chain.getArgs().get(0);
            if (message instanceof String) {
                String text = (String) message;
                if (text.contains("异常") || text.contains("失败")) {
                    clearUnlockPaidBBurst("reward failed");
                    module.log(4, TAG, "suppress unlockpaid/b toast: " + text);
                    return null;
                }
                if (text.contains("已获取") && text.contains("分钟")) {
                    onUnlockPaidBRewardSuccess(module, cl, chain.getThisObject());
                    if (isUnlockPaidBBurstActive()) {
                        module.log(4, TAG, "suppress unlockpaid/b success toast (burst)");
                        return null;
                    }
                }
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidBRewardSuccess(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> handler = Class.forName(
                "com.ximalaya.ting.android.host.manager.ad.unlockpaid.b$8$1", false, cl);
        Class<?> baseResponse = Class.forName(BASE_RESPONSE, false, cl);
        Method method = handler.getDeclaredMethod("a", baseResponse);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object response = chain.getArgs().get(0);
            if (response != null) {
                try {
                    Method getRet = response.getClass().getMethod("getRet");
                    Object ret = getRet.invoke(response);
                    if (readBypassDailyLimit() && ret instanceof Integer && (Integer) ret != 0) {
                        Method setRet = response.getClass().getMethod("setRet", int.class);
                        setRet.invoke(response, 0);
                        ret = 0;
                        module.log(4, TAG, "bypass daily limit: patch increaseFreeTime ret");
                        Log.i(TAG, "bypass daily limit: patch increaseFreeTime ret");
                    }
                    if (ret instanceof Integer && (Integer) ret == 0) {
                        Object handlerObj = chain.getThisObject();
                        Object task = getField(handlerObj, "a");
                        Object unlockB = getField(task, "b");
                        onUnlockPaidBRewardSuccess(module, cl, unlockB);
                    } else {
                        clearUnlockPaidBBurst("api ret!=" + ret);
                    }
                } catch (Throwable t) {
                    module.log(5, TAG, "unlockpaid/b reward response: " + t.getMessage());
                }
            }
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookUnlockPaidBRewardNotify(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> unlock = Class.forName(UNLOCKPAID_B, false, cl);
        Class<?> playCallback = Class.forName(
                "com.ximalaya.ting.android.host.manager.play.j$g", false, cl);
        Method method = unlock.getDeclaredMethod("a", unlock, int.class, playCallback);
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            Object result = chain.proceed();
            onUnlockPaidBRewardSuccess(module, cl, chain.getArgs().get(0));
            return result;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static void beginUnlockPaidBBurst(Method method, Object[] args, int totalClaims) {
        UnlockPaidBBurstSession session = new UnlockPaidBBurstSession();
        session.totalClaims = XimalayaPrefs.clampBurstCount(totalClaims);
        session.remaining = session.totalClaims - 1;
        session.unlockType = args[0] instanceof Integer ? (Integer) args[0] : 0;
        Class<?>[] params = method.getParameterTypes();
        session.fourParam = params.length == 4;
        for (int i = 0; i < params.length; i++) {
            if (REWARD_EXTRA_PARAMS.equals(params[i].getName())) {
                session.rewardExtra = args[i];
            } else if (params[i] == String.class && args[i] instanceof String) {
                String value = (String) args[i];
                if (!value.isEmpty()) {
                    session.source = value;
                }
            } else if (UNLOCKPAID_B_CALLBACK.equals(params[i].getName())) {
                session.callback = args[i];
            }
        }
        if (session.callback == null && args.length > 0) {
            session.callback = args[args.length - 1];
        }
        UNLOCK_B_BURST = session;
    }

    private static void clearUnlockPaidBBurst(String reason) {
        synchronized (UNLOCK_B_BURST_LOCK) {
            if (UNLOCK_B_BURST != null) {
                UNLOCK_B_BURST = null;
            }
        }
    }

    private static void onUnlockPaidBRewardSuccess(
            ZoeModule module, ClassLoader cl, Object unlockB
    ) {
        UnlockPaidBBurstSession snapshot;
        int claimIndex;
        boolean continueBurst;
        synchronized (UNLOCK_B_BURST_LOCK) {
            if (UNLOCK_B_BURST == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - UNLOCK_B_BURST.lastSuccessMs < UNLOCK_B_SUCCESS_DEBOUNCE_MS) {
                return;
            }
            UNLOCK_B_BURST.lastSuccessMs = now;
            continueBurst = UNLOCK_B_BURST.remaining > 0;
            claimIndex = UNLOCK_B_BURST.totalClaims - UNLOCK_B_BURST.remaining;
            snapshot = copyBurstSession(UNLOCK_B_BURST);
            UNLOCK_B_BURST.remaining--;
            if (!continueBurst || UNLOCK_B_BURST.remaining < 0) {
                module.log(4, TAG, "unlockpaid/b burst finished (" + snapshot.totalClaims + " claims)");
                Log.i(TAG, "unlockpaid/b burst finished (" + snapshot.totalClaims + " claims)");
                UNLOCK_B_BURST = null;
            } else {
                UNLOCK_B_BURST.autoRepeat = true;
            }
        }
        module.log(4, TAG, "unlockpaid/b burst claim " + claimIndex + "/" + snapshot.totalClaims);
        Log.i(TAG, "unlockpaid/b burst claim " + claimIndex + "/" + snapshot.totalClaims);
        if (!continueBurst && snapshot.totalClaims > 1) {
            showBurstFinishedToast(module, cl, snapshot.totalClaims);
        }
        if (!continueBurst) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> repeatUnlockPaidBBurst(module, cl, unlockB, snapshot), UNLOCK_B_BURST_DELAY_MS);
    }

    private static void showBurstFinishedToast(
            ZoeModule module, ClassLoader cl, int totalClaims
    ) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            Activity activity = resolveHostActivity(cl);
            if (activity == null || activity.isFinishing()) {
                return;
            }
            try {
                android.widget.Toast.makeText(
                        activity,
                        "已静默领取 " + totalClaims + " 次（约 " + (totalClaims * 20) + " 分钟）",
                        android.widget.Toast.LENGTH_LONG
                ).show();
            } catch (Throwable t) {
                module.log(5, TAG, "burst toast: " + t.getMessage());
            }
        });
    }

    private static UnlockPaidBBurstSession copyBurstSession(UnlockPaidBBurstSession source) {
        UnlockPaidBBurstSession copy = new UnlockPaidBBurstSession();
        copy.totalClaims = source.totalClaims;
        copy.remaining = source.remaining;
        copy.autoRepeat = source.autoRepeat;
        copy.fourParam = source.fourParam;
        copy.unlockType = source.unlockType;
        copy.rewardExtra = source.rewardExtra;
        copy.source = source.source;
        copy.callback = source.callback;
        copy.lastSuccessMs = source.lastSuccessMs;
        return copy;
    }

    private static void repeatUnlockPaidBBurst(
            ZoeModule module, ClassLoader cl, Object unlockB, UnlockPaidBBurstSession session
    ) {
        try {
            resetUnlockPaidBForBurst(unlockB);
            Class<?> unlock = Class.forName(UNLOCKPAID_B, false, cl);
            Object callback = session.callback;
            if (callback == null) {
                callback = getField(unlockB, "i");
            }
            Method method;
            Object[] args;
            if (session.fourParam) {
                Class<?> rewardExtra = Class.forName(REWARD_EXTRA_PARAMS, false, cl);
                method = unlock.getDeclaredMethod(
                        "a", int.class, rewardExtra, String.class,
                        Class.forName(UNLOCKPAID_B_CALLBACK, false, cl));
                args = new Object[]{session.unlockType, session.rewardExtra, session.source, callback};
            } else {
                method = unlock.getDeclaredMethod(
                        "a", int.class, String.class,
                        Class.forName(UNLOCKPAID_B_CALLBACK, false, cl));
                args = new Object[]{session.unlockType, session.source, callback};
            }
            method.setAccessible(true);
            invokeUnlockPaidBEntry(module, unlockB, method, args);
            module.log(4, TAG, "unlockpaid/b burst re-entry (" + session.source + ")");
            scheduleSilentBurstMaintenance(module, cl, unlockB);
        } catch (Throwable t) {
            clearUnlockPaidBBurst("repeat failed");
            module.log(5, TAG, "unlockpaid/b burst repeat: " + t.getMessage());
        }
    }

    private static void resetUnlockPaidBForBurst(Object unlockB) {
        try {
            resetUnlockPaidBRewardGate(unlockB);
            setInstanceField(unlockB, "y", false);
            setInstanceField(unlockB, "p", false);
        } catch (Throwable ignored) {
        }
    }

    private static String extractUnlockPaidBSource(Method method, Object[] args) {
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i] == String.class && args[i] instanceof String) {
                String source = (String) args[i];
                if (!source.isEmpty()) {
                    return source;
                }
            }
        }
        return "?";
    }

    private static void prepareUnlockPaidBFromArgs(
            ZoeModule module, ClassLoader cl, Object callback, Iterable<?> args
    ) {
        try {
            Object unlockB = resolveUnlockPaidBFromCallback(callback);
            if (unlockB == null) {
                return;
            }
            if (getField(unlockB, "c") != null) {
                return;
            }
            Object thirdAd = findThirdAd(args);
            if (thirdAd != null && injectUnlockPaidBAdFromThirdAd(module, unlockB, thirdAd)) {
                return;
            }
            for (Object arg : args) {
                if (selectUnlockPaidBAd(module, cl, unlockB, arg)) {
                    return;
                }
            }
        } catch (Throwable t) {
            module.log(5, TAG, "prepare unlockpaid/b ad: " + t.getMessage());
        }
    }

    private static Object findThirdAd(Iterable<?> args) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String name = arg.getClass().getName();
            if (name.endsWith(".thirdad.a.a") || name.contains("thirdad.a.a")) {
                return arg;
            }
        }
        return null;
    }

    private static boolean injectUnlockPaidBAdFromThirdAd(
            ZoeModule module, Object unlockB, Object thirdAd
    ) {
        try {
            Method method = thirdAd.getClass().getMethod("v");
            Object advertis = method.invoke(thirdAd);
            if (!isUnlockTrackAd(advertis)) {
                return false;
            }
            setInstanceField(unlockB, "c", advertis);
            module.log(4, TAG, "unlockpaid/b ad injected from thirdAd");
            Log.i(TAG, "unlockpaid/b ad injected from thirdAd");
            return true;
        } catch (Throwable t) {
            module.log(5, TAG, "inject thirdAd ad: " + t.getMessage());
            return false;
        }
    }

    private static Object resolveUnlockPaidBFromCallback(Object callback) throws Exception {
        if (callback == null) {
            return null;
        }
        Object unlockB = getField(callback, "b");
        if (unlockB != null) {
            return unlockB;
        }
        return getField(callback, "c");
    }

    private static boolean selectUnlockPaidBAd(
            ZoeModule module, ClassLoader cl, Object unlockB, Object arg
    ) throws Exception {
        if (arg == null) {
            return false;
        }
        if (arg instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) arg;
            for (Object item : list) {
                if (isUnlockTrackAd(item)) {
                    setInstanceField(unlockB, "c", item);
                    module.log(4, TAG, "selected unlockpaid/b ad from list");
                    return true;
                }
            }
            return false;
        }
        if (isUnlockTrackAd(arg)) {
            setInstanceField(unlockB, "c", arg);
            module.log(4, TAG, "selected unlockpaid/b ad from arg");
            return true;
        }
        return false;
    }

    private static void ensureUnlockPaidBAdSelected(
            ZoeModule module, ClassLoader cl, Object unlockB
    ) throws Exception {
        if (getField(unlockB, "c") != null) {
            return;
        }
        Object listObj = getField(unlockB, "b");
        if (!(listObj instanceof java.util.List)) {
            return;
        }
        java.util.List<?> list = (java.util.List<?>) listObj;
        for (Object item : list) {
            if (isUnlockTrackAd(item)) {
                setInstanceField(unlockB, "c", item);
                module.log(4, TAG, "selected unlockpaid/b ad from cache list");
                return;
            }
        }
    }

    private static boolean isUnlockTrackAd(Object value) {
        if (value == null) {
            return false;
        }
        try {
            Class<?> track = Class.forName(
                    AD_UNLOCK_TRACK, false, value.getClass().getClassLoader());
            return track.isInstance(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void fastGrantUnlockPaidTrackIfPending(
            ZoeModule module, ClassLoader cl, Object unlockD
    ) {
        try {
            Object dialog = getField(unlockD, "q");
            if (dialog == null) {
                return;
            }
            Method isShowing = dialog.getClass().getMethod("isShowing");
            if (!Boolean.TRUE.equals(isShowing.invoke(dialog))) {
                return;
            }
            module.log(4, TAG, "unlockpaid/d loading still visible -> fast grant");
            setInstanceField(unlockD, "p", true);
            setInstanceField(unlockD, "m", true);
            triggerUnlockPaidTrackReward(module, cl, unlockD);
        } catch (Throwable t) {
            module.log(5, TAG, "fast grant unlockpaid/d: " + t.getMessage());
        }
    }

    private static void grantBusinessModule9(ZoeModule module, Object bm9) {
        try {
            ClassLoader cl = bm9.getClass().getClassLoader();
            dismissUnlockPaidSingleton(module, cl);
            invokeOnTarget(module, bm9, "a", null);
            setInstanceField(bm9, "e", true);
            setInstanceField(bm9, "d", "1");
            setInstanceField(bm9, "g", "1");
            setInstanceField(bm9, "p", System.currentTimeMillis() - 2000L);
            Method grant = bm9.getClass().getDeclaredMethod("b", String.class);
            grant.setAccessible(true);
            grant.invoke(bm9, "onReward");
            module.log(4, TAG, "grant dispatched");
        } catch (Throwable t) {
            module.log(5, TAG, "grant failed: " + t.getMessage());
        }
    }

    private static void dismissUnlockPaidLoading(ZoeModule module, ClassLoader cl, Object callback) {
        try {
            String name = callback.getClass().getName();
            if (name.contains("unlockpaid$b$")) {
                Object unlockB = getField(callback, "b");
                if (unlockB != null) {
                    dismissUnlockPaidBLoading(module, unlockB);
                }
                return;
            }
            if (name.contains("unlockpaid$d$")) {
                Object unlockD = getField(callback, "c");
                if (unlockD != null) {
                    dismissUnlockPaidTrackLoading(module, unlockD);
                }
                return;
            }
            Object unlock = getField(callback, "c");
            if (unlock == null) {
                dismissUnlockPaidSingleton(module, cl);
                return;
            }
            Class<?> unlockCls = Class.forName(UNLOCKPAID_C, false, cl);
            Method dismiss = unlockCls.getDeclaredMethod("b", unlockCls);
            dismiss.setAccessible(true);
            dismiss.invoke(null, unlock);
            module.log(4, TAG, "loading dismissed");
        } catch (Throwable t) {
            module.log(5, TAG, "dismiss loading: " + t.getMessage());
        }
    }

    private static void dismissUnlockPaidTrackLoading(ZoeModule module, Object unlockD) {
        try {
            Method dismiss = unlockD.getClass().getDeclaredMethod("t");
            dismiss.setAccessible(true);
            dismiss.invoke(unlockD);
            module.log(4, TAG, "unlockpaid/d loading dismissed");
        } catch (Throwable t) {
            module.log(5, TAG, "dismiss unlockpaid/d: " + t.getMessage());
        }
    }

    private static void triggerUnlockPaidTrackReward(
            ZoeModule module, ClassLoader cl, Object unlockD
    ) throws Exception {
        Class<?> unlockCls = Class.forName(UNLOCKPAID_D, false, cl);
        Method grant = unlockCls.getDeclaredMethod("b", unlockCls, boolean.class);
        grant.setAccessible(true);
        grant.invoke(null, unlockD, false);
        module.log(4, TAG, "unlockpaid/d reward triggered");
    }

    private static void dismissUnlockPaidBLoading(ZoeModule module, Object unlockB) {
        try {
            Class<?> unlockCls = unlockB.getClass();
            Method dismiss = unlockCls.getDeclaredMethod("b", unlockCls);
            dismiss.setAccessible(true);
            dismiss.invoke(null, unlockB);
            module.log(4, TAG, "unlockpaid/b loading dismissed");
        } catch (Throwable t) {
            module.log(5, TAG, "dismiss unlockpaid/b: " + t.getMessage());
        }
    }

    private static boolean tryUnlockPaidBReward(
            ZoeModule module, ClassLoader cl, Object unlockB
    ) {
        int key = System.identityHashCode(unlockB);
        synchronized (UNLOCK_B_REWARD_GATE_LOCK) {
            if (!UNLOCK_B_REWARD_GATE.add(key)) {
                module.log(4, TAG, "unlockpaid/b reward gated (duplicate)");
                Log.i(TAG, "unlockpaid/b reward gated (duplicate)");
                return false;
            }
        }
        try {
            Class<?> unlockCls = Class.forName(UNLOCKPAID_B, false, cl);
            Method grant = unlockCls.getDeclaredMethod("h", unlockCls, boolean.class);
            grant.setAccessible(true);
            grant.invoke(null, unlockB, false);
            module.log(4, TAG, "unlockpaid/b reward triggered");
            Log.i(TAG, "unlockpaid/b reward triggered");
            return true;
        } catch (Throwable t) {
            synchronized (UNLOCK_B_REWARD_GATE_LOCK) {
                UNLOCK_B_REWARD_GATE.remove(key);
            }
            module.log(5, TAG, "unlockpaid/b reward failed: " + t.getMessage());
            Log.w(TAG, "unlockpaid/b reward failed: " + t.getMessage());
            return false;
        }
    }

    private static void dismissUnlockPaidSingleton(ZoeModule module, ClassLoader cl) {
        try {
            Class<?> unlockCls = Class.forName(UNLOCKPAID_C, false, cl);
            Object unlock = unlockCls.getMethod("a").invoke(null);
            Method dismiss = unlockCls.getDeclaredMethod("b", unlockCls);
            dismiss.setAccessible(true);
            dismiss.invoke(null, unlock);
        } catch (Throwable t) {
            module.log(5, TAG, "dismiss singleton loading: " + t.getMessage());
        }
    }

    private static void completeUnlockPaidReward(ZoeModule module, ClassLoader cl, Object callback) {
        try {
            Object unlock = getField(callback, "c");
            if (unlock == null) {
                return;
            }
            Class<?> unlockCls = Class.forName(UNLOCKPAID_C, false, cl);
            Method setReward = unlockCls.getDeclaredMethod("c", unlockCls, boolean.class);
            setReward.setAccessible(true);
            setReward.invoke(null, unlock, true);
            Method finish = unlockCls.getDeclaredMethod("a", unlockCls, Activity.class, boolean.class);
            finish.setAccessible(true);
            finish.invoke(null, unlock, null, true);
            module.log(4, TAG, "unlockpaid reward completed");
        } catch (Throwable t) {
            module.log(5, TAG, "complete unlockpaid: " + t.getMessage());
        }
    }

    private static String patchIncentiveJson(ZoeModule module, ClassLoader cl, String json) {
        if (json == null || json.isEmpty()) {
            return "{\"data\":{\"success\":true,\"retry\":false,\"toast\":\"领取成功\"}}";
        }
        try {
            Class<?> gsonCls = Class.forName("com.google.gson.Gson", false, cl);
            Class<?> responseCls = Class.forName(INCENTIVE_REWARD_RESPONSE, false, cl);
            Object gson = gsonCls.getDeclaredConstructor().newInstance();
            Method fromJson = gsonCls.getMethod("fromJson", String.class, Class.class);
            Object response = fromJson.invoke(gson, json, responseCls);
            patchIncentiveResponse(module, response);
            Method toJson = gsonCls.getMethod("toJson", Object.class);
            return (String) toJson.invoke(gson, response);
        } catch (Throwable t) {
            module.log(5, TAG, "patchIncentiveJson: " + t.getMessage());
            return json;
        }
    }

    private static int hookBusinessModule9FailRedirect(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> handler = Class.forName(BUSINESS_MODULE_9, false, cl);
        Method failMethod = handler.getDeclaredMethod("a", String.class);
        String id = hookId(failMethod);
        if (!HOOKED.add(id)) {
            return 0;
        }
        failMethod.setAccessible(true);
        module.hook(failMethod).setExceptionMode(mode).intercept(chain -> {
            String reason = (String) chain.getArgs().get(0);
            module.log(4, TAG, "redirect ad fail -> grant (" + reason + ")");
            try {
                Object self = chain.getThisObject();
                dismissUnlockPaidSingleton(module, cl);
                setInstanceField(self, "e", true);
                Method grant = handler.getDeclaredMethod("b", String.class);
                grant.setAccessible(true);
                grant.invoke(self, "onReward");
            } catch (Throwable t) {
                module.log(5, TAG, "fail redirect: " + t.getMessage());
            }
            return null;
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static boolean isUnlockPaidBInstance(Object value, ClassLoader cl) {
        if (value == null) {
            return false;
        }
        try {
            Class<?> unlockCls = Class.forName(UNLOCKPAID_B, false, cl);
            return unlockCls.isInstance(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isUnlockPaidBVideoCallback(Object callback) {
        if (callback == null) {
            return false;
        }
        String cbName = callback.getClass().getName();
        return cbName.contains("unlockpaid$b$") || cbName.contains("unlockpaid.b$");
    }

    private static void dispatchVideoAdReward(ZoeModule module, ClassLoader cl, Object callback) {
        String cbName = callback.getClass().getName();
        if (isUnlockPaidBVideoCallback(callback)) {
            grantUnlockPaidB(module, cl, callback);
            return;
        }
        if (cbName.contains("unlockpaid$d$")) {
            grantUnlockPaidTrack(module, cl, callback);
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            dismissUnlockPaidLoading(module, cl, callback);
            try {
                Class<?> adManagerB = Class.forName(AD_MANAGER_B, false, cl);
                invokeOnTarget(module, callback, "a",
                        new Class<?>[]{boolean.class, int.class, adManagerB},
                        true, 1, null);
                invokeUnlockPaidVerify(module, callback);
            } catch (Throwable t) {
                module.log(5, TAG, "dispatch reward: " + t.getMessage());
            }
        });
        handler.postDelayed(() -> invokeOnTarget(module, callback, "b", null), 150L);
        handler.postDelayed(() -> {
            invokeOnTarget(module, callback, "a", new Class<?>[]{boolean.class}, true);
            completeUnlockPaidReward(module, cl, callback);
        }, 300L);
    }

    private static void grantUnlockPaidB(ZoeModule module, ClassLoader cl, Object callback) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                Object unlockB = resolveUnlockPaidBFromCallback(callback);
                if (unlockB == null) {
                    unlockB = Class.forName(UNLOCKPAID_B, false, cl)
                            .getMethod("a")
                            .invoke(null);
                }
                if (!isUnlockPaidBInstance(unlockB, cl)) {
                    module.log(5, TAG, "unlockpaid/b invalid manager: "
                            + unlockB.getClass().getName());
                    return;
                }
                if (getField(unlockB, "c") == null) {
                    module.log(5, TAG, "unlockpaid/b ad not ready, skip grant");
                    Log.w(TAG, "unlockpaid/b ad not ready, skip grant");
                    return;
                }

                Object rewardExtra = getField(unlockB, "B");
                if (rewardExtra == null) {
                    rewardExtra = getField(unlockB, "C");
                }
                if (rewardExtra != null) {
                    Method getTwice = rewardExtra.getClass().getMethod("getRewardTwiceCallBack");
                    Object twice = getTwice.invoke(rewardExtra);
                    if (twice != null) {
                        dismissUnlockPaidBLoading(module, unlockB);
                        twice.getClass().getMethod("callReward").invoke(twice);
                        module.log(4, TAG, "unlockpaid/b callReward dispatched");
                        Log.i(TAG, "unlockpaid/b callReward dispatched");
                        return;
                    }
                }
                simulateUnlockPaidBVideoAdReward(module, cl, callback, unlockB);
            } catch (Throwable t) {
                module.log(5, TAG, "grant unlockpaid/b: " + t.getMessage());
                Log.w(TAG, "grant unlockpaid/b: " + t.getMessage());
            }
        });
    }

    private static void simulateUnlockPaidBVideoAdReward(
            ZoeModule module, ClassLoader cl, Object callback, Object unlockB
    ) {
        boolean burst = isUnlockPaidBBurstActive();
        long lifecycleDelay = burst ? UNLOCK_B_BURST_LIFECYCLE_DELAY_MS : 300L;
        long successDelay = burst ? UNLOCK_B_BURST_SUCCESS_DELAY_MS : 500L;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            dismissUnlockPaidBLoading(module, unlockB);
            invokeOnTarget(module, callback, "a", null);
            invokeOnTarget(module, callback, "c", null);
            invokeOnTarget(module, callback, "b", null);
            module.log(4, TAG, "unlockpaid/b video lifecycle simulated");
            Log.i(TAG, "unlockpaid/b video lifecycle simulated");
        });
        handler.postDelayed(() -> {
            try {
                Method close = findMethodExact(callback.getClass(), "a",
                        new Class<?>[]{boolean.class});
                if (close != null) {
                    close.setAccessible(true);
                    close.invoke(callback, true);
                    module.log(4, TAG, "unlockpaid/b ad close -> notify reward");
                    Log.i(TAG, "unlockpaid/b ad close -> notify reward");
                    handler.postDelayed(
                            () -> onUnlockPaidBRewardSuccess(module, cl, unlockB), successDelay);
                    return;
                }
            } catch (Throwable t) {
                module.log(5, TAG, "unlockpaid/b ad close: " + t.getMessage());
            }
            tryUnlockPaidBReward(module, cl, unlockB);
        }, lifecycleDelay);
    }

    private static void grantUnlockPaidTrack(ZoeModule module, ClassLoader cl, Object callback) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try {
                Object unlockD = getField(callback, "c");
                if (unlockD == null) {
                    unlockD = Class.forName(UNLOCKPAID_D, false, cl).getMethod("b").invoke(null);
                }
                dismissUnlockPaidTrackLoading(module, unlockD);
                invokeOnTarget(module, callback, "c", null);
                setInstanceField(unlockD, "p", true);
                setInstanceField(unlockD, "m", true);

                Object rewardExtra = getField(callback, "b");
                if (rewardExtra != null) {
                    Method getTwice = rewardExtra.getClass().getMethod("getRewardTwiceCallBack");
                    Object twice = getTwice.invoke(rewardExtra);
                    if (twice != null) {
                        twice.getClass().getMethod("callReward").invoke(twice);
                        module.log(4, TAG, "unlockpaid/d callReward dispatched");
                        return;
                    }
                }
                triggerUnlockPaidTrackReward(module, cl, unlockD);
            } catch (Throwable t) {
                module.log(5, TAG, "grant unlockpaid/d: " + t.getMessage());
            }
        });
    }

    private static void invokeUnlockPaidVerify(ZoeModule module, Object callback) {
        String name = callback.getClass().getName();
        if (name.contains("unlockpaid$c$") || name.contains("unlockpaid$b$")) {
            invokeOnTarget(module, callback, "c", null);
        }
    }

    private static void invokeOnTarget(
            ZoeModule module, Object target, String name, Class<?>[] paramTypes, Object... args
    ) {
        try {
            Method method = findMethodExact(target.getClass(), name, paramTypes);
            if (method == null) {
                module.log(5, TAG, name + " not found on " + target.getClass().getSimpleName());
                return;
            }
            method.setAccessible(true);
            if (args == null || args.length == 0) {
                method.invoke(target);
            } else {
                method.invoke(target, args);
            }
        } catch (Throwable t) {
            module.log(5, TAG, name + " invoke failed: " + t.getMessage());
        }
    }

    private static Method findMethodExact(Class<?> cls, String name, Class<?>[] paramTypes) {
        for (Method method : cls.getMethods()) {
            if (matches(method, name, paramTypes)) {
                return method;
            }
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (matches(method, name, paramTypes)) {
                return method;
            }
        }
        return null;
    }

    private static boolean matches(Method method, String name, Class<?>[] paramTypes) {
        if (!name.equals(method.getName())) {
            return false;
        }
        Class<?>[] actual = method.getParameterTypes();
        if (paramTypes == null) {
            return actual.length == 0;
        }
        if (actual.length != paramTypes.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (!actual[i].isAssignableFrom(paramTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static Object findVideoAdCallback(ClassLoader cl, Iterable<?> args) {
        Object callback = findArgByInterface(cl, args, VIDEO_AD_CALLBACK_H);
        if (callback != null) {
            return callback;
        }
        callback = findArgByInterface(cl, args, VIDEO_AD_CALLBACK);
        if (callback != null) {
            return callback;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String name = arg.getClass().getName();
            if (name.contains("unlockpaid$b$") || name.contains("unlockpaid.b$")) {
                return arg;
            }
        }
        return null;
    }

    private static Object findArgByInterface(
            ClassLoader cl, Iterable<?> args, String interfaceName
    ) {
        try {
            Class<?> callbackType = Class.forName(interfaceName, false, cl);
            for (Object arg : args) {
                if (arg != null && callbackType.isAssignableFrom(arg.getClass())) {
                    return arg;
                }
            }
        } catch (Throwable ignored) {
            return findArgByType(args, interfaceName);
        }
        return null;
    }

    private static Object findArgByType(Iterable<?> args, String typeName) {
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (typeName.equals(arg.getClass().getName()) || implementsType(arg.getClass(), typeName)) {
                return arg;
            }
        }
        return null;
    }

    private static boolean implementsType(Class<?> cls, String typeName) {
        for (Class<?> iface : cls.getInterfaces()) {
            if (typeName.equals(iface.getName())) {
                return true;
            }
        }
        Class<?> superCls = cls.getSuperclass();
        return superCls != null && implementsType(superCls, typeName);
    }

    private static void setInstanceField(Object target, String name, Object value) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static int hookExplicitGoldCoinHandlers(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> goldCoin = Class.forName(AD_GOLD_COIN_DATA, false, cl);
        int count = 0;
        for (String className : GOLD_COIN_HANDLERS) {
            count += hookGoldCoinMethod(module, cl, className, goldCoin, mode);
        }
        return count > 0 ? 1 : 0;
    }

    private static int hookGoldCoinMethod(
            ZoeModule module,
            ClassLoader cl,
            String className,
            Class<?> goldCoin,
            XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> handler = Class.forName(className, false, cl);
        if (Modifier.isInterface(handler.getModifiers())) {
            return 0;
        }
        Method method = handler.getDeclaredMethod("a", goldCoin);
        if (Modifier.isAbstract(method.getModifiers())) {
            return 0;
        }
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            patchGoldCoinData(module, chain.getArgs().get(0));
            return chain.proceed();
        });
        module.log(4, TAG, "hooked " + id);
        return 1;
    }

    private static int hookGsonRewardParse(ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> gson = Class.forName("com.google.gson.Gson", false, cl);
            for (Method method : gson.getDeclaredMethods()) {
                if (!"fromJson".equals(method.getName()) || method.getParameterTypes().length != 2) {
                    continue;
                }
                Class<?> second = method.getParameterTypes()[1];
                if (second == String.class) {
                    continue;
                }
                String id = hookId(method);
                if (!HOOKED.add(id)) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    patchRewardObject(module, result);
                    return result;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "Gson hook failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static int hookGoldCoinHandlers(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) {
        int count = 0;
        try {
            Class<?> businessModule = Class.forName(
                    "com.ximalaya.ting.android.reactnative.modules.BusinessModule", false, cl);
            Class<?> goldCoin = Class.forName(AD_GOLD_COIN_DATA, false, cl);
            for (Class<?> inner : businessModule.getDeclaredClasses()) {
                if (Modifier.isInterface(inner.getModifiers())) {
                    continue;
                }
                for (Method method : inner.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1 || params[0] != goldCoin) {
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
                    module.hook(method).setExceptionMode(mode).intercept(chain -> {
                        patchGoldCoinData(module, chain.getArgs().get(0));
                        return chain.proceed();
                    });
                    count++;
                }
            }
        } catch (Throwable t) {
            module.log(5, TAG, "gold coin scan failed: " + t.getMessage());
        }
        return count > 0 ? 1 : 0;
    }

    private static int hookIncentiveRewardDataSetters(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode
    ) throws Throwable {
        Class<?> data = Class.forName(INCENTIVE_REWARD_DATA, false, cl);
        int count = 0;
        Method setSuccess = data.getDeclaredMethod("setSuccess", boolean.class);
        Method setRetry = data.getDeclaredMethod("setRetry", boolean.class);
        count += hookArgOverride(module, setSuccess, 0, true, mode);
        count += hookArgOverride(module, setRetry, 0, false, mode);
        return count;
    }

    private static int hookArgOverride(
            ZoeModule module, Method method, int index, Object value, XposedInterface.ExceptionMode mode
    ) {
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        method.setAccessible(true);
        module.hook(method).setExceptionMode(mode).intercept(chain -> {
            chain.getArgs().set(index, value);
            return chain.proceed();
        });
        return 1;
    }

    private static int hookBooleanGetter(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String methodName,
            boolean value,
            XposedInterface.ExceptionMode mode
    ) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method method = cls.getDeclaredMethod(methodName);
            String id = hookId(method);
            if (!HOOKED.add(id)) {
                return 0;
            }
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> value);
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            module.log(5, TAG, className + "#" + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static boolean implementsRewardListener(Class<?> cls) {
        for (Class<?> iface : cls.getInterfaces()) {
            if (REWARD_LISTENER.equals(iface.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void dispatchInstantReward(ZoeModule module, Object listener) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> safeCall(module, listener, "onAdLoad", new Object[]{null}));
        handler.postDelayed(() -> {
            safeCall(module, listener, "onAdPlayStart");
            safeCall(module, listener, "onReward", true);
            safeCall(module, listener, "onVideoComplete");
            handler.postDelayed(() -> safeCall(module, listener, "onAdClose"), 80L);
        }, 200L);
    }

    private static void safeCall(ZoeModule module, Object target, String methodName, Object... args) {
        try {
            if ("onAdLoad".equals(methodName)) {
                Method method = findMethod(target.getClass(), methodName, 1);
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(target, new Object[]{null});
                    return;
                }
            }
            if (args.length == 0) {
                Method method = findMethod(target.getClass(), methodName, 0);
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(target);
                }
                return;
            }
            if ("onReward".equals(methodName) && args.length == 1) {
                Method method = findMethod(target.getClass(), methodName, 1);
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(target, args[0]);
                }
            }
        } catch (Throwable t) {
            module.log(5, TAG, methodName + " failed: " + t.getMessage());
        }
    }

    private static void safeCall(ZoeModule module, Object target, String methodName) {
        safeCall(module, target, methodName, new Object[0]);
    }

    private static void patchRewardObject(ZoeModule module, Object result) {
        if (result == null) {
            return;
        }
        String name = result.getClass().getName();
        if (AD_GOLD_COIN_DATA.equals(name)) {
            patchGoldCoinData(module, result);
        } else if (INCENTIVE_REWARD_RESPONSE.equals(name)) {
            patchIncentiveResponse(module, result);
        } else if (UNLOCK_RESULT.equals(name)) {
            patchUnlockResult(module, result);
        } else if (BASE_RESPONSE.equals(name)) {
            patchBaseResponse(module, result);
        }
    }

    private static void patchBaseResponse(ZoeModule module, Object data) {
        if (data == null) {
            return;
        }
        try {
            Method setRet = data.getClass().getMethod("setRet", int.class);
            setRet.invoke(data, 0);
        } catch (Throwable t) {
            module.log(5, TAG, "patchBaseResponse: " + t.getMessage());
        }
    }

    private static void patchUnlockResult(ZoeModule module, Object data) {
        if (data == null) {
            return;
        }
        try {
            setBooleanField(data, "success", true);
            int remain = getIntField(data, "remainTimes");
            if (remain <= 0) {
                setIntField(data, "remainTimes", 1);
            }
            long expire = data.getClass().getField("localNewPermissionExpireSecond").getLong(data);
            if (expire <= 0L) {
                data.getClass().getField("localNewPermissionExpireSecond")
                        .setLong(data, System.currentTimeMillis() / 1000L + 1200L);
            }
            long base = data.getClass().getField("localNewBaseTimeStamp").getLong(data);
            if (base <= 0L) {
                data.getClass().getField("localNewBaseTimeStamp")
                        .setLong(data, System.currentTimeMillis() / 1000L);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "patchUnlockResult: " + t.getMessage());
        }
    }

    private static void patchGoldCoinData(ZoeModule module, Object data) {
        if (data == null) {
            return;
        }
        try {
            setBooleanField(data, "success", true);
            setBooleanField(data, "retry", false);
            int coins = getIntField(data, "coins");
            if (coins <= 0) {
                setIntField(data, "coins", 1);
            }
        } catch (Throwable t) {
            module.log(5, TAG, "patchGoldCoinData: " + t.getMessage());
        }
    }

    private static void patchIncentiveResponse(ZoeModule module, Object response) {
        try {
            Method getData = response.getClass().getMethod("getData");
            Object data = getData.invoke(response);
            if (data == null) {
                Class<?> dataCls = Class.forName(
                        INCENTIVE_REWARD_DATA, false, response.getClass().getClassLoader());
                data = dataCls.getDeclaredConstructor().newInstance();
                response.getClass().getMethod("setData", dataCls).invoke(response, data);
            }
            data.getClass().getMethod("setSuccess", boolean.class).invoke(data, true);
            data.getClass().getMethod("setRetry", boolean.class).invoke(data, false);
            try {
                setObjectField(data, "toast", "领取成功");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            module.log(5, TAG, "patchIncentiveResponse: " + t.getMessage());
        }
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getField(name);
        field.setBoolean(target, value);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getField(name);
        field.setInt(target, value);
    }

    private static int getIntField(Object target, String name) throws Exception {
        return target.getClass().getField(name).getInt(target);
    }

    private static void setObjectField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method method : cls.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static String hookId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }
}
