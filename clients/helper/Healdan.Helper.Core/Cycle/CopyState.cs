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
/// <param name="Pending">
/// The copy has changes still on their way to Healdan: a save queued, going up or waiting for the server, or one the
/// app wrote that hasn't settled yet. False once Healdan has them, and for a copy nothing more is sent for. The
/// extension asks <c>/v1/saved</c> again while it's true, so the title moves on however long the server takes.
/// </param>
/// <param name="SavesStopped">
/// The copy isn't read-only, but its saves won't go up any more: its check-out ended here, or newer content was stored
/// (M3.6 spec §6.3). The channel answers it as read-only; the helper's own windows read <see cref="ReadOnly"/>.
/// </param>
public sealed record CopyState(bool Known, long DocNumber, string Title, string DocRef, bool ReadOnly, string Path, string? HoldBy,
    bool Pending = false, bool SavesStopped = false)
{
    /// <summary>The state of a path the helper has no copy at.</summary>
    public static CopyState Unknown(string path) => new(false, 0, "", "", false, path, null);
}
