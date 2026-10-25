using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Tests.Api;

public class HealdanApiTests : IDisposable
{
    private const string DocJson = """
        {"type":"document","docNumber":1234,"name":"Lease.docx","size":10,"version":"1.1","fileVersion":"1.0",
         "reference":"1234.1.0","folder":{"id":5,"name":"Documents","kind":"folder"},"matter":null,
         "dateFiled":"2026-10-23T14:05:00Z","lastModified":"2026-10-23T14:05:00Z","hold":null,"canEdit":true}
        """;

    private readonly FakeHandler _handler = new();
    private readonly string _dir = Directory.CreateTempSubdirectory("healdan-api-").FullName;

    public void Dispose() => Directory.Delete(_dir, true);

    private HealdanApi Api(string token = "hd_tok") =>
        new(new Uri("https://healdan.example"), _handler) { Token = token };

    [Fact]
    public async Task SendsBearerToken()
    {
        _handler.Enqueue(HttpStatusCode.OK, DocJson);
        var doc = await Api().DocumentAsync(1234);

        Assert.Equal("Bearer hd_tok", _handler.Requests[0].Authorization);
        Assert.Equal("https://healdan.example/api/v1/documents/1234", _handler.Requests[0].Uri.ToString());
        Assert.Equal("1.1", doc.Version);
        Assert.Equal("1.0", doc.FileVersion);
        Assert.Equal("folder", doc.Folder.Kind);
    }

    [Fact]
    public async Task SendsNoAuthorizationWithoutAToken()
    {
        _handler.Enqueue(HttpStatusCode.OK,
            """{"product":"Healdan","version":"9.3.1","apiVersion":"v1","labels":{"matter":"Matter","matters":"Matters"},"signIn":["password"]}""");
        var info = await new HealdanApi(new Uri("https://healdan.example"), _handler).ServerAsync();

        Assert.Null(_handler.Requests[0].Authorization);
        Assert.Equal("Healdan", info.Product);
    }

    [Fact]
    public async Task SignInSendsTheDeviceAndKeepsTheToken()
    {
        _handler.Enqueue(HttpStatusCode.Created,
            """{"token":"hd_abc","id":1,"name":"Mac","expiresAt":"2027-01-01T00:00:00Z","user":{"id":2,"username":"u","name":"U","email":null,"language":"en","admin":false}}""");
        var api = new HealdanApi(new Uri("https://healdan.example"), _handler);
        var token = await api.SignInAsync("u", "pw", "Healdan Helper on Mac", "dev-1");

        Assert.Equal("hd_abc", token);
        Assert.Equal("hd_abc", api.Token);
        var body = JsonNode.Parse(_handler.Requests[0].BodyText)!;
        Assert.Equal("u", (string?)body["username"]);
        Assert.Equal("Healdan Helper on Mac", (string?)body["name"]);
        Assert.Equal("dev-1", (string?)body["device"]!["id"]);
    }

