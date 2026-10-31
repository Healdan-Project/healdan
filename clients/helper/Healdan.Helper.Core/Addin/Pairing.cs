using System.Buffers.Text;
using System.Security.Cryptography;
using System.Text;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Addin;

/// <summary>How a pairing's <see cref="Pairing.Finish"/> went.</summary>
public abstract record PairResult
{
    private PairResult()
    {
    }

    /// <summary>
    /// The right code: <paramref name="Value"/> is the add-in's key for <paramref name="App"/>, which it sends with every
    /// request.
    /// </summary>
    public sealed record Key(string Value, string App) : PairResult;

    /// <summary>A wrong code; with no tries left, the pairing is over.</summary>
    public sealed record WrongCode(int TriesLeft) : PairResult;

    /// <summary>No pairing goes by that id: it expired, was used, was cancelled, or another one replaced it.</summary>
    public sealed record Expired : PairResult;
}

/// <summary>
/// Pairing rests after <see cref="Pairing.WrongCodesBeforeRest"/> wrong codes in a row, for <see cref="Pairing.Rest"/>:
/// nothing is shown meanwhile.
/// </summary>
public sealed class PairingRestingException(TimeSpan left)
    : InvalidOperationException("Too many wrong pairing codes in a row: pairing rests for a while.")
{
    /// <summary>How long it rests for yet.</summary>
    public TimeSpan Left { get; } = left;
}

/// <summary>An app paired with the helper, for Settings: when it last paired.</summary>
public sealed record PairedApp(string App, DateTimeOffset Paired);

/// <summary>
/// Pairing Word, Excel and PowerPoint with the helper (M3.5 spec §5.9, §9). The first time a Healdan button is pressed in
/// an app, the add-in asks to pair, and the helper's window shows a six-digit code, which the user types into the add-in's
/// pane. The right code gives the add-in a random key for that app, which it keeps and sends with every request.
/// <para>The code is drawn at random, shown only in the helper's window, and works for <see cref="Lifetime"/> and
/// <see cref="Tries"/> tries. One pairing at a time: a new one cancels the one before, and its window. The helper keeps
/// only each key's hash (<see cref="AddinKeys"/>), so nothing on disk opens the port. Another user of the Mac can make the
/// helper show a code, but can't see it.</para>
/// <para>Nor can they guess it by starting pairing over and over, three guesses each time, faster than anyone types:
/// after <see cref="WrongCodesBeforeRest"/> wrong codes in a row, whichever pairings they were for, pairing rests for
/// <see cref="Rest"/>, so a million codes take years to try. A pairing that works starts the count again.</para>
/// <para>Nothing here logs: the caller logs what happened, never a code or a key.</para>
/// </summary>
/// <param name="clock">
/// The real clock in the helper; tests pass one they can move on. Expiry and rest go by its timer, which the time of
/// day being changed doesn't move; the window is told when its code expires by the time of day.
/// </param>
public sealed class Pairing(HelperPaths paths, IWindows windows, TimeProvider clock)
{
    /// <summary>How long a code can be typed for.</summary>
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(2);

    /// <summary>How many wrong codes cancel a pairing.</summary>
    public const int Tries = 3;

    /// <summary>How many wrong codes in a row, across pairings, make pairing rest.</summary>
    public const int WrongCodesBeforeRest = 9;

    /// <summary>How long pairing rests after <see cref="WrongCodesBeforeRest"/> wrong codes in a row.</summary>
    public static readonly TimeSpan Rest = TimeSpan.FromMinutes(10);

    /// <summary>A key's random bytes.</summary>
    private const int KeyBytes = 32;

    /// <summary>Longer than any key the helper gives, so it's no key: nothing longer is hashed.</summary>
    private const int MaxKeyLength = 256;

    private readonly object _gate = new();
    private Pending? _pending;
    private int _wrongInARow;

    /// <summary>When pairing began to rest, on the clock's timer (<see cref="TimeProvider.GetTimestamp"/>); null while it doesn't.</summary>
    private long? _restingSince;

    /// <summary>The apps that can pair, by the names Office's add-in host gives them: Word, Excel and PowerPoint.</summary>
    public static IReadOnlyList<string> Apps { get; } = [.. Enum.GetValues<OfficeProgram>().Select(OfficePrograms.CheckOutName)];

