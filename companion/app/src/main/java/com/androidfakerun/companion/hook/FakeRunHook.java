package com.androidfakerun.companion.hook;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口（assets/xposed_init 指向本类）。
 *
 * <p>作用域建议（LSPosed 管理器里勾选）：
 * <ol>
 *   <li>必须：需要伪装的目标应用（微信/钉钉/跑步 App 等）；</li>
 *   <li>可选：{@code android}（系统框架）——仅 GMS fused 代理链路异常时才开，
 *       配套 Hook 模式默认不依赖它；</li>
 *   <li>本模块自身包名会被跳过，避免自己 Hook 自己。</li>
 * </ol>
 */
public final class FakeRunHook implements de.robv.android.xposed.IXposedHookLoadPackage {
    private static final String SELF = "com.androidfakerun.companion";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null || pkg.equals(SELF)) return;
        try {
            if ("android".equals(pkg)) {
                // system_server：只做消毒不清坐标，失败也不影响（见 SystemServerHooks 注释）。
                SystemServerHooks.init(lpparam);
            } else {
                AppLocationHooks.init(lpparam);
            }
        } catch (Throwable t) {
            XposedBridge.log("[FakeRun] init failed for " + pkg + ": " + t);
        }
    }
}
