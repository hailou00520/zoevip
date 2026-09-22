package com.afusekt.lsp.hook;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared entitlement seeding / override logic for CapyPlayer (legacy + libxposed).
 */
public final class CapyPlayerEntitlementSupport {

    public static final String PREFS_NAME = "FlutterSharedPreferences";
    public static final String PRO_PRODUCT = "capyplayer.pro.lifetime";
    public static final String LIFETIME_PRODUCT = "capyplayer.pro.lifetime";

    public static final String SUBSCRIPTION_STATE_JSON =
            CapyPlayerSubscriptionPatcher.LIFETIME_STATE_JSON;

    /** Skip repeated SharedPreferences commits that rebuild Flutter image widgets. */
    private static final AtomicBoolean SP_SEEDED = new AtomicBoolean(false);

    public static String replacementForKey(String key) {
        return CapyPlayerSubscriptionPatcher.replacementForKey(key);
    }

    private CapyPlayerEntitlementSupport() {
    }

    /** DataStore pb only — safe under libxposed (avoids SharedPreferences write hooks). */
    public static void seedDataStorePb(Context context) {
        if (context == null) {
            return;
        }
        try {
            PreferencesPbSeeder.seedLifetimePro(context, SUBSCRIPTION_STATE_JSON, PRO_PRODUCT);
        } catch (Throwable ignored) {
        }
    }

    /** Call when a free/rejected write is observed so a later seed can re-apply. */
    public static void markNeedsReseed() {
        SP_SEEDED.set(false);
        PreferencesPbSeeder.markNeedsReseed();
    }

    /** Force re-seed even if a prior seed succeeded (e.g. after login sync). */
    public static void forceReseedProSubscription(Context context) {
        markNeedsReseed();
        seedProSubscription(context);
    }

    public static void seedProSubscription(Context context) {
        if (context == null) {
            return;
        }
        try {
            // WebDAV restore can overwrite XML/pb with free after our first seed.
            // Hooked SharedPreferences.getString always returns lifetime, so detect via files.
            if (SP_SEEDED.get() && diskLooksLifetimePro(context)) {
                seedDataStorePb(context);
                return;
            }
            SP_SEEDED.set(false);
            PreferencesPbSeeder.markNeedsReseed();
            seedDataStorePb(context);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            long now = System.currentTimeMillis();
            editor.putString("flutter.subscription_state", SUBSCRIPTION_STATE_JSON);
            editor.putString("flutter.subscription_data",
                    CapyPlayerSubscriptionPatcher.lifetimeLocalDataJson());
            editor.putString("flutter.desktop_subscription_state", SUBSCRIPTION_STATE_JSON);
            editor.putString("flutter.subscription_sync_times", "{\"google\":" + now + "}");
            editor.putString("flutter.synced_purchase_ids",
                    "[\"" + PRO_PRODUCT + "\",\"" + LIFETIME_PRODUCT + "\"]");
            editor.putString("flutter.last_subscription_sync_time", String.valueOf(now));
            editor.putString("flutter.add_resource_quota", String.valueOf(Integer.MAX_VALUE));
            editor.putString("flutter.isPro", "true");
            editor.putString("flutter.subscriptionId", PRO_PRODUCT);
            editor.putString("flutter.proFeatureEntitlementReady", "true");
            editor.putString("flutter.subscriptionEntitlementReady", "true");
            editor.putString("flutter.hideSubscriptionCard", "true");
            // Fake tokens cause rejectedReceipt → free. Clear any leftover.
            editor.remove("flutter.purchaseCredential");
            editor.remove("purchaseCredential");
            editor.commit();
            SP_SEEDED.set(true);
        } catch (Throwable ignored) {
        }
    }

