namespace Healdan.Helper.Core.Session;

/// <summary>What became of the sign-in kept in the keychain at a start (<see cref="SignIn.TryResumeAsync"/>).</summary>
public enum Resume
{
    /// <summary>Healdan took the kept token: the helper is signed in.</summary>
    Resumed,

    /// <summary>No usable sign-in was kept: none, or one that isn't this computer's, which the helper revoked and removed.</summary>
    None,

    /// <summary>
    /// Healdan refused the kept token with a <c>401</c>: it expired, was revoked (by Sign Out on another device, an
    /// administrator, or the provider's check, M4 spec §9), or the password changed. It's removed.
    /// </summary>
    Refused
}
