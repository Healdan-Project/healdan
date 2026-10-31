using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

public sealed class HelperSettingsTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-settings-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    private HelperPaths Paths => HelperPaths.For(_dir);

    [Fact]
    public void KindsRoundTrip()
    {
        new HelperSettings { Server = "https://healdan.example", Kinds = new Dictionary<string, string> { ["Documents"] = "Microsoft Office" } }.Save(Paths);

        var again = HelperSettings.Load(Paths);

        Assert.Equal("https://healdan.example", again.Server);
        Assert.Equal(new Dictionary<string, string> { ["Documents"] = "Microsoft Office" }, again.Kinds);
        Assert.Equal("Microsoft Office", again.ChosenApp("Documents"));
        Assert.Null(again.ChosenApp("Spreadsheets"));
    }

    [Fact]
    public void AnOlderSettingsFileHasNoKinds()
    {
        Directory.CreateDirectory(Paths.Support);
        File.WriteAllText(Paths.Settings, """{ "server": "https://healdan.example", "loginItemRegistered": true }""");

        var settings = HelperSettings.Load(Paths);

        Assert.Equal("https://healdan.example", settings.Server);
        Assert.True(settings.LoginItemRegistered);
        Assert.Null(settings.Kinds);
        Assert.Null(settings.ChosenApp("Documents"));
        Assert.Null(settings.CopiesAnnounced);
    }

    [Theory]
    [InlineData("Pages")]
    [InlineData("")]
    [InlineData("libreoffice")]
    [InlineData("Default")]
    public void AnUnknownAppInKindsIsIgnored(string app)
    {
        var settings = new HelperSettings { Kinds = new Dictionary<string, string> { ["Documents"] = app, ["Spreadsheets"] = "LibreOffice" } };

        Assert.Null(settings.ChosenApp("Documents"));
        Assert.Equal("LibreOffice", settings.ChosenApp("Spreadsheets"));
    }

    [Fact]
    public void UpdateKeepsTheKinds()
    {
        new HelperSettings { Kinds = new Dictionary<string, string> { ["Presentations"] = "Microsoft Office" } }.Save(Paths);

        HelperSettings.Update(Paths, s => s.Server = "https://healdan.example");

        Assert.Equal("Microsoft Office", HelperSettings.Load(Paths).ChosenApp("Presentations"));
    }
}
