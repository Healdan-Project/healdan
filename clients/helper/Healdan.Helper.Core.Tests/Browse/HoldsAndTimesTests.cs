using System.Text.Json;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>What the windows say about who holds a document and when it was saved (spec §5.8, §5.10, §9.10).</summary>
public class HoldsAndTimesTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-25T12:00:00Z");

    private static HoldView Hold(long byId, string? byName, string? deviceName = null, string? app = null, string kind = "checkout") =>
        new(kind, new UserRef(byId, byName), DateTimeOffset.Parse("2026-10-25T11:59:00Z"),
            deviceName is null ? null : new DeviceView("dev-1", deviceName, app));

    [Fact]
    public void CheckedOutToYouOnMacBookOrToSomeoneElse()
    {
        Assert.Equal("checked out to you on MacBook", Holds.Text(Hold(1, "Robert Example", "Healdan Helper on MacBook", "LibreOffice"), me: 1));
        Assert.Equal("checked out to you", Holds.Text(Hold(1, "Robert Example"), me: 1)); // from the web page: no device
        Assert.Equal("checked out to Jane Smith", Holds.Text(Hold(2, "Jane Smith"), me: 1));
        Assert.Equal("checked out to someone", Holds.Text(new HoldView("checkout", null, null, null), me: 1));
        Assert.Equal("locked by Jane Smith", Holds.Text(Hold(2, "Jane Smith", kind: "lock"), me: 1));
        Assert.Equal("locked by you", Holds.Text(Hold(1, "Robert Example", kind: "lock"), me: 1));
        Assert.Equal("", Holds.Text(null, me: 1));
        Assert.True(Holds.ByMe(Hold(1, "Robert Example"), 1));
        Assert.False(Holds.ByMe(Hold(2, "Jane Smith"), 1));
        Assert.False(Holds.ByMe(Hold(1, "Robert Example"), null)); // not known yet
    }

    [Theory]
    [InlineData("Healdan Helper on MacBook", "MacBook")]
    [InlineData("Healdan Helper on Robert's iMac", "Robert's iMac")]
    [InlineData("Word on MacBook", "Word on MacBook")]
    [InlineData("Healdan Helper on ", "Healdan Helper on")]
    [InlineData("", "another computer")]
    public void DevicesAreCalledByTheirComputersName(string token, string shown) =>
        Assert.Equal(shown, DeviceNames.Short(token));

    [Fact]
    public void TheLastSaveIsTheVersionWhoseFileTheDocumentHas()
    {
        // Newest first: a change of properties (1.3, file 1.2), then the save 1.2.
        var versions = new List<VersionView>
        {
            new("1.3", "1.2", "snapshot", DateTimeOffset.Parse("2026-10-25T11:00:00Z"), null, null, "event.changed"),
            new("1.2", "1.2", "snapshot", DateTimeOffset.Parse("2026-10-25T10:42:00Z"), null, null, "event.checkedin"),
            new("1.1", "1.1", "snapshot", DateTimeOffset.Parse("2026-10-24T16:00:00Z"), null, null, "event.checkedin"),
        };

        Assert.Equal(DateTimeOffset.Parse("2026-10-25T10:42:00Z"), Versions.LastSaved(versions, "1.2"));
        Assert.Null(Versions.LastSaved(versions, "1.0")); // not on this page
        Assert.Equal(["1.2", "1.1"], Versions.Saves(versions).Select(v => v.Version));
    }

    [Fact]
    public void EditedReadsLikeTheMockup()
    {
        var utc = TimeZoneInfo.Utc;
        Assert.Equal("10:42", When.Short(DateTimeOffset.Parse("2026-10-25T10:42:00Z"), Now, utc));
        Assert.Equal("Monday", When.Short(DateTimeOffset.Parse("2026-10-19T10:42:00Z"), Now, utc));
        Assert.Equal("18 Oct", When.Short(DateTimeOffset.Parse("2026-10-18T10:42:00Z"), Now, utc));
        Assert.Equal("12 Oct 2025", When.Short(DateTimeOffset.Parse("2025-10-12T10:42:00Z"), Now, utc));
        Assert.Equal("", When.Short(null, Now, utc));
    }

    [Fact]
    public void EditedIsInTheComputersTimeZone()
    {
        var tokyo = TimeZoneInfo.FindSystemTimeZoneById("Asia/Tokyo");
        // 23:30 UTC on the 24th is 08:30 on the 25th in Tokyo: today there.
        Assert.Equal("08:30", When.Short(DateTimeOffset.Parse("2026-10-24T23:30:00Z"), Now, tokyo));
    }

    [Fact]
    public void ATimeInASentence()
    {
        var utc = TimeZoneInfo.Utc;
        Assert.Equal("today at 10:42", When.InSentence(DateTimeOffset.Parse("2026-10-25T10:42:00Z"), Now, utc));
        Assert.Equal("yesterday at 16:05", When.InSentence(DateTimeOffset.Parse("2026-10-24T16:05:00Z"), Now, utc));
        Assert.Equal("on 12 Oct at 09:00", When.InSentence(DateTimeOffset.Parse("2026-10-12T09:00:00Z"), Now, utc));
        Assert.Equal("on 12 Oct 2025 at 09:00", When.InSentence(DateTimeOffset.Parse("2025-10-12T09:00:00Z"), Now, utc));
    }

    [Fact]
    public void CheckedOutOnAnotherComputerInTheOwnersWords()
    {
        var utc = TimeZoneInfo.Utc;
        var warning = Warnings.HeldElsewhere(1234, "Healdan Helper on MacBook", "LibreOffice", DateTimeOffset.Parse("2026-10-25T10:42:00Z"), Now, utc);

        Assert.Equal("#1234 is checked out to you on MacBook (LibreOffice)", warning.Title);
        Assert.Equal("It was last saved to Healdan today at 10:42.", warning.LastSaved);
        Assert.Equal("Edits on MacBook that haven't reached Healdan won't be in this copy.", warning.Emphasis);
        Assert.Equal("If MacBook was offline or still has unsaved changes, editing here means working without them. "
            + "MacBook keeps its copy, and you can file it later as a new document.", warning.Detail);

        var unknown = Warnings.HeldElsewhere(1234, "Healdan Helper on MacBook", null, null, Now, utc);
        Assert.Equal("#1234 is checked out to you on MacBook", unknown.Title);
        Assert.Null(unknown.LastSaved);
    }

    [Fact]
    public void AfterACrash()
    {
        var crashed = Warnings.Crashed("Lease agreement #1234 v2.1", readOnly: false);
        Assert.Equal("Lease agreement #1234 v2.1 closed unexpectedly.", crashed.Headline);
        Assert.Equal("It's still checked out to you, and your local copy is kept.", crashed.Body);
        Assert.Equal("Lease agreement #1234 v2.1 closed unexpectedly. It's still checked out to you, and your local copy is kept.",
            crashed.ToString()); // the notification's sentence, as before

        var readOnly = Warnings.Crashed("Lease agreement #1234 v2.1 (read-only)", readOnly: true);
        Assert.Equal("Lease agreement #1234 v2.1 (read-only) closed unexpectedly.", readOnly.Headline);
        Assert.Equal("It was read-only, so nothing is lost.", readOnly.Body);
    }

    [Fact]
    public void ADocumentsSummaryForTheOpenWindow()
    {
        var doc = JsonSerializer.Deserialize<DocumentView>(Views.Doc(1234, "Lease agreement.docx", "2.1",
            hold: Views.Hold(1, "Robert Example", "MacBook", "LibreOffice")).ToJsonString(), new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        var properties = new Core.Forms.FormModel(Tests.Forms.Forms.Form());

        var rows = DocumentDetails.RowsFor(doc, properties, DateTimeOffset.Parse("2026-10-25T10:42:00Z"), me: 1, Now, TimeZoneInfo.Utc);

        Assert.Equal(
            ["Doc # = 1234 v2.1", "Matter = 998.1 – Healdan Demo", "Folder = Documents", "Author = Robert Example", "Class = Docs",
             "Subclass = Draft", "Last Saved = today at 10:42", "Checked Out = to you on MacBook"],
            rows.Select(r => $"{r.Label} = {r.Value}"));
    }
}
