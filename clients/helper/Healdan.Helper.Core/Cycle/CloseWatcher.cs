using System.Diagnostics;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// Notices a copy's window closing, and the app going away (spec §5.5, §5.10).
/// <list type="bullet">
/// <item>Every <see cref="CycleTimings.LockPoll"/>: a lock file that has been seen and is then gone at two checks in a
/// row means the window closed. One never seen doesn't count, as the app may not have opened the file yet.</item>
/// <item>Every <see cref="CycleTimings.CrashPoll"/>: once the app has been seen running, its process gone with the
/// copy's lock file still there is a crash. Gone without one is a close: a read-only copy may never get a lock file.</item>
/// <item>A crashed copy the cycle watches with <see cref="WatchCrashed"/> (M3.5 spec §5.14): its lock file written
/// again, or back after it went, means the app has the copy open again, as Word does with a recovered window.</item>
/// </list>
/// Each copy is watched with the app it was opened in (<see cref="IOfficeApp.LockExists"/>, and
/// <see cref="IOfficeApp.IsRunningFor"/> for the process), as LibreOffice's lock file says nothing of a Word window.
/// </summary>
internal sealed class CloseWatcher : IDisposable
{
    private sealed class Entry(string path, IOfficeApp app, bool appSeen, bool lockSeen)
    {
        public string Path { get; } = path;

        public IOfficeApp App { get; } = app;

        public bool LockSeen { get; set; } = lockSeen;

        public int Gone { get; set; }

        public bool AppSeen { get; set; } = appSeen;

        /// <summary>The app went away with the copy open: only the lock file being written again is looked for.</summary>
        public bool Crashed { get; init; }

        /// <summary>When the lock file the crash left was last written (<see cref="IOfficeApp.LockWritten"/>).</summary>
        public DateTime? CrashWritten { get; init; }

        /// <summary>The lock file the crash left has gone since, so its coming back is the app's.</summary>
        public bool LockGoneSinceCrash { get; set; }
    }

    private readonly object _lock = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private readonly CycleTimings _timings;
    private readonly Action<string> _closed;
    private readonly Action<string> _crashed;
    private readonly Action<string>? _reopened;
    private readonly CancellationTokenSource _stop = new();

    /// <param name="closed">Called once when a copy's window has closed; the copy is no longer watched.</param>
    /// <param name="crashed">Called once when the app went away with the copy open; the copy is no longer watched.</param>
    /// <param name="reopened">
    /// Called once when a copy watched with <see cref="WatchCrashed"/> has its lock or owner file again; the copy is then
    /// watched for its close as one the app has open.
    /// </param>
    public CloseWatcher(CycleTimings timings, Action<string> closed, Action<string> crashed, Action<string>? reopened = null)
    {
        _timings = timings;
        _closed = closed;
        _crashed = crashed;
        _reopened = reopened;
        _ = Task.Run(() => RunAsync(_stop.Token));
    }

    /// <param name="app">The app the copy was opened in, which says whether its window is open and whether it is running.</param>
    /// <param name="appSeenRunning">True when the app is known to be running with the copy open, as after a restart.</param>
    /// <param name="lockSeen">
    /// True when the copy's lock file is known to have been there, so its going is a close from the first look, as for a
    /// copy watched again after a pause.
    /// </param>
    public void Watch(string path, IOfficeApp app, bool appSeenRunning = false, bool lockSeen = false)
    {
        lock (_lock)
            _entries[path] = new Entry(path, app, appSeenRunning, lockSeen);
    }

    /// <summary>
    /// Watches a copy whose app went away with it open for the app to have it open again (M3.5 spec §5.14): its lock file
    /// written again, or back after it went. Neither its close nor a crash is looked for meanwhile.
    /// </summary>
    public void WatchCrashed(string path, IOfficeApp app)
    {
        var written = app.LockWritten(path);
        lock (_lock)
            _entries[path] = new Entry(path, app, false, false) { Crashed = true, CrashWritten = written };
    }

    /// <summary>Whether the copy is watched and its lock file has been seen.</summary>
    public bool LockSeen(string path)
    {
        lock (_lock)
            return _entries.TryGetValue(path, out var entry) && entry.LockSeen;
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
                var reopened = new List<Entry>();
                foreach (var entry in entries)
                {
                    if (entry.Crashed)
                    {
                        if (!entry.App.LockExists(entry.Path))
                            entry.LockGoneSinceCrash = true;
                        else if (entry.LockGoneSinceCrash || entry.App.LockWritten(entry.Path) != entry.CrashWritten)
                            reopened.Add(entry);
                    }
                    else if (entry.App.LockExists(entry.Path))
                        (entry.LockSeen, entry.Gone) = (true, 0);
                    else if (entry.LockSeen && ++entry.Gone >= 2)
                        closed.Add(entry);
                }

                if (Stopwatch.GetElapsedTime(lastCrashCheck) >= _timings.CrashPoll)
                {
                    lastCrashCheck = Stopwatch.GetTimestamp();
                    foreach (var entry in entries.Except(closed).Where(e => !e.Crashed))
                    {
                        if (IsRunning(entry))
                            entry.AppSeen = true;
                        else if (entry.AppSeen)
                            (entry.App.LockExists(entry.Path) ? crashed : closed).Add(entry);
                    }
                }

                // Report only what is still watched: the cycle may have let a copy go meanwhile. A copy the app has open
                // again is watched as any open copy from here on.
                lock (_lock)
                {
                    closed.RemoveAll(e => !Take(e));
                    crashed.RemoveAll(e => !Take(e));
                    reopened.RemoveAll(e => !Take(e));
                    foreach (var entry in reopened)
                        _entries[entry.Path] = new Entry(entry.Path, entry.App, appSeen: true, lockSeen: true);
                }
                foreach (var entry in closed)
                    _closed(entry.Path);
                foreach (var entry in crashed)
                    _crashed(entry.Path);
                foreach (var entry in reopened)
                    _reopened?.Invoke(entry.Path);
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
