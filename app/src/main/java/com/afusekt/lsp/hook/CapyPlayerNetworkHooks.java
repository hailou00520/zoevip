package com.afusekt.lsp.hook;

import com.afusekt.lsp.MainHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Intercepts subscription HTTP payloads before Dart consumes them. */
final class CapyPlayerNetworkHooks {

    private static final String TAG = MainHook.TAG + ":CapyPlayer";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private CapyPlayerNetworkHooks() {
    }

    static void install(ClassLoader cl) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        hookOkHttp(cl);
        hookSocketReads(cl);
        log("network hooks installed");
    }

    private static void hookOkHttp(ClassLoader cl) {
        try {
            Class<?> body = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    patchResult(param);
                }
            };
            XposedHelpers.findAndHookMethod(body, "string", hook);
            XposedHelpers.findAndHookMethod(body, "bytes", hook);
            try {
                XposedHelpers.findAndHookMethod(body, "byteStream", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // byteStream is harder to patch safely; string/bytes cover most callers.
                    }
                });
            } catch (Throwable ignored) {
            }
            log("OkHttp ResponseBody hooked");
        } catch (Throwable t) {
            log("OkHttp hook skipped: " + t.getMessage());
        }
    }

    private static void hookSocketReads(ClassLoader cl) {
        try {
            XC_MethodHook readHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof Integer) {
                        int read = (Integer) result;
                        if (read <= 0) {
                            return;
                        }
                        patchReadBuffer(param.args, read);
                    }
                }
            };
            XposedHelpers.findAndHookMethod("java.net.SocketInputStream", cl,
                    "read", byte[].class, int.class, int.class, readHook);
            XposedHelpers.findAndHookMethod("java.net.SocketInputStream", cl,
                    "read", byte[].class, readHook);
            log("SocketInputStream hooked");
        } catch (Throwable t) {
            log("SocketInputStream hook skipped: " + t.getMessage());
        }
    }

    private static void patchReadBuffer(Object[] args, int read) {
        if (args == null || args.length == 0 || !(args[0] instanceof byte[])) {
            return;
        }
        byte[] buffer = (byte[]) args[0];
        int offset = 0;
        int length = read;
        if (args.length >= 3 && args[1] instanceof Integer && args[2] instanceof Integer) {
            offset = (Integer) args[1];
            length = (Integer) args[2];
        }
        if (offset < 0 || length <= 0 || offset + length > buffer.length) {
            return;
        }
        String chunk = new String(buffer, offset, length, StandardCharsets.UTF_8);
        if (!CapyPlayerSubscriptionPatcher.looksLikeSubscriptionPayload(chunk)) {
            return;
        }
        String patched = CapyPlayerSubscriptionPatcher.patchText(chunk);
        if (patched.equals(chunk)) {
            return;
        }
        byte[] bytes = patched.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > length) {
            return;
        }
        System.arraycopy(bytes, 0, buffer, offset, bytes.length);
        if (bytes.length < length) {
            for (int i = offset + bytes.length; i < offset + length; i++) {
                buffer[i] = ' ';
            }
        }
        log("patched SocketInputStream subscription chunk");
    }

    private static void patchResult(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (result instanceof String) {
            String patched = CapyPlayerSubscriptionPatcher.patchText((String) result);
            if (!patched.equals(result)) {
                param.setResult(patched);
                log("patched OkHttp subscription body");
            }
            return;
        }
        if (result instanceof byte[]) {
            byte[] patched = CapyPlayerSubscriptionPatcher.patchBytes((byte[]) result);
            if (patched != result) {
                param.setResult(patched);
                log("patched OkHttp subscription bytes");
            }
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
