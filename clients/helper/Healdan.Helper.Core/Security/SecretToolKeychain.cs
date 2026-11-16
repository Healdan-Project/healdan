// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.ComponentModel;
using System.Diagnostics;
using System.Text;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// The Secret Service on Ubuntu, through <c>secret-tool</c>. The secret goes in on its standard input, never on the
/// command line where <c>ps</c> would show it. Every failure that isn't "nothing kept" is an
/// <see cref="InvalidOperationException"/>, which the session reads as "the keychain couldn't be read" and answers by
/// asking for the sign-in again: a missing tool, a keyring that doesn't answer, an error from the tool.
/// </summary>
/// <param name="service">The service name the items are kept under.</param>
/// <param name="tool">The tool to run; only tests name another.</param>
/// <param name="timeout">
/// How long one call may take (two minutes by default): a locked keyring makes GNOME ask for its password, and the user
/// needs time to type it.
/// </param>
public sealed class SecretToolKeychain(string service = "Healdan Helper", string tool = "secret-tool", TimeSpan? timeout = null)
    : IKeychain
{
    private readonly TimeSpan _timeout = timeout ?? TimeSpan.FromMinutes(2);

    public string? Load(string account)
    {
        var (code, output, error) = Run(["lookup", "service", service, "account", account], null);
        if (code == 0) return output;
        // secret-tool exits 1 with nothing at all when no item matches; a locked or missing keyring says why.
        if (code == 1 && output.Length == 0 && error.Length == 0) return null;
        throw Failed("read", code, error);
    }

    public void Save(string account, string secret)
    {
        var (code, _, error) = Run(["store", "--label=Healdan Helper", "service", service, "account", account], secret);
        if (code != 0) throw Failed("store", code, error);
    }

    public void Delete(string account)
    {
        // clear exits non-zero when nothing matched; either way the item is gone afterwards.
        Run(["clear", "service", service, "account", account], null);
    }

    /// <summary>The tool's last line of error text, when it had any: never the secret, which only goes in on stdin.</summary>
    private static InvalidOperationException Failed(string what, int code, string error)
    {
        var last = error.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).LastOrDefault();
        return new InvalidOperationException(
            $"secret-tool could not {what} the sign-in (exit {code})" + (last is null ? "." : $": {last}"));
    }

    private (int Code, string Output, string Error) Run(string[] args, string? stdin)
    {
        var info = new ProcessStartInfo(tool)
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            StandardOutputEncoding = Encoding.UTF8
        };
        foreach (var a in args) info.ArgumentList.Add(a);
        Process process;
        try
        {
            process = Process.Start(info) ?? throw new InvalidOperationException("secret-tool did not start.");
        }
        catch (Win32Exception e)
        {
            throw new InvalidOperationException("secret-tool isn't installed. Install the libsecret-tools package.", e);
        }
        using (process)
        {
            // Read both ends as the tool runs, so a full pipe can't stall it.
            var output = process.StandardOutput.ReadToEndAsync();
            var error = process.StandardError.ReadToEndAsync();
            try
            {
                if (stdin is not null) process.StandardInput.Write(stdin);
                process.StandardInput.Close();
            }
            catch (IOException)
            {
                // the tool ended without reading it; its exit code says what happened
            }
            if (!process.WaitForExit(_timeout))
            {
                try
                {
                    process.Kill(true);
                }
                catch (InvalidOperationException)
                {
                    // it ended just now
                }
                process.WaitForExit();
                throw new InvalidOperationException(
                    $"The keyring didn't answer within {_timeout.TotalSeconds:0} seconds. If it is locked, unlock it and try again.");
            }
            return (process.ExitCode, output.Result, error.Result);
        }
    }
}
