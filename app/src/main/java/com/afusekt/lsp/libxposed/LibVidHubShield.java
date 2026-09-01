package com.afusekt.lsp.libxposed;

import android.annotation.SuppressLint;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * VidHub NIS shield. {@link #installEarly} runs in {@code onPackageLoaded} (before
 * {@code Application.attachBaseContext}) to block {@code MyJni.cp()}.
 */
public final class LibVidHubShield {

    private static final String MY_JNI = "com.netease.nis.wrapper.MyJni";
    private static final AtomicBoolean CP_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean LATE_INSTALLED = new AtomicBoolean(false);

    private LibVidHubShield() {
    }

    /** Must run in {@code onPackageLoaded} — before NIS {@code attachBaseContext}. */
    public static void installEarly(
            ZoeModule module,
            XposedModuleInterface.PackageLoadedParam param
    ) {
        if (!CP_HOOKED.compareAndSet(false, true)) {
            return;
        }
        ClassLoader cl = param.getDefaultClassLoader();
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Class<?> jni = Class.forName(MY_JNI, false, cl);
            Method cp = jni.getDeclaredMethod("cp");
            module.hook(cp).setExceptionMode(mode).intercept(chain -> {
                module.log(4, ZoeIds.TAG, "NIS MyJni.cp blocked (early)");
                return null;
            });
            module.log(4, ZoeIds.TAG, "NIS MyJni.cp hooked (onPackageLoaded)");
        } catch (Throwable t) {
            CP_HOOKED.set(false);
            module.log(5, ZoeIds.TAG, "NIS MyJni.cp early hook failed: " + t.getMessage());
        }
    }

    public static void install(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        if (!LATE_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        LibNisBypass.installEarly(module);
        installToastGuard(module);
        installRiskMessageBlock(module, param.getClassLoader());
        module.log(4, ZoeIds.TAG, "VidHub NIS shield installed (late)");
    }

    private static void installRiskMessageBlock(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Class<?> dialog = Class.forName("com.netease.nis.wrapper.NEDialog", false, cl);
            for (Method method : dialog.getDeclaredMethods()) {
                if (!"showRiskMessage".equals(method.getName())) {
                    continue;
                }
                module.hook(method).setExceptionMode(mode).intercept(chain -> {
                    Object code = chain.getArg(0);
                    if (code instanceof Integer i && i == 9) {
                        module.log(4, ZoeIds.TAG, "blocked NEDialog.showRiskMessage(9)");
                        return null;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "NEDialog hook failed: " + t.getMessage());
        }
    }

    private static void installToastGuard(ZoeModule module) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Method show = Toast.class.getDeclaredMethod("show");
            module.hook(show).setExceptionMode(mode).intercept(chain -> {
                Object toast = chain.getThisObject();
                if (toast instanceof Toast t && isHookDetectionMessage(readToastText(t))) {
                    module.log(4, ZoeIds.TAG, "suppressed hook-detection toast");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Toast.show hook failed: " + t.getMessage());
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private static String readToastText(Toast toast) {
        try {
            Object text = Toast.class.getDeclaredField("mText").get(toast);
            if (text != null) {
                return String.valueOf(text);
            }
        } catch (Throwable ignored) {
        }
        try {
            Object view = null;
            try {
                view = Toast.class.getDeclaredField("mNextView").get(toast);
            } catch (Throwable ignored) {
                view = Toast.class.getDeclaredField("mView").get(toast);
            }
            if (view instanceof TextView textView) {
                CharSequence label = textView.getText();
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
                || lower.contains("xposed")
                || lower.contains("lsposed")
                || message.contains("环境");
    }
}
