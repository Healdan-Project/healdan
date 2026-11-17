// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// The tests' own clock: while it's stopped, its time of day doesn't move, so a test can compare a time the code under test
/// stamped with one it reads itself, to the tick.
/// </summary>
public sealed class PausableClockTests
{
    [Fact]
    public void AStoppedClocksTimeOfDayIsTheSameAtEveryReading()
    {
        var clock = new PausableClock();
        clock.Pause();

        var first = clock.GetUtcNow();
        for (var i = 0; i < 10_000; i++)
            Assert.Equal(first, clock.GetUtcNow());
        Assert.Equal(TimeSpan.Zero, first.Offset);
    }

    [Fact]
    public void AdvancingAStoppedClockMovesItsTimeOfDayAndNothingElse()
    {
        var clock = new PausableClock();
        clock.Pause();
        var stopped = clock.GetUtcNow();

        clock.Advance(TimeSpan.FromMinutes(10));

        Assert.Equal(stopped + TimeSpan.FromMinutes(10), clock.GetUtcNow());
        Assert.Equal(clock.GetUtcNow(), clock.GetUtcNow());
    }

    [Fact]
    public void ARunningClockKeepsRealTimeAndResumesFromWhereItStopped()
    {
        var clock = new PausableClock();
        Assert.InRange((clock.GetUtcNow() - DateTimeOffset.UtcNow).Duration(), TimeSpan.Zero, TimeSpan.FromSeconds(1));

        clock.Pause();
        var stopped = clock.GetUtcNow();
        Thread.Sleep(200);
        clock.Resume();

        // It lost the time it was stopped for, and goes on from there.
        var resumed = clock.GetUtcNow();
        Assert.InRange((resumed - stopped).Duration(), TimeSpan.Zero, TimeSpan.FromMilliseconds(100));
        Assert.True(DateTimeOffset.UtcNow - resumed > TimeSpan.FromMilliseconds(150));
    }
}
