namespace Healdan.Helper.Core.Platform;

/// <summary>Healdan's LibreOffice extension as <c>unopkg list</c> shows it.</summary>
/// <param name="Version">Its version, if the listing gives one.</param>
/// <param name="Registered">
/// LibreOffice loads it. A failed <c>unopkg add</c> (the trial's "couldn't connect to pipe") leaves it listed but
/// unregistered.
/// </param>
public sealed record InstalledExtension(string? Version, bool Registered)
{
    /// <summary>The version LibreOffice is using, or null when it isn't using any: an unregistered one counts as missing.</summary>
    public string? UsableVersion => Registered ? Version : null;
}
