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

## 构建

在 PowerShell 中运行：

```powershell
.\build.ps1
```

脚本会在工作区 `.toolchain` 中准备 .NET 8 SDK、Gradle 8.9、Android SDK 35 和 platform-tools，然后生成完整免安装发布包到 `publish\win-x64`。构建 JDK 固定使用 `C:\envs\Java\jdk-17`；如本机路径不同，请修改 `build.ps1` 和 `tools\bootstrap-toolchain.ps1` 中的 `$Jdk`。

## 实现说明

- 桌面端：.NET 8 WPF，仅支持 Windows。
- 手机端：原生 Android Java，最低支持 Android 6.0（API 23），并以 Android 15（API 35）SDK 构建。
- 通信：桌面端通过序列号锁定单台 ADB 设备，使用显式广播更新 GPS/Network 测试提供程序。
- 安全：ADB 参数使用 `ProcessStartInfo.ArgumentList` 传递，不经 shell 拼接。

## 免责声明

本项目仅供开发与测试用途。请勿用于欺骗运动数据、平台风控或任何违法违规用途；使用者应自行承担设备、账号与数据风险。
