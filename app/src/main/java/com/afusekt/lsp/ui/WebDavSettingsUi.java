package com.afusekt.lsp.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.sync.WebDavConfigAccess;
import com.afusekt.lsp.sync.WebDavConfigSnapshot;
import com.afusekt.lsp.sync.WebDavHttpClient;
import com.afusekt.lsp.sync.WebDavPrefs;
import com.afusekt.lsp.sync.WebDavSyncConfig;

import android.util.Log;

/** WebDAV settings UI shown inside Afusekt (or ZoeVIP module app). */
public final class WebDavSettingsUi {

    private WebDavSettingsUi() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            if (AfusektMaterialUi.isAfusekt(activity)) {
                showAfusektDialog(activity);
            } else {
                showModuleDialog(activity);
            }
        } catch (Throwable t) {
            Log.e(MainHook.TAG, "WebDAV settings open failed", t);
            Toast.makeText(activity, "无法打开 WebDAV 设置", Toast.LENGTH_LONG).show();
        }
    }

    private static void showAfusektDialog(Activity activity) {
        Controller controller = new Controller(activity);
        ScrollView form = controller.buildAfusektForm(controller::testConnection);
        try {
            Object builder = AfusektMaterialUi.newDialogBuilder(activity);
            AfusektMaterialUi.setDialogTitle(builder, "WebDAV 同步");
            AfusektMaterialUi.setDialogView(builder, form);
            AfusektMaterialUi.setDialogPositiveButton(builder, "保存", (dialog, which) -> {
                if (!controller.save(false)) {
                    Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show();
                }
            });
            AfusektMaterialUi.setDialogNegativeButton(builder, "取消", null);
            AfusektMaterialUi.showDialog(AfusektMaterialUi.createDialog(builder), activity);
        } catch (Throwable materialError) {
            AfusektMaterialUi.logFailure("material dialog", materialError);
            AlertDialog fallback = new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                    .setTitle("WebDAV 同步")
                    .setView(form)
                    .setPositiveButton("保存", (dialog, which) -> {
                        if (!controller.save(false)) {
                            Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .create();
            AfusektMaterialUi.showDialog(fallback, activity);
        }
    }

    private static void showModuleDialog(Activity activity) {
        Controller controller = new Controller(activity);
        ScrollView scrollView = controller.buildModuleForm(controller::testConnection);
        new AlertDialog.Builder(activity, UiColors.dialogTheme(activity))
                .setTitle("WebDAV 同步")
                .setView(scrollView)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (!controller.save(false)) {
                        Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static final class Controller {
        private final Activity activity;
        private CheckBox enabledBox;
        private EditText baseUrlInput;
        private EditText usernameInput;
        private EditText passwordInput;
        private EditText remotePathInput;
        private TextView statusView;

        Controller(Activity activity) {
            this.activity = activity;
        }

        ScrollView buildAfusektForm(Runnable onTest) {
            LinearLayout root = AfusektMaterialUi.newFormRoot(activity);
            root.addView(
                    AfusektMaterialUi.newDescription(
                            activity,
                            "将「同步资源库」改为你自己的 WebDAV 服务器，无需官方 Pro 云端。"
                    ),
                    AfusektMaterialUi.matchWrapLinear()
            );

            enabledBox = AfusektMaterialUi.newCheckBox(activity, "启用 WebDAV 同步");
            enabledBox.setOnCheckedChangeListener((buttonView, isChecked) -> refreshStatus(null));
            root.addView(enabledBox, AfusektMaterialUi.matchWrapLinear());

            statusView = new TextView(activity);
            statusView.setTextSize(14f);
            statusView.setPadding(0, AfusektMaterialUi.dp(activity, 8), 0, AfusektMaterialUi.dp(activity, 4));
            root.addView(statusView, AfusektMaterialUi.matchWrapLinear());

            AfusektMaterialUi.TextField baseUrl = AfusektMaterialUi.newClearTextField(
                    activity,
                    "WebDAV 地址"
            );
            AfusektMaterialUi.TextField username = AfusektMaterialUi.newClearTextField(
                    activity,
                    "用户名"
            );
            AfusektMaterialUi.TextField password = AfusektMaterialUi.newPasswordField(
                    activity,
                    "密码"
            );
            AfusektMaterialUi.TextField remotePath = AfusektMaterialUi.newClearTextField(
                    activity,
                    "远程文件名"
            );

            baseUrlInput = baseUrl.input;
            usernameInput = username.input;
            passwordInput = password.input;
            remotePathInput = remotePath.input;

            AfusektMaterialUi.addField(root, baseUrl.layout);
            AfusektMaterialUi.addField(root, username.layout);
            AfusektMaterialUi.addField(root, password.layout);
            AfusektMaterialUi.addField(root, remotePath.layout);

            Button testButton = AfusektMaterialUi.newOutlinedButton(activity, "测试连接");
            testButton.setOnClickListener(v -> onTest.run());
            AfusektMaterialUi.addActionButton(root, testButton);

            load();
            refreshStatus(null);

            ScrollView scrollView = new ScrollView(activity);
            scrollView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            scrollView.addView(root, matchWrap());
            return scrollView;
        }

        ScrollView buildModuleForm(Runnable onTest) {
            int bg = UiColors.bg(activity);
            int accent = UiColors.accent(activity);

            ScrollView scrollView = new ScrollView(activity);
            scrollView.setBackgroundColor(bg);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = UiKit.dp(activity, 16);
            root.setPadding(pad, pad, pad, pad);
            scrollView.addView(root, matchWrap());

            TextView hint = new TextView(activity);
            hint.setText("将「同步资源库」改为你自己的 WebDAV 服务器，无需官方 Pro 云端。");
            hint.setTextColor(UiColors.muted(activity));
            hint.setTextSize(14f);
            hint.setLineSpacing(0, 1.25f);
            hint.setPadding(0, 0, 0, UiKit.dp(activity, 12));
            root.addView(hint);

            enabledBox = UiKit.checkBox(activity, "启用 WebDAV 同步");
            enabledBox.setOnCheckedChangeListener((buttonView, isChecked) -> refreshStatus(null));
            root.addView(enabledBox, UiKit.matchWrap());

            LinearLayout statusRow = new LinearLayout(activity);
            statusRow.setOrientation(LinearLayout.HORIZONTAL);
            statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams statusRowLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 6));
            root.addView(statusRow, statusRowLp);

            statusView = new TextView(activity);
            statusView.setTextSize(14f);
            LinearLayout.LayoutParams statusLp = UiKit.matchWrap();
            statusLp.leftMargin = UiKit.dp(activity, 8);
            statusRow.addView(UiKit.statusDot(activity, accent));
            statusRow.addView(statusView, statusLp);

            root.addView(UiKit.fieldLabel(activity, "WebDAV 地址"));
            baseUrlInput = UiKit.field(activity, "https://nas.example.com/dav/");
            root.addView(baseUrlInput, UiKit.matchWrap());
            root.addView(UiKit.fieldLabel(activity, "用户名"));
            usernameInput = UiKit.field(activity, "");
            root.addView(usernameInput, UiKit.matchWrap());
            root.addView(UiKit.fieldLabel(activity, "密码"));
            passwordInput = UiKit.field(activity, "");
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            root.addView(passwordInput, UiKit.matchWrap());
            root.addView(UiKit.fieldLabel(activity, "远程文件名"));
            remotePathInput = UiKit.field(activity, WebDavSyncConfig.DEFAULT_REMOTE_PATH);
            root.addView(remotePathInput, UiKit.matchWrap());

            Button testButton = UiKit.tonalButton(activity, "测试连接");
            testButton.setOnClickListener(v -> onTest.run());
            LinearLayout.LayoutParams testLp = UiKit.matchWrapTopMargin(UiKit.dp(activity, 20));
            root.addView(testButton, testLp);

            load();
            refreshStatus(null);
            return scrollView;
        }

        private void load() {
            WebDavConfigSnapshot snapshot = WebDavConfigAccess.load(activity);
            enabledBox.setChecked(snapshot.enabled);
            baseUrlInput.setText(snapshot.baseUrl);
            usernameInput.setText(snapshot.username);
            passwordInput.setText(snapshot.password);
            remotePathInput.setText(snapshot.remotePath);
        }

        boolean save(boolean quiet) {
            WebDavConfigSnapshot snapshot = new WebDavConfigSnapshot(
                    enabledBox.isChecked(),
                    textOf(baseUrlInput),
                    textOf(usernameInput),
                    textOf(passwordInput),
                    textOf(remotePathInput)
            );
            boolean ok = WebDavConfigAccess.save(activity, snapshot);
            if (!quiet) {
                Toast.makeText(
                        activity,
                        ok ? "已保存，可直接同步资源库" : "保存失败，请重试",
                        Toast.LENGTH_LONG
                ).show();
            }
            if (ok) {
                load();
                refreshStatus(null);
            }
            return ok;
        }

        void testConnection() {
            if (!save(true)) {
                Toast.makeText(activity, "保存失败，无法测试", Toast.LENGTH_SHORT).show();
                return;
            }
            WebDavConfigSnapshot snapshot = WebDavConfigAccess.load(activity);
            if (!snapshot.isConfigured()) {
                Toast.makeText(activity, "请先填写地址、用户名并勾选启用", Toast.LENGTH_SHORT).show();
                return;
            }
            refreshStatus("正在测试连接…");
            new Thread(() -> {
                try {
                    String base = WebDavPrefs.trimTrailingSlash(snapshot.baseUrl);
                    String path = snapshot.remotePath.replace('\\', '/');
                    String url = base.endsWith("/") ? base + path : base + "/" + path;
                    WebDavHttpClient.Response response = WebDavHttpClient.get(
                            url,
                            snapshot.username,
                            snapshot.password
                    );
                    activity.runOnUiThread(() -> showTestResult(response.code));
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        refreshStatus("未连接");
                        Toast.makeText(activity, "测试连接失败：网络异常或地址不对", Toast.LENGTH_LONG).show();
                    });
                }
            }, "webdav-test").start();
        }

        private void showTestResult(int code) {
            if (code >= 200 && code < 300) {
                refreshStatus("已连接");
                Toast.makeText(activity, "测试连接成功，已连接", Toast.LENGTH_LONG).show();
                return;
            }
            if (code == 404) {
                refreshStatus("已连接（同步文件稍后上传会自动创建）");
                Toast.makeText(activity, "测试连接成功。还没有同步文件，上传后会自动创建", Toast.LENGTH_LONG).show();
                return;
            }
            if (code == 401 || code == 403) {
                refreshStatus("未连接");
                Toast.makeText(activity, "测试连接失败：账号或密码不正确", Toast.LENGTH_LONG).show();
                return;
            }
            refreshStatus("未连接");
            Toast.makeText(activity, "测试连接失败，请检查地址和网络", Toast.LENGTH_LONG).show();
        }

        private void refreshStatus(String override) {
            int accent = UiColors.accent(activity);
            int muted = UiColors.muted(activity);
            if (override != null) {
                statusView.setText(override);
                statusView.setTextColor(override.startsWith("已连接") ? accent : muted);
                return;
            }
            if (WebDavConfigAccess.isConfigured(activity)) {
                statusView.setText("WebDAV 同步已启用");
                statusView.setTextColor(accent);
            } else if (isConfiguredInUi()) {
                statusView.setText("已填写，请点击「保存」生效");
                statusView.setTextColor(accent);
            } else if (!enabledBox.isChecked()) {
                statusView.setText("未启用（同步资源库走官方云端）");
                statusView.setTextColor(muted);
            } else {
                statusView.setText("请填写地址和用户名后保存");
                statusView.setTextColor(muted);
            }
        }

        private boolean isConfiguredInUi() {
            return enabledBox.isChecked()
                    && !textOf(baseUrlInput).trim().isEmpty()
                    && !textOf(usernameInput).trim().isEmpty();
        }

        private ViewGroup.LayoutParams matchWrap() {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private static String textOf(EditText input) {
        return input.getText().toString();
    }
}
