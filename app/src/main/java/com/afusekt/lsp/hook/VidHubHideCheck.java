package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.io.BufferedReader;
import java.io.FileReader;

import de.robv.android.xposed.XposedBridge;

/** Warn when the module APK is still visible in {@code /proc/self/maps}. */
public final class VidHubHideCheck {

    private VidHubHideCheck() {
    }

    public static void warnIfExposed() {
        if (!isModuleVisibleInMaps()) {
            XposedBridge.log(MainHook.TAG + ": VidHub maps check OK (module hidden)");
            return;
        }
        XposedBridge.log(MainHook.TAG + ": VidHub maps EXPOSED — enable LSPosed "
                + "\"Hide module\" for ZoeVIP on VidHub, then reboot");
    }

    private static boolean isModuleVisibleInMaps() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("zoevip")
                        || lower.contains("com.zoevip.lsp")
                        || lower.contains("lsposed")
                        || lower.contains("libxposed")
                        || lower.contains("xposed")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
