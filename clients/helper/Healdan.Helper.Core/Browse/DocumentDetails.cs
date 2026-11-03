using System.Globalization;
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

    /// <summary>
    /// How much later than the last save a hold must start to say since when. A save that keeps the document checked out
    /// checks it in and out again in one request, which moves <c>hold.since</c> to just after the save (M2 §6.4).
    /// </summary>
    private static readonly TimeSpan KeptThroughASave = TimeSpan.FromMinutes(1);

    public static async Task<DocumentDetails> LoadAsync(HealdanApi api, long n, long? me, DateTimeOffset now, TimeZoneInfo? zone = null,
        CultureInfo? culture = null, CancellationToken ct = default)
    {
        var doc = api.DocumentAsync(n, ct);
        var properties = api.PropertiesAsync(n, ct);
        var versions = api.VersionsAsync(n, VersionLimit, ct);
        var thumbnail = api.ThumbnailAsync(n, ct);
        await Task.WhenAll(doc, properties, versions);
        var timeline = (await versions).Items;
        var rows = RowsFor(await doc, new FormModel(await properties), Versions.LastSaved(timeline, (await doc).FileVersion), me, now, zone, culture);
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

    /// <summary>
    /// Doc # and any legacy doc #, Matter and its Client, Folder, Author, Typist, Class and Subclass, a filed email's From,
    /// Subject and Sent, when it was filed and last saved, its size, who has it checked out, and its comments (#146).
    /// A property without a value has no row.
    /// </summary>
    public static IReadOnlyList<Detail> RowsFor(DocumentView doc, FormModel properties, DateTimeOffset? lastSaved, long? me, DateTimeOffset now,
        TimeZoneInfo? zone = null, CultureInfo? culture = null)
    {
        var rows = new List<Detail> { new("Doc #", $"{doc.DocNumber} v{doc.FileVersion}") };
        AddProperties(rows, properties, culture, "legacydocnum"); // where people coming from an old system look for the old number
        if (doc.Matter is { } matter)
            rows.Add(new Detail("Matter", matter.Display));
        if (doc.Matter?.Client is { } client)
            rows.Add(new Detail("Client", client.Name));
        rows.Add(new Detail("Folder", doc.Folder.Name));
        AddProperties(rows, properties, culture, "author", "typist", FormModel.ClassField, "docsubclass", "emailfrom", "emailsubject", "emailsent");
        rows.Add(new Detail("Filed", When.Date(doc.DateFiled, zone, culture)));
        if (lastSaved is { } saved)
            rows.Add(new Detail("Last Saved", When.InSentence(saved, now, zone, culture)));
        rows.Add(new Detail("Size", FileSizes.Text(doc.Size, culture)));
        if (Holds.Text(doc.Hold, me) is { Length: > 0 } holder)
        {
            var who = holder.Replace("checked out ", "").Replace("locked ", "");
            // Since when only for a hold taken after the last save: one kept through a save has its since moved to it,
            // and the Last Saved row says when that was (spec §9.10).
            if (doc.Hold!.Since is { } since && lastSaved is { } last && since - last > KeptThroughASave)
                who += ", since " + When.Date(since, zone, culture);
            rows.Add(new Detail(doc.Hold.Kind == "lock" ? "Locked" : "Checked Out", who));
        }
        AddProperties(rows, properties, culture, "comments");
        return rows;
    }

    /// <summary>
    /// The user may not check the document out (no Write permission, immutable or archived), and nobody holds it, so it
    /// opens read-only. A hold has its own row saying who has it.
    /// </summary>
    public static bool MayNotEdit(DocumentView doc) => !doc.CanEdit && doc.Hold is null;

    /// <summary>A row for each of these properties that has a value, under the profile's label; a date in the region's order.</summary>
    private static void AddProperties(List<Detail> rows, FormModel properties, CultureInfo? culture, params string[] names)
    {
        foreach (var name in names)
            if (properties.Field(name) is { HasValue: true } field)
                rows.Add(new Detail(field.Label, field.Date is { } date ? When.Date(date, culture) : field.Text));
    }
}
