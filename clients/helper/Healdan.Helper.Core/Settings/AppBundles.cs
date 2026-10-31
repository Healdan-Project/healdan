using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// The bundle identifiers macOS knows the office apps by, which LaunchServices finds an app by wherever it is installed and
/// names the app that opens a kind of file by (M3.5 spec §5.10). Matching goes by identifier, never by where the app is or
/// what its window says.
/// </summary>
public static class AppBundles
{
    /// <summary>LibreOffice's, as <c>LibreOffice.app</c>'s Info.plist names it.</summary>
    public const string LibreOffice = "org.libreoffice.script";

    /// <summary>The program's bundle identifier. PowerPoint's has a small p.</summary>
    public static string Of(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => "com.microsoft.Word",
        OfficeProgram.Excel => "com.microsoft.Excel",
        OfficeProgram.PowerPoint => "com.microsoft.Powerpoint",
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>
    /// The name the helper gives the app with that identifier: <c>Microsoft Office</c> for Word, Excel and PowerPoint,
    /// <c>LibreOffice</c> for LibreOffice, and null for any other app, or none (the system has no default for the kind of
    /// file). LaunchServices matches identifiers without regard to case, and so does this.
    /// </summary>
    public static string? NameOf(string? bundleIdentifier)
    {
        if (string.IsNullOrEmpty(bundleIdentifier))
            return null;
        if (bundleIdentifier.Equals(LibreOffice, StringComparison.OrdinalIgnoreCase))
            return LibreOfficeApp.AppName;
        return Enum.GetValues<OfficeProgram>().Any(p => bundleIdentifier.Equals(Of(p), StringComparison.OrdinalIgnoreCase))
            ? MicrosoftOfficeApp.AppName
            : null;
    }
}
