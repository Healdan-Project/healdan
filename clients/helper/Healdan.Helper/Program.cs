using Avalonia;
using Avalonia.Controls;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Platform;

namespace Healdan.Helper;

/// <summary>
/// Starts the helper: one per user, in the menu bar and out of the Dock (spec §5.1). A second start hands the running
/// helper any link it was started for, and quits.
/// </summary>
internal static class Program
{
    /// <summary>The helper's folders; under <c>HEALDAN_HELPER_DEV_ROOT</c> in a Debug build when it's set.</summary>
    public static HelperPaths Paths { get; private set; } = null!;

    public static HelperLog Log { get; private set; } = null!;

    [STAThread]
    public static int Main(string[] args)
    {
        Paths = HelperPaths.For(DevRoot());
        Log = new HelperLog(Paths.Log);
        if (AnotherHelperAnswers(Paths))
        {
            // Two helpers would both send the same copies' saves. macOS starts a second copy of the app for a link
            // when it hands links to another copy than the one running, and sends the link as an Apple event once
            // the app runs, so this one starts just far enough to take it.
            Log.Info("Another Healdan Helper is running for this user, so this one hands it any link it was started for, and quits");
            if (!OperatingSystem.IsMacOS())
                return 0;
            WaitForDisplay();
            return Configure<HandOverApp>().StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
        }
        WaitForDisplay();
        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
    }

    /// <summary>
    /// Avalonia can't start while no display is on (Task 16: CVReturn -6661), as when the helper is started while the
    /// display sleeps, so the start waits for one instead of crashing.
    /// </summary>
    private static void WaitForDisplay()
    {
        if (MacPlatform.HasActiveDisplay())
            return;
        Log.Info("No display is on, so the start waits for one");
        while (!MacPlatform.HasActiveDisplay())
            Thread.Sleep(TimeSpan.FromSeconds(2));
        Log.Info("A display is on, so the start goes on");
    }

    public static AppBuilder BuildAvaloniaApp() => Configure<App>();

    private static AppBuilder Configure<TApp>() where TApp : Application, new() =>
        AppBuilder.Configure<TApp>()
            .UsePlatformDetect()
            .With(new MacOSPlatformOptions { ShowInDock = false })
            .LogToTrace();

    /// <summary>A Debug build run by hand can keep its files apart from the real helper's.</summary>
    private static string? DevRoot()
    {
#if DEBUG
        return Environment.GetEnvironmentVariable("HEALDAN_HELPER_DEV_ROOT") is { Length: > 0 } root ? root : null;
#else
        return null;
#endif
    }

    /// <summary>A helper whose channel answers its ping is running; a <c>channel.json</c> left by one that stopped isn't.</summary>
    private static bool AnotherHelperAnswers(HelperPaths paths)
    {
        var info = ChannelInfo.Read(paths);
        if (info is null || info.Pid == Environment.ProcessId)
            return false;
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
            using var answer = http.Send(new HttpRequestMessage(HttpMethod.Get, $"http://127.0.0.1:{info.Port}/v1/ping"));
            return answer.IsSuccessStatusCode;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            return false;
        }
    }
}
