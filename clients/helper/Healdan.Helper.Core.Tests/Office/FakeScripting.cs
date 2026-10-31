using System.Collections.Concurrent;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// Word, Excel and PowerPoint as the cycle sees them through <see cref="IOfficeScripting"/>: opening a copy starts the
/// program (<see cref="IsRunning"/>, which is what a process check would say) and, for a copy it can write, makes the
/// owner file it keeps beside it. The test closes the window by deleting that file (<see cref="Close"/>) or crashes the
/// program, which leaves it. Every other operation is a test's own to set up, so a call the test didn't expect fails.
/// </summary>
public sealed class FakeScripting : IOfficeScripting
{
    private readonly object _lock = new();
    private readonly List<(OfficeProgram Program, string Path)> _opened = [];
    private readonly ConcurrentDictionary<string, bool> _running = new(StringComparer.Ordinal);

    /// <summary>Every open the cycle asked for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Path)> Opened
    {
        get
        {
            lock (_lock)
                return [.. _opened];
        }
    }

    /// <summary>When set, <see cref="OpenAsync"/> fails, as when the program can't be driven.</summary>
    public bool FailOpen { get; set; }

    /// <summary>Whether the program's process is running, by the name a process check knows it by (<see cref="OfficePrograms.ProcessName"/>).</summary>
    public bool IsRunning(string processName) => _running.ContainsKey(processName);

    /// <summary>Starts or stops a program without opening anything, as another document of the user's does.</summary>
    public void SetRunning(OfficeProgram p, bool running)
    {
        if (running)
            _running[OfficePrograms.ProcessName(p)] = true;
        else
            _running.TryRemove(OfficePrograms.ProcessName(p), out _);
    }

    public Task OpenAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        if (FailOpen)
            throw new OfficeScriptException($"{OfficePrograms.CheckOutName(p)} didn't answer (fake).");
        lock (_lock)
            _opened.Add((p, path));
        SetRunning(p, true);
        // The program keeps an owner file beside a copy it can write; a read-only copy gets none.
        if (OperatingSystem.IsWindows() || (File.GetUnixFileMode(path) & UnixFileMode.UserWrite) != 0)
            File.WriteAllText(OwnerPath(p, path), "fake owner file");
        return Task.CompletedTask;
    }

    /// <summary>The program's owner file for a copy: the first name it may use (Word's long-name form).</summary>
    public static string OwnerPath(OfficeProgram p, string path) =>
        Path.Combine(Path.GetDirectoryName(path)!, OfficePrograms.OwnerNames(p, Path.GetFileName(path)).First());

    /// <summary>The window closes: the program removes its owner file.</summary>
    public static void Close(OfficeProgram p, string path) => File.Delete(OwnerPath(p, path));

    public Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default) => throw Unexpected();

    public Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default) => throw Unexpected();

    public Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default) => throw Unexpected();

    public Task SaveAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<RenameResult> RenameIfUnchangedAsync(OfficeProgram p, string path, string newPath, CancellationToken ct = default) => throw Unexpected();

    public Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default) => throw Unexpected();

    public Task ReopenAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<bool> SetDocIdAsync(string path, string docRef, CancellationToken ct = default) => throw Unexpected();

    private static NotSupportedException Unexpected() => new("The fake scripting only opens documents.");
}
