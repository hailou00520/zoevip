package com.afusekt.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.ui.UiColors;
import com.afusekt.lsp.ui.UiKit;
import com.afusekt.lsp.ui.XimalayaSettingsUi;

/**
 * ZoeVIP home: header + list of adapted apps + shortcut into LSPosed.
 * Built from {@link UiKit} so it follows system light/dark automatically.
 */
public final class MainActivity extends Activity {

    private static final int[] ACCENT_GRADIENT = {0xFF3DDC97, 0xFF0B7A52};
    private static final int[] RED_GRADIENT = {0xFFFF8A80, 0xFFC62828};
    private static final int[] BLUE_GRADIENT = {0xFF82B1FF, 0xFF1E88E5};
    private static final int[] ORANGE_GRADIENT = {0xFFFFCC80, 0xFFF57C00};
    private static final int[] PURPLE_GRADIENT = {0xFFCE93D8, 0xFF7B1FA2};
    private static final int[] TEAL_GRADIENT = {0xFF80DEEA, 0xFF00838F};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild so install status stays fresh.
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = UiKit.scrollRoot(this);
        LinearLayout root = UiKit.column(this);
        UiKit.attach(scroll, root);
        setContentView(scroll);
        UiKit.applyStatusBar(this);

        // ---- header ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View logo = UiKit.appIcon(this, getPackageName(), "Z", ACCENT_GRADIENT);
        logo.setElevation(UiKit.dp(this, 3));
        header.addView(logo);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = UiKit.matchWrap();
        titleLp.leftMargin = UiKit.dp(this, 16);
        titleBlock.setLayoutParams(titleLp);

        TextView brand = UiKit.display(this, "ZoeVIP");
        titleBlock.addView(brand);

        TextView subtitle = UiKit.muted(this, "多应用 VIP / PRO 解锁模块 · v" + versionName());
        titleBlock.addView(subtitle);
        header.addView(titleBlock);
        root.addView(header);

