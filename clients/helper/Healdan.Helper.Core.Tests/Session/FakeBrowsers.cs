using System.Net;
using Healdan.Helper.Core.Session;

namespace Healdan.Helper.Core.Tests.Session;

/// <summary>
/// The person's browser. Opened at the address the helper gives, it asks Healdan to sign in (which sends it back to
/// the helper's loopback address), optionally changes what came back, and follows that to the helper's listener.
/// <see cref="Done"/> is where the listener sent it on to.
/// </summary>
internal sealed class FakeBrowser(HttpMessageHandler healdan, Func<Uri, Uri>? change = null) : IBrowser
{
    public Uri? Opened { get; private set; }

    public Uri? CameBackTo { get; private set; }

    public Task<Uri>? Done { get; private set; }

    public void Open(Uri address)
    {
        Opened = address;
        // Not on the caller's thread: the listener answers only once the helper waits for it, after Open returns.
        Done = Task.Run(async () =>
        {
            using var toHealdan = new HttpClient(healdan, disposeHandler: false);
            using var asked = await toHealdan.GetAsync(address);
            Assert.Equal(HttpStatusCode.Found, asked.StatusCode);
            CameBackTo = change?.Invoke(asked.Headers.Location!) ?? asked.Headers.Location!;
            using var toHelper = new HttpClient(new SocketsHttpHandler { AllowAutoRedirect = false });
            using var answered = await toHelper.GetAsync(CameBackTo);
            Assert.Equal(HttpStatusCode.Found, answered.StatusCode);
            return answered.Headers.Location!;
        });
    }
}

/// <summary>A browser the person never finishes in.</summary>
internal sealed class IdleBrowser : IBrowser
{
    private volatile Uri? _opened;

    public Uri? Opened => _opened;

    public void Open(Uri address) => _opened = address;
}
