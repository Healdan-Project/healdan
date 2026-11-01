using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// Whether CoffeePDF's Healdan support is on (M3.6 spec §4.2), from its <c>settings.json</c>, whose shape CoffeePDF keeps
/// as a contract: <c>{ "healdan": { "enabled": true, … }, … }</c>. The helper reads only <c>healdan.enabled</c>, never
/// writes the file, and reads it at each choice, so turning support on in CoffeePDF takes effect with the next PDF opened.
/// </summary>
public static class CoffeePdfSettings
{
    /// <summary>Overrides CoffeePDF's settings folder, as CoffeePDF itself takes it, for tests and development.</summary>
    public const string FolderVariable = "COFFEEPDF_SETTINGS_DIR";

    /// <summary>
    /// CoffeePDF's settings folder, found as CoffeePDF finds it (its <c>SettingsLocation</c>): <see cref="FolderVariable"/>
    /// when it's set, else <c>~/Library/Application Support/CoffeePDF/</c> on macOS, and <c>$XDG_CONFIG_HOME/coffeepdf/</c>
    /// (or <c>~/.config/coffeepdf/</c>) elsewhere.
    /// </summary>
    public static string Folder()
    {
        if (Environment.GetEnvironmentVariable(FolderVariable) is { Length: > 0 } overridden)
            return overridden;
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS())
            return Path.Combine(home, "Library", "Application Support", "CoffeePDF");
        var config = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME") is { Length: > 0 } xdg ? xdg : Path.Combine(home, ".config");
        return Path.Combine(config, "coffeepdf");
    }

    /// <summary>
    /// Whether <c>healdan.enabled</c> is the JSON value <c>true</c> in the folder's <c>settings.json</c>. A missing,
    /// unreadable or malformed file, or any other value, is off.
    /// </summary>
    public static bool HealdanEnabled(string folder)
    {
        try
        {
            using var stream = File.OpenRead(Path.Combine(folder, "settings.json"));
            return JsonNode.Parse(stream) is JsonObject root && root["healdan"] is JsonObject healdan
                && healdan["enabled"] is JsonValue enabled && enabled.GetValueKind() == JsonValueKind.True;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException or ArgumentException)
        {
            return false;
        }
    }
}
