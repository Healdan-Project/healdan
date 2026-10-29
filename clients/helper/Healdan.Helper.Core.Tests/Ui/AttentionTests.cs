using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>The menu bar icon's dot (polish spec §7.2): shown while the helper needs the user, and why.</summary>
public sealed class AttentionTests
{
    [Fact]
    public void NothingNeedsYouWhenAllIsWell() =>
        Assert.Null(Attention.Reason(signedIn: true, startFailed: false, crashed: false, kept: 0, extensionMissing: false, extensionOutdated: false));

    [Theory]
    [InlineData(false, false, false, 0, false, false, "Not Signed In")]
    [InlineData(true, true, false, 0, false, false, "Couldn't Start")]
    [InlineData(true, false, true, 0, false, false, "A Document Closed Unexpectedly")]
    [InlineData(true, false, false, 2, false, false, "Not Yet in Healdan")]
    [InlineData(true, false, false, 0, true, false, "LibreOffice Extension Not Installed")]
    [InlineData(true, false, false, 0, true, true, "LibreOffice Extension Needs Updating")]
    public void EachReasonShowsTheDot(bool signedIn, bool startFailed, bool crashed, int kept, bool missing, bool outdated, string reason) =>
        Assert.Equal(reason, Attention.Reason(signedIn, startFailed, crashed, kept, missing, outdated));

    [Fact]
    public void TheFirstReasonIsNamed() =>
        Assert.Equal("Not Signed In", Attention.Reason(signedIn: false, startFailed: true, crashed: true, kept: 3, extensionMissing: true, extensionOutdated: true));

    [Fact]
    public void AnOlderExtensionComesAfterTheCopies() =>
        Assert.Equal("Not Yet in Healdan", Attention.Reason(signedIn: true, startFailed: false, crashed: false, kept: 1, extensionMissing: true, extensionOutdated: true));

    [Fact]
    public void TheToolTipSaysWhy()
    {
        Assert.Equal("Healdan Helper", Attention.ToolTip(null));
        Assert.Equal("Healdan Helper: Not Signed In", Attention.ToolTip("Not Signed In"));
        Assert.Equal("Healdan Helper: LibreOffice Extension Needs Updating", Attention.ToolTip("LibreOffice Extension Needs Updating"));
    }
}
