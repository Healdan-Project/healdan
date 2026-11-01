using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// CoffeePDF as the cycle sees it: the real <see cref="CoffeePdfApp"/>, with its launch and its process check faked. Opening
/// an editable copy writes CoffeePDF's lock file, as its window does once the PDF has loaded (CoffeePDF spec §6.8), unless
/// <see cref="WriteLockOnOpen"/> is off; a read-only copy gets none. It changes real files in the copies folder, so the
/// watchers see them. Wrap <see cref="App"/> in <see cref="SeenLocks"/> to wait for the watchers.
/// </summary>
public sealed class FakeCoffeePdf
{
    private readonly ConcurrentQueue<string> _opened = new();
    private volatile bool _running;

    public FakeCoffeePdf() => App = new CoffeePdfApp(name => name == CoffeePdfApp.ProcessName && _running, Open);

    public CoffeePdfApp App { get; }

    /// <summary>Every path the cycle asked CoffeePDF to open, in order.</summary>
    public IReadOnlyList<string> Opened => [.. _opened];

    public bool Running
    {
        get => _running;
        set => _running = value;
    }

    /// <summary>False while the PDF is still loading: CoffeePDF writes its lock file once the window has the PDF.</summary>
    public bool WriteLockOnOpen { get; set; } = true;

    private void Open(string path)
    {
        _opened.Enqueue(path);
        _running = true;
        if (WriteLockOnOpen && (OperatingSystem.IsWindows() || (File.GetUnixFileMode(path) & UnixFileMode.UserWrite) != 0))
            WriteLock(path);
    }

    public static string LockPath(string copy) =>
        Path.Combine(Path.GetDirectoryName(copy)!, $".~coffeepdf.{Path.GetFileName(copy)}#");

    public static void WriteLock(string copy) => File.WriteAllText(LockPath(copy), """{"pid": 1, "opened": "2026-11-01T09:00:00Z"}""");

    /// <summary>The window closes: CoffeePDF removes its lock file.</summary>
    public static void Close(string copy) => File.Delete(LockPath(copy));

    /// <summary>CoffeePDF's save (its <c>AtomicFile</c>): a temporary file in the copy's folder, renamed over the copy.</summary>
    /// <returns>The temporary file's path, which no longer exists.</returns>
    public static string Save(string copy, string content)
    {
        var temp = Path.Combine(Path.GetDirectoryName(copy)!, $".coffeepdf-{Guid.NewGuid():N}.tmp");
        File.WriteAllText(temp, content);
        File.Move(temp, copy, overwrite: true);
        return temp;
    }

    /// <summary>CoffeePDF goes away, leaving its lock files behind, or, for a read-only copy, nothing.</summary>
    public void Crash() => _running = false;
}
