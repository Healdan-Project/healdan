using System.Diagnostics;
using System.Text;

namespace Healdan.Helper.Core.Cycle;

/// <summary>A file's size and last write time, which every save changes; <c>default</c> when there's no file.</summary>
internal readonly record struct FileStamp(bool Exists, long Length, DateTime Written)
{
    public static FileStamp Of(string path)
    {
        try
        {
            var info = new FileInfo(path);
            return info.Exists ? new FileStamp(true, info.Length, info.LastWriteTimeUtc) : default;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return default;
        }
    }
}

/// <summary>
/// Notices saves of the open copies. One <see cref="FileSystemWatcher"/> on the copies folder routes each event to its
/// copy by the copy's file name, so a rename onto that name (a write-then-rename save) counts as a write of the copy and
/// the temporary file is never looked at. A touched copy is reported settled once its size and time have stayed the same
/// for <see cref="CycleTimings.Settle"/>, checked every <see cref="CycleTimings.SizePoll"/>, so a file written in pieces
/// is reported once, whole (Review Focus 3). Every <see cref="CycleTimings.CrashPoll"/> the copies' files are also
/// compared with how they last settled, so a change the watcher dropped is still noticed.
/// </summary>
internal sealed class SaveWatcher : IDisposable
{
    private sealed class Entry(string path)
    {
        public string Path { get; } = path;

        /// <summary>Counts the changes seen; a settle only ends when none came while it watched.</summary>
        public long Changes { get; set; }

        public bool Settling { get; set; }

        /// <summary>The file as it last settled, for the slow rescan.</summary>
        public FileStamp Settled { get; set; }
    }

    private readonly object _lock = new();
    private readonly Dictionary<string, Entry> _byName = new(StringComparer.Ordinal);
    private readonly CycleTimings _timings;
    private readonly Action<string> _settled;
    private readonly FileSystemWatcher _watcher;
    private readonly CancellationTokenSource _stop = new();

    /// <param name="settled">Called with the copy's path once its file has settled after a change; never under a lock.</param>
    public SaveWatcher(string folder, CycleTimings timings, Action<string> settled)
    {
        _timings = timings;
        _settled = settled;
        _watcher = new FileSystemWatcher(folder)
        {
            IncludeSubdirectories = false,
            NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.CreationTime,
            InternalBufferSize = 64 * 1024
        };
        _watcher.Changed += (_, e) => OnEvent(e.Name);
        _watcher.Created += (_, e) => OnEvent(e.Name);
        _watcher.Deleted += (_, e) => OnEvent(e.Name);
        _watcher.Renamed += (_, e) =>
        {
            OnEvent(e.Name);
            OnEvent(e.OldName);
        };
        _watcher.Error += (_, _) => TouchAll(); // the event buffer overflowed: look at every copy
        _watcher.EnableRaisingEvents = true;
        _ = Task.Run(() => RescanAsync(_stop.Token));
    }

    /// <summary>Starts watching a copy, taking its file as it is now as settled.</summary>
    public void Watch(string path)
    {
        lock (_lock)
            _byName[Key(path)] = new Entry(path) { Settled = FileStamp.Of(path) };
    }

    public void Unwatch(string path)
    {
        lock (_lock)
            _byName.Remove(Key(path));
    }

    /// <summary>Treats the copy as changed, as Save as New Version does when there may be no write to come.</summary>
    public void Touch(string path)
    {
        lock (_lock)
            if (_byName.TryGetValue(Key(path), out var entry))
                Touch(entry);
    }

    public void Dispose()
    {
        _stop.Cancel();
        _watcher.Dispose();
        lock (_lock)
            _byName.Clear();
    }

    /// <summary>Copies are one flat folder, so the file name alone says which copy an event is for.</summary>
    private static string Key(string path) => Path.GetFileName(path).Normalize(NormalizationForm.FormC);

    private void OnEvent(string? name)
    {
        if (name is null)
            return;
        lock (_lock)
            if (_byName.TryGetValue(Key(name), out var entry))
                Touch(entry);
    }

    private void TouchAll()
    {
        lock (_lock)
            foreach (var entry in _byName.Values)
                Touch(entry);
    }

    /// <summary>Under the lock: counts the change, and starts the settle unless one is running.</summary>
    private void Touch(Entry entry)
    {
        entry.Changes++;
        if (entry.Settling || _stop.IsCancellationRequested)
            return;
        entry.Settling = true;
        _ = Task.Run(() => SettleAsync(entry, _stop.Token));
    }

    private bool IsWatched(Entry entry)
    {
        lock (_lock)
            return _byName.TryGetValue(Key(entry.Path), out var current) && current == entry;
    }

    private async Task SettleAsync(Entry entry, CancellationToken ct)
    {
        var handedOver = false; // once this loop has cleared Settling, a newer settle may own it
        try
        {
            long seen;
            lock (_lock)
                seen = entry.Changes;
            var stamp = FileStamp.Of(entry.Path);
            var since = Stopwatch.GetTimestamp();
            while (true)
            {
                await Task.Delay(_timings.SizePoll, ct);
                if (!IsWatched(entry))
                    return;
                var now = FileStamp.Of(entry.Path);
                long changes;
                lock (_lock)
                    changes = entry.Changes;
                if (now != stamp || changes != seen)
                {
                    (stamp, seen, since) = (now, changes, Stopwatch.GetTimestamp());
                    continue;
                }
                // A file that's gone is mid-way through a write-then-rename: wait for it to come back.
                if (!now.Exists || Stopwatch.GetElapsedTime(since) < _timings.Settle)
                    continue;
                lock (_lock)
                {
                    if (entry.Changes != seen)
                        continue;
                    entry.Settling = false;
                    entry.Settled = now;
                    handedOver = true;
                }
                break;
            }
            _settled(entry.Path);
        }
        catch (OperationCanceledException)
        {
            // Stopped.
        }
        finally
        {
            if (!handedOver)
                lock (_lock)
                    entry.Settling = false;
        }
    }

    private async Task RescanAsync(CancellationToken ct)
    {
        try
        {
            while (true)
            {
                await Task.Delay(_timings.CrashPoll, ct);
                List<Entry> entries;
                lock (_lock)
                    entries = [.. _byName.Values];
                foreach (var entry in entries)
                {
                    var now = FileStamp.Of(entry.Path);
                    lock (_lock)
                        if (!entry.Settling && now != entry.Settled && _byName.ContainsValue(entry))
                            Touch(entry);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Stopped.
        }
    }
}
