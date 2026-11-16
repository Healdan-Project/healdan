// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Diagnostics;
using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>Linux's native notifications, through <c>notify-send</c> (spec §5.1).</summary>
public sealed class NotifySendTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "healdan-notify-" + Guid.NewGuid().ToString("N"));

    public NotifySendTests() => Directory.CreateDirectory(_folder);

    public void Dispose() => TempFolder.Delete(_folder);

    [Theory]
    [InlineData("Healdan Helper", "Saved a new version.")]
    [InlineData("-t 1 --urgency=critical", "-- $(id) `id` | cat; rm -rf ~")]
    public void HandsTheTextOverWholeAfterTheOptions(string title, string message)
    {
        var start = NotifySend.Command(title, message);

        Assert.Equal("notify-send", start.FileName);
        Assert.False(start.UseShellExecute); // no shell reads the text
        Assert.Empty(start.Arguments);
        Assert.Equal(
            ["--app-name=Healdan Helper", "--icon=healdan-helper", "--hint=string:desktop-entry:healdan-helper", "--", title, message],
            start.ArgumentList);
    }

    [Fact]
    public void ATitleStartingWithADashComesAfterTheEndOfOptions()
    {
        var args = NotifySend.Command("-Draft", "text").ArgumentList;

        Assert.True(args.IndexOf("--") < args.IndexOf("-Draft"));
    }

    [Fact]
    public void AMissingToolIsNotShown() =>
        Assert.False(NotifySend.TryPost("Title", "Message", Path.Combine(_folder, "no-such-notify-send")));

    [Fact]
    public void AToolThatExitsZeroShowedIt()
    {
        if (OperatingSystem.IsWindows()) return;

        Assert.True(NotifySend.TryPost("Title", "Message", Script("exit 0")));
    }

    [Fact]
    public void AToolThatFailsDidNotShowIt()
    {
        if (OperatingSystem.IsWindows()) return;

        Assert.False(NotifySend.TryPost("Title", "Message", Script("exit 1")));
    }

    [Fact]
    public void AToolThatHangsIsKilledAfterAboutFiveSeconds()
    {
        if (OperatingSystem.IsWindows()) return;

        var clock = Stopwatch.StartNew();
        var shown = NotifySend.TryPost("Title", "Message", Script("exec sleep 60"));

        Assert.False(shown);
        Assert.InRange(clock.Elapsed, TimeSpan.FromSeconds(4), TimeSpan.FromSeconds(15));
    }

    private string Script(string body)
    {
        var path = Path.Combine(_folder, "notify-send-" + Guid.NewGuid().ToString("N"));
        File.WriteAllText(path, "#!/bin/sh\n" + body + "\n");
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        return path;
    }
}
