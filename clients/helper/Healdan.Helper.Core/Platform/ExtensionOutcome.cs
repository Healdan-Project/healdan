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

    /// <summary>It was opened in LibreOffice before, and LibreOffice is running, so it can't be checked until it isn't.</summary>
    AwaitingLibreOffice,

    /// <summary>Something went wrong; the log says what, and the user was told when there's something to do.</summary>
    Failed
}
