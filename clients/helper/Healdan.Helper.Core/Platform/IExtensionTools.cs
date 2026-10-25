namespace Healdan.Helper.Core.Platform;

/// <summary>
/// What installing the LibreOffice extension needs from the system (spec §5.12). The app implements it on macOS
/// with LibreOffice's own <c>unopkg</c> and <c>open</c>; tests fake it.
/// </summary>
public interface IExtensionTools
{
    /// <summary>LibreOffice is installed where the helper looks for it.</summary>
    bool LibreOfficeInstalled { get; }

    /// <summary>LibreOffice is running, so <c>unopkg</c> mustn't be: beside it, <c>unopkg list</c> hung for minutes and listed nothing.</summary>
    bool LibreOfficeRunning();

    /// <summary><c>unopkg list</c>'s output; null when it couldn't run or took too long.</summary>
    Task<string?> ListAsync(CancellationToken cancel);

    /// <summary><c>unopkg add --force</c>: null when it worked, otherwise why not, for the log.</summary>
    Task<string?> AddAsync(string oxt, CancellationToken cancel);

    /// <summary><c>unopkg remove</c> of Healdan's extension, to clear what a failed add left. Never throws.</summary>
    Task RemoveAsync(CancellationToken cancel);

    /// <summary>Opens the <c>.oxt</c> in LibreOffice, which asks the user to install it; throws when it can't.</summary>
    void OpenInLibreOffice(string oxt);
}
