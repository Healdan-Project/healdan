using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

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
        name = DocumentNames.WithoutExtension(name, formats).Trim();
        if (docNumber is not null)
            name = CopySuffix.Replace(name, "").Trim(); // a copy's title carries its doc # and version
        return new SaveNewRequest(Text(body, "app") ?? "LibreOffice", name, formats, Text(body, "path"), docNumber);
    }

    /// <summary>
    /// The name the window starts with. Save as New Document names the new document after the original as Healdan has
    /// it, <paramref name="originalName"/> without its extension, whatever the caller sent: the window's title or the
    /// copy's file name would carry the original's doc # and version into the new document's name.
    /// </summary>
    public string StartingName(string? originalName)
    {
        if (DocNumber is null || string.IsNullOrWhiteSpace(originalName))
            return Name;
        var dot = originalName.LastIndexOf('.');
        return (dot > 0 ? originalName[..dot] : originalName).Trim();
    }

    /// <summary>
    /// The name to file under, from what the user typed: an extension that's one of the offered formats' is dropped,
    /// as the chosen format adds its own ("Lease.docx" saved as .odt is "Lease.odt", not "Lease.docx.odt").
    /// </summary>
    public string FinalName(string? typed) => DocumentNames.WithoutExtension((typed ?? "").Trim(), Formats).Trim();

    /// <summary>A local copy's " #1234 v2.1" and " (read-only)", as its title and file name end.</summary>
    private static readonly Regex CopySuffix = new(@"\s#\d+ v\d+(\.\d+)*( \(read-only\))?$", RegexOptions.CultureInvariant);

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
