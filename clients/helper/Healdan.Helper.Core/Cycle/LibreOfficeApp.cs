// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Diagnostics;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// LibreOffice: opened with <c>open -a LibreOffice</c> on macOS and <c>libreoffice</c> on Linux. Its process is
/// <c>soffice</c> on macOS and <c>soffice.bin</c> on Linux, and it keeps <c>.~lock.&lt;copy file name&gt;#</c> beside
/// a file it has open for editing (plan: "The app's processes", the lock file).
/// </summary>
public sealed class LibreOfficeApp : IOfficeApp
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    /// <summary>What a copy's record names as its app.</summary>
    public const string AppName = "LibreOffice";

    public string Name => AppName;

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

    /// <summary>
    /// Whether LibreOffice is running. On Linux only the user's own live <c>soffice.bin</c> processes count: another
    /// user's LibreOffice (a second desktop session, or a conversion service in a Docker container) is no sign the
    /// user's own is open, and counting it would hide a crash of theirs from the close watcher for good. A zombie
    /// doesn't count either.
    /// </summary>
    public bool IsRunning()
    {
        if (!OperatingSystem.IsMacOS())
            return OwnProcesses.AnyNamed("soffice.bin");

        var processes = Process.GetProcessesByName("soffice");
        foreach (var process in processes)
            process.Dispose();
        return processes.Length > 0;
    }

    public bool LockExists(string path) =>
        File.Exists(Path.Combine(Path.GetDirectoryName(path)!, CopyNames.LibreOfficeLockName(Path.GetFileName(path))));
}
