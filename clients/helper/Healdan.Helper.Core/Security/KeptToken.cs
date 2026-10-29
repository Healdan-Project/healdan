using System.Text.Json;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// What the keychain keeps for a server: the device token and the device id it was issued for, as JSON. The server
/// records a check-out under the token's device, and the API doesn't say which that is, so the helper keeps it beside
/// the token and never uses one issued to another device id.
/// </summary>
public sealed record KeptToken(string Device, string Token)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public string ToSecret() => JsonSerializer.Serialize(this, Json);

    /// <summary>The kept token, or null when the secret isn't one: a bare token, as helpers before this one kept.</summary>
    public static KeptToken? Read(string secret)
    {
        try
        {
            return JsonSerializer.Deserialize<KeptToken>(secret, Json) is { Device.Length: > 0, Token.Length: > 0 } kept ? kept : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
