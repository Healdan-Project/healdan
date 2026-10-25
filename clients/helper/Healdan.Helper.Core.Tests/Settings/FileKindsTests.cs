using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>Settings' "which app opens each kind" (spec §5.11): the three kinds, all LibreOffice in M3.</summary>
public class FileKindsTests
{
    [Fact]
    public void TheKindsAreTheSpecs()
    {
        Assert.Equal(["Documents", "Spreadsheets", "Presentations"], FileKinds.All.Select(k => k.Label));
        Assert.Equal([".doc", ".docx", ".docm", ".dotx", ".dotm", ".rtf", ".odt", ".ott"], FileKinds.Documents.Extensions);
        Assert.Equal([".xls", ".xlsx", ".xlsm", ".ods"], FileKinds.Spreadsheets.Extensions);
        Assert.Equal([".ppt", ".pptx", ".pptm", ".odp"], FileKinds.Presentations.Extensions);
    }

    [Fact]
    public void EveryKindOpensInLibreOfficeAndAnythingElseInTheDefaultApp()
    {
        var settings = new HelperSettings();
        foreach (var extension in FileKinds.All.SelectMany(k => k.Extensions))
            Assert.Equal("LibreOffice", settings.AppFor(extension));
        Assert.Equal("LibreOffice", settings.AppFor("DOCX"));
        Assert.Equal("Default", settings.AppFor(".pdf"));
        Assert.Equal(FileKinds.Documents, FileKinds.Of("Lease.DOCX"));
        Assert.Null(FileKinds.Of("Signed NDA.pdf"));
    }
}
