using System.Security.Cryptography;
using System.Text;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// The proof key a desktop sign-in sends with its code (RFC 7636, <c>S256</c>): the helper keeps the verifier to itself
/// until it exchanges the code, so a code taken off the loopback address is useless to whoever took it. The state
/// (<see cref="NewState"/>) ties the answer to the helper's own request. Never logged.
/// </summary>
public static class Pkce
{
    /// <summary>A new verifier, 32 random bytes in base64url, and its challenge: the SHA-256 of the verifier, in base64url.</summary>
    public static (string Verifier, string Challenge) Make()
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        return (verifier, ChallengeOf(verifier));
    }

    /// <summary>A new state, 32 random bytes in base64url: the letters the server accepts in one.</summary>
    internal static string NewState() => Base64Url(RandomNumberGenerator.GetBytes(32));

    /// <summary>The <c>S256</c> challenge of <paramref name="verifier"/>.</summary>
    internal static string ChallengeOf(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
