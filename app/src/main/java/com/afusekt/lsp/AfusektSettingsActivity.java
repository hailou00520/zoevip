package com.afusekt.lsp;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.sync.WebDavHttpClient;
import com.afusekt.lsp.sync.WebDavPrefs;
import com.afusekt.lsp.sync.WebDavSyncConfig;
import com.afusekt.lsp.ui.UiColors;
import com.afusekt.lsp.ui.UiKit;

/** Per-app settings for Afusekt within ZoeVIP. */
public final class AfusektSettingsActivity extends Activity {

    private CheckBox enabledBox;
    private EditText baseUrlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private EditText remotePathInput;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (savedInstanceState != null) {
            restoreFields(savedInstanceState);
        } else {
            load();
        }
        refreshStatus(null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("enabled", enabledBox.isChecked());
        outState.putString("base_url", textOf(baseUrlInput));
        outState.putString("username", textOf(usernameInput));
        outState.putString("password", textOf(passwordInput));
        outState.putString("remote_path", textOf(remotePathInput));
    }

    private void buildUi() {
        ScrollView scrollView = UiKit.scrollRoot(this);
        LinearLayout root = UiKit.column(this);
        UiKit.attach(scrollView, root);
        setContentView(scrollView);
        UiKit.applyStatusBar(this);

        // ---- header with back ----
        Button back = UiKit.textButton(this, "‹ 返回");
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        backLp.rightMargin = UiKit.dp(this, 8);
        root.addView(back, backLp);
        back.setOnClickListener(v -> finish());

        TextView title = UiKit.display(this, "Afusekt");
        root.addView(title);

        TextView hint = UiKit.muted(this,
                "PRO/VIP 本地解锁已默认启用（需在 LSPosed 勾选 Afusekt 作用域）。\n\n"
                        + "下方可把「同步资源库」改走你自己的 WebDAV；刮削按每个资源库绑定备份。");
        LinearLayout.LayoutParams hintLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 6));
        root.addView(hint, hintLp);

        // ---- WebDAV card ----
        LinearLayout card = UiKit.card(this);
        UiKit.cardPadding(card, 16);
        LinearLayout.LayoutParams cardLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 20));
        root.addView(card, cardLp);

        card.addView(UiKit.title(this, "WebDAV 同步"));

        enabledBox = UiKit.checkBox(this, "启用 WebDAV 同步");
        enabledBox.setOnCheckedChangeListener((buttonView, isChecked) -> refreshStatus(null));
        LinearLayout.LayoutParams boxLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 10));
        card.addView(enabledBox, boxLp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusRowLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 6));
        card.addView(statusRow, statusRowLp);

        statusView = new TextView(this);
        statusView.setTextSize(14f);
        LinearLayout.LayoutParams statusLp = UiKit.matchWrap();
        statusLp.leftMargin = UiKit.dp(this, 8);
        statusRow.addView(UiKit.statusDot(this, UiColors.accent(this)));
        statusRow.addView(statusView, statusLp);

        baseUrlInput = addField(card, "WebDAV 地址", "https://nas.example.com/dav/");
        usernameInput = addField(card, "用户名", "");
        passwordInput = addField(card, "密码", "");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        remotePathInput = addField(card, "远程文件名", WebDavSyncConfig.DEFAULT_REMOTE_PATH);

        Button saveButton = UiKit.filledButton(this, "保存");
        LinearLayout.LayoutParams saveLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 20));
        card.addView(saveButton, saveLp);
        saveButton.setOnClickListener(v -> save(false));

        Button testButton = UiKit.tonalButton(this, "测试连接");
        LinearLayout.LayoutParams testLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 10));
        card.addView(testButton, testLp);
        testButton.setOnClickListener(v -> testConnection());
    }

    private EditText addField(LinearLayout parent, String label, String placeholder) {
        parent.addView(UiKit.fieldLabel(this, label));
        EditText input = UiKit.field(this, placeholder);
        parent.addView(input, UiKit.matchWrap());
        return input;
    }

    private void load() {
        var prefs = WebDavPrefs.openModule(this);
        enabledBox.setChecked(prefs.getBoolean(WebDavPrefs.KEY_ENABLED, false));
        baseUrlInput.setText(prefs.getString(WebDavPrefs.KEY_BASE_URL, ""));
        usernameInput.setText(prefs.getString(WebDavPrefs.KEY_USERNAME, ""));
        passwordInput.setText(prefs.getString(WebDavPrefs.KEY_PASSWORD, ""));
        remotePathInput.setText(prefs.getString(
                WebDavPrefs.KEY_REMOTE_PATH,
                WebDavSyncConfig.DEFAULT_REMOTE_PATH
        ));
    }

    private void restoreFields(Bundle state) {
        enabledBox.setChecked(state.getBoolean("enabled", false));
        baseUrlInput.setText(state.getString("base_url", ""));
        usernameInput.setText(state.getString("username", ""));
        passwordInput.setText(state.getString("password", ""));
        remotePathInput.setText(state.getString("remote_path", WebDavSyncConfig.DEFAULT_REMOTE_PATH));
    }

    private boolean save(boolean quiet) {
        boolean ok = WebDavPrefs.saveModule(
                this,
                enabledBox.isChecked(),
                textOf(baseUrlInput),
                textOf(usernameInput),
                textOf(passwordInput),
                textOf(remotePathInput)
        );
        if (!quiet) {
            Toast.makeText(
                    this,
                    ok ? "已保存。若目标软件已在运行，配置会自动同步；否则请重新打开。" : "保存失败，请重试",
                    Toast.LENGTH_LONG
            ).show();
        }
        if (ok) {
            load();
        }
        return ok;
    }

    private void testConnection() {
        if (!save(true)) {
            Toast.makeText(this, "保存失败，无法测试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!WebDavPrefs.isConfiguredLocal(this)) {
            Toast.makeText(this, "请先填写地址、用户名并勾选启用", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshStatus("正在测试连接…");
        Toast.makeText(this, "正在测试连接…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                WebDavHttpClient.Response response = WebDavHttpClient.get(
                        WebDavPrefs.buildRemoteUrlLocal(this),
                        WebDavPrefs.getUsernameLocal(this),
                        WebDavPrefs.getPasswordLocal(this)
                );
                runOnUiThread(() -> showTestResult(response.code));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    refreshStatus("未连接");
                    Toast.makeText(this, "测试连接失败：网络异常或地址不对", Toast.LENGTH_LONG).show();
                });
            }
        }, "webdav-test").start();
    }

    private void showTestResult(int code) {
        if (code >= 200 && code < 300) {
            refreshStatus("已连接");
            Toast.makeText(this, "测试连接成功，已连接", Toast.LENGTH_LONG).show();
            return;
        }
        if (code == 404) {
            // Account works; sync file not uploaded yet.
            refreshStatus("已连接（同步文件稍后上传会自动创建）");
            Toast.makeText(this, "测试连接成功。还没有同步文件，上传后会自动创建", Toast.LENGTH_LONG).show();
            return;
        }
        if (code == 401 || code == 403) {
            refreshStatus("未连接");
            Toast.makeText(this, "测试连接失败：账号或密码不正确", Toast.LENGTH_LONG).show();
            return;
        }
        refreshStatus("未连接");
        Toast.makeText(this, "测试连接失败，请检查地址和网络", Toast.LENGTH_LONG).show();
    }

    private void refreshStatus(String override) {
        if (statusView == null) {
            return;
        }
        int accent = UiColors.accent(this);
        int muted = UiColors.muted(this);
        if (override != null) {
            statusView.setText(override);
            statusView.setTextColor(override.startsWith("已连接") ? accent : muted);
            return;
        }
        if (WebDavPrefs.isConfiguredLocal(this)) {
            statusView.setText("WebDAV 同步已启用");
            statusView.setTextColor(accent);
        } else if (isConfiguredInUi()) {
            statusView.setText("已填写，请点击「保存」生效");
            statusView.setTextColor(accent);
        } else if (!enabledBox.isChecked()) {
            statusView.setText("未启用 WebDAV（Afusekt 同步资源库走官方云端）");
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

    private static String textOf(EditText input) {
        return input.getText().toString();
    }
}
