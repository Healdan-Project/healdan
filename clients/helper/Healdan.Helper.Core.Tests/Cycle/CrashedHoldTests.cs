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
/// Save as New Document can file it. What Word brings back by itself is checked the same way before it's sent.
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

    [Fact]
    public async Task ReopenWhileHealdanDoesntAnswerReopensWithinTheWait()
    {
        // A server that takes the request and never answers holds the reopen up for HoldCheck, not the API's 10 minutes.
        var (cycle, path) = await OpenAsync(timings: Fast with { HoldCheck = TimeSpan.FromMilliseconds(300) });
        await CrashAsync(path);
        Server.Before = async (call, ct) =>
        {
            if (call == "GET /documents/5")
                await Task.Delay(Timeout.Infinite, ct);
            return null;
        };
        Scripting.WindowText = "the work Word recovered";

        var reopen = cycle.OpenAsync(5, false);

        Assert.Same(reopen, await Task.WhenAny(reopen, Task.Delay(TimeSpan.FromSeconds(10))));
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal("the work Word recovered", Server.Doc(5).Content);
        Assert.Empty(Events.Ended);
        Assert.Empty(Events.Errors);
    }

    [Theory]
    [InlineData(503, "unavailable")]
    [InlineData(429, "too-many-requests")]
    [InlineData(401, "unauthenticated")]
    public async Task ReopenWhileTheCheckOutCantBeTakenYetReopensAsBefore(int status, string code)
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = null);
        Server.Before = (call, _) => Task.FromResult<(int, string)?>(call == "POST /documents/5/checkout" ? (status, code) : null);

        await cycle.OpenAsync(5, false);

        // Reopened as before #136: nothing is said yet, and its first save finds out.
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        Assert.Equal(2, Server.Count("POST /documents/5/checkout"));
        Assert.Empty(Events.Ended);
        Assert.True(Store.Find(path)!.Value.Item2.CheckedOutHere);
        await Wait.Until(() => Events.Ended.Contains(path), "the first save refused");
        Assert.Equal(["not-held"], Server.Saves.Select(s => s.Outcome));
    }

    [Fact]
    public async Task ReopenWhenTheCheckOutIsTakenInTheMomentSaysAtOnceItCantBeSaved()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = null);
        Server.Before = (call, _) =>
        {
            if (call == "POST /documents/5/checkout")
                Server.Change(5, d => d.Hold = FakeApi.OtherPerson()); // between the read and the check-out
            return Task.FromResult<(int, string)?>(null);
        };

        await cycle.OpenAsync(5, false);

        Assert.Equal([path], Events.Ended);
        Assert.Equal(FakeApi.OtherPerson(), Server.Doc(5).Hold);
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await Task.Delay(Quiet);
        Assert.Equal(0, Server.SavesReceived);
        Assert.Empty(Events.Errors);
    }

    [Theory]
    [InlineData(404, "not-found")] // deleted in the web page
    [InlineData(403, "forbidden")] // no longer the user's to read
    public async Task ReopenOfADocumentThatCantBeReadSaysAtOnceItCantBeSaved(int status, string code)
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Before = (call, _) => Task.FromResult<(int, string)?>(call == "GET /documents/5" ? (status, code) : null);

        await cycle.OpenAsync(5, false);

        Assert.Equal([path], Events.Ended);
        Assert.Equal(1, Server.Count("POST /documents/5/checkout"));
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
        Assert.Equal([OfficeProgram.Word], Scripting.Started);
        await Wait.Until(() => Scripting.SavesAs.Count == 1, "the recovered window saved back");
        await Task.Delay(Quiet);
        Assert.Equal(0, Server.SavesReceived);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ReopenWhileASaveIsOnItsWayLeavesItToTheSavesAnswer()
    {
        // The copy's own save, still unanswered, moves the document on too, so it isn't taken for someone else's newer
        // content: its answer says what happened, as before #136.
        var (cycle, path) = await OpenAsync();
        await WatchedAsync(path);
        Server.HoldSaves();
        Save(path, "saved just before the crash");
        await Wait.Until(() => Server.SavesReceived == 1, "the save sent");
        await CrashAsync(path);

        await cycle.OpenAsync(5, false);

        Assert.Equal(1, Server.Count("GET /documents/5")); // the first open's: Healdan wasn't asked
        Assert.Empty(Events.Ended);
        Server.OpenSaves();
        await Wait.Until(() => Server.Saves.Count == 2, "the save and the recovered window stored");
        Assert.Equal(["stored 1.1", "stored 1.2"], Server.Saves.Select(s => s.Outcome));
        Assert.Empty(Events.Ended);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task ACheckInDuringTheCheckLeavesNoCheckOutBehind()
    {
        // Send and Check In while Reopen waits for Healdan: the check-out taken again after it is released, as nothing
        // else would release it.
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = null);
        Server.Before = async (call, _) =>
        {
            if (call == "POST /documents/5/checkout")
            {
                await cycle.ClosedAsync(path);
                await Wait.Until(() => cycle.OpenCopies().Count == 0, "the check-in done");
            }
            return null;
        };

        await cycle.OpenAsync(5, false);

        Assert.Equal(2, Server.Count("POST /documents/5/checkout"));
        Assert.Null(Server.Doc(5).Hold);
        Assert.Equal(1, Server.Count("DELETE /documents/5/checkout"));
        Assert.Empty(cycle.OpenCopies());
        Assert.Empty(Scripting.Started);
        Assert.Contains(Events.Errors, e => e.Contains("still being checked in"));
    }

    [Fact]
    public async Task WhatWordBringsBackByItselfTakesAnEndedCheckOutAgain()
    {
        var (cycle, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = null); // the web page's Unlock
        Scripting.WindowText = "the work Word recovered";

        Scripting.Recover(OfficeProgram.Word, path); // started again by the user, with no Reopen

        await RenamedTo(cycle, CopyPath("Lease agreement #5 v1.1.docx"));
        Assert.Equal(2, Server.Count("POST /documents/5/checkout"));
        Assert.Equal(FakeApi.Here("Word"), Server.Doc(5).Hold);
        Assert.Equal(["stored 1.1"], Server.Saves.Select(s => s.Outcome));
        Assert.Equal("the work Word recovered", Server.Doc(5).Content);
        Assert.Equal([path], Events.Reopens);
        Assert.Empty(Events.Ended);
        Assert.Empty(Events.Errors);
    }

    [Fact]
    public async Task WhatWordBringsBackByItselfIsntSentOnceSomeoneElseHasIt()
    {
        var (_, path) = await OpenAsync();
        await CrashAsync(path);
        Server.Change(5, d => d.Hold = FakeApi.OtherPerson());
        Scripting.WindowText = "the work Word recovered";

        Scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => Events.Ended.Contains(path), "said before anything is sent");
        await Wait.Until(() => Events.Reopens.Contains(path), "the recovered window saved back");
        await Task.Delay(Quiet);
        Assert.Equal(0, Server.SavesReceived);
        Assert.Equal(1, Server.Count("POST /documents/5/checkout"));
        Assert.Equal(FakeApi.OtherPerson(), Server.Doc(5).Hold);
        Assert.Equal("the work Word recovered", File.ReadAllText(path));
        Assert.Single(Events.Ended);
        Assert.Empty(Events.Errors);
    }
}
