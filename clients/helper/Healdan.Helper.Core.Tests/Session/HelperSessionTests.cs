using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Office;
using Healdan.Helper.Core.Tests.Ui;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Session;

public sealed class HelperSessionTests : IDisposable
{
    private const string Account = "https://healdan.test";
    private const string Password = "pa55-w0rd-secret";

    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-session-").FullName;
    private readonly FakeApi _server = new();
    private readonly SessionServer _http;
    private readonly FakeApp _app = new();
    private readonly MemoryKeychain _keychain = new();
    private readonly FakeWindows _windows = new();
    private readonly FakeNotifier _notifier = new();
    private readonly List<HelperSession> _sessions = [];

    public HelperSessionTests()
    {
        _server.Add(5, "Lease agreement.docx", "downloaded");
        _http = new SessionServer(_server);
    }

    public void Dispose()
    {
        foreach (var session in _sessions)
            session.Dispose();
        _server.OpenSaves();
        _http.HoldSignIns?.TrySetResult();
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private HelperSession Make(TimeSpan? signOutFlush = null, OfficeApps? apps = null)
    {
        var device = new DeviceIdentity(FakeApi.HereDevice, FakeApi.HereName);
        var session = new HelperSession(Paths, _keychain, device, apps ?? TestApps.Of(_app), _windows, _notifier, new HelperLog(Paths.Log), _http, Fast,
            signOutFlush);
        _sessions.Add(session);
        return session;
    }

    /// <summary>A token as the keychain keeps it for this computer: with the device id it was issued for.</summary>
    private static string KeptHere(string token) => new KeptToken(FakeApi.HereDevice, token).ToSecret();

    private void SignedInBefore()
    {
        new HelperSettings { Server = Account }.Save(Paths);
        _keychain.Items[Account] = KeptHere("hd_kept_token");
    }

    /// <summary>A request to the running helper's channel, as the extension sends it.</summary>
    private async Task<HttpResponseMessage> PostChannel(string route, JsonObject body)
    {
        var info = ChannelInfo.Read(Paths)!;
        using var http = new HttpClient();
        using var request = new HttpRequestMessage(HttpMethod.Post, $"http://127.0.0.1:{info.Port}/v1/{route}")
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json")
        };
        request.Headers.Add(ChannelInfo.SecretHeader, info.Secret);
        return await http.SendAsync(request);
    }

    private async Task<string> OpenAsync(HelperSession session)
    {
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(_app.Opened);
        await Wait.Until(() => _app.LockSeen(path), "the close watcher sees the lock file");
        return path;
    }

    private static DocumentView View(long number, string name = "Lease agreement.docx") =>
        new("document", number, name, 10, "2.1", "2.1", $"{number}.2.1",
            new FolderRef(5, "Documents", "folder"), null, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, true);

    private bool Revoked() => _http.Calls.Contains("DELETE healdan.test/api/v1/auth/tokens/current");

    private string[] LogLines() => File.Exists(Paths.Log) ? File.ReadAllLines(Paths.Log) : [];

    private int LogIndex(string text)
    {
        var lines = LogLines();
        var i = Array.FindIndex(lines, l => l.Contains(text, StringComparison.Ordinal));
        Assert.True(i >= 0, $"the log has \"{text}\":\n{string.Join("\n", lines)}");
        return i;
    }

    /// <summary>Whether the log has <paramref name="text"/> yet, read while the helper may still be writing to it.</summary>
    private bool Logged(string text)
    {
        try
        {
            using var log = new StreamReader(new FileStream(Paths.Log, FileMode.Open, FileAccess.Read, FileShare.ReadWrite));
            return log.ReadToEnd().Contains(text, StringComparison.Ordinal);
        }
        catch (FileNotFoundException)
        {
            return false;
        }
    }

    /// <summary>
    /// A Sign In window that stays up to the end of the test. A refused open tells the session twice, from the call and
    /// from the cycle, and the second asks for the sign-in again if the first window has closed by then: a log line,
    /// or a whole sign-in, after the test has deleted its folder.
    /// </summary>
    private static Task<bool> StaysUp() => new TaskCompletionSource<bool>().Task;

    [Fact]
    public async Task StartsInOrderAndOnlyThenOpensLinks()
    {
        SignedInBefore();
        Directory.CreateDirectory(Paths.Copies);
        var part = Path.Combine(Paths.Copies, ".healdan-0123.part");
        File.WriteAllText(part, "half a download");
        var session = Make();

        // The link that started the helper arrives first.
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        Assert.Empty(_server.Calls);

        await session.StartAsync();
        await Wait.Until(() => _app.Opened.Count == 1, "the held link's open");

        Assert.False(File.Exists(part));
        Assert.True(File.Exists(Paths.Channel));
        Assert.True(session.Started);
        Assert.Equal("Robert Example", session.UserName);
        var signedIn = LogIndex("Signed in to healdan.test");
        var resumed = LogIndex("Copies resumed");
        var channel = LogIndex("Channel listening on 127.0.0.1:");
        var opening = LogIndex("Opening #5");
        Assert.True(signedIn < resumed && resumed < channel && channel < opening,
            string.Join("\n", LogLines()));
        Assert.Empty(_windows.SignIns);
    }

