using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Security;

/// <summary>Run by hand on a Mac: <c>dotnet test … --filter "Category=MacKeychain"</c>. Uses a test-only service name.</summary>
[Trait("Category", "MacKeychain")]
public sealed class MacKeychainTests
{
    [Fact]
    public void StoresReadsUpdatesAndDeletes()
    {
        if (!OperatingSystem.IsMacOS()) return;
        var keychain = new MacKeychain("Healdan Helper Test");
        var account = "https://test-" + Guid.NewGuid().ToString("N") + ".example";
        try
        {
            Assert.Null(keychain.Load(account));
            keychain.Save(account, "hd_first");
            Assert.Equal("hd_first", keychain.Load(account));
            keychain.Save(account, "hd_second");
            Assert.Equal("hd_second", keychain.Load(account));
            keychain.Delete(account);
            Assert.Null(keychain.Load(account));
            keychain.Delete(account); // deleting nothing is fine
        }
        finally
        {
            keychain.Delete(account);
        }
    }
}
