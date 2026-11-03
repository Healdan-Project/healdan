// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Net.Sockets;

namespace Healdan.Helper.Core.Session;

/// <summary>What the browser brought to the helper's loopback address: the query's <c>code</c>, <c>state</c> and <c>error</c>, each null when absent.</summary>
public sealed record LoopbackResult(string? Code, string? State, string? Error);

/// <summary>
/// The helper's end of a sign-in in the system browser (RFC 8252, spec §6.2): a listener on <c>127.0.0.1</c>, at a port
/// the system gave, for one answer. Healdan sends the browser back to <see cref="RedirectUri"/> with the code, or the
/// reason it refused; the receiver reads that, sends the browser on to a page of Healdan's that tells the person to
/// close the tab, and stops, so nothing else can reach the port. Only a <c>GET</c> of <c>/</c> is the answer: any other
/// method or path, such as a browser's <c>/favicon.ico</c>, gets a <c>404</c> and uses nothing up. Only the loopback
/// address is ever bound.
/// </summary>
/// <remarks>The code, state and error are the caller's to guard: nothing here logs them.</remarks>
public sealed class LoopbackReceiver : IAsyncDisposable
{
    /// <summary>How often a port the system gave is lost to someone else before a listener is bound on it.</summary>
    private const int Attempts = 3;

    private readonly HttpListener _listener;
    private int _waited;
    private int _stopped;

    private LoopbackReceiver(HttpListener listener, Uri redirectUri)
    {
        _listener = listener;
        RedirectUri = redirectUri;
    }

    /// <summary>Where Healdan sends the browser back: <c>http://127.0.0.1:&lt;port&gt;/</c>.</summary>
    public Uri RedirectUri { get; }

    /// <summary>
    /// Starts listening on a free port. The system's free port is found by binding port 0 and letting it go, so
    /// another program may take it before the listener does; that is tried again, up to three times.
    /// </summary>
    /// <exception cref="IOException">No port could be listened on.</exception>
    public static LoopbackReceiver Start()
    {
        Exception? last = null;
        for (var attempt = 0; attempt < Attempts; attempt++)
        {
            var prefix = $"http://127.0.0.1:{FreePort()}/";
            var listener = new HttpListener();
            listener.Prefixes.Add(prefix);
            try
            {
                listener.Start();
                return new LoopbackReceiver(listener, new Uri(prefix));
            }
            catch (Exception e) when (e is HttpListenerException or SocketException)
            {
                last = e;
                listener.Close();
            }
        }
        throw new IOException("Healdan Helper couldn't listen on this computer for the sign-in.", last);
    }

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

    /// <summary>
    /// The first <c>GET</c> of <c>/</c> at the port: its code, state or error. The browser is sent on (<c>302</c>) to
    /// <paramref name="done"/>, and the listener stops. Any other request gets a <c>404</c>, and the wait goes on.
    /// </summary>
    /// <exception cref="TimeoutException">Nothing came within <paramref name="timeout"/>.</exception>
    /// <exception cref="OperationCanceledException">The caller cancelled, or the receiver was disposed.</exception>
    /// <exception cref="InvalidOperationException">It has already been asked to wait: it takes one request only.</exception>
    public Task<LoopbackResult> WaitAsync(Uri done, TimeSpan timeout, CancellationToken ct) =>
        WaitAsync(_ => done, timeout, ct);

    /// <summary>
    /// As <see cref="WaitAsync(Uri, TimeSpan, CancellationToken)"/>, with the page the browser is sent on to chosen
    /// from what it brought: the caller checks the state, and the page can say how it went.
    /// </summary>
    public async Task<LoopbackResult> WaitAsync(Func<LoopbackResult, Uri> done, TimeSpan timeout, CancellationToken ct)
    {
        if (Interlocked.Exchange(ref _waited, 1) == 1)
            throw new InvalidOperationException("The listener takes one request, and has been asked for it already.");

        using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
        limit.CancelAfter(timeout);
        try
        {
            while (true)
            {
                var context = await AcceptAsync(limit.Token, ct);
                if (!IsTheAnswer(context.Request))
                {
                    NotFound(context.Response);
                    continue;
                }
                var query = context.Request.QueryString;
                var result = new LoopbackResult(Value(query["code"]), Value(query["state"]), Value(query["error"]));
                Respond(context.Response, done(result));
                return result;
            }
        }
        finally
        {
            Stop();
        }
    }

    /// <summary>The next request to the port, within the wait's time.</summary>
    private async Task<HttpListenerContext> AcceptAsync(CancellationToken limit, CancellationToken ct)
    {
        var accepting = _listener.GetContextAsync();
        try
        {
            return await accepting.WaitAsync(limit);
        }
        catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or HttpListenerException)
        {
            Stop();
            _ = accepting.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously);
            if (e is OperationCanceledException && ct.IsCancellationRequested)
                throw;
            if (e is OperationCanceledException)
                throw new TimeoutException("Nothing came back to the sign-in's address in time.");
            throw new OperationCanceledException("The sign-in's listener was closed.", e);
        }
    }

    /// <summary>Whether it's the browser coming back to <see cref="RedirectUri"/>: a <c>GET</c> of <c>/</c>.</summary>
    private static bool IsTheAnswer(HttpListenerRequest request) =>
        request.HttpMethod == "GET" && request.Url?.AbsolutePath == "/";

    /// <summary>Answers a request that isn't the answer with a 404, and closes its connection.</summary>
    private static void NotFound(HttpListenerResponse response)
    {
        try
        {
            response.KeepAlive = false;
            response.StatusCode = (int)HttpStatusCode.NotFound;
            response.Headers["Cache-Control"] = "no-store";
            response.Close();
        }
        catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or IOException or InvalidOperationException)
        {
            // gone
        }
    }

    private static string? Value(string? v) => string.IsNullOrEmpty(v) ? null : v;

    /// <summary>
    /// Sends the browser on. It's not cached and carries no referrer, and the connection closes with it. A browser
    /// that has gone away doesn't matter: what it brought is already in hand.
    /// </summary>
    private static void Respond(HttpListenerResponse response, Uri next)
    {
        try
        {
            response.KeepAlive = false;
            response.Headers["Cache-Control"] = "no-store";
            response.Headers["Referrer-Policy"] = "no-referrer";
            response.Redirect(next.AbsoluteUri);
            response.Close();
        }
        catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or IOException or InvalidOperationException)
        {
            // gone
        }
    }

    /// <summary>Stops listening, for good: the port is free again.</summary>
    private void Stop()
    {
        if (Interlocked.Exchange(ref _stopped, 1) == 1)
            return;
        try
        {
            _listener.Close();
        }
        catch (ObjectDisposedException)
        {
            // already closed
        }
    }

    public ValueTask DisposeAsync()
    {
        Stop();
        return ValueTask.CompletedTask;
    }
}
