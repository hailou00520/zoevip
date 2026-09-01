package com.afusekt.lsp.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Answers config pull requests from the hooked Afusekt process. */
public final class WebDavConfigRequestReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.zoevip.lsp.WEBDAV_CONFIG_REQUEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        WebDavSyncConfig.log("config request received");
        WebDavConfigPush.broadcast(context.getApplicationContext());
    }
}
