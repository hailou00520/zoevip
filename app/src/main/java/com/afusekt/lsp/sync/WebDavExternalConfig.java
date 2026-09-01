package com.afusekt.lsp.sync;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Fallback config channel when package visibility blocks ContentProvider access.
 * Module writes; Afusekt hook reads from public Downloads.
 */
public final class WebDavExternalConfig {

    private static final String DIR = ".zoevip";
    private static final String FILE = "webdav.json";

    private WebDavExternalConfig() {
    }

    public static File getConfigFile() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return new File(new File(downloads, DIR), FILE);
    }

    public static void exportSnapshot(WebDavConfigSnapshot snapshot) {
        if (snapshot == null || !snapshot.enabled) {
            delete();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put(WebDavPrefs.KEY_ENABLED, snapshot.enabled);
            json.put(WebDavPrefs.KEY_BASE_URL, snapshot.baseUrl);
            json.put(WebDavPrefs.KEY_USERNAME, snapshot.username);
            json.put(WebDavPrefs.KEY_PASSWORD, snapshot.password);
            json.put(WebDavPrefs.KEY_REMOTE_PATH, snapshot.remotePath);
            File target = getConfigFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            WebDavSyncConfig.log("external config exported to " + target.getAbsolutePath());
        } catch (Throwable t) {
            WebDavSyncConfig.log("external export failed: " + t.getMessage());
        }
    }

    public static void exportFromModule(Context context) {
        try {
            var prefs = WebDavPrefs.openModule(context);
            if (!prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false)) {
                delete();
                return;
            }
            JSONObject json = new JSONObject();
            json.put(WebDavPrefs.KEY_ENABLED, prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false));
            json.put(WebDavPrefs.KEY_BASE_URL, prefs.getString(WebDavPrefs.KEY_BASE_URL, ""));
            json.put(WebDavPrefs.KEY_USERNAME, prefs.getString(WebDavPrefs.KEY_USERNAME, ""));
            json.put(WebDavPrefs.KEY_PASSWORD, prefs.getString(WebDavPrefs.KEY_PASSWORD, ""));
            json.put(
                    WebDavPrefs.KEY_REMOTE_PATH,
                    prefs.getString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
            File target = getConfigFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            WebDavSyncConfig.log("external config exported to " + target.getAbsolutePath());
        } catch (Throwable t) {
            WebDavSyncConfig.log("external export failed: " + t.getMessage());
        }
    }

    static WebDavConfigStore load() {
        File file = getConfigFile();
        if (!file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file),
                StandardCharsets.UTF_8
        ))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            WebDavConfigStore store = new WebDavConfigStore();
            store.apply(
                    json.optBoolean(WebDavPrefs.KEY_ENABLED, false),
                    json.optString(WebDavPrefs.KEY_BASE_URL, ""),
                    json.optString(WebDavPrefs.KEY_USERNAME, ""),
                    json.optString(WebDavPrefs.KEY_PASSWORD, ""),
                    json.optString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
            );
            return store;
        } catch (Throwable t) {
            WebDavSyncConfig.log("external read failed: " + t.getMessage());
            return null;
        }
    }

    static void delete() {
        try {
            File file = getConfigFile();
            if (file.isFile() && !file.delete()) {
                WebDavSyncConfig.log("external delete failed: " + file.getAbsolutePath());
            }
        } catch (Throwable t) {
            WebDavSyncConfig.log("external delete failed: " + t.getMessage());
        }
    }
}
