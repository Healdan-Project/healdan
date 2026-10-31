using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Settings;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>M3.5 spec §5.11: the notice before each program's first save into Copies, once per program, ever.</summary>
public sealed class CopiesNoticeTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-notice-").FullName;
    private readonly FakeNotifier _notifier = new();

    public void Dispose() => Directory.Delete(_root, true);

    private HelperPaths Paths => HelperPaths.For(_root);

    [Fact]
    public void EachProgramIsToldOnce()
    {
        var notice = new CopiesNotice(Paths, _notifier);

        notice.BeforeFirstSave(OfficeProgram.Word);
        notice.BeforeFirstSave(OfficeProgram.Word);
        notice.BeforeFirstSave(OfficeProgram.Excel);
        notice.BeforeFirstSave(OfficeProgram.PowerPoint);
        notice.BeforeFirstSave(OfficeProgram.Excel);

        Assert.Equal(
            [
                "Word will ask for access to Healdan Helper's Copies folder. Click Select…, go up one level, select Copies, and click Grant Access.",
                "Excel will ask for access to Healdan Helper's Copies folder. Click Select…, go up one level, select Copies, and click Grant Access.",
                "PowerPoint will ask for access to Healdan Helper's Copies folder. Click Select…, go up one level, select Copies, and click Grant Access.",
            ],
            _notifier.Notices.Select(n => n.Message));
        Assert.All(_notifier.Notices, n => Assert.Equal("Healdan Helper", n.Title));
        Assert.Equal(["Word", "Excel", "PowerPoint"], HelperSettings.Load(Paths).CopiesAnnounced);
    }

    [Fact]
    public void TheProgramsToldAreRememberedAcrossStarts()
    {
        new HelperSettings { Server = "https://healdan.example" }.Save(Paths);
        new CopiesNotice(Paths, _notifier).BeforeFirstSave(OfficeProgram.Word);

        var again = new CopiesNotice(Paths, _notifier); // a later start, or Save to Healdan's own notice
        again.BeforeFirstSave(OfficeProgram.Word);
        again.BeforeFirstSave(OfficeProgram.Excel);

        Assert.Equal(["Word", "Excel"], _notifier.Notices.Select(n => n.Message.Split(' ')[0]));
        var settings = HelperSettings.Load(Paths);
        Assert.Equal("https://healdan.example", settings.Server); // the rest of the settings are kept
        Assert.Equal(["Word", "Excel"], settings.CopiesAnnounced);
    }
}
