using Healdan.Helper.Core.Browse;

namespace Healdan.Helper.Core.Ui;

/// <summary>What the two warning windows say (spec §5.8, §5.10), in the owner's words.</summary>
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

    /// <summary>After a Crash: "<c>title</c> closed unexpectedly. It's still checked out to you, and your local copy is kept."</summary>
    public static string Crashed(string title, bool readOnly) => readOnly
        ? $"{title} closed unexpectedly. It was read-only, so nothing is lost."
        : $"{title} closed unexpectedly. It's still checked out to you, and your local copy is kept.";
}
