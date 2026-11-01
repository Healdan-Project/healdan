using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// M3.5 spec §5.4: Word and Excel take the window's title from the file name, so after each confirmed save their copy
/// is saved under the confirmed version's name, unless the window has unsaved changes. PowerPoint's copies keep theirs.
/// </summary>
public sealed class OfficeRenameTests : OfficeCycleTestBase
{
    private const string WordsNotice =
        "Word will ask for access to Healdan Helper's Copies folder. Click Select…, go up one level, select Copies, and click Grant Access.";

    [Fact]
    public async Task AConfirmedSaveRenamesTheCopy()
    {
        var (cycle, path) = await OpenAsync();

        Save(path, "first draft");

        var renamed = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, renamed);
        Assert.Equal([(OfficeProgram.Word, path, renamed)], Scripting.Renames);
        Assert.True(File.Exists(renamed));
        Assert.False(File.Exists(path)); // Word leaves the file it saved from, and the helper deletes it
        var record = Store.Find(renamed)!.Value.Item2;
        Assert.Equal(("Lease agreement #5 v1.1.docx", "1.1", "Microsoft Office", true),
            (record.FileName, record.FileVersion, record.App, record.CheckedOutHere));
        Assert.Null(Store.Find(path));
        Assert.False(File.Exists(CopyPath(".Lease agreement #5 v1.0.docx.healdan.json")));
        Assert.Equal("Lease agreement #5 v1.1", cycle.StateOf(renamed).Title);
        Assert.False(cycle.StateOf(path).Known);
        Assert.Equal([renamed], cycle.OpenCopies().Select(c => c.Path));
        Assert.Equal("first draft", Server.Doc(5).Content);
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task TheRenamedFileIsntSentAgain()
    {
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        var renamed = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, renamed);
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");

        await Task.Delay(Quiet);

        // Word wrote the renamed file, which isn't the saved one byte for byte: it's the copy's confirmed content now.
        Assert.Equal(1, Server.SavesReceived);
        Assert.False(Store.HasUnconfirmedChanges(renamed));
        Assert.False(cycle.StateOf(renamed).Pending);
        Assert.Equal(0, cycle.Unsent());

