namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// A copy's state as the channel gives it to the add-ins (plan: the channel's routes).
/// </summary>
/// <param name="Known">False for a path that isn't a local copy; the other members are then empty.</param>
/// <param name="DocNumber">The document's number.</param>
/// <param name="Title">What the window's title shows (spec §5.7), from the content's version: <c>Lease agreement #1234 v2.1</c>.</param>
/// <param name="DocRef">The doc ID value: <c>#1234 v2.1</c>.</param>
/// <param name="ReadOnly">Opened without a check-out.</param>
/// <param name="Path">The copy's path.</param>
/// <param name="HoldBy">For a read-only copy, who has the document checked out, when someone has; null otherwise.</param>
public sealed record CopyState(bool Known, long DocNumber, string Title, string DocRef, bool ReadOnly, string Path, string? HoldBy)
{
    /// <summary>The state of a path the helper has no copy at.</summary>
    public static CopyState Unknown(string path) => new(false, 0, "", "", false, path, null);
}
