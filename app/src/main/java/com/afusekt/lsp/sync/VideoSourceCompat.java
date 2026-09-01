package com.afusekt.lsp.sync;

import com.afusekt.lsp.util.Reflect;

import org.json.JSONObject;

import java.lang.reflect.Method;

/** Read/write VideoSource across Afusekt versions (obfuscated fields vs Kotlin names). */
public final class VideoSourceCompat {

    private VideoSourceCompat() {
    }

    public static String getId(Object source) {
        return stringValue(source, "getId", "id", "a");
    }

    public static String getSourceType(Object source) {
        return stringValue(source, "o", "getSourceType", "sourceType", "e");
    }

    public static boolean isScan(Object source) {
        return boolValue(source, "q", "isScan", "k");
    }

    public static JSONObject toJson(Object source, String userAccount) throws Exception {
        JSONObject item = new JSONObject();
        item.put("id", 0);
        item.put("sourceId", getId(source));
        item.put("name", stringValue(source, "getName", "name", "b"));
        item.put("account", stringValue(source, "b", "getAccount", "account", "c"));
        item.put("pass", stringValue(source, "j", "getPass", "pass", "d"));
        item.put("sourceType", getSourceType(source));
        item.put("token", stringValue(source, "p", "getToken", "token", "f"));
        item.put("path", stringValue(source, "k", "getPath", "path", "g"));
        item.put("optCode", stringValue(source, "i", "getOtpCode", "otpCode", "h"));
        item.put("refresh", boolValue(source, "l", "isRefresh", "refresh", "j"));
        item.put("isScan", isScan(source));
        item.put("isShow", boolValue(source, "m", "isShow", "show", "l"));
        item.put("backup", stringValue(source, "f", "getBackup", "backup", "m"));
        item.put("userAccount", userAccount == null ? "" : userAccount);
        item.put("sort", intValue(source, "n", "getSort", "sort"));
        item.put("icon", stringValue(source, "g", "getIcon", "icon", "p"));
        item.put("lockPassword", stringValue(source, "h", "getLockPassword", "lockPassword", "q"));
        item.put("background", stringValue(source, "e", "getBackground", "background", "r"));
        item.put("attachedLine", stringValue(source, "c", "getAttachedLine", "attachedLine", "s"));
        item.put("attachedLineName", stringValue(source, "d", "getAttachedLineName", "attachedLineName"));
        return item;
    }

    public static Object fromJson(JSONObject item, Class<?> videoSourceClass) {
        String sourceId = optString(item, "sourceId");
        String name = optString(item, "name");
        String account = optString(item, "account");
        String pass = optString(item, "pass");
        String sourceType = optString(item, "sourceType");
        String token = optString(item, "token");
        String path = optString(item, "path");
        String optCode = optString(item, "optCode");
        boolean refresh = item.optBoolean("refresh", false);
        boolean isScan = item.optBoolean("isScan", true);
        boolean isShow = item.optBoolean("isShow", true);
        String backup = optString(item, "backup");
        int sort = item.optInt("sort", 0);
        String icon = optString(item, "icon");
        String lockPassword = optString(item, "lockPassword");
        String background = optString(item, "background");
        String attachedLine = optString(item, "attachedLine");
        String attachedLineName = optString(item, "attachedLineName");

        Object modern = tryNewInstance(
                videoSourceClass,
                sourceId, name, account, pass, sourceType, token, path, optCode,
                refresh, isScan, isShow, backup, sort, icon,
                lockPassword, background, attachedLine, attachedLineName
        );
        if (modern != null) {
            return modern;
        }
        return Reflect.newInstance(
                videoSourceClass,
                sourceId,
                name,
                account,
                pass,
                sourceType,
                token,
                path,
                optCode,
                refresh,
                isScan,
                isShow,
                backup,
                sort,
                icon,
                lockPassword,
                background,
                attachedLine,
                optString(item, "userAccount")
        );
    }

    private static Object tryNewInstance(Class<?> cls, Object... args) {
        try {
            return Reflect.newInstance(cls, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringValue(Object source, String... names) {
        for (String name : names) {
            Object value = read(source, name);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static boolean boolValue(Object source, String... names) {
        for (String name : names) {
            Object value = read(source, name);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        }
        return false;
    }

    private static int intValue(Object source, String... names) {
        for (String name : names) {
            Object value = read(source, name);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        }
        return 0;
    }

    private static Object read(Object source, String name) {
        if (source == null || name == null || name.isEmpty()) {
            return null;
        }
        try {
            Method method = source.getClass().getMethod(name);
            return method.invoke(source);
        } catch (Throwable ignored) {
        }
        try {
            return Reflect.getObjectField(source, name);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String optString(JSONObject item, String key) {
        if (!item.has(key) || item.isNull(key)) {
            return "";
        }
        return item.optString(key, "");
    }
}
