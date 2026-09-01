package com.afusekt.lsp.hook;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.AssetManager;
import android.os.Build;

import com.afusekt.lsp.MainHook;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Make nPatch / LSPatch-repacked 番茄小说 / 红果短剧 look official to PackageManager.
 * Reads {@code originalSignature} from {@code assets/npatch/config.json} or
 * {@code assets/lspatch/config.json}. Local spoof is required when patch sig bypass is weak,
 * otherwise server may return code 110 (版本不安全).
 */
public final class FanqieNovelSafeHooks {

    private static final String TAG = MainHook.TAG + ":FanqieNovelSafe";
    private static final String PM = "android.app.ApplicationPackageManager";

    private static final String[] HIDE_PACKAGES = {
            "com.zoevip.lsp",
            "top.nkbe.npatch",
            "org.lsposed.manager",
            "org.lsposed.manager.v2",
            "de.robv.android.xposed.installer",
    };

    private static final String[] HIDE_PACKAGE_PREFIXES = {
            "org.lsposed.",
            "com.zoevip.",
            "io.github.libxposed.",
            "top.nkbe.",
    };

    private static volatile Signature[] originalSignatures;

    private FanqieNovelSafeHooks() {
    }

    public static void apply(ClassLoader cl) {
        apply(cl, null);
    }

    public static void apply(ClassLoader cl, ApplicationInfo appInfo) {
        if (appInfo != null) {
            loadOriginalSignatureFromApk(appInfo.sourceDir);
        }
        int hooks = 0;
        hooks += hookGetPackageInfo(cl);
        hooks += hookGetPackageInfoFlags(cl);
        hooks += hookGetPackageInfoLong(cl);
        hooks += hookGetInstalledPackages(cl);
        hooks += hookHasSigningCertificate(cl);
        hooks += hookSigningInfoReaders();
        ProcMapsFilter.install();
        XposedBridge.log(TAG + ": safe hooks installed (" + hooks
                + "), sigReady=" + (originalSignatures != null));
    }

    public static void onApplication(Application app) {
        loadOriginalSignature(app);
        if (originalSignatures != null) {
            XposedBridge.log(TAG + ": original signature ready ("
                    + originalSignatures[0].toByteArray().length + " bytes)");
        } else {
            XposedBridge.log(TAG + ": original signature missing — nPatch config not found");
        }
    }

