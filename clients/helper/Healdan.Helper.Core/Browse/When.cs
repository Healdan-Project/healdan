using System.Globalization;
using System.Text.RegularExpressions;

namespace Healdan.Helper.Core.Browse;

/// <summary>
/// Times as the windows show them, in the computer's time zone and the order its region writes them: "10:42 AM" today,
/// "Monday" this week, "Oct 12" this year, "Oct 12, 2025" before, in the US; "10:42", "12 Oct" and "12 Oct 2025" in
/// the UK. A timestamp, unlike a date property (spec §9.10), is a moment, so it's shown where the user is. The words
/// around them stay English, as the rest of the helper is. The culture is the computer's unless a test names one.
/// </summary>
public static partial class When
{
    /// <summary>The Edited column's form.</summary>
    public static string Short(DateTimeOffset? at, DateTimeOffset now, TimeZoneInfo? zone = null, CultureInfo? culture = null)
    {
        if (at is not { } t)
            return "";
        var c = culture ?? CultureInfo.CurrentCulture;
        var (local, today) = Local(t, now, zone);
        var days = today.DayNumber - DateOnly.FromDateTime(local.DateTime).DayNumber;
        return days switch
        {
            0 => Time(local, c),
            > 0 and < 7 => local.ToString("dddd", c),
            _ => Day(local, today, c)
        };
    }

    /// <summary>For a sentence: "today at 10:42", "yesterday at 16:05", "on 12 Oct at 09:00".</summary>
    public static string InSentence(DateTimeOffset at, DateTimeOffset now, TimeZoneInfo? zone = null, CultureInfo? culture = null)
    {
        var c = culture ?? CultureInfo.CurrentCulture;
        var (local, today) = Local(at, now, zone);
        var time = Time(local, c);
        return (today.DayNumber - DateOnly.FromDateTime(local.DateTime).DayNumber) switch
        {
            0 => $"today at {time}",
            1 => $"yesterday at {time}",
            _ => $"on {Day(local, today, c)} at {time}"
        };
    }

    /// <summary>The day alone, month in full and year always: "March 3, 2026", or "3 March 2026" in the UK. For when a document was filed.</summary>
    public static string Date(DateTimeOffset at, TimeZoneInfo? zone = null, CultureInfo? culture = null)
    {
        var c = culture ?? CultureInfo.CurrentCulture;
        return TimeZoneInfo.ConvertTime(at, zone ?? TimeZoneInfo.Local).ToString(LongDate(c), c);
    }

    /// <summary>A calendar date, such as a date property's, as <see cref="Date(DateTimeOffset, TimeZoneInfo?, CultureInfo?)"/> writes it, with no time zone.</summary>
    public static string Date(DateOnly date, CultureInfo? culture = null)
    {
        var c = culture ?? CultureInfo.CurrentCulture;
        return date.ToString(LongDate(c), c);
    }

    /// <summary>The region's day with the month abbreviated and the year, "MMM d, yyyy" in the US, for a date picker.</summary>
    public static string ShortDatePattern(CultureInfo? culture = null) => Abbreviated(LongDate(culture ?? CultureInfo.CurrentCulture));

    private static (DateTimeOffset Local, DateOnly Today) Local(DateTimeOffset at, DateTimeOffset now, TimeZoneInfo? zone)
    {
        var z = zone ?? TimeZoneInfo.Local;
        return (TimeZoneInfo.ConvertTime(at, z), DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(now, z).DateTime));
    }

    private static string Time(DateTimeOffset local, CultureInfo c) => local.ToString(c.DateTimeFormat.ShortTimePattern, c);

    private static string Day(DateTimeOffset local, DateOnly today, CultureInfo c) =>
        local.ToString(local.Year == today.Year ? Abbreviated(c.DateTimeFormat.MonthDayPattern) : ShortDatePattern(c), c);

    /// <summary>The region's long date without its weekday: .NET has no medium date, and ICU's long one starts or ends with it.</summary>
    private static string LongDate(CultureInfo c) => Weekday().Replace(c.DateTimeFormat.LongDatePattern, " ").Trim();

    private static string Abbreviated(string pattern) => pattern.Replace("MMMM", "MMM", StringComparison.Ordinal);

    [GeneratedRegex(@"\s*,?\s*dddd\s*,?\s*")]
    private static partial Regex Weekday();
}
