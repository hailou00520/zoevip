package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.prefs.XimalayaPrefs;

/** ZoeVIP module settings for Ximalaya hooks. */
public final class XimalayaSettingsUi {

    private XimalayaSettingsUi() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        int pad = UiKit.dp(activity, 16);
        LinearLayout panel = UiKit.dialogPanel(activity);
        panel.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        AdaptedAppRegistry.Entry entry = findXimalayaEntry();
        if (entry != null) {
            header.addView(UiKit.appIconForEntry(activity, entry, 40));
        }

        TextView title = UiKit.title(activity, "喜马拉雅");
        title.setTextSize(17f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        titleLp.leftMargin = UiKit.dp(activity, 12);
        title.setLayoutParams(titleLp);
        header.addView(title);
        panel.addView(header);

        TextView hint = UiKit.body(activity,
                "24 小时领取上限绑在账号上，只清数据不换号通常无效。开启下方测试开关后可继续测模块。");
        hint.setTextSize(13f);
        LinearLayout.LayoutParams hintLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));
        panel.addView(hint, hintLp);

        CheckBox bypassBox = UiKit.checkBox(activity, "测试：绕过 24h 领取上限");
        bypassBox.setChecked(XimalayaPrefs.isBypassDailyLimit(activity));
        LinearLayout.LayoutParams bypassLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));
        panel.addView(bypassBox, bypassLp);

        Button burstButton = UiKit.tonalButton(
                activity,
                "默认连领 " + XimalayaPrefs.getBurstCount(activity) + " 次");
        LinearLayout.LayoutParams burstLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 10));
        panel.addView(burstButton, burstLp);

        panel.addView(UiKit.divider(activity));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams actionsLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 10));

        Button closeBtn = UiKit.textButton(activity, "关闭");
        actions.addView(closeBtn);

        Button saveBtn = UiKit.filledButton(activity, "保存");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.leftMargin = UiKit.dp(activity, 8);
        saveBtn.setLayoutParams(saveLp);
        actions.addView(saveBtn);
        panel.addView(actions, actionsLp);

        AlertDialog dialog = UiKit.showDialog(activity, panel);
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        burstButton.setOnClickListener(v -> XimalayaBurstPicker.show(
                activity,
                XimalayaPrefs.getBurstCount(activity),
                new XimalayaBurstPicker.Listener() {
                    @Override
                    public void onSelected(int count) {
                        burstButton.setText("默认连领 " + count + " 次");
                        Toast.makeText(
                                activity,
                                "默认连领 " + count + " 次",
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onCancelled() {
                    }
                }));

        saveBtn.setOnClickListener(v -> {
            XimalayaPrefs.setBypassDailyLimit(activity, bypassBox.isChecked());
            Toast.makeText(
                    activity,
                    bypassBox.isChecked()
                            ? "已开启测试绕过（需重启喜马拉雅）"
                            : "已关闭测试绕过（需重启喜马拉雅）",
                    Toast.LENGTH_LONG
            ).show();
            dialog.dismiss();
        });
    }

    private static AdaptedAppRegistry.Entry findXimalayaEntry() {
        for (AdaptedAppRegistry.Entry entry : AdaptedAppRegistry.all()) {
            if ("com.ximalaya.ting.android".equals(entry.packageName)) {
                return entry;
            }
        }
        return null;
    }
}
