namespace Healdan.Helper.Core.Ui;

/// <summary>
/// Why the menu bar icon shows its dot (polish spec §7.2), the first reason first; null while nothing needs the user.
/// Saves waiting to be sent don't count: they clear by themselves.
/// </summary>
public static class Attention
{
    /// <param name="extensionMissing">The bundled LibreOffice extension isn't installed (<c>ExtensionCheck.Missing</c>).</param>
    /// <param name="extensionOutdated">
    /// An older version of it is (<c>ExtensionCheck.Older</c>), so its reason is that it needs updating, not that it
    /// isn't installed (changed in the build).
    /// </param>
    /// <param name="waiting">
    /// A window waits for the user's answer (#143), and may have gone behind another app's: the menu has a line that
    /// brings it up. It comes before the rest, as the app's button that opened it waits too.
    /// </param>
    public static string? Reason(bool signedIn, bool startFailed, bool waiting, bool crashed, int kept, bool extensionMissing, bool extensionOutdated) =>
        !signedIn ? "Not Signed In"
        : startFailed ? "Couldn't Start"
        : waiting ? "Waiting for Your Answer"
        : crashed ? "A Document Closed Unexpectedly"
        : kept > 0 ? "Not Yet in Healdan"
        : extensionOutdated ? "LibreOffice Extension Needs Updating"
        : extensionMissing ? "LibreOffice Extension Not Installed"
        : null;

    /// <summary>The icon's tooltip: the app's name, and the reason when there is one.</summary>
    public static string ToolTip(string? reason) => reason is null ? "Healdan Helper" : $"Healdan Helper: {reason}";
}
