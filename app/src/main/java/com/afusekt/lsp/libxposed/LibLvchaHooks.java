package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.view.View;

import com.afusekt.lsp.LvchaHookSupport;
import com.afusekt.lsp.LvchaUiStrip;
import com.afusekt.lsp.LvchaUiStripHooks;
import com.afusekt.lsp.LvchaUnlockConfig;
import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 绿茶 VPN / LVCHA ({@code com.abjlvcha.main}) 钻石会员 unlock via libxposed.
 * NPatch 会把 origin.apk 延迟解压，{@code defpackage.vs1} 需 ClassLoader 监听 + 重试。
 */
public final class LibLvchaHooks {

    private static final String TAG = ZoeIds.TAG + ":Lvcha";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CLASS_LOADER_MONITOR = new AtomicBoolean(false);
    private static final AtomicBoolean TOAST_SHOWN = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LibLvchaHooks() {
    }

    public static void onPackageLoaded(ZoeModule module, ClassLoader cl) {
        installClassLoaderMonitor(module);
        tryInstall(module, cl, "package-loaded");
        scheduleRetry(module, cl);
    }

    public static void onPackageReady(ZoeModule module, XposedModuleInterface.PackageReadyParam param) {
        tryInstall(module, param.getClassLoader(), "package-ready");
        hookApplicationOnCreate(module);
        scheduleRetry(module, param.getClassLoader());
    }

