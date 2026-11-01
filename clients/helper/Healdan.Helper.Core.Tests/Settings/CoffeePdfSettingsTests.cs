using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>CoffeePDF's Healdan support, as the helper reads it from CoffeePDF's settings.json (M3.6 spec §4.2).</summary>
public sealed class CoffeePdfSettingsTests : IDisposable
{
    private readonly string _folder = Directory.CreateTempSubdirectory("healdan-coffeepdf-settings-").FullName;

    public void Dispose() => Directory.Delete(_folder, true);

    private void Write(string json) => File.WriteAllText(Path.Combine(_folder, "settings.json"), json);

    [Fact]
    public void OnWhenEnabledIsTrue()
    {
        Write("""{ "healdan": { "enabled": true, "asked": true }, "markup": { "author": "Test Reviewer" } }""");

        Assert.True(CoffeePdfSettings.HealdanEnabled(_folder));
    }

    [Theory]
    [InlineData("""{ "healdan": { "enabled": false, "asked": true } }""")]
    [InlineData("""{ "healdan": { "asked": true } }""")]
    [InlineData("""{ "healdan": { "enabled": "true" } }""")]
    [InlineData("""{ "healdan": { "enabled": 1 } }""")]
    [InlineData("""{ "healdan": { "enabled": null } }""")]
    [InlineData("""{ "healdan": true }""")]
    [InlineData("""{ "markup": { "author": "Test Reviewer" } }""")]
    [InlineData("""[true]""")]
    [InlineData("""{ "healdan": { "enabled": tru""")]
    [InlineData("")]
    public void OffForAnythingElse(string json)
    {
        Write(json);

        Assert.False(CoffeePdfSettings.HealdanEnabled(_folder));
    }

    [Fact]
    public void OffWithNoFileOrFolder()
    {
        Assert.False(CoffeePdfSettings.HealdanEnabled(_folder));
        Assert.False(CoffeePdfSettings.HealdanEnabled(Path.Combine(_folder, "missing")));
    }

    [Fact]
    public void TheFolderIsFoundAsCoffeePdfFindsIt()
    {
        var before = Environment.GetEnvironmentVariable(CoffeePdfSettings.FolderVariable);
        try
        {
            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, _folder);
            Assert.Equal(_folder, CoffeePdfSettings.Folder());

            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, null);
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (OperatingSystem.IsMacOS())
                Assert.Equal(Path.Combine(home, "Library", "Application Support", "CoffeePDF"), CoffeePdfSettings.Folder());
            else
                Assert.Equal("coffeepdf", Path.GetFileName(CoffeePdfSettings.Folder()));
        }
        finally
        {
            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, before);
        }
    }
}
