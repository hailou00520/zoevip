package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.afusekt.lsp.MainHook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Builds Material 3 widgets using Afusekt's bundled Material Components (no XposedBridge). */
final class AfusektMaterialUi {

    private static final int END_ICON_CLEAR_TEXT = 2;
    private static final int END_ICON_PASSWORD_TOGGLE = 1;

    private AfusektMaterialUi() {
    }

    static boolean isAfusekt(Context context) {
        return MainHook.AFUSEKT_PACKAGE.equals(context.getPackageName());
    }

    static Object newDialogBuilder(Activity activity) {
        Class<?> builderClass = findClass(
                activity,
                "com.google.android.material.dialog.MaterialAlertDialogBuilder"
        );
        return newInstance(builderClass, activity, 0);
    }

    static void setDialogTitle(Object builder, CharSequence title) {
        invokeBuilder(builder, new String[]{"r", "setTitle"}, title);
    }

    static void setDialogView(Object builder, View view) {
        invokeBuilder(builder, new String[]{"t", "setView"}, view);
    }

    static void setDialogPositiveButton(
            Object builder,
            CharSequence label,
            DialogInterface.OnClickListener listener
    ) {
        try {
            callMethod(builder, "p", label, listener);
        } catch (Throwable ignored) {
            callMethod(builder, "f", label, listener);
        }
    }

    static void setDialogNegativeButton(
            Object builder,
            CharSequence label,
            DialogInterface.OnClickListener listener
    ) {
        try {
            callMethod(builder, "m", String.valueOf(label), listener);
        } catch (Throwable ignored) {
            callMethod(builder, "e", label, listener);
        }
    }

    static android.app.AlertDialog createDialog(Object builder) {
        return (android.app.AlertDialog) callMethod(builder, "create");
    }

    static void showDialog(AlertDialog dialog, Activity activity) {
        dialog.show();
        applyDialogRounding(dialog, activity);
    }

    static void applyDialogRounding(AlertDialog dialog, Activity activity) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        int radius = dialogCornerRadius(activity);
        int surface = UiColors.dialogSurface(activity);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        View decor = window.getDecorView();
        decor.setBackgroundColor(Color.TRANSPARENT);

