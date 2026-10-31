using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>Which program opens a file, and the facts about each program the helper needs (spec §5.1, §5.2).</summary>
public sealed class OfficeProgramsTests
{
    [Theory]
    [InlineData("Lease #1 v1.0.doc", OfficeProgram.Word)]
    [InlineData("Lease #1 v1.0.docx", OfficeProgram.Word)]
    [InlineData("Lease #1 v1.0.docm", OfficeProgram.Word)]
    [InlineData("Lease #1 v1.0.dotx", OfficeProgram.Word)]
    [InlineData("Lease #1 v1.0.dotm", OfficeProgram.Word)]
    [InlineData("Lease #1 v1.0.rtf", OfficeProgram.Word)]
    [InlineData("Fees #3 v1.0.xls", OfficeProgram.Excel)]
    [InlineData("Fees #3 v1.0.xlsx", OfficeProgram.Excel)]
    [InlineData("Fees #3 v1.0.xlsm", OfficeProgram.Excel)]
    [InlineData("Deck #4 v1.0.ppt", OfficeProgram.PowerPoint)]
    [InlineData("Deck #4 v1.0.pptx", OfficeProgram.PowerPoint)]
    [InlineData("Deck #4 v1.0.pptm", OfficeProgram.PowerPoint)]
    [InlineData("LEASE #1 V1.0.DOCX", OfficeProgram.Word)]
    [InlineData("/Users/me/Healdan Helper/Copies/Fees.XLSX", OfficeProgram.Excel)]
    public void TheExtensionNamesTheProgram(string fileName, OfficeProgram expected) =>
        Assert.Equal(expected, OfficePrograms.For(fileName));

    [Theory]
    [InlineData("Notes.odt")]
    [InlineData("Notes.pdf")]
    [InlineData("Notes.txt")]
    [InlineData("Notes.docx.bak")]
    [InlineData("docx")]
    [InlineData("Notes.")]
    [InlineData("")]
    public void AnythingElseHasNoProgram(string fileName) =>
        Assert.Null(OfficePrograms.For(fileName));

    [Fact]
    public void EachProgramsFacts()
    {
        Assert.Equal("Microsoft Word", OfficePrograms.ProcessName(OfficeProgram.Word));
        Assert.Equal("Microsoft Excel", OfficePrograms.ProcessName(OfficeProgram.Excel));
        Assert.Equal("Microsoft PowerPoint", OfficePrograms.ProcessName(OfficeProgram.PowerPoint));
        Assert.Equal("/Applications/Microsoft Word.app", OfficePrograms.BundlePath(OfficeProgram.Word));
        Assert.Equal("/Applications/Microsoft Excel.app", OfficePrograms.BundlePath(OfficeProgram.Excel));
        Assert.Equal("/Applications/Microsoft PowerPoint.app", OfficePrograms.BundlePath(OfficeProgram.PowerPoint));
        Assert.Equal("Word", OfficePrograms.CheckOutName(OfficeProgram.Word));
        Assert.Equal("Excel", OfficePrograms.CheckOutName(OfficeProgram.Excel));
        Assert.Equal("PowerPoint", OfficePrograms.CheckOutName(OfficeProgram.PowerPoint));
        Assert.Equal(".docx", OfficePrograms.OwnFormat(OfficeProgram.Word));
        Assert.Equal(".xlsx", OfficePrograms.OwnFormat(OfficeProgram.Excel));
        Assert.Equal(".pptx", OfficePrograms.OwnFormat(OfficeProgram.PowerPoint));
        Assert.True(OfficePrograms.RenamesAfterSave(OfficeProgram.Word));
        Assert.True(OfficePrograms.RenamesAfterSave(OfficeProgram.Excel));
        Assert.False(OfficePrograms.RenamesAfterSave(OfficeProgram.PowerPoint));
    }

    [Fact]
    public void EveryProgramOpensItsOwnFormat()
    {
        foreach (var program in Enum.GetValues<OfficeProgram>())
            Assert.Equal(program, OfficePrograms.For("a" + OfficePrograms.OwnFormat(program)));
    }

    [Fact]
    public void WordsLongNamesLoseTheirFirstTwoCharactersToTheOwnerFile()
    {
        // A name of two characters or fewer has nothing to replace, so only the prefixed form remains.
        Assert.Equal(["~$ab"], OfficePrograms.OwnerNames(OfficeProgram.Word, "ab"));
        Assert.Equal(["~$c", "~$abc"], OfficePrograms.OwnerNames(OfficeProgram.Word, "abc"));
    }

    [Fact]
    public void TheOwnerFilesFirstTwoCharactersAreWholeCharacters()
    {
        // An emoji is one character to the user, two UTF-16 units, and a cut inside it would not be a name.
        var names = OfficePrograms.OwnerNames(OfficeProgram.Word, "📎aLease #1 v1.0.docx").ToList();

        Assert.Equal(["~$Lease #1 v1.0.docx", "~$📎aLease #1 v1.0.docx"], names);
    }
}
