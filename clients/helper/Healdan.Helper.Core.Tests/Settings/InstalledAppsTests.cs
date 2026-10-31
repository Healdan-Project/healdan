using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

public sealed class InstalledAppsTests
{
    private const string Home = "/Users/robert";

    private static InstalledApps OnMac(params string[] bundles) => InstalledApps.Detect(macOS: true, Home, bundles.Contains);

    [Fact]
    public void OnAMacItIsWhichBundlesAreThere()
    {
        Assert.Equal(new InstalledApps(false, false, false, false), OnMac());
        Assert.Equal(
            new InstalledApps(true, true, false, true),
            OnMac("/Applications/LibreOffice.app", "/Applications/Microsoft Word.app", "/Applications/Microsoft PowerPoint.app"));
        Assert.Equal(
            new InstalledApps(false, false, true, false),
            OnMac(OfficePrograms.BundlePath(OfficeProgram.Excel)));
    }

    [Fact]
    public void LibreOfficeInTheUsersOwnApplicationsCounts() =>
        Assert.True(OnMac($"{Home}/Applications/LibreOffice.app").LibreOffice);

    [Fact]
    public void ElsewhereMicrosoftOfficeIsNeverInstalledAndLibreOfficeIsTakenToBe()
    {
        var linux = InstalledApps.Detect(macOS: false, "/home/robert", _ => true);

        Assert.Equal(new InstalledApps(LibreOffice: true, Word: false, Excel: false, PowerPoint: false), linux);
        Assert.False(linux.MicrosoftOffice);
    }

    [Fact]
    public void HasAnswersForEachProgram()
    {
        var installed = new InstalledApps(true, Word: true, Excel: false, PowerPoint: true);

        Assert.True(installed.Has(OfficeProgram.Word));
        Assert.False(installed.Has(OfficeProgram.Excel));
        Assert.True(installed.Has(OfficeProgram.PowerPoint));
        Assert.True(installed.MicrosoftOffice);
        Assert.False(new InstalledApps(true, false, false, false).MicrosoftOffice);
    }

    /// <summary>The Mac with LaunchServices knowing the bundle identifiers it's given, wherever the apps are.</summary>
    private static InstalledApps WhereverTheyAre(params string[] bundleIdentifiers) =>
        InstalledApps.Detect(macOS: true, Home, _ => false, bundleIdentifiers.Contains);

    [Fact]
    public void AnAppLaunchServicesFindsAnywhereCounts()
    {
        Assert.Equal(new InstalledApps(false, false, false, false), WhereverTheyAre());
        Assert.Equal(new InstalledApps(true, false, false, false), WhereverTheyAre("org.libreoffice.script")); // open -a LibreOffice finds it there
        Assert.Equal(new InstalledApps(false, true, true, true), WhereverTheyAre("com.microsoft.Word", "com.microsoft.Excel", "com.microsoft.Powerpoint"));
        Assert.Equal(new InstalledApps(false, false, true, false), WhereverTheyAre("com.microsoft.Excel", "com.microsoft.Outlook"));
    }

    [Fact]
    public void LaunchServicesIsAskedOnlyAboutWhatTheFoldersDidntShow()
    {
        var asked = new List<string>();

        var installed = InstalledApps.Detect(macOS: true, Home, p => p is "/Applications/LibreOffice.app" or "/Applications/Microsoft Word.app", id =>
        {
            asked.Add(id);
            return id == "com.microsoft.Powerpoint";
        });

        Assert.Equal(new InstalledApps(true, true, false, true), installed);
        Assert.Equal(["com.microsoft.Excel", "com.microsoft.Powerpoint"], asked);
    }

    [Fact]
    public void ElsewhereLaunchServicesIsNotAsked()
    {
        var linux = InstalledApps.Detect(macOS: false, "/home/robert", _ => false, _ => throw new InvalidOperationException("Not on a Mac."));

        Assert.Equal(new InstalledApps(LibreOffice: true, Word: false, Excel: false, PowerPoint: false), linux);
    }
}
