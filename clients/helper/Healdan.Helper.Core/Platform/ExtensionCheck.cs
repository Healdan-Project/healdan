using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Installs or updates the LibreOffice extension the helper carries (spec §5.12), once a start has signed in:
/// <list type="number">
/// <item>reads the installed version with <c>unopkg list</c>;</item>
/// <item>if it's older, tries <c>unopkg add</c> from the helper's own process;</item>
/// <item>if that fails, opens the <c>.oxt</c> in LibreOffice for the user to accept, and says so.</item>
/// </list>
/// It runs once per helper version, unless the installed extension is still older. unopkg is never run beside a
/// running LibreOffice, which then gets the <c>.oxt</c> unless it was asked already. Which way worked goes to the log
/// and <see cref="HelperSettings.Extension"/>. Nothing opens while one of the helper's windows waits for an answer.
/// </summary>
/// <param name="helperVersion">This helper's version: a new one checks again.</param>
/// <param name="windowWaiting">One of the helper's windows is waiting for an answer.</param>
/// <param name="poll">How often a waiting check looks again; 2 s by default.</param>
public sealed class ExtensionCheck(IExtensionTools tools, HelperPaths paths, INotifier notifier, HelperLog log, string helperVersion,
    Func<bool> windowWaiting, TimeSpan? poll = null)
{
    private const string Helper = "Healdan Helper";

    private readonly TimeSpan _poll = poll ?? TimeSpan.FromSeconds(2);

    /// <param name="bundledOxt">The <c>.oxt</c> inside the app; null, or missing, in a build that carries none.</param>
    public async Task<ExtensionOutcome> RunAsync(string? bundledOxt, CancellationToken cancel = default)
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

        var record = Record();
        if (record.CheckedFor == helperVersion && !ExtensionVersion.NeedsUpdate(record.Version, bundled))
            return ExtensionOutcome.Current; // checked already for this helper version
        if (!tools.LibreOfficeInstalled)
        {
            log.Warn("LibreOffice isn't installed, so its extension can't be");
            notifier.Notify(Helper, "LibreOffice isn't installed, so documents can't open. Install LibreOffice, then quit and reopen Healdan Helper.");
            return ExtensionOutcome.NoLibreOffice;
        }
        await WindowsAnsweredAsync(cancel);

        if (tools.LibreOfficeRunning())
        {
            if (!ExtensionVersion.NeedsUpdate(record.Version, bundled))
            {
                Save(record with { CheckedFor = helperVersion });
                log.Info($"LibreOffice extension {record.Version} was installed when last checked; LibreOffice is running, so it isn't listed now");
                return ExtensionOutcome.Current;
            }
            if (record.OpenedFor == bundled)
            {
                log.Info($"LibreOffice extension {bundled} was opened in LibreOffice before; it's checked at a start while LibreOffice is closed");
                return ExtensionOutcome.AwaitingLibreOffice;
            }
            return await OpenAsync(record, bundled, bundledOxt, "LibreOffice is running, so unopkg isn't used", cancel);
        }

        var installed = await ListAsync(cancel);
        if (installed?.UsableVersion is { } current && !ExtensionVersion.NeedsUpdate(current, bundled))
        {
            var way = record.OpenedFor is not null && ExtensionVersion.Compare(current, record.OpenedFor) >= 0
                ? ExtensionRecord.ByLibreOffice
                : record.Version == current && record.Way is { } kept ? kept : ExtensionRecord.Found;
            Save(record with
            {
                Version = current, Way = way, At = way == record.Way && current == record.Version ? record.At : DateTimeOffset.Now,
                CheckedFor = helperVersion, OpenedFor = null
            });
            log.Info($"LibreOffice extension {current} is installed ({Said(way)})");
            return ExtensionOutcome.Current;
        }

        var reason = $"unopkg failed for Healdan Helper {helperVersion} before";
        if (record.UnopkgFailedFor != helperVersion)
        {
            log.Info($"Installing LibreOffice extension {bundled} with unopkg (installed: {installed?.Version ?? "none"}{(installed is { Registered: false } ? ", unregistered" : "")})");
            var failure = await tools.AddAsync(bundledOxt, cancel);
            installed = await ListAsync(cancel);
            if (failure is null && installed?.UsableVersion is { } added && !ExtensionVersion.NeedsUpdate(added, bundled))
            {
                Save(record with { Version = added, Way = ExtensionRecord.ByUnopkg, At = DateTimeOffset.Now, CheckedFor = helperVersion, OpenedFor = null });
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
        return await OpenAsync(record, bundled, bundledOxt, reason, cancel);
    }

    private async Task<ExtensionOutcome> OpenAsync(ExtensionRecord record, string bundled, string oxt, string reason, CancellationToken cancel)
    {
        await WindowsAnsweredAsync(cancel);
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
        Save(record with { OpenedFor = bundled });
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
