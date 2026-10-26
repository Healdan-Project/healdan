namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// A settle's clock (<see cref="Core.Cycle.CycleTimings.Clock"/>) that keeps real time until a test stops it. While it's
/// stopped, no settle ends, however long the runner takes; the test can still move it on by hand.
/// </summary>
public sealed class PausableClock : TimeProvider
{
    private readonly object _lock = new();
    private long _behind; // how far behind the real clock this one is, in timestamp ticks
    private long? _stoppedAt; // the real clock's timestamp when this one stopped

    public override long GetTimestamp()
    {
        lock (_lock)
            return (_stoppedAt ?? TimeProvider.System.GetTimestamp()) - _behind;
    }

    public void Pause()
    {
        lock (_lock)
            _stoppedAt ??= TimeProvider.System.GetTimestamp();
    }

    /// <summary>Starts it again from where it stopped.</summary>
    public void Resume()
    {
        lock (_lock)
        {
            if (_stoppedAt is { } stoppedAt)
                _behind += TimeProvider.System.GetTimestamp() - stoppedAt;
            _stoppedAt = null;
        }
    }

    /// <summary>Moves it on by <paramref name="time"/>, stopped or not.</summary>
    public void Advance(TimeSpan time)
    {
        lock (_lock)
            _behind -= (long)(time.TotalSeconds * TimestampFrequency);
    }
}
