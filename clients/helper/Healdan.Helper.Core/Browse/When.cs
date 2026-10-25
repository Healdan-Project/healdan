using System.Globalization;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// Times as the windows show them, in the computer's time zone: "10:42" today, "Monday" this week, "12 Oct" this year,
/// "12 Oct 2025" before. A timestamp, unlike a date property (spec §9.10), is a moment, so it's shown where the user is.
/// </summary>
public static class When
{
    /// <summary>The Edited column's form.</summary>
    public static string Short(DateTimeOffset? at, DateTimeOffset now, TimeZoneInfo? zone = null)
    {
        if (at is not { } t)
            return "";
        var (local, today) = Local(t, now, zone);
        var days = today.DayNumber - DateOnly.FromDateTime(local.DateTime).DayNumber;
        return days switch
        {
            0 => local.ToString("HH:mm", CultureInfo.InvariantCulture),
            > 0 and < 7 => local.ToString("dddd", CultureInfo.InvariantCulture),
            _ => Day(local, today)
        };
    }

    /// <summary>For a sentence: "today at 10:42", "yesterday at 16:05", "on 12 Oct at 09:00".</summary>
    public static string InSentence(DateTimeOffset at, DateTimeOffset now, TimeZoneInfo? zone = null)
    {
        var (local, today) = Local(at, now, zone);
        var time = local.ToString("HH:mm", CultureInfo.InvariantCulture);
        return (today.DayNumber - DateOnly.FromDateTime(local.DateTime).DayNumber) switch
        {
            0 => $"today at {time}",
            1 => $"yesterday at {time}",
            _ => $"on {Day(local, today)} at {time}"
        };
    }

    private static (DateTimeOffset Local, DateOnly Today) Local(DateTimeOffset at, DateTimeOffset now, TimeZoneInfo? zone)
    {
        var z = zone ?? TimeZoneInfo.Local;
        return (TimeZoneInfo.ConvertTime(at, z), DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now, z).DateTime));
    }

    private static string Day(DateTimeOffset local, DateOnly today) =>
        local.ToString(local.Year == today.Year ? "d MMM" : "d MMM yyyy", CultureInfo.InvariantCulture);
}
