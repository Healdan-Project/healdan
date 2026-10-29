#if DEBUG
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Platform;

/// <summary>
/// Debug builds only: a keychain holding the token from <c>HEALDAN_HELPER_DEV_TOKEN</c>, so a build run by hand
/// against a local stack signs in without its window. The token is taken as issued to this helper's device id, so
/// check-outs read as this computer's only when it was. It keeps a token from a sign-in in memory, never on disk.
/// </summary>
internal sealed class DevKeychain(string token, string deviceId) : IKeychain
{
    private string? _secret = new KeptToken(deviceId, token).ToSecret();

    public string? Load(string account) => _secret;

    public void Save(string account, string secret) => _secret = secret;

    public void Delete(string account) => _secret = null;
}
#endif
