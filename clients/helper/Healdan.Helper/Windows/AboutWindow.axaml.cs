using System.Reflection;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Threading;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Windows;

/// <summary>
/// About Healdan Helper (#89): the helper's version and copyright, the server with the Healdan version it reports
/// (asked for as the window opens, and "Checking…" until it answers), links to the docs and the licence, the
/// open-source notices, and Copy for Support, which puts all of it on one line on the clipboard.
/// </summary>
public partial class AboutWindow : Window
{
    private readonly CancellationTokenSource _closing = new();

    /// <summary>For the XAML loader; the helper always opens it with the session.</summary>
    public AboutWindow() => InitializeComponent();

    internal AboutWindow(HelperSession? session, AppWindows windows) : this()
    {
        var version = About.Version(typeof(AboutWindow).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion);
        VersionText.Text = "Version " + version;
        CopyrightText.Text = About.Copyright;
        ServerText.Text = session?.Server is null ? About.ServerLine(null) : "Checking…";
        var server = session is null ? Task.FromResult<AboutServer?>(null) : session.AboutServerAsync(_closing.Token);
        _ = ShowServerAsync(server);

        DocsLink.Click += (_, _) => windows.OpenWebPage(About.Docs);
        LicenceLink.Click += (_, _) => windows.OpenWebPage(About.Licence);
        NoticesButton.Click += (_, _) => windows.ShowNotices();
        CopyButton.Click += async (_, _) => await CopyAsync(version, server);
        DoneButton.Click += (_, _) => Close();
        Opened += (_, _) => DoneButton.Focus();
        Closed += (_, _) => _closing.Cancel();
    }

    private async Task ShowServerAsync(Task<AboutServer?> server)
    {
        var line = About.ServerLine(await ServerOrNullAsync(server));
        Dispatcher.UIThread.Post(() => ServerText.Text = line);
    }

    /// <summary>Waits for the server line if it's still being checked, so the copy says what About will show.</summary>
    private async Task CopyAsync(string version, Task<AboutServer?> server)
    {
        if (Clipboard is not { } clipboard)
            return;
        try
        {
            await clipboard.SetTextAsync(About.SupportLine(version, About.System(), await ServerOrNullAsync(server)));
        }
        catch (Exception e)
        {
            Program.Log.Error("Copy for Support failed", e);
            return;
        }
        CopyButton.Content = "Copied";
        DispatcherTimer.RunOnce(() => CopyButton.Content = "Copy for Support", TimeSpan.FromSeconds(2));
    }

    /// <summary>The server's line; a failure the session didn't expect is logged, and About says "Not signed in".</summary>
    private static async Task<AboutServer?> ServerOrNullAsync(Task<AboutServer?> server)
    {
        try
        {
            return await server;
        }
        catch (Exception e)
        {
            Program.Log.Error("About couldn't check the server", e);
            return null;
        }
    }
}
