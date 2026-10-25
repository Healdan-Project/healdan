using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;

namespace Healdan.Helper.Core.Tests.Channel;

public sealed class ChannelServerTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "hd-channel-" + Guid.NewGuid().ToString("N"));
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(30) };
    private readonly List<ChannelServer> servers = [];

    private sealed class Handler(Func<string, JsonObject, Task<JsonNode?>> onCall) : IChannelHandler
    {
        public Task<JsonNode?> HandleAsync(string route, JsonObject body) => onCall(route, body);
    }

    private HelperPaths Paths() => HelperPaths.For(root);

    private ChannelServer Start(Func<string, JsonObject, Task<JsonNode?>>? onCall = null)
    {
        var server = new ChannelServer(Paths(), new Handler(onCall ?? ((_, b) => Task.FromResult<JsonNode?>(new JsonObject { ["echo"] = b.DeepClone() }))));
        server.Start();
        servers.Add(server);
        return server;
    }

    private async Task<HttpResponseMessage> Send(HttpMethod method, string path, string? body = "{}", string? secret = "auto")
    {
        var info = ChannelInfo.Read(Paths())!;
        var request = new HttpRequestMessage(method, $"http://127.0.0.1:{info.Port}{path}");
        if (body is not null)
            request.Content = new StringContent(body, Encoding.UTF8, new MediaTypeHeaderValue("application/json"));
        if (secret == "auto")
            request.Headers.Add("X-Healdan-Secret", info.Secret);
        else if (secret is not null)
            request.Headers.Add("X-Healdan-Secret", secret);
        return await http.SendAsync(request);
    }

    [Fact]
    public async Task PingNeedsNoSecret()
    {
        Start();
        var response = await Send(HttpMethod.Get, "/v1/ping", null, null);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(JsonNode.Parse(await response.Content.ReadAsStringAsync())!["ok"]!.GetValue<bool>());
    }

    [Fact]
    public async Task MissingSecret403()
    {
        Start();
        Assert.Equal(HttpStatusCode.Forbidden, (await Send(HttpMethod.Post, "/v1/state", "{}", null)).StatusCode);
    }

    [Fact]
    public async Task WrongSecret403()
    {
        Start();
        Assert.Equal(HttpStatusCode.Forbidden, (await Send(HttpMethod.Post, "/v1/state", "{}", new string('0', 48))).StatusCode);
    }

    [Fact]
    public void InfoFileIsPrivate()
    {
        Start();
        var path = Paths().Channel;
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(path));
        var json = JsonNode.Parse(File.ReadAllText(path))!;
        Assert.True(json["port"]!.GetValue<int>() > 0);
        Assert.Matches("^[0-9a-f]{48}$", json["secret"]!.GetValue<string>());
        Assert.Equal(Environment.ProcessId, json["pid"]!.GetValue<int>());
    }

    [Fact]
    public async Task UnknownRoute404()
    {
        Start();
        Assert.Equal(HttpStatusCode.NotFound, (await Send(HttpMethod.Post, "/v1/nothing")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await Send(HttpMethod.Post, "/other")).StatusCode);
    }

    [Fact]
    public async Task WrongMethod405()
    {
        Start();
        Assert.Equal(HttpStatusCode.MethodNotAllowed, (await Send(HttpMethod.Get, "/v1/state", null)).StatusCode);
        Assert.Equal(HttpStatusCode.MethodNotAllowed, (await Send(HttpMethod.Post, "/v1/ping")).StatusCode);
    }

    [Fact]
    public async Task BodyReachesHandlerAndAnswerComesBack()
    {
        Start();
        var response = await Send(HttpMethod.Post, "/v1/state", """{"path":"/x"}""");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("/x", JsonNode.Parse(await response.Content.ReadAsStringAsync())!["echo"]!["path"]!.GetValue<string>());
    }

    [Fact]
    public async Task NullAnswerIsEmptyObject()
    {
        Start((_, _) => Task.FromResult<JsonNode?>(null));
        var response = await Send(HttpMethod.Post, "/v1/closed", """{"path":"/x"}""");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Empty(JsonNode.Parse(await response.Content.ReadAsStringAsync())!.AsObject());
    }

    [Theory]
    [InlineData("not json")]
    [InlineData("[1]")]
    [InlineData("\"s\"")]
    [InlineData("null")]
    public async Task BadBody400(string body)
    {
        Start();
        Assert.Equal(HttpStatusCode.BadRequest, (await Send(HttpMethod.Post, "/v1/state", body)).StatusCode);
    }

    [Fact]
    public async Task HugeBody413()
    {
        Start();
        var big = "{\"x\":\"" + new string('a', 70_000) + "\"}";
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, (await Send(HttpMethod.Post, "/v1/state", big)).StatusCode);
    }

    [Fact]
    public async Task ThrowingHandlerAnswers500AndServerLives()
    {
        Start((route, _) => route == "state" ? throw new InvalidOperationException("secret-detail") : Task.FromResult<JsonNode?>(new JsonObject()));
        var response = await Send(HttpMethod.Post, "/v1/state");
        Assert.Equal(HttpStatusCode.InternalServerError, response.StatusCode);
        Assert.DoesNotContain("secret-detail", await response.Content.ReadAsStringAsync());
        Assert.Equal(HttpStatusCode.OK, (await Send(HttpMethod.Post, "/v1/closed")).StatusCode);
    }

    [Fact]
    public void StopDeletesOwnInfoFileButNotAnotherHelpers()
    {
        var server = Start();
        server.Stop();
        Assert.False(File.Exists(Paths().Channel));

        var second = Start();
        File.WriteAllText(Paths().Channel, """{"port":1,"secret":"x","pid":1}""");
        second.Stop();
        Assert.True(File.Exists(Paths().Channel));
    }

    [Fact]
    public async Task ForwardReachesRunningServer()
    {
        var seen = new TaskCompletionSource<string>();
        Start((route, body) =>
        {
            seen.TrySetResult(route + ":" + body["path"]);
            return Task.FromResult<JsonNode?>(new JsonObject());
        });
        Assert.True(await Task.Run(() => ChannelInfo.TryForward(Paths(), "closed", new JsonObject { ["path"] = "/p" }, TimeSpan.FromSeconds(5))));
        Assert.Equal("closed:/p", await seen.Task.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void ForwardFailsWhenNoneRunning()
    {
        var watch = Stopwatch.StartNew();
        Assert.False(ChannelInfo.TryForward(Paths(), "link", new JsonObject(), TimeSpan.FromSeconds(2)));
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(3));
    }

    [Fact]
    public void ForwardFailsWhenProcessIsGone()
    {
        Directory.CreateDirectory(root);
        File.WriteAllText(Paths().Channel, $$"""{"port":1,"secret":"x","pid":{{int.MaxValue - 5}}}""");
        Assert.False(ChannelInfo.TryForward(Paths(), "link", new JsonObject(), TimeSpan.FromSeconds(2)));
    }

    [Fact]
    public async Task LinkAnswersAtOnce()
    {
        var release = new TaskCompletionSource();
        var finished = new TaskCompletionSource();
        Start(async (route, _) =>
        {
            if (route == "link")
            {
                await release.Task;
                finished.SetResult();
            }
            return new JsonObject();
        });
        var watch = Stopwatch.StartNew();
        var response = await Send(HttpMethod.Post, "/v1/link", """{"url":"healdan://x"}""");
        Assert.True(watch.ElapsedMilliseconds < 200, $"took {watch.ElapsedMilliseconds} ms");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Empty(JsonNode.Parse(await response.Content.ReadAsStringAsync())!.AsObject());
        Assert.False(finished.Task.IsCompleted);
        release.SetResult();
        await finished.Task.WaitAsync(TimeSpan.FromSeconds(5));
    }

    public void Dispose()
    {
        foreach (var s in servers)
            s.Stop();
        http.Dispose();
        if (Directory.Exists(root))
            Directory.Delete(root, true);
    }
}
