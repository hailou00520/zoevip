package com.afusekt.lsp.sync;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-source scrape shards — stream read/write to include full metadata without OOM.
 */
public final class ScrapeShardSync {

    public static final int SCRAPE_VERSION = 3;
    public static final String SCRAPE_MODE = "per-source";

    public static final class Stats {
        public final int movies;
        public final int tvs;
        public final int infos;

        Stats(int movies, int tvs, int infos) {
            this.movies = movies;
            this.tvs = tvs;
            this.infos = infos;
        }

        public int total() {
            return movies + tvs + infos;
        }
    }

    private ScrapeShardSync() {
    }

    public static List<String> allSourceIds(List<?> videoSources) {
        ArrayList<String> ids = new ArrayList<>();
        if (videoSources == null) {
            return ids;
        }
        for (Object source : videoSources) {
            String id = VideoSourceCompat.getId(source).trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static Stats writeSourceToFile(
            Context context,
            ClassLoader classLoader,
            String sourceId,
            File outFile
    ) throws Exception {
        SQLiteDatabase db = AfusektCompat.openWritableDb(context, classLoader);
        Set<String> mediaIds = collectMediaIds(db, sourceId);
        try (FileOutputStream fos = new FileOutputStream(outFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             JsonWriter writer = new JsonWriter(osw)) {
            writer.setLenient(true);
            writer.beginObject();
            writer.name("sourceId").value(sourceId);
            writer.name("scrapeVersion").value(SCRAPE_VERSION);
            int movies = writeTable(writer, "MovieData", db, sourceId);
            int tvs = writeTable(writer, "TvData", db, sourceId);
            int infos = writeVideoInfo(writer, db, mediaIds);
            writer.endObject();
            writer.flush();
            WebDavSyncConfig.log("scrape shard " + sourceId + " movies=" + movies
                    + " tvs=" + tvs + " info=" + infos);
            return new Stats(movies, tvs, infos);
        }
    }

    public static int restoreFromFile(
            Context context,
            ClassLoader classLoader,
            String sourceId,
            File inFile
    ) throws Exception {
        SQLiteDatabase db = AfusektCompat.openWritableDb(context, classLoader);
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM MovieData WHERE sourceId = ?", new Object[]{sourceId});
            db.execSQL("DELETE FROM TvData WHERE sourceId = ?", new Object[]{sourceId});
            int total = 0;
            try (FileInputStream fis = new FileInputStream(inFile);
                 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
                 JsonReader reader = new JsonReader(isr)) {
                reader.setLenient(true);
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("MovieData".equals(name)) {
                        total += restoreTable(reader, db, "MovieData", true);
                    } else if ("TvData".equals(name)) {
                        total += restoreTable(reader, db, "TvData", true);
                    } else if ("VideoInfoTable".equals(name)) {
                        total += restoreTable(reader, db, "VideoInfoTable", false);
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
            db.setTransactionSuccessful();
            WebDavSyncConfig.log("scrape shard restored " + sourceId + " rows=" + total);
            return total;
        } finally {
            db.endTransaction();
        }
    }

    private static int writeTable(JsonWriter writer, String table, SQLiteDatabase db, String sourceId)
            throws Exception {
        writer.name(table).beginArray();
        int count = 0;
        try (Cursor cursor = db.rawQuery(
                "SELECT * FROM " + table + " WHERE sourceId = ?",
                new String[]{sourceId}
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
                    writeValue(writer, cursor, c, type);
                }
                writer.endObject();
                count++;
            }
        }
        writer.endArray();
        return count;
    }

    private static int writeVideoInfo(JsonWriter writer, SQLiteDatabase db, Set<String> mediaIds)
            throws Exception {
        writer.name("VideoInfoTable").beginArray();
        if (mediaIds == null || mediaIds.isEmpty()) {
            writer.endArray();
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
                        writeValue(writer, cursor, c, type);
                    }
                    writer.endObject();
                    count++;
                }
            }
        }
        writer.endArray();
        return count;
    }

    private static void writeValue(JsonWriter writer, Cursor cursor, int index, int type)
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

    private static Set<String> collectMediaIds(SQLiteDatabase db, String sourceId) {
        Set<String> ids = new HashSet<>();
        collectIdColumn(db, "MovieData", "videoId", sourceId, ids);
        collectIdColumn(db, "TvData", "tvID", sourceId, ids);
        collectIdColumn(db, "TvData", "tvId", sourceId, ids);
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

    private static int restoreTable(JsonReader reader, SQLiteDatabase db, String table, boolean skipId)
            throws Exception {
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
}
