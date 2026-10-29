using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Tests.Links;

public sealed class LinkHandOverTests : IDisposable
{
    private const string Link = "healdan://open/5?server=healdan.test";

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-handover-").FullName;
    private readonly List<ChannelServer> _servers = [];

    public void Dispose()
    {
        foreach (var server in _servers)
            server.Dispose();
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private HelperLog Log => new(Paths.Log);

    private string Logged() => File.Exists(Paths.Log) ? File.ReadAllText(Paths.Log) : "";

    private sealed class Handler(Func<string, JsonObject, Task<JsonNode?>> onCall) : IChannelHandler
    {
        public Task<JsonNode?> HandleAsync(string route, JsonObject body) => onCall(route, body);
    }

    /// <summary>The running helper's channel, as a second helper finds it through <c>channel.json</c>.</summary>
    private void RunningHelper(Func<string, JsonObject, Task<JsonNode?>> onCall)
    {
        var server = new ChannelServer(Paths, new Handler(onCall));
        server.Start();
        _servers.Add(server);
    }

    [Fact]
    public async Task HandsTheLinkToTheRunningHelper()
    {
        var seen = new TaskCompletionSource<(string Route, string? Url)>();
        RunningHelper((route, body) =>
        {
            seen.TrySetResult((route, (string?)body["url"]));
            return Task.FromResult<JsonNode?>(new JsonObject());
        });

        Assert.True(await Task.Run(() => new LinkHandOver(Paths, Log).Send(Link)));

        Assert.Equal(("link", Link), await seen.Task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Contains("Handed a link to #5 to the running Healdan Helper", Logged());
    }

    [Fact]
    public void LogsALinkDroppedWhenNoHelperTakesIt()
    {
        Assert.False(new LinkHandOver(Paths, Log, TimeSpan.FromSeconds(2)).Send(Link));

        Assert.Contains("Dropped a link to #5: the running Healdan Helper didn't take it", Logged());
    }

    [Fact]
    public void LogsALinkDroppedWhenTheChannelRefusesIt()
    {
        RunningHelper((_, _) => Task.FromResult<JsonNode?>(new JsonObject()));
        File.WriteAllText(Paths.Channel, File.ReadAllText(Paths.Channel).Replace("\"secret\":\"", "\"secret\":\"0"));

        Assert.False(new LinkHandOver(Paths, Log, TimeSpan.FromSeconds(2)).Send(Link));

        Assert.Contains("Dropped a link to #5", Logged());
    }

    [Fact]
    public void NamesNoLinkThatIsntHealdans()
    {
        Assert.False(new LinkHandOver(Paths, Log, TimeSpan.FromSeconds(2)).Send("healdan://delete/5\nINFO forged line"));

        var text = Logged();
        Assert.Contains("Dropped a link that isn't Healdan's open link", text);
        Assert.DoesNotContain("forged", text);
    }
}