    private static void scheduleRetry(ZoeModule module, ClassLoader cl) {
        for (long delay : LvchaHookSupport.RETRY_DELAYS_MS) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                tryInstall(module, cl, "retry@" + delay);
            }, "ZoeVIP-lvcha-" + delay);
            t.setDaemon(true);
            t.start();
        }
    }

    private static void hookApplicationOnCreate(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getThisObject();
                        if (app instanceof Application application
                                && ZoeIds.isLvchaPackage(application.getPackageName())) {
                            tryInstall(module, application.getClassLoader(), "application");
                        }
                        return result;
                    });
        } catch (Throwable t) {
            module.log(5, TAG, "Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void installClassLoaderMonitor(ZoeModule module) {
        if (!CLASS_LOADER_MONITOR.compareAndSet(false, true)) {
            return;
        }
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            module.hook(loadClass)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object nameObj = chain.getArg(0);
                        String name = nameObj instanceof String ? (String) nameObj : null;
                        if (LvchaHookSupport.isUserManagerClass(name)) {
                            tryInstall(module, (ClassLoader) chain.getThisObject(),
                                    "loadClass:" + name);
                        }
                        return result;
                    });
            module.log(4, TAG, "ClassLoader.loadClass monitor installed");
        } catch (Throwable t) {
            CLASS_LOADER_MONITOR.set(false);
            module.log(5, TAG, "ClassLoader monitor failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(LvchaHookSupport.USER_MANAGER, false, cl);
        } catch (Throwable t) {
            module.log(5, TAG, "hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        module.log(4, TAG, "hooks installing (" + source + ")");
        try {
            int count = installHooks(module, cl);
            count += installUiStripHooks(module, cl);
            module.log(4, TAG, "hooks installed (" + count + ") until 5555-05-20");
            showToastOnce(module, cl);
        } catch (Throwable t) {
            INSTALLED.set(false);
            module.log(6, TAG, "hooks failed: " + t.getMessage(), t);
        }
    }

    private static void showToastOnce(ZoeModule module, ClassLoader cl) {
        if (!TOAST_SHOWN.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> appCls = Class.forName(LvchaHookSupport.LVCHA_APPLICATION, false, cl);
            java.lang.reflect.Field field = appCls.getDeclaredField("l");
            field.setAccessible(true);
            Object app = field.get(null);
            if (app instanceof Application application) {
                android.widget.Toast.makeText(
                        application,
                        "ZoeVIP 已注入 · 钻石会员至 5555-05-20",
                        android.widget.Toast.LENGTH_SHORT
                ).show();
            }
        } catch (Throwable t) {
            module.log(5, TAG, "toast skipped: " + t.getMessage());
        }
    }

    private static int installHooks(ZoeModule module, ClassLoader cl) throws Throwable {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        Class<?> userManager = Class.forName(LvchaHookSupport.USER_MANAGER, false, cl);
        int count = 0;
        count += hookReturn(module, userManager, "h0", true, mode);
        count += hookReturn(module, userManager, "g0", true, mode);
        count += hookDynamicLong(module, userManager, "H", mode);
        count += hookReturn(module, userManager, "X", LvchaUnlockConfig.DIAMOND_VIP_TYPE, mode);
        count += hookReturn(module, userManager, "S", 99, mode);
        count += hookReturn(module, userManager, "f0", true, mode);
        if (count == 0) {
            throw new IllegalStateException("no vs1 getters hooked");
        }
        return count;
    }

    private static int installUiStripHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = LvchaUiStripHooks.install(new LibUiBackend(module, mode), cl);
        try {
            count += hookReturn(module, Class.forName(LvchaHookSupport.USER_MANAGER, false, cl),
                    "z", null, mode);
        } catch (Throwable t) {
            module.log(5, TAG, "vs1#z strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookDynamicLong(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = LvchaHookSupport.findNoArgMethod(cls, methodName);
        if (method == null) {
            module.log(5, TAG, "method not found: " + cls.getName() + "#" + methodName);
            return 0;
        }
        if (method.getReturnType() != long.class && method.getReturnType() != Long.class) {
            module.log(5, TAG, "not a long getter: " + cls.getName() + "#" + methodName);
            return 0;
        }
        String id = LvchaHookSupport.hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode)
                    .intercept(chain -> LvchaUnlockConfig.diamondRemainMs());
            module.log(4, TAG, "hooked " + id + " -> 5555-05-20");
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, TAG, "hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            Object value,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = LvchaHookSupport.findNoArgMethod(cls, methodName);
        if (method == null) {
            module.log(5, TAG, "method not found: " + cls.getName() + "#" + methodName);
            return 0;
        }
        String id = LvchaHookSupport.hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> value);
            module.log(4, TAG, "hooked " + id);
            return 1;
        } catch (Throwable t) {
            HOOKED.remove(id);
            module.log(5, TAG, "hook failed " + id + ": " + t.getMessage());
            return 0;
        }
    }

    private static final class LibUiBackend implements LvchaUiStripHooks.Backend {
        private final ZoeModule module;
        private final XposedInterface.ExceptionMode mode;

        private LibUiBackend(ZoeModule module, XposedInterface.ExceptionMode mode) {
            this.module = module;
            this.mode = mode;
        }

        @Override
        public boolean register(Method method) {
            return HOOKED.add(LvchaHookSupport.hookId(method));
        }

        @Override
        public void hookAfter(Method method, LvchaUiStripHooks.AfterListener listener) {
            int paramCount = method.getParameterTypes().length;
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                Object[] args = new Object[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    args[i] = chain.getArg(i);
                }
                listener.onAfter(chain.getThisObject(), args, result);
                return result;
            });
        }

        @Override
        public void hookReplaceNull(Method method) {
            module.hook(method).setExceptionMode(mode).intercept(chain -> null);
        }

        @Override
        public void hookReplace(Method method, LvchaUiStripHooks.ReplaceListener listener) {
            int paramCount = method.getParameterTypes().length;
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object[] args = new Object[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    args[i] = chain.getArg(i);
                }
                return listener.replace(chain.getThisObject(), args);
            });
        }

        @Override
        public void hookShortCircuit(Method method, LvchaUiStripHooks.ShortCircuitListener listener) {
            int paramCount = method.getParameterTypes().length;
            module.hook(method).setExceptionMode(mode).intercept(chain -> {
                Object[] args = new Object[paramCount];
                for (int i = 0; i < paramCount; i++) {
                    args[i] = chain.getArg(i);
                }
                if (listener.skipOriginal(chain.getThisObject(), args)) {
                    return null;
                }
                return chain.proceed();
            });
        }

        @Override
        public int installViewPagerRemap(ClassLoader cl) {
            int count = 0;
            try {
                Class<?> viewPagerCls = Class.forName(
                        "androidx.viewpager.widget.ViewPager", false, cl);
                Method setCurrentItem = viewPagerCls.getDeclaredMethod(
                        "setCurrentItem", int.class, boolean.class);
                if (register(setCurrentItem)) {
                    module.hook(setCurrentItem).setExceptionMode(mode).intercept(chain -> {
                        Object pager = chain.getThisObject();
                        if (!(pager instanceof View view) || !LvchaUiStrip.isMainActivityPager(view)) {
                            return chain.proceed();
                        }
                        Object indexObj = chain.getArg(0);
                        if (!(indexObj instanceof Integer index)) {
                            return chain.proceed();
                        }
                        int mapped = LvchaUiStrip.remapMainPagerIndex(index);
                        if (mapped == index) {
                            return chain.proceed();
                        }
                        return chain.proceed(new Object[]{mapped, chain.getArg(1)});
                    });
                    count++;
                }

                Method setCurrentItemSimple = viewPagerCls.getDeclaredMethod(
                        "setCurrentItem", int.class);
                if (register(setCurrentItemSimple)) {
                    module.hook(setCurrentItemSimple).setExceptionMode(mode).intercept(chain -> {
                        Object pager = chain.getThisObject();
                        if (!(pager instanceof View view) || !LvchaUiStrip.isMainActivityPager(view)) {
                            return chain.proceed();
                        }
                        Object indexObj = chain.getArg(0);
                        if (!(indexObj instanceof Integer index)) {
                            return chain.proceed();
                        }
                        int mapped = LvchaUiStrip.remapMainPagerIndex(index);
                        if (mapped == index) {
                            return chain.proceed();
                        }
                        return chain.proceed(new Object[]{mapped});
                    });
                    count++;
                }
            } catch (Throwable t) {
                module.log(5, TAG, "ViewPager remap failed: " + t.getMessage());
            }
            return count;
        }
    }
}