    /// <summary>
    /// Starts pairing <paramref name="app"/>: cancels any pairing under way, draws a code and shows it in the helper's
    /// window. Returns the id <see cref="Finish"/> names this pairing by, which isn't the code.
    /// </summary>
    /// <exception cref="ArgumentException"><paramref name="app"/> isn't Word, Excel or PowerPoint: nothing is shown.</exception>
    /// <exception cref="PairingRestingException">Too many wrong codes in a row, a moment ago: nothing is shown.</exception>
    public string Start(string app)
    {
        if (!Apps.Contains(app))
            throw new ArgumentException("Only Word, Excel and PowerPoint pair with the helper.", nameof(app));
        var pending = new Pending(
            Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16)),
            app,
            RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture),
            clock.GetTimestamp());
        lock (_gate)
        {
            if (_restingSince is { } since && clock.GetElapsedTime(since) < Rest)
                throw new PairingRestingException(Rest - clock.GetElapsedTime(since));
            _restingSince = null;
            // In the lock, so the window always shows the code of the pairing under way.
            if (_pending is not null)
                windows.ClosePairingCode();
            _pending = pending;
            windows.ShowPairingCode(app, pending.Code, clock.GetUtcNow() + Lifetime);
        }
        return pending.Id;
    }

    /// <summary>
    /// The code the user typed for the pairing <paramref name="pairingId"/>: the right one gives the add-in its key, which is
    /// kept from then on (as its hash); a wrong one uses up a try, and the last cancels the pairing. The window closes once
    /// the pairing is over, whichever way. A space in the code, as the window shows it, doesn't count.
    /// </summary>
    public PairResult Finish(string pairingId, string code)
    {
        lock (_gate)
        {
            var pending = _pending;
            if (pending is null || pending.Id != pairingId)
                return new PairResult.Expired();
            if (clock.GetElapsedTime(pending.Started) >= Lifetime)
            {
                End();
                return new PairResult.Expired();
            }
            var typed = Encoding.UTF8.GetBytes(code.Replace(" ", "", StringComparison.Ordinal));
            if (!CryptographicOperations.FixedTimeEquals(typed, Encoding.UTF8.GetBytes(pending.Code)))
            {
                pending.TriesLeft--;
                if (++_wrongInARow >= WrongCodesBeforeRest)
                    (_wrongInARow, _restingSince, pending.TriesLeft) = (0, clock.GetTimestamp(), 0);
                if (pending.TriesLeft == 0)
                    End();
                return new PairResult.WrongCode(pending.TriesLeft);
            }
            _wrongInARow = 0;
            var key = Base64Url.EncodeToString(RandomNumberGenerator.GetBytes(KeyBytes));
            AddinKeys.Save(paths, [.. AddinKeys.Load(paths), new AddinKey(AddinKeys.Hash(key), pending.App, clock.GetUtcNow())]);
            End();
            return new PairResult.Key(key, pending.App);
        }
    }

    /// <summary>
    /// The app a key was paired for, or null when it's no key the helper gave or one it has forgotten. Each kept hash is
    /// compared in fixed time, so how long the answer takes says nothing of how close a guess came. Read from the file each
    /// time, so a key Settings forgets stops working at once.
    /// </summary>
    public string? AppFor(string key)
    {
        if (string.IsNullOrEmpty(key) || key.Length > MaxKeyLength)
            return null;
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(key));
        string? app = null;
        foreach (var kept in AddinKeys.Load(paths))
        {
            byte[] keptHash;
            try
            {
                keptHash = Convert.FromHexString(kept.Hash);
            }
            catch (FormatException)
            {
                continue; // a damaged entry opens nothing
            }
            if (CryptographicOperations.FixedTimeEquals(hash, keptHash))
                app ??= kept.App;
        }
        return app;
    }

    /// <summary>The apps paired, each once with when it last paired, in the order Word, Excel, PowerPoint: for Settings.</summary>
    public IReadOnlyList<PairedApp> Paired()
    {
        var keys = AddinKeys.Load(paths);
        return [.. Apps.Where(a => keys.Any(k => k.App == a)).Select(a => new PairedApp(a, keys.Where(k => k.App == a).Max(k => k.Paired)))];
    }

    /// <summary>Settings' Forget: every key <paramref name="app"/> was given stops working. It pairs again at its next request.</summary>
    public void Forget(string app)
    {
        lock (_gate)
        {
            var keys = AddinKeys.Load(paths);
            if (keys.Any(k => k.App == app))
                AddinKeys.Save(paths, keys.Where(k => k.App != app));
        }
    }

    /// <summary>
    /// The helper stopped, or the user cancelled the pairing window showing <paramref name="code"/>: the pairing under way,
    /// if any, is over. A window's Cancel names its code, so one that a newer pairing has just replaced ends nothing.
    /// </summary>
    public void Cancel(string? code = null)
    {
        lock (_gate)
        {
            if (_pending is not null && (code is null || code == _pending.Code))
                End();
        }
    }

    /// <summary>Under the lock: the pairing under way is over, and its window goes.</summary>
    private void End()
    {
        _pending = null;
        windows.ClosePairingCode();
    }

    /// <summary>The pairing under way: its id, its app, its code, when it started (on the clock's timer) and the tries it has left.</summary>
    private sealed class Pending(string id, string app, string code, long started)
    {
        public string Id { get; } = id;

        public string App { get; } = app;

        public string Code { get; } = code;

        public long Started { get; } = started;

        public int TriesLeft { get; set; } = Tries;
    }
}
