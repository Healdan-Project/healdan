using System.Security.Cryptography.X509Certificates;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Addin;

/// <summary>When Set Up Microsoft Office applies and is offered, and what its Set Up does (M3.5 spec §5.13).</summary>
public sealed class OfficeSetUpTests : IDisposable
{
    private static readonly InstalledApps WordOnly = new(LibreOffice: true, Word: true, Excel: false, PowerPoint: false);
    private static readonly InstalledApps NoOffice = new(LibreOffice: true, Word: false, Excel: false, PowerPoint: false);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-setup-").FullName;
    private readonly PausableClock _clock = new();
    private readonly FakeCertificateTrust _trust = new();

    public OfficeSetUpTests() => _clock.Pause();

    public void Dispose() => Directory.Delete(_root, true);

    private HelperPaths Paths => HelperPaths.For(_root);

    private OfficeSetUp SetUp(InstalledApps? installed = null, bool macOS = true) =>
        new(Paths, new AddinCertificate(Paths, _clock), _trust, () => installed ?? WordOnly, macOS);

    [Theory]
    [InlineData(true, true, true)]
    [InlineData(true, false, false)]
    [InlineData(false, true, false)] // nothing off a Mac: Microsoft Office is a Mac app here, whatever InstalledApps says
    [InlineData(false, false, false)]
    public void AppliesToAMacWithMicrosoftOffice(bool macOS, bool office, bool applies)
    {
        var setUp = SetUp(office ? WordOnly : NoOffice, macOS);

        Assert.Equal(applies, setUp.Applies);
        Assert.Equal(applies, setUp.Needed); // no certificate yet, so it is needed wherever it applies
    }

    [Fact]
    public void ExcelOrPowerPointAloneIsEnough()
    {
        Assert.True(SetUp(new InstalledApps(false, false, true, false)).Applies);
        Assert.True(SetUp(new InstalledApps(false, false, false, true)).Applies);
    }

    [Fact]
    public void OffAMacItIsNeverNeededAndTheCertificateIsNotEvenLookedAt()
    {
        var setUp = SetUp(WordOnly, macOS: false);

        Assert.False(setUp.Needed);
        Assert.False(setUp.TakeFirstOffer());
        Assert.Empty(_trust.Asked);
        Assert.False(File.Exists(Paths.AddinCertificate));
        Assert.False(HelperSettings.Load(Paths).OfficeSetUpOffered);
    }

    [Fact]
    public async Task IsNeededUntilTheCertificateIsTrusted()
    {
        var setUp = SetUp();
        Assert.True(setUp.Needed);

        var result = await setUp.TrustAsync(CancellationToken.None);

        Assert.Equal(SetUpResult.Done, result);
        Assert.False(setUp.Needed);
    }

    [Fact]
    public void TheFirstOfferComesOnceAndIsRemembered()
    {
        Assert.True(SetUp().TakeFirstOffer());
        Assert.True(HelperSettings.Load(Paths).OfficeSetUpOffered);

        Assert.False(SetUp().TakeFirstOffer()); // the next start, still not set up
        Assert.True(SetUp().Needed); // but the menu still offers it
    }

    [Fact]
    public void NothingIsOfferedWhenThereIsNothingToSetUp()
    {
        Assert.False(SetUp(NoOffice).TakeFirstOffer());
        Assert.False(HelperSettings.Load(Paths).OfficeSetUpOffered); // it can still be offered once Office is there
        Assert.True(SetUp().TakeFirstOffer());
    }

    [Fact]
    public async Task NothingIsOfferedWhenItIsDoneAlready()
    {
        await SetUp().TrustAsync(CancellationToken.None);

        Assert.False(SetUp().TakeFirstOffer());
    }

    [Fact]
    public async Task SetUpMakesTheCertificateAndAsksMacOSToTrustIt()
    {
        string? shown = null;
        _trust.OnTrust = path =>
        {
            shown = File.ReadAllText(path); // it is there when macOS is asked
            return true;
        };

        var result = await SetUp().TrustAsync(CancellationToken.None);

        Assert.True(result.Trusted);
        Assert.Null(result.Problem);
        Assert.Equal([Paths.AddinCertificate], _trust.Trusting);
        Assert.Contains("BEGIN CERTIFICATE", shown);
    }

    [Fact]
    public async Task ACancelledPasswordPromptIsNotAFailure()
    {
        _trust.OnTrust = _ => false;

        var result = await SetUp().TrustAsync(CancellationToken.None);

        Assert.Equal(SetUpResult.Cancelled, result);
        Assert.False(result.Trusted);
        Assert.Null(result.Problem);
        Assert.True(SetUp().Needed);
    }

    [Fact]
    public async Task AFailureCarriesWhatWentWrong()
    {
        _trust.OnTrust = _ => throw new InvalidOperationException("One or more parameters passed to a function were not valid.");

        var result = await SetUp().TrustAsync(CancellationToken.None);

        Assert.False(result.Trusted);
        Assert.Equal("Set Up failed: One or more parameters passed to a function were not valid.", result.Problem);
    }

    [Fact]
    public async Task ACertificateMacOSTookButDoesNotTrustIsAFailure()
    {
        var trust = new FakeCertificateTrust { OnTrust = _ => true };
        var setUp = new OfficeSetUp(Paths, new AddinCertificate(Paths, _clock), new Forgetful(trust), () => WordOnly, true);

        var result = await setUp.TrustAsync(CancellationToken.None);

        Assert.False(result.Trusted);
        Assert.Contains("doesn't trust", result.Problem);
    }

    /// <summary>A Mac that accepts the certificate and then does not trust it.</summary>
    private sealed class Forgetful(FakeCertificateTrust inner) : ICertificateTrust
    {
        public bool IsTrusted(X509Certificate2 cert) => false;

        public Task<bool> TrustAsync(string certPemPath, CancellationToken ct) => inner.TrustAsync(certPemPath, ct);
    }
}
