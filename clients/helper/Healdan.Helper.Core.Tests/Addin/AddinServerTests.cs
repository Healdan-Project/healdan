using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>
/// The add-in's port (M3.5 spec §5.8, §9): HTTPS on loopback, with the helper's own certificate, answering only its own
/// server's pages, at <c>127.0.0.1</c> or <c>localhost</c>, with a paired key and a protocol it speaks. Review Focus 5:
/// another user of the Mac, a web page in a browser, or a page from another Healdan is refused before any route runs,
/// and no key or code is ever logged. A real server, on ports the system says are free, and a client that accepts only
/// its certificate, as the add-in's web view does once the Mac trusts it.
/// </summary>
public sealed class AddinServerTests : OfficeCycleTestBase, IDisposable
{
    private const string Origin = "https://healdan.test";

    private static readonly string[] CorsHeaders =
    [
        "Access-Control-Allow-Origin", "Vary", "Access-Control-Allow-Headers", "Access-Control-Allow-Methods",
        "Access-Control-Allow-Private-Network", "Access-Control-Max-Age",
    ];

    private readonly FakeWindows _windows = new();
    private readonly List<AddinServer> _servers = [];
    private readonly List<IDisposable> _held = [];
    private volatile bool _signedIn = true;
    private string _thumbprint = "";
    private HttpClient? _http;

    public AddinServerTests()
    {
        foreach (var program in Enum.GetValues<OfficeProgram>())
            Scripting.SetRunning(program, true);
    }

    public new void Dispose()
    {
        foreach (var server in _servers)
            server.Dispose();
        foreach (var held in _held)
            held.Dispose();
        _http?.Dispose();
        base.Dispose();
    }

    /// <summary>A port nothing listens on now.</summary>
    private static int FreePort()
    {
        var probe = new TcpListener(IPAddress.Loopback, 0);
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

    /// <summary>The server as the helper starts it, signed in to <see cref="Origin"/>, on <paramref name="ports"/> (a free one by default).</summary>
    private AddinServer Start(IReadOnlyList<int>? ports = null)
    {
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        certificate.LoadOrCreate().Dispose();
        var served = certificate.ForServer() ?? throw new InvalidOperationException("no certificate to serve");
        _thumbprint = served.Thumbprint;
        var cycle = Cycle();
        var log = new HelperLog(Paths.Log);
        var apps = TestApps.Of(LibreOffice, microsoftOffice: Office);
        var handler = new AddinHandler(new Pairing(Paths, _windows, TimeProvider.System),
            new OfficeFiling(cycle, apps, _windows, Notifier, Paths, log),
            new ChannelHandler(cycle, new LinkHandler(_windows, Notifier, log), _windows, Notifier, log, Paths), cycle, apps, log);
        var server = new AddinServer(handler, () => new Uri(Origin + "/"), () => _signedIn, served, log, ports ?? [FreePort()]);
        _servers.Add(server);
        Assert.True(server.Start());
        return server;
    }

    /// <summary>A client that trusts the helper's certificate and nothing else.</summary>
    private HttpClient Http => _http ??= new HttpClient(new HttpClientHandler
    {
        ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert?.Thumbprint == _thumbprint,
    }) { Timeout = TimeSpan.FromSeconds(30) };

    private static HttpRequestMessage Request(AddinServer server, HttpMethod method, string route, JsonObject? body = null,
        string? origin = Origin, string? protocol = "1", string? key = null)
    {
        var request = new HttpRequestMessage(method, $"https://127.0.0.1:{server.Port}/v1/{route}");
        if (body is not null)
            request.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        if (origin is not null)
            request.Headers.Add("Origin", origin);
        if (protocol is not null)
            request.Headers.Add("X-Healdan-Protocol", protocol);
        if (key is not null)
            request.Headers.Add("X-Healdan-Key", key);
        return request;
    }

    private async Task<(HttpStatusCode Status, JsonNode? Body, HttpResponseMessage Response)> Send(HttpRequestMessage request)
    {
        var response = await Http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        return (response.StatusCode, text.Length == 0 ? null : JsonNode.Parse(text), response);
    }

    private Task<(HttpStatusCode Status, JsonNode? Body, HttpResponseMessage Response)> Post(AddinServer server, string route,
        JsonObject? body = null, string? key = null, string? origin = Origin, string? protocol = "1") =>
        Send(Request(server, HttpMethod.Post, route, body ?? new JsonObject(), origin, protocol, key));

    private static void IsError(HttpStatusCode status, string code, (HttpStatusCode Status, JsonNode? Body, HttpResponseMessage Response) answer)
    {
        Assert.Equal(status, answer.Status);
        Assert.Equal($$"""{"error":"{{code}}"}""", answer.Body?.ToJsonString());
    }

    private static bool HasCors(HttpResponseMessage response) => CorsHeaders.Any(response.Headers.Contains);

    /// <summary>Pairs Word over the port, as the add-in does, and returns its key.</summary>
    private async Task<string> PairAsync(AddinServer server, string app = "Word")
    {
        var start = await Post(server, "pair/start", new JsonObject { ["app"] = app });
        Assert.Equal(HttpStatusCode.OK, start.Status);
        var finish = await Post(server, "pair/finish", new JsonObject { ["pairingId"] = (string?)start.Body!["pairingId"], ["code"] = _windows.ShownCode });
        Assert.Equal(HttpStatusCode.OK, finish.Status);
        return (string)finish.Body!["key"]!;
    }

    /// <summary>One request written by hand over TLS, for what a well-behaved client never sends; the answer's status line and body.</summary>
    private async Task<(string StatusLine, string Body)> Raw(AddinServer server, string head)
    {
        using var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, server.Port!.Value);
        await using var tls = new SslStream(client.GetStream(), false, (_, cert, _, _) => cert?.GetCertHashString() == _thumbprint);
        await tls.AuthenticateAsClientAsync("127.0.0.1");
        await tls.WriteAsync(Encoding.ASCII.GetBytes(head));
        await tls.FlushAsync();
        using var reader = new StreamReader(tls, Encoding.UTF8);
        var answer = await reader.ReadToEndAsync().WaitAsync(TimeSpan.FromSeconds(10)); // the server closes once it has answered
        var split = answer.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        return (answer[..answer.IndexOf("\r\n", StringComparison.Ordinal)], split < 0 ? "" : answer[(split + 4)..]);
    }

