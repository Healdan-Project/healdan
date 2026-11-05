// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Mail;

namespace Healdan.Helper.Core.Tests.Mail;

/// <summary>
/// The helper's Message-IDs are the server's (healdan-core's <c>MessageIds.normalize</c>), as it matches the server's
/// answers to its ids by exact text: these are what the server makes of each.
/// </summary>
public sealed class MessageIdsTests
{
    [Theory]
    [InlineData("<abc@example.com>", "abc@example.com")]
    [InlineData("  abc@example.com \t", "abc@example.com")]
    [InlineData("< abc@example.com >", "abc@example.com")]
    public void IdsAreNormalisedAsTheServerDoes(string header, string expected) =>
        Assert.Equal(expected, MessageIds.Normalise(header));

    [Fact]
    public void TheSpaceTrimmedIsJavasAndTheTextIsTheServersPlane()
    {
        // Facts, not theory data: these characters don't all travel well through a test's serialised data.
        Assert.Equal("abc@example.com", MessageIds.Normalise("\u2003<abc@example.com>\u2003")); // an em space Java strips
        Assert.Equal("<abc@example.com>\u00A0", MessageIds.Normalise("<abc@example.com>\u00A0")); // a no-break space it doesn't
        Assert.Equal("abc\u0085", MessageIds.Normalise("abc\u0085")); // nor NEL, which .NET's Trim would take
        Assert.Equal("a b@example.com", MessageIds.Normalise("a\0b@example.com")); // NUL becomes a space
        Assert.Equal("abc", MessageIds.Normalise("\0abc\0"));
        Assert.Equal("a\uFFFDb@example.com", MessageIds.Normalise("a\U0001F600b@example.com")); // one U+FFFD for the pair
        Assert.Equal("a\uFFFDb", MessageIds.Normalise("a\uD83Db")); // a lone surrogate
        Assert.Null(MessageIds.Normalise("< \0 >"));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("  ")]
    [InlineData("<>")]
    public void AnIdWithNothingInItIsNone(string? header) => Assert.Null(MessageIds.Normalise(header));

    [Fact]
    public void AnIdOver998CharactersIsNone()
    {
        Assert.Equal(998, MessageIds.Normalise("<" + new string('a', 998) + ">")!.Length);
        Assert.Null(MessageIds.Normalise(new string('a', 999)));
    }
}
