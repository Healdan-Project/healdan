using System.Text.Json;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// The helper's settings (<c>settings.json</c>). Never the password or the token: those live in memory for one call and
/// in the keychain.
/// </summary>
public sealed class HelperSettings
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <summary>One <see cref="Update"/> at a time in this process, so two changes can't lose each other.</summary>
    private static readonly object Writing = new();

    /// <summary>The server's address, such as <c>https://healdan.example</c>.</summary>
    public string? Server { get; set; }

    /// <summary>What the helper last did about its LibreOffice extension, and which way worked (spec §5.12).</summary>
    public ExtensionRecord? Extension { get; set; }

    /// <summary>
    /// The helper has registered itself to start at sign-in (macOS's login item), which it does once, after the first
    /// sign-in. Turning it off in System Settings is the user's choice, so it isn't registered again.
    /// </summary>
    public bool LoginItemRegistered { get; set; }

    /// <summary>
    /// The helper has offered Set Up Microsoft Office (M3.5 spec §5.13), which it does once, at the first start that finds
    /// Word, Excel or PowerPoint installed and the certificate not yet trusted. After that it is in the menu and Settings,
    /// not shown again unasked.
    /// </summary>
    public bool OfficeSetUpOffered { get; set; }

    /// <summary>
    /// The app the user chose for each kind of file (<see cref="FileKinds"/>, by its label): <c>LibreOffice</c> or
    /// <c>Microsoft Office</c>. Null, or a kind left out, is the default (<see cref="AppChooser"/>).
    /// </summary>
    public Dictionary<string, string>? Kinds { get; set; }

    /// <summary>
    /// The programs (<c>Word</c>, <c>Excel</c>, <c>PowerPoint</c>) the helper has told, before their first save into
    /// Copies, how to give them access to it (<see cref="Office.CopiesNotice"/>). Null in a file from before it was kept.
    /// </summary>
    public List<string>? CopiesAnnounced { get; set; }

    /// <summary>
    /// The app chosen for the kind with this label, or null when none was, or when the settings name one that isn't an
    /// app the helper opens copies in (an older or edited file): that is no choice.
    /// </summary>
    public string? ChosenApp(string kindLabel)
    {
        if (Kinds is null || !Kinds.TryGetValue(kindLabel, out var app))
            return null;
        return app is Cycle.LibreOfficeApp.AppName or Office.MicrosoftOfficeApp.AppName ? app : null;
    }

    /// <summary>Loads the settings, changes them and saves them, with no other <see cref="Update"/> in between.</summary>
    public static void Update(HelperPaths paths, Action<HelperSettings> change)
    {
        lock (Writing)
        {
            var settings = Load(paths);
            change(settings);
            settings.Save(paths);
        }
    }

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