    [Fact]
    public async Task HelloNeedsNoKey()
    {
        var server = Start();

        var (status, body, response) = await Send(Request(server, HttpMethod.Get, "hello", protocol: null));

        Assert.Equal(HttpStatusCode.OK, status);
        Assert.Equal("""{"name":"Healdan Helper","protocols":[1],"server":"https://healdan.test"}""", body!.ToJsonString());
        Assert.Equal(Origin, response.Headers.GetValues("Access-Control-Allow-Origin").Single());
        Assert.Equal("close", response.Headers.Connection.Single());
    }

    [Fact]
    public void ThePortsAreTheSpecs()
    {
        Assert.Equal([47615, 47616, 47617, 47618], AddinPorts.All);
    }

    [Fact]
    public async Task TheFirstFreePortIsTaken()
    {
        // Another user's helper has the first port.
        var taken = new TcpListener(IPAddress.Loopback, FreePort());
        taken.Start();
        _held.Add(taken.Server);
        var first = ((IPEndPoint)taken.LocalEndpoint).Port;
        var second = FreePort();

        var server = Start([first, second]);

        Assert.Equal(second, server.Port);
        Assert.Equal(HttpStatusCode.OK, (await Send(Request(server, HttpMethod.Get, "hello", protocol: null))).Status);
        Assert.Contains($"127.0.0.1:{second}", Log);

        // All taken: it doesn't start, and says so.
        var none = new AddinServer(Handler(), () => new Uri(Origin), () => true, NewCertificate(), new HelperLog(Paths.Log), [first]);
        _servers.Add(none);
        Assert.False(none.Start());
        Assert.Null(none.Port);
    }

    private AddinHandler Handler()
    {
        var cycle = Cycle();
        var log = new HelperLog(Paths.Log);
        var apps = TestApps.Of(LibreOffice, microsoftOffice: Office);
        return new AddinHandler(new Pairing(Paths, _windows, TimeProvider.System), new OfficeFiling(cycle, apps, _windows, Notifier, Paths, log),
            new ChannelHandler(cycle, new LinkHandler(_windows, Notifier, log), _windows, Notifier, log, Paths), cycle, apps, log);
    }

    private System.Security.Cryptography.X509Certificates.X509Certificate2 NewCertificate()
    {
        var certificate = new AddinCertificate(Paths, TimeProvider.System);
        certificate.LoadOrCreate().Dispose();
        return certificate.ForServer()!;
    }

