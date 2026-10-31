using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Addin;
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

    private HelperSession Make(TimeSpan? signOutFlush = null, OfficeApps? apps = null, ICertificateTrust? trust = null,
        AddinCertificate? certificate = null, IReadOnlyList<int>? addinPorts = null)
    {
        var device = new DeviceIdentity(FakeApi.HereDevice, FakeApi.HereName);
        var session = new HelperSession(Paths, _keychain, device, apps ?? TestApps.Of(_app), _windows, _notifier, new HelperLog(Paths.Log), _http, Fast,
            signOutFlush, trust, certificate, addinPorts);
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

    /// <summary>
    /// M3.5 spec §5.14: a session with Word, whose document 5 is open and has crashed, and then Word restores its window
    /// at the copy's path and the helper saves it back. Returns the crashed copy, as the crash window holds it.
    /// </summary>
    private async Task<(HelperSession Session, FakeScripting Scripting, CopyState Crashed)> WordRecoveredAsync(params RenameResult[] renames)
    {
        var scripting = new FakeScripting { WindowText = "the work Word recovered" };
        scripting.AnswerRenames(renames);
        var office = new SeenLocks(new MicrosoftOfficeApp(scripting, scripting.IsRunning));
        SignedInBefore();
        var session = Make(apps: TestApps.Of(_app, microsoftOffice: office, appFor: _ => "Microsoft Office"));
        await session.StartAsync();
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(scripting.Opened).Path;
        await Wait.Until(() => office.LockSeen(path) && office.RunningSeen(path), "Word seen with the copy open");
        scripting.SetRunning(OfficeProgram.Word, false);
        await Wait.Until(() => !_windows.Crashes.IsEmpty, "the crash window");
        var crashed = Assert.Single(_windows.Crashes);

        scripting.Recover(OfficeProgram.Word, path);

        await Wait.Until(() => _windows.Dismissed.Contains(path), "the crash window dismissed");
        await Wait.Until(() => _server.Saves.Count == 1, "the recovered work sent");
        Assert.False(session.HasCrashed(crashed));
        return (session, scripting, crashed);
    }

    [Fact]
    public async Task SendAndCheckInIsRefusedOnceWordHasTheCopyAgain()
    {
        // The rename waits on unsaved changes, so the copy is still where it crashed, and Word has its window open.
        var (session, _, crashed) = await WordRecoveredAsync(RenameResult.Unsaved);
        await Wait.Until(() => session.Cycle!.Unsent() == 0, "the save back done with");

        session.SendAndCheckIn(crashed); // a crash window's button, clicked just as it went

        await Wait.Until(() => _notifier.All.Contains("open again"), "the user told why");
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.All(_server.Saves, save => Assert.True(save.KeepCheckedOut));
        Assert.True(File.Exists(crashed.Path));
        Assert.Equal([crashed.Path], session.OpenCopies().Select(c => c.Path));
        Assert.NotNull(_server.Doc(5).Hold);
    }

    [Fact]
    public async Task SendAndCheckInIsRefusedWhileTheSaveBackLooksForWordsWindow()
    {
        // The final review's Minor 2: once Word has the crashed copy open again (its owner file written since the crash),
        // the copy is its window's, even while the save-back is still looking for that window by its path. Checking it
        // in then would delete it from under Word's recovered window.
        var scripting = new FakeScripting { WindowText = "the work Word recovered", NeverListed = true };
        var office = new SeenLocks(new MicrosoftOfficeApp(scripting, scripting.IsRunning));
        SignedInBefore();
        var session = Make(apps: TestApps.Of(_app, microsoftOffice: office, appFor: _ => "Microsoft Office"));
        await session.StartAsync();
        await session.Links.Handle("healdan://open/5?server=healdan.test");
        var path = Assert.Single(scripting.Opened).Path;
        await Wait.Until(() => office.LockSeen(path) && office.RunningSeen(path), "Word seen with the copy open");
        scripting.SetRunning(OfficeProgram.Word, false);
        await Wait.Until(() => !_windows.Crashes.IsEmpty, "the crash window");
        var crashed = Assert.Single(_windows.Crashes);
        scripting.Recover(OfficeProgram.Word, path);
        await Wait.Until(() => scripting.IsOpenAsked > 0, "the save-back looking for Word's window");

        session.SendAndCheckIn(crashed);

        await Wait.Until(() => _notifier.All.Contains("open again"), "the user told why");
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.Empty(_server.Saves);
        Assert.True(File.Exists(path));
        Assert.NotNull(_server.Doc(5).Hold);

        // Once Word lists it, the recovered work is saved back and sent, as before.
        scripting.NeverListed = false;
        await Wait.Until(() => _server.Saves.Count == 1, "the recovered work sent");
        Assert.True(_server.Saves[0].KeepCheckedOut);
    }

    [Fact]
    public async Task ReopenAfterWordsRenameBringsUpTheRenamedCopy()
    {
        var (session, scripting, crashed) = await WordRecoveredAsync();
        var renamed = Path.Combine(Paths.Copies, "Lease agreement #5 v1.1.docx");
        await Wait.Until(() => session.OpenCopies().Any(c => c.Path == renamed), "renamed after the save");

        session.Bring(crashed); // the crash window's Reopen, or the menu, holding the copy's old path
        session.SendAndCheckIn(crashed);

        await Wait.Until(() => scripting.Opened.Count == 2, "brought up in Word");
        Assert.Equal((OfficeProgram.Word, renamed), scripting.Opened[^1]);
        Assert.Empty(_app.Opened); // never handed to LibreOffice as a copy it doesn't know
        await Wait.Until(() => _notifier.All.Contains("open again"), "the check-in refused");
        Assert.Equal(0, _server.Count("DELETE /documents/5/checkout"));
        Assert.True(File.Exists(renamed));
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

    /// <summary>A port nothing listens on now.</summary>
    private static int FreePort()
    {
        var probe = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        try
        {
            return ((IPEndPoint)probe.LocalEndpoint).Port;
        }
        finally
        {
            probe.Stop();
        }
    }

    /// <summary><c>GET /v1/hello</c> to the add-in's port, as the add-in's page on the server sends it, trusting only the helper's certificate.</summary>
    private static async Task<HttpResponseMessage> HelloAsync(int port, string thumbprint)
    {
        using var http = new HttpClient(new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert?.Thumbprint == thumbprint,
        }) { Timeout = TimeSpan.FromSeconds(30) };
        using var request = new HttpRequestMessage(HttpMethod.Get, $"https://127.0.0.1:{port}/v1/hello");
        request.Headers.Add("Origin", Account);
        return await http.SendAsync(request);
    }

    [Fact]
    public async Task TheAddinsPortOpensOnceSetUpHasTheMacTrustTheCertificate()
    {
        SignedInBefore();
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        var port = FreePort();
        var session = Make(trust: trust, certificate: certificate, addinPorts: [port]);

        await session.StartAsync();

        Assert.True(session.Started);
        Assert.Null(session.AddinPort); // no certificate: Set Up Microsoft Office hasn't run
        Assert.False(File.Exists(Paths.AddinCertificate)); // and starting made none
        Assert.True(Logged("Set Up Microsoft Office"));

        // Made, but not trusted yet: still closed, as Office's web view would refuse it.
        string thumbprint;
        using (var made = certificate.LoadOrCreate())
            thumbprint = made.Thumbprint;
        session.EnsureAddin();
        Assert.Null(session.AddinPort);

        // Set Up has the Mac trust it: the port opens with no restart, and answers the server's own pages.
        trust.Trusted.Add(thumbprint);
        session.EnsureAddin();
        Assert.Equal(port, session.AddinPort);
        Assert.True(Logged($"127.0.0.1:{port}"));
        using (var hello = await HelloAsync(port, thumbprint))
        {
            Assert.Equal(HttpStatusCode.OK, hello.StatusCode);
            Assert.Equal(Account, (string?)JsonNode.Parse(await hello.Content.ReadAsStringAsync())!["server"]);
        }
        session.EnsureAddin(); // nothing to do: the same certificate, still open
        Assert.Equal(port, session.AddinPort);

        // Quitting closes it.
        session.Dispose();
        Assert.Null(session.AddinPort);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => HelloAsync(port, thumbprint));
    }

    [Fact]
    public async Task WithoutAMacsTrustThereIsNoAddinsPort()
    {
        SignedInBefore();
        var session = Make(); // off a Mac: no trust to ask

        await session.StartAsync();

        Assert.True(session.Started);
        Assert.Null(session.AddinPort);
        session.EnsureAddin();
        Assert.Null(session.AddinPort);
    }

    /// <summary>A request to the add-in's port as the add-in's page on the server sends it, trusting only the helper's certificate.</summary>
    private static async Task<(HttpStatusCode Status, JsonNode? Body)> AddinAsync(int port, string thumbprint, string route,
        JsonObject? body = null, string? key = null)
    {
        using var http = new HttpClient(new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert?.Thumbprint == thumbprint,
        }) { Timeout = TimeSpan.FromSeconds(30) };
        using var request = new HttpRequestMessage(HttpMethod.Post, $"https://127.0.0.1:{port}/v1/{route}")
        {
            Content = new StringContent((body ?? new JsonObject()).ToJsonString(), Encoding.UTF8, "application/json"),
        };
        request.Headers.Add("Origin", Account);
        request.Headers.Add("X-Healdan-Protocol", "1");
        if (key is not null)
            request.Headers.Add("X-Healdan-Key", key);
        using var response = await http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        return (response.StatusCode, text.Length == 0 ? null : JsonNode.Parse(text));
    }

    /// <summary>Pairs <paramref name="app"/> over the add-in's port, with the code the helper's window shows, and returns its key.</summary>
    private async Task<string> PairAddinAsync(int port, string thumbprint, string app = "Word")
    {
        var start = await AddinAsync(port, thumbprint, "pair/start", new JsonObject { ["app"] = app });
        var finish = await AddinAsync(port, thumbprint, "pair/finish",
            new JsonObject { ["pairingId"] = (string?)start.Body!["pairingId"], ["code"] = _windows.ShownCode });
        Assert.Equal(HttpStatusCode.OK, finish.Status);
        return (string)finish.Body!["key"]!;
    }

    /// <summary>Set Up made the certificate and the Mac trusts it: its thumbprint.</summary>
    private static string Trusted(AddinCertificate certificate, FakeCertificateTrust trust)
    {
        using var made = certificate.LoadOrCreate();
        trust.Trusted.Add(made.Thumbprint);
        return made.Thumbprint;
    }

    /// <summary>Word through a fake scripting seam, beside LibreOffice, for the add-in's document routes.</summary>
    private OfficeApps WithWord(FakeScripting scripting) =>
        TestApps.Of(_app, microsoftOffice: new MicrosoftOfficeApp(scripting, scripting.IsRunning));

    [Fact]
    public async Task TheAddinsPortStaysOpenSignedOutAndSaysSo()
    {
        SignedInBefore();
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        var thumbprint = Trusted(certificate, trust);
        var port = FreePort();
        var session = Make(apps: WithWord(new FakeScripting()), trust: trust, certificate: certificate, addinPorts: [port]);
        await session.StartAsync();
        var key = await PairAddinAsync(port, thumbprint);
        Assert.Equal(HttpStatusCode.OK, (await AddinAsync(port, thumbprint, "state", new JsonObject { ["url"] = "" }, key)).Status);

        // A pairing under way when the user signs out goes on.
        var excel = await AddinAsync(port, thumbprint, "pair/start", new JsonObject { ["app"] = "Excel" });
        var code = _windows.ShownCode;

        await session.SignOutAsync();

        Assert.True(Revoked());
        Assert.Equal(port, session.AddinPort); // spec §6.3: the pane can say the helper is signed out
        Assert.Equal(Account, (string?)JsonNode.Parse(await (await HelloAsync(port, thumbprint)).Content.ReadAsStringAsync())!["server"]);
        var state = await AddinAsync(port, thumbprint, "state", new JsonObject { ["url"] = "" }, key);
        Assert.Equal((HttpStatusCode.Conflict, """{"error":"signed-out"}"""), (state.Status, state.Body!.ToJsonString()));
        var ping = await AddinAsync(port, thumbprint, "ping", key: key);
        Assert.Equal((HttpStatusCode.OK, """{"ok":true,"signedIn":false}"""), (ping.Status, ping.Body!.ToJsonString()));
        var finish = await AddinAsync(port, thumbprint, "pair/finish", new JsonObject { ["pairingId"] = (string?)excel.Body!["pairingId"], ["code"] = code });
        Assert.Equal(HttpStatusCode.OK, finish.Status);

        // Quitting closes it, and ends any pairing.
        await AddinAsync(port, thumbprint, "pair/start", new JsonObject { ["app"] = "PowerPoint" });
        Assert.NotNull(_windows.ShownCode);
        session.Dispose();
        Assert.Null(session.AddinPort);
        Assert.Null(_windows.ShownCode);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => HelloAsync(port, thumbprint));
    }

    [Fact]
    public async Task TheAddinsPortOpensBeforeAnySignIn()
    {
        new HelperSettings { Server = Account }.Save(Paths); // signed out before, so no token is kept
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        var thumbprint = Trusted(certificate, trust);
        var port = FreePort();
        var session = Make(apps: WithWord(new FakeScripting()), trust: trust, certificate: certificate, addinPorts: [port]);

        await session.StartAsync(); // the Sign In window is closed

        Assert.False(session.SignedIn);
        Assert.Equal(port, session.AddinPort);
        var key = await PairAddinAsync(port, thumbprint); // pairing needs no sign-in
        IsSignedOut(await AddinAsync(port, thumbprint, "state", new JsonObject { ["url"] = "" }, key));

        _windows.OnSignIn = async prompt => await prompt.SignInAsync(prompt.Server!, "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.Equal(port, session.AddinPort); // the same port all along
        var state = await AddinAsync(port, thumbprint, "state", new JsonObject { ["url"] = "" }, key);
        Assert.Equal((HttpStatusCode.OK, """{"known":false}"""), (state.Status, state.Body!.ToJsonString()));
    }

    [Fact]
    public async Task ASaveToHealdanTicketDoesntOutliveASignOut()
    {
        SignedInBefore();
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        var thumbprint = Trusted(certificate, trust);
        var port = FreePort();
        var scripting = new FakeScripting { Front = _ => new FrontDocument(null, "", "Document1"), MarksShown = true };
        scripting.SetRunning(OfficeProgram.Word, true);
        _windows.Answer = WindowAnswers.Save(9, "Memo", "docx", new JsonObject(), WindowAnswers.TempPath(Paths, "docx"));
        var session = Make(apps: WithWord(scripting), trust: trust, certificate: certificate, addinPorts: [port]);
        await session.StartAsync();
        var key = await PairAddinAsync(port, thumbprint);
        var saveNew = await AddinAsync(port, thumbprint, "save-new", new JsonObject { ["app"] = "Word", ["url"] = "", ["asNew"] = false }, key);
        var ticket = (string)saveNew.Body!["ticket"]!;

        await session.SignOutAsync();
        _windows.OnSignIn = async prompt => await prompt.SignInAsync(prompt.Server!, "robert", Password) is null;
        await session.SignInAgainAsync();
        Assert.True(session.Started);

        // The window the ticket was for was chosen under the old sign-in: it may be another document, or another
        // server's folder, by now.
        var file = await AddinAsync(port, thumbprint, "file",
            new JsonObject { ["ticket"] = ticket, ["mark"] = "hd-mark-1", ["bytes"] = Convert.ToBase64String("the window's bytes"u8.ToArray()) }, key);

        Assert.Equal((HttpStatusCode.Conflict, """{"error":"no-ticket"}"""), (file.Status, file.Body!.ToJsonString()));
        Assert.DoesNotContain(_server.Calls, c => c.StartsWith("filed ", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ACertificateInItsLastThirtyDaysStillServesAndSetUpsRenewalTakesOver()
    {
        SignedInBefore();
        var clock = new PausableClock();
        clock.Pause();
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, clock);
        var old = Trusted(certificate, trust);
        clock.Advance(TimeSpan.FromDays(771)); // 29 days left: Set Up offers to renew it
        var port = FreePort();
        var session = Make(trust: trust, certificate: certificate, addinPorts: [port]);

        await session.StartAsync();

        Assert.True(certificate.NeedsSetUp(trust)); // the menu offers Set Up
        Assert.Equal(port, session.AddinPort); // and meanwhile the add-in still works
        Assert.Equal(HttpStatusCode.OK, (await HelloAsync(port, old)).StatusCode);

        // Set Up renews it, and the Mac trusts the new one: the port serves it, on the same port.
        var renewed = Trusted(certificate, trust);
        Assert.NotEqual(old, renewed);
        session.EnsureAddin();

        Assert.Equal(port, session.AddinPort);
        Assert.Equal(HttpStatusCode.OK, (await HelloAsync(port, renewed)).StatusCode);
        await Assert.ThrowsAnyAsync<HttpRequestException>(() => HelloAsync(port, old));
    }

    [Fact]
    public async Task AnExpiredCertificateKeepsTheAddinsPortClosed()
    {
        SignedInBefore();
        var clock = new PausableClock();
        clock.Pause();
        var trust = new FakeCertificateTrust();
        var certificate = new AddinCertificate(Paths, clock);
        Trusted(certificate, trust);
        clock.Advance(TimeSpan.FromDays(801));
        var session = Make(trust: trust, certificate: certificate, addinPorts: [FreePort()]);

        await session.StartAsync();

        Assert.True(session.Started);
        Assert.Null(session.AddinPort);
    }

    private static void IsSignedOut((HttpStatusCode Status, JsonNode? Body) answer) =>
        Assert.Equal((HttpStatusCode.Conflict, """{"error":"signed-out"}"""), (answer.Status, answer.Body?.ToJsonString()));
}
