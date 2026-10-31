using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Office;
using Healdan.Helper.Core.Tests.Ui;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>
/// What the add-in's routes do once the port has let a request through (M3.5 plan: the add-in's routes): the document a
/// request is about is the copy its <c>url</c> names, else the program's front window's (spec §5.8), and the routes go to
/// the filing, the windows and the cycle as the extension's do.
/// </summary>
public sealed class AddinHandlerTests : OfficeCycleTestBase
{
    private readonly FakeWindows _windows = new();

    public AddinHandlerTests()
    {
        foreach (var program in Enum.GetValues<OfficeProgram>())
            Scripting.SetRunning(program, true);
    }

    private AddinHandler Handler(CopyCycle cycle)
    {
        var log = new HelperLog(Paths.Log);
        var apps = TestApps.Of(LibreOffice, microsoftOffice: Office);
        var filing = new OfficeFiling(cycle, apps, _windows, Notifier, Paths, log);
        var channel = new ChannelHandler(cycle, new LinkHandler(_windows, Notifier, log), _windows, Notifier, log, Paths);
        return new AddinHandler(new Pairing(Paths, _windows, TimeProvider.System), filing, channel, cycle, apps, log);
    }

    /// <summary>The front window holds the copy at <paramref name="path"/>, as Word reports it.</summary>
    private void InFront(string? path) =>
        Scripting.Front = _ => path is null ? null : new FrontDocument(path, Path.GetFileName(path), Path.GetFileName(path));

    private static JsonObject Body(string? url = "") => new() { ["app"] = "Word", ["url"] = url };

    [Fact]
    public async Task StateNamesTheFrontWindowsCopy()
    {
        var (cycle, path) = await OpenAsync();
        Save(path, "first draft");
        var renamed = CopyPath("Lease agreement #5 v1.1.docx");
        await RenamedTo(cycle, renamed);
        InFront(renamed);
        var handler = Handler(cycle);

        // The pane's url is empty, as for a ribbon button whose code has no document location: the front window decides.
        var answer = await handler.HandleAsync("state", "Word", Body(""));

        Assert.Equal(200, answer.Status);
        Assert.Equal(true, (bool?)answer.Body["known"]);
        Assert.Equal("#5 v1.1", (string?)answer.Body["docRef"]); // what Insert Doc ID puts in the footer
        Assert.Equal("Lease agreement #5 v1.1", (string?)answer.Body["title"]);
        Assert.Equal(renamed, (string?)answer.Body["path"]);

        // A url naming the copy wins over the front window, in either spelling Office may give.
        InFront("/Users/someone/Documents/Another.docx");
        var url = new Uri(renamed).AbsoluteUri;
        Assert.Contains("%20", url);
        Assert.Equal("#5 v1.1", (string?)(await handler.HandleAsync("state", "Word", Body(url))).Body["docRef"]);
        Assert.Equal("#5 v1.1", (string?)(await handler.HandleAsync("state", "Word", Body(renamed))).Body["docRef"]);

        // A window that isn't a copy is unknown.
        var unknown = await handler.HandleAsync("state", "Word", Body("https://contoso-my.sharepoint.com/Lease.docx"));
        Assert.Equal((200, """{"known":false}"""), (unknown.Status, unknown.Body.ToJsonString()));
    }

    [Fact]
    public async Task NotACopyIsSaid()
    {
        var (cycle, _) = await OpenAsync();
        InFront("/Users/someone/Documents/Memo.docx");
        var handler = Handler(cycle);

        var answer = await handler.HandleAsync("new-version", "Word", Body(""));

        Assert.Equal((409, """{"error":"not-a-copy"}"""), (answer.Status, answer.Body.ToJsonString()));
        await Task.Delay(Quiet);
        Assert.Empty(Server.Saves);

        // No document open at all is the same.
        InFront(null);
        Assert.Equal(409, (await handler.HandleAsync("new-version", "Word", Body(""))).Status);
    }

    [Fact]
    public async Task NewVersionSavesUnsavedChangesFirst()
    {
        var (cycle, path) = await OpenAsync();
        InFront(path);
        var unsaved = true;
        Scripting.Unsaved = (_, _) => unsaved;
        var handler = Handler(cycle);

        var answer = await handler.HandleAsync("new-version", "Word", Body(""));

        Assert.Equal((200, "{}"), (answer.Status, answer.Body.ToJsonString()));
        Assert.Equal([(OfficeProgram.Word, path)], Scripting.Saves); // spec §5.6: saved, and that save is the version
        var v2 = CopyPath("Lease agreement #5 v2.0.docx");
        await RenamedTo(cycle, v2);
        Assert.Equal(["version"], Server.Saves.Select(s => s.Kind));

        // Nothing unsaved: the file as it is goes up as the version, with no save.
        unsaved = false;
        InFront(v2);
        await handler.HandleAsync("new-version", "Word", Body(""));
        await RenamedTo(cycle, CopyPath("Lease agreement #5 v3.0.docx"));
        Assert.Single(Scripting.Saves);
        Assert.Equal(["version", "version"], Server.Saves.Select(s => s.Kind));
    }

