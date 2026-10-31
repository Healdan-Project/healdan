using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// M3.5 spec §5.5: Save to Healdan and Save as New Document from Word, Excel and PowerPoint. The helper takes the
/// program's front window as <c>save-new</c> arrives, files the bytes the add-in sends with its ticket, and saves that
/// window as the new document's copy, checking by the add-in's mark, where the program shows it, that it's the window the
/// request came from (Review Focus 2), and by a fingerprint that nothing typed meanwhile becomes the copy's starting
/// point unsent (Review Focus 1).
/// </summary>
public sealed class OfficeFilingTests : OfficeCycleTestBase
{
    /// <summary>A Word document never saved: Word leaves its own name empty, and its window shows "Document1".</summary>
    private static readonly FrontDocument Document1 = new(null, "", "Document1");

    private static readonly FrontDocument Book1 = new(null, "Book1", "Book1");

    private static readonly FrontDocument Presentation1 = new(null, "Presentation1", "Presentation1");

    private const string Mark = "hd-mark-1";

    /// <summary>The fingerprint of the window as the tests leave it.</summary>
    private static readonly string Fingerprint = FakeScripting.FingerprintOf("the window as it is");

    private readonly FakeWindows _windows = new();
    private readonly PausableClock _clock = new();

    public OfficeFilingTests()
    {
        _windows.Answer = SaveAnswer("Memo", "docx");
        Scripting.Front = p => p switch
        {
            OfficeProgram.Excel => Book1,
            OfficeProgram.PowerPoint => Presentation1,
            _ => Document1,
        };
        Scripting.MarksShown = true; // Word and Excel show it within about a third of a second (spec §8.5)
        foreach (var program in Enum.GetValues<OfficeProgram>())
            Scripting.SetRunning(program, true);
    }

    /// <summary>The Save window's answer as the app makes it, with a temporary file the add-in has no use for.</summary>
    private JsonObject SaveAnswer(string name, string format) =>
        WindowAnswers.Save(9, name, format, new JsonObject { ["Client"] = "1" }, WindowAnswers.TempPath(Paths, format));

    private OfficeFiling Filing(CopyCycle cycle, TimeSpan? markWait = null) =>
        new(cycle, TestApps.Of(LibreOffice, microsoftOffice: Office), _windows, Notifier, Paths, new HelperLog(Paths.Log), _clock, markWait);

    /// <summary>Save to Healdan's window, answered Save: the ticket <c>file</c> names it by.</summary>
    private static async Task<string> TicketAsync(OfficeFiling filing, OfficeProgram p = OfficeProgram.Word, bool asNew = false)
    {
        var answer = await filing.SaveNewAsync(p, "", asNew);
        Assert.Equal("save", (string?)answer["action"]);
        return (string?)answer["ticket"] ?? throw new InvalidOperationException("no ticket");
    }

    private static byte[] Bytes(string text) => Encoding.UTF8.GetBytes(text);

    /// <summary>The temporary files left behind in the helper's Temp folder.</summary>
    private IEnumerable<string> Temporary => Directory.Exists(Paths.Temp) ? Directory.EnumerateFiles(Paths.Temp) : [];

    /// <summary>The other spelling of a path (<c>/tmp</c> and <c>/private/tmp</c>), as an app may report it.</summary>
    private static string Twin(string path) => path.StartsWith("/private/", StringComparison.Ordinal) ? path["/private".Length..] : "/private" + path;

