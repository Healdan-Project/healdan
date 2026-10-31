using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>The bundle identifiers macOS knows the office apps by, and the name the helper gives each (M3.5 spec §5.10).</summary>
public sealed class AppBundlesTests
{
    [Theory]
    [InlineData("com.microsoft.Word", "Microsoft Office")]
    [InlineData("com.microsoft.Excel", "Microsoft Office")]
    [InlineData("com.microsoft.Powerpoint", "Microsoft Office")]
    [InlineData("com.microsoft.PowerPoint", "Microsoft Office")] // LaunchServices doesn't go by case
    [InlineData("org.libreoffice.script", "LibreOffice")]
    public void AnOfficeAppIsNamedByItsBundle(string bundleIdentifier, string name) =>
        Assert.Equal(name, AppBundles.NameOf(bundleIdentifier));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("com.apple.iWork.Pages")]
    [InlineData("com.apple.TextEdit")]
    [InlineData("com.microsoft.Outlook")]
    [InlineData("com.microsoft.onenote.mac")]
    [InlineData("org.libreoffice")]
    public void AnythingElseHasNoName(string? bundleIdentifier) =>
        Assert.Null(AppBundles.NameOf(bundleIdentifier));

    [Fact]
    public void EachProgramHasItsOwnBundle()
    {
        Assert.Equal("com.microsoft.Word", AppBundles.Of(OfficeProgram.Word));
        Assert.Equal("com.microsoft.Excel", AppBundles.Of(OfficeProgram.Excel));
        Assert.Equal("com.microsoft.Powerpoint", AppBundles.Of(OfficeProgram.PowerPoint));
        Assert.Equal("org.libreoffice.script", AppBundles.LibreOffice);
        Assert.All(Enum.GetValues<OfficeProgram>(), p => Assert.Equal("Microsoft Office", AppBundles.NameOf(AppBundles.Of(p))));
    }
}
