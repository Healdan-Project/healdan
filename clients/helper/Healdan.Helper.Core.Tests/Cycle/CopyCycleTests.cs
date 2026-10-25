using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

public sealed class CopyCycleTests : IDisposable
{
    /// <summary>The trial's timings shortened, in the same order, so the tests run fast.</summary>
    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    /// <summary>Long enough after a save for a second, wrong, upload to have arrived.</summary>
    private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(600);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-cycle-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeApp _app = new();
    private readonly RecordingEvents _events = new();
    private readonly List<CopyCycle> _cycles = [];

    public CopyCycleTests() => _server.Add(5, "Lease agreement.docx", "downloaded");

    public void Dispose()
    {
        foreach (var cycle in _cycles)
            cycle.Dispose();
        _server.OpenSaves();
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private CopyStore Store => new(Paths);

    private CopyCycle Cycle(CycleTimings? timings = null)
    {
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var cycle = new CopyCycle(api, new CopyStore(Paths), _app, FakeApi.HereName, _events, timings ?? Fast);
        _cycles.Add(cycle);
        return cycle;
    }

    private async Task<(CopyCycle Cycle, string Path)> OpenAsync(long docNumber = 5, CycleTimings? timings = null)
    {
        var cycle = Cycle(timings);
        await cycle.OpenAsync(docNumber, false);
        Assert.Empty(_events.Errors);
        return (cycle, _app.Opened[^1]);
    }

    private string CopyPath(string name) => Path.Combine(Paths.Copies, name);

    private string[] CopiesFolder() =>
        Directory.Exists(Paths.Copies) ? Directory.GetFileSystemEntries(Paths.Copies) : [];

    private static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

    private static void AssertReadOnlyFile(string path)
    {
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead, File.GetUnixFileMode(path));
    }

    [Fact]
    public async Task OpenChecksOutDownloadsAndLaunches()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(5, false);

        var path = CopyPath("Lease agreement #5 v1.0.docx");
        Assert.Equal([path], _app.Opened);
        Assert.Equal("downloaded", File.ReadAllText(path));
        var record = Store.Find(path)!.Value.Item2;
        Assert.Equal((5L, "1.0", "1.0"), (record.DocNumber, record.Version, record.FileVersion));
        Assert.True(record.CheckedOutHere);
        Assert.False(record.ReadOnly);
        Assert.Equal("LibreOffice", record.App);
        Assert.False(Store.HasUnconfirmedChanges(path));
        Assert.Equal(FakeApi.Here("LibreOffice"), _server.Doc(5).Hold);
        Assert.Equal(1, _server.Count("POST /documents/5/checkout"));
        Assert.Equal(new CopyState(true, 5, "Lease agreement #5 v1.0", "#5 v1.0", false, path, null), cycle.StateOf(path));
        // The copy, its record and the app's lock file; no temporary file is left from the download.
        Assert.Equal(
            new[] { path, CopyPath(".Lease agreement #5 v1.0.docx.healdan.json"), FakeApp.LockPath(path) }.Order(),
            CopiesFolder().Order());
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task OpenReadOnlyWhenHeldByOther()
    {
        _server.Change(5, d => d.Hold = FakeApi.OtherPerson());
        var cycle = Cycle();
        await cycle.OpenAsync(5, false);

        var path = CopyPath("Lease agreement #5 v1.0 (read-only).docx");
        Assert.Equal([path], _app.Opened);
        Assert.Equal("downloaded", File.ReadAllText(path));
        AssertReadOnlyFile(path);
        Assert.Equal(0, _server.Count("POST /documents/5/checkout"));
        Assert.Equal(FakeApi.OtherPerson(), _server.Doc(5).Hold);
        var state = cycle.StateOf(path);
        Assert.True(state.ReadOnly);
        Assert.Equal("Pat Example", state.HoldBy);
        Assert.Equal("Lease agreement #5 v1.0 (read-only)", state.Title);
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
    }

