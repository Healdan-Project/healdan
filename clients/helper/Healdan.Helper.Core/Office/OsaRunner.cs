using System.Diagnostics;
using System.Text;

namespace Healdan.Helper.Core.Office;

/// <summary>
/// The real <see cref="IOsaRunner"/>. The script goes to <c>osascript</c> on standard input and every value after the
/// dash, so no value is ever part of a script's text. Failures carry the program's own error text and never the arguments.
/// </summary>
public sealed class OsaRunner : IOsaRunner
{
    private const string Osascript = "/usr/bin/osascript";

    public async Task<string> RunAsync(string script, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct)
    {
        var start = Command(Osascript, ["-", .. args], redirectInput: true);
        var answer = await ExecuteAsync(start, script, timeout, ct, "osascript");
        return answer.EndsWith('\n') ? answer[..^1] : answer;
    }

    public async Task RunProcessAsync(string file, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct) =>
        await ExecuteAsync(Command(file, args, redirectInput: false), null, timeout, ct, Path.GetFileName(file));

    private static ProcessStartInfo Command(string file, IEnumerable<string> args, bool redirectInput)
    {
        var start = new ProcessStartInfo(file)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = redirectInput,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        if (redirectInput)
            start.StandardInputEncoding = new UTF8Encoding(false);
        foreach (var arg in args)
            start.ArgumentList.Add(arg);
        return start;
    }

    /// <summary>Runs the process to its end and returns its standard output.</summary>
    private static async Task<string> ExecuteAsync(ProcessStartInfo start, string? input, TimeSpan timeout, CancellationToken ct, string what)
    {
        Process process;
        try
        {
            process = Process.Start(start) ?? throw new OfficeScriptException($"{what} couldn't be started.");
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            throw new OfficeScriptException($"{start.FileName} couldn't be started: {e.Message}", e);
        }

        using (process)
        using (var limit = CancellationTokenSource.CreateLinkedTokenSource(ct))
        {
            limit.CancelAfter(timeout);
            var output = process.StandardOutput.ReadToEndAsync(limit.Token);
            var error = process.StandardError.ReadToEndAsync(limit.Token);
            try
            {
                if (input is not null)
                {
                    try
                    {
                        await process.StandardInput.WriteAsync(input.AsMemory(), limit.Token);
                        process.StandardInput.Close();
                    }
                    catch (IOException)
                    {
                        // It stopped reading, and its exit code and error text say why.
                    }
                }
                await process.WaitForExitAsync(limit.Token);
                await Task.WhenAll(output, error);
            }
            catch (OperationCanceledException)
            {
                Stop(process);
                if (ct.IsCancellationRequested)
                    throw;
                throw new OfficeScriptException($"{what} didn't answer within {timeout.TotalSeconds:0.#} seconds.");
            }

            if (process.ExitCode != 0)
            {
                var text = (await error).Trim();
                throw new OfficeScriptException(text.Length > 0 ? text : $"{what} exited with {process.ExitCode}.");
            }
            return await output;
        }
    }

    private static void Stop(Process process)
    {
        try
        {
            process.Kill(entireProcessTree: true);
        }
        catch (InvalidOperationException)
        {
            // It had finished.
        }
    }
}
