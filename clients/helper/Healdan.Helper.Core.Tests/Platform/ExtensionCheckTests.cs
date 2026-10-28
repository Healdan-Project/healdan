using System.IO.Compression;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Ui;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>
/// Installing or updating the LibreOffice extension at start (spec §5.12), and which way worked recorded.
/// <para>macOS (R22): never unopkg; what's installed comes from LibreOffice's registry; the <c>.oxt</c> is opened in
/// LibreOffice at most once per helper version, and after that a notice and the menu's item offer it while it isn't
/// installed.</para>
/// <para>Linux (phase 2): unopkg first, else the <c>.oxt</c> opened in LibreOffice; never unopkg beside a running
/// LibreOffice; once per helper version unless the installed one is still older.</para>
/// </summary>
public sealed class ExtensionCheckTests : IDisposable
{
    private const string Helper = "0.2.0";

    /// <summary>Settings' tooltip while the extension isn't installed.</summary>
    private const string NotInstalledTip =
        $"Not installed. To add Healdan's menu and toolbar to LibreOffice, choose {ExtensionCheck.MenuItem} in the Healdan Helper menu.";

    /// <summary>Settings' tooltip while 0.1.0 is installed and the helper carries 0.2.0.</summary>
    private const string OlderTip =
        $"Version 0.1.0 is installed. To update it to 0.2.0, choose {ExtensionCheck.MenuItem} in the Healdan Helper menu.";

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

    // --- Linux (phase 2): unopkg ---------------------------------------------------------------------------------

    [Fact]
    public async Task ACurrentExtensionIsLeftAloneAndRecorded()
    {
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.Current, await Linux(Oxt("0.2.0")).RunAsync());

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

