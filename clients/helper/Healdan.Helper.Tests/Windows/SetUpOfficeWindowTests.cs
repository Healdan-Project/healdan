using System.Security.Cryptography.X509Certificates;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// Set Up Microsoft Office's window (M3.5 spec §5.13), as the user's clicks drive it, over the helper's own look. Like the
/// app's other tests they are for a Mac: Skia's text is laid out with San Francisco's fonts, and nothing has started
/// Skia's headless session on Linux.
/// </summary>
public sealed class SetUpOfficeWindowTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-setup-window-").FullName;
    private readonly FakeMac _mac = new();

    public void Dispose() => Directory.Delete(_root, true);

    /// <summary>Makes the window and shows it on the UI thread, runs <paramref name="test"/> there, and closes it.</summary>
    private Task Run(Func<SetUpOfficeWindow, Task> test, Action? trusted = null) => Headless.RunAsync(async () =>
    {
        var paths = HelperPaths.For(_root);
        var setUp = new OfficeSetUp(
            paths, new AddinCertificate(paths, TimeProvider.System), _mac, () => new InstalledApps(true, Word: true, Excel: true, PowerPoint: true), macOS: true);
        var window = new SetUpOfficeWindow(setUp, new HelperLog(paths.Log), trusted);
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

    private static T Find<T>(Window window, string name) where T : Control => window.FindControl<T>(name)!;

    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    private static async Task Until(Func<bool> condition)
    {
        var until = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < until, "It didn't happen within 15 seconds.");
            await Task.Delay(10);
        }
    }

    [MacFact]
    public Task SaysWhatItWillAskForAndWhy() => Run(window =>
    {
        var texts = window.GetVisualDescendants().OfType<TextBlock>().Select(t => t.Text).ToList();

        Assert.Equal("Set Up Microsoft Office", window.Title);
        Assert.Contains("Set Up Microsoft Office", texts);
        Assert.Contains("Trust Healdan Helper's certificate", texts);
        Assert.Contains("macOS asks for your password once. Word, Excel and PowerPoint use it to reach Healdan Helper on this Mac.", texts);
        Assert.Contains("Allow Healdan Helper to control each app", texts);
        Assert.Contains("The first time it saves a document in Word, Excel or PowerPoint, macOS asks. Choose Allow.", texts);
        Assert.Contains("Grant each app the Copies folder", texts);
        Assert.Contains("The first time an app saves into Healdan Helper's Copies folder, it asks. Click Select…, go up one level, select Copies, and click Grant Access.", texts);
        Assert.Equal("Not Now", Find<Button>(window, "NotNowButton").Content);
        var setUp = Find<Button>(window, "SetUpButton");
        Assert.Equal("Set Up", setUp.Content);
        Assert.True(setUp.IsDefault);
        Assert.False(Find<StackPanel>(window, "DoneMark").IsVisible);
        Assert.False(Find<TextBlock>(window, "ProblemText").IsVisible);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task SetUpTrustsTheCertificateAndShowsATick() => Run(async window =>
    {
        Click(Find<Button>(window, "SetUpButton"));
        await Until(() => window.Trusted);

        Assert.Equal([HelperPaths.For(_root).AddinCertificate], _mac.Trusting);
        Assert.True(Find<StackPanel>(window, "DoneMark").IsVisible);
        Assert.False(Find<TextBlock>(window, "ProblemText").IsVisible);
        Assert.Equal("Done", Find<Button>(window, "SetUpButton").Content);
        Assert.False(Find<Button>(window, "NotNowButton").IsVisible);

        Click(Find<Button>(window, "SetUpButton")); // Done closes it
        Assert.False(window.IsVisible);
        Assert.True(window.Trusted);
    });

    [MacFact]
    public Task TrustingCallsBackAtOnceWithTheWindowStillOpen()
    {
        // The owner's walkthrough (2026-10-31): the add-in's port opened only once the window closed, and it was left open
        // on Done while Word tried to reach the helper.
        var calls = 0;
        return Run(async window =>
        {
            Click(Find<Button>(window, "SetUpButton"));
            await Until(() => window.Trusted);

            Assert.Equal(1, calls);
            Assert.True(window.IsVisible);
            Click(Find<Button>(window, "SetUpButton")); // Done
            Assert.Equal(1, calls);
        }, trusted: () => calls++);
    }

    [MacFact]
    public Task ACancelledPasswordPromptSaysSoAndCanBeTriedAgain() => Run(async window =>
    {
        _mac.OnTrust = _ => Task.FromResult(false);
        Click(Find<Button>(window, "SetUpButton"));
        await Until(() => Find<TextBlock>(window, "ProblemText").IsVisible);

        Assert.Contains("password prompt was cancelled", Find<TextBlock>(window, "ProblemText").Text);
        Assert.False(window.Trusted);
        Assert.False(Find<StackPanel>(window, "DoneMark").IsVisible);
        var setUp = Find<Button>(window, "SetUpButton");
        Assert.Equal("Set Up", setUp.Content);
        Assert.True(setUp.IsEnabled);

        _mac.OnTrust = _ => Task.FromResult(true); // the second try
        Click(setUp);
        await Until(() => window.Trusted);
        Assert.False(Find<TextBlock>(window, "ProblemText").IsVisible);
    });

    [MacFact]
    public Task AFailureShowsWhyAndNoTick() => Run(async window =>
    {
        _mac.OnTrust = _ => throw new InvalidOperationException("One or more parameters passed to a function were not valid.");
        Click(Find<Button>(window, "SetUpButton"));
        await Until(() => Find<TextBlock>(window, "ProblemText").IsVisible);

        Assert.Equal("Set Up failed: One or more parameters passed to a function were not valid.", Find<TextBlock>(window, "ProblemText").Text);
        Assert.False(window.Trusted);
        Assert.False(Find<StackPanel>(window, "DoneMark").IsVisible);
        Assert.True(Find<Button>(window, "SetUpButton").IsEnabled);
    });

    [MacFact]
    public Task NotNowAsksNothingAndLeavesItForTheMenu()
    {
        var calls = 0;
        return Run(window =>
        {
            Click(Find<Button>(window, "NotNowButton"));

            Assert.False(window.IsVisible);
            Assert.False(window.Trusted);
            Assert.Empty(_mac.Trusting);
            Assert.Equal(0, calls);
            return Task.CompletedTask;
        }, trusted: () => calls++);
    }

    [MacFact]
    public Task ClosingWhileMacOSAsksStopsTheAsk() => Run(async window =>
    {
        var asking = new TaskCompletionSource();
        var stopped = new TaskCompletionSource();
        _mac.OnTrust = async ct =>
        {
            ct.Register(() => stopped.TrySetResult());
            asking.TrySetResult();
            await Task.Delay(Timeout.Infinite, ct);
            return true;
        };
        Click(Find<Button>(window, "SetUpButton"));
        await asking.Task.WaitAsync(TimeSpan.FromSeconds(15));
        Assert.False(Find<Button>(window, "SetUpButton").IsEnabled); // busy while it asks

        window.Close();

        await stopped.Task.WaitAsync(TimeSpan.FromSeconds(15));
        Assert.False(window.Trusted);
    });

    /// <summary>The Mac: the user's answer to the password prompt is whatever the test says, and a trusted certificate stays trusted.</summary>
    private sealed class FakeMac : ICertificateTrust
    {
        private readonly HashSet<string> _trusted = [];

        public Func<CancellationToken, Task<bool>> OnTrust { get; set; } = _ => Task.FromResult(true);

        public List<string> Trusting { get; } = [];

        public bool IsTrusted(X509Certificate2 cert) => _trusted.Contains(cert.Thumbprint);

        public async Task<bool> TrustAsync(string certPemPath, CancellationToken ct)
        {
            Trusting.Add(certPemPath);
            var accepted = await OnTrust(ct);
            if (accepted)
                _trusted.Add(X509Certificate2.CreateFromPem(File.ReadAllText(certPemPath)).Thumbprint);
            return accepted;
        }
    }
}
