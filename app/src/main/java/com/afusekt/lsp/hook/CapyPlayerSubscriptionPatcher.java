package com.afusekt.lsp.hook;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Patches subscription API / storage payloads to lifetime Pro. */
public final class CapyPlayerSubscriptionPatcher {

    private static final String PRO_PRODUCT = "capyplayer.pro.lifetime";

    /**
     * Local DataStore blob for {@code flutter.subscription_data}.
     * Must NOT include purchaseToken / rejectedReceipt — server verify rejects fake tokens
     * and {@code SubscriptionSyncService} resets tier to free.
     */
    static String lifetimeLocalDataJson() {
        // Far-future lastVerifiedAt reduces immediate re-verify → free.
        long verified = 4102444800000L; // 2100-01-01
        return "{\"tier\":\"lifetime\",\"lastVerifiedAt\":" + verified
                + ",\"productId\":\"" + PRO_PRODUCT + "\""
                + ",\"lifetimeMember\":true,\"nonRecurring\":true,\"status\":\"active\"}";
    }

    static final String LIFETIME_STATE_JSON =
            "{\"tier\":\"lifetime\",\"lastVerifiedAt\":4102444800000,"
                    + "\"productId\":\"" + PRO_PRODUCT + "\","
                    + "\"hasSubscription\":true,\"lifetimeMember\":true,"
                    + "\"subscriptionActive\":true,\"isActive\":true,\"isPro\":true,"
                    + "\"nonRecurring\":true,\"status\":\"active\"}";

    static final String LIFETIME_API_JSON =
            "{\"hasSubscription\":true,\"has_subscription\":true,"
                    + "\"subscriptionActive\":true,\"lifetimeMember\":true,"
                    + "\"annualMember\":true,\"nonRecurring\":true,\"isActive\":true,"
                    + "\"tier\":\"lifetime\",\"status\":\"active\",\"validTill\":null,"
                    + "\"productId\":\"" + PRO_PRODUCT + "\","
                    + "\"product_id\":\"" + PRO_PRODUCT + "\","
                    + "\"google_product_id\":\"" + PRO_PRODUCT + "\","
                    + "\"subscription\":{\"tier\":\"lifetime\",\"status\":\"active\","
                    + "\"lifetime\":true,\"lifetimeMember\":true,\"nonRecurring\":true,"
                    + "\"isActive\":true,\"has_subscription\":true,"
                    + "\"productId\":\"" + PRO_PRODUCT + "\","
                    + "\"product_id\":\"" + PRO_PRODUCT + "\","
                    + "\"validTill\":null,\"expiresAt\":null,\"expires_at\":null}}";

    private CapyPlayerSubscriptionPatcher() {
    }

    static String replacementForKey(String key) {
        if (key != null && key.toLowerCase(Locale.ROOT).contains("subscription_data")) {
            return lifetimeLocalDataJson();
        }
        return LIFETIME_STATE_JSON;
    }

    static String patchText(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        // Never scan multi-MB WebDAV / backup payloads.
        if (body.length() > 65536) {
            return body;
        }
        if (body.contains("\"rejectedReceipt\":true")) {
            body = body.replace("\"rejectedReceipt\":true", "\"rejectedReceipt\":null");
        }
        if (!looksLikeSubscriptionPayload(body)) {
            return body;
        }
        if (isLifetimePayload(body)) {
            return body;
        }
        if (body.trim().startsWith("{")) {
            return LIFETIME_API_JSON;
        }
        return body;
    }

    static byte[] patchBytes(byte[] body) {
        if (body == null || body.length == 0) {
            return body;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        String patched = patchText(text);
        if (patched.equals(text)) {
            return body;
        }
        return patched.getBytes(StandardCharsets.UTF_8);
    }

    static boolean looksLikeSubscriptionPayload(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Backup / WebDAV bodies can be tens of MB — never toLowerCase them.
        if (text.length() > 65536) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("hassubscription")
                || lower.contains("subscription/status")
                || lower.contains("subscriptions/status")
                || lower.contains("subscriptions/verify")
                || lower.contains("subscriptions/sync")
                || lower.contains("subscriptionstatusresponse")
                || (lower.contains("subscription") && lower.contains("\"tier\""))
                || lower.contains("subscription_state")
                || lower.contains("subscription_data")
                || lower.contains("desktop_subscription_state")
                || lower.contains("has_subscription")
                || lower.contains("lifetimemember");
    }

    public static boolean isLifetimePayload(String text) {
        if (text == null || text.length() > 65536) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("\"tier\":\"lifetime\"")
                || lower.contains("\"tier\":\"life\"")
                || (lower.contains("\"hassubscription\":true")
                && (lower.contains("\"lifetime\":true")
                || lower.contains("\"lifetimemember\":true")));
    }

    static boolean isDowngradePayload(String text) {
        if (text == null || text.length() > 65536 || !text.trim().startsWith("{")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("\"hassubscription\":false")
                || lower.contains("\"has_subscription\":false")
                || lower.contains("\"subscriptionactive\":false")
                || lower.contains("\"lifetimemember\":false")
                || lower.contains("\"isactive\":false")
                || lower.contains("\"ispro\":false")
                || lower.contains("\"status\":\"inactive\"")
                || lower.contains("\"status\":\"expired\"")
                || lower.contains("\"tier\":\"free\"")
                || lower.contains("\"plan\":\"free\"")
                || lower.contains("\"rejectedreceipt\"");
    }
}
