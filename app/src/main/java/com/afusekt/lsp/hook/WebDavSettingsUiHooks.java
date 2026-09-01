package com.afusekt.lsp.hook;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.sync.WebDavConfigAccess;
import com.afusekt.lsp.ui.WebDavSettingsUi;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Injects WebDAV settings entry into Afusekt → 设置 → 通用. */
public final class WebDavSettingsUiHooks {

    private static final String GENERAL_SETTINGS =
            "com.attempt.afusekt.mainView.activity.GeneralSettingView";

    private WebDavSettingsUiHooks() {
    }

    public static void apply(ClassLoader classLoader) {
        hookGeneralSettings(classLoader);
    }

    private static void hookGeneralSettings(ClassLoader classLoader) {
        try {
            Class<?> settingsClass = XposedHelpers.findClass(GENERAL_SETTINGS, classLoader);
            XposedHelpers.findAndHookMethod(settingsClass, "P", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    injectWebDavRow(activity);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": WebDAV settings UI hook failed: " + t.getMessage());
        }
    }

    private static void injectWebDavRow(Activity activity) {
        try {
            int syncBoxId = activity.getResources().getIdentifier(
                    "app_sync_library_box",
                    "id",
                    MainHook.AFUSEKT_PACKAGE
            );
            if (syncBoxId == 0) {
                return;
            }
            View syncBox = activity.findViewById(syncBoxId);
            if (!(syncBox instanceof ViewGroup)) {
                return;
            }
            View rowHost = (View) syncBox.getParent();
            if (!(rowHost instanceof ViewGroup)) {
                return;
            }
            ViewGroup section = (ViewGroup) rowHost.getParent();
            if (section == null) {
                return;
            }
            int insertIndex = section.indexOfChild(rowHost);
            if (insertIndex < 0) {
                return;
            }
            View existing = section.findViewWithTag("zoevip_webdav_row");
            if (existing != null) {
                updateSummary(activity, existing);
                return;
            }

            TextView[] referenceTexts = findRowTexts((ViewGroup) syncBox);

            ViewGroup outer = (ViewGroup) XposedHelpers.newInstance(rowHost.getClass(), activity);
            outer.setTag("zoevip_webdav_row");
            if (rowHost.getLayoutParams() != null) {
                outer.setLayoutParams(copyLayoutParams(rowHost.getLayoutParams()));
            }

            ViewGroup card = (ViewGroup) XposedHelpers.newInstance(syncBox.getClass(), activity);
            card.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            if (syncBox.getBackground() != null) {
                card.setBackground(syncBox.getBackground().getConstantState().newDrawable());
            }
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> WebDavSettingsUi.show(activity));

            float density = activity.getResources().getDisplayMetrics().density;
            int padV = rowPaddingVertical(syncBox, density);
            int padH = 0;
            View inner = firstChild((ViewGroup) syncBox);
            if (inner != null) {
                padH = inner.getPaddingLeft();
            }

            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setPadding(padH, padV, padH, padV);
            content.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            ImageView icon = new ImageView(activity);
            int iconId = activity.getResources().getIdentifier(
                    "cloud_sync",
                    "drawable",
                    MainHook.AFUSEKT_PACKAGE
            );
            if (iconId != 0) {
                icon.setImageResource(iconId);
            }
            int iconSize = Math.round(24f * density);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            textLp.leftMargin = Math.round(16f * density);
            textCol.setLayoutParams(textLp);

            TextView title = newTextView(activity, referenceTexts, 0);
            title.setText("WebDAV 同步");

            TextView subtitle = newTextView(activity, referenceTexts, 1);
            subtitle.setTag("zoevip_webdav_subtitle");

            textCol.addView(title);
            textCol.addView(subtitle);

            content.addView(icon);
            content.addView(textCol);
            card.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            outer.addView(card, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            section.addView(outer, insertIndex + 1);
            updateSummary(activity, outer);
            XposedBridge.log(MainHook.TAG + ": WebDAV settings row injected");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ": WebDAV settings row failed: " + t.getMessage());
        }
    }

    private static TextView newTextView(Activity activity, TextView[] references, int index) {
        TextView view = new TextView(activity);
        TextView reference = references != null && references.length > index ? references[index] : null;
        if (reference != null) {
            view.setTextColor(reference.getTextColors());
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.getTextSize());
            if (reference.getTypeface() != null) {
                view.setTypeface(reference.getTypeface());
            }
        } else if (index == 0) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        }
        return view;
    }

    private static TextView[] findRowTexts(ViewGroup card) {
        List<TextView> texts = new ArrayList<>();
        collectTextViews(card, texts);
        return texts.toArray(new TextView[0]);
    }

    private static void collectTextViews(ViewGroup parent, List<TextView> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                out.add((TextView) child);
            } else if (child instanceof ViewGroup) {
                collectTextViews((ViewGroup) child, out);
            }
        }
    }

    private static View firstChild(ViewGroup parent) {
        return parent.getChildCount() > 0 ? parent.getChildAt(0) : null;
    }

    private static int rowPaddingVertical(View syncBox, float density) {
        View inner = firstChild(syncBox instanceof ViewGroup ? (ViewGroup) syncBox : null);
        if (inner != null && inner.getPaddingTop() > 0) {
            return inner.getPaddingTop();
        }
        int dimenId = syncBox.getResources().getIdentifier(
                "item_padding",
                "dimen",
                MainHook.AFUSEKT_PACKAGE
        );
        if (dimenId != 0) {
            return syncBox.getResources().getDimensionPixelSize(dimenId);
        }
        return Math.round(14f * density);
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source instanceof LinearLayout.LayoutParams linear) {
            return new LinearLayout.LayoutParams(linear);
        }
        if (source instanceof ViewGroup.MarginLayoutParams margin) {
            return new ViewGroup.MarginLayoutParams(margin);
        }
        return new ViewGroup.LayoutParams(source.width, source.height);
    }

    private static void updateSummary(Activity activity, View row) {
        if (row == null) {
            return;
        }
        TextView subtitle = row.findViewWithTag("zoevip_webdav_subtitle");
        if (subtitle == null) {
            return;
        }
        subtitle.setText(WebDavConfigAccess.isConfigured(activity)
                ? "已启用，点击配置 WebDAV 服务器"
                : "替代官方云端，使用自有 WebDAV");
    }
}
