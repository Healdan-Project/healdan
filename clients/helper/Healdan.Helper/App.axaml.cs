using System.Reflection;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Platform;
using Healdan.Helper.Tray;
using Healdan.Helper.Windows;

namespace Healdan.Helper;

/// <summary>
/// Puts the helper together: the session (sign-in, copies, channel and links, in that order), the menu-bar icon,
/// notifications and windows. Links arrive as Apple events (<see cref="ProtocolActivatedEventArgs"/>), started or not.
/// </summary>
public partial class App : Application
{
    private readonly HelperLog _log = Program.Log;
    private readonly List<PosixSignalRegistration> _signals = [];
    private HelperSession? _session;
    private TrayMenu? _tray;
    private bool _stopped;

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;
            try
            {
                Compose(desktop);
            }
            catch (Exception e)
            {
                _log.Error("Healdan Helper couldn't start", e);
                desktop.Shutdown(1);
            }
        }
        base.OnFrameworkInitializationCompleted();
    }

    private void Compose(IClassicDesktopStyleApplicationLifetime desktop)
    {
        var version = Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        _log.Info($"Healdan Helper {version} starting (pid {Environment.ProcessId}, {RuntimeInformation.OSDescription})");

        var paths = Program.Paths;
        var notifier = new Notifier(_log);
        var windows = new AppWindows(notifier, _log, paths);
        var device = DeviceIdentity.Load(paths, log: _log);
        var session = new HelperSession(paths, Keychain(), device, new LibreOfficeApp(), windows, notifier, _log, otherFiles: new DefaultApp());
        _session = session;
        windows.Session = session;

        // Subscribed before the run loop starts, so the link that started the helper isn't missed; the session holds
        // it until it has started.
        if (this.TryGetFeature<IActivatableLifetime>() is { } activatable)
            activatable.Activated += OnActivated;

        _tray = new TrayMenu(this, session, notifier, windows, () => desktop.Shutdown());
        desktop.Exit += (_, _) => Stop();
        foreach (var signal in new[] { PosixSignal.SIGTERM, PosixSignal.SIGINT })
            _signals.Add(PosixSignalRegistration.Create(signal, context =>
            {
                context.Cancel = true; // quit as the menu's Quit does, so channel.json goes
                Dispatcher.UIThread.Post(() => desktop.Shutdown());
            }));

        notifier.Start();
        _ = Task.Run(async () =>
        {
            try
            {
                await session.StartAsync();
            }
            catch (Exception e)
            {
                _log.Error("Starting failed", e);
                notifier.Notify("Healdan Helper", "Healdan Helper couldn't start: " + e.Message);
            }
        });
    }

    private void OnActivated(object? sender, ActivatedEventArgs e)
    {
        if (e is not ProtocolActivatedEventArgs { Uri: { } uri } || _session is not { } session)
            return;
        var link = session.Links.Handle(uri.OriginalString);
        link.ContinueWith(t => _log.Error("A link failed", t.Exception), TaskContinuationOptions.OnlyOnFaulted);
    }

    /// <summary>The system's keychain; a Debug build run by hand can take a token from <c>HEALDAN_HELPER_DEV_TOKEN</c> instead.</summary>
    private IKeychain Keychain()
    {
#if DEBUG
        if (Environment.GetEnvironmentVariable("HEALDAN_HELPER_DEV_TOKEN") is { Length: > 0 } token)
        {
            _log.Warn("Debug build: the sign-in comes from HEALDAN_HELPER_DEV_TOKEN, not the keychain");
            return new DevKeychain(token);
        }
#endif
        return OperatingSystem.IsMacOS() ? new MacKeychain() : new SecretToolKeychain();
    }

    private void Stop()
    {
        if (_stopped)
            return;
        _stopped = true;
        _session?.Dispose();
        _tray?.Dispose();
        foreach (var signal in _signals)
            signal.Dispose();
        _log.Info("Stopped");
    }
}
