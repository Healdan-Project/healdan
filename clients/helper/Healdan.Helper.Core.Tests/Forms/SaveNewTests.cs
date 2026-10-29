using System.Text.Json.Nodes;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Forms;

/// <summary>What <c>/v1/save-new</c> asks the Save window, and what it answers (plan: the channel's routes; spec §5.6).</summary>
public class SaveNewTests
{
    [Fact]
    public void TheAppsOwnFormatComesFirstAndIsChosen()
    {
        var request = SaveNewRequest.From(new JsonObject
        {
            ["app"] = "LibreOffice", ["name"] = "Untitled 1", ["formats"] = new JsonArray("odt", "docx"), ["path"] = null
        });

        Assert.Equal(["odt", "docx"], request.Formats);
        Assert.Equal("odt", request.DefaultFormat());
        Assert.Equal("Untitled 1", request.Name);
        Assert.Null(request.Path);
        Assert.Null(request.DocNumber);
    }

    [Fact]
    public void FormatsAreReadAsTheExtensionsTheyName()
    {
        var request = SaveNewRequest.From(new JsonObject { ["formats"] = new JsonArray(".ODS", "xlsx", "ods") });

        Assert.Equal(["ods", "xlsx"], request.Formats);
        Assert.Equal("LibreOffice", request.App);
    }

    [Fact]
    public void SaveAsNewDocumentKeepsTheCopysFormatWhenItsOffered()
    {
        var request = SaveNewRequest.From(new JsonObject
        {
            ["name"] = "Lease agreement.docx", ["formats"] = new JsonArray("odt", "docx"),
            ["path"] = "/copies/Lease agreement #5 v1.0.docx", ["docNumber"] = 5
        });

        Assert.Equal("docx", request.DefaultFormat());
        Assert.Equal("Lease agreement", request.Name); // the format is chosen apart from the name
        Assert.Equal(5, request.DocNumber);
    }

    [Fact]
    public void FormatsHaveNamesPeopleKnow()
    {
        Assert.Equal("OpenDocument Text (.odt)", FileFormats.Label("odt"));
        Assert.Equal("Word Document (.docx)", FileFormats.Label("docx"));
        Assert.Equal("OpenDocument Spreadsheet (.ods)", FileFormats.Label("ods"));
        Assert.Equal("Excel Workbook (.xlsx)", FileFormats.Label("xlsx"));
        Assert.Equal("OpenDocument Presentation (.odp)", FileFormats.Label("odp"));
        Assert.Equal("PowerPoint Presentation (.pptx)", FileFormats.Label("pptx"));
        Assert.Equal("OpenDocument Drawing (.odg)", FileFormats.Label("odg"));
        Assert.Equal(".rtf", FileFormats.Label("rtf"));
    }

    [Theory]
    [InlineData("", "Enter a name.")]
    [InlineData("   ", "Enter a name.")]
    [InlineData("A/B", "A name can't contain / or \\.")]
    [InlineData("A\\B", "A name can't contain / or \\.")]
    [InlineData("Tab\there", "A name can't contain / or \\.")]
    [InlineData("Lease agreement", null)]
    [InlineData("Re: A – Ünïcødé", null)]
    public void NamesAreChecked(string name, string? problem) =>
        Assert.Equal(problem, DocumentNames.Problem(name, "odt"));

    [Fact]
    public void ANameMustFitWithItsExtension()
    {
        Assert.Null(DocumentNames.Problem(new string('a', 251), "odt")); // 255 with ".odt"
        Assert.Equal("The name is too long: at most 255 characters with its extension.", DocumentNames.Problem(new string('a', 252), "odt"));
    }

