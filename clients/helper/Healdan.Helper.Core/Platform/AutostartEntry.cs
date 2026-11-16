// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Whether the helper starts at sign-in on Ubuntu: the package's autostart entry
/// (<c>/etc/xdg/autostart/healdan-helper.desktop</c>) is there for every account, and a user turns it off with an entry
/// of their own in <c>~/.config/autostart</c> (what GNOME Tweaks' Startup Applications writes) that says
/// <c>Hidden=true</c> or <c>X-GNOME-Autostart-enabled=false</c>.
/// </summary>
public static class AutostartEntry
{
    public const string FileName = "healdan-helper.desktop";

    /// <summary>The system entry the package installs.</summary>
    public const string SystemPath = "/etc/xdg/autostart/" + FileName;

    /// <summary>Settings' row: a short value, whether it's on, and, unless it is, the line saying why or how to turn it on.</summary>
    public sealed record Row(string Value, bool On, string? Note);

    /// <summary>The user's entry: under <c>$XDG_CONFIG_HOME</c> when that is set, else <c>~/.config</c>.</summary>
    public static string UserPath(string? xdgConfigHome, string home) =>
        Path.Combine(string.IsNullOrEmpty(xdgConfigHome) ? Path.Combine(home, ".config") : xdgConfigHome, "autostart", FileName);

    /// <param name="systemEntryExists">Whether the package's entry is installed; it isn't in a build run by hand.</param>
    /// <param name="userEntry">The user's entry's text, or null when they have none.</param>
    public static Row Summarize(bool systemEntryExists, string? userEntry)
    {
        if (!systemEntryExists)
            return new("Not in This Build", false, "The installed package starts Healdan Helper when you sign in to this computer.");
        if (userEntry is not null && SwitchesOff(userEntry))
            return new("Off", false,
                "You turned it off for your account. To turn it on, enable Healdan Helper in GNOME Tweaks' Startup Applications, "
                + "or delete ~/.config/autostart/" + FileName + ".");
        return new("On", true, null);
    }

    /// <summary>Whether the entry says <c>Hidden=true</c> or <c>X-GNOME-Autostart-enabled=false</c> (values in any case).</summary>
    private static bool SwitchesOff(string entry)
    {
        foreach (var raw in entry.Split('\n'))
        {
            var line = raw.Trim();
            var equals = line.IndexOf('=');
            if (line.StartsWith('#') || equals < 0)
                continue;
            var key = line[..equals].Trim();
            var value = line[(equals + 1)..].Trim();
            if (key == "Hidden" && value.Equals("true", StringComparison.OrdinalIgnoreCase))
                return true;
            if (key == "X-GNOME-Autostart-enabled" && value.Equals("false", StringComparison.OrdinalIgnoreCase))
                return true;
        }
        return false;
    }
}