        // ---- primary action ----
        Button lspButton = UiKit.filledButton(this, "打开 LSPosed");
        LinearLayout.LayoutParams lspLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 20));
        root.addView(lspButton, lspLp);
        lspButton.setOnClickListener(v -> openLsposed());

        // ---- adapted apps ----
        root.addView(UiKit.section(this, "已适配软件"));

        root.addView(appCard(
                "Afusekt",
                "com.attempt.afusekt",
                "PRO/VIP 解锁 · WebDAV 资源库同步（含刮削）",
                ACCENT_GRADIENT,
                "进入设置",
                v -> startActivity(new Intent(this, AfusektSettingsActivity.class))
        ));

        root.addView(appCard(
                "CapyPlayer",
                "com.feifeiduck.capyplayer",
                "Lifetime Pro 解锁 · 无限资源库 · 全部 Pro 体验",
                RED_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "CapyPlayer 无需额外设置，启用模块并勾选作用域后重启 App 即可解锁 Pro。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "VidHub / Media Hub",
                "com.oumi.utility.media.hub",
                "Lifetime VIP/PRO 解锁 · 绑定账号后自动 Pro",
                BLUE_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "VidHub：LSPosed 启用 ZoeVIP → 勾选本应用 → 开启「隐藏模块」→ 强制停止后重开。可在设置里绑定账号，Pro 由模块解锁。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "喜马拉雅",
                "com.ximalaya.ting.android",
                "看广告领时长 · 跳过激励视频直接领奖",
                ORANGE_GRADIENT,
                "进入设置",
                v -> XimalayaSettingsUi.show(this)
        ));

        root.addView(appCard(
                "美图秀秀",
                "com.mt.mtxx.mtxx",
                "VIP/SVIP 会员功能解锁",
                PURPLE_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "美图秀秀：LSPosed 启用 ZoeVIP → 勾选本应用 → 开启「隐藏模块」→ 强制停止后重开。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "番茄畅听",
                "com.xs.fm",
                "VIP 会员解锁 · 亮会员标 · 精简开屏/贴片/激励广告",
                ACCENT_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "番茄畅听：LSPosed 启用 ZoeVIP → 勾选 com.xs.fm → 强制停止后重开。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "番茄免费小说",
                "com.dragon.read",
                "VIP 会员解锁 · 会员标 · 屏蔽不安全提示 · 免广告",
                ORANGE_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                "番茄小说：NPatch 用户请在 NPatch 管理器里启用 ZoeVIP 并勾选 com.dragon.read；"
                        + "LSPosed 用户同样勾选作用域。强制停止后重开，应弹出「ZoeVIP 已注入」。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "Scene / VTools",
                "com.omarea.vtools",
                "工具箱 VIP 功能解锁（3.8.0 新增）",
                TEAL_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "VTools：LSPosed 启用 ZoeVIP → 勾选本应用 → 强制停止后重开。",
                        Toast.LENGTH_LONG).show()
        ));

        root.addView(appCard(
                "绿茶 VPN",
                "com.abjlvcha.main",
                "钻石会员解锁 · 到期 5555-05-20 · 钻石专线",
                ACCENT_GRADIENT,
                "查看说明",
                v -> Toast.makeText(this,
                        "绿茶 VPN：LSPosed 启用 ZoeVIP → 勾选 com.abjlvcha.main（或 com.lvcha.main）"
                                + " → 开启「隐藏模块」→ 强制停止后重开。",
                        Toast.LENGTH_LONG).show()
        ));

        // ---- footer tip ----
        LinearLayout tipCard = UiKit.card(this);
        UiKit.cardPadding(tipCard, 16);
        LinearLayout.LayoutParams tipLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 24));
        root.addView(tipCard, tipLp);

        TextView tipTitle = UiKit.title(this, "使用步骤");
        tipCard.addView(tipTitle);

        TextView tip = UiKit.body(this,
                "1. 打开 LSPosed → 启用 ZoeVIP\n"
                        + "2. 勾选对应软件的作用域\n"
                        + "3. 强制停止目标软件后重新打开");
        LinearLayout.LayoutParams tipBodyLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 8));
        tipCard.addView(tip, tipBodyLp);

        TextView version = UiKit.small(this,
                "ZoeVIP " + versionName() + " (build " + versionCode() + ")");
        LinearLayout.LayoutParams verLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 12));
        tipCard.addView(version, verLp);
    }

    private View appCard(
            String title,
            String packageName,
            String desc,
            int[] gradient,
            String actionLabel,
            View.OnClickListener click
    ) {
        LinearLayout card = UiKit.card(this);
        UiKit.cardPadding(card, 16);
        LinearLayout.LayoutParams cardLp = UiKit.matchWrap();
        cardLp.bottomMargin = UiKit.dp(this, 12);
        card.setLayoutParams(cardLp);
        card.setOnClickListener(click);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        View iconView = UiKit.appIcon(this, packageName, title.substring(0, 1), gradient);
        top.addView(iconView);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = UiKit.matchWrap();
        infoLp.leftMargin = UiKit.dp(this, 14);
        info.setLayoutParams(infoLp);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiKit.title(this, title);
        nameRow.addView(name, UiKit.matchWrap());
        boolean installed = isInstalled(packageName);
        TextView badge = UiKit.badge(this, installed ? "已安装" : "未安装", installed);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = UiKit.dp(this, 8);
        nameRow.addView(badge, badgeLp);
        info.addView(nameRow);

        TextView pkg = UiKit.small(this, packageName);
        LinearLayout.LayoutParams pkgLp = UiKit.matchWrap();
        pkgLp.topMargin = UiKit.dp(this, 2);
        info.addView(pkg, pkgLp);
        top.addView(info);
        card.addView(top);

        TextView d = UiKit.body(this, desc);
        LinearLayout.LayoutParams dLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 10));
        card.addView(d, dLp);

        TextView action = new TextView(this);
        action.setText(actionLabel + " ›");
        action.setTextSize(13f);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setTextColor(UiColors.accent(this));
        LinearLayout.LayoutParams actionLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 12));
        card.addView(action, actionLp);

        return card;
    }

    private void openLsposed() {
        PackageManager pm = getPackageManager();
        String[] candidates = {
                "org.lsposed.manager",
                "io.github.lsposed.manager",
                "org.lsposed.manager.v2"
        };
        for (String pkg : candidates) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        // Fallback: try common LSPosed deep link / market
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("lsposed://module"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "未找到 LSPosed Manager，请手动打开 LSPosed", Toast.LENGTH_LONG).show();
    }

    private boolean isInstalled(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return info != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int versionCode() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionCode;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
