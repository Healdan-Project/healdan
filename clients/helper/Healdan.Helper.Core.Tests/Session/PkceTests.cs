using System.Text.RegularExpressions;
using Healdan.Helper.Core.Session;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>The PKCE verifier and challenge (RFC 7636) the helper's browser sign-in uses.</summary>
public sealed partial class PkceTests
{
    [GeneratedRegex("^[A-Za-z0-9_-]{43}$")]
    private static partial Regex Base64UrlOf32Bytes();

    [Fact]
    public void ChallengeIsTheVerifiersSha256()
    {
        // RFC 7636, appendix B
        Assert.Equal("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.ChallengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));

        var (verifier, challenge) = Pkce.Make();
        Assert.Equal(Pkce.ChallengeOf(verifier), challenge);
        Assert.Matches(Base64UrlOf32Bytes(), verifier); // 32 random bytes, base64url, no padding: what the contract allows
        Assert.Matches(Base64UrlOf32Bytes(), challenge); // a SHA-256 is 32 bytes too
    }

    [Fact]
    public void EachMakeIsDifferent()
    {
        var first = Pkce.Make();
        var second = Pkce.Make();

        Assert.NotEqual(first.Verifier, second.Verifier);
        Assert.NotEqual(first.Challenge, second.Challenge);
    }

    [Fact]
    public void AStateIsRandomAndAllowedInAnAddress()
    {
        var first = Pkce.NewState();

        Assert.Matches(Base64UrlOf32Bytes(), first);
        Assert.NotEqual(first, Pkce.NewState());
    }
}
