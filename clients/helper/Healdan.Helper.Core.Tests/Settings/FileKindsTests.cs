using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>Settings' "which app opens each kind" (spec §5.11): the four kinds, LibreOffice until the user or the system says otherwise.</summary>
public class FileKindsTests
{
    [Fact]
    public void TheKindsAreTheSpecs()
    {
        Assert.Equal(["Documents", "Spreadsheets", "Presentations", "Drawings"], FileKinds.All.Select(k => k.Label));
        Assert.Equal([".doc", ".docx", ".docm", ".dotx", ".dotm", ".rtf", ".odt", ".ott"], FileKinds.Documents.Extensions);
        Assert.Equal([".xls", ".xlsx", ".xlsm", ".ods"], FileKinds.Spreadsheets.Extensions);
        Assert.Equal([".ppt", ".pptx", ".pptm", ".odp"], FileKinds.Presentations.Extensions);
        // Only Draw's own format: Draw opens Visio and Publisher files but can't save them, so they stay the default app's.
        Assert.Equal([".odg"], FileKinds.Drawings.Extensions);
    }

    [Fact]
    public void EveryKindOpensInLibreOfficeAndAnythingElseInTheDefaultAppWithNoChoiceMade()
    {
        var chooser = new AppChooser(new HelperSettings(), new InstalledApps(true, true, true, true), _ => null);
        foreach (var extension in FileKinds.All.SelectMany(k => k.Extensions))
            Assert.Equal("LibreOffice", chooser.For("a" + extension));
        Assert.Equal("LibreOffice", chooser.For("a.DOCX"));
        Assert.Equal("Default", chooser.For("a.pdf"));
        Assert.Equal(FileKinds.Documents, FileKinds.Of("Lease.DOCX"));
        Assert.Null(FileKinds.Of("Signed NDA.pdf"));
    }

    [Fact]
    public void EachKindsMainFormatIsWhatTheSystemsDefaultIsAskedAbout()
    {
        Assert.Equal("docx", FileKinds.Documents.MainFormat);
        Assert.Equal("xlsx", FileKinds.Spreadsheets.MainFormat);
        Assert.Equal("pptx", FileKinds.Presentations.MainFormat);
        Assert.Null(FileKinds.Drawings.MainFormat); // Office doesn't open them
    }

    [Fact]
    public void AFilesKindIsItsOwnExtensions()
    {
        // What links and the cycle go by: the name's own extension, never a bare name that looks like one.
        Assert.Equal(FileKinds.Spreadsheets, FileKinds.OfFile("Budget.XLSX"));
        Assert.Equal(FileKinds.Documents, FileKinds.OfFile("/Copies/Lease #5 v1.0.odt"));
        Assert.Equal(FileKinds.Drawings, FileKinds.OfFile("/Copies/Floor plan #11 v1.0.odg"));
        Assert.Null(FileKinds.OfFile("Site layout.vsdx"));
        Assert.Null(FileKinds.OfFile("docx"));
        Assert.Null(FileKinds.OfFile("Signed NDA.pdf"));
        Assert.Null(FileKinds.OfFile("Run me.command"));
    }
}
