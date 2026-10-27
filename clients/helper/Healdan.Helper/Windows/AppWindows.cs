using System.Text.Json.Nodes;
using Avalonia.Controls;
using Avalonia.Threading;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Windows;

/// <summary>
/// The helper's windows (spec §5.8). Each comes to the front over the app (<see cref="MacPlatform.Activate"/>), and
/// once the last one closes the focus goes back to the app (<see cref="MacPlatform.GiveBackFocus"/>).
/// <para>A window that asks what the user started (Sign In, Sign Out on This Computer Only?, Save, Open, Properties,
/// Checked Out on Another Computer) waits for an answer: links are dropped meanwhile (spec §5.3), and a dropped link
/// brings it to the front. One the user didn't ask for, After a Crash, and Settings, which asks nothing, hold no links
/// back, and nor do About and its Open-Source Notices (<see cref="OpenWindows"/>).</para>
/// <para>One of each window at a time: a second request for one that's open brings it to the front and is answered
/// Cancel. The windows read Healdan through the session's API client, whose refused token asks for the sign-in again,
/// and open documents through its cycle once they've closed, so the app never waits on a window for a download.
/// Before the helper is signed in and started, Save and Open answer with the app's own box and Sign In opens.</para>
/// </summary>
internal sealed class AppWindows(Notifier notifier, HelperLog log, HelperPaths paths) : IWindows
{
    private readonly OpenWindows _open = new();
    private readonly Dictionary<string, Window> _single = [];
    private readonly HashSet<string> _crashShown = new(StringComparer.Ordinal);
    private Task<bool>? _signIn;

    /// <summary>The running helper, set once it's made (it needs these windows to be made first).</summary>
    public HelperSession? Session { get; set; }

    /// <summary>The LibreOffice extension's check, for Settings; set with <see cref="Session"/>.</summary>
    public ExtensionCheck? Extension { get; set; }

    public bool Waiting => _open.Waiting;

    public void BringWaiting() => Dispatcher.UIThread.Post(_open.BringWaiting);

    // --- Sign In ---------------------------------------------------------------------------------------------------

    public Task<bool> SignInAsync(SignInPrompt prompt) => OnUi(() =>
    {
        if (_signIn is { IsCompleted: false } open)
            return open;
        var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var window = new SignInWindow(prompt);
        if (!Show(window, waits: true, () => done.TrySetResult(window.SignedIn)))
            return Task.FromResult(false);
        log.Info("Sign In window shown");
        return _signIn = done.Task;
    });

    /// <summary>The session runs one Sign Out at a time, its window included, so there's only ever one of these.</summary>
    public Task<bool> SignOutHereAsync(string server, int unsent) => OnUi(() =>
    {
        var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var window = new SignOutHereWindow(Warnings.SignOutHere(server, unsent));
        return Show(window, waits: true, () => done.TrySetResult(window.SignOutHere)) ? done.Task : Task.FromResult(false);
    });

    // --- The channel's window routes -------------------------------------------------------------------------------

    public Task<JsonObject> OpenDialogAsync(JsonObject request) => OpenAsync(fromApp: true);

    /// <summary>The menu's Open from Healdan…: the same window, with no app's own box to go back to.</summary>
    public void OpenFromMenu() => _ = OpenAsync(fromApp: false);

    public Task<JsonObject> SaveNewAsync(JsonObject request)
    {
#if DEBUG
        if (DevAnswers.Load(log) is { } dev && Session is { SignedIn: true, Api: { } devApi })
            return dev.SaveNewAsync(request, devApi, paths);
#endif
        return OnUi(() => Ready("save-new") is { } api
            ? Answering("save", () => new SaveWindow(SaveNewRequest.From(request), api, paths), w => w.Answer)
            : Task.FromResult(WindowAnswers.NotReady("save-new")));
    }

    public Task<JsonObject> PropertiesAsync(JsonObject request)
    {
        // The channel added the doc # once it found the path is a local copy's (ChannelHandler).
        var docNumber = request["docNumber"] is JsonValue n && long.TryParse(n.ToJsonString(), out var d) ? d : 0;
        var path = request["path"] is JsonValue p && p.TryGetValue<string>(out var text) ? text : "";
#if DEBUG
        if (DevAnswers.Load(log) is { } dev && Session is { SignedIn: true, Api: { } devApi })
            return dev.PropertiesAsync(docNumber, devApi);
#endif
        return OnUi(() =>
        {
            if (Ready("properties") is not { } api)
                return Task.FromResult(WindowAnswers.NotReady("properties"));
            var title = Session?.Cycle?.StateOf(path) is { Known: true } copy ? copy.Title : $"#{docNumber}";
            return Answering("properties", () => new PropertiesWindow(api, docNumber, title), w => w.Answer);
        });
    }

