using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>Each row's icon by its file's kind (polish spec §4.4): the office kinds as Settings has them, PDFs, images, and the rest.</summary>
public sealed class FileIconsTests
{
    [Theory]
    [InlineData("Lease agreement.docx", "file-text")]
    [InlineData("Minutes.ODT", "file-text")]
    [InlineData("Letter.rtf", "file-text")]
    [InlineData("Schedule of dilapidations.xlsx", "file-spreadsheet")]
    [InlineData("Costs.ods", "file-spreadsheet")]
    [InlineData("Pitch.pptx", "presentation")]
    [InlineData("Floor plan.odg", "triangle-square-circle")]
    [InlineData("Site layout.vsdx", "file")]
    [InlineData("Signed NDA.pdf", "file-type-pdf")]
    [InlineData("Site photo.JPG", "photo")]
    [InlineData("Plan.heic", "photo")]
    [InlineData("archive.zip", "file")]
    [InlineData("docx", "file")]
    public void EachFileGetsItsKindsIcon(string name, string icon) => Assert.Equal(icon, FileIcons.For(name));

    [Fact]
    public void SettingsRowsGetTheirKindsIcon() =>
        Assert.Equal(["file-text", "file-spreadsheet", "presentation", "triangle-square-circle"], FileKinds.All.Select(FileIcons.For));
}
