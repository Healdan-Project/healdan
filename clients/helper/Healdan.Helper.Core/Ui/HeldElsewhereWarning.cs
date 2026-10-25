namespace Healdan.Helper.Core.Ui;

/// <summary>Checked Out on Another Computer's words (spec §5.10), with the owner's warning about edits not yet in Healdan.</summary>
/// <param name="Title">"#1234 is checked out to you on MacBook (LibreOffice)".</param>
/// <param name="LastSaved">"It was last saved to Healdan today at 10:42.", or null when the server doesn't say.</param>
/// <param name="Emphasis">The warning's first sentence, shown bold.</param>
/// <param name="Detail">The rest of the warning.</param>
public sealed record HeldElsewhereWarning(string Title, string? LastSaved, string Emphasis, string Detail);
