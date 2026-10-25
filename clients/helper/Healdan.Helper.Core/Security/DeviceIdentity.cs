using System.Diagnostics;
using System.Text.Json;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Security;

/// <summary>
/// This computer, as the server knows it: a random id made once and kept in <c>device.json</c> beside the settings
/// (it isn't a secret), and the name shown beside the token. Signing in again sends the same id, so the server keeps
/// recognising this computer's check-outs.
/// </summary>
public sealed partial record DeviceIdentity(string Id, string Name)
{
    /// <summary>The server's limit on a token's name (<c>ApiTokens.MAX_NAME_LENGTH</c>).</summary>
    private const int MaxNameLength = 100;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    /// <summary>
    /// Reads the id, or makes and keeps one. A file that can't be read is renamed to <c>device.json.bad</c> and a new
    /// id made, so it is never silently overwritten; check-outs held under the old id then read as another computer's.
    /// That is noted in <paramref name="log"/>.
    /// </summary>
    public static DeviceIdentity Load(HelperPaths paths, string? computerName = null, HelperLog? log = null)
    {
        var name = Cut("Healdan Helper on " + (computerName ?? ComputerName()));
        var id = ReadId(paths.Device);
        if (id is null)
        {
            if (File.Exists(paths.Device))
            {
                File.Move(paths.Device, paths.Device + ".bad", overwrite: true);
                log?.Warn("device.json was unreadable; kept as device.json.bad and made a new device id");
            }
            id = Guid.NewGuid().ToString();
            Directory.CreateDirectory(paths.Support);
            var temp = paths.Device + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(new Stored(id), Json));
            File.Move(temp, paths.Device, overwrite: true);
        }
        return new DeviceIdentity(id, name);
    }

    private static string? ReadId(string path)
    {
        try
        {
            var id = JsonSerializer.Deserialize<Stored>(File.ReadAllText(path), Json)?.Id;
            return id is not null && ValidId().IsMatch(id) ? id : null;
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static string Cut(string name) => name.Length <= MaxNameLength ? name : name[..MaxNameLength];

    /// <summary>The friendly name on macOS (<c>scutil</c>, which ships with it), otherwise the machine name.</summary>
    private static string ComputerName()
    {
        if (OperatingSystem.IsMacOS())
        {
            try
            {
                var info = new ProcessStartInfo("/usr/sbin/scutil", ["--get", "ComputerName"])
                {
                    RedirectStandardOutput = true,
                    UseShellExecute = false
                };
                using var process = Process.Start(info);
                if (process is not null)
                {
                    var friendly = process.StandardOutput.ReadToEnd().Trim();
                    process.WaitForExit();
                    if (process.ExitCode == 0 && friendly.Length > 0) return friendly;
                }
            }
            catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
            {
                // fall back to the machine name
            }
        }
        return Environment.MachineName;
    }

    private sealed record Stored(string? Id);

    [GeneratedRegex("^[A-Za-z0-9-]{1,64}$")]
    private static partial Regex ValidId();
}
