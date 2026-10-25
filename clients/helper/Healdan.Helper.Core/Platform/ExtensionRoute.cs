namespace Healdan.Helper.Core.Platform;

/// <summary>How the helper installs its LibreOffice extension (spec §5.12).</summary>
public enum ExtensionRoute
{
    /// <summary>
    /// macOS (R22): the <c>.oxt</c> opened in LibreOffice for the user to accept, and never unopkg, which failed on
    /// every Mac it was tried on. What's installed is read from LibreOffice's user registry, so nothing runs.
    /// </summary>
    OpenInLibreOffice,

    /// <summary>Linux (phase 2): unopkg while LibreOffice is closed, with the <c>.oxt</c> opened in LibreOffice if it fails.</summary>
    Unopkg
}
