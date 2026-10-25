using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Cycle;
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

    private HelperSession Make(TimeSpan? signOutFlush = null)
    {
        var device = new DeviceIdentity(FakeApi.HereDevice, FakeApi.HereName);
        var session = new HelperSession(Paths, _keychain, device, _app, _windows, _notifier, new HelperLog(Paths.Log), _http, Fast,
            signOutFlush);
        _sessions.Add(session);
        return session;
    }

    private void SignedInBefore()
    {
        new HelperSettings { Server = Account }.Save(Paths);
        _keychain.Items[Account] = "hd_kept_token";
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

    private bool Revoked() => _http.Calls.Contains("DELETE healdan.test/api/v1/auth/tokens/current");

    private string[] LogLines() => File.Exists(Paths.Log) ? File.ReadAllLines(Paths.Log) : [];

    private int LogIndex(string text)
    {
        var lines = LogLines();
        var i = Array.FindIndex(lines, l => l.Contains(text, StringComparison.Ordinal));
        Assert.True(i >= 0, $"the log has \"{text}\":\n{string.Join("\n", lines)}");
        return i;
    }

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
        Assert.Equal(SessionServer.IssuedToken, _keychain.Items[Account]); // an address without a scheme is https
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
    public async Task ARefusedTokenAsksForTheSignInAgain()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();
        var asked = new TaskCompletionSource<SignInPrompt>(TaskCreationOptions.RunContinuationsAsynchronously);
        _windows.OnSignIn = async prompt =>
        {
            asked.TrySetResult(prompt);
            _server.Unauthorized = false;
            return await prompt.SignInAsync(prompt.Server!, "robert", Password) is null;
        };

        _server.Unauthorized = true;
        await session.Links.Handle("healdan://open/5?server=healdan.test");

        var again = await asked.Task.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.True(again.ServerFixed); // one server per helper while it runs
        Assert.Equal(Account, again.Server);
        await Wait.Until(() => _keychain.Items.GetValueOrDefault(Account) == SessionServer.IssuedToken, "the new token kept");
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
        Assert.Equal("hd_kept_token", _keychain.Items[Account]);
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
        Assert.Equal("hd_kept_token", _keychain.Items[Account]);
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
        Assert.Equal("hd_kept_token", _keychain.Items[Account]); // the next start carries on, and sends the save
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
        await Wait.Until(() => session.Started, "the start after the late sign-in", TimeSpan.FromSeconds(5));
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
