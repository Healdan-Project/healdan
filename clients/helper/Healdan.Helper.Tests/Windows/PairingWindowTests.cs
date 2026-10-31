using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// The pairing window (M3.5 spec §5.9), over the helper's own look: the code Word, Excel or PowerPoint's Healdan pane asks
/// for, and when the window goes. For a Mac, as the app's other window tests are.
/// </summary>
public sealed class PairingWindowTests
{
    /// <summary>Makes the window and shows it on the UI thread, runs <paramref name="test"/> there, and closes it.</summary>
    private static Task Run(string app, string code, DateTimeOffset expires, Func<PairingWindow, Task> test) => Headless.RunAsync(async () =>
    {
        var window = new PairingWindow(app, code, expires);
        window.Show();
        try
        {
            await test(window);
        }
        finally
        {
            window.Close();
        }
        return 0;
    });

    private static async Task Until(Func<bool> condition)
    {
        var until = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < until, "It didn't happen within 15 seconds.");
            await Task.Delay(10);
        }
    }

    private static DateTimeOffset InTwoMinutes => DateTimeOffset.UtcNow.AddMinutes(2);

    [MacFact]
    public Task ShowsTheCodeAndWhereToTypeIt() => Run("Word", "048213", InTwoMinutes, window =>
    {
        var texts = window.GetVisualDescendants().OfType<TextBlock>().Select(t => t.Text).ToList();

        Assert.Equal("Pair Word", window.Title);
        Assert.Contains("Word wants to connect to Healdan Helper", texts);
        Assert.Contains("048 213", texts); // large, three and three, leading zero kept
        Assert.Contains("Type this code in the Healdan pane in Word. It expires in 2 minutes.", texts);
        var code = window.FindControl<TextBlock>("CodeText")!;
        Assert.Equal("Code 0 4 8 2 1 3", AutomationProperties.GetName(code)); // read out digit by digit
        var cancel = window.FindControl<Button>("CancelButton")!;
        Assert.Equal("Cancel", cancel.Content);
        Assert.True(cancel.IsCancel);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task CancelIsTheUsersToEndThePairing() => Run("Excel", "123456", InTwoMinutes, window =>
    {
        window.FindControl<Button>("CancelButton")!.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

        Assert.False(window.IsVisible);
        Assert.True(window.Cancelled);
        Assert.Equal("123456", window.Code);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task APairingThatEndsClosesItWithoutCancelling() => Run("PowerPoint", "123456", InTwoMinutes, window =>
    {
        window.EndQuietly(); // paired, or replaced by a newer pairing

        Assert.False(window.IsVisible);
        Assert.False(window.Cancelled);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task ItClosesWhenTheCodeExpires() => Run("Word", "123456", DateTimeOffset.UtcNow.AddMilliseconds(200), async window =>
    {
        Assert.True(window.IsVisible);

        await Until(() => !window.IsVisible);

        Assert.False(window.Cancelled);
    });
}
