using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// An app that remembers which copies the cycle asked it about and found a lock file for, so a test closes a window only
/// once the close watcher has seen the lock file (it counts a lock file going only after it was seen), and which it
/// found the program running for, so a test crashes the program only once the crash check has seen it running.
/// </summary>
public sealed class SeenLocks(IOfficeApp inner) : IOfficeApp
{
    private readonly ConcurrentDictionary<string, bool> _seen = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, bool> _running = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, int> _asked = new(StringComparer.Ordinal);

    public string Name => inner.Name;

    public IOfficeScripting? Scripting => inner.Scripting;

    public void Open(string path)
    {
        _seen.TryRemove(path, out _);
        inner.Open(path);
    }

    public bool IsRunning() => inner.IsRunning();

    public bool LockExists(string path)
    {
        _asked.AddOrUpdate(path, 1, (_, n) => n + 1);
        var exists = inner.LockExists(path);
        if (exists)
            _seen[path] = true;
        return exists;
    }

    public DateTime? LockWritten(string path) => inner.LockWritten(path);

    public string CheckOutName(string fileName) => inner.CheckOutName(fileName);

    public bool IsRunningFor(string path)
    {
        var running = inner.IsRunningFor(path);
        if (running)
            _running[path] = true;
        return running;
    }

    /// <summary>True once the cycle has asked about the copy's lock file and found it, since this app last opened the copy.</summary>
    public bool LockSeen(string path) => _seen.ContainsKey(path);

    /// <summary>True once the crash check has asked about the copy's program and found it running.</summary>
    public bool RunningSeen(string path) => _running.ContainsKey(path);

    /// <summary>Whether the cycle has ever asked this app about the copy's lock file.</summary>
    public bool WasAskedAbout(string path) => _asked.ContainsKey(path);

    /// <summary>How many times the cycle has asked about the copy's lock file: once for each look the close watcher takes.</summary>
    public int Asked(string path) => _asked.GetValueOrDefault(path);
}
