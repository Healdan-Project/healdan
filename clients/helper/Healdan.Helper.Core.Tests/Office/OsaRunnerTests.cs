using System.Diagnostics;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// The runner that starts <c>osascript</c> and <c>open</c>. The process handling (exit codes, error text, timeouts) is
/// the same for both, so it is tested with the shell where there is one; <c>osascript</c> itself runs only on a Mac,
/// and only a script that talks to no app.
/// </summary>
public sealed class OsaRunnerTests
{
    private static readonly TimeSpan Ample = TimeSpan.FromSeconds(30);

    private readonly OsaRunner _runner = new();

    [Fact]
    public async Task ANonZeroExitThrowsWithTheErrorTextButNotTheArguments()
    {
        if (OperatingSystem.IsWindows()) return;

        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunProcessAsync("/bin/sh", ["-c", "echo 'it broke' >&2; exit 3", "sh", "SECRET-ARGUMENT"], Ample, default));

        Assert.Contains("it broke", failure.Message);
        Assert.DoesNotContain("SECRET-ARGUMENT", failure.Message);
    }

    [Fact]
    public async Task ASilentFailureStillSaysItFailed()
    {
        if (OperatingSystem.IsWindows()) return;

        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunProcessAsync("/bin/sh", ["-c", "exit 4"], Ample, default));

        Assert.Contains("4", failure.Message);
    }

    [Fact]
    public async Task AProcessThatSucceedsReturnsQuietly()
    {
        if (OperatingSystem.IsWindows()) return;

        await _runner.RunProcessAsync("/bin/sh", ["-c", "echo fine; exit 0"], Ample, default);
    }

    [Fact]
    public async Task ATimeoutKillsTheProcessAndSaysSo()
    {
        if (OperatingSystem.IsWindows()) return;

        var clock = Stopwatch.StartNew();
        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunProcessAsync("/bin/sh", ["-c", "sleep 60"], TimeSpan.FromMilliseconds(300), default));

        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(20), "the process was waited for after its time was up");
        Assert.Contains("didn't answer", failure.Message);
    }

    [Fact]
    public async Task ACancelledCallStopsTheProcessAndIsNotAFailure()
    {
        if (OperatingSystem.IsWindows()) return;

        using var cancel = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));

        var clock = Stopwatch.StartNew();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            _runner.RunProcessAsync("/bin/sh", ["-c", "sleep 60"], Ample, cancel.Token));
        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(20));
    }

    [Fact]
    public async Task AProgramThatIsntThereIsAFailureToo()
    {
        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunProcessAsync("/healdan-no-such-program", [], Ample, default));

        Assert.Contains("/healdan-no-such-program", failure.Message);
    }

    [Fact]
    public async Task OsascriptTakesTheScriptOnStandardInputAndTheValuesAsArguments()
    {
        if (!OperatingSystem.IsMacOS()) return;

        const string value = "Lease \"#1\"; do shell script \"x\" – ünï 📎";
        var answer = await _runner.RunAsync("on run argv\n    return (item 1 of argv) & \"|\" & (item 2 of argv)\nend run", [value, ""], Ample, default);

        Assert.Equal(value + "|", answer); // one newline stripped; nothing else changed
    }

    [Fact]
    public async Task OsascriptsErrorsComeBackAsOfficeScriptExceptions()
    {
        if (!OperatingSystem.IsMacOS()) return;

        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunAsync("on run argv\n    error \"the script said no\"\nend run", ["SECRET-ARGUMENT"], Ample, default));

        Assert.Contains("the script said no", failure.Message);
        Assert.DoesNotContain("SECRET-ARGUMENT", failure.Message);
    }

    [Fact]
    public async Task OsascriptIsStoppedWhenItsTimeIsUp()
    {
        if (!OperatingSystem.IsMacOS()) return;

        var clock = Stopwatch.StartNew();
        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() =>
            _runner.RunAsync("on run argv\n    delay 60\nend run", [], TimeSpan.FromMilliseconds(500), default));

        Assert.True(clock.Elapsed < TimeSpan.FromSeconds(20));
        Assert.Contains("didn't answer", failure.Message);
    }
}
