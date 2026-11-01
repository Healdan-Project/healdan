using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Channel;

public sealed class ChannelHandlerTests : IDisposable
{
    private static readonly Uri Server = new("https://healdan.test");

    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-handler-").FullName;
    private readonly PausableClock _clock = new();
    private readonly List<string> _outside = [];
    private readonly FakeApi _server = new();
    private readonly FakeApp _app = new();
    private readonly FakeCoffeePdf _coffee = new();
    private readonly RecordingEvents _events = new();
    private readonly FakeWindows _windows = new();
    private readonly FakeNotifier _notifier = new();
    private readonly CopyCycle _cycle;
    private readonly ChannelHandler _handler;

    public ChannelHandlerTests()
    {
        _server.Add(5, "Lease agreement.docx", "downloaded");
        var paths = HelperPaths.For(_root);
        var log = new HelperLog(paths.Log);
        var api = new HealdanApi(Server, _server) { Token = "hd_test" };
        _cycle = new CopyCycle(api, new CopyStore(paths), TestApps.Of(_app, coffeePdf: _coffee.App), FakeApi.HereDevice, _events,
            Fast with { Clock = _clock });
        var links = new LinkHandler(_windows, _notifier, log);
        links.Ready(Server, _cycle).Wait();
        _handler = new ChannelHandler(_cycle, links, _windows, _notifier, log, paths, savedWait: TimeSpan.FromSeconds(10));
    }

    public void Dispose()
    {
        _cycle.Dispose();
        Directory.Delete(_root, true);
        foreach (var path in _outside)
        {
            if (Directory.Exists(path))
                Directory.Delete(path, true);
            else
                File.Delete(path);
        }
    }

    private string CopyPath(string name) => Path.Combine(HelperPaths.For(_root).Copies, name);

    private async Task<JsonObject> Call(string route, JsonObject body) =>
        (await _handler.HandleAsync(route, body))?.AsObject() ?? new JsonObject();

    private static JsonObject PathBody(string path) => new() { ["path"] = path };

    private CopyRecord RecordOf(string path) => new CopyStore(HelperPaths.For(_root)).Find(path)!.Value.Item2;

    /// <summary>
    /// The app saves the copy in place. That empties the file first, so the settle's clock is stopped until the content
    /// is in it: the empty file can't settle and go up in its place, however slow the runner.
    /// </summary>
    private void Save(string path, string content)
    {
        _clock.Pause();
        FakeApp.Write(path, content);
        _clock.Resume();
    }

