namespace Healdan.Helper.Core.Platform;

/// <summary>How a start's extension check ended (<see cref="ExtensionCheck"/>).</summary>
public enum ExtensionOutcome
{
    /// <summary>This build carries no <c>.oxt</c> (a build run by hand, not the packaged app).</summary>
    NotBundled,

    /// <summary>LibreOffice isn't installed; the user was told.</summary>
    NoLibreOffice,

    /// <summary>The installed extension is the bundled one or newer.</summary>
    Current,

    /// <summary><c>unopkg add</c> installed it.</summary>
    InstalledByUnopkg,

    /// <summary>The <c>.oxt</c> was opened in LibreOffice for the user to accept; the next start checks it went in.</summary>
    OpenedInLibreOffice,

    /// <summary>Linux: it was opened in LibreOffice before, and LibreOffice is running, so it can't be checked until it isn't.</summary>
    AwaitingLibreOffice,

    /// <summary>
    /// macOS: it isn't installed, and LibreOffice was asked already for this helper version, so it isn't asked
    /// again; the user is told, and the menu offers Install the LibreOffice Extension.
    /// </summary>
    NotInstalled,

    /// <summary>Something went wrong; the log says what, and the user was told when there's something to do.</summary>
    Failed
}
