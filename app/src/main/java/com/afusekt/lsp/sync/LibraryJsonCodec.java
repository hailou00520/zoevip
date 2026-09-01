package com.afusekt.lsp.sync;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class LibraryJsonCodec {

    private LibraryJsonCodec() {
    }

    public static String encode(String userAccount, List<?> videoSources) throws Exception {
        return encode(userAccount, videoSources, null, null);
    }

    public static String encode(
            String userAccount,
            List<?> videoSources,
            Context context,
            ClassLoader classLoader
    ) throws Exception {
        JSONObject root = new JSONObject();
        root.put("userAccount", userAccount == null ? "" : userAccount);
        JSONArray array = new JSONArray();
        if (videoSources != null) {
            for (Object source : videoSources) {
                array.put(VideoSourceCompat.toJson(source, userAccount));
            }
        }
        root.put("videoSources", array);
        if (context != null && classLoader != null) {
            try {
                ScrapeTableSync.appendToRoot(root, context, classLoader, videoSources);
            } catch (Throwable t) {
                WebDavSyncConfig.log("scrape dump skipped: " + t);
                root.put("scrapeError", String.valueOf(t.getMessage()));
            }
        }
        return root.toString();
    }

    /** Write full backup (videoSources + scrapeTables) to a file without holding the whole JSON in memory. */
    public static ScrapeTableSync.WriteStats writeToFile(
            File file,
            String userAccount,
            List<?> videoSources,
            Context context,
            ClassLoader classLoader
    ) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.setLenient(true);
            writer.beginObject();
            writer.name("userAccount").value(userAccount == null ? "" : userAccount);
            writer.name("videoSources").beginArray();
            if (videoSources != null) {
                for (Object source : videoSources) {
                    writeJsonObject(writer, VideoSourceCompat.toJson(source, userAccount));
                }
            }
            writer.endArray();
            ScrapeTableSync.WriteStats stats = ScrapeTableSync.writeScrapeTablesToWriter(
                    writer, context, classLoader, videoSources);
            writer.endObject();
            writer.flush();
            return stats;
        }
    }

    public static List<Object> decodeToVideoSources(String json, ClassLoader classLoader) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray array;
        if (root.has("videoSources")) {
            array = root.getJSONArray("videoSources");
        } else if (root.has("data")) {
            array = root.getJSONArray("data");
        } else {
            throw new IllegalStateException("JSON 缺少 videoSources/data 字段");
        }

        Class<?> videoSourceClass = classLoader.loadClass("com.attempt.afusekt.liveData.VideoSource");
        ArrayList<Object> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            result.add(VideoSourceCompat.fromJson(item, videoSourceClass));
        }
        return result;
    }

    public static List<Object> decodeVideoSourcesFromFile(File file, ClassLoader classLoader) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.setLenient(true);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("videoSources".equals(name)) {
                    return parseVideoSourcesArray(reader, classLoader);
                }
                reader.skipValue();
            }
            throw new IllegalStateException("JSON 缺少 videoSources 字段");
        }
    }

    private static List<Object> parseVideoSourcesArray(JsonReader reader, ClassLoader classLoader) throws Exception {
        Class<?> videoSourceClass = classLoader.loadClass("com.attempt.afusekt.liveData.VideoSource");
        ArrayList<Object> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            JSONObject item = readJsonObject(reader);
            result.add(VideoSourceCompat.fromJson(item, videoSourceClass));
        }
        reader.endArray();
        return result;
    }

    private static JSONObject readJsonObject(JsonReader reader) throws Exception {
        JSONObject object = new JSONObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                object.put(key, JSONObject.NULL);
            } else if (reader.peek() == JsonToken.BOOLEAN) {
                object.put(key, reader.nextBoolean());
            } else if (reader.peek() == JsonToken.NUMBER) {
                String num = reader.nextString();
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    object.put(key, Double.parseDouble(num));
                } else {
                    try {
                        object.put(key, Long.parseLong(num));
                    } catch (NumberFormatException e) {
                        object.put(key, num);
                    }
                }
            } else if (reader.peek() == JsonToken.STRING) {
                object.put(key, reader.nextString());
            } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                object.put(key, readJsonArray(reader));
            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                object.put(key, readJsonObject(reader));
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return object;
    }

    private static JSONArray readJsonArray(JsonReader reader) throws Exception {
        JSONArray array = new JSONArray();
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull();
                array.put(JSONObject.NULL);
            } else if (reader.peek() == JsonToken.BOOLEAN) {
                array.put(reader.nextBoolean());
            } else if (reader.peek() == JsonToken.NUMBER) {
                String num = reader.nextString();
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    array.put(Double.parseDouble(num));
                } else {
                    try {
                        array.put(Long.parseLong(num));
                    } catch (NumberFormatException e) {
                        array.put(num);
                    }
                }
            } else if (reader.peek() == JsonToken.STRING) {
                array.put(reader.nextString());
            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                array.put(readJsonObject(reader));
            } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                array.put(readJsonArray(reader));
            } else {
                reader.skipValue();
            }
        }
        reader.endArray();
        return array;
    }

    private static void writeJsonObject(JsonWriter writer, JSONObject object) throws Exception {
        writer.beginObject();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            writer.name(key);
            writeJsonValue(writer, object.get(key));
        }
        writer.endObject();
    }

    private static void writeJsonValue(JsonWriter writer, Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) {
            writer.nullValue();
        } else if (value instanceof Boolean) {
            writer.value((Boolean) value);
        } else if (value instanceof Integer) {
            writer.value((Integer) value);
        } else if (value instanceof Long) {
            writer.value((Long) value);
        } else if (value instanceof Double) {
            writer.value((Double) value);
        } else if (value instanceof Float) {
            writer.value((Float) value);
        } else if (value instanceof String) {
            writer.value((String) value);
        } else if (value instanceof JSONArray) {
            writer.beginArray();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                writeJsonValue(writer, array.get(i));
            }
            writer.endArray();
        } else if (value instanceof JSONObject) {
            writeJsonObject(writer, (JSONObject) value);
        } else {
            writer.value(String.valueOf(value));
        }
    }
}
