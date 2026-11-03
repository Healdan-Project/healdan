// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Ui;

/// <summary>
/// The helper's windows that are open, and which of them wait for an answer the user was asked for: while one does,
/// links are dropped (spec §5.3), and a dropped link brings it to the front so the user sees why. A window the user
/// didn't ask for, such as After a Crash, holds nothing back. A window counts only once it's shown, so one that fails
/// to show can't hold links back for good. Each waiting window has a line in the menu, which brings it up once it's
/// gone behind another app's (#143). Calls come from any thread.
/// </summary>
public sealed class OpenWindows
{
    private readonly object _gate = new();
    private readonly List<Shown> _open = [];

    private sealed record Shown(bool Waits, Action? Bring, string? Title);

    /// <summary>A window opened or closed, for the menu and its dot. Raised outside the lock, on the caller's thread.</summary>
    public event Action? Changed;

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

    /// <summary>The waiting windows the menu can bring up, oldest first: those shown with a title and a way to bring them up.</summary>
    public IReadOnlyList<WaitingWindow> WaitingWindows
    {
        get
        {
            lock (_gate)
                return _open.Where(w => w is { Waits: true, Title: not null, Bring: not null }).Select(w => new WaitingWindow(w.Title!, w.Bring!)).ToList();
        }
    }

    /// <summary>
    /// Shows a window with <paramref name="show"/>, which may throw (the window then isn't counted). Returns what to
    /// call once the window has closed, which says whether it was the last one open, so the focus can go back.
    /// </summary>
    /// <param name="waits">It asks something the user started: links wait for it.</param>
    /// <param name="bring">Brings the window to the front, for a link dropped while it waits and for its menu line.</param>
    /// <param name="title">The window's title, which its menu line shows while it waits.</param>
    public Func<bool> Show(Action show, bool waits, Action? bring = null, string? title = null)
    {
        show();
        var shown = new Shown(waits, bring, title);
        lock (_gate)
            _open.Add(shown);
        Changed?.Invoke();
        return () =>
        {
            bool removed, last;
            lock (_gate)
                (removed, last) = (_open.Remove(shown), _open.Count == 0);
            if (removed)
                Changed?.Invoke();
            return removed && last;
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

/// <summary>A window that waits for an answer, as the menu lists it (#143).</summary>
public sealed record WaitingWindow(string Title, Action Bring)
{
    /// <summary>"Save as New Document…": the title, with the ellipsis of an item that opens a window, unless it asks a question.</summary>
    public string MenuLine => Title.EndsWith('?') ? Title : Title + "…";
}
