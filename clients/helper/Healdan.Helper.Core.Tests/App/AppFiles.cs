namespace Healdan.Helper.Core.Tests.App;

/// <summary>Where the app's own files are, for the tests that read them: the repository's root and the app's project.</summary>
internal static class AppFiles
{
    private static readonly Lazy<string> Root = new(() =>
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "clients", "helper", "Healdan.Helper.sln")))
            dir = dir.Parent;
        Assert.NotNull(dir);
        return dir.FullName;
    });

    public static string Repository(params string[] path) => Path.Combine([Root.Value, .. path]);

    public static string Helper(params string[] path) => Repository(["clients", "helper", "Healdan.Helper", .. path]);
}
