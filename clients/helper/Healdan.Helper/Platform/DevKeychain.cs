#if DEBUG
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Platform;

/// <summary>
/// Debug builds only: a keychain holding the token from <c>HEALDAN_HELPER_DEV_TOKEN</c>, so a build run by hand
/// against a local stack signs in without its window. It keeps a token from a sign-in in memory, never on disk.
/// </summary>
internal sealed class DevKeychain(string token) : IKeychain
{
    private string? _token = token;

    public string? Load(string account) => _token;

    public void Save(string account, string secret) => _token = secret;

    public void Delete(string account) => _token = null;
}
#endif
