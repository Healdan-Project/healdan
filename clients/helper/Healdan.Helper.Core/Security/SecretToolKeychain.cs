using System.Diagnostics;
using System.Text;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// The Secret Service on Ubuntu, through <c>secret-tool</c>. The secret goes in on its standard input, never on the
/// command line where <c>ps</c> would show it.
/// </summary>
public sealed class SecretToolKeychain(string service = "Healdan Helper") : IKeychain
{
    public string? Load(string account)
    {
        var (code, output) = Run(["lookup", "service", service, "account", account], null);
        // secret-tool exits 1 with no output when nothing matches.
        if (code != 0 && output.Length == 0) return null;
        if (code != 0) throw new InvalidOperationException($"secret-tool could not read the sign-in (exit {code}).");
        return output;
    }

    public void Save(string account, string secret)
    {
        var (code, _) = Run(["store", "--label=Healdan Helper", "service", service, "account", account], secret);
        if (code != 0) throw new InvalidOperationException($"secret-tool could not store the sign-in (exit {code}).");
    }

    public void Delete(string account)
    {
        // clear exits non-zero when nothing matched; either way the item is gone afterwards.
        Run(["clear", "service", service, "account", account], null);
    }

    private static (int Code, string Output) Run(string[] args, string? stdin)
    {
        var info = new ProcessStartInfo("secret-tool")
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            StandardOutputEncoding = Encoding.UTF8
        };
        foreach (var a in args) info.ArgumentList.Add(a);
        using var process = Process.Start(info) ?? throw new InvalidOperationException("secret-tool did not start.");
        // Drain stderr so a full pipe can't stall the tool.
        var error = process.StandardError.ReadToEndAsync();
        if (stdin is not null) process.StandardInput.Write(stdin);
        process.StandardInput.Close();
        var output = process.StandardOutput.ReadToEnd();
        process.WaitForExit();
        error.Wait();
        return (process.ExitCode, output);
    }
}
