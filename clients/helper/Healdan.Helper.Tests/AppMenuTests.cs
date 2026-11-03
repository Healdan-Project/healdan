// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Avalonia.Controls;
using Avalonia.Input;

namespace Healdan.Helper.Tests;

/// <summary>
/// The app menu macOS shows while one of the helper's windows is open and the helper is in the Dock (#143), in place of
/// Avalonia's, which offers "About Avalonia".
/// </summary>
public sealed class AppMenuTests
{
    [MacFact]
    public void ItHasAboutSettingsHideAndQuit() => Headless.Run(() =>
    {
        var menu = AppMenu.Build(() => { }, () => { }, () => { }, () => { });

        Assert.Equal(["About Healdan Helper", "-", "Settings…", "-", "Hide Healdan Helper", "-", "Quit Healdan Helper"],
            menu.Items.Select(i => i is NativeMenuItem and not NativeMenuItemSeparator ? ((NativeMenuItem)i).Header : "-"));
        var items = menu.Items.OfType<NativeMenuItem>().Where(i => i is not NativeMenuItemSeparator).ToDictionary(i => i.Header!);
        Assert.Equal(new KeyGesture(Key.OemComma, KeyModifiers.Meta), items["Settings…"].Gesture);
        Assert.Equal(new KeyGesture(Key.H, KeyModifiers.Meta), items["Hide Healdan Helper"].Gesture);
        Assert.Equal(new KeyGesture(Key.Q, KeyModifiers.Meta), items["Quit Healdan Helper"].Gesture);
        return 0;
    });
}
