namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>Waits for something the cycle does in the background, without sleeping longer than it takes.</summary>
public static class Wait
{
    public static async Task Until(Func<bool> condition, string what, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(15));
        while (!condition())
        {
            if (DateTime.UtcNow > deadline)
                throw new TimeoutException("Timed out waiting for: " + what);
            await Task.Delay(10);
        }
    }
}
