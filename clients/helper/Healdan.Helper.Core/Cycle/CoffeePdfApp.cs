using System.Diagnostics;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// CoffeePDF (M3.6 spec §4.1), the PDF app for macOS that works with Healdan through the helper's channel: opened by its
/// bundle identifier with <c>open -b</c>, its process is <c>CoffeePDF</c>, and it keeps
/// <c>.~coffeepdf.&lt;copy file name&gt;#</c> beside a copy it has open for editing (CoffeePDF spec §6.8). It restores
/// nothing after a crash, so it keeps <see cref="IOfficeApp"/>'s other defaults: a crash gets the crash window LibreOffice's
/// copies get, with no recovery watch, and the check-out names it <c>CoffeePDF</c>.
/// </summary>
/// <param name="processRunning">Whether a process of that name runs, as the platform sees it.</param>
/// <param name="open">Opens a copy in CoffeePDF; null for <c>open -b</c>. Tests pass their own.</param>
public sealed class CoffeePdfApp(Func<string, bool> processRunning, Action<string>? open = null) : IOfficeApp
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    /// <summary>What a copy's record, and the check-out, name as its app.</summary>
    public const string AppName = "CoffeePDF";

    /// <summary>Its process's name on macOS, the executable in <c>CoffeePDF.app/Contents/MacOS</c>.</summary>
    public const string ProcessName = "CoffeePDF";

    public string Name => AppName;

    public void Open(string path)
    {
        if (open is not null)
        {
            open(path);
            return;
        }
        if (!OperatingSystem.IsMacOS())
            throw new IOException("CoffeePDF runs only on macOS.");
        // open hands the file to CoffeePDF, which keeps one instance, and returns; a non-zero exit means it couldn't.
        var start = new ProcessStartInfo("open") { UseShellExecute = false, ArgumentList = { "-b", AppBundles.CoffeePdf, path } };
        using var launch = Process.Start(start) ?? throw new IOException("CoffeePDF couldn't be started.");
        if (!launch.WaitForExit(OpenTimeout))
            return;
        if (launch.ExitCode != 0)
            throw new IOException($"CoffeePDF couldn't open the file (open exited with {launch.ExitCode}).");
    }

    public bool IsRunning() => processRunning(ProcessName);

    public bool LockExists(string path) =>
        File.Exists(Path.Combine(Path.GetDirectoryName(path)!, CopyNames.CoffeePdfLockName(Path.GetFileName(path))));
}
