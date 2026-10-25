using System.Text.RegularExpressions;
using Healdan.Helper.Core.Security;

namespace Healdan.Helper.Core.Tests.Security;

public sealed class DeviceIdentityTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-device-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    [Fact]
    public void DeviceIdIsStable()
    {
        var paths = HelperPaths.For(_dir);
        var first = DeviceIdentity.Load(paths, "Mac");
        var second = DeviceIdentity.Load(paths, "Mac");
        Assert.Equal(first.Id, second.Id);
        Assert.Matches(new Regex("^[A-Za-z0-9-]{1,64}$"), first.Id);
        Assert.Equal("Healdan Helper on Mac", first.Name);
    }

    [Fact]
    public void NameIsCutToTheServersLimit()
    {
        var identity = DeviceIdentity.Load(HelperPaths.For(_dir), new string('x', 300));
        Assert.Equal(100, identity.Name.Length);
        Assert.StartsWith("Healdan Helper on x", identity.Name);
    }

    [Fact]
    public void ACorruptFileIsKeptAsideAndANewIdMade()
    {
        var paths = HelperPaths.For(_dir);
        File.WriteAllText(paths.Device, "{not json");
        var identity = DeviceIdentity.Load(paths, "Mac");
        Assert.Matches(new Regex("^[A-Za-z0-9-]{1,64}$"), identity.Id);
        Assert.Equal("{not json", File.ReadAllText(paths.Device + ".bad"));
        Assert.Equal(identity.Id, DeviceIdentity.Load(paths, "Mac").Id);
    }

    [Fact]
    public void AnIdWithOddCharactersIsNotTrusted()
    {
        var paths = HelperPaths.For(_dir);
        File.WriteAllText(paths.Device, """{"id":"../etc"}""");
        var identity = DeviceIdentity.Load(paths, "Mac");
        Assert.NotEqual("../etc", identity.Id);
        Assert.True(File.Exists(paths.Device + ".bad"));
    }
}
