package com.afusekt.lsp.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

public final class WebDavConfigPush {

    private WebDavConfigPush() {
    }

    public static void broadcast(Context context) {
        if (context == null) {
            return;
        }
        var prefs = WebDavPrefs.openModule(context);
        Intent intent = new Intent(WebDavConfigReceiver.ACTION);
        intent.setPackage("com.attempt.afusekt");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        intent.putExtra(WebDavPrefs.KEY_ENABLED, prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false));
        intent.putExtra(WebDavPrefs.KEY_BASE_URL, prefs.getString(WebDavPrefs.KEY_BASE_URL, ""));
        intent.putExtra(WebDavPrefs.KEY_USERNAME, prefs.getString(WebDavPrefs.KEY_USERNAME, ""));
        intent.putExtra(WebDavPrefs.KEY_PASSWORD, prefs.getString(WebDavPrefs.KEY_PASSWORD, ""));
        intent.putExtra(
                WebDavPrefs.KEY_REMOTE_PATH,
                prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
        );
        context.sendBroadcast(intent);
        WebDavSyncConfig.log("config pushed to Afusekt enabled="
                + prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false));
    }

    public static void broadcast(Context context, JSONObject json) {
        if (context == null || json == null) {
            return;
        }
        Intent intent = new Intent(WebDavConfigReceiver.ACTION);
        intent.setPackage("com.attempt.afusekt");
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        }
        intent.putExtra(WebDavPrefs.KEY_ENABLED, json.optBoolean(WebDavPrefs.KEY_ENABLED, false));
        intent.putExtra(WebDavPrefs.KEY_BASE_URL, json.optString(WebDavPrefs.KEY_BASE_URL, ""));
        intent.putExtra(WebDavPrefs.KEY_USERNAME, json.optString(WebDavPrefs.KEY_USERNAME, ""));
        intent.putExtra(WebDavPrefs.KEY_PASSWORD, json.optString(WebDavPrefs.KEY_PASSWORD, ""));
        intent.putExtra(
                WebDavPrefs.KEY_REMOTE_PATH,
                json.optString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
        );
        context.sendBroadcast(intent);
        WebDavSyncConfig.log("config pushed to Afusekt enabled="
                + json.optBoolean(WebDavPrefs.KEY_ENABLED, false));
    }
}
