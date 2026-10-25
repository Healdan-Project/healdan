using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>One <see cref="ICycleEvents.HeldElsewhere"/> call.</summary>
public sealed record HeldElsewhereEvent(string? Path, long DocNumber, string DeviceName, string? App, DateTimeOffset? LastSaved);

/// <summary>Remembers what the cycle told the app, for the tests to wait on.</summary>
public sealed class RecordingEvents : ICycleEvents
{
    public ConcurrentQueue<(string Path, CopyState State)> Confirmations { get; } = new();

    public ConcurrentQueue<HeldElsewhereEvent> HeldElsewheres { get; } = new();

    public ConcurrentQueue<string> Ended { get; } = new();

    public ConcurrentQueue<string> Crashes { get; } = new();

    public ConcurrentQueue<int> Waits { get; } = new();

    public ConcurrentQueue<string> Errors { get; } = new();

    private int _signIns;

    public int SignIns => Volatile.Read(ref _signIns);

    public void Confirmed(string path, CopyState state) => Confirmations.Enqueue((path, state));

    public void HeldElsewhere(string? path, long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved) =>
        HeldElsewheres.Enqueue(new HeldElsewhereEvent(path, docNumber, deviceName, app, lastSaved));

    public void CheckoutEnded(string path) => Ended.Enqueue(path);

    public void Crashed(string path) => Crashes.Enqueue(path);

    public void Waiting(int pending) => Waits.Enqueue(pending);

    public void SignInNeeded() => Interlocked.Increment(ref _signIns);

    public void Error(string message) => Errors.Enqueue(message);

    /// <summary>The title of the last confirmation for <paramref name="path"/>, or null.</summary>
    public string? LastTitle(string path) =>
        Confirmations.Where(c => c.Path == path).Select(c => c.State.Title).LastOrDefault();
}
