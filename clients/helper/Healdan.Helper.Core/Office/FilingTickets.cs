using System.Security.Cryptography;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// Save to Healdan's window, answered Save, from Word, Excel or PowerPoint (M3.5 spec §5.5): <c>/v1/file</c> names it by
/// <paramref name="Id"/>. <paramref name="Window"/> is the program's front window as <c>save-new</c> arrived, before the
/// helper's window opened, which is the one whose button was pressed; <paramref name="Replaces"/> the copy it was, for
/// Save as New Document; <paramref name="Document"/> what the user chose in the window: folder, name and properties.
/// </summary>
public sealed record FilingTicket(string Id, OfficeProgram Program, FrontDocument Window, string? Replaces, DateTimeOffset Expires,
    NewDocument Document);

/// <summary>
/// The tickets Save to Healdan's window has given and <c>/v1/file</c> hasn't used yet. Each is used once, and expires
/// after <see cref="Lifetime"/>: a window the user saved from long ago may be another document by now.
/// </summary>
/// <param name="clock">The real clock by default; tests pass one they can move on.</param>
public sealed class FilingTickets(TimeProvider? clock = null)
{
    /// <summary>How long a ticket can be used for.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(10);

    private readonly TimeProvider _clock = clock ?? TimeProvider.System;
    private readonly Dictionary<string, FilingTicket> _tickets = new(StringComparer.Ordinal);

    /// <summary>A new ticket for the window, which expires after <see cref="Lifetime"/>. Expired tickets go meanwhile.</summary>
    public FilingTicket Issue(OfficeProgram p, FrontDocument window, string? replaces, NewDocument document)
    {
        var now = _clock.GetUtcNow();
        var ticket = new FilingTicket(Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)), p, window, replaces, now + Lifetime, document);
        lock (_tickets)
        {
            foreach (var expired in _tickets.Values.Where(t => t.Expires <= now).Select(t => t.Id).ToList())
                _tickets.Remove(expired);
            _tickets[ticket.Id] = ticket;
        }
        return ticket;
    }

    /// <summary>The ticket, which can't be used again; null when it was never given, has been used or has expired.</summary>
    public FilingTicket? Take(string id)
    {
        lock (_tickets)
            return _tickets.Remove(id, out var ticket) && ticket.Expires > _clock.GetUtcNow() ? ticket : null;
    }
}
