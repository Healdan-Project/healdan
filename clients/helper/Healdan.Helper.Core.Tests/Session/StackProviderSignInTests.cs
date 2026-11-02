using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Session;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>A stack test of a provider sign-in: it skips without <c>HEALDAN_STACK_URL</c> and <c>HEALDAN_STACK_SIGNIN_PROVIDER</c>.</summary>
public sealed class StackSignInFactAttribute : FactAttribute
{
    public StackSignInFactAttribute()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("HEALDAN_STACK_URL")))
            Skip = "HEALDAN_STACK_URL isn't set.";
        else if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("HEALDAN_STACK_SIGNIN_PROVIDER")))
            Skip = "HEALDAN_STACK_SIGNIN_PROVIDER isn't set.";
    }
}

/// <summary>
/// The helper's sign-in through a provider against a real stack (M4 spec §16.1): the smoke stack's stand-in Keycloak,
/// provider <c>HEALDAN_STACK_SIGNIN_PROVIDER</c>, as the person <c>HEALDAN_STACK_SIGNIN_USER</c> with
/// <c>HEALDAN_STACK_SIGNIN_PASSWORD</c>. A fake browser plays the person's: it follows the redirects with a cookie
/// jar, fills in Keycloak's login form, and comes back to the helper's real loopback listener. Keycloak names itself
/// <c>http://keycloak:8080</c>, the address the app reaches it at on the stack's network, so the browser sends that
/// name to <c>HEALDAN_STACK_KEYCLOAK</c> (<c>host:port</c>), Keycloak's port on this computer.
/// </summary>
[Trait("Category", "Stack")]
public sealed partial class StackProviderSignInTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-stack-signin-").FullName;

    public void Dispose() => Directory.Delete(_root, true);

    private static string? Env(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value ? value : null;

    private static string Needed(string name) => Env(name) ?? throw new InvalidOperationException($"{name} isn't set.");

    [StackSignInFact]
    public async Task SignsInThroughTheStandIn()
    {
        var user = Needed("HEALDAN_STACK_SIGNIN_USER");
        var api = new HealdanApi(new Uri(Needed("HEALDAN_STACK_URL")));
        var keychain = new MemoryKeychain();
        var device = new DeviceIdentity(Guid.NewGuid().ToString(), "Healdan Helper stack sign-in test");
        var log = new HelperLog(Path.Combine(_root, "helper.log"));
        var browser = new KeycloakBrowser(Env("HEALDAN_STACK_KEYCLOAK"), user, Needed("HEALDAN_STACK_SIGNIN_PASSWORD"));

        using var stop = new CancellationTokenSource();
        var signingIn = new SignIn(api, keychain, device, log: log).SignInWithProviderAsync(Needed("HEALDAN_STACK_SIGNIN_PROVIDER"), browser, stop.Token);
        await Wait.Until(() => browser.Done is not null || signingIn.IsCompleted, "the browser opened", TimeSpan.FromSeconds(60));
        if (browser.Done is { } browsing && await Task.WhenAny(signingIn, browsing) == browsing
            && await Task.WhenAny(signingIn, Task.Delay(TimeSpan.FromSeconds(30))) != signingIn)
        {
            // The browser stopped, and the helper heard nothing: it stopped somewhere else than the helper's address,
            // and the helper would wait its 5 minutes. Say where instead.
            stop.Cancel();
            var (at, status, page) = await browsing; // or the browser's own failure
            Assert.Fail($"The browser stopped at {at} with HTTP {(int)status}, not Healdan's done page: {page[..Math.Min(page.Length, 400)]}");
        }
        await signingIn;
        try
        {
            // The browser was sent on to Healdan's done page, which says to close the tab.
            var (done, status, page) = await browser.Done!;
            Assert.Equal(HttpStatusCode.OK, status);
            Assert.Equal("/api/signin/done", done.AbsolutePath);
            Assert.DoesNotContain("error=", done.Query);
            Assert.Contains("You can close this tab.", page);
            Assert.True(browser.LoggedIn, "the browser never met Keycloak's login form");

            // The token the code was exchanged for is the person's, and it's the one kept.
            var me = await api.GetJsonAsync("v1/me");
            Assert.Equal(user, me.GetProperty("username").GetString());
            Assert.Equal(api.Token, KeptToken.Read(Assert.Single(keychain.Items).Value)!.Token);
            Assert.DoesNotContain(api.Token!, await File.ReadAllTextAsync(log.Path));
        }
        finally
        {
            await api.SignOutAsync(); // the stack's device tokens stay few
        }
    }

    [GeneratedRegex("""<form\b[^>]*\bid="kc-form-login"[^>]*>""", RegexOptions.IgnoreCase)]
    private static partial Regex LoginForm();

    [GeneratedRegex("\\baction=\"([^\"]*)\"", RegexOptions.IgnoreCase)]
    private static partial Regex Action();

    /// <summary>
    /// The person's browser: from the address the helper opens, it follows each redirect with one cookie jar, posts
    /// Keycloak's login form once, and stops at the first page that isn't a redirect: Healdan's done page, by way of
    /// the helper's loopback listener. <see cref="Done"/> is where it stopped, with the page's status and text.
    /// </summary>
    private sealed class KeycloakBrowser(string? keycloak, string user, string password) : IBrowser
    {
        private volatile Task<(Uri Address, HttpStatusCode Status, string Page)>? _done;

        /// <summary>Where the browser stopped, once it has: the address, its status and the page.</summary>
        public Task<(Uri Address, HttpStatusCode Status, string Page)>? Done => _done;

        /// <summary>Whether it posted Keycloak's login form.</summary>
        public bool LoggedIn { get; private set; }

        // Not on the helper's thread: the listener answers only once the helper waits for it, after Open returns.
        public void Open(Uri address) => _done = Task.Run(() => SignInAsync(address));

        private async Task<(Uri, HttpStatusCode, string)> SignInAsync(Uri address)
        {
            using var handler = new SocketsHttpHandler
            {
                AllowAutoRedirect = false,
                CookieContainer = new CookieContainer(),
                ConnectCallback = ConnectAsync
            };
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(60) };
            var at = address;
            var response = await http.GetAsync(at);
            try
            {
                for (var hops = 0; ; hops++)
                {
                    Assert.True(hops < 20, $"more than 20 hops, at {at}");
                    if ((int)response.StatusCode is >= 300 and < 400 && response.Headers.Location is { } next)
                    {
                        at = new Uri(at, next);
                        response.Dispose();
                        response = await http.GetAsync(at);
                        continue;
                    }
                    var page = await response.Content.ReadAsStringAsync();
                    if (!LoggedIn && LoginForm().Match(page) is { Success: true } form && Action().Match(form.Value) is { Success: true } action)
                    {
                        LoggedIn = true;
                        at = new Uri(at, WebUtility.HtmlDecode(action.Groups[1].Value));
                        response.Dispose();
                        response = await http.PostAsync(at, new FormUrlEncodedContent(new Dictionary<string, string>
                        {
                            ["username"] = user,
                            ["password"] = password,
                            ["credentialId"] = ""
                        }));
                        continue;
                    }
                    return (at, response.StatusCode, page);
                }
            }
            finally
            {
                response.Dispose();
            }
        }

        /// <summary>Keycloak's own name goes to its port on this computer; every other address as it is.</summary>
        private async ValueTask<Stream> ConnectAsync(SocketsHttpConnectionContext context, CancellationToken ct)
        {
            var to = context.DnsEndPoint;
            if (keycloak is not null && to is { Host: "keycloak", Port: 8080 })
            {
                var colon = keycloak.LastIndexOf(':');
                to = new DnsEndPoint(keycloak[..colon], int.Parse(keycloak[(colon + 1)..]));
            }
            var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
            try
            {
                await socket.ConnectAsync(to, ct);
                return new NetworkStream(socket, ownsSocket: true);
            }
            catch
            {
                socket.Dispose();
                throw;
            }
        }
    }
}
