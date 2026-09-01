package com.afusekt.lsp.hook;

import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.MainHook;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * VidHub NIS bypass: suppress detection UI and spoof env probes.
 * Avoid hooking {@code MyJni.cp()} — triggers delayed exit 28.
 */
public final class VidHubAntiHookBypass {

    private static final String MY_JNI = "com.netease.nis.wrapper.MyJni";
    private static final String NE_DIALOG = "com.netease.nis.wrapper.NEDialog";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private VidHubAntiHookBypass() {
    }

    public static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookMyJniProbes(classLoader);
        hookNeDialog(classLoader);
        hookHookDetectionToastShow();
        XposedBridge.log(MainHook.TAG + ": VidHub NIS bypass installed");
    }

    private static void hookMyJniProbes(ClassLoader classLoader) {
        try {
            Class<?> jni = Class.forName(MY_JNI, false, classLoader);
            XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(jni, "getEnvInfo"),
                    XC_MethodReplacement.returnConstant("")
            );
            XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(jni, "id", long.class, boolean.class),
                    XC_MethodReplacement.returnConstant(false)
            );
            XposedBridge.log(MainHook.TAG + ": MyJni env probes spoofed");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": MyJni probe hooks failed: " + t.getMessage());
        }
    }

    private static void hookNeDialog(ClassLoader classLoader) {
        try {
            Class<?> dialog = Class.forName(NE_DIALOG, false, classLoader);
            XposedHelpers.findAndHookMethod(
                    dialog,
                    "showRiskMessage",
                    int.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ((int) param.args[0] == 9) {
                                param.setResult(null);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": NEDialog hooks failed: " + t.getMessage());
        }
    }

    private static void hookHookDetectionToastShow() {
        try {
            XposedHelpers.findAndHookMethod(
                    Toast.class,
                    "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isHookDetectionMessage(readToastText((Toast) param.thisObject))) {
                                XposedBridge.log(MainHook.TAG + ": suppressed hook-detection toast");
                                param.setResult(null);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Toast.show hook failed: " + t.getMessage());
        }
    }

    private static String readToastText(Toast toast) {
        if (toast == null) {
            return "";
        }
        try {
            Object text = XposedHelpers.getObjectField(toast, "mText");
            if (text != null) {
                return String.valueOf(text);
            }
        } catch (Throwable ignored) {
        }
        try {
            Object view = XposedHelpers.getObjectField(toast, "mNextView");
            if (view == null) {
                view = XposedHelpers.getObjectField(toast, "mView");
            }
            if (view instanceof TextView) {
                CharSequence label = ((TextView) view).getText();
                return label != null ? label.toString() : "";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean isHookDetectionMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        return message.contains("hook")
                || message.contains("Hook")
                || lower.contains("xposed")
                || lower.contains("lsposed")
                || message.contains("环境");
    }
}
