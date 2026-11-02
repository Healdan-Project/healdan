using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Web;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>A helper's sign-in through a provider, as the fake server received it at <c>/api/signin/&lt;id&gt;</c>.</summary>
public sealed record ProviderStart(string Provider, string RedirectUri, string State, string CodeChallenge);

/// <summary>
/// The sign-in routes (<c>/server</c>, <c>/auth/tokens</c>, <c>/me</c>) in front of <see cref="FakeApi"/>'s documents.
/// <c>other.test</c> isn't Healdan, and <c>newer.test</c> speaks an API the helper doesn't.
/// It also plays the provider sign-in's two ends as the real server does (M4 spec §6.2): <c>GET /api/signin/&lt;id&gt;</c>
/// sends the browser back to the helper's loopback address with a one-time code, or with a refusal, and the code's
/// exchange at <c>/auth/tokens</c> checks the PKCE verifier and the address, and works once.
/// </summary>
public sealed class SessionServer(FakeApi documents) : DelegatingHandler(documents)
{
    public const string IssuedToken = "hd_issued_token_value";
    public const string WrongPassword = "wrong";

    private readonly object _lock = new();
    private readonly List<string> _calls = [];
    private readonly List<ProviderStart> _starts = [];
    private readonly List<JsonNode> _exchanges = [];
    private readonly List<string> _codes = [];
    private readonly Dictionary<string, ProviderStart> _open = [];

    /// <summary>The providers <c>/server</c> lists, in the order of their buttons.</summary>
    public List<(string Id, string Label)> Providers { get; } = [];

    /// <summary>When set, a provider sign-in is refused with this code, as <c>no-group</c>: the browser is sent back with <c>error</c>.</summary>
    public string? RefuseProviderWith { get; set; }

    /// <summary>When set, every code exchange is answered <c>401 sign-in-failed</c>, as one that's used up or too late would be.</summary>
    public bool RefuseExchanges { get; set; }

    /// <summary>Every provider sign-in the browser started here.</summary>
    public IReadOnlyList<ProviderStart> Starts
    {
        get
        {
            lock (_lock)
                return [.. _starts];
        }
    }

    /// <summary>The bodies of the code exchanges that reached the server, whatever it answered.</summary>
    public IReadOnlyList<JsonNode> Exchanges
    {
        get
        {
            lock (_lock)
                return [.. _exchanges];
        }
    }

    /// <summary>Every one-time code the server made.</summary>
    public IReadOnlyList<string> Codes
    {
        get
        {
            lock (_lock)
                return [.. _codes];
        }
    }

    /// <summary>When set, sign-ins wait for it: received, but unanswered.</summary>
    public TaskCompletionSource? HoldSignIns { get; set; }

    /// <summary>When set, Sign Out's revoke is answered with this status and no body, as a server error or a proxy would.</summary>
    public HttpStatusCode? RevokeStatus { get; set; }

    /// <summary>Runs when a revoke arrives, before it's answered.</summary>
    public Action? OnRevoke { get; set; }

