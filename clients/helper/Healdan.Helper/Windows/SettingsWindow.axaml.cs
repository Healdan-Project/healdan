using System.Globalization;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// Settings (spec §5.8, §5.11, §5.12; polish spec §6.5; M3.5 spec §5.10): the server the helper signs in to, and the app that
/// opens each kind of file, as grouped rows. One server per helper (spec §5.1), so the address changes only by signing out and
/// in again. Each kind has a pop-up of the installed apps (LibreOffice, Microsoft Office) showing the chooser's answer now, and
/// choosing one writes it to <see cref="HelperSettings.Kinds"/>; a kind with one app to offer, Drawings always, shows it as
/// text. PDFs follow as text too: CoffeePDF while it's installed with Healdan support on, else the default app, read-only
/// (M3.6 spec §4.4). Below them, while Word, Excel or PowerPoint is installed or an app is paired, the Microsoft Office group:
/// Set Up (its status, and Set Up… while it's needed) and the paired apps, each with when it paired and Forget. Then whether
/// the helper starts at sign-in, and its LibreOffice extension's version and when it went in. While the extension isn't
/// installed its row offers Install…, which does what the menu's Install the LibreOffice Extension item does, and while
/// an older version is, Update…, which does the same (changed in the build).
/// </summary>
public partial class SettingsWindow : Window
{
    private const string Https = "https://";

    /// <summary>Turn on Healdan support in CoffeePDF to have PDFs open there (M3.6 spec §4.4).</summary>
    internal const string PdfSupportOffNote = "Turn on Healdan support in CoffeePDF's Settings to edit PDFs there.";

    private readonly HelperPaths? _paths;
    private readonly OfficeSettings? _office;
    private readonly HelperLog? _log;

    /// <summary>For the XAML loader; the helper always opens it with the settings.</summary>
    public SettingsWindow() => InitializeComponent();

    /// <param name="paths">Where a kind's choice is kept (<see cref="HelperSettings"/>).</param>
    /// <param name="extension">The extension's check, which Install… (or Update…) runs; null when the build has none.</param>
    /// <param name="office">Microsoft Office's Set Up and paired apps.</param>
    internal SettingsWindow(string? server, bool signedIn, HelperPaths paths, AppChooser chooser, LoginItemSummary login, ExtensionCheck? extension,
        OfficeSettings office, HelperLog? log = null) : this()
    {
        (_paths, _office, _log) = (paths, office, log);
        // As Sign In takes it: https:// left out, as Sign In adds it, and http:// or a path kept.
        ServerValue.Text = server is null ? "Not Set Yet"
            : server.StartsWith(Https, StringComparison.OrdinalIgnoreCase) ? server[Https.Length..] : server;
        ServerNote.Text = signedIn
            ? "To use another server, sign out from the Healdan Helper menu, then sign in with its address."
            : "Sign in from the Healdan Helper menu to choose the server.";
        for (var i = 0; i < FileKinds.All.Count; i++)
            KindsGroup.Children.Add(KindRow(FileKinds.All[i], chooser, divided: i > 0));
        KindsGroup.Children.Add(PdfRow(chooser));
        SetUpButton.Click += OnSetUp;
        ShowOffice();

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

    /// <summary>
    /// A kind of file, its extensions under it, and the app that opens it: a pop-up of the installed apps when there are two
    /// to choose between, else the one as text (Drawings, or a Mac with only one of the apps).
    /// </summary>
    private Control KindRow(FileKind kind, AppChooser chooser, bool divided)
    {
        var app = chooser.ForKind(kind);
        Control value;
        if (chooser.Offered(kind) is { Count: > 1 } offered)
        {
            var box = new ComboBox { ItemsSource = offered, SelectedItem = app, MinWidth = 150, VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetName(box, $"{kind.Label} Open In");
            var saved = app; // what a failed write goes back to
            box.SelectionChanged += (_, _) =>
            {
                if (box.SelectedItem is string chosen && chosen != saved)
                    saved = Choose(kind, chosen) ? chosen : saved;
                if (box.SelectedItem as string != saved)
                    box.SelectedItem = saved;
            };
            value = box;
        }
        else
            value = AppText(app);
        return Row(FileIcons.For(kind), kind.Label, string.Join(" ", kind.Extensions), null, value, divided);
    }

    /// <summary>A row of the kinds' group: its icon, its label with its extensions and any note under them, and the app.</summary>
    private static Control Row(string iconName, string label, string extensions, string? note, Control value, bool divided)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Classes = { "row" } };
        var icon = Icons.View(iconName);
        icon.Classes.Add("folder");
        icon.Margin = new Thickness(0, 0, 8, 0);
        icon.VerticalAlignment = VerticalAlignment.Center;
        var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        name.Children.Add(new TextBlock { Text = label });
        name.Children.Add(new TextBlock { Text = extensions, Classes = { "muted" }, FontSize = 11 });
        if (note is not null)
            name.Children.Add(new TextBlock { Text = note, Classes = { "muted" }, FontSize = 11, TextWrapping = TextWrapping.Wrap });
        Grid.SetColumn(name, 1);
        Grid.SetColumn(value, 2);
        grid.Children.Add(icon);
        grid.Children.Add(name);
        grid.Children.Add(value);
        return divided ? new Border { Classes = { "divided" }, Child = grid } : grid;
    }

