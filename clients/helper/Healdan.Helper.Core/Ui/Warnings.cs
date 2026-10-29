using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Core.Ui;

/// <summary>What the warning windows say (spec §5.2, §5.8, §5.10), the first two in the owner's words.</summary>
public static class Warnings
{
    /// <param name="deviceName">The device's name as the hold has it, such as <c>Healdan Helper on MacBook</c>.</param>
    public static HeldElsewhereWarning HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved,
        DateTimeOffset now, TimeZoneInfo? zone = null)
    {
        var device = DeviceNames.Short(deviceName);
        return new HeldElsewhereWarning(
            $"#{docNumber} is checked out to you on {device}" + (string.IsNullOrWhiteSpace(app) ? "" : $" ({app})"),
            lastSaved is { } at ? $"It was last saved to Healdan {When.InSentence(at, now, zone)}." : null,
            $"Edits on {device} that haven't reached Healdan won't be in this copy.",
            $"If {device} was offline or still has unsaved changes, editing here means working without them. "
            + $"{device} keeps its copy, and you can file it later as a new document.");
    }

    /// <summary>After a Crash: "<c>title</c> closed unexpectedly." then what became of it (polish spec §6.8).</summary>
    public static CrashedWarning Crashed(string title, bool readOnly) => new(
        $"{title} closed unexpectedly.",
        readOnly ? "It was read-only, so nothing is lost." : "It's still checked out to you, and your local copy is kept.");

    /// <summary>Sign Out couldn't reach Healdan to revoke the token (spec §5.2).</summary>
    /// <param name="server">The server's host, and its port when it isn't the usual one.</param>
    /// <param name="unsent">How many copies have a save that never reached Healdan.</param>
    public static SignOutHereWarning SignOutHere(string server, int unsent) => new(
        $"Healdan at {server} can't be reached",
        "Healdan Helper can't end this computer's sign-in there. If the server has gone for good, or you're moving to "
        + "another one, it can forget the sign-in on this computer instead. Healdan still counts this computer as signed "
        + "in until the sign-in expires or you change your password.",
        unsent switch
        {
            0 => null,
            1 => $"1 save hasn't reached Healdan. Its copy stays on this computer, and goes up the next time you sign in to {server}.",
            _ => $"{unsent} saves haven't reached Healdan. Their copies stay on this computer, and go up the next time you sign in to {server}."
        });
}
