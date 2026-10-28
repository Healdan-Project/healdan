namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Settings' row for the LibreOffice extension (polish spec §6.5): the value on the right, the line under its name,
/// whether Install… shows, and the row's tooltip.
/// </summary>
public sealed record ExtensionSummary(string Value, string? Note, bool CanInstall, string Tip)
{
    public static readonly ExtensionSummary NotInThisBuild =
        new("Not in This Build", "The installed app installs it.", false, "Not in this build: the installed app installs it.");
}