    [Fact]
    public async Task SignOutDeletesTheCurrentToken()
    {
        _handler.Enqueue(HttpStatusCode.NoContent, "");
        await Api().SignOutAsync();

        Assert.Equal(HttpMethod.Delete, _handler.Requests[0].Method);
        Assert.EndsWith("/api/v1/auth/tokens/current", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task CheckOutSendsAppAndMove()
    {
        _handler.Enqueue(HttpStatusCode.OK, DocJson);
        await Api().CheckOutAsync(1234, "Word", move: true);

        var body = JsonNode.Parse(_handler.Requests[0].BodyText)!;
        Assert.Equal("Word", (string?)body["app"]);
        Assert.True((bool?)body["move"]);
        Assert.EndsWith("/documents/1234/checkout", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task ReleaseDeletesTheCheckOut()
    {
        _handler.Enqueue(HttpStatusCode.OK, DocJson);
        await Api().ReleaseAsync(1234);

        Assert.Equal(HttpMethod.Delete, _handler.Requests[0].Method);
        Assert.EndsWith("/documents/1234/checkout", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task DownloadWritesTheFileAndAsksForTheVersion()
    {
        _handler.EnqueueBytes([1, 2, 3, 4]);
        var path = Path.Combine(_dir, "Lease.docx");
        await Api().DownloadAsync(1234, path, "1.0");

        Assert.Equal([1, 2, 3, 4], await File.ReadAllBytesAsync(path));
        Assert.Equal("?version=1.0", _handler.Requests[0].Uri.Query);
        Assert.EndsWith("/documents/1234/content", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task ADownloadThatBreaksOffLeavesNoFile()
    {
        _handler.EnqueueResponse(() => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StreamContent(new BreakingStream([1, 2, 3, 4]))
        });
        var path = Path.Combine(_dir, "Lease.docx");

        var ex = await Assert.ThrowsAnyAsync<Exception>(() => Api().DownloadAsync(1234, path));

        Assert.True(ex is IOException or HttpRequestException, ex.ToString());
        Assert.Empty(Directory.GetFileSystemEntries(_dir));
    }

    [Fact]
    public async Task ADownloadReplacesTheFileWhole()
    {
        var path = Path.Combine(_dir, "Lease.docx");
        await File.WriteAllBytesAsync(path, [9, 9, 9, 9, 9, 9]);
        _handler.EnqueueBytes([1, 2]);

        await Api().DownloadAsync(1234, path);

        Assert.Equal([1, 2], await File.ReadAllBytesAsync(path));
        Assert.Single(Directory.GetFileSystemEntries(_dir));
    }

    /// <summary>Gives its bytes, then fails as a dropped connection does.</summary>
    private sealed class BreakingStream(byte[] first) : MemoryStream(first)
    {
        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = base.Read(buffer, offset, count);
            return n > 0 ? n : throw new IOException("The connection broke off.");
        }

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            var n = await base.ReadAsync(buffer, cancellationToken);
            return n > 0 ? n : throw new IOException("The connection broke off.");
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
            ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();
    }

    [Fact]
    public async Task SaveSendsMultipartWithNameAndPreconditions()
    {
        _handler.Enqueue(HttpStatusCode.Created, DocJson);
        var path = Path.Combine(_dir, "Lease.docx");
        await File.WriteAllBytesAsync(path, "file-content"u8.ToArray());
        var saveId = Guid.NewGuid().ToString();

        await Api().SaveAsync(1234, path, new SaveRequest("snapshot", true, "Lease.docx", "1.1", true, saveId));

        var seen = _handler.Requests[0];
        Assert.Equal(HttpMethod.Post, seen.Method);
        Assert.EndsWith("/documents/1234/versions", seen.Uri.AbsolutePath);
        Assert.StartsWith("multipart/form-data", seen.ContentType);
        var text = seen.BodyText;
        Assert.Contains("name=version", text);
        Assert.Contains("name=file", text);
        Assert.Contains("file-content", text);
        var json = text[(text.IndexOf('{'))..(text.IndexOf('}') + 1)];
        var part = JsonNode.Parse(json)!;
        Assert.Equal("snapshot", (string?)part["kind"]);
        Assert.True((bool?)part["keepCheckedOut"]);
        Assert.Equal("Lease.docx", (string?)part["name"]);
        Assert.Equal("1.1", (string?)part["ifVersion"]);
        Assert.True((bool?)part["ifHeld"]);
        Assert.Equal(saveId, (string?)part["saveId"]);
    }

    [Fact]
    public async Task FileSendsTheDocumentAndFilePart()
    {
        _handler.Enqueue(HttpStatusCode.Created, DocJson);
        var path = Path.Combine(_dir, "New.docx");
        await File.WriteAllBytesAsync(path, "bytes"u8.ToArray());

        await Api().FileAsync(path, new NewDocument(5, "New.docx", new JsonObject { ["docclass"] = "Letters" }, true));

        var text = _handler.Requests[0].BodyText;
        Assert.EndsWith("/api/v1/documents", _handler.Requests[0].Uri.AbsolutePath);
        Assert.Contains("name=document", text);
        Assert.Contains("name=file", text);
        Assert.Contains("\"folderId\":5", text);
        Assert.Contains("\"checkOut\":true", text);
        Assert.Contains("\"docclass\":\"Letters\"", text);
    }

    [Fact]
    public async Task PatchPropertiesSendsValues()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"items":[],"offset":0,"limit":50,"total":0}""");
        using var values = JsonDocument.Parse("""{"docclass":"Letters"}""");
        var answer = await Api().PatchPropertiesAsync(1234, values.RootElement);

        Assert.Equal(HttpMethod.Patch, _handler.Requests[0].Method);
        Assert.Equal("""{"values":{"docclass":"Letters"}}""", _handler.Requests[0].BodyText);
        Assert.Equal(0, answer.GetProperty("total").GetInt32());
    }

    [Fact]
    public async Task GetPageReadsTheItems()
    {
        _handler.Enqueue(HttpStatusCode.OK,
            """{"items":[{"id":1,"name":"Matters","kind":"store"}],"offset":0,"limit":50,"total":null}""");
        var page = await Api().GetPageAsync<FolderRef>("v1/me/recent-folders");

        Assert.Single(page.Items);
        Assert.Equal("store", page.Items[0].Kind);
        Assert.Null(page.Total);
        Assert.EndsWith("/api/v1/me/recent-folders", _handler.Requests[0].Uri.AbsolutePath);
    }

    [Fact]
    public async Task ProblemBecomesApiException()
    {
        _handler.Enqueue(HttpStatusCode.Conflict,
            """{"type":"https://healdan.com/problems/held-elsewhere","title":"Held elsewhere","status":409,"detail":"Checked out on your other computer.","code":"held-elsewhere","lastSaved":null}""",
            "application/problem+json");

        var ex = await Assert.ThrowsAsync<ApiException>(() => Api().ReleaseAsync(1234));

        Assert.Equal(409, ex.Status);
        Assert.Equal("held-elsewhere", ex.Code);
        Assert.Equal("Checked out on your other computer.", ex.Detail);
        Assert.Equal("held-elsewhere", ex.Body.GetProperty("code").GetString());
    }

    [Fact]
    public async Task AnAnswerThatIsNotAProblemStillThrows()
    {
        _handler.Enqueue(HttpStatusCode.BadGateway, "<html>bad gateway</html>", "text/html");

        var ex = await Assert.ThrowsAsync<ApiException>(() => Api().DocumentAsync(1));

        Assert.Equal(502, ex.Status);
        Assert.Equal("http-502", ex.Code);
    }

    [Fact]
    public void RefusesPlainHttp()
    {
        Assert.Throws<ArgumentException>(() => HealdanApi.RequireHttps(new Uri("http://healdan.example")));
        Assert.Throws<ArgumentException>(() => new HealdanApi(new Uri("http://healdan.example"), _handler));
        HealdanApi.RequireHttps(new Uri("http://localhost:8093"));
        HealdanApi.RequireHttps(new Uri("http://127.0.0.1:8093"));
        HealdanApi.RequireHttps(new Uri("http://[::1]:8093"));
        HealdanApi.RequireHttps(new Uri("https://healdan.example"));
        _ = new HealdanApi(new Uri("http://localhost:8093"), _handler);
    }
}
