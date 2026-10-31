using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>
/// Pairing Word, Excel and PowerPoint with the helper (M3.5 spec §5.9): a six-digit code shown only in the helper's window,
/// two minutes and three tries to type it, one pairing at a time, and a random key for the add-in of which the helper keeps
/// only the hash.
/// </summary>
public sealed class PairingTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-pairing-").FullName;
    private readonly PausableClock _clock = new();
    private readonly FakeWindows _windows = new();

    public PairingTests() => _clock.Pause();

    public void Dispose() => Directory.Delete(_root, true);

    private HelperPaths Paths => HelperPaths.For(_root);

    private Pairing NewPairing() => new(Paths, _windows, _clock);

    private string Shown => _windows.ShownCode ?? throw new InvalidOperationException("No pairing code is shown.");

    /// <summary>
    /// The two times are the same moment. The stopped clock gives the time of day from two readings, so the same moment can
    /// come back a tick or two apart.
    /// </summary>
    private static void Same(DateTimeOffset expected, DateTimeOffset actual) =>
        Assert.InRange((actual - expected).Duration(), TimeSpan.Zero, TimeSpan.FromMilliseconds(5));

    /// <summary>A code that isn't <paramref name="code"/>.</summary>
    private static string Not(string code) => code == "000000" ? "111111" : "000000";

    [Fact]
    public void ACodeIsSixDigitsAndShown()
    {
        var pairing = NewPairing();

        var id = pairing.Start("Word");

        Assert.False(string.IsNullOrEmpty(id));
        var (app, code, expires) = Assert.Single(_windows.PairingCodes);
        Assert.Equal("Word", app);
        Assert.Matches("^[0-9]{6}$", code);
        Same(_clock.GetUtcNow() + TimeSpan.FromMinutes(2), expires);
        Assert.Equal(code, _windows.ShownCode);

        // Each pairing has a code of its own, drawn at random.
        var codes = Enumerable.Range(0, 20).Select(_ =>
        {
            pairing.Start("Word");
            return Shown;
        }).ToHashSet();
        Assert.True(codes.Count > 1);
    }

    [Fact]
    public void AnAppThatIsntWordExcelOrPowerPointIsRefused()
    {
        var pairing = NewPairing();

        Assert.Throws<ArgumentException>(() => pairing.Start("Your bank wants your password"));
        Assert.Throws<ArgumentException>(() => pairing.Start("word"));

        Assert.Empty(_windows.PairingCodes); // the window never shows a name it was sent
    }

    [Fact]
    public void TheRightCodeGivesAKey()
    {
        var pairing = NewPairing();
        var id = pairing.Start("Excel");
        var code = Shown;

        var key = Assert.IsType<PairResult.Key>(pairing.Finish(id, code));

        Assert.Matches("^[A-Za-z0-9_-]{43}$", key.Value); // 32 random bytes, base64url without padding
        Assert.Equal("Excel", key.App);
        Assert.Equal("Excel", pairing.AppFor(key.Value));
        Assert.Null(pairing.AppFor("not-a-key"));
        Assert.Null(pairing.AppFor(""));
        Assert.Null(_windows.ShownCode); // the window closes once paired
        Assert.Equal(1, _windows.PairingClosed);

        // A code works once.
        Assert.IsType<PairResult.Expired>(pairing.Finish(id, code));
    }

    [Fact]
    public void TheCodeMayBeTypedWithItsSpace()
    {
        var pairing = NewPairing();
        var id = pairing.Start("Word");
        var code = Shown;

        var key = Assert.IsType<PairResult.Key>(pairing.Finish(id, code[..3] + " " + code[3..]));

        Assert.Equal("Word", pairing.AppFor(key.Value));
    }

    [Fact]
    public void AKeyIsStoredOnlyAsItsHash()
    {
        var pairing = NewPairing();
        var id = pairing.Start("PowerPoint");
        var key = Assert.IsType<PairResult.Key>(pairing.Finish(id, Shown)).Value;

        var file = Path.Combine(_root, "addin-keys.json");
        Assert.Equal(file, Paths.AddinKeys);
        var text = File.ReadAllText(file);
        Assert.DoesNotContain(key, text);
        var hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(key)));
        var entry = Assert.Single(JsonNode.Parse(text)!["keys"]!.AsArray())!.AsObject();
        Assert.Equal(["hash", "app", "paired"], entry.Select(p => p.Key));
        Assert.Equal(hash, (string?)entry["hash"]);
        Assert.Equal("PowerPoint", (string?)entry["app"]);
        Same(_clock.GetUtcNow(), DateTimeOffset.Parse((string)entry["paired"]!, System.Globalization.CultureInfo.InvariantCulture));
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(file));
        Assert.Empty(Directory.GetFiles(_root, "*.tmp")); // written whole, then renamed into place
    }

    [Fact]
    public void ThreeWrongCodesCancel()
    {
        var pairing = NewPairing();
        var id = pairing.Start("Word");
        var code = Shown;

        Assert.Equal(2, Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, Not(code))).TriesLeft);
        Assert.Equal(1, Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, "not even digits")).TriesLeft);
        Assert.Equal(code, _windows.ShownCode); // still up for the third try
        Assert.Equal(0, Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, Not(code))).TriesLeft);

        Assert.Null(_windows.ShownCode); // cancelled
        Assert.IsType<PairResult.Expired>(pairing.Finish(id, code)); // the right code is no use now
        Assert.Empty(pairing.Paired());
        Assert.False(File.Exists(Paths.AddinKeys));
    }

    [Fact]
    public void ManyWrongCodesInARowRestPairing()
    {
        // Another user of the Mac can start pairing again and again, three guesses each time, faster than anyone could
        // type: nine wrong codes in a row, across pairings, and pairing rests for ten minutes.
        Assert.Equal((9, TimeSpan.FromMinutes(10)), (Pairing.WrongCodesBeforeRest, Pairing.Rest));
        var pairing = NewPairing();
        for (var pairings = 0; pairings < 4; pairings++)
        {
            var id = pairing.Start("Word"); // a new pairing every second guess, so none reaches its third
            var code = Shown;
            Assert.Equal(2, Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, Not(code))).TriesLeft);
            Assert.Equal(1, Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, Not(code))).TriesLeft);
        }
        var last = pairing.Start("Word");
        var lastCode = Shown;
        Assert.Equal(0, Assert.IsType<PairResult.WrongCode>(pairing.Finish(last, Not(lastCode))).TriesLeft); // the ninth

        Assert.Null(_windows.ShownCode);
        Assert.IsType<PairResult.Expired>(pairing.Finish(last, lastCode));
        var shown = _windows.PairingCodes.Count;
        Assert.Throws<PairingRestingException>(() => pairing.Start("Word"));
        Assert.Equal(shown, _windows.PairingCodes.Count); // no window while it rests

        _clock.Advance(TimeSpan.FromMinutes(9));
        Assert.Throws<PairingRestingException>(() => pairing.Start("Word"));
        _clock.Advance(TimeSpan.FromMinutes(1));
        var again = pairing.Start("Word");
        Assert.IsType<PairResult.Key>(pairing.Finish(again, Shown));

        // A pairing that works starts the count again.
        for (var guess = 0; guess < 8; guess++)
        {
            var id = pairing.Start("Excel");
            Assert.IsType<PairResult.WrongCode>(pairing.Finish(id, Not(Shown)));
        }
        Assert.IsType<PairResult.Key>(pairing.Finish(pairing.Start("Excel"), Shown));
        Assert.IsType<PairResult.WrongCode>(pairing.Finish(pairing.Start("Excel"), Not(Shown)));
        pairing.Start("Excel"); // not resting: one wrong code since
    }

    [Fact]
    public void ACodeExpiresAfterTwoMinutes()
    {
        var pairing = NewPairing();
        var first = pairing.Start("Word");
        _clock.Advance(TimeSpan.FromSeconds(119));
        Assert.IsType<PairResult.Key>(pairing.Finish(first, Shown)); // in time

        var second = pairing.Start("Word");
        var code = Shown;
        _clock.Advance(TimeSpan.FromMinutes(2));

        Assert.IsType<PairResult.Expired>(pairing.Finish(second, code));
        Assert.Null(_windows.ShownCode);
        Assert.Single(pairing.Paired()); // the first pairing's key only
    }

    [Fact]
    public void OnePairingAtATime()
    {
        var pairing = NewPairing();
        var first = pairing.Start("Word");
        var firstCode = Shown;

        var second = pairing.Start("Excel");
        var secondCode = Shown;

        Assert.Equal(1, _windows.PairingClosed); // the first one's window went before the second's came
        Assert.Equal(["Word", "Excel"], _windows.PairingCodes.Select(c => c.App));
        Assert.IsType<PairResult.Expired>(pairing.Finish(first, firstCode));
        var key = Assert.IsType<PairResult.Key>(pairing.Finish(second, secondCode));
        Assert.Equal("Excel", pairing.AppFor(key.Value));
    }

    [Fact]
    public void CancelEndsThePairing()
    {
        var pairing = NewPairing();
        var id = pairing.Start("Word");
        var code = Shown;

        // The window of a pairing a newer one replaced is cancelled late: the newer one goes on.
        pairing.Cancel(Not(code));
        Assert.Equal(code, _windows.ShownCode);

        pairing.Cancel(code); // its own window's Cancel

        Assert.Null(_windows.ShownCode);
        Assert.IsType<PairResult.Expired>(pairing.Finish(id, code));
        pairing.Cancel(); // nothing to cancel
        Assert.Equal(1, _windows.PairingClosed);

        // The helper stopping ends any pairing.
        var again = pairing.Start("Word");
        pairing.Cancel();
        Assert.IsType<PairResult.Expired>(pairing.Finish(again, code));
    }

    [Fact]
    public void ForgetRemovesTheAppsKeys()
    {
        var pairing = NewPairing();
        var word = PairApp(pairing, "Word");
        _clock.Advance(TimeSpan.FromDays(3));
        var wordAgain = PairApp(pairing, "Word"); // after Office lost the first key, say
        var excel = PairApp(pairing, "Excel");

        var paired = pairing.Paired();
        Assert.Equal(["Word", "Excel"], paired.Select(p => p.App)); // each app once, as Settings lists them
        Assert.All(paired, p => Same(_clock.GetUtcNow(), p.Paired)); // Word's latest pairing, not its first

        pairing.Forget("Word");

        Assert.Null(pairing.AppFor(word));
        Assert.Null(pairing.AppFor(wordAgain));
        Assert.Equal("Excel", pairing.AppFor(excel));
        Assert.Equal(["Excel"], pairing.Paired().Select(p => p.App));
    }

    [Fact]
    public void KeysSurviveARestart()
    {
        var key = PairApp(NewPairing(), "Word");
        var paired = _clock.GetUtcNow();

        var restarted = NewPairing();

        Assert.Equal("Word", restarted.AppFor(key));
        var app = Assert.Single(restarted.Paired());
        Assert.Equal("Word", app.App);
        Same(paired, app.Paired);
    }

    [Fact]
    public void ADamagedKeysFileIsNoKeys()
    {
        Directory.CreateDirectory(_root);
        File.WriteAllText(Paths.AddinKeys, "{ not json");
        var pairing = NewPairing();

        Assert.Null(pairing.AppFor("anything"));
        Assert.Empty(pairing.Paired());
        var key = PairApp(pairing, "Word"); // pairing again writes a good file

        Assert.Equal("Word", NewPairing().AppFor(key));
    }

    private string PairApp(Pairing pairing, string app)
    {
        var id = pairing.Start(app);
        return Assert.IsType<PairResult.Key>(pairing.Finish(id, Shown)).Value;
    }
}
