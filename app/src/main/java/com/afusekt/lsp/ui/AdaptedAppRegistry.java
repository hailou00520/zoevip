package com.afusekt.lsp.ui;

import com.afusekt.lsp.R;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Central list of ZoeVIP adapted applications for the home screen. */
public final class AdaptedAppRegistry {

    public static final int ACTION_SETTINGS = 1;
    public static final int ACTION_XIMALAYA = 2;
    public static final int ACTION_GUIDE = 3;

    private static final int[] ACCENT = {0xFF3DDC97, 0xFF0B7A52};
    private static final int[] RED = {0xFFFF8A80, 0xFFC62828};
    private static final int[] BLUE = {0xFF82B1FF, 0xFF1E88E5};
    private static final int[] ORANGE = {0xFFFFCC80, 0xFFF57C00};
    private static final int[] PURPLE = {0xFFCE93D8, 0xFF7B1FA2};
    private static final int[] TEAL = {0xFF80DEEA, 0xFF00838F};
    private static final int[] INDIGO = {0xFFB39DDB, 0xFF4527A0};

    private static final List<Entry> ENTRIES = buildEntries();

    private AdaptedAppRegistry() {
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static boolean isInstalled(Context context, Entry entry) {
        return getInstallInfo(context, entry).installed;
    }

    public static int installedCount(Context context) {
        int count = 0;
        for (Entry entry : ENTRIES) {
            if (isInstalled(context, entry)) {
                count++;
            }
        }
        return count;
    }

    public static String resolveInstalledPackage(Context context, Entry entry) {
        InstallInfo info = getInstallInfo(context, entry);
        return info.installed ? info.packageName : null;
    }

    public static InstallInfo getInstallInfo(Context context, Entry entry) {
        PackageManager pm = context.getPackageManager();
        String pkg = tryPackage(pm, entry.packageName);
        if (pkg != null) {
            return infoFor(pm, pkg);
        }
        for (String alt : entry.altPackages) {
            pkg = tryPackage(pm, alt);
            if (pkg != null) {
                return infoFor(pm, pkg);
            }
        }
        return InstallInfo.missing(entry.packageName);
    }

    public static List<String> allPackages(Entry entry) {
        List<String> packages = new ArrayList<>(1 + entry.altPackages.length);
        packages.add(entry.packageName);
        Collections.addAll(packages, entry.altPackages);
        return packages;
    }

    public static String displayPackage(Entry entry) {
        if (entry.altPackages.length == 0) {
            return entry.packageName;
        }
        return entry.packageName + " · " + entry.altPackages[0];
    }

    /** Card subtitle: adapted target version only. */
    public static String displayAdaptedVersion(Entry entry) {
        if (entry.adaptedVersion == null || entry.adaptedVersion.isEmpty()) {
            return "";
        }
        return entry.adaptedVersion;
    }

    public static String displayInstallStatus(Context context, Entry entry) {
        InstallInfo info = getInstallInfo(context, entry);
        if (!info.installed) {
            return "未安装";
        }
        return "已安装 v" + info.versionName;
    }

    public static String formatGuideVersion(Context context, Entry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.adaptedVersion != null && !entry.adaptedVersion.isEmpty()) {
            sb.append(entry.adaptedVersion);
        }
        if (entry.adaptNote != null && !entry.adaptNote.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(entry.adaptNote);
        }
        InstallInfo info = getInstallInfo(context, entry);
        if (info.installed) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("本机 v").append(info.versionName)
                    .append(" (").append(info.packageName).append(')');
        }
        return sb.toString();
    }

    private static String tryPackage(PackageManager pm, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        try {
            if (pm.getLaunchIntentForPackage(packageName) != null) {
                return packageName;
            }
        } catch (Throwable ignored) {
        }
        try {
            pm.getApplicationInfo(packageName, 0);
            return packageName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        try {
            pm.getPackageInfo(packageName, 0);
            return packageName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private static InstallInfo infoFor(PackageManager pm, String packageName) {
        try {
            PackageInfo pkg = pm.getPackageInfo(packageName, 0);
            String versionName = pkg.versionName != null ? pkg.versionName : String.valueOf(pkg.versionCode);
            return new InstallInfo(true, packageName, versionName, pkg.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return InstallInfo.missing(packageName);
        }
    }

    private static List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(
                "Afusekt",
                "com.attempt.afusekt",
                R.drawable.ic_adapt_afusekt,
                "3.2.5.1",
                "PRO/VIP 解锁 · WebDAV 资源库同步（含刮削）",
                ACCENT,
                ACTION_SETTINGS,
                "Afusekt",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选 com.attempt.afusekt",
                        "强制停止 Afusekt 后重新打开",
                        "可在本模块「进入设置」配置 WebDAV 同步"
                },
                "进入设置",
                null
        ));
        list.add(new Entry(
                "CapyPlayer",
                "com.feifeiduck.capyplayer",
                R.drawable.ic_adapt_capyplayer,
                "1.1.5",
                "Lifetime Pro · 无限资源库 · 全部 Pro 体验",
                RED,
                ACTION_GUIDE,
                "CapyPlayer",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选本应用",
                        "无需额外设置，重启 App 即可解锁 Pro"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "VidHub / Media Hub",
                "com.oumi.utility.media.hub",
                R.drawable.ic_adapt_vidhub,
                "3.0.1",
                "Lifetime VIP/PRO · 绑定账号后自动 Pro",
                BLUE,
                ACTION_GUIDE,
                "VidHub",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选本应用",
                        "建议开启 LSPosed「隐藏模块」",
                        "强制停止后重开；可在 App 内绑定账号"
                },
                "查看说明",
                "建议开启隐藏模块"
        ));
        list.add(new Entry(
                "喜马拉雅",
                "com.ximalaya.ting.android",
                R.drawable.ic_adapt_ximalaya,
                "9.5.1.3",
                "看广告领时长 · 跳过激励视频直接领奖",
                ORANGE,
                ACTION_XIMALAYA,
                "喜马拉雅",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选本应用",
                        "强制停止后重开",
                        "可在「进入设置」调整连领次数与测试开关"
                },
                "进入设置",
                null
        ));
        list.add(new Entry(
                "美图秀秀",
                "com.mt.mtxx.mtxx",
                R.drawable.ic_adapt_mtxx,
                "12.17.0",
                "VIP/SVIP 会员功能解锁",
                PURPLE,
                ACTION_GUIDE,
                "美图秀秀",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选本应用",
                        "建议开启「隐藏模块」",
                        "强制停止后重开"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "番茄畅听",
                "com.xs.fm",
                R.drawable.ic_adapt_fanqie_fm,
                "理论适配所有版本",
                "VIP 解锁 · 亮会员标 · 精简开屏/贴片/激励广告",
                ACCENT,
                ACTION_GUIDE,
                "番茄畅听",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选 com.xs.fm",
                        "强制停止后重开"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "番茄免费小说",
                "com.dragon.read",
                R.drawable.ic_adapt_dragon_read,
                "理论适配所有版本",
                "VIP 解锁 · 会员标 · 屏蔽不安全提示 · 免广告",
                ORANGE,
                ACTION_GUIDE,
                "番茄免费小说",
                new String[]{
                        "NPatch：在管理器启用 ZoeVIP 并勾选 com.dragon.read",
                        "LSPosed：同样勾选作用域后强制停止重开",
                        "成功注入会弹出「ZoeVIP 已注入」提示"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "红果免费短剧",
                "com.phoenix.read",
                R.drawable.ic_adapt_hongguo,
                "理论适配所有版本",
                "VIP 解锁 · 会员标 · 短剧付费锁 · 免广告",
                RED,
                ACTION_GUIDE,
                "红果免费短剧",
                new String[]{
                        "NPatch：在管理器启用 ZoeVIP 并勾选 com.phoenix.read",
                        "LSPosed：勾选 com.phoenix.read 后强制停止重开",
                        "成功注入会弹出「ZoeVIP 已注入红果短剧」提示"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "红果免费漫剧",
                "com.kylin.read",
                R.drawable.ic_adapt_kylin,
                "理论适配所有版本",
                "VIP 解锁 · 会员标 · 漫剧付费锁 · 免广告",
                PURPLE,
                ACTION_GUIDE,
                "红果免费漫剧",
                new String[]{
                        "NPatch：在管理器启用 ZoeVIP 并勾选 com.kylin.read",
                        "LSPosed：勾选 com.kylin.read 后强制停止重开",
                        "成功注入会弹出「ZoeVIP 已注入红果漫剧」提示"
                },
                "查看说明",
                "包名 com.kylin.read"
        ));
        list.add(new Entry(
                "Scene / VTools",
                "com.omarea.vtools",
                R.drawable.ic_adapt_vtools,
                "9.4.6",
                "工具箱 VIP 功能解锁",
                TEAL,
                ACTION_GUIDE,
                "VTools",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选本应用",
                        "强制停止后重开"
                },
                "查看说明",
                null
        ));
        list.add(new Entry(
                "Yamby",
                "com.hush.yamby",
                R.drawable.ic_adapt_yamby,
                "2.0.5.5",
                "Lifetime Pro · MMKV + Billing 伪造 · Pro 页解锁",
                BLUE,
                ACTION_GUIDE,
                "Yamby",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选 com.hush.yamby",
                        "NPatch 重打包包名同为 com.hush.yamby",
                        "强制停止后重开；设置页应显示 Pro"
                },
                "查看说明",
                "libxposed API 102+"
        ));
        list.add(new Entry(
                "Hills",
                "com.mountains.hills",
                R.drawable.ic_adapt_hills,
                "1.7.2 / 1.8.0",
                "Pro 解锁 · libapp 补丁 + Player JSON hook",
                INDIGO,
                ACTION_GUIDE,
                "Hills",
                new String[]{
                        "LSPosed 启用 ZoeVIP 并勾选 com.mountains.hills",
                        "强制停止后重开",
                        "仅 libxposed (API 102+) 路径生效"
                },
                "查看说明",
                "libxposed API 102+"
        ));
        list.add(new Entry(
                "绿茶 VPN",
                "com.abjlvcha.main",
                new String[]{"com.lvcha.main"},
                R.drawable.ic_adapt_lvcha,
                "2.6.7",
                "钻石会员解锁 · 到期 5555-05-20 · UI 精简",
                ACCENT,
                ACTION_GUIDE,
                "绿茶 VPN",
                new String[]{
                        "LSPosed 启用 ZoeVIP",
                        "勾选 com.abjlvcha.main 或 com.lvcha.main",
                        "建议开启「隐藏模块」后强制停止重开"
                },
                "查看说明",
                "NPatch 包名 com.abjlvcha.main"
        ));
        return Collections.unmodifiableList(list);
    }

    public static final class InstallInfo {
        public final boolean installed;
        public final String packageName;
        public final String versionName;
        public final long versionCode;

        InstallInfo(boolean installed, String packageName, String versionName, long versionCode) {
            this.installed = installed;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
        }

        static InstallInfo missing(String packageName) {
            return new InstallInfo(false, packageName, "", 0L);
        }
    }

    public static final class Entry {
        public final String title;
        public final String packageName;
        public final String[] altPackages;
        public final int iconRes;
        public final String adaptedVersion;
        public final String description;
        public final int[] gradient;
        public final int action;
        public final String guideTitle;
        public final String[] guideSteps;
        public final String actionLabel;
        public final String adaptNote;

        Entry(
                String title,
                String packageName,
                int iconRes,
                String adaptedVersion,
                String description,
                int[] gradient,
                int action,
                String guideTitle,
                String[] guideSteps,
                String actionLabel,
                String adaptNote
        ) {
            this.title = title;
            this.packageName = packageName;
            this.altPackages = new String[0];
            this.iconRes = iconRes;
            this.adaptedVersion = adaptedVersion;
            this.description = description;
            this.gradient = gradient;
            this.action = action;
            this.guideTitle = guideTitle;
            this.guideSteps = guideSteps;
            this.actionLabel = actionLabel;
            this.adaptNote = adaptNote;
        }

        Entry(
                String title,
                String packageName,
                String[] altPackages,
                int iconRes,
                String adaptedVersion,
                String description,
                int[] gradient,
                int action,
                String guideTitle,
                String[] guideSteps,
                String actionLabel,
                String adaptNote
        ) {
            this.title = title;
            this.packageName = packageName;
            this.altPackages = altPackages != null ? altPackages : new String[0];
            this.iconRes = iconRes;
            this.adaptedVersion = adaptedVersion;
            this.description = description;
            this.gradient = gradient;
            this.action = action;
            this.guideTitle = guideTitle;
            this.guideSteps = guideSteps;
            this.actionLabel = actionLabel;
            this.adaptNote = adaptNote;
        }
    }
}
