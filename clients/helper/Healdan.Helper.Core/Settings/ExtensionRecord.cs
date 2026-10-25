using System.Globalization;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// What the helper last did about its LibreOffice extension (spec §5.12), in <c>settings.json</c>: so the check runs
/// once per helper version, and the log and Settings can say which way worked.
/// </summary>
public sealed record ExtensionRecord
{
    /// <summary>The way it went in by <c>unopkg add</c>, from the helper's own process.</summary>
    public const string ByUnopkg = "unopkg";

    /// <summary>The way it went in by the user accepting the <c>.oxt</c> the helper opened in LibreOffice.</summary>
    public const string ByLibreOffice = "LibreOffice";

    /// <summary>It was installed already when the helper first looked.</summary>
    public const string Found = "found";

    /// <summary>The version last seen installed and registered.</summary>
    public string? Version { get; init; }

    /// <summary>How <see cref="Version"/> went in: <see cref="ByUnopkg"/>, <see cref="ByLibreOffice"/> or <see cref="Found"/>.</summary>
    public string? Way { get; init; }

    /// <summary>When <see cref="Version"/> and <see cref="Way"/> were recorded.</summary>
    public DateTimeOffset? At { get; init; }

    /// <summary>The helper version whose check found the extension current; another version checks again.</summary>
    public string? CheckedFor { get; init; }

    /// <summary>The bundled version the helper opened in LibreOffice for the user to accept, not yet seen installed.</summary>
    public string? OpenedFor { get; init; }

    /// <summary>The helper version whose <c>unopkg add</c> failed, so unopkg isn't tried again until the next one.</summary>
    public string? UnopkgFailedFor { get; init; }

    /// <summary>One line for Settings.</summary>
    public string Describe()
    {
        var installed = Version is null
            ? null
            : Way switch
            {
                ByUnopkg => $"Version {Version}, installed by Healdan Helper{On()}.",
                ByLibreOffice => $"Version {Version}, installed in LibreOffice{On()}.",
                _ => $"Version {Version}, installed."
            };
        if (OpenedFor is not null)
            return $"Waiting for you to accept version {OpenedFor} in LibreOffice." + (Version is null ? "" : $" Version {Version} is installed.");
        return installed ?? "Not installed yet.";
    }

    private string On() => At is { } at ? " on " + at.ToLocalTime().ToString("d MMMM yyyy", CultureInfo.InvariantCulture) : "";
}
