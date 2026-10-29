using Avalonia;
using Avalonia.Headless;
using Healdan.Helper.Platform;

namespace Healdan.Helper.Tests;

/// <summary>
/// Avalonia without a display, for the tests: Skia's own drawing rather than the headless stub's, and the helper's text
/// shaper, as <c>Program.BuildAvaloniaApp</c> adds it. One session serves the whole run, as Avalonia sets up once.
/// </summary>
public static class Headless
{
    private static readonly Lazy<HeadlessUnitTestSession> Session = new(() => HeadlessUnitTestSession.StartNew(typeof(Headless)));

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<Application>()
            .UseSkia()
            .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
            .UseSystemFontShaper();

    /// <summary>Runs <paramref name="action"/> on the session's UI thread.</summary>
    public static T Run<T>(Func<T> action) => Session.Value.Dispatch(action, CancellationToken.None).GetAwaiter().GetResult();
}
