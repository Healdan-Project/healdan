using System.Collections.Concurrent;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// Word, Excel and PowerPoint as the cycle sees them through <see cref="IOfficeScripting"/>: opening a copy starts the
/// program (<see cref="IsRunning"/>, which is what a process check would say) and, for a copy it can write, makes the
/// owner file it keeps beside it. The test closes the window by deleting that file (<see cref="Close"/>) or crashes the
/// program, which leaves it. The operations the cycle uses after a save or a crash act on the files as the programs do,
/// and are recorded: the rename (<see cref="RenameIfUnchangedAsync"/>), the look for a document by its path, Save As,
/// Save, PowerPoint's close and reopen, and Word's doc ID. Filing from Office asks for the front window
/// (<see cref="Front"/>), the add-in's mark, which answer what the test set, and a fingerprint of what the window holds
/// (<see cref="WindowText"/>), which Save As compares, as the scripts do. Anything else is for the add-in's other
/// routes, so a call the test didn't expect fails.
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
    private readonly ConcurrentQueue<(Exception Error, bool AfterRenaming)> _renameFailures = new();
    private readonly ConcurrentQueue<bool> _isOpenAnswers = new();
    private int _isOpenAsked;
    private readonly ConcurrentQueue<Exception> _saveAsFailures = new();
    private readonly ConcurrentQueue<bool> _markAnswers = new();
    private readonly List<(OfficeProgram Program, string Mark)> _marksAsked = [];
    private readonly List<(OfficeProgram Program, DocumentRef Doc)> _fingerprints = [];
    private readonly ConcurrentQueue<string?> _fingerprintAnswers = new();
    private readonly ConcurrentQueue<Exception> _fingerprintFailures = new();
    private int _frontAsked;
    private readonly List<OfficeProgram> _frontPrograms = [];
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

    /// <summary>
    /// The next rename throws this, as when Word is busy or a dialog is up. With <paramref name="afterRenaming"/>, the
    /// program renames the copy all the same, as Word carries out a Save As whose script gave up waiting.
    /// </summary>
    public void FailNextRename(Exception e, bool afterRenaming = false) => _renameFailures.Enqueue((e, afterRenaming));

    /// <summary>The next looks for a document by its path answer these, in order; after them, whether its owner file is there.</summary>
    public void AnswerIsOpen(params bool[] answers)
    {
        foreach (var answer in answers)
            _isOpenAnswers.Enqueue(answer);
    }

    /// <summary>When set, no document is ever found by its path, as when the program lists a restored window under another name.</summary>
    public bool NeverListed { get; set; }

    /// <summary>How many times the cycle looked for a document by its path.</summary>
    public int IsOpenAsked => Volatile.Read(ref _isOpenAsked);

    /// <summary>
    /// How long a Save As takes. Meanwhile the document's owner file is gone, as when Word saves a window over its own
    /// file and writes its owner file again.
    /// </summary>
    public TimeSpan SaveAsTakes { get; set; }

    /// <summary>The next Save As throws this, writing nothing.</summary>
    public void FailNextSaveAs(Exception e) => _saveAsFailures.Enqueue(e);

    /// <summary>
    /// Runs as a Save As begins, before the program reads the window: to see what the user had been told by then, or to
    /// type into the window, say.
    /// </summary>
    public Action? BeforeSaveAs { get; set; }

    /// <summary>
    /// Runs once a Save As under another name has written the file, before it answers, as the script answers only after
    /// the program has saved: a slow answer, say, during which the cycle sees the write settle.
    /// </summary>
    public Func<string, Task>? AfterSaveAsWrite { get; set; }

    /// <summary>The program's front window, which <see cref="FrontDocumentAsync"/> answers each time it's asked; none by default.</summary>
    public Func<OfficeProgram, FrontDocument?> Front { get; set; } = _ => null;

    /// <summary>How many times the front window was asked for.</summary>
    public int FrontAsked => Volatile.Read(ref _frontAsked);

    /// <summary>The programs whose front window was asked for, in order.</summary>
    public IReadOnlyList<OfficeProgram> FrontPrograms => Copy(_frontPrograms);

    /// <summary>
    /// Whether a document has unsaved changes, for Save as New Version (null: it isn't open). Unset, the question fails, as
    /// a call the test didn't expect.
    /// </summary>
    public Func<OfficeProgram, string, bool?>? Unsaved { get; set; }

    /// <summary>The next looks for the add-in's mark answer these, in order; after them, <see cref="MarksShown"/>.</summary>
    public void AnswerMarks(params bool[] answers)
    {
        foreach (var answer in answers)
            _markAnswers.Enqueue(answer);
    }

    /// <summary>Whether a look for the add-in's mark finds it, once the answers the test gave are used up. PowerPoint's never does.</summary>
    public bool MarksShown { get; set; }

    /// <summary>Runs at each look for the add-in's mark, before it answers: a line typed meanwhile, say.</summary>
    public Action? OnMarkLook { get; set; }

    /// <summary>Every look for the add-in's mark, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, string Mark)> MarksAsked => Copy(_marksAsked);

    /// <summary>What <see cref="FingerprintAsync"/> answers for a window holding <paramref name="windowText"/>.</summary>
    public static string FingerprintOf(string windowText) => "fingerprint of: " + windowText;

    /// <summary>Every fingerprint asked for, in order.</summary>
    public IReadOnlyList<(OfficeProgram Program, DocumentRef Doc)> Fingerprints => Copy(_fingerprints);

    /// <summary>
    /// The next fingerprints answer these, in order, null as when the program has no document to fingerprint; after
    /// them, what the window holds.
    /// </summary>
    public void AnswerFingerprints(params string?[] answers)
    {
        foreach (var answer in answers)
            _fingerprintAnswers.Enqueue(answer);
    }

    /// <summary>The next fingerprint throws this, as when the program doesn't answer.</summary>
    public void FailNextFingerprint(Exception e) => _fingerprintFailures.Enqueue(e);

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

    public Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default)
    {
        Interlocked.Increment(ref _frontAsked);
        lock (_lock)
            _frontPrograms.Add(p);
        return Task.FromResult(Front(p));
    }

    /// <summary>Found by its path: the answers the test gave, then whether the program's owner file is beside it.</summary>
    public Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default)
    {
        Interlocked.Increment(ref _isOpenAsked);
        if (_isOpenAnswers.TryDequeue(out var answer))
            return Task.FromResult(answer);
        return Task.FromResult(!NeverListed && File.Exists(OwnerPath(p, path)));
    }

    public Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default) =>
        Unsaved is { } unsaved ? Task.FromResult(unsaved(p, path)) : throw Unexpected();

    /// <summary>What the window holds now, summarised (<see cref="FingerprintOf"/>), unless the test gave another answer.</summary>
    public Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default)
    {
        lock (_lock)
            _fingerprints.Add((p, doc));
        if (_fingerprintFailures.TryDequeue(out var failure))
            throw failure;
        return Task.FromResult(_fingerprintAnswers.TryDequeue(out var answer) ? answer : FingerprintOf(WindowText));
    }

    public Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default)
    {
        lock (_lock)
            _marksAsked.Add((p, mark));
        OnMarkLook?.Invoke();
        return Task.FromResult(_markAnswers.TryDequeue(out var answer) ? answer : MarksShown);
    }

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
        var failure = _renameFailures.TryDequeue(out var queuedFailure) ? queuedFailure : default;
        if (failure.Error is not null && !failure.AfterRenaming)
            throw failure.Error;
        var answer = failure.Error is not null ? RenameResult.Renamed
            : _renameAnswers.TryDequeue(out var queued) ? queued : RenameResult.Renamed;
        if (answer is RenameResult.Missing or RenameResult.Unsaved)
            return answer;
        var text = File.ReadAllText(path) + (answer == RenameResult.RenamedChanged ? GapEdit : "");
        File.WriteAllText(newPath, $"{text} (as {Path.GetFileName(newPath)})");
        File.WriteAllText(OwnerPath(p, newPath), "fake owner file");
        if (!KeepsOldOwnerFile)
            File.Delete(OwnerPath(p, path));
        await Task.Delay(RenameTakes, ct);
        if (failure.Error is not null)
            throw failure.Error;
        return answer;
    }

    /// <summary>
    /// Writes what the window holds (<see cref="WindowText"/>) to the new path. It answers how the document was found, by
    /// the first of the path, the mark (when <see cref="MarksShown"/>) and the window's name it was given, never the front
    /// window, and whether the window changed since <paramref name="fingerprint"/>. A document saved under another name,
    /// as a new document is when it's filed, takes its owner file there, as the program does.
    /// </summary>
    public async Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default)
    {
        lock (_lock)
            _savesAs.Add((p, doc, newPath, fingerprint));
        BeforeSaveAs?.Invoke();
        if (_saveAsFailures.TryDequeue(out var failure))
            throw failure;
        var foundBy = doc.Path is not null ? "path" : doc.Mark is not null && MarksShown ? "mark" : "window";
        var answer = new SaveAsResult(foundBy, fingerprint is not null && fingerprint != FingerprintOf(WindowText));
        if (doc.Path != newPath)
        {
            File.WriteAllText(newPath, WindowText);
            File.WriteAllText(OwnerPath(p, newPath), "fake owner file");
            if (doc.Path is not null)
                File.Delete(OwnerPath(p, doc.Path));
            if (AfterSaveAsWrite is { } after)
                await after(newPath);
            return answer;
        }
        if (SaveAsTakes > TimeSpan.Zero)
        {
            File.Delete(OwnerPath(p, newPath));
            await Task.Delay(SaveAsTakes, ct);
        }
        File.WriteAllText(newPath, WindowText);
        if (SaveAsTakes > TimeSpan.Zero)
            File.WriteAllText(OwnerPath(p, newPath), "fake owner file");
        return answer;
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
