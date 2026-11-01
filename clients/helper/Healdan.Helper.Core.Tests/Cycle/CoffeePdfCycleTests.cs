using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// PDFs in CoffeePDF (M3.6 spec §5, §6.1, §7): M3's cycle with CoffeePDF as a copy's app, through the real
/// <see cref="CoffeePdfApp"/> with its launch and process check faked (<see cref="FakeCoffeePdf"/>).
/// </summary>
public sealed class CoffeePdfCycleTests : IDisposable
{
    /// <summary>The trial's timings shortened, in the same order, so the tests run fast.</summary>
    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    /// <summary>Long enough after a save for a second, wrong, upload to have arrived.</summary>
    private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(600);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-coffeepdf-cycle-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeApp _libreOffice = new();
    private readonly FakeOpener _opener = new();
    private readonly FakeCoffeePdf _coffee = new();
    private readonly SeenLocks _coffeePdf;
    private readonly RecordingEvents _events = new();
    private readonly List<CopyCycle> _cycles = [];

    /// <summary>Whether CoffeePDF is installed with Healdan support on, as the chooser reads it at each open.</summary>
    private volatile bool _available = true;

    public CoffeePdfCycleTests()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        _coffeePdf = new SeenLocks(_coffee.App);
    }

    public void Dispose()
    {
        foreach (var cycle in _cycles)
            cycle.Dispose();
        _server.OpenSaves();
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private CopyStore Store => new(Paths);

    private string CopyPath(string name) => Path.Combine(Paths.Copies, name);

    private string Pdf => CopyPath("Signed NDA #7 v1.0.pdf");

    private string ReadOnlyPdf => CopyPath("Signed NDA #7 v1.0 (read-only).pdf");

    private CopyCycle Cycle()
    {
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var apps = TestApps.Of(_libreOffice, _opener, appFor: name =>
            PdfFiles.Is(name) ? (_available ? CoffeePdfApp.AppName : DefaultApp.AppName)
            : FileKinds.OfFile(name) is null ? DefaultApp.AppName : LibreOfficeApp.AppName, coffeePdf: _coffeePdf);
        var cycle = new CopyCycle(api, new CopyStore(Paths), apps, FakeApi.HereDevice, _events, Fast);
        _cycles.Add(cycle);
        return cycle;
    }

    private static void AssertReadOnlyFile(string path)
    {
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead, File.GetUnixFileMode(path));
    }

    [Fact]
    public async Task APdfOpensInCoffeePdfCheckedOut()
    {
        var cycle = Cycle();

        await cycle.OpenAsync(7, false);

        Assert.Equal([Pdf], _coffee.Opened);
        Assert.Empty(_opener.Opened);
        Assert.Empty(_libreOffice.Opened);
        Assert.Equal("a pdf", File.ReadAllText(Pdf));
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold); // "checked out to you on … (CoffeePDF)"
        var record = Store.Find(Pdf)!.Value.Item2;
        Assert.Equal("CoffeePDF", record.App);
        Assert.True(record.CheckedOutHere);
        Assert.Equal(new CopyState(true, 7, "Signed NDA #7 v1.0", "#7 v1.0", false, Pdf, null), cycle.StateOf(Pdf));
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task APdfLinkOpensInCoffeePdf()
    {
        var cycle = Cycle();

        await cycle.OpenLinkAsync(7, false);

        Assert.Equal([Pdf], _coffee.Opened);
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task APdfLinkWithoutCoffeePdfOpensReadOnlyInTheDefaultApp()
    {
        _available = false;

        await Cycle().OpenLinkAsync(7, false);

        Assert.Equal([ReadOnlyPdf], _opener.Opened);
        Assert.Empty(_coffee.Opened);
        Assert.Equal(0, _server.Count("POST /documents/7/checkout"));
        AssertReadOnlyFile(ReadOnlyPdf);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task AReadOnlyOpenIsTrackedInCoffeePdf()
    {
        var cycle = Cycle();

        await cycle.OpenLinkAsync(7, true);

        Assert.Equal([ReadOnlyPdf], _coffee.Opened);
        Assert.Equal(0, _server.Count("POST /documents/7/checkout"));
        AssertReadOnlyFile(ReadOnlyPdf);
        Assert.False(File.Exists(FakeCoffeePdf.LockPath(ReadOnlyPdf))); // a read-only copy gets no lock file
        Assert.Equal([cycle.StateOf(ReadOnlyPdf)], cycle.OpenCopies()); // unlike the default app's, it has a session
    }

    [Fact]
    public async Task AReadOnlyCopyInCoffeePdfGoesWhenItsWindowCloses()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, true);

        Assert.True(await cycle.ClosedAsync(ReadOnlyPdf));

        await Wait.Until(() => !File.Exists(ReadOnlyPdf), "the read-only copy deleted");
        Assert.Null(Store.Find(ReadOnlyPdf));
        Assert.False(cycle.StateOf(ReadOnlyPdf).Known);
    }

    [Fact]
    public async Task AReadOnlyCopyGoesWhenCoffeePdfQuits()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, true);
        await Wait.Until(() => _coffeePdf.RunningSeen(ReadOnlyPdf), "the crash check sees CoffeePDF running");

        _coffee.Crash(); // gone with no lock file: for a read-only copy, a close

        await Wait.Until(() => !File.Exists(ReadOnlyPdf), "the read-only copy deleted");
        Assert.Empty(_events.Crashes);
    }

    [Fact]
    public async Task HeldByAnotherPersonItOpensReadOnlyInCoffeePdf()
    {
        _server.Change(7, d => d.Hold = FakeApi.OtherPerson());
        var cycle = Cycle();

        await cycle.OpenAsync(7, false);

        Assert.Equal([ReadOnlyPdf], _coffee.Opened);
        Assert.Equal("Pat Example", cycle.StateOf(ReadOnlyPdf).HoldBy); // the read-only bar names the person (spec §2.2)
        Assert.True(cycle.StateOf(ReadOnlyPdf).ReadOnly);
        AssertReadOnlyFile(ReadOnlyPdf);
    }

    [Fact]
    public async Task ACopyOpenWhenSupportIsTurnedOffStaysCoffeePdfs()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen");

        _available = false; // CoffeePDF §5: it takes effect for each copy as its window closes
        await cycle.OpenAsync(7, false);

        Assert.Equal([Pdf, Pdf], _coffee.Opened); // brought to the front, not opened in the default app
        Assert.Empty(_opener.Opened);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen again");
        FakeCoffeePdf.Close(Pdf);
        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
    }
}
