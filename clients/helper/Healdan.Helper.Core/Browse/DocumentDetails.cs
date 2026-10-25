using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;

namespace Healdan.Helper.Core.Browse;

/// <summary>Open's right pane for the selected document (spec §5.8): its main properties, its saves and its thumbnail.</summary>
/// <param name="Saves">The versions it was saved as, newest first; an older one opens read-only.</param>
/// <param name="Thumbnail">The thumbnail's PNG, or null for a placeholder (not made yet, or no download permission).</param>
public sealed record DocumentDetails(DocumentView Document, IReadOnlyList<Detail> Rows, IReadOnlyList<VersionView> Saves, byte[]? Thumbnail)
{
    /// <summary>How many timeline rows the pane reads: enough for the recent saves.</summary>
    public const int VersionLimit = 50;

    public static async Task<DocumentDetails> LoadAsync(HealdanApi api, long n, long? me, DateTimeOffset now, TimeZoneInfo? zone = null,
        CancellationToken ct = default)
    {
        var doc = api.DocumentAsync(n, ct);
        var properties = api.PropertiesAsync(n, ct);
        var versions = api.VersionsAsync(n, VersionLimit, ct);
        var thumbnail = api.ThumbnailAsync(n, ct);
        await Task.WhenAll(doc, properties, versions);
        var timeline = (await versions).Items;
        var rows = RowsFor(await doc, new FormModel(await properties), Versions.LastSaved(timeline, (await doc).FileVersion), me, now, zone);
        byte[]? image;
        try
        {
            image = await thumbnail;
        }
        catch (Exception e) when (e is ApiException or HttpRequestException)
        {
            image = null; // a placeholder is shown
        }
        return new DocumentDetails(await doc, rows, Versions.Saves(timeline), image);
    }

    /// <summary>Doc #, Matter, Folder, Author, Class and Subclass, when it was last saved, and who has it checked out.</summary>
    public static IReadOnlyList<Detail> RowsFor(DocumentView doc, FormModel properties, DateTimeOffset? lastSaved, long? me, DateTimeOffset now,
        TimeZoneInfo? zone = null)
    {
        var rows = new List<Detail> { new("Doc #", $"{doc.DocNumber} v{doc.FileVersion}") };
        if (doc.Matter is { } matter)
            rows.Add(new Detail("Matter", matter.Display));
        rows.Add(new Detail("Folder", doc.Folder.Name));
        foreach (var name in new[] { "author", FormModel.ClassField, "docsubclass" })
            if (properties.Field(name) is { HasValue: true } field)
                rows.Add(new Detail(field.Label, field.Text));
        if (lastSaved is { } saved)
            rows.Add(new Detail("Last Saved", When.InSentence(saved, now, zone)));
        if (Holds.Text(doc.Hold, me) is { Length: > 0 } holder)
            rows.Add(new Detail(doc.Hold!.Kind == "lock" ? "Locked" : "Checked Out", holder.Replace("checked out ", "").Replace("locked ", "")));
        if (properties.Field("comments") is { HasValue: true } comments)
            rows.Add(new Detail(comments.Label, comments.Text));
        return rows;
    }
}
