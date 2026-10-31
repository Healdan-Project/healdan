using Healdan.Helper.Core.Settings;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Tests.Platform;

/// <summary>
/// What LaunchServices says about apps (M3.5 spec §5.10), asked for real: which app opens a kind of file by default, and
/// whether an app is installed. They are for a Mac, and they depend on nothing but what every Mac has: Finder, and an app
/// that opens a text file. Which app opens .docx is the machine's own, so the answer is only checked to be the helper's names.
/// </summary>
public sealed class DefaultAppsTests
{
    private static readonly string?[] Names = ["Microsoft Office", "LibreOffice", null];

    [MacFact]
    public void AMacHasAnAppForTextFiles()
    {
        var bundle = LaunchServices.DefaultAppBundle("txt");

        Assert.False(string.IsNullOrEmpty(bundle));
        Assert.Equal(AppBundles.NameOf(bundle), DefaultApps.For("txt"));
    }

    [MacFact]
    public void TheOfficeFormatsAreNamedByTheAppThatOpensThem()
    {
        foreach (var extension in new[] { "docx", "xlsx", "pptx", "doc", "odt" })
        {
            var name = DefaultApps.For(extension);

            Assert.Contains(name, Names);
            Assert.Equal(AppBundles.NameOf(LaunchServices.DefaultAppBundle(extension)), name);
        }
    }

    [MacFact]
    public void ADotBeforeTheExtensionDoesntMatter() =>
        Assert.Equal(DefaultApps.For("txt"), DefaultApps.For(".txt"));

    [MacFact]
    public void NothingOpensAnExtensionNobodyKnows()
    {
        Assert.Null(LaunchServices.DefaultAppBundle("zzqxv9"));
        Assert.Null(DefaultApps.For("zzqxv9"));
    }

    [MacFact]
    public void NoExtensionIsNoApp()
    {
        Assert.Null(DefaultApps.For(""));
        Assert.Null(DefaultApps.For("."));
        Assert.Null(DefaultApps.For("  "));
    }

    [MacFact]
    public void AnAppIsInstalledWhenLaunchServicesFindsItByItsBundle()
    {
        Assert.True(LaunchServices.IsInstalled("com.apple.finder"));
        Assert.False(LaunchServices.IsInstalled("com.healdan.no-such-app"));
        Assert.False(LaunchServices.IsInstalled(""));
    }

    [MacFact]
    public async Task ItAnswersTheSameFromAnyThread()
    {
        var expected = (DefaultApps.For("docx"), LaunchServices.IsInstalled("com.apple.finder"));

        var answers = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => Task.Run(() =>
        {
            var seen = new List<(string?, bool)>();
            for (var i = 0; i < 50; i++)
                seen.Add((DefaultApps.For("docx"), LaunchServices.IsInstalled("com.apple.finder")));
            return seen;
        })));

        Assert.All(answers.SelectMany(a => a), a => Assert.Equal(expected, a));
    }
}