    /** File-level check — bypasses hooked SharedPreferences getters. */
    public static boolean diskLooksLifetimePro(Context context) {
        if (context == null) {
            return false;
        }
        try {
            java.io.File xml = new java.io.File(
                    context.getApplicationInfo().dataDir + "/shared_prefs/" + PREFS_NAME + ".xml");
            if (xmlExistsFree(xml)) {
                return false;
            }
            java.io.File pb = new java.io.File(
                    context.getFilesDir(), "datastore/" + PREFS_NAME + ".preferences_pb");
            if (pb.exists()) {
                return !PreferencesPbSeeder.needsReseedPublic(pb);
            }
            // No pb yet — XML must contain lifetime and no free tier blob.
            return xml.exists() && !xmlExistsFree(xml) && xmlContainsLifetime(xml);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean xmlExistsFree(java.io.File xml) {
        return fileContains(xml, "\"tier\":\"free\"") || fileContains(xml, "rejectedReceipt");
    }

    private static boolean xmlContainsLifetime(java.io.File xml) {
        return fileContains(xml, "\"tier\":\"lifetime\"");
    }

    private static boolean fileContains(java.io.File file, String needle) {
        if (file == null || !file.exists() || needle == null) {
            return false;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 512 * 1024L)];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    public static Object patchPlatformValue(Object value) {
        return patchReplyValue(value);
    }

    public static String overrideString(String key, String current) {
        if (isPurchaseCredentialKey(key)) {
            return "";
        }
        if (isSubscriptionKey(key)) {
            return replacementForKey(key);
        }
        if (isQuotaKey(key)) {
            return String.valueOf(Integer.MAX_VALUE);
        }
        if (isEntitlementKey(key) || normalizedKey(key).equals("ispro")) {
            return "true";
        }
        if (current != null && isDowngradeSubscriptionJson(current)) {
            return replacementForKey(key);
        }
        return null;
    }

    public static Object overrideDataStoreValue(String name) {
        if (isPurchaseCredentialKey(name)) {
            return "";
        }
        if (isSubscriptionKey(name)) {
            return replacementForKey(name);
        }
        if (isQuotaKey(name)) {
            return String.valueOf(Integer.MAX_VALUE);
        }
        if (isEntitlementKey(name) || normalizedKey(name).equals("ispro")) {
            return "true";
        }
        if (normalizedKey(name).contains("synced_purchase_ids")) {
            return "[\"" + PRO_PRODUCT + "\",\"" + LIFETIME_PRODUCT + "\"]";
        }
        return null;
    }

    public static boolean isPurchaseCredentialKey(String key) {
        return normalizedKey(key).contains("purchasecredential");
    }

    public static boolean shouldForceTrue(String key) {
        return isEntitlementKey(key)
                || normalizedKey(key).contains("has_subscription")
                || normalizedKey(key).contains("subscription_active");
    }

    public static boolean isSubscriptionKey(String key) {
        String n = normalizedKey(key);
        return n.contains("subscription_state")
                || n.contains("subscription_data")
                || n.contains("desktop_subscription")
                || n.contains("subscription_status");
    }

    public static boolean isEntitlementKey(String key) {
        String n = normalizedKey(key);
        return n.contains("entitlement") || n.equals("is_pro") || n.equals("ispro")
                || n.contains("hide_subscription");
    }

    public static boolean isQuotaKey(String key) {
        String n = normalizedKey(key);
        return n.equals("add_resource_quota") || n.contains("resource_quota");
    }

    public static boolean isDowngradeSubscriptionJson(String value) {
        if (value == null || value.length() > 65536 || !value.trim().startsWith("{")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("\"hassubscription\":false")
                || lower.contains("\"has_subscription\":false")
                || lower.contains("\"isactive\":false")
                || lower.contains("\"ispro\":false")
                || lower.contains("\"status\":\"inactive\"")
                || lower.contains("\"status\":\"expired\"")
                || lower.contains("\"tier\":\"free\"")
                || lower.contains("\"plan\":\"free\"")
                || lower.contains("\"subscriptionactive\":false")
                || lower.contains("\"lifetimemember\":false")
                || lower.contains("\"rejectedreceipt\"");
    }

    @SuppressWarnings("unchecked")
    public static boolean patchPreferenceMap(Map<?, ?> map) {
        boolean changed = false;
        Map<Object, Object> writable = (Map<Object, Object>) map;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            if (isSubscriptionKey(k) || (v instanceof String && isDowngradeSubscriptionJson((String) v))) {
                writable.put(e.getKey(), replacementForKey(k));
                changed = true;
            } else if (isQuotaKey(k)) {
                writable.put(e.getKey(), String.valueOf(Integer.MAX_VALUE));
                changed = true;
            } else if (isEntitlementKey(k)) {
                writable.put(e.getKey(), "true");
                changed = true;
            } else if (v != null) {
                Object inner = patchReplyValue(v);
                if (inner != v) {
                    writable.put(e.getKey(), inner);
                    changed = true;
                }
            }
        }
        if (looksLikeSharedPreferenceMap(map)) {
            changed |= ensureProKeys(writable);
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static Object patchReplyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String s = (String) value;
            if (isDowngradeSubscriptionJson(s)) {
                return SUBSCRIPTION_STATE_JSON;
            }
            if (PlatformMessagePatcher.looksLikeSubscriptionPayload(s)
                    && s.trim().startsWith("{")
                    && !s.contains("\"lifetime\":true")
                    && !s.contains("\"tier\":\"lifetime\"")) {
                return SUBSCRIPTION_STATE_JSON;
            }
            return value;
        }
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            patchPreferenceMap(map);
            return map;
        }
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object inner = patchReplyValue(list.get(i));
                if (inner != list.get(i)) {
                    list.set(i, inner);
                }
            }
            return list;
        }
        return value;
    }

    private static boolean looksLikeSharedPreferenceMap(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (String.valueOf(key).startsWith("flutter.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean ensureProKeys(Map<Object, Object> map) {
        boolean changed = false;
        changed |= putIfDifferent(map, "flutter.subscription_state", SUBSCRIPTION_STATE_JSON);
        changed |= putIfDifferent(map, "flutter.subscription_data",
                CapyPlayerSubscriptionPatcher.lifetimeLocalDataJson());
        changed |= putIfDifferent(map, "flutter.desktop_subscription_state", SUBSCRIPTION_STATE_JSON);
        changed |= putIfDifferent(map, "flutter.add_resource_quota", String.valueOf(Integer.MAX_VALUE));
        changed |= putIfDifferent(map, "flutter.isPro", "true");
        changed |= putIfDifferent(map, "flutter.synced_purchase_ids",
                "[\"" + PRO_PRODUCT + "\",\"" + LIFETIME_PRODUCT + "\"]");
        changed |= putIfDifferent(map, "flutter.subscriptionId", PRO_PRODUCT);
        changed |= putIfDifferent(map, "flutter.purchaseCredential", "");
        return changed;
    }

    private static boolean putIfDifferent(Map<Object, Object> map, String key, String value) {
        Object current = map.get(key);
        if (value.equals(String.valueOf(current))) {
            return false;
        }
        map.put(key, value);
        return true;
    }

    public static ByteBuffer patchPlatformMessage(ByteBuffer message, int position) {
        return PlatformMessagePatcher.patch(message, position);
    }

    /**
     * Writes patched platform reply into the existing direct buffer (no arg replacement).
     *
     * @return new read position for the native callback, or -1 if unchanged
     */
    public static int patchPlatformMessageInPlace(ByteBuffer message, int position) {
        ByteBuffer patched = patchPlatformMessage(message, position);
        if (patched == null) {
            return -1;
        }
        patched.rewind();
        byte[] bytes = new byte[patched.remaining()];
        patched.get(bytes);
        message.position(position);
        message.put(bytes);
        message.position(position);
        message.limit(position + bytes.length);
        return position;
    }

    public static String patchHttpText(String body) {
        return CapyPlayerSubscriptionPatcher.patchText(body);
    }

    public static boolean isPreferencesPbPath(String path) {
        return PreferencesPbSeeder.isPreferencesPbPath(path);
    }

    private static String normalizedKey(String key) {
        if (key == null) {
            return "";
        }
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("flutter.")) {
            return lower.substring("flutter.".length());
        }
        return lower;
    }
}
