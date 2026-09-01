package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks server sync from overwriting lifetime Pro in preferences_pb.
 */
final class PreferencesPbWriteGuard {

    private static final Map<FileOutputStream, String> STREAM_PATHS = new java.util.WeakHashMap<>();
    private static final Set<String> GUARDED_PATHS = ConcurrentHashMap.newKeySet();

    private PreferencesPbWriteGuard() {
    }

    static void install() {
        try {
            XC_MethodHook trackPath = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    File file = (File) param.args[0];
                    if (file == null || param.getResult() == null) {
                        return;
                    }
                    if (PreferencesPbSeeder.isPreferencesPbPath(file.getAbsolutePath())) {
                        FileOutputStream stream = (FileOutputStream) param.thisObject;
                        STREAM_PATHS.put(stream, file.getAbsolutePath());
                        GUARDED_PATHS.add(file.getAbsolutePath());
                    }
                }
            };
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, trackPath);
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, boolean.class, trackPath);
            XposedBridge.hookAllMethods(FileOutputStream.class, "write", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    FileOutputStream stream = (FileOutputStream) param.thisObject;
                    String path = STREAM_PATHS.get(stream);
                    if (path == null || !GUARDED_PATHS.contains(path)) {
                        return;
                    }
                    byte[] payload = extractBytes(param.args);
                    if (payload != null && containsDowngrade(payload)) {
                        param.setResult(null);
                        XposedBridge.log(MainHook.TAG + ":CapyPlayer: blocked pb downgrade write");
                    }
                }
            });
            XposedBridge.log(MainHook.TAG + ":CapyPlayer: preferences_pb write guard installed");
            android.util.Log.i(MainHook.TAG, MainHook.TAG + ":CapyPlayer: preferences_pb write guard installed");
        } catch (Throwable t) {
            XposedBridge.log(MainHook.TAG + ":CapyPlayer: pb write guard skipped: " + t.getMessage());
        }
    }

    private static byte[] extractBytes(Object[] args) {
        if (args.length == 1 && args[0] instanceof byte[]) {
            return (byte[]) args[0];
        }
        if (args.length == 3 && args[0] instanceof byte[]) {
            byte[] buffer = (byte[]) args[0];
            int offset = (Integer) args[1];
            int length = (Integer) args[2];
            byte[] slice = new byte[length];
            System.arraycopy(buffer, offset, slice, 0, length);
            return slice;
        }
        return null;
    }

    private static boolean containsDowngrade(byte[] payload) {
        String lower = new String(payload, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return lower.contains("hassubscription")
                && (lower.contains(":false") || lower.contains("\"tier\":\"free\""));
    }
}
