namespace Healdan.Helper.Core.Office;

/// <summary>
/// Starts the programs the macOS scripting seam needs, so a test can answer in their place: <c>osascript</c> for
/// AppleScript and <c>open</c> for opening a file in an app (M3.5 spec §5.2).
/// </summary>
public interface IOsaRunner
{
    /// <summary>
    /// Runs an AppleScript with <c>/usr/bin/osascript -</c>: the script on standard input, <paramref name="args"/> after
    /// the dash, where the script reads them as <c>argv</c>. Answers what the script returned, without its closing newline.
    /// </summary>
    /// <exception cref="OfficeScriptException">The script failed or didn't answer within <paramref name="timeout"/>.</exception>
    Task<string> RunAsync(string script, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct);

    /// <summary>Runs a program and waits for it to exit, such as <c>/usr/bin/open -a "Microsoft Excel" path</c>.</summary>
    /// <exception cref="OfficeScriptException">It couldn't be started, exited with an error or didn't finish within <paramref name="timeout"/>.</exception>
    Task RunProcessAsync(string file, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct);
}
