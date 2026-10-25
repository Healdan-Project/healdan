using System.ComponentModel;
using System.Diagnostics;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Platform;

/// <summary>
/// Installs or updates the LibreOffice extension that comes inside the app (spec §5.12). The choices are
/// <see cref="ExtensionCheck"/>'s, in the core; this is what they need from the system.
/// <para>On macOS (R22) unopkg never runs: it failed in the trial, in Task 18 and on the owner's Mac ("couldn't connect
/// to pipe" after about 41 s), and what it starts can outlive it beside the user's own LibreOffice. What's installed
/// is read from LibreOffice's user registry, and the <c>.oxt</c> is opened in LibreOffice for the user to accept.</para>
/// <para>On Linux (phase 2, Task 22) unopkg runs from the helper's process with <c>TMPDIR=/tmp</c>, with time limits.</para>
/// </summary>
internal sealed class ExtensionInstaller : IExtensionTools
{
    private static readonly TimeSpan ListTimeout = TimeSpan.FromSeconds(60);

    /// <summary>A failing add took about 41 s on the owner's Mac; this leaves room for a slow one that works.</summary>
    private static readonly TimeSpan AddTimeout = TimeSpan.FromSeconds(180);

    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    private readonly HelperLog _log;
    private readonly LibreOfficeApp _app = new();
    private readonly string? _libreOffice = FindLibreOffice();

    public ExtensionInstaller(HelperLog log, INotifier notifier, HelperPaths paths, string helperVersion, Func<bool> windowWaiting)
    {
        _log = log;
        Check = new ExtensionCheck(this, paths, notifier, log, helperVersion,
            OperatingSystem.IsMacOS() ? ExtensionRoute.OpenInLibreOffice : ExtensionRoute.Unopkg,
            OperatingSystem.IsMacOS() ? BundledOxt : null, windowWaiting);
    }

    /// <summary>The check, which the menu (its Install the LibreOffice Extension item) and Settings read too.</summary>
    public ExtensionCheck Check { get; }

