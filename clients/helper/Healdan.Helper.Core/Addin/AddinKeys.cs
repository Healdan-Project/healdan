using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Healdan.Helper.Core.Addin;

/// <summary>
/// A paired add-in's key, as the helper keeps it: only its SHA-256, in lower-case hex, never the key itself, with the app
/// it was paired for and when (M3.5 spec §5.9).
/// </summary>
public sealed record AddinKey(
    [property: JsonPropertyName("hash")] string Hash,
    [property: JsonPropertyName("app")] string App,
    [property: JsonPropertyName("paired")] DateTimeOffset Paired);

/// <summary>
/// <c>addin-keys.json</c> (<see cref="HelperPaths.AddinKeys"/>): <c>{"keys": [{"hash", "app", "paired"}]}</c>, which only
/// the user can read. Anyone who could read it learns only hashes, which open nothing.
/// </summary>
public static class AddinKeys
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <summary>A key's hash, as the file keeps it.</summary>
    public static string Hash(string key) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(key)));

    /// <summary>The keys in the file; none when it's missing or can't be read, so every app pairs again.</summary>
    public static IReadOnlyList<AddinKey> Load(HelperPaths paths)
    {
        try
        {
            var file = JsonSerializer.Deserialize<KeysFile>(File.ReadAllText(paths.AddinKeys), Json);
            return [.. (file?.Keys ?? []).Where(k => k is { Hash.Length: 64, App.Length: > 0 })];
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException or NotSupportedException)
        {
            return [];
        }
    }

    /// <summary>Writes the file in one step, private to the user from the moment it exists.</summary>
    public static void Save(HelperPaths paths, IEnumerable<AddinKey> keys)
    {
        Directory.CreateDirectory(paths.Support);
        var temp = paths.AddinKeys + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var options = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write };
            if (!OperatingSystem.IsWindows())
                options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
            using (var stream = new FileStream(temp, options))
                JsonSerializer.Serialize(stream, new KeysFile([.. keys]), Json);
            File.Move(temp, paths.AddinKeys, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }

    private sealed record KeysFile([property: JsonPropertyName("keys")] List<AddinKey>? Keys);
}
