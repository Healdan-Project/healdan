using System.Net;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Api;

namespace Healdan.Helper.Core.Tests.Session;

public sealed class SignInTests : IDisposable
{
    private const string Account = "https://healdan.example";
    private const string Password = "pa55-w0rd-secret";
    private const string Token = "hd_tokenvalue123";

    private const string ServerJson = """{"product":"Healdan","version":"9.3.1","apiVersion":"v1","labels":{"matter":"Matter","matters":"Matters"},"signIn":["password"]}""";

    private const string SignedInJson = $$$"""
        {"token":"{{{Token}}}","id":7,"name":"Healdan Helper on Mac","expiresAt":"2027-01-01T00:00:00Z",
         "user":{"id":1,"username":"ann","name":"Ann","admin":false}}
        """;

    private readonly FakeHandler _handler = new();
    private readonly MemoryKeychain _keychain = new();
    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-signin-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    private HealdanApi Api() => new(new Uri(Account), _handler);

    private DeviceIdentity Device() => DeviceIdentity.Load(HelperPaths.For(_dir), "Mac");

    private SignIn Make(HealdanApi api) => new(api, _keychain, Device());

    /// <summary>The token as the keychain keeps it for this computer: with the device id it was issued for.</summary>
    private string KeptHere(string token) => new KeptToken(Device().Id, token).ToSecret();

    [Fact]
    public async Task SignInSendsDeviceAndStoresOnlyToken()
    {
        _handler.Enqueue(HttpStatusCode.OK, ServerJson);
        _handler.Enqueue(HttpStatusCode.OK, SignedInJson);
        var device = Device();
        var api = Api();

        await Make(api).SignInAsync("ann", Password);

        var request = _handler.Requests[1];
        Assert.EndsWith("/api/v1/auth/tokens", request.Uri.AbsolutePath);
        Assert.Contains($"\"id\":\"{device.Id}\"", request.BodyText);
        Assert.Contains("\"name\":\"Healdan Helper on Mac\"", request.BodyText);
        Assert.Equal(new KeptToken(device.Id, Token), KeptToken.Read(_keychain.Items[Account]));
        Assert.Equal(Token, api.Token);
        foreach (var file in Directory.EnumerateFiles(_dir, "*", SearchOption.AllDirectories))
        {
            var text = File.ReadAllText(file);
            Assert.DoesNotContain(Password, text);
            Assert.DoesNotContain(Token, text);
        }
    }

    [Fact]
    public async Task SignInRefusesAServerThatIsNotHealdan()
    {
        _handler.Enqueue(HttpStatusCode.OK, ServerJson.Replace("\"Healdan\"", "\"Other\""));
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(Api()).SignInAsync("ann", Password));
        Assert.Contains("Healdan", e.Message);
        Assert.Single(_handler.Requests);
        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public async Task SignInRefusesAnApiVersionItDoesNotSpeak()
    {
        _handler.Enqueue(HttpStatusCode.OK, ServerJson.Replace("\"v1\"", "\"v2\""));
        var e = await Assert.ThrowsAsync<InvalidOperationException>(() => Make(Api()).SignInAsync("ann", Password));
        Assert.Contains("v2", e.Message);
        Assert.Single(_handler.Requests);
    }

