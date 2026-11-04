// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Forms;

/// <summary>
/// What filing a message asks of the Save window's email mode (M5 spec §7.3): the message as <c>inspect</c> found it
/// (its subject, sender and when it was sent, read-only), the attachments to offer, and the folder to open at: a File
/// Here's whose form needs something typed or that Healdan refused, or the pane's first suggestion. There's no name: M1
/// names every email after its subject (plan correction 10).
/// </summary>
/// <param name="Attachments">The parts to offer as separate documents: never the inline ones (M5 spec §6.6).</param>
/// <param name="Subjects">The messages' subjects: one for one message, none when it has no subject, and more for several (Task 13).</param>
/// <param name="FolderId">The folder to open at; null opens at Recent Folders.</param>
/// <param name="Refusal">Why Healdan refused a File Here into that folder, shown as the window opens (M5 spec §10); null for none.</param>
/// <param name="To">The To recipients, joined with ", ", read-only beside From.</param>
/// <param name="Cc">The CC recipients, likewise.</param>
/// <param name="Received">When it was received, read-only beside <paramref name="Sent"/>.</param>
public sealed record SaveEmailRequest(string? Subject, string? From, DateTimeOffset? Sent, IReadOnlyList<EmailPart> Attachments,
    IReadOnlyList<string> Subjects, long? FolderId, SubmitOutcome.Refused? Refusal = null,
    string? To = null, string? Cc = null, DateTimeOffset? Received = null)
{
    /// <summary>One message, from what <c>inspect</c> found in it, with its inline parts left out.</summary>
    public static SaveEmailRequest For(EmailInspection inspection, long? folderId, SubmitOutcome.Refused? refusal = null) => new(
        inspection.Subject, inspection.From, inspection.Sent, [.. inspection.Attachments.Where(a => !a.Inline)],
        inspection.Subject is { } subject ? [subject] : [], folderId, refusal, inspection.To, inspection.Cc, inspection.Received);

    /// <summary>
    /// The request as <see cref="IWindows"/> passes it, which <see cref="ToJson"/> made; anything it lacks is left empty.
    /// Named apart from the record's <see cref="From"/>, the sender, which C# won't let a method share.
    /// </summary>
    public static SaveEmailRequest FromJson(JsonObject request)
    {
        var attachments = request["attachments"] is JsonArray parts
            ? parts.OfType<JsonObject>().Select(p => p.Deserialize<EmailPart>(HealdanApi.Json)).OfType<EmailPart>().Where(p => !p.Inline).ToList()
            : [];
        var subjects = request["subjects"] is JsonArray list
            ? list.Select(s => s is JsonValue v && v.TryGetValue<string>(out var text) ? text : null).OfType<string>().ToList()
            : [];
        var sent = Moment(request, "sent");
        // Any JSON number: a value made in code may hold an int, which TryGetValue<long> doesn't answer.
        long? folderId = request["folderId"] is JsonValue n && n.GetValueKind() == JsonValueKind.Number
            && long.TryParse(n.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var id) ? id : null;
        SubmitOutcome.Refused? refusal = null;
        if (request["refusal"] is JsonObject refused && Text(refused, "problem") is { } problem)
        {
            var fields = refused["fields"] is JsonArray errors
                ? errors.OfType<JsonObject>().Select(f => f.Deserialize<FieldError>(HealdanApi.Json)).OfType<FieldError>().ToList()
                : [];
            refusal = new SubmitOutcome.Refused(problem, fields);
        }
        return new SaveEmailRequest(Text(request, "subject"), Text(request, "from"), sent, attachments, subjects, folderId, refusal,
            Text(request, "to"), Text(request, "cc"), Moment(request, "received"));
    }

    private static DateTimeOffset? Moment(JsonObject body, string key) =>
        body[key] is JsonValue at && at.TryGetValue<string>(out var stamp)
        && DateTimeOffset.TryParse(stamp, CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed) ? parsed : null;

    /// <summary>
    /// <c>{"subject", "from", "to", "cc", "sent", "received", "attachments": [EmailPart…], "subjects": […], "folderId",
    /// "refusal": {"problem", "fields": [FieldError…]}}</c>, for <see cref="IWindows.SaveEmailAsync"/>.
    /// </summary>
    public JsonObject ToJson() => new()
    {
        ["subject"] = Subject,
        ["from"] = From,
        ["to"] = To,
        ["cc"] = Cc,
        ["sent"] = Sent?.ToString("O", CultureInfo.InvariantCulture),
        ["received"] = Received?.ToString("O", CultureInfo.InvariantCulture),
        ["attachments"] = JsonSerializer.SerializeToNode(Attachments, HealdanApi.Json),
        ["subjects"] = new JsonArray([.. Subjects.Select(s => (JsonNode?)s)]),
        ["folderId"] = FolderId,
        ["refusal"] = Refusal is { } r
            ? new JsonObject { ["problem"] = r.Problem, ["fields"] = JsonSerializer.SerializeToNode(r.Fields, HealdanApi.Json) }
            : null,
    };

    /// <summary>An attachment as its tick names it: <c>Lease.pdf (2.0 KB)</c>.</summary>
    public static string Label(EmailPart part, CultureInfo? culture = null) => $"{part.Name} ({FileSizes.Text(part.Size, culture)})";

    private static string? Text(JsonObject body, string key) =>
        body[key] is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;
}
