// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Diagnostics;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Channel;
using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// A second helper's links. Only one helper runs for each user, but a second process starts for a link: on Linux for
/// every click (the desktop starts the helper with the link as its argument), on macOS when the copy it hands
/// <c>healdan:</c> links to isn't the one running, such as an old one in <c>/Applications</c> beside a newer one. That
/// process quits, so it first hands each link it's given to the running helper over the channel, as <c>POST /v1/link</c>
/// with the secret from <c>channel.json</c>. A link it can't hand over is logged as dropped, with why (Linux's second
/// process also says so in a notice, <see cref="NotTakenNotice"/>); only Healdan's open links are handed over, and a
/// link is named in the log and the notice only by its doc #.
/// </summary>
/// <param name="timeout">How long the running helper has to take the link; 3 s by default, as it answers at once.</param>
public sealed class LinkHandOver(HelperPaths paths, HelperLog log, TimeSpan? timeout = null)
{
    private const string Helper = "the running Healdan Helper";

    private static readonly TimeSpan Retry = TimeSpan.FromMilliseconds(250);

    /// <summary>What the notice says when a second helper is started with no link while one runs (Linux).</summary>
    public const string AlreadyRunningNotice = "Healdan Helper is already running. Its menu is in the top bar.";

    /// <summary>
    /// What the notice says when the running helper didn't take a link (it has no channel while signed out or signing in),
    /// naming the doc # and never the whole link; null when <paramref name="url"/> isn't Healdan's open link.
    /// </summary>
    public static string? NotTakenNotice(string url) =>
        HealdanLink.TryParse(url, out var n, out _, out _)
            ? $"Healdan Helper didn't open #{n}. Sign in from its menu, then click the link again."
            : null;

    /// <summary>
    /// True once the running helper has taken <paramref name="url"/>; it opens it as its own links. With a
    /// <paramref name="wait"/> it tries again about every 250 ms until the helper takes it or the wait ends, as a helper
    /// still signing in has no channel yet; without one it tries once.
    /// </summary>
    public bool Send(string url, TimeSpan? wait = null)
    {
        if (!HealdanLink.TryParse(url, out var n, out _, out _))
        {
            log.Warn("Dropped a link that isn't Healdan's open link");
            return false;
        }
        if (!Forward(url, wait))
        {
            log.Warn($"Dropped a link to #{n}: {Helper} didn't take it, or nothing answers its channel");
            return false;
        }
        log.Info($"Handed a link to #{n} to {Helper}");
        return true;
    }

    /// <summary>
    /// Tries again only while the link can't have reached the running helper: one sent that got no answer in time may
    /// have been taken, and sending it again would open it twice.
    /// </summary>
    private bool Forward(string url, TimeSpan? wait)
    {
        var until = Stopwatch.StartNew();
        while (true)
        {
            switch (ChannelInfo.Forward(paths, "link", new JsonObject { ["url"] = url }, timeout ?? TimeSpan.FromSeconds(3)))
            {
                case ChannelInfo.ForwardOutcome.Taken:
                    return true;
                case ChannelInfo.ForwardOutcome.Unknown:
                    log.Warn($"{Helper} didn't answer in time, so the link isn't sent again: it may have it");
                    return false;
            }
            if (wait is not { } limit)
                return false;
            var left = limit - until.Elapsed; // read once, so it can't go below zero between a check and the sleep
            if (left <= TimeSpan.Zero)
                return false;
            Thread.Sleep(left < Retry ? left : Retry);
        }
    }
}
