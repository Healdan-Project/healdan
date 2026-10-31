using System.Security.Cryptography;
using System.Text;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Office;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// A cycle with Microsoft Office for Word's, Excel's and PowerPoint's files, over <see cref="FakeScripting"/>, and
/// LibreOffice for the rest, as the helper runs on a Mac with both. Document 5 is <c>Lease agreement.docx</c>.
/// </summary>
public abstract class OfficeCycleTestBase : IDisposable
{
    /// <summary>The trial's timings shortened, in the same order, so the tests run fast.</summary>
    protected static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    /// <summary>Long enough after a save for a second, wrong, upload to have arrived.</summary>
    protected static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(600);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-office-").FullName;
    private readonly List<CopyCycle> _cycles = [];

    protected FakeApi Server { get; } = new();

    protected FakeApp LibreOffice { get; } = new();

    protected FakeScripting Scripting { get; } = new();

    protected SeenLocks Office { get; }

    protected RecordingEvents Events { get; } = new();

    protected FakeNotifier Notifier { get; } = new();

    protected OfficeCycleTestBase()
    {
        Server.Add(5, "Lease agreement.docx", "downloaded");
        Office = new SeenLocks(new MicrosoftOfficeApp(Scripting, Scripting.IsRunning));
    }

    public void Dispose()
    {
        foreach (var cycle in _cycles)
            cycle.Dispose();
        Server.OpenSaves();
        Directory.Delete(_root, true);
        GC.SuppressFinalize(this);
    }

    protected HelperPaths Paths => HelperPaths.For(_root);

    protected CopyStore Store => new(Paths);

    protected string CopyPath(string name) => Path.Combine(Paths.Copies, name);

    /// <summary>A cycle as the helper makes one, telling <see cref="Notifier"/> and logging to the helper's log.</summary>
    protected CopyCycle Cycle(CycleTimings? timings = null)
    {
        var api = new HealdanApi(new Uri("https://healdan.test"), Server) { Token = "hd_test" };
        var apps = TestApps.Of(LibreOffice, microsoftOffice: Office,
            appFor: name => OfficePrograms.For(name) is null ? "LibreOffice" : MicrosoftOfficeApp.AppName);
        var office = new OfficeAfterSave(new CopiesNotice(Paths, Notifier), new HelperLog(Paths.Log));
        var cycle = new CopyCycle(api, new CopyStore(Paths), apps, FakeApi.HereDevice, Events, timings ?? Fast, office);
        _cycles.Add(cycle);
        return cycle;
    }

    /// <summary>Opens a document for editing in a new cycle, and returns the cycle and the copy it opened.</summary>
    protected async Task<(CopyCycle Cycle, string Path)> OpenAsync(long docNumber = 5, string name = "Lease agreement #5 v1.0.docx",
        CycleTimings? timings = null)
    {
        var cycle = Cycle(timings);
        await cycle.OpenAsync(docNumber, false);
        Assert.Empty(Events.Errors);
        var path = CopyPath(name);
        Assert.Equal(path, Scripting.Opened[^1].Path);
        return (cycle, path);
    }

    /// <summary>The program saves: the file is written beside the copy and renamed over it, so it's never seen half written.</summary>
    protected static void Save(string path, string content) => FakeApp.WriteThenRename(path, content);

    /// <summary>Waits until the cycle looks after the copy at <paramref name="path"/>: renamed there, with its record beside it.</summary>
    protected static Task RenamedTo(CopyCycle cycle, string path) =>
        Wait.Until(() => cycle.OpenCopies().Any(c => c.Path == path), "the copy renamed to " + Path.GetFileName(path));

    /// <summary>Waits until the close watcher has seen the copy's owner file and the crash check has seen its program running.</summary>
    protected Task WatchedAsync(string path) =>
        Wait.Until(() => Office.LockSeen(path) && Office.RunningSeen(path), "the copy seen open, and its program running");

    /// <summary>Waits until the close watcher has looked at the copy's owner file <paramref name="looks"/> more times.</summary>
    protected async Task LooksAsync(string path, int looks = 4)
    {
        var asked = Office.Asked(path);
        await Wait.Until(() => Office.Asked(path) >= asked + looks, $"{looks} more looks at {Path.GetFileName(path)}'s owner file");
    }

    protected static string Hash(string text) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text)));

    /// <summary>Whether the copy's save waiting for the one going up holds <paramref name="content"/>.</summary>
    protected static bool Queued(CopyCycle cycle, string path, string content) =>
        string.Equals(cycle.QueuedHash(path), Hash(content), StringComparison.OrdinalIgnoreCase);

    /// <summary>The helper's log, or nothing before its first line.</summary>
    protected string Log => File.Exists(Paths.Log) ? File.ReadAllText(Paths.Log) : "";
}
