package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
/** Google Play purchase probe so bind-subscription dialog can appear. */
public final class VidHubBillingHooks {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private VidHubBillingHooks() {
    }

    public static void install(ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> purchase = XposedHelpers.findClass("com.android.billingclient.api.Purchase", cl);
            XposedBridge.hookAllMethods(purchase, "getPurchaseState", XC_MethodReplacement.returnConstant(1));
            XposedBridge.hookAllMethods(purchase, "isAcknowledged", XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(purchase, "getProducts", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof List && !((List<?>) param.getResult()).isEmpty()) {
                        return;
                    }
                    param.setResult(Collections.singletonList("vidhub.pro.lifetime"));
                }
            });
            XposedBridge.log(MainHook.TAG + ": VidHub billing hooks installed");
        } catch (Throwable t) {
            INSTALLED.set(false);
            XposedBridge.log(MainHook.TAG + ": VidHub billing hooks skipped: " + t.getMessage());
        }
    }
}
