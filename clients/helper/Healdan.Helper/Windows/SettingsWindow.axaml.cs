using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// Settings (spec §5.8, §5.11, §5.12; polish spec §6.5): the server the helper signs in to, and the app that opens each
/// kind of file, as grouped rows. One server per helper (spec §5.1), so the address changes only by signing out and in
/// again. In M3 every kind opens in LibreOffice, so the choice is shown, as plain text, but fixed. Below them: whether
/// the helper starts at sign-in, and its LibreOffice extension's version and when it went in. While the extension isn't
/// installed its row offers Install…, which does what the menu's Install the LibreOffice Extension item does, and while
/// an older version is, Update…, which does the same (changed in the build).
/// </summary>
public partial class SettingsWindow : Window
{
    /// <summary>For the XAML loader; the helper always opens it with the settings.</summary>
    public SettingsWindow() => InitializeComponent();

    /// <param name="install">What Install… (or Update…) runs; the row then shows the summary it returns. Null when the build has no extension.</param>
    internal SettingsWindow(string? server, bool signedIn, HelperSettings settings, LoginItemSummary login, ExtensionSummary extension,
        string extensionTip, Func<Task<ExtensionSummary>>? install) : this()
    {
        // A hand-edited Server setting may not be an address at all: shown as it is, rather than ending the helper.
        ServerValue.Text = server is null ? "Not Set Yet"
            : Uri.TryCreate(server, UriKind.Absolute, out var uri) && uri.Authority.Length > 0 ? HealdanLink.HostAndPort(uri)
            : server;
        ServerNote.Text = signedIn
            ? "To use another server, sign out from the Healdan Helper menu, then sign in with its address."
            : "Sign in from the Healdan Helper menu to choose the server.";
        for (var i = 0; i < FileKinds.All.Count; i++)
            KindsGroup.Children.Add(KindRow(FileKinds.All[i], settings.AppFor(FileKinds.All[i].Extensions[0]), divided: i > 0));

        LoginValue.Text = login.Value;
        LoginDot.Bind(Shape.FillProperty, LoginDot.GetResourceObservable(login.On ? "HdOnBrush" : "HdOffBrush"));
        ToolTip.SetTip(LoginRow, login.Tip);
        ShowExtension(extension);
        ToolTip.SetTip(ExtensionRow, extensionTip);
        InstallButton.Click += async (_, _) =>
        {
            if (install is null)
                return;
            InstallButton.IsEnabled = false;
            try
            {
                ShowExtension(await install());
            }
            catch (Exception e)
            {
                Program.Log.Error("Settings' Install… failed", e);
            }
            InstallButton.IsEnabled = true;
        };
        DoneButton.Click += (_, _) => Close();
    }

    private void ShowExtension(ExtensionSummary extension)
    {
        ExtensionValue.Text = extension.Value;
        (ExtensionNote.Text, ExtensionNote.IsVisible) = (extension.Note, extension.Note is not null);
        InstallButton.IsVisible = extension.CanInstall;
        InstallButton.Content = extension.Button; // Install…, or Update… while an older version is installed
    }

    /// <summary>A kind of file, its extensions under it, and the app that opens it as plain text: no disabled pop-up.</summary>
    private static Control KindRow(FileKind kind, string app, bool divided)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Classes = { "row" } };
        var icon = Icons.View(FileIcons.For("x" + kind.Extensions[0]));
        icon.Classes.Add("folder");
        icon.Margin = new Thickness(0, 0, 8, 0);
        icon.VerticalAlignment = VerticalAlignment.Center;
        var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        name.Children.Add(new TextBlock { Text = kind.Label });
        name.Children.Add(new TextBlock { Text = string.Join(" ", kind.Extensions), Classes = { "muted" }, FontSize = 11 });
        var value = new TextBlock { Text = app, VerticalAlignment = VerticalAlignment.Center };
        value.Bind(TextBlock.ForegroundProperty, value.GetResourceObservable("HdSecondaryBrush")); // follows a theme switch
        Grid.SetColumn(name, 1);
        Grid.SetColumn(value, 2);
        grid.Children.Add(icon);
        grid.Children.Add(name);
        grid.Children.Add(value);
        return divided ? new Border { Classes = { "divided" }, Child = grid } : grid;
    }
}
