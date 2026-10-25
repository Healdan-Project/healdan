using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// Who has a document, as Open's middle pane says it under the title (spec §5.8): "checked out to you on MacBook", or
/// "checked out to Jane Smith". Never since when: <c>hold.since</c> moves on every save (spec §9.10).
/// </summary>
public static class Holds
{
    /// <param name="me">The signed-in user's id; null while it isn't known, when every hold reads as someone's.</param>
    public static string Text(HoldView? hold, long? me)
    {
        if (hold is null)
            return "";
        var mine = ByMe(hold, me);
        if (hold.Kind == "lock")
            return mine ? "locked by you" : $"locked by {hold.By?.Name ?? "someone"}";
        if (!mine)
            return $"checked out to {hold.By?.Name ?? "someone"}";
        return hold.Device is { } device ? $"checked out to you on {DeviceNames.Short(device.Name)}" : "checked out to you";
    }

    public static bool ByMe(HoldView? hold, long? me) => hold?.By is { } by && me is { } id && by.Id == id;
}
