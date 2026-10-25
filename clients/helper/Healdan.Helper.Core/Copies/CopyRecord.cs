namespace Healdan.Helper.Core.Copies;

/// <summary>
/// What the helper remembers about one local copy, in the hidden file beside it, so a restart (or a crash) loses nothing.
/// </summary>
/// <param name="DocNumber">The document's number in Healdan.</param>
/// <param name="DocumentName">The document's name with its extension, as Healdan has it.</param>
/// <param name="Version">The document's version when the copy was made.</param>
/// <param name="FileVersion">The content's version; it moves after each confirmed save and is in the copy's name.</param>
/// <param name="ReadOnly">The copy was opened without a check-out.</param>
/// <param name="CheckedOutHere">The document is checked out to this computer for this copy.</param>
/// <param name="ConfirmedHash">SHA-256 (hex) of the content Healdan last confirmed; null until the first download finishes.</param>
/// <param name="PendingKind">The kind of save waiting to go up, or null.</param>
/// <param name="App">The app the copy was opened in.</param>
/// <param name="LastSaveId">The id of the last save sent, so a retry after a lost answer isn't stored twice.</param>
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
    string? LastSaveId);
