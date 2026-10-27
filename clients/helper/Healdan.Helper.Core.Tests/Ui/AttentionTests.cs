using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>The menu bar icon's dot (polish spec §7.2): shown while the helper needs the user, and why.</summary>
public sealed class AttentionTests
{
    [Fact]
    public void NothingNeedsYouWhenAllIsWell() =>
        Assert.Null(Attention.Reason(signedIn: true, startFailed: false, crashed: false, kept: 0, extensionMissing: false));

    [Theory]
    [InlineData(false, false, false, 0, false, "Not Signed In")]
    [InlineData(true, true, false, 0, false, "Couldn't Start")]
    [InlineData(true, false, true, 0, false, "A Document Closed Unexpectedly")]
    [InlineData(true, false, false, 2, false, "Not Yet in Healdan")]
    [InlineData(true, false, false, 0, true, "LibreOffice Extension Not Installed")]
    public void EachReasonShowsTheDot(bool signedIn, bool startFailed, bool crashed, int kept, bool missing, string reason) =>
        Assert.Equal(reason, Attention.Reason(signedIn, startFailed, crashed, kept, missing));

    [Fact]
    public void TheFirstReasonIsNamed() =>
        Assert.Equal("Not Signed In", Attention.Reason(signedIn: false, startFailed: true, crashed: true, kept: 3, extensionMissing: true));

    [Fact]
    public void TheToolTipSaysWhy()
    {
        Assert.Equal("Healdan Helper", Attention.ToolTip(null));
        Assert.Equal("Healdan Helper: Not Signed In", Attention.ToolTip("Not Signed In"));
    }
}
