using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// A second helper's links. Only one helper runs for each user, but macOS starts another copy of the app when the copy
/// it hands <c>healdan:</c> links to isn't the one running, such as an old one in <c>/Applications</c> beside a newer
/// one. That copy quits, so it first hands each link it's given to the running helper over the channel, as
/// <c>POST /v1/link</c> with the secret from <c>channel.json</c>. A link it can't hand over is logged as dropped, with
/// why; only Healdan's open links are handed over, and a link is named in the log only by its doc #.
/// </summary>
/// <param name="timeout">How long the running helper has to take the link; 3 s by default, as it answers at once.</param>
public sealed class LinkHandOver(HelperPaths paths, HelperLog log, TimeSpan? timeout = null)
{
    private const string Helper = "the running Healdan Helper";

    /// <summary>True once the running helper has taken <paramref name="url"/>; it opens it as its own links.</summary>
    public bool Send(string url)
    {
        if (!HealdanLink.TryParse(url, out var n, out _, out _))
        {
            log.Warn("Dropped a link that isn't Healdan's open link");
            return false;
        }
        if (!ChannelInfo.TryForward(paths, "link", new JsonObject { ["url"] = url }, timeout ?? TimeSpan.FromSeconds(3)))
        {
            log.Warn($"Dropped a link to #{n}: {Helper} didn't take it, or nothing answers its channel");
            return false;
        }
        log.Info($"Handed a link to #{n} to {Helper}");
        return true;
    }
}
