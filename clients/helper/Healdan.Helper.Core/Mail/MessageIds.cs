// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Mail;

/// <summary>
/// A message's Message-ID as the server compares it (M5 spec §6.1): the header's value without its angle brackets or
/// surrounding space, compared exactly, at most <see cref="MaxLength"/> characters. The server answers each id it was asked
/// about in this form, so the helper puts the ids it sends, and the answers it matches them to, in it too.
/// </summary>
internal static class MessageIds
{
    /// <summary>The longest Message-ID kept; a longer one counts as none.</summary>
    public const int MaxLength = 998;

    /// <summary>The id without its brackets (which go as a pair) or surrounding space; null when that is empty or too long.</summary>
    public static string? Normalise(string? header)
    {
        if (header is null)
            return null;
        var id = header.Trim();
        if (id.Length >= 2 && id[0] == '<' && id[^1] == '>')
            id = id[1..^1].Trim();
        return id.Length is 0 or > MaxLength ? null : id;
    }
}
