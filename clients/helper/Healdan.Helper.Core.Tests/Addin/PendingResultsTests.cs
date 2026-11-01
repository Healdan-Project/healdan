using System.Text.Json.Nodes;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>
/// Short requests to the add-in's port (M3.5 plan: the add-in's routes, Task 9b). Office's web view is WebKit, which is
/// likely to give up on a request that has had no answer for about a minute, while the Open, Save and Properties windows
/// wait for the user: a route still running after the hold time answers <c>202 {"pending": id}</c> and goes on, and
/// <c>result</c> answers its outcome once, to the key that started it. The hold time here is milliseconds, not 20 s; a
/// slow route waits on a gate the test opens, and a quick one gets a hold no machine runs past, so nothing depends on
/// how fast the machine is.
/// </summary>
public sealed class PendingResultsTests
{
    private static readonly TimeSpan Hold = TimeSpan.FromMilliseconds(100);

    private const string KeyA = "key-a-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private const string KeyB = "key-b-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private readonly PausableClock _clock = new();

    private PendingResults Results() => new(Hold, _clock);

    private static AddinAnswer Saved() => AddinAnswer.Ok(new JsonObject { ["action"] = "save", ["ticket"] = "t-1" });

    /// <summary>A route that runs until <paramref name="gate"/> opens, as a window does until the user answers it.</summary>
    private static Func<Task<AddinAnswer>> Until(TaskCompletionSource gate, Func<AddinAnswer>? answer = null) => async () =>
    {
        await gate.Task;
        return (answer ?? Saved)();
    };

    private static Task<AddinAnswer> Quick() => Task.FromResult(Saved());

    private static void Is(int status, string json, AddinAnswer answer) =>
        Assert.Equal((status, json), (answer.Status, answer.Body.ToJsonString()));

    /// <summary>The answer is <c>202 {"pending": id}</c>; returns the id.</summary>
    private static string Pending(AddinAnswer answer)
    {
        Assert.Equal(202, answer.Status);
        Assert.Equal(["pending"], answer.Body.Select(p => p.Key));
        return (string)answer.Body["pending"]!;
    }

    [Fact]
    public async Task AQuickRouteAnswersAtOnce()
    {
        // The hold's timer keeps real time, as the route's 10 ms does, so a busy runner could take longer than Hold over
        // them (macOS's did). A minute it never takes, and the route still answers as soon as it's done.
        var results = new PendingResults(TimeSpan.FromMinutes(1), _clock);

        var answer = await results.RunAsync("save-new", KeyA, async () =>
        {
            await Task.Delay(10);
            return new AddinAnswer(201, new JsonObject { ["action"] = "cancel" });
        });

        Is(201, """{"action":"cancel"}""", answer); // its own status and body, with no 202

        // And it keeps nothing: many more never reach the cap.
        for (var i = 0; i < PendingResults.MaxPerKey + 6; i++)
            Is(200, """{"action":"save","ticket":"t-1"}""", await results.RunAsync("file", KeyA, Quick));
    }