    private Task<JsonObject> OpenAsync(bool fromApp)
    {
#if DEBUG
        if (fromApp && DevAnswers.Load(log) is { } dev)
            return Task.FromResult(dev.OpenDialog());
#endif
        return OnUi(() =>
        {
            if (Ready("open-dialog") is not { } api)
                return Task.FromResult(WindowAnswers.NotReady("open-dialog"));
            return Answering("open", () => new OpenWindow(api, fromApp), window =>
            {
                if (window.Chosen is { } chosen && Session?.Cycle is { } cycle)
                {
                    log.Info($"Opening #{chosen.DocNumber}{(chosen.ReadOnly ? " read-only" : "")}{(chosen.Version is { } v ? $" at v{v}" : "")} from Open from Healdan");
                    _ = Task.Run(async () =>
                    {
                        await cycle.OpenAsync(chosen.DocNumber, chosen.ReadOnly, chosen.Version);
                        if (!chosen.ReadOnly)
                            LinkHandler.SayIfReadOnly(cycle, chosen.DocNumber, notifier);
                    });
                }
                return window.Answer;
            });
        });
    }

    // --- The cycle's warnings --------------------------------------------------------------------------------------

    /// <summary>The user's own open asks first, so it waits like the window they opened it from.</summary>
    public void HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved) => Dispatcher.UIThread.Post(() =>
    {
        var key = $"held-{docNumber}";
        if (Bring(key))
            return;
        var window = new HeldElsewhereWindow(Warnings.HeldElsewhere(docNumber, deviceName, app, lastSaved, DateTimeOffset.Now));
        _single[key] = window;
        var shown = Show(window, waits: true, () =>
        {
            _single.Remove(key);
            if (Session?.Cycle is not { } cycle)
                return;
            switch (window.Choice)
            {
                case HeldElsewhereChoice.OpenReadOnly:
                    log.Info($"#{docNumber}: Open Read-Only, while it's checked out on {deviceName}");
                    _ = Task.Run(() => cycle.OpenAsync(docNumber, true));
                    break;
                case HeldElsewhereChoice.EditHere:
                    log.Info($"#{docNumber}: Edit Here Instead, moving its check-out from {deviceName}");
                    _ = Task.Run(() => cycle.MoveHereAsync(docNumber));
                    break;
            }
        });
        if (!shown)
            _single.Remove(key);
    });

    /// <summary>Nobody asked for it, and it may sit behind the browser: it holds no links back.</summary>
    public void Crashed(CopyState copy) => Dispatcher.UIThread.Post(() =>
    {
        if (!_crashShown.Add(copy.Path))
            return; // its window is up already
        var window = new CrashWindow(copy);
        var shown = Show(window, waits: false, () =>
        {
            _crashShown.Remove(copy.Path);
            switch (window.Choice)
            {
                case CrashChoice.Reopen:
                    Session?.Bring(copy);
                    break;
                case CrashChoice.CheckIn:
                    Session?.SendAndCheckIn(copy);
                    break;
            }
        });
        if (!shown)
        {
            _crashShown.Remove(copy.Path);
            notifier.Notify(copy.Title, Warnings.Crashed(copy.Title, copy.ReadOnly).ToString() + " Choose it in the Healdan Helper menu to reopen it.");
        }
    });

    // --- Settings --------------------------------------------------------------------------------------------------

    /// <summary>The menu's Settings…; one window at a time.</summary>
    public void ShowSettings() => Dispatcher.UIThread.Post(() =>
    {
        if (Bring("settings"))
            return;
        var server = Session?.Server is { } s ? s.GetLeftPart(UriPartial.Path).TrimEnd('/') : HelperSettings.Load(paths).Server;
        var extension = Extension?.Describe() ?? "Not in this build: the installed app installs it.";
        var window = new SettingsWindow(server, Session?.SignedIn == true, HelperSettings.Load(paths), LoginItem.Describe(), extension);
        _single["settings"] = window;
        if (!Show(window, waits: false, () => _single.Remove("settings")))
            _single.Remove("settings");
    });

    // --- About -----------------------------------------------------------------------------------------------------

    /// <summary>About Healdan Helper (#89), which asks nothing, like Settings.</summary>
    public void ShowAbout() => Dispatcher.UIThread.Post(() => ShowOnly("about", () => new AboutWindow(Session, this)));

    /// <summary>About's Open-Source Notices (#89).</summary>
    public void ShowNotices() => Dispatcher.UIThread.Post(() => ShowOnly("notices", () => new NoticesWindow()));

    /// <summary>
    /// Opens a web page in the usual browser, off the UI thread, for the menu's Open Healdan in Browser (#87) and
    /// About's links (#89). When no browser can be started, the user is told.
    /// </summary>
    public void OpenWebPage(Uri page) => _ = Task.Run(() =>
    {
        try
        {
            SystemOpener.OpenWebPage(page);
        }
        catch (Exception e)
        {
            log.Error($"{page} couldn't be opened in the browser", e);
            notifier.Notify("Healdan Helper", "Healdan Helper couldn't open your browser.");
        }
    });

    /// <summary>UI thread: shows a window that asks nothing, or brings the one of that kind up if it's open.</summary>
    private void ShowOnly(string key, Func<Window> make)
    {
        if (Bring(key))
            return;
        Window window;
        try
        {
            window = make();
        }
        catch (Exception e)
        {
            log.Error($"The {key} window couldn't be made", e);
            return;
        }
        _single[key] = window;
        if (!Show(window, waits: false, () => _single.Remove(key)))
            _single.Remove(key);
    }

    // --- Showing windows -------------------------------------------------------------------------------------------

    /// <summary>UI thread: shows a window that answers a channel request when it closes, one of each kind at a time.</summary>
    private Task<JsonObject> Answering<T>(string key, Func<T> make, Func<T, JsonObject> answer) where T : Window
    {
        if (Bring(key))
        {
            log.Info($"A {key} window is open already: the new request is cancelled");
            return Task.FromResult(WindowAnswers.Cancel());
        }
        var window = make();
        var done = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        _single[key] = window;
        var shown = Show(window, waits: true, () =>
        {
            _single.Remove(key);
            JsonObject result;
            try
            {
                result = answer(window);
            }
            catch (Exception e)
            {
                log.Error($"The {window.Title} window's answer failed", e);
                result = WindowAnswers.Cancel();
            }
            log.Info($"{window.Title}: {result["action"]}");
            done.TrySetResult(result);
        });
        if (!shown)
        {
            _single.Remove(key);
            return Task.FromResult(WindowAnswers.Cancel());
        }
        log.Info($"{window.Title} window shown");
        return done.Task;
    }

    /// <summary>UI thread: brings the window of that kind up if one is open.</summary>
    private bool Bring(string key)
    {
        if (!_single.TryGetValue(key, out var open))
            return false;
        MacPlatform.Activate();
        open.Activate();
        return true;
    }

    /// <summary>
    /// The API client for a window, once the helper is signed in and started. Before, or after Healdan refused the
    /// token, the user is told and the Sign In window opens; the caller answers <see cref="WindowAnswers.NotReady"/>.
    /// </summary>
    private HealdanApi? Ready(string route)
    {
        if (Session is { SignedIn: true, Api: { } api })
            return api;
        log.Warn($"/v1/{route} was asked for before the helper was signed in and started");
        notifier.Notify("Healdan Helper", WindowAnswers.NotReadyNotice(route));
        if (Session is { } session)
            _ = Task.Run(session.SignInAgainAsync);
        return null;
    }

    /// <summary>
    /// UI thread: shows a window over the app, and hands the focus back once the last one closes. A window is counted
    /// only once it's shown; one that fails to show is logged, counts for nothing, and returns false.
    /// </summary>
    private bool Show(Window window, bool waits, Action closed)
    {
        Func<bool> close;
        try
        {
            close = _open.Show(() =>
            {
                MacPlatform.Activate();
                window.Show();
                window.Activate();
            }, waits, bring: () =>
            {
                MacPlatform.Activate();
                window.Activate();
            });
        }
        catch (Exception e)
        {
            log.Error($"The {window.Title} window couldn't be shown", e);
            return false;
        }
        window.Closed += (_, _) =>
        {
            // No longer waiting before the answer goes out, so a link the answer lets through isn't dropped.
            var last = close();
            try
            {
                closed();
            }
            catch (Exception e)
            {
                log.Error($"After the {window.Title} window closed", e);
            }
            if (last)
                MacPlatform.GiveBackFocus();
        };
        return true;
    }

    /// <summary>Runs <paramref name="show"/> on the UI thread, from any thread, and returns what it returns.</summary>
    private static Task<T> OnUi<T>(Func<Task<T>> show)
    {
        var shown = new TaskCompletionSource<Task<T>>(TaskCreationOptions.RunContinuationsAsynchronously);
        Dispatcher.UIThread.Post(() =>
        {
            try
            {
                shown.SetResult(show());
            }
            catch (Exception e)
            {
                shown.SetException(e);
            }
        });
        return shown.Task.Unwrap();
    }
}
