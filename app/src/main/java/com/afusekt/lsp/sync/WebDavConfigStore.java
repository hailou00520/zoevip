package com.afusekt.lsp.sync;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import com.afusekt.lsp.MainHook;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

final class WebDavConfigStore {

    private boolean enabled;
    private String baseUrl = "";
    private String username = "";
    private String password = "";
    private String remotePath = WebDavSyncConfig.DEFAULT_REMOTE_PATH;

    boolean isConfigured() {
        return enabled && !isEmpty(baseUrl) && !isEmpty(username);
    }

    boolean isEnabled() {
        return enabled;
    }

    String getBaseUrl() {
        return WebDavPrefs.trimTrailingSlash(baseUrl);
    }

    String getUsername() {
        return username == null ? "" : username;
    }

    String getPassword() {
        return password == null ? "" : password;
    }

    String getRemotePath() {
        if (remotePath == null || remotePath.trim().isEmpty()) {
            return WebDavSyncConfig.DEFAULT_REMOTE_PATH;
        }
        return remotePath.trim().replace('\\', '/');
    }

    String buildRemoteUrl() {
        String base = getBaseUrl();
        String path = getRemotePath();
        if (base.endsWith("/")) {
            return base + path;
        }
        return base + "/" + path;
    }

    static WebDavConfigStore load(Context context) {
        WebDavConfigStore live = loadLive(context);
        WebDavSyncConfig.log("config resolved: " + live.describe());
        if (live.isConfigured()) {
            syncCache(context, live);
        }
        return live;
    }

    static void persistDisabled(Context context, WebDavConfigStore store) {
        if (context == null || store == null) {
            return;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put(WebDavPrefs.KEY_ENABLED, false);
            json.put(WebDavPrefs.KEY_BASE_URL, store.baseUrl == null ? "" : store.baseUrl);
            json.put(WebDavPrefs.KEY_USERNAME, store.username == null ? "" : store.username);
            json.put(WebDavPrefs.KEY_PASSWORD, "");
            json.put(WebDavPrefs.KEY_REMOTE_PATH, store.getRemotePath());
            WebDavConfigCache.save(context.getApplicationContext(), json);
            WebDavConfigReceiver.remember(store);
            WebDavSyncConfig.log("disabled config persisted to cache");
        } catch (Throwable t) {
            WebDavSyncConfig.log("persist disabled failed: " + t.getMessage());
        }
    }

    /**
     * Afusekt-side broadcast cache is the most reliable channel on Android 13+ because
     * package visibility and scoped storage block other IPC paths.
     */
    private static WebDavConfigStore loadLive(Context context) {
        WebDavConfigStore fromMemory = WebDavConfigReceiver.getLastConfig();
        if (fromMemory != null && fromMemory.isConfigured()) {
            WebDavSyncConfig.log("config source=memory");
            return fromMemory;
        }

        WebDavConfigStore fromCache = WebDavConfigCache.load(context);
        if (fromCache != null && fromCache.isConfigured()) {
            WebDavSyncConfig.log("config source=afusekt_cache");
            return fromCache;
        }

        WebDavConfigStore fromPrefsFile = WebDavPrefsReader.readFresh();
        if (fromPrefsFile != null && fromPrefsFile.isConfigured()) {
            WebDavSyncConfig.log("config source=prefs_file");
            WebDavConfigReceiver.remember(fromPrefsFile);
            return fromPrefsFile;
        }

        WebDavConfigStore fromProvider = loadFromProvider(context);
        if (fromProvider != null && fromProvider.isConfigured()) {
            WebDavSyncConfig.log("config source=provider");
            return fromProvider;
        }

        WebDavConfigStore fromPrefs = loadFromXSharedPreferences();
        if (fromPrefs.isConfigured()) {
            WebDavSyncConfig.log("config source=xprefs");
            return fromPrefs;
        }

        WebDavConfigStore fromModuleContext = loadFromModuleContext(context);
        if (fromModuleContext != null && fromModuleContext.isConfigured()) {
            WebDavSyncConfig.log("config source=module_context");
            return fromModuleContext;
        }

        WebDavConfigStore fromExternal = WebDavExternalConfig.load();
        if (fromExternal != null && fromExternal.isConfigured()) {
            WebDavSyncConfig.log("config source=external_file");
            return fromExternal;
        }

        if (fromMemory != null) {
            WebDavSyncConfig.log("config source=memory disabled");
            return fromMemory;
        }
        if (fromCache != null) {
            WebDavSyncConfig.log("config source=afusekt_cache disabled");
            return fromCache;
        }
        if (fromProvider != null) {
            WebDavSyncConfig.log("config source=provider disabled");
            return finalizeStore(fromProvider);
        }
        if (modulePrefsFileExists()) {
            WebDavSyncConfig.log("config source=xprefs disabled");
            return finalizeStore(fromPrefs);
        }

        WebDavSyncConfig.log("config source=default");
        return fromPrefs;
    }