    [Fact]
    public async Task OpenFailureReleases()
    {
        // The download breaks off after the check-out.
        _server.BreakDownloads = true;
        var cycle = Cycle();
        await cycle.OpenAsync(5, false);

        Assert.Equal(1, _server.Count("POST /documents/5/checkout"));
        Assert.Equal(1, _server.Count("DELETE /documents/5/checkout"));
        Assert.Null(_server.Doc(5).Hold);
        Assert.Empty(_app.Opened);
        Assert.Empty(CopiesFolder());
        Assert.Single(_events.Errors);

        // The app doesn't start.
        _server.BreakDownloads = false;
        _app.FailOpen = true;
        await cycle.OpenAsync(5, false);

        Assert.Equal(2, _server.Count("DELETE /documents/5/checkout"));
        Assert.Null(_server.Doc(5).Hold);
        Assert.Empty(CopiesFolder());
        Assert.Equal(2, _events.Errors.Count);
    }

    [Fact]
    public async Task SaveSendsSnapshotWithPreconditions()
    {
        var (_, path) = await OpenAsync();
        FakeApp.Write(path, "edited");

        await Wait.Until(() => _events.LastTitle(path) is not null, "the save's confirmation");
        await Task.Delay(Quiet);

        var save = Assert.Single(_server.Saves);
        Assert.Equal("snapshot", save.Kind);
        Assert.True(save.KeepCheckedOut);
        Assert.Equal("1.0", save.IfVersion);
        Assert.True(save.IfHeld);
        Assert.Equal("Lease agreement.docx", save.Name);
        Assert.True(Guid.TryParse(save.SaveId, out _));
        Assert.Equal("edited", save.Content);
        Assert.Equal("stored 1.1", save.Outcome);
        var (_, state) = Assert.Single(_events.Confirmations);
        Assert.Equal("Lease agreement #5 v1.1", state.Title);
        Assert.Equal("#5 v1.1", state.DocRef);

        // R19: the open copy keeps its name; the record moves on.
        Assert.Equal(CopyPath("Lease agreement #5 v1.0.docx"), path);
        Assert.Equal(path, state.Path);
        var record = Store.Find(path)!.Value.Item2;
        Assert.Equal(("1.1", "1.1"), (record.Version, record.FileVersion));
        Assert.Equal(Hash("edited"), record.ConfirmedHash, ignoreCase: true);
        Assert.Null(record.PendingKind);
        Assert.Equal(save.SaveId, record.LastSaveId);
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);
    }

    [Fact]
    public async Task WriteThenRenameSentOnce()
    {
        var (_, path) = await OpenAsync();
        FakeApp.WriteThenRename(path, "renamed into place");

        await Wait.Until(() => _server.Saves.Count == 1, "the save");
        await Task.Delay(Quiet);

        Assert.Equal("renamed into place", Assert.Single(_server.Saves).Content);
    }

    [Fact]
    public async Task PartialWriteNotSent()
    {
        // The file grows every 200 ms for 2 s; the settle time must be longer than the gaps.
        var timings = Fast with { Settle = TimeSpan.FromMilliseconds(600), SizePoll = TimeSpan.FromMilliseconds(100) };
        var (_, path) = await OpenAsync(timings: timings);

        await FakeApp.GrowAsync(path, pieces: 11, every: TimeSpan.FromMilliseconds(200));

        await Wait.Until(() => _server.Saves.Count == 1, "the save");
        await Task.Delay(Quiet * 2);
        var save = Assert.Single(_server.Saves);
        Assert.Equal(string.Concat(Enumerable.Range(0, 11).Select(i => $"piece {i};")), save.Content);
    }

    [Fact]
    public async Task TwoSavesInOrder()
    {
        var (_, path) = await OpenAsync();
        _server.HoldSaves();
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");

        FakeApp.Write(path, "B"); // waits behind A
        await Task.Delay(Fast.Settle * 3);
        FakeApp.Write(path, "C"); // replaces B, which hasn't started
        await Task.Delay(Fast.Settle * 3);
        Assert.Equal(1, _server.SavesReceived); // nothing overtakes A, and A isn't cancelled
        _server.OpenSaves();

        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.2", "the title on the last save");
        await Task.Delay(Quiet);
        Assert.Equal(["A", "C"], _server.Saves.Select(s => s.Content));
        Assert.Equal(["stored 1.1", "stored 1.2"], _server.Saves.Select(s => s.Outcome));
        Assert.Equal("C", _server.Doc(5).Content);

        // Cmd+S twice within the settle time is one save, of the last.
        FakeApp.Write(path, "D");
        await Task.Delay(Fast.Settle / 3);
        FakeApp.Write(path, "E");
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.3", "the title after a double save");
        await Task.Delay(Quiet);
        Assert.Equal(["A", "C", "E"], _server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task LostAnswerRetriedWithSameSaveId()
    {
        var (_, path) = await OpenAsync();
        _server.DropAnswers(1);
        FakeApp.Write(path, "edited");

        await Wait.Until(() => _events.LastTitle(path) is not null, "the confirmation");
        await Task.Delay(Quiet);

        var saves = _server.Saves;
        Assert.Equal(["stored 1.1", "repeat"], saves.Select(s => s.Outcome));
        Assert.Equal(saves[0].SaveId, saves[1].SaveId);
        Assert.Equal("edited", saves[1].Content);
        Assert.Equal(["1.0", "1.1"], _server.Doc(5).Files.Keys.Order());
        Assert.Equal("Lease agreement #5 v1.1", _events.LastTitle(path));
        Assert.Empty(_events.Ended);
        Assert.False(Store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public async Task ServerDownKeepsCopy()
    {
        var (_, path) = await OpenAsync();
        _server.Down = true;
        FakeApp.Write(path, "written while the server is down");

        await Wait.Until(() => _events.Waits.Contains(1), "Waiting(1)");
        await Task.Delay(Fast.RetryDelays[0] * 4);
        Assert.True(File.Exists(path));
        Assert.True(Store.HasUnconfirmedChanges(path));
        Assert.Equal("snapshot", Store.Find(path)!.Value.Item2.PendingKind);
        Assert.Empty(_server.Saves);

        _server.Down = false;
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.1", "the save once the server is back");
        await Wait.Until(() => _events.Waits.LastOrDefault() == 0, "Waiting(0)");
        Assert.Equal("written while the server is down", _server.Doc(5).Content);
        Assert.Single(_server.Saves);
        Assert.False(Store.HasUnconfirmedChanges(path));
        Assert.True(File.Exists(path));
    }

    [Fact]
    public async Task RestartResends()
    {
        var (first, path) = await OpenAsync();
        _server.Down = true;
        FakeApp.Write(path, "written before the restart");
        await Wait.Until(() => _events.Waits.Contains(1), "Waiting(1)");
        first.Dispose();
        var saveId = Store.Find(path)!.Value.Item2.LastSaveId;
        Assert.NotNull(saveId); // written to the record before the save was sent

        var second = Cycle();
        await second.ResumeAsync();
        await Task.Delay(Fast.RetryDelays[0] * 4);
        Assert.Empty(_server.Saves);
        Assert.True(File.Exists(path));

        _server.Down = false;
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.1", "the resent save");
        await Task.Delay(Quiet);
        var save = Assert.Single(_server.Saves);
        Assert.Equal(saveId, save.SaveId);
        Assert.Equal("written before the restart", save.Content);
        Assert.True(save.KeepCheckedOut); // the copy is still open in the app
        Assert.False(Store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public async Task RestartAfterALostAnswerStillSendsTheNewerSave()
    {
        // A save is stored but its answer lost, the helper stops before retrying, and the user saves again before
        // it restarts: the saveId it kept went up with other bytes, so the newer save must still reach Healdan.
        var (first, path) = await OpenAsync(timings: Fast with { RetryDelays = [TimeSpan.FromMinutes(5)] });
        _server.DropAnswers(1);
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.Saves.Count == 1, "A stored, its answer lost");
        first.Dispose();
        FakeApp.Write(path, "B");

        var second = Cycle();
        await second.ResumeAsync();

        await Wait.Until(() => _events.Confirmations.Any(c => c.Path == path), "B confirmed");
        await Task.Delay(Quiet);
        Assert.Equal("B", _server.Doc(5).Content);
        Assert.Empty(_events.Ended);
        Assert.False(Store.HasUnconfirmedChanges(path));
        Assert.Equal(_server.Doc(5).FileVersion, Store.Find(path)!.Value.Item2.FileVersion);
        Assert.Equal($"Lease agreement #5 v{_server.Doc(5).FileVersion}", _events.LastTitle(path));
    }

    [Fact]
    public async Task RestartReleasesAnOpenThatNeverFinished()
    {
        // The helper stopped after the check-out but before the download was in place: only the record is left.
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var doc = await api.CheckOutAsync(5, "LibreOffice");
        var (path, record, _) = Store.Create(doc, false, "LibreOffice");
        Store.Save(record with { CheckedOutHere = true });

        await Cycle().ResumeAsync();

        await Wait.Until(() => _server.Doc(5).Hold is null, "the check-out released");
        await Wait.Until(() => Store.Find(path) is null, "the record dropped");
        Assert.Empty(_server.Saves);
        Assert.Empty(CopiesFolder());
    }

    [Fact]
    public async Task AnOpenRefusedForTheTokenAsksEveryTime()
    {
        _server.Unauthorized = true;
        var cycle = Cycle();

        await cycle.OpenAsync(5, false);
        await cycle.OpenAsync(5, false);

        Assert.Equal(2, _events.SignIns);
        Assert.Empty(_app.Opened);
        Assert.Empty(CopiesFolder());
    }

    [Fact]
    public async Task AdoptIsNotASave()
    {
        var cycle = Cycle();
        var temp = Path.Combine(_root, "new.odt");
        File.WriteAllText(temp, "filed");

        var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Letter.odt", new JsonObject(), false), null);

        Assert.True(state.Known);
        Assert.Equal(CopyPath("Letter #100 v1.0.odt"), state.Path);
        Assert.Equal("Letter #100 v1.0", state.Title);
        Assert.Contains("filed 100 checkOut=True", _server.Calls); // always checked out: it stays open
        Assert.NotNull(_server.Doc(100).Hold);

        // The extension stores the open document under the copy's name: its starting point, not a save.
        FakeApp.Write(state.Path, "adopted by the app");
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        await Task.Delay(Fast.Settle * 4);
        Assert.Empty(_server.Saves);
        Assert.False(Store.HasUnconfirmedChanges(state.Path));

        FakeApp.Write(state.Path, "a real save");
        await Wait.Until(() => _server.Saves.Count == 1, "the first real save");
        Assert.Equal("1.0", _server.Saves[0].IfVersion);
        Assert.Equal("Letter.odt", _server.Saves[0].Name);
        await Wait.Until(() => _events.LastTitle(state.Path) == "Letter #100 v1.1", "its confirmation");
    }

    [Fact]
    public async Task SaveAsNewDocumentReleasesOriginal()
    {
        var (cycle, original) = await OpenAsync();
        var temp = Path.Combine(_root, "save-as.docx");
        File.WriteAllText(temp, "the window's content");

        var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Lease agreement (2).docx", new JsonObject(), true), original);

        Assert.Equal(CopyPath("Lease agreement (2) #100 v1.0.docx"), state.Path);
        await Wait.Until(() => _server.Doc(5).Hold is null, "#5 released");
        Assert.True(File.Exists(original), "the original copy stays until the app has adopted the new one");

        // The extension stores the window under the new copy's name, and LibreOffice moves its lock file with it.
        FakeApp.Write(state.Path, "the window's content");
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        FakeApp.Close(original);
        await Wait.Until(() => !File.Exists(original), "the original copy deleted");
        await Task.Delay(Quiet);

        Assert.Null(Store.Find(original));
        Assert.True(File.Exists(state.Path));
        Assert.NotNull(Store.Find(state.Path));
        Assert.Empty(_server.Saves); // nothing saved to #5, and the adoption isn't a save
        Assert.Equal(1, _server.Count("DELETE /documents/5/checkout"));
        Assert.Equal("1.0", _server.Doc(5).Version);
        Assert.NotNull(_server.Doc(100).Hold);
        Assert.Empty(_events.Ended);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task OpenHeldElsewhereAsks()
    {
        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());
        var cycle = Cycle();
        await cycle.OpenAsync(5, false);

        var asked = Assert.Single(_events.HeldElsewheres);
        Assert.Equal(new HeldElsewhereEvent(null, 5, "Healdan Helper on MacBook", "LibreOffice", _server.Doc(5).LastSaved), asked);
        Assert.Empty(_app.Opened);
        Assert.Equal(FakeApi.OtherDevice(), _server.Doc(5).Hold);
        Assert.Empty(CopiesFolder());

        await cycle.MoveHereAsync(5);

        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);
        var path = Assert.Single(_app.Opened);
        Assert.Equal(CopyPath("Lease agreement #5 v1.0.docx"), path);
        Assert.True(Store.Find(path)!.Value.Item2.CheckedOutHere);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task OlderVersionReadOnly()
    {
        _server.Change(5, d =>
        {
            d.Files["1.1"] = "v1.1"u8.ToArray();
            d.Files["1.2"] = "v1.2"u8.ToArray();
            d.Version = d.FileVersion = "1.2";
        });
        var cycle = Cycle();
        await cycle.OpenAsync(5, false, "1.1");

        var path = CopyPath("Lease agreement #5 v1.1 (read-only).docx");
        Assert.Equal([path], _app.Opened);
        Assert.Equal("v1.1", File.ReadAllText(path));
        AssertReadOnlyFile(path);
        Assert.Equal(0, _server.Count("POST /documents/5/checkout"));
        Assert.Equal(1, _server.Count("GET /documents/5/content?version=1.1"));
        Assert.Null(_server.Doc(5).Hold);
        var state = cycle.StateOf(path);
        Assert.True(state.ReadOnly);
        Assert.Equal("#5 v1.1", state.DocRef);
    }

    [Fact]
    public async Task NextVersion()
    {
        var (cycle, path) = await OpenAsync();
        cycle.MarkNextVersion(path);
        FakeApp.Write(path, "a new version");
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v2.0", "v2.0");

        // Without unsaved changes, the button sends the current file as a version.
        cycle.MarkNextVersion(path);
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v3.0", "v3.0");
        await Task.Delay(Quiet);

        Assert.Equal(["version", "version"], _server.Saves.Select(s => s.Kind));
        Assert.All(_server.Saves, s => Assert.True(s.KeepCheckedOut));
        Assert.Equal(["a new version", "a new version"], _server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task CloseChecksIn()
    {
        _server.Add(6, "Notes.odt", "notes");
        // A settle longer than the test: the last save is still settling when the window closes.
        var cycle = Cycle(Fast with { Settle = TimeSpan.FromSeconds(30) });
        await cycle.OpenAsync(5, false);
        await cycle.OpenAsync(6, false);
        var (edited, untouched) = (_app.Opened[0], _app.Opened[1]);
        await Task.Delay(Fast.LockPoll * 4); // the windows stay open long enough for their lock files to be seen

        FakeApp.Write(edited, "saved just before closing");
        FakeApp.Close(edited);
        FakeApp.Close(untouched);

        await Wait.Until(() => !File.Exists(edited) && !File.Exists(untouched), "both copies deleted");
        var save = Assert.Single(_server.Saves);
        Assert.Equal(5, save.DocNumber);
        Assert.False(save.KeepCheckedOut);
        Assert.True(save.IfHeld);
        Assert.Equal("saved just before closing", save.Content);
        Assert.Null(_server.Doc(5).Hold);
        Assert.Equal("saved just before closing", _server.Doc(5).Content);
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(1, _server.Count("DELETE /documents/6/checkout"));
        Assert.Null(_server.Doc(6).Hold);
        Assert.Empty(Store.All());
        Assert.Empty(CopiesFolder());
    }

    [Fact]
    public async Task AReadOnlyCopyGoesWhenItsWindowCloses()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(5, true);
        var path = Assert.Single(_app.Opened);
        Assert.False(_app.LockExists(path)); // so only the extension's close event can say it closed

        await cycle.ClosedAsync(path);

        await Wait.Until(() => !File.Exists(path), "the read-only copy deleted");
        Assert.Null(Store.Find(path));
        Assert.False(cycle.StateOf(path).Known);
        Assert.Equal(0, _server.Count("POST /documents/5/checkout"));
    }

    [Fact]
    public async Task CrashKeepsCopy()
    {
        var (cycle, path) = await OpenAsync();
        FakeApp.Write(path, "saved before the crash");
        await Wait.Until(() => _events.LastTitle(path) is not null, "the save");
        await Task.Delay(Fast.CrashPoll * 2); // the crash check has seen the app running
        _app.Crash();

        await Wait.Until(() => _events.Crashes.Contains(path), "Crashed");
        Assert.True(File.Exists(path));
        Assert.NotNull(Store.Find(path));
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));

        // Reopen opens the kept copy rather than downloading over it.
        await cycle.OpenAsync(5, false);
        Assert.Equal([path, path], _app.Opened);
        Assert.Equal(1, _server.Count("GET /documents/5/content"));

        // After a restart the helper offers it again, and reopens it the same way.
        _app.Crash();
        cycle.Dispose();
        var restarted = Cycle();
        await restarted.ResumeAsync();
        Assert.Equal(2, _events.Crashes.Count(p => p == path));
        await restarted.OpenAsync(5, false);
        Assert.Equal([path, path, path], _app.Opened);

        // An open that comes before the resume finds the kept copy in the folder.
        _app.Crash();
        restarted.Dispose();
        await Cycle().OpenAsync(5, false);
        Assert.Equal([path, path, path, path], _app.Opened);

        Assert.Equal(1, _server.Count("GET /documents/5/content"));
        Assert.Equal("saved before the crash", File.ReadAllText(path));
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);
        Assert.Single(Store.All());
        Assert.Empty(_events.Errors);
    }

    [Theory]
    [InlineData("held-elsewhere")]
    [InlineData("not-held")]
    public async Task HeldElsewhereKeepsCopy(string refusal)
    {
        var (_, path) = await OpenAsync();
        // Another of the user's computers took the check-out over, or the web page released it.
        _server.Change(5, d => d.Hold = refusal == "held-elsewhere" ? FakeApi.OtherDevice() : null);
        FakeApp.Write(path, "my edits");

        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        await Task.Delay(Quiet);
        Assert.Equal(refusal, Assert.Single(_server.Saves).Outcome);
        Assert.Equal("my edits", File.ReadAllText(path));
        Assert.True(Store.HasUnconfirmedChanges(path));
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
        Assert.Equal("downloaded", _server.Doc(5).Content);

        // Closing the window keeps it too: a check-in would be refused the same way.
        FakeApp.Close(path);
        await Task.Delay(Quiet);
        Assert.True(File.Exists(path));
        Assert.Single(_server.Saves);
    }

    [Fact]
    public async Task VersionMovedResentOnce()
    {
        var (_, path) = await OpenAsync();
        // Someone changed the properties in the web page: the version moves, the file doesn't.
        _server.Change(5, d => d.Version = "1.1");
        FakeApp.Write(path, "A");

        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.2", "the resent save");
        var saves = _server.Saves;
        Assert.Equal(["version-moved", "stored 1.2"], saves.Select(s => s.Outcome));
        Assert.Equal(["1.0", "1.1"], saves.Select(s => s.IfVersion));
        Assert.Equal(saves[0].SaveId, saves[1].SaveId);

        // Newer content was stored meanwhile (the file version moved): the save isn't sent over it.
        _server.Change(5, d =>
        {
            d.Files["1.3"] = "someone else's"u8.ToArray();
            d.Version = d.FileVersion = "1.3";
        });
        FakeApp.Write(path, "B");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        await Task.Delay(Quiet);
        Assert.Equal(3, _server.Saves.Count);
        Assert.Equal("version-moved", _server.Saves[2].Outcome);
        Assert.Equal("someone else's", _server.Doc(5).Content);
        Assert.Equal("B", File.ReadAllText(path));
        Assert.True(Store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public async Task UnauthorizedAsksSignIn()
    {
        var (cycle, path) = await OpenAsync();
        _server.Unauthorized = true;
        FakeApp.Write(path, "A");

        await Wait.Until(() => _events.SignIns > 0, "SignInNeeded");
        await Task.Delay(Fast.RetryDelays[0] * 5);
        Assert.Equal(1, _events.SignIns); // asked once, not at every retry
        Assert.Empty(_server.Saves);
        Assert.True(Store.HasUnconfirmedChanges(path));

        _server.Unauthorized = false; // signed in again
        cycle.RetryNow();
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.1", "the save after signing in");
        Assert.Equal("A", _server.Doc(5).Content);
    }

    [Fact]
    public async Task SavedAnswersOnceTheSaveIsConfirmed()
    {
        var (cycle, path) = await OpenAsync();
        var waiting = cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        FakeApp.Write(path, "A");
        Assert.Equal("Lease agreement #5 v1.1", (await waiting).Title);

        // A save of the same bytes has nothing to send: it answers once the file has settled.
        var started = DateTime.UtcNow;
        var unchanged = cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        FakeApp.Write(path, "A");
        Assert.Equal("Lease agreement #5 v1.1", (await unchanged).Title);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(5));

        // With no save at all it gives up after the time given, with the current state.
        var none = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromMilliseconds(300));
        Assert.Equal("Lease agreement #5 v1.1", none.Title);
        Assert.Single(_server.Saves);
    }
}
