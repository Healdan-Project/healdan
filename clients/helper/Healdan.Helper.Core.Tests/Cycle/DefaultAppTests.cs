using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>The kinds of file the system's default app is never handed (the final review's C2).</summary>
public sealed class DefaultAppTests
{
    private static readonly string[] Refused =
    [
        ".app", ".command", ".tool", ".terminal", ".pkg", ".mpkg", ".dmg", ".workflow", ".action", ".jar", ".fileloc",
        ".inetloc", ".webloc", ".scpt", ".scptd", ".applescript", ".sh", ".py", ".rb", ".pl", ".prefPane", ".kext",
        ".mobileconfig", ".shortcut"
    ];

    public static TheoryData<string> RefusedExtensions() => [.. Refused];

    [Fact]
    public void TheListIsTheReviews() => Assert.Equal(Refused, DefaultApp.RefusedExtensions);

    [Theory]
    [MemberData(nameof(RefusedExtensions))]
    public void RefusesFilesThatCanRunPrograms(string extension)
    {
        Assert.True(DefaultApp.Refuses("Invoice" + extension));
        Assert.True(DefaultApp.Refuses("/Copies/Invoice #7 v1.0 (read-only)" + extension.ToUpperInvariant()));
    }

    [Theory]
    [InlineData("Signed NDA.pdf")]
    [InlineData("Photo.JPG")]
    [InlineData("Notes.txt")]
    [InlineData("No extension")]
    [InlineData("Invoice.command.pdf")]
    public void HandsOverOtherFiles(string name) => Assert.False(DefaultApp.Refuses(name));
}
