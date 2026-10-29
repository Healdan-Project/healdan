using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Chrome;
using Avalonia.Input;

namespace Healdan.Helper.Windows;

/// <summary>
/// Open's and Save's toolbar in the title bar on macOS, as Finder's (polish spec §5.3): the window's name at its left,
/// after the system's buttons, and the bar's empty space dragging the window. Elsewhere the system's title bar stays,
/// with the toolbar under it.
/// </summary>
internal static class BrowserChrome
{
    /// <summary>Room for the system's three buttons at the bar's left.</summary>
    private const double SystemButtons = 78;

    private const double BarHeight = 44;

    /// <param name="controls">The bar's controls, which take clicks rather than dragging the window.</param>
    public static void Apply(Window window, Border toolbar, TextBlock title, IEnumerable<Control> controls)
    {
        if (!OperatingSystem.IsMacOS())
            return;
        window.ExtendClientAreaToDecorationsHint = true;
        window.ExtendClientAreaTitleBarHeightHint = BarHeight;
        toolbar.MinHeight = BarHeight;
        toolbar.Padding = new Thickness(SystemButtons, 0, 12, 0);
        (title.Text, title.IsVisible) = (window.Title, true);
        WindowDecorationProperties.SetElementRole(toolbar, WindowDecorationsElementRole.TitleBar);
        foreach (var control in controls)
            WindowDecorationProperties.SetElementRole(control, WindowDecorationsElementRole.User);
    }
}
