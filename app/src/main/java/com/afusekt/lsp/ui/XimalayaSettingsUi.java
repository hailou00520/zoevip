package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
        int pad = UiKit.dp(activity, 20);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(activity);
        hint.setText("24 小时领取上限绑在账号上，只清数据不换号通常无效。开启下方测试开关后可继续测模块。");
        hint.setTextSize(14f);
        hint.setTextColor(UiColors.text(activity));
        hint.setLineSpacing(0, 1.2f);
        root.addView(hint, UiKit.matchWrap());

        CheckBox bypassBox = UiKit.checkBox(activity, "测试：绕过 24h 领取上限");
        bypassBox.setChecked(XimalayaPrefs.isBypassDailyLimit(activity));
        LinearLayout.LayoutParams boxLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 16));
        root.addView(bypassBox, boxLp);

        Button burstButton = UiKit.tonalButton(
                activity,
                "设置默认连领次数（当前 " + XimalayaPrefs.getBurstCount(activity) + " 次）");
        LinearLayout.LayoutParams burstLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));
        root.addView(burstButton, burstLp);

        AlertDialog dialog = new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                .setTitle("喜马拉雅 · ZoeVIP")
                .setView(root)
                .setPositiveButton("保存", null)
                .setNegativeButton("关闭", null)
                .create();

        burstButton.setOnClickListener(v -> XimalayaBurstPicker.show(
                activity,
                XimalayaPrefs.getBurstCount(activity),
                new XimalayaBurstPicker.Listener() {
                    @Override
                    public void onSelected(int count) {
                        burstButton.setText("设置默认连领次数（当前 " + count + " 次）");
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

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            XimalayaPrefs.setBypassDailyLimit(activity, bypassBox.isChecked());
            Toast.makeText(
                    activity,
                    bypassBox.isChecked()
                            ? "已开启测试绕过（需重启喜马拉雅）"
                            : "已关闭测试绕过（需重启喜马拉雅）",
                    Toast.LENGTH_LONG
            ).show();
            dialog.dismiss();
        }));
        dialog.show();
    }
}
