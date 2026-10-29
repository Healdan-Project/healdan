using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// Signing in once, and keeping only the device token, in the keychain (spec §5.2), with the device id it was issued
/// for (<see cref="KeptToken"/>). The password is in memory for the one call; no file, log or exception message holds
/// it or the token.
/// </summary>
/// <param name="revokeTimeout">How long Sign Out, or the revoke of a kept token that isn't this computer's, waits for the server; 30 s by default.</param>
/// <param name="log">Where the revoke of a kept token that isn't this computer's is noted.</param>
public sealed class SignIn(HealdanApi api, IKeychain keychain, DeviceIdentity device, TimeSpan? revokeTimeout = null,
    HelperLog? log = null)
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
    /// propagate and keep the token. A token issued to another device id, or kept without one by an older helper, is
    /// never used: the server would record this computer's check-outs under that device, and this computer would then
    /// read them as another's. It's revoked and removed, and the answer is false.
    /// </summary>
    public async Task<bool> TryResumeAsync(CancellationToken ct = default)
    {
        var secret = keychain.Load(Account);
        if (secret is null) return false;
        var kept = KeptToken.Read(secret);
        if (kept?.Device != device.Id)
        {
            await DiscardAsync(kept?.Token ?? secret,
                kept is null ? "was kept without its device id" : $"was issued to device {kept.Device}, not this computer's {device.Id}", ct);
            return false;
        }
        api.Token = kept.Token;
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
    /// Revokes a kept token that isn't this computer's, then removes it. It's removed whether or not Healdan answers:
    /// one it couldn't revoke is still accepted there until it expires, but nothing here uses it again.
    /// </summary>
    private async Task DiscardAsync(string token, string why, CancellationToken ct)
    {
        log?.Warn($"The sign-in kept for {Account} {why}, so it's revoked and a new one is asked for");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(_revokeTimeout);
        api.Token = token;
        try
        {
            await api.SignOutAsync(timeout.Token);
        }
        catch (ApiException e) when (e.Status == 401)
        {
            // already expired or revoked
        }
        catch (Exception e) when (e is ApiException or HttpRequestException or IOException
                                      || (e is OperationCanceledException && !ct.IsCancellationRequested))
        {
            log?.Warn($"Healdan couldn't revoke that sign-in ({e.GetType().Name}); it's forgotten here all the same");
        }
        finally
        {
            api.Token = null;
        }
        keychain.Delete(Account);
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
        keychain.Save(Account, new KeptToken(device.Id, token).ToSecret());
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
    /// a server Sign Out can't reach, once the user has said so. The keychain goes first, so a keychain that refuses
    /// leaves the client its token, and the sign-in carries on as it was.
    /// </summary>
    public void ForgetHere()
    {
        keychain.Delete(Account);
        api.Token = null;
    }
}
