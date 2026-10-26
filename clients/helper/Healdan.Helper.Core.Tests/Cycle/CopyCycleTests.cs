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
    private readonly FakeOpener _opener = new();
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

    private CopyCycle Cycle(CycleTimings? timings = null, IOfficeApp? otherFiles = null)
    {
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var cycle = new CopyCycle(api, new CopyStore(Paths), _app, FakeApi.HereDevice, _events, timings ?? Fast, otherFiles);
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

    /// <summary>Whether the copy's save waiting for the one going up holds <paramref name="content"/>.</summary>
    private static bool Queued(CopyCycle cycle, string path, string content) =>
        string.Equals(cycle.QueuedHash(path), Hash(content), StringComparison.OrdinalIgnoreCase);

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
        Assert.Equal([cycle.StateOf(path)], cycle.OpenCopies());
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
        // The settle's clock is stopped while the file grows, and moves on half a settle between pieces: the file
        // never looks finished however long the runner stalls the writer, and the growth lasts 20 settles, so a
        // cycle that sent one settle after the first change would still fail.
        const int pieces = 40;
        var clock = new PausableClock();
        var (_, path) = await OpenAsync(timings: Fast with { Clock = clock });

        clock.Pause();
        await FakeApp.GrowAsync(path, pieces: pieces, every: TimeSpan.FromMilliseconds(50), between: () => clock.Advance(Fast.Settle / 2));
        clock.Resume();

        await Wait.Until(() => _server.Saves.Count == 1, "the save");
        await Task.Delay(Quiet);
        var save = Assert.Single(_server.Saves);
        Assert.Equal(string.Concat(Enumerable.Range(0, pieces).Select(i => $"piece {i};")), save.Content);
    }

    [Fact]
    public async Task TwoSavesInOrder()
    {
        var clock = new PausableClock();
        var (cycle, path) = await OpenAsync(timings: Fast with { Clock = clock });
        _server.HoldSaves();
        // A write in place empties the file first. The clock is stopped until A is in it, so the empty file can't
        // settle and go up in A's place.
        clock.Pause();
        FakeApp.Write(path, "A");
        clock.Resume();
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");

        // Each write waits until it's queued, so B settles alone and C replaces it, however slow the runner.
        FakeApp.Write(path, "B");
        await Wait.Until(() => Queued(cycle, path, "B"), "B waiting behind A");
        FakeApp.Write(path, "C");
        await Wait.Until(() => Queued(cycle, path, "C"), "C in place of B, which hasn't started");
        Assert.Equal(1, _server.SavesReceived); // nothing overtakes A
        _server.OpenSaves();

        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.2", "the title on the last save");
        await Task.Delay(Quiet);
        Assert.Equal(2, _server.SavesReceived); // A wasn't cancelled and sent again
        Assert.Equal(["A", "C"], _server.Saves.Select(s => s.Content));
        Assert.Equal(["stored 1.1", "stored 1.2"], _server.Saves.Select(s => s.Outcome));
        Assert.Equal("C", _server.Doc(5).Content);

        // Cmd+S twice within the settle time is one save, of the last. The settle's clock is stopped between the
        // two, so a stalled runner can't split them.
        clock.Pause();
        FakeApp.Write(path, "D");
        FakeApp.Write(path, "E");
        clock.Resume();
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
        var clock = new PausableClock();
        var cycle = Cycle(Fast with { Clock = clock });
        var temp = Path.Combine(_root, "new.odt");
        File.WriteAllText(temp, "filed");

        var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Letter.odt", new JsonObject(), false), null);

        Assert.True(state.Known);
        Assert.Equal(CopyPath("Letter #100 v1.0.odt"), state.Path);
        Assert.Equal("Letter #100 v1.0", state.Title);
        Assert.Contains("filed 100 checkOut=True", _server.Calls); // always checked out: it stays open
        Assert.NotNull(_server.Doc(100).Hold);

        // The extension stores the open document under the copy's name: its starting point, not a save. The clock is
        // stopped while it's written, so the emptied file can't settle first and be taken as the starting point.
        clock.Pause();
        FakeApp.Write(state.Path, "adopted by the app");
        clock.Resume();
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        await Wait.Until(() => !Store.HasUnconfirmedChanges(state.Path), "the app's write taken as the starting point");
        await Task.Delay(Quiet);
        Assert.Empty(_server.Saves);

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
        await Wait.Until(() => _app.LockSeen(edited) && _app.LockSeen(untouched), "both lock files seen");

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
        Assert.Empty(cycle.OpenCopies());
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
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
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
    public async Task VersionMovedAfterARenameKeepsTheNewName()
    {
        var (_, path) = await OpenAsync();
        // Renamed in the web page while it's open here: the version moves, the file doesn't.
        _server.Change(5, d =>
        {
            d.Name = "Signed lease.docx";
            d.Version = "1.1";
        });
        FakeApp.Write(path, "A");

        await Wait.Until(() => _events.LastTitle(path) is not null, "the resent save");
        var saves = _server.Saves;
        Assert.Equal(["version-moved", "stored 1.2"], saves.Select(s => s.Outcome));
        Assert.Equal(["Lease agreement.docx", "Signed lease.docx"], saves.Select(s => s.Name));
        Assert.Equal("Signed lease.docx", _server.Doc(5).Name); // the save didn't undo the rename
        Assert.Equal("Signed lease #5 v1.2", _events.LastTitle(path));
        Assert.Equal(CopyPath("Lease agreement #5 v1.0.docx"), path); // R19: the open copy keeps its name
        Assert.Equal("Signed lease.docx", Store.Find(path)!.Value.Item2.DocumentName);

        FakeApp.Write(path, "B");
        await Wait.Until(() => _events.LastTitle(path) == "Signed lease #5 v1.3", "the next save");
        Assert.Equal("Signed lease.docx", _server.Saves[^1].Name);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AMovedVersionStillHeldHereIsReleased(bool saveAsNewDocument)
    {
        var (cycle, path) = await OpenAsync();
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
        // Newer content was stored while the check-out stayed this computer's, as from the web page.
        _server.Change(5, d =>
        {
            d.Files["1.1"] = "from the web page"u8.ToArray();
            d.Version = d.FileVersion = "1.1";
        });
        FakeApp.Write(path, "mine");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        Assert.True(Store.Find(path)!.Value.Item2.CheckedOutHere); // still this computer's, so it's released later
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);

        if (saveAsNewDocument)
        {
            var temp = Path.Combine(_root, "mine.docx");
            File.WriteAllText(temp, "mine");
            var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Mine.docx", new JsonObject(), true), path);
            await Wait.Until(() => _server.Doc(5).Hold is null, "#5 released");
            FakeApp.Write(state.Path, "mine");
            await Wait.Until(() => !File.Exists(path), "the original copy deleted once the app has adopted the new one");
        }
        else
        {
            FakeApp.Close(path);
            await Wait.Until(() => _server.Doc(5).Hold is null, "released when the window closes");
            await Task.Delay(Quiet);
            Assert.Equal("mine", File.ReadAllText(path)); // kept: its changes never went up
            Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere);
        }
        Assert.Equal("from the web page", _server.Doc(5).Content);
        Assert.Equal("version-moved", Assert.Single(_server.Saves).Outcome);
        Assert.Equal(1, _server.Count("DELETE /documents/5/checkout"));
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task OpenFindsAKeptCopyBehindTheServer()
    {
        // The app crashed with a save not yet sent; then the web page released the document and newer content
        // was stored.
        var (first, path) = await OpenAsync(timings: Fast with { Settle = TimeSpan.FromSeconds(30) });
        FakeApp.Write(path, "my unsent edits");
        _app.Crash();
        first.Dispose();
        _server.Change(5, d =>
        {
            d.Hold = null;
            d.Files["1.1"] = "newer"u8.ToArray();
            d.Version = d.FileVersion = "1.1";
        });

        var cycle = Cycle();
        await cycle.OpenAsync(5, false);

        // The kept copy opens as it is, never downloaded over, and is marked ended rather than saved over 1.1.
        Assert.Equal([path, path], _app.Opened);
        Assert.Equal(1, _server.Count("GET /documents/5/content"));
        Assert.Contains(path, _events.Ended);
        Assert.Equal("my unsent edits", File.ReadAllText(path));
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold); // the open's check-out, held until the copy closes
        FakeApp.Write(path, "more edits");
        await Task.Delay(Fast.Settle * 4);
        Assert.Empty(_server.Saves);

        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
        FakeApp.Close(path);
        await Wait.Until(() => _server.Doc(5).Hold is null, "released when the window closes");
        Assert.Equal("more edits", File.ReadAllText(path));
        Assert.Equal("newer", _server.Doc(5).Content);
    }

    [Fact]
    public async Task ACloseWhoseFileCantBeReadYetStillChecksItIn()
    {
        var (cycle, path) = await OpenAsync(timings: Fast with { Settle = TimeSpan.FromSeconds(30) });
        FakeApp.Write(path, "the last save");
        // Something else holds the file exclusively, so it can't be read for now, whoever the tests run as.
        var holder = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
        try
        {
            Assert.ThrowsAny<IOException>(() => new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete));
            FakeApp.Close(path);
            await cycle.ClosedAsync(path); // the extension's close event: the close is under way now, not after a poll

            await Wait.Until(() => _events.Waits.Contains(1), "the check-in waiting to read the file");
            Assert.Equal(0, _server.Count("DELETE /documents/5/checkout")); // not released in place of the save
            Assert.Empty(_server.Saves);
        }
        finally
        {
            holder.Dispose();
        }

        await Wait.Until(() => !File.Exists(path), "the copy deleted after the check-in");
        var save = Assert.Single(_server.Saves);
        Assert.False(save.KeepCheckedOut);
        Assert.Equal("the last save", save.Content);
        Assert.Null(_server.Doc(5).Hold);
    }

    [Fact]
    public async Task ACloseAfterTheFileWentSendsTheWaitingSave()
    {
        var (cycle, path) = await OpenAsync();
        _server.HoldSaves();
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        // B waits behind A. Marked as a new version, its kind shows in the record once it's queued, so the test
        // knows B is waiting rather than guessing how long its settling takes.
        FakeApp.Write(path, "B");
        cycle.MarkNextVersion(path);
        await Wait.Until(() => Store.Find(path)?.Item2.PendingKind == "version", "B waiting behind A");
        File.Delete(path);
        FakeApp.Close(path);
        await cycle.ClosedAsync(path); // the window closes while A is still going up
        _server.OpenSaves();

        await Wait.Until(() => _server.Doc(5).Hold is null, "checked in");
        Assert.Equal(["A", "B"], _server.Saves.Select(s => s.Content));
        Assert.False(_server.Saves[1].KeepCheckedOut); // B went up with the check-in
        Assert.Equal("version", _server.Saves[1].Kind);
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        await Wait.Until(() => Store.Find(path) is null, "the record gone with the check-in");
    }

    [Fact]
    public async Task AHoldIsThisComputersByItsDeviceIdNotItsName()
    {
        var (_, path) = await OpenAsync();
        // version-moved, with the content unchanged, but held by another computer that has this one's name.
        _server.AnswerNextSaveVersionMoved(FakeApi.Here() with { DeviceId = "dev-twin" });
        FakeApp.Write(path, "A");

        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        await Task.Delay(Quiet);
        Assert.Equal("version-moved (canned)", Assert.Single(_server.Saves).Outcome); // not resent
        Assert.False(Store.Find(path)!.Value.Item2.CheckedOutHere); // and not this computer's to release
    }

    [Fact]
    public async Task AThrowingHandlerDoesntStopTheNextSave()
    {
        var (_, path) = await OpenAsync();
        _events.ThrowOnConfirmed = true;
        FakeApp.Write(path, "A");
        await Wait.Until(() => _events.LastTitle(path) is not null, "A confirmed");

        FakeApp.Write(path, "B");
        await Wait.Until(() => _events.LastTitle(path) == "Lease agreement #5 v1.2", "B confirmed");
        Assert.Equal(["A", "B"], _server.Saves.Select(s => s.Content));
        Assert.Contains(_events.Errors, e => e.Contains("couldn't show an update"));
        Assert.False(Store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public async Task ShutdownDuringADownloadReleasesTheCheckOut()
    {
        _server.HoldDownloads();
        var cycle = Cycle();
        var opening = cycle.OpenAsync(5, false);
        await Wait.Until(() => _server.Count("GET /documents/5/content") == 1, "the download under way");
        cycle.Dispose();
        await opening; // reported, not thrown

        await Wait.Until(() => _server.Doc(5).Hold is null, "released on the way out");
        Assert.Empty(_app.Opened);
        Assert.Empty(Store.All()); // released, so no record is left for ResumeAsync to release

        // A temporary file a download left is tidied at the next start.
        File.WriteAllText(Path.Combine(Paths.Copies, ".healdan-0123456789abcdef0123456789abcdef.part"), "half");
        await Cycle().ResumeAsync();
        await Wait.Until(() => CopiesFolder().Length == 0, "nothing left in the folder");
        Assert.Null(_server.Doc(5).Hold);
    }

    [Theory]
    [InlineData("someone else")]
    [InlineData("the user, in the web page")]
    public async Task ARecordOfAStoppedOpenNeverReleasesAnotherHold(string holder)
    {
        // An open stopped before its download was in place left its record, and the hold changed hands since.
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var doc = await api.CheckOutAsync(5, "LibreOffice");
        var (path, record, _) = Store.Create(doc, false, "LibreOffice");
        Store.Save(record with { CheckedOutHere = true });
        var hold = holder == "someone else" ? FakeApi.OtherPerson() : new FakeHold(FakeApi.MyId, FakeApi.MyName, null, null, null);
        _server.Change(5, d => d.Hold = hold);

        await Cycle().ResumeAsync();

        await Wait.Until(() => Store.Find(path) is null, "the record dropped");
        await Task.Delay(Quiet);
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(hold, _server.Doc(5).Hold);
        Assert.Empty(_events.Ended); // no copy, so nothing to keep or offer
        Assert.Empty(CopiesFolder());
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task AWindowClosedWhileARefusedSaveWasGoingUp(bool stillHeldHere)
    {
        var (cycle, path) = await OpenAsync();
        _server.HoldSaves();
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        FakeApp.Close(path);
        await cycle.ClosedAsync(path); // the window closes while A is going up; its check-in waits behind A
        if (stillHeldHere) // newer content was stored, with the check-out still this computer's: version-moved
            _server.Change(5, d =>
            {
                d.Files["1.1"] = "newer"u8.ToArray();
                d.Version = d.FileVersion = "1.1";
            });
        else // the web page released it: not-held
            _server.Change(5, d => d.Hold = null);
        _server.OpenSaves();

        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        if (stillHeldHere)
            await Wait.Until(() => _server.Doc(5).Hold is null, "released, as the window has closed");
        await Task.Delay(Quiet);
        Assert.Equal(stillHeldHere ? 1 : 0, _server.Count("DELETE /documents/5/checkout"));
        Assert.Single(_server.Saves);
        Assert.Equal("A", File.ReadAllText(path)); // kept: its changes didn't go up

        Assert.Empty(cycle.OpenCopies()); // the closed copy was let go, not left listed as still closing
    }

    [Fact]
    public async Task RestartAfterALostAnswerAndARenameKeepsTheNewName()
    {
        var (first, path) = await OpenAsync(timings: Fast with { RetryDelays = [TimeSpan.FromMinutes(5)] });
        _server.DropAnswers(1);
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.Saves.Count == 1, "A stored, its answer lost");
        first.Dispose();
        FakeApp.Write(path, "B"); // saved while the helper was down
        _server.Change(5, d =>
        {
            d.Name = "Signed lease.docx"; // and renamed in the web page meanwhile
            d.Version = "1.2";
        });

        await Cycle().ResumeAsync();

        await Wait.Until(() => _server.Doc(5).Content == "B" && _events.LastTitle(path) is not null, "B stored and confirmed");
        Assert.Equal(["stored 1.1", "repeat", "stored 1.3"], _server.Saves.Select(s => s.Outcome));
        Assert.Equal("Signed lease.docx", _server.Saves[^1].Name);
        Assert.Equal("Signed lease.docx", _server.Doc(5).Name);
        Assert.Equal("Signed lease #5 v1.3", _events.LastTitle(path));
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
        // As the extension asks: after the app has written the save.
        var (cycle, path) = await OpenAsync();
        FakeApp.Write(path, "A");
        var waited = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        Assert.Equal("Lease agreement #5 v1.1", waited.Title);
        Assert.False(waited.Pending);

        // A save of the same bytes has nothing to send: it answers at once.
        var started = DateTime.UtcNow;
        FakeApp.Write(path, "A");
        var unchanged = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        Assert.Equal("Lease agreement #5 v1.1", unchanged.Title);
        Assert.False(unchanged.Pending);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(5));

        // So does a call with no save at all.
        var none = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        Assert.Equal("Lease agreement #5 v1.1", none.Title);
        Assert.False(none.Pending);
        Assert.True(DateTime.UtcNow - started < TimeSpan.FromSeconds(5));
        Assert.Single(_server.Saves);
    }

    [Fact]
    public async Task SavedAfterTwoQuickSavesEndsOnTheLast()
    {
        // The final review's probe, in the extension's order: each /v1/saved comes after its save is written.
        var (cycle, path) = await OpenAsync();
        _server.HoldSaves();
        FakeApp.Write(path, "A");
        var first = cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10)); // the extension's /v1/saved after save A
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        FakeApp.Write(path, "B");
        var second = cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10)); // and after save B
        await Task.Delay(Fast.Settle * 3);
        _server.OpenSaves();

        var (afterA, afterB) = (await first, await second);
        Assert.Equal("Lease agreement #5 v1.2", afterB.Title); // what the extension sets as the title after save B
        Assert.Equal("Lease agreement #5 v1.2", afterA.Title); // and A's answer never puts it back a version
        Assert.False(afterA.Pending);
        Assert.False(afterB.Pending);
        Assert.Equal(["A", "B"], _server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task SavedAfterSaveAsNewVersionGivesTheVersion()
    {
        var (cycle, path) = await OpenAsync();
        _server.HoldSaves();
        FakeApp.Write(path, "A");
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        cycle.MarkNextVersion(path); // Save as New Version with nothing unsaved, while A goes up
        var answer = cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        _server.OpenSaves();

        Assert.Equal("Lease agreement #5 v2.0", (await answer).Title);
        Assert.Equal(["snapshot", "version"], _server.Saves.Select(s => s.Kind));
    }

    [Fact]
    public async Task SavedDuringAnOutageSaysItsPendingThenMovesOn()
    {
        var (cycle, path) = await OpenAsync();
        _server.Down = true;
        FakeApp.Write(path, "A");

        // The wait runs out while Healdan can't be reached: the answer says a save is still to go.
        var during = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromMilliseconds(400));
        Assert.Equal("Lease agreement #5 v1.0", during.Title);
        Assert.True(during.Pending);

        // The extension asks again while that's so; once Healdan is back, the answer moves the title on.
        _server.Down = false;
        var after = await cycle.WaitForConfirmationAsync(path, TimeSpan.FromSeconds(10));
        Assert.Equal("Lease agreement #5 v1.1", after.Title);
        Assert.False(after.Pending);
        Assert.False(cycle.StateOf(path).Pending);
    }

    [Fact]
    public async Task ASaveJustAfterAdoptionReachesHealdan()
    {
        // The final review's probe (C1): a save within the settle after storeAsURL used to fold into the baseline.
        var cycle = Cycle();
        var temp = Path.Combine(_root, "new.odt");
        File.WriteAllText(temp, "filed");
        var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Letter.odt", new JsonObject(), false), null);

        FakeApp.Write(state.Path, "adopted by the app"); // storeAsURL
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        var adopted = cycle.Adopted(state.Path); // the extension's /v1/adopted, before the window takes input again
        Assert.Equal("Letter #100 v1.0", adopted.Title);
        await Task.Delay(Fast.Settle / 2);
        FakeApp.Write(state.Path, "adopted, then the user typed and pressed Cmd+S"); // a save within the settle
        await Wait.Until(() => _events.LastTitle(state.Path) == "Letter #100 v1.1", "the save confirmed");
        await Wait.Until(() => _app.LockSeen(state.Path), "lock seen");
        FakeApp.Close(state.Path); // closed with nothing more to save
        await Wait.Until(() => _server.Doc(100).Hold is null, "#100 checked in or released");
        await Wait.Until(() => !File.Exists(state.Path), "the copy deleted once Healdan has it");
        await Task.Delay(Quiet);

        Assert.Equal("adopted, then the user typed and pressed Cmd+S", _server.Doc(100).Content);
        Assert.Equal(["adopted, then the user typed and pressed Cmd+S"], _server.Saves.Select(s => s.Content)); // the adoption isn't one
    }

    [Fact]
    public async Task ARestartWhileAdoptingDoesntSendTheAdoption()
    {
        var first = Cycle();
        var temp = Path.Combine(_root, "new.odt");
        File.WriteAllText(temp, "filed");
        var state = await first.AdoptNewAsync(temp, new NewDocument(9, "Letter.odt", new JsonObject(), false), null);
        FakeApp.Write(state.Path, "adopted by the app");
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        first.Dispose(); // stopped before the adoption had settled, and before /v1/adopted
        _app.Running = true;

        await Cycle().ResumeAsync();
        await Task.Delay(Fast.Settle * 4);

        Assert.Empty(_server.Saves); // not sent as an identical version
        Assert.False(Store.HasUnconfirmedChanges(state.Path));
        FakeApp.Write(state.Path, "a real save");
        await Wait.Until(() => _server.Doc(100).Content == "a real save", "the next save");
    }

    [Fact]
    public async Task ARestartWhileAdoptingStillSendsALaterSave()
    {
        // The helper stopped while adopting, and the user saved again a while later, while it was down: that save
        // may be anything, so it goes up rather than being taken for the adoption.
        var first = Cycle();
        var temp = Path.Combine(_root, "new.odt");
        File.WriteAllText(temp, "filed");
        var state = await first.AdoptNewAsync(temp, new NewDocument(9, "Letter.odt", new JsonObject(), false), null);
        first.Dispose();
        FakeApp.Write(state.Path, "saved while the helper was down");
        File.SetLastWriteTimeUtc(state.Path, DateTime.UtcNow + TimeSpan.FromMinutes(2));
        File.WriteAllText(FakeApp.LockPath(state.Path), "lock");
        _app.Running = true;

        await Cycle().ResumeAsync();

        await Wait.Until(() => _server.Doc(100).Content == "saved while the helper was down", "the save sent");
    }

    [Fact]
    public async Task KeptCopiesListsACopyWhoseChangesCantGoUp()
    {
        var (cycle, path) = await OpenAsync();
        Assert.Empty(cycle.KeptCopies()); // open: the cycle looks after it
        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());
        FakeApp.Write(path, "my edits");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        Assert.Empty(cycle.KeptCopies()); // still open in the app
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");

        FakeApp.Close(path);
        await Wait.Until(() => cycle.OpenCopies().Count == 0, "the window's close done");

        var kept = Assert.Single(cycle.KeptCopies());
        Assert.Equal((path, "Lease agreement #5 v1.0", 5L), (kept.Path, kept.Title, kept.DocNumber));

        // And after a restart, from the record alone.
        cycle.Dispose();
        var restarted = Cycle();
        await restarted.ResumeAsync();
        Assert.Equal(path, Assert.Single(restarted.KeptCopies()).Path);
    }

    [Fact]
    public async Task KeptCopiesLeavesOutWhatHealdanHas()
    {
        _server.Add(6, "Notes.odt", "notes");
        var (cycle, path) = await OpenAsync();
        await cycle.OpenAsync(6, true);
        FakeApp.Write(path, "A");
        await Wait.Until(() => _events.LastTitle(path) is not null, "A confirmed");

        Assert.Empty(cycle.KeptCopies()); // open copies, confirmed or not, and read-only ones
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
        FakeApp.Close(path);
        await Wait.Until(() => !File.Exists(path), "checked in and deleted");
        Assert.Empty(cycle.KeptCopies());
    }

    [Fact]
    public async Task AKeptCopyOpensWithNoCheckOutOrDownload()
    {
        var (cycle, path) = await OpenAsync();
        _server.Change(5, d => d.Hold = null); // the web page released it
        FakeApp.Write(path, "my edits");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
        FakeApp.Close(path);
        await Wait.Until(() => cycle.KeptCopies().Count == 1, "listed");
        var calls = _server.Calls.Count;

        cycle.OpenKept(path);

        Assert.Equal([path, path], _app.Opened);
        Assert.Equal(calls, _server.Calls.Count);
        Assert.Equal("my edits", File.ReadAllText(path));

        // Save as New Document from it files the changes, and the kept copy goes.
        var temp = Path.Combine(_root, "mine.docx");
        File.WriteAllText(temp, "my edits");
        var state = await cycle.AdoptNewAsync(temp, new NewDocument(9, "Mine.docx", new JsonObject(), true), path);
        FakeApp.Write(state.Path, "my edits");
        cycle.Adopted(state.Path);
        await Wait.Until(() => !File.Exists(path), "the kept copy deleted once the new one is adopted");
        Assert.Empty(cycle.KeptCopies());
        Assert.Null(_server.Doc(5).Hold); // nothing taken back
        Assert.Equal("downloaded", _server.Doc(5).Content);
    }

    [Fact]
    public async Task ACheckInAnsweredWithAnotherComputersHoldIsntTakenForThisOnes()
    {
        // The check-in is stored but its answer lost; before the retry, the user checks it out on another computer.
        var (cycle, path) = await OpenAsync(timings: Fast with { RetryDelays = [TimeSpan.FromMinutes(5)] });
        await Wait.Until(() => _app.LockSeen(path), "the lock file seen");
        FakeApp.Write(path, "the last save");
        await Wait.Until(() => _events.LastTitle(path) is not null, "the save confirmed");
        _server.DropAnswers(1);
        FakeApp.Write(path, "checked in");
        FakeApp.Close(path);
        await cycle.ClosedAsync(path);
        await Wait.Until(() => _server.Saves.Count == 2, "the check-in stored, its answer lost");
        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());

        cycle.RetryNow(); // the repeat's 201 shows the other computer's hold, with its device

        await Wait.Until(() => !File.Exists(path), "the copy deleted: Healdan has the check-in");
        await Task.Delay(Quiet);
        Assert.Equal(["stored 1.1", "stored 1.2", "repeat"], _server.Saves.Select(s => s.Outcome));
        Assert.Empty(_events.Ended); // the check-in went up: nothing is kept or offered
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.Equal(FakeApi.OtherDevice(), _server.Doc(5).Hold);
    }

    [Fact]
    public async Task ASecondOpenWhileTheFirstIsUnderWayDownloadsNothing()
    {
        _server.HoldDownloads();
        var cycle = Cycle();
        var first = cycle.OpenAsync(5, false);
        await Wait.Until(() => _server.Count("GET /documents/5/content") == 1, "the first download under way");

        var second = cycle.OpenAsync(5, false); // a double-click on Open in App
        Assert.Same(second, await Task.WhenAny(second, Task.Delay(TimeSpan.FromSeconds(5))));
        Assert.Equal(1, _server.Count("POST /documents/5/checkout"));
        Assert.Equal(1, _server.Count("GET /documents/5/content"));

        _server.OpenDownloads();
        await first;
        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
        Assert.Equal("downloaded", File.ReadAllText(_app.Opened[0]));
        Assert.Equal(1, _server.Count("GET /documents/5/content"));
        Assert.Empty(_events.Errors);

        // Once it's done, the document opens again as usual.
        await cycle.OpenAsync(5, false);
        Assert.Equal(2, _app.Opened.Count);
    }

    [Fact]
    public async Task ALinkOpensOnlyOfficeKinds()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenLinkAsync(7, false);

        Assert.Empty(_opener.Opened);
        Assert.Empty(_app.Opened);
        Assert.Equal(0, _server.Count("GET /documents/7/content"));
        Assert.Empty(CopiesFolder());
        Assert.Contains("Open it from the web page, or from Open from Healdan", Assert.Single(_events.Errors));

        // An office kind still opens from a link.
        await cycle.OpenLinkAsync(5, false);
        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
    }

    [Theory]
    [InlineData("Invoice.command")]
    [InlineData("Invoice.app")]
    [InlineData("Invoice.pkg")]
    [InlineData("Invoice.terminal")]
    [InlineData("Invoice.WebLoc")]
    [InlineData("Invoice.sh")]
    public async Task TheDefaultAppNeverGetsAFileThatCanRunPrograms(string name)
    {
        _server.Add(8, name, "#!/bin/sh");
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenAsync(8, false);

        Assert.Empty(_opener.Opened);
        Assert.Empty(_app.Opened);
        Assert.Equal(0, _server.Count("GET /documents/8/content")); // not even downloaded
        Assert.Empty(CopiesFolder());
        Assert.Contains("never opens", Assert.Single(_events.Errors));
    }

    [Fact]
    public async Task ACopyForTheDefaultAppIsQuarantined()
    {
        if (!OperatingSystem.IsMacOS())
            return; // Gatekeeper's attribute is macOS's
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenAsync(7, false);

        var path = Assert.Single(_opener.Opened);
        AssertReadOnlyFile(path);
        var xattr = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("xattr")
        {
            ArgumentList = { "-p", "com.apple.quarantine", path },
            RedirectStandardOutput = true,
            RedirectStandardError = true
        })!;
        var value = (await xattr.StandardOutput.ReadToEndAsync()).Trim();
        await xattr.WaitForExitAsync();
        Assert.Equal(0, xattr.ExitCode);
        Assert.Matches("^0081;[0-9a-f]{8};Healdan Helper;$", value);
    }

    [Fact]
    public async Task AFileNoOfficeAppEditsOpensReadOnlyInTheDefaultApp()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenAsync(7, false);

        var path = CopyPath("Signed NDA #7 v1.0 (read-only).pdf");
        Assert.Equal([path], _opener.Opened);
        Assert.Empty(_app.Opened);
        Assert.Empty(_events.Errors);
        Assert.DoesNotContain(_server.Calls, c => c.Contains("/checkout", StringComparison.Ordinal)); // spec 5.11: no check-out
        Assert.Null(_server.Doc(7).Hold);
        Assert.Equal("a pdf", File.ReadAllText(path));
        AssertReadOnlyFile(path);
        Assert.Empty(cycle.OpenCopies()); // it keeps no lock file and is never saved, so nothing watches it
    }

    [Fact]
    public async Task AnOfficeDocumentStillOpensInItsApp()
    {
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenAsync(5, false);

        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
        Assert.Empty(_opener.Opened);
        Assert.NotNull(_server.Doc(5).Hold);
    }

    [Fact]
    public async Task TheDefaultAppsCopiesGoAtTheNextStart()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        await Cycle(otherFiles: _opener).OpenAsync(7, false);
        var path = CopyPath("Signed NDA #7 v1.0 (read-only).pdf");
        Assert.True(File.Exists(path));

        _app.Running = true; // LibreOffice being open makes no difference: it never had this copy
        await Cycle(otherFiles: _opener).ResumeAsync();

        Assert.False(File.Exists(path)); // read-only and Healdan's own bytes: nothing is lost
        Assert.Empty(CopiesFolder()); // its record went with it
    }
}