    public IReadOnlyList<string> Calls
    {
        get
        {
            lock (_lock)
                return [.. _calls];
        }
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var path = request.RequestUri!.AbsolutePath;
        var call = $"{request.Method} {request.RequestUri.Host}{path}";
        lock (_lock)
            _calls.Add(call);
        if (documents.Down)
            throw new HttpRequestException("Connection refused (fake).");

        switch (request.Method.Method, path)
        {
            case ("GET", "/api/v1/server"):
                var host = request.RequestUri.Host;
                var product = host == "other.test" ? "Elsewhere" : "Healdan";
                var apiVersion = host == "newer.test" ? "v2" : "v1";
                var providers = new JsonArray([.. Providers.Select(p => (JsonNode)new JsonObject { ["id"] = p.Id, ["label"] = p.Label })]);
                var signIn = providers.Count == 0 ? "[\"password\"]" : "[\"password\",\"provider\"]";
                return Json(HttpStatusCode.OK,
                    $$"""{"product":"{{product}}","version":"9.3.1","apiVersion":"{{apiVersion}}","labels":{"matter":"Matter","matters":"Matters"},"signIn":{{signIn}},"providers":{{providers.ToJsonString()}},"passwordLabel":"Sign In with a Password"}""");
            case ("GET", var start) when start.StartsWith("/api/signin/", StringComparison.Ordinal):
                return SignInStart(request, start["/api/signin/".Length..]);
            case ("POST", "/api/v1/auth/tokens"):
                var body = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!;
                if (HoldSignIns is { } gate)
                    await gate.Task.WaitAsync(ct);
                if (body["code"] is not null)
                    return Exchange(body);
                if ((string?)body["password"] == WrongPassword)
                    return Json(HttpStatusCode.Unauthorized,
                        """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"The username or password is wrong.","code":"sign-in-failed"}""");
                return Json(HttpStatusCode.Created,
                    $$"""{"token":"{{IssuedToken}}","id":7,"name":"{{(string?)body["name"]}}","expiresAt":"2027-01-01T00:00:00Z","user":{{User}}}""");
            case ("DELETE", "/api/v1/auth/tokens/current"):
                OnRevoke?.Invoke();
                return new HttpResponseMessage(RevokeStatus ?? HttpStatusCode.NoContent);
            case ("GET", "/api/v1/me"):
                if (documents.Unauthorized || request.Headers.Authorization is null)
                    return Json(HttpStatusCode.Unauthorized,
                        """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Sign in again.","code":"unauthenticated"}""");
                return Json(HttpStatusCode.OK, User);
        }
        return await base.SendAsync(request, ct);
    }

    /// <summary>The browser asks to sign in through a provider: it's sent back to the helper, as the real server does.</summary>
    private HttpResponseMessage SignInStart(HttpRequestMessage request, string provider)
    {
        var query = HttpUtility.ParseQueryString(request.RequestUri!.Query);
        var redirect = query["redirect_uri"];
        var state = query["state"];
        var challenge = query["code_challenge"];
        var loopback = Uri.TryCreate(redirect, UriKind.Absolute, out var back) && back is { Scheme: "http", Host: "127.0.0.1" } && back.AbsolutePath == "/";
        if (query["client"] != "helper" || !loopback || string.IsNullOrEmpty(state) || challenge is not { Length: 43 }
            || query["code_challenge_method"] != "S256")
            return new HttpResponseMessage(HttpStatusCode.BadRequest) { Content = new StringContent("Bad sign-in request (fake).") };

        var start = new ProviderStart(provider, redirect!, state, challenge);
        string answer;
        lock (_lock)
        {
            _starts.Add(start);
            if (RefuseProviderWith is { } error)
                answer = $"error={Uri.EscapeDataString(error)}";
            else
            {
                var code = "code-" + Guid.NewGuid().ToString("N");
                _codes.Add(code);
                _open[code] = start;
                answer = $"code={code}";
            }
        }
        var response = new HttpResponseMessage(HttpStatusCode.Found);
        response.Headers.Location = new Uri($"{redirect}?{answer}&state={Uri.EscapeDataString(state)}");
        return response;
    }

    /// <summary>The helper's code with its verifier and address: it works once, and a wrong verifier or address uses it up.</summary>
    private HttpResponseMessage Exchange(JsonNode body)
    {
        ProviderStart? start;
        lock (_lock)
        {
            _exchanges.Add(body.DeepClone());
            var code = (string?)body["code"] ?? "";
            start = _open.Remove(code, out var found) ? found : null;
        }
        var verifier = (string?)body["codeVerifier"] ?? "";
        var challenge = Convert.ToBase64String(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        if (RefuseExchanges || start is null || start.CodeChallenge != challenge || start.RedirectUri != (string?)body["redirectUri"])
            return Json(HttpStatusCode.Unauthorized,
                """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"The sign-in failed.","code":"sign-in-failed"}""");
        return Json(HttpStatusCode.Created,
            $$"""{"token":"{{IssuedToken}}","id":7,"name":"{{(string?)body["name"]}}","expiresAt":"2027-01-01T00:00:00Z","user":{{User}}}""");
    }

    private const string User = """{"id":1,"username":"robert","name":"Robert Example","email":null,"language":"en","admin":false}""";

    private static HttpResponseMessage Json(HttpStatusCode status, string body) =>
        new(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
}
