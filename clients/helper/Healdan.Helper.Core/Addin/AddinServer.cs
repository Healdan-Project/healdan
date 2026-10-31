using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Addin;

/// <summary>
/// The add-in's port (M3.5 spec §5.8, §9): a second listener beside the extension's channel, which Word, Excel and
/// PowerPoint's Healdan add-in calls from its pages on the helper's own server. Office's web view blocks a secure page's
/// requests to <c>http://127.0.0.1</c>, so it's HTTPS, with the helper's own certificate, on <c>127.0.0.1</c> only, at the
/// first of <see cref="AddinPorts.All"/> it can take.
/// <para>HTTP/1.1, one request per connection (<c>Connection: close</c>): a request line, headers to an empty line, and a
/// <c>Content-Length</c> body. Anything else is <c>400 bad-request</c>. Before any route runs, a request must pass, in
/// this order: <c>Host</c> is this port at <c>127.0.0.1</c> or <c>localhost</c> (no DNS rebinding); <c>Origin</c> is the
/// helper's server's (no other page, and no other Healdan's), and only then does an answer carry CORS headers, a
/// preflight's included; <c>X-Healdan-Protocol</c> is one it speaks; <c>X-Healdan-Key</c> is a paired key
/// (<see cref="Pairing.AppFor"/>), but for <c>hello</c> and pairing itself; and the helper is signed in, but for
/// <c>ping</c>, which says whether it is. Review Focus 5: another user of the Mac has no key, and a web page has neither
/// the origin nor the key.</para>
/// <para>It does transport and those checks; the routes are <see cref="AddinHandler"/>'s. Its log names routes and
/// refusals, never a header's value, a key, a code or a body.</para>
/// <para>It runs for the helper's lifetime, signed in or not, so the add-in's pane can say which (spec §6.3). Nothing from
/// outside stops it: a connection that fails before it's taken, or the process running out of file descriptors for a
/// moment, is logged once and the port goes on listening. When Set Up renews the certificate, the running port serves the
/// new one from the next connection on (<see cref="UseCertificate"/>), on the same port.</para>
/// </summary>
/// <param name="server">The helper's server, as <c>settings.json</c> holds it, whose pages alone it answers; null answers none.</param>
/// <param name="signedIn">Whether the helper holds a token Healdan hasn't refused.</param>
/// <param name="certificate">
/// The helper's certificate, with its key (<see cref="AddinCertificate.ForServer"/>), which <see cref="Dispose"/> disposes.
/// </param>
/// <param name="ports">The ports to try, in order: <see cref="AddinPorts.All"/> by default.</param>
public sealed class AddinServer(AddinHandler handler, Func<Uri?> server, Func<bool> signedIn, X509Certificate2 certificate, HelperLog log,
    IReadOnlyList<int>? ports = null) : IDisposable
{
    /// <summary>The largest body taken: Save to Healdan's document, in base64 (plan: the add-in's port).</summary>
    public const long MaxBodyBytes = 150L * 1024 * 1024;

    /// <summary>The largest body any other route takes, so one without a key can't make the helper hold 150 MiB.</summary>
    private const long SmallBodyBytes = 1024 * 1024;

    /// <summary>The request line and headers together.</summary>
    private const int MaxHeadBytes = 16 * 1024;

    /// <summary>How long a client has to finish TLS and send its whole request; a route then takes as long as its window.</summary>
    private static readonly TimeSpan ReadTimeout = TimeSpan.FromSeconds(60);

    /// <summary>How long the server reads on after answering, so the client gets the answer rather than a reset.</summary>
    private static readonly TimeSpan Linger = TimeSpan.FromSeconds(2);

    public const string ProtocolHeader = "X-Healdan-Protocol";

    public const string KeyHeader = "X-Healdan-Key";

    /// <summary>The protocols this helper speaks; an add-in that sends another is told to update the helper.</summary>
    public static IReadOnlyList<int> Protocols { get; } = [1];

    /// <summary>The routes that need no key: the add-in pairs through them.</summary>
    private static readonly HashSet<string> PairingRoutes = ["pair/start", "pair/finish"];

    /// <summary>The routes that need a paired key. They're the add-in's own, apart from the extension's channel's.</summary>
    private static readonly HashSet<string> KeyedRoutes = ["ping", "open-dialog", "save-new", "file", "new-version", "properties", "state"];

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly object _gate = new();

    /// <summary>The certificates it served before Set Up renewed them, kept until it's disposed, as a handshake may still be using one.</summary>
    private readonly List<X509Certificate2> _retired = [];

    private X509Certificate2 _certificate = certificate;
    private TcpListener? _listener;
    private CancellationTokenSource? _stop;
    private int? _port;

    /// <summary>The port it took, or null before it has started, when every port was taken, and once stopped.</summary>
    public int? Port
    {
        get
        {
            lock (_gate)
                return _port;
        }
    }

    /// <summary>The certificate it serves now.</summary>
    public string Thumbprint
    {
        get
        {
            lock (_gate)
                return _certificate.Thumbprint;
        }
    }

    /// <summary>
    /// Serves <paramref name="renewed"/> from the next connection on, on the same port (Set Up renewed the certificate).
    /// The server disposes it, with the one it replaces, when it's disposed.
    /// </summary>
    public void UseCertificate(X509Certificate2 renewed)
    {
        lock (_gate)
        {
            _retired.Add(_certificate);
            _certificate = renewed;
        }
        log.Info("The Office add-in's port serves the certificate Set Up Microsoft Office renewed");
    }

    /// <summary>Takes the next connection: the listener's own accept, unless a test stands in for it to make it fail.</summary>
    internal Func<TcpListener, CancellationToken, ValueTask<TcpClient>> AcceptClient { get; init; } =
        static (listener, ct) => listener.AcceptTcpClientAsync(ct);

    /// <summary>How long it waits before accepting again once the process has run out of file descriptors.</summary>
    internal TimeSpan DescriptorPause { get; init; } = TimeSpan.FromSeconds(1);

    /// <summary>Listens on the first of the ports it can take. False when every one is taken, by another user's helper, say.</summary>
    public bool Start()
    {
        lock (_gate)
        {
            if (_listener is not null)
                throw new InvalidOperationException("The add-in's port is already started.");
            foreach (var port in ports ?? AddinPorts.All)
            {
                var candidate = new TcpListener(IPAddress.Loopback, port);
                try
                {
                    candidate.Start();
                }
                catch (SocketException)
                {
                    candidate.Stop();
                    continue;
                }
                var stop = new CancellationTokenSource();
                var stopping = stop.Token;
                (_listener, _stop, _port) = (candidate, stop, port);
                log.Info($"The Office add-in's port is open on 127.0.0.1:{port}");
                _ = Task.Run(() => AcceptLoopAsync(candidate, port, stopping));
                return true;
            }
            log.Warn($"The Office add-in's port couldn't open: ports {string.Join(", ", ports ?? AddinPorts.All)} are all taken");
            return false;
        }
    }

    /// <summary>Stops listening. Requests already in hand finish.</summary>
    public void Stop()
    {
        TcpListener? listener;
        CancellationTokenSource? stop;
        lock (_gate)
        {
            (listener, stop, _listener, _stop, _port) = (_listener, _stop, null, null, null);
        }
        stop?.Cancel();
        listener?.Stop();
        stop?.Dispose();
    }

    /// <summary>Stops listening, and lets the certificates go.</summary>
    public void Dispose()
    {
        Stop();
        lock (_gate)
        {
            foreach (var old in _retired)
                old.Dispose();
            _retired.Clear();
            _certificate.Dispose();
        }
    }

    /// <summary>
    /// Takes connections until <see cref="Stop"/>. A failed accept never ends it, so nobody can close the port from outside:
    /// a connection reset before it was taken, or the process out of file descriptors (after which it waits
    /// <see cref="DescriptorPause"/>), is logged once for each kind and the port goes on listening, as Kestrel's does.
    /// </summary>
    private async Task AcceptLoopAsync(TcpListener listener, int port, CancellationToken stop)
    {
        var said = new HashSet<SocketError>();
        while (!stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await AcceptClient(listener, stop);
            }
            catch (Exception e) when (stop.IsCancellationRequested || e is ObjectDisposedException or OperationCanceledException)
            {
                return; // stopped
            }
            catch (SocketException e)
            {
                if (said.Add(e.SocketErrorCode))
                    log.Warn($"The Office add-in's port couldn't take a connection ({e.SocketErrorCode}): it goes on listening");
                var pause = e.SocketErrorCode switch
                {
                    SocketError.ConnectionAborted or SocketError.ConnectionReset => TimeSpan.Zero,
                    SocketError.TooManyOpenSockets or SocketError.NoBufferSpaceAvailable => DescriptorPause,
                    _ => TimeSpan.FromMilliseconds(100), // something else, which may not pass at once: not a busy loop
                };
                try
                {
                    if (pause > TimeSpan.Zero)
                        await Task.Delay(pause, stop);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
                continue;
            }
            catch (Exception e)
            {
                log.Error("The Office add-in's port stopped listening", e);
                return;
            }
            _ = Task.Run(() => ServeAsync(client, port));
        }
    }

    /// <summary>One connection to <paramref name="port"/>: TLS, one request, its answer, then close.</summary>
    private async Task ServeAsync(TcpClient client, int port)
    {
        using (client)
        {
            try
            {
                await using var tls = new SslStream(client.GetStream(), leaveInnerStreamOpen: true);
                Answer answer;
                using (var reading = new CancellationTokenSource(ReadTimeout))
                {
                    X509Certificate2 serving;
                    lock (_gate)
                        serving = _certificate; // the one Set Up renewed, from the next connection on
                    await tls.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = serving,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                        ClientCertificateRequired = false,
                    }, reading.Token);
                    answer = await AnswerAsync(tls, port, reading.Token);
                }
                await tls.WriteAsync(answer.Bytes());
                await tls.FlushAsync();
                await LingerAsync(client, tls);
            }
            catch (Exception e) when (e is IOException or AuthenticationException or OperationCanceledException or ObjectDisposedException
                                           or SocketException or InvalidOperationException)
            {
                // The caller went away, never spoke TLS, or took too long: there's no one to answer.
            }
            catch (Exception e)
            {
                log.Error("A request to the Office add-in's port failed", e);
            }
        }
    }

    /// <summary>
    /// After the answer: TLS's close, then the connection's sending side, then whatever the client still sends is read and
    /// dropped for a moment, so a body it was sending (one refused unread) doesn't turn into a reset that loses the answer.
    /// </summary>
    private static async Task LingerAsync(TcpClient client, SslStream tls)
    {
        try
        {
            await tls.ShutdownAsync();
            client.Client.Shutdown(SocketShutdown.Send);
            using var linger = new CancellationTokenSource(Linger);
            var sink = new byte[16 * 1024];
            var stream = client.GetStream();
            while (await stream.ReadAsync(sink, linger.Token) > 0)
            {
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException or ObjectDisposedException)
        {
        }
    }

    /// <summary>Reads the request and decides its answer, checking it in the order the class describes.</summary>
    private async Task<Answer> AnswerAsync(SslStream tls, int port, CancellationToken reading)
    {
        var request = await Head.ReadAsync(tls, reading);
        if (request is null)
            return Answer.Error(400, "bad-request");

        // Host first: a page whose name was pointed at 127.0.0.1 (DNS rebinding) sends its own name.
        if (request.Host is not { } host
            || !(string.Equals(host, $"127.0.0.1:{port}", StringComparison.Ordinal)
                 || string.Equals(host, $"localhost:{port}", StringComparison.OrdinalIgnoreCase)))
            return Refused(403, "bad-host", "its Host isn't this port");

        // Then whose page it is: only the helper's server's, and only then does any answer carry CORS headers.
        var serverUri = server();
        var origin = request.Origin;
        if (serverUri is null || origin is null || !string.Equals(origin, ServerAddress.Origin(serverUri), StringComparison.OrdinalIgnoreCase))
            return Refused(403, "bad-origin", origin is null ? "it has no Origin" : "its Origin isn't the helper's server");

        var answer = await RouteAsync(request, tls, reading);
        return answer with { Origin = origin };
    }

    private async Task<Answer> RouteAsync(Head request, SslStream tls, CancellationToken reading)
    {
        // A preflight carries neither the protocol nor the key; its answer is the CORS headers themselves.
        if (request.Method == "OPTIONS")
            return new Answer(204, null);

        var route = request.Target.StartsWith("/v1/", StringComparison.Ordinal) ? request.Target[4..] : null;
        if (route == "hello")
        {
            return request.Method == "GET"
                ? new Answer(200, new JsonObject
                {
                    ["name"] = "Healdan Helper",
                    ["protocols"] = new JsonArray([.. Protocols.Select(p => (JsonNode)p)]),
                    ["server"] = server() is { } s ? ServerAddress.Origin(s) : null,
                })
                : Answer.Error(405, "method-not-allowed");
        }

        if (request.Protocol is not { } given || !int.TryParse(given, NumberStyles.None, CultureInfo.InvariantCulture, out var protocol))
            return Answer.Error(400, "bad-request");
        if (!Protocols.Contains(protocol))
        {
            log.Info($"The Office add-in speaks protocol {protocol}, which this helper doesn't: it's asked to update the helper");
            return Answer.Error(426, "update-healdan-helper");
        }

        string? app = null;
        if (route is null || !PairingRoutes.Contains(route))
        {
            // Every other route, known or not, needs a key, so a caller without one learns nothing of which there are.
            app = request.Key is { } key ? handler.Pairing.AppFor(key) : null;
            if (app is null)
                return Refused(403, "not-paired", request.Key is null ? "it has no key" : "its key isn't one the helper gave");
            if (route is null || !KeyedRoutes.Contains(route))
                return Answer.Error(404, "not-found");
        }
        if (request.Method != "POST")
            return Answer.Error(405, "method-not-allowed");

        if (route == "ping")
            return new Answer(200, new JsonObject { ["ok"] = true, ["signedIn"] = signedIn() });
        if (app is not null && !signedIn())
            return Answer.Error(409, "signed-out");

        if (request.ContentLength > (route == "file" ? MaxBodyBytes : SmallBodyBytes))
            return Answer.Error(413, "too-large");
        var body = await request.ReadBodyAsync(tls, reading);
        if (body is null)
            return Answer.Error(400, "bad-request");

        var answer = await handler.HandleAsync(route, app, body);
        return new Answer(answer.Status, answer.Body);
    }

    /// <summary>A refusal before any route ran, logged without the request's values.</summary>
    private Answer Refused(int status, string code, string why)
    {
        log.Warn($"Refused a request to the Office add-in's port ({code}): {why}");
        return Answer.Error(status, code);
    }

    /// <summary>
    /// An answer, with the CORS headers once <see cref="Origin"/> is set (the request's own, after it was checked). A
    /// <c>204</c> has no body.
    /// </summary>
    private sealed record Answer(int Status, JsonObject? Body, string? Origin = null)
    {
        public static Answer Error(int status, string code) => new(status, new JsonObject { ["error"] = code });

        public byte[] Bytes()
        {
            var body = Body is null ? [] : Encoding.UTF8.GetBytes(Body.ToJsonString(Json));
            var head = new StringBuilder();
            head.Append(CultureInfo.InvariantCulture, $"HTTP/1.1 {Status} {Reason(Status)}\r\n");
            if (Status != 204)
            {
                head.Append("Content-Type: application/json; charset=utf-8\r\n");
                head.Append(CultureInfo.InvariantCulture, $"Content-Length: {body.Length}\r\n");
            }
            head.Append("Cache-Control: no-store\r\n");
            head.Append("Connection: close\r\n");
            if (Origin is not null)
            {
                head.Append(CultureInfo.InvariantCulture, $"Access-Control-Allow-Origin: {Origin}\r\n");
                head.Append("Vary: Origin\r\n");
                head.Append("Access-Control-Allow-Headers: Content-Type, X-Healdan-Key, X-Healdan-Protocol\r\n");
                head.Append("Access-Control-Allow-Methods: GET, POST\r\n");
                // Chromium's private-network preflight (Windows' WebView2, later); Office for Mac's WebKit doesn't ask.
                head.Append("Access-Control-Allow-Private-Network: true\r\n");
                head.Append("Access-Control-Max-Age: 600\r\n");
            }
            head.Append("\r\n");
            return [.. Encoding.ASCII.GetBytes(head.ToString()), .. body];
        }

        private static string Reason(int status) => status switch
        {
            200 => "OK",
            204 => "No Content",
            400 => "Bad Request",
            403 => "Forbidden",
            404 => "Not Found",
            405 => "Method Not Allowed",
            409 => "Conflict",
            413 => "Content Too Large",
            426 => "Upgrade Required",
            _ => "Internal Server Error",
        };
    }

    /// <summary>
    /// A request's line and headers, read strictly: <c>METHOD /target HTTP/1.1</c>, then <c>Name: value</c> lines to an
    /// empty one, within <see cref="MaxHeadBytes"/>. No folded lines, no <c>Transfer-Encoding</c>, and the headers the
    /// checks read appear once at most. The body, if any, is read later, once the checks have passed.
    /// </summary>
    private sealed class Head
    {
        private static readonly HashSet<string> Single = new(StringComparer.OrdinalIgnoreCase)
        {
            "Host", "Origin", "Content-Length", ProtocolHeader, KeyHeader,
        };

        private readonly Dictionary<string, string> _headers = new(StringComparer.OrdinalIgnoreCase);
        private byte[] _rest = [];

        private Head(string method, string target)
        {
            (Method, Target) = (method, target);
        }

        public string Method { get; }

        public string Target { get; }

        public string? Host => Header("Host");

        public string? Origin => Header("Origin");

        public string? Protocol => Header(ProtocolHeader);

        public string? Key => Header(KeyHeader);

        public long ContentLength { get; private set; }

        private string? Header(string name) => _headers.TryGetValue(name, out var value) ? value : null;

        /// <summary>The request's head, or null when it isn't one this server takes.</summary>
        public static async Task<Head?> ReadAsync(Stream stream, CancellationToken ct)
        {
            var buffer = new byte[MaxHeadBytes];
            var filled = 0;
            int end;
            while ((end = buffer.AsSpan(0, filled).IndexOf("\r\n\r\n"u8)) < 0)
            {
                if (filled == buffer.Length)
                    return null; // a head this long is no request of the add-in's
                var read = await stream.ReadAsync(buffer.AsMemory(filled), ct);
                if (read == 0)
                    return null;
                filled += read;
            }
            var head = Parse(Encoding.Latin1.GetString(buffer, 0, end));
            if (head is not null)
                head._rest = buffer[(end + 4)..filled];
            return head;
        }

        private static Head? Parse(string text)
        {
            var lines = text.Split("\r\n");
            var parts = lines[0].Split(' ');
            if (parts.Length != 3 || parts[2] != "HTTP/1.1" || parts[0].Length == 0 || !parts[0].All(char.IsAsciiLetterUpper)
                || !parts[1].StartsWith('/'))
                return null;
            var head = new Head(parts[0], parts[1]);
            foreach (var line in lines.Skip(1))
            {
                var colon = line.IndexOf(':');
                if (colon <= 0 || line[..colon].Any(c => c <= ' ' || c >= 127))
                    return null; // no name, a folded line, or a name with a space in it
                var name = line[..colon];
                var value = line[(colon + 1)..].Trim(' ', '\t');
                if (string.Equals(name, "Transfer-Encoding", StringComparison.OrdinalIgnoreCase))
                    return null;
                if (head._headers.ContainsKey(name))
                {
                    if (Single.Contains(name))
                        return null;
                    continue;
                }
                head._headers[name] = value;
            }
            if (head.Header("Content-Length") is { } length)
            {
                if (length.Length is 0 or > 19 || !long.TryParse(length, NumberStyles.None, CultureInfo.InvariantCulture, out var bytes))
                    return null;
                head.ContentLength = bytes;
            }
            return head;
        }

        /// <summary>
        /// The body, exactly <see cref="ContentLength"/> bytes, as a JSON object; null when it isn't one, isn't UTF-8, or
        /// when more came than it said (a second request on the connection).
        /// </summary>
        public async Task<JsonObject?> ReadBodyAsync(Stream stream, CancellationToken ct)
        {
            if (_rest.Length > ContentLength)
                return null;
            var body = new byte[ContentLength];
            _rest.CopyTo(body, 0);
            var filled = _rest.Length;
            while (filled < body.Length)
            {
                var read = await stream.ReadAsync(body.AsMemory(filled), ct);
                if (read == 0)
                    return null;
                filled += read;
            }
            // System.Text.Json reads text that isn't UTF-8 and fails only when a value is looked at.
            if (!System.Text.Unicode.Utf8.IsValid(body))
                return null;
            try
            {
                return JsonNode.Parse(body) as JsonObject;
            }
            catch (JsonException)
            {
                return null;
            }
        }
    }
}
