using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// Acts on the web page's links (spec §5.3), from an Apple event or the channel's <c>/v1/link</c>. A link only opens,
/// and only from the server the helper is signed in to; one naming another server, or none, is refused with a notice
/// saying which server the helper is signed in to. Until the helper is <see cref="Ready"/> (signed in, and its copies
/// resumed, so no open runs before the stale downloads are swept), the first link is held and opened then, even while
/// the Sign In window waits; later ones are dropped. Once it's ready, links are dropped while one of the helper's
/// windows waits for an answer.
/// </summary>
public sealed class LinkHandler(IWindows windows, INotifier notifier, HelperLog log)
{
    private const string Helper = "Healdan Helper";

    private readonly object _gate = new();
    private Uri? _server;
    private CopyCycle? _cycle;
    private string? _held;
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
            (held, _held) = (_held, null);
        }
        log.Info("Accepting links");
        return held is null ? Task.CompletedTask : Handle(held);
    }

    /// <summary>Links are held again, as after Sign Out. <paramref name="notice"/>, if given, is said to whoever clicks one.</summary>
    public void NotReady(string? notice = null)
    {
        lock (_gate)
            (_server, _cycle, _notReadyNotice) = (null, null, notice);
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
                if (_held is null)
                {
                    _held = url;
                    log.Info($"Holding a link to #{n} until the helper is ready");
                }
                else
                    log.Info($"Dropped a link to #{n}: another is waiting for the helper to be ready");
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
        await Task.Run(() => cycle.OpenAsync(n, readOnly));
        if (!readOnly)
            SayIfReadOnly(cycle, n);
    }

    /// <summary>Spec §5.5: an open the user didn't ask to be read-only says so, and who has the document checked out.</summary>
    private void SayIfReadOnly(CopyCycle cycle, long n)
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
