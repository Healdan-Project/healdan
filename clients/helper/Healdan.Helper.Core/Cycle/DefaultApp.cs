using System.Diagnostics;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// The system's default app, for a file no office app edits (spec §5.11), such as a PDF: <c>open</c> on macOS,
/// <c>xdg-open</c> on Linux. The cycle gives it a read-only copy and never checks such a file out. It keeps no lock
/// file, so nothing can tell when it's closed: the copy goes at the helper's next start. It's never given a kind of
/// file that runs a program, installs something or changes the system when opened (<see cref="Refuses"/>): anyone
/// who can add files to a matter could have put one there (final review C2).
/// </summary>
public sealed class DefaultApp : IOfficeApp
{
    /// <summary>What a copy's record names as its app; <see cref="Settings.HelperSettings.AppFor"/> says it too.</summary>
    public const string AppName = "Default";

    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    /// <summary>The kinds of file the default app is never handed, by extension: apps, installers, scripts and the like.</summary>
    public static readonly IReadOnlyList<string> RefusedExtensions =
    [
        ".app", ".command", ".tool", ".terminal", ".pkg", ".mpkg", ".dmg", ".workflow", ".action", ".jar", ".fileloc",
        ".inetloc", ".webloc", ".scpt", ".scptd", ".applescript", ".sh", ".py", ".rb", ".pl", ".prefPane", ".kext",
        ".mobileconfig", ".shortcut"
    ];

    /// <summary>Whether the file's extension, in any case, is one the default app is never handed.</summary>
    public static bool Refuses(string fileName) =>
        Path.GetExtension(fileName) is { Length: > 1 } ext && RefusedExtensions.Contains(ext, StringComparer.OrdinalIgnoreCase);

    public string Name => AppName;

    public void Open(string path)
    {
        if (Refuses(path))
            throw new IOException($"Healdan Helper never opens {Path.GetExtension(path)} files.");
        var start = OperatingSystem.IsMacOS() ? new ProcessStartInfo("open") { ArgumentList = { path } }
            : OperatingSystem.IsWindows() ? new ProcessStartInfo(path) { UseShellExecute = true }
            : new ProcessStartInfo("xdg-open") { ArgumentList = { path } };
        if (!OperatingSystem.IsWindows())
            start.UseShellExecute = false;
        using var opener = Process.Start(start) ?? throw new IOException("The file's app couldn't be started.");
        if (OperatingSystem.IsWindows() || !opener.WaitForExit(OpenTimeout))
            return;
        if (opener.ExitCode != 0)
            throw new IOException($"The computer has no app to open it with ({start.FileName} exited with {opener.ExitCode}).");
    }

    /// <summary>Nothing is watched for it, so it never counts as running.</summary>
    public bool IsRunning() => false;

    public bool LockExists(string path) => false;
}
