using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>
/// The helper signing in through a provider in the system browser (M4 spec §6.2): the address it opens, the loopback
/// listener, the state, the exchange of the one-time code, and what it keeps. <see cref="SessionServer"/> plays
/// Healdan, and a fake browser plays the person's: it asks Healdan to sign in, as the system browser would, and follows
/// Healdan's redirect to the helper's real listener on loopback.
/// </summary>
public sealed partial class ProviderSignInTests : IDisposable
{
    private const string Account = "https://healdan.example";

    private readonly FakeApi _documents = new();
    private readonly SessionServer _server;
    private readonly MemoryKeychain _keychain = new();
    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-provider-").FullName;

    public ProviderSignInTests() => _server = new SessionServer(_documents);

    public void Dispose() => TempFolder.Delete(_dir);

    [GeneratedRegex(@"^http://127\.0\.0\.1:\d{4,5}/$")]
    private static partial Regex LoopbackAddress();

    [GeneratedRegex("^[A-Za-z0-9._~-]{16,128}$")]
    private static partial Regex AllowedState();

    private DeviceIdentity Device() => DeviceIdentity.Load(HelperPaths.For(_dir), "Mac");

    private HelperLog Log() => new(Path.Combine(_dir, "helper.log"));

    private HealdanApi Api() => new(new Uri(Account), _server);

    private SignIn Make(HealdanApi api, TimeSpan? browserTimeout = null, HelperLog? log = null) =>
        new(api, _keychain, Device(), log: log, browserTimeout: browserTimeout);

    private static string Query(Uri address, string name) =>
        System.Web.HttpUtility.ParseQueryString(address.Query)[name] ?? "";

    private static string S256(string verifier) =>
        Convert.ToBase64String(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    [Fact]
    public async Task SignsInAndKeepsTheToken()
    {
        var browser = new FakeBrowser(_server);
        var api = Api();

        await Make(api).SignInWithProviderAsync("entra", browser);

        // It opened Healdan's sign-in for the provider, with the loopback address, a state and the challenge.
        var opened = browser.Opened!;
        Assert.Equal("https", opened.Scheme);
        Assert.Equal("healdan.example", opened.Host);
        Assert.Equal("/api/signin/entra", opened.AbsolutePath);
        Assert.Equal("helper", Query(opened, "client"));
        Assert.Equal("S256", Query(opened, "code_challenge_method"));
        var start = Assert.Single(_server.Starts);
        Assert.Matches(LoopbackAddress(), start.RedirectUri);
        Assert.Matches(AllowedState(), start.State);
        Assert.Equal(43, start.CodeChallenge.Length);

        // It exchanged the code with the verifier of that challenge, the same address, and this computer.
        var exchange = Assert.Single(_server.Exchanges);
        Assert.Equal(Assert.Single(_server.Codes), (string?)exchange["code"]);
        Assert.Equal(start.CodeChallenge, S256((string)exchange["codeVerifier"]!));
        Assert.Equal(start.RedirectUri, (string?)exchange["redirectUri"]);
        Assert.Equal(Device().Name, (string?)exchange["name"]);
        Assert.Equal(Device().Id, (string?)exchange["device"]!["id"]);
        Assert.Null(exchange["username"]);
        Assert.Null(exchange["password"]);

        // It kept the token as a password sign-in does, and the browser was sent on to the done page.
        Assert.Equal(new KeptToken(Device().Id, SessionServer.IssuedToken), KeptToken.Read(_keychain.Items[Account]));
        Assert.Equal(SessionServer.IssuedToken, api.Token);
        Assert.Equal(new Uri($"{Account}/api/signin/done"), await browser.Done!);
    }

    [Fact]
    public async Task ChecksTheServerIsHealdanFirst()
    {
        var browser = new FakeBrowser(_server);
        var api = new HealdanApi(new Uri("https://other.test"), _server);

        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(api).SignInWithProviderAsync("entra", browser));

        Assert.Contains("Healdan", e.Message);
        Assert.Null(browser.Opened);
        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public async Task RefusesAnApiVersionItDoesNotSpeak()
    {
        var browser = new FakeBrowser(_server);
        var api = new HealdanApi(new Uri("https://newer.test"), _server);

        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(api).SignInWithProviderAsync("entra", browser));

        Assert.Contains("v2", e.Message);
        Assert.Null(browser.Opened);
    }

