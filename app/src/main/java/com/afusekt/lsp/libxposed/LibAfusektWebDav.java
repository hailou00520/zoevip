package com.afusekt.lsp.libxposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.ZoeIds;
import com.afusekt.lsp.ZoeModule;
import com.afusekt.lsp.hook.WebDavSyncHooks;
import com.afusekt.lsp.sync.WebDavConfigProvider;
import com.afusekt.lsp.sync.WebDavConfigReceiver;
import com.afusekt.lsp.sync.WebDavLibrarySync;
import com.afusekt.lsp.sync.WebDavSyncConfig;
import com.afusekt.lsp.ui.WebDavSettingsUi;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Afusekt WebDAV 适配（libxposed 移植版）。
 *
 * <p>3.3.x 切换纯 libxposed 路径后，WebDAV 设置行/弹窗与同步重定向钩子全部失联
 * （ZoeModule 只调用了 LibAfusektHooks，WebDavSyncHooks/WebDavSettingsUiHooks 无人安装）。
 * 本类把整套 WebDAV 钩子用 {@code module.hook} + 反射重装，并把「WebDAV 同步」设置行
 * 按 Afusekt 3.2.4 设置页的真实布局（16dp 卡片边距 / 24dp 图标 / 16dp 文本间距 / 圆角卡片）
 * 逐项克隆参照行，避免硬编码造成的布局不适配。</p>
 *
 * <p>定位锚点：3.2.4 中 {@code app_sync_library_box} 已无代码引用，改为按
 * 行标题文本「同步资源库」定位并向上回溯视图层级。</p>
 */
public final class LibAfusektWebDav {

    private static final String GENERAL_SETTING =
            "com.attempt.afusekt.mainView.activity.GeneralSettingView";
    private static final String FRAGMENT =
            "com.attempt.afusekt.mainView.fragments.localLibraryFragment.VideoLibraryFragment";
    private static final String ORDER_TOOLS_COMPANION =
            "com.attempt.afusekt.tools.OrderUserTools$Companion";
    private static final String SP_UTIL = "com.attempt.afusekt.tools.SpUtil";
  /** v3.2.x MMKV prefs (replaces SpUtil for sync-setting gate). */
    private static final String MMKV_PREFS = "ax6";
    private static final String SYSTEM_TOOL_COMPANION =
            "com.attempt.afusekt.tools.SystemTool$Companion";
    private static final String PACKAGE_MANAGER = "android.app.ApplicationPackageManager";

    private static final String ROW_TAG = "zoevip_webdav_row";
    private static final String SUBTITLE_TAG = "zoevip_webdav_subtitle";
    private static final String SYNC_ROW_TITLE = "同步资源库";

    private static final long[] ROW_RETRY_DELAYS_MS = {0L, 300L, 800L, 1500L, 3000L, 5000L, 8000L};

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private LibAfusektWebDav() {
    }

    /** Called from {@code LibAfusektHooks.installLate} (onPackageReady). */
    public static void installLate(
            ZoeModule module,
            XposedModuleInterface.PackageReadyParam param
    ) {
        ClassLoader cl = param.getClassLoader();
        installVisibility(module, cl);
        installToastSuppress(module, cl);
        installProviderHook(module);
        installConfigReceiver(module);
        installActivityResume(module);
        installSyncRedirects(module, cl);
        installRowHook(module, cl);
        module.log(4, ZoeIds.TAG, "Afusekt WebDAV hooks installed (libxposed)");
    }

    // ---------------------------------------------------------------- UI row

