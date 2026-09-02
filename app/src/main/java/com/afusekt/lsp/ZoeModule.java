package com.afusekt.lsp;

import androidx.annotation.NonNull;

import com.afusekt.lsp.libxposed.LibAfusektHooks;
import com.afusekt.lsp.libxposed.LibAfusektShield;
import com.afusekt.lsp.libxposed.LibAntiDetect;
import com.afusekt.lsp.libxposed.LibFanqieHooks;
import com.afusekt.lsp.libxposed.LibFanqieNovelHooks;
import com.afusekt.lsp.libxposed.LegacyHookBridge;
import com.afusekt.lsp.libxposed.LibHideCheck;
import com.afusekt.lsp.libxposed.LibHillsHooks;
import com.afusekt.lsp.libxposed.LibLvchaHooks;
import com.afusekt.lsp.libxposed.LibMtxxHooks;
import com.afusekt.lsp.libxposed.LibProcMapsFilter;
import com.afusekt.lsp.libxposed.LibXimalayaHooks;
import com.afusekt.lsp.libxposed.LibVToolsHooks;
import com.afusekt.lsp.libxposed.LibVidHubHooks;
import com.afusekt.lsp.libxposed.LibVidHubShield;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/** libxposed entry (Zot-style). Legacy XposedBridge metadata removed from manifest. */
public final class ZoeModule extends XposedModule {

    private static final AtomicBoolean ANTI_DETECT_INSTALLED = new AtomicBoolean(false);

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        String pkg = param.getPackageName();
        // Afusekt: skip LibAntiDetect — stack/ClassLoader hooks freeze Compose UI (white screen).
        if (!ZoeIds.AFUSEKT_PACKAGE.equals(pkg)) {
            installAntiDetectOnce();
        }
        if (ZoeIds.VIDHUB_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibVidHubShield.installEarly(this, param);
            LibVidHubHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.VTOOLS_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibVToolsHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.XIMALAYA_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibXimalayaHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.MTXX_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibMtxxHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.FANQIE_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibFanqieHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.isDragonReadFamily(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            // Do NOT call FanqieNovelSafeHooks here — it pulls classic XposedBridge /
            // MainHook and crashes Vector/LSPosed API 102+.
            LibFanqieNovelHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.HILLS_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibProcMapsFilter.install(this);
            LibHillsHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.isLvchaPackage(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibLvchaHooks.onPackageLoaded(this, param.getDefaultClassLoader());
            return;
        }
        if (ZoeIds.AFUSEKT_PACKAGE.equals(pkg)) {
            log(4, ZoeIds.TAG, "onPackageLoaded: " + pkg);
            LibAfusektShield.installEarly(this, param);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!ZoeIds.AFUSEKT_PACKAGE.equals(pkg)) {
            installAntiDetectOnce();
        }
        log(4, ZoeIds.TAG, "onPackageReady: " + pkg);

        if (ZoeIds.VIDHUB_PACKAGE.equals(pkg)) {
            LibVidHubShield.install(this, param);
            LibHideCheck.warnIfExposed(this);
            LibVidHubHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.VTOOLS_PACKAGE.equals(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibVToolsHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.AFUSEKT_PACKAGE.equals(pkg)) {
            LibAfusektHooks.installLate(this, param);
            return;
        }
        if (ZoeIds.XIMALAYA_PACKAGE.equals(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibXimalayaHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.MTXX_PACKAGE.equals(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibMtxxHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.FANQIE_PACKAGE.equals(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibFanqieHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.isDragonReadFamily(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibFanqieNovelHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.HILLS_PACKAGE.equals(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibHillsHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.isLvchaPackage(pkg)) {
            LibHideCheck.warnIfExposed(this);
            LibLvchaHooks.onPackageReady(this, param);
            return;
        }
        if (ZoeIds.CAPYPLAYER_PACKAGE.equals(pkg)) {
            try {
                LegacyHookBridge.route(param);
            } catch (Throwable t) {
                log(5, ZoeIds.TAG, "CapyPlayer legacy hooks failed: " + t.getMessage());
            }
        }
    }

    private void installAntiDetectOnce() {
        if (ANTI_DETECT_INSTALLED.compareAndSet(false, true)) {
            LibAntiDetect.install(this);
        }
    }
}
