package com.afusekt.lsp.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.afusekt.lsp.util.Reflect;

/**
 * Scrape data is attached to each VideoSource (by sourceId).
 * <ul>
 *   <li>网盘/本地/SMB/Alist 等：本地刮削是唯一来源，必须随资源库备份</li>
 *   <li>Emby/Jellyfin/Plex 等媒体服：元数据主要在服务器；仅当开启本地扫描(isScan)时才备份</li>
 * </ul>
 */
public final class ScrapeTableSync {

    public static final class WriteStats {
        public final int movies;
        public final int tvs;
        public final int infos;

        WriteStats(int movies, int tvs, int infos) {
            this.movies = movies;
            this.tvs = tvs;
            this.infos = infos;
        }

        public int total() {
            return movies + tvs + infos;
        }
    }

    private ScrapeTableSync() {
    }

    public static void appendToRoot(
            JSONObject root,
            Context context,
            ClassLoader classLoader,
            List<?> videoSources
    ) throws Exception {
        SQLiteDatabase db = openWritableDb(context, classLoader);
        Set<String> sourceIds = scrapeSourceIds(videoSources, classLoader);
        JSONArray movies = dumpBySourceIds(db, "MovieData", sourceIds);
        JSONArray tvs = dumpBySourceIds(db, "TvData", sourceIds);
        Set<String> mediaIds = collectMediaIds(movies, tvs);
        JSONArray infos = dumpVideoInfo(db, mediaIds);

        JSONObject scrape = new JSONObject();
        scrape.put("MovieData", movies);
        scrape.put("TvData", tvs);
        scrape.put("VideoInfoTable", infos);
        scrape.put("sourceIds", toJsonArray(sourceIds));

        root.put("scrapeTables", scrape);
        root.put("scrapeVersion", 2);
        root.put("scrapeMovieCount", movies.length());
        root.put("scrapeTvCount", tvs.length());
        WebDavSyncConfig.log("scrape dump movies=" + movies.length()
                + " tvs=" + tvs.length()
                + " info=" + infos.length()
                + " sources=" + sourceIds.size());
    }

    /** Stream full scrapeTables into an open JSON object (caller writes videoSources first). */
    public static WriteStats writeScrapeTablesToWriter(
            JsonWriter writer,
            Context context,
            ClassLoader classLoader,
            List<?> videoSources
    ) throws Exception {
        SQLiteDatabase db = openWritableDb(context, classLoader);
        Set<String> sourceIds = scrapeSourceIds(videoSources, classLoader);

        writer.name("scrapeTables").beginObject();

        writer.name("sourceIds").beginArray();
        for (String id : sourceIds) {
            writer.value(id);
        }
        writer.endArray();

        writer.name("MovieData").beginArray();
        int movies = writeTableRows(writer, db, "MovieData", sourceIds);
        writer.endArray();

        writer.name("TvData").beginArray();
        int tvs = writeTableRows(writer, db, "TvData", sourceIds);
        writer.endArray();

        Set<String> mediaIds = collectMediaIdsFromDb(db, sourceIds);

        writer.name("VideoInfoTable").beginArray();
        int infos = writeVideoInfoRows(writer, db, mediaIds);
        writer.endArray();

        writer.endObject();

        writer.name("scrapeVersion").value(2);
        writer.name("scrapeMovieCount").value(movies);
        writer.name("scrapeTvCount").value(tvs);

        WebDavSyncConfig.log("scrape stream movies=" + movies
                + " tvs=" + tvs
                + " info=" + infos
                + " sources=" + sourceIds.size());
        return new WriteStats(movies, tvs, infos);
    }

