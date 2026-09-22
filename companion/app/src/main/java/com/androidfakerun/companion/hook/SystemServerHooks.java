package com.androidfakerun.companion.hook;

import android.location.Location;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * system_server（包名 {@code android}）侧的加固 Hook，可选。
 *
 * <p>配套在 Hook 模式下本来就不产生 test provider，因此<b>默认不需要勾选“系统框架”</b>；
 * 只有当目标应用走 GMS fused 代理、且用户没有把 GMS 加入作用域时，才需要打开本层。
 * 所有 Hook 都包在 try/catch 里，任何失败只记日志、绝不抛到 system_server，
 * 避免因 Hook 写法与 ROM（MIUI/HyperOS/ColorOS）实现差异导致 system_server 崩溃重启。
 *
 * <p>策略：在 LocationManagerService 真实分发入口处改写 Location 参数坐标，
 * 并清掉 mock 标记。不同 Android 版本类名/方法名不同，这里做模糊适配：
 * 类 {@code com.android.server.location.LocationManagerService}（A10+）/
 * {@code com.android.server.LocationManagerService}（A9-），方法
 * {@code handleLocationChangedLocked/handleLocationChanged/reportLocation/onReportLocation}。
 */
public final class SystemServerHooks {
    private SystemServerHooks() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        Class<?> service = findServiceClass(cl);
        if (service == null) {
            XposedBridge.log("[FakeRun] system_server: LocationManagerService not found, skip.");
            return;
        }
        for (String m : new String[]{"handleLocationChangedLocked", "handleLocationChanged",
                "reportLocation", "onReportLocation", "handleLocationChangedPassive"}) {
            try {
                XposedBridge.hookAllMethods(service, m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            rewriteLocationArgs(param.args);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.log("[FakeRun] system_server hooked " + service.getName() + "#" + m);
            } catch (Throwable ignored) {
                // ROM 里没有这个方法就跳过。
            }
        }
        // A12+ 真实分发入口：LocationProviderManager.onReportLocation
        try {
            Class<?> pm = XposedHelpers.findClass(
                    "com.android.server.location.provider.LocationProviderManager", cl);
            XposedBridge.hookAllMethods(pm, "onReportLocation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        rewriteLocationArgs(param.args);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log("[FakeRun] system_server hooked LocationProviderManager#onReportLocation");
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> findServiceClass(ClassLoader cl) {
        try {
            return XposedHelpers.findClass("com.android.server.location.LocationManagerService", cl);
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.findClass("com.android.server.LocationManagerService", cl);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 注意：system_server 进程没有“当前坐标”（坐标存在配套应用的 Provider 里，
     * system uid 直接跨进程 query 配套 Provider 可能因配套未启动而失败）。
     * 因此本层只做“消毒”（清 mock 标记），不改坐标；坐标改写由目标进程侧的
     * getter Hook 完成。这样即使 Provider 不可用，system_server 也绝不受影响。
     */
    private static void rewriteLocationArgs(Object[] args) {
        if (args == null) return;
        for (Object arg : args) {
            if (arg instanceof Location) {
                Location loc = (Location) arg;
                try {
                    XposedHelpers.setBooleanField(loc, "mMock", false);
                } catch (Throwable ignored) {
                }
                try {
                    XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false);
                } catch (Throwable ignored) {
                }
                try {
                    loc.setMock(false);
                } catch (Throwable ignored) {
                }
                try {
                    Bundle extras = loc.getExtras();
                    if (extras != null) {
                        extras.remove("mockLocation");
                        extras.remove("isMock");
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
