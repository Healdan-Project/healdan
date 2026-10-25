using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Forms;

/// <summary>
/// What <c>/v1/save-new</c> asks of the Save window (plan: the channel's routes): the app, the document's name, the
/// formats it can be written in (the app's own first, spec §5.6), and for Save as New Document the copy it comes from,
/// with its doc #, which the channel adds once it has checked the path is a copy.
/// </summary>
public sealed record SaveNewRequest(string App, string Name, IReadOnlyList<string> Formats, string? Path, long? DocNumber)
{
    public static SaveNewRequest From(JsonObject body)
    {
        var formats = body["formats"] is JsonArray list
            ? list.Select(f => f is JsonValue v && v.TryGetValue<string>(out var s) ? s.Trim().TrimStart('.').ToLowerInvariant() : "")
                .Where(f => f.Length > 0).Distinct().ToList()
            : [];
        var name = Text(body, "name") ?? "";
        // Any JSON number: a value made in code may hold an int, which TryGetValue<long> doesn't answer.
        long? docNumber = body["docNumber"] is JsonValue n && n.GetValueKind() == JsonValueKind.Number
            && long.TryParse(n.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var d) ? d : null;
        return new SaveNewRequest(Text(body, "app") ?? "LibreOffice", DocumentNames.WithoutExtension(name, formats).Trim(), formats,
            Text(body, "path"), docNumber);
    }

    /// <summary>The format chosen at first: the copy's own for Save as New Document when it's offered, else the app's own.</summary>
    public string? DefaultFormat()
    {
        if (Path is not null && System.IO.Path.GetExtension(Path).TrimStart('.').ToLowerInvariant() is { Length: > 0 } own
            && Formats.Contains(own))
            return own;
        return Formats.Count > 0 ? Formats[0] : null;
    }

    private static string? Text(JsonObject body, string key) =>
        body[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;
}
