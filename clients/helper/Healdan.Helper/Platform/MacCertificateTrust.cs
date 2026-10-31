using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Platform;

/// <summary>
/// macOS's trust for the helper's own certificate (M3.5 spec §5.13). Trusting runs <c>security add-trusted-cert</c>, which
/// asks the user for their password and trusts the certificate for SSL in their login keychain.
/// <para>Whether it is trusted asks Security.framework the question Safari and Office's web view ask: a TLS server
/// evaluation for <c>127.0.0.1</c>, with the user's trust settings counted. <c>X509Chain</c> would give the wrong
/// answer: on macOS it evaluates with the basic X.509 policy, which a trust setting for SSL alone doesn't apply to, so it
/// calls a certificate macOS trusts for SSL "untrusted" (tried on the M3.5 trial's own certificate).</para>
/// </summary>
internal sealed class MacCertificateTrust(string? home = null) : ICertificateTrust
{
    private const string Security = "/System/Library/Frameworks/Security.framework/Security";
    private const string CoreFoundation = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private const string SecurityTool = "/usr/bin/security";
    private const uint Utf8 = 0x08000100; // kCFStringEncodingUTF8

    /// <summary>The address the add-in's pages connect to (spec §5.8).</summary>
    private const string Host = "127.0.0.1";

    /// <summary>The password prompt waits for the user; this is only a stop for one left alone for good.</summary>
    private static readonly TimeSpan Limit = TimeSpan.FromMinutes(10);

    private readonly string _home = home ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

    public bool IsTrusted(X509Certificate2 cert) => OperatingSystem.IsMacOS() && IsTrustedFor(cert, Host, trustItself: false);

    /// <summary>
    /// A TLS server evaluation of the certificate for <paramref name="host"/> (null: any), with the system's, the
    /// administrator's and the user's trust settings. The certificate stands alone: it is trusted as its own anchor or not
    /// at all. <paramref name="trustItself"/> is for the tests: it evaluates the certificate as if the user had trusted it,
    /// which shows macOS accepts it for a secure connection, without changing anyone's trust settings.
    /// </summary>
    internal static bool IsTrustedFor(X509Certificate2 cert, string? host, bool trustItself)
    {
        IntPtr data = IntPtr.Zero, certificate = IntPtr.Zero, certificates = IntPtr.Zero, name = IntPtr.Zero, policy = IntPtr.Zero,
            trust = IntPtr.Zero, error = IntPtr.Zero;
        try
        {
            var der = cert.RawData;
            data = CFDataCreate(IntPtr.Zero, der, der.Length);
            certificate = data == IntPtr.Zero ? IntPtr.Zero : SecCertificateCreateWithData(IntPtr.Zero, data);
            if (certificate == IntPtr.Zero)
                return false;
            certificates = CFArrayCreate(IntPtr.Zero, [certificate], 1, IntPtr.Zero);
            if (host is not null)
                name = CFStringCreateWithCString(IntPtr.Zero, host, Utf8);
            policy = SecPolicyCreateSSL(true, name);
            return certificates != IntPtr.Zero && policy != IntPtr.Zero
                && SecTrustCreateWithCertificates(certificates, policy, out trust) == 0
                && (!trustItself || SecTrustSetAnchorCertificates(trust, certificates) == 0)
                && SecTrustEvaluateWithError(trust, out error);
        }
        finally
        {
            foreach (var handle in new[] { error, trust, policy, name, certificates, certificate, data })
            {
                if (handle != IntPtr.Zero)
                    CFRelease(handle);
            }
        }
    }

    public async Task<bool> TrustAsync(string certPemPath, CancellationToken ct)
    {
        if (!OperatingSystem.IsMacOS())
            throw new PlatformNotSupportedException("Only macOS trusts the certificate this way.");
        var start = new ProcessStartInfo(SecurityTool) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var argument in Arguments(_home, certPemPath))
            start.ArgumentList.Add(argument);

        using var process = Process.Start(start) ?? throw new InvalidOperationException("security couldn't be started.");
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(Limit);
        var output = process.StandardOutput.ReadToEndAsync(limit.Token);
        var error = process.StandardError.ReadToEndAsync(limit.Token);
        try
        {
            await process.WaitForExitAsync(limit.Token);
            await Task.WhenAll(output, error);
        }
        catch (OperationCanceledException)
        {
            Stop(process);
            if (ct.IsCancellationRequested)
                throw;
            throw new TimeoutException($"security didn't finish within {Limit.TotalMinutes:0} minutes.");
        }

        if (process.ExitCode == 0)
            return true;
        var text = (await error).Trim();
        if (UserCancelled(text))
            return false;
        throw new InvalidOperationException(text.Length > 0 ? text : $"security exited with {process.ExitCode}.");
    }

    /// <summary>
    /// <c>security</c>'s arguments to trust the certificate as a root for SSL in the user's login keychain. A shell never
    /// sees them. (<c>-r trustAsRoot</c> failed on a self-signed certificate in the M3.5 trial, with "One or more parameters
    /// passed to a function were not valid"; <c>-r trustRoot</c> worked.)
    /// </summary>
    internal static string[] Arguments(string home, string certPemPath) =>
        ["add-trusted-cert", "-r", "trustRoot", "-p", "ssl", "-k", Path.Combine(home, "Library", "Keychains", "login.keychain-db"), certPemPath];

    /// <summary>The user cancelled the password prompt: <c>security</c> says the authorization or the operation was canceled.</summary>
    internal static bool UserCancelled(string errorText) => errorText.Contains("cancel", StringComparison.OrdinalIgnoreCase);

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

    [DllImport(CoreFoundation)]
    private static extern IntPtr CFDataCreate(IntPtr allocator, byte[] bytes, nint length);

    [DllImport(CoreFoundation)]
    private static extern IntPtr CFArrayCreate(IntPtr allocator, IntPtr[] values, nint count, IntPtr callBacks);

    [DllImport(CoreFoundation)]
    private static extern IntPtr CFStringCreateWithCString(IntPtr allocator, [MarshalAs(UnmanagedType.LPUTF8Str)] string text, uint encoding);

    [DllImport(CoreFoundation)]
    private static extern void CFRelease(IntPtr cf);

    [DllImport(Security)]
    private static extern IntPtr SecCertificateCreateWithData(IntPtr allocator, IntPtr data);

    [DllImport(Security)]
    private static extern IntPtr SecPolicyCreateSSL([MarshalAs(UnmanagedType.U1)] bool server, IntPtr hostname);

    [DllImport(Security)]
    private static extern int SecTrustCreateWithCertificates(IntPtr certificates, IntPtr policies, out IntPtr trust);

    [DllImport(Security)]
    private static extern int SecTrustSetAnchorCertificates(IntPtr trust, IntPtr anchorCertificates);

    [DllImport(Security)]
    [return: MarshalAs(UnmanagedType.U1)]
    private static extern bool SecTrustEvaluateWithError(IntPtr trust, out IntPtr error);
}
