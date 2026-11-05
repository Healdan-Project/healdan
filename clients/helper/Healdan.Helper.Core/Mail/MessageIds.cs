// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text;

namespace Healdan.Helper.Core.Mail;

/// <summary>
/// A message's Message-ID as the server compares it (M5 spec §6.1): the header's value without its angle brackets or
/// surrounding space, compared exactly, at most <see cref="MaxLength"/> characters. The server answers each id it was asked
/// about in this form, so the helper puts the ids it sends, and the answers it matches them to, in it too. It follows the
/// server's <c>MessageIds.normalize</c> to the character: a character beyond the Basic Multilingual Plane, or a lone
/// surrogate, becomes U+FFFD (as MariaDB's tables can't hold it), NUL becomes a space, and the space trimmed is what Java's
/// <c>strip()</c> trims, which leaves a no-break space alone.
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
        var id = Strip(BasicPlane(header).Replace('\0', ' '));
        if (id.Length >= 2 && id[0] == '<' && id[^1] == '>')
            id = Strip(id[1..^1]);
        return id.Length is 0 or > MaxLength ? null : id;
    }

    /// <summary>U+FFFD for each character beyond the Basic Multilingual Plane (a surrogate pair) and each lone surrogate.</summary>
    private static string BasicPlane(string text)
    {
        if (!text.Any(char.IsSurrogate))
            return text;
        var clean = new StringBuilder(text.Length);
        for (var i = 0; i < text.Length; i++)
        {
            if (char.IsHighSurrogate(text[i]) && i + 1 < text.Length && char.IsLowSurrogate(text[i + 1]))
                i++; // the pair is one character, and one U+FFFD
            clean.Append(char.IsSurrogate(text[i]) ? '\uFFFD' : text[i]);
        }
        return clean.ToString();
    }

    /// <summary>The text without the space Java's <c>strip()</c> takes off either end.</summary>
    private static string Strip(string text)
    {
        int start = 0, end = text.Length;
        while (start < end && IsJavaWhitespace(text[start]))
            start++;
        while (end > start && IsJavaWhitespace(text[end - 1]))
            end--;
        return text[start..end];
    }

    /// <summary>Java's <c>Character.isWhitespace</c>: Unicode's spaces but the no-break ones, and the controls tab to return and 1C to 1F.</summary>
    private static bool IsJavaWhitespace(char c) =>
        c is >= '\t' and <= '\r' or >= '\u001C' and <= '\u001F'
        || (char.GetUnicodeCategory(c) is UnicodeCategory.SpaceSeparator or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator
            && c is not ('\u00A0' or '\u2007' or '\u202F'));
}
