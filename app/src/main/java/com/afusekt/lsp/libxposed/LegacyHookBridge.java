package com.afusekt.lsp.libxposed;

import android.content.pm.ApplicationInfo;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.hook.CapyPlayerHooks;

import java.lang.reflect.Constructor;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Bridges legacy XposedBridge hooks for apps that still use the old API internally.
 * Other apps are handled by dedicated {@code Lib*} hook classes in {@link com.afusekt.lsp.ZoeModule}.
 */
public final class LegacyHookBridge {

    private LegacyHookBridge() {
    }

    public static void route(XposedModuleInterface.PackageReadyParam param) {
        if (!ZoeIds.CAPYPLAYER_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        try {
            CapyPlayerHooks.apply(buildLoadPackageParam(param));
            XposedBridge.log(ZoeIds.TAG + ": CapyPlayer legacy hooks applied");
        } catch (Throwable t) {
            XposedBridge.log(ZoeIds.TAG + ": CapyPlayer legacy hooks failed: " + t.getMessage());
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
