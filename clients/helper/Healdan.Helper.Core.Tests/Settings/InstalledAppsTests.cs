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
}
