using System.Globalization;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Installs or updates the LibreOffice extension the helper carries (spec §5.12), once a start has signed in, and
/// says while it isn't installed. Nothing it opens comes up over a window waiting for an answer, and which way worked
/// goes to the log and <see cref="HelperSettings.Extension"/>.
/// <para><b>macOS</b> (<see cref="ExtensionRoute.OpenInLibreOffice"/>, R22): unopkg never runs. Every start reads
/// LibreOffice's user registry, which runs nothing. When the bundled extension is newer than the one installed, or
/// none is, it opens the <c>.oxt</c> in LibreOffice for the user to accept, once per helper version. After that it
/// doesn't start LibreOffice again: each start tells the user, and the menu offers Install the LibreOffice Extension
/// (<see cref="Missing"/>, <see cref="InstallFromMenu"/>) until it's installed.</para>
/// <para><b>Linux</b> (<see cref="ExtensionRoute.Unopkg"/>, phase 2): <c>unopkg list</c>, then <c>unopkg add</c>
/// once per helper version, else the <c>.oxt</c> opened in LibreOffice. unopkg never runs beside a running
/// LibreOffice, which then gets the <c>.oxt</c> unless it was asked already. It runs once per helper version, unless
/// the installed extension is still older.</para>
/// </summary>
/// <param name="helperVersion">This helper's version: a new one checks again, and may open LibreOffice again.</param>
/// <param name="bundledOxt">The <c>.oxt</c> inside the app; null, or missing, in a build that carries none.</param>
/// <param name="windowWaiting">One of the helper's windows is waiting for an answer.</param>
/// <param name="poll">How often a waiting check looks again; 2 s by default.</param>
public sealed class ExtensionCheck(IExtensionTools tools, HelperPaths paths, INotifier notifier, HelperLog log, string helperVersion,
    ExtensionRoute route, string? bundledOxt, Func<bool> windowWaiting, TimeSpan? poll = null)
{
    private const string Helper = "Healdan Helper";

    /// <summary>The menu item's name, as the notices give it.</summary>
    public const string MenuItem = "Install the LibreOffice Extension…";

    private readonly TimeSpan _poll = poll ?? TimeSpan.FromSeconds(2);
    private string? _bundled;
    private int _missing;

    /// <summary>
    /// The bundled extension isn't installed, as the last check or <see cref="Refresh"/> found, so the menu offers
    /// <see cref="MenuItem"/>. False until the start's check has run.
    /// </summary>
    public bool Missing => Volatile.Read(ref _missing) == 1;

    /// <summary>Raised on any thread when <see cref="Missing"/> changes.</summary>
    public event Action? Changed;

    /// <summary>The start's check, once signed in.</summary>
    public async Task<ExtensionOutcome> RunAsync(CancellationToken cancel = default)
    {
        if (bundledOxt is null || !File.Exists(bundledOxt))
        {
            log.Info("This build carries no LibreOffice extension, so none is installed");
            return ExtensionOutcome.NotBundled;
        }
        string bundled;
        try
        {
            bundled = ExtensionVersion.OfOxt(bundledOxt);
        }
        catch (Exception e) when (e is IOException or InvalidDataException or UnauthorizedAccessException)
        {
            log.Error("The LibreOffice extension this helper carries can't be read", e);
            return ExtensionOutcome.Failed;
        }
        Volatile.Write(ref _bundled, bundled);

        var record = Record();
        if (route == ExtensionRoute.Unopkg && record.CheckedFor == helperVersion && !ExtensionVersion.NeedsUpdate(record.Version, bundled))
            return ExtensionOutcome.Current; // checked already for this helper version; macOS reads the registry every start
        if (!tools.LibreOfficeInstalled)
        {
            log.Warn("LibreOffice isn't installed, so its extension can't be");
            notifier.Notify(Helper, "LibreOffice isn't installed, so documents can't open. Install LibreOffice, then quit and reopen Healdan Helper.");
            return ExtensionOutcome.NoLibreOffice;
        }
        return route == ExtensionRoute.OpenInLibreOffice
            ? await OneClickAsync(record, bundled, bundledOxt, cancel)
            : await UnopkgAsync(record, bundled, bundledOxt, cancel);
    }

    /// <summary>
    /// macOS: reads the registry again, as the menu opens, so its item goes once the user has accepted the extension.
    /// Elsewhere, what the last check found.
    /// </summary>
    public bool Refresh()
    {
        if (route != ExtensionRoute.OpenInLibreOffice || Volatile.Read(ref _bundled) is not { } bundled)
            return Missing;
        if (!tools.LibreOfficeInstalled)
            SetMissing(false); // nothing to install into
        else if (!RecordIfCurrent(tools.ReadInstalled(), bundled, Record()))
            SetMissing(true);
        return Missing;
    }

    /// <summary>The menu's <see cref="MenuItem"/>: the <c>.oxt</c> opened in LibreOffice now, whatever was asked before.</summary>
    public ExtensionOutcome InstallFromMenu()
    {
        if (bundledOxt is null || Volatile.Read(ref _bundled) is not { } bundled)
            return ExtensionOutcome.NotBundled;
        return Open(Record(), bundled, bundledOxt, "chosen from the menu");
    }

    /// <summary>Settings' line.</summary>
    public string Describe()
    {
        if (bundledOxt is null || !File.Exists(bundledOxt))
            return "Not in this build: the installed app installs it.";
        return Refresh()
            ? $"Not installed. To add Healdan's menu and toolbar to LibreOffice, choose {MenuItem} in the Healdan Helper menu."
            : Record().Describe();
    }

    /// <summary>Settings' row (polish spec §6.5); <see cref="Describe"/> is its tooltip.</summary>
    public ExtensionSummary Summary()
    {
        if (bundledOxt is null || !File.Exists(bundledOxt))
            return ExtensionSummary.NotInThisBuild;
        var missing = Refresh(); // reads the registry first, which may record what the user accepted
        var record = Record();
        if (missing)
            return new("Not Installed", record.OpenedFor is { } waiting
                ? $"Waiting for you to accept version {waiting} in LibreOffice."
                : "Adds Healdan's menu and toolbar to LibreOffice.", true);
        return record.Version is { } version
            ? new(version, record.At is { } at ? "Installed on " + at.ToLocalTime().ToString("d MMMM yyyy", CultureInfo.InvariantCulture) : "Installed", false)
            : new("Not Installed", null, false);
    }

    /// <summary>macOS (R22): the registry, and the <c>.oxt</c> opened in LibreOffice once per helper version.</summary>
    private async Task<ExtensionOutcome> OneClickAsync(ExtensionRecord record, string bundled, string oxt, CancellationToken cancel)
    {
        var installed = tools.ReadInstalled();
        if (RecordIfCurrent(installed, bundled, record))
            return ExtensionOutcome.Current;
        SetMissing(true);
        var why = installed is null ? "none is installed"
            : installed.Registered ? $"{installed.Version ?? "a version without a number"} is installed"
            : $"{installed.Version} is turned off";
        if (record.OpenedBy != helperVersion)
        {
            await WindowsAnsweredAsync(cancel);
            return Open(Record(), bundled, oxt, why);
        }
        log.Info($"LibreOffice extension {bundled} isn't installed ({why}); LibreOffice was asked already for Healdan Helper {helperVersion}, so the menu offers it");
        notifier.Notify(Helper, $"Healdan's menu and toolbar aren't in LibreOffice yet. To add them, choose {MenuItem} in the Healdan Helper menu.");
        return ExtensionOutcome.NotInstalled;
    }

    /// <summary>Linux (phase 2): unopkg while LibreOffice is closed, else the <c>.oxt</c> opened in LibreOffice.</summary>
    private async Task<ExtensionOutcome> UnopkgAsync(ExtensionRecord record, string bundled, string oxt, CancellationToken cancel)
    {
        await WindowsAnsweredAsync(cancel);
        if (tools.LibreOfficeRunning())
        {
            if (!ExtensionVersion.NeedsUpdate(record.Version, bundled))
            {
                Save(record with { CheckedFor = helperVersion });
                SetMissing(false);
                log.Info($"LibreOffice extension {record.Version} was installed when last checked; LibreOffice is running, so it isn't listed now");
                return ExtensionOutcome.Current;
            }
            SetMissing(true);
            if (record.OpenedFor == bundled)
            {
                log.Info($"LibreOffice extension {bundled} was opened in LibreOffice before; it's checked at a start while LibreOffice is closed");
                return ExtensionOutcome.AwaitingLibreOffice;
            }
            return Open(record, bundled, oxt, "LibreOffice is running, so unopkg isn't used");
        }

        var installed = await ListAsync(cancel);
        if (RecordIfCurrent(installed, bundled, record))
            return ExtensionOutcome.Current;
        SetMissing(true);

        var reason = $"unopkg failed for Healdan Helper {helperVersion} before";
        if (record.UnopkgFailedFor != helperVersion)
        {
            log.Info($"Installing LibreOffice extension {bundled} with unopkg (installed: {installed?.Version ?? "none"}{(installed is { Registered: false } ? ", unregistered" : "")})");
            var failure = await tools.AddAsync(oxt, cancel);
            installed = await ListAsync(cancel);
            if (failure is null && installed?.UsableVersion is { } added && !ExtensionVersion.NeedsUpdate(added, bundled))
            {
                Save(record with { Version = added, Way = ExtensionRecord.ByUnopkg, At = DateTimeOffset.Now, CheckedFor = helperVersion, OpenedFor = null });
                SetMissing(false);
                log.Info($"LibreOffice extension {added} installed by unopkg");
                return ExtensionOutcome.InstalledByUnopkg;
            }
            reason = failure ?? "unopkg finished, but doesn't list the extension as registered";
            log.Warn("unopkg couldn't install the LibreOffice extension: " + reason);
            record = record with { UnopkgFailedFor = helperVersion };
            Save(record);
        }
        // A failed add leaves the extension listed but unregistered. Removing it makes LibreOffice's own install a plain one.
        if (installed is { Registered: false } && !tools.LibreOfficeRunning())
        {
            log.Info("Removing the unregistered extension a failed unopkg add left");
            await tools.RemoveAsync(cancel);
        }
        await WindowsAnsweredAsync(cancel);
        return Open(record, bundled, oxt, reason);
    }

    /// <summary>When the installed extension is the bundled one or newer: recorded, with the way it went in, and true.</summary>
    private bool RecordIfCurrent(InstalledExtension? installed, string bundled, ExtensionRecord record)
    {
        if (installed?.UsableVersion is not { } current || ExtensionVersion.NeedsUpdate(current, bundled))
            return false;
        var way = record.OpenedFor is not null && ExtensionVersion.Compare(current, record.OpenedFor) >= 0
            ? ExtensionRecord.ByLibreOffice
            : record.Version == current && record.Way is { } kept ? kept : ExtensionRecord.Found;
        var changed = record.Version != current || record.Way != way || record.CheckedFor != helperVersion || record.OpenedFor is not null;
        if (changed)
        {
            Save(record with
            {
                Version = current, Way = way, At = way == record.Way && current == record.Version ? record.At : DateTimeOffset.Now,
                CheckedFor = helperVersion, OpenedFor = null
            });
            log.Info($"LibreOffice extension {current} is installed ({Said(way)})");
        }
        SetMissing(false);
        return true;
    }

    private ExtensionOutcome Open(ExtensionRecord record, string bundled, string oxt, string reason)
    {
        try
        {
            tools.OpenInLibreOffice(oxt);
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            log.Error("LibreOffice couldn't open the extension", e);
            notifier.Notify(Helper, $"Healdan's LibreOffice extension couldn't be installed: {e.Message} Quit and reopen Healdan Helper to try again.");
            return ExtensionOutcome.Failed;
        }
        Save(record with { OpenedFor = bundled, OpenedBy = helperVersion });
        log.Info($"Opened LibreOffice extension {bundled} in LibreOffice for the user to accept ({reason})");
        notifier.Notify(Helper, "LibreOffice is asking to install Healdan's extension, which adds Healdan's menu and toolbar. Click OK, then restart LibreOffice when it asks.");
        return ExtensionOutcome.OpenedInLibreOffice;
    }

    private async Task<InstalledExtension?> ListAsync(CancellationToken cancel)
    {
        var output = await tools.ListAsync(cancel);
        if (output is null)
            log.Warn("unopkg list didn't answer, so the installed extension's version isn't known");
        return output is null ? null : ExtensionVersion.FromUnopkgList(output);
    }

    /// <summary>Nothing of this check comes up over a window the user is answering.</summary>
    private async Task WindowsAnsweredAsync(CancellationToken cancel)
    {
        while (windowWaiting())
            await Task.Delay(_poll, cancel);
    }

    private void SetMissing(bool missing)
    {
        if (Interlocked.Exchange(ref _missing, missing ? 1 : 0) == (missing ? 1 : 0))
            return;
        try
        {
            Changed?.Invoke();
        }
        catch (Exception e)
        {
            log.Error("The menu couldn't be told about the extension", e);
        }
    }

    private ExtensionRecord Record() => HelperSettings.Load(paths).Extension ?? new ExtensionRecord();

    private void Save(ExtensionRecord record)
    {
        try
        {
            HelperSettings.Update(paths, s => s.Extension = record);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.Error("The extension's record couldn't be saved", e);
        }
    }

    private static string Said(string way) => way switch
    {
        ExtensionRecord.ByUnopkg => "installed by unopkg",
        ExtensionRecord.ByLibreOffice => "the user accepted it in LibreOffice",
        _ => "it was installed already"
    };
}
