using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Office;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>The apps a cycle chooses between: a new copy's app comes from the chooser, an existing copy's from its record.</summary>
public sealed class OfficeAppsTests
{
    private readonly FakeApp _libreOffice = new();
    private readonly FakeOpener _other = new();
    private readonly MicrosoftOfficeApp _microsoft = new(new FakeScripting(), _ => false);

    private static CopyRecord Record(string app) =>
        new(5, "Lease.docx", "1.0", "1.0", false, true, null, null, app, null, "Lease #5 v1.0.docx");

    [Fact]
    public void ARecordKeepsItsApp()
    {
        var settings = new HelperSettings { Kinds = new Dictionary<string, string> { ["Documents"] = "Microsoft Office" } };
        var chooser = new AppChooser(settings, new InstalledApps(true, true, true, true), _ => null);
        var apps = new OfficeApps(_libreOffice, _microsoft, _other, chooser.For);
        Assert.Same(_microsoft, apps.For("Lease.docx"));
        var record = Record("Microsoft Office");

        // The user turns to LibreOffice for Documents; the copy open in Word is still Word's.
        settings.Kinds = new Dictionary<string, string> { ["Documents"] = "LibreOffice" };

        Assert.Same(_libreOffice, apps.For("Lease.docx"));
        Assert.Same(_microsoft, apps.Of(record));
        Assert.Same(_libreOffice, apps.Of(Record("LibreOffice")));
        Assert.Same(_other, apps.Of(Record("Default")));
    }

    [Fact]
    public void ANewCopysAppIsTheChoosersAnswer()
    {
        var apps = new OfficeApps(_libreOffice, _microsoft, _other, name => name.EndsWith(".pdf") ? "Default" : name.EndsWith(".docx") ? "Microsoft Office" : "LibreOffice");

        Assert.Same(_microsoft, apps.For("a.docx"));
        Assert.Same(_libreOffice, apps.For("a.odt"));
        Assert.Same(_other, apps.For("a.pdf"));
    }

    [Fact]
    public void AnUnknownNameIsLibreOffice()
    {
        var apps = new OfficeApps(_libreOffice, _microsoft, _other, _ => "Pages");

        Assert.Same(_libreOffice, apps.For("a.docx"));
        Assert.Same(_libreOffice, apps.Of(Record("Pages")));
    }

    [Fact]
    public void AnAppThatsNotThereFallsBackToLibreOffice()
    {
        // Linux has no Microsoft Office, and a helper may have no default app: those names mean LibreOffice there.
        var apps = new OfficeApps(_libreOffice, null, null, name => name.EndsWith(".pdf") ? "Default" : "Microsoft Office");

        Assert.Same(_libreOffice, apps.For("a.docx"));
        Assert.Same(_libreOffice, apps.For("a.pdf"));
        Assert.Same(_libreOffice, apps.Of(Record("Microsoft Office")));
        Assert.Same(_libreOffice, apps.Of(Record("Default")));
        Assert.Equal([_libreOffice], apps.All);
    }

    [Fact]
    public void AllHoldsEveryAppThereIs()
    {
        Assert.Equal<IOfficeApp>([_libreOffice, _microsoft, _other], new OfficeApps(_libreOffice, _microsoft, _other, _ => "LibreOffice").All);
        Assert.Equal<IOfficeApp>([_libreOffice, _other], new OfficeApps(_libreOffice, null, _other, _ => "LibreOffice").All);
    }

    [Fact]
    public void OnlyTheOtherFilesAppIsTheOtherFilesApp()
    {
        var apps = new OfficeApps(_libreOffice, _microsoft, _other, _ => "LibreOffice");

        Assert.True(apps.IsOtherFiles(_other));
        Assert.False(apps.IsOtherFiles(_libreOffice));
        Assert.False(apps.IsOtherFiles(_microsoft));
        Assert.False(new OfficeApps(_libreOffice, null, null, _ => "LibreOffice").IsOtherFiles(_libreOffice));
    }
}
