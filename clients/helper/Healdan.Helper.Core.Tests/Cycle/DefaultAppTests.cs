// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>The kinds of file the system's default app is never handed (the final review's C2).</summary>
public sealed class DefaultAppTests
{
    private static readonly string[] Refused =
    [
        ".app", ".command", ".tool", ".terminal", ".pkg", ".mpkg", ".dmg", ".workflow", ".action", ".jar", ".fileloc",
        ".inetloc", ".webloc", ".scpt", ".scptd", ".applescript", ".sh", ".py", ".rb", ".pl", ".prefPane", ".kext",
        ".mobileconfig", ".shortcut",
        ".desktop", ".appimage", ".deb", ".rpm", ".snap", ".flatpak", ".flatpakref", ".flatpakrepo", ".run", ".bin", ".exe",
        ".msi", ".bat", ".cmd", ".com", ".ps1", ".vbs", ".lnk", ".jnlp"
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
    [InlineData("Editor.DeskTop")]
    [InlineData("/Copies/Tool.AppImage")]
    [InlineData("setup.EXE")]
    [InlineData("Update.Deb")]
    [InlineData("/Copies/run me.Ps1")]
    public void RefusesLinuxAndWineKindsInAnyCase(string name) => Assert.True(DefaultApp.Refuses(name));

    [Theory]
    [InlineData("Signed NDA.pdf")]
    [InlineData("Photo.JPG")]
    [InlineData("Notes.txt")]
    [InlineData("No extension")]
    [InlineData("Invoice.command.pdf")]
    [InlineData("Contract.DOCX")]
    [InlineData("Setup.exe.pdf")]
    public void HandsOverOtherFiles(string name) => Assert.False(DefaultApp.Refuses(name));
}