        Assert.Equal(ExtensionOutcome.InstalledByUnopkg, await Linux(Oxt("0.2.0")).RunAsync());

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

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Linux(Oxt("0.2.0")).RunAsync());

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

        Assert.Equal(ExtensionOutcome.Current, await Linux(Oxt("0.2.0")).RunAsync());

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
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Empty(_tools.Added);
        Assert.Equal(1, _tools.Opened);
    }

    [Fact]
    public async Task ACheckedVersionIsNotCheckedAgain()
    {
        Save(new ExtensionRecord { Version = "0.2.0", Way = "unopkg", CheckedFor = Helper });

        Assert.Equal(ExtensionOutcome.Current, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Equal(0, _tools.Listed);
        Assert.Equal(0, _tools.RunningChecks);
    }

    [Fact]
    public async Task ANewHelperVersionChecksAgain()
    {
        Save(new ExtensionRecord { Version = "0.1.0", Way = "unopkg", CheckedFor = "0.1.0", UnopkgFailedFor = "0.1.0" });
        _tools.Lists.Enqueue(Listing("0.1.0", registered: true));
        _tools.Lists.Enqueue(Listing("0.2.0", registered: true));

        Assert.Equal(ExtensionOutcome.InstalledByUnopkg, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Single(_tools.Added); // the old version's unopkg failure doesn't count for this one
    }

    [Fact]
    public async Task ARunningLibreOfficeGetsTheOxtNeverUnopkg()
    {
        // Beside a running LibreOffice, unopkg list waited minutes on this Mac and then listed nothing.
        Save(new ExtensionRecord { Version = "0.1.0", Way = "unopkg", CheckedFor = "0.1.0" });
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Linux(Oxt("0.2.0")).RunAsync());

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

        Assert.Equal(ExtensionOutcome.AwaitingLibreOffice, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Equal(0, _tools.Opened);
        Assert.Equal(0, _tools.Listed);
    }

    [Fact]
    public async Task ARunningLibreOfficeWithTheRecordedVersionIsCurrent()
    {
        Save(new ExtensionRecord { Version = "0.2.0", Way = "unopkg", CheckedFor = "0.1.0" });
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.Current, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Equal(0, _tools.Opened);
        Assert.Equal(Helper, Record().CheckedFor);
    }

    [Fact]
    public async Task NothingOpensWhileAWindowWaits()
    {
        _tools.Lists.Enqueue(Listing(null, registered: false));
        _tools.AddFailure = "couldn't connect to pipe";
        var waiting = true;
        var check = Linux(Oxt("0.2.0"), () => Volatile.Read(ref waiting));

        var run = check.RunAsync();
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

        Assert.Equal(ExtensionOutcome.NoLibreOffice, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Contains("LibreOffice isn't installed", _notifier.All);
        Assert.Equal(0, _tools.Listed);
    }

    [Fact]
    public async Task ABuildWithoutTheOxtDoesNothing()
    {
        Assert.Equal(ExtensionOutcome.NotBundled, await Linux(null).RunAsync());
        Assert.Equal(ExtensionOutcome.NotBundled, await Linux(Path.Combine(_dir, "missing.oxt")).RunAsync());

        Assert.Equal(0, _tools.RunningChecks);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AnOpenThatFailsIsTold()
    {
        _tools.Lists.Enqueue(Listing(null, registered: false));
        _tools.AddFailure = "couldn't connect to pipe";
        _tools.OpenFailure = new IOException("open exited with 1");

        Assert.Equal(ExtensionOutcome.Failed, await Linux(Oxt("0.2.0")).RunAsync());

        Assert.Contains("couldn't be installed", _notifier.All);
        Assert.Null(Record().OpenedFor);
    }

    // --- macOS (R22): one click in LibreOffice, never unopkg --------------------------------------------------------

    [Fact]
    public async Task MacNeverStartsUnopkg()
    {
        foreach (var (registry, running) in new (InstalledExtension?, bool)[]
                 {
                     (null, false), (new("0.1.0", true), false), (new("0.2.0", false), true), (new("0.2.0", true), true), (null, true)
                 })
        {
            _tools.Registry = registry;
            _tools.Running = running;
            await Mac(Oxt("0.2.0")).RunAsync();
            await Mac(Oxt("0.2.0"), helper: "0.3.0").RunAsync();
        }

        Assert.Equal(0, _tools.UnopkgCalls);
        Assert.True(_tools.RegistryReads > 0);
    }

    [Fact]
    public async Task MacOpensTheOxtAtMostOncePerHelperVersion()
    {
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Mac(Oxt("0.2.0")).RunAsync());
        Assert.Equal(1, _tools.Opened);
        Assert.Equal(("0.2.0", Helper), (Record().OpenedFor, Record().OpenedBy));

        // The next starts of this helper don't start LibreOffice again, even with it closed.
        Assert.Equal(ExtensionOutcome.NotInstalled, await Mac(Oxt("0.2.0")).RunAsync());
        _tools.Running = true;
        Assert.Equal(ExtensionOutcome.NotInstalled, await Mac(Oxt("0.2.0")).RunAsync());
        Assert.Equal(1, _tools.Opened);

        // A new helper version asks once more.
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Mac(Oxt("0.2.0"), helper: "0.2.1").RunAsync());
        Assert.Equal(2, _tools.Opened);
    }

    [Fact]
    public async Task MacOffersTheMenuItemAndANoticeWhileItIsntInstalled()
    {
        Save(new ExtensionRecord { OpenedFor = "0.2.0", OpenedBy = Helper });
        var check = Mac(Oxt("0.2.0"));
        var changes = 0;
        check.Changed += () => changes++;
        Assert.False(check.Missing); // nothing to offer before the start's check

        Assert.Equal(ExtensionOutcome.NotInstalled, await check.RunAsync());

        Assert.True(check.Missing);
        Assert.Equal(1, changes);
        Assert.Contains(ExtensionCheck.MenuItem, _notifier.All);
        Assert.StartsWith("Not installed.", check.Describe());
        Assert.True(check.Refresh()); // the menu opens: still not installed

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, check.InstallFromMenu());
        Assert.Equal(1, _tools.Opened);

        _tools.Registry = new InstalledExtension("0.2.0", true); // the user clicked OK
        Assert.False(check.Refresh());
        Assert.Equal(2, changes);
        Assert.Equal(("0.2.0", "LibreOffice", Helper), (Record().Version, Record().Way, Record().CheckedFor));
        Assert.StartsWith("Version 0.2.0, installed in LibreOffice on ", check.Describe());
    }

    [Fact]
    public async Task MacSeesAnInstalledExtensionWithLibreOfficeOpenOrClosed()
    {
        _tools.Registry = new InstalledExtension("0.2.0", true);
        _tools.Running = true;

        Assert.Equal(ExtensionOutcome.Current, await Mac(Oxt("0.2.0")).RunAsync());

        Assert.Equal(0, _tools.Opened);
        Assert.Equal(("0.2.0", "found", Helper), (Record().Version, Record().Way, Record().CheckedFor));
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task MacUpdatesAnOlderOrTurnedOffExtension()
    {
        _tools.Registry = new InstalledExtension("0.1.0", true);
        var check = Mac(Oxt("0.2.0"));
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await check.RunAsync());
        Assert.True(check.Missing);
        Assert.Contains("0.1.0 is installed", File.ReadAllText(_paths.Log));

        _tools.Registry = new InstalledExtension("0.2.0", false); // turned off in LibreOffice
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await Mac(Oxt("0.2.0"), helper: "0.2.1").RunAsync());
        Assert.Contains("0.2.0 is turned off", File.ReadAllText(_paths.Log));
        Assert.Equal(2, _tools.Opened);
    }

    [Fact]
    public async Task MacOpensNothingWhileAWindowWaits()
    {
        var waiting = true;
        var run = Mac(Oxt("0.2.0"), () => Volatile.Read(ref waiting)).RunAsync();
        await Task.Delay(100);
        Assert.Equal(0, _tools.Opened);
        Volatile.Write(ref waiting, false);

        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await run);
        Assert.Equal(1, _tools.Opened);
    }

    [Fact]
    public async Task MacWithoutTheOxtOffersNothing()
    {
        var check = Mac(null);
        Assert.Equal(ExtensionOutcome.NotBundled, await check.RunAsync());
        Assert.False(check.Refresh());
        Assert.Equal(ExtensionOutcome.NotBundled, check.InstallFromMenu());
        Assert.Equal("Not in this build: the installed app installs it.", check.Describe());
        Assert.Equal(0, _tools.RegistryReads);
    }

    [Fact]
    public async Task MacSettingsRowOffersInstallWhileItIsntInstalled()
    {
        Save(new ExtensionRecord { OpenedFor = "0.2.0", OpenedBy = Helper });
        var check = Mac(Oxt("0.2.0"));
        await check.RunAsync();

        Assert.Equal(new ExtensionSummary("Not Installed", "Waiting for you to accept version 0.2.0 in LibreOffice.", true, NotInstalledTip), check.Summary());

        _tools.Registry = new InstalledExtension("0.2.0", true); // the user clicked OK
        var installed = check.Summary();
        Assert.Equal("0.2.0", installed.Value);
        Assert.StartsWith("Installed on ", installed.Note);
        Assert.False(installed.CanInstall);
        Assert.Equal(check.Describe(), installed.Tip);
        Assert.StartsWith("Version 0.2.0, installed in LibreOffice on ", installed.Tip);
    }

    [Fact]
    public async Task MacSettingsRowBeforeLibreOfficeWasAsked()
    {
        var check = Mac(Oxt("0.2.0"));
        Assert.Equal(new ExtensionSummary("Not Installed", null, false, "Not installed yet."), check.Summary()); // before the start's check: nothing to offer yet

        await check.RunAsync(); // opens it in LibreOffice once
        Assert.Equal(new ExtensionSummary("Not Installed", "Waiting for you to accept version 0.2.0 in LibreOffice.", true, NotInstalledTip), check.Summary());
    }

    [Fact]
    public async Task MacSettingsRowOffersUpdateWhileAnOlderVersionIsInstalled()
    {
        _tools.Registry = new InstalledExtension("0.1.0", true); // LibreOffice shows 0.1.0's toolbar
        var waiting = true;
        var check = Mac(Oxt("0.2.0"), () => Volatile.Read(ref waiting));
        var run = check.RunAsync(); // waits for the window before opening anything

        Assert.True(check.Missing);
        Assert.Equal("0.1.0", check.Older);
        Assert.Equal(new ExtensionSummary("0.1.0", "Version 0.2.0 is ready to add to LibreOffice.", true, OlderTip, ExtensionSummary.Update),
            check.Summary());

        Volatile.Write(ref waiting, false);
        Assert.Equal(ExtensionOutcome.OpenedInLibreOffice, await run);
        Assert.Equal(new ExtensionSummary("0.1.0", "Version 0.2.0 is waiting for you to accept it in LibreOffice.", true, OlderTip,
            ExtensionSummary.Update), check.Summary());
        Assert.Equal(OlderTip, check.Describe());

        _tools.Registry = new InstalledExtension("0.2.0", true); // the user clicked OK
        var updated = check.Summary();
        Assert.Equal(("0.2.0", false), (updated.Value, updated.CanInstall));
        Assert.Null(check.Older);
    }

    [Fact]
    public async Task MacSettingsRowCallsATurnedOffExtensionNotInstalled()
    {
        _tools.Registry = new InstalledExtension("0.1.0", false); // turned off in LibreOffice, so no toolbar shows
        var check = Mac(Oxt("0.2.0"));
        await check.RunAsync();

        Assert.Null(check.Older);
        Assert.Equal(new ExtensionSummary("Not Installed", "Waiting for you to accept version 0.2.0 in LibreOffice.", true, NotInstalledTip),
            check.Summary());
        Assert.Equal(ExtensionSummary.Install, check.Summary().Button);
    }

    [Fact]
    public async Task MacOlderVersionsRowSurvivesThePoll()
    {
        _tools.Registry = new InstalledExtension("0.1.0", true);
        var check = Mac(Oxt("0.2.0"));
        await check.RunAsync(); // opens 0.2.0 in LibreOffice
        var changes = 0;
        check.Changed += () => changes++;

        // The menu bar icon reads the registry every 5 s while the extension is missing; nothing changed, so nothing's raised.
        Assert.True(check.Refresh());
        Assert.True(check.Refresh());
        Assert.Equal(0, changes);
        Assert.Equal("0.1.0", check.Older);
        Assert.Equal(new ExtensionSummary("0.1.0", "Version 0.2.0 is waiting for you to accept it in LibreOffice.", true, OlderTip,
            ExtensionSummary.Update), check.Summary());
        Assert.Equal("LibreOffice Extension Needs Updating", Reason(check));
    }

    [Fact]
    public async Task NothingIsOlderWhileNothingIsInstalled()
    {
        var check = Mac(Oxt("0.2.0"));
        Assert.Null(check.Older); // before the start's check
        await check.RunAsync();
        Assert.True(check.Missing);
        Assert.Null(check.Older);
    }

    [Fact]
    public async Task LinuxKnowsAnOlderVersionUnopkgCouldntReplace()
    {
        _tools.Lists.Enqueue(Listing("0.1.0", true));
        _tools.Lists.Enqueue(Listing("0.1.0", true)); // the add failed, and 0.1.0 is still in use
        _tools.AddFailure = "couldn't connect to pipe";
        var check = Linux(Oxt("0.2.0"));
        await check.RunAsync();

        Assert.True(check.Missing);
        Assert.Equal("0.1.0", check.Older);
    }

    [Fact]
    public void SettingsRowWithoutTheOxt() => Assert.Equal(ExtensionSummary.NotInThisBuild, Mac(null).Summary());

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

    /// <summary>The dot's reason as the menu bar icon gives it on each rebuild, with all else well.</summary>
    private static string? Reason(ExtensionCheck check) =>
        Attention.Reason(signedIn: true, startFailed: false, crashed: false, kept: 0, check.Missing, check.Older is not null);

    private ExtensionCheck Linux(string? oxt, Func<bool>? waiting = null) =>
        new(_tools, _paths, _notifier, _log, Helper, ExtensionRoute.Unopkg, oxt, waiting ?? (() => false), poll: TimeSpan.FromMilliseconds(10));

    private ExtensionCheck Mac(string? oxt, Func<bool>? waiting = null, string helper = Helper) =>
        new(_tools, _paths, _notifier, _log, helper, ExtensionRoute.OpenInLibreOffice, oxt, waiting ?? (() => false), poll: TimeSpan.FromMilliseconds(10));

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

        /// <summary>What LibreOffice's registry says is installed.</summary>
        public InstalledExtension? Registry { get; set; }

        public int RegistryReads { get; private set; }

        /// <summary>Every unopkg call (list, add, remove), which macOS must never make.</summary>
        public int UnopkgCalls { get; private set; }

        public InstalledExtension? ReadInstalled()
        {
            RegistryReads++;
            return Registry;
        }

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
            UnopkgCalls++;
            Listed++;
            return Task.FromResult<string?>(Lists.Count > 0 ? Lists.Dequeue() : Listing(null, false));
        }

        public Task<string?> AddAsync(string oxt, CancellationToken cancel)
        {
            UnopkgCalls++;
            Added.Add(oxt);
            return Task.FromResult(AddFailure);
        }

        public Task RemoveAsync(CancellationToken cancel)
        {
            UnopkgCalls++;
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