    public static int restoreFromRoot(
            JSONObject root,
            Context context,
            ClassLoader classLoader,
            List<?> videoSources
    ) throws Exception {
        if (!root.has("scrapeTables")) {
            return -1;
        }
        JSONObject scrape = root.getJSONObject("scrapeTables");
        SQLiteDatabase db = openWritableDb(context, classLoader);
        Set<String> sourceIds = scrapeSourceIds(videoSources, classLoader);
        JSONArray backupIds = scrape.optJSONArray("sourceIds");
        if (backupIds != null) {
            for (int i = 0; i < backupIds.length(); i++) {
                String id = backupIds.optString(i, "").trim();
                if (!id.isEmpty()) {
                    sourceIds.add(id);
                }
            }
        }

        int total = 0;
        db.beginTransaction();
        try {
            for (String sourceId : sourceIds) {
                db.execSQL("DELETE FROM MovieData WHERE sourceId = ?", new Object[]{sourceId});
                db.execSQL("DELETE FROM TvData WHERE sourceId = ?", new Object[]{sourceId});
            }

            int movies = restoreTable(db, "MovieData", scrape.optJSONArray("MovieData"), true);
            int tvs = restoreTable(db, "TvData", scrape.optJSONArray("TvData"), true);
            int infos = restoreTable(db, "VideoInfoTable", scrape.optJSONArray("VideoInfoTable"), false);
            total = movies + tvs + infos;
            WebDavSyncConfig.log("scrape restore movies=" + movies + " tvs=" + tvs + " info=" + infos);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return total;
    }

    /** Restore scrapeTables from a single backup file without loading the whole JSON into memory. */
    public static int restoreFromBackupFile(
            File file,
            Context context,
            ClassLoader classLoader,
            List<?> videoSources
    ) throws Exception {
        SQLiteDatabase db = openWritableDb(context, classLoader);
        Set<String> sourceIds = scrapeSourceIds(videoSources, classLoader);
        int total = -1;
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.setLenient(true);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("scrapeTables".equals(name)) {
                    total = restoreScrapeTables(reader, db, sourceIds);
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        }
        return total;
    }

    static Set<String> scrapeSourceIds(List<?> videoSources, ClassLoader classLoader) {
        Set<String> ids = new HashSet<>();
        if (videoSources == null) {
            return ids;
        }
        for (Object source : videoSources) {
            try {
                String id = VideoSourceCompat.getId(source).trim();
                if (id.isEmpty()) {
                    continue;
                }
                String type = VideoSourceCompat.getSourceType(source);
                boolean isScan = VideoSourceCompat.isScan(source);
                if (isLocalScrapeType(type, classLoader) || isScan) {
                    ids.add(id);
                }
            } catch (Throwable ignored) {
            }
        }
        if (ids.isEmpty()) {
            for (Object source : videoSources) {
                try {
                    String id = VideoSourceCompat.getId(source).trim();
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return ids;
    }

    private static boolean isLocalScrapeType(String type, ClassLoader classLoader) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        try {
            Class<?> companion = Reflect.findClass(
                    "com.attempt.afusekt.tools.ResourceType$Companion", classLoader);
            Object result = Reflect.callStaticMethod(companion, "a", type);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            String lower = type.toLowerCase();
            return lower.contains("webdav")
                    || lower.contains("alist")
                    || lower.contains("smb")
                    || lower.contains("local")
                    || lower.contains("onedrive")
                    || lower.contains("drive")
                    || lower.contains("aliyun")
                    || lower.contains("baidu");
        }
    }

    private static SQLiteDatabase openWritableDb(Context context, ClassLoader classLoader) {
        return AfusektCompat.openWritableDb(context, classLoader);
    }

    private static JSONArray dumpBySourceIds(
            SQLiteDatabase db,
            String table,
            Collection<String> sourceIds
    ) {
        JSONArray rows = new JSONArray();
        if (sourceIds == null || sourceIds.isEmpty()) {
            return rows;
        }
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[sourceIds.size()];
        int i = 0;
        for (String id : sourceIds) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i++] = id;
        }
        try (Cursor cursor = db.rawQuery(
                "SELECT * FROM " + table + " WHERE sourceId IN (" + placeholders + ")",
                args
        )) {
            appendCursor(rows, cursor);
        }
        return rows;
    }

    private static int writeTableRows(
            JsonWriter writer,
            SQLiteDatabase db,
            String table,
            Collection<String> sourceIds
    ) throws Exception {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return 0;
        }
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[sourceIds.size()];
        int i = 0;
        for (String id : sourceIds) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
            args[i++] = id;
        }
        int count = 0;
        try (Cursor cursor = db.rawQuery(
                "SELECT * FROM " + table + " WHERE sourceId IN (" + placeholders + ")",
                args
        )) {
            String[] columns = cursor.getColumnNames();
            while (cursor.moveToNext()) {
                writer.beginObject();
                for (int c = 0; c < columns.length; c++) {
                    int type = cursor.getType(c);
                    if (type == Cursor.FIELD_TYPE_BLOB) {
                        continue;
                    }
                    writer.name(columns[c]);
                    writeCursorValue(writer, cursor, c, type);
                }
                writer.endObject();
                count++;
            }
        }
        return count;
    }

    private static JSONArray dumpVideoInfo(SQLiteDatabase db, Set<String> mediaIds) {
        JSONArray rows = new JSONArray();
        if (mediaIds == null || mediaIds.isEmpty()) {
            return rows;
        }
        final int chunk = 200;
        String[] all = mediaIds.toArray(new String[0]);
        for (int start = 0; start < all.length; start += chunk) {
            int end = Math.min(start + chunk, all.length);
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[end - start];
            for (int i = start; i < end; i++) {
                if (i > start) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                args[i - start] = all[i];
            }
            try (Cursor cursor = db.rawQuery(
                    "SELECT * FROM VideoInfoTable WHERE id IN (" + placeholders + ")",
                    args
            )) {
                appendCursor(rows, cursor);
            }
        }
        return rows;
    }

    private static int writeVideoInfoRows(JsonWriter writer, SQLiteDatabase db, Set<String> mediaIds)
            throws Exception {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        final int chunk = 200;
        String[] all = mediaIds.toArray(new String[0]);
        for (int start = 0; start < all.length; start += chunk) {
            int end = Math.min(start + chunk, all.length);
            StringBuilder placeholders = new StringBuilder();
            String[] args = new String[end - start];
            for (int i = start; i < end; i++) {
                if (i > start) {
                    placeholders.append(',');
                }
                placeholders.append('?');
                args[i - start] = all[i];
            }
            try (Cursor cursor = db.rawQuery(
                    "SELECT * FROM VideoInfoTable WHERE id IN (" + placeholders + ")",
                    args
            )) {
                String[] columns = cursor.getColumnNames();
                while (cursor.moveToNext()) {
                    writer.beginObject();
                    for (int c = 0; c < columns.length; c++) {
                        int type = cursor.getType(c);
                        if (type == Cursor.FIELD_TYPE_BLOB) {
                            continue;
                        }
                        writer.name(columns[c]);
                        writeCursorValue(writer, cursor, c, type);
                    }
                    writer.endObject();
                    count++;
                }
            }
        }
        return count;
    }

    private static void writeCursorValue(JsonWriter writer, Cursor cursor, int index, int type)
            throws Exception {
        if (type == Cursor.FIELD_TYPE_NULL) {
            writer.nullValue();
        } else if (type == Cursor.FIELD_TYPE_INTEGER) {
            writer.value(cursor.getLong(index));
        } else if (type == Cursor.FIELD_TYPE_FLOAT) {
            writer.value(cursor.getDouble(index));
        } else {
            String value = cursor.getString(index);
            writer.value(value == null ? "" : value);
        }
    }

    private static Set<String> collectMediaIds(JSONArray movies, JSONArray tvs) {
        Set<String> ids = new HashSet<>();
        collectIdField(movies, "videoId", ids);
        collectIdField(tvs, "tvID", ids);
        collectIdField(tvs, "tvId", ids);
        return ids;
    }

    private static Set<String> collectMediaIdsFromDb(SQLiteDatabase db, Collection<String> sourceIds) {
        Set<String> ids = new HashSet<>();
        if (sourceIds == null) {
            return ids;
        }
        for (String sourceId : sourceIds) {
            collectIdColumn(db, "MovieData", "videoId", sourceId, ids);
            collectIdColumn(db, "TvData", "tvID", sourceId, ids);
            collectIdColumn(db, "TvData", "tvId", sourceId, ids);
        }
        return ids;
    }

    private static void collectIdColumn(
            SQLiteDatabase db,
            String table,
            String column,
            String sourceId,
            Set<String> out
    ) {
        try (Cursor cursor = db.rawQuery(
                "SELECT DISTINCT " + column + " FROM " + table
                        + " WHERE sourceId = ? AND " + column + " IS NOT NULL AND " + column + " != ''",
                new String[]{sourceId}
        )) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (id != null && !id.trim().isEmpty()) {
                    out.add(id.trim());
                }
            }
        } catch (Throwable t) {
            WebDavSyncConfig.log("collectMediaIds failed " + table + "." + column + ": " + t.getMessage());
        }
    }

    private static void collectIdField(JSONArray rows, String key, Set<String> out) {
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String id = row.optString(key, "").trim();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
    }

    private static void appendCursor(JSONArray rows, Cursor cursor) {
        String[] columns = cursor.getColumnNames();
        while (cursor.moveToNext()) {
            try {
                JSONObject row = new JSONObject();
                for (int c = 0; c < columns.length; c++) {
                    String name = columns[c];
                    int type = cursor.getType(c);
                    if (type == Cursor.FIELD_TYPE_NULL) {
                        row.put(name, JSONObject.NULL);
                    } else if (type == Cursor.FIELD_TYPE_INTEGER) {
                        row.put(name, cursor.getLong(c));
                    } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                        row.put(name, cursor.getDouble(c));
                    } else if (type == Cursor.FIELD_TYPE_BLOB) {
                        continue;
                    } else {
                        row.put(name, cursor.getString(c));
                    }
                }
                rows.put(row);
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteBySourceIds(SQLiteDatabase db, Set<String> sourceIds) {
        for (String sourceId : sourceIds) {
            db.execSQL("DELETE FROM MovieData WHERE sourceId = ?", new Object[]{sourceId});
            db.execSQL("DELETE FROM TvData WHERE sourceId = ?", new Object[]{sourceId});
        }
    }

    private static int restoreScrapeTables(JsonReader reader, SQLiteDatabase db, Set<String> sourceIds)
            throws Exception {
        reader.beginObject();
        int movies = 0;
        int tvs = 0;
        int infos = 0;
        boolean deleted = false;

        db.beginTransaction();
        try {
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("sourceIds".equals(name)) {
                    JSONArray backupIds = readStringArray(reader);
                    for (int i = 0; i < backupIds.length(); i++) {
                        String id = backupIds.optString(i, "").trim();
                        if (!id.isEmpty()) {
                            sourceIds.add(id);
                        }
                    }
                    if (!deleted) {
                        deleteBySourceIds(db, sourceIds);
                        deleted = true;
                    }
                } else if ("MovieData".equals(name)) {
                    if (!deleted) {
                        deleteBySourceIds(db, sourceIds);
                        deleted = true;
                    }
                    movies = restoreTableStreaming(reader, db, "MovieData", true);
                } else if ("TvData".equals(name)) {
                    tvs = restoreTableStreaming(reader, db, "TvData", true);
                } else if ("VideoInfoTable".equals(name)) {
                    infos = restoreTableStreaming(reader, db, "VideoInfoTable", false);
                } else {
                    reader.skipValue();
                }
            }
            WebDavSyncConfig.log("scrape stream restore movies=" + movies + " tvs=" + tvs + " info=" + infos);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        reader.endObject();
        return movies + tvs + infos;
    }

    private static JSONArray readStringArray(JsonReader reader) throws Exception {
        JSONArray array = new JSONArray();
        reader.beginArray();
        while (reader.hasNext()) {
            array.put(reader.nextString());
        }
        reader.endArray();
        return array;
    }

    private static int restoreTableStreaming(
            JsonReader reader,
            SQLiteDatabase db,
            String table,
            boolean skipId
    ) throws Exception {
        reader.beginArray();
        int inserted = 0;
        while (reader.hasNext()) {
            reader.beginObject();
            ContentValues values = new ContentValues();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (skipId && "id".equals(key)) {
                    reader.skipValue();
                    continue;
                }
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull();
                    values.putNull(key);
                    continue;
                }
                if (reader.peek() == JsonToken.BOOLEAN) {
                    values.put(key, reader.nextBoolean() ? 1 : 0);
                } else if (reader.peek() == JsonToken.NUMBER) {
                    String num = reader.nextString();
                    if (num.contains(".") || num.contains("e") || num.contains("E")) {
                        values.put(key, Double.parseDouble(num));
                    } else {
                        try {
                            values.put(key, Long.parseLong(num));
                        } catch (NumberFormatException e) {
                            values.put(key, num);
                        }
                    }
                } else {
                    values.put(key, reader.nextString());
                }
            }
            reader.endObject();
            long result = db.insertWithOnConflict(
                    table,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            if (result != -1L) {
                inserted++;
            }
        }
        reader.endArray();
        return inserted;
    }

    private static int restoreTable(
            SQLiteDatabase db,
            String table,
            JSONArray rows,
            boolean skipId
    ) throws Exception {
        if (rows == null || rows.length() == 0) {
            return 0;
        }
        int inserted = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ContentValues values = new ContentValues();
            Iterator<String> keys = row.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (skipId && "id".equals(key)) {
                    continue;
                }
                if (row.isNull(key)) {
                    values.putNull(key);
                    continue;
                }
                Object value = row.get(key);
                if (value instanceof Boolean) {
                    values.put(key, (Boolean) value ? 1 : 0);
                } else if (value instanceof Integer) {
                    values.put(key, (Integer) value);
                } else if (value instanceof Long) {
                    values.put(key, (Long) value);
                } else if (value instanceof Double) {
                    values.put(key, (Double) value);
                } else if (value instanceof Float) {
                    values.put(key, (Float) value);
                } else {
                    values.put(key, String.valueOf(value));
                }
            }
            long result = db.insertWithOnConflict(
                    table,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            if (result != -1L) {
                inserted++;
            }
        }
        return inserted;
    }

    private static JSONArray toJsonArray(Collection<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) {
                array.put(value);
            }
        }
        return array;
    }
}
