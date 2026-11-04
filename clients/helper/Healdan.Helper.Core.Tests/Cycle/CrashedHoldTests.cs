// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// #136: a crashed copy keeps its check-out, which can end while its program is away, as when the web page's Unlock
/// releases it. Reopening the copy (the crash window's Reopen, Open from Healdan or a link) asks Healdan first. A
/// check-out that ended with nothing newer stored is taken again, so the copy's saves go up as before. Anything else
/// says at once that the copy can't be saved to Healdan, rather than at its first save, and the copy still reopens, so
/// Save as New Document can file it.
/// </summary>
public sealed class CrashedHoldTests : OfficeCycleTestBase
{
    /// <summary>Force Quit: Word goes, and its owner file stays beside the copy.</summary>
    private async Task CrashAsync(string path)
    {
        await WatchedAsync(path);
        Scripting.SetRunning(OfficeProgram.Word, false);
        await Wait.Until(() => Events.Crashes.Contains(path), "Crashed");
    }

    [Theory]
    [InlineData("unlocked")] // the web page's Unlock
    [InlineData("checked out in the web page")] // then checked out to the user there, on no device
    public async Task ReopenAfterTheCheckOutEndedChecksItOutAgain(string since)
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = since == "unlocked" ? null : new FakeHold(FakeApi.MyId, FakeApi.MyName, null, null, null));
        Scripting.WindowText = "the work Word recovered";

        await cycle.OpenAsync(5, false); // the crash window's Reopen, or Open from Healdan

        Assert.Equal(2, Server.Count("POST /documents/5/checkout"));
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        // Word brings back what it recovered, and the helper saves it to Healdan as after any crash.
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(["stored 1.1"], Server.Saves.Select(s => s.Outcome));
        Assert.Equal("the work Word recovered", Server.Doc(5).Content);
        Assert.Empty(Events.Ended);
        Assert.Empty(Events.Errors);
    }

    [Theory]
    [InlineData("someone else")]
    [InlineData("another computer")]
    public async Task ReopenAfterTheCheckOutWasTakenSaysAtOnceItCantBeSaved(string taker)
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        var hold = taker == "someone else" ? FakeApi.OtherPerson() : FakeApi.OtherDevice();
        Server.Change(5, d => d.Hold = hold);
        Scripting.WindowText = "the work Word recovered";

        await cycle.OpenAsync(5, false);

        // Said before anything is saved, and the copy still reopens.
        Assert.Equal([path], Events.Ended);
        Assert.Equal(0, Server.SavesReceived);
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        Assert.Equal(1, Server.Count("POST /documents/5/checkout")); // never taken from its holder
        Assert.Equal(hold, Server.Doc(5).Hold);
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
        Assert.Empty(Events.HeldElsewheres);
        // What Word recovered is saved back into the copy, and kept there for Save as New Document: nothing is sent.
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await Task.Delay(Quiet);
        Assert.Empty(Server.Saves);
        Assert.Equal("the work Word recovered", File.ReadAllText(path));
        Assert.True(Store.HasUnconfirmedChanges(path));
        Assert.Single(Events.Ended);
        Assert.Empty(Events.Errors);
    }

    [Theory]
    [InlineData(false)] // unlocked in the web page, and a newer version uploaded there
    [InlineData(true)] // a newer version uploaded in the web page while it stayed checked out here
    public async Task ReopenAfterNewerContentWasStoredSaysAtOnceItCantBeSaved(bool stillHeldHere)
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d =>
        {
            d.Hold = stillHeldHere ? d.Hold : null;
            d.Files["1.1"] = "newer, from the web page"u8.ToArray();
            d.Version = d.FileVersion = "1.1";
        });
        Scripting.WindowText = "the work Word recovered";

        await cycle.OpenAsync(5, false);

        Assert.Equal([path], Events.Ended);
        Assert.Equal(0, Server.SavesReceived);
        Assert.Equal(1, Server.Count("POST /documents/5/checkout")); // not taken again: the copy's changes can't go over 1.1
        Assert.Equal(stillHeldHere ? FakeApi.Here("Word") : null, Server.Doc(5).Hold);
        Assert.Equal(stillHeldHere, Store.Find(path)!.Value.Item2.CheckedOutHere); // still this computer's, so released later
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await Task.Delay(Quiet);
        Assert.Empty(Server.Saves);
        Assert.Equal("the work Word recovered", File.ReadAllText(path));
        Assert.Equal(1, Server.Count("GET /documents/5/content")); // the kept copy, never downloaded over

        // Closing it releases a check-out still held here, and keeps the copy.
        await LooksAsync(path);
        FakeScripting.Close(OfficeProgram.Word, path);
        await Wait.Until(() => cycle.OpenCopies().Count == 0, "the close done");
        Assert.Null(Server.Doc(5).Hold);
        Assert.Equal(stillHeldHere ? 1 : 0, Server.Count("DELETE /documents/5/checkout"));
        Assert.Equal("the work Word recovered", File.ReadAllText(path));
        Assert.Equal("newer, from the web page", Server.Doc(5).Content);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ReopenWhileHealdanCantBeReachedReopensAsBefore()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Down = true;
        Scripting.WindowText = "the work Word recovered";

        await cycle.OpenAsync(5, false);

        // The copy reopens, as it did before the helper asked: its save waits for Healdan, as any save does.
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        Assert.Equal(2, Server.Count("GET /documents/5")); // the first open's, and the one Healdan didn't answer
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        Server.Down = false;
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal("the work Word recovered", Server.Doc(5).Content);
        Assert.Empty(Events.Ended);
        Assert.Empty(Events.Errors);
    }
}
