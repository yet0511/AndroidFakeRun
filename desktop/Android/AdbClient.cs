using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;

namespace AndroidFakeRun.Android;

internal sealed class AdbClient
{
    public const string PackageName = "com.androidfakerun.companion";
    private readonly string _adbPath;

    public AdbClient() => _adbPath = FindAdb();

    public async Task<AndroidDevice> ConnectAsync(CancellationToken token)
    {
        await RunAsync(["start-server"], token);
        var devices = ParseDevices((await RunAsync(["devices", "-l"], token)).Output);
        var unauthorized = devices.FirstOrDefault(d => d.State == "unauthorized");
        if (unauthorized is not null)
            throw new InvalidOperationException("手机尚未授权 USB 调试，请解锁手机并在弹窗中选择“允许”。");
        var online = devices.Where(d => d.State == "device").ToList();
        if (online.Count == 0) throw new InvalidOperationException("未发现安卓设备。请检查数据线、USB 调试和手机驱动。 ");
        if (online.Count > 1) throw new InvalidOperationException("检测到多个安卓设备，请只保留一台后重试。");

        var serial = online[0].Serial;
        var model = await ShellAsync(serial, ["getprop", "ro.product.model"], token);
        var android = await ShellAsync(serial, ["getprop", "ro.build.version.release"], token);
        var sdk = await ShellAsync(serial, ["getprop", "ro.build.version.sdk"], token);
        return new AndroidDevice(serial, model.Trim(), android.Trim(), sdk.Trim());
    }

    public async Task PrepareCompanionAsync(string serial, CancellationToken token)
    {
        var package = await RunForDeviceAsync(serial, ["shell", "pm", "path", PackageName], token, false);
        if (package.ExitCode != 0 || !package.Output.Contains("package:", StringComparison.OrdinalIgnoreCase))
        {
            var apk = FindCompanionApk();
            var install = await RunAsync(["-s", serial, "install", "-r", apk], token, TimeSpan.FromMinutes(2));
            if (install.ExitCode != 0 || !install.Output.Contains("Success", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("配套 APK 安装失败：" + install.Output.Trim());
        }

        await RunForDeviceAsync(serial, ["shell", "appops", "set", PackageName, "android:mock_location", "allow"], token, false);
        await RunForDeviceAsync(serial, ["shell", "am", "start", "-n", PackageName + "/.MainActivity"], token, false);
        var ping = await BroadcastAsync(serial, "PING", [], token);
        if (!ping.Contains("result=-1", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("手机配套端未响应。请打开手机上的 Android Fake Run 后重试。");
    }

    public async Task SetLocationAsync(string serial, double latitude, double longitude, double speed, CancellationToken token)
    {
        var args = new[]
        {
            "--es", "latitude", latitude.ToString("R", CultureInfo.InvariantCulture),
            "--es", "longitude", longitude.ToString("R", CultureInfo.InvariantCulture),
            "--es", "speed", speed.ToString("R", CultureInfo.InvariantCulture)
        };
        var output = await BroadcastAsync(serial, "SET_LOCATION", args, token);
        if (!output.Contains("result=-1", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(ParseBroadcastError(output));
    }

    public async Task ResetLocationAsync(string serial, CancellationToken token)
    {
        var output = await BroadcastAsync(serial, "RESET", [], token);
        if (!output.Contains("result=-1", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(ParseBroadcastError(output));
    }

    private async Task<string> BroadcastAsync(string serial, string action, IEnumerable<string> extras, CancellationToken token)
    {
        var args = new List<string> { "shell", "am", "broadcast", "-n", PackageName + "/.MockLocationReceiver", "-a", PackageName + "." + action };
        args.AddRange(extras);
        return (await RunForDeviceAsync(serial, args, token, false)).Output;
    }

    private async Task<string> ShellAsync(string serial, IEnumerable<string> args, CancellationToken token)
    {
        var all = new List<string> { "shell" };
        all.AddRange(args);
        return (await RunForDeviceAsync(serial, all, token)).Output;
    }

    private Task<AdbResult> RunForDeviceAsync(string serial, IEnumerable<string> args, CancellationToken token, bool throwOnError = true)
    {
        var all = new List<string> { "-s", serial };
        all.AddRange(args);
        return RunAsync(all, token, null, throwOnError);
    }

    private async Task<AdbResult> RunAsync(IEnumerable<string> arguments, CancellationToken token,
        TimeSpan? timeout = null, bool throwOnError = true)
    {
        using var process = new Process();
        process.StartInfo = new ProcessStartInfo(_adbPath)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        foreach (var argument in arguments) process.StartInfo.ArgumentList.Add(argument);
        process.Start();
        var outputTask = process.StandardOutput.ReadToEndAsync(token);
        var errorTask = process.StandardError.ReadToEndAsync(token);
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeoutCts.CancelAfter(timeout ?? TimeSpan.FromSeconds(20));
        try { await process.WaitForExitAsync(timeoutCts.Token); }
        catch (OperationCanceledException) when (!token.IsCancellationRequested)
        {
            try { process.Kill(true); } catch { }
            throw new TimeoutException("ADB 操作超时，请检查手机连接和授权弹窗。");
        }
        var output = (await outputTask) + (await errorTask);
        var result = new AdbResult(process.ExitCode, output);
        if (throwOnError && result.ExitCode != 0) throw new InvalidOperationException("ADB 执行失败：" + output.Trim());
        return result;
    }

    private static List<AndroidDeviceState> ParseDevices(string output) => output.Split('\n')
        .Select(line => line.Trim()).Where(line => line.Length > 0 && !line.StartsWith("List of"))
        .Select(line => line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries))
        .Where(parts => parts.Length >= 2)
        .Select(parts => new AndroidDeviceState(parts[0], parts[1])).ToList();

    private static string FindAdb()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "platform-tools", "adb.exe"),
            Path.Combine(AppContext.BaseDirectory, "adb.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe")
        };
        var found = candidates.FirstOrDefault(File.Exists);
        if (found is not null) return found;
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        found = path.Split(Path.PathSeparator).Select(p => Path.Combine(p.Trim(), "adb.exe")).FirstOrDefault(File.Exists);
        return found ?? throw new FileNotFoundException("找不到 adb.exe。请使用完整发布包，或安装 Android platform-tools 并加入 PATH。");
    }

    private static string FindCompanionApk()
    {
        var candidates = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "companion", "AndroidFakeRun.Companion.apk"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "companion", "app", "build", "outputs", "apk", "release", "app-release.apk"))
        };
        return candidates.FirstOrDefault(File.Exists) ?? throw new FileNotFoundException("发布包中缺少 AndroidFakeRun.Companion.apk。");
    }

    private static string ParseBroadcastError(string output)
    {
        var marker = "data=\"";
        var start = output.IndexOf(marker, StringComparison.OrdinalIgnoreCase);
        if (start >= 0)
        {
            start += marker.Length;
            var end = output.IndexOf('"', start);
            if (end > start) return output[start..end];
        }
        return "设置模拟定位失败。请在开发者选项中选择 Android Fake Run 为模拟位置信息应用。";
    }

    private sealed record AndroidDeviceState(string Serial, string State);
    private sealed record AdbResult(int ExitCode, string Output);
}

internal sealed record AndroidDevice(string Serial, string Model, string AndroidVersion, string ApiLevel);
