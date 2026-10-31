using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// M3.5 spec §5.14 and Review Focus 3 and 4: after a crash, Word restores the copy's window at its own path as
/// "Recovered", where ⌘S opens Save As, so the helper saves it back over the copy itself. PowerPoint's close and reopen
/// after Save to Healdan (§5.5 step 7) is never a close.
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
        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        Assert.Equal((OfficeProgram.Word, new DocumentRef(path, null, null), path, (string?)null), Scripting.SavesAs[0]);
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
    public async Task ReopenFromTheCrashWindowStillSavesTheRecoveredWindowBack()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.WindowText = "the work Word recovered";

        // The crash window's Reopen opens the copy in Word, which restores its recovered window there.
        await cycle.OpenAsync(5, false);

        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["the work Word recovered"], Server.Saves.Select(s => s.Content));
        Assert.Equal(1, Server.Count("GET /documents/5/content")); // the kept copy, never downloaded over
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
    public async Task AFailedSaveBackLeavesTheCrashWindowsWayOut()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Scripting.FailNextSaveAs(new OfficeScriptException("Word has no document to save."));

        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Log.Contains("Word has no document to save."), "the failure logged");
        Assert.Empty(Server.Saves);
        Assert.Equal("downloaded", File.ReadAllText(path));
        Assert.Equal([path], cycle.OpenCopies().Select(c => c.Path));
        Assert.Empty(Events.Errors);

        // Send and Check In, from the crash window, still checks it in.
        await cycle.ClosedAsync(path);
        await Wait.Until(() => !File.Exists(path), "checked in");
        Assert.Null(Server.Doc(5).Hold);
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
