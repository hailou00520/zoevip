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
 * whole app shares one look: system light/dark aware colors, rounded corners, elevation
 * shadows, ripple feedback and consistent typography.</p>
 */
public final class UiKit {

    public static final float CARD_RADIUS_DP = 16f;
    public static final float FIELD_RADIUS_DP = 12f;
    public static final float BUTTON_RADIUS_DP = 12f;

    private UiKit() {
    }

    // ---------------------------------------------------------------- dims

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

    // ---------------------------------------------------------------- cards

    /** Rounded surface card with ripple + elevation shadow. */
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
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(UiColors.ripple(context)),
                content,
                mask);
        card.setBackground(ripple);
        card.setElevation(dp(context, 2));
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    public static void cardPadding(View card, int padDp) {
        int p = dp(card.getContext(), padDp);
        card.setPadding(p, p, p, p);
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
        b.setElevation(dp(context, 2));
        int h = dp(context, 14);
        int w = dp(context, 16);
        b.setPadding(w, h, w, h);
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

    /** App icon (real installed icon, or letter avatar fallback). */
    public static View appIcon(Context context, String packageName, String fallbackText, int[] gradient) {
        Drawable icon = null;
        boolean installed = false;
        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            icon = context.getPackageManager().getApplicationIcon(info);
            installed = true;
        } catch (Throwable ignored) {
        }

        int size = dp(context, 44);
        if (installed && icon != null) {
            ImageView image = new ImageView(context);
            image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(icon);
            return image;
        }

        // Letter avatar with per-app gradient.
        TextView letter = new TextView(context);
        letter.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        letter.setText(fallbackText);
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(18f);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        letter.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{gradient[0], gradient[1]});
        bg.setCornerRadius(size / 2f);
        letter.setBackground(bg);
        return letter;
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
