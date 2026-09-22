package com.afusekt.lsp.hook;

import android.content.Context;
import android.util.Log;

import com.afusekt.lsp.MainHook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes FlutterSharedPreferences DataStore protobuf directly.
 *
 * Value proto (androidx.datastore.preferences):
 *   boolean=1, float=2, integer=3, long=4, string=5, string_set=6, double=7
 */
final class PreferencesPbSeeder {

    private static final String LOG_PREFIX = MainHook.TAG + ":CapyPlayer:Pb";
    private static final String FILE_NAME = "FlutterSharedPreferences.preferences_pb";
    private static final AtomicBoolean SEEDED = new AtomicBoolean(false);

    private PreferencesPbSeeder() {
    }

    static void markNeedsReseed() {
        SEEDED.set(false);
    }

    static void seedLifetimePro(Context context, String subscriptionJson, String productId) {
        try {
            File dir = new File(context.getFilesDir(), "datastore");
            File target = new File(dir, FILE_NAME);
            if (SEEDED.get() && !needsReseed(target)) {
                return;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            long now = System.currentTimeMillis();
            // Match on-device free blob shape; do NOT seed fake purchaseCredential —
            // Play verify rejects it and SubscriptionSyncService resets to free.
            String localData = CapyPlayerSubscriptionPatcher.lifetimeLocalDataJson();
            putString(values, "flutter.subscription_state", subscriptionJson);
            putString(values, "flutter.subscription_data", localData);
            putString(values, "flutter.desktop_subscription_state", subscriptionJson);
            putString(values, "subscription_state", subscriptionJson);
            putString(values, "subscription_data", localData);
            putString(values, "desktop_subscription_state", subscriptionJson);
            putString(values, "flutter.subscription_sync_times", "{\"google\":" + now + "}");
            putString(values, "flutter.synced_purchase_ids",
                    "[\"" + productId + "\",\"capyplayer.pro.lifetime\"]");
            putString(values, "flutter.last_subscription_sync_time", String.valueOf(now));
            putInt(values, "flutter.add_resource_quota", Integer.MAX_VALUE);
            putInt(values, "add_resource_quota", Integer.MAX_VALUE);
            putBool(values, "flutter.isPro", true);
            putBool(values, "isPro", true);
            putString(values, "flutter.subscriptionId", productId);
            putBool(values, "flutter.proFeatureEntitlementReady", true);
            putBool(values, "flutter.subscriptionEntitlementReady", true);
            putBool(values, "proFeatureEntitlementReady", true);
            putBool(values, "subscriptionEntitlementReady", true);
            putBool(values, "flutter.hideSubscriptionCard", true);
            putBool(values, "hideSubscriptionCard", true);
            // Empty credential clears leftover fake tokens (later duplicate key wins).
            putString(values, "flutter.purchaseCredential", "");
            putString(values, "purchaseCredential", "");
            writePb(context, values, LOG_PREFIX);
            SEEDED.set(true);
        } catch (Throwable t) {
            SEEDED.set(false);
            Log.i(MainHook.TAG, LOG_PREFIX + ": failed: " + t.getMessage());
        }
    }

    static boolean needsReseedPublic(File target) {
        return needsReseed(target);
    }

    private static boolean needsReseed(File target) {
        if (target == null || !target.exists()) {
            return true;
        }
        try (FileInputStream in = new FileInputStream(target)) {
            byte[] buf = new byte[(int) Math.min(target.length(), 256 * 1024L)];
            int n = in.read(buf);
            if (n <= 0) {
                return true;
            }
            String text = new String(buf, 0, n, StandardCharsets.ISO_8859_1).toLowerCase();
            return text.contains("\"tier\":\"free\"")
                    || text.contains("rejectedreceipt")
                    || text.contains("zoevip.pro.token")
                    || !text.contains("\"tier\":\"lifetime\"");
        } catch (Throwable t) {
            return true;
        }
    }

    private static void writePb(Context context, Map<String, Object> values, String logPrefix) throws Exception {
        File dir = new File(context.getFilesDir(), "datastore");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.i(MainHook.TAG, logPrefix + ": mkdir failed");
            return;
        }
        File target = new File(dir, FILE_NAME);
        File temp = new File(dir, FILE_NAME + ".tmp");
        byte[] encoded = encodeMap(values);
        // DataStore map: later duplicate keys win. Overlay onto the real file
        // instead of replacing it (a full replace wiped ~95KB of prefs).
        long existing = target.exists() ? target.length() : 0L;
        try (FileOutputStream out = new FileOutputStream(temp)) {
            if (existing > encoded.length) {
                try (FileInputStream in = new FileInputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
            out.write(encoded);
            out.flush();
        }
        if (target.exists() && !target.delete()) {
            Log.i(MainHook.TAG, logPrefix + ": could not replace existing pb");
        }
        if (!temp.renameTo(target)) {
            Log.i(MainHook.TAG, logPrefix + ": rename failed");
        } else {
            Log.i(MainHook.TAG, logPrefix + ": seeded pb ("
                    + temp.length() + " bytes, overlay " + encoded.length
                    + ", kept " + existing + ")");
        }
    }

    static boolean isPreferencesPbPath(String path) {
        return path != null && path.contains("FlutterSharedPreferences.preferences_pb");
    }

    private static void putString(Map<String, Object> map, String key, String value) {
        map.put(key, Value.string(value));
    }

    private static void putInt(Map<String, Object> map, String key, int value) {
        map.put(key, Value.integer(value));
    }

    private static void putBool(Map<String, Object> map, String key, boolean value) {
        map.put(key, Value.bool(value));
    }

    private static byte[] encodeMap(Map<String, Object> entries) throws IOException {
        PreferencesProtoWriter writer = new PreferencesProtoWriter();
        for (Map.Entry<String, Object> entry : entries.entrySet()) {
            writer.writeEntry(entry.getKey(), (Value) entry.getValue());
        }
        return writer.toByteArray();
    }

    private static final class Value {
        final int wireField;
        final Object payload;

        private Value(int wireField, Object payload) {
            this.wireField = wireField;
            this.payload = payload;
        }

        static Value string(String s) {
            return new Value(5, s);
        }

        static Value integer(int i) {
            return new Value(3, i);
        }

        static Value bool(boolean b) {
            return new Value(1, b);
        }
    }

    private static final class PreferencesProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void writeEntry(String key, Value value) throws IOException {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            byte[] valueBytes = encodeValue(value);
            byte[] entryBytes = concat(encodeLengthDelimited(1, keyBytes), encodeLengthDelimited(2, valueBytes));
            writeLengthDelimited(1, entryBytes);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }

        private static byte[] encodeValue(Value value) throws IOException {
            ByteArrayOutputStream valueOut = new ByteArrayOutputStream();
            switch (value.wireField) {
                case 1:
                    writeTag(valueOut, 1, 0);
                    valueOut.write(Boolean.TRUE.equals(value.payload) ? 1 : 0);
                    break;
                case 3:
                    writeTag(valueOut, 3, 0);
                    writeVarint(valueOut, (Integer) value.payload);
                    break;
                case 5:
                    writeTag(valueOut, 5, 2);
                    byte[] bytes = ((String) value.payload).getBytes(StandardCharsets.UTF_8);
                    writeVarint(valueOut, bytes.length);
                    valueOut.write(bytes);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported value field " + value.wireField);
            }
            return valueOut.toByteArray();
        }

        private void writeLengthDelimited(int fieldNumber, byte[] payload) throws IOException {
            writeTag(out, fieldNumber, 2);
            writeVarint(out, payload.length);
            out.write(payload);
        }

        private static byte[] encodeLengthDelimited(int fieldNumber, byte[] payload) throws IOException {
            ByteArrayOutputStream fieldOut = new ByteArrayOutputStream();
            writeTag(fieldOut, fieldNumber, 2);
            writeVarint(fieldOut, payload.length);
            fieldOut.write(payload);
            return fieldOut.toByteArray();
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] merged = new byte[a.length + b.length];
            System.arraycopy(a, 0, merged, 0, a.length);
            System.arraycopy(b, 0, merged, a.length, b.length);
            return merged;
        }

        private static void writeTag(OutputStream out, int fieldNumber, int wireType) throws IOException {
            writeVarint(out, (fieldNumber << 3) | wireType);
        }

        private static void writeVarint(OutputStream out, int value) throws IOException {
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
    }
}
