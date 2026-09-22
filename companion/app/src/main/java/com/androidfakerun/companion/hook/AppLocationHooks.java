package com.androidfakerun.companion.hook;

import android.content.ContentResolver;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 目标应用进程内的防检测 Hook（主通道）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>配套在 Hook 模式下<b>从不调用 addTestProvider</b>，系统侧天然就没有 mock provider，
 *       这里再把getter 级坐标统一改写为预设值，因此真实 GPS / fused / network 回调拿到
 *       的任何 {@link Location} 对象读出来的都是假坐标。</li>
 *   <li>强制 {@code isFromMockProvider()/isMock()=false}，并剥掉 extras 里的
 *       {@code mockLocation} 残留，顺手把 provider 统一报成 {@code gps}。</li>
 *   <li>把 {@code getLastKnownLocation/getLastLocation} 直接替换为假 fix；
 *       把 Wi-Fi 扫描 / 基站定位返回掏空，迫使 SDK 回落到 GPS 主通道，避免用真实
 *       无线指纹反推（Wi-Fi BSSID 克隆是 phase-2，见 README）。</li>
 *   <li>拦截 {@code Settings.Secure} 与 {@code AppOpsManager} 里 mock 相关查询，
 *       让目标应用认为“系统从未开启模拟位置”。</li>
 * </ul>
 */
public final class AppLocationHooks {
    /** OP_MOCK_LOCATION 在 AppOpsManager 里的操作码（API 23+ 固定 58）。 */
    private static final int OP_MOCK_LOCATION = 58;
    /** AppOpsManager.MODE_IGNORED：让目标应用认为“没有应用被授模拟位置”。 */
    private static final int MODE_IGNORED = 1;

    private AppLocationHooks() {
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        hookLocationSanitize();
        hookLocationManager();
        hookMockTraces();
        hookRadioFingerprint();
        hookGnssNmea();
    }

    // ---------- Location 本体消毒 ----------

