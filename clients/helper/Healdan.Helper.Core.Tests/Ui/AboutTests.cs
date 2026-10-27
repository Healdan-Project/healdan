using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>What About Healdan Helper says (#89): the version, the server line and the Copy for Support line.</summary>
public sealed class AboutTests
{
    [Theory]
    [InlineData("0.1.0", "0.1.0")]
    [InlineData("0.1.0-rc1", "0.1.0-rc1")]
    [InlineData("1.0.0+40cc299e4d509c6da85eab49356bd8ccd23a7b44", "1.0.0 (40cc299e4)")] // a Debug build's commit, shortened
    [InlineData("1.0.0+abc12", "1.0.0 (abc12)")]
    [InlineData(null, "unknown")]
    [InlineData("", "unknown")]
    public void ShowsTheVersion(string? informational, string shown) => Assert.Equal(shown, About.Version(informational));

    public static TheoryData<AboutServer?, string> ServerLines() => new()
    {
        { new AboutServer("healdan.example", SignedIn: true, Version: "9.3.2"), "Signed in to healdan.example · Healdan 9.3.2" },
        { new AboutServer("healdan.example:8443", SignedIn: false, Version: "9.3.2"), "Not signed in to healdan.example:8443 · Healdan 9.3.2" },
        { new AboutServer("healdan.example", SignedIn: true, Version: null), "healdan.example couldn't be reached" },
        { null, "Not signed in" }
    };

    [Theory]
    [MemberData(nameof(ServerLines))]
    public void SaysWhereTheHelperSignsIn(AboutServer? server, string line) => Assert.Equal(line, About.ServerLine(server));

    public static TheoryData<AboutServer?, string> SupportLines() => new()
    {
        { new AboutServer("healdan.example", SignedIn: true, Version: "9.3.2"),
            "Healdan Helper 0.1.0, macOS 27.0.1 (arm64), server healdan.example, Healdan 9.3.2" },
        { new AboutServer("healdan.example", SignedIn: false, Version: null),
            "Healdan Helper 0.1.0, macOS 27.0.1 (arm64), server healdan.example (couldn't be reached)" },
        { null, "Healdan Helper 0.1.0, macOS 27.0.1 (arm64), no server" }
    };

    [Theory]
    [MemberData(nameof(SupportLines))]
    public void CopiesOneLineForSupport(AboutServer? server, string line) =>
        Assert.Equal(line, About.SupportLine("0.1.0", "macOS 27.0.1 (arm64)", server));
}
