using System.Text.Json;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// The helper's settings (<c>settings.json</c>). Never the password or the token: those live in memory for one call and
/// in the keychain.
/// </summary>
public sealed class HelperSettings
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <summary>The server's address, such as <c>https://healdan.example</c>.</summary>
    public string? Server { get; set; }

    /// <summary>"LibreOffice" for every kind in §5.11 (<see cref="FileKinds"/>) in M3, otherwise "Default" (the system's default app, read-only).</summary>
    public string AppFor(string extension) => FileKinds.Of(extension) is null ? Cycle.DefaultApp.AppName : "LibreOffice";

    /// <summary>The saved settings, or empty ones when there are none or the file can't be read.</summary>
    public static HelperSettings Load(HelperPaths paths)
    {
        try
        {
            return JsonSerializer.Deserialize<HelperSettings>(File.ReadAllText(paths.Settings), Json) ?? new HelperSettings();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            return new HelperSettings();
        }
    }

    /// <summary>Written whole through a temporary file renamed over the old one, so a crash leaves either.</summary>
    public void Save(HelperPaths paths)
    {
        Directory.CreateDirectory(paths.Support);
        var temp = paths.Settings + $".{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(this, Json));
            File.Move(temp, paths.Settings, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }
}
