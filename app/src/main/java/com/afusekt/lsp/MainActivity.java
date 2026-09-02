package com.afusekt.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afusekt.lsp.ui.AdaptedAppRegistry;
import com.afusekt.lsp.ui.AppGuideDialog;
import com.afusekt.lsp.ui.UiColors;
import com.afusekt.lsp.ui.UiKit;
import com.afusekt.lsp.ui.XimalayaSettingsUi;

import java.util.List;
import java.util.Locale;

/**
 * ZoeVIP home: hero header, search/filter, adapted app list, LSPosed shortcut.
 */
public final class MainActivity extends Activity {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_INSTALLED = 1;
    private static final int FILTER_NOT_INSTALLED = 2;

    private LinearLayout root;
    private LinearLayout appListContainer;
    private EditText searchField;
    private TextView emptyView;
    private TextView chipAll;
    private TextView chipInstalled;
    private TextView chipNotInstalled;
    private TextView heroStat;

    private int filterMode = FILTER_ALL;
    private String searchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeroStats();
        refreshAppList();
    }

    private void buildUi() {
        ScrollView scroll = UiKit.scrollRoot(this);
        root = UiKit.column(this);
        UiKit.attach(scroll, root);
        setContentView(scroll);
        UiKit.applyStatusBar(this);

        int total = AdaptedAppRegistry.all().size();
        LinearLayout hero = UiKit.heroCard(
                this,
                "ZoeVIP",
                "多应用 VIP / PRO 解锁模块 · v" + versionName(),
                "已适配 " + total + " 个应用"
        );
        heroStat = (TextView) hero.getChildAt(hero.getChildCount() - 1);
        root.addView(hero);
        refreshHeroStats();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 16));
        root.addView(actions, actionsLp);

        Button lspButton = UiKit.gradientButton(this, "打开 LSPosed");
        lspButton.setOnClickListener(v -> openLsposed());
        LinearLayout.LayoutParams lspLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(lspButton, lspLp);

        Button refreshButton = UiKit.outlinedButton(this, "刷新");
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshLp.leftMargin = UiKit.dp(this, 10);
        refreshButton.setOnClickListener(v -> {
            refreshHeroStats();
            refreshAppList();
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show();
        });
        actions.addView(refreshButton, refreshLp);

        searchField = UiKit.searchField(this, "搜索应用名或包名");
        LinearLayout.LayoutParams searchLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 16));
        root.addView(searchField, searchLp);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                refreshAppList();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        android.widget.HorizontalScrollView chipScroll = UiKit.chipRow(this);
        LinearLayout chips = UiKit.chipContainer(this);
        chipScroll.addView(chips, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams chipLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 10));
        root.addView(chipScroll, chipLp);

        chipAll = UiKit.chip(this, "全部", filterMode == FILTER_ALL, v -> setFilter(FILTER_ALL));
        chipInstalled = UiKit.chip(this, "已安装", filterMode == FILTER_INSTALLED,
                v -> setFilter(FILTER_INSTALLED));
        chipNotInstalled = UiKit.chip(this, "未安装", filterMode == FILTER_NOT_INSTALLED,
                v -> setFilter(FILTER_NOT_INSTALLED));
        chips.addView(chipAll);
        chips.addView(chipInstalled);
        chips.addView(chipNotInstalled);

        root.addView(UiKit.section(this, "已适配软件 · " + total));

        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(appListContainer);

        emptyView = UiKit.muted(this, "没有匹配的应用");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, UiKit.dp(this, 24), 0, UiKit.dp(this, 24));
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        LinearLayout tipCard = UiKit.card(this);
        UiKit.cardPadding(tipCard, 16);
        LinearLayout.LayoutParams tipLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 24));
        root.addView(tipCard, tipLp);

        tipCard.addView(UiKit.title(this, "快速上手"));
        tipCard.addView(UiKit.numberedStep(this, 1, "打开 LSPosed → 启用 ZoeVIP 模块"),
                UiKit.matchWrapTopMargin(UiKit.dp(this, 12)));
        tipCard.addView(UiKit.numberedStep(this, 2, "勾选目标应用的作用域"),
                UiKit.matchWrapTopMargin(UiKit.dp(this, 10)));
        tipCard.addView(UiKit.numberedStep(this, 3, "强制停止目标 App 后重新打开"),
                UiKit.matchWrapTopMargin(UiKit.dp(this, 10)));

        TextView version = UiKit.small(this,
                "ZoeVIP " + versionName() + " (build " + versionCode() + ")");
        LinearLayout.LayoutParams verLp = UiKit.matchWrapTopMargin(UiKit.dp(this, 14));
        tipCard.addView(version, verLp);

        refreshAppList();
    }

    private void setFilter(int mode) {
        filterMode = mode;
        UiKit.applyChipStyle(chipAll, mode == FILTER_ALL);
        UiKit.applyChipStyle(chipInstalled, mode == FILTER_INSTALLED);
        UiKit.applyChipStyle(chipNotInstalled, mode == FILTER_NOT_INSTALLED);
        refreshAppList();
    }

    private void refreshHeroStats() {
        if (heroStat == null) {
            return;
        }
        int installed = AdaptedAppRegistry.installedCount(this);
        int total = AdaptedAppRegistry.all().size();
        heroStat.setText("已适配 " + total + " 个应用 · 本机已安装 " + installed + " 个");
    }

    private void refreshAppList() {
        if (appListContainer == null) {
            return;
        }
        appListContainer.removeAllViews();
        List<AdaptedAppRegistry.Entry> entries = AdaptedAppRegistry.all();
        int shown = 0;
        for (AdaptedAppRegistry.Entry entry : entries) {
            if (!matchesFilter(entry)) {
                continue;
            }
            if (!matchesSearch(entry)) {
                continue;
            }
            appListContainer.addView(buildAppCard(entry));
            shown++;
        }
        emptyView.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private boolean matchesFilter(AdaptedAppRegistry.Entry entry) {
        boolean installed = AdaptedAppRegistry.isInstalled(this, entry);
        if (filterMode == FILTER_INSTALLED) {
            return installed;
        }
        if (filterMode == FILTER_NOT_INSTALLED) {
            return !installed;
        }
        return true;
    }

    private boolean matchesSearch(AdaptedAppRegistry.Entry entry) {
        if (searchQuery.isEmpty()) {
            return true;
        }
        if (entry.title.toLowerCase(Locale.ROOT).contains(searchQuery)) {
            return true;
        }
        if (entry.packageName.toLowerCase(Locale.ROOT).contains(searchQuery)) {
            return true;
        }
        for (String alt : entry.altPackages) {
            if (alt.toLowerCase(Locale.ROOT).contains(searchQuery)) {
                return true;
            }
        }
        if (entry.adaptedVersion != null
                && entry.adaptedVersion.toLowerCase(Locale.ROOT).contains(searchQuery)) {
            return true;
        }
        AdaptedAppRegistry.InstallInfo info = AdaptedAppRegistry.getInstallInfo(this, entry);
        if (info.installed && info.versionName.toLowerCase(Locale.ROOT).contains(searchQuery)) {
            return true;
        }
        return false;
    }

    private View buildAppCard(AdaptedAppRegistry.Entry entry) {
        LinearLayout card = UiKit.card(this);
        UiKit.cardPadding(card, 10);
        LinearLayout.LayoutParams cardLp = UiKit.matchWrap();
        cardLp.bottomMargin = UiKit.dp(this, 6);
        card.setLayoutParams(cardLp);
        card.setOnClickListener(v -> openEntry(entry));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(UiKit.appIconForEntry(this, entry, 36));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = UiKit.dp(this, 10);
        info.setLayoutParams(infoLp);

        TextView name = UiKit.title(this, entry.title);
        name.setTextSize(15f);
        info.addView(name);

        String adaptedVersion = AdaptedAppRegistry.displayAdaptedVersion(entry);
        if (!adaptedVersion.isEmpty()) {
            TextView version = UiKit.small(this, adaptedVersion);
            LinearLayout.LayoutParams versionLp = UiKit.matchWrap();
            versionLp.topMargin = UiKit.dp(this, 1);
            info.addView(version, versionLp);
        }

        row.addView(info);

        boolean installed = AdaptedAppRegistry.isInstalled(this, entry);
        TextView status = UiKit.badge(this, installed ? "已安装" : "未安装", installed);
        row.addView(status);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(18f);
        chevron.setTextColor(UiColors.muted(this));
        chevron.setPadding(UiKit.dp(this, 6), 0, 0, 0);
        row.addView(chevron);

        card.addView(row);
        return card;
    }

    private void openEntry(AdaptedAppRegistry.Entry entry) {
        if (entry.action == AdaptedAppRegistry.ACTION_SETTINGS) {
            startActivity(new Intent(this, AfusektSettingsActivity.class));
            return;
        }
        if (entry.action == AdaptedAppRegistry.ACTION_XIMALAYA) {
            XimalayaSettingsUi.show(this);
            return;
        }
        AppGuideDialog.show(this, entry);
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
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("lsposed://module"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        Toast.makeText(this, "未找到 LSPosed Manager，请手动打开 LSPosed", Toast.LENGTH_LONG).show();
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