    private static WebDavConfigStore finalizeStore(WebDavConfigStore store) {
        if (store != null && !store.enabled) {
            WebDavExternalConfig.delete();
        }
        return store;
    }

    private static boolean modulePrefsFileExists() {
        XSharedPreferences prefs = openHookPrefs();
        File file = prefs.getFile();
        return file != null && file.exists();
    }

    private static void syncCache(Context context, WebDavConfigStore store) {
        if (context == null || store == null) {
            return;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put(WebDavPrefs.KEY_ENABLED, store.enabled);
            json.put(WebDavPrefs.KEY_BASE_URL, store.baseUrl == null ? "" : store.baseUrl);
            json.put(WebDavPrefs.KEY_USERNAME, store.username == null ? "" : store.username);
            json.put(WebDavPrefs.KEY_PASSWORD, store.password == null ? "" : store.password);
            json.put(WebDavPrefs.KEY_REMOTE_PATH, store.getRemotePath());
            WebDavConfigCache.save(context.getApplicationContext(), json);
        } catch (Throwable t) {
            WebDavSyncConfig.log("config cache sync failed: " + t.getMessage());
        }
    }

    void apply(boolean enabled, String baseUrl, String username, String password, String remotePath) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.remotePath = remotePath;
    }

    private static WebDavConfigStore loadFromModuleContext(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Context moduleContext = context.getApplicationContext().createPackageContext(
                    WebDavSyncConfig.MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY
            );
            var prefs = moduleContext.getSharedPreferences(WebDavPrefs.PREFS, Context.MODE_PRIVATE);
            return fromPrefs(
                    prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                    prefs.getString(WebDavPrefs.KEY_BASE_URL, ""),
                    prefs.getString(WebDavPrefs.KEY_USERNAME, ""),
                    prefs.getString(WebDavPrefs.KEY_PASSWORD, ""),
                    prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
        } catch (Throwable t) {
            WebDavSyncConfig.log("module context read failed: " + t.getMessage());
            return null;
        }
    }

    static void applyBundle(Context context, android.os.Bundle bundle) {
        applyBundle(context, bundle, false);
    }

    static void applyBundle(Context context, android.os.Bundle bundle, boolean allowDisable) {
        if (context == null || bundle == null) {
            return;
        }
        WebDavConfigStore incoming = fromBundle(bundle);
        if (incoming.isConfigured()) {
            WebDavConfigReceiver.remember(incoming);
            syncCache(context.getApplicationContext(), incoming);
            return;
        }
        if (!incoming.isEnabled()) {
            if (!allowDisable && isConfiguredSomewhere(context)) {
                WebDavSyncConfig.log("skip applying disabled inline config");
                return;
            }
            WebDavConfigReceiver.remember(incoming);
            syncCache(context.getApplicationContext(), incoming);
            WebDavSyncConfig.log("config disabled from module");
            return;
        }
        if (isConfiguredSomewhere(context)) {
            WebDavSyncConfig.log("skip applying incomplete config");
            return;
        }
        WebDavConfigReceiver.remember(incoming);
        syncCache(context.getApplicationContext(), incoming);
    }

    private static boolean isConfiguredSomewhere(Context context) {
        WebDavConfigStore memory = WebDavConfigReceiver.getLastConfig();
        if (memory != null && memory.isConfigured()) {
            return true;
        }
        WebDavConfigStore cache = WebDavConfigCache.load(context);
        if (cache != null && cache.isConfigured()) {
            return true;
        }
        WebDavConfigStore fresh = WebDavPrefsReader.readFresh();
        return fresh != null && fresh.isConfigured();
    }

    static WebDavConfigStore fromBundle(android.os.Bundle bundle) {
        return fromPrefs(
                bundle.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                bundle.getString(WebDavPrefs.KEY_BASE_URL, ""),
                bundle.getString(WebDavPrefs.KEY_USERNAME, ""),
                bundle.getString(WebDavPrefs.KEY_PASSWORD, ""),
                bundle.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
        );
    }

    static void warmFromInline(Context context) {
        if (context == null) {
            return;
        }
        try {
            Bundle bundle = buildInlineConfigBundle();
            if (bundle == null || !bundle.getBoolean(WebDavPrefs.KEY_ENABLED, false)) {
                return;
            }
            String baseUrl = bundle.getString(WebDavPrefs.KEY_BASE_URL, "");
            String username = bundle.getString(WebDavPrefs.KEY_USERNAME, "");
            if (baseUrl == null || baseUrl.trim().isEmpty() || username == null || username.trim().isEmpty()) {
                return;
            }
            applyBundle(context, bundle);
            WebDavSyncConfig.log("inline config warmed into cache");
        } catch (Throwable t) {
            WebDavSyncConfig.log("inline warm failed: " + t.getMessage());
        }
    }

    static Bundle buildInlineConfigBundle() {
        try {
            XSharedPreferences prefs = openHookPrefs();
            try {
                prefs.makeWorldReadable();
            } catch (Throwable ignored) {
            }
            prefs.reload();

            Bundle bundle = new Bundle();
            bundle.putBoolean(WebDavPrefs.KEY_ENABLED, prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false));
            bundle.putString(WebDavPrefs.KEY_BASE_URL, prefs.getString(WebDavPrefs.KEY_BASE_URL, ""));
            bundle.putString(WebDavPrefs.KEY_USERNAME, prefs.getString(WebDavPrefs.KEY_USERNAME, ""));
            bundle.putString(WebDavPrefs.KEY_PASSWORD, prefs.getString(WebDavPrefs.KEY_PASSWORD, ""));
            bundle.putString(
                    WebDavPrefs.KEY_REMOTE_PATH,
                    prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
            return bundle;
        } catch (Throwable t) {
            WebDavSyncConfig.log("inline provider bundle failed: " + t.getMessage());
            return null;
        }
    }

    private static WebDavConfigStore loadFromProvider(Context context) {
        if (context == null) {
            return null;
        }
        try {
            Bundle bundle = context.getApplicationContext().getContentResolver().call(
                    WebDavConfigProvider.CONTENT_URI,
                    "getConfig",
                    null,
                    null
            );
            if (bundle == null) {
                bundle = buildInlineConfigBundle();
            }
            if (bundle == null) {
                return null;
            }
            return fromPrefs(
                    bundle.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                    bundle.getString(WebDavPrefs.KEY_BASE_URL, ""),
                    bundle.getString(WebDavPrefs.KEY_USERNAME, ""),
                    bundle.getString(WebDavPrefs.KEY_PASSWORD, ""),
                    bundle.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
        } catch (Throwable t) {
            WebDavSyncConfig.log("provider read failed: " + t.getMessage());
            try {
                Bundle bundle = buildInlineConfigBundle();
                if (bundle == null) {
                    return null;
                }
                return fromPrefs(
                        bundle.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                        bundle.getString(WebDavPrefs.KEY_BASE_URL, ""),
                        bundle.getString(WebDavPrefs.KEY_USERNAME, ""),
                        bundle.getString(WebDavPrefs.KEY_PASSWORD, ""),
                        bundle.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
                );
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static WebDavConfigStore loadFromXSharedPreferences() {
        WebDavConfigStore fresh = WebDavPrefsReader.readFresh();
        if (fresh != null) {
            return fresh;
        }
        XSharedPreferences prefs = openHookPrefs();
        try {
            prefs.makeWorldReadable();
        } catch (Throwable ignored) {
        }
        prefs.reload();
        return fromPrefs(
                prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false),
                prefs.getString(WebDavPrefs.KEY_BASE_URL, ""),
                prefs.getString(WebDavPrefs.KEY_USERNAME, ""),
                prefs.getString(WebDavPrefs.KEY_PASSWORD, ""),
                prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
        );
    }

    private static WebDavConfigStore fromPrefs(
            boolean enabled,
            String baseUrl,
            String username,
            String password,
            String remotePath
    ) {
        WebDavConfigStore store = new WebDavConfigStore();
        store.apply(enabled, baseUrl, username, password, remotePath);
        return store;
    }

    private static XSharedPreferences openHookPrefs() {
        XSharedPreferences byPackage = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, WebDavPrefs.PREFS);
        byPackage.reload();
        if (byPackage.getFile() != null && byPackage.getFile().exists()) {
            return byPackage;
        }
        File direct = new File(
                Environment.getDataDirectory(),
                "data/" + WebDavSyncConfig.MODULE_PACKAGE + "/shared_prefs/" + WebDavPrefs.PREFS + ".xml"
        );
        return new XSharedPreferences(direct);
    }

    String describeForLog() {
        return describe();
    }

    private String describe() {
        return "enabled=" + enabled
                + ", url=" + mask(getBaseUrl())
                + ", user=" + mask(getUsername())
                + ", path=" + getRemotePath();
    }

    private static String mask(String value) {
        if (isEmpty(value)) {
            return "(empty)";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, Math.min(8, value.length())) + "...";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
