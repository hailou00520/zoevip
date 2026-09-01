package com.afusekt.lsp.sync;

import android.os.Bundle;

/** Saved WebDAV settings snapshot for UI and cache. */
public final class WebDavConfigSnapshot {

    public final boolean enabled;
    public final String baseUrl;
    public final String username;
    public final String password;
    public final String remotePath;

    public WebDavConfigSnapshot(
            boolean enabled,
            String baseUrl,
            String username,
            String password,
            String remotePath
    ) {
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.remotePath = remotePath == null || remotePath.trim().isEmpty()
                ? WebDavSyncConfig.DEFAULT_REMOTE_PATH
                : remotePath.trim();
    }

    static WebDavConfigSnapshot empty() {
        return new WebDavConfigSnapshot(false, "", "", "", WebDavSyncConfig.DEFAULT_REMOTE_PATH);
    }

    static WebDavConfigSnapshot fromStore(WebDavConfigStore store) {
        return new WebDavConfigSnapshot(
                store.isEnabled(),
                store.getBaseUrl(),
                store.getUsername(),
                store.getPassword(),
                store.getRemotePath()
        );
    }

    public boolean isConfigured() {
        return enabled
                && !baseUrl.trim().isEmpty()
                && !username.trim().isEmpty();
    }

    Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(WebDavPrefs.KEY_ENABLED, enabled);
        bundle.putString(WebDavPrefs.KEY_BASE_URL, baseUrl);
        bundle.putString(WebDavPrefs.KEY_USERNAME, username);
        bundle.putString(WebDavPrefs.KEY_PASSWORD, password);
        bundle.putString(WebDavPrefs.KEY_REMOTE_PATH, remotePath);
        return bundle;
    }
}