    [Fact]
    public async Task WrongHostIsRefused()
    {
        var server = Start();
        var key = await PairAsync(server);

        // DNS rebinding: a page at healdan.example whose name now points at 127.0.0.1.
        var rebound = Request(server, HttpMethod.Post, "state", new JsonObject { ["url"] = "" }, key: key);
        rebound.Headers.Host = $"healdan.example:{server.Port}";
        var answer = await Send(rebound);
        IsError(HttpStatusCode.Forbidden, "bad-host", answer);
        Assert.False(HasCors(answer.Response));

        var otherPort = Request(server, HttpMethod.Get, "hello", protocol: null);
        otherPort.Headers.Host = $"127.0.0.1:{server.Port + 1}";
        IsError(HttpStatusCode.Forbidden, "bad-host", await Send(otherPort));

        var noPort = Request(server, HttpMethod.Get, "hello", protocol: null);
        noPort.Headers.Host = "127.0.0.1";
        IsError(HttpStatusCode.Forbidden, "bad-host", await Send(noPort));

        Assert.Equal(0, Scripting.FrontAsked); // no route ran

        // localhost is this Mac too.
        var local = Request(server, HttpMethod.Get, "hello", protocol: null);
        local.Headers.Host = $"localhost:{server.Port}";
        Assert.Equal(HttpStatusCode.OK, (await Send(local)).Status);
    }

    [Fact]
    public async Task WrongOriginIsRefused()
    {
        var server = Start();
        var key = await PairAsync(server);
        Scripting.Front = _ => new FrontDocument(null, "", "Document1");

        foreach (var origin in new[] { "https://evil.example", "http://healdan.test", "https://healdan.test:8443", "https://healdan.test.evil.example", "null", "" })
        {
            // A page in a browser, a page from another Healdan, or one that hides where it's from, with the right key even.
            var answer = await Post(server, "state", new JsonObject { ["app"] = "Word", ["url"] = "" }, key, origin);
            IsError(HttpStatusCode.Forbidden, "bad-origin", answer);
            Assert.False(HasCors(answer.Response));
        }

        // No Origin at all, as another program on the Mac might send.
        var none = await Post(server, "state", new JsonObject { ["app"] = "Word", ["url"] = "" }, key, origin: null);
        IsError(HttpStatusCode.Forbidden, "bad-origin", none);
        Assert.False(HasCors(none.Response));
        IsError(HttpStatusCode.Forbidden, "bad-origin", await Send(Request(server, HttpMethod.Get, "hello", origin: "https://evil.example", protocol: null)));

        // Nor can it pair, or ask the helper to show a code.
        IsError(HttpStatusCode.Forbidden, "bad-origin", await Post(server, "pair/start", new JsonObject { ["app"] = "Word" }, origin: "https://evil.example"));
        Assert.Single(_windows.PairingCodes); // this test's own pairing only

        var preflight = Request(server, HttpMethod.Options, "state", origin: "https://evil.example", protocol: null);
        IsError(HttpStatusCode.Forbidden, "bad-origin", await Send(preflight));

        Assert.Equal(0, Scripting.FrontAsked); // no route ran
    }

    [Fact]
    public async Task PreflightAnswersCors()
    {
        var server = Start();
        var preflight = Request(server, HttpMethod.Options, "save-new", protocol: null);
        preflight.Headers.Add("Access-Control-Request-Method", "POST");
        preflight.Headers.Add("Access-Control-Request-Headers", "content-type,x-healdan-key,x-healdan-protocol");
        preflight.Headers.Add("Access-Control-Request-Private-Network", "true");

        var (status, body, response) = await Send(preflight);

        Assert.Equal(HttpStatusCode.NoContent, status);
        Assert.Null(body);
        string Header(string name) => string.Join(", ", response.Headers.GetValues(name));
        Assert.Equal(Origin, Header("Access-Control-Allow-Origin"));
        Assert.Equal("Origin", Header("Vary"));
        Assert.Equal("Content-Type, X-Healdan-Key, X-Healdan-Protocol", Header("Access-Control-Allow-Headers"));
        Assert.Equal("GET, POST", Header("Access-Control-Allow-Methods"));
        Assert.Equal("true", Header("Access-Control-Allow-Private-Network"));
        Assert.Equal("600", Header("Access-Control-Max-Age"));

        // Every answer to the server's own page carries them, errors included, so the pane can read why.
        var refused = await Post(server, "state", key: null);
        Assert.All(CorsHeaders, h => Assert.True(refused.Response.Headers.Contains(h), h));
    }

