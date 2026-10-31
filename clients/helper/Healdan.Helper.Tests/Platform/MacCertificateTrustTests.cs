using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Tests.Platform;

/// <summary>
/// macOS's trust for the helper's certificate. Nothing here changes the Mac's trust settings: asking <c>security</c> to
/// trust a certificate asks the user for their password, so that is checked by hand (the plan's Task 6, Step 5).
/// </summary>
public sealed class MacCertificateTrustTests
{
    [Fact]
    public void AsksSecurityToTrustTheCertificateAsARootForSslInTheLoginKeychain() =>
        Assert.Equal(
            ["add-trusted-cert", "-r", "trustRoot", "-p", "ssl", "-k", "/Users/robert/Library/Keychains/login.keychain-db", "/Users/robert/Library/Application Support/Healdan Helper/addin-cert.pem"],
            MacCertificateTrust.Arguments("/Users/robert", "/Users/robert/Library/Application Support/Healdan Helper/addin-cert.pem"));

    [Theory]
    [InlineData("SecTrustSettingsSetTrustSettings: The authorization was canceled by the user.", true)]
    [InlineData("User canceled the operation.", true)]
    [InlineData("One or more parameters passed to a function were not valid.", false)]
    [InlineData("", false)]
    public void RecognisesTheUserCancellingThePasswordPrompt(string error, bool cancelled) =>
        Assert.Equal(cancelled, MacCertificateTrust.UserCancelled(error));

    [MacFact]
    public void ACertificateNobodyTrustedIsNotTrusted()
    {
        using var cert = Made();

        Assert.False(new MacCertificateTrust().IsTrusted(cert));
        Assert.False(MacCertificateTrust.IsTrustedFor(cert, "127.0.0.1", trustItself: false));
    }

    /// <summary>
    /// Once trusted, macOS takes the certificate for a secure connection to the names it is made for and no others. The
    /// trust is stood in for by the certificate being its own anchor: the user's settings stay as they are.
    /// </summary>
    [MacFact]
    public void ATrustedCertificateIsGoodForLoopbackAndNothingElse()
    {
        using var cert = Made();

        Assert.True(MacCertificateTrust.IsTrustedFor(cert, "127.0.0.1", trustItself: true));
        Assert.True(MacCertificateTrust.IsTrustedFor(cert, "localhost", trustItself: true));
        Assert.False(MacCertificateTrust.IsTrustedFor(cert, "healdan.example", trustItself: true));
    }

    private static X509Certificate2 Made()
    {
        var root = Directory.CreateTempSubdirectory("healdan-trust-").FullName;
        try
        {
            return new AddinCertificate(HelperPaths.For(root), TimeProvider.System).LoadOrCreate();
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }
}
