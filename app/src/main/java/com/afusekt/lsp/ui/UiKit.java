package com.afusekt.lsp.ui;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * ZoeVIP unified design system (pure-code, Material 3 inspired).
 *
 * <p>Every module screen (home, settings, dialogs) builds from these primitives so the
 * whole app shares one look: system light/dark aware colors, rounded corners, flat
 * surfaces, ripple feedback and consistent typography.</p>
 */
public final class UiKit {

    public static final float CARD_RADIUS_DP = 16f;
    public static final float FIELD_RADIUS_DP = 12f;
    public static final float BUTTON_RADIUS_DP = 12f;

    private UiKit() {
    }

    // ---------------------------------------------------------------- dims

    /** Strip platform default elevation/shadow (keeps cards visually flat). */
    public static void flatten(View view) {
        if (view == null) {
            return;
        }
        view.setElevation(0f);
        view.setTranslationZ(0f);
        view.setStateListAnimator(null);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(Color.TRANSPARENT);
            view.setOutlineSpotShadowColor(Color.TRANSPARENT);
        }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().scaledDensity);
    }

    // ---------------------------------------------------------------- roots

    /** Full-screen scroll root with theme background (call setContentView on it). */
    public static ScrollView scrollRoot(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(UiColors.bg(activity));
        scroll.setFillViewport(false);
        return scroll;
    }

    /** Vertical content column with standard page padding. */
    public static LinearLayout column(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        root.setPadding(pad, dp(activity, 16), pad, dp(activity, 28));
        return root;
    }

    public static void attach(ScrollView scroll, LinearLayout column) {
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWrapTopMargin(int dp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp;
        return lp;
    }

    public static LinearLayout.LayoutParams matchWrapLeftMargin(int dp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.leftMargin = dp;
        return lp;
    }

    /** Horizontal chip row container. */
    public static android.widget.HorizontalScrollView chipRow(Activity activity) {
        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, 0);
        return scroll;
    }

    public static LinearLayout chipContainer(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** Filter / tag chip. */
    public static TextView chip(
            Context context,
            CharSequence text,
            boolean selected,
            View.OnClickListener listener
    ) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(13f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        int hPad = dp(context, 14);
        int vPad = dp(context, 8);
        chip.setPadding(hPad, vPad, hPad, vPad);
        applyChipStyle(chip, selected);
        chip.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(context, 8);
        chip.setLayoutParams(lp);
        return chip;
    }

    public static void applyChipStyle(TextView chip, boolean selected) {
        Context context = chip.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 20));
        if (selected) {
            bg.setColor(UiColors.accent(context));
            chip.setTextColor(UiColors.onAccent(context));
        } else {
            bg.setColor(UiColors.surfaceVariant(context));
            chip.setTextColor(UiColors.text(context));
        }
        chip.setBackground(bg);
    }

    /** Search field with rounded surface fill. */
    public static EditText searchField(Context context, CharSequence hint) {
        EditText input = field(context, hint);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.surfaceVariant(context));
        bg.setCornerRadius(dp(context, Math.round(FIELD_RADIUS_DP)));
        input.setBackground(bg);
        input.setCompoundDrawablePadding(dp(context, 8));
        flatten(input);
        return input;
    }

    /** Hero banner with brand gradient. */
    public static LinearLayout heroCard(
            Activity activity,
            CharSequence title,
            CharSequence subtitle,
            CharSequence statLine
    ) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int radius = dp(activity, Math.round(CARD_RADIUS_DP));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{UiColors.accent(activity), 0xFF064D36});
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        int pad = dp(activity, 20);
        card.setPadding(pad, pad, pad, pad);

        TextView t = new TextView(activity);
        t.setText(title);
        t.setTextSize(28f);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setTextColor(Color.WHITE);
        card.addView(t);

        TextView sub = new TextView(activity);
        sub.setText(subtitle);
        sub.setTextSize(13f);
        sub.setTextColor(0xCCFFFFFF);
        sub.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams subLp = matchWrapTopMargin(dp(activity, 6));
        card.addView(sub, subLp);

        if (statLine != null && statLine.length() > 0) {
            TextView stat = new TextView(activity);
            stat.setText(statLine);
            stat.setTextSize(12f);
            stat.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            stat.setTextColor(0xE6FFFFFF);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0x33FFFFFF);
            pill.setCornerRadius(dp(activity, 12));
            stat.setBackground(pill);
            int pillPad = dp(activity, 10);
            stat.setPadding(pillPad, dp(activity, 6), pillPad, dp(activity, 6));
            LinearLayout.LayoutParams statLp = matchWrapTopMargin(dp(activity, 14));
            card.addView(stat, statLp);
        }
        flatten(card);
        return card;
    }

    /** Gradient primary button (matches hero). */
    public static Button gradientButton(Context context, CharSequence text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{UiColors.accent(context), 0xFF0B7A52});
        bg.setCornerRadius(dp(context, Math.round(BUTTON_RADIUS_DP)));
        b.setBackground(bg);
        int h = dp(context, 14);
        int w = dp(context, 16);
        b.setPadding(w, h, w, h);
        flatten(b);
        return b;
    }

    public static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(UiColors.outline(context));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(context, 12);
        lp.height = dp(context, 1);
        line.setLayoutParams(lp);
        return line;
    }

    /** Numbered step line for tips section. */
    public static LinearLayout numberedStep(Context context, int number, CharSequence text) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView num = new TextView(context);
        num.setText(String.valueOf(number));
        num.setTextSize(12f);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        num.setTextColor(UiColors.onAccent(context));
        num.setGravity(Gravity.CENTER);
        int numSize = dp(context, 22);
        num.setLayoutParams(new LinearLayout.LayoutParams(numSize, numSize));
        num.setBackground(rounded(context, UiColors.accent(context), 11));

        TextView body = body(context, text);
        LinearLayout.LayoutParams bodyLp = matchWrapLeftMargin(dp(context, 10));
        body.setLayoutParams(bodyLp);

        row.addView(num);
        row.addView(body);
        return row;
    }

    // ---------------------------------------------------------------- cards

    /** Rounded surface card with ripple and hairline border (flat, no shadow). */
    public static LinearLayout card(Context context) {
        return card(context, CARD_RADIUS_DP);
    }

    public static LinearLayout card(Context context, float radiusDp) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int radius = dp(context, Math.round(radiusDp));
        GradientDrawable content = new GradientDrawable();
        content.setColor(UiColors.surface(context));
        content.setCornerRadius(radius);
        content.setStroke(dp(context, 1), UiColors.outline(context));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(UiColors.ripple(context)),
                content,
                mask);
        card.setBackground(ripple);
        card.setClickable(true);
        card.setFocusable(true);
        flatten(card);
        return card;
    }

    public static void cardPadding(View card, int padDp) {
        int p = dp(card.getContext(), padDp);
        card.setPadding(p, p, p, p);
    }

    /** Flat dialog panel (single surface, hairline border, no nested cards). */
    public static LinearLayout dialogPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int radius = dp(context, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.surface(context));
        bg.setCornerRadius(radius);
        bg.setStroke(dp(context, 1), UiColors.outline(context));
        panel.setBackground(bg);
        flatten(panel);
        return panel;
    }

    public static void applyDialogWindow(android.app.Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.58f);
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        int margin = dp(dialog.getContext(), 24);
        params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
        params.horizontalMargin = margin;
        window.setAttributes(params);
        flatten(window.getDecorView());
    }

    /** Build and show a flat themed dialog with transparent window chrome. */
    public static android.app.AlertDialog showDialog(
            Activity activity,
            View content
    ) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
                activity,
                UiColors.dialogTheme(activity))
                .setView(content)
                .create();
        dialog.show();
        applyDialogWindow(dialog);
        return dialog;
    }

    // ---------------------------------------------------------------- typography

    public static TextView display(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(30f);
        v.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        v.setTextColor(UiColors.text(context));
        return v;
    }

    public static TextView title(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(20f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        v.setTextColor(UiColors.text(context));
        return v;
    }

    public static TextView section(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(13f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        v.setTextColor(UiColors.muted(context));
        v.setLetterSpacing(0.06f);
        v.setPadding(0, dp(context, 24), 0, dp(context, 10));
        return v;
    }

    public static TextView body(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(14f);
        v.setTextColor(UiColors.text(context));
        v.setLineSpacing(0, 1.25f);
        return v;
    }

    public static TextView muted(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(13f);
        v.setTextColor(UiColors.muted(context));
        v.setLineSpacing(0, 1.2f);
        return v;
    }

    public static TextView small(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(12f);
        v.setTextColor(UiColors.muted(context));
        return v;
    }

    /** Small pill badge (e.g. install status). */
    public static TextView badge(Context context, CharSequence text, boolean positive) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(11f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        v.setTextColor(positive ? UiColors.onAccent(context) : UiColors.muted(context));
        int bgColor = positive ? UiColors.accent(context) : UiColors.surfaceVariant(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(context, 20));
        v.setBackground(bg);
        int h = dp(context, 12);
        int w = dp(context, 12);
        v.setPadding(w, h / 2, w, h / 2);
        return v;
    }

    /** Status dot used next to status text. */
    public static View statusDot(Context context, int color) {
        View dot = new View(context);
        int size = dp(context, 8);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setShape(GradientDrawable.OVAL);
        dot.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = dp(context, 2);
        dot.setLayoutParams(lp);
        return dot;
    }

    // ---------------------------------------------------------------- buttons

    /** Filled primary button (accent background). */
    public static Button filledButton(Context context, CharSequence text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(UiColors.onAccent(context));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.accent(context));
        bg.setCornerRadius(dp(context, Math.round(BUTTON_RADIUS_DP)));
        b.setBackground(bg);
        int h = dp(context, 14);
        int w = dp(context, 16);
        b.setPadding(w, h, w, h);
        flatten(b);
        return b;
    }

    /** Tonal button (primary container fill). */
    public static Button tonalButton(Context context, CharSequence text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(UiColors.onAccentContainer(context));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.accentContainer(context));
        bg.setCornerRadius(dp(context, Math.round(BUTTON_RADIUS_DP)));
        b.setBackground(bg);
        int h = dp(context, 14);
        int w = dp(context, 16);
        b.setPadding(w, h, w, h);
        flatten(b);
        return b;
    }

    /** Outlined button (transparent fill, accent border). */
    public static Button outlinedButton(Context context, CharSequence text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(UiColors.accent(context));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(context, Math.round(BUTTON_RADIUS_DP)));
        bg.setStroke(dp(context, 1), UiColors.outline(context));
        b.setBackground(bg);
        int h = dp(context, 14);
        int w = dp(context, 16);
        b.setPadding(w, h, w, h);
        flatten(b);
        return b;
    }

    /** Text button (no fill). */
    public static Button textButton(Context context, CharSequence text) {
        Button b = new Button(context);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(UiColors.accent(context));
        b.setBackground(null);
        b.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        flatten(b);
        return b;
    }

    // ---------------------------------------------------------------- inputs

    /** Outlined text field with hint. */
    public static EditText field(Context context, CharSequence hint) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setHintTextColor(UiColors.muted(context));
        input.setTextColor(UiColors.text(context));
        input.setSingleLine(true);
        input.setTextSize(15f);
        input.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiColors.surface(context));
        bg.setCornerRadius(dp(context, Math.round(FIELD_RADIUS_DP)));
        bg.setStroke(dp(context, 1), UiColors.outline(context));
        input.setBackground(bg);
        flatten(input);
        return input;
    }

    /** Label above a field. */
    public static TextView fieldLabel(Context context, CharSequence text) {
        TextView v = new TextView(context);
        v.setText(text);
        v.setTextSize(13f);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setTextColor(UiColors.muted(context));
        v.setPadding(dp(context, 4), dp(context, 14), 0, dp(context, 6));
        return v;
    }

    public static CheckBox checkBox(Context context, CharSequence text) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setTextColor(UiColors.text(context));
        box.setTextSize(14f);
        box.setButtonTintList(ColorStateList.valueOf(UiColors.accent(context)));
        return box;
    }

    // ---------------------------------------------------------------- app icon

    /** App icon for an adapted entry (installed launcher icon, else bundled fallback). */
    public static View appIconForEntry(Context context, AdaptedAppRegistry.Entry entry) {
        return appIconForEntry(context, entry, 40);
    }

    public static View appIconForEntry(Context context, AdaptedAppRegistry.Entry entry, int sizeDp) {
        Drawable icon = resolveInstalledIcon(context, entry);
        if (icon == null) {
            icon = bundledIcon(context, entry.iconRes);
        }
        return appIconDrawable(context, icon, sizeDp);
    }

    /** App icon (installed launcher icon, else bundled fallback). */
    public static View appIcon(Context context, String packageName, int fallbackIconRes) {
        Drawable icon = null;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            icon = context.getPackageManager().getApplicationIcon(info);
        } catch (Throwable ignored) {
        }
        if (icon == null) {
            icon = bundledIcon(context, fallbackIconRes);
        }
        return appIconDrawable(context, icon, 40);
    }

    private static Drawable bundledIcon(Context context, int iconRes) {
        if (iconRes == 0) {
            return null;
        }
        try {
            return context.getDrawable(iconRes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable resolveInstalledIcon(Context context, AdaptedAppRegistry.Entry entry) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : AdaptedAppRegistry.allPackages(entry)) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) == null) {
                    continue;
                }
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                return pm.getApplicationIcon(info);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ImageView appIconDrawable(Context context, Drawable icon, int sizeDp) {
        if (icon == null) {
            icon = context.getDrawable(android.R.drawable.sym_def_app_icon);
        }
        int size = dp(context, sizeDp);
        int radius = dp(context, Math.max(8, sizeDp / 4));
        ImageView image = new ImageView(context);
        image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setImageDrawable(icon);
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        flatten(image);
        return image;
    }

    /** Rounded corners on any view (e.g. badges inside cards). */
    public static GradientDrawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(context, radiusDp));
        return bg;
    }

    // ---------------------------------------------------------------- chrome

    /** Tint the status bar to the theme background and pick light/dark icons. */
    public static void applyStatusBar(Activity activity) {
        try {
            Window window = activity.getWindow();
            window.setStatusBarColor(UiColors.bg(activity));
            window.setNavigationBarColor(UiColors.bg(activity));
            boolean dark = UiColors.isDark(activity);
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            int lightStatus = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            int lightNav = android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (dark) {
                flags &= ~lightStatus;
                flags &= ~lightNav;
            } else {
                flags |= lightStatus;
                flags |= lightNav;
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    public static int accent(Context context) {
        return UiColors.accent(context);
    }
}
