using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>The certificate the helper makes for the add-in's port (M3.5 spec §5.13).</summary>
public sealed class AddinCertificateTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-addin-cert-").FullName;
    private readonly PausableClock _clock = new();

    public AddinCertificateTests() => _clock.Pause();

    public void Dispose() => Directory.Delete(_root, true);

    private HelperPaths Paths => HelperPaths.For(_root);

    private AddinCertificate Certificate() => new(Paths, _clock);

    [Fact]
    public void MakesTheCertificateTheSpecDescribes()
    {
        var made = _clock.GetUtcNow();

        using var cert = Certificate().LoadOrCreate();

        Assert.Equal("CN=Healdan Helper (this Mac)", cert.Subject);
        Assert.Equal(cert.Subject, cert.Issuer); // self-signed

        using var rsa = cert.GetRSAPublicKey()!;
        Assert.Equal(2048, rsa.KeySize);
        Assert.Equal("1.2.840.113549.1.1.11", cert.SignatureAlgorithm.Value); // sha256WithRSAEncryption

        var san = Assert.Single(cert.Extensions.OfType<X509SubjectAlternativeNameExtension>());
        Assert.Equal(["localhost"], san.EnumerateDnsNames());
        Assert.Equal([IPAddress.Loopback], san.EnumerateIPAddresses());

        var basic = Assert.Single(cert.Extensions.OfType<X509BasicConstraintsExtension>());
        Assert.False(basic.CertificateAuthority);
        Assert.True(basic.Critical);

        var usage = Assert.Single(cert.Extensions.OfType<X509KeyUsageExtension>());
        Assert.Equal(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, usage.KeyUsages);

        var enhanced = Assert.Single(cert.Extensions.OfType<X509EnhancedKeyUsageExtension>());
        Assert.Equal(["1.3.6.1.5.5.7.3.1"], enhanced.EnhancedKeyUsages.Cast<Oid>().Select(o => o.Value)); // server authentication

        // 800 days from now, and valid from a day ago so a Mac whose clock is a little slow still accepts it.
        Assert.InRange((cert.NotAfter.ToUniversalTime() - made).TotalDays, 799.99, 800.01);
        Assert.InRange((made - cert.NotBefore.ToUniversalTime()).TotalDays, 0.99, 1.01);
    }

    [Fact]
    public void KeepsTheCertificateAndKeyInPemFilesOnlyTheUserCanRead()
    {
        using var cert = Certificate().LoadOrCreate();

        Assert.Equal(Path.Combine(_root, "addin-cert.pem"), Paths.AddinCertificate);
        Assert.Equal(Path.Combine(_root, "addin-key.pem"), Paths.AddinKey);
        Assert.Contains("-----BEGIN CERTIFICATE-----", File.ReadAllText(Paths.AddinCertificate));
        Assert.Contains("-----BEGIN PRIVATE KEY-----", File.ReadAllText(Paths.AddinKey));
        Assert.DoesNotContain("PRIVATE KEY", File.ReadAllText(Paths.AddinCertificate));
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite, File.GetUnixFileMode(Paths.AddinKey));
        Assert.Empty(Directory.GetFiles(_root, "*.tmp")); // renamed into place, none left behind
        Assert.False(cert.HasPrivateKey); // LoadOrCreate answers the certificate; ForServer adds the key
    }

    [Fact]
    public void KeepsAnExistingCertificate()
    {
        using var first = Certificate().LoadOrCreate();
        _clock.Advance(TimeSpan.FromDays(400));

        using var again = Certificate().LoadOrCreate();

        Assert.Equal(first.Thumbprint, again.Thumbprint);
    }

    [Fact]
    public void RenewsWithinThirtyDaysOfExpiry()
    {
        using var first = Certificate().LoadOrCreate();

        _clock.Advance(TimeSpan.FromDays(769)); // 31 days left
        using var stillGood = Certificate().LoadOrCreate();
        Assert.Equal(first.Thumbprint, stillGood.Thumbprint);

        _clock.Advance(TimeSpan.FromDays(2)); // 29 days left
        using var renewed = Certificate().LoadOrCreate();

        Assert.NotEqual(first.Thumbprint, renewed.Thumbprint);
        Assert.InRange((renewed.NotAfter.ToUniversalTime() - _clock.GetUtcNow()).TotalDays, 799.99, 800.01);
        using var kept = Certificate().LoadOrCreate();
        Assert.Equal(renewed.Thumbprint, kept.Thumbprint);
    }

    [Fact]
    public void MakesAgainWhatCannotBeRead()
    {
        Directory.CreateDirectory(_root);
        File.WriteAllText(Paths.AddinCertificate, "not a certificate");
        File.WriteAllText(Paths.AddinKey, "not a key");

        using var cert = Certificate().LoadOrCreate();

        Assert.Equal("CN=Healdan Helper (this Mac)", cert.Subject);
        using var server = Certificate().ForServer();
        Assert.True(server.HasPrivateKey);
    }

    [Fact]
    public void MakesAgainWhenTheKeyIsNotTheCertificates()
    {
        using var first = Certificate().LoadOrCreate();
        using var rsa = RSA.Create(2048);
        File.WriteAllText(Paths.AddinKey, rsa.ExportPkcs8PrivateKeyPem()); // another key

        using var again = Certificate().LoadOrCreate();

        Assert.NotEqual(first.Thumbprint, again.Thumbprint);
        using var server = Certificate().ForServer();
        Assert.True(server.HasPrivateKey);
    }

    [Fact]
    public async Task ServesTls()
    {
        using var server = Certificate().ForServer();
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var served = Task.Run(async () =>
            {
                using var client = await listener.AcceptTcpClientAsync();
                await using var tls = new SslStream(client.GetStream());
                await tls.AuthenticateAsServerAsync(server);
                var seen = new byte[1024];
                var read = await tls.ReadAsync(seen);
                await tls.WriteAsync("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"u8.ToArray());
                await tls.FlushAsync();
                return read;
            });
            // Accepts exactly this certificate, as the add-in's web view will once the Mac trusts it.
            using var http = new HttpClient(new HttpClientHandler
            {
                ServerCertificateCustomValidationCallback = (_, cert, _, _) => cert?.Thumbprint == server.Thumbprint,
            });

            using var response = await http.GetAsync($"https://127.0.0.1:{port}/").WaitAsync(TimeSpan.FromSeconds(30));

            Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
            Assert.True(await served.WaitAsync(TimeSpan.FromSeconds(30)) > 0);
        }
        finally
        {
            listener.Stop();
        }
    }

    [Fact]
    public void NeedsSetUpUntilTrusted()
    {
        var certificate = Certificate();
        var trust = new FakeCertificateTrust();
        Assert.True(certificate.NeedsSetUp(trust)); // none made yet
        Assert.False(File.Exists(Paths.AddinCertificate)); // asking doesn't make one

        using var cert = certificate.LoadOrCreate();
        Assert.True(certificate.NeedsSetUp(trust)); // made, not trusted
        Assert.Equal([cert.Thumbprint], trust.Asked);

        trust.Trusted.Add(cert.Thumbprint);
        Assert.False(certificate.NeedsSetUp(trust));
    }

    [Fact]
    public void NeedsSetUpOnceItIsExpiringEvenWhileTrusted()
    {
        var certificate = Certificate();
        using var cert = certificate.LoadOrCreate();
        var trust = new FakeCertificateTrust { Trusted = { cert.Thumbprint } };
        Assert.False(certificate.NeedsSetUp(trust));

        _clock.Advance(TimeSpan.FromDays(771)); // 29 days left

        Assert.True(certificate.NeedsSetUp(trust));
        using var renewed = certificate.LoadOrCreate();
        Assert.True(certificate.NeedsSetUp(trust)); // the renewed one isn't trusted yet
        trust.Trusted.Add(renewed.Thumbprint);
        Assert.False(certificate.NeedsSetUp(trust));
    }
}
