package com.afusekt.lsp.libxposed;

import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.afusekt.lsp.LvchaUiStrip;
import com.afusekt.lsp.LvchaUnlockConfig;
import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
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
    /** Runtime dex type is {@code Lvs1;} (default package). Jadx shows {@code defpackage.vs1}. */
    private static final String USER_MANAGER = "vs1";
    private static final String CIRCLE_FRAGMENT = "com.lvcha.main.fragment.CircleFragment";
    private static final String MAIN_FRAGMENT = "com.lvcha.main.fragment.MainFragment";
    private static final String MY_FRAGMENT = "com.lvcha.main.fragment.MyFragment";
    private static final String MAIN_ACTIVITY = "com.lvcha.main.activity.MainActivity";
    private static final String TOP_VIEW = "com.lvcha.main.View.TopView";

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
        for (long delay : new long[]{300L, 800L, 1500L, 3000L, 6000L, 12000L}) {
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
                        if (isUserManagerClass(name)) {
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

    private static boolean isUserManagerClass(String name) {
        return "vs1".equals(name) || USER_MANAGER.equals(name) || name != null && name.endsWith(".vs1");
    }

    private static void tryInstall(ZoeModule module, ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(USER_MANAGER, false, cl);
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
            Class<?> appCls = Class.forName("com.lvcha.main.LvchaApplication", false, cl);
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
        Class<?> userManager = Class.forName(USER_MANAGER, false, cl);
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

    /** UI 精简：去导航 Tab、主页收藏夹/广告/抽奖/推广等。 */
    private static int installUiStripHooks(ZoeModule module, ClassLoader cl) {
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        int count = 0;
        count += hookCircleFragmentStrip(module, cl, mode);
        count += hookTopViewStrip(module, cl, mode);
        count += hookMainFragmentStrip(module, cl, mode);
        count += hookMyFragmentStrip(module, cl, mode);
        count += hookMainActivityStrip(module, cl, mode);
        try {
            count += hookReturn(module, Class.forName(USER_MANAGER, false, cl), "z", null, mode);
        } catch (Throwable t) {
            module.log(5, TAG, "vs1#z strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookCircleFragmentStrip(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> circleFragment = Class.forName(CIRCLE_FRAGMENT, false, cl);
            Method render = circleFragment.getDeclaredMethod("x", ViewGroup.class);
            render.setAccessible(true);
            if (HOOKED.add(hookId(render))) {
                module.hook(render).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    Object arg = chain.getArg(0);
                    if (arg instanceof ViewGroup viewGroup) {
                        LvchaUiStrip.hideNavigationPromo(viewGroup, cl);
                    }
                    return result;
                });
                count++;
            }

            Method fetchAds = circleFragment.getDeclaredMethod("z");
            fetchAds.setAccessible(true);
            if (HOOKED.add(hookId(fetchAds))) {
                module.hook(fetchAds).setExceptionMode(mode).intercept(chain -> null);
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "CircleFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookTopViewStrip(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> topView = Class.forName(TOP_VIEW, false, cl);
            Method setData = topView.getDeclaredMethod("setData", List.class);
            setData.setAccessible(true);
            if (HOOKED.add(hookId(setData))) {
                module.hook(setData).setExceptionMode(mode).intercept(chain -> {
                    Object self = chain.getThisObject();
                    if (self instanceof View view) {
                        LvchaUiStrip.hideTopBanner(view);
                    }
                    return null;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "TopView strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMainFragmentStrip(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> mainFragment = Class.forName(MAIN_FRAGMENT, false, cl);
            Method onCreateView = mainFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (HOOKED.add(hookId(onCreateView))) {
                module.hook(onCreateView).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof View view) {
                        LvchaUiStrip.hideMainPagePromo(view, cl);
                    }
                    return result;
                });
                count++;
            }

            Method refreshBanner = mainFragment.getDeclaredMethod("v");
            refreshBanner.setAccessible(true);
            if (HOOKED.add(hookId(refreshBanner))) {
                module.hook(refreshBanner).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    LvchaUiStrip.forceHideMainActiveBanner(chain.getThisObject());
                    return result;
                });
                count++;
            }

            Method onResume = mainFragment.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(onResume))) {
                module.hook(onResume).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    try {
                        Method getView = self.getClass().getMethod("getView");
                        Object view = getView.invoke(self);
                        if (view instanceof View root) {
                            LvchaUiStrip.hideMainPagePromo(root, cl);
                        }
                        LvchaUiStrip.forceHideMainActiveBanner(self);
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "MainFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMyFragmentStrip(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> myFragment = Class.forName(MY_FRAGMENT, false, cl);
            Method onCreateView = myFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (HOOKED.add(hookId(onCreateView))) {
                module.hook(onCreateView).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof View view) {
                        LvchaUiStrip.hideMyPagePromo(view, cl);
                    }
                    return result;
                });
                count++;
            }

            Method onResume = myFragment.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(onResume))) {
                module.hook(onResume).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    try {
                        Method getView = self.getClass().getMethod("getView");
                        Object view = getView.invoke(self);
                        if (view instanceof View root) {
                            LvchaUiStrip.hideMyPagePromo(root, cl);
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "MyFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMainActivityStrip(
            ZoeModule module, ClassLoader cl, XposedInterface.ExceptionMode mode) {
        int count = 0;
        try {
            Class<?> mainActivity = Class.forName(MAIN_ACTIVITY, false, cl);
            Method onCreate = mainActivity.getDeclaredMethod("onCreate", Bundle.class);
            if (HOOKED.add(hookId(onCreate))) {
                module.hook(onCreate).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    LvchaUiStrip.stripNavigationTab(chain.getThisObject(), cl);
                    return result;
                });
                count++;
            }

            Method onResume = mainActivity.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(onResume))) {
                module.hook(onResume).setExceptionMode(mode).intercept(chain -> {
                    Object result = chain.proceed();
                    LvchaUiStrip.stripNavigationTab(chain.getThisObject(), cl);
                    return result;
                });
                count++;
            }

            Method highlightTab = mainActivity.getDeclaredMethod("w", int.class);
            highlightTab.setAccessible(true);
            if (HOOKED.add(hookId(highlightTab))) {
                module.hook(highlightTab).setExceptionMode(mode).intercept(chain -> {
                    Object indexObj = chain.getArg(0);
                    if (indexObj instanceof Integer index && index <= 1) {
                        LvchaUiStrip.applyTabHighlight(chain.getThisObject(), index);
                        return null;
                    }
                    return chain.proceed();
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(5, TAG, "MainActivity strip failed: " + t.getMessage());
        }

        try {
            Class<?> viewPagerCls = Class.forName("androidx.viewpager.widget.ViewPager", false, cl);
            Method setCurrentItem = viewPagerCls.getDeclaredMethod(
                    "setCurrentItem", int.class, boolean.class);
            if (HOOKED.add(hookId(setCurrentItem))) {
                module.hook(setCurrentItem).setExceptionMode(mode).intercept(chain -> {
                    Object pager = chain.getThisObject();
                    if (!(pager instanceof View view)
                            || !LvchaUiStrip.isMainActivityPager(view, cl)) {
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
            if (HOOKED.add(hookId(setCurrentItemSimple))) {
                module.hook(setCurrentItemSimple).setExceptionMode(mode).intercept(chain -> {
                    Object pager = chain.getThisObject();
                    if (!(pager instanceof View view)
                            || !LvchaUiStrip.isMainActivityPager(view, cl)) {
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

    private static int hookDynamicLong(
            ZoeModule module,
            Class<?> cls,
            String methodName,
            XposedInterface.ExceptionMode mode
    ) {
        Method method = findNoArgMethod(cls, methodName);
        if (method == null) {
            module.log(5, TAG, "method not found: " + cls.getName() + "#" + methodName);
            return 0;
        }
        if (method.getReturnType() != long.class && method.getReturnType() != Long.class) {
            module.log(5, TAG, "not a long getter: " + cls.getName() + "#" + methodName);
            return 0;
        }
        String id = hookId(method);
        if (!HOOKED.add(id)) {
            return 0;
        }
        try {
            method.setAccessible(true);
            module.hook(method).setExceptionMode(mode).intercept(chain -> LvchaUnlockConfig.diamondRemainMs());
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
        Method method = findNoArgMethod(cls, methodName);
        if (method == null) {
            module.log(5, TAG, "method not found: " + cls.getName() + "#" + methodName);
            return 0;
        }
        String id = hookId(method);
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

    private static Method findNoArgMethod(Class<?> cls, String name) {
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        return null;
    }

    private static String hookId(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }
}
