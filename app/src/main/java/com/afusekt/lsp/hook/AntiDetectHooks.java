package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides Xposed / LSPosed from stack-trace and class-loader probes.
 * Ported from Zot global anti-detect ({@code c1.l} / {@code w20}).
 */
public final class AntiDetectHooks {

    private static final String[] FILTER_PREFIXES = {
            "de.robv.android.xposed",
            "org.lsposed",
            "io.github.libxposed",
            "com.zoevip.lsp",
            "top.obsidian.zot",
    };

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger PASS_HOOK_COUNTER = new AtomicInteger(0);

    private static ClassLoader xposedClassLoader;
    private static ClassLoader bootClassLoader;
    private static ClassLoader moduleClassLoader;

    private AntiDetectHooks() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            xposedClassLoader = Class.forName("de.robv.android.xposed.XposedBridge")
                    .getClassLoader();
        } catch (Throwable ignored) {
        }
        bootClassLoader = Object.class.getClassLoader();
        try {
            moduleClassLoader = Class.forName("io.github.libxposed.api.XposedModule")
                    .getClassLoader();
        } catch (Throwable ignored) {
            moduleClassLoader = bootClassLoader;
        }

        hookClassGetClassLoader();
        hookThreadGetStackTrace();
        hookThrowableGetStackTrace();
        hookGetAllStackTraces();
        XposedBridge.log(MainHook.TAG + ": Anti-detect hooks installed");
    }

    private static void hookClassGetClassLoader() {
        if (xposedClassLoader == null) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    Class.class,
                    "getClassLoader",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof ClassLoader)) {
                                return;
                            }
                            if (!result.equals(xposedClassLoader)) {
                                return;
                            }
                            if (!stackContainsXposedCaller(Thread.currentThread().getStackTrace())) {
                                return;
                            }
                            if (PASS_HOOK_COUNTER.getAndIncrement() % 2 == 0) {
                                param.setResult(moduleClassLoader != null ? moduleClassLoader : bootClassLoader);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": PassHook failed: " + t.getMessage());
        }
    }

    private static void hookThreadGetStackTrace() {
        try {
            XposedHelpers.findAndHookMethod(
                    Thread.class,
                    "getStackTrace",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            StackTraceElement[] stack = (StackTraceElement[]) param.getResult();
                            StackTraceElement[] filtered = trimHookFrames(stack);
                            if (filtered != stack) {
                                param.setResult(filtered);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Thread.getStackTrace hook failed: " + t.getMessage());
        }
    }

    private static void hookThrowableGetStackTrace() {
        try {
            XposedHelpers.findAndHookMethod(
                    Throwable.class,
                    "getStackTrace",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            StackTraceElement[] stack = (StackTraceElement[]) param.getResult();
                            StackTraceElement[] filtered = trimHookFrames(stack);
                            if (filtered != stack) {
                                param.setResult(filtered);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": Throwable.getStackTrace hook failed: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void hookGetAllStackTraces() {
        try {
            XposedHelpers.findAndHookMethod(
                    Thread.class,
                    "getAllStackTraces",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Map<Thread, StackTraceElement[]> map =
                                    (Map<Thread, StackTraceElement[]>) param.getResult();
                            if (map == null) {
                                return;
                            }
                            Map<Thread, StackTraceElement[]> filtered = new HashMap<>(map.size());
                            for (Map.Entry<Thread, StackTraceElement[]> entry : map.entrySet()) {
                                filtered.put(entry.getKey(), trimHookFrames(entry.getValue()));
                            }
                            param.setResult(Collections.unmodifiableMap(filtered));
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": getAllStackTraces hook failed: " + t.getMessage());
        }
    }

    private static StackTraceElement[] trimHookFrames(StackTraceElement[] original) {
        if (original == null || original.length == 0) {
            return original;
        }
        StackTraceElement[] trimmed = trimBeforeXposedBridgeLoad(original);
        List<StackTraceElement> kept = new ArrayList<>(trimmed.length);
        for (StackTraceElement element : trimmed) {
            if (!shouldFilterFrame(element)) {
                kept.add(element);
            }
        }
        return kept.toArray(new StackTraceElement[0]);
    }

    private static StackTraceElement[] trimBeforeXposedBridgeLoad(StackTraceElement[] stack) {
        if (xposedClassLoader == null || stack == null) {
            return stack;
        }
        for (int i = 0; i < stack.length; i++) {
            StackTraceElement element = stack[i];
            if (!"java.lang.Thread".equals(element.getClassName())
                    || !"getStackTrace".equals(element.getMethodName())) {
                continue;
            }
            for (int j = i + 2; j < stack.length; j++) {
                try {
                    Class.forName(stack[j].getClassName(), false, xposedClassLoader);
                } catch (Throwable t) {
                    if (j == i + 2) {
                        return stack;
                    }
                    StackTraceElement[] copy = new StackTraceElement[stack.length - j];
                    System.arraycopy(stack, j, copy, 0, copy.length);
                    return copy;
                }
            }
            return new StackTraceElement[0];
        }
        return stack;
    }

    private static boolean shouldFilterFrame(StackTraceElement element) {
        String className = element.getClassName();
        if (className == null) {
            return false;
        }
        for (String prefix : FILTER_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        String lower = className.toLowerCase();
        if (lower.contains("xposed") || lower.contains("lsposed") || lower.contains("edxposed")) {
            return true;
        }
        return "java.lang.Thread".equals(className)
                && "getStackTrace".equals(element.getMethodName());
    }

    private static boolean stackContainsXposedCaller(StackTraceElement[] stack) {
        if (stack == null) {
            return false;
        }
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className == null) {
                continue;
            }
            for (String prefix : FILTER_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
            String lower = className.toLowerCase();
            if (lower.contains("xposed") || lower.contains("lsposed")) {
                return true;
            }
        }
        return false;
    }
}
