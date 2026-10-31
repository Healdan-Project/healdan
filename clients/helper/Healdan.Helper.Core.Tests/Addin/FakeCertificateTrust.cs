using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>The Mac's trust as the tests drive it: certificates trusted by thumbprint, and what asking to trust one does.</summary>
public sealed class FakeCertificateTrust : ICertificateTrust
{
    public HashSet<string> Trusted { get; } = [];

    /// <summary>The thumbprints <see cref="IsTrusted"/> was asked about.</summary>
    public List<string> Asked { get; } = [];

    /// <summary>The paths <see cref="TrustAsync"/> was asked to trust.</summary>
    public List<string> Trusting { get; } = [];

    /// <summary>What asking to trust a certificate does: by default the user accepts, and the certificate is trusted from then on.</summary>
    public Func<string, bool> OnTrust { get; set; } = _ => true;

    public bool IsTrusted(X509Certificate2 cert)
    {
        Asked.Add(cert.Thumbprint);
        return Trusted.Contains(cert.Thumbprint);
    }

    public Task<bool> TrustAsync(string certPemPath, CancellationToken ct)
    {
        Trusting.Add(certPemPath);
        var accepted = OnTrust(certPemPath);
        if (accepted)
            Trusted.Add(X509Certificate2.CreateFromPem(File.ReadAllText(certPemPath)).Thumbprint);
        return Task.FromResult(accepted);
    }
}
