package com.afusekt.lsp.sync;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public final class WebDavConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zoevip.lsp.webdav";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD_GET_CONFIG = "getConfig";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET_CONFIG.equals(method) || getContext() == null) {
            return null;
        }
        var prefs = WebDavPrefs.openModule(getContext());
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
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
