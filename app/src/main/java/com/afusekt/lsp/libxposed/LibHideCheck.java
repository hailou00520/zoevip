package com.afusekt.lsp.libxposed;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;

import java.io.BufferedReader;
import java.io.FileReader;

/** Warn when ZoeVIP is visible in /proc/self/maps (LSPosed hide not enabled). */
public final class LibHideCheck {

    private LibHideCheck() {
    }

    public static void warnIfExposed(ZoeModule module) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("zoevip")
                        || lower.contains("com.zoevip.lsp")
                        || lower.contains("de.robv.android.xposed")
                        || lower.contains("lsposed")) {
                    module.log(5, ZoeIds.TAG,
                            "maps EXPOSED: " + line.trim()
                                    + " — enable LSPosed Hide module for ZoeVIP");
                    return;
                }
            }
            module.log(4, ZoeIds.TAG, "maps check OK (module hidden)");
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "maps check failed: " + t.getMessage());
        }
    }
}
