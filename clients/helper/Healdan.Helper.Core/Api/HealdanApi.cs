using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Healdan.Helper.Core.Api;

/// <summary>The helper's client for Healdan's JSON API (<c>/api/v1</c>). Calls are signed with <see cref="Token"/>.</summary>
public sealed class HealdanApi
{
    /// <summary>The API's JSON: camelCase, nulls left out of requests. Also reads problems' members (<see cref="Problem"/>).</summary>
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    private readonly HttpClient _http;

    /// <summary>Plain HTTP only to this computer, so development stacks work; certificates are always checked.</summary>
    public HealdanApi(Uri server, HttpMessageHandler? handler = null)
    {
        RequireHttps(server);
        Server = server;
        _http = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: false);
        _http.BaseAddress = new Uri(server.GetLeftPart(UriPartial.Path).TrimEnd('/') + "/api/");
        _http.Timeout = TimeSpan.FromMinutes(10);
    }

    /// <summary>The server's address as given.</summary>
    public Uri Server { get; }

    /// <summary>The device token. Never logged.</summary>
    public string? Token { get; set; }

    /// <summary>Spec §3.3: refuses <c>http</c> unless the host is this computer.</summary>
    public static void RequireHttps(Uri server)
    {
        if (server.Scheme == Uri.UriSchemeHttps) return;
        if (server.Scheme == Uri.UriSchemeHttp && server.Host is "localhost" or "127.0.0.1" or "::1" or "[::1]") return;
        throw new ArgumentException("The server's address must be https (http is allowed only for localhost).", nameof(server));
    }

    public Task<ServerInfo> ServerAsync(CancellationToken ct = default) =>
        SendJsonAsync<ServerInfo>(HttpMethod.Get, "v1/server", null, ct);

    /// <summary>Signs in, keeps the token in <see cref="Token"/> and returns it.</summary>
    public async Task<string> SignInAsync(string user, string password, string deviceName, string deviceId, CancellationToken ct = default)
    {
        Token = null;
        var body = new SignInBody(user, password, deviceName, new DeviceRef(deviceId));
        var signedIn = await SendJsonAsync<SignedIn>(HttpMethod.Post, "v1/auth/tokens", JsonContent.Create(body, options: Json), ct);
        Token = signedIn.Token;
        return signedIn.Token;
    }

    public async Task SignOutAsync(CancellationToken ct = default)
    {
        using var response = await SendAsync(HttpMethod.Delete, "v1/auth/tokens/current", null, ct);
        Token = null;
    }

    public Task<DocumentView> DocumentAsync(long n, CancellationToken ct = default) =>
        SendJsonAsync<DocumentView>(HttpMethod.Get, $"v1/documents/{n}", null, ct);

    public Task<DocumentView> CheckOutAsync(long n, string app, bool move = false, CancellationToken ct = default) =>
        SendJsonAsync<DocumentView>(HttpMethod.Post, $"v1/documents/{n}/checkout",
            JsonContent.Create(new CheckoutBody(move, app), options: Json), ct);

    public Task<DocumentView> ReleaseAsync(long n, CancellationToken ct = default) =>
        SendJsonAsync<DocumentView>(HttpMethod.Delete, $"v1/documents/{n}/checkout", null, ct);

    /// <summary>
    /// Downloads the current file, or that of <paramref name="version"/>, to <paramref name="path"/>. It's written to a
    /// hidden temporary file beside it first and moved into place whole, so a download that breaks off never leaves a
    /// partial file at <paramref name="path"/>, nor replaces one that was there.
    /// </summary>
    public async Task DownloadAsync(long n, string path, string? version = null, CancellationToken ct = default)
    {
        var url = $"v1/documents/{n}/content" + (version is null ? "" : "?version=" + Uri.EscapeDataString(version));
        using var response = await SendAsync(HttpMethod.Get, url, null, ct);
        var temp = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(path))!, $".healdan-{Guid.NewGuid():N}.part");
        try
        {
            await using (var file = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
                await response.Content.CopyToAsync(file, ct);
            File.Move(temp, path, overwrite: true);
        }
        finally
        {
            File.Delete(temp); // nothing left once it has been moved
        }
    }

    /// <summary>Saves the file at <paramref name="path"/>. It's read into memory first, as the app may write it meanwhile.</summary>
    public async Task<DocumentView> SaveAsync(long n, string path, SaveRequest request, CancellationToken ct = default) =>
        await SaveAsync(n, await File.ReadAllBytesAsync(path, ct), Path.GetFileName(path), request, ct);

    /// <summary>Saves <paramref name="content"/>, so the caller knows exactly which bytes it sent (and can send them again).</summary>
    public async Task<DocumentView> SaveAsync(long n, byte[] content, string fileName, SaveRequest request, CancellationToken ct = default)
    {
        using var form = Multipart("version", request, content, fileName);
        return await SendJsonAsync<DocumentView>(HttpMethod.Post, $"v1/documents/{n}/versions", form, ct);
    }

    /// <summary>Files the file at <paramref name="path"/> as a new document.</summary>
    public async Task<DocumentView> FileAsync(string path, NewDocument request, CancellationToken ct = default)
    {
        var bytes = await File.ReadAllBytesAsync(path, ct);
        using var form = Multipart("document", request, bytes, Path.GetFileName(path));
        return await SendJsonAsync<DocumentView>(HttpMethod.Post, "v1/documents", form, ct);
    }

    public Task<Page<T>> GetPageAsync<T>(string relativePath, CancellationToken ct = default) =>
        SendJsonAsync<Page<T>>(HttpMethod.Get, relativePath, null, ct);

    public Task<JsonElement> GetJsonAsync(string relativePath, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Get, relativePath, null, ct);

    public Task<JsonElement> PatchPropertiesAsync(long n, JsonElement values, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Patch, $"v1/documents/{n}/properties",
            JsonContent.Create(new PropertyPatchBody(values), options: Json), ct);

    // --- The windows' calls (spec §5.8) -----------------------------------------------------------------------------

    public Task<UserView> MeAsync(CancellationToken ct = default) =>
        SendJsonAsync<UserView>(HttpMethod.Get, "v1/me", null, ct);

    /// <summary>
    /// A list of folders, documents and matters: one of the left panes' places (such as <c>v1/matters/recent</c>), a
    /// search, or a folder's children. Items of a type the helper doesn't show are left out.
    /// </summary>
    public async Task<Page<ListItem>> ListAsync(string relativePath, CancellationToken ct = default)
    {
        var page = await GetPageAsync<JsonElement>(relativePath, ct);
        return new Page<ListItem>([.. page.Items.Select(ListItem.From).OfType<ListItem>()], page.Offset, page.Limit, page.Total);
    }

    public Task<FolderView> FolderAsync(long id, CancellationToken ct = default) =>
        SendJsonAsync<FolderView>(HttpMethod.Get, $"v1/folders/{id}", null, ct);

    /// <summary>The folder's subfolders, then its documents (a search folder's documents), up to <paramref name="limit"/>.</summary>
    public Task<Page<ListItem>> ChildrenAsync(long id, int limit, CancellationToken ct = default) =>
        ListAsync($"v1/folders/{id}/children?limit={limit}", ct);

    /// <summary>
    /// The old web page's searches (<c>documents</c>, <c>folders</c>, <c>matters</c>, <c>clients</c>, ...);
    /// <paramref name="within"/> is a folder to search inside, for <c>folders</c> and <c>documents</c> only.
    /// </summary>
    public Task<Page<ListItem>> SearchAsync(string scope, string q, long? within, int limit, CancellationToken ct = default) =>
        ListAsync($"v1/search?scope={Uri.EscapeDataString(scope)}&q={Uri.EscapeDataString(q)}"
            + (within is { } w ? $"&within={w}" : "") + $"&limit={limit}", ct);

    /// <summary>Resolves a typed doc #, such as <c>#1234 v2.1</c>, to the document and version.</summary>
    public Task<LookupView> LookupAsync(string q, CancellationToken ct = default) =>
        SendJsonAsync<LookupView>(HttpMethod.Get, $"v1/lookup?q={Uri.EscapeDataString(q)}", null, ct);

    public Task<FolderView> CreateFolderAsync(long parentId, string name, CancellationToken ct = default) =>
        SendJsonAsync<FolderView>(HttpMethod.Post, "v1/folders", JsonContent.Create(new NewFolderBody(parentId, name), options: Json), ct);

    /// <summary>Adds a folder or a document (exactly one of the two) to the user's Favorites.</summary>
    public Task<FavoriteView> AddFavoriteAsync(long? folderId, long? docNumber, CancellationToken ct = default) =>
        SendJsonAsync<FavoriteView>(HttpMethod.Post, "v1/me/favorites",
            JsonContent.Create(new NewFavoriteBody(folderId, docNumber), options: Json), ct);

    public async Task RemoveFavoriteAsync(long id, CancellationToken ct = default)
    {
        using var response = await SendAsync(HttpMethod.Delete, $"v1/me/favorites/{id}", null, ct);
    }

    /// <summary>The properties form for filing a new document into the folder (a <c>ProfileFieldPage</c>).</summary>
    public Task<JsonElement> FormAsync(long folderId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Get, $"v1/folders/{folderId}/form", null, ct);

    /// <summary>The document's properties (a <c>ProfileFieldPage</c>).</summary>
    public Task<JsonElement> PropertiesAsync(long n, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Get, $"v1/documents/{n}/properties", null, ct);

    /// <summary>The document's timeline, newest first, up to <paramref name="limit"/> rows.</summary>
    public Task<Page<VersionView>> VersionsAsync(long n, int limit, CancellationToken ct = default) =>
        GetPageAsync<VersionView>($"v1/documents/{n}/versions?limit={limit}", ct);

    /// <summary>The thumbnail's PNG, or null when there's none yet or the user may not download the document.</summary>
    public async Task<byte[]?> ThumbnailAsync(long n, CancellationToken ct = default)
    {
        try
        {
            using var response = await SendAsync(HttpMethod.Get, $"v1/documents/{n}/thumbnail", null, ct);
            return await response.Content.ReadAsByteArrayAsync(ct);
        }
        catch (ApiException e) when (e.Status is 404 or 403)
        {
            return null;
        }
    }

    /// <summary>People for a user field such as Author, by name or username.</summary>
    public Task<Page<UserHit>> FindUsersAsync(string q, CancellationToken ct = default) =>
        GetPageAsync<UserHit>($"v1/users?q={Uri.EscapeDataString(q)}", ct);

    private static MultipartFormDataContent Multipart<T>(string jsonPart, T json, byte[] file, string fileName)
    {
        var form = new MultipartFormDataContent();
        var part = new StringContent(JsonSerializer.Serialize(json, Json));
        part.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        form.Add(part, jsonPart);
        var content = new ByteArrayContent(file);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(content, "file", fileName);
        return form;
    }

    private async Task<T> SendJsonAsync<T>(HttpMethod method, string path, HttpContent? content, CancellationToken ct)
    {
        using var response = await SendAsync(method, path, content, ct);
        var value = await response.Content.ReadFromJsonAsync<T>(Json, ct);
        return value ?? throw new ApiException((int)response.StatusCode, "empty-answer", "The server sent an empty answer.", default);
    }

    /// <summary>Sends the request, and turns a refusal into an <see cref="ApiException"/>.</summary>
    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, HttpContent? content, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, path) { Content = content };
        if (Token is not null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        if (response.IsSuccessStatusCode) return response;
        try
        {
            throw await ToExceptionAsync(response, ct);
        }
        finally
        {
            response.Dispose();
        }
    }

    private static async Task<ApiException> ToExceptionAsync(HttpResponseMessage response, CancellationToken ct)
    {
        var status = (int)response.StatusCode;
        try
        {
            var body = await response.Content.ReadFromJsonAsync<JsonElement>(Json, ct);
            if (body.ValueKind == JsonValueKind.Object && body.TryGetProperty("code", out var code) && code.ValueKind == JsonValueKind.String)
            {
                var detail = body.TryGetProperty("detail", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString()! : "";
                return new ApiException(status, code.GetString()!, detail, body.Clone());
            }
        }
        catch (JsonException)
        {
            // Not a problem answer, such as a proxy's error page.
        }
        return new ApiException(status, $"http-{status}", response.ReasonPhrase ?? "The server refused the request.", default);
    }
}
