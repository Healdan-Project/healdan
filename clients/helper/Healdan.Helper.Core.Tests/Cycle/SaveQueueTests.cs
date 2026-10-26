using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>A copy's saves: one at a time, in order, and the kind each goes up as (Review Focus 2, spec §5.5).</summary>
public sealed class SaveQueueTests : IDisposable
{
    /// <summary>Long enough for a job that shouldn't be sent yet to have been.</summary>
    private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(300);

    private readonly object _lock = new();
    private readonly List<(string Hash, string Kind)> _sent = [];
    private readonly Dictionary<string, TaskCompletionSource> _held = [];
    private readonly List<Exception> _failures = [];
    private readonly SaveQueue _queue;

    public SaveQueueTests() => _queue = new SaveQueue(SendAsync, CycleTimings.Default, _ => { }, Failed);

    public void Dispose()
    {
        _queue.Dispose();
        lock (_lock)
            foreach (var held in _held.Values)
                held.TrySetResult();
    }

    private (string Hash, string Kind)[] Sent
    {
        get
        {
            lock (_lock)
                return [.. _sent];
        }
    }

    private static SaveJob Job(string hash, string kind = "snapshot") => new() { Content = [1], Hash = hash, Kind = kind };

    /// <summary>The job with this hash isn't answered until the returned source is set.</summary>
    private TaskCompletionSource Hold(string hash)
    {
        var held = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_lock)
            _held[hash] = held;
        return held;
    }

    private async Task<SaveOutcome> SendAsync(SaveJob job, CancellationToken ct)
    {
        TaskCompletionSource? held;
        lock (_lock)
        {
            _sent.Add((job.Hash!, job.Kind));
            held = _held.GetValueOrDefault(job.Hash!);
        }
        if (held is not null)
            await held.Task.WaitAsync(ct);
        return SaveOutcome.Done;
    }

    private void Failed(SaveJob job, Exception e)
    {
        lock (_lock)
            _failures.Add(e);
    }

    private async Task Done()
    {
        await Wait.Until(() => _queue.Idle, "the queue done");
        lock (_lock)
            Assert.Empty(_failures);
    }

    [Fact]
    public async Task AJobIsntSentUntilWhatItsQueuingWritesIsWritten()
    {
        var a = Hold("A");
        _queue.Enqueue(Job("A"));
        await Wait.Until(() => Sent.Length == 1, "A being sent");

        // B's record is still being written when A is done: the send, which reads the record, waits for it.
        var writing = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var written = new ManualResetEventSlim();
        var queuing = Task.Run(() => _queue.Enqueue(Job("B"), _ =>
        {
            writing.SetResult();
            written.Wait();
        }));
        await writing.Task;
        a.SetResult();
        await Task.Delay(Quiet);
        Assert.Equal(["A"], Sent.Select(s => s.Hash));

        written.Set();
        await queuing;
        await Done();
        Assert.Equal(["A", "B"], Sent.Select(s => s.Hash));
    }

    [Fact]
    public async Task ASaveReplacingAWaitingVersionGoesAsTheVersion()
    {
        var a = Hold("A");
        _queue.Enqueue(Job("A"));
        await Wait.Until(() => Sent.Length == 1, "A being sent");
        _queue.Enqueue(Job("B", "version"));

        string? settled = null;
        _queue.Enqueue(Job("C"), j => settled = j.Kind);
        Assert.Equal("version", settled); // what the copy's record is told

        a.SetResult();
        await Done();
        Assert.Equal([("A", "snapshot"), ("C", "version")], Sent);
    }

    [Fact]
    public async Task AVersionAlreadyBeingSentGoesAsItIsAndTheNewerSaveAfterIt()
    {
        var a = Hold("A");
        var b = Hold("B");
        _queue.Enqueue(Job("A"));
        await Wait.Until(() => Sent.Length == 1, "A being sent");
        _queue.Enqueue(Job("B", "version"));
        a.SetResult();
        await Wait.Until(() => Sent.Length == 2, "B being sent");

        string? settled = null;
        _queue.Enqueue(Job("C"), j => settled = j.Kind);
        Assert.Equal("snapshot", settled);

        b.SetResult();
        await Done();
        Assert.Equal([("A", "snapshot"), ("B", "version"), ("C", "snapshot")], Sent);
    }
}
