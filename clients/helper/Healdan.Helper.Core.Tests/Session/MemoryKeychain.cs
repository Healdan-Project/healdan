using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>A keychain in memory for tests.</summary>
public sealed class MemoryKeychain : IKeychain
{
    public Dictionary<string, string> Items { get; } = [];

    public List<string> Log { get; } = [];

    public string? Load(string account) => Items.GetValueOrDefault(account);

    public void Save(string account, string secret)
    {
        Items[account] = secret;
        Log.Add("save");
    }

    public void Delete(string account)
    {
        Items.Remove(account);
        Log.Add("delete");
    }
}
