using System.IO.Compression;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>
/// Installing or updating the LibreOffice extension at start (spec §5.12): unopkg first, else the <c>.oxt</c> opened in
/// LibreOffice for the user to accept; never unopkg beside a running LibreOffice; once per helper version unless the
/// installed one is still older; and which way worked is recorded.
/// </summary>
public sealed class ExtensionCheckTests : IDisposable
{
    private const string Helper = "0.2.0";

    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-check-").FullName;
    private readonly FakeTools _tools = new();
    private readonly FakeNotifier _notifier = new();
    private readonly HelperPaths _paths;
    private readonly HelperLog _log;

    public ExtensionCheckTests()
    {
        _paths = HelperPaths.For(_dir);
        _log = new HelperLog(_paths.Log);
    }

    public void Dispose() => Directory.Delete(_dir, true);

    [Fact]
    public async Task ACurrentExtensionIsLeftAloneAndRecorded()
    {
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.Current, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Empty(_tools.Added);
        Assert.Equal(0, _tools.Opened);
        var record = Record();
        Assert.Equal("0.2.0", record.Version);
        Assert.Equal("found", record.Way);
        Assert.Equal(Helper, record.CheckedFor);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AnOlderOneIsUpdatedByUnopkg()
    {
        _tools.Lists.Enqueue(Listing("0.1.0", registered: true));
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.InstalledByUnopkg, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Single(_tools.Added);
        Assert.Equal(0, _tools.Opened);
        Assert.Equal(("0.2.0", "unopkg", Helper), (Record().Version, Record().Way, Record().CheckedFor));
        Assert.Contains("installed by unopkg", File.ReadAllText(_paths.Log));
        Assert.Empty(_notifier.Notices); // quiet when it works
    }

    [Fact]
    public async Task AFailedUnopkgOpensTheOxtInLibreOffice()
    {
        _tools.Lists.Enqueue(Listing(null, registered: false));
        _tools.AddFailure = "couldn't connect to pipe";
        _tools.Lists.Enqueue(Listing("0.2.0", registered: false)); // a failed add leaves it unregistered

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Single(_tools.Added);
        Assert.Equal(1, _tools.Removed); // so LibreOffice's own install is a plain one
        Assert.Equal(1, _tools.Opened);
        var record = Record();
        Assert.Equal(Helper, record.UnopkgFailedFor);
        Assert.Equal("0.2.0", record.OpenedFor);
        Assert.Null(record.CheckedFor);
        Assert.Contains("OK", _notifier.All);
        Assert.Contains("couldn't connect to pipe", File.ReadAllText(_paths.Log));
    }

    [Fact]
    public async Task TheNextStartConfirmsWhatTheUserAccepted()
    {
        Save(new ExtensionRecord { OpenedFor = "0.2.0", UnopkgFailedFor = Helper });
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.Current, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Empty(_tools.Added);
        Assert.Equal(0, _tools.Opened);
        var record = Record();
        Assert.Equal(("0.2.0", "LibreOffice", Helper), (record.Version, record.Way, record.CheckedFor));
        Assert.Null(record.OpenedFor);
    }

    [Fact]
    public async Task UnopkgIsTriedOncePerHelperVersion()
    {
        Save(new ExtensionRecord { OpenedFor = "0.2.0", UnopkgFailedFor = Helper });
        _tools.Lists.Enqueue(Listing(null, registered: false));

        // Still older (the user didn't accept it): opened again, without another unopkg attempt.
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Empty(_tools.Added);
        Assert.Equal(1, _tools.Opened);
    }

    [Fact]
    public async Task ACheckedVersionIsNotCheckedAgain()
    {
        Save(new ExtensionRecord { Version = "0.2.0", Way = "unopkg", CheckedFor = Helper });

        Assert.Equal(ExtensionOutcome.Current, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Equal(0, _tools.Listed);
        Assert.Equal(0, _tools.RunningChecks);
    }

    [Fact]
    public async Task ANewHelperVersionChecksAgain()
    {
        Save(new ExtensionRecord { Version = "0.1.0", Way = "unopkg", CheckedFor = "0.1.0", UnopkgFailedFor = "0.1.0" });
        _tools.Lists.Enqueue(Listing("0.1.0", registered: true));
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.InstalledByUnopkg, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Single(_tools.Added); // the old version's unopkg failure doesn't count for this one
    }

    [Fact]
    public async Task ARunningLibreOfficeGetsTheOxtNeverUnopkg()
    {
        // Beside a running LibreOffice, unopkg list waited minutes on this Mac and then listed nothing.
        Save(new ExtensionRecord { Version = "0.1.0", Way = "unopkg", CheckedFor = "0.1.0" });
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Equal(0, _tools.Listed);
        Assert.Empty(_tools.Added);
        Assert.Equal(0, _tools.Removed);
        Assert.Equal(1, _tools.Opened);
        Assert.Equal("0.2.0", Record().OpenedFor);
    }

    [Fact]
    public async Task ARunningLibreOfficeIsAskedOnlyOnce()
    {
        Save(new ExtensionRecord { Version = "0.1.0", OpenedFor = "0.2.0" });
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.AwaitingLibreOffice, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Equal(0, _tools.Opened);
        Assert.Equal(0, _tools.Listed);
    }

    [Fact]
    public async Task ARunningLibreOfficeWithTheRecordedVersionIsCurrent()
    {
        Save(new ExtensionRecord { Version = "0.2.0", Way = "unopkg", CheckedFor = "0.1.0" });
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.Current, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Equal(0, _tools.Opened);
        Assert.Equal(Helper, Record().CheckedFor);
    }

    [Fact]
    public async Task NothingOpensWhileAWindowWaits()
    {
        _tools.Lists.Enqueue(Listing(null, registered: false));
        _tools.AddFailure = "couldn't connect to pipe";
        var waiting = true;
        var check = Check(() => Volatile.Read(ref waiting));

        var run = check.RunAsync(Oxt("0.2.0"));
        await Task.Delay(100);
        Assert.Equal(0, _tools.Opened);
        Volatile.Write(ref waiting, false);

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await run);
        Assert.Equal(1, _tools.Opened);
    }

    [Fact]
    public async Task WithoutLibreOfficeTheUserIsTold()
    {
        _tools.Installed = false;

        Assert.Equal(ExtensionOutcome.NoLibreOffice, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Contains("LibreOffice isn't installed", _notifier.All);
        Assert.Equal(0, _tools.Listed);
    }

    [Fact]
    public async Task ABuildWithoutTheOxtDoesNothing()
    {
        Assert.Equal(ExtensionOutcome.NotBundled, await Check().RunAsync(null));
        Assert.Equal(ExtensionOutcome.NotBundled, await Check().RunAsync(Path.Combine(_dir, "missing.oxt")));

        Assert.Equal(0, _tools.RunningChecks);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AnOpenThatFailsIsTold()
    {
        _tools.Lists.Enqueue(Listing(null, registered: false));
        _tools.AddFailure = "couldn't connect to pipe";
        _tools.OpenFailure = new IOException("open exited with 1");

        Assert.Equal(ExtensionOutcome.Failed, await Check().RunAsync(Oxt("0.2.0")));

        Assert.Contains("couldn't be installed", _notifier.All);
        Assert.Null(Record().OpenedFor);
    }

    [Fact]
    public void SettingsSayWhichWayWorked()
    {
        var at = new DateTimeOffset(new DateTime(2026, 10, 25, 12, 0, 0, DateTimeKind.Local)); // shown in local time
        Assert.Equal("Version 0.2.0, installed by Healdan Helper on 25 October 2026.",
            new ExtensionRecord { Version = "0.2.0", Way = "unopkg", At = at }.Describe());
        Assert.Equal("Version 0.2.0, installed in LibreOffice on 25 October 2026.",
            new ExtensionRecord { Version = "0.2.0", Way = "LibreOffice", At = at }.Describe());
        Assert.Equal("Version 0.2.0, installed.", new ExtensionRecord { Version = "0.2.0", Way = "found", At = at }.Describe());
        Assert.Equal("Waiting for you to accept version 0.3.0 in LibreOffice. Version 0.2.0 is installed.",
            new ExtensionRecord { Version = "0.2.0", Way = "unopkg", At = at, OpenedFor = "0.3.0" }.Describe());
        Assert.Equal("Waiting for you to accept version 0.3.0 in LibreOffice.", new ExtensionRecord { OpenedFor = "0.3.0" }.Describe());
        Assert.Equal("Not installed yet.", new ExtensionRecord().Describe());
    }

    private ExtensionCheck Check(Func<bool>? waiting = null) =>
        new(_tools, _paths, _notifier, _log, Helper, waiting ?? (() => false), poll: TimeSpan.FromMilliseconds(10));

    private ExtensionRecord Record() => HelperSettings.Load(_paths).Extension ?? new ExtensionRecord();

    private void Save(ExtensionRecord record) => HelperSettings.Update(_paths, s => s.Extension = record);

    private string Oxt(string version)
    {
        var path = Path.Combine(_dir, $"healdan-{version}.oxt");
        if (File.Exists(path))
            return path;
        using var zip = ZipFile.Open(path, ZipArchiveMode.Create);
        using var writer = new StreamWriter(zip.CreateEntry("description.xml").Open());
        writer.Write($"""
            <description xmlns="http://openoffice.org/extensions/description/2006">
              <identifier value="org.healdan.libreoffice"/>
              <version value="{version}"/>
            </description>
            """);
        return path;
    }

    /// <summary><c>unopkg list</c>'s layout (see <see cref="ExtensionVersionTests"/>), with Healdan's extension or none.</summary>
    private static string Listing(string? version, bool registered) => version is null
        ? "All deployed user extensions:\n\n<none>\n"
        : $"All deployed user extensions:\n\nIdentifier: org.healdan.libreoffice\n  Version: {version}\n  is registered: {(registered ? "yes" : "no")}\n\n";

    private sealed class FakeTools : IExtensionTools
    {
        public Queue<string> Lists { get; } = new();
        public List<string> Added { get; } = [];
        public string? AddFailure { get; set; }
        public Exception? OpenFailure { get; set; }
        public bool Installed { get; set; } = true;
        public bool Running { get; set; }
        public int Listed { get; private set; }
        public int Removed { get; private set; }
        public int Opened { get; private set; }
        public int RunningChecks { get; private set; }

        public bool LibreOfficeInstalled
        {
            get
            {
                RunningChecks++;
                return Installed;
            }
        }

        public bool LibreOfficeRunning()
        {
            RunningChecks++;
            return Running;
        }

        public Task<string?> ListAsync(CancellationToken cancel)
        {
            Listed++;
            return Task.FromResult<string?>(Lists.Count > 0 ? Lists.Dequeue() : Listing(null, false));
        }

        public Task<string?> AddAsync(string oxt, CancellationToken cancel)
        {
            Added.Add(oxt);
            return Task.FromResult(AddFailure);
        }

        public Task RemoveAsync(CancellationToken cancel)
        {
            Removed++;
            return Task.CompletedTask;
        }

        public void OpenInLibreOffice(string oxt)
        {
            if (OpenFailure is not null)
                throw OpenFailure;
            Opened++;
        }
    }
}
