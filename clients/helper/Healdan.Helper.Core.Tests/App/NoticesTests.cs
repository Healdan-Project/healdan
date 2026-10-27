using System.Text.Json;

namespace Healdan.Helper.Core.Tests.App;

/// <summary>
/// The app's third-party notices (#89), which About's Open-Source Notices shows. Each section's <c>Packages:</c> line
/// names the NuGet packages it covers, and together they name every package the helper restores, at the version it
/// restores, and nothing else. A package added or moved fails here until its notice is written.
/// </summary>
public sealed class NoticesTests
{
    private const string PackagesLine = "Packages: ";

    [Fact]
    public void NameEveryPackageTheHelperRestores()
    {
        var assets = AppFiles.Helper("obj", "project.assets.json");
        Assert.True(File.Exists(assets), "Restore the whole solution first: dotnet test clients/helper/Healdan.Helper.sln");
        using var restore = JsonDocument.Parse(File.ReadAllText(assets));
        var restored = restore.RootElement.GetProperty("libraries").EnumerateObject()
            .Where(library => library.Value.GetProperty("type").GetString() == "package")
            .Select(library => library.Name.Replace('/', ' '))
            .Order(StringComparer.Ordinal);

        var named = File.ReadLines(AppFiles.Helper("THIRD-PARTY-NOTICES.txt"))
            .Where(line => line.StartsWith(PackagesLine, StringComparison.Ordinal))
            .SelectMany(line => line[PackagesLine.Length..].Split(", "))
            .Order(StringComparer.Ordinal);

        Assert.Equal(restored, named);
    }
}
