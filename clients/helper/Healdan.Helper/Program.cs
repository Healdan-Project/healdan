// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Avalonia;
using Avalonia.Controls;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Platform;

namespace Healdan.Helper;

/// <summary>
/// Starts the helper: one per user, in the menu bar, and in the Dock only while one of its windows is open (spec §5.1,
/// #143). A second start hands the running helper any link it was started for, and quits.
/// </summary>
internal static class Program
{
    /// <summary>The helper's folders; under <c>HEALDAN_HELPER_DEV_ROOT</c> in a Debug build when it's set.</summary>
    public static HelperPaths Paths { get; private set; } = null!;

    public static HelperLog Log { get; private set; } = null!;

    private static SingleInstance? _instance;

    [STAThread]
    public static int Main(string[] args)
    {
        Paths = HelperPaths.For(DevRoot());
        Log = new HelperLog(Paths.Log);
        // Held for the life of the process (a static field, so the GC can't release it). A helper that holds none, one from
        // before the lock or one that couldn't open its file, counts too when its channel answers, on both systems.
        _instance = SingleInstance.TryAcquire(Paths, Log);
        if (_instance is null || AnotherHelperAnswers(Paths))
        {
            // Two helpers would both send the same copies' saves. macOS starts a second copy of the app for a link
            // when it hands links to another copy than the one running, and sends the link as an Apple event once
            // the app runs, so this one starts just far enough to take it.
            Log.Info("Another Healdan Helper is running for this user, so this one hands it any link it was started for, and quits");
            if (OperatingSystem.IsLinux())
                return HandOverLinks(args);
            if (!OperatingSystem.IsMacOS())
                return 0;
            WaitForDisplay();
            return Configure<HandOverApp>().StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
        }
        WaitForDisplay();
        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args, ShutdownMode.OnExplicitShutdown);
    }

    /// <summary>
    /// Linux starts a new process for each click of a link, with the link as its argument, so this hands each one to the
    /// running helper and quits, with no window. The helper may still be signing in, so it waits up to 30 s for it.
    /// </summary>
    private static int HandOverLinks(string[] args)
    {
        var handOver = new LinkHandOver(Paths, Log);
        var links = 0;
        foreach (var arg in args)
        {
            if (!HealdanLink.HasScheme(arg))
                continue;
            links++;
            // The wait gave up: no channel (signed out, or Sign In dismissed or still up) or the helper refused it. A
            // healdan: link that isn't an open link isn't handed over (Send logs it), and the user is told it was ignored.
            if (!handOver.Send(arg, TimeSpan.FromSeconds(30)))
                NotifySend.TryPost("Healdan Helper", LinkHandOver.NotTakenNotice(arg) ?? LinkHandler.IgnoredNotice);
        }
        // Started from the app grid while the helper runs: say so, as nothing else shows.
        if (links == 0)
            NotifySend.TryPost("Healdan Helper", LinkHandOver.AlreadyRunningNotice);
        return 0;
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
            .UseSystemFontShaper()
            // Out of the Dock until a window opens (MacPlatform.ShowInDock); then the app menu is App's own (AppMenu).
            .With(new MacOSPlatformOptions { ShowInDock = false, DisableDefaultApplicationMenuItems = true })
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

    /// <summary>
    /// A helper whose channel answers its ping, and whose process is alive (on Linux, the user's own), is running; a
    /// <c>channel.json</c> left by one that stopped isn't, whatever now answers on its port.
    /// </summary>
    private static bool AnotherHelperAnswers(HelperPaths paths)
    {
        var info = ChannelInfo.Read(paths);
        if (info is null || info.Pid == Environment.ProcessId || !ChannelInfo.IsAlive(info.Pid))
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
