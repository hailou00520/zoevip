package com.afusekt.lsp.libxposed;

import android.content.pm.ApplicationInfo;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.hook.AfusektHooks;
import com.afusekt.lsp.hook.CapyPlayerHooks;
import com.afusekt.lsp.hook.LvchaHooks;
import com.afusekt.lsp.hook.MtxxHooks;
import com.afusekt.lsp.hook.VToolsHooks;
import com.afusekt.lsp.hook.XimalayaHooks;
import com.afusekt.lsp.hook.VidHubHooks;

import java.lang.reflect.Constructor;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Bridges legacy XposedBridge hooks for apps that still use the old API internally.
 */
public final class LegacyHookBridge {

    private LegacyHookBridge() {
    }

    public static void route(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (ZoeIds.AFUSEKT_PACKAGE.equals(pkg)) {
            applyAfusekt(param);
        } else if (ZoeIds.CAPYPLAYER_PACKAGE.equals(pkg)) {
            applyCapyPlayer(param);
        } else if (ZoeIds.VIDHUB_PACKAGE.equals(pkg)) {
            applyVidHub(param);
        } else if (ZoeIds.VTOOLS_PACKAGE.equals(pkg)) {
            applyVTools(param);
        } else if (ZoeIds.XIMALAYA_PACKAGE.equals(pkg)) {
            applyXimalaya(param);
        } else if (ZoeIds.MTXX_PACKAGE.equals(pkg)) {
            applyMtxx(param);
        } else if (ZoeIds.isLvchaPackage(pkg)) {
            applyLvcha(param);
        }
    }

    private static void applyAfusekt(XposedModuleInterface.PackageReadyParam param) {
        try {
            AfusektHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": Afusekt legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": Afusekt legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyVidHub(XposedModuleInterface.PackageReadyParam param) {
        try {
            VidHubHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": VidHub legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": VidHub legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyCapyPlayer(XposedModuleInterface.PackageReadyParam param) {
        try {
            CapyPlayerHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": CapyPlayer legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": CapyPlayer legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyVTools(XposedModuleInterface.PackageReadyParam param) {
        try {
            VToolsHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": VTools legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": VTools legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyXimalaya(XposedModuleInterface.PackageReadyParam param) {
        try {
            XimalayaHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": Ximalaya legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": Ximalaya legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyMtxx(XposedModuleInterface.PackageReadyParam param) {
        try {
            MtxxHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": Meitu Xiuxiu legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": Meitu Xiuxiu legacy hooks failed: " + t.getMessage());
        }
    }

    private static void applyLvcha(XposedModuleInterface.PackageReadyParam param) {
        try {
            LvchaHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": LVCHA legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": LVCHA legacy hooks failed: " + t.getMessage());
        }
    }

    private static LoadPackageParam buildLoadPackageParam(
            XposedModuleInterface.PackageReadyParam param
    ) throws Exception {
        Constructor<LoadPackageParam> ctor =
                LoadPackageParam.class.getDeclaredConstructor(CopyOnWriteArrayList.class);
        ctor.setAccessible(true);
        @SuppressWarnings("unchecked")
        LoadPackageParam lpparam = ctor.newInstance(new CopyOnWriteArrayList<XC_LoadPackage>());
        lpparam.packageName = param.getPackageName();
        ApplicationInfo appInfo = param.getApplicationInfo();
        if (appInfo != null && appInfo.processName != null) {
            lpparam.processName = appInfo.processName;
        } else {
            lpparam.processName = param.getPackageName();
        }
        lpparam.classLoader = param.getClassLoader();
        lpparam.appInfo = appInfo;
        lpparam.isFirstApplication = param.isFirstPackage();
        return lpparam;
    }
}
