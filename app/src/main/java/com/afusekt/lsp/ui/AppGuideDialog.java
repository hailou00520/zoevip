package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Styled guide dialog for adapted apps (replaces long Toast messages). */
public final class AppGuideDialog {

    private AppGuideDialog() {
    }

    public static void show(Activity activity, AdaptedAppRegistry.Entry entry) {
        if (activity == null || activity.isFinishing() || entry == null) {
            return;
        }

        int pad = UiKit.dp(activity, 16);
        LinearLayout panel = UiKit.dialogPanel(activity);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(UiKit.appIconForEntry(activity, entry, 44));

        LinearLayout headerText = new LinearLayout(activity);
        headerText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headerTextLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        headerTextLp.leftMargin = UiKit.dp(activity, 12);
        headerText.setLayoutParams(headerTextLp);

        TextView title = UiKit.title(activity, entry.title);
        title.setTextSize(17f);
        headerText.addView(title);

        TextView pkg = UiKit.small(activity, AdaptedAppRegistry.displayPackage(entry));
        LinearLayout.LayoutParams pkgLp = UiKit.matchWrap();
        pkgLp.topMargin = UiKit.dp(activity, 2);
        headerText.addView(pkg, pkgLp);

        String meta = buildMetaLine(activity, entry);
        if (!meta.isEmpty()) {
            TextView metaView = UiKit.small(activity, meta);
            LinearLayout.LayoutParams metaLp = UiKit.matchWrap();
            metaLp.topMargin = UiKit.dp(activity, 4);
            headerText.addView(metaView, metaLp);
        }

        header.addView(headerText);
        panel.addView(header);

        panel.addView(UiKit.divider(activity));

        TextView desc = UiKit.body(activity, entry.description);
        desc.setTextSize(13f);
        LinearLayout.LayoutParams descLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 10));
        panel.addView(desc, descLp);

        TextView stepsLabel = UiKit.small(activity, "使用步骤");
        stepsLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        stepsLabel.setTextColor(UiColors.muted(activity));
        LinearLayout.LayoutParams stepsLabelLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));
        panel.addView(stepsLabel, stepsLabelLp);

        for (int i = 0; i < entry.guideSteps.length; i++) {
            panel.addView(
                    UiKit.numberedStep(activity, i + 1, entry.guideSteps[i]),
                    UiKit.matchWrapTopMargin(UiKit.dp(activity, 8)));
        }

        boolean installed = AdaptedAppRegistry.isInstalled(activity, entry);
        TextView status = UiKit.small(
                activity,
                installed ? "已检测到安装，可直接打开测试" : "未检测到安装，请先安装目标 App");
        status.setTextColor(installed ? UiColors.accent(activity) : UiColors.muted(activity));
        LinearLayout.LayoutParams statusLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));
        panel.addView(status, statusLp);

        panel.addView(UiKit.divider(activity));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams actionsLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 10));

        Button copyBtn = UiKit.textButton(activity, "复制包名");
        copyBtn.setOnClickListener(v -> copyPackage(activity, entry));
        actions.addView(copyBtn);

        Button closeBtn = UiKit.textButton(activity, "关闭");
        actions.addView(closeBtn);

        if (installed) {
            Button openBtn = UiKit.tonalButton(activity, "打开应用");
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            openLp.leftMargin = UiKit.dp(activity, 8);
            openBtn.setLayoutParams(openLp);
            openBtn.setOnClickListener(v -> openApp(activity, entry));
            actions.addView(openBtn);
        }

        panel.addView(actions, actionsLp);

        AlertDialog dialog = new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                .setView(panel)
                .create();
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        UiKit.applyDialogWindow(dialog);
    }

    private static String buildMetaLine(Activity activity, AdaptedAppRegistry.Entry entry) {
        StringBuilder sb = new StringBuilder();
        String adapted = AdaptedAppRegistry.displayAdaptedVersion(entry);
        if (!adapted.isEmpty()) {
            sb.append(adapted);
        }
        if (entry.adaptNote != null && !entry.adaptNote.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(entry.adaptNote);
        }
        String install = AdaptedAppRegistry.displayInstallStatus(activity, entry);
        if (!install.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(install);
        }
        return sb.toString();
    }

    private static void openApp(Activity activity, AdaptedAppRegistry.Entry entry) {
        PackageManager pm = activity.getPackageManager();
        String pkg = AdaptedAppRegistry.resolveInstalledPackage(activity, entry);
        if (pkg == null || pkg.isEmpty()) {
            Toast.makeText(activity, "应用未安装", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) {
            activity.startActivity(launch);
        } else {
            Toast.makeText(activity, "无法打开 " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyPackage(Activity activity, AdaptedAppRegistry.Entry entry) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) activity.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE);
            String text = AdaptedAppRegistry.displayPackage(entry);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("package", text));
            Toast.makeText(activity, "已复制包名", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(activity, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}
