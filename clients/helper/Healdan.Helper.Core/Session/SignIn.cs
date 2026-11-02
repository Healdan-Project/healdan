using System.Security.Cryptography;
using System.Text;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// Signing in once, and keeping only the device token, in the keychain (spec §5.2), with the device id it was issued
/// for (<see cref="KeptToken"/>). The password is in memory for the one call; no file, log or exception message holds
/// it or the token. A sign-in through a provider (<see cref="SignInWithProviderAsync"/>, M4 spec §6.2) ends in the
/// same token and the same keychain item; its code, verifier and state are in memory for that call alone.
/// </summary>
/// <param name="revokeTimeout">How long Sign Out, or the revoke of a kept token that isn't this computer's, waits for the server; 30 s by default.</param>
/// <param name="log">Where the revoke of a kept token that isn't this computer's, and how a sign-in in the browser ended, are noted.</param>
/// <param name="browserTimeout">How long a sign-in in the browser is waited for; 5 minutes by default. Tests shorten it.</param>
public sealed class SignIn(HealdanApi api, IKeychain keychain, DeviceIdentity device, TimeSpan? revokeTimeout = null,
    HelperLog? log = null, TimeSpan? browserTimeout = null)
{
    /// <summary>The API version this helper speaks.</summary>
    public const string SupportedApiVersion = "v1";

    /// <summary>Sign Out waits this long for the revoke, not the API client's ten minutes, as the Sign In window does.</summary>
    private readonly TimeSpan _revokeTimeout = revokeTimeout ?? TimeSpan.FromSeconds(30);

    /// <summary>How long the helper listens for the browser to come back (spec §6.2); the message says so.</summary>
    private readonly TimeSpan _browserTimeout = browserTimeout ?? TimeSpan.FromMinutes(5);

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
        await RequireHealdanAsync(ct);
        var token = await api.SignInAsync(user, password, device.Name, device.Id, ct);
        keychain.Save(Account, new KeptToken(device.Id, token).ToSecret());
    }

    private async Task RequireHealdanAsync(CancellationToken ct)
    {
        var server = await api.ServerAsync(ct);
        if (server.Product != "Healdan")
            throw new InvalidOperationException("That address isn't a Healdan server.");
        if (server.ApiVersion != SupportedApiVersion)
            throw new InvalidOperationException(
                $"This server speaks API {server.ApiVersion}, which this version of Healdan Helper doesn't. Update the helper.");
    }

    /// <summary>
    /// Signs in through a provider (Microsoft Entra ID, say) in the system browser, and keeps the token as a password
    /// sign-in does (M4 spec §6.2, RFC 8252). The helper listens on a loopback port, opens
    /// <c>/api/signin/&lt;id&gt;</c> in <paramref name="browser"/> with its address, a state and a PKCE challenge, and
    /// waits for Healdan to send the browser back with a one-time code, which it exchanges, with the verifier, for the
    /// device token. The code, verifier and state are never logged, and the code is sent only to Healdan.
    /// </summary>
    /// <exception cref="InvalidOperationException">The server isn't Healdan, or speaks another API; or the browser came back
    /// with another request's state (nothing is exchanged).</exception>
    /// <exception cref="ProviderRefusedException">Healdan refused the sign-in; the code says why.</exception>
    /// <exception cref="OperationCanceledException">The browser didn't finish within 5 minutes, or <paramref name="ct"/> was cancelled.</exception>
    /// <exception cref="ApiException">Healdan refused the code (<c>sign-in-failed</c>): it came too late, or was used.</exception>
    /// <exception cref="IOException">The browser couldn't be opened, or the helper couldn't listen.</exception>
    /// <exception cref="ArgumentException">There is no provider id.</exception>
    public async Task SignInWithProviderAsync(string providerId, IBrowser browser, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(providerId);
        await RequireHealdanAsync(ct);
        var (verifier, challenge) = Pkce.Make();
        var state = Pkce.NewState();

        LoopbackResult result;
        Uri redirect;
        await using (var receiver = LoopbackReceiver.Start())
        {
            redirect = receiver.RedirectUri;
            var start = Page($"signin/{Uri.EscapeDataString(providerId)}",
                $"client=helper&redirect_uri={Uri.EscapeDataString(redirect.AbsoluteUri)}&state={state}"
                + $"&code_challenge={challenge}&code_challenge_method=S256");
            log?.Info($"Signing in to {Account} through {providerId} in the browser");
            browser.Open(start);
            try
            {
                result = await receiver.WaitAsync(back => DonePage(Verdict(back, state)), _browserTimeout, ct);
            }
            catch (TimeoutException)
            {
                log?.Warn($"The sign-in through {providerId} in the browser didn't finish in time");
                throw new OperationCanceledException("The sign-in in your browser didn't finish within 5 minutes.");
            }
        }

        if (!StateMatches(result.State, state))
        {
            log?.Warn($"The sign-in through {providerId} came back for another request, so it's refused");
            throw new InvalidOperationException("The sign-in came back for another request. Try again.");
        }
        if (Refusal(result) is { } code)
        {
            log?.Warn($"The sign-in through {providerId} was refused ({code})");
            throw new ProviderRefusedException(code);
        }

        var token = await api.ExchangeCodeAsync(result.Code!, verifier, redirect, device.Name, device.Id, ct);
        keychain.Save(Account, new KeptToken(device.Id, token).ToSecret());
        log?.Info($"Signed in to {Account} through {providerId}");
    }

    /// <summary>What the browser brought, in a word: <c>null</c> for a code to exchange, else the refusal's code.</summary>
    private static string? Verdict(LoopbackResult result, string state) =>
        !StateMatches(result.State, state) ? "expired" : Refusal(result);

    /// <summary>
    /// The refusal's code the browser brought, or null when it brought a code to exchange. Healdan's refusal codes are
    /// lowercase letters, digits and hyphens (32 at most); anything else, or neither a code nor an error, is taken for
    /// the provider's trouble, so nothing odd reaches a window or the done page.
    /// </summary>
    private static string? Refusal(LoopbackResult result)
    {
        if (result.Error is { } error)
            return error.Length is >= 1 and <= 32 && error.All(c => c is (>= 'a' and <= 'z') or (>= '0' and <= '9') or '-') ? error : "provider";
        return result.Code is null ? "provider" : null;
    }

    private static bool StateMatches(string? brought, string state) =>
        brought is not null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(brought), Encoding.UTF8.GetBytes(state));

    /// <summary>A page of Healdan's: <c>https://host/api/&lt;path&gt;?&lt;query&gt;</c>, beside the API the helper is signed in to.</summary>
    private Uri Page(string path, string query) =>
        new($"{api.Server.GetLeftPart(UriPartial.Path).TrimEnd('/')}/api/{path}" + (query.Length > 0 ? "?" + query : ""));

    /// <summary>The page the browser is sent on to once the helper has what it brought: it says to close the tab, or what went wrong, which never names the provider.</summary>
    private Uri DonePage(string? error) =>
        Page("signin/done", error is null ? "" : $"error={Uri.EscapeDataString(error)}");

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
