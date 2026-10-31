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
/// Each copy is watched with the app it was opened in (<see cref="IOfficeApp.LockExists"/>, and
/// <see cref="IOfficeApp.IsRunningFor"/> for the process), as LibreOffice's lock file says nothing of a Word window.
/// </summary>
internal sealed class CloseWatcher : IDisposable
{
    private sealed class Entry(string path, IOfficeApp app, bool appSeen)
    {
        public string Path { get; } = path;

        public IOfficeApp App { get; } = app;

        public bool LockSeen { get; set; }

        public int Gone { get; set; }

        public bool AppSeen { get; set; } = appSeen;
    }

    private readonly object _lock = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly CycleTimings _timings;
    private readonly Action<string> _closed;
    private readonly Action<string> _crashed;
    private readonly CancellationTokenSource _stop = new();

    /// <param name="closed">Called once when a copy's window has closed; the copy is no longer watched.</param>
    /// <param name="crashed">Called once when the app went away with the copy open; the copy is no longer watched.</param>
    public CloseWatcher(CycleTimings timings, Action<string> closed, Action<string> crashed)
    {
        _timings = timings;
        _closed = closed;
        _crashed = crashed;
        _ = Task.Run(() => RunAsync(_stop.Token));
    }

    /// <param name="app">The app the copy was opened in, which says whether its window is open and whether it is running.</param>
    /// <param name="appSeenRunning">True when the app is known to be running with the copy open, as after a restart.</param>
    public void Watch(string path, IOfficeApp app, bool appSeenRunning = false)
    {
        lock (_lock)
            _entries[path] = new Entry(path, app, appSeenRunning);
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
                    if (entry.App.LockExists(entry.Path))
                        (entry.LockSeen, entry.Gone) = (true, 0);
                    else if (entry.LockSeen && ++entry.Gone >= 2)
                        closed.Add(entry);
                }

                if (Stopwatch.GetElapsedTime(lastCrashCheck) >= _timings.CrashPoll)
                {
                    lastCrashCheck = Stopwatch.GetTimestamp();
                    foreach (var entry in entries.Except(closed))
                    {
                        if (IsRunning(entry))
                            entry.AppSeen = true;
                        else if (entry.AppSeen)
                            (entry.App.LockExists(entry.Path) ? crashed : closed).Add(entry);
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
    private static bool IsRunning(Entry entry)
    {
        try
        {
            return entry.App.IsRunningFor(entry.Path);
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or IOException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            return true;
        }
    }
}