    [Fact]
    public async Task WithoutAKeyIsNotPaired()
    {
        var server = Start();
        Scripting.Front = _ => new FrontDocument(null, "", "Document1");

        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "state", new JsonObject { ["url"] = "" }));
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "state", new JsonObject { ["url"] = "" }, key: "a-key-nobody-was-given"));
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "save-new", new JsonObject { ["asNew"] = false }));
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "ping"));
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "no-such-route")); // says nothing of which routes there are
        Assert.Equal(0, Scripting.FrontAsked);
        Assert.Empty(_windows.Asked);

        // Paired, the same request goes through, and a key forgotten in Settings stops working.
        var key = await PairAsync(server);
        Assert.Equal(HttpStatusCode.OK, (await Post(server, "state", new JsonObject { ["url"] = "" }, key)).Status);
        IsError(HttpStatusCode.NotFound, "not-found", await Post(server, "no-such-route", key: key));
        new Pairing(Paths, _windows, TimeProvider.System).Forget("Word");
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "state", new JsonObject { ["url"] = "" }, key));
    }

    [Fact]
    public async Task PairingOverThePort()
    {
        var server = Start();

        var start = await Post(server, "pair/start", new JsonObject { ["app"] = "Excel" });
        Assert.Equal(HttpStatusCode.OK, start.Status);
        var pairingId = (string)start.Body!["pairingId"]!;
        var code = _windows.ShownCode!;
        var wrong = code == "000000" ? "111111" : "000000";

        var tryWrong = await Post(server, "pair/finish", new JsonObject { ["pairingId"] = pairingId, ["code"] = wrong });
        Assert.Equal(HttpStatusCode.Forbidden, tryWrong.Status);
        Assert.Equal("""{"error":"wrong-code","triesLeft":2}""", tryWrong.Body!.ToJsonString());

        var right = await Post(server, "pair/finish", new JsonObject { ["pairingId"] = pairingId, ["code"] = code });
        Assert.Equal(HttpStatusCode.OK, right.Status);
        var key = (string)right.Body!["key"]!;
        IsError(HttpStatusCode.Forbidden, "pairing-expired", await Post(server, "pair/finish", new JsonObject { ["pairingId"] = pairingId, ["code"] = code }));

        var ping = await Post(server, "ping", key: key);
        Assert.Equal((HttpStatusCode.OK, """{"ok":true,"signedIn":true}"""), (ping.Status, ping.Body!.ToJsonString()));

        IsError(HttpStatusCode.BadRequest, "bad-request", await Post(server, "pair/start", new JsonObject { ["app"] = "Notepad" }));

        // Nine wrong codes in a row: pairing rests, and says so.
        for (var guess = 0; guess < Pairing.WrongCodesBeforeRest; guess++)
        {
            var guessing = await Post(server, "pair/start", new JsonObject { ["app"] = "Word" });
            var given = _windows.ShownCode == "000000" ? "111111" : "000000";
            Assert.Equal(HttpStatusCode.Forbidden, (await Post(server, "pair/finish", new JsonObject { ["pairingId"] = (string?)guessing.Body!["pairingId"], ["code"] = given })).Status);
        }
        IsError(HttpStatusCode.Forbidden, "too-many-tries", await Post(server, "pair/start", new JsonObject { ["app"] = "Word" }));
        IsError(HttpStatusCode.MethodNotAllowed, "method-not-allowed", await Send(Request(server, HttpMethod.Get, "pair/start")));
    }

    [Fact]
    public async Task AnOldProtocolIsAskedToUpdate()
    {
        var server = Start();
        var key = await PairAsync(server);

        IsError(HttpStatusCode.UpgradeRequired, "update-healdan-helper", await Post(server, "ping", key: key, protocol: "2"));
        IsError(HttpStatusCode.UpgradeRequired, "update-healdan-helper", await Post(server, "pair/start", new JsonObject { ["app"] = "Word" }, protocol: "2"));
        IsError(HttpStatusCode.BadRequest, "bad-request", await Post(server, "ping", key: key, protocol: null));
        IsError(HttpStatusCode.BadRequest, "bad-request", await Post(server, "ping", key: key, protocol: "one"));
        Assert.Single(_windows.PairingCodes); // the newer add-in's pairing never started
    }

    [Fact]
    public async Task SignedOutIsSaid()
    {
        var server = Start();
        var key = await PairAsync(server);
        Scripting.Front = _ => new FrontDocument(null, "", "Document1");
        _signedIn = false;

        foreach (var route in new[] { "open-dialog", "save-new", "file", "new-version", "properties", "state" })
            IsError(HttpStatusCode.Conflict, "signed-out", await Post(server, route, new JsonObject { ["app"] = "Word", ["url"] = "" }, key));
        var ping = await Post(server, "ping", key: key);
        Assert.Equal((HttpStatusCode.OK, """{"ok":true,"signedIn":false}"""), (ping.Status, ping.Body!.ToJsonString()));
        Assert.Equal(0, Scripting.FrontAsked);
        Assert.Empty(_windows.Asked);
    }

    [Fact]
    public async Task BodiesOverTheCapAreRefused()
    {
        var server = Start();
        var key = await PairAsync(server);
        Assert.Equal(150L * 1024 * 1024, AddinServer.MaxBodyBytes);

        // Only the head is sent: the answer comes without the server waiting for a body it won't read.
        var (statusLine, body) = await Raw(server,
            $"POST /v1/file HTTP/1.1\r\nHost: 127.0.0.1:{server.Port}\r\nOrigin: {Origin}\r\nX-Healdan-Protocol: 1\r\n"
            + $"X-Healdan-Key: {key}\r\nContent-Type: application/json\r\nContent-Length: {150L * 1024 * 1024 + 1}\r\n\r\n");

        Assert.Equal("HTTP/1.1 413 Content Too Large", statusLine);
        Assert.Equal("""{"error":"too-large"}""", body);
    }

    [Fact]
    public async Task MalformedRequestsAreBadRequests()
    {
        var server = Start();
        var host = $"Host: 127.0.0.1:{server.Port}\r\nOrigin: {Origin}\r\n";

        Assert.Equal("HTTP/1.1 400 Bad Request", (await Raw(server, "NONSENSE\r\n\r\n")).StatusLine);
        Assert.Equal("HTTP/1.1 400 Bad Request", (await Raw(server, $"GET /v1/hello HTTP/1.0\r\n{host}\r\n")).StatusLine);
        Assert.Equal("HTTP/1.1 400 Bad Request", (await Raw(server, $"GET /v1/hello HTTP/1.1\r\n{host}Broken header\r\n\r\n")).StatusLine);
        Assert.Equal("HTTP/1.1 400 Bad Request",
            (await Raw(server, $"POST /v1/pair/start HTTP/1.1\r\n{host}X-Healdan-Protocol: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")).StatusLine);
        Assert.Equal("HTTP/1.1 400 Bad Request",
            (await Raw(server, $"GET /v1/hello HTTP/1.1\r\n{host}Host: localhost:{server.Port}\r\n\r\n")).StatusLine); // two Hosts
        Assert.Equal("HTTP/1.1 400 Bad Request",
            (await Raw(server, $"POST /v1/pair/start HTTP/1.1\r\n{host}X-Healdan-Protocol: 1\r\nContent-Length: 2\r\n\r\n[]")).StatusLine); // not an object

        // A head that never ends is cut off rather than read for ever.
        Assert.Equal("HTTP/1.1 400 Bad Request",
            (await Raw(server, $"GET /v1/hello HTTP/1.1\r\n{host}X-Padding: {new string('a', 20_000)}\r\n\r\n")).StatusLine);

        // And the server still answers after them.
        Assert.Equal(HttpStatusCode.OK, (await Send(Request(server, HttpMethod.Get, "hello", protocol: null))).Status);
    }

    [Fact]
    public async Task NothingSecretIsLogged()
    {
        var server = Start();

        var start = await Post(server, "pair/start", new JsonObject { ["app"] = "Word" });
        var code = _windows.ShownCode!;
        var finish = await Post(server, "pair/finish", new JsonObject { ["pairingId"] = (string?)start.Body!["pairingId"], ["code"] = code });
        var key = (string)finish.Body!["key"]!;
        Assert.Equal(HttpStatusCode.OK, (await Post(server, "ping", key: key)).Status);
        IsError(HttpStatusCode.Forbidden, "not-paired", await Post(server, "ping", key: key + "x"));

        var log = Log;
        Assert.Contains("Word paired", log); // the log says what happened
        Assert.DoesNotContain(key, log);
        Assert.DoesNotContain(key[..12], log);
        Assert.DoesNotContain(code, log);
        Assert.DoesNotContain((string)start.Body!["pairingId"]!, log);
    }
}
