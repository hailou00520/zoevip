package com.afusekt.lsp.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.afusekt.lsp.sync.WebDavSyncConfig;

import de.robv.android.xposed.XSharedPreferences;

public final class XimalayaPrefs {

    public static final String PREFS = "ximalaya_hooks";
    public static final String KEY_BURST_COUNT = "burst_claim_count";
    public static final String KEY_BYPASS_DAILY_LIMIT = "bypass_daily_limit";
    public static final int DEFAULT_BURST_COUNT = 10;
    public static final int MAX_BURST_COUNT = 50;
    public static final boolean DEFAULT_BYPASS_DAILY_LIMIT = true;

    private XimalayaPrefs() {
    }

    public static int clampBurstCount(int count) {
        return Math.max(1, Math.min(count, MAX_BURST_COUNT));
    }

    public static SharedPreferences openModule(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getBurstCount(Context context) {
        return clampBurstCount(openModule(context).getInt(KEY_BURST_COUNT, DEFAULT_BURST_COUNT));
    }

    public static int getBurstCount(XSharedPreferences prefs) {
        return clampBurstCount(prefs.getInt(KEY_BURST_COUNT, DEFAULT_BURST_COUNT));
    }

    public static void setBurstCount(Context context, int count) {
        openModule(context)
                .edit()
                .putInt(KEY_BURST_COUNT, clampBurstCount(count))
                .apply();
        notifyHookProcess();
    }

    public static boolean isBypassDailyLimit(Context context) {
        return openModule(context).getBoolean(KEY_BYPASS_DAILY_LIMIT, DEFAULT_BYPASS_DAILY_LIMIT);
    }

    public static boolean isBypassDailyLimit(XSharedPreferences prefs) {
        return prefs.getBoolean(KEY_BYPASS_DAILY_LIMIT, DEFAULT_BYPASS_DAILY_LIMIT);
    }

    public static void setBypassDailyLimit(Context context, boolean enabled) {
        openModule(context)
                .edit()
                .putBoolean(KEY_BYPASS_DAILY_LIMIT, enabled)
                .apply();
        notifyHookProcess();
    }

    public static void notifyHookProcess() {
        try {
            XSharedPreferences hookPrefs = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, PREFS);
            hookPrefs.makeWorldReadable();
            hookPrefs.reload();
        } catch (Throwable ignored) {
        }
    }
}
