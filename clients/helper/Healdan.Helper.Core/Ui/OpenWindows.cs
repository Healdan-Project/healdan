namespace Healdan.Helper.Core.Ui;

/// <summary>
/// The helper's windows that are open, and which of them wait for an answer the user was asked for: while one does,
/// links are dropped (spec §5.3), and a dropped link brings it to the front so the user sees why. A window the user
/// didn't ask for, such as After a Crash, holds nothing back. A window counts only once it's shown, so one that fails
/// to show can't hold links back for good. Calls come from any thread.
/// </summary>
public sealed class OpenWindows
{
    private readonly object _gate = new();
    private readonly List<Shown> _open = [];

    private sealed record Shown(bool Waits, Action? Bring);

    /// <summary>A window waiting for an answer is open.</summary>
    public bool Waiting
    {
        get
        {
            lock (_gate)
                return _open.Any(w => w.Waits);
        }
    }

    public int Count
    {
        get
        {
            lock (_gate)
                return _open.Count;
        }
    }

    /// <summary>
    /// Shows a window with <paramref name="show"/>, which may throw (the window then isn't counted). Returns what to
    /// call once the window has closed, which says whether it was the last one open, so the focus can go back.
    /// </summary>
    /// <param name="waits">It asks something the user started: links wait for it.</param>
    /// <param name="bring">Brings the window to the front, for a link dropped while it waits.</param>
    public Func<bool> Show(Action show, bool waits, Action? bring = null)
    {
        show();
        var shown = new Shown(waits, bring);
        lock (_gate)
            _open.Add(shown);
        return () =>
        {
            lock (_gate)
                return _open.Remove(shown) && _open.Count == 0;
        };
    }

    /// <summary>A link was dropped because a window waits: the newest waiting window comes to the front.</summary>
    public void BringWaiting()
    {
        Action? bring;
        lock (_gate)
            bring = _open.LastOrDefault(w => w.Waits)?.Bring;
        bring?.Invoke();
    }
}
