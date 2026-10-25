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
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private HelperSession Make()
    {
        var device = new DeviceIdentity(FakeApi.HereDevice, FakeApi.HereName);
        var session = new HelperSession(Paths, _keychain, device, _app, _windows, _notifier, new HelperLog(Paths.Log), _http, Fast);
        _sessions.Add(session);
        return session;
    }

    private void SignedInBefore()
    {
        new HelperSettings { Server = Account }.Save(Paths);
        _keychain.Items[Account] = "hd_kept_token";
    }

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
}
