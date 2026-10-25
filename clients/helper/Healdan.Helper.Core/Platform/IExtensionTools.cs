namespace Healdan.Helper.Core.Platform;

/// <summary>
/// What installing the LibreOffice extension needs from the system (spec §5.12). The app implements it: LibreOffice's
/// registry and <c>open</c> on macOS, where unopkg is never run (R22), and <c>unopkg</c> on Linux. Tests fake it.
/// </summary>
public interface IExtensionTools
{
    /// <summary>LibreOffice is installed where the helper looks for it.</summary>
    bool LibreOfficeInstalled { get; }

    /// <summary>LibreOffice is running, so <c>unopkg</c> mustn't be: beside it, <c>unopkg list</c> hung for minutes and listed nothing.</summary>
    bool LibreOfficeRunning();

    /// <summary>
    /// Healdan's extension as LibreOffice's user registry records it (<see cref="ExtensionVersion.FromRegistry"/>),
    /// read from its files without running anything; null when none is recorded.
    /// </summary>
    InstalledExtension? ReadInstalled();

    /// <summary><c>unopkg list</c>'s output (Linux only); null when it couldn't run or took too long.</summary>
    Task<string?> ListAsync(CancellationToken cancel);

    /// <summary><c>unopkg add --force</c>: null when it worked, otherwise why not, for the log.</summary>
    Task<string?> AddAsync(string oxt, CancellationToken cancel);

    /// <summary><c>unopkg remove</c> of Healdan's extension, to clear what a failed add left. Never throws.</summary>
    Task RemoveAsync(CancellationToken cancel);

    /// <summary>Opens the <c>.oxt</c> in LibreOffice, which asks the user to install it; throws when it can't.</summary>
    void OpenInLibreOffice(string oxt);
}
