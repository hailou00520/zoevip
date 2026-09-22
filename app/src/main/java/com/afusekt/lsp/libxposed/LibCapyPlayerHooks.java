package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.widget.Toast;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * CapyPlayer entry for libxposed (LSPosed API 102+).
 */
public final class LibCapyPlayerHooks {

    private static final AtomicBoolean JAVA_HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);

    private LibCapyPlayerHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        module.log(4, ZoeIds.TAG, "CapyPlayer onPackageLoaded");
        LibCapyPlayerNative.installLoadMonitor(module);
        installJavaHooks(module, cl);
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        module.log(4, ZoeIds.TAG, "CapyPlayer onPackageReady");
        ClassLoader cl = param.getClassLoader();
        LibCapyPlayerNative.installLoadMonitor(module);
        hookApplicationToast(module);
        installJavaHooks(module, cl);
    }

    private static void installJavaHooks(ZoeModule module, ClassLoader cl) {
        if (!JAVA_HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            LibCapyPlayerJavaHooks.install(module, cl);
            module.log(4, ZoeIds.TAG, "CapyPlayer Java hooks applied");
        } catch (Throwable t) {
            JAVA_HOOKS_INSTALLED.set(false);
            module.log(5, ZoeIds.TAG, "CapyPlayer Java hooks failed: " + t.getMessage());
        }
    }

    private static void hookApplicationToast(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getThisObject();
                        if (app instanceof Application application
                                && ZoeIds.CAPYPLAYER_PACKAGE.equals(application.getPackageName())
                                && TOAST_SHOWN.compareAndSet(false, true)) {
                            Toast.makeText(
                                    application,
                                    "ZoeVIP · CapyPlayer 已加载",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "CapyPlayer toast hook failed: " + t.getMessage());
        }
    }
}
