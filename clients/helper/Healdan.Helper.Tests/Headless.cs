using Avalonia;
using Avalonia.Headless;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Tests;

/// <summary>
/// Avalonia without a display, for the tests: Skia's own drawing rather than the headless stub's, and the helper's text
/// shaper, as <c>Program.BuildAvaloniaApp</c> adds it, and the helper's look (Fluent and <c>HelperStyles.axaml</c>, as
/// <c>App.axaml</c> loads them) so a window made here is the one the user sees. One session serves the whole run, as
/// Avalonia sets up once.
/// </summary>
public static class Headless
{
    private static readonly Lazy<HeadlessUnitTestSession> Session = new(() => HeadlessUnitTestSession.StartNew(typeof(Headless)));

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<Application>()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .UseSystemFontShaper()
            .AfterSetup(builder =>
            {
                var styles = builder.Instance!.Styles;
                styles.Add(new FluentTheme { DensityStyle = DensityStyle.Compact });
                styles.Add(new StyleInclude(new Uri("avares://HealdanHelper/")) { Source = new Uri("avares://HealdanHelper/Windows/HelperStyles.axaml") });
            });

    /// <summary>Runs <paramref name="action"/> on the session's UI thread.</summary>
    public static T Run<T>(Func<T> action) => Session.Value.Dispatch(action, CancellationToken.None).GetAwaiter().GetResult();

    /// <summary>Runs <paramref name="action"/> on the session's UI thread, where what it awaits comes back to, as a window's handlers do.</summary>
    public static Task<T> RunAsync<T>(Func<Task<T>> action) => Session.Value.Dispatch(action, CancellationToken.None);
}
