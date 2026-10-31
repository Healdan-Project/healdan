using System.Diagnostics;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Platform;

/// <summary>
/// The apps copies open in and the choice between them (M3.5 spec §5.10): LibreOffice, Microsoft Office on macOS (Word,
/// Excel and PowerPoint, driven by AppleScript; it can't be installed anywhere else), and the system's default app for
/// files no office app edits.
/// </summary>
internal static class OfficeAppSet
{
    /// <summary>
    /// What the chooser says now: the settings and the installed apps are read at each call, so a change in Settings or a
    /// newly installed app takes effect with the next copy opened, and Settings show the same answer.
    /// </summary>
    public static AppChooser Chooser(HelperPaths paths) =>
        new(HelperSettings.Load(paths), InstalledApps.Detect(), _ => null); // the system's default app: DefaultApps.For, when it exists

    public static OfficeApps Create(HelperPaths paths)
    {
        IOfficeApp? microsoftOffice = OperatingSystem.IsMacOS()
            ? new MicrosoftOfficeApp(new MacOfficeScripting(new OsaRunner(), paths.Copies), ProcessRunning)
            : null;
        return new OfficeApps(new LibreOfficeApp(), microsoftOffice, new DefaultApp(), name => Chooser(paths).For(name));
    }

    /// <summary>Whether a process of that name runs; macOS names a process by its executable, such as <c>Microsoft Word</c>.</summary>
    private static bool ProcessRunning(string processName)
    {
        var processes = Process.GetProcessesByName(processName);
        foreach (var process in processes)
            process.Dispose();
        return processes.Length > 0;
    }
}
