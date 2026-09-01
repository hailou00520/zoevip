package com.afusekt.lsp.hook;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.sync.WebDavConfigReceiver;
import com.afusekt.lsp.sync.WebDavLibrarySync;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class WebDavSyncHooks {

    private static final String FRAGMENT =
            "com.attempt.afusekt.mainView.fragments.localLibraryFragment.VideoLibraryFragment";
    private static final String ORDER_TOOLS_COMPANION = "com.attempt.afusekt.tools.OrderUserTools$Companion";

    /** Tracks whether WebDAV redirect is active for the current sync action. */
    public static final AtomicBoolean WEBDAV_CONFIGURED = new AtomicBoolean(false);

    /** True while a WebDAV sync is active; used to drop overlapping official toasts. */
    public static final AtomicBoolean WEBDAV_ACTIVE = new AtomicBoolean(false);

    private WebDavSyncHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        ModuleVisibilityHook.apply(cl);
        WebDavProviderHook.apply(cl);
        ToastSuppressHook.apply(cl);
        hookConfigReceiver(cl);
        hookActivityResume(cl);
        hookSyncMenuGate(cl);
        hookUploadCompanionRedirect(cl);
        hookDownloadRedirect(cl);
        hookSyncSettingGate(cl);
        XposedBridge.log(MainHook.TAG + ": WebDAV sync hooks installed");
    }

    static boolean shouldRedirect(Context context) {
        boolean configured = WebDavSyncConfig.resolveForSync(context);
        WEBDAV_CONFIGURED.set(configured);
        return configured;
    }

    static boolean isBlockedToast(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        if (message.startsWith("WebDAV")) {
            return false;
        }
        if (message.contains("无权限")
                || message.contains("無權限")
                || message.equalsIgnoreCase("No Permission")) {
            return WEBDAV_CONFIGURED.get() || WEBDAV_ACTIVE.get();
        }
        if (!WEBDAV_ACTIVE.get()) {
            return false;
        }
        return message.contains("成功上传")
                || message.contains("资源库")
                || message.contains("同步资源库未开启")
                || "操作成功".equals(message)
                || "error".equalsIgnoreCase(message);
    }

    private static void hookConfigReceiver(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (!MainHook.AFUSEKT_PACKAGE.equals(app.getPackageName())) {
                        return;
                    }
                    WebDavConfigReceiver.register(app);
                    requestConfigFromModule(app);
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> WebDavSyncConfig.refreshForSync(app),
                            300
                    );
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": config receiver hook failed: " + t.getMessage());
        }
    }

    private static void hookActivityResume(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!MainHook.AFUSEKT_PACKAGE.equals(activity.getPackageName())) {
                        return;
                    }
                    WebDavSyncConfig.refreshForSync(activity);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": activity resume hook failed: " + t.getMessage());
        }
    }

    private static void requestConfigFromModule(Context context) {
        WebDavSyncConfig.refreshForSync(context);
        WebDavSyncConfig.logConfigSnapshot(context);
        XposedBridge.log(MainHook.TAG + ": config request broadcast sent");
    }

    private static void hookSyncMenuGate(ClassLoader classLoader) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(FRAGMENT, classLoader);
            XposedHelpers.findAndHookMethod(
                    fragmentClass,
                    "addLibrary",
                    Context.class,
                    android.view.MenuItem.class,
                    java.util.List.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            AfusektHooks.ensureProFlags(classLoader);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": sync menu gate hook failed: " + t.getMessage());
        }
    }

    private static void hookUploadCompanionRedirect(ClassLoader classLoader) {
        try {
            Class<?> companion = XposedHelpers.findClass(ORDER_TOOLS_COMPANION, classLoader);
            Class<?> alertDialogClass = XposedHelpers.findClass("androidx.appcompat.app.AlertDialog", classLoader);
            XposedHelpers.findAndHookMethod(
                    companion,
                    "c",
                    Context.class,
                    List.class,
                    alertDialogClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (!shouldRedirect(context)) {
                                WebDavSyncConfig.logConfigSnapshot(context);
                                WebDavLibrarySync.notifyOfficialSync(context, classLoader);
                                XposedBridge.log(MainHook.TAG + ": keep official upload path");
                                return;
                            }
                            AfusektHooks.ensureProFlags(classLoader);
                            XposedBridge.log(MainHook.TAG + ": redirect OrderUserTools.c upload");
                            param.setResult(null);
                            @SuppressWarnings("unchecked")
                            List<?> sources = (List<?>) param.args[1];
                            WebDavLibrarySync.upload(context, sources, param.args[2], classLoader);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": upload hook failed: " + t.getMessage());
        }
    }

    private static void hookDownloadRedirect(ClassLoader classLoader) {
        try {
            Class<?> fragmentClass = XposedHelpers.findClass(FRAGMENT, classLoader);
            XposedHelpers.findAndHookMethod(fragmentClass, "getVideoSource", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Context context = (Context) XposedHelpers.callMethod(param.thisObject, "requireContext");
                    if (!shouldRedirect(context)) {
                        WebDavSyncConfig.logConfigSnapshot(context);
                        WebDavLibrarySync.notifyOfficialSync(context, classLoader);
                        return;
                    }
                    AfusektHooks.ensureProFlags(classLoader);
                    XposedBridge.log(MainHook.TAG + ": redirect getVideoSource download");
                    param.setResult(null);
                    WebDavLibrarySync.download(param.thisObject, classLoader);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": download hook failed: " + t.getMessage());
        }
    }

    private static void hookSyncSettingGate(ClassLoader classLoader) {
        try {
            Class<?> spUtil = XposedHelpers.findClass("com.attempt.afusekt.tools.SpUtil", classLoader);
            XposedHelpers.findAndHookMethod(spUtil, "d", Context.class, String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[1];
                    if (!WebDavSyncConfig.AFUSEKT_SYNC_SETTING_KEY.equals(key)) {
                        return;
                    }
                    Context context = (Context) param.args[0];
                    if (WebDavSyncConfig.isConfigured(context)) {
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": sync setting hook failed: " + t.getMessage());
        }
    }
}
