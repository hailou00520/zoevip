package com.afusekt.lsp.hook;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.sync.WebDavConfigProvider;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Afusekt cannot see the LSPosed module package on Android 11+ without a {@code queries}
 * entry. These hooks expose just enough package/provider metadata for config IPC.
 */
public final class ModuleVisibilityHook {

    private static final String PM = "android.app.ApplicationPackageManager";
    private static final String PROVIDER_CLASS = WebDavConfigProvider.class.getName();

    private ModuleVisibilityHook() {
    }

    public static void apply(ClassLoader classLoader) {
        hookResolveContentProvider(classLoader);
        hookGetApplicationInfo(classLoader);
        hookGetPackageInfo(classLoader);
    }

    private static void hookResolveContentProvider(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(PM, classLoader),
                    "resolveContentProvider",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null) {
                                return;
                            }
                            if (!(param.args[0] instanceof String authority)) {
                                return;
                            }
                            if (!WebDavConfigProvider.AUTHORITY.equals(authority)) {
                                return;
                            }
                            param.setResult(buildProviderInfo());
                            XposedBridge.log(MainHook.TAG + ": resolveContentProvider patched for "
                                    + WebDavConfigProvider.AUTHORITY);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": resolveContentProvider hook failed: " + t.getMessage());
        }
    }

    private static void hookGetApplicationInfo(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(PM, classLoader, "getApplicationInfo",
                    String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null || param.hasThrowable()) {
                                return;
                            }
                            if (!WebDavSyncConfig.MODULE_PACKAGE.equals(param.args[0])) {
                                return;
                            }
                            param.setResult(buildApplicationInfo());
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": getApplicationInfo hook failed: " + t.getMessage());
        }
    }

    private static void hookGetPackageInfo(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(PM, classLoader, "getPackageInfo",
                    String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getResult() != null || param.hasThrowable()) {
                                return;
                            }
                            if (!WebDavSyncConfig.MODULE_PACKAGE.equals(param.args[0])) {
                                return;
                            }
                            PackageInfo info = new PackageInfo();
                            info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
                            info.applicationInfo = buildApplicationInfo();
                            param.setResult(info);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": getPackageInfo hook failed: " + t.getMessage());
        }
    }

    private static ProviderInfo buildProviderInfo() {
        ProviderInfo info = new ProviderInfo();
        info.authority = WebDavConfigProvider.AUTHORITY;
        info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
        info.name = PROVIDER_CLASS;
        info.exported = true;
        info.enabled = true;
        info.applicationInfo = buildApplicationInfo();
        return info;
    }

    private static ApplicationInfo buildApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
        info.enabled = true;
        return info;
    }
}
