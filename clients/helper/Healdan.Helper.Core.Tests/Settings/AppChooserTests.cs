using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>
/// Which app a new copy opens in (M3.5 spec §5.10): the user's choice for its kind, else the system's default for the
/// kind's main format when that's one of the two, else LibreOffice, and only an app that's installed.
/// </summary>
public sealed class AppChooserTests
{
    private static readonly InstalledApps Everything = new(LibreOffice: true, Word: true, Excel: true, PowerPoint: true);

    private static HelperSettings Choosing(string kind, string app) => new() { Kinds = new Dictionary<string, string> { [kind] = app } };

    private static AppChooser Chooser(HelperSettings? settings = null, InstalledApps? installed = null, Func<string, string?>? systemDefault = null) =>
        new(settings ?? new HelperSettings(), installed ?? Everything, systemDefault ?? (_ => null));

    [Theory]
    [InlineData("a.odt")]
    [InlineData("a.ott")]
    [InlineData("a.ods")]
    [InlineData("a.odp")]
    [InlineData("a.odg")]
    public void OpenDocumentFilesStayInLibreOffice(string fileName)
    {
        var settings = new HelperSettings
        {
            Kinds = new Dictionary<string, string>
            {
                ["Documents"] = "Microsoft Office",
                ["Spreadsheets"] = "Microsoft Office",
                ["Presentations"] = "Microsoft Office",
                ["Drawings"] = "Microsoft Office"
            }
        };

        Assert.Equal("LibreOffice", Chooser(settings).For(fileName));
    }

    [Theory]
    [InlineData("a.docx", "Documents")]
    [InlineData("a.doc", "Documents")]
    [InlineData("a.RTF", "Documents")]
    [InlineData("a.xlsx", "Spreadsheets")]
    [InlineData("a.xls", "Spreadsheets")]
    [InlineData("a.pptx", "Presentations")]
    [InlineData("a.ppt", "Presentations")]
    public void TheChoiceAppliesToMicrosoftFormats(string fileName, string kind)
    {
        Assert.Equal("Microsoft Office", Chooser(Choosing(kind, "Microsoft Office")).For(fileName));
        Assert.Equal("LibreOffice", Chooser(Choosing(kind, "LibreOffice"), systemDefault: _ => "Microsoft Office").For(fileName));
    }

    [Fact]
    public void AChoiceIsForItsOwnKindOnly()
    {
        var chooser = Chooser(Choosing("Documents", "Microsoft Office"));

        Assert.Equal("Microsoft Office", chooser.For("a.docx"));
        Assert.Equal("LibreOffice", chooser.For("a.xlsx"));
        Assert.Equal("LibreOffice", chooser.For("a.pptx"));
    }

    [Fact]
    public void TheDefaultIsTheSystemsWhenItsOneOfTheTwo()
    {
        var asked = new List<string>();

        // Word opens .docx on this Mac; the other kinds' main formats are asked about too.
        string? SystemDefault(string extension)
        {
            asked.Add(extension);
            return extension == "docx" ? "Microsoft Office" : null;
        }

        var chooser = Chooser(systemDefault: SystemDefault);
        Assert.Equal("Microsoft Office", chooser.For("a.docx"));
        Assert.Equal("Microsoft Office", chooser.For("a.doc")); // the kind's main format, not the file's own
        Assert.Equal("LibreOffice", chooser.For("a.xlsx"));
        Assert.Equal("LibreOffice", chooser.For("a.odg"));
        Assert.Equal(["docx", "docx", "xlsx"], asked); // an extension, without its dot; Drawings has no main format
    }

    [Theory]
    [InlineData(null)]
    [InlineData("Pages")]
    [InlineData("LibreOffice")]
    public void AnythingElseAsTheSystemsDefaultIsLibreOffice(string? systemDefault) =>
        Assert.Equal("LibreOffice", Chooser(systemDefault: _ => systemDefault).For("a.docx"));

    [Fact]
    public void OnlyWhatsInstalled()
    {
        var noWord = Everything with { Word = false };
        Assert.Equal("LibreOffice", Chooser(Choosing("Documents", "Microsoft Office"), noWord).For("a.docx"));
        Assert.Equal("LibreOffice", Chooser(installed: noWord, systemDefault: _ => "Microsoft Office").For("a.docx"));
        // The other kinds' programs are looked at on their own.
        Assert.Equal("Microsoft Office", Chooser(Choosing("Spreadsheets", "Microsoft Office"), noWord).For("a.xlsx"));

        var noLibreOffice = Everything with { LibreOffice = false };
        Assert.Equal("Microsoft Office", Chooser(Choosing("Documents", "LibreOffice"), noLibreOffice).For("a.docx"));
        Assert.Equal("Microsoft Office", Chooser(installed: noLibreOffice).For("a.xlsx"));
        // Word can open an OpenDocument text file when LibreOffice isn't there; nothing but LibreOffice opens a drawing.
        Assert.Equal("Microsoft Office", Chooser(installed: noLibreOffice).For("a.odt"));
        Assert.Equal("Microsoft Office", Chooser(installed: noLibreOffice).For("a.ods"));
        Assert.Equal("Microsoft Office", Chooser(installed: noLibreOffice).For("a.odp"));
        Assert.Equal("Default", Chooser(installed: noLibreOffice).For("a.odg"));
        Assert.Equal("Default", Chooser(installed: noLibreOffice with { Word = false }).For("a.odt"));
        Assert.Equal("Microsoft Office", Chooser(installed: noLibreOffice with { Word = false }).For("a.ods"));

        var nothing = new InstalledApps(false, false, false, false);
        Assert.Equal("Default", Chooser(installed: nothing).For("a.docx"));
        Assert.Equal("Default", Chooser(Choosing("Documents", "Microsoft Office"), nothing).For("a.docx"));
    }

