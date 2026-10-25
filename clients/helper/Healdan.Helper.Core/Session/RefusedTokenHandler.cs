using System.Net;

namespace Healdan.Helper.Core.Session;

/// <summary>
/// Sits under every API client the session makes and notices Healdan refusing the device token (spec §5.2: a
/// <c>401</c> at any time means it expired or was revoked), whichever call it was: a save, a filing, a window's
/// search. Only a call that carried a token counts, so a wrong password at Sign In never does.
/// </summary>
internal sealed class RefusedTokenHandler(Action refused) : DelegatingHandler
{
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var response = await base.SendAsync(request, cancellationToken);
        if (response.StatusCode == HttpStatusCode.Unauthorized && request.Headers.Authorization is not null)
        {
            try
            {
                refused();
            }
            catch (Exception)
            {
                // Telling the session must never break the call; the caller sees the 401 as usual.
            }
        }
        return response;
    }
}
