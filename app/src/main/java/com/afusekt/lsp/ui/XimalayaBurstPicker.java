package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.prefs.XimalayaPrefs;
import com.afusekt.lsp.sync.WebDavSyncConfig;

/** Lets the user pick how many silent burst claims to run after tapping claim-free-time. */
public final class XimalayaBurstPicker {

    private static final int[] PRESETS = {1, 5, 10, 20, 30};

    public interface Listener {
        void onSelected(int count);

        void onCancelled();
    }

    private XimalayaBurstPicker() {
    }

    public static void show(Activity activity, int defaultCount, Listener listener) {
        if (activity == null || activity.isFinishing()) {
            if (listener != null) {
                listener.onSelected(XimalayaPrefs.clampBurstCount(defaultCount));
            }
            return;
        }
        int safeDefault = XimalayaPrefs.clampBurstCount(defaultCount);
        ScrollView scroll = new ScrollView(activity);
        int pad = dp(activity, 20);
        scroll.setPadding(pad, dp(activity, 8), pad, dp(activity, 4));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, matchWrap());

        TextView hint = new TextView(activity);
        hint.setText("每次约 +20 分钟，选好后后台静默连领，无需等待 loading。");
        hint.setTextSize(14f);
        hint.setTextColor(UiColors.text(activity));
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(0, 0, 0, dp(activity, 12));
        content.addView(hint, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                .setTitle("自动领取次数")
                .setView(scroll)
                .setNegativeButton("取消", (d, which) -> {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                })
                .setOnCancelListener(d -> {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                })
                .create();

        for (int preset : PRESETS) {
            content.addView(optionButton(activity, formatPreset(preset), () -> {
                dialog.dismiss();
                finish(activity, preset, listener);
            }));
        }
        content.addView(optionButton(activity, "自定义次数…", () -> {
            dialog.dismiss();
            showCustomInput(activity, safeDefault, listener);
        }));

        dialog.show();
    }

    private static Button optionButton(Activity activity, String label, Runnable action) {
        Button button = UiKit.tonalButton(activity, label);
        button.setGravity(android.view.Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(activity, 8);
        button.setLayoutParams(lp);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static void showCustomInput(Activity activity, int defaultCount, Listener listener) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(defaultCount));
        input.setSelection(input.getText().length());
        input.setTextColor(UiColors.text(activity));
        int pad = dp(activity, 20);
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, dp(activity, 8), pad, 0);
        wrap.addView(input, matchWrap());

        new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                .setTitle("自定义次数")
                .setMessage("1 ~ " + XimalayaPrefs.MAX_BURST_COUNT + " 次")
                .setView(wrap)
                .setPositiveButton("开始", (dialog, which) -> {
                    int count;
                    try {
                        count = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, "请输入有效数字", Toast.LENGTH_SHORT).show();
                        if (listener != null) {
                            listener.onCancelled();
                        }
                        return;
                    }
                    finish(activity, count, listener);
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                })
                .setOnCancelListener(dialog -> {
                    if (listener != null) {
                        listener.onCancelled();
                    }
                })
                .show();
    }

    private static void finish(Activity activity, int count, Listener listener) {
        int safe = XimalayaPrefs.clampBurstCount(count);
        saveBurstCount(activity, safe);
        if (listener != null) {
            listener.onSelected(safe);
        }
    }

    private static void saveBurstCount(Activity activity, int count) {
        try {
            Context moduleCtx = activity.createPackageContext(
                    WebDavSyncConfig.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            XimalayaPrefs.setBurstCount(moduleCtx, count);
        } catch (Throwable ignored) {
        }
    }

    private static String formatPreset(int count) {
        return count + " 次（约 " + (count * 20) + " 分钟）";
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
