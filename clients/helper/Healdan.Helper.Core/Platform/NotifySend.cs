// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.ComponentModel;
using System.Diagnostics;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Native notifications on Linux (spec §5.1), through <c>notify-send</c>, which the package depends on
/// (<c>libnotify-bin</c>). No shell is involved: the text goes over as separate arguments, after <c>--</c> so a title
/// that starts with a dash isn't read as an option.
/// </summary>
public static class NotifySend
{
    /// <summary>How long the tool is given by default: it only hands the notice to the desktop, so more means it is stuck.</summary>
    public static readonly TimeSpan DefaultLimit = TimeSpan.FromSeconds(5);

    /// <summary>The command that shows the notice. The icon and desktop entry are what the package installs.</summary>
    public static ProcessStartInfo Command(string title, string message, string tool = "notify-send")
    {
        var info = new ProcessStartInfo(tool) { UseShellExecute = false };
        foreach (var argument in new[]
                 {
                     "--app-name=Healdan Helper", "--icon=healdan-helper", "--hint=string:desktop-entry:healdan-helper", "--",
                     title, message
                 })
            info.ArgumentList.Add(argument);
        return info;
    }

    /// <summary>
    /// Shows the notice. True only when the tool exited 0 within <paramref name="limit"/> (five seconds by default); false when it's missing, fails or is
    /// stuck (it's then killed), so the caller can fall back to the menu's status line.
    /// </summary>
    public static bool TryPost(string title, string message, string tool = "notify-send", TimeSpan? limit = null)
    {
        try
        {
            using var process = Process.Start(Command(title, message, tool));
            if (process is null) return false;
            if (!process.WaitForExit(limit ?? DefaultLimit))
            {
                try
                {
                    process.Kill(true);
                }
                catch (InvalidOperationException)
                {
                    // it ended just now
                }
                return false;
            }
            return process.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false; // notify-send isn't installed
        }
    }
}
