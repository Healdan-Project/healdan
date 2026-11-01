using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// M3.5 spec §5.14 and Review Focus 3 and 4: after a crash, Word restores the copy's window at its own path as
/// "Recovered", where ⌘S opens Save As, so the helper saves it back over the copy itself. It's looked for once Word runs
/// again, as Word 16.114 never wrote the owner file the crash left again (the owner's walkthrough), and the crash window's
/// Reopen starts Word rather than handing it the file, which opened a second window beside the recovered one.
/// PowerPoint's close and reopen after Save to Healdan (§5.5 step 7) is never a close.
/// </summary>
public sealed class OfficeCrashTests : OfficeCycleTestBase
{
    /// <summary>Force Quit with unsaved work: Word goes, and its owner file stays beside the copy.</summary>
    private async Task CrashAsync(string path)
    {
        await WatchedAsync(path);
        Scripting.SetRunning(OfficeProgram.Word, false);
        await Wait.Until(() => Events.Crashes.Contains(path), "Crashed");
        Assert.True(File.Exists(FakeScripting.OwnerPath(OfficeProgram.Word, path)));
    }

    [Fact]
    public async Task WordsRecoveredWindowIsSavedBack()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Assert.Empty(Scripting.SavesAs);

        Scripting.WindowText = "the work Word recovered";
        var crashLeft = File.GetLastWriteTimeUtc(FakeScripting.OwnerPath(OfficeProgram.Word, path));
        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        Assert.Equal((OfficeProgram.Word, new DocumentRef(path, null, null), path, (string?)null), Scripting.SavesAs[0]);
        Assert.Equal(crashLeft, File.GetLastWriteTimeUtc(FakeScripting.OwnerPath(OfficeProgram.Word, path))); // seen without it
        // The change goes up as a save, and the title moves on.
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
        Assert.Equal("snapshot", Server.Saves[0].Kind);
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal([path], Events.Reopens);
        Assert.Single(Events.Crashes);

        // Looked after as any open copy: closing it checks it in.
        await LooksAsync(v11);
        FakeScripting.Close(OfficeProgram.Word, v11);
        await Wait.Until(() => !File.Exists(v11), "checked in");
        Assert.Null(Server.Doc(5).Hold);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ReopenFromTheCrashWindowStartsWordWhichBringsTheRecoveredWindowBack()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.WindowText = "the work Word recovered";

