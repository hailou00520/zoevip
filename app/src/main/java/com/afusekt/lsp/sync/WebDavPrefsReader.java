package com.afusekt.lsp.sync;

import android.os.Environment;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/** Reads module prefs straight from disk to avoid stale XSharedPreferences in a live process. */
public final class WebDavPrefsReader {

    private WebDavPrefsReader() {
    }

    static WebDavConfigStore readFresh() {
        for (File file : candidateFiles()) {
            WebDavConfigStore store = parseXml(file);
            if (store != null) {
                WebDavSyncConfig.log("prefs file read: " + file.getAbsolutePath() + " -> " + store.describeForLog());
                return store;
            }
        }
        return null;
    }

    private static File[] candidateFiles() {
        File[] ordered = new File[4];
        int index = 0;

        try {
            XSharedPreferences prefs = new XSharedPreferences(WebDavSyncConfig.MODULE_PACKAGE, WebDavPrefs.PREFS);
            prefs.reload();
            File xpFile = prefs.getFile();
            if (xpFile != null) {
                ordered[index++] = xpFile;
            }
        } catch (Throwable ignored) {
        }

        ordered[index++] = new File(
                Environment.getDataDirectory(),
                "data/" + WebDavSyncConfig.MODULE_PACKAGE + "/shared_prefs/" + WebDavPrefs.PREFS + ".xml"
        );

        File userDe = new File(
                "/data/user_de/0/" + WebDavSyncConfig.MODULE_PACKAGE + "/shared_prefs/" + WebDavPrefs.PREFS + ".xml"
        );
        ordered[index++] = userDe;

        File external = WebDavExternalConfig.getConfigFile();
        if (external != null) {
            ordered[index] = external;
        }
        return ordered;
    }

    private static WebDavConfigStore parseXml(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            if (file.getName().endsWith(".json")) {
                return parseJson(in);
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");

            boolean enabled = false;
            Map<String, String> strings = new HashMap<>();
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("boolean".equals(name)) {
                        String key = parser.getAttributeValue(null, "name");
                        String value = parser.getAttributeValue(null, "value");
                        if (WebDavPrefs.KEY_ENABLED.equals(key)) {
                            enabled = "true".equals(value);
                        }
                    } else if ("string".equals(name)) {
                        String key = parser.getAttributeValue(null, "name");
                        String value = parser.nextText();
                        if (key != null) {
                            strings.put(key, value == null ? "" : value);
                        }
                    }
                }
                event = parser.next();
            }

            WebDavConfigStore store = new WebDavConfigStore();
            store.apply(
                    enabled,
                    strings.getOrDefault(WebDavPrefs.KEY_BASE_URL, ""),
                    strings.getOrDefault(WebDavPrefs.KEY_USERNAME, ""),
                    strings.getOrDefault(WebDavPrefs.KEY_PASSWORD, ""),
                    strings.getOrDefault(
                            WebDavPrefs.KEY_REMOTE_PATH,
                            WebDavSyncConfig.DEFAULT_REMOTE_PATH
                    )
            );
            return store;
        } catch (Throwable t) {
            WebDavSyncConfig.log("prefs file parse failed: " + file.getAbsolutePath() + " -> " + t.getMessage());
            return null;
        }
    }

    private static WebDavConfigStore parseJson(FileInputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        org.json.JSONObject json = new org.json.JSONObject(sb.toString());
        WebDavConfigStore store = new WebDavConfigStore();
        store.apply(
                json.optBoolean(WebDavPrefs.KEY_ENABLED, false),
                json.optString(WebDavPrefs.KEY_BASE_URL, ""),
                json.optString(WebDavPrefs.KEY_USERNAME, ""),
                json.optString(WebDavPrefs.KEY_PASSWORD, ""),
                json.optString(WebDavPrefs.KEY_REMOTE_PATH, WebDavSyncConfig.DEFAULT_REMOTE_PATH)
        );
        return store;
    }
}