    [Fact]
    public async Task ASlowRouteAnswersPendingThenTheResult()
    {
        var results = Results();
        var user = new TaskCompletionSource();

        var id = Pending(await results.RunAsync("save-new", KeyA, Until(user)));

        Assert.Matches("^[A-Za-z0-9_-]{22}$", id); // 16 random bytes, in base64url
        Assert.Equal(id, Pending(await results.ResultAsync(KeyA, id))); // still running: 202 again, after the hold time
        user.SetResult(); // the user saves in the window
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, id));

        // Each slow route has an id of its own.
        var other = Pending(await results.RunAsync("save-new", KeyA, Until(new TaskCompletionSource())));
        Assert.NotEqual(id, other);
    }

    [Fact]
    public async Task AnErrorIsKeptAsTheOutcome()
    {
        var results = Results();
        var user = new TaskCompletionSource();

        var id = Pending(await results.RunAsync("file", KeyA, Until(user, () => AddinAnswer.Error(409, "wrong-window"))));
        user.SetResult();

        Is(409, """{"error":"wrong-window"}""", await results.ResultAsync(KeyA, id));
    }

    [Fact]
    public async Task AResultIsAnsweredOnce()
    {
        var results = Results();
        var user = new TaskCompletionSource();
        var id = Pending(await results.RunAsync("save-new", KeyA, Until(user)));
        user.SetResult();

        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, id));
        Is(404, """{"error":"no-result"}""", await results.ResultAsync(KeyA, id));

        // Two asking at once while it runs: one gets the outcome, the other nothing.
        var second = new TaskCompletionSource();
        var racing = Pending(await results.RunAsync("save-new", KeyA, Until(second)));
        var asks = new[] { results.ResultAsync(KeyA, racing), results.ResultAsync(KeyA, racing) };
        second.SetResult();
        var answers = await Task.WhenAll(asks);
        Assert.Equal([200, 404], answers.Select(a => a.Status).Order());
    }

    [Fact]
    public async Task AnotherKeysIdIsNoResult()
    {
        var results = Results();
        var user = new TaskCompletionSource();
        var id = Pending(await results.RunAsync("save-new", KeyA, Until(user)));
        user.SetResult();
        await results.WhenFinished(id);

        // Another app's key, or another pairing's, learns nothing of it, and doesn't use it up.
        Is(404, """{"error":"no-result"}""", await results.ResultAsync(KeyB, id));
        Is(404, """{"error":"no-result"}""", await results.ResultAsync(KeyA + "x", id));
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, id));

        Is(404, """{"error":"no-result"}""", await results.ResultAsync(KeyA, "an-id-nobody-was-given"));
    }

    [Fact]
    public async Task OutcomesExpire()
    {
        var results = Results();
        _clock.Pause();
        var user = new TaskCompletionSource();
        var kept = Pending(await results.RunAsync("save-new", KeyA, Until(user)));
        var dropped = Pending(await results.RunAsync("file", KeyA, Until(user)));
        var running = new TaskCompletionSource();
        var stillRunning = Pending(await results.RunAsync("properties", KeyA, Until(running)));
        user.SetResult();
        await results.WhenFinished(kept);
        await results.WhenFinished(dropped);

        _clock.Advance(PendingResults.Kept - TimeSpan.FromSeconds(1));
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, kept));

        _clock.Advance(TimeSpan.FromSeconds(1)); // ten minutes since it finished
        Is(404, """{"error":"no-result"}""", await results.ResultAsync(KeyA, dropped));

        // Ten minutes count from when a route finishes: one still running is kept, however long it takes.
        _clock.Advance(TimeSpan.FromMinutes(30));
        Assert.Equal(stillRunning, Pending(await results.ResultAsync(KeyA, stillRunning)));
        running.SetResult();
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, stillRunning));
    }

    [Fact]
    public async Task PendingIdsAreCappedPerKey()
    {
        var results = Results();
        var user = new TaskCompletionSource();

        var started = await Task.WhenAll(Enumerable.Range(0, PendingResults.MaxPerKey)
            .Select(_ => results.RunAsync("save-new", KeyA, Until(user))));
        var ids = started.Select(Pending).ToList();
        Assert.Equal(64, ids.Distinct().Count());

        var ran = false;
        Is(429, """{"error":"too-many-pending"}""", await results.RunAsync("save-new", KeyA, () =>
        {
            ran = true;
            return Quick();
        }));
        Assert.False(ran); // the route never started

        // Another key isn't held up by this one's, and ping and state still answer.
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.RunAsync("save-new", KeyB, Quick));
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.RunAsync("state", KeyA, Quick));

        // Finished but not collected, they still count; collecting one makes room for one.
        user.SetResult();
        foreach (var id in ids)
            await results.WhenFinished(id);
        Assert.Equal(429, (await results.RunAsync("file", KeyA, Quick)).Status);
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.ResultAsync(KeyA, ids[0]));
        Is(200, """{"action":"save","ticket":"t-1"}""", await results.RunAsync("file", KeyA, Quick));
    }

    [Fact]
    public async Task PingAndStateNeverPend()
    {
        var results = Results();

        foreach (var route in new[] { "ping", "state" })
        {
            var answer = await results.RunAsync(route, KeyA, async () =>
            {
                await Task.Delay(3 * Hold);
                return AddinAnswer.Ok(new JsonObject { ["known"] = false });
            });

            Is(200, """{"known":false}""", answer);
        }
    }
}
