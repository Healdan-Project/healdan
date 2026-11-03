// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// A file's size as Open and Properties show it (#146): in 1024-byte KB, MB and GB to one decimal place, as the web page
/// shows it (<c>Util.formatSizeCompact</c>), so both give the same number for the same document. Finder's 1000-byte
/// units would differ. The decimal separator is the region's.
/// </summary>
public static class FileSizes
{
    public static string Text(long bytes, CultureInfo? culture = null)
    {
        var c = culture ?? CultureInfo.CurrentCulture;
        return bytes switch
        {
            1 => "1 byte",
            < 1024 => bytes.ToString(c) + " bytes",
            < 1024 * 1024 => Units(bytes / 1024.0, "KB", c),
            < 1024 * 1024 * 1024 => Units(bytes / (1024.0 * 1024), "MB", c),
            _ => Units(bytes / (1024.0 * 1024 * 1024), "GB", c)
        };
    }

    private static string Units(double size, string unit, CultureInfo c) => size.ToString("0.0", c) + " " + unit;
}
