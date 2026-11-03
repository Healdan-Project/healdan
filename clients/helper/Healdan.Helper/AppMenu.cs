using Avalonia.Controls;
using Avalonia.Input;

namespace Healdan.Helper;

/// <summary>
/// The app menu macOS shows while one of the helper's windows is open, when the helper is in the Dock and ⌘Tab (#143):
/// About Healdan Helper, Settings… (⌘,), Hide Healdan Helper (⌘H) and Quit Healdan Helper (⌘Q). It replaces Avalonia's,
/// which offers "About Avalonia" (<c>DisableDefaultApplicationMenuItems</c> in <see cref="Program"/>). Quit goes through
/// the same shutdown as the menu bar icon's Quit.
/// </summary>
internal static class AppMenu
{
    public static NativeMenu Build(Action about, Action settings, Action hide, Action quit) =>
    [
        Item("About Healdan Helper", about),
        new NativeMenuItemSeparator(),
        Item("Settings…", settings, new KeyGesture(Key.OemComma, KeyModifiers.Meta)),
        new NativeMenuItemSeparator(),
        Item("Hide Healdan Helper", hide, new KeyGesture(Key.H, KeyModifiers.Meta)),
        new NativeMenuItemSeparator(),
        Item("Quit Healdan Helper", quit, new KeyGesture(Key.Q, KeyModifiers.Meta))
    ];

    private static NativeMenuItem Item(string text, Action click, KeyGesture? gesture = null)
    {
        var item = new NativeMenuItem(text) { Gesture = gesture };
        item.Click += (_, _) => click();
        return item;
    }
}
