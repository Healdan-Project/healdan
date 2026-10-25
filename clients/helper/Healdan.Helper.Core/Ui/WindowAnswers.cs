using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Ui;

/// <summary>The window routes' answers (plan: the channel's routes), made in one place so their shapes stay the contract's.</summary>
public static class WindowAnswers
{
    /// <summary>
    /// Save to Healdan's Save. The extension writes the document in <paramref name="format"/> to
    /// <paramref name="tempPath"/>, then sends the rest back with <c>/v1/file</c>.
    /// </summary>
    public static JsonObject Save(long folderId, string name, string format, JsonObject properties, string tempPath) => new()
    {
        ["action"] = "save",
        ["folderId"] = folderId,
        ["name"] = name,
        ["format"] = format,
        ["properties"] = properties.DeepClone(),
        ["tempPath"] = tempPath
    };

    /// <summary>"Save to local computer" or "Open from local computer": the app's own box, at once.</summary>
    public static JsonObject Local() => Action("local");

    public static JsonObject Cancel() => Action("cancel");

    /// <summary>Open from Healdan opened the document itself.</summary>
    public static JsonObject Opened() => Action("opened");

    /// <summary>Properties saved the changes.</summary>
    public static JsonObject Saved() => Action("saved");

    /// <summary>Properties on a document that isn't in Healdan: the caller runs Save to Healdan.</summary>
    public static JsonObject File() => Action("file");

    /// <summary>
    /// The answer when the helper isn't signed in or hasn't started, so no window can read Healdan: Save and Open fall
    /// back to the app's own box, so the user's Cmd+S still saves, on this computer; Properties has nothing to show.
    /// </summary>
    public static JsonObject NotReady(string route) => route == "properties" ? Cancel() : Local();

    /// <summary>What the user is told alongside <see cref="NotReady"/>, as the Sign In window opens.</summary>
    public static string NotReadyNotice(string route) => route switch
    {
        "save-new" => "Healdan Helper isn't signed in, so this document is saved on this computer. Sign in to save to Healdan.",
        "open-dialog" => "Healdan Helper isn't signed in, so LibreOffice's own Open box is shown. Sign in to open from Healdan.",
        _ => "Sign in to Healdan Helper to see the document's properties."
    };

    /// <summary>
    /// A new path for the extension to write a new document's content to, in the helper's Temp folder, which only the
    /// user can read and from which <c>/v1/file</c> deletes it once filed. The file isn't made here.
    /// </summary>
    public static string TempPath(HelperPaths paths, string format) =>
        Path.Combine(paths.Temp, $"{Guid.NewGuid():N}.{format.TrimStart('.').ToLowerInvariant()}");

    private static JsonObject Action(string action) => new() { ["action"] = action };
}
