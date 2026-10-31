using System.Collections.Concurrent;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// Word, Excel and PowerPoint as the cycle sees them through <see cref="IOfficeScripting"/>: opening a copy starts the
/// program (<see cref="IsRunning"/>, which is what a process check would say) and, for a copy it can write, makes the
/// owner file it keeps beside it. The test closes the window by deleting that file (<see cref="Close"/>) or crashes the
/// program, which leaves it. The operations the cycle uses after a save act on the files as the programs do, and are
/// recorded: the rename (<see cref="RenameIfUnchangedAsync"/>), Save As, Save, PowerPoint's close and reopen, and Word's
/// doc ID. The rest are for the add-in's routes, so a call the test didn't expect fails.
/// </summary>
public sealed class FakeScripting : IOfficeScripting
{
    private readonly object _lock = new();
    private readonly List<(OfficeProgram Program, string Path)> _opened = [];
    private readonly List<(OfficeProgram Program, string Path, string NewPath)> _renames = [];
    private readonly List<(OfficeProgram Program, DocumentRef Doc, string NewPath, string? Fingerprint)> _savesAs = [];
    private readonly List<(OfficeProgram Program, string Path)> _saves = [];
    private readonly List<(OfficeProgram Program, string Path)> _reopens = [];
    private readonly List<(string Path, string DocRef)> _docIds = [];
    private readonly ConcurrentQueue<RenameResult> _renameAnswers = new();
    private readonly ConcurrentQueue<Exception> _renameFailures = new();
    private readonly ConcurrentQueue<Exception> _saveAsFailures = new();
    private readonly ConcurrentDictionary<string, bool> _running = new(StringComparer.Ordinal);

    /// <summary>Every open the cycle asked for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Path)> Opened => Copy(_opened);

    /// <summary>Every rename the cycle asked for, in order, whatever it answered.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Path, string NewPath)> Renames => Copy(_renames);

    /// <summary>Every Save As the cycle asked for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, DocumentRef Doc, string NewPath, string? Fingerprint)> SavesAs => Copy(_savesAs);

    /// <summary>Every save in place the cycle asked for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Path)> Saves => Copy(_saves);

    /// <summary>Every close and reopen the cycle asked PowerPoint for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Path)> Reopens => Copy(_reopens);

    /// <summary>Every doc ID the cycle set in Word, in order, whether the document had the property or not.</summary>
    public IReadOnlyList<(string Path, string DocRef)> DocIds => Copy(_docIds);

    /// <summary>When set, <see cref="OpenAsync"/> fails, as when the program can't be driven.</summary>
    public bool FailOpen { get; set; }

    /// <summary>Whether Word's documents carry <c>Healdan Doc ID</c>, which Insert Doc ID adds: without it, <see cref="SetDocIdAsync"/> answers false.</summary>
    public bool HasDocId { get; set; } = true;

    /// <summary>What the window holds, which Save As writes: the recovered work after a crash, say.</summary>
    public string WindowText { get; set; } = "the window as it is";

    /// <summary>What a rename that answers <see cref="RenameResult.RenamedChanged"/> adds: an edit typed between the check and the save.</summary>
    public const string GapEdit = " and a line typed in the gap";

    /// <summary>
    /// When set, a rename leaves the owner file of the name it renamed from, as it leaves one a crash left: Word clears
    /// only the owner file of the document it opened.
    /// </summary>
    public bool KeepsOldOwnerFile { get; set; }

    /// <summary>Runs as a rename begins, before the program looks at the document: a save landing just then, say.</summary>
    public Action<string>? BeforeRename { get; set; }

    /// <summary>
    /// How long a rename takes to answer once the file is written under its new name, as Word's script looks for the
    /// renamed document and fingerprints it again.
    /// </summary>
    public TimeSpan RenameTakes { get; set; }

    /// <summary>How long PowerPoint's close and reopen leaves the presentation without its owner file.</summary>
    public TimeSpan ReopenGap { get; set; } = TimeSpan.FromSeconds(2);

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

    /// <summary>The next renames answer these, in order; after them, a rename renames (<see cref="RenameResult.Renamed"/>).</summary>
    public void AnswerRenames(params RenameResult[] answers)
    {
        foreach (var answer in answers)
            _renameAnswers.Enqueue(answer);
    }

