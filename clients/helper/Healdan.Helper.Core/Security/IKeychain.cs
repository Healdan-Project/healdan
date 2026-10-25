namespace Healdan.Helper.Core.Security;

/// <summary>Where the device token lives: the system's keychain, never a file. The account is the server's base URL.</summary>
public interface IKeychain
{
    /// <summary>The secret kept for <paramref name="account"/>, or null when there is none.</summary>
    string? Load(string account);

    /// <summary>Stores the secret, replacing any kept for the account.</summary>
    void Save(string account, string secret);

    /// <summary>Removes the account's secret; nothing to remove is not an error.</summary>
    void Delete(string account);
}
