package com.afusekt.lsp;

import android.util.Log;

import com.afusekt.lsp.hook.AfusektHooks;
import com.afusekt.lsp.hook.AntiDetectHooks;
import com.afusekt.lsp.hook.CapyPlayerHooks;
import com.afusekt.lsp.hook.FanqieHooks;
import com.afusekt.lsp.hook.FanqieNovelHooks;
import com.afusekt.lsp.hook.FanqieNovelSafeHooks;
import com.afusekt.lsp.hook.LvchaHooks;
import com.afusekt.lsp.hook.MtxxHooks;
import com.afusekt.lsp.hook.VToolsHooks;
import com.afusekt.lsp.hook.XimalayaHooks;
import com.afusekt.lsp.hook.VidHubAntiHookBypass;
import com.afusekt.lsp.hook.VidHubHooks;
import com.afusekt.lsp.hook.VidHubNativeGuard;
import com.afusekt.lsp.hook.VidHubNisGuard;
import com.afusekt.lsp.hook.VidHubScopeGuard;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Classic Xposed entry (NPatch / LSPatch). Do not reference this class from
 * libxposed ({@link ZoeModule}) paths — Vector API 102+ rejects {@link IXposedHookLoadPackage}.
 */
public final class MainHook implements IXposedHookLoadPackage {

    // Aliases kept for legacy hook classes under hook/ that still import MainHook.
    public static final String TAG = ZoeIds.TAG;
    public static final String AFUSEKT_PACKAGE = ZoeIds.AFUSEKT_PACKAGE;
    public static final String CAPYPLAYER_PACKAGE = ZoeIds.CAPYPLAYER_PACKAGE;
    public static final String VIDHUB_PACKAGE = ZoeIds.VIDHUB_PACKAGE;
    public static final String VTOOLS_PACKAGE = ZoeIds.VTOOLS_PACKAGE;
    public static final String XIMALAYA_PACKAGE = ZoeIds.XIMALAYA_PACKAGE;
    public static final String MTXX_PACKAGE = ZoeIds.MTXX_PACKAGE;
    public static final String FANQIE_PACKAGE = ZoeIds.FANQIE_PACKAGE;
    public static final String FANQIE_NOVEL_PACKAGE = ZoeIds.FANQIE_NOVEL_PACKAGE;
    public static final String HONGGUO_PACKAGE = ZoeIds.HONGGUO_PACKAGE;
    public static final String KYLIN_PACKAGE = ZoeIds.KYLIN_PACKAGE;
    public static final String LVCHA_PACKAGE = ZoeIds.LVCHA_PACKAGE;

    public static boolean isDragonReadFamily(String packageName) {
        return ZoeIds.isDragonReadFamily(packageName);
    }

    public static boolean isLvchaPackage(String packageName) {
        return ZoeIds.isLvchaPackage(packageName);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        AntiDetectHooks.install();
        Log.i(TAG, "Loading hooks for " + lpparam.packageName);

        try {
            if (AFUSEKT_PACKAGE.equals(lpparam.packageName)) {
                AfusektHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for Afusekt");
            } else if (CAPYPLAYER_PACKAGE.equals(lpparam.packageName)) {
                CapyPlayerHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for CapyPlayer");
            } else if (VTOOLS_PACKAGE.equals(lpparam.packageName)) {
                VToolsHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for VTools / Scene");
            } else if (XIMALAYA_PACKAGE.equals(lpparam.packageName)) {
                XimalayaHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for Ximalaya");
            } else if (MTXX_PACKAGE.equals(lpparam.packageName)) {
                MtxxHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for Meitu Xiuxiu");
            } else if (FANQIE_PACKAGE.equals(lpparam.packageName)) {
                FanqieHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for Fanqie Changting");
            } else if (isDragonReadFamily(lpparam.packageName)) {
                FanqieNovelHooks.apply(lpparam);
                FanqieNovelSafeHooks.apply(lpparam.classLoader, lpparam.appInfo);
                Log.i(TAG, "All hooks applied for DragonRead family: " + lpparam.packageName);
            } else if (isLvchaPackage(lpparam.packageName)) {
                LvchaHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for LVCHA / 绿茶VPN");
            } else if (VIDHUB_PACKAGE.equals(lpparam.packageName)) {
                if (VidHubScopeGuard.isZotScoped(lpparam)) {
                    VidHubScopeGuard.logZotConflict();
                    return;
                }
                VidHubNisGuard.install(lpparam.classLoader);
                VidHubAntiHookBypass.install(lpparam.classLoader);
                VidHubNativeGuard.install();
                VidHubHooks.apply(lpparam);
                Log.i(TAG, "All hooks applied for VidHub / Media Hub");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply hooks for " + lpparam.packageName, t);
        }
    }
}
