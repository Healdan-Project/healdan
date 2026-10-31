using System.Reflection;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Controls.Primitives;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Threading;
using Healdan.Helper.Core;
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
    private readonly CancellationTokenSource _stopping = new();
    private HelperSession? _session;
    private TrayMenu? _tray;
    private bool _stopped;
    private int _afterStart;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
        // San Francisco on macOS (polish spec §4.2): Skia's default there is Helvetica. Every top level, so pop-ups and
        // tooltips too; elsewhere the desktop's own font. SystemFont's shaper lays it out as Skia draws it.
        if (OperatingSystem.IsMacOS())
        {
            var font = new Style(x => x.Is<TopLevel>());
            font.Setters.Add(new Setter(TemplatedControl.FontFamilyProperty, new FontFamily(SystemFont.Family)));
            Styles.Add(font);
        }
    }

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
        var session = new HelperSession(paths, Keychain(paths, device), device, OfficeAppSet.Create(paths), windows, notifier, _log);
        _session = session;
        windows.Session = session;
        var extension = new ExtensionInstaller(_log, notifier, paths, version ?? "0.0.0", () => windows.Waiting);
        windows.Extension = extension.Check;
        session.Changed += () =>
        {
            if (session.Started)
                AfterFirstStart(extension);
        };

        // Subscribed before the run loop starts, so the link that started the helper isn't missed; the session holds
        // it until it has started.
        if (this.TryGetFeature<IActivatableLifetime>() is { } activatable)
            activatable.Activated += OnActivated;

        _tray = new TrayMenu(this, session, notifier, windows, extension.Check, () => desktop.Shutdown());
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

    /// <summary>
    /// Once per run, after the first start that signed in: start at sign-in (macOS's login item) and the LibreOffice
    /// extension's install or update (spec §5.12). Both run off the UI thread, and a failure is only logged.
    /// </summary>
    private void AfterFirstStart(ExtensionInstaller extension)
    {
        if (Interlocked.Exchange(ref _afterStart, 1) == 1)
            return;
        _ = Task.Run(async () =>
        {
            try
            {
                LoginItem.EnsureRegistered(Program.Paths, _log);
            }
            catch (Exception e)
            {
                _log.Error("Start at sign-in couldn't be set up", e);
            }
            try
            {
                _log.Info($"LibreOffice extension check: {await extension.EnsureCurrentAsync(_stopping.Token)}");
            }
            catch (OperationCanceledException) when (_stopping.IsCancellationRequested)
            {
                _log.Info("Quit during the LibreOffice extension check");
            }
            catch (Exception e)
            {
                _log.Error("The LibreOffice extension check failed", e);
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

    /// <summary>
    /// The system's keychain, under the service <paramref name="paths"/> names, so a Debug build with its files apart has
    /// its own entry; one run by hand can take a token from <c>HEALDAN_HELPER_DEV_TOKEN</c> instead.
    /// </summary>
    private IKeychain Keychain(HelperPaths paths, DeviceIdentity device)
    {
#if DEBUG
        if (Environment.GetEnvironmentVariable("HEALDAN_HELPER_DEV_TOKEN") is { Length: > 0 } token)
        {
            _log.Warn("Debug build: the sign-in comes from HEALDAN_HELPER_DEV_TOKEN, not the keychain, as this device's");
            return new DevKeychain(token, device.Id);
        }
#endif
        return OperatingSystem.IsMacOS() ? new MacKeychain(paths.KeychainService) : new SecretToolKeychain(paths.KeychainService);
    }

    private void Stop()
    {
        if (_stopped)
            return;
        _stopped = true;
        _stopping.Cancel();
        _session?.Dispose();
        _tray?.Dispose();
        foreach (var signal in _signals)
            signal.Dispose();
        _log.Info("Stopped");
    }
}
