using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>CoffeePDF as an app of the cycle (M3.6 spec §4.1).</summary>
public sealed class CoffeePdfAppTests : IDisposable
{
    private readonly string _folder = Directory.CreateTempSubdirectory("healdan-coffeepdf-app-").FullName;

    public void Dispose() => Directory.Delete(_folder, true);

    [Fact]
    public void ItsLockFileIsCoffeePdfsOwnBesideTheCopy()
    {
        var app = new CoffeePdfApp(_ => false);
        var copy = Path.Combine(_folder, "Signed NDA #7 v1.0.pdf");
        File.WriteAllText(copy, "a pdf");
        Assert.False(app.LockExists(copy));

        File.WriteAllText(Path.Combine(_folder, ".~lock.Signed NDA #7 v1.0.pdf#"), "LibreOffice's, not CoffeePDF's");
        Assert.False(app.LockExists(copy));

        File.WriteAllText(Path.Combine(_folder, ".~coffeepdf.Signed NDA #7 v1.0.pdf#"), """{"pid": 1}""");
        Assert.True(app.LockExists(copy));
    }

    [Fact]
    public void ItGoesByItsOwnNameAndKeepsTheDefaults()
    {
        string? asked = null;
        IOfficeApp app = new CoffeePdfApp(name =>
        {
            asked = name;
            return true;
        });

        Assert.Equal("CoffeePDF", app.Name);
        Assert.Equal("CoffeePDF", app.CheckOutName("Signed NDA.pdf")); // "checked out to you on MacBook (CoffeePDF)"
        Assert.True(app.IsRunning());
        Assert.Equal("CoffeePDF", asked);
        Assert.True(app.IsRunningFor("/any/copy.pdf"));
        Assert.Null(app.LockWritten("/any/copy.pdf")); // no recovery watch: CoffeePDF restores nothing after a crash
        Assert.Null(app.Scripting);
    }

    [Fact]
    public void OpenAndStartGoToTheOpenerItsGiven()
    {
        var opened = new List<string>();
        IOfficeApp app = new CoffeePdfApp(_ => false, opened.Add);

        app.Open("/copies/a.pdf");
        app.Start("/copies/b.pdf"); // Start is Open: there is nothing for CoffeePDF to restore

        Assert.Equal(["/copies/a.pdf", "/copies/b.pdf"], opened);
    }
}
