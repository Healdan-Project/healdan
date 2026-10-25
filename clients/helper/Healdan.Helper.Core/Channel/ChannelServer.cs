using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Channel;

/// <summary>
/// The local HTTP channel the add-ins call (spec §10). It listens on <c>127.0.0.1</c> only, at a port picked at start, and
/// every request but <c>GET /v1/ping</c> must carry the secret from <c>channel.json</c>, so other users of the computer
/// can't drive the helper. It does transport only: the routes' meaning is the <see cref="IChannelHandler"/>'s.
/// </summary>
public sealed class ChannelServer(HelperPaths paths, IChannelHandler handler) : IDisposable
{
    /// <summary>The extension sends small JSON; anything bigger is refused.</summary>
    private const int MaxBodyBytes = 64 * 1024;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private HttpListener? listener;
    private ChannelInfo? info;
    private byte[] secretBytes = [];

    public void Start()
    {
        if (listener is not null)
            throw new InvalidOperationException("The channel is already started.");

        var secret = Convert.ToHexString(RandomNumberGenerator.GetBytes(24)).ToLowerInvariant();
        secretBytes = Encoding.UTF8.GetBytes(secret);

        // HttpListener can't bind port 0, so learn a free port and retry if someone takes it before we do.
        for (var attempt = 1; ; attempt++)
        {
            var port = FreePort();
            var candidate = new HttpListener();
            candidate.Prefixes.Add($"http://127.0.0.1:{port}/");
            try
            {
                candidate.Start();
                listener = candidate;
                info = new ChannelInfo(port, secret, Environment.ProcessId);
                break;
            }
            catch (HttpListenerException) when (attempt < 5)
            {
                candidate.Close();
            }
        }

        try
        {
            ChannelInfo.Write(paths, info!);
        }
        catch
        {
            listener.Close();
            listener = null;
            throw;
        }
        _ = Task.Run(AcceptLoopAsync);
    }

    /// <summary>Stops listening and removes <c>channel.json</c> if it is still ours (another helper may have replaced it).</summary>
    public void Stop()
    {
        var current = listener;
        listener = null;
        if (current is null)
            return;
        try
        {
            current.Close();
        }
        catch (ObjectDisposedException)
        {
        }

        var ours = info;
        var onDisk = ChannelInfo.Read(paths);
        if (ours is not null && onDisk is not null && onDisk.Pid == ours.Pid && onDisk.Port == ours.Port && onDisk.Secret == ours.Secret)
            File.Delete(paths.Channel);
    }

    public void Dispose() => Stop();

    private static int FreePort()
    {
        var probe = new TcpListener(IPAddress.Loopback, 0);
        probe.Start();
        try
        {
            return ((IPEndPoint)probe.LocalEndpoint).Port;
        }
        finally
        {
            probe.Stop();
        }
    }

    private async Task AcceptLoopAsync()
    {
        var current = listener;
        while (current is { IsListening: true })
        {
            HttpListenerContext context;
            try
            {
                context = await current.GetContextAsync();
            }
            catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or InvalidOperationException)
            {
                return;
            }
            _ = Task.Run(() => ServeAsync(context));
        }
    }

    private async Task ServeAsync(HttpListenerContext context)
    {
        try
        {
            var (status, answer) = await RouteAsync(context.Request);
            await Respond(context, status, answer);
        }
        catch
        {
            // The connection is gone or the answer can't be sent; the server stays up and nothing is worth telling the caller.
            try
            {
                context.Response.Abort();
            }
            catch (ObjectDisposedException)
            {
            }
        }
    }

    private async Task<(int Status, JsonNode Answer)> RouteAsync(HttpListenerRequest request)
    {
        var path = request.Url?.AbsolutePath ?? "";
        if (!path.StartsWith("/v1/", StringComparison.Ordinal) || path.Length == 4 || path.IndexOf('/', 4) >= 0)
            return (404, Error("not-found"));
        var route = path[4..];

        if (route == "ping")
        {
            return request.HttpMethod == "GET" ? (200, new JsonObject { ["ok"] = true }) : (405, Error("method-not-allowed"));
        }

        if (!SecretMatches(request.Headers[ChannelInfo.SecretHeader]))
            return (403, Error("forbidden"));
        if (!IsRoute(route))
            return (404, Error("not-found"));
        if (request.HttpMethod != "POST")
            return (405, Error("method-not-allowed"));

        var (body, failure) = await ReadBodyAsync(request);
        if (body is null)
            return (failure, Error(failure == 413 ? "too-large" : "bad-request"));

        try
        {
            if (route == "link")
            {
                // The open waits on a download; the caller needs its answer within 3 s (see IChannelHandler).
                _ = Task.Run(async () =>
                {
                    try
                    {
                        await handler.HandleAsync(route, body);
                    }
                    catch
                    {
                        // Nobody is waiting for it.
                    }
                });
                return (200, new JsonObject());
            }
            return (200, await handler.HandleAsync(route, body) ?? new JsonObject());
        }
        catch
        {
            return (500, Error("internal-error"));
        }
    }

    private static readonly HashSet<string> Routes =
    [
        "link", "open-dialog", "save-new", "file", "saved", "new-version", "properties", "closed", "state",
    ];

    private static bool IsRoute(string route) => Routes.Contains(route);

    private bool SecretMatches(string? given)
    {
        if (string.IsNullOrEmpty(given))
            return false;
        // Equal lengths are public (always 48), so the early exit leaks nothing.
        return CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(given), secretBytes);
    }

    private static async Task<(JsonObject? Body, int Failure)> ReadBodyAsync(HttpListenerRequest request)
    {
        if (request.ContentLength64 > MaxBodyBytes)
            return (null, 413);

        using var buffer = new MemoryStream();
        var chunk = new byte[4096];
        int read;
        while ((read = await request.InputStream.ReadAsync(chunk)) > 0)
        {
            buffer.Write(chunk, 0, read);
            if (buffer.Length > MaxBodyBytes)
                return (null, 413);
        }

        try
        {
            return (JsonNode.Parse(buffer.ToArray()) as JsonObject, 400);
        }
        catch (JsonException)
        {
            return (null, 400);
        }
    }

    private static JsonObject Error(string code) => new() { ["error"] = code };

    private static async Task Respond(HttpListenerContext context, int status, JsonNode answer)
    {
        var bytes = Encoding.UTF8.GetBytes(answer.ToJsonString(Json));
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json; charset=utf-8";
        context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes);
        context.Response.Close();
    }
}
