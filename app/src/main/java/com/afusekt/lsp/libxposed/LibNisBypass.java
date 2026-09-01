package com.afusekt.lsp.libxposed;

import android.os.Process;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/** Blocks NetEase NIS scheduled native kill (MyJni.cp → exit 28). */
public final class LibNisBypass {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean JAVA_EXIT_HOOKED = new AtomicBoolean(false);

    private LibNisBypass() {
    }

    public static void installEarly(ZoeModule module) {
        installJavaExitHooks(module);
    }

    public static void install(ZoeModule module, ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            installJavaExitHooks(module);
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Class<?> myJni = Class.forName("com.netease.nis.wrapper.MyJni", false, cl);
            Method cp = myJni.getDeclaredMethod("cp");
            module.hook(cp)
                    .setExceptionMode(mode)
                    .intercept(chain -> {
                        module.log(4, ZoeIds.TAG, "NIS MyJni.cp blocked");
                        return null;
                    });
            module.log(4, ZoeIds.TAG, "NIS bypass: MyJni.cp hooked");
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(5, ZoeIds.TAG, "NIS bypass failed: " + t.getMessage());
        }
        installJavaExitHooks(module);
    }

    private static void installJavaExitHooks(ZoeModule module) {
        if (!JAVA_EXIT_HOOKED.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Method systemExit = System.class.getDeclaredMethod("exit", int.class);
            module.hook(systemExit).setExceptionMode(mode).intercept(chain -> {
                int code = (Integer) chain.getArg(0);
                if (code == 28) {
                    module.log(4, ZoeIds.TAG, "blocked System.exit(28)");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "System.exit hook failed: " + t.getMessage());
        }
        try {
            Method runtimeExit = Runtime.class.getDeclaredMethod("exit", int.class);
            module.hook(runtimeExit).setExceptionMode(mode).intercept(chain -> {
                int code = (Integer) chain.getArg(0);
                if (code == 28) {
                    module.log(4, ZoeIds.TAG, "blocked Runtime.exit(28)");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Runtime.exit hook failed: " + t.getMessage());
        }
        try {
            Method kill = Process.class.getDeclaredMethod("killProcess", int.class);
            module.hook(kill).setExceptionMode(mode).intercept(chain -> {
                int pid = (Integer) chain.getArg(0);
                if (pid == Process.myPid()) {
                    module.log(4, ZoeIds.TAG, "blocked Process.killProcess(self)");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "killProcess hook failed: " + t.getMessage());
        }
    }

    public static void installToastBlock(ZoeModule module, ClassLoader cl) {
        try {
            Class<?> dialog = Class.forName("com.netease.nis.wrapper.NEDialog", false, cl);
            for (Method method : dialog.getDeclaredMethods()) {
                if (!"showRiskMessage".equals(method.getName())) {
                    continue;
                }
                module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> null);
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "NEDialog block failed: " + t.getMessage());
        }
    }
}
