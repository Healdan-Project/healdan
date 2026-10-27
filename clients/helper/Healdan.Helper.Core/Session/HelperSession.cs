using System.Text.Json;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// The helper while it runs, signed in to one server (spec §5.1). Starting goes in a fixed order: the sign-in (the kept
/// token, or the Sign In window), then the copies' resume, which sweeps the downloads a stop left half done, and only
/// then the channel and links, so no open runs before the sweep. It also turns the cycle's events into what the user
/// is told (spec §5.1, §5.10): quiet when things work, a notice when something needs them, a window when it needs an
/// answer. Every API client it makes reports a refused token, whichever call met it, so a <c>401</c> anywhere asks
/// for the password again (spec §5.2). Nothing here logs a token, a password or a file's contents.
/// </summary>
public sealed class HelperSession : ICycleEvents, IDisposable
{
    private const string Helper = "Healdan Helper";
    private const string SignInToOpen = "Sign in to Healdan Helper to open documents.";

    /// <summary>The Sign In window waits this long for the server, not the API client's ten minutes.</summary>
    private static readonly TimeSpan SignInTimeout = TimeSpan.FromSeconds(30);

    /// <summary>When saves can't go up during Sign Out, the check that Healdan answers at all waits this long.</summary>
    private static readonly TimeSpan ReachTimeout = TimeSpan.FromSeconds(10);

    /// <summary>Once the user chooses Sign Out on This Computer, the revoke is tried once more for this long, in case Healdan is back.</summary>
    private static readonly TimeSpan RetryRevokeTimeout = TimeSpan.FromSeconds(5);

    private readonly HelperPaths _paths;
    private readonly IKeychain _keychain;
    private readonly DeviceIdentity _device;
    private readonly IOfficeApp _app;
    private readonly IOfficeApp? _otherFiles;
    private readonly IWindows _windows;
    private readonly INotifier _notifier;
    private readonly HelperLog _log;
    private readonly CycleTimings? _timings;
    private readonly TimeSpan _signOutFlush;

    /// <summary>Under every API client: tells <see cref="TokenRefused"/> of a 401 to a call that carried the token.</summary>
    private readonly RefusedTokenHandler _http;

    /// <summary>Guards the fields below. Nothing waits or raises an event while holding it.</summary>
    private readonly object _gate = new();

    /// <summary>One start, sign-in-then-start or sign-out at a time.</summary>
    private readonly SemaphoreSlim _lifecycle = new(1, 1);

    private readonly HashSet<string> _crashed = new(StringComparer.Ordinal);
    private HealdanApi? _api;

    /// <summary>The cycle while its copies are resumed: its events need it, but the menu and windows don't see it yet.</summary>
    private CopyCycle? _resuming;

    /// <summary>The cycle once its copies are resumed.</summary>
    private CopyCycle? _cycle;

    private ChannelServer? _channel;
    private bool _signedIn;
    private bool _started;
    private bool _startFailed;
    private bool _signingOut;
    private string? _userName;
    private int _waiting;
    private int _asking;

    /// <summary>1 while a Sign Out runs, its Sign Out on This Computer Only? window included.</summary>
    private int _signOut;

    private bool _disposed;

    /// <param name="http">The HTTP handler under every API client; the system's by default (tests pass a fake server).</param>
    /// <param name="timings">The cycle's timings; the trial's by default.</param>
    /// <param name="signOutFlush">How long Sign Out waits for unsent saves before it gives up and keeps the sign-in; 30 s by default.</param>
    /// <param name="otherFiles">The app for files no office app edits (<see cref="DefaultApp"/>); without one, everything opens in <paramref name="app"/>.</param>
    public HelperSession(HelperPaths paths, IKeychain keychain, DeviceIdentity device, IOfficeApp app, IWindows windows,
        INotifier notifier, HelperLog log, HttpMessageHandler? http = null, CycleTimings? timings = null, TimeSpan? signOutFlush = null,
        IOfficeApp? otherFiles = null)
    {
        _otherFiles = otherFiles;
        _paths = paths;
        _keychain = keychain;
        _device = device;
        _app = app;
        _windows = windows;
        _notifier = notifier;
        _log = log;
        _timings = timings;
        _signOutFlush = signOutFlush ?? TimeSpan.FromSeconds(30);
        _http = new RefusedTokenHandler(TokenRefused) { InnerHandler = http ?? new SocketsHttpHandler() };
        Links = new LinkHandler(windows, notifier, log);
    }

