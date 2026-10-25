namespace Healdan.Helper.Core.Tests.Forms;

/// <summary>Tests that change the process's time zone run alone, so no other test sees it.</summary>
[CollectionDefinition(Name, DisableParallelization = true)]
public sealed class TimeZoneCollection
{
    public const string Name = "Time zone";

    private static readonly object Gate = new();

    /// <summary>Runs <paramref name="test"/> with the process's local time zone set to <paramref name="zone"/>.</summary>
    public static void InZone(string zone, Action test)
    {
        lock (Gate)
        {
            var before = Environment.GetEnvironmentVariable("TZ");
            Environment.SetEnvironmentVariable("TZ", zone);
            TimeZoneInfo.ClearCachedData();
            try
            {
                Assert.Equal(zone, TimeZoneInfo.Local.Id);
                test();
            }
            finally
            {
                Environment.SetEnvironmentVariable("TZ", before);
                TimeZoneInfo.ClearCachedData();
            }
        }
    }
}
