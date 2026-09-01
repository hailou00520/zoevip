package com.afusekt.lsp.sync;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Module UI writes via {@link SharedPreferences}; hooks read via {@link XSharedPreferences}.
 */
public final class WebDavPrefs {

    public static final String PREFS = "webdav_sync";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_REMOTE_PATH = "remote_path";

    private WebDavPrefs() {
    }

    public static SharedPreferences openModule(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean saveModule(
            Context context,
            boolean enabled,
            String baseUrl,
            String username,
            String password,
            String remotePath
    ) {
        boolean ok = openModule(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BASE_URL, baseUrl == null ? "" : baseUrl.trim())
                .putString(KEY_USERNAME, username == null ? "" : username)
                .putString(KEY_PASSWORD, password == null ? "" : password)
                .putString(KEY_REMOTE_PATH, remotePath == null ? "" : remotePath.trim())
                .commit();
        notifyHookProcess();
        WebDavExternalConfig.exportFromModule(context);
        WebDavConfigPush.broadcast(context);
        return ok;
    }

    public static void notifyHookProcess() {
        try {
            XSharedPreferences hookPrefs = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, PREFS);
            hookPrefs.makeWorldReadable();
            hookPrefs.reload();
        } catch (Throwable ignored) {
            // LSPosed xposedsharedprefs handles sync on supported builds.
        }
    }

    public static boolean isConfiguredLocal(Context context) {
        SharedPreferences prefs = openModule(context);
        return prefs.getBoolean(KEY_ENABLED, false)
                && !isEmpty(prefs.getString(KEY_BASE_URL, ""))
                && !isEmpty(prefs.getString(KEY_USERNAME, ""));
    }

    public static String buildRemoteUrlLocal(Context context) {
        SharedPreferences prefs = openModule(context);
        String base = trimTrailingSlash(prefs.getString(KEY_BASE_URL, ""));
        String path = prefs.getString(KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH);
        if (path == null || path.trim().isEmpty()) {
            path = WebDavSyncConfig.DEFAULT_REMOTE_PATH;
        } else {
            path = path.trim().replace('\\', '/');
        }
        if (base.endsWith("/")) {
            return base + path;
        }
        return base + "/" + path;
    }

    public static String getUsernameLocal(Context context) {
        return openModule(context).getString(KEY_USERNAME, "");
    }

    public static String getPasswordLocal(Context context) {
        return openModule(context).getString(KEY_PASSWORD, "");
    }

    public static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
