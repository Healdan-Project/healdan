using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Links;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Channel;

/// <summary>
/// What the channel's routes do (plan: "The channel's routes"). <c>link</c>, <c>file</c>, <c>saved</c>,
/// <c>new-version</c>, <c>closed</c> and <c>state</c> need no window and run on the cycle; <c>open-dialog</c>,
/// <c>save-new</c> and <c>properties</c> are the windows' (<see cref="IWindows"/>). A path the routes take must be a
/// local copy's, in the copies folder: the cycle finds copies by file name, and a local file that happens to share a
/// copy's name must never be taken for it.
/// </summary>
public sealed class ChannelHandler(CopyCycle cycle, LinkHandler links, IWindows windows, INotifier notifier, HelperLog log,
    HelperPaths paths, TimeSpan? savedWait = null) : IChannelHandler
{
    /// <summary><c>/v1/saved</c> answers with the next confirmation, or after this with the copy's state as it is.</summary>
    public static readonly TimeSpan SavedWait = TimeSpan.FromSeconds(120);

    public async Task<JsonNode?> HandleAsync(string route, JsonObject body)
    {
        switch (route)
        {
            case "link":
                await links.Handle(Text(body, "url"));
                return new JsonObject();
            case "open-dialog":
                return await windows.OpenDialogAsync(body);
            case "save-new":
                return await windows.SaveNewAsync(body);
            case "properties":
                return await windows.PropertiesAsync(body);
            case "file":
                return await FileAsync(body);
            case "saved":
                var saved = Text(body, "path");
                return Json(InCopies(saved) ? await cycle.WaitForConfirmationAsync(saved, savedWait ?? SavedWait) : CopyState.Unknown(saved));
            case "new-version":
                if (Copy(body, route) is { } next)
                    cycle.MarkNextVersion(next);
                return new JsonObject();
            case "closed":
                if (Copy(body, route) is { } closed)
                    await cycle.ClosedAsync(closed);
                return new JsonObject();
            case "state":
                var path = Text(body, "path");
                return Json(InCopies(path) ? cycle.StateOf(path) : CopyState.Unknown(path));
            default:
                throw new ArgumentException($"The channel has no route {route}.", nameof(route));
        }
    }

    /// <summary>
    /// Save to Healdan and Save as New Document: files the window's content, which the extension wrote to
    /// <c>tempPath</c>, and answers the copy the app is to save the window as. The temporary file is deleted afterwards,
    /// whether the filing worked or not, but only from the system's temporary folder or the helper's own. A failed
    /// filing is told to the user and thrown, so the caller falls back to the app's own save.
    /// </summary>
    private async Task<JsonNode> FileAsync(JsonObject body)
    {
        var tempPath = Text(body, "tempPath");
        try
        {
            var folderId = body["folderId"] is JsonValue id && id.GetValueKind() == JsonValueKind.Number
                && long.TryParse(id.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var folder) && folder >= 0
                ? folder
                : throw new ArgumentException("folderId is missing or isn't a folder's id.", "folderId");
            var name = DocumentName(Text(body, "name"), Optional(body, "format"));
            var properties = body["properties"] is JsonObject given ? (JsonObject)given.DeepClone() : new JsonObject();
            var replaces = Optional(body, "replaces");
            if (replaces is not null && !InCopies(replaces))
            {
                log.Warn("Save as New Document named an original that isn't a local copy; it's left alone");
                replaces = null;
            }
            try
            {
                var state = await cycle.AdoptNewAsync(tempPath, new NewDocument(folderId, name, properties, true), replaces);
                log.Info($"Filed {state.DocRef} from {(replaces is null ? "a new document" : "Save as New Document")}");
                return Json(state);
            }
            catch (Exception e)
            {
                log.Error($"Filing {name} failed", e);
                notifier.Notify("Healdan Helper", e switch
                {
                    ApiException { Status: 401 } => $"{name} wasn't filed: sign in to Healdan again, then save it once more.",
                    ApiException api => $"{name} couldn't be filed in Healdan: {api.Detail}",
                    FileNotFoundException or DirectoryNotFoundException or UnauthorizedAccessException =>
                        $"{name} couldn't be filed: the copy LibreOffice wrote for it couldn't be read. It's still open, so save it again.",
                    _ => $"{name} couldn't be filed in Healdan, which may be unreachable. It's still open, so save it again."
                });
                throw;
            }
        }
        finally
        {
            DeleteTemporary(tempPath);
        }
    }

    /// <summary>The document's name with its extension (the API wants one): the format's, when the name lacks it.</summary>
    private static string DocumentName(string name, string? format)
    {
        if (string.IsNullOrEmpty(format))
            return name;
        var ext = "." + format.TrimStart('.');
        return name.EndsWith(ext, StringComparison.OrdinalIgnoreCase) ? name : name + ext;
    }

    private void DeleteTemporary(string path)
    {
        try
        {
            if (Disposable(path, paths) is { } file)
                File.Delete(file);
            else if (File.Exists(path))
                log.Warn("The temporary file to file wasn't in the temporary folder or the helper's, so it was left in place");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.Warn("The temporary file couldn't be deleted: " + e.Message);
        }
    }

    /// <summary>
    /// The file to delete for <paramref name="path"/>: the file itself, found through any links in its folders, when it
    /// is a plain file in the system's temporary folder or the helper's own folders. Null for anything else.
    /// </summary>
    internal static string? Disposable(string path, HelperPaths paths)
    {
        if (!Path.IsPathFullyQualified(path))
            return null;
        var full = Path.GetFullPath(path);
        if (RealPath(Path.GetDirectoryName(full)!) is not { } folder)
            return null;
        var file = Path.Combine(folder, Path.GetFileName(full));
        var info = new FileInfo(file);
        if (!info.Exists || info.LinkTarget is not null)
            return null;
        List<string> roots = [Path.GetTempPath(), paths.Support];
        if (!OperatingSystem.IsWindows())
            roots.Add("/tmp");
        foreach (var root in roots)
            if (RealPath(root) is { Length: > 1 } real // never a root that resolves to "/", which would take in everything
                && (folder == real || folder.StartsWith(real.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.Ordinal)))
                return file;
        return null;
    }

    /// <summary>The path with every link in it followed (<c>/tmp</c> is <c>/private/tmp</c> on macOS), or null if a part is missing.</summary>
    private static string? RealPath(string path, int depth = 0)
    {
        if (depth > 32)
            return null; // a loop of links
        var full = Path.GetFullPath(path);
        var root = Path.GetPathRoot(full)!;
        var current = root;
        foreach (var part in full[root.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            var next = Path.Combine(current, part);
            var info = new FileInfo(next);
            if (info.LinkTarget is { } target)
            {
                if (RealPath(Path.IsPathRooted(target) ? target : Path.Combine(current, target), depth + 1) is not { } resolved)
                    return null;
                next = resolved;
            }
            else if (!info.Exists && !Directory.Exists(next))
                return null;
            current = next;
        }
        return current;
    }

    /// <summary>Whether the path is in the copies folder, as every copy is.</summary>
    private bool InCopies(string path)
    {
        if (!Path.IsPathFullyQualified(path))
            return false;
        var folder = Path.GetDirectoryName(Path.GetFullPath(path));
        if (folder is null)
            return false;
        return Same(folder, paths.Copies) || (RealPath(folder) is { } real && RealPath(paths.Copies) is { } copies && Same(real, copies));

        static bool Same(string a, string b) =>
            string.Equals(Path.TrimEndingDirectorySeparator(a).Normalize(NormalizationForm.FormC),
                Path.TrimEndingDirectorySeparator(Path.GetFullPath(b)).Normalize(NormalizationForm.FormC), StringComparison.Ordinal);
    }

    /// <summary>The body's copy path, or null (logged) when the path isn't a local copy's.</summary>
    private string? Copy(JsonObject body, string route)
    {
        var path = Text(body, "path");
        if (InCopies(path))
            return path;
        log.Warn($"Ignored /v1/{route} for a file that isn't a local copy");
        return null;
    }

    /// <summary>A copy's state as the routes answer it (plan: the copy's state), or <c>{"known": false}</c>.</summary>
    internal static JsonObject Json(CopyState s) => !s.Known
        ? new JsonObject { ["known"] = false }
        : new JsonObject
        {
            ["known"] = true,
            ["docNumber"] = s.DocNumber,
            ["title"] = s.Title,
            ["docRef"] = s.DocRef,
            ["readOnly"] = s.ReadOnly,
            ["path"] = s.Path,
            ["holdBy"] = s.HoldBy
        };

    private static string Text(JsonObject body, string key) =>
        body[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0
            ? s
            : throw new ArgumentException($"{key} is missing or isn't text.", key);

    private static string? Optional(JsonObject body, string key) => body[key] switch
    {
        null => null,
        JsonValue v when v.TryGetValue<string>(out var s) => s.Length == 0 ? null : s,
        _ => throw new ArgumentException($"{key} isn't text.", key)
    };
}
