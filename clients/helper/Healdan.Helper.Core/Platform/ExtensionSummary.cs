namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Settings' row for the LibreOffice extension (polish spec §6.5): the value on the right, the line under its name, and
/// whether Install… shows.
/// </summary>
public sealed record ExtensionSummary(string Value, string? Note, bool CanInstall)
{
    public static readonly ExtensionSummary NotInThisBuild = new("Not in This Build", "The installed app installs it.", false);
}
