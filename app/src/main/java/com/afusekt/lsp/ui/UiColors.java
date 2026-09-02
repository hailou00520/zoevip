package com.afusekt.lsp.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;

import com.afusekt.lsp.R;
import com.afusekt.lsp.sync.WebDavSyncConfig;

/** Theme-safe colors for UI shown inside Afusekt or ZoeVIP module app. */
public final class UiColors {

    private static final int FALLBACK_BG_LIGHT = 0xFFF9F9FF;
    private static final int FALLBACK_BG_DARK = 0xFF0F1419;
    private static final int FALLBACK_DIALOG_LIGHT = 0xFFE7E8EE;
    private static final int FALLBACK_SURFACE_LIGHT = 0xFFF5F5F5;
    private static final int FALLBACK_SURFACE_DARK = 0xFF1A2332;
    private static final int FALLBACK_ACCENT = 0xFF6750A4;
    private static final int FALLBACK_TEXT_LIGHT = 0xDE000000;
    private static final int FALLBACK_TEXT_DARK = 0xFFF2F5F8;
    private static final int FALLBACK_MUTED_LIGHT = 0x99000000;
    private static final int FALLBACK_MUTED_DARK = 0xFF8B9BB0;

    private UiColors() {
    }

    public static boolean isDark(Context context) {
        int uiMode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_bg, FALLBACK_BG_DARK);
        }
        int fromTheme = themeColor(context, "colorBackground", android.R.attr.colorBackground, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        int mdBackground = afusektColor(context, "md_theme_background", 0);
        if (mdBackground != 0) {
            return mdBackground;
        }
        return isDarkTheme(context) ? FALLBACK_BG_DARK : FALLBACK_BG_LIGHT;
    }

    /** Slightly elevated surface (e.g. tonal chips, input fills). */
    public static int surfaceVariant(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_surface_variant, FALLBACK_SURFACE_DARK);
        }
        int fromTheme = themeColor(context, "colorSurfaceVariant", 0, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return surface(context);
    }

    /** Accent-colored container (M3 primary container / tonal button fill). */
    public static int accentContainer(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_accent_container, FALLBACK_SURFACE_DARK);
        }
        int fromTheme = themeColor(context, "colorPrimaryContainer", 0, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return accent(context);
    }

    /** Text color that sits on accentContainer. */
    public static int onAccentContainer(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_on_accent_container, FALLBACK_TEXT_DARK);
        }
        int fromTheme = themeColor(context, "colorOnPrimaryContainer", 0, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return text(context);
    }

    /** Text on solid accent fill (primary button label). */
    public static int onAccent(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_on_accent, FALLBACK_BG_DARK);
        }
        int fromTheme = themeColor(context, "colorOnPrimary", android.R.attr.textColorPrimaryInverse, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return bg(context);
    }

    /** Ripple highlight color for cards/buttons. */
    public static int ripple(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_ripple, 0x263DDC97);
        }
        int accent = accent(context);
        return (accent & 0x00FFFFFF) | 0x26000000;
    }

    /** Hairline border color for outlined controls. */
    public static int outline(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_outline, FALLBACK_MUTED_DARK);
        }
        int fromTheme = themeColor(context, "colorOutline", 0, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return muted(context);
    }

    public static int dialogSurface(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_surface, FALLBACK_SURFACE_DARK);
        }
        int fromDialogTheme = themedColorFromStyle(context, dialogTheme(context), "backgroundTint");
        if (fromDialogTheme != 0) {
            return fromDialogTheme;
        }
        int containerHigh = themeColor(context, "colorSurfaceContainerHigh", 0, 0);
        if (containerHigh != 0) {
            return containerHigh;
        }
        int container = themeColor(context, "colorSurfaceContainer", 0, 0);
        if (container != 0) {
            return container;
        }
        int mdContainerHigh = afusektColor(context, "md_theme_surfaceContainerHigh", 0);
        if (mdContainerHigh != 0) {
            return mdContainerHigh;
        }
        int surface = themeColor(context, "colorSurface", 0, 0);
        if (surface != 0) {
            return surface;
        }
        return isDarkTheme(context) ? FALLBACK_BG_DARK : FALLBACK_DIALOG_LIGHT;
    }

    public static int surface(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_surface, FALLBACK_SURFACE_DARK);
        }
        int card = afusektColor(context, "list_item_card_back", 0);
        if (card != 0) {
            return card;
        }
        int fromTheme = themeColor(context, "colorSurfaceVariant", 0, 0);
        if (fromTheme != 0) {
            return fromTheme;
        }
        return isDarkTheme(context) ? FALLBACK_SURFACE_DARK : FALLBACK_SURFACE_LIGHT;
    }

    public static int accent(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_accent, FALLBACK_ACCENT);
        }
        int fromTheme = themeColor(context, "colorPrimary", android.R.attr.colorPrimary, 0);
        return fromTheme != 0 ? fromTheme : FALLBACK_ACCENT;
    }

    public static int text(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_text, FALLBACK_TEXT_DARK);
        }
        int fromTheme = themeColor(
                context,
                "colorOnSurface",
                android.R.attr.textColorPrimary,
                0
        );
        if (fromTheme != 0) {
            return fromTheme;
        }
        return isDarkTheme(context) ? FALLBACK_TEXT_DARK : FALLBACK_TEXT_LIGHT;
    }

    public static int muted(Context context) {
        if (isModuleApp(context)) {
            return moduleColor(context, R.color.zoe_muted, FALLBACK_MUTED_DARK);
        }
        int fromTheme = themeColor(
                context,
                "colorSecondary",
                android.R.attr.textColorSecondary,
                0
        );
        if (fromTheme != 0) {
            return fromTheme;
        }
        return isDarkTheme(context) ? FALLBACK_MUTED_DARK : FALLBACK_MUTED_LIGHT;
    }

    public static int cardBackground(Context context) {
        int fromAfusekt = afusektColor(context, "list_item_card_back", 0);
        if (fromAfusekt != 0) {
            return fromAfusekt;
        }
        return surface(context);
    }

    public static int dialogTheme(Context context) {
        if (isModuleApp(context)) {
            return R.style.Theme_ZoeVIP_Dialog;
        }
        int materialTheme = themeResource(context, "materialAlertDialogTheme", 0);
        if (materialTheme != 0) {
            return materialTheme;
        }
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.alertDialogTheme, value, true)
                && value.resourceId != 0) {
            return value.resourceId;
        }
        return isDarkTheme(context)
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    public static int themeDimension(Context context, String attrName, int fallback) {
        if (isModuleApp(context)) {
            return fallback;
        }
        int attr = resolveAttr(context, attrName, 0);
        if (attr == 0) {
            return fallback;
        }
        TypedArrayCompat array = new TypedArrayCompat(context, new int[]{attr});
        try {
            return array.getDimensionPixelSize(0, fallback);
        } finally {
            array.recycle();
        }
    }

    private static int themedColorFromStyle(Context context, int styleRes, String attrName) {
        if (styleRes == 0) {
            return 0;
        }
        int attr = resolveAttr(context, attrName, 0);
        if (attr == 0) {
            return 0;
        }
        TypedArrayCompat array = new TypedArrayCompat(context, styleRes, new int[]{attr});
        try {
            return array.getColor(0, 0);
        } finally {
            array.recycle();
        }
    }

    private static int themeResource(Context context, String attrName, int fallback) {
        int attr = resolveAttr(context, attrName, 0);
        if (attr == 0) {
            return fallback;
        }
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, value, true) && value.resourceId != 0) {
            return value.resourceId;
        }
        return fallback;
    }

    private static boolean isModuleApp(Context context) {
        return WebDavSyncConfig.MODULE_PACKAGE.equals(context.getPackageName());
    }

    private static boolean isDarkTheme(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.isLightTheme, value, true)) {
            return value.data == 0;
        }
        int bg = themeColor(context, "colorBackground", android.R.attr.colorBackground, FALLBACK_BG_LIGHT);
        return Color.luminance(bg) < 0.5f;
    }

    private static int moduleColor(Context context, int resId, int fallback) {
        if (!isModuleApp(context)) {
            return fallback;
        }
        try {
            return context.getResources().getColor(resId, context.getTheme());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int afusektColor(Context context, String name, int fallback) {
        if (isModuleApp(context)) {
            return fallback;
        }
        try {
            int id = context.getResources().getIdentifier(
                    name,
                    "color",
                    "com.attempt.afusekt"
            );
            if (id == 0) {
                return fallback;
            }
            return context.getResources().getColor(id, context.getTheme());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int themeColor(Context context, String attrName, int androidAttr, int fallback) {
        int attr = resolveAttr(context, attrName, androidAttr);
        if (attr == 0) {
            return fallback;
        }
        return styledColor(context, attr, fallback);
    }

    private static int resolveAttr(Context context, String attrName, int androidAttr) {
        if (!isModuleApp(context) && attrName != null) {
            int id = context.getResources().getIdentifier(
                    attrName,
                    "attr",
                    "com.attempt.afusekt"
            );
            if (id != 0) {
                return id;
            }
        }
        return androidAttr;
    }

    private static int styledColor(Context context, int attr, int fallback) {
        TypedArrayCompat array = new TypedArrayCompat(context, new int[]{attr});
        try {
            return array.getColor(0, fallback);
        } finally {
            array.recycle();
        }
    }

    /** Thin wrapper so we do not depend on androidx.appcompat at compile time. */
    private static final class TypedArrayCompat {
        private final android.content.res.TypedArray array;

        TypedArrayCompat(Context context, int[] attrs) {
            array = context.obtainStyledAttributes(attrs);
        }

        TypedArrayCompat(Context context, int themeRes, int[] attrs) {
            array = context.obtainStyledAttributes(themeRes, attrs);
        }

        int getColor(int index, int fallback) {
            return array.getColor(index, fallback);
        }

        int getDimensionPixelSize(int index, int fallback) {
            return array.getDimensionPixelSize(index, fallback);
        }

        void recycle() {
            array.recycle();
        }
    }
}
