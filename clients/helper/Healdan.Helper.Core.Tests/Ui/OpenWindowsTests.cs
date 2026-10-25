using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>Which windows are open and which wait for an answer, for the links they hold back (spec §5.3).</summary>
public class OpenWindowsTests
{
    [Fact]
    public void AWindowThatFailsToShowIsntCounted()
    {
        var windows = new OpenWindows();

        Assert.Throws<InvalidOperationException>(() => windows.Show(() => throw new InvalidOperationException("no display"), waits: true));

        Assert.False(windows.Waiting); // links would otherwise be dropped for good
        Assert.Equal(0, windows.Count);
    }

    [Fact]
    public void AWaitingWindowHoldsLinksBackUntilItCloses()
    {
        var windows = new OpenWindows();
        var first = windows.Show(() => { }, waits: true);
        var second = windows.Show(() => { }, waits: true);
        Assert.True(windows.Waiting);

        Assert.False(first());
        Assert.True(windows.Waiting);
        Assert.True(second()); // the last one: the focus goes back to the app
        Assert.False(windows.Waiting);
        Assert.False(second()); // closing twice changes nothing
    }

    [Fact]
    public void AWindowTheUserDidntAskForDoesntHoldLinksBack()
    {
        var windows = new OpenWindows();
        var brought = 0;

        var crash = windows.Show(() => { }, waits: false, bring: () => brought++);

        Assert.False(windows.Waiting);
        Assert.Equal(1, windows.Count);
        windows.BringWaiting();
        Assert.Equal(0, brought);
        Assert.True(crash());
    }

    [Fact]
    public void ADroppedLinkBringsTheNewestWaitingWindowUp()
    {
        var windows = new OpenWindows();
        var brought = new List<string>();
        windows.Show(() => { }, waits: true, bring: () => brought.Add("save"));
        var held = windows.Show(() => { }, waits: true, bring: () => brought.Add("held"));
        windows.Show(() => { }, waits: false, bring: () => brought.Add("crash"));

        windows.BringWaiting();
        held();
        windows.BringWaiting();

        Assert.Equal(["held", "save"], brought);
    }
}
