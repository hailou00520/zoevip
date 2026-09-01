package com.afusekt.lsp.sync;

import android.content.Context;
import android.os.Bundle;

/** Read/write WebDAV config from Afusekt hook process or module app. */
public final class WebDavConfigAccess {

    private WebDavConfigAccess() {
    }

    public static WebDavConfigSnapshot load(Context context) {
        if (context == null) {
            return WebDavConfigSnapshot.empty();
        }
        Context app = context.getApplicationContext();

        WebDavConfigStore memory = WebDavConfigReceiver.getLastConfig();
        if (memory != null && (memory.isConfigured() || memory.isEnabled())) {
            return WebDavConfigSnapshot.fromStore(memory);
        }

        WebDavConfigStore cache = WebDavConfigCache.load(app);
        if (cache != null && (cache.isConfigured() || cache.isEnabled())) {
            return WebDavConfigSnapshot.fromStore(cache);
        }

        WebDavConfigStore fresh = WebDavPrefsReader.readFresh();
        if (fresh != null) {
            return WebDavConfigSnapshot.fromStore(fresh);
        }

        if (WebDavSyncConfig.MODULE_PACKAGE.equals(app.getPackageName())) {
            var prefs = WebDavPrefs.openModule(app);
            return new WebDavConfigSnapshot(
                    prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                    prefs.getString(WebDavPrefs.KEY_BASE_URL, ""),
                    prefs.getString(WebDavPrefs.KEY_USERNAME, ""),
                    prefs.getString(WebDavPrefs.KEY_PASSWORD, ""),
                    prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
        }

        return WebDavConfigSnapshot.empty();
    }

    public static boolean save(Context context, WebDavConfigSnapshot snapshot) {
        if (context == null || snapshot == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        Bundle bundle = snapshot.toBundle();
        WebDavConfigStore.applyBundle(app, bundle, true);
        WebDavExternalConfig.exportSnapshot(snapshot);

        if (WebDavSyncConfig.MODULE_PACKAGE.equals(app.getPackageName())) {
            return WebDavPrefs.saveModule(
                    app,
                    snapshot.enabled,
                    snapshot.baseUrl,
                    snapshot.username,
                    snapshot.password,
                    snapshot.remotePath
            );
        }

        try {
            Context module = app.createPackageContext(
                    WebDavSyncConfig.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            WebDavPrefs.saveModule(
                    module,
                    snapshot.enabled,
                    snapshot.baseUrl,
                    snapshot.username,
                    snapshot.password,
                    snapshot.remotePath
            );
        } catch (Throwable ignored) {
            WebDavSyncConfig.log("module prefs sync skipped from Afusekt");
        }
        return true;
    }

    public static boolean isConfigured(Context context) {
        WebDavConfigSnapshot snapshot = load(context);
        return snapshot.isConfigured();
    }
}
