// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>A file's size as the windows show it (#146): the web page's units, so both show the same number.</summary>
public class FileSizesTests
{
    private static readonly CultureInfo Us = new("en-US");

    [Theory]
    [InlineData(0, "0 bytes")]
    [InlineData(1, "1 byte")]
    [InlineData(1023, "1023 bytes")]
    [InlineData(1024, "1.0 KB")]
    [InlineData(49_357, "48.2 KB")]
    [InlineData(1_363_149, "1.3 MB")]
    [InlineData(2_147_483_648, "2.0 GB")]
    public void InTheWebPagesUnitsOf1024Bytes(long bytes, string shown) => Assert.Equal(shown, FileSizes.Text(bytes, Us));

    [Fact]
    public void TheDecimalSeparatorIsTheRegions() => Assert.Equal("48,2 KB", FileSizes.Text(49_357, new CultureInfo("de-DE")));
}
