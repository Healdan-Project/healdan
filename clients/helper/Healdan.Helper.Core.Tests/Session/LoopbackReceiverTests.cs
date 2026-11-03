// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Net.Sockets;
using Healdan.Helper.Core.Session;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>
/// The helper's one-request listener on the loopback address (RFC 8252). Real sockets, on ports the system gives, so
/// parallel tests never meet. The browser's part is played by an HttpClient that follows no redirect.
/// </summary>
public sealed class LoopbackReceiverTests : IAsyncLifetime
{
    private static readonly Uri Done = new("https://healdan.example/api/signin/done?provider=entra");
    private static readonly TimeSpan Plenty = TimeSpan.FromSeconds(30);

    private readonly List<LoopbackReceiver> _receivers = [];

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        foreach (var receiver in _receivers)
            await receiver.DisposeAsync();
    }

    private LoopbackReceiver Start()
    {
        var receiver = LoopbackReceiver.Start();
        _receivers.Add(receiver);
        return receiver;
    }

    /// <summary>One request as the browser sends it. A fresh client each time, so a kept-alive connection can't answer.</summary>
    private static async Task<HttpResponseMessage> Get(Uri address)
    {
        using var http = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false }) { Timeout = Plenty };
        return await http.GetAsync(address);
    }

    private static Uri With(LoopbackReceiver receiver, string query) => new(receiver.RedirectUri, query);

    [Fact]
    public void RedirectIsLoopback()
    {
        var receiver = Start();

        Assert.Equal("http", receiver.RedirectUri.Scheme);
        Assert.Equal("127.0.0.1", receiver.RedirectUri.Host);
        Assert.InRange(receiver.RedirectUri.Port, 1024, 65535);
        Assert.Equal($"http://127.0.0.1:{receiver.RedirectUri.Port}/", receiver.RedirectUri.AbsoluteUri);
    }

    [Fact]
    public async Task ReceivesTheCode()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);

        using var response = await Get(With(receiver, "?code=c&state=s"));

        Assert.Equal(new LoopbackResult("c", "s", null), await waiting);
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        Assert.Equal(Done, response.Headers.Location);
    }

    [Fact]
    public async Task ReceivesAnError()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);

        using var response = await Get(With(receiver, "?error=no-group&state=s"));

        Assert.Equal(new LoopbackResult(null, "s", "no-group"), await waiting);
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
    }

    [Fact]
    public async Task ReadsWhatIsEncodedInTheAddress()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);

        using var response = await Get(With(receiver, "?code=a%2Bb%3Dc&state=x%20y"));

        Assert.Equal(new LoopbackResult("a+b=c", "x y", null), await waiting);
    }

    [Fact]
    public async Task AnswersWithWhereTheResultSays()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(
            result => new Uri(Done + (result.Error is null ? "" : "&error=" + result.Error)), Plenty, default);

        using var response = await Get(With(receiver, "?error=provider&state=s"));
        await waiting;

        Assert.Equal(new Uri(Done + "&error=provider"), response.Headers.Location);
    }

    [Fact]
    public async Task AnAddressWithNothingInItIsAnEmptyResult()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);

        using var response = await Get(receiver.RedirectUri);

        Assert.Equal(new LoopbackResult(null, null, null), await waiting);
        Assert.Equal(Done, response.Headers.Location);
    }

    [Fact]
    public async Task TimesOut()
    {
        var receiver = Start();

        await Assert.ThrowsAsync<TimeoutException>(() => receiver.WaitAsync(Done, TimeSpan.FromMilliseconds(100), default));

        await AssertRefused(receiver.RedirectUri); // it stopped listening
    }

    [Fact]
    public async Task StopsWhenCancelled()
    {
        var receiver = Start();
        using var cancel = new CancellationTokenSource();
        var waiting = receiver.WaitAsync(Done, Plenty, cancel.Token);

        await cancel.CancelAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiting);
        await AssertRefused(receiver.RedirectUri);
    }

    [Fact]
    public async Task OneRequestOnly()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);
        using (await Get(With(receiver, "?code=c&state=s")))
        {
        }
        await waiting; // it has stopped by now

        await AssertRefused(With(receiver, "?code=other&state=s"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => receiver.WaitAsync(Done, Plenty, default));
    }

    [Fact]
    public async Task OnlyGetOfTheRootIsTheAnswer()
    {
        // A browser's favicon, another method or another path gets a 404 and uses nothing up: the wait goes on until
        // the browser comes back to the address Healdan was given.
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);
        var port = receiver.RedirectUri.Port;

        foreach (var path in new[] { "/favicon.ico", "/callback?code=wrong&state=s", "/a/b" })
        {
            using var other = await Get(new Uri($"http://127.0.0.1:{port}{path}"));
            Assert.Equal(HttpStatusCode.NotFound, other.StatusCode);
            Assert.Null(other.Headers.Location);
        }
        foreach (var method in new[] { HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Head, HttpMethod.Options })
        {
            using var http = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false }) { Timeout = Plenty };
            using var answer = await http.SendAsync(new HttpRequestMessage(method, With(receiver, "?code=wrong&state=s")));
            Assert.Equal(HttpStatusCode.NotFound, answer.StatusCode);
        }
        Assert.False(waiting.IsCompleted);

        using var response = await Get(With(receiver, "?code=c&state=s"));

        Assert.Equal(new LoopbackResult("c", "s", null), await waiting);
        Assert.Equal(Done, response.Headers.Location);
        await AssertRefused(With(receiver, "?code=other&state=s"));
    }

    [Fact]
    public async Task TheWaitsTimeoutCoversTheRequestsThatArentTheAnswer()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, TimeSpan.FromMilliseconds(500), default);

        using (var other = await Get(new Uri(receiver.RedirectUri, "/favicon.ico")))
            Assert.Equal(HttpStatusCode.NotFound, other.StatusCode);

        await Assert.ThrowsAsync<TimeoutException>(() => waiting);
        await AssertRefused(receiver.RedirectUri);
    }

    [Fact]
    public async Task DisposeFreesThePort()
    {
        var receiver = Start();
        var port = receiver.RedirectUri.Port;

        await receiver.DisposeAsync();
        await receiver.DisposeAsync(); // twice does no harm

        var taken = new TcpListener(IPAddress.Loopback, port);
        taken.Start();
        taken.Stop();
    }

    [Fact]
    public async Task DisposeEndsAWait()
    {
        var receiver = Start();
        var waiting = receiver.WaitAsync(Done, Plenty, default);

        await receiver.DisposeAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiting);
    }

    [Fact]
    public async Task EachReceiverHasItsOwnPort()
    {
        var first = Start();
        var second = Start();

        Assert.NotEqual(first.RedirectUri.Port, second.RedirectUri.Port);
        var waiting = second.WaitAsync(Done, Plenty, default);
        using var response = await Get(With(second, "?code=c&state=s"));
        Assert.Equal("c", (await waiting).Code);
    }

    private static async Task AssertRefused(Uri address) =>
        await Assert.ThrowsAsync<HttpRequestException>(async () => (await Get(address)).Dispose());
}
