using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// The apps the cycle chooses between (M3.5 spec §5.10): a new copy opens in the app <paramref name="appFor"/> names for
/// its file (the user's choice for its kind, installed apps only), and an existing copy stays with the app its record
/// names, whatever Settings say now. An app that isn't in the set, or a name nothing knows, is LibreOffice: it was the
/// only app before Microsoft Office, so a record from then names it, and a helper without Microsoft Office (Linux) or
/// without a default app opens everything there. CoffeePDF (M3.6 spec §4.3) is in the set whatever its availability, so a
/// copy opened in it stays its.
/// </summary>
/// <param name="libreOffice">LibreOffice, the app for anything else.</param>
/// <param name="microsoftOffice">Microsoft Office (<see cref="Office.MicrosoftOfficeApp"/>); null where it can't be installed.</param>
/// <param name="otherFiles">The app for files no office app edits (<see cref="DefaultApp"/>); without one, everything opens in an office app.</param>
/// <param name="appFor">The name of the app a new copy of a file opens in: <see cref="Name"/>s of the apps above.</param>
/// <param name="coffeePdf">CoffeePDF (<see cref="CoffeePdfApp"/>); null where it can't be installed.</param>
public sealed class OfficeApps(IOfficeApp libreOffice, IOfficeApp? microsoftOffice, IOfficeApp? otherFiles, Func<string, string> appFor,
    IOfficeApp? coffeePdf = null)
{
    /// <summary>LibreOffice, which anything unknown opens in.</summary>
    public IOfficeApp LibreOffice => libreOffice;

    /// <summary>
    /// Microsoft Office, whose windows Save to Healdan files from (<see cref="Office.OfficeFiling"/>); null where it can't
    /// be installed.
    /// </summary>
    public IOfficeApp? MicrosoftOffice => microsoftOffice;

    /// <summary>CoffeePDF, whatever its availability now; null where it can't be installed.</summary>
    public IOfficeApp? CoffeePdf => coffeePdf;

    /// <summary>Every app in the set, LibreOffice first.</summary>
    public IReadOnlyList<IOfficeApp> All { get; } =
        new[] { libreOffice, microsoftOffice, coffeePdf, otherFiles }.OfType<IOfficeApp>().ToList();

    /// <summary>The app for a new copy of a file: the chooser's answer.</summary>
    public IOfficeApp For(string fileName) => Named(appFor(fileName));

    /// <summary>
    /// The app whose window a document filed through the channel's <c>/v1/file</c> is in, by the <c>app</c> its caller names
    /// (M3.6 spec §6.2): CoffeePDF for <c>CoffeePDF</c>, and LibreOffice, the extension's, for anything else or nothing.
    /// </summary>
    public IOfficeApp Filing(string? name) => name == CoffeePdfApp.AppName && coffeePdf is not null ? coffeePdf : libreOffice;

    /// <summary>The app an existing copy was opened in, by its record.</summary>
    public IOfficeApp Of(CopyRecord record) => Named(record.App);

    /// <summary>Whether this is the app for files no office app edits: they open read-only, with no check-out.</summary>
    public bool IsOtherFiles(IOfficeApp app) => otherFiles is not null && ReferenceEquals(app, otherFiles);

    private IOfficeApp Named(string name) => All.FirstOrDefault(a => a.Name == name) ?? libreOffice;
}
