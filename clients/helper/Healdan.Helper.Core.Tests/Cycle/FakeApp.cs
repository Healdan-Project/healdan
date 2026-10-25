using System.Text;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// LibreOffice as the cycle sees it: it opens copies (making the lock file for one it can write), writes them in the
/// ways an app saves, and closes or crashes. It changes real files in the copies folder, so the watchers see them.
/// </summary>
public sealed class FakeApp : IOfficeApp
{
    private readonly object _lock = new();
    private readonly List<string> _opened = [];
    private volatile bool _running;

    public string Name => "LibreOffice";

    /// <summary>When set, <see cref="Open"/> fails, as when the app can't be started.</summary>
    public bool FailOpen { get; set; }

    /// <summary>Every path the cycle asked to open, in order.</summary>
    public IReadOnlyList<string> Opened
    {
        get
        {
            lock (_lock)
                return [.. _opened];
        }
    }

    public bool Running
    {
        get => _running;
        set => _running = value;
    }

    public void Open(string path)
    {
        if (FailOpen)
            throw new IOException("LibreOffice didn't start (fake).");
        lock (_lock)
            _opened.Add(path);
        _running = true;
        // LibreOffice locks a file it opens for editing; a read-only copy gets no lock file.
        if (OperatingSystem.IsWindows() || (File.GetUnixFileMode(path) & UnixFileMode.UserWrite) != 0)
            File.WriteAllText(LockPath(path), "fake lock");
    }

    public bool IsRunning() => _running;

    public bool LockExists(string path) => File.Exists(LockPath(path));

    public static string LockPath(string path) =>
        Path.Combine(Path.GetDirectoryName(path)!, $".~lock.{Path.GetFileName(path)}#");

    /// <summary>A save that writes the file in place.</summary>
    public static void Write(string path, string content) => File.WriteAllText(path, content);

    /// <summary>A save that writes a temporary file beside the copy and renames it over the copy.</summary>
    public static void WriteThenRename(string path, string content)
    {
        var temp = Path.Combine(Path.GetDirectoryName(path)!, $"lu{Guid.NewGuid():N}.tmp");
        File.WriteAllText(temp, content);
        File.Move(temp, path, overwrite: true);
    }

    /// <summary>A save written in pieces: one piece every <paramref name="every"/>.</summary>
    public static async Task GrowAsync(string path, int pieces, TimeSpan every)
    {
        await using var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.ReadWrite);
        for (var i = 0; i < pieces; i++)
        {
            await file.WriteAsync(Encoding.UTF8.GetBytes($"piece {i};"));
            await file.FlushAsync();
            if (i < pieces - 1)
                await Task.Delay(every);
        }
    }

    /// <summary>The window closes: LibreOffice removes its lock file.</summary>
    public static void Close(string path) => File.Delete(LockPath(path));

    /// <summary>The app goes away and leaves its lock files behind.</summary>
    public void Crash() => _running = false;
}
