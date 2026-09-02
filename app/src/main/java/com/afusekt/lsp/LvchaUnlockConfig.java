package com.afusekt.lsp;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 绿茶 VPN 钻石会员解锁参数。
 * 钻石会员 = {@code vs1.X() == 2}（应用内 super_vip / my_fragment_svip 文案）。
 */
public final class LvchaUnlockConfig {

    /** {@code vs1.X()}：2 = 钻石会员（SVIP）。 */
    public static final int DIAMOND_VIP_TYPE = 2;

    /** 到期时刻：5555-05-20 23:59:59.999（Asia/Shanghai）。 */
    private static final long EXPIRE_AT_MS = buildExpireAtMs();

    private LvchaUnlockConfig() {
    }

    /** 钻石会员剩余毫秒（供 {@code vs1.H()} hook 使用）。 */
    public static long diamondRemainMs() {
        return Math.max(0L, EXPIRE_AT_MS - System.currentTimeMillis());
    }

    private static long buildExpireAtMs() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        cal.set(Calendar.YEAR, 5555);
        cal.set(Calendar.MONTH, Calendar.MAY);
        cal.set(Calendar.DAY_OF_MONTH, 20);
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTimeInMillis();
    }
}
