using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>A keychain in memory for tests.</summary>
public sealed class MemoryKeychain : IKeychain
{
    public Dictionary<string, string> Items { get; } = [];

    public List<string> Log { get; } = [];

    /// <summary>When set, Delete refuses, as a locked keychain would.</summary>
    public bool RefuseDelete { get; set; }

    public string? Load(string account) => Items.GetValueOrDefault(account);

    public void Save(string account, string secret)
    {
        Items[account] = secret;
        Log.Add("save");
    }

    public void Delete(string account)
    {
        if (RefuseDelete)
            throw new InvalidOperationException("The keychain refused to delete the sign-in (fake).");
        Items.Remove(account);
        Log.Add("delete");
    }
}
