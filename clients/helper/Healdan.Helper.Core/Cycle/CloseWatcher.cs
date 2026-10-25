using System.Diagnostics;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// Notices a copy's window closing, and the app going away (spec §5.5, §5.10).
/// <list type="bullet">
/// <item>Every <see cref="CycleTimings.LockPoll"/>: a lock file that has been seen and is then gone at two checks in a
/// row means the window closed. One never seen doesn't count, as the app may not have opened the file yet.</item>
/// <item>Every <see cref="CycleTimings.CrashPoll"/>: once the app has been seen running, its process gone with the
/// copy's lock file still there is a crash. Gone without one is a close: a read-only copy may never get a lock file.</item>
/// </list>
/// </summary>
internal sealed class CloseWatcher : IDisposable
{
    private sealed class Entry(string path, bool appSeen)
    {
        public string Path { get; } = path;

        public bool LockSeen { get; set; }

        public int Gone { get; set; }

        public bool AppSeen { get; set; } = appSeen;
    }

    private readonly object _lock = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly IOfficeApp _app;
    private readonly CycleTimings _timings;
    private readonly Action<string> _closed;
    private readonly Action<string> _crashed;
    private readonly CancellationTokenSource _stop = new();

    /// <param name="closed">Called once when a copy's window has closed; the copy is no longer watched.</param>
    /// <param name="crashed">Called once when the app went away with the copy open; the copy is no longer watched.</param>
    public CloseWatcher(IOfficeApp app, CycleTimings timings, Action<string> closed, Action<string> crashed)
    {
        _app = app;
        _timings = timings;
        _closed = closed;
        _crashed = crashed;
        _ = Task.Run(() => RunAsync(_stop.Token));
    }

    /// <param name="appSeenRunning">True when the app is known to be running with the copy open, as after a restart.</param>
    public void Watch(string path, bool appSeenRunning = false)
    {
        lock (_lock)
            _entries[path] = new Entry(path, appSeenRunning);
    }

    public void Unwatch(string path)
    {
        lock (_lock)
            _entries.Remove(path);
    }

    public void Dispose() => _stop.Cancel();

    private async Task RunAsync(CancellationToken ct)
    {
        var lastCrashCheck = Stopwatch.GetTimestamp();
        try
        {
            while (true)
            {
                await Task.Delay(_timings.LockPoll, ct);
                List<Entry> entries;
                lock (_lock)
                    entries = [.. _entries.Values];
                var closed = new List<Entry>();
                var crashed = new List<Entry>();
                foreach (var entry in entries)
                {
                    if (_app.LockExists(entry.Path))
                        (entry.LockSeen, entry.Gone) = (true, 0);
                    else if (entry.LockSeen && ++entry.Gone >= 2)
                        closed.Add(entry);
                }

                if (Stopwatch.GetElapsedTime(lastCrashCheck) >= _timings.CrashPoll)
                {
                    lastCrashCheck = Stopwatch.GetTimestamp();
                    var running = IsRunning();
                    foreach (var entry in entries.Except(closed))
                    {
                        if (running)
                            entry.AppSeen = true;
                        else if (entry.AppSeen)
                            (_app.LockExists(entry.Path) ? crashed : closed).Add(entry);
                    }
                }

                // Report only what is still watched: the cycle may have let a copy go meanwhile.
                lock (_lock)
                {
                    closed.RemoveAll(e => !Take(e));
                    crashed.RemoveAll(e => !Take(e));
                }
                foreach (var entry in closed)
                    _closed(entry.Path);
                foreach (var entry in crashed)
                    _crashed(entry.Path);
            }
        }
        catch (OperationCanceledException)
        {
            // Stopped.
        }
    }

    /// <summary>Under the lock: stops watching the entry if it's still the one watched for its path.</summary>
    private bool Take(Entry entry)
    {
        if (!_entries.TryGetValue(entry.Path, out var current) || current != entry)
            return false;
        _entries.Remove(entry.Path);
        return true;
    }

    /// <summary>When the process list can't be read, the app counts as running: a false crash would worry the user.</summary>
    private bool IsRunning()
    {
        try
        {
            return _app.IsRunning();
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            return true;
        }
    }
}
