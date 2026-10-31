using System.Security.Cryptography.X509Certificates;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// Whether the computer trusts the helper's own certificate for the add-in's port, and asking it to (M3.5 spec §5.13).
/// The computer's trust is a platform piece: macOS's is <c>MacCertificateTrust</c>, in the app.
/// </summary>
public interface ICertificateTrust
{
    /// <summary>
    /// Whether the computer trusts the certificate for a secure connection to <c>127.0.0.1</c>, as a web view finds when it
    /// connects, with the user's own trust settings counted.
    /// </summary>
    bool IsTrusted(X509Certificate2 cert);

    /// <summary>
    /// Asks the computer to trust the certificate in the PEM file, which asks the user for their password. False when the
    /// user cancels; a failure to ask is thrown.
    /// </summary>
    Task<bool> TrustAsync(string certPemPath, CancellationToken ct);
}
