namespace Healdan.Helper.Core.Office;

/// <summary>
/// The document in a program's front window: <paramref name="Path"/> is null for a document never saved.
/// <paramref name="Name"/> is the document's own name, which Word leaves empty until the first save, and
/// <paramref name="WindowName"/> the name its window shows (null when the program gave none), which finds a document
/// that has no path yet.
/// </summary>
public sealed record FrontDocument(string? Path, string Name, string? WindowName);

/// <summary>
/// How to find a document: by its path, else by the add-in's mark, else by its window's name. Failing those, a fingerprint
/// takes the front window, while Save As finds nothing: it never saves another document under a copy's name.
/// </summary>
public sealed record DocumentRef(string? Path, string? Mark, string? WindowName);

/// <summary>What <see cref="IOfficeScripting.RenameIfUnchangedAsync"/> did.</summary>
public enum RenameResult
{
    /// <summary>The document isn't open.</summary>
    Missing,

    /// <summary>It has unsaved changes, so nothing was saved: renaming would save them as a version nobody asked for (spec §5.4).</summary>
    Unsaved,

    /// <summary>It was saved under the new name.</summary>
    Renamed,

    /// <summary>It was saved under the new name, and its content changed between the check and the save: an edit landed in the new file.</summary>
    RenamedChanged,
}

/// <summary>
/// What <see cref="IOfficeScripting.SaveAsAsync"/> did. <paramref name="FoundBy"/> is how the document was found:
/// <c>path</c>, <c>mark</c> or <c>window</c>, never the front window. <paramref name="Changed"/> is whether its content differed from the fingerprint
/// the caller gave, as the save began or once it was done, or couldn't be found under the new name to tell; false when the
/// caller gave none.
/// </summary>
public sealed record SaveAsResult(string FoundBy, bool Changed);

/// <summary>An operation on Word, Excel or PowerPoint failed: the program refused, didn't answer in time, or the document wasn't there.</summary>
public sealed class OfficeScriptException : Exception
{
    public OfficeScriptException(string message) : base(message)
    {
    }

    public OfficeScriptException(string message, Exception inner) : base(message, inner)
    {
    }
}

/// <summary>
/// Everything the helper does to a document in Word, Excel or PowerPoint, behind one seam (M3.5 spec §5.2). The macOS
/// implementation drives the apps with AppleScript, and a Windows one over COM may follow; nothing above the seam knows
/// which. Documents are found by path (compared as the app reports it, or by file name), and a path is always a full one.
/// </summary>
public interface IOfficeScripting
{
    /// <summary>
    /// Opens a file in its program, kept off Open Recent where the program allows (Word and Excel; PowerPoint has no
    /// control), or brings its window to the front if it is open already.
    /// </summary>
    Task OpenAsync(OfficeProgram p, string path, CancellationToken ct = default);

    /// <summary>The document in the program's front window, or null when it has none open (or isn't running).</summary>
    Task<FrontDocument?> FrontDocumentAsync(OfficeProgram p, CancellationToken ct = default);

    /// <summary>Whether the program has the document open.</summary>
    Task<bool> IsOpenAsync(OfficeProgram p, string path, CancellationToken ct = default);

    /// <summary>Whether the document has changes not yet saved; null when it isn't open.</summary>
    Task<bool?> HasUnsavedChangesAsync(OfficeProgram p, string path, CancellationToken ct = default);

    /// <summary>
    /// A summary of the document's content, to tell whether an edit landed between two moments: the SHA-256, in hex, of
    /// the text the program's script gives. The front document's when the one given isn't found, as a wrong guess only
    /// makes a later Save As say it changed; null when no document is open.
    /// </summary>
    Task<string?> FingerprintAsync(OfficeProgram p, DocumentRef doc, CancellationToken ct = default);

    /// <summary>Whether an open document carries the add-in's mark.</summary>
    Task<bool> HasMarkAsync(OfficeProgram p, string mark, CancellationToken ct = default);

    /// <summary>Saves the document where it is.</summary>
    /// <exception cref="OfficeScriptException">It isn't open.</exception>
    Task SaveAsync(OfficeProgram p, string path, CancellationToken ct = default);

    /// <summary>
    /// Saves the document under a new name only if it has no unsaved changes, kept off Open Recent, in one script that
    /// also fingerprints the content before and after. The new file's extension picks the file format.
    /// </summary>
    /// <exception cref="NotSupportedException">The program doesn't rename after a save (<see cref="OfficePrograms.RenamesAfterSave"/>).</exception>
    Task<RenameResult> RenameIfUnchangedAsync(OfficeProgram p, string path, string newPath, CancellationToken ct = default);

    /// <summary>
    /// Saves a document under a new path, kept off Open Recent where the program allows, and says how it found the
    /// document and whether its content, before the save or after it, differs from <paramref name="fingerprint"/> (a value
    /// <see cref="FingerprintAsync"/> gave).
    /// The new path's extension picks the file format. It never takes the front window for the document given (Review
    /// Focus 2): another document saved under the new path would become that copy.
    /// </summary>
    /// <exception cref="OfficeScriptException">The document wasn't found by anything it was given.</exception>
    Task<SaveAsResult> SaveAsAsync(OfficeProgram p, DocumentRef doc, string newPath, string? fingerprint, CancellationToken ct = default);

    /// <summary>
    /// Closes a saved document and opens it again from its file, so that PowerPoint saves it in place afterwards (spec §5.5).
    /// A document with unsaved changes is left as it is.
    /// </summary>
    /// <exception cref="NotSupportedException">Only PowerPoint needs this.</exception>
    /// <exception cref="OfficeScriptException">The document isn't open, or has unsaved changes.</exception>
    Task ReopenAsync(OfficeProgram p, string path, CancellationToken ct = default);

    /// <summary>
    /// Word: sets the document property <c>Healdan Doc ID</c>, updates the fields showing it and leaves the document
    /// saved if it was. False when the document has no such property (none is made) or isn't open.
    /// </summary>
    Task<bool> SetDocIdAsync(string path, string docRef, CancellationToken ct = default);
}
