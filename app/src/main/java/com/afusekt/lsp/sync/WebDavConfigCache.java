package com.afusekt.lsp.sync;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Config cached inside Afusekt's private storage, fed by module broadcasts. */
public final class WebDavConfigCache {

    private static final String FILE = "afusekt_webdav_config.json";

    private WebDavConfigCache() {
    }

    public static void save(Context context, JSONObject json) {
        if (context == null || json == null) {
            return;
        }
        try {
            File target = new File(context.getFilesDir(), FILE);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            WebDavSyncConfig.log("config cached at " + target.getAbsolutePath());
        } catch (Throwable t) {
            WebDavSyncConfig.log("config cache write failed: " + t.getMessage());
        }
    }

    static WebDavConfigStore load(Context context) {
        if (context == null) {
            return null;
        }
        File file = new File(context.getFilesDir(), FILE);
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
            WebDavSyncConfig.log("config cache read failed: " + t.getMessage());
            return null;
        }
    }
}