    [Theory]
    [InlineData("a.pdf")]
    [InlineData("a.vsdx")]
    [InlineData("a.command")]
    [InlineData("docx")]
    [InlineData("a")]
    public void OtherFilesAreTheDefaultApp(string fileName) =>
        Assert.Equal("Default", Chooser().For(fileName));

    [Fact]
    public void AnUnknownChoiceIsTheDefaultChoice()
    {
        Assert.Equal("LibreOffice", Chooser(Choosing("Documents", "Pages")).For("a.docx"));
        Assert.Equal("Microsoft Office", Chooser(Choosing("Documents", "Pages"), systemDefault: _ => "Microsoft Office").For("a.docx"));
    }

    [Fact]
    public void TheSystemDefaultComesFromDefaultApps()
    {
        // What LaunchServices says opens each extension, by its bundle identifier: DefaultApps.For names the app by it.
        var opens = new Dictionary<string, string> { ["docx"] = "com.microsoft.Word", ["xlsx"] = "com.apple.iWork.Numbers", ["pptx"] = "org.libreoffice.script" };
        string? SystemDefault(string extension) => AppBundles.NameOf(opens.GetValueOrDefault(extension));

        var chooser = Chooser(systemDefault: SystemDefault);
        Assert.Equal("Microsoft Office", chooser.For("a.docx")); // a fresh install: no choice yet
        Assert.Equal("LibreOffice", chooser.For("a.xlsx")); // Numbers is neither
        Assert.Equal("LibreOffice", chooser.For("a.pptx"));
        Assert.Equal("LibreOffice", chooser.For("a.odg"));

        opens["docx"] = "com.apple.iWork.Pages"; // the Mac's default for .docx changes
        Assert.Equal("LibreOffice", chooser.For("a.docx"));
        Assert.Equal("Microsoft Office", Chooser(Choosing("Documents", "Microsoft Office"), systemDefault: SystemDefault).For("a.docx")); // a choice wins
    }

    [Fact]
    public void SettingsOfferOnlyWhatsInstalledForEachKind()
    {
        var chooser = Chooser();
        Assert.Equal(["LibreOffice", "Microsoft Office"], chooser.Offered(FileKinds.Documents));
        Assert.Equal(["LibreOffice", "Microsoft Office"], chooser.Offered(FileKinds.Spreadsheets));
        Assert.Equal(["LibreOffice", "Microsoft Office"], chooser.Offered(FileKinds.Presentations));
        Assert.Equal(["LibreOffice"], chooser.Offered(FileKinds.Drawings)); // Office doesn't open them

        var noWord = Chooser(installed: Everything with { Word = false });
        Assert.Equal(["LibreOffice"], noWord.Offered(FileKinds.Documents)); // each kind's program on its own
        Assert.Equal(["LibreOffice", "Microsoft Office"], noWord.Offered(FileKinds.Spreadsheets));

        var noLibreOffice = Chooser(installed: Everything with { LibreOffice = false });
        Assert.Equal(["Microsoft Office"], noLibreOffice.Offered(FileKinds.Documents));
        Assert.Empty(noLibreOffice.Offered(FileKinds.Drawings));

        Assert.Empty(Chooser(installed: new InstalledApps(false, false, false, false)).Offered(FileKinds.Documents));
    }

    [Fact]
    public void ForKindIsWhatTheKindsFilesOpenIn()
    {
        var chooser = Chooser(Choosing("Spreadsheets", "Microsoft Office"), systemDefault: _ => null);

        Assert.Equal("LibreOffice", chooser.ForKind(FileKinds.Documents));
        Assert.Equal("Microsoft Office", chooser.ForKind(FileKinds.Spreadsheets));
        Assert.Equal("LibreOffice", chooser.ForKind(FileKinds.Drawings));
        Assert.Equal("Default", Chooser(installed: new InstalledApps(false, false, false, false)).ForKind(FileKinds.Documents));
    }
}
