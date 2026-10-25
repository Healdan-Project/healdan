using System.Net;
using System.Text;

namespace Healdan.Helper.Core.Tests.Api;

/// <summary>A request as the fake handler saw it, with its body read before the client disposed it.</summary>
public sealed record SeenRequest(HttpMethod Method, Uri Uri, string? Authorization, string? ContentType, byte[] Body)
{
    public string BodyText => Encoding.UTF8.GetString(Body);
}

/// <summary>Answers the client's requests from a queue, and remembers them.</summary>
public sealed class FakeHandler : HttpMessageHandler
{
    private readonly Queue<Func<HttpResponseMessage>> _answers = new();

    public List<SeenRequest> Requests { get; } = [];

    public void Enqueue(HttpStatusCode status, string body, string contentType = "application/json")
    {
        _answers.Enqueue(() => new HttpResponseMessage(status)
        {
            Content = new StringContent(body, Encoding.UTF8, contentType)
        });
    }

    public void EnqueueBytes(byte[] body)
    {
        _answers.Enqueue(() => new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(body) });
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var body = request.Content is null ? [] : await request.Content.ReadAsByteArrayAsync(cancellationToken);
        Requests.Add(new SeenRequest(request.Method, request.RequestUri!,
            request.Headers.Authorization?.ToString(), request.Content?.Headers.ContentType?.ToString(), body));
        return _answers.Dequeue()();
    }
}
