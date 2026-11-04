// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>Who holds a document in <see cref="FakeApi"/>: a person, and the device their check-out is held on.</summary>
public sealed record FakeHold(long ById, string ByName, string? DeviceId, string? DeviceName, string? App);

/// <summary>A save as the fake server received it, and what it answered.</summary>
public sealed record FakeSave(
    long DocNumber, string Kind, bool KeepCheckedOut, string? Name, string? IfVersion, bool IfHeld, string? SaveId,
    string Content, string Outcome);

/// <summary>A copy of a message in <see cref="FakeApi"/>: the document, the folder it's in, and the message's normalised Message-ID.</summary>
public sealed record FakeEmailCopy(string MessageId, long DocNumber, long FolderId);

/// <summary>
/// A folder in <see cref="FakeApi"/>, for <c>GET /folders/{id}</c> and <c>/folders/recent</c>: in the matter
/// <paramref name="MatterId"/>, shown as <paramref name="Matter"/>, and one the person may file into when
/// <paramref name="CanFile"/>.
/// </summary>
public sealed record FakeFolder(long Id, string Name, long MatterId, string Matter, bool CanFile);

/// <summary>A document in <see cref="FakeApi"/>.</summary>
public sealed class FakeDoc
{
    public required long Number { get; init; }

    /// <summary>
    /// The document's internal id, which the server's addresses (<c>/frontend.jsp?docId=</c>) use. It is never the doc #, so a
    /// test can't pass by taking one for the other.
    /// </summary>
    public long Id => 90_000 + Number;

    public required string Name { get; set; }

    public string Version { get; set; } = "1.0";

    public string FileVersion { get; set; } = "1.0";

    /// <summary>Each save's file, by its version.</summary>
    public Dictionary<string, byte[]> Files { get; } = [];

    public FakeHold? Hold { get; set; }

    public bool CanWrite { get; set; } = true;

    public string? LastSaveId { get; set; }

    public DateTimeOffset LastSaved { get; set; } = DateTimeOffset.Parse("2026-10-24T09:30:00Z");

    public DateTimeOffset DateFiled { get; set; } = DateTimeOffset.Parse("2026-10-01T09:00:00Z");

    public string Content => Encoding.UTF8.GetString(Files[FileVersion]);
}

/// <summary>
/// The documents API as far as the cycle calls it, with the server's state in memory: check-outs held on a device,
/// saves with their preconditions and saveIds (in the order spec §9.5 and the contract give), filings, and switches
/// for the failures the cycle must survive. Every caller is <see cref="MyName"/> on <see cref="HereName"/>.
/// </summary>
public sealed class FakeApi : HttpMessageHandler
{
    public const long MyId = 1;
    public const string MyName = "Robert Example";
    public const string HereDevice = "dev-here";
    public const string HereName = "Healdan Helper on Test Mac";

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly object _lock = new();
    private readonly Dictionary<long, FakeDoc> _docs = [];
    private readonly List<FakeSave> _saves = [];
    private readonly List<string> _calls = [];
    private long _nextNumber = 100;
    private int _dropAnswers;
    private TaskCompletionSource? _saveGate;
    private TaskCompletionSource? _downloadGate;
    private FakeHold? _cannedVersionMovedHold;
    private bool _refuseNextSave;
    private bool _refuseNextFiling;
    private bool _breakNextSave;
    private readonly List<FakeEmailCopy> _emailCopies = [];
    private readonly Dictionary<long, FakeFolder> _folders = [];
    private List<long> _recentFolders = [];
    private readonly Dictionary<long, string> _forms = [];
    private readonly HashSet<long> _brokenFolders = [];
    private readonly List<NewEmail> _emailsReceived = [];

    public static FakeHold Here(string? app = "LibreOffice") => new(MyId, MyName, HereDevice, HereName, app);

    public static FakeHold OtherDevice() => new(MyId, MyName, "dev-other", "Healdan Helper on MacBook", "LibreOffice");

    public static FakeHold OtherPerson() => new(2, "Pat Example", "dev-pat", "Healdan Helper on Pat's Mac", "LibreOffice");

    /// <summary>Every request fails as if the server can't be reached.</summary>
    public volatile bool Down;

    /// <summary>Every request is <c>401</c>, as when the token expired.</summary>
    public volatile bool Unauthorized;

    /// <summary>Downloads send a few bytes and then break off.</summary>
    public volatile bool BreakDownloads;

