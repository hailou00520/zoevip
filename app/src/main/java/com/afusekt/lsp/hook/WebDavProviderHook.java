package com.afusekt.lsp.hook;

import android.net.Uri;
import android.os.Bundle;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.sync.WebDavConfigProvider;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Supplies module WebDAV prefs when Afusekt cannot resolve the exported provider. */
final class WebDavProviderHook {

    private WebDavProviderHook() {
    }

    static void apply(ClassLoader classLoader) {
        hookContentResolverCall(classLoader, Uri.class, String.class, String.class, android.os.Bundle.class);
        hookContentResolverCall(classLoader, String.class, String.class, String.class, android.os.Bundle.class);
    }

    private static void hookContentResolverCall(ClassLoader classLoader, Class<?>... parameterTypes) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.ContentResolver",
                    classLoader,
                    "call",
                    parameterTypes,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isGetConfigCall(param.args)) {
                                return;
                            }
                            Bundle bundle = readFreshBundle();
                            if (bundle != null) {
                                param.setResult(bundle);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": ContentResolver.call hook failed: " + t.getMessage());
        }
    }

    private static boolean isGetConfigCall(Object[] args) {
        if (args == null || args.length < 2 || !"getConfig".equals(args[1])) {
            return false;
        }
        Object target = args[0];
        if (target instanceof Uri uri) {
            return WebDavConfigProvider.AUTHORITY.equals(uri.getAuthority());
        }
        if (target instanceof String authority) {
            return WebDavConfigProvider.AUTHORITY.equals(authority);
        }
        return false;
    }

    private static Bundle readFreshBundle() {
        return WebDavSyncConfig.readFreshConfigBundle();
    }
}
