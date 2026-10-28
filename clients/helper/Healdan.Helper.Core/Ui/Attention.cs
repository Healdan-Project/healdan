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
    public static string? Reason(bool signedIn, bool startFailed, bool crashed, int kept, bool extensionMissing, bool extensionOutdated) =>
        !signedIn ? "Not Signed In"
        : startFailed ? "Couldn't Start"
        : crashed ? "A Document Closed Unexpectedly"
        : kept > 0 ? "Not Yet in Healdan"
        : extensionOutdated ? "LibreOffice Extension Needs Updating"
        : extensionMissing ? "LibreOffice Extension Not Installed"
        : null;

    /// <summary>The icon's tooltip: the app's name, and the reason when there is one.</summary>
    public static string ToolTip(string? reason) => reason is null ? "Healdan Helper" : $"Healdan Helper: {reason}";
}
