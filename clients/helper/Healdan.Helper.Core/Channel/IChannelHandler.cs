using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Channel;

/// <summary>
/// What answers the channel's routes (plan: "The channel's routes"). <c>route</c> is the name after <c>/v1/</c>, such as
/// <c>save-new</c>. The answer is sent as JSON; null becomes <c>{}</c>. A throw becomes a bare <c>500</c> and never
/// stops the server.
/// <para>The server itself runs <c>link</c> in the background and answers <c>{}</c> at once, whatever the handler
/// takes, because an Apple event or hand-over must be answered within 3 s and the open waits on a download. The handler
/// therefore does the whole open in <c>link</c>, and a failure there is the handler's to report to the user, because the
/// server has no one to tell and drops it.</para>
/// </summary>
public interface IChannelHandler
{
    Task<JsonNode?> HandleAsync(string route, JsonObject body);
}
