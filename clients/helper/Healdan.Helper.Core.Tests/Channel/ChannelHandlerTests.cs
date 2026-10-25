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
    private readonly List<string> _outside = [];
    private readonly FakeApi _server = new();
    private readonly FakeApp _app = new();
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
        _cycle = new CopyCycle(api, new CopyStore(paths), _app, FakeApi.HereDevice, _events, Fast);
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
                ["holdBy"] = null
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

        FakeApp.Write(path, "edited");
        var state = await Call("saved", PathBody(path));

        Assert.Equal("Lease agreement #5 v1.1", (string?)state["title"]);
        Assert.Equal("#5 v1.1", (string?)state["docRef"]);
        Assert.Equal("edited", _server.Doc(5).Content);
    }

    [Fact]
    public async Task NewVersionSendsTheNextSaveAsAVersion()
    {
        var path = await OpenAsync();

        Assert.Equal("{}", (await Call("new-version", PathBody(path))).ToJsonString());
        FakeApp.Write(path, "the next save");

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