    public FakeDoc Add(long number, string name, string content, string version = "1.0")
    {
        var doc = new FakeDoc { Number = number, Name = name, Version = version, FileVersion = version };
        doc.Files[version] = Encoding.UTF8.GetBytes(content);
        lock (_lock)
            _docs[number] = doc;
        return doc;
    }

    public FakeDoc Doc(long number)
    {
        lock (_lock)
            return _docs[number];
    }

    /// <summary>Changes a document as another request would, under the server's lock.</summary>
    public void Change(long number, Action<FakeDoc> change)
    {
        lock (_lock)
            change(_docs[number]);
    }

    /// <summary>The next <paramref name="n"/> saves are stored, but their answers never arrive.</summary>
    public void DropAnswers(int n)
    {
        lock (_lock)
            _dropAnswers = n;
    }

    /// <summary>Holds every save open, received but unanswered, until <see cref="OpenSaves"/>.</summary>
    public void HoldSaves()
    {
        lock (_lock)
            _saveGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    public void OpenSaves()
    {
        TaskCompletionSource? gate;
        lock (_lock)
        {
            gate = _saveGate;
            _saveGate = null;
        }
        gate?.TrySetResult();
    }

    /// <summary>Holds every download open, its answer unsent, until <see cref="OpenDownloads"/> or the request is cancelled.</summary>
    public void HoldDownloads()
    {
        lock (_lock)
            _downloadGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    public void OpenDownloads()
    {
        TaskCompletionSource? gate;
        lock (_lock)
        {
            gate = _downloadGate;
            _downloadGate = null;
        }
        gate?.TrySetResult();
    }

    /// <summary>
    /// Answers the next save <c>version-moved</c> with the document as it is, its version one on and its file version
    /// the same, but shown held by <paramref name="hold"/>: what no real server sends for another device's hold, to
    /// check the helper tells this computer's hold by its device id.
    /// </summary>
    public void AnswerNextSaveVersionMoved(FakeHold hold)
    {
        lock (_lock)
            _cannedVersionMovedHold = hold;
    }

    /// <summary>Refuses the next save for good, as a file too large does (<c>413</c>): nothing is stored.</summary>
    public void RefuseNextSave()
    {
        lock (_lock)
            _refuseNextSave = true;
    }

    /// <summary>Refuses the next filing (<c>POST /documents</c> or <c>POST /emails</c>), as a folder the user can't add to does: nothing is filed.</summary>
    public void RefuseNextFiling()
    {
        lock (_lock)
            _refuseNextFiling = true;
    }

    /// <summary>
    /// Makes <paramref name="docNumber"/> (made if it isn't there) a copy of the message <paramref name="messageId"/> in
    /// folder <paramref name="folderId"/>, for <c>GET /emails</c>, <c>POST /emails/inspect</c> and as the copy a filing
    /// meets. The id is kept as the server does, without angle brackets. <paramref name="filed"/> is when it was filed.
    /// </summary>
    public void AddEmailCopy(string messageId, long docNumber, long folderId, DateTimeOffset? filed = null)
    {
        lock (_lock)
        {
            if (!_docs.ContainsKey(docNumber))
                _docs[docNumber] = new FakeDoc { Number = docNumber, Name = "Message.eml", Files = { ["1.0"] = [] } };
            if (filed is { } when)
                _docs[docNumber].DateFiled = when;
            _emailCopies.Add(new FakeEmailCopy(NormaliseMessageId(messageId), docNumber, folderId));
        }
    }

    /// <summary>
    /// Makes folder <paramref name="id"/> known, for <c>GET /folders/{id}</c>: a folder of that name in the matter
    /// <paramref name="matterId"/>, whose copies of a message are shown in it. Others are <c>404</c>.
    /// </summary>
    public void AddFolder(long id, string name, long matterId = 3, string matter = "1.1 – Smoke Matter", bool canFile = true)
    {
        lock (_lock)
            _folders[id] = new FakeFolder(id, name, matterId, matter, canFile);
    }

    /// <summary>Sets <c>GET /folders/recent</c>: these folders, in this order (each made with <see cref="AddFolder"/>).</summary>
    public void SetRecentFolders(params long[] ids)
    {
        lock (_lock)
            _recentFolders = [.. ids];
    }

    /// <summary>
    /// Sets <c>GET /folders/{id}/form</c> for the folder: <paramref name="form"/>, a <c>ProfileFieldPage</c>. A folder
    /// <see cref="AddFolder"/> made without one answers <see cref="Tests.Forms.Forms.Page"/>, whose required fields all
    /// have values; any other is <c>404</c>.
    /// </summary>
    public void SetForm(long folderId, JsonElement form)
    {
        lock (_lock)
            _forms[folderId] = form.GetRawText();
    }

    /// <summary><c>GET /folders/{id}</c> for the folder fails from now on as a server in trouble does (<c>503</c>).</summary>
    public void BreakFolder(long id)
    {
        lock (_lock)
            _brokenFolders.Add(id);
    }

    /// <summary>The <c>email</c> part of each <c>POST /emails</c> received, filed or refused, in order.</summary>
    public IReadOnlyList<NewEmail> EmailsReceived
    {
        get
        {
            lock (_lock)
                return [.. _emailsReceived];
        }
    }

    /// <summary>
    /// Answers <c>POST /emails</c> itself: called with the <c>email</c> part and the file, it gives the <c>201</c> body.
    /// Left null, the fake files only the email (its copy joins <see cref="AddEmailCopy"/>'s), refuses a message the
    /// folder already holds as <c>409 already-filed</c> with the copy, and a ticked attachment as <c>422</c>, as it has none.
    /// </summary>
    public Func<NewEmail, byte[], FiledEmail>? FileEmail { get; set; }

    /// <summary>
    /// Answers <c>POST /emails/inspect</c> itself. Left null, the fake reads the file's <c>Message-ID</c> and
    /// <c>Subject</c> headers, finds no attachments and gives the copies <see cref="AddEmailCopy"/> made.
    /// </summary>
    public Func<byte[], EmailInspection>? InspectEmail { get; set; }

    /// <summary>The next save throws what no request should, so the cycle meets a failure it doesn't expect.</summary>
    public void BreakNextSave()
    {
        lock (_lock)
            _breakNextSave = true;
    }

    public IReadOnlyList<FakeSave> Saves
    {
        get
        {
            lock (_lock)
                return [.. _saves];
        }
    }

    /// <summary>The saves received so far, stored or not, including those held open.</summary>
    public int SavesReceived
    {
        get
        {
            lock (_lock)
                return _received;
        }
    }

    private int _received;

    /// <summary>Each request as <c>METHOD /path?query</c>, below <c>/api/v1</c>.</summary>
    public IReadOnlyList<string> Calls
    {
        get
        {
            lock (_lock)
                return [.. _calls];
        }
    }

    public int Count(string call) => Calls.Count(c => c == call);

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var route = request.RequestUri!.AbsolutePath["/api/v1".Length..];
        lock (_lock)
            _calls.Add($"{request.Method} {route}{request.RequestUri.Query}");
        if (Down)
            throw new HttpRequestException("Connection refused (fake).");
        if (Unauthorized)
            return Problem(401, "unauthenticated", "Sign in again.");

        var parts = route.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts is ["documents"] && request.Method == HttpMethod.Post)
            return await FileAsync(request, ct);
        if (parts is ["emails", "inspect"] && request.Method == HttpMethod.Post)
            return await InspectAsync(request, ct);
        if (parts is ["emails"] && request.Method == HttpMethod.Post)
            return await FileEmailAsync(request, ct);
        if (parts is ["emails"] && request.Method == HttpMethod.Get)
            return CopiesOf(request.RequestUri);
        if (parts is ["folders", "recent"] && request.Method == HttpMethod.Get)
            return RecentFolders();
        if (parts is ["folders", var folderId] && request.Method == HttpMethod.Get && long.TryParse(folderId, out var folder))
            return FolderAnswer(folder);
        if (parts is ["folders", var formFolderId, "form"] && request.Method == HttpMethod.Get && long.TryParse(formFolderId, out var formFolder))
            return FormAnswer(formFolder);
        if (parts.Length < 2 || parts[0] != "documents" || !long.TryParse(parts[1], out var n))
            return Problem(404, "not-found", "No such route in the fake.");
        lock (_lock)
            if (!_docs.ContainsKey(n))
                return Problem(404, "not-found", "No such document.");

        return (request.Method.Method, parts.Length > 2 ? parts[2] : "") switch
        {
            ("GET", "") => Ok(Doc(n)),
            ("POST", "checkout") => await CheckOutAsync(n, request, ct),
            ("DELETE", "checkout") => Release(n),
            ("GET", "content") => await ContentAsync(n, request.RequestUri, ct),
            ("POST", "versions") => await SaveAsync(n, request, ct),
            _ => Problem(404, "not-found", "No such route in the fake.")
        };
    }

