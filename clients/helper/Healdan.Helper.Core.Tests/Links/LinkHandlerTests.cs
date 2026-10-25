using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Links;

public sealed class LinkHandlerTests : IDisposable
{
    private static readonly Uri Server = new("https://healdan.test");

    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-links-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeApp _app = new();
    private readonly RecordingEvents _events = new();
    private readonly FakeWindows _windows = new();
    private readonly FakeNotifier _notifier = new();
    private readonly CopyCycle _cycle;
    private readonly LinkHandler _links;

    public LinkHandlerTests()
    {
        _server.Add(5, "Lease agreement.docx", "downloaded");
        var paths = HelperPaths.For(_root);
        var api = new HealdanApi(Server, _server) { Token = "hd_test" };
        _cycle = new CopyCycle(api, new CopyStore(paths), _app, FakeApi.HereDevice, _events, Fast);
        _links = new LinkHandler(_windows, _notifier, new HelperLog(paths.Log));
    }

    public void Dispose()
    {
        _cycle.Dispose();
        Directory.Delete(_root, true);
    }

    private string CopyPath(string name) => Path.Combine(HelperPaths.For(_root).Copies, name);

    [Fact]
    public async Task OpensFromItsOwnServer()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5?server=healdan.test");

        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
        Assert.Equal(FakeApi.Here(), _server.Doc(5).Hold);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task OpensReadOnlyWhenAsked()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5?server=healdan.test&readonly=1");

        Assert.Equal([CopyPath("Lease agreement #5 v1.0 (read-only).docx")], _app.Opened);
        Assert.Null(_server.Doc(5).Hold);
        Assert.Empty(_notifier.Notices); // the user asked for read-only: nothing to say
    }

    [Fact]
    public async Task SaysWhoHoldsADocumentOpenedReadOnly()
    {
        _server.Change(5, d => d.Hold = FakeApi.OtherPerson());
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5?server=healdan.test");

        Assert.Equal([CopyPath("Lease agreement #5 v1.0 (read-only).docx")], _app.Opened);
        Assert.Contains("read-only", _notifier.All);
        Assert.Contains("Pat Example", _notifier.All);
    }

    [Fact]
    public async Task RefusesAnotherServerNamingItsOwn()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5?server=evil.example");

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Contains("healdan.test", _notifier.All);
        Assert.Contains("evil.example", _notifier.All);
    }

    [Fact]
    public async Task RefusesAnotherPortOnTheSameHost()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5?server=healdan.test:8443");

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Contains("healdan.test", _notifier.All);
    }

    [Fact]
    public async Task RefusesALinkWithoutItsServer()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://open/5");

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Single(_notifier.Notices);
    }

    [Fact]
    public async Task IgnoresLinksThatDoMoreThanOpen()
    {
        await _links.Ready(Server, _cycle);
        await _links.Handle("healdan://save/5?server=healdan.test");

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Contains("only opens", _notifier.All);
    }

    [Fact]
    public async Task DropsLinksWhileAWindowWaits()
    {
        await _links.Ready(Server, _cycle);
        _windows.IsWaiting = true;
        await _links.Handle("healdan://open/5?server=healdan.test");

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Empty(_notifier.Notices);
        Assert.Equal(1, _windows.Brought); // the window that's waiting comes up, so the user sees why

        _windows.IsWaiting = false;
        await _links.Handle("healdan://open/5?server=healdan.test");
        Assert.Single(_app.Opened);
    }

    [Fact]
    public async Task HoldsTheFirstLinkUntilReady()
    {
        // Clicking a link starts the helper on macOS, so it arrives before the sign-in and the copies' resume.
        await _links.Handle("healdan://open/5?server=healdan.test");
        await _links.Handle("healdan://open/6?server=healdan.test"); // a second while starting is dropped
        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);

        await _links.Ready(Server, _cycle);

        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
        Assert.DoesNotContain(_server.Calls, c => c.Contains("/documents/6"));
    }

    [Fact]
    public async Task HoldsTheLinkThatStartedTheHelperWhileTheSignInWindowWaits()
    {
        // First run: the link starts the helper, which has no token yet and shows Sign In, possibly before macOS
        // delivers the link. The window is part of getting ready, so the link waits for it rather than being dropped.
        _windows.IsWaiting = true;
        await _links.Handle("healdan://open/5?server=healdan.test");
        _windows.IsWaiting = false;

        await _links.Ready(Server, _cycle);

        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
    }

    [Fact]
    public async Task DropsLinksAfterSignOut()
    {
        await _links.Ready(Server, _cycle);
        _links.NotReady("Sign in to Healdan Helper to open documents."); // signed out
        await _links.Handle("healdan://open/5?server=healdan.test");

        Assert.Contains("Sign in", _notifier.All);
        await _links.Ready(Server, _cycle); // signed in again, maybe hours later: the link isn't opened then
        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
    }

    [Fact]
    public async Task DropsTheHeldLinkWhenTheStartUpSignInIsDismissed()
    {
        await _links.Handle("healdan://open/5?server=healdan.test"); // the link that started the helper
        _links.NotReady("Sign in to Healdan Helper to open documents."); // the user closed Sign In

        await _links.Handle("healdan://open/6?server=healdan.test");
        await _links.Ready(Server, _cycle); // signing in from the menu later opens neither

        Assert.Empty(_app.Opened);
        Assert.Empty(_server.Calls);
        Assert.Contains("Sign in", _notifier.All);
    }
}