    /// <summary>The app as text, for a row with nothing to choose between.</summary>
    private static TextBlock AppText(string app)
    {
        var text = new TextBlock { Text = app, VerticalAlignment = VerticalAlignment.Center };
        text.Bind(TextBlock.ForegroundProperty, text.GetResourceObservable("HdSecondaryBrush")); // follows a theme switch
        return text;
    }

    /// <summary>
    /// PDFs (M3.6 spec §4.4), as text: CoffeePDF while it's installed with Healdan support on, else the default app,
    /// read-only, with a note when CoffeePDF is installed and its support is off.
    /// </summary>
    private static Control PdfRow(AppChooser chooser) =>
        Row("file-type-pdf", "PDFs", ".pdf", chooser.CoffeePdfSupportOff ? PdfSupportOffNote : null,
            AppText(chooser.CoffeePdfAvailable ? CoffeePdfApp.AppName : "Default app, read-only"), divided: true);

    /// <summary>Keeps the user's choice for the kind, with the others as they were. False when it couldn't be written.</summary>
    private bool Choose(FileKind kind, string app)
    {
        try
        {
            HelperSettings.Update(_paths!, s => (s.Kinds ??= [])[kind.Label] = app);
            _log?.Info($"Settings: {kind.Label} open in {app}");
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log?.Error($"Settings couldn't keep {app} for {kind.Label}", e);
            return false;
        }
    }

    // --- Microsoft Office ------------------------------------------------------------------------------------------

    /// <summary>
    /// Microsoft Office's group (M3.5 spec §5.10): Set Up while Word, Excel or PowerPoint is installed, and the apps paired.
    /// Left out when neither. Read when the window opens and after Set Up or a Forget: a failure to read Set Up's state only
    /// leaves its row out, as Settings must still open.
    /// </summary>
    private void ShowOffice()
    {
        var office = _office!;
        bool applies, needed;
        try
        {
            (applies, needed) = (office.SetUp?.Applies == true, office.SetUp?.Needed == true);
        }
        catch (Exception e)
        {
            _log?.Error("Whether Set Up Microsoft Office is needed couldn't be told", e);
            (applies, needed) = (false, false);
        }
        IReadOnlyList<PairedApp> paired;
        try
        {
            paired = office.Paired();
        }
        catch (Exception e)
        {
            _log?.Error("The paired apps couldn't be listed", e);
            paired = [];
        }

        SetUpRow.IsVisible = applies;
        PairedSection.Classes.Set("divided", applies); // the line between the two rows, when there are two
        SetUpValue.Text = needed ? "Needed" : "Done";
        SetUpDot.Bind(Shape.FillProperty, SetUpDot.GetResourceObservable(needed ? "HdOffBrush" : "HdOnBrush"));
        SetUpButton.IsVisible = needed;

        PairedNone.IsVisible = paired.Count == 0;
        PairedList.Children.Clear();
        foreach (var app in paired)
            PairedList.Children.Add(PairedRow(app));

        OfficeGroup.IsVisible = OfficeHeader.IsVisible = applies || paired.Count > 0;
    }

    /// <summary>One paired app under Paired Apps: its name, when it paired, and Forget.</summary>
    private Control PairedRow(PairedApp paired)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Classes = { "row" } };
        var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(24, 0, 0, 0) }; // under the title, not its icon
        name.Children.Add(new TextBlock { Text = paired.App });
        name.Children.Add(new TextBlock
        {
            Text = "Paired on " + paired.Paired.ToLocalTime().ToString("d MMMM yyyy", CultureInfo.InvariantCulture),
            Classes = { "muted" },
            FontSize = 11
        });
        var forget = new Button { Content = "Forget", VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(forget, "Forget " + paired.App);
        forget.Click += (_, _) =>
        {
            try
            {
                _office!.Forget(paired.App);
                _log?.Info($"Settings: forgot {paired.App}'s pairing");
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                _log?.Error($"Settings couldn't forget {paired.App}'s pairing", e); // it stays on the list below
            }
            ShowOffice();
        };
        Grid.SetColumn(forget, 1);
        grid.Children.Add(name);
        grid.Children.Add(forget);
        return new Border { Classes = { "divided" }, Child = grid };
    }

    private async void OnSetUp(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        SetUpButton.IsEnabled = false;
        try
        {
            await _office!.RunSetUp();
        }
        catch (Exception ex)
        {
            _log?.Error("Set Up Microsoft Office failed from Settings", ex);
        }
        SetUpButton.IsEnabled = true;
        ShowOffice(); // Done, if the Mac trusts the certificate now
    }
}