    [Fact]
    public async Task SaveNewTakesTheFrontWindowFirst()
    {
        var windowUpWhenAsked = new List<bool>();
        Scripting.Front = _ =>
        {
            windowUpWhenAsked.Add(!_windows.Asked.IsEmpty);
            return Document1;
        };
        var filing = Filing(Cycle());

        var answer = await filing.SaveNewAsync(OfficeProgram.Word, "", asNew: false);

        Assert.Equal([false], windowUpWhenAsked); // taken once, before the Save window opened
        var (route, request) = Assert.Single(_windows.Asked);
        Assert.Equal("save-new", route);
        Assert.Equal("""{"app":"Word","name":"Document1","formats":["docx"],"path":null}""", request.ToJsonString());
        Assert.Equal("save", (string?)answer["action"]);
        Assert.False(string.IsNullOrEmpty((string?)answer["ticket"]));
        Assert.False(answer.ContainsKey("tempPath")); // the add-in sends the bytes, not a file
        Assert.Equal((9L, "Memo", "docx"), ((long?)answer["folderId"], (string?)answer["name"], (string?)answer["format"]));

        // Each program offers its own format only (spec §5.5 step 1).
        Scripting.Front = p => p == OfficeProgram.Excel ? new FrontDocument("/Users/x/Q3 budget.xlsx", "Q3 budget.xlsx", "Q3 budget.xlsx") : Presentation1;
        await filing.SaveNewAsync(OfficeProgram.Excel, null, asNew: false);
        await filing.SaveNewAsync(OfficeProgram.PowerPoint, null, asNew: false);
        Assert.Equal(["""{"app":"Excel","name":"Q3 budget","formats":["xlsx"],"path":null}""",
                """{"app":"PowerPoint","name":"Presentation1","formats":["pptx"],"path":null}"""],
            _windows.Asked.Skip(1).Select(a => a.Request.ToJsonString()));

        // Cancel is answered as it is, with no ticket.
        _windows.Answer = WindowAnswers.Cancel();
        Assert.Equal("""{"action":"cancel"}""", (await filing.SaveNewAsync(OfficeProgram.Word, "", asNew: false)).ToJsonString());
    }

    [Fact]
    public async Task FileFilesTheBytesAndSavesTheWindowAsTheCopy()
    {
        var cycle = Cycle();
        var filing = Filing(cycle);
        var ticket = await TicketAsync(filing);

        var state = await filing.FileAsync(ticket, Mark, Bytes("the window's bytes"));

        var copy = CopyPath("Memo #100 v1.0.docx");
        Assert.Contains("filed 100 checkOut=True", Server.Calls);
        Assert.Equal(("Memo.docx", "the window's bytes"), (Server.Doc(100).Name, Server.Doc(100).Content));
        // Fingerprinted as Save was clicked, before there was a mark, by the window save-new took.
        Assert.Equal([(OfficeProgram.Word, new DocumentRef(null, null, "Document1"))], Scripting.Fingerprints);
        Assert.Equal([(OfficeProgram.Word, new DocumentRef(null, Mark, "Document1"), copy, Fingerprint)], Scripting.SavesAs);
        Assert.Equal((true, 100L, "Memo #100 v1.0", "#100 v1.0", copy), (state.Known, state.DocNumber, state.Title, state.DocRef, state.Path));
        Assert.Equal(MicrosoftOfficeApp.AppName, Store.Find(copy)!.Value.Item2.App);
        Assert.Empty(Temporary); // the bytes' temporary file is gone
        Assert.Equal([copy], cycle.OpenCopies().Select(c => c.Path));
        Assert.Contains($"Filed #100 v1.0 from Word", Log);

        // The window's write under the copy's name is its starting point, not a save.
        await Task.Delay(Quiet);
        Assert.Empty(Server.Saves);
        Assert.False(Store.HasUnconfirmedChanges(copy));

        // The user's next save is one.
        Save(copy, "the next save");
        await Wait.Until(() => Server.Doc(100).Content == "the next save", "the next save sent");
        Assert.Equal("1.0", Server.Saves.Single().IfVersion);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task TheMarkIsWaitedForThenChecked()
    {
        Assert.Equal((TimeSpan.FromSeconds(3), TimeSpan.FromMilliseconds(250)), (OfficeFiling.MarkWait, OfficeFiling.MarkPoll));
        var cycle = Cycle();
        var filing = Filing(cycle);
        Scripting.MarksShown = false;
        Scripting.AnswerMarks(false, false, true); // shown at the third look, half a second in

        var first = await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("first"));

        Assert.Equal([Mark, Mark, Mark], Scripting.MarksAsked.Select(m => m.Mark));
        Assert.Equal(1, Scripting.FrontAsked); // save-new's only: the mark says which window it is
        Assert.Equal(new DocumentRef(null, Mark, "Document1"), Scripting.SavesAs.Single().Doc);
        Assert.Equal("Memo #100 v1.0", first.Title);

        // Review Focus 2: the user clicked into another document while the Save window was open, and its own mark is
        // there, not this request's. Nothing is filed, and nothing is saved.
        filing = Filing(cycle, markWait: TimeSpan.FromMilliseconds(500)); // so the mark that never shows isn't waited 3 s for
        var second = await TicketAsync(filing);
        Scripting.Front = _ => new FrontDocument(null, "", "Document2");
        var refused = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.FileAsync(second, "hd-mark-2", Bytes("second")));

