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

/// <summary>A document in <see cref="FakeApi"/>.</summary>
public sealed class FakeDoc
{
    public required long Number { get; init; }

    public required string Name { get; set; }

    public string Version { get; set; } = "1.0";

    public string FileVersion { get; set; } = "1.0";

    /// <summary>Each save's file, by its version.</summary>
    public Dictionary<string, byte[]> Files { get; } = [];

    public FakeHold? Hold { get; set; }

    public bool CanWrite { get; set; } = true;

    public string? LastSaveId { get; set; }

    public DateTimeOffset LastSaved { get; set; } = DateTimeOffset.Parse("2026-10-24T09:30:00Z");

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
    private bool _breakNextSave;

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
            var doc = new FakeDoc { Number = _nextNumber++, Name = (string)json["name"]! };
            doc.Files["1.0"] = file;
            if ((bool?)json["checkOut"] == true)
                doc.Hold = Here(null);
            _docs[doc.Number] = doc;
            _calls.Add($"filed {doc.Number} checkOut={(bool?)json["checkOut"]}");
            return Ok(doc, HttpStatusCode.Created);
        }
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
        DateTimeOffset.Parse("2026-10-01T09:00:00Z"), d.LastSaved, Hold(d.Hold),
        d.CanWrite && (d.Hold is null || (d.Hold.ById == MyId && (d.Hold.DeviceId is null || d.Hold.DeviceId == HereDevice))));

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
