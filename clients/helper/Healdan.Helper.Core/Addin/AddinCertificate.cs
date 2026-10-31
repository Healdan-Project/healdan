using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Addin;

/// <summary>
/// The certificate for the add-in's port (M3.5 spec §5.8, §5.13). Office's web view blocks a secure page's requests to
/// <c>http://127.0.0.1</c>, so the port is HTTPS, with a certificate the Mac trusts: the helper's own, which the user
/// trusts once (Set Up Microsoft Office). It is self-signed and good for <c>localhost</c> and <c>127.0.0.1</c> only, not a
/// certificate authority, with its key in a file only the user can read. Set Up makes it when missing, and again once fewer
/// than 30 of its 800 days are left, which needs trusting again; the port only ever serves the one there is.
/// </summary>
public sealed class AddinCertificate(HelperPaths paths, TimeProvider clock)
{
    /// <summary>The certificate's subject.</summary>
    public const string Subject = "CN=Healdan Helper (this Mac)";

    /// <summary>How long a certificate lasts. macOS refuses a server certificate valid for more than 825 days.</summary>
    private static readonly TimeSpan Lifetime = TimeSpan.FromDays(800);

    /// <summary>It starts a day before it is made, so a computer whose clock is a little slow still accepts it.</summary>
    private static readonly TimeSpan Backdate = TimeSpan.FromDays(1);

    /// <summary>A certificate with fewer days than this left is made again.</summary>
    private static readonly TimeSpan RenewWithin = TimeSpan.FromDays(30);

    private const string ServerAuthentication = "1.3.6.1.5.5.7.3.1";

    private const UnixFileMode PrivateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;

    private readonly object _gate = new();

    /// <summary>
    /// The certificate (not its key), made when it is missing, can't be read, or has fewer than 30 days left. The caller
    /// disposes it.
    /// </summary>
    public X509Certificate2 LoadOrCreate()
    {
        lock (_gate)
        {
            var kept = TryLoad();
            if (kept is not null && !Expiring(kept))
                return kept;
            kept?.Dispose();
            return Create();
        }
    }

    /// <summary>
    /// The certificate with its private key, for the add-in's port (<see cref="System.Net.Security.SslStream"/>), while there
    /// is one that hasn't expired; null otherwise. It never makes or renews one: that's Set Up Microsoft Office's
    /// (<see cref="LoadOrCreate"/>), which has the Mac trust it, so the port never serves a certificate the user wasn't asked
    /// to trust in place of one they were. On macOS the key has to be imported again to be used for TLS, so the pair is
    /// exported as PKCS#12 and loaded from that. The caller disposes it.
    /// </summary>
    public X509Certificate2? ForServer()
    {
        lock (_gate)
        {
            using var kept = TryLoad();
            var now = clock.GetUtcNow();
            if (kept is null || kept.NotAfter.ToUniversalTime() <= now || kept.NotBefore.ToUniversalTime() > now)
                return null;
            using var pair = X509Certificate2.CreateFromPemFile(paths.AddinCertificate, paths.AddinKey);
            return X509CertificateLoader.LoadPkcs12(pair.Export(X509ContentType.Pkcs12), null);
        }
    }

    /// <summary>
    /// True when Set Up Microsoft Office has something to do: there is no certificate yet, it has fewer than 30 days left,
    /// or <paramref name="trust"/> doesn't trust it. It makes nothing.
    /// </summary>
    public bool NeedsSetUp(ICertificateTrust trust)
    {
        X509Certificate2? cert;
        lock (_gate)
            cert = TryLoad();
        if (cert is null)
            return true;
        using (cert)
            return Expiring(cert) || !trust.IsTrusted(cert);
    }

    private bool Expiring(X509Certificate2 cert) => cert.NotAfter.ToUniversalTime() - clock.GetUtcNow() < RenewWithin;

    /// <summary>The certificate in its file, if the file is a certificate and the key file is its key; otherwise null.</summary>
    private X509Certificate2? TryLoad()
    {
        try
        {
            using var pair = X509Certificate2.CreateFromPemFile(paths.AddinCertificate, paths.AddinKey);
            return pair.HasPrivateKey ? X509CertificateLoader.LoadCertificate(pair.RawData) : null;
        }
        catch (Exception e) when (e is CryptographicException or ArgumentException or IOException or UnauthorizedAccessException)
        {
            return null; // missing, damaged, or a key that isn't the certificate's: made again
        }
    }

    private X509Certificate2 Create()
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(new X500DistinguishedName(Subject), rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, critical: true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, critical: true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid(ServerAuthentication)], critical: false));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, critical: false));
        var names = new SubjectAlternativeNameBuilder();
        names.AddDnsName("localhost");
        names.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(names.Build());

        var now = clock.GetUtcNow();
        using var made = request.CreateSelfSigned(now - Backdate, now + Lifetime);

        Directory.CreateDirectory(paths.Support);
        // Neither file is ever half written. A crash between the two leaves a certificate and a key that don't match, which
        // TryLoad takes as no certificate, so the next call makes them again.
        WriteAtomically(paths.AddinKey, rsa.ExportPkcs8PrivateKeyPem(), PrivateMode);
        WriteAtomically(paths.AddinCertificate, made.ExportCertificatePem(), null);
        return X509CertificateLoader.LoadCertificate(made.RawData);
    }

    /// <summary>Written to a temporary file, with its mode from the start, then renamed over the old one.</summary>
    private static void WriteAtomically(string path, string pem, UnixFileMode? mode)
    {
        var temp = path + $".{Guid.NewGuid():N}.tmp";
        try
        {
            var options = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write };
            if (mode is { } m && !OperatingSystem.IsWindows())
                options.UnixCreateMode = m;
            using (var stream = new StreamWriter(temp, options))
                stream.Write(pem);
            File.Move(temp, path, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }
}
