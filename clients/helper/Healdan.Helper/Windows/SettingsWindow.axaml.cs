using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Threading;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// Settings (spec §5.8, §5.11, §5.12; polish spec §6.5): the server the helper signs in to, and the app that opens each
/// kind of file, as grouped rows. One server per helper (spec §5.1), so the address changes only by signing out and in
/// again. The app that opens each kind is shown, as plain text, as the chooser answers now. Below them: whether
/// the helper starts at sign-in, and its LibreOffice extension's version and when it went in. While the extension isn't
/// installed its row offers Install…, which does what the menu's Install the LibreOffice Extension item does, and while
/// an older version is, Update…, which does the same (changed in the build).
/// </summary>
public partial class SettingsWindow : Window
{
    private const string Https = "https://";

    /// <summary>For the XAML loader; the helper always opens it with the settings.</summary>
    public SettingsWindow() => InitializeComponent();

    /// <param name="extension">The extension's check, which Install… (or Update…) runs; null when the build has none.</param>
    internal SettingsWindow(string? server, bool signedIn, AppChooser chooser, LoginItemSummary login, ExtensionCheck? extension) : this()
    {
        // As Sign In takes it: https:// left out, as Sign In adds it, and http:// or a path kept.
        ServerValue.Text = server is null ? "Not Set Yet"
            : server.StartsWith(Https, StringComparison.OrdinalIgnoreCase) ? server[Https.Length..] : server;
        ServerNote.Text = signedIn
            ? "To use another server, sign out from the Healdan Helper menu, then sign in with its address."
            : "Sign in from the Healdan Helper menu to choose the server.";
        for (var i = 0; i < FileKinds.All.Count; i++)
            KindsGroup.Children.Add(KindRow(FileKinds.All[i], chooser.For("a" + FileKinds.All[i].Extensions[0]), divided: i > 0));

        LoginValue.Text = login.Value;
        LoginDot.Bind(Shape.FillProperty, LoginDot.GetResourceObservable(login.On ? "HdOnBrush" : "HdOffBrush"));
        (LoginNote.Text, LoginNote.IsVisible) = (login.Note, login.Note is not null);
        ShowExtension(extension);
        if (extension is not null)
        {
            // The menu bar icon reads LibreOffice's registry again while the extension is missing, so the row follows
            // an install the user accepts there.
            Action changed = () => Dispatcher.UIThread.Post(() => ShowExtension(extension));
            extension.Changed += changed;
            Closed += (_, _) => extension.Changed -= changed;
        }
        InstallButton.Click += async (_, _) =>
        {
            if (extension is null)
                return;
            InstallButton.IsEnabled = false;
            try
            {
                await Task.Run(extension.InstallFromMenu);
            }
            catch (Exception e)
            {
                Program.Log.Error("Settings' Install… failed", e);
            }
            ShowExtension(extension); // what it says now, such as "Waiting for you to accept …" (call 4)
            InstallButton.IsEnabled = true;
        };
        DoneButton.Click += (_, _) => Close();
    }

    private void ShowExtension(ExtensionCheck? check)
    {
        var extension = check?.Summary() ?? ExtensionSummary.NotInThisBuild;
        ExtensionValue.Text = extension.Value;
        (ExtensionNote.Text, ExtensionNote.IsVisible) = (extension.Note, extension.Note is not null);
        InstallButton.IsVisible = extension.CanInstall;
        InstallButton.Content = extension.Button; // Install…, or Update… while an older version is installed
        ToolTip.SetTip(ExtensionRow, extension.Tip);
    }

    /// <summary>A kind of file, its extensions under it, and the app that opens it as plain text: no pop-up yet.</summary>
    private static Control KindRow(FileKind kind, string app, bool divided)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Classes = { "row" } };
        var icon = Icons.View(FileIcons.For(kind));
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