    [Fact]
    public async Task AProvidersIdIsEscapedInTheAddress()
    {
        var idle = new IdleBrowser();

        await Assert.ThrowsAsync<OperationCanceledException>(
            () => Make(Api(), TimeSpan.FromMilliseconds(50)).SignInWithProviderAsync("a/../b?c#d", idle));

        Assert.Equal("/api/signin/a%2F..%2Fb%3Fc%23d", idle.Opened!.AbsolutePath);
        Assert.Equal("", idle.Opened.Fragment);
    }

    [Fact]
    public async Task NeedsAProvider()
    {
        var browser = new FakeBrowser(_server);

        await Assert.ThrowsAsync<ArgumentException>(() => Make(Api()).SignInWithProviderAsync(" ", browser));

        Assert.Null(browser.Opened);
    }

    [Fact]
    public async Task StateMismatchRefused()
    {
        var browser = new FakeBrowser(_server, back => new Uri(Regex.Replace(back.AbsoluteUri, "state=[^&]*", "state=somebody-elses-state")));

        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(Api()).SignInWithProviderAsync("entra", browser));

        Assert.Equal("The sign-in came back for another request. Try again.", e.Message);
        Assert.Empty(_server.Exchanges); // the code was never sent anywhere
        Assert.Empty(_keychain.Items);
        Assert.Equal(new Uri($"{Account}/api/signin/done?error=expired"), await browser.Done!);
    }

    [Fact]
    public async Task ARequestWithNoStateIsRefusedToo()
    {
        var browser = new FakeBrowser(_server, back => new Uri(back.GetLeftPart(UriPartial.Path) + "?code=guessed"));

        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(Api()).SignInWithProviderAsync("entra", browser));

        Assert.Equal("The sign-in came back for another request. Try again.", e.Message);
        Assert.Empty(_server.Exchanges);
        Assert.Equal(new Uri($"{Account}/api/signin/done?error=expired"), await browser.Done!);
    }

    [Theory]
    [InlineData("no-group")]
    [InlineData("email-taken")]
    [InlineData("username-taken")]
    [InlineData("account")]
    [InlineData("provider")]
    [InlineData("expired")]
    public async Task RefusalReported(string code)
    {
        _server.RefuseProviderWith = code;
        var browser = new FakeBrowser(_server);
        var api = Api();

        var e = await Assert.ThrowsAsync<ProviderRefusedException>(() => Make(api).SignInWithProviderAsync("entra", browser));

        Assert.Equal(code, e.Code);
        Assert.Equal($"The sign-in was refused ({code}).", e.Message);
        Assert.Empty(_server.Exchanges);
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
        Assert.Equal(new Uri($"{Account}/api/signin/done?error={code}"), await browser.Done!);
    }

    [Fact]
    public async Task ARefusalWithAStrangeCodeIsTheProvidersTrouble()
    {
        _server.RefuseProviderWith = "<script>alert(1)</script>";
        var browser = new FakeBrowser(_server);

        var e = await Assert.ThrowsAsync<ProviderRefusedException>(() => Make(Api()).SignInWithProviderAsync("entra", browser));

        Assert.Equal("provider", e.Code);
        Assert.Equal(new Uri($"{Account}/api/signin/done?error=provider"), await browser.Done!);
    }

    [Fact]
    public async Task ACodeThatIsRefusedLeavesNothingKept()
    {
        // The server turns the exchange down (the code is used, or took too long): the helper stays signed out.
        var browser = new FakeBrowser(_server);
        _server.RefuseExchanges = true;
        var api = Api();

        var e = await Assert.ThrowsAsync<ApiException>(() => Make(api).SignInWithProviderAsync("entra", browser));

        Assert.Equal(401, e.Status);
        Assert.Equal("sign-in-failed", e.Code);
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
    }

    [Fact]
    public async Task BrowserNeverComesBack()
    {
        var idle = new IdleBrowser();

        var e = await Assert.ThrowsAsync<OperationCanceledException>(
            () => Make(Api(), TimeSpan.FromMilliseconds(150)).SignInWithProviderAsync("entra", idle));

        Assert.Equal("The sign-in in your browser didn't finish within 5 minutes.", e.Message);
        Assert.Empty(_keychain.Items);
        Assert.Empty(_server.Exchanges);
        // It stopped listening: nothing else can come in on that port.
        using var http = new HttpClient();
        await Assert.ThrowsAsync<HttpRequestException>(() => http.GetAsync(Query(idle.Opened!, "redirect_uri") + "?code=late&state=late"));
    }

    [Fact]
    public async Task TheUserCancelsTheWait()
    {
        var idle = new IdleBrowser();
        using var cancel = new CancellationTokenSource();
        var signIn = Make(Api()).SignInWithProviderAsync("entra", idle, cancel.Token);
        await Wait.Until(() => idle.Opened is not null, "the browser is opened");

        await cancel.CancelAsync();

        var e = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => signIn);
        Assert.DoesNotContain("5 minutes", e.Message);
        var free = new TcpListener(IPAddress.Loopback, new Uri(Query(idle.Opened!, "redirect_uri")).Port);
        free.Start(); // the port is free again
        free.Stop();
    }

    [Fact]
    public async Task ABrowserThatCannotBeOpenedFreesThePort()
    {
        var port = 0;
        var browser = new ThrowingBrowser(address => port = new Uri(Query(address, "redirect_uri")).Port);

        await Assert.ThrowsAsync<IOException>(() => Make(Api()).SignInWithProviderAsync("entra", browser));

        var free = new TcpListener(IPAddress.Loopback, port);
        free.Start();
        free.Stop();
        Assert.Empty(_keychain.Items);
    }

    private sealed class ThrowingBrowser(Action<Uri> seen) : IBrowser
    {
        public void Open(Uri address)
        {
            seen(address);
            throw new IOException("The computer has no app to open it with.");
        }
    }

    [Fact]
    public async Task NoTokenInAnyLog()
    {
        var log = Log();
        var browser = new FakeBrowser(_server);
        await Make(Api(), log: log).SignInWithProviderAsync("entra", browser);

        var secrets = new List<string> { SessionServer.IssuedToken, Assert.Single(_server.Codes) };
        var exchange = Assert.Single(_server.Exchanges);
        secrets.Add((string)exchange["codeVerifier"]!);
        secrets.Add(Assert.Single(_server.Starts).State);

        // A refusal and a mismatch too: nothing they say or log has the state or a code in it.
        var mismatch = new FakeBrowser(_server, back => new Uri(Regex.Replace(back.AbsoluteUri, "state=[^&]*", "state=somebody-elses-state")));
        var e1 = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(Api(), log: log).SignInWithProviderAsync("entra", mismatch));
        _server.RefuseProviderWith = "no-group";
        var refused = new FakeBrowser(_server);
        var e2 = await Assert.ThrowsAsync<ProviderRefusedException>(() => Make(Api(), log: log).SignInWithProviderAsync("entra", refused));
        secrets.AddRange(_server.Codes);
        secrets.AddRange(_server.Starts.Select(s => s.State));

        var logged = File.ReadAllText(Path.Combine(_dir, "helper.log"));
        Assert.NotEmpty(logged); // it did log, so the test means something
        foreach (var secret in secrets.Distinct())
        {
            Assert.DoesNotContain(secret, logged);
            Assert.DoesNotContain(secret, e1.Message);
            Assert.DoesNotContain(secret, e2.Message);
        }
        foreach (var file in Directory.EnumerateFiles(_dir, "*", SearchOption.AllDirectories))
            foreach (var secret in secrets.Distinct())
                Assert.DoesNotContain(secret, await File.ReadAllTextAsync(file));
    }
}
