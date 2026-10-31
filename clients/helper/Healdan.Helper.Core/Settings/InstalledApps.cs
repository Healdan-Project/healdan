using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Settings;

/// <summary>Which office apps are installed on this computer, so Settings offer, and the cycle uses, only those (M3.5 spec §5.10).</summary>
public sealed record InstalledApps(bool LibreOffice, bool Word, bool Excel, bool PowerPoint)
{
    /// <summary>Whether the Microsoft Office program is installed.</summary>
    public bool Has(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => Word,
        OfficeProgram.Excel => Excel,
        OfficeProgram.PowerPoint => PowerPoint,
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>Whether any of Word, Excel and PowerPoint is installed.</summary>
    public bool MicrosoftOffice => Word || Excel || PowerPoint;

    /// <summary>Looks on this computer, as the helper looks for LibreOffice and the Office programs on macOS.</summary>
    public static InstalledApps Detect() =>
        Detect(OperatingSystem.IsMacOS(), Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), Directory.Exists);

    /// <summary>
    /// On macOS: the app bundles. LibreOffice.app is in <c>/Applications</c> or the user's own <c>~/Applications</c>, and
    /// Microsoft's programs are in <c>/Applications</c> (<see cref="OfficePrograms.BundlePath"/>). Elsewhere Microsoft
    /// Office is never installed, and LibreOffice counts as installed: nothing here can tell, and it has always been
    /// taken to be (a missing one fails when it is opened).
    /// </summary>
    internal static InstalledApps Detect(bool macOS, string home, Func<string, bool> directoryExists)
    {
        if (!macOS)
            return new InstalledApps(LibreOffice: true, Word: false, Excel: false, PowerPoint: false);
        return new InstalledApps(
            LibreOffice: directoryExists("/Applications/LibreOffice.app") || directoryExists(Path.Combine(home, "Applications", "LibreOffice.app")),
            Word: directoryExists(OfficePrograms.BundlePath(OfficeProgram.Word)),
            Excel: directoryExists(OfficePrograms.BundlePath(OfficeProgram.Excel)),
            PowerPoint: directoryExists(OfficePrograms.BundlePath(OfficeProgram.PowerPoint)));
    }
}