    [Fact]
    public async Task WaitsForTheSignInWindowWhenThereIsNoToken()
    {
        new HelperSettings { Server = Account }.Save(Paths);
        var session = Make();
        await session.Links.Handle("healdan://open/5?server=healdan.test");

        await session.StartAsync(); // the user closes the window

        var prompt = Assert.Single(_windows.SignIns);
        Assert.Equal(Account, prompt.Server);
        Assert.False(prompt.ServerFixed);
        Assert.False(session.Started);
        Assert.False(File.Exists(Paths.Channel));
        Assert.Empty(_app.Opened);
    }

    [Fact]
    public async Task RevokesATokenKeptForAnotherDeviceAndAsksForTheSignIn()
    {
        new HelperSettings { Server = Account }.Save(Paths);
        _keychain.Items[Account] = new KeptToken("00d0aa76-dev-helper", "hd_kept_token").ToSecret();
        _windows.OnSignIn = async prompt => await prompt.SignInAsync(Account, "robert", Password) is null;
        var session = Make();

        await session.StartAsync();

        Assert.Equal("DELETE healdan.test/api/v1/auth/tokens/current", _http.Calls[0]); // never used, only revoked
        Assert.Equal(Account,Assert.Single(_windows.SignIns).Server);
        Assert.True(session.Started);
        Assert.Equal(KeptHere(SessionServer.IssuedToken), _keychain.Items[Account]);
        LogIndex("issued to device 00d0aa76-dev-helper");
    }

    [Fact]
    public async Task SignsInFromTheWindowThenStarts()
    {
        var refusals = new List<string?>();
        _windows.OnSignIn = async prompt =>
        {
            refusals.Add(await prompt.SignInAsync("healdan.test", "robert", Password));
            return refusals[^1] is null;
        };
        var session = Make();

        await session.StartAsync();

        Assert.Equal([null], refusals);
        Assert.True(session.Started);
        Assert.Equal(KeptHere(SessionServer.IssuedToken), _keychain.Items[Account]); // an address without a scheme is https
        Assert.Equal(Account, HelperSettings.Load(Paths).Server);
        Assert.True(File.Exists(Paths.Channel));
        foreach (var file in Directory.EnumerateFiles(_root, "*", SearchOption.AllDirectories))
        {
            var text = File.ReadAllText(file);
            Assert.DoesNotContain(Password, text);
            Assert.DoesNotContain(SessionServer.IssuedToken, text);
        }
    }

    [Fact]
    public async Task TheSignInWindowShowsWhyASignInWasRefused()
    {
        var refusals = new List<string?>();
        _windows.OnSignIn = async prompt =>
        {
            refusals.Add(await prompt.SignInAsync("http://healdan.example", "robert", Password));
            refusals.Add(await prompt.SignInAsync("https://other.test", "robert", Password));
            refusals.Add(await prompt.SignInAsync("https://newer.test", "robert", Password));
            refusals.Add(await prompt.SignInAsync(Account, "robert", SessionServer.WrongPassword));
            refusals.Add(await prompt.SignInAsync("", "robert", Password));
            return false;
        };
        var session = Make();

        await session.StartAsync();

        Assert.Equal(5, refusals.Count);
        Assert.Contains("https", refusals[0]);
        Assert.DoesNotContain("Parameter", refusals[0]);
        Assert.Contains("isn't a Healdan server", refusals[1]);
        Assert.Contains("v2", refusals[2]);
        Assert.Equal("The username or password is wrong.", refusals[3]);
        Assert.NotNull(refusals[4]);
        Assert.Empty(_keychain.Items);
        Assert.False(session.Started);
        Assert.DoesNotContain(Password, File.ReadAllText(Paths.Log));
    }

