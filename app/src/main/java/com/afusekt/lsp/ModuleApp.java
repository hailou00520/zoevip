package com.afusekt.lsp;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.afusekt.lsp.prefs.XimalayaPrefs;
import com.afusekt.lsp.sync.WebDavConfigPush;
import com.afusekt.lsp.sync.WebDavExternalConfig;
import com.afusekt.lsp.sync.WebDavPrefs;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import de.robv.android.xposed.XSharedPreferences;

public final class ModuleApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            XSharedPreferences prefs = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, WebDavPrefs.PREFS);
            prefs.makeWorldReadable();
            XSharedPreferences ximalaya = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, XimalayaPrefs.PREFS);
            ximalaya.makeWorldReadable();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            WebDavPrefs.notifyHookProcess();
            XimalayaPrefs.notifyHookProcess();
            WebDavExternalConfig.exportFromModule(this);
            if (WebDavPrefs.isConfiguredLocal(this)) {
                WebDavConfigPush.broadcast(this);
            }
        } catch (Throwable t) {
            Log.w("ZoeVIP", "module startup sync failed: " + t.getMessage());
        }
    }
}
