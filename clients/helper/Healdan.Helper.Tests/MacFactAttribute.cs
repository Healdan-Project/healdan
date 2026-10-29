namespace Healdan.Helper.Tests;

/// <summary>A test of something only macOS has; it skips itself elsewhere.</summary>
public sealed class MacFactAttribute : FactAttribute
{
    public MacFactAttribute()
    {
        if (!OperatingSystem.IsMacOS())
            Skip = "This needs macOS.";
    }
}
