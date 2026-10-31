namespace Healdan.Helper.Core;

/// <summary>Where the helper keeps its files (plan: "The helper's files"). Tests pass a <c>root</c> so they never touch the real folders.</summary>
public sealed class HelperPaths
{
    private const string Name = "Healdan Helper";

    private HelperPaths(string support, string log, string keychainService)
    {
        Support = support;
        Log = log;
        KeychainService = keychainService;
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

    /// <summary>
    /// The certificate the add-in's port serves (M3.5 spec §5.13): the helper's own, PEM, which the user trusts once.
    /// </summary>
    public string AddinCertificate => Path.Combine(Support, "addin-cert.pem");

    /// <summary>The certificate's private key, PEM, which only the user can read (<see cref="Addin.AddinCertificate"/>).</summary>
    public string AddinKey => Path.Combine(Support, "addin-key.pem");

    /// <summary>
    /// The paired add-ins' keys (M3.5 spec §5.9), each kept only as its hash, with its app and when it was paired; only the
    /// user can read it (<see cref="Addin.AddinKeys"/>).
    /// </summary>
    public string AddinKeys => Path.Combine(Support, "addin-keys.json");

    public string Log { get; }

    /// <summary>
    /// The keychain's service for the device token: the helper's name, or, for folders under a <c>root</c>, one of
    /// their own. A helper with its own <c>device.json</c> then never resumes with another's token, which the server
    /// would take as another device's.
    /// </summary>
    public string KeychainService { get; }

    /// <summary>The usual folders for this user, or everything under <paramref name="root"/> when it is given.</summary>
    public static HelperPaths For(string? root = null)
    {
        if (root is not null)
            return new HelperPaths(root, Path.Combine(root, "helper.log"), $"{Name} ({Path.GetFullPath(root)})");

        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS())
            return new HelperPaths(
                Path.Combine(home, "Library", "Application Support", Name),
                Path.Combine(home, "Library", "Logs", Name, "helper.log"), Name);

        var data = Environment.GetEnvironmentVariable("XDG_DATA_HOME");
        if (string.IsNullOrEmpty(data))
            data = Path.Combine(home, ".local", "share");
        var support = Path.Combine(data, "healdan-helper");
        return new HelperPaths(support, Path.Combine(support, "helper.log"), Name);
    }
}
