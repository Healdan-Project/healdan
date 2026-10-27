using System.Diagnostics;

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// The system's way of opening a file or a web page in the user's usual app for it: <c>open</c> on macOS,
/// <c>xdg-open</c> on Linux, the shell on Windows. The target is handed over as one argument, and no shell reads it.
/// The default app (spec §5.11) and the menu's Open Healdan in Browser use it.
/// </summary>
public static class SystemOpener
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    /// <summary>The command that opens <paramref name="target"/>, a file's path or a web page's address.</summary>
    public static ProcessStartInfo Command(string target)
    {
        var start = OperatingSystem.IsMacOS() ? new ProcessStartInfo("open") { ArgumentList = { target } }
            : OperatingSystem.IsWindows() ? new ProcessStartInfo(target) { UseShellExecute = true }
            : new ProcessStartInfo("xdg-open") { ArgumentList = { target } };
        if (!OperatingSystem.IsWindows())
            start.UseShellExecute = false;
        return start;
    }

    /// <summary>Opens <paramref name="target"/>, and waits a while for the opener to say whether anything could.</summary>
    /// <exception cref="IOException">Nothing on the computer opens it.</exception>
    public static void Open(string target)
    {
        var start = Command(target);
        using var opener = Process.Start(start) ?? throw new IOException("The app to open it with couldn't be started.");
        if (OperatingSystem.IsWindows() || !opener.WaitForExit(OpenTimeout))
            return;
        if (opener.ExitCode != 0)
            throw new IOException($"The computer has no app to open it with ({start.FileName} exited with {opener.ExitCode}).");
    }

    /// <summary>Opens a web page in the usual browser. Never any other kind of address, which could start an app.</summary>
    /// <exception cref="ArgumentException">It isn't an <c>https</c> or <c>http</c> address.</exception>
    public static void OpenWebPage(Uri page)
    {
        if (page.Scheme is not ("https" or "http"))
            throw new ArgumentException($"Healdan Helper opens only web pages, not {page.Scheme} addresses.", nameof(page));
        Open(page.AbsoluteUri);
    }
}
