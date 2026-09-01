package com.afusekt.lsp.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.hook.WebDavSyncHooks;
import com.afusekt.lsp.util.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebDavLibrarySync {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private WebDavLibrarySync() {
    }

    public static void notifyOfficialSync(Context context, ClassLoader classLoader) {
        toast(context, classLoader, "WebDAV 同步未启用，使用官方云端同步");
    }

    public static void upload(Context context, List<?> sources, Object dialog, ClassLoader classLoader) {
        WebDavSyncConfig.log("upload requested, sources=" + (sources == null ? 0 : sources.size()));
        if (!WebDavSyncConfig.isConfigured(context)) {
            toast(context, classLoader, "WebDAV：请先在 Afusekt Unlock 模块中配置并启用");
            dismiss(dialog);
            return;
        }
        if (sources == null || sources.isEmpty()) {
            toast(context, classLoader, "WebDAV：资源库是空的，先添加一个再上传");
            dismiss(dialog);
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            toast(context, classLoader, "WebDAV：同步进行中，请稍候");
            return;
        }
        WebDavSyncHooks.WEBDAV_ACTIVE.set(true);
        showDialog(dialog);

        new Thread(() -> {
            File backupTemp = null;
            try {
                String account = readAccount(context, classLoader);
                String username = WebDavSyncConfig.getUsername(context);
                String password = WebDavSyncConfig.getPassword(context);
                String url = WebDavSyncConfig.buildRemoteUrl(context);

                toast(context, classLoader, "WebDAV 正在打包同步文件...");
                backupTemp = File.createTempFile("afusekt-webdav-", ".json", context.getCacheDir());
                ScrapeTableSync.WriteStats stats = LibraryJsonCodec.writeToFile(
                        backupTemp, account, sources, context, classLoader);

                WebDavSyncConfig.log("PUT " + url + " bytes=" + backupTemp.length());
                WebDavHttpClient.Response response = WebDavHttpClient.putFile(
                        url, username, password, backupTemp);

                if (response.isSuccess()) {
                    toast(context, classLoader,
                            "WebDAV 上传完成：" + sources.size() + " 个库，电影刮削 "
                                    + stats.movies + " / 剧集 " + stats.tvs
                                    + " / 详情 " + stats.infos);
                } else {
                    toast(context, classLoader, "WebDAV 上传失败：HTTP " + response.code
                            + (response.body == null || response.body.isEmpty() ? "" : " " + response.body));
                }
                WebDavSyncConfig.log("upload HTTP " + response.code
                        + " movies=" + stats.movies + " tvs=" + stats.tvs + " info=" + stats.infos);
            } catch (Throwable t) {
                WebDavSyncConfig.log("upload failed: " + t);
                toast(context, classLoader, "WebDAV 上传失败：" + t.getMessage());
            } finally {
                if (backupTemp != null && backupTemp.exists()) {
                    backupTemp.delete();
                }
                dismiss(dialog);
                finishActive();
            }
        }, "afusekt-webdav-upload").start();
    }

    public static void download(Object fragment, ClassLoader classLoader) {
        Context context = resolveFragmentContext(fragment);
        if (context == null) {
            WebDavSyncConfig.log("download skipped: no context");
            return;
        }
        download(context, classLoader, fragment);
    }

    private static void download(Context context, ClassLoader classLoader, Object fragment) {
        WebDavSyncConfig.log("download requested");
        if (!WebDavSyncConfig.isConfigured(context)) {
            toast(context, classLoader, "WebDAV：请先在 Afusekt Unlock 模块中配置并启用");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            toast(context, classLoader, "WebDAV：同步进行中，请稍候");
            return;
        }
        WebDavSyncHooks.WEBDAV_ACTIVE.set(true);
        toast(context, classLoader, "WebDAV 正在获取资源库...");

        new Thread(() -> {
            File backupTemp = null;
            File shardTemp = null;
            try {
                String username = WebDavSyncConfig.getUsername(context);
                String password = WebDavSyncConfig.getPassword(context);
                String url = WebDavSyncConfig.buildRemoteUrl(context);
                WebDavSyncConfig.log("GET " + url);

                backupTemp = File.createTempFile("afusekt-webdav-", ".json", context.getCacheDir());
                WebDavHttpClient.Response response = WebDavHttpClient.getToFile(
                        url, username, password, backupTemp);
                if (response.code == 404) {
                    toast(context, classLoader, "WebDAV：远端还没有同步文件，请先上传");
                    return;
                }
                if (!response.isSuccess()) {
                    toast(context, classLoader, "WebDAV 下载失败：HTTP " + response.code
                            + (response.body == null || response.body.isEmpty() ? "" : " " + response.body));
                    return;
                }
                if (backupTemp.length() == 0L) {
                    toast(context, classLoader, "WebDAV：同步文件为空");
                    return;
                }

                List<Object> sources = LibraryJsonCodec.decodeVideoSourcesFromFile(backupTemp, classLoader);
                if (sources.isEmpty()) {
                    toast(context, classLoader, "WebDAV：同步文件里没有资源库");
                    return;
                }

                insertSources(context, sources, classLoader);
                int scrapeCount;
                if (isShardManifestFile(backupTemp)) {
                    scrapeCount = 0;
                    String manifestJson = readTextFile(backupTemp);
                    org.json.JSONObject root = new org.json.JSONObject(manifestJson);
                    List<String> sourceIds = ScrapeShardSync.allSourceIds(sources);
                    for (int i = 0; i < sourceIds.size(); i++) {
                        String sourceId = sourceIds.get(i);
                        toast(context, classLoader,
                                "WebDAV 恢复刮削 " + (i + 1) + "/" + sourceIds.size() + "...");
                        String shardUrl = WebDavSyncConfig.buildScrapeShardUrl(context, sourceId);
                        shardTemp = File.createTempFile("afusekt-scrape-", ".json", context.getCacheDir());
                        WebDavHttpClient.Response shardResponse = WebDavHttpClient.getToFile(
                                shardUrl, username, password, shardTemp);
                        if (shardResponse.code == 404) {
                            WebDavSyncConfig.log("scrape shard missing: " + sourceId);
                            shardTemp.delete();
                            shardTemp = null;
                            continue;
                        }
                        if (!shardResponse.isSuccess()) {
                            toast(context, classLoader, "WebDAV 刮削下载失败：" + sourceId
                                    + " HTTP " + shardResponse.code);
                            return;
                        }
                        scrapeCount += ScrapeShardSync.restoreFromFile(
                                context, classLoader, sourceId, shardTemp);
                        shardTemp.delete();
                        shardTemp = null;
                    }
                    if (scrapeCount == 0 && root.optInt("scrapeShardCount", 0) > 0) {
                        scrapeCount = root.optInt("scrapeMovieCount", 0)
                                + root.optInt("scrapeTvCount", 0)
                                + root.optInt("scrapeInfoCount", 0);
                    }
                } else {
                    scrapeCount = ScrapeTableSync.restoreFromBackupFile(
                            backupTemp, context, classLoader, sources);
                }
                refreshLibraryUi(fragment);
                final String message;
                if (scrapeCount < 0) {
                    message = "WebDAV 已恢复 " + sources.size() + " 个资源库；旧同步文件不含刮削，需重新上传";
                } else {
                    message = "WebDAV 下载完成：" + sources.size() + " 个资源库，刮削 " + scrapeCount + " 条";
                }
                toast(context, classLoader, message);
                WebDavSyncConfig.log("pull imported " + sources.size() + " sources, scrape=" + scrapeCount);
            } catch (Throwable t) {
                WebDavSyncConfig.log("pull failed: " + t);
                toast(context, classLoader, "WebDAV 下载失败：" + t.getMessage());
            } finally {
                if (backupTemp != null && backupTemp.exists()) {
                    backupTemp.delete();
                }
                if (shardTemp != null && shardTemp.exists()) {
                    shardTemp.delete();
                }
                finishActive();
            }
        }, "afusekt-webdav-download").start();
    }

    private static String readTextFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = fis.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean isShardManifestFile(File file) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.InputStreamReader isr = new java.io.InputStreamReader(fis, StandardCharsets.UTF_8);
             android.util.JsonReader reader = new android.util.JsonReader(isr)) {
            reader.setLenient(true);
            reader.beginObject();
            boolean shardMode = false;
            int scrapeVersion = 0;
            boolean hasScrapeTables = false;
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("scrapeMode".equals(name)) {
                    shardMode = ScrapeShardSync.SCRAPE_MODE.equals(reader.nextString());
                } else if ("scrapeVersion".equals(name)) {
                    scrapeVersion = reader.nextInt();
                } else if ("scrapeTables".equals(name)) {
                    hasScrapeTables = true;
                    reader.skipValue();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            return shardMode || (scrapeVersion >= ScrapeShardSync.SCRAPE_VERSION && !hasScrapeTables);
        } catch (Throwable t) {
            WebDavSyncConfig.log("shard manifest probe failed: " + t.getMessage());
            return false;
        }
    }

    private static Context resolveFragmentContext(Object fragment) {
        if (fragment == null) {
            return null;
        }
        try {
            Object ctx = fragment.getClass().getMethod("requireContext").invoke(fragment);
            if (ctx instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object ctx = fragment.getClass().getMethod("getContext").invoke(fragment);
            if (ctx instanceof Context context) {
                return context;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void refreshLibraryUi(Object fragment) {
        if (fragment == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                Reflect.callMethod(fragment, "q");
            } catch (Throwable ignored) {
            }
        });
    }

    private static void finishActive() {
        MAIN.postDelayed(() -> {
            WEBDAV_ACTIVE_OFF();
            BUSY.set(false);
        }, 1500);
    }

    private static void WEBDAV_ACTIVE_OFF() {
        WebDavSyncHooks.WEBDAV_ACTIVE.set(false);
    }

    private static void insertSources(Context context, List<Object> sources, ClassLoader classLoader) {
        Object database = AfusektCompat.getAppDatabase(context, classLoader);
        Object dao = Reflect.callMethod(database, "G");
        try {
            Reflect.callMethod(dao, "j", sources);
        } catch (Throwable ignored) {
            for (Object source : sources) {
                Reflect.callMethod(dao, "i", source);
            }
        }
    }

    private static String readAccount(Context context, ClassLoader classLoader) {
        return AfusektCompat.readAccount(context, classLoader);
    }

    private static void toast(Context context, ClassLoader classLoader, String message) {
        MAIN.post(() -> {
            boolean shown = false;
            try {
                Class<?> outer = Class.forName("com.attempt.afusekt.tools.SystemTool", false, classLoader);
                Field companionField = outer.getDeclaredField("a");
                companionField.setAccessible(true);
                Object companion = companionField.get(null);
                Method toastMethod = companion.getClass().getDeclaredMethod("K", Context.class, String.class);
                toastMethod.setAccessible(true);
                toastMethod.invoke(null, context, message);
                shown = true;
            } catch (Throwable t) {
                WebDavSyncConfig.log("SystemTool toast failed: " + t.getMessage());
            }
            if (!shown) {
                try {
                    Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Log.e(MainHook.TAG, "toast fallback failed: " + t.getMessage());
                }
            }
        });
    }

    private static void showDialog(Object dialog) {
        if (dialog == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                Reflect.callMethod(dialog, "show");
            } catch (Throwable ignored) {
            }
        });
    }

    private static void dismiss(Object dialog) {
        if (dialog == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                Reflect.callMethod(dialog, "dismiss");
            } catch (Throwable ignored) {
            }
        });
    }
}