    [Fact]
    public async Task ACheckoutThatEndedSaysTheDocumentCantBeSavedAnyMore()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(_app.Opened);

        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());
        FakeApp.Write(path, "an edit that can't go up");

        await Wait.Until(() => _notifier.All.Contains("can't be saved to Healdan any more"), "the notice");
        Assert.Contains("Lease agreement #5 v1.0", _notifier.All);
        Assert.Contains("Save as New Document", _notifier.All);
    }

    [Fact]
    public async Task ACopyNotYetInHealdanIsListedForTheMenuAndOpensFromIt()
    {
        // Spec 5.4: once its window has closed, a copy whose changes can't go up is still reachable from the menu.
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());
        FakeApp.Write(path, "an edit that can't go up");
        await Wait.Until(() => _notifier.All.Contains("can't be saved to Healdan any more"), "the notice");
        Assert.Empty(session.KeptCopies()); // still open: it's among the documents open
        FakeApp.Close(path);
        await Wait.Until(() => session.OpenCopies().Count == 0, "the window's close done");

        var kept = Assert.Single(session.KeptCopies());
        Assert.Equal("Lease agreement #5 v1.0", kept.Title);
        session.OpenKept(kept);

        await Wait.Until(() => _app.Opened.Count == 2, "opened again from the menu");
        Assert.Equal(path, _app.Opened[^1]);
        Assert.Equal(1, _server.Count("GET /documents/5/content")); // no download, and no check-out taken
        Assert.Equal(1, _server.Count("POST /documents/5/checkout"));
        await Wait.Until(() => _notifier.All.Contains("aren't in Healdan yet"), "how to file it");
        Assert.Contains("Save as New Document", _notifier.All);
    }

    [Fact]
    public async Task AKeptOfficeCopyThatCantBeOpenedFromTheMenuTellsTheUser()
    {
        // Declining macOS's "control Microsoft Word" prompt, for one, makes the scripting fail: OfficeScriptException.
        var scripting = new FakeScripting();
        var office = new SeenLocks(new MicrosoftOfficeApp(scripting, scripting.IsRunning));
        SignedInBefore();
        var session = Make(apps: TestApps.Of(_app, microsoftOffice: office, appFor: _ => "Microsoft Office"));
        await session.StartAsync();
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(scripting.Opened).Path;
        await Wait.Until(() => office.LockSeen(path), "the close watcher sees the owner file");
        _server.Change(5, d => d.Hold = FakeApi.OtherDevice());
        FakeApp.Write(path, "an edit that can't go up");
        await Wait.Until(() => _notifier.All.Contains("can't be saved to Healdan any more"), "the notice");
        FakeScripting.Close(OfficeProgram.Word, path);
        await Wait.Until(() => session.OpenCopies().Count == 0, "the window's close done");
        var kept = Assert.Single(session.KeptCopies());
        scripting.FailOpen = true;

        session.OpenKept(kept);

        var timeout = TimeSpan.FromSeconds(5); // so a click that does nothing fails soon
        await Wait.Until(() => _notifier.All.Contains("It couldn't be opened:"), "the user told", timeout);
        await Wait.Until(() => Logged("Couldn't open the kept copy of #5 v1.0"), "the log line", timeout);
        Assert.Contains("Word didn't answer", _notifier.All);
        Assert.Single(scripting.Opened); // only the first open
    }

    [Fact]
    public async Task ARefusedTokenAsksForTheSignInAgain()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var asked = new TaskCompletionSource<SignInPrompt>(TaskCreationOptions.RunContinuationsAsynchronously);
        var signedIn = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _windows.OnSignIn = async prompt =>
        {
            asked.TrySetResult(prompt);
            _server.Unauthorized = false;
            // Set once the whole sign-in has run, its settings and log included, which go on after the token is kept.
            signedIn.TrySetResult(await prompt.SignInAsync(prompt.Server!, "robert", Password));
            return await StaysUp();
        };

        _server.Unauthorized = true;
        await session.Links.Handle("healdan://open/5?server=healdan.test");

        var again = await asked.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(again.ServerFixed); // one server per helper while it runs
        Assert.Equal(Account, again.Server);
        Assert.Null(await signedIn.Task.WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.Equal(KeptHere(SessionServer.IssuedToken), _keychain.Items[Account]);
        Assert.Contains("Sign in", _notifier.All);
    }

    [Fact]
    public async Task SignOutRevokesTheTokenAndStops()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();

        await session.SignOutAsync();

        Assert.Contains("DELETE healdan.test/api/v1/auth/tokens/current", _http.Calls);
        Assert.Empty(_keychain.Items);
        Assert.False(session.Started);
        Assert.False(session.SignedIn);
        Assert.False(File.Exists(Paths.Channel));
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        Assert.Empty(_app.Opened);
        Assert.Contains("Sign in", _notifier.All);
    }

    [Fact]
    public async Task TheWebPageIsTheServersFromTheStartAndStaysAfterSignOut()
    {
        SignedInBefore();
        var session = Make();
        Assert.Null(session.WebPage); // nothing for the menu to open yet

        await session.StartAsync();
        Assert.Equal("https://healdan.test/", session.WebPage?.AbsoluteUri);

        await session.SignOutAsync();
        Assert.Equal("https://healdan.test/", session.WebPage?.AbsoluteUri); // the browser has its own sign-in
    }

    [Fact]
    public async Task TheWebPageKeepsTheServersPath()
    {
        new HelperSettings { Server = Account + "/healdan" }.Save(Paths);
        _keychain.Items[Account] = KeptHere("hd_kept_token"); // the keychain keeps one sign-in per scheme and host
        _server.Down = true; // the fake answers only at the root, so the helper starts offline
        var session = Make();

        await session.StartAsync();

        Assert.True(session.Started);
        Assert.Equal("https://healdan.test/healdan/", session.WebPage?.AbsoluteUri);
    }

    [Fact]
    public async Task AboutReadsTheServersVersionSignedInOrOut()
    {
        SignedInBefore();
        var session = Make();
        Assert.Null(await session.AboutServerAsync()); // no server yet

        await session.StartAsync();
        Assert.Equal(new AboutServer("healdan.test", SignedIn: true, Version: "9.3.1"), await session.AboutServerAsync());

        await session.SignOutAsync();
        Assert.Equal(new AboutServer("healdan.test", SignedIn: false, Version: "9.3.1"), await session.AboutServerAsync());
    }

    [Fact]
    public async Task AboutSaysWhenTheServerCantBeReached()
    {
        SignedInBefore();
        _server.Down = true;
        var session = Make();
        await session.StartAsync();

        Assert.Equal(new AboutServer("healdan.test", SignedIn: true, Version: null), await session.AboutServerAsync());
    }

    [Fact]
    public async Task AboutStopsAskingWhenItsWindowGivesUp()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();

        var about = await session.AboutServerAsync(new CancellationToken(canceled: true));

        Assert.Equal(new AboutServer("healdan.test", SignedIn: true, Version: null), about);
    }

    [Fact]
    public async Task QuittingRemovesTheChannelFileAndKeepsTheCopies()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(_app.Opened);

        session.Dispose();

        Assert.False(File.Exists(Paths.Channel));
        Assert.True(File.Exists(path));
        Assert.NotNull(_server.Doc(5).Hold); // still checked out: the next start carries on
    }

    [Fact]
    public async Task StartsOfflineWithTheKeptToken()
    {
        SignedInBefore();
        _server.Down = true;
        var session = Make();

        await session.StartAsync();

        Assert.True(session.Started);
        Assert.True(File.Exists(Paths.Channel));
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
        Assert.Empty(_windows.SignIns);
    }

    [Fact]
    public async Task ARefusedTokenWhileFilingAsksForTheSignInAgain()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var asked = new TaskCompletionSource<SignInPrompt>(TaskCreationOptions.RunContinuationsAsynchronously);
        _windows.OnSignIn = prompt =>
        {
            asked.TrySetResult(prompt);
            return Task.FromResult(false);
        };
        var temp = Path.Combine(Path.GetTempPath(), $"healdan-save-{Guid.NewGuid():N}.odt");
        File.WriteAllText(temp, "the window's content");
        _server.Unauthorized = true;

        using var answer = await PostChannel("file",
            new JsonObject { ["tempPath"] = temp, ["folderId"] = 9, ["name"] = "Memo", ["format"] = "odt" });

        Assert.Equal(HttpStatusCode.InternalServerError, answer.StatusCode); // the extension falls back to its own save
        var prompt = await asked.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(prompt.ServerFixed);
        Assert.Equal(Account, prompt.Server);
        Assert.False(session.SignedIn); // the menu offers Sign In, not Sign Out
        Assert.False(File.Exists(temp));
    }

    [Fact]
    public async Task SignOutSendsASaveStillSettlingBeforeRevokingTheToken()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);

        FakeApp.Write(path, "saved just before Sign Out");
        await session.SignOutAsync(); // well within the settle time

        Assert.Equal("saved just before Sign Out", _server.Doc(5).Content);
        var calls = _http.Calls.ToList();
        var save = calls.IndexOf("POST healdan.test/api/v1/documents/5/versions");
        var revoke = calls.IndexOf("DELETE healdan.test/api/v1/auth/tokens/current");
        Assert.True(save >= 0 && revoke > save, string.Join("\n", calls));
        Assert.Empty(_keychain.Items);
        Assert.False(session.Started);
    }

    [Fact]
    public async Task SignOutKeepsTheSignInWhileASaveCantGoUp()
    {
        SignedInBefore();
        var session = Make(signOutFlush: TimeSpan.FromMilliseconds(500));
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.HoldSaves();
        FakeApp.Write(path, "an edit still going up");
        await Wait.Until(() => _server.SavesReceived == 1, "the save going up");

        await session.SignOutAsync();

        Assert.False(Revoked());
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
        Assert.True(session.SignedIn);
        Assert.True(session.Started);
        Assert.Contains("still waiting to go to Healdan", _notifier.All);
    }

    [Fact]
    public async Task QuittingWhileSignOutWaitsForASaveKeepsTheSignIn()
    {
        SignedInBefore();
        var session = Make(signOutFlush: TimeSpan.FromSeconds(10));
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.HoldSaves();
        FakeApp.Write(path, "an edit still going up");
        await Wait.Until(() => _server.SavesReceived == 1, "the save going up");

        var signOut = session.SignOutAsync();
        await Task.Delay(300);
        Assert.False(signOut.IsCompleted, "Sign Out waits for the save");
        session.Dispose(); // Quit
        await signOut.WaitAsync(TimeSpan.FromSeconds(10));

        Assert.False(Revoked());
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]); // the next start carries on, and sends the save
    }

    [Fact]
    public async Task SignOutOffersToForgetTheSignInHereWhenHealdanCantBeReached()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);

        await session.SignOutAsync();

        Assert.Equal([("healdan.test", 0)], _windows.SignOutHeres);
        Assert.Empty(_keychain.Items);
        Assert.False(session.SignedIn);
        Assert.False(session.Started);
        Assert.False(File.Exists(Paths.Channel));
        Assert.Equal(Account, HelperSettings.Load(Paths).Server); // the Sign In window offers it, and takes another
        LogIndex("Signed out on this computer only");
    }

    [Fact]
    public async Task StayingSignedInWhenHealdanCantBeReachedKeepsTheSignIn()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;

        await session.SignOutAsync(); // the user chooses Stay Signed In

        Assert.Single(_windows.SignOutHeres);
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
        Assert.True(session.SignedIn);
        Assert.True(session.Started);
    }

    [Theory]
    [InlineData(HttpStatusCode.BadGateway)]
    [InlineData(HttpStatusCode.ServiceUnavailable)]
    [InlineData(HttpStatusCode.GatewayTimeout)]
    public async Task AProxyWhoseServerHasGoneCountsAsHealdanUnreachable(HttpStatusCode status)
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _http.RevokeStatus = status;
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);

        await session.SignOutAsync();

        Assert.Single(_windows.SignOutHeres);
        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public async Task ARefusalHealdanAnswersKeepsTheSignInWithoutOfferingToForgetIt()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _http.RevokeStatus = HttpStatusCode.InternalServerError;

        await session.SignOutAsync();

        Assert.Empty(_windows.SignOutHeres);
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
        Assert.True(session.SignedIn);
        Assert.Contains("You're still signed in", _notifier.All);
        Assert.DoesNotContain("can't be reached", _notifier.All);
    }

    [Fact]
    public async Task SignOutWithASaveThatCantReachHealdanSaysItsCopyStays()
    {
        SignedInBefore();
        var session = Make(signOutFlush: TimeSpan.FromMilliseconds(300));
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.Down = true;
        FakeApp.Write(path, "an edit that can't go up");
        await Wait.Until(() => _notifier.All.Contains("A save is waiting for Healdan"), "the save waiting");
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);

        await session.SignOutAsync();

        Assert.Equal([("healdan.test", 1)], _windows.SignOutHeres);
        Assert.Empty(_keychain.Items);
        Assert.False(session.Started);
        Assert.Equal("an edit that can't go up", File.ReadAllText(path)); // the next sign-in to this server sends it
    }

    [Fact]
    public async Task QuittingWhileSignOutAsksKeepsTheSignIn()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        _windows.OnSignOutHere = (_, _) =>
        {
            session.Dispose(); // Quit while the window is up
            return Task.FromResult(true);
        };

        await session.SignOutAsync();

        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
    }

    [Fact]
    public async Task QuittingWhileSignOutRevokesAsksNothingAndKeepsTheSignIn()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _http.RevokeStatus = HttpStatusCode.ServiceUnavailable;
        _http.OnRevoke = session.Dispose; // Quit while the revoke waits for its answer

        await session.SignOutAsync();

        Assert.Empty(_windows.SignOutHeres);
        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
    }

    [Fact]
    public async Task SigningOutHereTriesTheRevokeOnceMoreInCaseHealdanIsBack()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        _windows.OnSignOutHere = (_, _) =>
        {
            _server.Down = false; // back while the window was up
            return Task.FromResult(true);
        };

        await session.SignOutAsync();

        Assert.Equal(2, _http.Calls.Count(c => c == "DELETE healdan.test/api/v1/auth/tokens/current"));
        Assert.Empty(_keychain.Items);
        Assert.False(session.Started);
        LogIndex("Signed out: healdan.test answered after all");
    }

    [Fact]
    public async Task AKeychainThatRefusesKeepsTheSignInHere()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        _keychain.RefuseDelete = true;
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);

        await session.SignOutAsync();

        Assert.Equal(KeptHere("hd_kept_token"), _keychain.Items[Account]);
        Assert.True(session.SignedIn);
        Assert.True(session.Started);
        Assert.NotNull(session.Api?.Token); // the client keeps its token, so Sign Out can ask again
        Assert.Contains("refused to delete", _notifier.All);

        await session.SignOutAsync();

        Assert.Equal(2, _windows.SignOutHeres.Count);
    }

    [Fact]
    public async Task ASecondSignOutWhileTheWindowIsUpDoesNothing()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        Task? second = null;
        _windows.OnSignOutHere = (_, _) =>
        {
            second = session.SignOutAsync();
            return Task.FromResult(false);
        };

        await session.SignOutAsync();

        Assert.True(second!.IsCompletedSuccessfully);
        Assert.Single(_windows.SignOutHeres);
        Assert.Single(_http.Calls, c => c == "DELETE healdan.test/api/v1/auth/tokens/current");
    }

    [Fact]
    public async Task ARefusedTokenWhileSignOutAsksIsToldAsUsual()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        _server.Down = true;
        var asked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _windows.OnSignIn = _ =>
        {
            asked.TrySetResult();
            return StaysUp();
        };
        _windows.OnSignOutHere = async (_, _) =>
        {
            (_server.Down, _server.Unauthorized) = (false, true); // Healdan is back, and refuses the token
            await session.Links.Handle("healdan://open/5?server=healdan.test");
            await Wait.Until(() => _notifier.All.Contains("Sign in to Healdan again"), "the refused token told");
            return false;
        };

        await session.SignOutAsync();

        Assert.True(session.Started);
        await asked.Task.WaitAsync(TimeSpan.FromSeconds(10)); // the Sign In window too, which comes after the notice
    }

    [Fact]
    public async Task SigningInToAnotherServerSetsTheOldServersCopiesAside()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        await session.SignOutAsync();
        Assert.True(File.Exists(path)); // still checked out on healdan.test, so its copy stays
        FakeApp.Close(path); // after Sign Out, so nothing checks it in
        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;

        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.Equal("https://second.test", HelperSettings.Load(Paths).Server);
        Assert.False(File.Exists(path));
        var aside = Path.Combine(_root, "Copies from healdan.test");
        Assert.True(File.Exists(Path.Combine(aside, Path.GetFileName(path))));
        Assert.True(File.Exists(Path.Combine(aside, "." + Path.GetFileName(path) + ".healdan.json")));
        Assert.DoesNotContain(_http.Calls, c => c.Contains("second.test/api/v1/documents/5", StringComparison.Ordinal)); // not resumed there
        Assert.Contains("Copies from healdan.test", _notifier.All);
        Assert.Contains("brings them back when you sign in to healdan.test again", _notifier.All);
    }

    [Fact]
    public async Task SigningInAgainToTheSameServerKeepsItsCopies()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        await session.SignOutAsync();
        _windows.OnSignIn = async prompt => await prompt.SignInAsync(prompt.Server!, "robert", Password) is null;

        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.True(File.Exists(path));
        Assert.False(Directory.Exists(Path.Combine(_root, "Copies from healdan.test")));
    }

    [Fact]
    public async Task ACopyRecordsTheServerItCameFrom()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();

        var path = await OpenAsync(session);

        Assert.Equal(Account, new CopyStore(Paths).Find(path)!.Value.Item2.Server);
    }

    [Fact]
    public async Task CopiesSetAsideComeBackWhenTheirServerSignsInAgain()
    {
        SignedInBefore();
        var session = Make(signOutFlush: TimeSpan.FromMilliseconds(300));
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.Down = true;
        FakeApp.Write(path, "an edit that can't go up");
        await Wait.Until(() => _notifier.All.Contains("A save is waiting for Healdan"), "the save waiting");
        FakeApp.Close(path); // its window closes with the save still waiting
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);
        await session.SignOutAsync();
        _server.Down = false;

        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;
        await session.SignInAgainAsync();
        Assert.True(session.Started);
        Assert.Equal(new AsideCopies("healdan.test", Path.Combine(_root, "Copies from healdan.test"), 1),
            Assert.Single(session.CopiesAside()));
        await session.SignOutAsync();

        _windows.OnSignIn = async prompt => await prompt.SignInAsync(Account, "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.Empty(session.CopiesAside());
        Assert.Contains("The copy set aside from healdan.test is back", _notifier.All);
        await Wait.Until(() => _server.Saves.Any(s => s.DocNumber == 5 && s.Content == "an edit that can't go up"),
            "the kept save sent to its own server");
        Assert.DoesNotContain(_http.Calls, c => c.Contains("second.test/api/v1/documents/5", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ACopyNamingAnotherServerIsSetAsideAtTheStartWhenTheSettingsAreLost()
    {
        SignedInBefore();
        var first = Make();
        await first.StartAsync();
        var path = await OpenAsync(first);
        first.Dispose(); // Quit: the copy stays, checked out on healdan.test
        FakeApp.Close(path);
        File.Delete(Paths.Settings);
        _keychain.Items.Clear();
        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;

        var second = Make();
        await second.StartAsync();
        await Wait.Until(() => second.Started, "started on second.test");

        Assert.True(File.Exists(Path.Combine(_root, "Copies from healdan.test", Path.GetFileName(path))));
        Assert.Contains("won't send them to second.test", _notifier.All);
        Assert.DoesNotContain(_http.Calls, c => c.Contains("second.test/api/v1/documents/5", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AStartThatCantSetAsideAnotherServersCopyDoesntStart()
    {
        if (OperatingSystem.IsWindows())
            return;
        SignedInBefore();
        var (path, _, _) = new CopyStore(Paths, "https://second.test").Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "second.test's document 5");
        File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserExecute); // nothing can leave it
        try
        {
            var session = Make();
            await session.StartAsync();
            await Wait.Until(() => session.StartFailed, "the start refused");

            Assert.False(session.Started);
            Assert.Contains("hasn't started", _notifier.All);
            Assert.True(File.Exists(path));
            Assert.DoesNotContain(_http.Calls, c => c.Contains("healdan.test/api/v1/documents/5", StringComparison.Ordinal));
        }
        finally
        {
            File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    [Fact]
    public async Task ASignInToAnotherServerRefusedForAMoveThatFailedChangesNothing()
    {
        if (OperatingSystem.IsWindows())
            return;
        SignedInBefore();
        _keychain.Items.Clear(); // no kept token, so the start asks for a sign-in, and the user types another server
        var (path, _, _) = new CopyStore(Paths, Account).Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "healdan.test's document 5");
        string? refusal = null;
        _windows.OnSignIn = async prompt =>
        {
            refusal = await prompt.SignInAsync("https://second.test", "robert", Password);
            return false;
        };
        File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserExecute); // nothing can leave it
        try
        {
            var session = Make();
            await session.StartAsync();

            Assert.Contains("couldn't move the copies from healdan.test out of the way", refusal);
            Assert.Equal(Account, HelperSettings.Load(Paths).Server);
            Assert.False(session.Started);
            Assert.False(session.SignedIn);
            Assert.True(File.Exists(path));
            Assert.Empty(session.CopiesAside());
        }
        finally
        {
            File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    [Fact]
    public async Task ASignInToAnotherServerIsRefusedWhileACopyItWouldSetAsideIsOpen()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        await session.SignOutAsync();
        string? refusal = null;
        _windows.OnSignIn = async prompt =>
        {
            refusal = await prompt.SignInAsync("https://second.test", "robert", Password);
            return false;
        };

        await session.SignInAgainAsync();

        Assert.Equal($"Close {Path.GetFileNameWithoutExtension(path)} in LibreOffice first. Healdan Helper moves the copies "
            + "from healdan.test out of the way before it signs in to second.test, and that one is open.", refusal);
        Assert.False(session.SignedIn);
        Assert.Equal(Account, HelperSettings.Load(Paths).Server);
        Assert.True(File.Exists(path)); // not moved from under its window
        Assert.Empty(session.CopiesAside());
        Assert.DoesNotContain(_http.Calls, c => c.Contains(" second.test/", StringComparison.Ordinal)); // no token made there

        FakeApp.Close(path);
        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.True(File.Exists(Path.Combine(_root, "Copies from healdan.test", Path.GetFileName(path))));
    }

    [Fact]
    public async Task ASignInToAnotherServerIsRefusedForAnyOfSeveralOpenCopies()
    {
        SignedInBefore();
        _keychain.Items.Clear();
        foreach (var number in new long[] { 5, 6, 7 })
        {
            var (path, _, _) = new CopyStore(Paths, Account).Create(View(number), false, "LibreOffice");
            File.WriteAllText(path, $"document {number}");
            File.WriteAllText(FakeApp.LockPath(path), "open in LibreOffice");
        }
        _app.Running = true;
        string? refusal = null;
        _windows.OnSignIn = async prompt =>
        {
            refusal = await prompt.SignInAsync("https://second.test", "robert", Password);
            return false;
        };

        await Make().StartAsync();

        Assert.StartsWith("Close Lease agreement #5 v2.1 and 2 other documents in LibreOffice first.", refusal);
        Assert.EndsWith("and those are open.", refusal);
    }

    [Fact]
    public async Task ASignInToAnotherServerIsRefusedWhileACopyIsOpenInMicrosoftOffice()
    {
        SignedInBefore();
        _keychain.Items.Clear();
        var (path, _, _) = new CopyStore(Paths, Account).Create(View(5), false, "Microsoft Office");
        File.WriteAllText(path, "document 5");
        var scripting = new FakeScripting();
        File.WriteAllText(FakeScripting.OwnerPath(OfficeProgram.Word, path), "open in Word");
        scripting.SetRunning(OfficeProgram.Word, true); // LibreOffice isn't running: it's another app that is
        var apps = TestApps.Of(_app, microsoftOffice: new MicrosoftOfficeApp(scripting, scripting.IsRunning));
        string? refusal = null;
        _windows.OnSignIn = async prompt =>
        {
            refusal = await prompt.SignInAsync("https://second.test", "robert", Password);
            return false;
        };

        await Make(apps: apps).StartAsync();

        Assert.StartsWith("Close Lease agreement #5 v2.1 in Microsoft Office first.", refusal);
        Assert.True(File.Exists(path));
    }

    [Fact]
    public async Task AnotherServersCopyOpenInMicrosoftOfficeStaysInCopiesAtAStart()
    {
        var (path, _, _) = new CopyStore(Paths, "https://second.test").Create(View(5), false, "Microsoft Office");
        File.WriteAllText(path, "second.test's document 5");
        var scripting = new FakeScripting();
        File.WriteAllText(FakeScripting.OwnerPath(OfficeProgram.Word, path), "open in Word");
        scripting.SetRunning(OfficeProgram.Word, true);
        SignedInBefore();

        var session = Make(apps: TestApps.Of(_app, microsoftOffice: new MicrosoftOfficeApp(scripting, scripting.IsRunning)));
        await session.StartAsync();
        await Wait.Until(() => session.Started, "started");

        Assert.True(File.Exists(path)); // not moved from under its window
        Assert.Contains("open in Microsoft Office, so it stays in Healdan Helper's Copies folder until you close it", _notifier.All);
    }

    [Fact]
    public async Task ASignInToAnotherServerGoesOnPastALockFileTheAppLeftWhenItCrashed()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        var lockFile = FakeApp.LockPath(path);
        await session.SignOutAsync();
        Assert.True(File.Exists(path)); // still checked out on healdan.test, so its copy stays
        _app.Crash(); // LibreOffice goes, and leaves its lock file

        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.False(File.Exists(path));
        Assert.True(File.Exists(Path.Combine(_root, "Copies from healdan.test", Path.GetFileName(path))));
        Assert.True(File.Exists(lockFile)); // the app deletes its lock file by the path it opened, so it stays
        await session.SignOutAsync();

        _windows.OnSignIn = async prompt => await prompt.SignInAsync(Account, "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.True(File.Exists(path));
        Assert.False(Directory.Exists(Path.Combine(_root, "Copies from healdan.test")));
        Assert.True(File.Exists(lockFile));
        Assert.Equal(path, Assert.Single(session.OpenCopies()).Path); // the cycle looks after it as crashed
    }

    [Fact]
    public async Task AnotherServersCopyOpenInTheAppStaysInCopiesAtAStartUnseenUntilItsClosed()
    {
        var (path, _, _) = new CopyStore(Paths, "https://second.test").Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "second.test's document 5");
        File.WriteAllText(FakeApp.LockPath(path), "open in LibreOffice");
        _app.Running = true;
        SignedInBefore();

        var first = Make();
        await first.StartAsync();
        await Wait.Until(() => first.Started, "started");

        Assert.True(File.Exists(path)); // not moved from under its window
        Assert.False(Directory.Exists(Path.Combine(_root, "Copies from second.test")));
        Assert.Contains("stays in Healdan Helper's Copies folder until you close it", _notifier.All);
        Assert.Empty(first.OpenCopies());
        using (var state = await PostChannel("state", new JsonObject { ["path"] = path }))
            Assert.False(JsonNode.Parse(await state.Content.ReadAsStringAsync())!["known"]!.GetValue<bool>());
        using (var closed = await PostChannel("closed", new JsonObject { ["path"] = path }))
            Assert.Equal(HttpStatusCode.OK, closed.StatusCode);
        await Task.Delay(300); // a close would have gone up by now
        Assert.DoesNotContain(_http.Calls, c => c.Contains("healdan.test/api/v1/documents/5", StringComparison.Ordinal));
        first.Dispose();

        FakeApp.Close(path);
        var second = Make();
        await second.StartAsync();
        await Wait.Until(() => second.Started, "started again");

        Assert.False(File.Exists(path));
        Assert.True(File.Exists(Path.Combine(_root, "Copies from second.test", Path.GetFileName(path))));
    }

    [Fact]
    public async Task ASetAsideCopyOpenInAnAppComesBackOnceClosed()
    {
        var (path, _, _) = new CopyStore(Paths, Account).Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "edited while away");
        new CopyStore(Paths, "https://second.test").SetAside("https://second.test", Account);
        var aside = Path.Combine(_root, "Copies from healdan.test");
        var lockFile = Path.Combine(aside, $".~lock.{Path.GetFileName(path)}#");
        File.WriteAllText(lockFile, "opened from that folder");
        SignedInBefore();

        var first = Make();
        await first.StartAsync();
        await Wait.Until(() => first.Started, "started");
        Assert.Contains("open in an app", _notifier.All);
        Assert.Equal(new AsideCopies("healdan.test", aside, 1), Assert.Single(first.CopiesAside()));
        Assert.False(File.Exists(path));
        first.Dispose();

        File.Delete(lockFile);
        var second = Make();
        await second.StartAsync();
        await Wait.Until(() => second.Started, "started again");

        Assert.True(File.Exists(path)); // its check-out had ended, so it's kept for Not Yet in Healdan
        Assert.False(Directory.Exists(aside));
        Assert.Contains("is back", _notifier.All);
    }

    [Fact]
    public async Task ASignInThatFinishesAfterItsWindowClosedStillStarts()
    {
        _http.HoldSignIns = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        Task<string?>? late = null;
        _windows.OnSignIn = prompt =>
        {
            late = prompt.SignInAsync(Account, "robert", Password);
            return Task.FromResult(false); // the user closed the window while the call was running
        };
        var session = Make();
        await session.StartAsync();
        Assert.False(session.Started);

        _http.HoldSignIns.SetResult();

        Assert.Null(await late!.WaitAsync(TimeSpan.FromSeconds(10)));
        // The start's last write, which comes after Started is set.
        await Wait.Until(() => Logged("Accepting links"), "the start after the late sign-in", TimeSpan.FromSeconds(5));
        Assert.True(session.Started);
        Assert.True(session.SignedIn);
        Assert.True(File.Exists(Paths.Channel));
    }

    [Fact]
    public async Task AFailedResumeIsReportedAndNothingStarts()
    {
        SignedInBefore();
        File.WriteAllText(Paths.Copies, "a file where the copies folder goes");
        var session = Make();

        await session.StartAsync();

        Assert.False(session.Started);
        Assert.True(session.StartFailed);
        Assert.Null(session.Cycle);
        Assert.Empty(session.OpenCopies());
        Assert.False(File.Exists(Paths.Channel));
        Assert.Contains("local copies", _notifier.All);
    }

    [Fact]
    public async Task SendAndCheckInAfterACrashChecksTheKeptCopyIn()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var path = await OpenAsync(session);
        FakeApp.Write(path, "saved just before the crash");
        await Task.Delay(Fast.CrashPoll * 2); // the crash check has seen the app running
        _app.Crash();
        await Wait.Until(() => !_windows.Crashes.IsEmpty, "the crash window");
        var copy = Assert.Single(_windows.Crashes);
        Assert.True(session.HasCrashed(copy));

        session.SendAndCheckIn(copy);

        await Wait.Until(() => !File.Exists(path), "the copy checked in and deleted");
        Assert.False(session.HasCrashed(copy));
        Assert.Null(_server.Doc(5).Hold);
        Assert.Equal("saved just before the crash", _server.Doc(5).Content);
    }
}
