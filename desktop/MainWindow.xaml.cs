using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Windows;
using AndroidFakeRun.Android;
using AndroidFakeRun.Core;

namespace AndroidFakeRun;

public partial class MainWindow
{
    private readonly AdbClient _adb;
    private readonly string _routeFile;
    private AndroidDevice? _device;
    private CancellationTokenSource? _runCts;
    private readonly ManualResetEventSlim _pauseGate = new(true);

    public MainWindow()
    {
        InitializeComponent();
        _routeFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AndroidFakeRun", "route.save");
        try { _adb = new AdbClient(); }
        catch (Exception exception)
        {
            _adb = null!;
            Loaded += (_, _) => ShowError(exception.Message);
        }
        try { if (File.Exists(_routeFile)) RouteTextBox.Text = File.ReadAllText(_routeFile); } catch { }
    }

    private async void Connect(object sender, RoutedEventArgs e)
    {
        if (_adb is null) { ShowError("ADB 不可用，请使用完整发布包。"); return; }
        SetBusy(true, "正在连接安卓设备并准备配套端...");
        try
        {
            _device = await _adb.ConnectAsync(CancellationToken.None);
            await _adb.PrepareCompanionAsync(_device.Serial, CancellationToken.None);
            DeviceDetails.Text = $"已连接：{_device.Model}\nAndroid {_device.AndroidVersion}（API {_device.ApiLevel}）\n序列号：{_device.Serial}";
            StatusText.Text = $"已连接 {_device.Model}";
            MessageBox.Show(this, "安卓设备连接成功。\n\n如果开始跑步时提示没有模拟位置权限，请在手机开发者选项中将 Android Fake Run 选为模拟位置信息应用。", "连接成功");
        }
        catch (Exception exception) { _device = null; ShowError(exception.Message); StatusText.Text = "连接失败"; }
        finally { SetBusy(false); }
    }

    private void Disconnect(object sender, RoutedEventArgs e)
    {
        StopInternal();
        _device = null;
        DeviceDetails.Text = "设备已断开。重新连接前请保持 USB 调试开启。";
        StatusText.Text = "等待连接安卓设备";
    }

    private async void ResetLocation(object sender, RoutedEventArgs e)
    {
        if (!EnsureConnected()) return;
        try
        {
            await _adb.ResetLocationAsync(_device!.Serial, CancellationToken.None);
            StatusText.Text = "已恢复手机正常定位";
            MessageBox.Show(this, "已重置模拟定位。", "完成");
        }
        catch (Exception exception) { ShowError(exception.Message); }
    }

    private async void StartRun(object sender, RoutedEventArgs e)
    {
        if (!EnsureConnected()) return;
        try
        {
            if (!double.TryParse(SpeedTextBox.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var speed) || speed is < 0.1 or > 10)
                throw new FormatException("跑步速度必须为 0.1 到 10 m/s。");
            if (!int.TryParse(RunTimesTextBox.Text, out var times) || times < 1)
                throw new FormatException("循环次数必须是大于等于 1 的整数。");

            var rawPoints = RouteParser.Parse(RouteTextBox.Text);
            Directory.CreateDirectory(Path.GetDirectoryName(_routeFile)!);
            File.WriteAllText(_routeFile, RouteTextBox.Text);
            var fixedPoints = rawPoints.Select(CoordinateUtils.Bd09ToWgs84).ToList();
            if (times > 1 && fixedPoints[^1] != fixedPoints[0]) fixedPoints.Add(fixedPoints[0]);
            var route = CoordinateUtils.InterpolateRoute(fixedPoints, speed);

            _runCts = new CancellationTokenSource();
            _pauseGate.Set();
            SetRunUi(true);
            StatusText.Text = "正在跑步中...";
            RunProgress.Value = 0;
            await RunRouteAsync(route, times, speed, _runCts.Token);
            if (!_runCts.IsCancellationRequested) StatusText.Text = "跑步完成";
        }
        catch (OperationCanceledException) { StatusText.Text = "跑步已停止"; }
        catch (Exception exception) { ShowError(exception.Message); StatusText.Text = "跑步失败"; }
        finally { SetRunUi(false); _runCts?.Dispose(); _runCts = null; }
    }

    private async Task RunRouteAsync(IReadOnlyList<GeoPoint> route, int times, double speed, CancellationToken token)
    {
        var total = route.Count * times;
        var completed = 0;
        for (var round = 0; round < times; round++)
        {
            foreach (var point in route)
            {
                await Task.Run(() => _pauseGate.Wait(token), token);
                await _adb.SetLocationAsync(_device!.Serial, point.Latitude, point.Longitude, speed, token);
                completed++;
                RunProgress.Value = completed * 100.0 / total;
                await Task.Delay(TimeSpan.FromSeconds(1), token);
            }
        }
    }

    private void PauseRun(object sender, RoutedEventArgs e)
    {
        _pauseGate.Reset();
        PauseButton.Visibility = Visibility.Collapsed;
        ResumeButton.Visibility = Visibility.Visible;
        StatusText.Text = "跑步已暂停";
    }

    private void ResumeRun(object sender, RoutedEventArgs e)
    {
        _pauseGate.Set();
        ResumeButton.Visibility = Visibility.Collapsed;
        PauseButton.Visibility = Visibility.Visible;
        StatusText.Text = "正在跑步中...";
    }

    private void StopRun(object sender, RoutedEventArgs e) => StopInternal();
    private void StopInternal() { _pauseGate.Set(); _runCts?.Cancel(); }

    private void Quit(object sender, RoutedEventArgs e) => Close();
    private void WindowClosing(object? sender, CancelEventArgs e) => StopInternal();

    private void ShowAndroidHelp(object sender, RoutedEventArgs e) => MessageBox.Show(this,
        "1. 手机进入“设置 → 关于手机”，连续点击系统版本以开启开发者选项。\n" +
        "2. 开启 USB 调试；红米/小米建议同时开启 USB 调试（安全设置）。\n" +
        "3. 用数据线连接电脑，并在手机上允许 USB 调试。\n" +
        "4. 点击本程序的“连接”，配套 APK 会自动安装。\n" +
        "5. 若系统要求，请在“选择模拟位置信息应用”中选择 Android Fake Run。",
        "安卓连接说明");

    private void ShowAbout(object sender, RoutedEventArgs e) => MessageBox.Show(this,
        "Android Fake Run\nWindows 控制端 + Android 配套端\n\n基于原 iOS Fake Run 的路线、坐标转换与跑步逻辑改造。\n请勿将本工具用于任何非法用途。", "关于");

    private bool EnsureConnected()
    {
        if (_device is not null) return true;
        ShowError("请先连接安卓设备。");
        return false;
    }

    private void SetBusy(bool busy, string? message = null)
    {
        ConnectButton.IsEnabled = !busy;
        DisconnectButton.IsEnabled = !busy;
        ResetButton.IsEnabled = !busy;
        if (message is not null) StatusText.Text = message;
    }

    private void SetRunUi(bool running)
    {
        StartButton.Visibility = running ? Visibility.Collapsed : Visibility.Visible;
        RunningButtons.Visibility = running ? Visibility.Visible : Visibility.Collapsed;
        PauseButton.Visibility = Visibility.Visible;
        ResumeButton.Visibility = Visibility.Collapsed;
        RunTimesTextBox.IsEnabled = !running;
        SpeedTextBox.IsEnabled = !running;
        ConnectButton.IsEnabled = !running;
    }

    private void ShowError(string message) => MessageBox.Show(this, message, "Android Fake Run", MessageBoxButton.OK, MessageBoxImage.Warning);
}
