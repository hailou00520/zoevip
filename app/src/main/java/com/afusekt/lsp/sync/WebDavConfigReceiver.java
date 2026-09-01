package com.afusekt.lsp.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Receives WebDAV config pushed from the ZoeVIP module app. */
public final class WebDavConfigReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.zoevip.lsp.WEBDAV_CONFIG";

    private static volatile WebDavConfigStore lastConfig;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        try {
            WebDavConfigStore.applyBundle(
                    context.getApplicationContext(),
                    intentToBundle(intent),
                    true
            );
            WebDavConfigStore store = getLastConfig();
            if (store != null) {
                WebDavSyncConfig.log("config broadcast received: " + store.describeForLog());
            }
        } catch (Throwable t) {
            WebDavSyncConfig.log("config broadcast failed: " + t.getMessage());
        }
    }

    static void remember(WebDavConfigStore store) {
        lastConfig = store;
    }

    static void clearLastConfig() {
        lastConfig = null;
    }

    static WebDavConfigStore getLastConfig() {
        return lastConfig;
    }

    private static android.os.Bundle intentToBundle(Intent intent) {
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putBoolean(WebDavPrefs.KEY_ENABLED, intent.getBooleanExtra(WebDavPrefs.KEY_ENABLED, false));
        bundle.putString(WebDavPrefs.KEY_BASE_URL, extra(intent, WebDavPrefs.KEY_BASE_URL));
        bundle.putString(WebDavPrefs.KEY_USERNAME, extra(intent, WebDavPrefs.KEY_USERNAME));
        bundle.putString(WebDavPrefs.KEY_PASSWORD, extra(intent, WebDavPrefs.KEY_PASSWORD));
        bundle.putString(WebDavPrefs.KEY_REMOTE_PATH, extra(intent, WebDavPrefs.KEY_REMOTE_PATH));
        return bundle;
    }

    private static String extra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    public static void register(Context context) {
        IntentFilter filter = new IntentFilter(ACTION);
        WebDavConfigReceiver receiver = new WebDavConfigReceiver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        WebDavSyncConfig.log("config receiver registered");
    }
}
