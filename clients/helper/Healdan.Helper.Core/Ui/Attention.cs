namespace Healdan.Helper.Core.Ui;

/// <summary>
/// Why the menu bar icon shows its dot (polish spec §7.2), the first reason first; null while nothing needs the user.
/// Saves waiting to be sent don't count: they clear by themselves.
/// </summary>
public static class Attention
{
    public static string? Reason(bool signedIn, bool startFailed, bool crashed, int kept, bool extensionMissing) =>
        !signedIn ? "Not Signed In"
        : startFailed ? "Couldn't Start"
        : crashed ? "A Document Closed Unexpectedly"
        : kept > 0 ? "Not Yet in Healdan"
        : extensionMissing ? "LibreOffice Extension Not Installed"
        : null;

    /// <summary>The icon's tooltip: the app's name, and the reason when there is one.</summary>
    public static string ToolTip(string? reason) => reason is null ? "Healdan Helper" : $"Healdan Helper: {reason}";
}
