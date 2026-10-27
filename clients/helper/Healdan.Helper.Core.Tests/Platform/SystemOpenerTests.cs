using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>The system's opener, which the default app (spec §5.11) and the menu's Open Healdan in Browser use.</summary>
public sealed class SystemOpenerTests
{
    [Theory]
    [InlineData("/Copies/Invoice #7 v1.0 (read-only).pdf")]
    [InlineData("/Copies/Notes; rm -rf ~ $(whoami) `id` | cat.pdf")]
    [InlineData("https://healdan.example/healdan/")]
    public void HandsTheTargetOverWhole(string target)
    {
        if (OperatingSystem.IsWindows()) return;

        var start = SystemOpener.Command(target);

        Assert.Equal(OperatingSystem.IsMacOS() ? "open" : "xdg-open", start.FileName);
        Assert.False(start.UseShellExecute); // no shell reads the target
        Assert.Equal([target], start.ArgumentList);
        Assert.Empty(start.Arguments);
    }

    [Theory]
    [InlineData("file:///healdan-opener-test/Calculator.app")]
    [InlineData("file:///healdan-opener-test/Notes.txt")]
    public void OpensOnlyWebPages(string address) =>
        Assert.Throws<ArgumentException>(() => SystemOpener.OpenWebPage(new Uri(address)));
}