    /// <summary>The <c>.oxt</c> in the app bundle's <c>Contents/Resources</c> (<c>package.sh</c> puts it there); null outside the app bundle.</summary>
    public static string? BundledOxt => MacPlatform.InAppBundle
        ? Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "Resources", "healdan.oxt"))
        : null;

    /// <summary>Installs or updates the extension if it needs it; at start, once signed in, never while a window waits.</summary>
    public Task<ExtensionOutcome> EnsureCurrentAsync(CancellationToken cancel) => Check.RunAsync(cancel);

    public bool LibreOfficeInstalled => OperatingSystem.IsMacOS() ? _libreOffice is not null : File.Exists(Unopkg);

    public bool LibreOfficeRunning() => _app.IsRunning();

    /// <summary>LibreOffice's user profile's registry, read without running anything.</summary>
    public InstalledExtension? ReadInstalled() => ExtensionVersion.FromRegistry(LibreOfficeProfile());

    public async Task<string?> ListAsync(CancellationToken cancel)
    {
        try
        {
            var (exit, output, error) = await UnopkgAsync(["list"], ListTimeout, cancel);
            if (exit == 0)
                return output;
            _log.Warn(exit == Timeout ? $"unopkg list didn't finish within {ListTimeout.TotalSeconds:0} s" : $"unopkg list exited with {exit}: {LastLine(error, output)}");
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or Win32Exception)
        {
            _log.Warn("unopkg list couldn't run: " + e.Message);
        }
        return null;
    }

    public async Task<string?> AddAsync(string oxt, CancellationToken cancel)
    {
        try
        {
            var (exit, output, error) = await UnopkgAsync(["add", "--force", oxt], AddTimeout, cancel);
            return exit == 0 ? null
                : exit == Timeout ? $"unopkg add didn't finish within {AddTimeout.TotalSeconds:0} s"
                : $"unopkg add exited with {exit}: {LastLine(error, output)}";
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or Win32Exception)
        {
            return "unopkg couldn't run: " + e.Message;
        }
    }

    public async Task RemoveAsync(CancellationToken cancel)
    {
        try
        {
            var (exit, output, error) = await UnopkgAsync(["remove", ExtensionVersion.Identifier], ListTimeout, cancel);
            if (exit != 0)
                _log.Warn($"unopkg remove exited with {exit}: {LastLine(error, output)}");
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or Win32Exception)
        {
            _log.Warn("unopkg remove couldn't run: " + e.Message);
        }
    }

    public void OpenInLibreOffice(string oxt)
    {
        if (!OperatingSystem.IsMacOS())
        {
            // The launcher may stay running as LibreOffice itself, so it isn't waited for.
            using var launcher = Process.Start(new ProcessStartInfo("libreoffice") { UseShellExecute = false, ArgumentList = { oxt } })
                ?? throw new IOException("LibreOffice couldn't be started.");
            return;
        }
        // open hands the file to LibreOffice, starting it if need be, and returns; LibreOffice then asks the user.
        var start = new ProcessStartInfo("open") { UseShellExecute = false, ArgumentList = { "-a", _libreOffice ?? "LibreOffice", oxt } };
        using var open = Process.Start(start) ?? throw new IOException("LibreOffice couldn't be started.");
        if (open.WaitForExit(OpenTimeout) && open.ExitCode != 0)
            throw new IOException($"LibreOffice couldn't open it (open exited with {open.ExitCode}).");
    }

    private const int Timeout = -1;

    /// <summary>Linux's unopkg; Task 22 settles where its package puts it.</summary>
    private static string Unopkg => "/usr/lib/libreoffice/program/unopkg";

    /// <summary>
    /// Runs unopkg with a short <c>TMPDIR</c> (its pipe's path is made from it), killed with what it started when it
    /// takes too long. Never on macOS (R22).
    /// </summary>
    private async Task<(int Exit, string Output, string Error)> UnopkgAsync(string[] arguments, TimeSpan timeout, CancellationToken cancel)
    {
        if (OperatingSystem.IsMacOS())
            throw new PlatformNotSupportedException("unopkg isn't run on macOS: the extension is opened in LibreOffice instead.");
        var start = new ProcessStartInfo(Unopkg)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = "/tmp"
        };
        foreach (var argument in arguments)
            start.ArgumentList.Add(argument);
        start.Environment["TMPDIR"] = "/tmp";
        using var process = Process.Start(start) ?? throw new IOException("unopkg couldn't be started.");
        process.StandardInput.Close(); // it never waits on a question
        var output = process.StandardOutput.ReadToEndAsync(cancel);
        var error = process.StandardError.ReadToEndAsync(cancel);
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        limit.CancelAfter(timeout);
        try
        {
            await process.WaitForExitAsync(limit.Token);
            // A LibreOffice it started can outlive it with the same pipes open, so the reads have the same limit.
            return (process.ExitCode, await output.WaitAsync(limit.Token), await error.WaitAsync(limit.Token));
        }
        catch (OperationCanceledException)
        {
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch (Exception e) when (e is InvalidOperationException or Win32Exception)
            {
                // It ended meanwhile.
            }
            cancel.ThrowIfCancellationRequested();
            return (Timeout, "", "");
        }
    }

    /// <summary>
    /// The last line unopkg wrote, its error first, for the log: such as the trial's "couldn't connect to pipe", rather
    /// than the "unopkg failed." after it.
    /// </summary>
    private static string LastLine(string error, string output)
    {
        foreach (var text in new[] { error, output })
        {
            var lines = text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            var last = lines.LastOrDefault(l => !l.StartsWith("ERROR: unopkg failed", StringComparison.Ordinal)) ?? lines.LastOrDefault();
            if (last is not null)
                return last.Length <= 300 ? last : last[..300] + "...";
        }
        return "no output";
    }

    /// <summary>LibreOffice's user profile: <c>~/Library/Application Support/LibreOffice/4/user</c> on macOS.</summary>
    private static string LibreOfficeProfile()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS())
            return Path.Combine(home, "Library", "Application Support", "LibreOffice", "4", "user");
        var config = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
        return Path.Combine(string.IsNullOrEmpty(config) ? Path.Combine(home, ".config") : config, "libreoffice", "4", "user");
    }

    /// <summary>macOS's LibreOffice.app, in Applications or the user's own.</summary>
    private static string? FindLibreOffice()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return new[] { "/Applications/LibreOffice.app", Path.Combine(home, "Applications", "LibreOffice.app") }.FirstOrDefault(Directory.Exists);
    }
}