    private bool Queued(string path, string content) =>
        string.Equals(_cycle.QueuedHash(path), Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(content))),
            StringComparison.OrdinalIgnoreCase);

    private async Task<string> OpenAsync()
    {
        await _cycle.OpenAsync(5, false);
        Assert.Empty(_events.Errors);
        var path = _app.Opened[^1];
        await Wait.Until(() => _app.LockSeen(path), "the close watcher sees the lock file");
        return path;
    }

    /// <summary>A temporary file as the extension writes the window's content for Save to Healdan.</summary>
    private static string TempFile(string content)
    {
        var path = Path.Combine(Path.GetTempPath(), $"healdan-save-{Guid.NewGuid():N}.odt");
        File.WriteAllText(path, content);
        return path;
    }

    private static JsonObject FileBody(string tempPath) => new()
    {
        ["tempPath"] = tempPath,
        ["folderId"] = 9,
        ["name"] = "Memo",
        ["format"] = "odt",
        ["properties"] = new JsonObject(),
        ["app"] = "LibreOffice",
        ["replaces"] = null
    };

    [Fact]
    public async Task StateOfAnOpenCopy()
    {
        var path = await OpenAsync();

        var state = await Call("state", PathBody(path));

        Assert.Equal(
            new JsonObject
            {
                ["known"] = true,
                ["docNumber"] = 5,
                ["title"] = "Lease agreement #5 v1.0",
                ["docRef"] = "#5 v1.0",
                ["readOnly"] = false,
                ["path"] = path,
                ["holdBy"] = null,
                ["pending"] = false
            }.ToJsonString(),
            state.ToJsonString());
    }

    [Fact]
    public async Task StateOfAnUnknownPath()
    {
        var state = await Call("state", PathBody(Path.Combine(_root, "elsewhere.odt")));

        Assert.Equal("""{"known":false}""", state.ToJsonString());
    }

    [Fact]
    public async Task SavedAnswersOnceTheSaveIsConfirmed()
    {
        var path = await OpenAsync();

        Save(path, "edited");
        var state = await Call("saved", PathBody(path));

        Assert.Equal("Lease agreement #5 v1.1", (string?)state["title"]);
        Assert.Equal("#5 v1.1", (string?)state["docRef"]);
        Assert.Equal("edited", _server.Doc(5).Content);
    }

    [Fact]
    public async Task SavedAfterTwoQuickSavesEndsOnTheLast()
    {
        var path = await OpenAsync();
        _server.HoldSaves();
        Save(path, "A");
        var afterA = Call("saved", PathBody(path));
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        Save(path, "B");
        var afterB = Call("saved", PathBody(path));
        await Wait.Until(() => Queued(path, "B"), "B waiting behind A"); // not a fixed time: a slow settle can't miss it
        _server.OpenSaves();

        foreach (var answer in new[] { await afterA, await afterB })
        {
            Assert.Equal("Lease agreement #5 v1.2", (string?)answer["title"]); // the title ends on the last save
            Assert.Equal("#5 v1.2", (string?)answer["docRef"]);
            Assert.False((bool?)answer["pending"]);
        }
        Assert.Equal("B", _server.Doc(5).Content);
    }

    [Fact]
    public async Task SavedAfterSaveAsNewVersionGivesTheVersion()
    {
        var path = await OpenAsync();
        _server.HoldSaves();
        Save(path, "A");
        var afterSave = Call("saved", PathBody(path));
        await Wait.Until(() => _server.SavesReceived == 1, "A on its way up");
        await Call("new-version", PathBody(path)); // with no unsaved changes: the file as it is goes as a version
        var afterVersion = Call("saved", PathBody(path));
        _server.OpenSaves();

        Assert.Equal("Lease agreement #5 v2.0", (string?)(await afterVersion)["title"]);
        Assert.Equal("Lease agreement #5 v2.0", (string?)(await afterSave)["title"]);
        Assert.Equal("2.0", _server.Doc(5).Version);
    }

    [Fact]
    public async Task SavedThroughAnOutageLongerThanItsWaitMovesTheTitleOnceHealdanIsBack()
    {
        var paths = HelperPaths.For(_root);
        var handler = new ChannelHandler(_cycle, new LinkHandler(_windows, _notifier, new HelperLog(paths.Log)), _windows, _notifier,
            new HelperLog(paths.Log), paths, savedWait: TimeSpan.FromMilliseconds(400)); // 120 s in the helper
        var path = await OpenAsync();
        _server.Down = true;
        Save(path, "written while Healdan is down");

        var during = (await handler.HandleAsync("saved", PathBody(path)))!.AsObject();
        Assert.Equal("#5 v1.0", (string?)during["docRef"]);
        Assert.True((bool?)during["pending"]); // the extension asks again

        _server.Down = false;
        var after = (await handler.HandleAsync("saved", PathBody(path)))!.AsObject();
        Assert.Equal("Lease agreement #5 v1.1", (string?)after["title"]);
        Assert.Equal("#5 v1.1", (string?)after["docRef"]);
        Assert.False((bool?)after["pending"]);
    }

    [Fact]
    public async Task AdoptedThenAQuickSaveReachesHealdan()
    {
        var filed = await Call("file", FileBody(TempFile("the window's content")));
        var path = (string)filed["path"]!;

        Save(path, "the window, as LibreOffice stored it"); // storeAsURL
        var adopted = await Call("adopted", PathBody(path));
        Assert.True((bool?)adopted["known"]);
        Assert.Equal("Memo #100 v1.0", (string?)adopted["title"]);
        Save(path, "typed straight after, and saved"); // within the settle of the adoption
        var saved = await Call("saved", PathBody(path));

        Assert.Equal("Memo #100 v1.1", (string?)saved["title"]);
        Assert.Equal("typed straight after, and saved", _server.Doc(100).Content);
        Assert.Equal(["typed straight after, and saved"], _server.Saves.Select(s => s.Content));
    }

    [Fact]
    public async Task AdoptedOfAFileThatIsntACopyIsUnknown()
    {
        var answer = await Call("adopted", PathBody(Path.Combine(_root, "elsewhere.odt")));

        Assert.Equal("""{"known":false}""", answer.ToJsonString());
    }

    [Fact]
    public async Task NewVersionSendsTheNextSaveAsAVersion()
    {
        var path = await OpenAsync();

        Assert.Equal("{}", (await Call("new-version", PathBody(path))).ToJsonString());
        Save(path, "the next save");

        await Wait.Until(() => _server.Saves.Any(s => s.Outcome.StartsWith("stored")), "the save");
        Assert.Equal("version", _server.Saves.Single().Kind);
        Assert.Equal("2.0", _server.Doc(5).Version);
    }

    [Fact]
    public async Task ClosedChecksTheCopyIn()
    {
        var path = await OpenAsync();
        FakeApp.Close(path);

        Assert.Equal("{}", (await Call("closed", PathBody(path))).ToJsonString());

        await Wait.Until(() => _server.Doc(5).Hold is null && !File.Exists(path), "the check-in and the copy deleted");
    }

    [Fact]
    public async Task FileAdoptsTheNewDocumentAndDeletesTheTempFile()
    {
        var temp = TempFile("the window's content");

        var state = await Call("file", FileBody(temp));

        Assert.True((bool?)state["known"]);
        Assert.Equal(100, (long?)state["docNumber"]);
        Assert.Equal("Memo #100 v1.0", (string?)state["title"]);
        Assert.Equal(CopyPath("Memo #100 v1.0.odt"), (string?)state["path"]);
        Assert.Equal("Memo.odt", _server.Doc(100).Name); // the format's extension is the name's
        Assert.Contains("filed 100 checkOut=True", _server.Calls);
        Assert.False(File.Exists(temp));
    }

    [Fact]
    public async Task FileFromCoffeePdfIsCoffeePdfsCopy()
    {
        var body = FileBody(TempFile("%PDF-1.7 the window's content"));
        body["format"] = "pdf";
        body["app"] = "CoffeePDF";

        var state = await Call("file", body);

        var path = (string)state["path"]!;
        Assert.Equal(CopyPath("Memo #100 v1.0.pdf"), path);
        Assert.Equal("CoffeePDF", RecordOf(path).App); // so the close watch and a restart look for CoffeePDF's lock
    }

    [Theory]
    [InlineData("LibreOffice")]
    [InlineData(null)]
    [InlineData("Pages")]
    [InlineData("Microsoft Office")]
    public async Task FileForAnyOtherAppIsLibreOffices(string? app)
    {
        var body = FileBody(TempFile("content"));
        if (app is null)
            body.Remove("app");
        else
            body["app"] = app;

        var state = await Call("file", body);

        Assert.Equal("LibreOffice", RecordOf((string)state["path"]!).App);
    }

    [Fact]
    public async Task SaveNewWithANullPathIsANewDocument()
    {
        await Call("save-new", new JsonObject
        {
            ["app"] = "CoffeePDF", ["name"] = "Scan", ["formats"] = new JsonArray("pdf"), ["path"] = null
        });

        var (_, request) = Assert.Single(_windows.Asked);
        Assert.Null(request["path"]);
        Assert.Null(request["docNumber"]);
    }

    [Fact]
    public async Task ACopyWhoseCheckOutEndedIsReadOnly()
    {
        var path = await OpenAsync();
        _server.Change(5, d => d.Hold = null); // the web page released it
        Save(path, "my edits");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");

        var state = await Call("state", PathBody(path));

        Assert.True((bool?)state["readOnly"]);
        Assert.Equal("Lease agreement #5 v1.0", (string?)state["title"]); // its name doesn't change
        Assert.Null((string?)state["holdBy"]);
        Assert.False(_cycle.StateOf(path).ReadOnly); // the helper's own windows read the record as before
    }

    [Fact]
    public async Task ACopyWhoseSavesCantGoOverNewerContentIsReadOnly()
    {
        var path = await OpenAsync();
        _server.Change(5, d =>
        {
            d.Files["1.1"] = "from the web page"u8.ToArray();
            d.Version = d.FileVersion = "1.1";
        });
        Save(path, "mine");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");

        var state = await Call("state", PathBody(path));

        Assert.True((bool?)state["readOnly"]); // still checked out here, but its saves don't go up
    }

    [Fact]
    public async Task AnOpenCopyIsntReadOnly()
    {
        var path = await OpenAsync();

        Assert.False((bool?)(await Call("state", PathBody(path)))["readOnly"]);
    }

    [Fact]
    public async Task ClosedForACopyTheHelperDoesntKnowIsLogged()
    {
        var stranger = CopyPath("Not a copy #9 v1.0.pdf");
        Directory.CreateDirectory(Path.GetDirectoryName(stranger)!);
        File.WriteAllText(stranger, "someone's file");

        Assert.Equal("{}", (await Call("closed", PathBody(stranger))).ToJsonString());

        await Wait.Until(() => File.ReadAllText(HelperPaths.For(_root).Log).Contains("Ignored /v1/closed"), "the log line");
        Assert.True(File.Exists(stranger));
    }

    [Fact]
    public async Task FileKeepsANameThatHasItsExtension()
    {
        var body = FileBody(TempFile("content"));
        body["name"] = "Memo.ODT";

        await Call("file", body);

        Assert.Equal("Memo.ODT", _server.Doc(100).Name);
    }

    [Fact]
    public async Task FileDeletesTheTempFileWhenFilingFails()
    {
        var temp = TempFile("the window's content");
        _server.Down = true;

        await Assert.ThrowsAnyAsync<Exception>(() => Call("file", FileBody(temp)));

        Assert.False(File.Exists(temp));
        Assert.Contains("Memo.odt", _notifier.All);
    }

    [Fact]
    public async Task FileNeverDeletesAFileOutsideTheTempFolderOrItsOwn()
    {
        var outside = Path.Combine(AppContext.BaseDirectory, $"healdan-keep-{Guid.NewGuid():N}.odt");
        _outside.Add(outside);
        File.WriteAllText(outside, "the user's own file");

        await Call("file", FileBody(outside));

        Assert.True(File.Exists(outside));
        Assert.Equal("the user's own file", File.ReadAllText(outside));
    }

    [Fact]
    public async Task FileNeverFollowsALinkOutOfTheTempFolder()
    {
        if (OperatingSystem.IsWindows())
            return;
        var outsideFolder = Path.Combine(AppContext.BaseDirectory, $"healdan-keep-{Guid.NewGuid():N}");
        _outside.Add(outsideFolder);
        Directory.CreateDirectory(outsideFolder);
        var target = Path.Combine(outsideFolder, "Precious.odt");
        File.WriteAllText(target, "the user's own file");
        var link = Path.Combine(Path.GetTempPath(), $"healdan-link-{Guid.NewGuid():N}");
        _outside.Add(link);
        Directory.CreateSymbolicLink(link, outsideFolder);

        await Call("file", FileBody(Path.Combine(link, "Precious.odt")));

        Assert.True(File.Exists(target));
    }

    [Fact]
    public async Task WindowRoutesGoToTheWindows()
    {
        foreach (var route in new[] { "open-dialog", "save-new" })
        {
            var request = new JsonObject { ["app"] = "LibreOffice", ["path"] = null };
            var answer = await Call(route, request);
            Assert.Equal("""{"action":"cancel"}""", answer.ToJsonString());
        }
        Assert.Equal(["open-dialog", "save-new"], _windows.Asked.Select(a => a.Route));
    }

    [Fact]
    public async Task PropertiesOfAFileThatIsntInHealdanFilesIt()
    {
        File.WriteAllText(Path.Combine(_root, "Local memo.odt"), "a file of the user's own");

        var answer = await Call("properties", PathBody(Path.Combine(_root, "Local memo.odt")));

        Assert.Equal("""{"action":"file"}""", answer.ToJsonString());
        Assert.Empty(_windows.Asked); // the caller runs Save to Healdan
    }

    [Fact]
    public async Task PropertiesOfAFileNamedAsACopyOutsideTheCopiesFolderFilesIt()
    {
        var copy = await OpenAsync();
        var lookalike = Path.Combine(_root, Path.GetFileName(copy));
        File.WriteAllText(lookalike, "not the copy");

        var answer = await Call("properties", PathBody(lookalike));

        Assert.Equal("""{"action":"file"}""", answer.ToJsonString());
        Assert.Empty(_windows.Asked);
    }

    [Fact]
    public async Task PropertiesOfACopyGoToTheWindowWithItsDocNumber()
    {
        var path = await OpenAsync();
        _windows.Answer = new JsonObject { ["action"] = "saved" };

        var answer = await Call("properties", PathBody(path));

        Assert.Equal("""{"action":"saved"}""", answer.ToJsonString());
        var (route, request) = Assert.Single(_windows.Asked);
        Assert.Equal("properties", route);
        Assert.Equal(5, (long?)request["docNumber"]);
        Assert.Equal(path, (string?)request["path"]);
    }

    [Fact]
    public async Task SaveAsNewDocumentNamesTheOriginalsDocNumber()
    {
        var path = await OpenAsync();

        await Call("save-new", new JsonObject
        {
            ["app"] = "LibreOffice", ["name"] = "Lease agreement", ["formats"] = new JsonArray("odt", "docx"), ["path"] = path
        });

        var (route, request) = Assert.Single(_windows.Asked);
        Assert.Equal("save-new", route);
        Assert.Equal(5, (long?)request["docNumber"]);
        Assert.Equal(path, (string?)request["path"]);
    }

    [Fact]
    public async Task SaveNewFromAFileThatIsntACopyIsANewDocument()
    {
        await Call("save-new", new JsonObject
        {
            ["app"] = "LibreOffice", ["name"] = "Local memo", ["formats"] = new JsonArray("odt", "docx"),
            ["path"] = Path.Combine(_root, "Local memo.odt")
        });

        var (_, request) = Assert.Single(_windows.Asked);
        Assert.Null(request["path"]);
        Assert.Null(request["docNumber"]);
    }

    [Fact]
    public async Task LinkOpensTheDocument()
    {
        var answer = await Call("link", new JsonObject { ["url"] = "healdan://open/5?server=healdan.test" });

        Assert.Equal("{}", answer.ToJsonString());
        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
    }

    [Fact]
    public async Task ARequestWithoutItsPathIsRefused()
    {
        await Assert.ThrowsAnyAsync<ArgumentException>(() => Call("state", new JsonObject()));
        await Assert.ThrowsAnyAsync<ArgumentException>(() => Call("saved", new JsonObject { ["path"] = 5 }));
    }

    [Fact]
    public async Task FileNeverDeletesALocalCopy()
    {
        // The system's temporary folder holds the tests' copies folder, so only the copies rule keeps this one.
        var copy = await OpenAsync();

        var state = await Call("file", FileBody(copy));

        Assert.Equal(100, (long?)state["docNumber"]); // filed
        Assert.True(File.Exists(copy));
        Assert.Equal("downloaded", File.ReadAllText(copy));
    }

    [Fact]
    public async Task FileDeletesFromTheHelpersTempFolderButNothingElseOfItsOwn()
    {
        var paths = HelperPaths.For(_root);
        Directory.CreateDirectory(paths.Temp);
        var temp = Path.Combine(paths.Temp, "Memo.odt");
        File.WriteAllText(temp, "the window's content");
        var own = Path.Combine(paths.Support, "Memo.odt");
        File.WriteAllText(own, "a file of the helper's own");

        await Call("file", FileBody(temp));
        await Call("file", FileBody(own));

        Assert.False(File.Exists(temp));
        Assert.True(File.Exists(own));
        Assert.True(File.Exists(paths.Log));
    }
}
