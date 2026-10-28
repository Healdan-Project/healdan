namespace Healdan.Helper.Core.Tests;

public sealed class HelperPathsTests
{
    [Fact]
    public void TheHelperKeepsItsSignInsUnderItsOwnName()
    {
        Assert.Equal("Healdan Helper", HelperPaths.For().KeychainService);
    }

    [Fact]
    public void AHelperWithItsFilesApartKeepsItsSignInsApartToo()
    {
        var root = Path.Combine(Path.GetTempPath(), "hd-paths-" + Guid.NewGuid().ToString("N"));
        var service = HelperPaths.For(root).KeychainService;

        Assert.NotEqual(HelperPaths.For().KeychainService, service);
        Assert.Contains(root, service);
        Assert.NotEqual(service, HelperPaths.For(root + "-other").KeychainService);
    }
}