    private async Task<HttpResponseMessage> CheckOutAsync(long n, HttpRequestMessage request, CancellationToken ct)
    {
        var body = request.Content is null ? null : JsonNode.Parse(await request.Content.ReadAsStringAsync(ct));
        var move = (bool?)body?["move"] ?? false;
        var app = (string?)body?["app"];
        lock (_lock)
        {
            var doc = _docs[n];
            if (!doc.CanWrite)
                return Problem(403, "forbidden", "You can't edit this document.");
            if (doc.Hold is { } h && h.ById != MyId)
                return Problem(409, "checked-out", "Checked out by someone else.", HoldJson(h));
            if (doc.Hold is { DeviceId: not null } mine && mine.DeviceId != HereDevice && !move)
                return HeldElsewhere(doc);
            doc.Hold = Here(app);
            return Ok(doc);
        }
    }

    private HttpResponseMessage Release(long n)
    {
        lock (_lock)
        {
            var doc = _docs[n];
            if (doc.Hold is { } h && h.ById != MyId)
                return Problem(409, "checked-out", "Checked out by someone else.", HoldJson(h));
            if (doc.Hold is { DeviceId: not null } mine && mine.DeviceId != HereDevice)
                return HeldElsewhere(doc);
            doc.Hold = null;
            return Ok(doc);
        }
    }

