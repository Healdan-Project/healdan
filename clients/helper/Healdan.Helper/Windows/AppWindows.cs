using System.Text.Json.Nodes;
using Avalonia.Controls;
using Avalonia.Threading;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// The helper's windows (spec §5.8). Each comes to the front over the app (<see cref="MacPlatform.Activate"/>), and
/// once the last one closes the focus goes back to the app (<see cref="MacPlatform.GiveBackFocus"/>). While one is open
/// it's waiting for an answer, and links are dropped (spec §5.3).
/// <para>Sign In is here. The Open, Save, Properties, checked-out-elsewhere and crash windows are the next task's: until
/// then the channel's window routes answer <c>cancel</c>, and the two warnings are notifications.</para>
/// </summary>
internal sealed class AppWindows(Notifier notifier, HelperLog log) : IWindows
{
    private static readonly JsonObject Cancel = new() { ["action"] = "cancel" };

    private int _open;
    private Task<bool>? _signIn;

    public bool Waiting => Volatile.Read(ref _open) > 0;

    public Task<bool> SignInAsync(SignInPrompt prompt)
    {
        var shown = new TaskCompletionSource<Task<bool>>(TaskCreationOptions.RunContinuationsAsynchronously);
        Dispatcher.UIThread.Post(() =>
        {
            try
            {
                shown.SetResult(ShowSignIn(prompt));
            }
            catch (Exception e)
            {
                shown.SetException(e);
            }
        });
        return shown.Task.Unwrap();
    }

    /// <summary>UI thread: one Sign In window at a time; asking again while it's open waits for the same one.</summary>
    private Task<bool> ShowSignIn(SignInPrompt prompt)
    {
        if (_signIn is { IsCompleted: false } open)
            return open;
        var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var window = new SignInWindow(prompt);
        Show(window, () => done.TrySetResult(window.SignedIn));
        log.Info("Sign In window shown");
        return _signIn = done.Task;
    }

    public Task<JsonObject> OpenDialogAsync(JsonObject request) => Cancelled("Open from Healdan");

    public Task<JsonObject> SaveNewAsync(JsonObject request) => Cancelled("Save to Healdan");

    public Task<JsonObject> PropertiesAsync(JsonObject request) => Cancelled("Properties");

    public void HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved)
    {
        var where = app is null ? deviceName : $"{deviceName} ({app})";
        var saved = lastSaved is { } t ? $", last saved to Healdan {t.ToLocalTime():d MMM HH:mm}" : "";
        notifier.Notify("Checked Out on Another Computer",
            $"#{docNumber} is checked out to you on {where}{saved}. Close it there, then open it here again.");
    }

    public void Crashed(CopyState copy) =>
        notifier.Notify(copy.Title, copy.ReadOnly
            ? "It closed unexpectedly. Choose it in the Healdan Helper menu to open it again."
            : "It closed unexpectedly. It's still checked out to you, and your local copy is kept. Choose it in the Healdan Helper menu to reopen it.");

    private Task<JsonObject> Cancelled(string window)
    {
        log.Info($"The {window} window isn't built yet, so the request is cancelled");
        return Task.FromResult((JsonObject)Cancel.DeepClone());
    }

    /// <summary>UI thread: shows a window over the app, and hands the focus back once the last one closes.</summary>
    private void Show(Window window, Action closed)
    {
        Interlocked.Increment(ref _open);
        window.Closed += (_, _) =>
        {
            // No longer waiting before the answer goes out, so a link the answer lets through isn't dropped.
            var last = Interlocked.Decrement(ref _open) == 0;
            closed();
            if (last)
                MacPlatform.GiveBackFocus();
        };
        MacPlatform.Activate();
        window.Show();
        window.Activate();
    }
}
