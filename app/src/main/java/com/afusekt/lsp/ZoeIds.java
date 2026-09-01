package com.afusekt.lsp;

/**
 * Shared package ids / tag — must NOT implement classic Xposed interfaces.
 * Modern Vector/LSPosed (API 102+) rejects loading {@code IXposedHookLoadPackage}.
 */
public final class ZoeIds {

    public static final String TAG = "ZoeVIP";
    public static final String AFUSEKT_PACKAGE = "com.attempt.afusekt";
    public static final String CAPYPLAYER_PACKAGE = "com.feifeiduck.capyplayer";
    public static final String VIDHUB_PACKAGE = "com.oumi.utility.media.hub";
    public static final String VTOOLS_PACKAGE = "com.omarea.vtools";
    public static final String XIMALAYA_PACKAGE = "com.ximalaya.ting.android";
    public static final String MTXX_PACKAGE = "com.mt.mtxx.mtxx";
    public static final String FANQIE_PACKAGE = "com.xs.fm";
    public static final String FANQIE_NOVEL_PACKAGE = "com.dragon.read";
    /** 红果免费短剧 */
    public static final String HONGGUO_PACKAGE = "com.phoenix.read";
    /** 红果 / 同内核变体 */
    public static final String KYLIN_PACKAGE = "com.kylin.read";
    /** Hills 播放器（Emby/Jellyfin） */
    public static final String HILLS_PACKAGE = "com.mountains.hills";

    private ZoeIds() {
    }

    /** 番茄小说 / 红果短剧同内核（dragon.read 组件）。 */
    public static boolean isDragonReadFamily(String packageName) {
        return FANQIE_NOVEL_PACKAGE.equals(packageName)
                || HONGGUO_PACKAGE.equals(packageName)
                || KYLIN_PACKAGE.equals(packageName);
    }
}
