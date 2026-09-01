package com.afusekt.lsp.hook;

import android.widget.Toast;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class ToastSuppressHook {

    private ToastSuppressHook() {
    }

    static void apply(ClassLoader classLoader) {
        hookMakeText(classLoader, CharSequence.class);
        hookMakeText(classLoader, String.class);
        hookSystemTool(classLoader);
    }

    private static void hookMakeText(ClassLoader classLoader, Class<?> textType) {
        try {
            XposedHelpers.findAndHookMethod(
                    Toast.class,
                    "makeText",
                    android.content.Context.class,
                    textType,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String message = param.args[1] == null ? null : String.valueOf(param.args[1]);
                            if (WebDavSyncHooks.isBlockedToast(message)) {
                                XposedBridge.log(MainHook.TAG + ": suppress Toast: " + message);
                                param.setResult(null);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Toast.makeText hook failed: " + t.getMessage());
        }
    }

    private static void hookSystemTool(ClassLoader classLoader) {
        try {
            Class<?> companion = XposedHelpers.findClass(
                    "com.attempt.afusekt.tools.SystemTool$Companion",
                    classLoader
            );
            XposedBridge.hookAllMethods(companion, "K", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String message)) {
                        return;
                    }
                    if (WebDavSyncHooks.isBlockedToast(message)) {
                        XposedBridge.log(MainHook.TAG + ": suppress SystemTool.K: " + message);
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(MainHook.TAG + ": SystemTool.K hooked via hookAllMethods");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": SystemTool hookAllMethods failed: " + t.getMessage());
        }
    }
}
