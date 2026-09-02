package com.afusekt.lsp.hook;

import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.afusekt.lsp.LvchaUiStrip;
import com.afusekt.lsp.LvchaUnlockConfig;
import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.ZoeIds;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 绿茶 VPN / LVCHA classic Xposed path (NPatch / LSPatch).
 */
public final class LvchaHooks {

    private static final String TAG = MainHook.TAG + ":Lvcha";
    /** Runtime dex type is {@code Lvs1;} (default package). Jadx shows {@code defpackage.vs1}. */
    private static final String USER_MANAGER = "vs1";
    private static final String CIRCLE_FRAGMENT = "com.lvcha.main.fragment.CircleFragment";
    private static final String MAIN_FRAGMENT = "com.lvcha.main.fragment.MainFragment";
    private static final String MY_FRAGMENT = "com.lvcha.main.fragment.MyFragment";
    private static final String MAIN_ACTIVITY = "com.lvcha.main.activity.MainActivity";
    private static final String TOP_VIEW = "com.lvcha.main.View.TopView";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CLASS_LOADER_MONITOR = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LvchaHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ZoeIds.isLvchaPackage(lpparam.packageName)) {
            return;
        }
        installClassLoaderMonitor(lpparam.classLoader);
        tryInstall(lpparam.classLoader, "load-package");
        hookApplicationOnCreate(lpparam.classLoader);
        scheduleRetry(lpparam.classLoader);
    }

    private static void scheduleRetry(ClassLoader cl) {
        for (long delay : new long[]{300L, 800L, 1500L, 3000L, 6000L, 12000L}) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                tryInstall(cl, "retry@" + delay);
            }, "ZoeVIP-lvcha-" + delay);
            t.setDaemon(true);
            t.start();
        }
    }

    private static void hookApplicationOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (ZoeIds.isLvchaPackage(app.getPackageName())) {
                        tryInstall(app.getClassLoader(), "application");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void installClassLoaderMonitor(ClassLoader seed) {
        if (!CLASS_LOADER_MONITOR.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    ClassLoader.class,
                    "loadClass",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (isUserManagerClass(name)) {
                                tryInstall((ClassLoader) param.thisObject, "loadClass:" + name);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": ClassLoader.loadClass monitor installed");
        } catch (Throwable t) {
            CLASS_LOADER_MONITOR.set(false);
            XposedBridge.log(TAG + ": ClassLoader monitor failed: " + t.getMessage());
        }
    }

    private static boolean isUserManagerClass(String name) {
        return "vs1".equals(name) || USER_MANAGER.equals(name) || name != null && name.endsWith(".vs1");
    }

    private static void tryInstall(ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(USER_MANAGER, false, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedBridge.log(TAG + ": hooks installing (" + source + ")");
        try {
            int count = installHooks(cl);
            count += installUiStripHooks(cl);
            XposedBridge.log(TAG + ": hooks installed (" + count + ")");
        } catch (Throwable t) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": hooks failed: " + t.getMessage());
        }
    }

    private static int installHooks(ClassLoader cl) {
        int count = 0;
        count += hookReturn(cl, "h0", true);
        count += hookReturn(cl, "g0", true);
        count += hookDynamicLong(cl, "H");
        count += hookReturn(cl, "X", LvchaUnlockConfig.DIAMOND_VIP_TYPE);
        count += hookReturn(cl, "S", 99);
        count += hookReturn(cl, "f0", true);
        return count;
    }

    /** UI 精简：去导航 Tab、主页收藏夹/广告/抽奖/推广等。 */
    private static int installUiStripHooks(ClassLoader cl) {
        int count = 0;
        count += hookCircleFragmentStrip(cl);
        count += hookTopViewStrip(cl);
        count += hookMainFragmentStrip(cl);
        count += hookMyFragmentStrip(cl);
        count += hookMainActivityStrip(cl);
        count += hookReturn(cl, "z", null);
        return count;
    }

    private static int hookCircleFragmentStrip(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> circleFragment = Class.forName(CIRCLE_FRAGMENT, false, cl);
            Method render = circleFragment.getDeclaredMethod("x", ViewGroup.class);
            render.setAccessible(true);
            if (HOOKED.add(hookId(circleFragment, render))) {
                XposedBridge.hookMethod(render, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[0] instanceof ViewGroup viewGroup) {
                            LvchaUiStrip.hideNavigationPromo(viewGroup, cl);
                        }
                    }
                });
                count++;
            }
            Method fetchAds = circleFragment.getDeclaredMethod("z");
            fetchAds.setAccessible(true);
            if (HOOKED.add(hookId(circleFragment, fetchAds))) {
                XposedBridge.hookMethod(fetchAds, XC_MethodReplacement.returnConstant(null));
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CircleFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookTopViewStrip(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> topView = Class.forName(TOP_VIEW, false, cl);
            Method setData = topView.getDeclaredMethod("setData", List.class);
            setData.setAccessible(true);
            if (HOOKED.add(hookId(topView, setData))) {
                XposedBridge.hookMethod(setData, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof View view) {
                            LvchaUiStrip.hideTopBanner(view);
                        }
                        return null;
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TopView strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMainFragmentStrip(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> mainFragment = Class.forName(MAIN_FRAGMENT, false, cl);
            Method onCreateView = mainFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (HOOKED.add(hookId(mainFragment, onCreateView))) {
                XposedBridge.hookMethod(onCreateView, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() instanceof View view) {
                            LvchaUiStrip.hideMainPagePromo(view, cl);
                        }
                    }
                });
                count++;
            }
            Method refreshBanner = mainFragment.getDeclaredMethod("v");
            refreshBanner.setAccessible(true);
            if (HOOKED.add(hookId(mainFragment, refreshBanner))) {
                XposedBridge.hookMethod(refreshBanner, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LvchaUiStrip.forceHideMainActiveBanner(param.thisObject);
                    }
                });
                count++;
            }

            Method onResume = mainFragment.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(mainFragment, onResume))) {
                XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Method getView = param.thisObject.getClass().getMethod("getView");
                            Object view = getView.invoke(param.thisObject);
                            if (view instanceof View root) {
                                LvchaUiStrip.hideMainPagePromo(root, cl);
                            }
                            LvchaUiStrip.forceHideMainActiveBanner(param.thisObject);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MainFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMyFragmentStrip(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> myFragment = Class.forName(MY_FRAGMENT, false, cl);
            Method onCreateView = myFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (HOOKED.add(hookId(myFragment, onCreateView))) {
                XposedBridge.hookMethod(onCreateView, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() instanceof View view) {
                            LvchaUiStrip.hideMyPagePromo(view, cl);
                        }
                    }
                });
                count++;
            }
            Method onResume = myFragment.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(myFragment, onResume))) {
                XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Method getView = param.thisObject.getClass().getMethod("getView");
                            Object view = getView.invoke(param.thisObject);
                            if (view instanceof View root) {
                                LvchaUiStrip.hideMyPagePromo(root, cl);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MyFragment strip failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookMainActivityStrip(ClassLoader cl) {
        int count = 0;
        try {
            Class<?> mainActivity = Class.forName(MAIN_ACTIVITY, false, cl);
            Method onCreate = mainActivity.getDeclaredMethod("onCreate", Bundle.class);
            if (HOOKED.add(hookId(mainActivity, onCreate))) {
                XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LvchaUiStrip.stripNavigationTab(param.thisObject, cl);
                    }
                });
                count++;
            }

            Method onResume = mainActivity.getDeclaredMethod("onResume");
            if (HOOKED.add(hookId(mainActivity, onResume))) {
                XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LvchaUiStrip.stripNavigationTab(param.thisObject, cl);
                    }
                });
                count++;
            }
            Method highlightTab = mainActivity.getDeclaredMethod("w", int.class);
            highlightTab.setAccessible(true);
            if (HOOKED.add(hookId(mainActivity, highlightTab))) {
                XposedBridge.hookMethod(highlightTab, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] instanceof Integer index && index <= 1) {
                            LvchaUiStrip.applyTabHighlight(param.thisObject, index);
                            param.setResult(null);
                        }
                    }
                });
                count++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MainActivity strip failed: " + t.getMessage());
        }

        try {
            Class<?> viewPagerCls = Class.forName("androidx.viewpager.widget.ViewPager", false, cl);
            XC_MethodHook remapPager = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View view
                            && LvchaUiStrip.isMainActivityPager(view, cl)
                            && param.args[0] instanceof Integer index) {
                        param.args[0] = LvchaUiStrip.remapMainPagerIndex(index);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(
                    viewPagerCls, "setCurrentItem", int.class, boolean.class, remapPager);
            XposedHelpers.findAndHookMethod(
                    viewPagerCls, "setCurrentItem", int.class, remapPager);
            count += 2;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ViewPager remap failed: " + t.getMessage());
        }
        return count;
    }

    private static int hookDynamicLong(ClassLoader cl, String methodName) {
        try {
            Class<?> cls = Class.forName(USER_MANAGER, false, cl);
            Method method = findNoArgMethod(cls, methodName);
            if (method == null) {
                XposedBridge.log(TAG + ": method not found: " + methodName);
                return 0;
            }
            String id = cls.getName() + "#" + methodName + "#dynamic";
            if (!HOOKED.add(id)) {
                return 0;
            }
            XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return LvchaUnlockConfig.diamondRemainMs();
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(ClassLoader cl, String methodName, Object value) {
        try {
            Class<?> cls = Class.forName(USER_MANAGER, false, cl);
            Method method = findNoArgMethod(cls, methodName);
            if (method == null) {
                XposedBridge.log(TAG + ": method not found: " + methodName);
                return 0;
            }
            String id = cls.getName() + "#" + methodName;
            if (!HOOKED.add(id)) {
                return 0;
            }
            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + methodName + " failed: " + t.getMessage());
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

    private static String hookId(Class<?> cls, Method method) {
        return cls.getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }
}
