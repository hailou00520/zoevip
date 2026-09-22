package com.afusekt.lsp.hook;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal StandardMessageCodec reader/writer for patching Flutter platform replies.
 * Only supports types needed by shared_preferences (String + Map of String values).
 */
final class PlatformMessagePatcher {

    private static final byte STRING = 7;
    private static final byte MAP = 13;

    private PlatformMessagePatcher() {
    }

    static ByteBuffer patch(ByteBuffer message, int position) {
        if (message == null) {
            return null;
        }
        try {
            ByteBuffer slice = message.duplicate();
            slice.order(ByteOrder.nativeOrder());
            slice.position(position);
            // Skip huge buffers (library dumps / binary blobs) — decode would OOM.
            if (slice.remaining() > 262144) {
                return null;
            }
            Object decoded = readValue(slice);
            Object patched = CapyPlayerEntitlementSupport.patchPlatformValue(decoded);
            if (patched == decoded) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeValue(out, patched);
            return ByteBuffer.wrap(out.toByteArray());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readValue(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }
        byte type = buffer.get();
        switch (type) {
            case 0:
                return null;
            case 1:
                return Boolean.TRUE;
            case 2:
                return Boolean.FALSE;
            case 3:
                return buffer.getInt();
            case 6:
                return buffer.getDouble();
            case STRING:
                return readString(buffer);
            case MAP:
                return readMap(buffer);
            default:
                throw new IllegalArgumentException("Unsupported codec type: " + type);
        }
    }

    private static Map<Object, Object> readMap(ByteBuffer buffer) {
        int size = readSize(buffer);
        Map<Object, Object> map = new LinkedHashMap<>(Math.max(size, 0));
        for (int i = 0; i < size; i++) {
            Object key = readValue(buffer);
            Object value = readValue(buffer);
            map.put(key, value);
        }
        return map;
    }

    private static String readString(ByteBuffer buffer) {
        int size = readSize(buffer);
        byte[] bytes = new byte[size];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readSize(ByteBuffer buffer) {
        int first = buffer.get() & 0xFF;
        if (first < 254) {
            return first;
        }
        if (first == 254) {
            return buffer.getChar();
        }
        return buffer.getInt();
    }

    private static void writeValue(ByteArrayOutputStream out, Object value) throws java.io.IOException {
        if (value == null) {
            out.write(0);
        } else if (value instanceof Boolean) {
            out.write(Boolean.TRUE.equals(value) ? 1 : 2);
        } else if (value instanceof Integer) {
            out.write(3);
            writeInt(out, (Integer) value);
        } else if (value instanceof Long) {
            out.write(4);
            writeLong(out, (Long) value);
        } else if (value instanceof Double) {
            out.write(6);
            writeLong(out, Double.doubleToLongBits((Double) value));
        } else if (value instanceof String) {
            out.write(STRING);
            writeString(out, (String) value);
        } else if (value instanceof Map) {
            out.write(MAP);
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) value;
            writeSize(out, map.size());
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                writeValue(out, entry.getKey());
                writeValue(out, entry.getValue());
            }
        } else {
            throw new IllegalArgumentException("Unsupported value: " + value.getClass());
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeSize(out, bytes.length);
        out.write(bytes);
    }

    private static void writeSize(ByteArrayOutputStream out, int size) throws java.io.IOException {
        if (size < 0) {
            throw new IllegalArgumentException("Negative size");
        }
        if (size < 254) {
            out.write(size);
        } else if (size <= 0xFFFF) {
            out.write(254);
            out.write((size >> 8) & 0xFF);
            out.write(size & 0xFF);
        } else {
            out.write(255);
            writeInt(out, size);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws java.io.IOException {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) throws java.io.IOException {
        writeInt(out, (int) (value >> 32));
        writeInt(out, (int) value);
    }

    static boolean looksLikeSubscriptionPayload(Object value) {
        if (value instanceof String) {
            return isSubscriptionText((String) value);
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (looksLikeSubscriptionPayload(entry.getKey())
                        || looksLikeSubscriptionPayload(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSubscriptionText(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("subscription")
                || lower.contains("hassubscription")
                || lower.contains("add_resource_quota")
                || lower.contains("ispro");
    }
}
