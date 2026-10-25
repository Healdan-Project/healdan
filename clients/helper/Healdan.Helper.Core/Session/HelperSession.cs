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
/// answer. Nothing here logs a token, a password or a file's contents.
/// </summary>
public sealed class HelperSession : ICycleEvents, IDisposable
{
    private const string Helper = "Healdan Helper";
    private const string SignInToOpen = "Sign in to Healdan Helper to open documents.";

    /// <summary>Sign Out sends waiting saves first, for this long, before it gives up and keeps the sign-in.</summary>
    private static readonly TimeSpan SignOutFlush = TimeSpan.FromSeconds(30);

    /// <summary>The Sign In window waits this long for the server, not the API client's ten minutes.</summary>
    private static readonly TimeSpan SignInTimeout = TimeSpan.FromSeconds(30);

    private readonly HelperPaths _paths;
    private readonly IKeychain _keychain;
    private readonly DeviceIdentity _device;
    private readonly IOfficeApp _app;
    private readonly IWindows _windows;
    private readonly INotifier _notifier;
    private readonly HelperLog _log;
    private readonly HttpMessageHandler? _http;
    private readonly CycleTimings? _timings;

    /// <summary>Guards the fields below. Nothing waits or raises an event while holding it.</summary>
    private readonly object _gate = new();

    /// <summary>One start, sign-in-then-start or sign-out at a time.</summary>
    private readonly SemaphoreSlim _lifecycle = new(1, 1);

    private readonly HashSet<string> _crashed = new(StringComparer.Ordinal);
    private HealdanApi? _api;
    private CopyCycle? _cycle;
    private ChannelServer? _channel;
    private bool _signedIn;
    private bool _started;
    private string? _userName;
    private int _waiting;
    private int _asking;
    private bool _disposed;

