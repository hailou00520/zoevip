package com.afusekt.lsp.sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.afusekt.lsp.util.Reflect;

import java.io.File;

/** Afusekt version-specific reflection (3.2.x uses ax6/MMKV + Lyn.a). */
public final class AfusektCompat {

    private AfusektCompat() {
    }

    public static String readAccount(Context context, ClassLoader classLoader) {
        try {
            Class<?> mi9 = Class.forName("mi9", false, classLoader);
            Object ax6 = Reflect.callStaticMethod(mi9, "a");
            Object account = Reflect.callMethod(ax6, "k", context, "account", "");
            return account == null ? "" : String.valueOf(account);
        } catch (Throwable ax6Error) {
            WebDavSyncConfig.log("readAccount ax6 failed: " + ax6Error.getMessage());
        }
        try {
            Class<?> spUtilCompanion = Reflect.findClass(
                    "com.attempt.afusekt.tools.SpUtil$Companion", classLoader);
            Object spUtil = Reflect.callStaticMethod(spUtilCompanion, "a");
            Object account = Reflect.callMethod(spUtil, "k", context, "account", "");
            return account == null ? "" : String.valueOf(account);
        } catch (Throwable spUtilError) {
            WebDavSyncConfig.log("readAccount fallback failed: " + spUtilError.getMessage());
            return "";
        }
    }

    public static Object getAppDatabase(Context context, ClassLoader classLoader) {
        Class<?> appDatabaseClass = Reflect.findClass(
                "com.attempt.afusekt.AppDatabase", classLoader);
        Object companion = Reflect.getStaticObjectField(appDatabaseClass, "l");
        try {
            return Reflect.callMethod(companion, "a", context);
        } catch (Throwable ignored) {
            return Reflect.callMethod(companion, "b", context);
        }
    }

    public static SQLiteDatabase openWritableDb(Context context, ClassLoader classLoader) {
        try {
            Object database = getAppDatabase(context, classLoader);
            Object openHelper = Reflect.callMethod(database, "i");
            Object supportDb = callFirst(openHelper, "W", "g2");
            if (supportDb != null) {
                SQLiteDatabase raw = unwrapSqlite(supportDb);
                if (raw != null) {
                    return raw;
                }
            }
        } catch (Throwable t) {
            WebDavSyncConfig.log("room unwrap failed, fallback path: " + t.getMessage());
        }
        File path = context.getDatabasePath("AppDatabase");
        return SQLiteDatabase.openDatabase(
                path.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READWRITE
        );
    }

    private static Object callFirst(Object target, String... names) {
        for (String name : names) {
            try {
                return Reflect.callMethod(target, name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static SQLiteDatabase unwrapSqlite(Object supportDb) {
        for (String field : new String[]{"d", "a", "delegate", "mDelegate"}) {
            try {
                Object raw = Reflect.getObjectField(supportDb, field);
                if (raw instanceof SQLiteDatabase) {
                    return (SQLiteDatabase) raw;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