        Assert.Equal("wrong-window", refused.Code);
        Assert.Single(Server.Calls, c => c.StartsWith("filed ", StringComparison.Ordinal));
        Assert.Single(Scripting.SavesAs);
        Assert.Contains("Nothing was filed: Word has another document in front now.", Notifier.All);
        Assert.Empty(Temporary);

        // The mark not shown in time, as once in the trial, with the window still in front: the window taken stands.
        Scripting.Front = _ => Document1;
        var third = await filing.FileAsync(await TicketAsync(filing), "hd-mark-3", Bytes("third"));

        Assert.Equal("Memo #101 v1.0", third.Title);
        Assert.Equal(new DocumentRef(null, "hd-mark-3", "Document1"), Scripting.SavesAs[^1].Doc);
    }

    [Fact]
    public async Task AWindowWithNoNameIsFiledOnlyByItsMark()
    {
        // The program gave no window name for a document never saved, so only the mark says which window it
        // is. Without it, nothing is filed: the front window's name and path would match the ticket's as null and null,
        // and Save As would find nothing once the document was filed.
        Scripting.Front = _ => new FrontDocument(null, "", null);
        Scripting.MarksShown = false;
        var cycle = Cycle();
        var filing = Filing(cycle, markWait: TimeSpan.FromMilliseconds(500));

        var refused = await Assert.ThrowsAsync<OfficeRefusedException>(async () => await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("bytes")));

        Assert.Equal("wrong-window", refused.Code);
        Assert.DoesNotContain(Server.Calls, c => c.StartsWith("filed ", StringComparison.Ordinal));
        Assert.Empty(Scripting.SavesAs);
        Assert.Contains("Nothing was filed: Word didn't say which window", Notifier.All);
        Assert.Empty(Temporary);

        // The mark shown, it finds the window.
        Scripting.MarksShown = true;
        var state = await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("bytes"));
        Assert.Equal("Memo #100 v1.0", state.Title);
        Assert.Equal(new DocumentRef(null, Mark, null), Scripting.SavesAs.Single().Doc);
    }

    [Fact]
    public async Task PowerPointWithoutAMarkUsesTheTicketsWindow()
    {
        Scripting.MarksShown = false; // PowerPoint never showed the mark to AppleScript (spec §8.5)
        Scripting.ReopenGap = TimeSpan.FromMilliseconds(100);
        _windows.Answer = SaveAnswer("Pitch", "pptx");
        var cycle = Cycle();
        var filing = Filing(cycle);

        var state = await filing.FileAsync(await TicketAsync(filing, OfficeProgram.PowerPoint), Mark, Bytes("slides"));

        var copy = CopyPath("Pitch #100 v1.0.pptx");
        Assert.Equal([(OfficeProgram.PowerPoint, new DocumentRef(null, Mark, "Presentation1"), copy, Fingerprint)], Scripting.SavesAs);
        Assert.Equal("Pitch #100 v1.0", state.Title);
        // Closed and opened again, so that its own Save saves in place (spec §5.5 step 7), which isn't a close.
        Assert.Equal([(OfficeProgram.PowerPoint, copy)], Scripting.Reopens);
        await LooksAsync(copy);
        Assert.Equal([copy], cycle.OpenCopies().Select(c => c.Path));
        Assert.NotNull(Server.Doc(100).Hold);

        // Another presentation in front by the time the bytes arrive: the ticket's window name decides, so it's refused.
        var second = await TicketAsync(filing, OfficeProgram.PowerPoint);
        Scripting.Front = _ => new FrontDocument(null, "Presentation2", "Presentation2");
        var refused = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.FileAsync(second, "hd-mark-2", Bytes("other slides")));
        Assert.Equal("wrong-window", refused.Code);
        Assert.Single(Scripting.SavesAs);
        Assert.Single(Server.Calls, c => c.StartsWith("filed ", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AnEditWhileFilingIsSent()
    {
        // Review Focus 1: a line typed after the add-in read the bytes and before the window was saved as the copy.
        Scripting.WindowText = "the bytes";
        Scripting.BeforeSaveAs = () => Scripting.WindowText = "the bytes, and a line typed while filing";
        var cycle = Cycle();
        var filing = Filing(cycle);

        var state = await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("the bytes"));

        Assert.Equal("Memo #100 v1.0", state.Title);
        await Wait.Until(() => Server.Doc(100).Content == "the bytes, and a line typed while filing", "the copy sent as a save");
        var save = Assert.Single(Server.Saves);
        Assert.Equal(("snapshot", "1.0"), (save.Kind, save.IfVersion));
        Assert.Equal("1.1", Server.Doc(100).Version);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx")); // and the title moves on, as after any save
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
    }

    [Fact]
    public async Task AnEditWhileFilingIsSentEvenIfItsWriteSettledFirst()
    {
        // As above, but the program answered slowly, so the cycle saw the write settle, and took it for the starting
        // point, before the answer said the window had changed.
        Scripting.WindowText = "the bytes";
        Scripting.BeforeSaveAs = () => Scripting.WindowText = "the bytes, and a line typed while filing";
        Scripting.AfterSaveAsWrite = path => Wait.Until(() => !Store.HasUnconfirmedChanges(path), "the write taken as the starting point");
        var cycle = Cycle();
        var filing = Filing(cycle);

        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("the bytes"));

        await Wait.Until(() => Server.Doc(100).Content == "the bytes, and a line typed while filing", "the copy sent as a save");
        Assert.Equal("1.1", Server.Doc(100).Version);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
    }

    [Fact]
    public async Task AnEditAsTheAddinReadsTheBytesIsSent()
    {
        // Review Focus 1, and the final review's Important 1: the fingerprint is of the window as Save was clicked in the
        // helper's Save window, before the add-in read the bytes, so a line typed while the bytes, the mark and the
        // request were on their way is sent as a save, not taken for the copy's starting point and lost at close.
        Scripting.WindowText = "the bytes";
        var cycle = Cycle();
        var filing = Filing(cycle);
        var ticket = await TicketAsync(filing);
        Scripting.WindowText = "the bytes, and a line typed as the add-in read them";

        await filing.FileAsync(ticket, Mark, Bytes("the bytes"));

        Assert.Equal(FakeScripting.FingerprintOf("the bytes"), Scripting.SavesAs.Single().Fingerprint);
        await Wait.Until(() => Server.Doc(100).Content == "the bytes, and a line typed as the add-in read them", "the edit sent as a save");
        var save = Assert.Single(Server.Saves);
        Assert.Equal(("snapshot", "1.0"), (save.Kind, save.IfVersion));
        Assert.Equal("1.1", Server.Doc(100).Version);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
    }

    [Fact]
    public async Task AWindowNotFingerprintedAtSaveIsSent()
    {
        // Nothing to compare the window with, so nothing shows the copy holds only what was filed: it goes up, at worst
        // as a version the same as the filed one.
        Scripting.WindowText = "the window, as Word saves it";
        Scripting.AnswerFingerprints((string?)null); // Word had no document to fingerprint as Save was clicked
        var cycle = Cycle();
        var filing = Filing(cycle);

        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("the window's bytes"));

        Assert.Null(Scripting.SavesAs.Single().Fingerprint);
        await Wait.Until(() => Server.Doc(100).Version == "1.1", "the copy sent as a save");
        Assert.Equal("the window, as Word saves it", Server.Doc(100).Content);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));

        // A fingerprint Word didn't answer is none either.
        Scripting.FailNextFingerprint(new OfficeScriptException("Word didn't answer (fake)."));
        _windows.Answer = SaveAnswer("Letter", "docx");

        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("the letter's bytes"));

        await Wait.Until(() => Server.Doc(101).Version == "1.1", "the second copy sent as a save");
        await RenamedTo(cycle, CopyPath("Letter #101 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 2, "the renames' last steps");
    }

    [Fact]
    public async Task AnEditDuringTheMarkWaitIsSent()
    {
        // Review Focus 1: the fingerprint is of the window as Save was clicked, before the helper waits for the mark, so a
        // line typed during that wait is sent as a save too, not taken for the copy's starting point and lost at close.
        Scripting.WindowText = "the bytes";
        Scripting.MarksShown = false;
        Scripting.AnswerMarks(false, true);
        Scripting.OnMarkLook = () => Scripting.WindowText = "the bytes, and a line typed while the mark was looked for";
        var cycle = Cycle();
        var filing = Filing(cycle);

        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("the bytes"));

        Assert.Equal(FakeScripting.FingerprintOf("the bytes"), Scripting.SavesAs.Single().Fingerprint);
        await Wait.Until(() => Server.Doc(100).Content == "the bytes, and a line typed while the mark was looked for", "the edit sent as a save");
        Assert.Equal("1.1", Server.Doc(100).Version);
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
    }

    [Fact]
    public async Task AFailedFilingLeavesTheWindowAlone()
    {
        Server.RefuseNextFiling();
        var cycle = Cycle();
        var filing = Filing(cycle);
        var ticket = await TicketAsync(filing);

        var failed = await Assert.ThrowsAsync<ApiException>(() => filing.FileAsync(ticket, Mark, Bytes("the window's bytes")));

        Assert.Equal("forbidden", failed.Code);
        Assert.Empty(Scripting.SavesAs); // the window stays as it was, unsaved, for the add-in to take its mark off
        Assert.Equal(["Memo.docx from Word couldn't be filed in Healdan: You can't add documents to this folder."],
            Notifier.Notices.Select(n => n.Message));
        Assert.Empty(Temporary);
        Assert.Empty(Store.All());
        Assert.Empty(cycle.OpenCopies());
    }

    [Fact]
    public async Task AWindowThatWontSaveAsTheCopyIsSaid()
    {
        Scripting.FailNextSaveAs(new OfficeScriptException("Word didn't answer (fake)."));
        var cycle = Cycle();
        var filing = Filing(cycle);

        await Assert.ThrowsAsync<OfficeScriptException>(async () => await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("filed")));

        Assert.Contains("#100 v1.0 was filed in Healdan, but Word didn't save the window as its copy", Notifier.All);
        Assert.Empty(Temporary);

        // Word carries the save out late, once the user has granted access: the window is the copy from then on.
        var copy = CopyPath("Memo #100 v1.0.docx");
        Save(copy, "the window, saved late");
        await Wait.Until(() => !Store.HasUnconfirmedChanges(copy), "the late write taken as the starting point");
        Save(copy, "the next save");
        await Wait.Until(() => Server.Doc(100).Content == "the next save", "the next save sent");
        await RenamedTo(cycle, CopyPath("Memo #100 v1.1.docx"));
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
    }

    [Fact]
    public async Task SaveAsNewDocumentReleasesTheOriginal()
    {
        var (cycle, original) = await OpenAsync();
        var name = Path.GetFileName(original);
        Scripting.Front = _ => new FrontDocument(original, name, name);
        _windows.Answer = SaveAnswer("Lease agreement (2)", "docx");
        var filing = Filing(cycle);

        var ticket = await TicketAsync(filing, asNew: true);

        var request = Assert.Single(_windows.Asked).Request;
        Assert.Equal((original, 5L), ((string?)request["path"], (long?)request["docNumber"]));
        Assert.Equal("Lease agreement", SaveNewRequest.From(request).Name); // named after the original, not its copy

        var state = await filing.FileAsync(ticket, Mark, Bytes("the window's bytes"));

        var copy = CopyPath("Lease agreement (2) #100 v1.0.docx");
        Assert.Equal(copy, state.Path);
        Assert.Equal(new DocumentRef(original, Mark, name), Scripting.SavesAs.Single().Doc);
        await Wait.Until(() => Server.Doc(5).Hold is null, "#5 released");
        await Wait.Until(() => !File.Exists(original), "the original copy deleted once the window is the new document's");
        await Task.Delay(Quiet);
        Assert.Null(Store.Find(original));
        Assert.Empty(Server.Saves); // nothing saved to #5, and the window's save as the new copy isn't a save
        Assert.Equal(1, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal("1.0", Server.Doc(5).Version);
        Assert.Equal([copy], cycle.OpenCopies().Select(c => c.Path));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task SaveAsNewDocumentFromAnotherWindowIsRefused()
    {
        // The add-in names one copy, and the program's front window is another: the window saved would be one, and the
        // copy released and deleted under its open window the other. Refused before the Save window opens.
        Server.Add(6, "Brief.docx", "brief");
        var (cycle, lease) = await OpenAsync();
        await cycle.OpenAsync(6, false);
        var brief = CopyPath("Brief #6 v1.0.docx");
        Scripting.Front = _ => new FrontDocument(lease, Path.GetFileName(lease), Path.GetFileName(lease));
        var filing = Filing(cycle);

        var refused = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.SaveNewAsync(OfficeProgram.Word, brief, asNew: true));

        Assert.Equal("wrong-window", refused.Code);
        Assert.Empty(_windows.Asked);

        // The front window's own copy named, or none: Save as New Document goes ahead.
        await filing.SaveNewAsync(OfficeProgram.Word, Twin(lease), asNew: true);
        await filing.SaveNewAsync(OfficeProgram.Word, "", asNew: true);
        Assert.Equal([lease, lease], _windows.Asked.Select(a => (string?)a.Request["path"]));
    }

    [Fact]
    public async Task TicketsAreOneUseAndExpire()
    {
        Assert.Equal(TimeSpan.FromMinutes(10), FilingTickets.Lifetime);
        var cycle = Cycle();
        var filing = Filing(cycle);
        var ticket = await TicketAsync(filing);
        await filing.FileAsync(ticket, Mark, Bytes("first"));

        var again = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.FileAsync(ticket, Mark, Bytes("again")));
        Assert.Equal("no-ticket", again.Code);

        var inTime = await TicketAsync(filing);
        _clock.Advance(TimeSpan.FromMinutes(10) - TimeSpan.FromSeconds(1));
        Assert.Equal("Memo #101 v1.0", (await filing.FileAsync(inTime, Mark, Bytes("in time"))).Title);

        var late = await TicketAsync(filing);
        _clock.Advance(TimeSpan.FromMinutes(10));
        var expired = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.FileAsync(late, Mark, Bytes("late")));
        Assert.Equal("no-ticket", expired.Code);
        var unknown = await Assert.ThrowsAsync<OfficeRefusedException>(() => filing.FileAsync("not-a-ticket", Mark, Bytes("?")));
        Assert.Equal("no-ticket", unknown.Code);

        Assert.Equal(["filed 100 checkOut=True", "filed 101 checkOut=True"], Server.Calls.Where(c => c.StartsWith("filed ", StringComparison.Ordinal)));
        Assert.Equal(2, Scripting.SavesAs.Count);
    }

    [Fact]
    public async Task CopyForPrefersAKnownUrl()
    {
        var (cycle, copy) = await OpenAsync();
        var filing = Filing(cycle);
        Scripting.Front = _ => null;

        // A url naming the copy wins, in any spelling of its path.
        Assert.Equal(copy, await filing.CopyForAsync(OfficeProgram.Word, copy));
        Assert.Equal(copy, await filing.CopyForAsync(OfficeProgram.Word, Twin(copy)));
        Assert.Equal(copy, await filing.CopyForAsync(OfficeProgram.Word, Path.Combine(Paths.Copies, "..", "Copies", Path.GetFileName(copy))));
        Assert.Equal(0, Scripting.FrontAsked);

        // A file elsewhere with the copy's name, a name no copy has now, or no url: the front window's copy, if it is one.
        var elsewhere = Path.Combine(Path.GetTempPath(), Path.GetFileName(copy));
        var stale = CopyPath("Lease agreement #5 v0.9.docx");
        Assert.Null(await filing.CopyForAsync(OfficeProgram.Word, elsewhere));
        Scripting.Front = _ => new FrontDocument(Twin(copy), Path.GetFileName(copy), Path.GetFileName(copy));
        foreach (var url in new[] { elsewhere, stale, "", null, "Lease agreement #5 v1.0.docx" })
            Assert.Equal(copy, await filing.CopyForAsync(OfficeProgram.Word, url));
        Scripting.Front = _ => new FrontDocument(elsewhere, Path.GetFileName(copy), Path.GetFileName(copy));
        Assert.Null(await filing.CopyForAsync(OfficeProgram.Word, ""));
        Scripting.Front = _ => Document1;
        Assert.Null(await filing.CopyForAsync(OfficeProgram.Word, ""));
    }

    [Fact]
    public async Task TheFirstSaveIntoCopiesIsAnnounced()
    {
        // Excel's first rename has told the user already; Word has never saved into Copies.
        Server.Add(8, "Fees.xlsx", "sheet");
        var cycle = Cycle();
        await cycle.OpenAsync(8, false);
        Save(CopyPath("Fees #8 v1.0.xlsx"), "sheet 2");
        await RenamedTo(cycle, CopyPath("Fees #8 v1.1.xlsx"));
        Assert.Equal([CopiesNotice.Message(OfficeProgram.Excel)], Notifier.Notices.Select(n => n.Message));
        var noticesAtSaveAs = new List<int>();
        Scripting.BeforeSaveAs = () => noticesAtSaveAs.Add(Notifier.Notices.Count);
        var filing = Filing(cycle);

        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("memo"));

        Assert.Equal([CopiesNotice.Message(OfficeProgram.Excel), CopiesNotice.Message(OfficeProgram.Word)],
            Notifier.Notices.Select(n => n.Message));
        Assert.Equal([2], noticesAtSaveAs); // told before Word asks

        _windows.Answer = SaveAnswer("Budget", "xlsx");
        await filing.FileAsync(await TicketAsync(filing, OfficeProgram.Excel), Mark, Bytes("budget"));
        _windows.Answer = SaveAnswer("Letter", "docx");
        await filing.FileAsync(await TicketAsync(filing), Mark, Bytes("letter"));

        Assert.Equal(2, Notifier.Notices.Count); // neither is told again
        Assert.Equal([2, 2, 2], noticesAtSaveAs);
    }
}
