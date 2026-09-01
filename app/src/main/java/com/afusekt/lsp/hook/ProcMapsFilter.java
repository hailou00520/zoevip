package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hides module paths from {@code /proc/self/maps} readers (NIS scans this). */
public final class ProcMapsFilter {

    private static final String[] HIDE_KEYWORDS = {
            "xposed",
            "lsposed",
            "edxposed",
            "libxposed",
            "zoevip",
            "obsidian.zot",
            "zot",
            "lspd",
    };

    private static final ThreadLocal<Boolean> IN_MAPS_READ = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private ProcMapsFilter() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookMapsInput();
        hookMapsReadLine();
        XposedBridge.log(MainHook.TAG + ": /proc/maps filter installed");
    }

    private static void hookMapsInput() {
        try {
            XposedHelpers.findAndHookConstructor(
                    FileInputStream.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String path = (String) param.args[0];
                            IN_MAPS_READ.set(path != null && path.contains("/maps"));
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": maps FileInputStream hook failed: " + t.getMessage());
        }
    }

    private static void hookMapsReadLine() {
        try {
            XposedHelpers.findAndHookMethod(
                    BufferedReader.class,
                    "readLine",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(IN_MAPS_READ.get())) {
                                return;
                            }
                            String line = (String) param.getResult();
                            int guard = 0;
                            while (line != null && shouldHide(line) && guard++ < 512) {
                                try {
                                    line = (String) XposedBridge.invokeOriginalMethod(
                                            param.method,
                                            param.thisObject,
                                            param.args
                                    );
                                } catch (Throwable ignored) {
                                    break;
                                }
                            }
                            param.setResult(line);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    FileInputStream.class,
                    "close",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            IN_MAPS_READ.set(false);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": maps readLine hook failed: " + t.getMessage());
        }
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
