using System.Text.Json;

namespace Healdan.Helper.Core.Api;

/// <summary>
/// One item of a list the windows show: a folder, a document or a matter, by its <c>type</c>, as search, a folder's
/// children and the recent lists return them. A favourite's item carries the favourite's id too.
/// </summary>
public sealed record ListItem(FolderView? Folder, DocumentView? Document, MatterView? Matter, long? FavoriteId)
{
    /// <summary>The item, or null for a type the helper doesn't show.</summary>
    public static ListItem? From(JsonElement item)
    {
        if (item.ValueKind != JsonValueKind.Object)
            return null;
        if (item.TryGetProperty("item", out _))
        {
            var favorite = item.Deserialize<FavoriteView>(HealdanApi.Json)!;
            return favorite.Folder() is { } folder ? new ListItem(folder, null, null, favorite.Id)
                : favorite.Document() is { } doc ? new ListItem(null, doc, null, favorite.Id)
                : null;
        }
        var type = item.TryGetProperty("type", out var t) && t.ValueKind == JsonValueKind.String ? t.GetString() : null;
        return type switch
        {
            "folder" => new ListItem(item.Deserialize<FolderView>(HealdanApi.Json), null, null, null),
            "document" => new ListItem(null, item.Deserialize<DocumentView>(HealdanApi.Json), null, null),
            "matter" => new ListItem(null, null, item.Deserialize<MatterView>(HealdanApi.Json), null),
            _ => null
        };
    }
}