    [Fact]
    public async Task WrongPasswordLeavesNothingBehindAndSaysNothingOfIt()
    {
        _handler.Enqueue(HttpStatusCode.OK, ServerJson);
        _handler.Enqueue(HttpStatusCode.Unauthorized, """{"code":"bad-credentials","detail":"Wrong username or password."}""");
        var e = await Assert.ThrowsAsync<ApiException>(() => Make(Api()).SignInAsync("ann", Password));
        Assert.DoesNotContain(Password, e.Message);
        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public async Task ResumeWithAValidTokenSucceeds()
    {
        _keychain.Items[Account] = KeptHere(Token);
        _handler.Enqueue(HttpStatusCode.OK, """{"id":1,"username":"ann","name":"Ann","admin":false}""");
        var api = Api();
        Assert.True(await Make(api).TryResumeAsync());
        Assert.Equal($"Bearer {Token}", _handler.Requests[0].Authorization);
        Assert.EndsWith("/api/v1/me", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task ResumeWithNoTokenIsFalseAndAsksNothing()
    {
        Assert.False(await Make(Api()).TryResumeAsync());
        Assert.Empty(_handler.Requests);
    }

    [Fact]
    public async Task ResumeWith401ClearsToken()
    {
        _keychain.Items[Account] = KeptHere(Token);
        _handler.Enqueue(HttpStatusCode.Unauthorized, """{"code":"unauthorized","detail":"Sign in again."}""");
        var api = Api();
        Assert.False(await Make(api).TryResumeAsync());
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
    }

    [Fact]
    public async Task ResumeKeepsTheTokenWhenTheServerCannotBeReached()
    {
        _keychain.Items[Account] = KeptHere(Token);
        _handler.EnqueueResponse(() => throw new HttpRequestException("offline"));
        await Assert.ThrowsAsync<HttpRequestException>(() => Make(Api()).TryResumeAsync());
        Assert.Equal(KeptHere(Token), _keychain.Items[Account]);
    }

    [Fact]
    public async Task ResumeRevokesATokenIssuedToAnotherDevice()
    {
        _keychain.Items[Account] = new KeptToken("00d0aa76-other-device", Token).ToSecret();
        _handler.Enqueue(HttpStatusCode.NoContent, "");
        var api = Api();

        Assert.False(await Make(api).TryResumeAsync());

        var revoke = Assert.Single(_handler.Requests);
        Assert.Equal(HttpMethod.Delete, revoke.Method);
        Assert.EndsWith("/api/v1/auth/tokens/current", revoke.Uri.AbsolutePath);
        Assert.Equal($"Bearer {Token}", revoke.Authorization);
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
    }

    [Fact]
    public async Task ResumeRevokesATokenKeptWithoutItsDevice()
    {
        _keychain.Items[Account] = Token; // as helpers before the device was kept with it did
        _handler.Enqueue(HttpStatusCode.NoContent, "");
        var api = Api();

        Assert.False(await Make(api).TryResumeAsync());

        var revoke = Assert.Single(_handler.Requests);
        Assert.Equal(HttpMethod.Delete, revoke.Method);
        Assert.Equal($"Bearer {Token}", revoke.Authorization);
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
    }

    [Fact]
    public async Task ResumeForgetsAnotherDevicesTokenWhenHealdanCannotRevokeIt()
    {
        _keychain.Items[Account] = new KeptToken("00d0aa76-other-device", Token).ToSecret();
        _handler.EnqueueResponse(() => throw new HttpRequestException("offline"));
        var log = new HelperLog(Path.Combine(_dir, "helper.log"));
        var api = Api();

        Assert.False(await new SignIn(api, _keychain, Device(), log: log).TryResumeAsync());

        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
        var text = File.ReadAllText(log.Path);
        Assert.Contains("00d0aa76-other-device", text);
        Assert.DoesNotContain(Token, text);
    }

    [Fact]
    public async Task ResumeForgetsAnotherDevicesTokenHealdanAlreadyRefuses()
    {
        _keychain.Items[Account] = new KeptToken("00d0aa76-other-device", Token).ToSecret();
        _handler.Enqueue(HttpStatusCode.Unauthorized, """{"code":"unauthorized","detail":"Sign in again."}""");

        Assert.False(await Make(Api()).TryResumeAsync());

        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public void AKeptTokenReadsBackAsItWasWritten()
    {
        var kept = new KeptToken("265fc672-device", Token);
        Assert.Equal(kept, KeptToken.Read(kept.ToSecret()));
        Assert.Null(KeptToken.Read(Token));
        Assert.Null(KeptToken.Read("{}"));
        Assert.Null(KeptToken.Read("""{"device":"265fc672-device"}"""));
        Assert.Null(KeptToken.Read("{not json"));
    }

    [Fact]
    public async Task SignOutFlushesThenRevokes()
    {
        _keychain.Items[Account] = KeptHere(Token);
        var api = Api();
        api.Token = Token;
        _handler.Enqueue(HttpStatusCode.NoContent, "");
        var flushedBeforeRevoke = false;

        await Make(api).SignOutAsync(() =>
        {
            flushedBeforeRevoke = _handler.Requests.Count == 0;
            return Task.CompletedTask;
        });

        Assert.True(flushedBeforeRevoke);
        Assert.Equal(HttpMethod.Delete, _handler.Requests[0].Method);
        Assert.EndsWith("/api/v1/auth/tokens/current", _handler.Requests[0].Uri.AbsolutePath);
        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
    }

    [Fact]
    public async Task SignOutWithAnAlreadyRevokedTokenStillClears()
    {
        _keychain.Items[Account] = KeptHere(Token);
        var api = Api();
        api.Token = Token;
        _handler.Enqueue(HttpStatusCode.Unauthorized, """{"code":"unauthorized","detail":"x"}""");
        await Make(api).SignOutAsync(() => Task.CompletedTask);
        Assert.Empty(_keychain.Items);
    }

    [Fact]
    public async Task SignOutKeepsTheTokenWhenFlushFails()
    {
        _keychain.Items[Account] = KeptHere(Token);
        var api = Api();
        api.Token = Token;
        await Assert.ThrowsAsync<IOException>(() => Make(api).SignOutAsync(() => throw new IOException("busy")));
        Assert.Equal(KeptHere(Token), _keychain.Items[Account]);
        Assert.Empty(_handler.Requests);
    }

    [Fact]
    public async Task SignOutGivesUpOnAServerThatDoesntAnswer()
    {
        _keychain.Items[Account] = KeptHere(Token);
        var api = new HealdanApi(new Uri(Account), new SilentHandler());
        api.Token = Token;
        var signIn = new SignIn(api, _keychain, Device(), revokeTimeout: TimeSpan.FromMilliseconds(200));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => signIn.SignOutAsync(() => Task.CompletedTask));

        Assert.Equal(KeptHere(Token), _keychain.Items[Account]);
    }

    [Fact]
    public void ForgetHereClearsTheTokenWithoutAskingTheServer()
    {
        _keychain.Items[Account] = KeptHere(Token);
        var api = Api();
        api.Token = Token;

        Make(api).ForgetHere();

        Assert.Empty(_keychain.Items);
        Assert.Null(api.Token);
        Assert.Empty(_handler.Requests);
    }

    /// <summary>A server that takes the request and never answers.</summary>
    private sealed class SilentHandler : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            await Task.Delay(Timeout.Infinite, ct);
            throw new InvalidOperationException("Never reached.");
        }
    }

    [Fact]
    public void SettingsKeepOnlyTheServerAndChooseLibreOffice()
    {
        var paths = HelperPaths.For(_dir);
        var s = HelperSettings.Load(paths);
        Assert.Null(s.Server);
        s.Server = Account;
        s.Save(paths);
        var again = HelperSettings.Load(paths);
        Assert.Equal(Account, again.Server);
        Assert.Null(again.Kinds);
        var chooser = new AppChooser(again, new InstalledApps(true, true, true, true), _ => null);
        Assert.Equal("LibreOffice", chooser.For("a.docx"));
        Assert.Equal("LibreOffice", chooser.For("a.XLSX"));
        Assert.Equal("LibreOffice", chooser.For("a.odp"));
        Assert.Equal("Default", chooser.For("a.pdf"));
    }
}
