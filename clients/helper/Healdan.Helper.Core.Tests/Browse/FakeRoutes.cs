using System.Collections.Concurrent;
using System.Net;
using System.Text;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>
/// The API as the windows call it: each answer is set for a request line, <c>METHOD /v1/path?query</c> (the query as
/// sent, so <c>#</c> is <c>%23</c>). A request with no answer set is a <c>404</c>. Every request is recorded.
/// </summary>
public sealed class FakeRoutes : HttpMessageHandler
{
    private readonly ConcurrentDictionary<string, Func<HttpResponseMessage>> _answers = new(StringComparer.Ordinal);

    public ConcurrentQueue<string> Calls { get; } = new();

    public ConcurrentQueue<(string Call, string Body)> Bodies { get; } = new();

    public void Answer(string call, string json, HttpStatusCode status = HttpStatusCode.OK) =>
        _answers[call] = () => new HttpResponseMessage(status) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    public void Answer(string call, JsonNode json, HttpStatusCode status = HttpStatusCode.OK) => Answer(call, json.ToJsonString(), status);

    public void AnswerBytes(string call, byte[] bytes) =>
        _answers[call] = () => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(bytes) };

    public void Problem(string call, int status, string code, string detail) =>
        Answer(call, new JsonObject
        {
            ["type"] = $"https://healdan.com/problems/{code}", ["title"] = code, ["status"] = status, ["detail"] = detail, ["code"] = code
        }, (HttpStatusCode)status);

    public int Count(string call) => Calls.Count(c => c == call);

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var call = $"{request.Method} {request.RequestUri!.AbsolutePath["/api".Length..]}{request.RequestUri.Query}";
        Calls.Enqueue(call);
        if (request.Content is not null)
            Bodies.Enqueue((call, await request.Content.ReadAsStringAsync(ct)));
        if (_answers.TryGetValue(call, out var answer))
            return answer();
        return new HttpResponseMessage(HttpStatusCode.NotFound)
        {
            Content = new StringContent($$"""{"type":"x","title":"Not found","status":404,"detail":"No answer for {{call}}","code":"not-found"}""",
                Encoding.UTF8, "application/json")
        };
    }
}