    [Fact]
    public async Task SaveNewAndFileGoThroughOfficeFiling()
    {
        var cycle = Cycle();
        Scripting.Front = _ => new FrontDocument(null, "", "Document1");
        Scripting.MarksShown = true;
        _windows.Answer = WindowAnswers.Save(9, "Memo", "docx", new JsonObject { ["Client"] = "1" }, WindowAnswers.TempPath(Paths, "docx"));
        var handler = Handler(cycle);

        var saveNew = await handler.HandleAsync("save-new", "Word", new JsonObject { ["app"] = "Word", ["url"] = "", ["asNew"] = false });

        Assert.Equal(200, saveNew.Status);
        var (route, request) = Assert.Single(_windows.Asked);
        Assert.Equal("save-new", route);
        Assert.Equal("""{"app":"Word","name":"Document1","formats":["docx"],"path":null}""", request.ToJsonString());
        Assert.Equal(["action", "folderId", "name", "format", "properties", "ticket"], saveNew.Body.Select(p => p.Key));
        var ticket = (string)saveNew.Body["ticket"]!;

        var file = await handler.HandleAsync("file", "Word", new JsonObject
        {
            ["ticket"] = ticket,
            ["mark"] = "hd-mark-1",
            ["bytes"] = Convert.ToBase64String(Encoding.UTF8.GetBytes("the window's bytes")),
        });

        Assert.Equal(200, file.Status);
        var copy = CopyPath("Memo #100 v1.0.docx");
        Assert.Equal((true, 100L, "#100 v1.0", copy), ((bool?)file.Body["known"], (long?)file.Body["docNumber"], (string?)file.Body["docRef"], (string?)file.Body["path"]));
        Assert.Equal(("Memo.docx", "the window's bytes"), (Server.Doc(100).Name, Server.Doc(100).Content));
        Assert.Equal(copy, Scripting.SavesAs.Single().NewPath);

        // A ticket works once, and its refusal is the filing's code.
        var again = await handler.HandleAsync("file", "Word", new JsonObject { ["ticket"] = ticket, ["mark"] = null, ["bytes"] = "AAAA" });
        Assert.Equal((409, """{"error":"no-ticket"}"""), (again.Status, again.Body.ToJsonString()));

        // Bytes that aren't base64 are a bad request, and file nothing.
        var garbled = await handler.HandleAsync("file", "Word", new JsonObject { ["ticket"] = "t", ["bytes"] = "not base64!" });
        Assert.Equal((400, """{"error":"bad-request"}"""), (garbled.Status, garbled.Body.ToJsonString()));
        Assert.Single(Server.Calls, c => c.StartsWith("filed ", StringComparison.Ordinal));
    }

    [Fact]
    public async Task PropertiesAndOpenGoToTheWindowsAsTheExtensionsDo()
    {
        var (cycle, path) = await OpenAsync();
        InFront(path);
        _windows.Answer = WindowAnswers.Saved();
        var handler = Handler(cycle);

        var properties = await handler.HandleAsync("properties", "Word", Body(""));

        Assert.Equal((200, """{"action":"saved"}"""), (properties.Status, properties.Body.ToJsonString()));
        var asked = Assert.Single(_windows.Asked);
        Assert.Equal(("properties", path, 5L), (asked.Route, (string?)asked.Request["path"], (long?)asked.Request["docNumber"]));

        // A document that isn't in Healdan has no properties there: the add-in runs Save to Healdan instead.
        InFront("/Users/someone/Documents/Memo.docx");
        var notInHealdan = await handler.HandleAsync("properties", "Word", Body(""));
        Assert.Equal((200, """{"action":"file"}"""), (notInHealdan.Status, notInHealdan.Body.ToJsonString()));
        Assert.Single(_windows.Asked);

        _windows.Answer = WindowAnswers.Opened();
        var open = await handler.HandleAsync("open-dialog", "Excel", new JsonObject { ["app"] = "Excel" });
        Assert.Equal((200, """{"action":"opened"}"""), (open.Status, open.Body.ToJsonString()));
        Assert.Equal(("open-dialog", """{"app":"Excel"}"""), (_windows.Asked.Last().Route, _windows.Asked.Last().Request.ToJsonString()));
    }

    [Fact]
    public async Task AKeyDrivesOnlyItsOwnApp()
    {
        var (cycle, path) = await OpenAsync();
        InFront(path);
        var handler = Handler(cycle);

        // Word's key, asking about Excel: refused before anything is looked at.
        var answer = await handler.HandleAsync("state", "Word", new JsonObject { ["app"] = "Excel", ["url"] = "" });

        Assert.Equal((400, """{"error":"bad-request"}"""), (answer.Status, answer.Body.ToJsonString()));
        Assert.Equal(0, Scripting.FrontAsked);

        // The body may leave the app out: the key says which it is.
        Assert.Equal(200, (await handler.HandleAsync("state", "Word", new JsonObject { ["url"] = "" })).Status);
        Assert.Equal([OfficeProgram.Word], Scripting.FrontPrograms);
    }

    [Fact]
    public void AFileUrlIsALocalPath()
    {
        Assert.Equal("/Users/someone/Copies/Lease agreement #5 v1.1.docx",
            AddinHandler.LocalPath("file:///Users/someone/Copies/Lease%20agreement%20%235%20v1.1.docx"));
        Assert.Equal("/Users/someone/Copies/Lease agreement #5 v1.1.docx",
            AddinHandler.LocalPath("file://localhost/Users/someone/Copies/Lease agreement #5 v1.1.docx"));
        Assert.Equal("/Users/someone/Memo.docx", AddinHandler.LocalPath("/Users/someone/Memo.docx"));
        Assert.Null(AddinHandler.LocalPath("file://fileserver/share/Memo.docx")); // another computer's
        Assert.Null(AddinHandler.LocalPath("https://contoso-my.sharepoint.com/Memo.docx"));
        Assert.Null(AddinHandler.LocalPath("Memo.docx"));
        Assert.Null(AddinHandler.LocalPath(""));
        Assert.Null(AddinHandler.LocalPath(null));
    }
}
