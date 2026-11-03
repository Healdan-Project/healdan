// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Forms;

namespace Healdan.Helper.Core.Tests.Browse;

/// <summary>Open's Properties tab (spec §5.8, #146): the rows it shows for a document, and when it opens read-only.</summary>
public class DocumentDetailsTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-25T12:00:00Z");
    private static readonly DateTimeOffset LastSaved = DateTimeOffset.Parse("2026-10-03T09:00:00Z");
    private static readonly CultureInfo Gb = new("en-GB");

    private static DocumentView Doc(JsonObject? hold = null, bool? canEdit = null, long size = 100, bool canOpen = true)
    {
        var json = Views.Doc(1234, "Lease agreement.docx", "2.1", hold: hold);
        json["size"] = size;
        json["canOpen"] = canOpen;
        if (canEdit is { } can)
            json["canEdit"] = can;
        return JsonSerializer.Deserialize<DocumentView>(json.ToJsonString(), new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
    }

    private static JsonObject Hold(string since, string kind = "checkout")
    {
        var hold = Views.Hold(2, "Jane Smith");
        (hold["since"], hold["kind"]) = (since, kind);
        return hold;
    }

    /// <summary>The form, with these properties' values set and the fields the fixture lacks added, as the profile has them.</summary>
    private static FormModel Properties(params (string Name, string Label, string Type, string? Value)[] values)
    {
        var page = JsonNode.Parse(Tests.Forms.Forms.Page)!.AsObject();
        var items = page["items"]!.AsArray();
        foreach (var (name, label, type, value) in values)
        {
            var field = items.Select(i => i!.AsObject()).FirstOrDefault(f => (string)f["name"]! == name);
            if (field is null)
                items.Add(field = new JsonObject
                {
                    ["name"] = name, ["label"] = label, ["type"] = type, ["required"] = false, ["editable"] = true,
                    ["source"] = null, ["inheritedFrom"] = null, ["options"] = null
                });
            field["value"] = value;
        }
        return new FormModel(JsonDocument.Parse(page.ToJsonString()).RootElement.Clone());
    }

    private static List<string> Rows(DocumentView doc, FormModel properties, DateTimeOffset? lastSaved = null) =>
        DocumentDetails.RowsFor(doc, properties, lastSaved ?? LastSaved, me: 1, Now, TimeZoneInfo.Utc, Gb).Select(r => $"{r.Label} = {r.Value}").ToList();

    [Fact]
    public void TheSizeFiledAndTypistAreShown()
    {
        Assert.Equal(
            ["Doc # = 1234 v2.1", "Matter = 998.1 – Healdan Demo", "Client = Northwind Properties", "Folder = Documents", "Author = Robert Example",
             "Typist = Robert Example", "Class = Docs", "Subclass = Draft", "Filed = 1 October 2026", "Last Saved = on 3 Oct at 09:00", "Size = 48.2 KB"],
            Rows(Doc(size: 49_357), Properties()));
    }

    [Fact]
    public void TheLegacyDocNumberFollowsTheDocNumberAndAnEmailSaysWhoSentItAndWhen()
    {
        var rows = Rows(Doc(), Properties(
            ("legacydocnum", "Legacy doc #", "string", "A-1234"),
            ("emailfrom", "From", "string", "jane@example.com"),
            ("emailsubject", "Subject", "string", "Lease renewal"),
            ("emailsent", "Sent", "date", "2026-03-03T00:00:00Z")));

        Assert.Equal("Legacy doc # = A-1234", rows[1]);
        Assert.Equal(["Subclass = Draft", "From = jane@example.com", "Subject = Lease renewal", "Sent = 3 March 2026", "Filed = 1 October 2026"],
            rows.SkipWhile(r => !r.StartsWith("Subclass", StringComparison.Ordinal)).Take(5));
    }

    [Fact]
    public void PropertiesWithoutAValueHaveNoRow()
    {
        var rows = Rows(Doc(), Properties(("legacydocnum", "Legacy doc #", "string", null), ("emailsubject", "Subject", "string", " ")));

        Assert.DoesNotContain(rows, r => r.StartsWith("Legacy", StringComparison.Ordinal) || r.StartsWith("From", StringComparison.Ordinal)
            || r.StartsWith("Subject", StringComparison.Ordinal));
    }

    [Fact]
    public void ACheckOutTakenAfterTheLastSaveSaysWhen() =>
        Assert.Contains("Checked Out = to Jane Smith, since 12 October 2026", Rows(Doc(Hold("2026-10-12T15:00:00Z")), Properties()));

    [Fact]
    public void ALockSaysWhenToo() =>
        Assert.Contains("Locked = by Jane Smith, since 12 October 2026", Rows(Doc(Hold("2026-10-12T15:00:00Z", "lock")), Properties()));

    [Fact]
    public void ACheckOutKeptThroughTheLastSaveDoesntSayWhen()
    {
        // A save that keeps the document checked out checks it in and out again, moving `since` to the save (M2 §6.4).
        var rows = Rows(Doc(Hold("2026-10-03T09:00:02Z")), Properties());

        Assert.Contains("Checked Out = to Jane Smith", rows);
        Assert.Contains("Last Saved = on 3 Oct at 09:00", rows);
    }

    [Fact]
    public void ACheckOutWithoutASinceOrALastSaveDoesntSayWhen()
    {
        var noSince = Hold("2026-10-12T15:00:00Z");
        noSince["since"] = null;
        Assert.Contains("Checked Out = to Jane Smith", Rows(Doc(noSince), Properties()));

        var unknownSave = DocumentDetails.RowsFor(Doc(Hold("2026-10-12T15:00:00Z")), Properties(), null, me: 1, Now, TimeZoneInfo.Utc, Gb);
        Assert.Contains(unknownSave, r => r is { Label: "Checked Out", Value: "to Jane Smith" });
    }

    [Fact]
    public void ADocumentTheUserMayNotEditOpensReadOnly()
    {
        Assert.True(DocumentDetails.MayNotEdit(Doc(canEdit: false))); // no Write permission, immutable or archived
        Assert.False(DocumentDetails.MayNotEdit(Doc()));
        Assert.False(DocumentDetails.MayNotEdit(Doc(Hold("2026-10-12T15:00:00Z"), canEdit: false))); // its Checked Out row says why
    }

    [Fact]
    public void ADocumentTheHelperCantOpenDoesntSayItOpensReadOnly() =>
        // No Download permission, or protected by a password: the server refuses its file too, so it opens neither way
        Assert.False(DocumentDetails.MayNotEdit(Doc(canEdit: false, canOpen: false)));
}