        View panel = decor.findViewById(resourceId(activity, "parentPanel"));
        if (panel == null) {
            panel = decor;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(radius);
        panel.setBackground(background);
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        float elevation = dimen(activity, "m3_alert_dialog_elevation", dp(activity, 6));
        panel.setElevation(elevation);

        View scroll = panel.findViewById(resourceId(activity, "scrollView"));
        if (scroll != null) {
            scroll.setBackgroundColor(Color.TRANSPARENT);
        }
        View custom = panel.findViewById(resourceId(activity, "custom"));
        if (custom != null) {
            custom.setBackgroundColor(Color.TRANSPARENT);
        }
        View customPanel = panel.findViewById(resourceId(activity, "customPanel"));
        if (customPanel != null) {
            customPanel.setBackgroundColor(Color.TRANSPARENT);
        }
        View contentPanel = panel.findViewById(resourceId(activity, "contentPanel"));
        if (contentPanel != null) {
            contentPanel.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    static int dialogCornerRadius(Context context) {
        int themed = UiColors.themeDimension(context, "dialogCornerRadius", 0);
        if (themed > 0) {
            return themed;
        }
        return dimen(
                context,
                "m3_sys_shape_corner_value_extra_large",
                dp(context, 28)
        );
    }

    static CheckBox newCheckBox(Activity activity, CharSequence label) {
        try {
            Class<?> checkBoxClass = findClass(
                    activity,
                    "com.google.android.material.checkbox.MaterialCheckBox"
            );
            CheckBox checkBox = (CheckBox) newInstance(checkBoxClass, activity);
            checkBox.setText(label);
            return checkBox;
        } catch (Throwable ignored) {
            CheckBox checkBox = new CheckBox(activity);
            checkBox.setText(label);
            checkBox.setTextColor(UiColors.text(activity));
            return checkBox;
        }
    }

    static TextView newDescription(Activity activity, CharSequence text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(UiColors.muted(activity));
        view.setTextSize(14f);
        view.setLineSpacing(0, 1.25f);
        view.setPadding(0, 0, 0, dp(activity, 8));
        return view;
    }

    static Button newOutlinedButton(Activity activity, CharSequence text) {
        try {
            Class<?> buttonClass = findClass(
                    activity,
                    "com.google.android.material.button.MaterialButton"
            );
            Button button = (Button) newInstance(buttonClass, activity);
            button.setText(text);
            button.setAllCaps(false);
            callMethod(button, "setCornerRadius", (float) cornerRadius(activity));
            int accent = UiColors.accent(activity);
            callMethod(button, "setStrokeColor", ColorStateList.valueOf(accent));
            callMethod(button, "setStrokeWidth", dp(activity, 1));
            callMethod(
                    button,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(UiColors.dialogSurface(activity))
            );
            button.setTextColor(accent);
            int padH = dp(activity, 16);
            int padV = dp(activity, 12);
            button.setPadding(padH, padV, padH, padV);
            return button;
        } catch (Throwable ignored) {
            Button button = new Button(activity);
            button.setText(text);
            button.setAllCaps(false);
            button.setTextColor(UiColors.accent(activity));
            return button;
        }
    }

    static TextField newTextField(Activity activity, CharSequence hint, int endIconMode) {
        Class<?> layoutClass = findClass(
                activity,
                "com.google.android.material.textfield.TextInputLayout"
        );
        Class<?> editClass = findClass(
                activity,
                "com.google.android.material.textfield.TextInputEditText"
        );
        View layout = (View) newInstance(layoutClass, activity);
        callMethod(layout, "setHint", hint);
        if (endIconMode >= 0) {
            callMethod(layout, "setEndIconMode", endIconMode);
        }
        EditText input = (EditText) newInstance(editClass, activity);
        callMethod(layout, "addView", input);
        layout.setLayoutParams(matchWrap());
        return new TextField(layout, input);
    }

    static TextField newClearTextField(Activity activity, CharSequence hint) {
        return newTextField(activity, hint, END_ICON_CLEAR_TEXT);
    }

    static TextField newPasswordField(Activity activity, CharSequence hint) {
        return newTextField(activity, hint, END_ICON_PASSWORD_TOGGLE);
    }

    static LinearLayout newFormRoot(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dimen(activity, "abc_dialog_padding_material", dp(activity, 16));
        int padV = dimen(activity, "abc_dialog_padding_top_material", dp(activity, 12));
        root.setPadding(padH, padV, padH, dimen(activity, "m3_alert_dialog_action_top_padding", dp(activity, 14)));
        return root;
    }

    static void addField(LinearLayout parent, View field) {
        LinearLayout.LayoutParams lp = matchWrapLinear();
        lp.topMargin = dimen(parent.getContext(), "m3_btn_dialog_btn_spacing", dp(parent.getContext(), 8));
        parent.addView(field, lp);
    }

    static void addActionButton(LinearLayout parent, View button) {
        LinearLayout.LayoutParams lp = matchWrapLinear();
        lp.topMargin = dimen(parent.getContext(), "m3_btn_dialog_btn_spacing", dp(parent.getContext(), 8))
                + dp(parent.getContext(), 12);
        parent.addView(button, lp);
    }

    static LinearLayout.LayoutParams matchWrapLinear() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    static int cornerRadius(Context context) {
        return dimen(context, "poster_radius", dp(context, 10));
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static void logFailure(String stage, Throwable error) {
        Log.e(MainHook.TAG, "WebDAV UI " + stage + ": " + error, error);
    }

    private static void invokeBuilder(Object builder, String[] methodNames, Object arg) {
        Throwable last = null;
        for (String methodName : methodNames) {
            try {
                callMethod(builder, methodName, arg);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            throw new RuntimeException(last);
        }
    }

    private static int resourceId(Context context, String name) {
        return context.getResources().getIdentifier(
                name,
                "id",
                MainHook.AFUSEKT_PACKAGE
        );
    }

    private static int dimen(Context context, String name, int fallback) {
        int id = context.getResources().getIdentifier(
                name,
                "dimen",
                MainHook.AFUSEKT_PACKAGE
        );
        if (id == 0) {
            return fallback;
        }
        return context.getResources().getDimensionPixelSize(id);
    }

    private static Class<?> findClass(Activity activity, String name) {
        try {
            return Class.forName(name, false, activity.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object newInstance(Class<?> cls, Object... args) {
        try {
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length != args.length) {
                    continue;
                }
                boolean match = true;
                for (int i = 0; i < types.length; i++) {
                    if (args[i] == null) {
                        if (types[i].isPrimitive()) {
                            match = false;
                            break;
                        }
                        continue;
                    }
                    if (!box(types[i]).isInstance(args[i]) && !types[i].isPrimitive()) {
                        // allow Context for Activity args etc via isAssignableFrom
                        if (!types[i].isAssignableFrom(args[i].getClass())) {
                            match = false;
                            break;
                        }
                    } else if (types[i].isPrimitive()) {
                        if (!box(types[i]).isInstance(args[i])) {
                            match = false;
                            break;
                        }
                    }
                }
                if (!match) {
                    continue;
                }
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
            throw new NoSuchMethodException("No matching constructor for " + cls.getName());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object callMethod(Object target, String name, Object... args) {
        try {
            Class<?> cls = target.getClass();
            while (cls != null) {
                for (Method method : cls.getDeclaredMethods()) {
                    if (!name.equals(method.getName())) {
                        continue;
                    }
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length != args.length) {
                        continue;
                    }
                    boolean match = true;
                    for (int i = 0; i < types.length; i++) {
                        if (args[i] == null) {
                            if (types[i].isPrimitive()) {
                                match = false;
                                break;
                            }
                            continue;
                        }
                        if (!types[i].isPrimitive()) {
                            if (!types[i].isAssignableFrom(args[i].getClass())) {
                                match = false;
                                break;
                            }
                        } else if (!box(types[i]).isInstance(args[i])) {
                            match = false;
                            break;
                        }
                    }
                    if (!match) {
                        continue;
                    }
                    method.setAccessible(true);
                    return method.invoke(target, args);
                }
                cls = cls.getSuperclass();
            }
            throw new NoSuchMethodException(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) {
            return Integer.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    static final class TextField {
        final View layout;
        final EditText input;

        TextField(View layout, EditText input) {
            this.layout = layout;
            this.input = input;
        }
    }
}
