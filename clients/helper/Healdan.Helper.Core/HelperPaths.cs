namespace Healdan.Helper.Core;

/// <summary>Where the helper keeps its files (plan: "The helper's files"). Tests pass a <c>root</c> so they never touch the real folders.</summary>
public sealed class HelperPaths
{
    private HelperPaths(string support, string log)
    {
        Support = support;
        Log = log;
    }

    /// <summary>The helper's own folder, which only the user can read.</summary>
    public string Support { get; }

    /// <summary>The one flat folder holding every local copy and its record.</summary>
    public string Copies => Path.Combine(Support, "Copies");

    /// <summary>
    /// Where the extension writes a window's content for Save to Healdan (<c>/v1/file</c>'s <c>tempPath</c>), which the
    /// helper deletes once filed. Only the user can read it.
    /// </summary>
    public string Temp => Path.Combine(Support, "Temp");

    public string Settings => Path.Combine(Support, "settings.json");

    public string Device => Path.Combine(Support, "device.json");

    public string Channel => Path.Combine(Support, "channel.json");

    public string Log { get; }

    /// <summary>The usual folders for this user, or everything under <paramref name="root"/> when it is given.</summary>
    public static HelperPaths For(string? root = null)
    {
        if (root is not null)
            return new HelperPaths(root, Path.Combine(root, "helper.log"));

        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS())
            return new HelperPaths(
                Path.Combine(home, "Library", "Application Support", "Healdan Helper"),
                Path.Combine(home, "Library", "Logs", "Healdan Helper", "helper.log"));

        var data = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
        if (string.IsNullOrEmpty(data))
            data = Path.Combine(home, ".local", "share");
        var support = Path.Combine(data, "healdan-helper");
        return new HelperPaths(support, Path.Combine(support, "helper.log"));
    }
}