    private async Task<HttpResponseMessage> ContentAsync(long n, Uri uri, CancellationToken ct)
    {
        TaskCompletionSource? gate;
        lock (_lock)
            gate = _downloadGate;
        if (gate is not null)
            await gate.Task.WaitAsync(ct);
        var version = uri.Query.StartsWith("?version=") ? Uri.UnescapeDataString(uri.Query["?version=".Length..]) : null;
        byte[] bytes;
        lock (_lock)
        {
            var doc = _docs[n];
            if (!doc.Files.TryGetValue(version ?? doc.FileVersion, out bytes!))
                return Problem(404, "not-found", "No such version.");
        }
        if (BreakDownloads)
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new BreakingStream(bytes[..1])) };
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(bytes) };
    }

    private async Task<HttpResponseMessage> SaveAsync(long n, HttpRequestMessage request, CancellationToken ct)
    {
        var (json, file) = await ReadFormAsync(request, "version", ct);
        var kind = (string)json["kind"]!;
        var keep = (bool?)json["keepCheckedOut"] ?? false;
        var ifVersion = (string?)json["ifVersion"];
        var ifHeld = (bool?)json["ifHeld"] ?? false;
        var saveId = (string?)json["saveId"];
        var name = (string?)json["name"];

        TaskCompletionSource? gate;
        lock (_lock)
        {
            _received++;
            gate = _saveGate;
        }
        if (gate is not null)
            await gate.Task.WaitAsync(ct);

        HttpResponseMessage answer;
        string outcome;
        bool drop;
        lock (_lock)
        {
            var doc = _docs[n];
            if (_breakNextSave)
            {
                _breakNextSave = false;
                _saves.Add(new FakeSave(n, kind, keep, name, ifVersion, ifHeld, saveId, Encoding.UTF8.GetString(file), "broken"));
                throw new InvalidOperationException("The save broke unexpectedly (fake).");
            }
            if (_refuseNextSave)
            {
                _refuseNextSave = false;
                (answer, outcome) = (Problem(413, "too-large", "The file is too large."), "refused");
            }
            else if (_cannedVersionMovedHold is { } shown)
            {
                _cannedVersionMovedHold = null;
                var view = View(doc) with { Version = Next(doc.Version, "snapshot"), Hold = Hold(shown) };
                (answer, outcome) = (Problem(409, "version-moved", "The document moved on.",
                    new JsonObject { ["document"] = JsonSerializer.SerializeToNode(view, Json) }), "version-moved (canned)");
            }
            else if (saveId is not null && saveId == doc.LastSaveId)
                (answer, outcome) = (Ok(doc, HttpStatusCode.Created), "repeat");
            else if (doc.Hold is { } h && h.ById != MyId)
                (answer, outcome) = (Problem(409, "checked-out", "Checked out by someone else.", HoldJson(h)), "checked-out");
            else if (doc.Hold is { DeviceId: not null } mine && mine.DeviceId != HereDevice)
                (answer, outcome) = (HeldElsewhere(doc), "held-elsewhere");
            else if (ifHeld && doc.Hold is null)
                (answer, outcome) = (Problem(409, "not-held", "It's no longer checked out to you."), "not-held");
            else if (ifVersion is not null && ifVersion != doc.Version)
                (answer, outcome) = (Problem(409, "version-moved", "The document moved on.",
                    new JsonObject { ["document"] = JsonSerializer.SerializeToNode(View(doc), Json) }), "version-moved");
            else
            {
                var number = Next(doc.Version, kind);
                doc.Files[number] = file;
                doc.Version = doc.FileVersion = number;
                if (name is not null)
                    doc.Name = name; // a save may rename the document, as the real server's does
                doc.LastSaveId = saveId;
                doc.LastSaved = DateTimeOffset.UtcNow;
                doc.Hold = keep ? Here(doc.Hold?.App) : null;
                (answer, outcome) = (Ok(doc, HttpStatusCode.Created), "stored " + number);
            }
            _saves.Add(new FakeSave(n, kind, keep, name, ifVersion, ifHeld, saveId, Encoding.UTF8.GetString(file), outcome));
            drop = outcome.StartsWith("stored") && _dropAnswers > 0;
            if (drop)
                _dropAnswers--;
        }

        if (drop)
        {
            answer.Dispose();
            throw new HttpRequestException("The connection dropped before the answer (fake).");
        }
        return answer;
    }

    private async Task<HttpResponseMessage> FileAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var (json, file) = await ReadFormAsync(request, "document", ct);
        lock (_lock)
        {
            if (_refuseNextFiling)
            {
                _refuseNextFiling = false;
                return Problem(403, "forbidden", "You can't add documents to this folder.");
            }
            var doc = new FakeDoc { Number = _nextNumber++, Name = (string)json["name"]! };
            doc.Files["1.0"] = file;
            if ((bool?)json["checkOut"] == true)
                doc.Hold = Here(null);
            _docs[doc.Number] = doc;
            _calls.Add($"filed {doc.Number} checkOut={(bool?)json["checkOut"]}");
            return Ok(doc, HttpStatusCode.Created);
        }
    }

    private async Task<HttpResponseMessage> InspectAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var file = await ReadFileAsync(request, ct);
        lock (_lock)
            return JsonAnswer(InspectEmail?.Invoke(file) ?? Inspect(file));
    }

    private EmailInspection Inspect(byte[] eml)
    {
        var (messageId, subject) = ReadHeaders(eml);
        return new EmailInspection(messageId, subject, null, null, [], messageId is null ? [] : CopyViews(messageId));
    }

    private async Task<HttpResponseMessage> FileEmailAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var (json, file) = await ReadFormAsync(request, "email", ct);
        var email = json.Deserialize<NewEmail>(Json)!;
        lock (_lock)
        {
            _emailsReceived.Add(email);
            if (_refuseNextFiling)
            {
                _refuseNextFiling = false;
                return Problem(403, "forbidden", "You can't add documents to this folder.");
            }
            if (FileEmail is { } custom)
                return JsonAnswer(custom(email, file), HttpStatusCode.Created);

            var (messageId, subject) = ReadHeaders(file);
            if (messageId is not null && _emailCopies.FirstOrDefault(c => c.MessageId == messageId && c.FolderId == email.FolderId) is { } held)
                return Problem(409, "already-filed", $"This message is already in this matter as #{held.DocNumber}.",
                    new JsonObject { ["document"] = JsonSerializer.SerializeToNode(View(_docs[held.DocNumber], held.FolderId), Json) });
            if (email.Attachments.Count > 0)
                return Problem(422, "validation", "Some fields are wrong.", new JsonObject
                {
                    ["fields"] = new JsonArray(email.Attachments.Select(i => (JsonNode)new JsonObject
                    {
                        ["field"] = "attachments",
                        ["code"] = "not-an-attachment",
                        ["detail"] = $"Part {i} isn't an attachment of this email."
                    }).ToArray())
                });

            var doc = new FakeDoc { Number = _nextNumber++, Name = (subject ?? "Message") + ".eml" };
            doc.Files["1.0"] = file;
            _docs[doc.Number] = doc;
            if (messageId is not null)
                _emailCopies.Add(new FakeEmailCopy(messageId, doc.Number, email.FolderId));
            _calls.Add($"filed {doc.Number}");
            return JsonAnswer(new FiledEmail(View(doc, email.FolderId), [], []), HttpStatusCode.Created);
        }
    }

    /// <summary>
    /// The answer of <c>GET /emails</c>: one item for each different id asked for, in order, whatever its brackets. As the
    /// server does, it counts the values sent, 1 to 100, not the distinct ids they come to.
    /// </summary>
    private HttpResponseMessage CopiesOf(Uri uri)
    {
        var sent = uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(p => p.Split('=', 2))
            .Where(p => p[0] == "messageId")
            .Select(p => Uri.UnescapeDataString(p.ElementAtOrDefault(1) ?? ""))
            .ToList();
        if (sent.Count is 0 or > 100)
            return Problem(400, "bad-request", "Send messageId 1 to 100 times.");
        var asked = sent.Select(NormaliseMessageId).Distinct().ToList();
        lock (_lock)
            return JsonAnswer(new EmailCopies([.. asked.Select(id => new EmailCopy(id, [.. _emailCopies
                .Where(c => c.MessageId == id)
                .Select(c => new EmailCopy.Copy(View(_docs[c.DocNumber], c.FolderId), $"http://localhost:8080/frontend.jsp?docId={_docs[c.DocNumber].Id}"))]))]));
    }

    private HttpResponseMessage RecentFolders()
    {
        lock (_lock)
        {
            var items = _recentFolders.Where(_folders.ContainsKey).Select(id => FolderOf(_folders[id])).ToList();
            return JsonAnswer(new Page<FolderView>(items, 0, 50, items.Count));
        }
    }

    private HttpResponseMessage FolderAnswer(long id)
    {
        lock (_lock)
        {
            if (_brokenFolders.Contains(id))
                return Problem(503, "unavailable", "Healdan is busy. Try again in a moment.");
            return _folders.TryGetValue(id, out var folder)
                ? JsonAnswer(FolderOf(folder))
                : Problem(404, "not-found", "No such folder.");
        }
    }

    private HttpResponseMessage FormAnswer(long id)
    {
        lock (_lock)
        {
            var form = _forms.TryGetValue(id, out var set) ? set : _folders.ContainsKey(id) ? Tests.Forms.Forms.Page : null;
            return form is null
                ? Problem(404, "not-found", "No such folder.")
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(form, Encoding.UTF8, "application/json") };
        }
    }

    private static FolderView FolderOf(FakeFolder f) => new(
        "folder", f.Id, f.Name, "folder", new MatterRef(f.MatterId, f.Matter), new StoreRef(1, "Healdan"),
        [new FolderRef(1, "Healdan", "store"), new FolderRef(10_000 + f.MatterId, f.Matter, "matter"), new FolderRef(f.Id, f.Name, "folder")],
        f.CanFile);

    private List<DocumentView> CopyViews(string messageId) =>
        [.. _emailCopies.Where(c => c.MessageId == messageId).Select(c => View(_docs[c.DocNumber], c.FolderId))];

    /// <summary>The Message-ID as the server keeps it: without its angle brackets (a pair) or surrounding space.</summary>
    private static string NormaliseMessageId(string id)
    {
        id = id.Trim();
        return id.Length >= 2 && id[0] == '<' && id[^1] == '>' ? id[1..^1].Trim() : id;
    }

    /// <summary>The <c>Message-ID</c> and <c>Subject</c> headers of a plain message, where the header block has them.</summary>
    private static (string? MessageId, string? Subject) ReadHeaders(byte[] eml)
    {
        string? messageId = null, subject = null;
        foreach (var line in Encoding.UTF8.GetString(eml).Split('\n'))
        {
            var text = line.TrimEnd('\r');
            if (text.Length == 0) break; // the header block ends
            if (text.StartsWith("Message-ID:", StringComparison.OrdinalIgnoreCase))
                messageId = NormaliseMessageId(text["Message-ID:".Length..]);
            else if (text.StartsWith("Subject:", StringComparison.OrdinalIgnoreCase))
                subject = text["Subject:".Length..].Trim();
        }
        return (messageId, subject);
    }

    private static async Task<byte[]> ReadFileAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var form = Assert.IsType<MultipartFormDataContent>(request.Content);
        foreach (var part in form)
            if (part.Headers.ContentDisposition?.Name?.Trim('"') == "file")
                return await part.ReadAsByteArrayAsync(ct);
        throw new InvalidOperationException("no file part");
    }

    private static async Task<(JsonNode Json, byte[] File)> ReadFormAsync(HttpRequestMessage request, string jsonPart, CancellationToken ct)
    {
        var form = Assert.IsType<MultipartFormDataContent>(request.Content);
        JsonNode? json = null;
        byte[]? file = null;
        foreach (var part in form)
        {
            var name = part.Headers.ContentDisposition?.Name?.Trim('"');
            if (name == jsonPart)
                json = JsonNode.Parse(await part.ReadAsStringAsync(ct));
            else if (name == "file")
                file = await part.ReadAsByteArrayAsync(ct);
        }
        return (json ?? throw new InvalidOperationException("no " + jsonPart + " part"), file ?? throw new InvalidOperationException("no file part"));
    }

    private static string Next(string version, string kind)
    {
        var dot = version.IndexOf('.');
        var major = int.Parse(version[..dot]);
        var minor = int.Parse(version[(dot + 1)..]);
        return kind == "version" ? $"{major + 1}.0" : $"{major}.{minor + 1}";
    }

    private HttpResponseMessage HeldElsewhere(FakeDoc doc)
    {
        var extra = HoldJson(doc.Hold!);
        extra["lastSaved"] = doc.LastSaved.ToString("O");
        return Problem(409, "held-elsewhere", "Checked out to you on another computer.", extra);
    }

    private static JsonObject HoldJson(FakeHold h) =>
        new() { ["hold"] = JsonSerializer.SerializeToNode(Hold(h), Json) };

    private static HoldView? Hold(FakeHold? h) => h is null
        ? null
        : new HoldView("checkout", new UserRef(h.ById, h.ByName), DateTimeOffset.Parse("2026-10-24T09:00:00Z"),
            h.ById == MyId && h.DeviceId is not null ? new DeviceView(h.DeviceId, h.DeviceName!, h.App) : null);

    private static DocumentView View(FakeDoc d) => new(
        "document", d.Number, d.Name, d.Files[d.FileVersion].Length, d.Version, d.FileVersion, $"{d.Number}.{d.FileVersion}",
        new FolderRef(9, "Documents", "folder"), new MatterRef(3, "1.1 – Smoke Matter"),
        d.DateFiled, d.LastSaved, Hold(d.Hold),
        d.CanWrite && (d.Hold is null || (d.Hold.ById == MyId && (d.Hold.DeviceId is null || d.Hold.DeviceId == HereDevice))));

    /// <summary>
    /// The document as a message copy in <paramref name="folderId"/>: in the folder <see cref="AddFolder"/> made (and its
    /// matter), else in a folder called Documents of the smoke matter.
    /// </summary>
    private DocumentView View(FakeDoc d, long folderId) => _folders.TryGetValue(folderId, out var f)
        ? View(d) with { Folder = new FolderRef(f.Id, f.Name, "folder"), Matter = new MatterRef(f.MatterId, f.Matter) }
        : View(d) with { Folder = new FolderRef(folderId, "Documents", "folder") };

    private static HttpResponseMessage JsonAnswer<T>(T body, HttpStatusCode status = HttpStatusCode.OK) => new(status)
    {
        Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json")
    };

    private static HttpResponseMessage Ok(FakeDoc doc, HttpStatusCode status = HttpStatusCode.OK) => new(status)
    {
        Content = new StringContent(JsonSerializer.Serialize(View(doc), Json), Encoding.UTF8, "application/json")
    };

    private static HttpResponseMessage Problem(int status, string code, string detail, JsonObject? extra = null)
    {
        var body = new JsonObject
        {
            ["type"] = "https://healdan.com/problems/" + code,
            ["title"] = code,
            ["status"] = status,
            ["detail"] = detail,
            ["code"] = code
        };
        foreach (var (key, value) in extra ?? [])
            body[key] = value?.DeepClone();
        return new HttpResponseMessage((HttpStatusCode)status)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/problem+json")
        };
    }

    /// <summary>Gives its bytes, then fails as a dropped connection does.</summary>
    private sealed class BreakingStream(byte[] first) : MemoryStream(first)
    {
        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = base.Read(buffer, offset, count);
            return n > 0 ? n : throw new IOException("The connection broke off (fake).");
        }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var n = await base.ReadAsync(buffer, cancellationToken);
            return n > 0 ? n : throw new IOException("The connection broke off (fake).");
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();
    }
}
