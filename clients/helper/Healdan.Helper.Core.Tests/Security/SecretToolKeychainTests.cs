// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Diagnostics;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Security;

/// <summary>The Secret Service on Ubuntu, driven through fake <c>secret-tool</c> scripts (Unix only).</summary>
public sealed class SecretToolKeychainTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "healdan-secret-tool-" + Guid.NewGuid().ToString("N"));

    public SecretToolKeychainTests() => Directory.CreateDirectory(_folder);

    public void Dispose() => TempFolder.Delete(_folder);

    [Fact]
    public void LoadReturnsWhatLookupPrints()
    {
        if (OperatingSystem.IsWindows()) return;

        var keychain = Keychain("printf 'the-secret'");

        Assert.Equal("the-secret", keychain.Load("me"));
    }

    [Fact]
    public void LoadAsksForTheServiceAndAccount()
    {
        if (OperatingSystem.IsWindows()) return;
        var args = Path.Combine(_folder, "args");

        Keychain($"echo \"$@\" > '{args}'; printf x").Load("me@example");

        Assert.Equal("lookup service Healdan Helper account me@example", File.ReadAllText(args).Trim());
    }

    [Fact]
    public void ASilentExitOneIsNothingKept()
    {
        if (OperatingSystem.IsWindows()) return;

        Assert.Null(Keychain("exit 1").Load("me"));
    }

    [Fact]
    public void AnExitWithAnErrorSaysWhatTheToolSaid()
    {
        if (OperatingSystem.IsWindows()) return;

        var keychain = Keychain("echo 'Cannot get secret of a locked object' >&2; echo 'last: keyring is locked' >&2; exit 1");

        var e = Assert.Throws<InvalidOperationException>(() => keychain.Load("me"));
        Assert.Contains("last: keyring is locked", e.Message);
    }

    [Fact]
    public void SaveGivesTheSecretOnStandardInputOnly()
    {
        if (OperatingSystem.IsWindows()) return;
        var stdin = Path.Combine(_folder, "stdin");
        var args = Path.Combine(_folder, "args");

        Keychain($"echo \"$@\" > '{args}'; cat > '{stdin}'").Save("me", "s3cr3t-value");

        Assert.Equal("s3cr3t-value", File.ReadAllText(stdin));
        Assert.DoesNotContain("s3cr3t-value", File.ReadAllText(args));
    }

    [Fact]
    public void ASaveThatFailsThrowsWithoutTheSecret()
    {
        if (OperatingSystem.IsWindows()) return;

        var e = Assert.Throws<InvalidOperationException>(
            () => Keychain("cat > /dev/null; echo 'no keyring' >&2; exit 1").Save("me", "s3cr3t-value"));

        Assert.Contains("no keyring", e.Message);
        Assert.DoesNotContain("s3cr3t-value", e.Message);
    }

    [Fact]
    public void DeleteIgnoresAToolThatFindsNothing()
    {
        if (OperatingSystem.IsWindows()) return;

        Keychain("exit 1").Delete("me");
    }

    [Fact]
    public void DeleteSaysWhenTheKeyringRefuses()
    {
        if (OperatingSystem.IsWindows()) return;

        var e = Assert.Throws<InvalidOperationException>(
            () => Keychain("echo 'Cannot delete a locked object' >&2; exit 1").Delete("me"));

        Assert.Equal("secret-tool could not clear the sign-in (exit 1): Cannot delete a locked object", e.Message);
    }

    [Fact]
    public void AMissingToolSaysToInstallLibsecretTools()
    {
        var keychain = new SecretToolKeychain(tool: Path.Combine(_folder, "no-such-secret-tool"));

        Assert.Contains("libsecret-tools", Assert.Throws<InvalidOperationException>(() => keychain.Load("me")).Message);
        Assert.Contains("libsecret-tools", Assert.Throws<InvalidOperationException>(() => keychain.Save("me", "x")).Message);
        Assert.Contains("libsecret-tools", Assert.Throws<InvalidOperationException>(() => keychain.Delete("me")).Message);
    }

    [Fact]
    public void AKeyringThatDoesNotAnswerIsKilledAndSaysSo()
    {
        if (OperatingSystem.IsWindows()) return;
        var keychain = Keychain("sleep 60", TimeSpan.FromSeconds(1));

        var clock = Stopwatch.StartNew();
        var e = Assert.Throws<InvalidOperationException>(() => keychain.Load("me"));

        Assert.Contains("didn't answer", e.Message);
        Assert.InRange(clock.Elapsed, TimeSpan.FromSeconds(0.8), TimeSpan.FromSeconds(10));
        Assert.Throws<InvalidOperationException>(() => keychain.Save("me", "x"));
        Assert.Throws<InvalidOperationException>(() => keychain.Delete("me"));
    }

    private SecretToolKeychain Keychain(string body, TimeSpan? timeout = null)
    {
        var path = Path.Combine(_folder, "secret-tool-" + Guid.NewGuid().ToString("N"));
        File.WriteAllText(path, "#!/bin/sh\n" + body + "\n");
        if (!OperatingSystem.IsWindows())
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        return new SecretToolKeychain(tool: path, timeout: timeout);
    }
}
