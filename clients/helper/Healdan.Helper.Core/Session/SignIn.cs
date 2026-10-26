using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// Signing in once, and keeping only the device token, in the keychain (spec §5.2). The password is in memory for the
/// one call; no file, log or exception message holds it or the token.
/// </summary>
/// <param name="revokeTimeout">How long Sign Out waits for the server to revoke the token; 30 s by default.</param>
public sealed class SignIn(HealdanApi api, IKeychain keychain, DeviceIdentity device, TimeSpan? revokeTimeout = null)
{
    /// <summary>The API version this helper speaks.</summary>
    public const string SupportedApiVersion = "v1";

    /// <summary>Sign Out waits this long for the revoke, not the API client's ten minutes, as the Sign In window does.</summary>
    private readonly TimeSpan _revokeTimeout = revokeTimeout ?? TimeSpan.FromSeconds(30);

    /// <summary>The keychain account: the server's base URL, <c>https://host[:port]</c>.</summary>
    private string Account => api.Server.GetLeftPart(UriPartial.Authority).ToLowerInvariant();

    /// <summary>
    /// Takes the token from the keychain and checks it with <c>/me</c>. A <c>401</c> means it expired or was revoked:
    /// it is removed and the answer is false, so the caller asks for the password. Other failures (no network)
    /// propagate and keep the token.
    /// </summary>
    public async Task<bool> TryResumeAsync(CancellationToken ct = default)
    {
        var token = keychain.Load(Account);
        if (token is null) return false;
        api.Token = token;
        try
        {
            await api.GetJsonAsync("v1/me", ct);
            return true;
        }
        catch (ApiException e) when (e.Status == 401)
        {
            api.Token = null;
            keychain.Delete(Account);
            return false;
        }
    }

    /// <summary>
    /// Checks the server is Healdan and speaks this API, signs in with this computer's device id, and keeps the token.
    /// </summary>
    /// <exception cref="InvalidOperationException">The server isn't Healdan, or its API isn't one this helper speaks.</exception>
    public async Task SignInAsync(string user, string password, CancellationToken ct = default)
    {
        var server = await api.ServerAsync(ct);
        if (server.Product != "Healdan")
            throw new InvalidOperationException("That address isn't a Healdan server.");
        if (server.ApiVersion != SupportedApiVersion)
            throw new InvalidOperationException(
                $"This server speaks API {server.ApiVersion}, which this version of Healdan Helper doesn't. Update the helper.");
        var token = await api.SignInAsync(user, password, device.Name, device.Id, ct);
        keychain.Save(Account, token);
    }

    /// <summary>
    /// Runs <paramref name="flush"/> (the waiting saves) first, then revokes the token and clears the keychain. A token
    /// the server already refuses (<c>401</c>) counts as revoked. If the flush or the revoke fails the token is kept; a
    /// revoke that gets no answer within the time allowed throws <see cref="OperationCanceledException"/>.
    /// </summary>
    public async Task SignOutAsync(Func<Task> flush, CancellationToken ct = default)
    {
        await flush();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(_revokeTimeout);
        try
        {
            await api.SignOutAsync(timeout.Token);
        }
        catch (ApiException e) when (e.Status == 401)
        {
            // already expired or revoked
        }
        ForgetHere();
    }

    /// <summary>
    /// Forgets the token on this computer without asking the server, which goes on accepting it until it expires: for
    /// a server Sign Out can't reach, once the user has said so.
    /// </summary>
    public void ForgetHere()
    {
        api.Token = null;
        keychain.Delete(Account);
    }
}
