// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// How long the cycle waits for things. <see cref="Default"/> is the trial's (plan: "Timings, from the trial");
/// tests pass shorter ones so they never sleep for a real timing.
/// </summary>
/// <param name="Settle">A save counts once the file has stopped changing for this long.</param>
/// <param name="SizePoll">How often a changing file's size and time are checked.</param>
/// <param name="CrashPoll">How often the app's process is checked (and the open copies' files, for a change the folder watcher missed).</param>
/// <param name="LockPoll">How often lock files are checked; one must be gone at two checks in a row.</param>
/// <param name="RetryDelays">The waits before each retry of a save the server didn't take; the last repeats.</param>
public sealed record CycleTimings(
    TimeSpan Settle,
    TimeSpan SizePoll,
    TimeSpan CrashPoll,
    TimeSpan LockPoll,
    IReadOnlyList<TimeSpan> RetryDelays)
{
    public static CycleTimings Default { get; } = new(
        TimeSpan.FromSeconds(1.2),
        TimeSpan.FromMilliseconds(300),
        TimeSpan.FromSeconds(5),
        TimeSpan.FromSeconds(1),
        [TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(60)]);

    /// <summary>
    /// The clock a <see cref="Settle"/> and the save back's look for the restored window (<see cref="RecoveryWait"/>) are
    /// timed on. Tests pass one they can stop, so whether two writes are one save, or whether the save back is still
    /// looking, never depends on how quickly the runner gets from one step to the next.
    /// </summary>
    internal TimeProvider Clock { get; init; } = TimeProvider.System;

    /// <summary>
    /// How long the save back after a crash looks for the restored window by its path (M3.5 spec §5.14), on
    /// <see cref="Clock"/>; and, in real time, how long after a look that found nothing it looks again while the program runs.
    /// </summary>
    public TimeSpan RecoveryWait { get; init; } = TimeSpan.FromSeconds(30);

    /// <summary>
    /// How long a crashed copy's reopen waits for Healdan to say whether its check-out still holds (#136) before it
    /// reopens as before, as when Healdan can't be reached.
    /// </summary>
    public TimeSpan HoldCheck { get; init; } = TimeSpan.FromSeconds(5);

    /// <summary>The wait before retry number <paramref name="attempt"/> (from 0).</summary>
    public TimeSpan RetryDelay(int attempt) =>
        RetryDelays.Count == 0 ? TimeSpan.FromSeconds(1) : RetryDelays[Math.Min(attempt, RetryDelays.Count - 1)];
}
