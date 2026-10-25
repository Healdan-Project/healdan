using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>
/// The sign-in routes (<c>/server</c>, <c>/auth/tokens</c>, <c>/me</c>) in front of <see cref="FakeApi"/>'s documents.
/// <c>other.test</c> isn't Healdan, and <c>newer.test</c> speaks an API the helper doesn't.
/// </summary>
public sealed class SessionServer(FakeApi documents) : DelegatingHandler(documents)
{
    public const string IssuedToken = "hd_issued_token_value";
    public const string WrongPassword = "wrong";

    private readonly object _lock = new();
    private readonly List<string> _calls = [];

    /// <summary>When set, sign-ins wait for it: received, but unanswered.</summary>
    public TaskCompletionSource? HoldSignIns { get; set; }

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
                return Json(HttpStatusCode.OK,
                    $$"""{"product":"{{product}}","version":"9.3.1","apiVersion":"{{apiVersion}}","labels":{"matter":"Matter","matters":"Matters"},"signIn":["password"]}""");
            case ("POST", "/api/v1/auth/tokens"):
                var body = JsonNode.Parse(await request.Content!.ReadAsStringAsync(ct))!;
                if (HoldSignIns is { } gate)
                    await gate.Task.WaitAsync(ct);
                if ((string?)body["password"] == WrongPassword)
                    return Json(HttpStatusCode.Unauthorized,
                        """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"The username or password is wrong.","code":"sign-in-failed"}""");
                return Json(HttpStatusCode.Created,
                    $$"""{"token":"{{IssuedToken}}","id":7,"name":"{{(string?)body["name"]}}","expiresAt":"2027-01-01T00:00:00Z","user":{{User}}}""");
            case ("DELETE", "/api/v1/auth/tokens/current"):
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            case ("GET", "/api/v1/me"):
                if (documents.Unauthorized || request.Headers.Authorization is null)
                    return Json(HttpStatusCode.Unauthorized,
                        """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Sign in again.","code":"unauthenticated"}""");
                return Json(HttpStatusCode.OK, User);
        }
        return await base.SendAsync(request, ct);
    }

    private const string User = """{"id":1,"username":"robert","name":"Robert Example","email":null,"language":"en","admin":false}""";

    private static HttpResponseMessage Json(HttpStatusCode status, string body) =>
        new(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };
}
