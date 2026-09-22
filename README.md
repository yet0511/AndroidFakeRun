# Android Fake Run

Windows 上控制安卓实体手机（包括红米/小米）的虚拟跑步工具。它保留原 `iOS Fake Run` 的路线输入、BD-09 → WGS-84 坐标转换、速度、循环次数、暂停、恢复、停止、进度和重置定位功能，并把 iOS 设备通信替换成 ADB + 安卓配套 APK。

## 直接下载

不需要编译，前往 [GitHub Releases](https://github.com/yet0511/AndroidFakeRun/releases/latest) 下载 `AndroidFakeRun-win-x64.zip`，完整解压后运行 `AndroidFakeRun.exe`。不要只下载单个 EXE，程序还需要压缩包内的 `platform-tools` 和 `companion` 目录。

## 使用方法

1. 在安卓手机的“设置 → 关于手机”中连续点击系统版本，开启开发者选项。
2. 在开发者选项中开启 **USB 调试**。红米/小米建议同时开启 **USB 调试（安全设置）**。
3. 用可传输数据的 USB 线连接电脑，在手机弹窗中允许这台电脑进行 USB 调试。
4. 运行 `AndroidFakeRun.exe`，点击“连接”。程序会自动安装并打开手机端 `Android Fake Run` 配套组件。
5. 若手机系统未自动允许，请进入开发者选项 → **选择模拟位置信息应用** → 选择 `Android Fake Run`。
6. 从原路径拾取页面复制路线 JSON，粘贴到左侧；设置循环次数与跑步速度后开始。
7. 结束后点击“重置定位”，让手机恢复使用真实 GPS。

MIUI/HyperOS 如果连接不稳定，还应允许 USB 安装、关闭针对该配套应用的省电限制，并保持手机解锁完成首次授权。

## 定位模式

- **经典模式（免 Root）**：桌面经 ADB 广播调用配套端的 `addTestProvider`，需在开发者选项中选择
  `Android Fake Run` 为模拟位置应用。缺点是 `Location.isFromMockProvider()` 为 `true`，
  会被运动/风控类 SDK 识别。
- **防检测注入模式（需 Root + LSPosed，推荐）**：在电脑端勾选“防检测注入模式”，配套端收到
  `SET_LOCATION` 后只把坐标写入 Hook 共享区（`FakeRunProvider`），全程不调用
  `addTestProvider`，系统里不存在 mock provider；LSPosed 模块在目标应用进程内把
  `Location` getter 改写为预设坐标，并强制 `isFromMockProvider()/isMock()=false`、
  剥掉 extras mock 残留、拦截 `Settings.Secure`/`AppOpsManager` 的 mock 查询，
  同时掏空 Wi-Fi 扫描与基站定位、压住 NMEA/GNSS 真实星空，迫使 SDK 回落到 GPS 主通道。

### 防检测模式操作要点

1. 设备已 Root（Magisk/KernelSU/APatch），安装 LSPosed 框架（Zygisk 版）。
2. 安装本项目配套 APK（桌面点“连接”会自动安装，APK 内已含 LSPosed 模块）。
3. 在 LSPosed 管理器中启用 `Android Fake Run`，作用域**只勾选需要伪装的目标应用**
   （默认不需要勾“系统框架”；仅 GMS fused 代理链路异常时才加勾 `android` 并重启）。
4. 强行停止目标应用（或重启手机）使 Hook 生效，在桌面勾选“防检测注入模式”后开始跑步。
5. 结束后点“重置定位”，Hook 共享坐标会被清空并恢复真实定位（10 分钟无推送会自动过期）。

已知限制（后续迭代）：Wi-Fi BSSID/基站“环境克隆”（按目标坐标注入虚假热点与小区）
与高德/腾讯系地图 SDK 专属字段伪装尚未接入，当前策略是掏空无线指纹；
Root/Xposed 自身的存在性检测（如 Shamiko 白名单）需用户自行配合隐藏。

## 构建

在 PowerShell 中运行：

```powershell
.\build.ps1
```

脚本会在工作区 `.toolchain` 中准备 .NET 8 SDK、Gradle 8.9、Android SDK 35 和 platform-tools，然后生成完整免安装发布包到 `publish\win-x64`。构建 JDK 固定使用 `C:\envs\Java\jdk-17`；如本机路径不同，请修改 `build.ps1` 和 `tools\bootstrap-toolchain.ps1` 中的 `$Jdk`。

## 实现说明

- 桌面端：.NET 8 WPF，仅支持 Windows。
- 手机端：原生 Android Java，最低支持 Android 6.0（API 23），并以 Android 15（API 35）SDK 构建。
- 通信：桌面端通过序列号锁定单台 ADB 设备，经典模式使用显式广播更新 GPS/Network 测试提供程序，
  防检测模式使用显式广播（ENABLE_HOOK/SET_LOCATION/RESET）更新 Hook 共享坐标。
- 安全：ADB 参数使用 `ProcessStartInfo.ArgumentList` 传递，不经 shell 拼接。

## 免责声明

本项目仅供开发与测试用途。请勿用于欺骗运动数据、平台风控或任何违法违规用途；使用者应自行承担设备、账号与数据风险。
