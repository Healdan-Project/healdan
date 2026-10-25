using Avalonia.Controls;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Windows;

/// <summary>
/// Settings (spec §5.8, §5.11): the server the helper signs in to, and the app that opens each kind of file. One
/// server per helper (spec §5.1), so the address changes only by signing out and in again. In M3 every kind opens in
/// LibreOffice, so the choice is shown but fixed. Below them, read-only: whether the helper starts at sign-in, and
/// its LibreOffice extension's version and which way it went in (spec §5.12).
/// </summary>
public partial class SettingsWindow : Window
{
    /// <summary>For the XAML loader; the helper always opens it with the settings.</summary>
    public SettingsWindow() => InitializeComponent();

    internal SettingsWindow(string? server, bool signedIn, HelperSettings settings, string loginItem, string extension) : this()
    {
        LoginItemLine.Text = loginItem;
        ExtensionLine.Text = extension;
        ServerBox.Text = server ?? "Not set yet";
        ServerNote.Text = signedIn
            ? "To use another server, sign out from the Healdan Helper menu, then sign in with its address."
            : "Sign in from the Healdan Helper menu to choose the server.";
        for (var row = 0; row < FileKinds.All.Count; row++)
        {
            var kind = FileKinds.All[row];
            var label = new StackPanel { Margin = new Avalonia.Thickness(0, 4) };
            label.Children.Add(new TextBlock { Text = kind.Label });
            label.Children.Add(new TextBlock { Text = string.Join(" ", kind.Extensions), Classes = { "muted" }, FontSize = 11 });
            var app = settings.AppFor(kind.Extensions[0]);
            var choice = new ComboBox
            {
                ItemsSource = new[] { app },
                SelectedIndex = 0,
                IsEnabled = false,
                HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Stretch,
                VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center
            };
            Grid.SetRow(label, row);
            Grid.SetRow(choice, row);
            Grid.SetColumn(choice, 1);
            KindsGrid.Children.Add(label);
            KindsGrid.Children.Add(choice);
        }
        DoneButton.Click += (_, _) => Close();
    }
}
