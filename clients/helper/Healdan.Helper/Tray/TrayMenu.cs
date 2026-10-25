using Avalonia;
using Avalonia.Controls;
using Avalonia.Platform;
using Avalonia.Threading;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Platform;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tray;

/// <summary>
/// The menu-bar icon and its menu (spec §5.1): who is signed in, the documents open, any saves waiting to be sent,
/// Open from Healdan (once the helper has started), Install the LibreOffice Extension while it isn't installed
/// (spec §5.12, R22), Settings, Sign In or Sign Out, and Quit. When native notifications
/// can't be shown, the last notice is the menu's status line. The menu is rebuilt when it opens and whenever the
/// session changes.
/// </summary>
internal sealed class TrayMenu : IDisposable
{
    /// <summary>A status line longer than this is cut, so the menu stays narrow.</summary>
    private const int MaxStatus = 90;

    private readonly HelperSession _session;
    private readonly Notifier _notifier;
    private readonly AppWindows _windows;
    private readonly ExtensionCheck _extension;
    private readonly Action _quit;
    private readonly NativeMenu _menu = new();
    private readonly TrayIcon _icon;

    public TrayMenu(Application app, HelperSession session, Notifier notifier, AppWindows windows, ExtensionCheck extension, Action quit)
    {
        _session = session;
        _extension = extension;
        _windows = windows;
        _notifier = notifier;
        _quit = quit;
        // Black on transparent, used as a template image so the menu bar tints it; GNOME's dark top bar takes the light one.
        var asset = OperatingSystem.IsLinux() ? "tray-light.png" : "tray.png";
        using var png = AssetLoader.Open(new Uri($"avares://HealdanHelper/Assets/{asset}"));
        _icon = new TrayIcon { Icon = new WindowIcon(png), ToolTipText = "Healdan Helper", Menu = _menu, IsVisible = true };
        if (OperatingSystem.IsMacOS())
            MacOSProperties.SetIsTemplateIcon(_icon, true);
        TrayIcon.SetIcons(app, [_icon]);

        _menu.NeedsUpdate += (_, _) => Build();
        session.Changed += Refresh;
        notifier.StatusChanged += Refresh;
        extension.Changed += Refresh;
        Build();
    }

    public void Dispose()
    {
        _session.Changed -= Refresh;
        _notifier.StatusChanged -= Refresh;
        _extension.Changed -= Refresh;
        _icon.IsVisible = false;
        _icon.Dispose();
    }

    private void Refresh() => Dispatcher.UIThread.Post(Build);

    private void Build()
    {
        var items = _menu.Items;
        items.Clear();
        items.Add(Label(Who()));
        if (_notifier.Status is { } status)
            items.Add(Item(status.Length <= MaxStatus ? status : status[..MaxStatus] + "…", _notifier.ClearStatus));
        items.Add(new NativeMenuItemSeparator());

        var copies = _session.OpenCopies();
        if (copies.Count == 0)
            items.Add(Label("No documents open"));
        foreach (var copy in copies)
            items.Add(Item(_session.HasCrashed(copy) ? copy.Title + " (closed unexpectedly)" : copy.Title, () => _session.Bring(copy)));
        var waiting = _session.SavesWaiting;
        if (waiting > 0)
            items.Add(Label(waiting == 1 ? "1 save waiting to be sent" : $"{waiting} saves waiting to be sent"));
        items.Add(new NativeMenuItemSeparator());

        var open = Item("Open from Healdan…", _windows.OpenFromMenu);
        open.IsEnabled = _session.Started && _session.SignedIn;
        items.Add(open);
        if (_extension.Refresh()) // reads LibreOffice's registry, so the item goes once the user has accepted it
            items.Add(Item(ExtensionCheck.MenuItem, InstallExtension));
        items.Add(Item("Settings…", _windows.ShowSettings));
        items.Add(_session.SignedIn
            ? Item("Sign Out", () => Run(_session.SignOutAsync))
            : Item("Sign In…", () => Run(_session.SignInAgainAsync)));
        items.Add(new NativeMenuItemSeparator());
        items.Add(Item("Quit Healdan Helper", _quit));
    }

    private void InstallExtension() => _ = Task.Run(() =>
    {
        try
        {
            _extension.InstallFromMenu();
        }
        catch (Exception e)
        {
            Program.Log.Error("Install the LibreOffice Extension failed", e);
        }
    });

    private string Who()
    {
        var server = _session.Server is { } s ? HealdanLink.HostAndPort(s) : null;
        if (_session.SignedIn)
            return _session.Started ? $"Signed in as {_session.UserName ?? "you"} on {server}"
                : _session.StartFailed ? $"Couldn't start on {server}: quit and start again"
                : $"Starting on {server}…";
        return server is null ? "Not signed in" : $"Not signed in to {server}";
    }

    private static NativeMenuItem Label(string text) => new(text) { IsEnabled = false };

    private static NativeMenuItem Item(string text, Action click)
    {
        var item = new NativeMenuItem(text);
        item.Click += (_, _) => click();
        return item;
    }

    private void Run(Func<Task> action) => _ = Task.Run(async () =>
    {
        try
        {
            await action();
        }
        catch (Exception e)
        {
            Program.Log.Error("A menu command failed", e);
            _notifier.Notify("Healdan Helper", e.Message);
        }
        Refresh();
    });
}
