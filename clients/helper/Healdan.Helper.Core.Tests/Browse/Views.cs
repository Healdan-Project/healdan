using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>
/// The API's views as JSON, for the windows' tests: Store "Healdan", matter 998.1's workspace (folder 10) with its
/// Documents folder (11) and Drafts inside that (12).
/// </summary>
public static class Views
{
    public const long Me = 1;

    public static JsonObject Ref(long id, string name, string kind) => new() { ["id"] = id, ["name"] = name, ["kind"] = kind };

    public static JsonArray PathTo(long id) => id switch
    {
        10 => [Ref(1, "Healdan", "store"), Ref(10, "998.1 – Healdan Demo", "matter")],
        11 => [Ref(1, "Healdan", "store"), Ref(10, "998.1 – Healdan Demo", "matter"), Ref(11, "Documents", "folder")],
        12 => [Ref(1, "Healdan", "store"), Ref(10, "998.1 – Healdan Demo", "matter"), Ref(11, "Documents", "folder"), Ref(12, "Drafts", "folder")],
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    };

    public static JsonObject Folder(long id)
    {
        var path = PathTo(id);
        var self = path[^1]!;
        return new JsonObject
        {
            ["type"] = "folder", ["id"] = id, ["name"] = (string)self["name"]!, ["kind"] = (string)self["kind"]!,
            ["matter"] = new JsonObject { ["id"] = 3, ["display"] = "998.1 – Healdan Demo" },
            ["store"] = new JsonObject { ["id"] = 1, ["name"] = "Healdan" },
            ["path"] = path, ["canFile"] = id != 10
        };
    }

    public static JsonObject Hold(long byId, string byName, string? device = null, string? app = null) => new()
    {
        ["kind"] = "checkout",
        ["by"] = new JsonObject { ["id"] = byId, ["name"] = byName },
        ["since"] = "2026-10-25T10:50:00Z",
        ["device"] = device is null ? null : new JsonObject { ["id"] = "dev-" + device, ["name"] = "Healdan Helper on " + device, ["app"] = app }
    };

    public static JsonObject Doc(long n, string name, string fileVersion = "1.0", string? version = null, long folder = 11, JsonObject? hold = null) => new()
    {
        ["type"] = "document", ["docNumber"] = n, ["name"] = name, ["size"] = 100, ["version"] = version ?? fileVersion,
        ["fileVersion"] = fileVersion, ["reference"] = $"{n}.{fileVersion}", ["folder"] = Ref(folder, folder == 12 ? "Drafts" : "Documents", "folder"),
        ["matter"] = new JsonObject { ["id"] = 3, ["display"] = "998.1 – Healdan Demo" },
        ["dateFiled"] = "2026-10-01T09:00:00Z", ["lastModified"] = "2026-10-25T10:50:00Z", ["hold"] = hold, ["canEdit"] = hold is null
    };

    public static JsonObject MatterView(long id, long folderId, string? lastChange = null) => new()
    {
        ["type"] = "matter", ["id"] = id, ["number"] = 1, ["display"] = "998.1 – Healdan Demo", ["name"] = "Healdan Demo",
        ["description"] = null, ["client"] = new JsonObject { ["id"] = 2, ["number"] = 998, ["name"] = "Healdan" },
        ["store"] = new JsonObject { ["id"] = 1, ["name"] = "Healdan" }, ["folderId"] = folderId, ["lastChange"] = lastChange
    };

    public static JsonObject Version(string version, string fileVersion, string date, string by = "Robert Example") => new()
    {
        ["version"] = version, ["fileVersion"] = fileVersion, ["kind"] = version.EndsWith(".0") ? "version" : "snapshot",
        ["date"] = date, ["by"] = new JsonObject { ["id"] = 1, ["name"] = by }, ["comment"] = null, ["event"] = "event.checkedin"
    };

    public static JsonObject Page(params JsonNode[] items) => new()
    {
        ["items"] = new JsonArray(items.Select(i => i.DeepClone()).ToArray()), ["offset"] = 0, ["limit"] = 50, ["total"] = items.Length
    };

    public static JsonObject Favorite(long id, JsonObject item) => new()
    {
        ["id"] = id, ["type"] = (string)item["type"]!, ["item"] = item.DeepClone()
    };

    public static JsonObject User() => new()
    {
        ["id"] = Me, ["username"] = "robert", ["name"] = "Robert Example", ["email"] = null, ["language"] = "en", ["admin"] = false
    };
}
