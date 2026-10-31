namespace Healdan.Helper.Core.Copies;

/// <summary>
/// What the helper remembers about one local copy, in the hidden file beside it, so a restart (or a crash) loses nothing.
/// </summary>
/// <param name="DocNumber">The document's number in Healdan.</param>
/// <param name="DocumentName">The document's name with its extension, as Healdan has it.</param>
/// <param name="Version">The document's version the copy's next save starts from (its <c>ifVersion</c>).</param>
/// <param name="FileVersion">The content's version; it moves after each confirmed save, and the title shows it.</param>
/// <param name="ReadOnly">The copy was opened without a check-out.</param>
/// <param name="CheckedOutHere">The document is checked out to this computer for this copy.</param>
/// <param name="ConfirmedHash">SHA-256 (hex) of the content Healdan last confirmed; null until the first download finishes.</param>
/// <param name="PendingKind">
/// The kind of the newest save still to go up, or null: the one waiting, or else the one being sent. The one being sent
/// keeps its own kind in <paramref name="SentKind"/>.
/// </param>
/// <param name="App">The app the copy was opened in.</param>
/// <param name="LastSaveId">The id of the last save sent, so a retry after a lost answer isn't stored twice.</param>
/// <param name="FileName">
/// The copy's own file name. It's the name the copy was opened with, and stays while the app has it open, however far
/// <paramref name="FileVersion"/> moves on (R19): renaming an open file would break the app's save path and lock file.
/// Word and Excel are the exception: after a confirmed save, the app itself saves the copy under the new version's name
/// (M3.5 spec §5.4), and the record follows it (<see cref="CopyStore.Moved"/>).
/// </param>
/// <param name="PendingHash">
/// SHA-256 (hex) of the bytes <paramref name="LastSaveId"/> was sent with while its answer isn't known, else null. A
/// retry repeats that saveId only with those same bytes.
/// </param>
/// <param name="SentKind">
/// The kind <paramref name="LastSaveId"/> was sent as, read only while <paramref name="PendingHash"/> is set: a retry
/// repeats it as that kind, whatever newer save waits behind it. Null in a record from before it was kept.
/// </param>
/// <param name="Server">
/// The address of the server the copy came from (<see cref="ServerAddress"/>), written when the copy is made. Null in a
/// record from before it was kept: that copy belongs to the server in <c>settings.json</c> (spec §5.4).
/// </param>
public sealed record CopyRecord(
    long DocNumber,
    string DocumentName,
    string Version,
    string FileVersion,
    bool ReadOnly,
    bool CheckedOutHere,
    string? ConfirmedHash,
    string? PendingKind,
    string App,
    string? LastSaveId,
    string FileName,
    string? PendingHash = null,
    string? SentKind = null,
    string? Server = null);
