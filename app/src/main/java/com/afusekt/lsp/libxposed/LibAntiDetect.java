package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Global anti-detect for libxposed injection (ported from Zot {@code c1.l} / ZoeVIP {@code AntiDetectHooks}).
 */
public final class LibAntiDetect {

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

    private LibAntiDetect() {
    }

    public static void install(ZoeModule module) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            xposedClassLoader = Class.forName("de.robv.android.xposed.XposedBridge")
                    .getClassLoader();
        } catch (Throwable ignored) {
        }
        bootClassLoader = Object.class.getClassLoader();
        ClassLoader libLoader = XposedModule.class.getClassLoader();
        moduleClassLoader = libLoader != null ? libLoader : bootClassLoader;

        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Method getClassLoader = Class.class.getDeclaredMethod("getClassLoader");
            final ClassLoader spoofLoader = moduleClassLoader != null ? moduleClassLoader : bootClassLoader;
            module.hook(getClassLoader).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof ClassLoader) || xposedClassLoader == null) {
                    return result;
                }
                if (!result.equals(xposedClassLoader)) {
                    return result;
                }
                StackTraceElement[] probeStack = captureProbeStack();
                StackTraceElement[] trimmed = trimBeforeXposedBridgeLoad(probeStack);
                if (trimmed.length == 0) {
                    return result;
                }
                if (!"getClassLoader".equals(trimmed[0].getMethodName())) {
                    StringBuilder trace = new StringBuilder();
                    for (StackTraceElement element : trimmed) {
                        if (!LibAntiDetect.class.getName().equals(element.getClassName())) {
                            trace.append(element).append('\n');
                        }
                    }
                    String text = trace.toString();
                    if (!text.contains("de.robv.android.xposed")) {
                        return result;
                    }
                    if (PASS_HOOK_COUNTER.getAndIncrement() % 2 == 0) {
                        return spoofLoader;
                    }
                    return result;
                }
                if (PASS_HOOK_COUNTER.getAndIncrement() % 2 == 0) {
                    return xposedClassLoader;
                }
                return spoofLoader;
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "PassHook failed: " + t.getMessage());
        }

        try {
            Method threadTrace = Thread.class.getDeclaredMethod("getStackTrace");
            module.hook(threadTrace).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof StackTraceElement[])) {
                    return result;
                }
                return trimHookFrames((StackTraceElement[]) result);
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Thread.getStackTrace hook failed: " + t.getMessage());
        }

        try {
            Method throwableTrace = Throwable.class.getDeclaredMethod("getStackTrace");
            module.hook(throwableTrace).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof StackTraceElement[])) {
                    return result;
                }
                return trimHookFrames((StackTraceElement[]) result);
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Throwable.getStackTrace hook failed: " + t.getMessage());
        }

        try {
            Method allTraces = Thread.class.getDeclaredMethod("getAllStackTraces");
            module.hook(allTraces).setExceptionMode(mode).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof Map)) {
                    return result;
                }
                @SuppressWarnings("unchecked")
                Map<Thread, StackTraceElement[]> map = (Map<Thread, StackTraceElement[]>) result;
                Map<Thread, StackTraceElement[]> filtered = new HashMap<>(map.size());
                for (Map.Entry<Thread, StackTraceElement[]> entry : map.entrySet()) {
                    filtered.put(entry.getKey(), trimHookFrames(entry.getValue()));
                }
                return Collections.unmodifiableMap(filtered);
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "getAllStackTraces hook failed: " + t.getMessage());
        }

        module.log(4, ZoeIds.TAG, "LibAntiDetect installed");
    }

    private static StackTraceElement[] captureProbeStack() {
        try {
            IllegalThreadStateException probe = new IllegalThreadStateException("KillModuleCompat");
            probe.getStackTrace();
            java.lang.reflect.Field field = Throwable.class.getDeclaredField("stackTrace");
            field.setAccessible(true);
            Object value = field.get(probe);
            if (value instanceof StackTraceElement[]) {
                return (StackTraceElement[]) value;
            }
        } catch (Throwable ignored) {
        }
        StackTraceElement[] fallback = Thread.currentThread().getStackTrace();
        return fallback != null ? fallback : new StackTraceElement[0];
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
}