    /// <summary>The links from Apple events and the channel. Links wait until the helper has started.</summary>
    public LinkHandler Links { get; }

    /// <summary>Something the menu shows changed: the sign-in, the documents open, the saves waiting. Raised on any thread.</summary>
    public event Action? Changed;

    /// <summary>The server the helper signs in to; set once it has been chosen.</summary>
    public Uri? Server
    {
        get
        {
            lock (_gate)
                return _api?.Server;
        }
    }

    /// <summary>
    /// The server's web page, for the menu's Open Healdan in Browser: there once <see cref="Server"/> is, and kept after
    /// Sign Out, as the browser has its own sign-in. A server under a path keeps it.
    /// </summary>
    public Uri? WebPage => Server is { } server ? new Uri(Address(server) + "/") : null;

    /// <summary>
    /// The API client once the helper has started, for the windows (Task 17's Open and Save). A <c>401</c> it meets
    /// asks for the password again, as the cycle's do.
    /// </summary>
    public HealdanApi? Api
    {
        get
        {
            lock (_gate)
                return _started ? _api : null;
        }
    }

    /// <summary>The signed-in user's name, once Healdan has said it.</summary>
    public string? UserName
    {
        get
        {
            lock (_gate)
                return _signedIn ? _userName : null;
        }
    }

    /// <summary>A token is held that Healdan hasn't refused.</summary>
    public bool SignedIn
    {
        get
        {
            lock (_gate)
                return _signedIn;
        }
    }

    /// <summary>The copies are looked after, the channel listens (unless it couldn't start) and links open.</summary>
    public bool Started
    {
        get
        {
            lock (_gate)
                return _started;
        }
    }

    /// <summary>The copies couldn't be resumed, so the helper hasn't started; the user has been told.</summary>
    public bool StartFailed
    {
        get
        {
            lock (_gate)
                return _startFailed;
        }
    }

    /// <summary>How many copies have a save waiting for the server.</summary>
    public int SavesWaiting
    {
        get
        {
            lock (_gate)
                return _waiting;
        }
    }

    /// <summary>The cycle once its copies are resumed, for the menu and the windows (Task 17's Open, Save and crash windows).</summary>
    public CopyCycle? Cycle
    {
        get
        {
            lock (_gate)
                return _cycle;
        }
    }

    /// <summary>The documents open, for the menu.</summary>
    public IReadOnlyList<CopyState> OpenCopies() => Cycle?.OpenCopies() ?? [];

    /// <summary>
    /// Spec §5.4: the copies whose changes can't go to Healdan, kept once their window closed, for the menu's Not Yet in
    /// Healdan (<see cref="CopyCycle.KeptCopies"/>).
    /// </summary>
    public IReadOnlyList<CopyState> KeptCopies() => Cycle?.KeptCopies() ?? [];

    /// <summary>
    /// The menu's click on a copy Not Yet in Healdan: opens it as it is, with no check-out or download, and says how
    /// to file its changes.
    /// </summary>
    public void OpenKept(CopyState copy)
    {
        var cycle = Cycle;
        if (cycle is null)
            return;
        _ = Task.Run(() =>
        {
            try
            {
                cycle.OpenKept(copy.Path);
                _log.Info($"Opened the kept copy of {copy.DocRef}, which isn't in Healdan yet");
                _notifier.Notify(copy.Title, "Your changes to it aren't in Healdan yet: use Save as New Document to file them.");
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException
                                           or System.ComponentModel.Win32Exception)
            {
                _log.Warn($"Couldn't open the kept copy of {copy.DocRef}: {e.Message}");
                _notifier.Notify(copy.Title, $"It couldn't be opened: {e.Message}");
            }
            RaiseChanged();
        });
    }

    /// <summary>Whether the app went away with this copy open, and it hasn't been reopened since.</summary>
    public bool HasCrashed(CopyState copy)
    {
        lock (_gate)
            return _crashed.Contains(copy.Path);
    }

    /// <summary>The cycle running, resumed or not: what the events and the sign-in go by.</summary>
    private CopyCycle? Live
    {
        get
        {
            lock (_gate)
                return _cycle ?? _resuming;
        }
    }

    private bool Disposed
    {
        get
        {
            lock (_gate)
                return _disposed;
        }
    }

