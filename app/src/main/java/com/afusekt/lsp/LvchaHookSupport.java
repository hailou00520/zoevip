package com.afusekt.lsp;

import java.lang.reflect.Method;

/** Shared LVCHA hook constants and reflection helpers. */
public final class LvchaHookSupport {

    /** Runtime dex type is {@code Lvs1;} (default package). Jadx shows {@code defpackage.vs1}. */
    public static final String USER_MANAGER = "vs1";
    public static final String CIRCLE_FRAGMENT = "com.lvcha.main.fragment.CircleFragment";
    public static final String MAIN_FRAGMENT = "com.lvcha.main.fragment.MainFragment";
    public static final String MY_FRAGMENT = "com.lvcha.main.fragment.MyFragment";
    public static final String MAIN_ACTIVITY = "com.lvcha.main.activity.MainActivity";
    public static final String TOP_VIEW = "com.lvcha.main.View.TopView";
    public static final String LVCHA_APPLICATION = "com.lvcha.main.LvchaApplication";

    public static final long[] RETRY_DELAYS_MS = {300L, 800L, 1500L, 3000L, 6000L, 12000L};

    private LvchaHookSupport() {
    }

    public static boolean isUserManagerClass(String name) {
        return "vs1".equals(name) || name != null && name.endsWith(".vs1");
    }

    public static Method findNoArgMethod(Class<?> cls, String name) {
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
                return method;
            }
        }
        return null;
    }

    public static String hookId(Class<?> cls, Method method) {
        return cls.getName() + "#" + method.getName()
                + "(" + method.getParameterTypes().length + ")";
    }

    public static String hookId(Method method) {
        return hookId(method.getDeclaringClass(), method);
    }
}
