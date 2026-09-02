package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        int pad = UiKit.dp(activity, 16);
        LinearLayout panel = UiKit.dialogPanel(activity);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = UiKit.title(activity, "自动领取次数");
        title.setTextSize(17f);
        panel.addView(title);

        TextView hint = UiKit.body(activity, "每次约 +20 分钟，选好后后台静默连领，无需等待 loading。");
        hint.setTextSize(13f);
        LinearLayout.LayoutParams hintLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 8));
        panel.addView(hint, hintLp);

        java.util.ArrayList<Button> presetButtons = new java.util.ArrayList<>();
        for (int preset : PRESETS) {
            Button button = optionButton(activity, formatPreset(preset));
            presetButtons.add(button);
            panel.addView(button, UiKit.matchWrapTopMargin(UiKit.dp(activity, 8)));
        }

        Button customBtn = UiKit.outlinedButton(activity, "自定义次数…");
        panel.addView(customBtn, UiKit.matchWrapTopMargin(UiKit.dp(activity, 8)));

        AlertDialog dialog = UiKit.showDialog(activity, panel);
        for (int i = 0; i < PRESETS.length; i++) {
            final int preset = PRESETS[i];
            presetButtons.get(i).setOnClickListener(v -> {
                dialog.dismiss();
                finish(activity, preset, listener);
            });
        }
        customBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showCustomInput(activity, safeDefault, listener);
        });
    }

    private static Button optionButton(Activity activity, String label) {
        Button button = UiKit.tonalButton(activity, label);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT);
        return button;
    }

    private static void showCustomInput(Activity activity, int defaultCount, Listener listener) {
        int pad = UiKit.dp(activity, 16);
        LinearLayout panel = UiKit.dialogPanel(activity);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = UiKit.title(activity, "自定义次数");
        title.setTextSize(17f);
        panel.addView(title);

        TextView hint = UiKit.small(activity, "1 ~ " + XimalayaPrefs.MAX_BURST_COUNT + " 次");
        LinearLayout.LayoutParams hintLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 6));
        panel.addView(hint, hintLp);

        EditText input = UiKit.field(activity, "次数");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(defaultCount));
        input.setSelection(input.getText().length());
        LinearLayout.LayoutParams inputLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 10));
        panel.addView(input, inputLp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 12));

        Button cancelBtn = UiKit.textButton(activity, "取消");
        actions.addView(cancelBtn);

        Button okBtn = UiKit.filledButton(activity, "确定");
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = UiKit.dp(activity, 8);
        okBtn.setLayoutParams(okLp);
        actions.addView(okBtn);
        panel.addView(actions, actionsLp);

        AlertDialog dialog = UiKit.showDialog(activity, panel);
        cancelBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) {
                listener.onCancelled();
            }
        });
        okBtn.setOnClickListener(v -> {
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
            dialog.dismiss();
            finish(activity, count, listener);
        });
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
}