    /// <param name="http">The HTTP handler every API client uses; the system's by default (tests pass a fake server).</param>
    /// <param name="timings">The cycle's timings; the trial's by default.</param>
    public HelperSession(HelperPaths paths, IKeychain keychain, DeviceIdentity device, IOfficeApp app, IWindows windows,
        INotifier notifier, HelperLog log, HttpMessageHandler? http = null, CycleTimings? timings = null)
    {
        _paths = paths;
        _keychain = keychain;
        _device = device;
        _app = app;
        _windows = windows;
        _notifier = notifier;
        _log = log;
        _http = http;
        _timings = timings;
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

    /// <summary>How many copies have a save waiting for the server.</summary>
    public int SavesWaiting
    {
        get
        {
            lock (_gate)
                return _waiting;
        }
    }

    /// <summary>The cycle while the helper has started, for the windows (Task 17's Open, Save and crash windows).</summary>
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

    /// <summary>Whether the app went away with this copy open, and it hasn't been reopened since.</summary>
    public bool HasCrashed(CopyState copy)
    {
        lock (_gate)
            return _crashed.Contains(copy.Path);
    }

    /// <summary>
    /// Starts: signs in with the kept token, or asks with the Sign In window; then resumes the copies; then starts the
    /// channel and opens links. If the user closes the Sign In window, nothing starts until Sign In from the menu.
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
                (server, running) = (_api?.Server, _cycle is not null);
            if (!await AskSignInAsync(running ? Address(server!) : HelperSettings.Load(_paths).Server, serverFixed: running))
                return;
            if (running)
                return; // TrySignInAsync has sent the waiting saves on their way
            await _lifecycle.WaitAsync();
            try
            {
                if (Cycle is null && !_disposed)
                    await BeginAsync();
            }
            finally
            {
                _lifecycle.Release();
            }
        }
        finally
        {
            Volatile.Write(ref _asking, 0);
        }
    }

    /// <summary>
    /// Sign Out (spec §5.2): sends the waiting saves first, then revokes the token and stops. Copies stay on this
    /// computer for the next sign-in. If saves are still waiting after a while, or the token can't be revoked, the user
    /// is told and stays signed in.
    /// </summary>
    public async Task SignOutAsync()
    {
        await _lifecycle.WaitAsync();
        try
        {
            HealdanApi? api;
            CopyCycle? cycle;
            lock (_gate)
                (api, cycle) = (_api, _cycle);
            if (api?.Token is null)
                return;
            try
            {
                await new SignIn(api, _keychain, _device).SignOutAsync(() => FlushAsync(cycle));
            }
            catch (Exception e) when (e is InvalidOperationException or ApiException or HttpRequestException or TaskCanceledException or IOException)
            {
                _log.Warn("Sign Out didn't finish: " + e.Message);
                _notifier.Notify(Helper, e is InvalidOperationException
                    ? e.Message
                    : "Healdan Helper couldn't sign out, as Healdan can't be reached. You're still signed in.");
                return;
            }
            _log.Info("Signed out");
            Stop(SignInToOpen);
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
    /// password is passed on for the one call and never kept.
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
                if (_cycle is not null && _api is not null && !SameServer(_api.Server, server))
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

        var settings = HelperSettings.Load(_paths);
        settings.Server = Address(server);
        settings.Save(_paths);
        SetSignedIn(api, await UserNameAsync(api));
        _log.Info($"Signed in to {LinkName(api)} as {user.Trim()}");
        Cycle?.RetryNow(); // after a 401: the saves that waited go now
        RaiseChanged();
        return null;
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

    /// <summary>Under <see cref="_lifecycle"/>: the copies' resume, then the channel, then links.</summary>
    private async Task BeginAsync()
    {
        HealdanApi api;
        lock (_gate)
        {
            if (_disposed)
                return; // quit while the Sign In window was up
            api = _api!;
        }
        var cycle = new CopyCycle(api, new CopyStore(_paths), _app, _device.Id, this, _timings);
        lock (_gate)
            _cycle = cycle; // before the resume, whose events name copies by their title
        await cycle.ResumeAsync();
        _log.Info($"Copies resumed: {cycle.OpenCopies().Count} looked after");

        var channel = new ChannelServer(_paths, new ChannelHandler(cycle, Links, _windows, _notifier, _log, _paths));
        try
        {
            channel.Start();
            _log.Info($"Channel listening on 127.0.0.1:{ChannelInfo.Read(_paths)?.Port}");
            bool quit;
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
            (channel, cycle) = (_channel, _cycle);
            (_channel, _cycle, _signedIn, _started, _userName, _waiting) = (null, null, false, false, null, 0);
            _crashed.Clear();
        }
        channel?.Stop();
        cycle?.Dispose();
        RaiseChanged();
    }

    /// <summary>Sign Out's flush: the waiting saves are tried now, and given a while to go up.</summary>
    private async Task FlushAsync(CopyCycle? cycle)
    {
        if (cycle is null || SavesWaiting == 0)
            return;
        cycle.RetryNow();
        var deadline = DateTime.UtcNow + SignOutFlush;
        while (SavesWaiting > 0 && DateTime.UtcNow < deadline)
            await Task.Delay(200);
        var left = SavesWaiting;
        if (left > 0)
            throw new InvalidOperationException(left == 1
                ? "A save is still waiting to go to Healdan, so you're still signed in. Sign out once it has gone."
                : $"{left} saves are still waiting to go to Healdan, so you're still signed in. Sign out once they have gone.");
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
        var copy = Cycle?.StateOf(path);
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

    void ICycleEvents.SignInNeeded()
    {
        lock (_gate)
        {
            if (_cycle is null)
                return; // signed out or quitting: a save that was going up meanwhile waits for the next start
            _signedIn = false;
        }
        _log.Warn("Healdan refused the token: asking for the sign-in again");
        _notifier.Notify(Helper, "Sign in to Healdan again. Your saves wait on this computer until you do.");
        RaiseChanged();
        _ = Task.Run(SignInAgainAsync);
    }

    void ICycleEvents.Error(string message)
    {
        _log.Warn(message);
        _notifier.Notify(Helper, message);
    }

    private string TitleOf(string path) =>
        Cycle?.StateOf(path) is { Known: true } state ? state.Title : Path.GetFileNameWithoutExtension(path);

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
