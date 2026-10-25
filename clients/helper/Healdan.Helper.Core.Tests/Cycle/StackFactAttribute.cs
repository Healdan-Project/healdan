namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>A test against a real Healdan stack at <c>HEALDAN_STACK_URL</c>; it skips itself when that isn't set.</summary>
public sealed class StackFactAttribute : FactAttribute
{
    public StackFactAttribute()
    {
        if (string.IsNullOrEmpty(Environment.GetEnvironmentVariable("HEALDAN_STACK_URL")))
            Skip = "HEALDAN_STACK_URL isn't set.";
    }
}
