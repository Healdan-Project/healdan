using System.Security.Cryptography.X509Certificates;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Core;
using Healdan.Helper.Core.Addin;
using Healdan.Helper.Core.Security;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Platform;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// Settings (M3.5 spec §5.10), as the user's clicks drive them, over the helper's own look: an app for each kind of file out
/// of what's installed, and the Microsoft Office group with Set Up and the paired apps. Like the app's other window tests
/// they are for a Mac.
/// </summary>
public sealed class SettingsWindowTests : IDisposable
{
    private static readonly InstalledApps Everything = new(LibreOffice: true, Word: true, Excel: true, PowerPoint: true);
    private static readonly InstalledApps OnlyLibreOffice = new(LibreOffice: true, Word: false, Excel: false, PowerPoint: false);
    private static readonly DateTimeOffset Paired = new(2026, 10, 29, 14, 30, 0, TimeSpan.Zero);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-settings-window-").FullName;
    private readonly FakeMac _mac = new();
    private readonly List<string> _forgotten = [];

    public void Dispose() => Directory.Delete(_root, true);

    private HelperPaths Paths => HelperPaths.For(_root);

    /// <summary>
    /// Makes the window over these apps and shows it on the UI thread, runs <paramref name="test"/> there, and closes it.
    /// <paramref name="paired"/> is what the helper has paired; Forget takes it off the list, as the helper's does.
    /// </summary>
    private Task Run(Func<SettingsWindow, Task> test, InstalledApps? installed = null, Func<string, string?>? systemDefault = null, List<PairedApp>? paired = null,
        Func<Task<bool>>? runSetUp = null, bool setUpAlready = false) => Headless.RunAsync(async () =>
    {
        var apps = installed ?? Everything;
        var setUp = new OfficeSetUp(Paths, new AddinCertificate(Paths, TimeProvider.System), _mac, () => apps, macOS: true);
        if (setUpAlready)
            Assert.True((await setUp.TrustAsync(CancellationToken.None)).Trusted);
        var pairedNow = paired ?? [];
        var office = new OfficeSettings(
            setUp,
            runSetUp ?? (async () => (await setUp.TrustAsync(CancellationToken.None)).Trusted),
            () => [.. pairedNow],
            app =>
            {
                _forgotten.Add(app);
                pairedNow.RemoveAll(p => p.App == app);
            });
        var chooser = new AppChooser(HelperSettings.Load(Paths), apps, systemDefault ?? (_ => null));
        var window = new SettingsWindow("healdan.example", signedIn: true, Paths, chooser, new LoginItemSummary("On", true, null), extension: null, office);
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

    private static List<string> Texts(Control from) =>
        [.. from.GetVisualDescendants().OfType<TextBlock>().Where(t => t.IsEffectivelyVisible).Select(t => t.Text ?? "")];

    /// <summary>The pop-up beside a kind, or null while the kind has nothing to choose between.</summary>
    private static ComboBox? PopUp(Window window, string kind) =>
        window.GetVisualDescendants().OfType<ComboBox>().SingleOrDefault(c => AutomationProperties.GetName(c) == $"{kind} Open In");

    /// <summary>A kind's row, whatever it shows for the app.</summary>
    private static Grid RowOf(Window window, string kind) =>
        window.GetVisualDescendants().OfType<TextBlock>().Single(t => t.Text == kind).GetVisualAncestors().OfType<Grid>().First();

    private static string[] Items(ComboBox box) => [.. box.Items.Cast<string>()];

    [MacFact]
    public Task EachKindHasAPopUpOfTheInstalledAppsShowingTheDefault() => Run(window =>
    {
        foreach (var kind in new[] { "Documents", "Spreadsheets", "Presentations" })
        {
            var box = PopUp(window, kind)!;
            Assert.Equal(["LibreOffice", "Microsoft Office"], Items(box));
            Assert.Equal("LibreOffice", box.SelectedItem); // nothing says otherwise
        }
        return Task.CompletedTask;
    });

    [MacFact]
    public Task TheSystemsDefaultIsWhatItShows() => Run(window =>
    {
        Assert.Equal("Microsoft Office", PopUp(window, "Documents")!.SelectedItem); // Word opens .docx
        Assert.Equal("LibreOffice", PopUp(window, "Spreadsheets")!.SelectedItem);
        return Task.CompletedTask;
    }, systemDefault: extension => extension == "docx" ? "Microsoft Office" : null);

    [MacFact]
    public Task AnEarlierChoiceIsWhatItShows()
    {
        HelperSettings.Update(Paths, s => s.Kinds = new Dictionary<string, string> { ["Presentations"] = "Microsoft Office" });
        return Run(window =>
        {
            Assert.Equal("Microsoft Office", PopUp(window, "Presentations")!.SelectedItem);
            Assert.Equal("LibreOffice", PopUp(window, "Documents")!.SelectedItem);
            return Task.CompletedTask;
        });
    }

    [MacFact]
    public Task DrawingsShowLibreOfficeOnlyAndNothingToChoose() => Run(window =>
    {
        Assert.Null(PopUp(window, "Drawings"));
        var drawings = RowOf(window, "Drawings");
        Assert.Contains("LibreOffice", Texts(drawings));
        Assert.DoesNotContain("Microsoft Office", Texts(drawings));
        return Task.CompletedTask;
    });

    [MacFact]
    public Task ChoosingAnAppWritesThatKindsChoice() => Run(window =>
    {
        PopUp(window, "Spreadsheets")!.SelectedItem = "Microsoft Office";

        var chosen = HelperSettings.Load(Paths);
        Assert.Equal("Microsoft Office", chosen.ChosenApp("Spreadsheets"));
        Assert.Null(chosen.ChosenApp("Documents"));

        PopUp(window, "Documents")!.SelectedItem = "Microsoft Office";
        PopUp(window, "Spreadsheets")!.SelectedItem = "LibreOffice"; // changed back, which is a choice too
        chosen = HelperSettings.Load(Paths);
        Assert.Equal("LibreOffice", chosen.ChosenApp("Spreadsheets"));
        Assert.Equal("Microsoft Office", chosen.ChosenApp("Documents"));
        Assert.Null(chosen.ChosenApp("Presentations"));
        return Task.CompletedTask;
    });

    [MacFact]
    public Task OpeningSettingsChoosesNothing() => Run(window =>
    {
        Assert.False(File.Exists(Paths.Settings));
        return Task.CompletedTask;
    });

    [MacFact]
    public Task OnlyWhatsInstalledIsOffered() => Run(window =>
    {
        Assert.Null(PopUp(window, "Documents")); // Word isn't there, so there's nothing to choose: LibreOffice, as text
        Assert.Contains("LibreOffice", Texts(RowOf(window, "Documents")));
        Assert.Equal(["LibreOffice", "Microsoft Office"], Items(PopUp(window, "Spreadsheets")!)); // Excel is, and each program is looked at on its own
        return Task.CompletedTask;
    }, installed: Everything with { Word = false }, systemDefault: _ => "Microsoft Office");

    [MacFact]
    public Task WithOnlyLibreOfficeThereIsNothingToChoose() => Run(window =>
    {
        Assert.Empty(window.GetVisualDescendants().OfType<ComboBox>());
        Assert.Equal(4, Texts(window).Count(t => t == "LibreOffice")); // one for each kind
        return Task.CompletedTask;
    }, installed: OnlyLibreOffice);

    [MacFact]
    public Task TheFootnoteSaysOpenDocumentFilesStayInLibreOffice() => Run(window =>
    {
        Assert.Contains(Texts(window), t => t.StartsWith("OpenDocument files open in LibreOffice whatever you choose", StringComparison.Ordinal));
        Assert.Contains(Texts(window), t => t.Contains("Other files open read-only in the computer's default app.", StringComparison.Ordinal));
        return Task.CompletedTask;
    });

    [MacFact]
    public Task TheMicrosoftOfficeGroupOffersSetUpUntilItsDone() => Run(async window =>
    {
        var group = Find<Border>(window, "OfficeGroup");
        Assert.True(group.IsVisible);
        Assert.True(Find<TextBlock>(window, "OfficeHeader").IsVisible);
        Assert.Equal("Needed", Find<TextBlock>(window, "SetUpValue").Text);
        var button = Find<Button>(window, "SetUpButton");
        Assert.True(button.IsVisible);
        Assert.Equal("Set Up…", button.Content);

        Click(button);
        await Until(() => !button.IsVisible);

        Assert.Equal("Done", Find<TextBlock>(window, "SetUpValue").Text);
        Assert.Single(_mac.Trusting);
    });

    [MacFact]
    public Task ACancelledSetUpLeavesItNeeded() => Run(async window =>
    {
        var button = Find<Button>(window, "SetUpButton");
        Click(button);
        await Until(() => button.IsEnabled);

        Assert.True(button.IsVisible);
        Assert.Equal("Needed", Find<TextBlock>(window, "SetUpValue").Text);
    }, runSetUp: () => Task.FromResult(false));

    [MacFact]
    public Task WhenTheMacTrustsTheCertificateAlreadySetUpIsDone() => Run(window =>
    {
        Assert.Equal("Done", Find<TextBlock>(window, "SetUpValue").Text);
        Assert.False(Find<Button>(window, "SetUpButton").IsVisible);
        Assert.True(Find<Grid>(window, "SetUpRow").IsVisible);
        return Task.CompletedTask;
    }, setUpAlready: true);

    [MacFact]
    public Task NoOfficeAndNothingPairedLeavesTheGroupOut() => Run(window =>
    {
        Assert.False(Find<Border>(window, "OfficeGroup").IsVisible);
        Assert.False(Find<TextBlock>(window, "OfficeHeader").IsVisible);
        return Task.CompletedTask;
    }, installed: OnlyLibreOffice);

    [MacFact]
    public Task NoAppPairedSaysSo() => Run(window =>
    {
        Assert.True(Find<TextBlock>(window, "PairedNone").IsVisible);
        Assert.Equal("None", Find<TextBlock>(window, "PairedNone").Text);
        Assert.Empty(Find<StackPanel>(window, "PairedList").Children);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task EachPairedAppShowsWhenItPairedWithForget() => Run(window =>
    {
        Assert.False(Find<TextBlock>(window, "PairedNone").IsVisible);
        var list = Find<StackPanel>(window, "PairedList");
        Assert.Equal(2, list.Children.Count);
        var texts = Texts(list);
        Assert.Contains("Word", texts);
        Assert.Contains("PowerPoint", texts);
        Assert.Contains($"Paired on {Paired.ToLocalTime():d MMMM yyyy}", texts);
        var forgets = list.GetVisualDescendants().OfType<Button>().ToList();
        Assert.Equal(["Forget", "Forget"], forgets.Select(b => b.Content));
        Assert.Equal(["Forget Word", "Forget PowerPoint"], forgets.Select(AutomationProperties.GetName));
        return Task.CompletedTask;
    }, paired: [new PairedApp("Word", Paired), new PairedApp("PowerPoint", Paired)]);

    [MacFact]
    public Task ForgetMakesTheHelperForgetThatAppAndTakesItOffTheList() => Run(window =>
    {
        var list = Find<StackPanel>(window, "PairedList");
        var forgetWord = list.GetVisualDescendants().OfType<Button>().First(b => AutomationProperties.GetName(b) == "Forget Word");

        Click(forgetWord);

        Assert.Equal(["Word"], _forgotten);
        Assert.Single(list.Children);
        Assert.DoesNotContain("Word", Texts(list));
        Assert.Contains("PowerPoint", Texts(list));
        Assert.False(Find<TextBlock>(window, "PairedNone").IsVisible);

        Click(list.GetVisualDescendants().OfType<Button>().Single());
        Assert.Equal(["Word", "PowerPoint"], _forgotten);
        Assert.Empty(list.Children);
        Assert.True(Find<TextBlock>(window, "PairedNone").IsVisible);
        return Task.CompletedTask;
    }, paired: [new PairedApp("Word", Paired), new PairedApp("PowerPoint", Paired)]);

    [MacFact]
    public Task AppsPairedWithoutOfficeInstalledStillShowWithoutSetUp() => Run(window =>
    {
        Assert.True(Find<Border>(window, "OfficeGroup").IsVisible);
        Assert.False(Find<Grid>(window, "SetUpRow").IsVisible); // nothing to set up on a Mac without Office
        Assert.DoesNotContain("divided", Find<Border>(window, "PairedSection").Classes); // so no line above the paired apps
        Assert.Single(Find<StackPanel>(window, "PairedList").Children);
        return Task.CompletedTask;
    }, installed: OnlyLibreOffice, paired: [new PairedApp("Excel", Paired)]);

    private static async Task Until(Func<bool> condition)
    {
        var until = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < until, "It didn't happen within 15 seconds.");
            await Task.Delay(10);
        }
    }

    /// <summary>The Mac: whatever the test says to the password prompt, and a trusted certificate stays trusted.</summary>
    private sealed class FakeMac : ICertificateTrust
    {
        private readonly HashSet<string> _trusted = [];

        public List<string> Trusting { get; } = [];

        public bool IsTrusted(X509Certificate2 cert) => _trusted.Contains(cert.Thumbprint);

        public Task<bool> TrustAsync(string certPemPath, CancellationToken ct)
        {
            Trusting.Add(certPemPath);
            _trusted.Add(X509Certificate2.CreateFromPem(File.ReadAllText(certPemPath)).Thumbprint);
            return Task.FromResult(true);
        }
    }
}
