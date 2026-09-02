package com.afusekt.lsp;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Shared LVCHA UI-strip hook targets for classic Xposed and libxposed backends.
 */
public final class LvchaUiStripHooks {

    public interface Backend {
        boolean register(Method method);

        void hookAfter(Method method, AfterListener listener);

        void hookReplaceNull(Method method);

        void hookReplace(Method method, ReplaceListener listener);

        /** @return true to skip the original method body */
        void hookShortCircuit(Method method, ShortCircuitListener listener);

        int installViewPagerRemap(ClassLoader cl);
    }

    @FunctionalInterface
    public interface AfterListener {
        void onAfter(Object thiz, Object[] args, Object result);
    }

    @FunctionalInterface
    public interface ReplaceListener {
        Object replace(Object thiz, Object[] args);
    }

    @FunctionalInterface
    public interface ShortCircuitListener {
        boolean skipOriginal(Object thiz, Object[] args);
    }

    private LvchaUiStripHooks() {
    }

    public static int install(Backend backend, ClassLoader cl) {
        int count = 0;
        count += hookCircleFragment(backend, cl);
        count += hookTopView(backend, cl);
        count += hookMainFragment(backend, cl);
        count += hookMyFragment(backend, cl);
        count += hookMainActivity(backend, cl);
        count += backend.installViewPagerRemap(cl);
        return count;
    }

    private static int hookCircleFragment(Backend backend, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> circleFragment = Class.forName(LvchaHookSupport.CIRCLE_FRAGMENT, false, cl);
            Method render = circleFragment.getDeclaredMethod("x", ViewGroup.class);
            render.setAccessible(true);
            if (backend.register(render)) {
                backend.hookAfter(render, (thiz, args, result) -> {
                    if (args[0] instanceof ViewGroup viewGroup) {
                        LvchaUiStrip.hideNavigationPromo(viewGroup);
                    }
                });
                count++;
            }
            Method fetchAds = circleFragment.getDeclaredMethod("z");
            fetchAds.setAccessible(true);
            if (backend.register(fetchAds)) {
                backend.hookReplaceNull(fetchAds);
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookTopView(Backend backend, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> topView = Class.forName(LvchaHookSupport.TOP_VIEW, false, cl);
            Method setData = topView.getDeclaredMethod("setData", List.class);
            setData.setAccessible(true);
            if (backend.register(setData)) {
                backend.hookReplace(setData, (thiz, args) -> {
                    if (thiz instanceof View view) {
                        LvchaUiStrip.hideTopBanner(view);
                    }
                    return null;
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookMainFragment(Backend backend, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> mainFragment = Class.forName(LvchaHookSupport.MAIN_FRAGMENT, false, cl);
            Method onCreateView = mainFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (backend.register(onCreateView)) {
                backend.hookAfter(onCreateView, (thiz, args, result) -> {
                    if (result instanceof View view) {
                        LvchaUiStrip.hideMainPagePromo(view);
                    }
                });
                count++;
            }
            Method refreshBanner = mainFragment.getDeclaredMethod("v");
            refreshBanner.setAccessible(true);
            if (backend.register(refreshBanner)) {
                backend.hookAfter(refreshBanner, (thiz, args, result) ->
                        LvchaUiStrip.forceHideMainActiveBanner(thiz));
                count++;
            }
            Method onResume = mainFragment.getDeclaredMethod("onResume");
            if (backend.register(onResume)) {
                backend.hookAfter(onResume, (thiz, args, result) -> {
                    try {
                        Method getView = thiz.getClass().getMethod("getView");
                        Object view = getView.invoke(thiz);
                        if (view instanceof View root) {
                            LvchaUiStrip.hideMainPagePromo(root);
                        }
                        LvchaUiStrip.forceHideMainActiveBanner(thiz);
                    } catch (Throwable ignored) {
                    }
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookMyFragment(Backend backend, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> myFragment = Class.forName(LvchaHookSupport.MY_FRAGMENT, false, cl);
            Method onCreateView = myFragment.getDeclaredMethod(
                    "onCreateView",
                    android.view.LayoutInflater.class,
                    ViewGroup.class,
                    Bundle.class
            );
            if (backend.register(onCreateView)) {
                backend.hookAfter(onCreateView, (thiz, args, result) -> {
                    if (result instanceof View view) {
                        LvchaUiStrip.hideMyPagePromo(view);
                    }
                });
                count++;
            }
            Method onResume = myFragment.getDeclaredMethod("onResume");
            if (backend.register(onResume)) {
                backend.hookAfter(onResume, (thiz, args, result) -> {
                    try {
                        Method getView = thiz.getClass().getMethod("getView");
                        Object view = getView.invoke(thiz);
                        if (view instanceof View root) {
                            LvchaUiStrip.hideMyPagePromo(root);
                        }
                    } catch (Throwable ignored) {
                    }
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private static int hookMainActivity(Backend backend, ClassLoader cl) {
        int count = 0;
        try {
            Class<?> mainActivity = Class.forName(LvchaHookSupport.MAIN_ACTIVITY, false, cl);
            Method onCreate = mainActivity.getDeclaredMethod("onCreate", Bundle.class);
            if (backend.register(onCreate)) {
                backend.hookAfter(onCreate, (thiz, args, result) ->
                        LvchaUiStrip.stripNavigationTab(thiz, cl));
                count++;
            }
            Method onResume = mainActivity.getDeclaredMethod("onResume");
            if (backend.register(onResume)) {
                backend.hookAfter(onResume, (thiz, args, result) ->
                        LvchaUiStrip.stripNavigationTab(thiz, cl));
                count++;
            }
            Method highlightTab = mainActivity.getDeclaredMethod("w", int.class);
            highlightTab.setAccessible(true);
            if (backend.register(highlightTab)) {
                backend.hookShortCircuit(highlightTab, (thiz, args) -> {
                    if (args[0] instanceof Integer index && index <= 1) {
                        LvchaUiStrip.applyTabHighlight(thiz, index);
                        return true;
                    }
                    return false;
                });
                count++;
            }
        } catch (Throwable ignored) {
        }
        return count;
    }
}
