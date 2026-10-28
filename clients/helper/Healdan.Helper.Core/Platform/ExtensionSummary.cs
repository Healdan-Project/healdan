namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Settings' row for the LibreOffice extension (polish spec §6.5): the value on the right, the line under its name,
/// whether its button shows, the row's tooltip, and the button's label: <see cref="Install"/> while none is installed,
/// and <see cref="Update"/> while an older version is (changed in the build).
/// </summary>
public sealed record ExtensionSummary(string Value, string? Note, bool CanInstall, string Tip, string Button = ExtensionSummary.Install)
{
    /// <summary>The button while no extension is installed.</summary>
    public const string Install = "Install…";

    /// <summary>The button while an older version is installed; it runs the same install.</summary>
    public const string Update = "Update…";

    public static readonly ExtensionSummary NotInThisBuild =
        new("Not in This Build", "The installed app installs it.", false, "Not in this build: the installed app installs it.");
}