    /// <summary>The next rename throws this, as when Word is busy or a dialog is up.</summary>
    public void FailNextRename(Exception e) => _renameFailures.Enqueue(e);

    /// <summary>The next Save As throws this, writing nothing.</summary>
    public void FailNextSaveAs(Exception e) => _saveAsFailures.Enqueue(e);

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

    /// <summary>
    /// The program crashed and starts again, restoring its window at the copy's own path, as Word does with a recovered
    /// document: it's running, and its owner file beside the copy is written again. The write time moves on by a
    /// second, so the clock's resolution can't hide the new file.
    /// </summary>
    public void Recover(OfficeProgram p, string path)
    {
        SetRunning(p, true);
        var owner = OwnerPath(p, path);
        var before = File.Exists(owner) ? File.GetLastWriteTimeUtc(owner) : DateTime.UtcNow;
        File.Delete(owner);
        File.WriteAllText(owner, "fake owner file, written again");
        File.SetLastWriteTimeUtc(owner, (before > DateTime.UtcNow ? before : DateTime.UtcNow).AddSeconds(1));
    }

    public Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default) => throw Unexpected();

    public Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default) => throw Unexpected();

    public Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default) => throw Unexpected();

    public Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default) => throw Unexpected();

    /// <summary>The document is already saved as the test left it, so nothing is written.</summary>
    public Task SaveAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        lock (_lock)
            _saves.Add((p, path));
        return Task.CompletedTask;
    }

    /// <summary>
    /// As Word and Excel do: with no unsaved changes, the document is written under the new name, the file it was saved
    /// from stays where it was, and the owner file goes to the new name. The new file isn't the old one byte for byte,
    /// as the program records its own name in it.
    /// </summary>
    public async Task<RenameResult> RenameIfUnchangedAsync(OfficeProgram p, string path, string newPath, CancellationToken ct = default)
    {
        if (!OfficePrograms.RenamesAfterSave(p))
            throw new NotSupportedException($"{OfficePrograms.CheckOutName(p)} copies keep the name they were opened with.");
        lock (_lock)
            _renames.Add((p, path, newPath));
        BeforeRename?.Invoke(path);
        if (_renameFailures.TryDequeue(out var failure))
            throw failure;
        var answer = _renameAnswers.TryDequeue(out var queued) ? queued : RenameResult.Renamed;
        if (answer is RenameResult.Missing or RenameResult.Unsaved)
            return answer;
        var text = File.ReadAllText(path) + (answer == RenameResult.RenamedChanged ? GapEdit : "");
        File.WriteAllText(newPath, $"{text} (as {Path.GetFileName(newPath)})");
        File.WriteAllText(OwnerPath(p, newPath), "fake owner file");
        if (!KeepsOldOwnerFile)
            File.Delete(OwnerPath(p, path));
        await Task.Delay(RenameTakes, ct);
        return answer;
    }

    /// <summary>Writes what the window holds (<see cref="WindowText"/>) to the new path, found by the path it was given.</summary>
    public Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default)
    {
        lock (_lock)
            _savesAs.Add((p, doc, newPath, fingerprint));
        if (_saveAsFailures.TryDequeue(out var failure))
            throw failure;
        File.WriteAllText(newPath, WindowText);
        return Task.FromResult(new SaveAsResult("path", false));
    }

    /// <summary>PowerPoint closes the presentation, so its owner file goes, and opens it again <see cref="ReopenGap"/> later.</summary>
    public async Task ReopenAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        if (p != OfficeProgram.PowerPoint)
            throw new NotSupportedException($"{OfficePrograms.CheckOutName(p)} documents don't need to be opened again after a save.");
        lock (_lock)
            _reopens.Add((p, path));
        File.Delete(OwnerPath(p, path));
        await Task.Delay(ReopenGap, ct);
        File.WriteAllText(OwnerPath(p, path), "fake owner file");
    }

    public Task<bool> SetDocIdAsync(string path, string docRef, CancellationToken ct = default)
    {
        lock (_lock)
            _docIds.Add((path, docRef));
        return Task.FromResult(HasDocId);
    }

    private IReadOnlyList<T> Copy<T>(List<T> list)
    {
        lock (_lock)
            return [.. list];
    }

    private static NotSupportedException Unexpected() => new("The fake scripting doesn't do that.");
}
