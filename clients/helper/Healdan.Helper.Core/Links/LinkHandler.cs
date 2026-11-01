using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// Acts on the web page's links (spec §5.3), from an Apple event or the channel's <c>/v1/link</c>. A link only opens,
/// only documents, spreadsheets, presentations, drawings and PDFs (<see cref="CopyCycle.OpenLinkAsync"/>; M3.6 spec §5.1),
/// and only from the server the helper is signed in to; one naming another server, or none, is refused with a notice
/// saying which server the helper is signed in to. The link that started the helper is held until it's first
/// <see cref="Ready"/> (signed in, and its copies resumed, so no open runs before the stale downloads are swept), even
/// while the Sign In window waits; later ones are dropped. Only that cold-start link is ever held: once the helper has
/// been ready, or the start-up Sign In was dismissed, a link while it isn't signed in is dropped with a notice, so
/// signing in hours later never checks out a document nobody asked for then. Once it's ready, links are dropped while
/// one of the helper's windows waits for an answer, and that window comes to the front.
/// </summary>
public sealed class LinkHandler(IWindows windows, INotifier notifier, HelperLog log)
{
    private const string Helper = "Healdan Helper";

    private readonly object _gate = new();
    private Uri? _server;
    private CopyCycle? _cycle;
    private string? _held;
    private bool _holding = true; // until the first Ready or NotReady: the cold-start link
    private string? _notReadyNotice;

    /// <summary>
    /// Links open from now on, against <paramref name="server"/> through <paramref name="cycle"/>. A link held while
    /// the helper was starting opens now; the task is that open.
    /// </summary>
    public Task Ready(Uri server, CopyCycle cycle)
    {
        string? held;
        lock (_gate)
        {
            (_server, _cycle, _notReadyNotice) = (server, cycle, null);
            (held, _held, _holding) = (_held, null, false);
        }
        log.Info("Accepting links");
        return held is null ? Task.CompletedTask : Handle(held);
    }

    /// <summary>
    /// Links stop opening, as after Sign Out or a dismissed start-up Sign In, and a link held from the start is dropped.
    /// <paramref name="notice"/>, if given, is said to whoever clicks one.
    /// </summary>
    public void NotReady(string? notice = null)
    {
        string? dropped;
        lock (_gate)
        {
            (_server, _cycle, _notReadyNotice) = (null, null, notice);
            (dropped, _held, _holding) = (_held, null, false);
        }
        if (dropped is not null)
            log.Info("Dropped the link held from the start: the helper isn't signed in");
    }

    /// <summary>
    /// Acts on <paramref name="url"/>. The task ends once the open has (it never throws: the cycle reports an open's
    /// failure itself). The open never runs on the caller's thread, so the app can call this from its UI thread.
    /// </summary>
    public async Task Handle(string url)
    {
        if (!HealdanLink.TryParse(url, out var n, out var readOnly, out var server))
        {
            log.Warn("Ignored a link that isn't Healdan's open link");
            notifier.Notify(Helper, "Healdan Helper only opens documents, so it ignored that link.");
            return;
        }
        Uri? signedIn;
        CopyCycle? cycle;
        string? notReady = null;
        lock (_gate)
        {
            (signedIn, cycle) = (_server, _cycle);
            if (cycle is null)
            {
                if (_holding && _held is null)
                {
                    _held = url;
                    log.Info($"Holding a link to #{n} until the helper is ready");
                }
                else
                    log.Info($"Dropped a link to #{n}: the helper isn't ready");
                notReady = _notReadyNotice;
            }
        }
        if (cycle is null || signedIn is null)
        {
            if (notReady is not null)
                notifier.Notify(Helper, notReady);
            return;
        }
        // Once the helper is ready. Before, the window waiting is Sign In, which is part of getting ready: the link
        // that started the helper waits for it rather than being lost.
        if (windows.Waiting)
        {
            log.Info($"Dropped a link to #{n}: a window is waiting for an answer");
            windows.BringWaiting();
            return;
        }

        var ours = HealdanLink.HostAndPort(signedIn);
        if (server is null || !HealdanLink.IsServer(server, signedIn))
        {
            // The link's server comes from a web page: it's shown only when it's a plain host and port.
            var theirs = HealdanLink.IsHostAndPort(server) ? server : null;
            log.Warn($"Refused a link to #{n} from {theirs ?? "an unnamed or unreadable server"}; signed in to {ours}");
            notifier.Notify(Helper, theirs is null
                ? $"That link doesn't say it's from {ours}, the server Healdan Helper is signed in to, so it wasn't opened."
                : $"That link is from {theirs}, but Healdan Helper is signed in to {ours}, so it wasn't opened.");
            return;
        }

        log.Info($"Opening #{n}{(readOnly ? " read-only" : "")} from a link");
        await Task.Run(() => cycle.OpenLinkAsync(n, readOnly)); // spec 5.11's kinds, and PDFs (M3.6 spec §5.1)
        if (!readOnly)
            SayIfReadOnly(cycle, n, notifier);
    }

    /// <summary>
    /// Spec §5.5: an open the user didn't ask to be read-only says so, and who has the document checked out. For a
    /// link's open and Open from Healdan's alike.
    /// </summary>
    public static void SayIfReadOnly(CopyCycle cycle, long n, INotifier notifier)
    {
        var copies = cycle.OpenCopies().Where(c => c.DocNumber == n).ToList();
        if (copies.Count == 0 || copies.Any(c => !c.ReadOnly))
            return;
        var copy = copies[^1];
        notifier.Notify(copy.Title, copy.HoldBy is { } who
            ? $"Opened read-only: {who} has it checked out."
            : "Opened read-only: you can't edit it.");
    }
}