    [Fact]
    public void SaveAnswersWithATemporaryFileInTheHelpersTempFolder()
    {
        var paths = HelperPaths.For(Path.Combine(Path.GetTempPath(), "healdan-answers"));

        var first = WindowAnswers.TempPath(paths, "odt");
        var second = WindowAnswers.TempPath(paths, "odt");

        Assert.Equal(paths.Temp, Path.GetDirectoryName(first));
        Assert.EndsWith(".odt", first);
        Assert.NotEqual(first, second);
        Assert.False(File.Exists(first)); // the extension writes it
    }

    [Fact]
    public void TheAnswersHaveTheRoutesShapes()
    {
        var save = WindowAnswers.Save(12, "Lease agreement", "odt", new JsonObject { ["comments"] = "x" }, "/t/a.odt");

        Assert.Equal(
            """{"action":"save","folderId":12,"name":"Lease agreement","format":"odt","properties":{"comments":"x"},"tempPath":"/t/a.odt"}""",
            save.ToJsonString());
        Assert.Equal("""{"action":"local"}""", WindowAnswers.Local().ToJsonString());
        Assert.Equal("""{"action":"cancel"}""", WindowAnswers.Cancel().ToJsonString());
        Assert.Equal("""{"action":"opened"}""", WindowAnswers.Opened().ToJsonString());
        Assert.Equal("""{"action":"saved"}""", WindowAnswers.Saved().ToJsonString());
        Assert.Equal("""{"action":"file"}""", WindowAnswers.File().ToJsonString());
    }

    [Fact]
    public void SaveAsNewDocumentIsNamedAfterTheOriginalNotTheCaller()
    {
        // The extension may send the window's title, which carries the copy's doc # and version.
        var request = SaveNewRequest.From(new JsonObject
        {
            ["name"] = "Lease agreement #1234 v2.1", ["formats"] = new JsonArray("odt", "docx"),
            ["path"] = "/copies/Lease agreement #1234 v2.1.docx", ["docNumber"] = 1234
        });

        Assert.Equal("Lease agreement", request.Name); // before the original is read
        Assert.Equal("Lease agreement", request.StartingName("Lease agreement.docx"));
        Assert.Equal("v1.2 draft", request.StartingName("v1.2 draft.odt"));
        Assert.Equal("Lease agreement", SaveNewRequest.From(new JsonObject
        {
            ["name"] = "Lease agreement #1234 v2.1 (read-only).docx", ["formats"] = new JsonArray("odt", "docx"), ["docNumber"] = 1234
        }).Name);
    }

    [Fact]
    public void ANewDocumentKeepsTheNameItCameWith()
    {
        var request = SaveNewRequest.From(new JsonObject { ["name"] = "Invoice #12 v2", ["formats"] = new JsonArray("odt", "docx") });

        Assert.Equal("Invoice #12 v2", request.Name); // only a copy's doc # and version are dropped
        Assert.Equal("Invoice #12 v2", request.StartingName(null));
    }

    [Theory]
    [InlineData("Lease.docx", "Lease")]
    [InlineData(" Lease.ODT ", "Lease")]
    [InlineData("Lease.pdf", "Lease.pdf")]
    [InlineData("Lease", "Lease")]
    public void ANameTypedWithAnOfferedFormatsExtensionLosesIt(string typed, string filed)
    {
        var request = SaveNewRequest.From(new JsonObject { ["formats"] = new JsonArray("odt", "docx") });

        Assert.Equal(filed, request.FinalName(typed));
    }

    [Fact]
    public void BeforeTheHelperIsReadyTheAppsOwnBoxOpens()
    {
        Assert.Equal("""{"action":"local"}""", WindowAnswers.NotReady("save-new").ToJsonString());
        Assert.Equal("""{"action":"local"}""", WindowAnswers.NotReady("open-dialog").ToJsonString());
        Assert.Equal("""{"action":"cancel"}""", WindowAnswers.NotReady("properties").ToJsonString());
        Assert.Contains("Sign in", WindowAnswers.NotReadyNotice("save-new"));
        Assert.Contains("on this computer", WindowAnswers.NotReadyNotice("save-new"));
    }
}
