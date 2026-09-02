package com.afusekt.lsp.hook;

import android.app.Application;
import android.view.View;

import com.afusekt.lsp.LvchaHookSupport;
import com.afusekt.lsp.LvchaUiStrip;
import com.afusekt.lsp.LvchaUiStripHooks;
import com.afusekt.lsp.LvchaUnlockConfig;
import com.afusekt.lsp.MainHook;
import com.afusekt.lsp.ZoeIds;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 绿茶 VPN / LVCHA classic Xposed path (NPatch / LSPatch).
 */
public final class LvchaHooks {

    private static final String TAG = MainHook.TAG + ":Lvcha";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CLASS_LOADER_MONITOR = new AtomicBoolean(false);
    private static final Set<String> HOOKED = new HashSet<>();

    private LvchaHooks() {
    }

    public static void apply(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!ZoeIds.isLvchaPackage(lpparam.packageName)) {
            return;
        }
        installClassLoaderMonitor();
        tryInstall(lpparam.classLoader, "load-package");
        hookApplicationOnCreate(lpparam.classLoader);
        scheduleRetry(lpparam.classLoader);
    }

    private static void scheduleRetry(ClassLoader cl) {
        for (long delay : LvchaHookSupport.RETRY_DELAYS_MS) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                tryInstall(cl, "retry@" + delay);
            }, "ZoeVIP-lvcha-" + delay);
            t.setDaemon(true);
            t.start();
        }
    }

    private static void hookApplicationOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (ZoeIds.isLvchaPackage(app.getPackageName())) {
                        tryInstall(app.getClassLoader(), "application");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Application.onCreate hook failed: " + t.getMessage());
        }
    }

    private static void installClassLoaderMonitor() {
        if (!CLASS_LOADER_MONITOR.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    ClassLoader.class,
                    "loadClass",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (LvchaHookSupport.isUserManagerClass(name)) {
                                tryInstall((ClassLoader) param.thisObject, "loadClass:" + name);
                            }
                        }
                    }
            );
            XposedBridge.log(TAG + ": ClassLoader.loadClass monitor installed");
        } catch (Throwable t) {
            CLASS_LOADER_MONITOR.set(false);
            XposedBridge.log(TAG + ": ClassLoader monitor failed: " + t.getMessage());
        }
    }

    private static void tryInstall(ClassLoader cl, String source) {
        if (INSTALLED.get()) {
            return;
        }
        try {
            Class.forName(LvchaHookSupport.USER_MANAGER, false, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hooks deferred (" + source + "): " + t.getMessage());
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedBridge.log(TAG + ": hooks installing (" + source + ")");
        try {
            int count = installHooks(cl);
            count += installUiStripHooks(cl);
            XposedBridge.log(TAG + ": hooks installed (" + count + ")");
        } catch (Throwable t) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": hooks failed: " + t.getMessage());
        }
    }

    private static int installHooks(ClassLoader cl) {
        int count = 0;
        count += hookReturn(cl, "h0", true);
        count += hookReturn(cl, "g0", true);
        count += hookDynamicLong(cl, "H");
        count += hookReturn(cl, "X", LvchaUnlockConfig.DIAMOND_VIP_TYPE);
        count += hookReturn(cl, "S", 99);
        count += hookReturn(cl, "f0", true);
        return count;
    }

    private static int installUiStripHooks(ClassLoader cl) {
        int count = LvchaUiStripHooks.install(new ClassicUiBackend(), cl);
        count += hookReturn(cl, "z", null);
        return count;
    }

    private static int hookDynamicLong(ClassLoader cl, String methodName) {
        try {
            Class<?> cls = Class.forName(LvchaHookSupport.USER_MANAGER, false, cl);
            Method method = LvchaHookSupport.findNoArgMethod(cls, methodName);
            if (method == null) {
                XposedBridge.log(TAG + ": method not found: " + methodName);
                return 0;
            }
            String id = cls.getName() + "#" + methodName + "#dynamic";
            if (!HOOKED.add(id)) {
                return 0;
            }
            XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return LvchaUnlockConfig.diamondRemainMs();
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static int hookReturn(ClassLoader cl, String methodName, Object value) {
        try {
            Class<?> cls = Class.forName(LvchaHookSupport.USER_MANAGER, false, cl);
            Method method = LvchaHookSupport.findNoArgMethod(cls, methodName);
            if (method == null) {
                XposedBridge.log(TAG + ": method not found: " + methodName);
                return 0;
            }
            String id = cls.getName() + "#" + methodName;
            if (!HOOKED.add(id)) {
                return 0;
            }
            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(value));
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + methodName + " failed: " + t.getMessage());
            return 0;
        }
    }

    private static final class ClassicUiBackend implements LvchaUiStripHooks.Backend {
        @Override
        public boolean register(Method method) {
            return HOOKED.add(LvchaHookSupport.hookId(method));
        }

        @Override
        public void hookAfter(Method method, LvchaUiStripHooks.AfterListener listener) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    listener.onAfter(param.thisObject, param.args, param.getResult());
                }
            });
        }

        @Override
        public void hookReplaceNull(Method method) {
            XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(null));
        }

        @Override
        public void hookReplace(Method method, LvchaUiStripHooks.ReplaceListener listener) {
            XposedBridge.hookMethod(method, new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return listener.replace(param.thisObject, param.args);
                }
            });
        }

        @Override
        public void hookShortCircuit(Method method, LvchaUiStripHooks.ShortCircuitListener listener) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (listener.skipOriginal(param.thisObject, param.args)) {
                        param.setResult(null);
                    }
                }
            });
        }

        @Override
        public int installViewPagerRemap(ClassLoader cl) {
            int count = 0;
            try {
                Class<?> viewPagerCls = Class.forName(
                        "androidx.viewpager.widget.ViewPager", false, cl);
                XC_MethodHook remapPager = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof View view
                                && LvchaUiStrip.isMainActivityPager(view)
                                && param.args[0] instanceof Integer index) {
                            param.args[0] = LvchaUiStrip.remapMainPagerIndex(index);
                        }
                    }
                };
                XposedHelpers.findAndHookMethod(
                        viewPagerCls, "setCurrentItem", int.class, boolean.class, remapPager);
                XposedHelpers.findAndHookMethod(
                        viewPagerCls, "setCurrentItem", int.class, remapPager);
                count += 2;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ViewPager remap failed: " + t.getMessage());
            }
            return count;
        }
    }
}
