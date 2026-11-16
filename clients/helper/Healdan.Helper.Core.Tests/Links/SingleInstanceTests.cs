// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Diagnostics;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Tests.Links;

public sealed class SingleInstanceTests : IDisposable
{
    private const string Link = "healdan://open/5?server=healdan.test";

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-single-").FullName;
    private readonly List<ChannelServer> _servers = [];

    public void Dispose()
    {
        lock (_servers)
        {
            foreach (var server in _servers)
                server.Dispose();
        }
        TempFolder.Delete(_root);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private string Logged() => File.Exists(Paths.Log) ? File.ReadAllText(Paths.Log) : "";

    private sealed class Handler(Func<string, JsonObject, Task<JsonNode?>> onCall) : IChannelHandler
    {
        public Task<JsonNode?> HandleAsync(string route, JsonObject body) => onCall(route, body);
    }

    [Fact]
    public void TheFirstStartHoldsTheLock()
    {
        var first = SingleInstance.TryAcquire(Paths);
        Assert.NotNull(first);

        Assert.Null(SingleInstance.TryAcquire(Paths));

        first.Dispose();
        using var again = SingleInstance.TryAcquire(Paths);
        Assert.NotNull(again);
    }

    [Fact]
    public void TheLockFileIsPrivate()
    {
        if (OperatingSystem.IsWindows())
            return;
        using var held = SingleInstance.TryAcquire(Paths);

        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(Path.Combine(Paths.Support, "helper.lock")));
    }

    [Fact]
    public void AFolderThatCantBeWrittenLeavesTheHelperRunningWithoutALock()
    {
        if (OperatingSystem.IsWindows() || Environment.UserName == "root")
            return; // Windows has no such modes, and root ignores them
        var logFile = Path.Combine(Directory.CreateTempSubdirectory("healdan-single-log-").FullName, "test.log");
        File.SetUnixFileMode(Paths.Support, UnixFileMode.UserRead | UnixFileMode.UserExecute);
        try
        {
            using var held = SingleInstance.TryAcquire(Paths, new HelperLog(logFile));

            Assert.NotNull(held);
            Assert.Contains("a second Healdan Helper can't be ruled out", File.ReadAllText(logFile));
        }
        finally
        {
            File.SetUnixFileMode(Paths.Support, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            TempFolder.Delete(Path.GetDirectoryName(logFile)!);
        }
    }

    [Fact]
    public async Task SecondStartWhileFirstStartingForwards()
    {
        var seen = new TaskCompletionSource<string?>();
        // The first helper is still signing in: its channel, and channel.json, come up about a second later.
        _ = Task.Run(async () =>
        {
            await Task.Delay(TimeSpan.FromSeconds(1));
            var server = new ChannelServer(Paths, new Handler((_, body) =>
            {
                seen.TrySetResult((string?)body["url"]);
                return Task.FromResult<JsonNode?>(new JsonObject());
            }));
            server.Start();
            lock (_servers)
                _servers.Add(server);
        });

        var sent = await Task.Run(() => new LinkHandOver(Paths, new HelperLog(Paths.Log)).Send(Link, TimeSpan.FromSeconds(10)));

        Assert.True(sent);
        // The channel answers only a request with the right secret header, so a taken link had it.
        Assert.Equal(Link, await seen.Task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Contains("Handed a link to #5 to the running Healdan Helper", Logged());
    }

    [Fact]
    public void SecondStartGivesUpAfterTheWait()
    {
        var clock = Stopwatch.StartNew();

        var sent = new LinkHandOver(Paths, new HelperLog(Paths.Log)).Send(Link, TimeSpan.FromSeconds(1));

        Assert.False(sent);
        Assert.InRange(clock.Elapsed, TimeSpan.FromSeconds(0.9), TimeSpan.FromSeconds(4));
        Assert.Contains("Dropped a link to #5: the running Healdan Helper didn't take it", Logged());
    }

    [Fact]
    public void WithoutAWaitItTriesOnce()
    {
        var clock = Stopwatch.StartNew();

        Assert.False(new LinkHandOver(Paths, new HelperLog(Paths.Log)).Send(Link));

        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(1));
        Assert.Contains("Dropped a link to #5", Logged());
    }
}