    private static void hookLocationSanitize() {
        // 1) mock 标记直接置 false（覆盖各大厂商 ROM 与 A12+ 的 isMock）。
        try {
            XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider",
                    XC_MethodReplacement.returnConstant(false));
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook isFromMockProvider failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Location.class, "isMock",
                    XC_MethodReplacement.returnConstant(false));
        } catch (Throwable ignored) {
            // API < 31 没有 isMock，忽略。
        }
        // 2) setMock(true) 强制改写为 false，防止系统/其它模块重新打标。
        try {
            XposedHelpers.findAndHookMethod(Location.class, "setMock", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = false;
                        }
                    });
        } catch (Throwable ignored) {
        }
        // 3) 反射兜底：读 extras 前先把 mMock / mIsFromMockProvider 字段清零并剥 mock key。
        try {
            XposedHelpers.findAndHookMethod(Location.class, "getExtras",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            scrubFields(param.thisObject);
                            Bundle extras = (Bundle) param.getResult();
                            if (extras != null) {
                                extras.remove("mockLocation");
                                extras.remove("isMock");
                                extras.remove("mock");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook Location.getExtras failed: " + t);
        }

        // 4) getter 级改写：任何 Location 读出来的都是假坐标（覆盖 fused/GMS/厂商 SDK）。
        hookGetter("getLatitude");
        hookGetter("getLongitude");
        hookGetter("getAccuracy");
        hookGetter("getSpeed");
        hookGetter("getAltitude");
        hookGetter("getBearing");
        try {
            XposedHelpers.findAndHookMethod(Location.class, "getProvider",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (active(param)) param.setResult(LocationManager.GPS_PROVIDER);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void hookGetter(String name) {
        try {
            XposedHelpers.findAndHookMethod(Location.class, name, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    HookConfig.Snapshot s = snapshot(param);
                    if (s == null) return;
                    if ("getLatitude".equals(name)) param.setResult(s.lat);
                    else if ("getLongitude".equals(name)) param.setResult(s.lng);
                    else if ("getAccuracy".equals(name)) param.setResult(s.accuracy);
                    else if ("getSpeed".equals(name)) param.setResult(s.speed);
                    else if ("getAltitude".equals(name)) param.setResult(0.0d);
                    else if ("getBearing".equals(name)) param.setResult(0.0f);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook Location." + name + " failed: " + t);
        }
    }

    // ---------- LocationManager：last 位置直返假 fix ----------

    private static void hookLocationManager() {
        try {
            XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            HookConfig.Snapshot s = snapshot(param);
                            if (s != null) param.setResult(buildFake((LocationManager) param.thisObject, s));
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook getLastKnownLocation failed: " + t);
        }
        try {
            // API 31+ LocationManager.getLastLocation()
            XposedBridge.hookAllMethods(LocationManager.class, "getLastLocation",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            HookConfig.Snapshot s = snapshot(param);
                            if (s != null) param.setResult(buildFake((LocationManager) param.thisObject, s));
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            // requestLocationUpdates 全重载：放行原调用（不断真实链路），getter 改写保证回调值也是假的；
            // 并立即补发一次假 fix，让暂停/刚注册的应用立刻拿到位置。
            XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            HookConfig.Snapshot s = snapshot(param);
                            if (s == null) return;
                            deliverFakeToListenerArgs(param, s);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook requestLocationUpdates failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(LocationManager.class, "requestSingleUpdate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            HookConfig.Snapshot s = snapshot(param);
                            if (s == null) return;
                            deliverFakeToListenerArgs(param, s);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    // ---------- mock 痕迹：Settings / AppOps ----------

    private static void hookMockTraces() {
        // Settings.Secure.getString(resolver, "mock_location"/"allow_mock_location") -> "0"
        // 部分 ROM/应用也会查 Settings.Global，顺手一起盖掉。
        XC_MethodHook hideMockSetting = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length == 2 && param.args[1] instanceof String) {
                    String key = (String) param.args[1];
                    if ("mock_location".equals(key) || "allow_mock_location".equals(key)
                            || "mock_location_app".equals(key)) {
                        param.setResult("0");
                    }
                }
            }
        };
        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure");
            XposedBridge.hookAllMethods(secure, "getString", hideMockSetting);
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook Settings.Secure failed: " + t);
        }
        try {
            Class<?> global = Class.forName("android.provider.Settings$Global");
            XposedBridge.hookAllMethods(global, "getString", hideMockSetting);
        } catch (Throwable ignored) {
        }
        // AppOpsManager.checkOp*/noteOp*(OP_MOCK_LOCATION) -> MODE_IGNORED
        try {
            Class<?> appOps = Class.forName("android.app.AppOpsManager");
            XC_MethodHook forceIgnore = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isMockOp(param.args)) param.setResult(MODE_IGNORED);
                }
            };
            for (String m : new String[]{"checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow", "checkOpNoThrowWithUid"}) {
                try {
                    XposedBridge.hookAllMethods(appOps, m, forceIgnore);
                } catch (Throwable ignored) {
                }
            }
            // OPSTR_MOCK_LOCATION 字符串重载
            try {
                XposedBridge.hookAllMethods(appOps, "checkOpNoThrow", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 1
                                && "android:mock_location".equals(param.args[0])) {
                            param.setResult(MODE_IGNORED);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook AppOpsManager failed: " + t);
        }
    }

    // ---------- 无线指纹：掏空 Wi-Fi / 基站，迫使回落 GPS ----------

    private static void hookRadioFingerprint() {
        // Wi-Fi 扫描掏空：防止 SDK 用真实 BSSID 反推真实城市。
        try {
            Class<?> wifi = Class.forName("android.net.wifi.WifiManager");
            XposedBridge.hookAllMethods(wifi, "getScanResults", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (active(param)) param.setResult(new java.util.ArrayList<>());
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook WifiManager failed: " + t);
        }
        // 基站掏空：getAllCellInfo -> 空列表；getCellLocation -> null。
        try {
            Class<?> tele = Class.forName("android.telephony.TelephonyManager");
            try {
                XposedBridge.hookAllMethods(tele, "getAllCellInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (active(param)) param.setResult(new java.util.ArrayList<>());
                    }
                });
            } catch (Throwable ignored) {
            }
            try {
                XposedBridge.hookAllMethods(tele, "getCellLocation", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (active(param)) param.setResult(null);
                    }
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] hook TelephonyManager failed: " + t);
        }
    }

    // ---------- GNSS / NMEA：压住真实星空，避免“星空与坐标对不上” ----------

    private static void hookGnssNmea() {
        try {
            // 不让目标应用注册真实 GNSS 回调（室内无星空是合理状态，比错位星空更可信）。
            XposedBridge.hookAllMethods(LocationManager.class, "registerGnssStatusCallback",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (active(param)) param.setResult(true);
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedBridge.hookAllMethods(LocationManager.class, "addNmeaListener",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (active(param)) param.setResult(true);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    // ---------- 内部工具 ----------

    private static boolean isMockOp(Object[] args) {
        if (args == null || args.length == 0) return false;
        return args[0] instanceof Integer && ((Integer) args[0]) == OP_MOCK_LOCATION;
    }

    private static boolean active(XC_MethodHook.MethodHookParam param) {
        return snapshot(param) != null;
    }

    private static HookConfig.Snapshot snapshot(XC_MethodHook.MethodHookParam param) {
        try {
            Object thiz = param.thisObject;
            ContentResolver resolver = null;
            if (thiz != null) {
                try {
                    Object ctx = XposedHelpers.callMethod(thiz, "getContext");
                    if (ctx instanceof android.content.Context) {
                        resolver = ((android.content.Context) ctx).getContentResolver();
                    }
                } catch (Throwable ignored) {
                }
            }
            if (resolver == null) {
                android.app.Application app = currentApp();
                if (app != null) resolver = app.getContentResolver();
            }
            if (resolver == null) return null;
            HookConfig.Snapshot s = HookConfig.get(resolver);
            // 未启用或过期（>10min 未推送）则放行真实定位，避免残留盖住正常使用。
            if (s == null || !s.isFresh()) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static android.app.Application currentApp() {
        try {
            return (android.app.Application) Class
                    .forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Location buildFake(LocationManager manager, HookConfig.Snapshot s) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(s.lat);
        loc.setLongitude(s.lng);
        loc.setAccuracy(s.accuracy);
        loc.setAltitude(0.0d);
        loc.setSpeed(s.speed);
        loc.setBearing(0.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        try {
            loc.setMock(false);
        } catch (Throwable ignored) {
        }
        scrubFields(loc);
        Bundle extras = loc.getExtras();
        if (extras != null) {
            extras.remove("mockLocation");
            extras.remove("isMock");
        }
        return loc;
    }

    private static void scrubFields(Object location) {
        try {
            XposedHelpers.setBooleanField(location, "mMock", false);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false);
        } catch (Throwable ignored) {
        }
    }

    /** 找到参数里的 LocationListener / LocationCallback，立即补发一次假 fix。 */
    private static void deliverFakeToListenerArgs(XC_MethodHook.MethodHookParam param,
                                                  HookConfig.Snapshot s) {
        try {
            if (param.args == null) return;
            Location fake = buildFake((LocationManager) param.thisObject, s);
            for (Object arg : param.args) {
                if (arg == null) continue;
                String name = arg.getClass().getName();
                try {
                    if (arg instanceof android.location.LocationListener) {
                        ((android.location.LocationListener) arg).onLocationChanged(fake);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                // LocationCallback（GMS / fused 链路）：onLocationResult(LocationResult)
                if (name.endsWith("LocationCallback")) {
                    try {
                        Class<?> resultClz = Class.forName("com.google.android.gms.location.LocationResult");
                        java.lang.reflect.Method create = resultClz.getMethod("create", java.util.List.class);
                        Object result = create.invoke(null, java.util.Collections.singletonList(fake));
                        arg.getClass().getMethod("onLocationResult", resultClz).invoke(arg, result);
                        return;
                    } catch (Throwable ignored) {
                    }
                    try {
                        Class<?> resultClz = Class.forName("android.location.LocationResult");
                        java.lang.reflect.Method wrap = null;
                        for (java.lang.reflect.Method m : resultClz.getMethods()) {
                            if (m.getName().equals("wrap")
                                    && m.getParameterTypes().length == 1
                                    && m.getParameterTypes()[0] == java.util.List.class) {
                                wrap = m;
                                break;
                            }
                        }
                        if (wrap != null) {
                            Object result = wrap.invoke(null,
                                    java.util.Collections.singletonList(fake));
                            for (java.lang.reflect.Method m : arg.getClass().getMethods()) {
                                if (m.getName().equals("onLocationResult")
                                        && m.getParameterTypes().length == 1) {
                                    m.invoke(arg, result);
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
