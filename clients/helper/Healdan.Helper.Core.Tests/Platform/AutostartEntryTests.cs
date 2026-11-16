// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>Settings' Start at Sign-In row on Ubuntu: the package's system entry, and a user's override of it.</summary>
public sealed class AutostartEntryTests
{
    private const string Entry = "[Desktop Entry]\nType=Application\nName=Healdan Helper\nExec=healdan-helper\n";

    [Fact]
    public void TheSystemEntryAloneIsOn()
    {
        var summary = AutostartEntry.Summarize(systemEntryExists: true, userEntry: null);

        Assert.Equal("On", summary.Value);
        Assert.True(summary.On);
        Assert.Null(summary.Note);
    }

    [Fact]
    public void AUserEntryThatDoesntSwitchItOffLeavesItOn()
    {
        var summary = AutostartEntry.Summarize(true, Entry + "X-GNOME-Autostart-enabled=true\n");

        Assert.True(summary.On);
    }

    [Theory]
    [InlineData("Hidden=true")]
    [InlineData("Hidden=TRUE")]
    [InlineData("Hidden = True")]
    [InlineData("X-GNOME-Autostart-enabled=false")]
    [InlineData("X-GNOME-Autostart-enabled=False")]
    public void AUserEntryThatSwitchesItOffIsOff(string line)
    {
        var summary = AutostartEntry.Summarize(true, Entry + line + "\n");

        Assert.Equal("Off", summary.Value);
        Assert.False(summary.On);
        Assert.Contains("Startup Applications", summary.Note);
        Assert.Contains("GNOME Tweaks", summary.Note);
    }

    [Theory]
    [InlineData("#Hidden=true")]
    [InlineData("Hidden=false")]
    public void ACommentOrAFalseValueDoesntSwitchItOff(string line)
    {
        Assert.True(AutostartEntry.Summarize(true, Entry + line + "\n").On);
    }

    [Fact]
    public void WithoutTheSystemEntryItIsNotInThisBuild()
    {
        var summary = AutostartEntry.Summarize(systemEntryExists: false, userEntry: null);

        Assert.Equal("Not in This Build", summary.Value);
        Assert.False(summary.On);
        Assert.NotNull(summary.Note);
    }

    [Fact]
    public void TheUserEntryIsFoundUnderXdgConfigHomeOrDotConfig()
    {
        Assert.Equal("/x/autostart/healdan-helper.desktop", AutostartEntry.UserPath("/x", "/home/u"));
        Assert.Equal("/home/u/.config/autostart/healdan-helper.desktop", AutostartEntry.UserPath(null, "/home/u"));
        Assert.Equal("/home/u/.config/autostart/healdan-helper.desktop", AutostartEntry.UserPath("", "/home/u"));
    }
}
