using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// Which app a new copy of a file opens in (M3.5 spec §5.10): <see cref="LibreOfficeApp.AppName"/>,
/// <see cref="MicrosoftOfficeApp.AppName"/>, or <see cref="DefaultApp.AppName"/> (the system's default app, read-only) for a
/// file no office app edits. For a Microsoft format or an older one it is the user's choice for the file's kind
/// (<see cref="HelperSettings.Kinds"/>), else the system's default app for the kind's main format when that is Microsoft
/// Office, else LibreOffice; an OpenDocument file is LibreOffice's whatever the choice. Only an installed app is chosen:
/// the other one stands in for one that isn't, and the default app for both missing. The settings are read at each call, so a
/// change takes effect with the next copy opened.
/// </summary>
/// <param name="settings">The helper's settings, kept by reference.</param>
/// <param name="installed">Which apps are installed.</param>
/// <param name="systemDefaultApp">
/// The app the system opens an extension with (given without its dot, such as <c>docx</c>): <c>Microsoft Office</c>,
/// <c>LibreOffice</c> or null for anything else, Pages for instance.
/// </param>
public sealed class AppChooser(HelperSettings settings, InstalledApps installed, Func<string, string?> systemDefaultApp)
{
    private static readonly string[] OpenDocumentExtensions = [".odt", ".ott", ".ods", ".odp", ".odg"];

    public string For(string fileName)
    {
        if (FileKinds.OfFile(fileName) is not { } kind)
            return DefaultApp.AppName;

        var microsoftOffice = MicrosoftOfficeApp.ProgramFor(fileName) is { } program && installed.Has(program);
        var preferred = IsOpenDocument(fileName)
            ? LibreOfficeApp.AppName
            : settings.ChosenApp(kind.Label) ?? SystemsChoice(kind) ?? LibreOfficeApp.AppName;
        var (first, second) = preferred == MicrosoftOfficeApp.AppName
            ? (microsoftOffice ? MicrosoftOfficeApp.AppName : null, installed.LibreOffice ? LibreOfficeApp.AppName : null)
            : (installed.LibreOffice ? LibreOfficeApp.AppName : null, microsoftOffice ? MicrosoftOfficeApp.AppName : null);
        return first ?? second ?? DefaultApp.AppName;
    }

    /// <summary>Microsoft Office when it is what the system opens the kind's main format with; null otherwise, and for a kind Office doesn't open.</summary>
    private string? SystemsChoice(FileKind kind) =>
        kind.MainFormat is { } format && systemDefaultApp(format) == MicrosoftOfficeApp.AppName ? MicrosoftOfficeApp.AppName : null;

    private static bool IsOpenDocument(string fileName) =>
        OpenDocumentExtensions.Contains(Path.GetExtension(fileName), StringComparer.OrdinalIgnoreCase);
}
