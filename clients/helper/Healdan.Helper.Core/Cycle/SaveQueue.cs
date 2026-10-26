namespace Healdan.Helper.Core.Cycle;

/// <summary>What <see cref="SaveQueue"/> does with a job after one try.</summary>
internal enum SaveOutcome
{
    /// <summary>Its outcome is known: stored, or refused for good.</summary>
    Done,

    /// <summary>The server didn't take it, or its answer was lost: send it again, the same, after a wait.</summary>
    Retry
}

/// <summary>
/// One thing to send for a copy: a save while editing, the check-in when its window closes, or a release. A save holds
/// the bytes it read once the file had settled, so what goes up is exactly what was hashed, and a retry sends the same.
/// </summary>
internal sealed class SaveJob
{
    /// <summary>The file as it settled; null for a release, or a close that found no file.</summary>
    public byte[]? Content { get; set; }

    /// <summary>SHA-256 (hex) of <see cref="Content"/>.</summary>
    public string? Hash { get; set; }

    /// <summary>
    /// The close's check-in reads the file when it's first sent, as the app has finished with it, and tries again
    /// later if it can't be read yet. If the file is gone, it sends <see cref="FallbackContent"/>: the newest save
    /// that was waiting when the window closed.
    /// </summary>
    public bool ReadAtSend { get; set; }

    public byte[]? FallbackContent { get; init; }

    public string? FallbackHash { get; init; }

    /// <summary><c>snapshot</c> or <c>version</c>; a save that replaces a waiting version becomes one (<see cref="SaveQueue.Enqueue"/>).</summary>
    public string Kind { get; set; } = "snapshot";

    /// <summary>The close's save: <c>keepCheckedOut: false</c>, or a release when nothing changed; then the copy goes.</summary>
    public bool CheckIn { get; init; }

    /// <summary>Save as New Document's original: release it, then delete its copy once <see cref="AfterAdoption"/> completes.</summary>
    public bool ReleaseOnly { get; init; }

    public Task? AfterAdoption { get; init; }

    /// <summary>Set at the first send and kept for every retry, so a retry after a lost answer is the same save.</summary>
    public string? SaveId { get; set; }

    public string? IfVersion { get; set; }

    /// <summary>
    /// The saveId was first sent, before a restart, with other bytes, so a <c>201</c> doesn't say which the server
    /// kept: these bytes are then sent again under a new saveId.
    /// </summary>
    public bool Uncertain { get; set; }

    /// <summary>It was sent again once after <c>version-moved</c>; a second refusal ends the check-out here.</summary>
    public bool Resent { get; set; }
}

/// <summary>
/// The saves of one copy, sent one at a time and in order (Review Focus 2). A job that hasn't started is replaced by a
/// newer one, which holds newer content, and which goes up as a version if the one it replaced was to. A job that has
/// started is never cancelled or overtaken by a newer one: it's retried, with the same saveId and bytes, until its
/// outcome is known, and only then does the next one go.
/// </summary>
internal sealed class SaveQueue : IDisposable
{
    private readonly object _lock = new();
    private readonly Func<SaveJob, CancellationToken, Task<SaveOutcome>> _send;
    private readonly CycleTimings _timings;
    private readonly Action<bool> _waiting;
    private readonly Action<Exception> _failed;
    private readonly CancellationTokenSource _stop = new();
    private SaveJob? _queued;
    private bool _looping;
    private TaskCompletionSource _wake = NewWake();

    /// <param name="send">Sends a job once. It reports refusals itself; only a cancelled send should throw.</param>
    /// <param name="waiting">Called with true when a job starts waiting for a retry, and false once it's done.</param>
    /// <param name="failed">Told of a send that threw anyway; the job then counts as done, and the queue goes on.</param>
    public SaveQueue(Func<SaveJob, CancellationToken, Task<SaveOutcome>> send, CycleTimings timings, Action<bool> waiting,
        Action<Exception> failed)
    {
        _send = send;
        _timings = timings;
        _waiting = waiting;
        _failed = failed;
    }

    /// <summary>
    /// Queues the job behind the one running, replacing a queued one that hasn't started. If that one was a Save as New
    /// Version, this job takes its <see cref="SaveJob.Kind"/>: its content holds that save's too, and the user still
    /// asked for a version.
    /// </summary>
    public void Enqueue(SaveJob job)
    {
        lock (_lock)
        {
            if (_stop.IsCancellationRequested)
                return;
            // Decided under the lock, so the job replaced is one that won't start: one taken meanwhile is sent as it is.
            if (_queued is { Kind: "version" })
                job.Kind = "version";
            _queued = job;
            if (_looping)
                return;
            _looping = true;
        }
        _ = Task.Run(RunAsync);
    }

    /// <summary>The job waiting to start, if any.</summary>
    public SaveJob? Queued
    {
        get
        {
            lock (_lock)
                return _queued;
        }
    }

    /// <summary>True when nothing is queued or being sent.</summary>
    public bool Idle
    {
        get
        {
            lock (_lock)
                return !_looping;
        }
    }

    /// <summary>Drops the job that hasn't started; the one being sent goes on.</summary>
    public void DropQueued()
    {
        lock (_lock)
            _queued = null;
    }

    /// <summary>Ends the wait before a retry now, as after signing in again.</summary>
    public void RetryNow()
    {
        TaskCompletionSource wake;
        lock (_lock)
        {
            wake = _wake;
            _wake = NewWake();
        }
        wake.TrySetResult();
    }

    /// <summary>Stops sending; a send in progress is cancelled. What isn't confirmed stays in the copy's record.</summary>
    public void Dispose()
    {
        _stop.Cancel();
        RetryNow();
    }

    private static TaskCompletionSource NewWake() => new(TaskCreationOptions.RunContinuationsAsynchronously);

    private async Task RunAsync()
    {
        var ct = _stop.Token;
        var ended = false;
        try
        {
            while (true)
            {
                SaveJob job;
                lock (_lock)
                {
                    // Decided under the lock, so a job queued now either is taken here or starts a new loop.
                    if (_queued is null || ct.IsCancellationRequested)
                    {
                        _looping = false;
                        ended = true;
                        return;
                    }
                    job = _queued;
                    _queued = null;
                }

                var waiting = false;
                for (var attempt = 0; ; attempt++)
                {
                    SaveOutcome outcome;
                    try
                    {
                        outcome = await _send(job, ct);
                    }
                    catch (Exception e) when (e is not OperationCanceledException || !ct.IsCancellationRequested)
                    {
                        // Never let one job stop the copy's saves: the copy keeps its changes, and the next save goes.
                        _failed(e);
                        outcome = SaveOutcome.Done;
                    }
                    if (outcome == SaveOutcome.Done)
                        break;
                    if (!waiting)
                    {
                        waiting = true;
                        _waiting(true);
                    }
                    Task wake;
                    lock (_lock)
                        wake = _wake.Task;
                    await Task.WhenAny(Task.Delay(_timings.RetryDelay(attempt), ct), wake);
                    ct.ThrowIfCancellationRequested();
                }
                if (waiting)
                    _waiting(false);
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // Stopped: the job's record says what's left to send.
        }
        finally
        {
            if (!ended)
                lock (_lock)
                    _looping = false;
        }
    }
}
