using System.Buffers.Text;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Addin;

/// <summary>
/// Keeps every request to the add-in's port short (M3.5 plan: the add-in's routes, added in the build, Task 9b). The add-in
/// calls the port with <c>fetch</c> from Office's web view, which is WebKit, and WebKit is likely to give up on a request
/// that has had no answer for about a minute. Several routes wait longer: the Open, Save and Properties windows wait for
/// the user, and <c>file</c> can wait up to 300 s behind Word's Grant File Access. A dropped answer would lose a ticket,
/// or the state of a copy just filed, while the pane says the helper can't be reached.
/// <para>So a keyed route still running after <see cref="DefaultHold"/> answers <c>202 {"pending": "&lt;id&gt;"}</c> and
/// goes on; its outcome, the status and body it would have answered, success or error, is kept under that id.
/// <c>/v1/result</c> waits up to the hold time again for it, and answers it, or <c>202</c> once more. An outcome belongs to
/// the key that started it, is answered once, and is kept for <see cref="Kept"/> after its route finishes. <c>ping</c> and
/// <c>state</c> never pend.</para>
/// <para>An id is 16 random bytes in base64url. Nothing here logs: the port logs a route's name and that it was pending,
/// never an id.</para>
/// </summary>
/// <param name="hold">How long a route runs before it answers <c>202</c>: <see cref="DefaultHold"/> in the helper.</param>
/// <param name="clock">The real clock in the helper; tests pass one they can move on. Expiry goes by its timer.</param>
public sealed class PendingResults(TimeSpan hold, TimeProvider clock)
{
    /// <summary>How long a route runs, and <c>result</c> waits, before answering <c>202</c>: well inside WebKit's minute.</summary>
    public static readonly TimeSpan DefaultHold = TimeSpan.FromSeconds(20);

    /// <summary>How long an outcome is kept after its route finished, for the add-in to collect.</summary>
    public static readonly TimeSpan Kept = TimeSpan.FromMinutes(10);

    /// <summary>How many outcomes one key can have waiting, running or finished; the next route is <c>429 too-many-pending</c>.</summary>
    public const int MaxPerKey = 64;

    /// <summary>The routes that answer when they're done, however long that takes: they never wait on the user.</summary>
    private static readonly HashSet<string> Direct = ["ping", "state"];

    private readonly ConcurrentDictionary<string, Entry> _entries = new(StringComparer.Ordinal);

    /// <summary>Held while counting a key's outcomes and adding one, so the cap holds for requests at once.</summary>
    private readonly object _adding = new();

    /// <summary>
    /// Runs <paramref name="route"/>'s <paramref name="handler"/> for the request whose key is <paramref name="key"/>, and
    /// answers what it answers, or <c>202 {"pending": id}</c> when it's still running after the hold time. With
    /// <see cref="MaxPerKey"/> outcomes waiting for the key, it's <c>429 too-many-pending</c>, and the handler never runs.
    /// </summary>
    public async Task<AddinAnswer> RunAsync(string route, string key, Func<Task<AddinAnswer>> handler)
    {
        if (Direct.Contains(route))
            return await handler();

        var entry = new Entry(Owner(key));
        string id;
        lock (_adding)
        {
            Sweep();
            if (_entries.Count(e => e.Value.IsOwnedBy(entry.Owner)) >= MaxPerKey)
                return AddinAnswer.Error(429, "too-many-pending");
            do
                id = Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(16));
            while (!_entries.TryAdd(id, entry));
        }

        _ = RunToOutcomeAsync(entry, handler);
        if (!await WithinHoldAsync(entry.Outcome))
            return Pending(id);
        _entries.TryRemove(id, out _); // answered now, and its id never given
        return await entry.Outcome;
    }

    /// <summary>
    /// <c>/v1/result</c>: the outcome kept under <paramref name="id"/>, once it's there within the hold time, else
    /// <c>202</c> again. An id unknown, expired, already answered, or started with another key than
    /// <paramref name="key"/> is <c>404 no-result</c>, and asking with another key doesn't use it up.
    /// </summary>
    public async Task<AddinAnswer> ResultAsync(string key, string id)
    {
        Sweep();
        if (!_entries.TryGetValue(id, out var entry) || !entry.IsOwnedBy(Owner(key)))
            return NoResult();
        if (!await WithinHoldAsync(entry.Outcome))
            return Pending(id);
        // Answered once: of two asking at once, one takes it, and the other finds nothing.
        return _entries.TryRemove(new KeyValuePair<string, Entry>(id, entry)) ? await entry.Outcome : NoResult();
    }

    /// <summary>For tests: completes once the route under <paramref name="id"/> has finished and its outcome is kept.</summary>
    internal Task WhenFinished(string id) => _entries.TryGetValue(id, out var entry) ? entry.Outcome : Task.CompletedTask;

    /// <summary>
    /// Runs the handler away from the request, so the hold time starts at once, and keeps its outcome with when it finished.
    /// A handler that throws keeps the exception, which the request that collects it meets, as one answered at once does.
    /// </summary>
    private async Task RunToOutcomeAsync(Entry entry, Func<Task<AddinAnswer>> handler)
    {
        AddinAnswer answer;
        try
        {
            answer = await Task.Run(handler);
        }
        catch (Exception e)
        {
            entry.Fail(clock.GetTimestamp(), e);
            return;
        }
        entry.Finish(clock.GetTimestamp(), answer);
    }

    /// <summary>Whether <paramref name="outcome"/> is there within the hold time.</summary>
    private async Task<bool> WithinHoldAsync(Task outcome)
    {
        using var done = new CancellationTokenSource();
        var first = await Task.WhenAny(outcome, Task.Delay(hold, clock, done.Token));
        done.Cancel(); // the timer goes with it
        return first == outcome;
    }

    /// <summary>Drops the outcomes that finished <see cref="Kept"/> ago or more. A route still running is kept, however long.</summary>
    private void Sweep()
    {
        foreach (var (id, entry) in _entries)
        {
            if (entry.Outcome.IsCompleted && clock.GetElapsedTime(entry.FinishedAt) >= Kept)
                _entries.TryRemove(new KeyValuePair<string, Entry>(id, entry));
        }
    }

    /// <summary>What an outcome is kept for: the key's hash, so nothing here holds the key itself.</summary>
    private static byte[] Owner(string key) => SHA256.HashData(Encoding.UTF8.GetBytes(key));

    private static AddinAnswer Pending(string id) => new(202, new JsonObject { ["pending"] = id });

    private static AddinAnswer NoResult() => AddinAnswer.Error(404, "no-result");

    /// <summary>One route's outcome, running or finished, and whose it is.</summary>
    private sealed class Entry(byte[] owner)
    {
        private readonly TaskCompletionSource<AddinAnswer> _outcome = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public byte[] Owner { get; } = owner;

        public Task<AddinAnswer> Outcome => _outcome.Task;

        /// <summary>When the route finished, on the clock's timer; read only once <see cref="Outcome"/> is complete.</summary>
        public long FinishedAt { get; private set; }

        /// <summary>Compared in fixed time, as the port compares keys.</summary>
        public bool IsOwnedBy(byte[] owner) => CryptographicOperations.FixedTimeEquals(Owner, owner);

        public void Finish(long at, AddinAnswer answer)
        {
            FinishedAt = at; // before the outcome completes, which publishes it
            _outcome.SetResult(answer);
        }

        public void Fail(long at, Exception e)
        {
            FinishedAt = at;
            _outcome.SetException(e);
        }
    }
}
