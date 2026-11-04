// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Mail;

/// <summary>
/// What Outlook's routes do (M5 spec §7.2): a message's status here, and, in the tasks that follow, filing it. Outlook isn't
/// in the copy cycle, so this works straight on the server's API.
/// <para>A status logs nothing, as the pane asks for one at every message it shows; its failures are the route's to log.
/// Nothing here logs a Message-ID, a subject, an address or a message's contents.</para>
/// </summary>
/// <param name="api">The signed-in server's API.</param>
/// <param name="windows">For the Save window's email mode, which filing opens.</param>
/// <param name="notifier">For the outcomes of filing that nobody is waiting at a window for.</param>
/// <param name="log">The helper's log.</param>
/// <param name="clock">The real clock in the helper; tests pass one they can move on. For a several-message ticket's expiry.</param>
#pragma warning disable CS9113 // Filing (M5 plan, Tasks 12 and 13) uses windows, notifier, log and clock; the session builds it complete from the start.
public sealed class MailFiling(HealdanApi api, IWindows windows, INotifier notifier, HelperLog log, TimeProvider? clock = null)
#pragma warning restore CS9113
{
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
        var suggestions = await SuggestionsAsync([.. threadCopies.Select(c => c.Document)]);

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
    /// the recent folders only if the thread's don't fill the list.
    /// </summary>
    private async Task<IReadOnlyList<FolderView>> SuggestionsAsync(IReadOnlyList<DocumentView> threadCopies)
    {
        Dictionary<long, FolderView?> read = [];
        var fileable = 0;
        foreach (var id in Suggestions.ThreadFolderIds(threadCopies))
        {
            if (fileable >= Suggestions.Max)
                break;
            var folder = await ReadFolderAsync(id);
            read[id] = folder;
            if (folder is { CanFile: true })
                fileable++;
        }
        var fromThread = Suggestions.Build(threadCopies, id => read.GetValueOrDefault(id), []);
        if (fromThread.Count >= Suggestions.Max)
            return fromThread;

        var recent = (await api.ListAsync(Place.RecentFolders.Path!)).Items.Select(i => i.Folder).OfType<FolderView>().ToList();
        return Suggestions.Build(threadCopies, id => read.GetValueOrDefault(id), recent);
    }

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
