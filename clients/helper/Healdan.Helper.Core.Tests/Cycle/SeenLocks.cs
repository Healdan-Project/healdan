using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// An app that remembers which copies the cycle asked it about and found a lock file for, so a test closes a window only
/// once the close watcher has seen the lock file (it counts a lock file going only after it was seen).
/// </summary>
public sealed class SeenLocks(IOfficeApp inner) : IOfficeApp
{
    private readonly ConcurrentDictionary<string, bool> _seen = new(StringComparer.Ordinal);
    private readonly ConcurrentQueue<string> _asked = new();

    public string Name => inner.Name;

    public void Open(string path)
    {
        _seen.TryRemove(path, out _);
        inner.Open(path);
    }

    public bool IsRunning() => inner.IsRunning();

    public bool LockExists(string path)
    {
        _asked.Enqueue(path);
        var exists = inner.LockExists(path);
        if (exists)
            _seen[path] = true;
        return exists;
    }

    public string CheckOutName(string fileName) => inner.CheckOutName(fileName);

    public bool IsRunningFor(string path) => inner.IsRunningFor(path);

    /// <summary>True once the cycle has asked about the copy's lock file and found it, since this app last opened the copy.</summary>
    public bool LockSeen(string path) => _seen.ContainsKey(path);

    /// <summary>Whether the cycle has ever asked this app about the copy's lock file.</summary>
    public bool WasAskedAbout(string path) => _asked.Contains(path);
}
