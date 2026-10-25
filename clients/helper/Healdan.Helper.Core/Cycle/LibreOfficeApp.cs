using System.Diagnostics;
using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// LibreOffice: opened with <c>open -a LibreOffice</c> on macOS and <c>libreoffice</c> on Linux. Its process is
/// <c>soffice</c> on macOS and <c>soffice.bin</c> on Linux, and it keeps <c>.~lock.&lt;copy file name&gt;#</c> beside
/// a file it has open for editing (plan: "The app's processes", the lock file).
/// </summary>
public sealed class LibreOfficeApp : IOfficeApp
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    public string Name => "LibreOffice";

    public void Open(string path)
    {
        if (OperatingSystem.IsMacOS())
        {
            // open hands the file to LibreOffice and returns; a non-zero exit means it couldn't (LibreOffice missing).
            var start = new ProcessStartInfo("open") { UseShellExecute = false, ArgumentList = { "-a", "LibreOffice", path } };
            using var open = Process.Start(start) ?? throw new IOException("LibreOffice couldn't be started.");
            if (!open.WaitForExit(OpenTimeout))
                return;
            if (open.ExitCode != 0)
                throw new IOException($"LibreOffice couldn't open the file (open exited with {open.ExitCode}).");
        }
        else
        {
            // The launcher may stay running as LibreOffice itself, so it isn't waited for.
            var start = new ProcessStartInfo("libreoffice") { UseShellExecute = false, ArgumentList = { path } };
            using var launcher = Process.Start(start) ?? throw new IOException("LibreOffice couldn't be started.");
        }
    }

    public bool IsRunning()
    {
        var processes = Process.GetProcessesByName(OperatingSystem.IsMacOS() ? "soffice" : "soffice.bin");
        foreach (var process in processes)
            process.Dispose();
        return processes.Length > 0;
    }

    public bool LockExists(string path) =>
        File.Exists(Path.Combine(Path.GetDirectoryName(path)!, CopyNames.LibreOfficeLockName(Path.GetFileName(path))));
}
