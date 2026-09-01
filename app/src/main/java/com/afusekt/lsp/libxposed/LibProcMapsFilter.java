package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/** Hides module paths from {@code /proc/self/maps} readers (NIS Java scans). */
public final class LibProcMapsFilter {

    private static final String[] HIDE_KEYWORDS = {
            "xposed",
            "lsposed",
            "edxposed",
            "libxposed",
            "zoevip",
            "obsidian.zot",
            "/zot/",
            "lspd",
            "riru",
    };

    private static final ThreadLocal<Boolean> IN_MAPS_READ = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private LibProcMapsFilter() {
    }

    public static void install(ZoeModule module) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedInterface.ExceptionMode mode = XposedInterface.ExceptionMode.PROTECTIVE;
        try {
            Constructor<FileInputStream> ctor = FileInputStream.class.getDeclaredConstructor(String.class);
            module.hook(ctor).setExceptionMode(mode).intercept(chain -> {
                Object path = chain.getArg(0);
                IN_MAPS_READ.set(path instanceof String s && s.contains("/maps"));
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "maps FileInputStream hook failed: " + t.getMessage());
        }
        try {
            Method readLine = BufferedReader.class.getDeclaredMethod("readLine");
            module.hook(readLine).setExceptionMode(mode).intercept(chain -> {
                if (!Boolean.TRUE.equals(IN_MAPS_READ.get())) {
                    return chain.proceed();
                }
                String line = (String) chain.proceed();
                int guard = 0;
                while (line != null && shouldHide(line) && guard++ < 512) {
                    line = (String) chain.proceed();
                }
                return line;
            });
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "maps readLine hook failed: " + t.getMessage());
        }
        try {
            Method close = FileInputStream.class.getDeclaredMethod("close");
            module.hook(close).setExceptionMode(mode).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    IN_MAPS_READ.set(false);
                }
            });
        } catch (Throwable ignored) {
        }
        module.log(4, ZoeIds.TAG, "Java /proc/maps filter installed");
    }

    private static boolean shouldHide(String line) {
        String lower = line.toLowerCase();
        for (String keyword : HIDE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