        // The crash window's Reopen starts Word, which restores its recovered window by itself.
        await cycle.OpenAsync(5, false);

        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
        Assert.Equal(1, Server.Count("GET /documents/5/content")); // the kept copy, never downloaded over
        Assert.Single(Scripting.Opened); // never handed the file
    }

    [Fact]
    public async Task ReopenBesideTheRecoveredWindowOpensNoSecondOne()
    {
        // The owner's walkthrough: Word had recovered the copy, Open from Healdan handed it the file, and Word opened it
        // again beside the recovered window, with the same name, so neither could be found to save back.
        var (cycle, path) = await OpenAsync(timings: Fast with { RecoveryWait = TimeSpan.FromSeconds(5) });
        await CrashAsync(path);
        Scripting.AnswerIsOpen(false); // the first look comes before Word lists it
        Scripting.Recover(OfficeProgram.Word, path);

        await cycle.OpenAsync(5, false);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Single(Scripting.Opened);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ReopenOpensTheCopyWhenWordBringsNothingBack()
    {
        // Word started with nothing to restore (AutoRecover hadn't written the work yet): the copy opens as last saved,
        // and is looked after as any open copy.
        var (cycle, path) = await OpenAsync(timings: Fast with { RecoveryWait = TimeSpan.FromMilliseconds(400) });
        await CrashAsync(path);
        Scripting.NeverListed = true;

        await cycle.OpenAsync(5, false);

        await Wait.Until(() => Scripting.Opened.Count == 2, "the copy opened as last saved");
        Assert.Equal(path, Scripting.Opened[1].Path);
        Assert.Empty(Scripting.SavesAs);
        Assert.False(cycle.IsCrashed(path));
        Scripting.NeverListed = false;
        await LooksAsync(path);
        FakeScripting.Close(OfficeProgram.Word, path);
        await Wait.Until(() => !File.Exists(path), "closing it checks it in");
        Assert.Null(Server.Doc(5).Hold);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ReopenWithWordRunningLooksBrieflyBeforeOpeningTheCopy()
    {
        // Word restores what it recovered as it starts: running without the copy, it's unlikely to bring it back, so the
        // copy isn't kept waiting for the whole look.
        var (cycle, path) = await OpenAsync(timings: Fast with { RecoveryWait = TimeSpan.FromSeconds(30) });
        await CrashAsync(path);
        Scripting.NeverListed = true;
        Scripting.SetRunning(OfficeProgram.Word, true); // started again for another document, without this one

        await cycle.OpenAsync(5, false);

        await Wait.Until(() => Scripting.Opened.Count == 2, "the copy opened as last saved", TimeSpan.FromSeconds(5));
        Assert.Empty(Scripting.SavesAs);
    }

    [Fact]
    public async Task AnOwnerFileWrittenAgainIsSeenToo()
    {
        // As Word did in the trial.
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.WindowText = "the work Word recovered";

        Scripting.RecoverWritingTheOwnerFile(OfficeProgram.Word, path);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
    }

    [Fact]
    public async Task SendAndCheckInIsntRefusedWhileWordRunsWithoutTheCopy()
    {
        // Word running is reason enough to look for the recovered window, but only finding it makes the copy open again.
        var (cycle, path) = await OpenAsync(timings: Fast with { RecoveryWait = TimeSpan.FromSeconds(30) });
        await CrashAsync(path);
        Scripting.NeverListed = true;
        Scripting.SetRunning(OfficeProgram.Word, true);

        await Wait.Until(() => Scripting.IsOpenAsked > 0, "looked for");

        Assert.False(cycle.IsOpenAgain(path));
    }

    [Fact]
    public async Task AStartThatFindsACrashWatchesForTheRecoveredWindow()
    {
        var (first, path) = await OpenAsync();
        first.Dispose();
        Scripting.SetRunning(OfficeProgram.Word, false); // Word went while the helper wasn't running

        var cycle = Cycle();
        await cycle.ResumeAsync();
        Assert.Equal([path], Events.Crashes);

        Scripting.WindowText = "the work Word recovered";
        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task TheStaleOwnerFileGoesWithTheRename()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.KeepsOldOwnerFile = true; // Word leaves the owner file the crash left

        Scripting.Recover(OfficeProgram.Word, path);

        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);
        Assert.All(CopyNames.OfficeOwnerNames(Path.GetFileName(path)), owner => Assert.False(File.Exists(CopyPath(owner)), owner));
        Assert.True(File.Exists(FakeScripting.OwnerPath(OfficeProgram.Word, v11))); // the renamed window's own stays
        await LooksAsync(v11);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
    }

    [Fact]
    public async Task AFailedSaveBackIsTriedAgain()
    {
        // Word busy, or a dialog in the way: the copy stays crashed, and the next look tries again.
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.FailNextSaveAs(new OfficeScriptException("Word has no document to save."));
        Scripting.WindowText = "the work Word recovered";

        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Log.Contains("Word has no document to save."), "the failure logged");
        await Wait.Until(() => Scripting.SavesAs.Count == 2, "the save back tried again");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
        Assert.Equal([path], Events.Reopens); // only once it worked
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task TheSaveBackWaitsUntilWordListsTheCopy()
    {
        // Word writes its owner file as it opens the document, which its scripting may not list yet: the front window
        // would be saved over the copy in its place.
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.AnswerIsOpen(false, false);
        Scripting.WindowText = "the work Word recovered";

        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        Assert.Equal(3, Scripting.IsOpenAsked); // twice not yet, then found by its path
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task ACopyWordDoesntListIsNeverSavedOver()
    {
        var timings = Fast with { RecoveryWait = TimeSpan.FromMilliseconds(400) };
        var (cycle, path) = await OpenAsync(timings: timings);
        await CrashAsync(path);
        Scripting.NeverListed = true;

        Scripting.Recover(OfficeProgram.Word, path);

        // No Save As at all, which would take Word's front window; the copy stays crashed, and is looked for again.
        await Wait.Until(() => Log.Contains("didn't find"), "the look given up, and logged");
        var asked = Scripting.IsOpenAsked;
        await Wait.Until(() => Scripting.IsOpenAsked > asked, "looked for again");
        Assert.Empty(Scripting.SavesAs);
        Assert.Empty(Events.Reopens);
        Assert.Empty(Server.Saves);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));

        // Once Word lists it, it's saved back.
        Scripting.NeverListed = false;
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal([path], Events.Reopens);
    }

    [Fact]
    public async Task TheCloseWatcherLetsGoDuringTheSaveBack()
    {
        // Word saving its window over its own file writes its owner file again, which is gone meanwhile.
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.SaveAsTakes = Fast.LockPoll * 12;

        Scripting.Recover(OfficeProgram.Word, path);

        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Single(Scripting.SavesAs);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Single(Server.Saves);
        Assert.True(Server.Saves[0].KeepCheckedOut);
    }

    [Fact]
    public async Task ACloseJustAfterPowerPointsReopenIsSeen()
    {
        // The final review's Minor 4: the presentation's owner file is back as the close watcher takes it on again, so
        // its going at the first look is a close. Looks are slow here, so the window closes before the first.
        Server.Add(9, "Deck.pptx", "slides");
        Scripting.ReopenGap = TimeSpan.FromMilliseconds(100);
        var (cycle, path) = await OpenAsync(9, "Deck #9 v1.0.pptx", Fast with { LockPoll = TimeSpan.FromMilliseconds(400) });
        await WatchedAsync(path);

        await cycle.ReopenOfficeAsync(path);
        FakeScripting.Close(OfficeProgram.PowerPoint, path);

        await Wait.Until(() => !File.Exists(path), "checked in and deleted");
        Assert.Null(Server.Doc(9).Hold);
        Assert.Equal(1, Server.Count("DELETE /documents/9/checkout"));
    }

    [Fact]
    public async Task PowerPointsReopenIsntAClose()
    {
        // Review Focus 3: the presentation's owner file goes for 2 s while PowerPoint closes it and opens it again.
        Server.Add(9, "Deck.pptx", "slides");
        var (cycle, path) = await OpenAsync(9, "Deck #9 v1.0.pptx");
        await WatchedAsync(path);

        await cycle.ReopenOfficeAsync(path);

        Assert.Equal([(OfficeProgram.PowerPoint, path)], Scripting.Saves);
        Assert.Equal([(OfficeProgram.PowerPoint, path)], Scripting.Reopens);
        await LooksAsync(path);
        Assert.Equal(0, Server.Count("DELETE /documents/9/checkout"));
        Assert.Equal(0, Server.SavesReceived);
        Assert.Empty(Events.Crashes);
        Assert.Equal(FakeApi.Here("PowerPoint"), Server.Doc(9).Hold);
        Assert.Equal([path], cycle.OpenCopies().Select(c => c.Path));

        // Its close is still a close.
        FakeScripting.Close(OfficeProgram.PowerPoint, path);
        await Wait.Until(() => !File.Exists(path), "checked in");
        Assert.Equal(1, Server.Count("DELETE /documents/9/checkout"));
        Assert.Empty(Events.Errors);
    }
}
