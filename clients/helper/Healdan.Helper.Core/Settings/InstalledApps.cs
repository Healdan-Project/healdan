// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Settings;

/// <summary>Which office apps are installed on this computer, so Settings offer, and the cycle uses, only those (M3.5 spec §5.10).</summary>
/// <param name="CoffeePdf">Whether CoffeePDF is installed (M3.6 spec §4.2); never off macOS.</param>
/// <param name="Outlook">
/// Whether Outlook is installed (M5 spec §7.1): it runs Healdan's add-in, so Set Up Microsoft Office applies, but it's
/// none of the copy cycle's programs. Never off macOS.
/// </param>
public sealed record InstalledApps(bool LibreOffice, bool Word, bool Excel, bool PowerPoint, bool CoffeePdf = false, bool Outlook = false)
{
    /// <summary>Whether the Microsoft Office program is installed.</summary>
    public bool Has(OfficeProgram p) => p switch
    {
        OfficeProgram.Word => Word,
        OfficeProgram.Excel => Excel,
        OfficeProgram.PowerPoint => PowerPoint,
        _ => throw new ArgumentOutOfRangeException(nameof(p)),
    };

    /// <summary>Whether any of Word, Excel and PowerPoint is installed: the Microsoft Office programs copies open in.</summary>
    public bool MicrosoftOffice => Word || Excel || PowerPoint;

    /// <summary>Whether any app that runs Healdan's add-in is installed: Word, Excel, PowerPoint or Outlook.</summary>
    public bool AnyAddinApp => MicrosoftOffice || Outlook;

    /// <summary>Looks on this computer, as the helper looks for LibreOffice, CoffeePDF, the Office programs and Outlook on macOS.</summary>
    /// <param name="bundleFound">
    /// Whether LaunchServices finds an app by its bundle identifier (<see cref="AppBundles"/>), wherever it is installed;
    /// null on a computer that has none. Only macOS asks it.
    /// </param>
    public static InstalledApps Detect(Func<string, bool>? bundleFound = null) =>
        Detect(OperatingSystem.IsMacOS(), Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), Directory.Exists, bundleFound);

    /// <summary>
    /// On macOS: the app bundles in the usual places, else what LaunchServices finds. LibreOffice.app and CoffeePDF.app are in
    /// <c>/Applications</c> or the user's own <c>~/Applications</c>, and Microsoft's programs are in <c>/Applications</c>
    /// (<see cref="OfficePrograms.BundlePath"/>, and <c>Microsoft Outlook.app</c>). An app anywhere else counts as well, as it did when M3 opened LibreOffice
    /// with <c>open -a</c>, which LaunchServices resolves. LaunchServices is asked only about an app the folders didn't
    /// show. Elsewhere Microsoft Office is never installed, and LibreOffice counts as installed: nothing here can tell, and
    /// it has always been taken to be (a missing one fails when it is opened).
    /// </summary>
    internal static InstalledApps Detect(bool macOS, string home, Func<string, bool> directoryExists, Func<string, bool>? bundleFound = null)
    {
        if (!macOS)
            return new InstalledApps(LibreOffice: true, Word: false, Excel: false, PowerPoint: false);
        bool Found(string identifier) => bundleFound is not null && bundleFound(identifier);
        return new InstalledApps(
            LibreOffice: directoryExists("/Applications/LibreOffice.app") || directoryExists(Path.Combine(home, "Applications", "LibreOffice.app"))
                || Found(AppBundles.LibreOffice),
            Word: Microsoft(OfficeProgram.Word),
            Excel: Microsoft(OfficeProgram.Excel),
            PowerPoint: Microsoft(OfficeProgram.PowerPoint),
            CoffeePdf: directoryExists("/Applications/CoffeePDF.app") || directoryExists(Path.Combine(home, "Applications", "CoffeePDF.app"))
                || Found(AppBundles.CoffeePdf),
            Outlook: directoryExists(OutlookBundlePath) || Found(AppBundles.Outlook));

        bool Microsoft(OfficeProgram p) => directoryExists(OfficePrograms.BundlePath(p)) || Found(AppBundles.Of(p));
    }

    /// <summary>Where Microsoft installs Outlook, as it does Word, Excel and PowerPoint.</summary>
    private const string OutlookBundlePath = "/Applications/Microsoft Outlook.app";
}
