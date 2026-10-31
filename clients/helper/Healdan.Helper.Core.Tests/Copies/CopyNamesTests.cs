using System.Text;
using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Tests.Copies;

public class CopyNamesTests
{
    [Fact]
    public void OfficeOwnerNamesAreEveryOneAnyProgramMayKeep()
    {
        // Word drops the first two characters of a long name and the first one of a short one, and Excel and PowerPoint
        // keep the whole name; each is a name only a copy can have, as its doc # is in it.
        Assert.Equal(
            new[] { "~$ase agreement #1234 v2.1.docx", "~$ease agreement #1234 v2.1.docx", "~$Lease agreement #1234 v2.1.docx" }.Order(StringComparer.Ordinal),
            CopyNames.OfficeOwnerNames("Lease agreement #1234 v2.1.docx").Order(StringComparer.Ordinal));
        Assert.Equal(new[] { "~$b", "~$ab" }.Order(StringComparer.Ordinal), CopyNames.OfficeOwnerNames("ab").Order(StringComparer.Ordinal));
        Assert.Equal(["~$a"], CopyNames.OfficeOwnerNames("a"));
        // Each name once, however many programs would give it.
        var names = CopyNames.OfficeOwnerNames("Deck #4 v1.0.pptx").ToList();
        Assert.Equal(names.Distinct(), names);
    }

    [Fact]
    public void OfficeOwnerNamesCutWholeCharacters() =>
        Assert.Contains("~$Lease.docx", CopyNames.OfficeOwnerNames("📎aLease.docx"));

    [Fact]
    public void PlainName() =>
        Assert.Equal("Lease agreement #1234 v2.1.docx", CopyNames.FileName("Lease agreement.docx", 1234, "2.1", false));

    [Fact]
    public void ReadOnly() =>
        Assert.Equal("Lease agreement #1234 v2.1 (read-only).docx", CopyNames.FileName("Lease agreement.docx", 1234, "2.1", true));

    [Fact]
    public void UnsafeCharacters() =>
        Assert.Equal("Re- A-B – Ünïcødé 📎 #7 v1.0.odt", CopyNames.FileName("Re: A/B – Ünïcødé 📎.odt", 7, "1.0", false));

    [Fact]
    public void ControlCharactersAndBackslashBecomeDashes() =>
        Assert.Equal("a-b-c-d #7 v1.0.odt", CopyNames.FileName("a\\b\tc\nd.odt", 7, "1.0", false));

    [Theory]
    [InlineData(300, "a")]
    [InlineData(300, "📎")]
    [InlineData(150, "é")]
    [InlineData(100, "日本")]
    public void TooLong(int count, string unit)
    {
        var name = string.Concat(Enumerable.Repeat(unit, count)) + ".odt";
        var file = CopyNames.FileName(name, 7, "1.0", false);

        var bytes = Encoding.UTF8.GetByteCount(file);
        Assert.True(bytes <= 255);
        Assert.True(bytes <= 241, "leaves room for the record's and LibreOffice's longer names");
        Assert.EndsWith(" #7 v1.0.odt", file);
        Assert.True(Encoding.UTF8.GetByteCount($".{file}.healdan.json") <= 255);
        Assert.True(Encoding.UTF8.GetByteCount($".~lock.{file}#") <= 255);
        // A cut inside a surrogate pair would not survive a UTF-8 round trip.
        Assert.Equal(file, Encoding.UTF8.GetString(Encoding.UTF8.GetBytes(file)));
        Assert.DoesNotContain('�', file);
    }

    [Fact]
    public void TooLongReadOnlyKeepsItsSuffix()
    {
        var file = CopyNames.FileName(new string('x', 400) + ".docx", 99999, "12.34", true);
        Assert.EndsWith(" #99999 v12.34 (read-only).docx", file);
        Assert.True(Encoding.UTF8.GetByteCount(file) <= 241);
    }

    [Fact]
    public void CutDoesNotSplitCombiningMarks()
    {
        var name = string.Concat(Enumerable.Repeat("é", 200)) + ".odt";
        var file = CopyNames.FileName(name, 7, "1.0", false);
        var stem = file[..file.IndexOf(" #7", StringComparison.Ordinal)];
        Assert.EndsWith("é", stem);
    }

    [Fact]
    public void CutNameLosesTrailingSpacesAndDots()
    {
        var name = new string('a', 230) + " . .tail.odt";
        var file = CopyNames.FileName(name, 7, "1.0", false);
        Assert.DoesNotContain(" . #", file);
        Assert.DoesNotMatch(@"[ .] #7", file);
    }

    [Theory]
    [InlineData(".docx")]
    [InlineData("...")]
    [InlineData("  .odt")]
    [InlineData("")]
    public void EmptyNameBecomesDocument(string name)
    {
        var file = CopyNames.FileName(name, 3, "1.0", false);
        Assert.StartsWith("Document #3 v1.0", file);
    }

    [Fact]
    public void NameWithoutExtension() =>
        Assert.Equal("Notes #3 v1.0", CopyNames.FileName("Notes", 3, "1.0", false));

    [Fact]
    public void TitleAndDocRef()
    {
        Assert.Equal("Lease agreement #1234 v2.1", CopyNames.Title("Lease agreement.docx", 1234, "2.1", false));
        Assert.Equal("Lease agreement #1234 v2.1 (read-only)", CopyNames.Title("Lease agreement.docx", 1234, "2.1", true));
        Assert.Equal("#1234 v2.1", CopyNames.DocRef(1234, "2.1"));
    }
}
