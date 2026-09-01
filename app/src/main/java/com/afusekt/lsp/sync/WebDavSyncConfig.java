package com.afusekt.lsp.sync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.afusekt.lsp.MainHook;

public final class WebDavSyncConfig {

    public static final String DEFAULT_REMOTE_PATH = "afusekt-library-sync.json";
    public static final String MODULE_PACKAGE = "com.zoevip.lsp";
    public static final String AFUSEKT_SYNC_SETTING_KEY = "同步资源库";

    private WebDavSyncConfig() {
    }

    /** Reload config from disk without async broadcasts that can race with sync. */
    public static void refreshForSync(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();

        WebDavConfigStore memory = WebDavConfigReceiver.getLastConfig();
        if (memory != null && memory.isConfigured()) {
            return;
        }

        WebDavConfigStore cache = WebDavConfigCache.load(app);
        if (cache != null && cache.isConfigured()) {
            WebDavConfigReceiver.remember(cache);
            log("config refreshed from afusekt cache");
            return;
        }

        WebDavConfigStore fresh = WebDavPrefsReader.readFresh();
        if (fresh != null && fresh.isConfigured()) {
            WebDavConfigReceiver.remember(fresh);
            WebDavConfigStore.applyBundle(app, bundleFromStore(fresh), false);
            log("config refreshed from prefs file");
        }
    }

    private static android.os.Bundle bundleFromStore(WebDavConfigStore store) {
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putBoolean(WebDavPrefs.KEY_ENABLED, store.isEnabled());
        bundle.putString(WebDavPrefs.KEY_BASE_URL, store.getBaseUrl());
        bundle.putString(WebDavPrefs.KEY_USERNAME, store.getUsername());
        bundle.putString(WebDavPrefs.KEY_PASSWORD, store.getPassword());
        bundle.putString(WebDavPrefs.KEY_REMOTE_PATH, store.getRemotePath());
        return bundle;
    }

    public static void warmFromInline(Context context) {
        WebDavConfigStore.warmFromInline(context);
    }

    public static android.os.Bundle buildInlineConfigBundle() {
        return WebDavConfigStore.buildInlineConfigBundle();
    }

    public static android.os.Bundle readFreshConfigBundle() {
        WebDavConfigStore store = WebDavPrefsReader.readFresh();
        if (store == null) {
            return buildInlineConfigBundle();
        }
        return bundleFromStore(store);
    }

    static WebDavConfigStore readFreshStore() {
        return WebDavPrefsReader.readFresh();
    }

    public static boolean isConfigured(Context context) {
        return load(context).isConfigured();
    }

    /** Fast path for sync hooks: memory/cache only, no disk reload. */
    public static boolean isConfiguredFast(Context context) {
        if (context == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        WebDavConfigStore memory = WebDavConfigReceiver.getLastConfig();
        if (memory != null && memory.isConfigured()) {
            return true;
        }
        WebDavConfigStore cache = WebDavConfigCache.load(app);
        if (cache != null && cache.isConfigured()) {
            WebDavConfigReceiver.remember(cache);
            return true;
        }
        return false;
    }

    /** Resolve whether WebDAV redirect should run, refreshing from disk only when needed. */
    public static boolean resolveForSync(Context context) {
        if (isConfiguredFast(context)) {
            return true;
        }
        refreshForSync(context);
        return isConfigured(context);
    }

    static boolean isConfiguredIncludingExternal(Context context) {
        if (load(context).isConfigured()) {
            return true;
        }
        WebDavConfigStore external = WebDavExternalConfig.load();
        return external != null && external.isConfigured();
    }

    public static String getBaseUrl(Context context) {
        return load(context).getBaseUrl();
    }

    public static String getUsername(Context context) {
        return load(context).getUsername();
    }

    public static String getPassword(Context context) {
        return load(context).getPassword();
    }

    public static String getRemotePath(Context context) {
        return load(context).getRemotePath();
    }

    public static String buildRemoteUrl(Context context) {
        return load(context).buildRemoteUrl();
    }

    public static String buildScrapeShardUrl(Context context, String sourceId) {
        String mainUrl = buildRemoteUrl(context);
        int slash = mainUrl.lastIndexOf('/');
        String dir = slash >= 0 ? mainUrl.substring(0, slash) : mainUrl;
        return dir + "/scrape/" + sanitizeSourceId(sourceId) + ".json";
    }

    static String sanitizeSourceId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) {
            return "unknown";
        }
        return sourceId
                .replace("/", "_")
                .replace("\\", "_")
                .replace(":", "_")
                .replace("?", "_")
                .replace("#", "_")
                .replace("%", "_");
    }

    public static void logConfigSnapshot(Context context) {
        if (context == null) {
            log("config snapshot skipped: no context");
            return;
        }
        log("config snapshot: " + load(context).describeForLog());
    }

    private static WebDavConfigStore load(Context context) {
        return WebDavConfigStore.load(context);
    }

    static void log(String message) {
        Log.i(MainHook.TAG, message);
    }
}