        Save(renamed, "second draft");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Equal(["first draft", "second draft"], Server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task UnsavedChangesHoldTheRename()
    {
        Scripting.AnswerRenames(RenameResult.Renamed, RenameResult.Unsaved);
        var (cycle, path) = await OpenAsync();
        Save(path, "v1.1's content");
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);

        // Saved, then typed on before the save was confirmed: the window has unsaved changes, so it keeps its name.
        Save(v11, "v1.2's content");
        await Wait.Until(() => Scripting.Renames.Count == 2, "the second rename tried");
        await Wait.Until(() => Events.LastTitle(v11) == "Lease agreement #5 v1.2", "v1.2 confirmed");
        await LooksAsync(v11); // looked after under its name again
        Assert.True(File.Exists(v11));
        Assert.False(File.Exists(CopyPath("Lease agreement #5 v1.2.docx")));
        Assert.Equal("1.2", Store.Find(v11)!.Value.Item2.FileVersion);
        Assert.Equal("Lease agreement #5 v1.2", cycle.StateOf(v11).Title);

        // The next confirmed save renames it straight to its version.
        Save(v11, "v1.3's content");
        var v13 = CopyPath("Lease agreement #5 v1.3.docx");
        await RenamedTo(cycle, v13);
        Assert.Equal((v11, v13), (Scripting.Renames[^1].Path, Scripting.Renames[^1].NewPath));
        Assert.False(File.Exists(v11));
        Assert.Equal(["v1.1's content", "v1.2's content", "v1.3's content"], Server.Saves.Select(s => s.Content));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task AnEditInTheGapIsSent()
    {
        // Review Focus 1: an edit typed between Word's check for unsaved changes and its save is in the renamed file.
        Scripting.AnswerRenames(RenameResult.RenamedChanged);
        var (cycle, path) = await OpenAsync();

        Save(path, "first draft");

        // The renamed file goes up as the next snapshot, and the title reaches it.
        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal(2, Server.Saves.Count);
        Assert.Equal("first draft", Server.Saves[0].Content);
        Assert.Contains(FakeScripting.GapEdit, Server.Saves[1].Content);
        Assert.Equal("snapshot", Server.Saves[1].Kind);
        Assert.Equal("Lease agreement #5 v1.2", cycle.StateOf(v12).Title);
        Assert.False(Store.HasUnconfirmedChanges(v12));
        Assert.Contains(FakeScripting.GapEdit, Server.Doc(5).Content);
    }

    [Fact]
    public async Task ASaveJustBeforeTheRenameIsSent()
    {
        // Review Focus 1, the moment before the check: a save landing as the rename begins is in the renamed file, and
        // the rename must not take that file for the content Healdan confirmed.
        var once = 0;
        Scripting.BeforeRename = from =>
        {
            if (Interlocked.Exchange(ref once, 1) == 0)
                File.WriteAllText(from, "saved as the rename began");
        };
        var (cycle, path) = await OpenAsync();

        Save(path, "first draft");

        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal("first draft", Server.Saves[0].Content);
        Assert.StartsWith("saved as the rename began", Server.Saves[1].Content);
        Assert.Equal(2, Server.Saves.Count);
    }

    [Fact]
    public async Task NoRenameWhileANewerSaveWaits()
    {
        var (cycle, path) = await OpenAsync();
        Server.HoldSaves();
        Save(path, "A");
        await Wait.Until(() => Server.SavesReceived == 1, "A on its way up");
        Save(path, "B");
        await Wait.Until(() => Queued(cycle, path, "B"), "B waiting behind A");

        Server.OpenSaves();

        // A's confirmation leaves the name to B's, so the copy is renamed once, from the name it opened with.
        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal([(OfficeProgram.Word, path, v12)], Scripting.Renames);
        Assert.Equal(["A", "B"], Server.Saves.Select(s => s.Content));
        Assert.False(File.Exists(CopyPath("Lease agreement #5 v1.1.docx")));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task AnOwnerFileAtTheNewNameHoldsTheRename()
    {
        // The final review's Minor 1: Word's owner file at the next version's name, without its document, says Word may
        // be saving there. The rename doesn't start, as when the name is taken, and the copy stays as it is.
        var (cycle, path) = await OpenAsync();
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        var stray = FakeScripting.OwnerPath(OfficeProgram.Word, v11);
        File.WriteAllText(stray, "Word's");

        Save(path, "first draft");
        await Wait.Until(() => Events.LastTitle(path) == "Lease agreement #5 v1.1", "the save confirmed");
        await LooksAsync(path); // looked after under its name again

        Assert.Empty(Scripting.Renames);
        Assert.Equal("first draft", File.ReadAllText(path));
        Assert.False(File.Exists(v11));
        Assert.Equal([path], cycle.OpenCopies().Select(c => c.Path));
        Assert.Equal("1.1", Store.Find(path)!.Value.Item2.FileVersion);

        // Gone, the next confirmed save renames it.
        File.Delete(stray);
        Save(path, "second draft");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Equal([(OfficeProgram.Word, path, CopyPath("Lease agreement #5 v1.2.docx"))], Scripting.Renames);
        Assert.Equal(["first draft", "second draft"], Server.Saves.Select(s => s.Content));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task NoRenameWhileTheSameContentWaitsToGoUp()
    {
        // The rename's queued-save guard: A goes up, then the user saves B and A again before it's done, so a save of
        // A waits behind it. The file holds what Healdan confirmed, but the waiting save's own confirmation renames it.
        var (cycle, path) = await OpenAsync();
        Server.HoldSaves();
        Save(path, "A");
        await Wait.Until(() => Server.SavesReceived == 1, "A on its way up");
        Save(path, "B");
        await Wait.Until(() => Queued(cycle, path, "B"), "B waiting behind A");
        Save(path, "A");
        await Wait.Until(() => Queued(cycle, path, "A"), "A again in B's place");

        Server.OpenSaves();

        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal([(OfficeProgram.Word, path, v12)], Scripting.Renames); // once, from the name it opened with
        Assert.Equal(["A", "A"], Server.Saves.Select(s => s.Content));
        Assert.False(File.Exists(CopyPath("Lease agreement #5 v1.1.docx")));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task NoRenameWhileASaveAsNewVersionWaits()
    {
        // The rename's NextKind guard: Save as New Version while A goes up, before the version's save settles. The
        // version's confirmation renames the copy, once, to the version's name. The settle's clock is stopped meanwhile,
        // so the version is still only asked for when A's confirmation comes.
        var clock = new PausableClock();
        var (cycle, path) = await OpenAsync(timings: Fast with { Clock = clock });
        Server.HoldSaves();
        Save(path, "A");
        await Wait.Until(() => Server.SavesReceived == 1, "A on its way up");
        clock.Pause();
        cycle.MarkNextVersion(path);

        Server.OpenSaves();
        await Wait.Until(() => Events.LastTitle(path) == "Lease agreement #5 v1.1", "A confirmed");
        await Task.Delay(Quiet);
        Assert.Empty(Scripting.Renames);

        clock.Resume();
        var v20 = CopyPath("Lease agreement #5 v2.0.docx");
        await RenamedTo(cycle, v20);
        Assert.Equal([(OfficeProgram.Word, path, v20)], Scripting.Renames);
        Assert.Equal([("A", "snapshot"), ("A", "version")], Server.Saves.Select(s => (s.Content, s.Kind)));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ACloseJustAfterARenameIsSeen()
    {
        // The final review's Minor 4: the renamed copy's owner file is there as the close watcher takes it on, so its
        // going at the first look is a close, not something to wait for Word to quit over. Looks are slow here, so the
        // window closes, as Word moves the footer on, before the first.
        Scripting.OnSetDocId = path => FakeScripting.Close(OfficeProgram.Word, path);
        var (cycle, path) = await OpenAsync(timings: Fast with { LockPoll = TimeSpan.FromMilliseconds(400) });
        await WatchedAsync(path);
        Save(path, "first draft");

        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step, as the window closes");
        Assert.Equal(v11, Scripting.DocIds[0].Path);
        await Wait.Until(() => Server.Doc(5).Hold is null, "the renamed copy checked in");
        await Wait.Until(() => !File.Exists(v11), "the renamed copy deleted");
        Assert.Equal(1, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(["first draft"], Server.Saves.Select(s => s.Content));
        Assert.Empty(cycle.OpenCopies());
    }

    [Fact]
    public async Task PowerPointNeverRenames()
    {
        Server.Add(9, "Deck.pptx", "slides");
        var (cycle, path) = await OpenAsync(9, "Deck #9 v1.0.pptx");

        Save(path, "slides 2");
        await Wait.Until(() => Events.LastTitle(path) == "Deck #9 v1.1", "the first save");
        Save(path, "slides 3");
        await Wait.Until(() => Events.LastTitle(path) == "Deck #9 v1.2", "the second save");

        Assert.Empty(Scripting.Renames);
        Assert.Empty(Scripting.DocIds);
        Assert.Equal(["slides 2", "slides 3"], Server.Saves.Select(s => s.Content));
        Assert.True(File.Exists(path));
        Assert.Equal("Deck #9 v1.0.pptx", Store.Find(path)!.Value.Item2.FileName);
        Assert.Equal("Deck #9 v1.2", cycle.StateOf(path).Title);
        Assert.Equal([path], cycle.OpenCopies().Select(c => c.Path));
        Assert.Empty(Notifier.Notices); // nothing is saved into Copies on PowerPoint's behalf here
    }

    [Fact]
    public async Task WordsFooterMovesOnAfterTheRename()
    {
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");

        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the doc ID set");
        Assert.Equal([(v11, "#5 v1.1")], Scripting.DocIds);

        // A document without the property is left alone: nothing is added, and nothing else changes.
        Scripting.HasDocId = false;
        Save(v11, "second draft");
        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await Wait.Until(() => Scripting.DocIds.Count == 2, "the doc ID tried");
        Assert.Equal((v12, "#5 v1.2"), Scripting.DocIds[1]);
        await RenamedTo(cycle, v12);
        await Task.Delay(Quiet);
        Assert.Equal(2, Server.SavesReceived);
        Assert.False(Store.HasUnconfirmedChanges(v12));
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task TheCloseWatcherFollowsTheRename()
    {
        Scripting.RenameTakes = Fast.LockPoll * 12; // the old name's owner file is gone for many looks before Word answers
        var (cycle, path) = await OpenAsync();
        await WatchedAsync(path);
        Save(path, "first draft");
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);
        Assert.False(File.Exists(FakeScripting.OwnerPath(OfficeProgram.Word, path))); // Word took its owner file to the new name

        // The close watcher looks at the new name's owner file again and again; the old one's being gone isn't a close.
        await LooksAsync(v11);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(1, Server.SavesReceived);
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.Equal([v11], cycle.OpenCopies().Select(c => c.Path));

        FakeScripting.Close(OfficeProgram.Word, v11);

        // The store deletes the copy, then its record: waiting for the file alone could look between the two.
        await Wait.Until(() => !File.Exists(v11) && Store.All().Count == 0, "the renamed copy checked in and deleted, record and all");
        Assert.Null(Server.Doc(5).Hold);
        Assert.Equal(1, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(1, Server.SavesReceived);
        Assert.Empty(cycle.OpenCopies());
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ARestartFindsTheRenamedCopy()
    {
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);
        await Wait.Until(() => Scripting.DocIds.Count == 1, "the rename's last step");
        cycle.Dispose(); // the helper stops; Word keeps the window open

        var restarted = Cycle();
        await restarted.ResumeAsync();

        Assert.Equal([v11], restarted.OpenCopies().Select(c => c.Path));
        Assert.Empty(Events.Crashes);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Save(v11, "after the restart");
        await RenamedTo(restarted, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Equal(["first draft", "after the restart"], Server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task ARestartFinishesARenameItsRecordDidntFollow()
    {
        // The helper stopped while Word saved the copy under its new name, before the record followed it: the old
        // file is still there, without its owner file, and the renamed file has none.
        Scripting.AnswerRenames(RenameResult.Unsaved);
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        await Wait.Until(() => Scripting.Renames.Count == 1, "the rename held");
        await Wait.Until(() => !cycle.StateOf(path).Pending && cycle.Unsent() == 0, "the save done with");
        cycle.Dispose();
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await Scripting.RenameIfUnchangedAsync(OfficeProgram.Word, path, v11);

        var restarted = Cycle();
        await restarted.ResumeAsync();

        // The renamed file is the copy: it isn't checked in as if its window had closed, and as nothing says what
        // Healdan has of it, it goes up.
        Assert.Equal([v11], restarted.OpenCopies().Select(c => c.Path));
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Null(Store.Find(path));
        Assert.False(File.Exists(path));
        await RenamedTo(restarted, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Equal("first draft", Server.Saves[0].Content);
        Assert.StartsWith("first draft (as Lease agreement #5 v1.1.docx)", Server.Saves[1].Content);
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
    }

    [Fact]
    public async Task AFailedRenameIsTakenAsUnsaved()
    {
        // Word busy, or a dialog up: the copy keeps its name, the user isn't troubled, and the next save tries again.
        Scripting.FailNextRename(new OfficeScriptException("Word didn't answer within 20 seconds."));
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        await Wait.Until(() => Log.Contains("Word didn't answer within 20 seconds."), "the failure logged");
        await LooksAsync(path);
        Assert.True(File.Exists(path));
        Assert.Equal("Lease agreement #5 v1.1", cycle.StateOf(path).Title);
        Assert.Empty(Events.Errors);

        Save(path, "second draft");

        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Equal(["first draft", "second draft"], Server.Saves.Select(s => s.Content));
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
    }

    [Fact]
    public async Task ARenameThatFailedButHappenedIsFollowed()
    {
        // The script gave up (Grant File Access left open past its limit, say), but Word saved under the new name.
        Scripting.FailNextRename(new OfficeScriptException("osascript didn't answer within 330 seconds."), afterRenaming: true);
        Scripting.KeepsOldOwnerFile = true; // so no close follows it: the rename's own look after the failure must
        var (cycle, path) = await OpenAsync();
        await WatchedAsync(path);

        Save(path, "first draft");

        // The renamed file is the copy: nothing says what Healdan has of it, so it goes up, and its window isn't
        // taken for closed.
        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal("first draft", Server.Saves[0].Content);
        Assert.StartsWith("first draft (as Lease agreement #5 v1.1.docx)", Server.Saves[1].Content);
        Assert.All(Server.Saves, save => Assert.True(save.KeepCheckedOut));
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.False(File.Exists(path));
        Assert.Null(Store.Find(path));
        Assert.False(File.Exists(FakeScripting.OwnerPath(OfficeProgram.Word, path)));
    }

    [Fact]
    public async Task ARenameWordCarriesOutLaterIsFollowed()
    {
        // The script gave up and nothing was renamed yet; the user grants access later, and Word carries the Save As
        // out then. Its owner file leaves the old name, which isn't the window closing.
        Scripting.FailNextRename(new OfficeScriptException("osascript didn't answer within 330 seconds."));
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        await Wait.Until(() => Log.Contains("didn't answer within 330 seconds"), "the failure logged");
        await LooksAsync(path); // watched under its old name again

        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await Scripting.RenameIfUnchangedAsync(OfficeProgram.Word, path, v11);

        var v12 = CopyPath("Lease agreement #5 v1.2.docx");
        await RenamedTo(cycle, v12);
        Assert.Equal(0, Server.Count("DELETE /documents/5/checkout"));
        Assert.All(Server.Saves, save => Assert.True(save.KeepCheckedOut));
        Assert.StartsWith("first draft (as Lease agreement #5 v1.1.docx)", Server.Saves[1].Content);
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.False(File.Exists(path));

        // Its close is still a close.
        await LooksAsync(v12);
        FakeScripting.Close(OfficeProgram.Word, v12);
        await Wait.Until(() => !File.Exists(v12), "checked in");
        Assert.Null(Server.Doc(5).Hold);
    }

    [Fact]
    public async Task ACloseJustAfterALateRenameIsFollowedIsSeen()
    {
        // The final review's residuals: a copy followed to its next name is watched with its owner file's state, as after
        // any rename, so the window closing before the first look at the new name is a close. Otherwise the going of an
        // owner file never seen would wait for Word to quit. Looks are slow here, so the window closes, as the followed
        // copy's own rename finds it unsaved, before the first.
        Scripting.FailNextRename(new OfficeScriptException("osascript didn't answer within 330 seconds."));
        var (cycle, path) = await OpenAsync(timings: Fast with { LockPoll = TimeSpan.FromMilliseconds(600) });
        await WatchedAsync(path);
        Save(path, "first draft");
        await Wait.Until(() => Log.Contains("didn't answer within 330 seconds"), "the failure logged");
        await LooksAsync(path, 2); // watched under its old name again

        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await Scripting.RenameIfUnchangedAsync(OfficeProgram.Word, path, v11);
        Scripting.BeforeRename = from => { if (from == v11) FakeScripting.Close(OfficeProgram.Word, v11); };
        Scripting.AnswerRenames(RenameResult.Unsaved);

        await Wait.Until(() => Server.Saves.Count == 2, "the followed copy sent");
        await Wait.Until(() => Server.Doc(5).Hold is null, "the followed copy checked in");
        await Wait.Until(() => !File.Exists(v11), "the followed copy deleted");
        Assert.Equal(1, Server.Count("DELETE /documents/5/checkout"));
        Assert.StartsWith("first draft (as Lease agreement #5 v1.1.docx)", Server.Saves[1].Content);
        Assert.Empty(cycle.OpenCopies());
    }

    [Fact]
    public async Task TheFirstRenameIsAnnounced()
    {
        Server.Add(8, "Fees.xlsx", "sheet");
        var noticesAtRename = new List<int>();
        Scripting.BeforeRename = _ => noticesAtRename.Add(Notifier.Notices.Count);
        var (cycle, path) = await OpenAsync();

        Save(path, "first draft");
        var v11 = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, v11);
        Assert.Equal([WordsNotice], Notifier.Notices.Select(n => n.Message));
        Assert.Equal(1, noticesAtRename[0]); // told before Word asks

        Save(v11, "second draft");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.2.docx"));
        Assert.Single(Notifier.Notices); // not again for Word

        await cycle.OpenAsync(8, false);
        var xlsx = CopyPath("Fees #8 v1.0.xlsx");
        Save(xlsx, "sheet 2");
        await RenamedTo(cycle, CopyPath("Fees #8 v1.1.xlsx"));
        Assert.Equal([WordsNotice, WordsNotice.Replace("Word", "Excel")], Notifier.Notices.Select(n => n.Message));
        Assert.Equal(["Word", "Excel"], HelperSettings.Load(Paths).CopiesAnnounced);
        Assert.DoesNotContain(Scripting.DocIds, d => d.Path.EndsWith(".xlsx", StringComparison.Ordinal)); // the footer is Word's
    }
}