    private static void installRowHook(ZoeModule module, ClassLoader cl) {
        Class<?> cls = findClass(cl, GENERAL_SETTING);
        if (cls == null) {
            module.log(5, ZoeIds.TAG, "GeneralSettingView missing; WebDAV row hook skipped");
            return;
        }
        // 3.2.4 混淆后的方法名：P=setupAppSettingRows, O=initViews（旧版本为可读名）
        for (String name : new String[]{"P", "setupAppSettingRows", "O", "initViews"}) {
            Method method = findMethod(cls, name, 0);
            if (method == null) {
                continue;
            }
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            ensureRowLater((Activity) self, module, 0);
                        }
                        return result;
                    });
            module.log(4, ZoeIds.TAG, "GeneralSettingView." + name + " hooked for WebDAV row");
            return;
        }
        module.log(5, ZoeIds.TAG, "GeneralSettingView row builder not found");
    }

    private static void ensureRowLater(Activity activity, ZoeModule module, int attempt) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        boolean ok = false;
        try {
            ok = injectRow(activity, module);
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "WebDAV settings row failed: " + t.getMessage());
        }
        if (ok) {
            return;
        }
        if (attempt >= ROW_RETRY_DELAYS_MS.length - 1) {
            module.log(6, ZoeIds.TAG, "WebDAV settings row retries exhausted");
            return;
        }
        long delay = ROW_RETRY_DELAYS_MS[attempt + 1];
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> ensureRowLater(activity, module, attempt + 1),
                delay
        );
    }

    /**
     * 注入「WebDAV 同步」行：插在「同步资源库」下方。
     * 高度必须 WRAP_CONTENT —— 之前用 MATCH_PARENT 会撑出大空白 + 图标漂浮。
     */
    private static boolean injectRow(Activity activity, ZoeModule module) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup root)) {
            return false;
        }

        // 清掉历史错误注入（大空白行）
        removeTaggedViews(root, ROW_TAG);

        ViewGroup rowHost = resolveSyncRowHost(activity, root);
        if (rowHost == null) {
            return false;
        }
        ViewGroup section = rowHost.getParent() instanceof ViewGroup
                ? (ViewGroup) rowHost.getParent()
                : null;
        if (section == null) {
            return false;
        }
        int insertIndex = section.indexOfChild(rowHost);
        if (insertIndex < 0) {
            return false;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int padH = Math.round(16f * density);
        int padV = Math.round(14f * density);
        int iconSize = Math.round(24f * density);
        int textGap = Math.round(16f * density);

        // 参照行样式
        List<TextView> refTexts = collectTextViews(rowHost);
        ImageView iconRef = findFirstImageView(rowHost);

        LinearLayout row = new LinearLayout(activity);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padH, padV, padH, padV);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("WebDAV 同步");
        row.setOnClickListener(v -> WebDavSettingsUi.show(activity));
        if (rowHost.getBackground() != null
                && rowHost.getBackground().getConstantState() != null) {
            row.setBackground(rowHost.getBackground().getConstantState().newDrawable());
        } else {
            View cardLike = findFirstCardLike(rowHost);
            if (cardLike != null && cardLike.getBackground() != null
                    && cardLike.getBackground().getConstantState() != null) {
                row.setBackground(cardLike.getBackground().getConstantState().newDrawable());
            }
        }

        ViewGroup.MarginLayoutParams rowLp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        if (rowHost.getLayoutParams() instanceof ViewGroup.MarginLayoutParams src) {
            rowLp.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
        } else {
            int m = Math.round(8f * density);
            rowLp.setMargins(m, m / 2, m, m / 2);
        }
        row.setLayoutParams(rowLp);

        ImageView icon = new ImageView(activity);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        int cloudId = activity.getResources().getIdentifier(
                "cloud_sync", "drawable", ZoeIds.AFUSEKT_PACKAGE);
        if (cloudId != 0) {
            icon.setImageResource(cloudId);
        } else if (iconRef != null && iconRef.getDrawable() != null
                && iconRef.getDrawable().getConstantState() != null) {
            icon.setImageDrawable(iconRef.getDrawable().getConstantState().newDrawable());
        }
        if (iconRef != null) {
            icon.setScaleType(iconRef.getScaleType());
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = textGap;
        textCol.setLayoutParams(textLp);

        TextView title = newTextViewLike(activity, refTexts, 0);
        title.setText("WebDAV 同步");
        TextView subtitle = newTextViewLike(activity, refTexts, 1);
        subtitle.setTag(SUBTITLE_TAG);
        textCol.addView(title);
        textCol.addView(subtitle);

        row.addView(icon);
        row.addView(textCol);
        section.addView(row, insertIndex + 1);
        updateSummary(activity, row);
        module.log(4, ZoeIds.TAG, "WebDAV settings row injected (compact WRAP_CONTENT)");
        return true;
    }

    /**
     * 定位「同步资源库」所在列表项（rowHost）。
     * 实测层级：title → textCol → content → card → rowHost → section。
     * 不能在 content（图标+文本+开关）处提前停下，否则会插进行内。
     */
    private static ViewGroup resolveSyncRowHost(Activity activity, ViewGroup root) {
        int syncBoxId = activity.getResources().getIdentifier(
                "app_sync_library_box", "id", ZoeIds.AFUSEKT_PACKAGE);
        if (syncBoxId != 0) {
            View syncBox = activity.findViewById(syncBoxId);
            if (syncBox != null && syncBox.getParent() instanceof ViewGroup rowHost
                    && rowHost.getParent() instanceof ViewGroup section
                    && section.getChildCount() >= 2) {
                return rowHost;
            }
        }
        TextView titleRef = findText(root, SYNC_ROW_TITLE);
        if (titleRef == null) {
            return null;
        }
        // 固定上溯 4 层到 rowHost（与 3.2.4 / 3.3.x 实测一致）
        View current = titleRef;
        for (int i = 0; i < 4; i++) {
            if (!(current.getParent() instanceof ViewGroup parent)) {
                return null;
            }
            current = parent;
        }
        if (current instanceof ViewGroup rowHost
                && rowHost.getParent() instanceof ViewGroup section
                && section.getChildCount() >= 2) {
            return rowHost;
        }
        // 兜底：继续上溯，直到父节点是多子项 section
        while (current != null && current.getParent() instanceof ViewGroup parent) {
            if (parent.getChildCount() >= 2 && current instanceof ViewGroup
                    && looksLikeSettingSection(parent)) {
                return (ViewGroup) current;
            }
            current = parent;
        }
        return null;
    }

    /** section 内应能看到「同步资源库」等设置标题，避免误把 content 当行。 */
    private static boolean looksLikeSettingSection(ViewGroup section) {
        int settingTitles = 0;
        for (int i = 0; i < section.getChildCount(); i++) {
            View child = section.getChildAt(i);
            if (!(child instanceof ViewGroup group)) {
                continue;
            }
            if (findText(group, SYNC_ROW_TITLE) != null
                    || findText(group, "隐藏播放记录") != null
                    || findText(group, "聚合搜索") != null) {
                settingTitles++;
            }
        }
        return settingTitles >= 1;
    }

    private static void removeTaggedViews(ViewGroup root, String tag) {
        List<View> doomed = new java.util.ArrayList<>();
        collectTagged(root, tag, doomed);
        for (View view : doomed) {
            if (view.getParent() instanceof ViewGroup parent) {
                parent.removeView(view);
            }
        }
    }

    private static void collectTagged(ViewGroup parent, String tag, List<View> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (tag.equals(child.getTag())) {
                out.add(child);
            }
            if (child instanceof ViewGroup group) {
                collectTagged(group, tag, out);
            }
        }
    }

    private static View findFirstCardLike(ViewGroup host) {
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            String name = child.getClass().getName();
            if (name.contains("CardView") || name.contains("MaterialCard")) {
                return child;
            }
            if (child instanceof ViewGroup nested) {
                View found = findFirstCardLike(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return host.getChildCount() > 0 ? host.getChildAt(0) : null;
    }

    private static void updateSummary(Activity activity, View row) {
        View subtitle = row.findViewWithTag(SUBTITLE_TAG);
        if (subtitle instanceof TextView view) {
            view.setText(WebDavSyncConfig.isConfigured(activity)
                    ? "已启用，点击配置 WebDAV 服务器"
                    : "替代官方云端，使用自有 WebDAV");
        }
    }

    // ---------------------------------------------------------------- config / lifecycle

    private static void installConfigReceiver(ZoeModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Application app
                                && ZoeIds.AFUSEKT_PACKAGE.equals(app.getPackageName())) {
                            try {
                                WebDavConfigReceiver.register(app);
                                WebDavSyncConfig.refreshForSync(app);
                                WebDavSyncConfig.logConfigSnapshot(app);
                            } catch (Throwable t) {
                                module.log(5, ZoeIds.TAG,
                                        "config receiver hook failed: " + t.getMessage());
                            }
                            new Handler(Looper.getMainLooper()).postDelayed(
                                    () -> WebDavSyncConfig.refreshForSync(app), 300);
                        }
                        return result;
                    });
            module.log(4, ZoeIds.TAG, "WebDAV config receiver hooked");
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void installActivityResume(ZoeModule module) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            module.hook(onResume)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity activity
                                && ZoeIds.AFUSEKT_PACKAGE.equals(activity.getPackageName())) {
                            WebDavSyncConfig.refreshForSync(activity);
                            if (GENERAL_SETTING.equals(activity.getClass().getName())) {
                                ensureRowLater(activity, module, 0);
                            }
                        }
                        return result;
                    });
            module.log(4, ZoeIds.TAG, "WebDAV activity resume hooked");
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Activity.onResume hook failed: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- package visibility

    private static void installVisibility(ZoeModule module, ClassLoader cl) {
        Class<?> pm = findClass(cl, PACKAGE_MANAGER);
        if (pm == null) {
            pm = forNameSafe(PACKAGE_MANAGER);
        }
        if (pm == null) {
            module.log(5, ZoeIds.TAG, "ApplicationPackageManager missing");
            return;
        }
        for (Method method : pm.getDeclaredMethods()) {
            String name = method.getName();
            final int paramCount = method.getParameterTypes().length;
            if ("resolveContentProvider".equals(name)) {
                module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result != null || paramCount < 1) {
                                return result;
                            }
                            if (WebDavConfigProvider.AUTHORITY.equals(
                                    String.valueOf(chain.getArg(0)))) {
                                return buildProviderInfo();
                            }
                            return result;
                        });
                module.log(4, ZoeIds.TAG, "visibility hook: ApplicationPackageManager." + name);
            } else if ("getApplicationInfo".equals(name) && paramCount == 2) {
                module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result == null
                                    && WebDavSyncConfig.MODULE_PACKAGE.equals(chain.getArg(0))) {
                                return buildApplicationInfo();
                            }
                            return result;
                        });
                module.log(4, ZoeIds.TAG, "visibility hook: ApplicationPackageManager." + name);
            } else if ("getPackageInfo".equals(name) && paramCount == 2) {
                module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result == null
                                    && WebDavSyncConfig.MODULE_PACKAGE.equals(chain.getArg(0))) {
                                PackageInfo info = new PackageInfo();
                                info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
                                info.applicationInfo = buildApplicationInfo();
                                return info;
                            }
                            return result;
                        });
                module.log(4, ZoeIds.TAG, "visibility hook: ApplicationPackageManager." + name);
            }
        }
    }

    private static ProviderInfo buildProviderInfo() {
        ProviderInfo info = new ProviderInfo();
        info.authority = WebDavConfigProvider.AUTHORITY;
        info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
        info.name = WebDavConfigProvider.class.getName();
        info.exported = true;
        info.enabled = true;
        info.applicationInfo = buildApplicationInfo();
        return info;
    }

    private static ApplicationInfo buildApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = WebDavSyncConfig.MODULE_PACKAGE;
        info.enabled = true;
        return info;
    }

    // ---------------------------------------------------------------- provider + toasts

    private static void installProviderHook(ZoeModule module) {
        try {
            Class<?> resolver = android.content.ContentResolver.class;
            for (Method method : resolver.getDeclaredMethods()) {
                if ("call".equals(method.getName())
                        && method.getParameterTypes().length == 4) {
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                if (isGetConfigCall(chain.getArg(0), chain.getArg(1))) {
                                    Bundle bundle = WebDavSyncConfig.readFreshConfigBundle();
                                    if (bundle != null) {
                                        return bundle;
                                    }
                                }
                                return chain.proceed();
                            });
                    module.log(4, ZoeIds.TAG, "ContentResolver.call("
                            + method.getParameterTypes()[0].getSimpleName() + ") hooked");
                }
            }
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "ContentResolver.call hook failed: " + t.getMessage());
        }
    }

    private static boolean isGetConfigCall(Object target, Object methodName) {
        return "getConfig".equals(String.valueOf(methodName))
                && WebDavConfigProvider.AUTHORITY.equals(String.valueOf(target));
    }

    private static void installToastSuppress(ZoeModule module, ClassLoader cl) {
        try {
            Method makeText = Toast.class.getDeclaredMethod(
                    "makeText", Context.class, CharSequence.class, int.class);
            module.hook(makeText)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        String message = chain.getArg(1) == null
                                ? null : String.valueOf(chain.getArg(1));
                        if (isBlockedToast(message)) {
                            module.log(4, ZoeIds.TAG, "suppress Toast: " + message);
                            return null;
                        }
                        return chain.proceed();
                    });
            module.log(4, ZoeIds.TAG, "Toast.makeText hooked");
        } catch (Throwable t) {
            module.log(5, ZoeIds.TAG, "Toast.makeText hook failed: " + t.getMessage());
        }

        Class<?> tool = findClass(cl, SYSTEM_TOOL_COMPANION);
        if (tool != null) {
            for (Method method : tool.getDeclaredMethods()) {
                if (!"K".equals(method.getName())) {
                    continue;
                }
                final int paramCount = method.getParameterTypes().length;
                if (paramCount < 2) {
                    continue;
                }
                module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (paramCount >= 2
                                    && chain.getArg(1) instanceof String message
                                    && isBlockedToast(message)) {
                                module.log(4, ZoeIds.TAG, "suppress SystemTool.K: " + message);
                                return null;
                            }
                            return chain.proceed();
                        });
                module.log(4, ZoeIds.TAG, "SystemTool.Companion.K hooked");
            }
        }
    }

    private static boolean isBlockedToast(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        if (message.startsWith("WebDAV")) {
            return false;
        }
        if (message.contains("无权限")
                || message.contains("無權限")
                || message.equalsIgnoreCase("No Permission")) {
            return WebDavSyncHooks.WEBDAV_CONFIGURED.get()
                    || WebDavSyncHooks.WEBDAV_ACTIVE.get();
        }
        if (!WebDavSyncHooks.WEBDAV_ACTIVE.get()) {
            return false;
        }
        return message.contains("成功上传")
                || message.contains("资源库")
                || message.contains("同步资源库未开启")
                || "操作成功".equals(message)
                || "error".equalsIgnoreCase(message);
    }

    // ---------------------------------------------------------------- sync redirects

    private static void installSyncRedirects(ZoeModule module, ClassLoader cl) {
        hookUploadRedirect(module, cl);
        hookDownloadRedirect(module, cl);
        hookSyncSettingGate(module, cl);
        hookMenuGate(module, cl);
    }

    private static void hookUploadRedirect(ZoeModule module, ClassLoader cl) {
        Class<?> companion = findClass(cl, ORDER_TOOLS_COMPANION);
        if (companion == null) {
            module.log(5, ZoeIds.TAG, "OrderUserTools$Companion missing; upload redirect skipped");
            return;
        }
        for (Method method : companion.getDeclaredMethods()) {
            if (!"c".equals(method.getName())
                    || method.getParameterTypes().length != 3) {
                continue;
            }
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Context context = (Context) chain.getArg(0);
                        if (!shouldRedirect(context)) {
                            WebDavSyncConfig.logConfigSnapshot(context);
                            WebDavLibrarySync.notifyOfficialSync(context, cl);
                            return chain.proceed();
                        }
                        @SuppressWarnings("unchecked")
                        List<?> sources = (List<?>) chain.getArg(1);
                        WebDavLibrarySync.upload(context, sources, chain.getArg(2), cl);
                        return null;
                    });
            module.log(4, ZoeIds.TAG, "OrderUserTools.Companion.c upload redirect hooked");
            return;
        }
        module.log(5, ZoeIds.TAG, "OrderUserTools.Companion.c not found");
    }

    private static void hookDownloadRedirect(ZoeModule module, ClassLoader cl) {
        Class<?> fragment = findClass(cl, FRAGMENT);
        if (fragment == null) {
            module.log(5, ZoeIds.TAG, "VideoLibraryFragment missing; download redirect skipped");
            return;
        }
        Method method = findMethod(fragment, "getVideoSource", 0);
        if (method == null) {
            module.log(5, ZoeIds.TAG, "VideoLibraryFragment.getVideoSource not found");
            return;
        }
        module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object self = chain.getThisObject();
                    Context context = (Context) invokeQuiet(self, "requireContext");
                    if (context == null || !shouldRedirect(context)) {
                        if (context != null) {
                            WebDavSyncConfig.logConfigSnapshot(context);
                            WebDavLibrarySync.notifyOfficialSync(context, cl);
                        }
                        return chain.proceed();
                    }
                    WebDavLibrarySync.download(self, cl);
                    return null;
                });
        module.log(4, ZoeIds.TAG, "VideoLibraryFragment.getVideoSource download redirect hooked");
    }

    private static void hookSyncSettingGate(ZoeModule module, ClassLoader cl) {
        hookBoolPrefGate(module, cl, SP_UTIL, "SpUtil.d");
        hookBoolPrefGate(module, cl, MMKV_PREFS, "ax6.d");
    }

    private static void hookBoolPrefGate(
            ZoeModule module,
            ClassLoader cl,
            String className,
            String label
    ) {
        Class<?> cls = findClass(cl, className);
        if (cls == null) {
            module.log(5, ZoeIds.TAG, className + " missing; " + label + " skipped");
            return;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (!"d".equals(method.getName())
                    || method.getParameterTypes().length != 3) {
                continue;
            }
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        String key = String.valueOf(chain.getArg(1));
                        if (WebDavSyncConfig.AFUSEKT_SYNC_SETTING_KEY.equals(key)
                                && WebDavSyncConfig.resolveForSync((Context) chain.getArg(0))) {
                            return Boolean.TRUE;
                        }
                        return result;
                    });
            module.log(4, ZoeIds.TAG, label + " sync setting gate hooked");
            return;
        }
        module.log(5, ZoeIds.TAG, label + " not found");
    }

    private static void hookMenuGate(ZoeModule module, ClassLoader cl) {
        Class<?> fragment = findClass(cl, FRAGMENT);
        if (fragment == null) {
            return;
        }
        Method method = findMethod(fragment, "addLibrary", 3);
        if (method == null) {
            return;
        }
        module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> chain.proceed());
        module.log(4, ZoeIds.TAG, "VideoLibraryFragment.addLibrary menu gate hooked");
    }

    private static boolean shouldRedirect(Context context) {
        boolean configured = WebDavSyncConfig.resolveForSync(context);
        WebDavSyncHooks.WEBDAV_CONFIGURED.set(configured);
        return configured;
    }

    // ---------------------------------------------------------------- helpers

    private static Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> forNameSafe(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        if (cls == null) {
            return null;
        }
        for (Method method : cls.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterTypes().length == paramCount) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeQuiet(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static TextView findText(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView view && text.contentEquals(view.getText())) {
                return view;
            }
            if (child instanceof ViewGroup group) {
                TextView hit = findText(group, text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static ImageView findFirstImageView(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ImageView imageView) {
                return imageView;
            }
            if (child instanceof ViewGroup group) {
                ImageView hit = findFirstImageView(group);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static List<TextView> collectTextViews(ViewGroup parent) {
        java.util.List<TextView> texts = new java.util.ArrayList<>();
        collectTextViews(parent, texts);
        return texts;
    }

    private static void collectTextViews(ViewGroup parent, java.util.List<TextView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView view) {
                out.add(view);
            } else if (child instanceof ViewGroup group) {
                collectTextViews(group, out);
            }
        }
    }

    private static TextView newTextViewLike(Activity activity, List<TextView> refs, int index) {
        TextView view = new TextView(activity);
        TextView ref = refs != null && refs.size() > index ? refs.get(index) : null;
        if (ref != null) {
            view.setTextColor(ref.getTextColors());
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, ref.getTextSize());
            if (ref.getTypeface() != null) {
                view.setTypeface(ref.getTypeface());
            }
        } else if (index == 0) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        }
        return view;
    }

}