    /// <summary>
    /// Starts: signs in with the kept token, or asks with the Sign In window; then resumes the copies; then starts the
    /// channel and opens links. If the user closes the Sign In window, nothing starts until they sign in from the menu.
    /// </summary>
    public async Task StartAsync()
    {
        _log.Info($"Starting, as device {_device.Id}");
        await _lifecycle.WaitAsync();
        try
        {
            var settings = HelperSettings.Load(_paths);
            if (!await ResumeSignInAsync(settings.Server) && !await AskSignInAsync(settings.Server, serverFixed: false))
            {
                _log.Info("Not signed in: waiting for Sign In from the menu");
                Links.NotReady(SignInToOpen);
                RaiseChanged();
                return;
            }
            await BeginAsync();
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    /// <summary>Sign In from the menu, and after Healdan refused the token (a <c>401</c>): the Sign In window, then start if the helper hadn't.</summary>
    public async Task SignInAgainAsync()
    {
        if (Interlocked.Exchange(ref _asking, 1) == 1)
            return; // the window is up already
        try
        {
            Uri? server;
            bool running;
            lock (_gate)
                (server, running) = (_api?.Server, (_cycle ?? _resuming) is not null);
            if (await AskSignInAsync(running ? Address(server!) : HelperSettings.Load(_paths).Server, serverFixed: running) && !running)
                await EnsureStartedAsync();
        }
        finally
        {
            Volatile.Write(ref _asking, 0);
        }
    }

    /// <summary>
    /// Sign Out (spec §5.2): sends every unsent save first, settling ones and those going up included, then revokes the
    /// token and stops. Copies stay on this computer for the next sign-in. If saves are still unsent after a while, or
    /// Healdan refuses the revoke, the user is told and stays signed in; a Quit meanwhile keeps the sign-in too. When
    /// Healdan can't be reached at all, a server gone for good would keep the helper signed in to it for ever, so the
    /// user is asked whether to forget the sign-in on this computer only. A second Sign Out meanwhile, while that
    /// window is up included, does nothing.
    /// </summary>
    public async Task SignOutAsync()
    {
        if (Interlocked.Exchange(ref _signOut, 1) == 1)
            return; // one is under way already
        try
        {
            if (await RevokeAsync() is { } unreached)
                await SignOutHereAsync(unreached.Api, unreached.Unsent);
        }
        finally
        {
            Volatile.Write(ref _signOut, 0);
        }
    }

    /// <summary>
    /// Under <see cref="_lifecycle"/>: Sign Out's flush and revoke, and the stop once they've worked. Returns the client
    /// and the saves left unsent when Healdan couldn't be reached at all, else null.
    /// </summary>
    private async Task<(HealdanApi Api, int Unsent)?> RevokeAsync()
    {
        await _lifecycle.WaitAsync();
        try
        {
            HealdanApi? api;
            CopyCycle? cycle;
            lock (_gate)
            {
                (api, cycle) = (_api, _cycle);
                if (api?.Token is null || _disposed)
                    return null;
                _signingOut = true; // its own 401s (a token already refused) are expected
            }
            try
            {
                try
                {
                    await new SignIn(api, _keychain, _device).SignOutAsync(() => FlushAsync(cycle));
                }
                catch (OperationCanceledException) when (Disposed)
                {
                    _log.Info("Quit during Sign Out: the sign-in is kept, and the next start sends what's unsent");
                    return null;
                }
                catch (UnsentSavesException e)
                {
                    if (await ReachableAsync(api))
                    {
                        _log.Warn("Sign Out didn't finish: " + e.Message);
                        _notifier.Notify(Helper, e.Message);
                        return null;
                    }
                    _log.Warn($"Sign Out couldn't send {e.Count} save(s): Healdan can't be reached");
                    return (api, e.Count);
                }
                catch (Exception e) when (Unreachable(e))
                {
                    _log.Warn($"Sign Out couldn't revoke the token: Healdan can't be reached ({e.GetType().Name}: {e.Message})");
                    return (api, 0);
                }
                catch (Exception e) when (e is InvalidOperationException or ApiException)
                {
                    _log.Warn("Sign Out didn't finish: " + e.Message);
                    _notifier.Notify(Helper, e is ApiException a
                        ? $"Healdan Helper couldn't sign out, as Healdan answered {a.Status}. You're still signed in."
                        : e.Message);
                    return null;
                }
                _log.Info("Signed out");
                Stop(SignInToOpen);
                return null;
            }
            finally
            {
                lock (_gate)
                    _signingOut = false;
            }
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    /// <summary>
    /// The menu's click on a document: brings its window up. A copy whose app went away is opened again, from the
    /// kept copy, and watched again.
    /// </summary>
    public void Bring(CopyState copy)
    {
        var cycle = Cycle;
        if (cycle is null)
            return;
        bool crashed;
        lock (_gate)
            crashed = _crashed.Remove(copy.Path);
        _ = Task.Run(async () =>
        {
            if (crashed && !copy.ReadOnly)
            {
                await cycle.OpenAsync(copy.DocNumber, false);
                RaiseChanged();
                return;
            }
            try
            {
                _app.Open(copy.Path);
            }
            catch (Exception e) when (e is IOException or InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                _log.Warn($"Couldn't bring up {copy.DocRef}: {e.Message}");
                _notifier.Notify(copy.Title, $"{_app.Name} couldn't open it: {e.Message}");
            }
        });
    }

    /// <summary>
    /// After a Crash's Send and Check In (spec §5.10): the kept copy's changes go up with the check-in, as when its
    /// window closes, and the copy goes once Healdan has them. A read-only copy is just let go.
    /// </summary>
    public void SendAndCheckIn(CopyState copy)
    {
        var cycle = Cycle;
        if (cycle is null)
            return;
        lock (_gate)
            _crashed.Remove(copy.Path);
        _log.Info($"Checking in {copy.DocRef} after it closed unexpectedly");
        _ = cycle.ClosedAsync(copy.Path);
        RaiseChanged();
    }

    /// <summary>Quit: stops the channel (removing <c>channel.json</c>) and the cycle. Copies and their records stay for the next start.</summary>
    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
                return;
            _disposed = true;
        }
        _log.Info("Quitting");
        Stop(null);
    }

    // --- Signing in ------------------------------------------------------------------------------------------------

    /// <summary>The kept token, checked with <c>/me</c>. Healdan being unreachable keeps it: the helper starts and saves wait.</summary>
    private async Task<bool> ResumeSignInAsync(string? address)
    {
        if (address is null)
            return false;
        HealdanApi api;
        try
        {
            api = NewApi(ParseAddress(address));
        }
        catch (Exception e) when (e is ArgumentException or UriFormatException)
        {
            _log.Warn("The settings' server address isn't usable: " + e.Message);
            return false;
        }
        try
        {
            if (!await new SignIn(api, _keychain, _device).TryResumeAsync())
            {
                _log.Info($"No usable sign-in for {LinkName(api)} is kept: asking for one");
                return false;
            }
            SetSignedIn(api, await UserNameAsync(api));
            _log.Info($"Signed in to {LinkName(api)} with the kept token");
        }
        catch (InvalidOperationException e)
        {
            _log.Warn("The keychain couldn't be read: " + e.Message);
            return false;
        }
        catch (Exception e) when (e is ApiException or HttpRequestException or TaskCanceledException or IOException or JsonException)
        {
            // TryResumeAsync keeps the token unless Healdan refused it, so the helper carries on, and saves wait for Healdan.
            SetSignedIn(api, null);
            _log.Warn($"Signed in to {LinkName(api)} with the kept token, but Healdan can't be reached yet ({e.GetType().Name})");
        }
        return true;
    }

    private async Task<bool> AskSignInAsync(string? server, bool serverFixed)
    {
        _log.Info("Asking for the sign-in");
        try
        {
            return await _windows.SignInAsync(new SignInPrompt(server, serverFixed, TrySignInAsync)) && SignedIn;
        }
        catch (Exception e)
        {
            _log.Error("The Sign In window failed", e);
            return false;
        }
    }

    /// <summary>
    /// The Sign In window's button (spec §5.2): null once signed in, else the reason, as the window shows it. The
    /// password is passed on for the one call and never kept. A sign-in that works always carries on to a full start,
    /// even when the user closed the window while it ran, so the helper is never signed in but not started.
    /// </summary>
    private async Task<string?> TrySignInAsync(string address, string user, string password)
    {
        if (string.IsNullOrWhiteSpace(address))
            return "Enter your Healdan server's address.";
        if (string.IsNullOrWhiteSpace(user) || string.IsNullOrEmpty(password))
            return "Enter your username and password.";
        Uri server;
        HealdanApi api;
        try
        {
            server = ParseAddress(address);
            HealdanApi? current;
            lock (_gate)
            {
                if ((_cycle ?? _resuming) is not null && _api is not null && !SameServer(_api.Server, server))
                    return $"Healdan Helper is signed in to {LinkName(_api)}. Sign out first to use another server.";
                current = _api is not null && SameServer(_api.Server, server) ? _api : null;
            }
            // The running cycle's client takes the new token; another server gets a client of its own.
            api = current ?? NewApi(server);
        }
        catch (Exception e) when (e is ArgumentException or UriFormatException)
        {
            return e is ArgumentException { ParamName: { } name } ? e.Message.Replace($" (Parameter '{name}')", "") : e.Message;
        }

        using var timeout = new CancellationTokenSource(SignInTimeout);
        try
        {
            await new SignIn(api, _keychain, _device).SignInAsync(user.Trim(), password, timeout.Token);
        }
        catch (InvalidOperationException e)
        {
            return e.Message; // not Healdan, an API this helper doesn't speak, or the keychain refused
        }
        catch (ApiException e)
        {
            return e.Code switch
            {
                "http-404" => "That address isn't a Healdan server.",
                _ when e.Code.StartsWith("http-", StringComparison.Ordinal) => $"The server answered {e.Status}: {e.Detail}",
                _ => e.Detail.Length > 0 ? e.Detail : "Healdan refused the sign-in."
            };
        }
        catch (JsonException)
        {
            return "That address isn't a Healdan server.";
        }
        catch (OperationCanceledException)
        {
            return $"{server.Authority} didn't answer within {SignInTimeout.TotalSeconds:0} seconds.";
        }
        catch (HttpRequestException e)
        {
            return $"{server.Authority} couldn't be reached: {e.Message}";
        }

        if (SetCopiesAside(server) is { } refusal)
            return refusal;
        HelperSettings.Update(_paths, s => s.Server = Address(server));
        SetSignedIn(api, await UserNameAsync(api));
        _log.Info($"Signed in to {LinkName(api)} as {user.Trim()}");
        if (Live is { } running)
            running.RetryNow(); // after a 401: the saves that waited go now
        else
            _ = Task.Run(EnsureStartedAsync); // a no-op when the window's caller starts the helper itself
        RaiseChanged();
        return null;
    }

    /// <summary>
    /// A sign-in to another server than the settings name (spec §5.4): the copies are all from that one, so they're set
    /// aside first (<see cref="CopyStore.SetAside"/>), and none goes to the new server, where the same doc # can be
    /// another document. The user is told where they are. Returns why the sign-in can't go on, or null.
    /// </summary>
    private string? SetCopiesAside(Uri server)
    {
        if (HelperSettings.Load(_paths).Server is not { } previous)
            return null;
        string from;
        try
        {
            var old = ParseAddress(previous);
            if (SameServer(old, server))
                return null;
            from = HealdanLink.HostAndPort(old);
        }
        catch (UriFormatException)
        {
            from = "an earlier server"; // the copies came from somewhere, but not from this one
        }
        var to = HealdanLink.HostAndPort(server);
        try
        {
            if (new CopyStore(_paths).SetAside(from) is { } aside)
            {
                var folder = Path.GetFileName(aside);
                _log.Info($"Signing in to {to}: the copies from {from} are set aside in {folder}");
                _notifier.Notify(Helper, $"The copies from {from} are now in the {folder} folder, beside Healdan Helper's Copies "
                    + $"folder. Healdan Helper won't send them to {to}.");
            }
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Warn($"The copies from {from} couldn't be set aside: {e.Message}");
            return $"Healdan Helper couldn't move the copies from {from} out of the way, so it hasn't signed in to {to}: {e.Message}";
        }
    }

    /// <summary>Starts the helper once signed in, unless it has started, its start failed, or it's quitting.</summary>
    private async Task EnsureStartedAsync()
    {
        await _lifecycle.WaitAsync();
        try
        {
            bool start;
            lock (_gate)
                start = !_disposed && !_startFailed && _signedIn && (_cycle ?? _resuming) is null;
            if (start)
                await BeginAsync();
        }
        catch (Exception e)
        {
            _log.Error("Starting after the sign-in failed", e);
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    /// <summary>
    /// Healdan refused the token, on any call that carried it (spec §5.2): the menu offers Sign In, the user hears it
    /// once, and the Sign In window asks for the password with the server fixed. Saves wait meanwhile. Before the helper
    /// has started, during Sign Out and while quitting, the caller deals with it.
    /// </summary>
    private void TokenRefused()
    {
        bool wasSignedIn;
        lock (_gate)
        {
            if ((_cycle ?? _resuming) is null || _signingOut || _disposed)
                return;
            (wasSignedIn, _signedIn) = (_signedIn, false);
        }
        if (wasSignedIn)
        {
            _log.Warn("Healdan refused the token: asking for the sign-in again");
            _notifier.Notify(Helper, "Sign in to Healdan again. Your saves wait on this computer until you do.");
            RaiseChanged();
        }
        if (Volatile.Read(ref _asking) == 0)
            _ = Task.Run(SignInAgainAsync);
    }

    private void SetSignedIn(HealdanApi api, string? userName)
    {
        lock (_gate)
            (_api, _signedIn, _userName) = (api, true, userName);
    }

    private async Task<string?> UserNameAsync(HealdanApi api)
    {
        try
        {
            var me = await api.GetJsonAsync("v1/me");
            return me.TryGetProperty("name", out var name) && name.ValueKind == JsonValueKind.String && name.GetString() is { Length: > 0 } n
                ? n
                : me.TryGetProperty("username", out var u) && u.ValueKind == JsonValueKind.String ? u.GetString() : null;
        }
        catch (Exception e) when (e is ApiException or HttpRequestException or TaskCanceledException or IOException or JsonException)
        {
            return null; // the menu says who once Healdan answers
        }
    }

    private HealdanApi NewApi(Uri server) => new(server, _http);

    /// <summary>What the user typed as the address: https unless it says otherwise (http only for this computer).</summary>
    private static Uri ParseAddress(string address)
    {
        var text = address.Trim();
        if (!text.Contains("://", StringComparison.Ordinal))
            text = "https://" + text;
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri) || uri.Scheme is not ("https" or "http") || uri.UserInfo.Length > 0
            || uri.Query.Length > 0 || uri.Fragment.Length > 0 || uri.Host.Length == 0)
            throw new UriFormatException("That isn't a server address. It looks like https://healdan.example.");
        return uri;
    }

    private static string Address(Uri server) => server.GetLeftPart(UriPartial.Path).TrimEnd('/');

    private static bool SameServer(Uri a, Uri b) => string.Equals(Address(a), Address(b), StringComparison.OrdinalIgnoreCase);

    private static string LinkName(HealdanApi api) => HealdanLink.HostAndPort(api.Server);

    // --- Starting and stopping -------------------------------------------------------------------------------------

    /// <summary>
    /// Under <see cref="_lifecycle"/>: the copies' resume, then the channel, then links. The menu and windows see the
    /// cycle only once its resume has finished; a resume that fails is told to the user, and nothing starts.
    /// </summary>
    private async Task BeginAsync()
    {
        HealdanApi api;
        lock (_gate)
        {
            if (_disposed || (_cycle ?? _resuming) is not null)
                return; // quit while the Sign In window was up, or started already
            api = _api!;
            _startFailed = false;
        }

        CopyCycle? cycle = null;
        try
        {
            cycle = new CopyCycle(api, new CopyStore(_paths), _app, _device.Id, this, _timings, _otherFiles);
            lock (_gate)
                _resuming = cycle; // its events name copies by their title
            await cycle.ResumeAsync();
        }
        catch (Exception e)
        {
            lock (_gate)
                (_resuming, _startFailed) = (null, true);
            cycle?.Dispose();
            _log.Error("The local copies couldn't be resumed, so the helper hasn't started", e);
            _notifier.Notify(Helper, $"Healdan Helper couldn't look after its local copies, so it hasn't started: {e.Message} Quit and start it again.");
            RaiseChanged();
            return;
        }
        bool quit;
        lock (_gate)
        {
            quit = _disposed;
            _resuming = null;
            if (!quit)
                _cycle = cycle;
        }
        if (quit)
        {
            cycle.Dispose();
            return;
        }
        _log.Info($"Copies resumed: {cycle.OpenCopies().Count} looked after");

        try
        {
            // The extension writes a window's content here for Save to Healdan; the helper deletes it once filed.
            if (OperatingSystem.IsWindows())
                Directory.CreateDirectory(_paths.Temp);
            else
                Directory.CreateDirectory(_paths.Temp, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Warn("The temporary folder couldn't be made: " + e.Message);
        }

        var channel = new ChannelServer(_paths, new ChannelHandler(cycle, Links, _windows, _notifier, _log, _paths));
        try
        {
            channel.Start();
            _log.Info($"Channel listening on 127.0.0.1:{ChannelInfo.Read(_paths)?.Port}");
            lock (_gate)
            {
                quit = _disposed;
                if (!quit)
                    _channel = channel;
            }
            if (quit)
            {
                channel.Stop(); // quit while starting: Dispose has run, so this start is undone here
                cycle.Dispose();
                return;
            }
        }
        catch (Exception e) when (e is System.Net.HttpListenerException or IOException or UnauthorizedAccessException)
        {
            _log.Error("The channel couldn't start", e);
            _notifier.Notify(Helper, "LibreOffice can't reach Healdan Helper, so its Save and Open buttons won't work. Quit and start the helper again.");
        }
        lock (_gate)
            _started = _cycle == cycle;
        _ = Links.Ready(api.Server, cycle);
        RaiseChanged();
    }

    /// <summary>Stops the channel, links and the cycle; copies and records stay as they are.</summary>
    private void Stop(string? linkNotice)
    {
        Links.NotReady(linkNotice);
        ChannelServer? channel;
        CopyCycle? cycle;
        lock (_gate)
        {
            (channel, cycle) = (_channel, _cycle ?? _resuming);
            (_channel, _cycle, _resuming, _signedIn, _started, _userName, _waiting) = (null, null, null, false, false, null, 0);
            _crashed.Clear();
        }
        channel?.Stop();
        cycle?.Dispose();
        RaiseChanged();
    }

    /// <summary>
    /// Sign Out's flush: every unsent save is tried now (<see cref="CopyCycle.Unsent"/>: queued, going up, waiting to
    /// retry, or still settling) and given a while to go up. Throws to keep the sign-in when some are still unsent, or
    /// when the helper is quitting meanwhile.
    /// </summary>
    private async Task FlushAsync(CopyCycle? cycle)
    {
        if (cycle is null)
            return;
        cycle.RetryNow();
        var deadline = DateTime.UtcNow + _signOutFlush;
        int left;
        while (true)
        {
            if (Disposed)
                throw new OperationCanceledException("Quitting during Sign Out.");
            left = cycle.Unsent();
            if (left == 0 || DateTime.UtcNow >= deadline)
                break;
            await Task.Delay(100);
        }
        if (Disposed)
            throw new OperationCanceledException("Quitting during Sign Out."); // a disposed cycle has nothing open, not nothing unsent
        if (left > 0)
            throw new UnsentSavesException(left);
    }

    /// <summary>
    /// Sign Out couldn't reach Healdan (spec §5.2): asks whether to forget the sign-in on this computer only. The
    /// window waits outside <see cref="_lifecycle"/>, as the user may take their time, and the cycle runs on meanwhile.
    /// On a yes the revoke is tried once more, briefly, in case Healdan is back; failing that, the token is forgotten
    /// here. Either way the helper stops. Unsent saves stay with their copies, for the next sign-in to this server. A
    /// Quit before or meanwhile, a sign-out some other way meanwhile, or a keychain that refuses keeps the sign-in.
    /// </summary>
    private async Task SignOutHereAsync(HealdanApi api, int unsent)
    {
        var server = LinkName(api);
        if (Disposed)
        {
            _log.Info("Quit during Sign Out: the sign-in is kept, and the next start sends what's unsent");
            return;
        }
        _log.Info($"Asking whether to sign out on this computer only, as {server} can't be reached ({unsent} save(s) unsent)");
        bool here;
        try
        {
            here = await _windows.SignOutHereAsync(server, unsent);
        }
        catch (Exception e)
        {
            _log.Error("The Sign Out on This Computer Only window failed", e);
            here = false;
        }
        if (!here)
        {
            _log.Info("Stayed signed in to " + server);
            return;
        }

        await _lifecycle.WaitAsync();
        try
        {
            bool still;
            lock (_gate)
            {
                still = !_disposed && _api == api && api.Token is not null;
                if (still)
                    _signingOut = true;
            }
            if (!still)
            {
                _log.Info($"The sign-in to {server} is kept: the helper quit, or signed out, while the window was up");
                return;
            }
            try
            {
                var signIn = new SignIn(api, _keychain, _device, RetryRevokeTimeout);
                try
                {
                    try
                    {
                        await signIn.SignOutAsync(() => Task.CompletedTask);
                        _log.Info($"Signed out: {server} answered after all");
                    }
                    catch (Exception e) when (Unreachable(e) || e is ApiException)
                    {
                        signIn.ForgetHere();
                        _log.Info($"Signed out on this computer only: {server} still accepts the token until it expires");
                    }
                }
                catch (InvalidOperationException e)
                {
                    _log.Warn("The keychain couldn't forget the sign-in: " + e.Message);
                    _notifier.Notify(Helper, e.Message);
                    return;
                }
                Stop(SignInToOpen);
            }
            finally
            {
                lock (_gate)
                    _signingOut = false;
            }
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    /// <summary>Whether Healdan answers at all, asked when saves couldn't go up during Sign Out.</summary>
    private static async Task<bool> ReachableAsync(HealdanApi api)
    {
        using var timeout = new CancellationTokenSource(ReachTimeout);
        try
        {
            await api.ServerAsync(timeout.Token);
            return true;
        }
        catch (Exception e)
        {
            return !Unreachable(e); // a refusal, or an answer that isn't Healdan's, is still an answer
        }
    }

    /// <summary>
    /// No answer from Healdan: no connection, no answer in time, or a proxy whose server has gone (502, 503, 504). A
    /// refusal Healdan sends itself is an answer.
    /// </summary>
    private static bool Unreachable(Exception e) =>
        e is HttpRequestException or OperationCanceledException or IOException || e is ApiException { Status: 502 or 503 or 504 };

    /// <summary>Sign Out's flush ended with saves still to go up.</summary>
    private sealed class UnsentSavesException(int count) : InvalidOperationException(count == 1
        ? "A save is still waiting to go to Healdan, so you're still signed in. Sign out once it has gone."
        : $"{count} saves are still waiting to go to Healdan, so you're still signed in. Sign out once they have gone.")
    {
        public int Count { get; } = count;
    }

    // --- What the cycle tells the user -----------------------------------------------------------------------------

    void ICycleEvents.Confirmed(string path, CopyState state) => RaiseChanged();

    void ICycleEvents.HeldElsewhere(string? path, long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved)
    {
        _log.Info($"#{docNumber} is checked out to this user on {deviceName}");
        _windows.HeldElsewhere(docNumber, deviceName, app, lastSaved);
    }

    void ICycleEvents.CheckoutEnded(string path)
    {
        var title = TitleOf(path);
        _log.Warn($"{title} can't be saved to Healdan any more; its copy is kept");
        _notifier.Notify(title, "It can't be saved to Healdan any more. Your copy is kept: use Save as New Document to file your changes.");
        RaiseChanged();
    }

    void ICycleEvents.Crashed(string path)
    {
        var copy = Live?.StateOf(path);
        if (copy is not { Known: true })
            return;
        lock (_gate)
            _crashed.Add(copy.Path);
        _log.Warn($"{copy.Title} closed unexpectedly; its copy and check-out are kept");
        _windows.Crashed(copy);
        RaiseChanged();
    }

    void ICycleEvents.Waiting(int pending)
    {
        int before;
        lock (_gate)
            (before, _waiting) = (_waiting, pending);
        if (before == 0 && pending > 0)
        {
            _log.Warn($"{pending} save(s) waiting for Healdan");
            _notifier.Notify(Helper, "A save is waiting for Healdan. It's kept on this computer and goes up once Healdan can be reached.");
        }
        else if (before > 0 && pending == 0)
        {
            _log.Info("Every waiting save has reached Healdan");
            _notifier.Notify(Helper, "Your saves have reached Healdan.");
        }
        RaiseChanged();
    }

    void ICycleEvents.SignInNeeded() => TokenRefused();

    void ICycleEvents.Error(string message)
    {
        _log.Warn(message);
        _notifier.Notify(Helper, message);
    }

    private string TitleOf(string path) =>
        Live?.StateOf(path) is { Known: true } state ? state.Title : Path.GetFileNameWithoutExtension(path);

    private void RaiseChanged()
    {
        try
        {
            Changed?.Invoke();
        }
        catch (Exception e)
        {
            _log.Error("The menu couldn't be updated", e);
        }
    }
}
