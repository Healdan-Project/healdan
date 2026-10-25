using System.ComponentModel;
using System.Diagnostics;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Platform;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Platform;

/// <summary>
/// Installs or updates the LibreOffice extension that comes inside the app (spec §5.12), on macOS. It runs
/// LibreOffice's own <c>unopkg</c> from the helper's process with <c>TMPDIR=/tmp</c>, and opens the <c>.oxt</c> in
/// LibreOffice when that fails, as unopkg did in the trial and on the owner's Mac (macOS 27, LibreOffice 26.8.1: "couldn't
/// connect to pipe" after about 40 s). The choices are <see cref="ExtensionCheck"/>'s, in the core.
/// </summary>
internal sealed class ExtensionInstaller(HelperLog log, INotifier notifier, HelperPaths paths, string helperVersion, Func<bool> windowWaiting)
    : IExtensionTools
{
    private static readonly TimeSpan ListTimeout = TimeSpan.FromSeconds(60);

    /// <summary>A failing add took about 41 s on the owner's Mac; this leaves room for a slow one that works.</summary>
    private static readonly TimeSpan AddTimeout = TimeSpan.FromSeconds(180);

    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    private readonly LibreOfficeApp _app = new();
    private readonly string? _libreOffice = FindLibreOffice();

    /// <summary>The <c>.oxt</c> in the app bundle's <c>Contents/Resources</c> (<c>package.sh</c> puts it there); null outside the app bundle.</summary>
    public static string? BundledOxt => MacPlatform.InAppBundle
        ? Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "Resources", "healdan.oxt"))
        : null;

    /// <summary>Installs or updates the extension if it needs it; at start, once signed in, never while a window waits.</summary>
    public Task<ExtensionOutcome> EnsureCurrentAsync(CancellationToken cancel) =>
        new ExtensionCheck(this, paths, notifier, log, helperVersion, windowWaiting).RunAsync(OperatingSystem.IsMacOS() ? BundledOxt : null, cancel);

    public bool LibreOfficeInstalled => _libreOffice is not null && File.Exists(Unopkg);

    public bool LibreOfficeRunning() => _app.IsRunning();

    public async Task<string?> ListAsync(CancellationToken cancel)
    {
        try
        {
            var (exit, output, error) = await UnopkgAsync(["list"], ListTimeout, cancel);
            if (exit == 0)
                return output;
            log.Warn(exit == Timeout ? $"unopkg list didn't finish within {ListTimeout.TotalSeconds:0} s" : $"unopkg list exited with {exit}: {LastLine(error, output)}");
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or Win32Exception)
        {
            log.Warn("unopkg list couldn't run: " + e.Message);
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
                log.Warn($"unopkg remove exited with {exit}: {LastLine(error, output)}");
        }
        catch (Exception e) when (e is IOException or InvalidOperationException or Win32Exception)
        {
            log.Warn("unopkg remove couldn't run: " + e.Message);
        }
    }

    public void OpenInLibreOffice(string oxt)
    {
        // open hands the file to LibreOffice, starting it if need be, and returns; LibreOffice then asks the user.
        var start = new ProcessStartInfo("open") { UseShellExecute = false, ArgumentList = { "-a", _libreOffice ?? "LibreOffice", oxt } };
        using var open = Process.Start(start) ?? throw new IOException("LibreOffice couldn't be started.");
        if (open.WaitForExit(OpenTimeout) && open.ExitCode != 0)
            throw new IOException($"LibreOffice couldn't open it (open exited with {open.ExitCode}).");
    }

    private const int Timeout = -1;

    private string Unopkg => Path.Combine(_libreOffice ?? "/Applications/LibreOffice.app", "Contents", "MacOS", "unopkg");

    /// <summary>Runs unopkg with a short <c>TMPDIR</c> (its pipe's path is made from it), killed with what it started when it takes too long.</summary>
    private async Task<(int Exit, string Output, string Error)> UnopkgAsync(string[] arguments, TimeSpan timeout, CancellationToken cancel)
    {
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

    private static string? FindLibreOffice()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return new[] { "/Applications/LibreOffice.app", Path.Combine(home, "Applications", "LibreOffice.app") }.FirstOrDefault(Directory.Exists);
    }
}
