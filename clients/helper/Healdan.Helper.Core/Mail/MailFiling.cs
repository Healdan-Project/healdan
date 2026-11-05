// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Mail;

/// <summary>
/// <c>inspect</c> refused the message as over Healdan's upload limit (<c>413 too-large</c>). The route answers it <c>409
/// over-upload-limit</c>, with <see cref="Exception.Message"/> (the server's words) as <c>detail</c>, so the pane can say so.
/// </summary>
public sealed class MailTooLargeException(string detail) : Exception(detail);

/// <summary>
/// What Outlook's routes do (M5 spec §7.2): a message's status, and filing one message, here; filing several follows (M5
/// plan, Task 13). Outlook isn't in the copy cycle, so this works straight on the server's API.
/// <para>A status logs nothing but a status it had to answer without suggestions, as the pane asks for one at every message
/// it shows; its failures are the route's to log, as a filing's are. A filing logs its outcome by doc #. Nothing here logs
/// a Message-ID, a subject, an address, an attachment's name or a message's contents.</para>
/// </summary>
/// <param name="api">The signed-in server's API.</param>
/// <param name="windows">For the Save window's email mode, which filing opens.</param>
/// <param name="notifier">
/// For a filing that failed before any window was up (File Here couldn't reach Healdan, or <c>inspect</c> failed): the pane
/// says only that it did, and the notification says why. The Save window shows its own refusals.
/// </param>
/// <param name="log">The helper's log.</param>
/// <param name="clock">The real clock in the helper; tests pass one they can move on. For a several-message ticket's expiry.</param>
#pragma warning disable CS9113 // Filing several messages (M5 plan, Task 13) uses clock; the session builds it complete from the start.
public sealed class MailFiling(HealdanApi api, IWindows windows, INotifier notifier, HelperLog log, TimeProvider? clock = null)
#pragma warning restore CS9113
{
    private const string Helper = "Healdan Helper";

    /// <summary>The most Message-IDs one status asks the server about: the message's, its In-Reply-To, and the newest 19 of its References.</summary>
    public const int MaxStatusIds = 21;

    /// <summary>
    /// The most the ids of one lookup add to the request line, as the server reads them (<c>messageId=…&amp;</c>, percent-encoded):
    /// well under the 8 KB that Tomcat and most proxies allow the line and the headers together, however long the ids are.
    /// </summary>
    private const int MaxQueryChars = 6000;

    private const string Parameter = "messageId=";

    /// <summary>
    /// <c>mail/status</c>: whether the message is filed, where, and the folders to suggest filing it in.
    /// <c>{"filed": bool, "copies": [{docNumber, name, matter, folder, address}], "elsewhere": bool, "suggestions": [{folderId, path}]}</c>.
    /// <para><paramref name="messageIds"/> are the message's own Message-ID first, then its In-Reply-To, then its References,
    /// oldest first, as the headers have them. Asking about every one of a long thread would make the request line too long,
    /// so only the message's own, its In-Reply-To and the newest 19 References go to the server (<see cref="MaxStatusIds"/>
    /// at most, and a few thousand characters of them), whatever is sent.</para>
    /// <para><c>copies</c> are the readable copies of the message's own id, <c>address</c> the page of the server's that shows
    /// one. <c>elsewhere</c> is true when the message is filed but its thread's newest copy is in a matter none of the
    /// message's copies is in, so the pane can say Filed in Another Matter. <c>suggestions</c> are the folders of the other
    /// ids' copies, newest first, then the person's recent folders (<see cref="Suggestions"/>).</para>
    /// <para>The server's answers are matched to the ids by the id the server gives back, normalised as the helper sent it,
    /// never by position, as the server collapses ids that differ only in their brackets.</para>
    /// </summary>
    /// <exception cref="ArgumentException"><paramref name="messageIds"/> is empty.</exception>
    /// <exception cref="ApiException">The server refused: it is not the helper's to say what was wrong.</exception>
    public async Task<JsonObject> StatusAsync(IReadOnlyList<string> messageIds)
    {
        if (messageIds.Count == 0)
            throw new ArgumentException("A message has at least its own Message-ID.", nameof(messageIds));

        var (own, ask) = IdsToAsk(messageIds);
        Dictionary<string, List<EmailCopy.Copy>> byId = [];
        if (ask.Count > 0)
        {
            foreach (var item in (await api.EmailCopiesAsync(ask)).Items)
                byId[item.MessageId] = item.Copies;
        }

        List<EmailCopy.Copy> ownCopies = own is not null && byId.TryGetValue(own, out var found) ? found : [];
        List<EmailCopy.Copy> threadCopies = [.. ask.Where(id => id != own).SelectMany(id => byId.TryGetValue(id, out var c) ? c : [])];
        HashSet<long> heldIn = [.. ownCopies.Select(c => c.Document.Matter?.Id).OfType<long>()];
        var suggestions = await SuggestionsAsync([.. threadCopies.Select(c => c.Document)], heldIn);

        return new JsonObject
        {
            ["filed"] = ownCopies.Count > 0,
            ["copies"] = new JsonArray([.. ownCopies.Select(c => (JsonNode)new JsonObject
            {
                ["docNumber"] = c.Document.DocNumber,
                ["name"] = c.Document.Name,
                ["matter"] = c.Document.Matter?.Display,
                ["folder"] = c.Document.Folder.Name,
                ["address"] = c.Address,
            })]),
            ["elsewhere"] = Elsewhere(ownCopies, threadCopies),
            ["suggestions"] = new JsonArray([.. suggestions.Select(f => (JsonNode)new JsonObject
            {
                ["folderId"] = f.Id,
                ["path"] = Suggestions.Written(f),
            })]),
        };
    }

    /// <summary>
    /// The ids to ask the server about, normalised, each once, in the order they matter: the message's own (the first of
    /// <paramref name="messageIds"/>), its In-Reply-To (the second), then References newest first, 19 at most. Ids the server
    /// would count as none (empty, over 998 characters), and those that would take the request line past
    /// <see cref="MaxQueryChars"/>, are left out. <c>Own</c> is the message's own id, null when it has none to ask about.
    /// </summary>
    private static (string? Own, List<string> Ask) IdsToAsk(IReadOnlyList<string> messageIds)
    {
        var own = MessageIds.Normalise(messageIds[0]);
        List<string> ask = [];
        var room = MaxQueryChars;

        void Take(string? id)
        {
            if (id is null || ask.Contains(id))
                return;
            var cost = Parameter.Length + Uri.EscapeDataString(id).Length + 1;
            if (cost > room)
                return;
            room -= cost;
            ask.Add(id);
        }

        Take(own);
        if (messageIds.Count > 1)
            Take(MessageIds.Normalise(messageIds[1]));
        for (var i = messageIds.Count - 1; i >= 2 && ask.Count < MaxStatusIds; i--)
            Take(MessageIds.Normalise(messageIds[i]));
        return (own, ask);
    }

    /// <summary>
    /// Whether the message's thread went to a matter the message isn't in: it is filed, the thread's newest copy is in a
    /// matter, and none of the message's copies is in that one.
    /// </summary>
    private static bool Elsewhere(List<EmailCopy.Copy> ownCopies, List<EmailCopy.Copy> threadCopies)
    {
        if (ownCopies.Count == 0 || threadCopies.Count == 0)
            return false;
        if (threadCopies.OrderByDescending(c => c.Document.DateFiled).First().Document.Matter is not { } threadMatter)
            return false;
        return ownCopies.All(c => c.Document.Matter?.Id != threadMatter.Id);
    }

    /// <summary>
    /// The folders to suggest: the thread's, each read once, and only as many as it takes to have <see cref="Suggestions.Max"/>;
    /// the recent folders only if the thread's don't fill the list. None in a matter of <paramref name="heldIn"/>, which hold
    /// the message already. Suggestions are a convenience: when the server fails a read for now (anything but a folder
    /// that's gone or not the person's to see), there are none, and the status still says where the message is filed.
    /// </summary>
    private async Task<IReadOnlyList<FolderView>> SuggestionsAsync(IReadOnlyList<DocumentView> threadCopies, IReadOnlySet<long> heldIn)
    {
        try
        {
            Dictionary<long, FolderView?> read = [];
            var offerable = 0;
            foreach (var id in Suggestions.ThreadFolderIds(threadCopies))
            {
                if (offerable >= Suggestions.Max)
                    break;
                var folder = await ReadFolderAsync(id);
                read[id] = folder;
                if (folder is not null && Suggestions.Offerable(folder, heldIn))
                    offerable++;
            }
            var fromThread = Suggestions.Build(threadCopies, id => read.GetValueOrDefault(id), [], heldIn);
            if (fromThread.Count >= Suggestions.Max)
                return fromThread;

            var recent = (await api.ListAsync(Place.RecentFolders.Path!)).Items.Select(i => i.Folder).OfType<FolderView>().ToList();
            return Suggestions.Build(threadCopies, id => read.GetValueOrDefault(id), recent, heldIn);
        }
        catch (Exception e) when (e is ApiException or HttpRequestException or OperationCanceledException or JsonException)
        {
            log.Warn($"A message's status was answered without suggestions, as a folder couldn't be read for now ({Why(e)})");
            return [];
        }
    }

    /// <summary>
    /// <c>mail/file</c>: files the message <paramref name="eml"/> (its <c>.eml</c>) and answers the outcome,
    /// <c>{"outcome": "filed" | "already-filed" | "cancelled", "email": {docNumber, name, matter, folder}, "attachments": n,
    /// "skipped": n}</c>, with <c>email</c> the new document or, for <c>already-filed</c>, the copy the matter holds, and the
    /// counts for <c>filed</c> only.
    /// <list type="bullet">
    /// <item><b>With <paramref name="folderId"/></b> (File Here): when the folder's form needs nothing typed (every required
    /// field has a value, its own or an inherited one), the message is filed there at once, with nothing typed and no
    /// attachments, so a one-click filing never fails for a missing property. When it needs something typed, or Healdan
    /// refuses the filing (or the folder), the Save window opens at it, with the refusal shown (M5 spec §10).</item>
    /// <item><b>The Save window's email mode</b> (File…, M5 spec §7.3) shows what <c>inspect</c> found, opening at
    /// <paramref name="openAt"/> (the pane's first suggestion) or else at Recent Folders, and files through the window
    /// (<see cref="SubmitAsync"/>): a refusal or an outage shows in it, and it stays open to choose another folder or untick
    /// an attachment. Its Cancel files nothing.</item>
    /// </list>
    /// <para>A matter that holds the message already answers <c>already-filed</c>, an outcome rather than a failure: a
    /// repeat after a lost answer, or a message someone else filed meanwhile, is done. Before any window is up, a failure
    /// of File Here that is an outage (no answer, or a 5xx), or any failure of <c>inspect</c>, is told to the user in a
    /// notification, with Healdan's own words where it gave any, and thrown, for the route to answer and log.</para>
    /// </summary>
    public async Task<JsonObject> FileAsync(byte[] eml, long? folderId, long? openAt = null)
    {
        EmailInspection inspection;
        SubmitOutcome.Refused? refusal = null;
        try
        {
            if (folderId is { } id)
            {
                bool nothingToType;
                try
                {
                    nothingToType = await NeedsNothingTypedAsync(id);
                }
                catch (ApiException e) when (!Outage(e))
                {
                    log.Warn($"File Here couldn't read folder {id}'s form ({Why(e)}): the Save window opens at it, saying why");
                    (nothingToType, refusal) = (false, RefusalOf(e));
                }
                if (nothingToType)
                {
                    try
                    {
                        return Filed(await api.FileEmailAsync(eml, new NewEmail(id, new JsonObject(), [])), id);
                    }
                    catch (ApiException e) when (e.Code == AlreadyFiled)
                    {
                        return AlreadyFiledOutcome(e);
                    }
                    catch (ApiException e) when (!Outage(e))
                    {
                        log.Warn($"Healdan refused File Here into folder {id} ({Why(e)}): the Save window opens at it, saying why");
                        refusal = RefusalOf(e);
                    }
                }
            }
            try
            {
                inspection = await api.InspectEmailAsync(eml);
            }
            catch (ApiException e) when (e.Status == 413 && e.Code == "too-large")
            {
                // A message over the upload limit: the pane says so, with the server's words (a limit, not a name).
                notifier.Notify(Helper, Notice(e));
                throw new MailTooLargeException(e.Detail);
            }
        }
        catch (Exception e) when (e is not MailTooLargeException)
        {
            notifier.Notify(Helper, Notice(e));
            throw;
        }

        JsonObject? outcome = null;
        var request = SaveEmailRequest.For(inspection, folderId ?? openAt, refusal);
        await windows.SaveEmailAsync(request.ToJson(), async answer =>
        {
            var (result, done) = await SubmitAsync(eml, answer);
            outcome ??= done;
            return result;
        });
        if (outcome is null)
        {
            log.Info("Filing an email from Outlook was cancelled in the Save window");
            return new JsonObject { ["outcome"] = "cancelled" };
        }
        return outcome;
    }

    /// <summary>
    /// The Save window's Save in its email mode: files what it chose. Filed, or the matter holds the message already, is
    /// done, with the route's outcome. Healdan's refusal (the filing rule, no permission, the size limit, a property, a
    /// ticked attachment) is shown in the window, and so is an outage, as <see cref="SubmitOutcome.Refused.CantReach"/>:
    /// the window stays open for another try. Nothing is told in a notification, as the window says it.
    /// </summary>
    private async Task<(SubmitOutcome Result, JsonObject? Outcome)> SubmitAsync(byte[] eml, JsonObject answer)
    {
        try
        {
            var email = Chosen(answer) ?? throw new InvalidOperationException("The Save window's Save chose nothing to file.");
            try
            {
                return (SubmitOutcome.Done, Filed(await api.FileEmailAsync(eml, email), email.FolderId));
            }
            catch (ApiException e) when (e.Code == AlreadyFiled)
            {
                return (SubmitOutcome.Done, AlreadyFiledOutcome(e));
            }
        }
        catch (Exception e) when (Outage(e))
        {
            log.Warn($"Filing an email from the Save window couldn't reach Healdan ({Why(e)}): the window stays open");
            return (new SubmitOutcome.Refused(SubmitOutcome.Refused.CantReach, []), null);
        }
        catch (ApiException e)
        {
            log.Warn($"Healdan refused an email from the Save window ({Why(e)}): the window shows why and stays open");
            return (RefusalOf(e), null);
        }
        catch (Exception e)
        {
            log.Error("Filing an email from the Save window failed", e);
            return (new SubmitOutcome.Refused("Healdan Helper couldn't file the email. Try again.", []), null);
        }
    }

    private const string AlreadyFiled = "already-filed";

    /// <summary>No answer, or Healdan's own failure (a 5xx): nothing the user can change but to try again.</summary>
    private static bool Outage(Exception e) =>
        e is HttpRequestException or OperationCanceledException or JsonException || e is ApiException { Status: >= 500 };

    /// <summary>A refusal as the window shows it: Healdan's words, and its field errors to place.</summary>
    private static SubmitOutcome.Refused RefusalOf(ApiException e)
    {
        List<FieldError> fields = [];
        if (e.Body.ValueKind == JsonValueKind.Object && e.Body.TryGetProperty("fields", out var errors) && errors.ValueKind == JsonValueKind.Array)
        {
            foreach (var error in errors.EnumerateArray())
            {
                try
                {
                    if (error.ValueKind == JsonValueKind.Object && error.Deserialize<FieldError>(HealdanApi.Json) is { Field: not null, Detail: not null } field)
                        fields.Add(field);
                }
                catch (JsonException)
                {
                    // An error the helper can't read has no place of its own; the problem's words still show.
                }
            }
        }
        return new SubmitOutcome.Refused(e.Status == 401 ? "Sign in to Healdan again, then choose Save." : e.Detail, fields);
    }

    /// <summary>The route's outcome for an email filed into <paramref name="folderId"/>, which is logged by doc #.</summary>
    private JsonObject Filed(FiledEmail filed, long folderId)
    {
        log.Info($"Filed an email from Outlook as #{filed.Email.DocNumber} in folder {folderId}, with {filed.Attachments.Count} "
            + $"of its attachments{(filed.Skipped.Count > 0 ? $" ({filed.Skipped.Count} the matter held already)" : "")}");
        return new JsonObject
        {
            ["outcome"] = "filed",
            ["email"] = Written(filed.Email),
            ["attachments"] = filed.Attachments.Count,
            ["skipped"] = filed.Skipped.Count,
        };
    }

    /// <summary>The route's outcome for a message the matter holds already, with its copy.</summary>
    private JsonObject AlreadyFiledOutcome(ApiException e)
    {
        var copy = CopyIn(e);
        log.Info($"An email from Outlook is in that matter already{(copy is null ? "" : $" as #{copy.DocNumber}")}: nothing more was filed");
        return new JsonObject { ["outcome"] = AlreadyFiled, ["email"] = copy is null ? null : Written(copy) };
    }

    /// <summary>Whether a filing into the folder needs nothing typed: every required field of its form has a value already.</summary>
    private async Task<bool> NeedsNothingTypedAsync(long folderId) => new FormModel(await api.FormAsync(folderId)).CanSave(folderCanFile: true);

    /// <summary>The Save window's answer as a filing, or null for anything but Save.</summary>
    private static NewEmail? Chosen(JsonObject answer)
    {
        if (answer["action"] is not JsonValue action || !action.TryGetValue<string>(out var chosen) || chosen != "save")
            return null;
        var folderId = Whole(answer["folderId"]) ?? throw new InvalidOperationException("The Save window chose no folder.");
        var properties = answer["properties"] is JsonObject given ? (JsonObject)given.DeepClone() : new JsonObject();
        List<int> attachments = answer["attachments"] is JsonArray ticked
            ? [.. ticked.Select(t => (int)(Whole(t) ?? throw new InvalidOperationException("The Save window ticked an attachment by no index.")))]
            : [];
        return new NewEmail(folderId, properties, attachments);
    }

    /// <summary>Any whole JSON number: a value made in code may hold an int, which <c>GetValue&lt;long&gt;</c> doesn't answer.</summary>
    private static long? Whole(JsonNode? node) =>
        node is JsonValue n && n.GetValueKind() == JsonValueKind.Number
        && long.TryParse(n.ToJsonString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var whole)
            ? whole : null;

    /// <summary>The copy a <c>409 already-filed</c> names, or null when it names none that can be read.</summary>
    private static DocumentView? CopyIn(ApiException e)
    {
        try
        {
            return e.Body.ValueKind == JsonValueKind.Object && e.Body.TryGetProperty("document", out var document)
                && document.ValueKind == JsonValueKind.Object
                ? document.Deserialize<DocumentView>(HealdanApi.Json)
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>A filed email as the pane shows it, as a status's copies are.</summary>
    private static JsonObject Written(DocumentView doc) => new()
    {
        ["docNumber"] = doc.DocNumber,
        ["name"] = doc.Name,
        ["matter"] = doc.Matter?.Display,
        ["folder"] = doc.Folder.Name,
    };

    /// <summary>
    /// What the user is told when a filing fails before any window is up. Healdan's own words where it gave any (a refusal
    /// of several fields lists each).
    /// </summary>
    private static string Notice(Exception e) => e switch
    {
        _ when Outage(e) => "The email couldn't be filed in Healdan, which may be unreachable. It's still in Outlook, so file it again.",
        ApiException { Status: 401 } => "The email wasn't filed: sign in to Healdan again, then file it once more.",
        ApiException api => $"The email couldn't be filed in Healdan: {Detail(api)}",
        _ => "The email couldn't be filed in Healdan. It's still in Outlook, so file it again."
    };

    private static string Detail(ApiException e)
    {
        if (e.Body.ValueKind == JsonValueKind.Object && e.Body.TryGetProperty("fields", out var fields) && fields.ValueKind == JsonValueKind.Array
            && fields.GetArrayLength() > 1)
        {
            var each = fields.EnumerateArray()
                .Select(f => f.ValueKind == JsonValueKind.Object && f.TryGetProperty("detail", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString() : null)
                .OfType<string>().ToList();
            if (each.Count > 0)
                return string.Join(" ", each);
        }
        return e.Detail;
    }

    /// <summary>A failure as the log may name it: Healdan's status and code, never its words, which can name an attachment.</summary>
    private static string Why(Exception e) => e is ApiException api ? $"Healdan answered {api.Status} {api.Code}" : e.GetType().Name;

    /// <summary>A folder, or null when it's gone or isn't the person's to see.</summary>
    private async Task<FolderView?> ReadFolderAsync(long id)
    {
        try
        {
            return await api.FolderAsync(id);
        }
        catch (ApiException e) when (e.Status is 404 or 403)
        {
            return null;
        }
    }
}