    /** Prefer early load from APK path so first PM queries already see the official sig. */
    private static void loadOriginalSignatureFromApk(String apkPath) {
        if (originalSignatures != null || apkPath == null || apkPath.isEmpty()) {
            return;
        }
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            int cookie = (Integer) XposedHelpers.callMethod(am, "addAssetPath", apkPath);
            if (cookie == 0) {
                return;
            }
            openPatchConfig(am);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": early signature load failed: " + t.getMessage());
        }
    }

    private static void loadOriginalSignature(Application app) {
        if (originalSignatures != null) {
            return;
        }
        try {
            openPatchConfig(app.getAssets());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": load originalSignature failed: " + t.getMessage());
        }
    }

    private static void openPatchConfig(AssetManager assets) throws Exception {
        String[] candidates = {
                "npatch/config.json",
                "lspatch/config.json",
        };
        Throwable last = null;
        for (String path : candidates) {
            try (InputStream in = assets.open(path)) {
                parseAndSetSignature(readAll(in));
                XposedBridge.log(TAG + ": loaded originalSignature from " + path);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            if (last instanceof Exception) {
                throw (Exception) last;
            }
            throw new Exception(last);
        }
    }

    private static void parseAndSetSignature(String jsonText) throws Exception {
        JSONObject json = new JSONObject(jsonText);
        String hex = json.optString("originalSignature", "");
        if (hex.isEmpty()) {
            return;
        }
        byte[] cert = hexToBytes(hex);
        originalSignatures = new Signature[]{new Signature(cert)};
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    private static int hookGetPackageInfo(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(PM, cl, "getPackageInfo",
                    String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shouldHidePackage(String.valueOf(param.args[0]))) {
                                param.setThrowable(new PackageManager.NameNotFoundException(
                                        String.valueOf(param.args[0])));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            patchPackageInfo(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": getPackageInfo hook failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookGetPackageInfoFlags(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            return 0;
        }
        try {
            Class<?> flagsCls = Class.forName("android.content.pm.PackageManager$PackageInfoFlags");
            XposedHelpers.findAndHookMethod(PM, cl, "getPackageInfo",
                    String.class, flagsCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shouldHidePackage(String.valueOf(param.args[0]))) {
                                param.setThrowable(new PackageManager.NameNotFoundException(
                                        String.valueOf(param.args[0])));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            patchPackageInfo(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGetPackageInfoLong(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) {
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(PM, cl, "getPackageInfo",
                    String.class, long.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shouldHidePackage(String.valueOf(param.args[0]))) {
                                param.setThrowable(new PackageManager.NameNotFoundException(
                                        String.valueOf(param.args[0])));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            patchPackageInfo(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookGetInstalledPackages(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(PM, cl, "getInstalledPackages", int.class,
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof List)) {
                                return;
                            }
                            List<PackageInfo> list = (List<PackageInfo>) result;
                            ArrayList<PackageInfo> filtered = new ArrayList<>(list.size());
                            for (PackageInfo info : list) {
                                if (info == null || shouldHidePackage(info.packageName)) {
                                    continue;
                                }
                                if (MainHook.FANQIE_NOVEL_PACKAGE.equals(info.packageName)) {
                                    applyOriginalSignature(info);
                                }
                                filtered.add(info);
                            }
                            param.setResult(filtered);
                        }
                    });
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hookHasSigningCertificate(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 28) {
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(PM, cl, "hasSigningCertificate",
                    String.class, byte[].class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!MainHook.FANQIE_NOVEL_PACKAGE.equals(String.valueOf(param.args[0]))) {
                                return;
                            }
                            Signature[] sigs = originalSignatures;
                            byte[] want = (byte[]) param.args[1];
                            if (sigs == null || want == null) {
                                return;
                            }
                            if (Arrays.equals(sigs[0].toByteArray(), want)) {
                                param.setResult(true);
                            }
                        }
                    });
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hookSigningInfoReaders() {
        if (Build.VERSION.SDK_INT < 28) {
            return 0;
        }
        int count = 0;
        try {
            XposedHelpers.findAndHookMethod(SigningInfo.class, "getApkContentsSigners",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Signature[] sigs = originalSignatures;
                            if (sigs != null) {
                                param.setResult(sigs);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(SigningInfo.class, "getSigningCertificateHistory",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Signature[] sigs = originalSignatures;
                            if (sigs != null) {
                                param.setResult(sigs);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(SigningInfo.class, "hasMultipleSigners",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (originalSignatures != null) {
                                param.setResult(false);
                            }
                        }
                    });
            count++;
        } catch (Throwable ignored) {
        }
        return count > 0 ? 1 : 0;
    }

    private static void patchPackageInfo(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (!(result instanceof PackageInfo info)) {
            return;
        }
        if (!MainHook.FANQIE_NOVEL_PACKAGE.equals(info.packageName)
                && !MainHook.FANQIE_NOVEL_PACKAGE.equals(String.valueOf(param.args[0]))) {
            return;
        }
        applyOriginalSignature(info);
    }

    private static void applyOriginalSignature(PackageInfo info) {
        Signature[] sigs = originalSignatures;
        if (info == null || sigs == null) {
            return;
        }
        try {
            info.signatures = sigs;
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        try {
            SigningInfo signingInfo = buildSigningInfo(sigs);
            if (signingInfo != null) {
                info.signingInfo = signingInfo;
            }
        } catch (Throwable ignored) {
        }
    }

    private static SigningInfo buildSigningInfo(Signature[] sigs) {
        try {
            for (Constructor<?> ctor : SigningInfo.class.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0].getName().contains("SigningDetails")) {
                    Object details = buildSigningDetails(sigs, params[0]);
                    if (details == null) {
                        continue;
                    }
                    ctor.setAccessible(true);
                    return (SigningInfo) ctor.newInstance(details);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object buildSigningDetails(Signature[] sigs, Class<?> detailsCls) {
        try {
            for (Constructor<?> ctor : detailsCls.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length >= 1 && params[0] == Signature[].class) {
                    ctor.setAccessible(true);
                    Object[] args = new Object[params.length];
                    args[0] = sigs;
                    for (int i = 1; i < params.length; i++) {
                        if (params[i] == int.class) {
                            args[i] = 0;
                        } else if (params[i] == boolean.class) {
                            args[i] = false;
                        } else {
                            args[i] = null;
                        }
                    }
                    return ctor.newInstance(args);
                }
            }
            try {
                Class<?> builderCls = Class.forName(detailsCls.getName() + "$Builder");
                Object builder = builderCls.getDeclaredConstructor(Signature[].class)
                        .newInstance((Object) sigs);
                Method build = builderCls.getDeclaredMethod("build");
                return build.invoke(builder);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean shouldHidePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        for (String exact : HIDE_PACKAGES) {
            if (pkg.equals(exact)) {
                return true;
            }
        }
        for (String prefix : HIDE_PACKAGE_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
