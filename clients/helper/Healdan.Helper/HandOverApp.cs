using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Threading;
using Healdan.Helper.Core.Links;

namespace Healdan.Helper;

/// <summary>
/// The whole of a second helper on macOS, started only to take the link it was started for: macOS sends that as an
/// Apple event once the app runs, not in its arguments. Each link goes to the running helper (<see cref="LinkHandOver"/>),
/// and it quits a second after the last, or after <see cref="Wait"/> when none comes, as when it's started by hand. It has
/// no window, menu-bar icon, sign-in or channel.
/// </summary>
internal sealed class HandOverApp : Application
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan AfterLink = TimeSpan.FromSeconds(1);

    private int _links;
    private int _handing;

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            var handOver = new LinkHandOver(Program.Paths, Program.Log);
            var quit = new DispatcherTimer(Wait, DispatcherPriority.Background, (_, _) =>
            {
                if (Volatile.Read(ref _handing) > 0)
                    return; // the next tick looks again
                if (_links == 0)
                    Program.Log.Info("No link came to hand over");
                desktop.Shutdown();
            });

            // Subscribed before the run loop starts, so the link that started this one isn't missed.
            if (this.TryGetFeature<IActivatableLifetime>() is { } activatable)
                activatable.Activated += (_, e) =>
                {
                    if (e is not ProtocolActivatedEventArgs { Uri: { } uri })
                        return;
                    _links++;
                    Interlocked.Increment(ref _handing);
                    quit.Interval = AfterLink;
                    _ = Task.Run(() =>
                    {
                        try
                        {
                            handOver.Send(uri.OriginalString);
                        }
                        finally
                        {
                            Interlocked.Decrement(ref _handing);
                        }
                    });
                };
            quit.Start();
        }
        base.OnFrameworkInitializationCompleted();
    }
}
