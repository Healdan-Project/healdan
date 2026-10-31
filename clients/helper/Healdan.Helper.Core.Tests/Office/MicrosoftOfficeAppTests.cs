using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Office;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>Microsoft Office as the cycle's app: the program a file opens in, its owner file, and whether it's running.</summary>
public sealed class MicrosoftOfficeAppTests : IDisposable
{
    private readonly string _folder = Directory.CreateTempSubdirectory("healdan-msoffice-").FullName;
    private readonly FakeScripting _scripting = new();
    private readonly HashSet<string> _running = [];
    private readonly MicrosoftOfficeApp _app;

    public MicrosoftOfficeAppTests() => _app = new MicrosoftOfficeApp(_scripting, _running.Contains);

    public void Dispose() => Directory.Delete(_folder, true);

    private string Copy(string name)
    {
        var path = Path.Combine(_folder, name);
        File.WriteAllText(path, "content");
        return path;
    }

    [Fact]
    public void ItsNameIsWhatACopysRecordKeeps()
    {
        Assert.Equal("Microsoft Office", MicrosoftOfficeApp.AppName);
        Assert.Equal("Microsoft Office", _app.Name);
        Assert.Same(_scripting, _app.Scripting);
    }

    [Theory]
    [InlineData("Lease #1 v1.0.docx", "Word")]
    [InlineData("Lease #1 v1.0.RTF", "Word")]
    [InlineData("Fees #2 v1.0.xlsm", "Excel")]
    [InlineData("Fees #2 v1.0.xls", "Excel")]
    [InlineData("Deck #3 v1.0.pptx", "PowerPoint")]
    [InlineData("Notes #4 v1.0.odt", "Word")] // when LibreOffice isn't there, Word opens it
    [InlineData("Sheet #4 v1.0.ods", "Excel")]
    [InlineData("Slides #4 v1.0.odp", "PowerPoint")]
    public void TheCheckOutNamesTheFilesProgram(string fileName, string expected) =>
        Assert.Equal(expected, ((IOfficeApp)_app).CheckOutName(fileName));

    [Fact]
    public void AFileNoProgramOpensIsCheckedOutAsMicrosoftOffice() =>
        Assert.Equal("Microsoft Office", ((IOfficeApp)_app).CheckOutName("Plan #1 v1.0.odg"));

    [Theory]
    [InlineData("Lease #1 v1.0.docx", OfficeProgram.Word)]
    [InlineData("Fees #2 v1.0.xlsx", OfficeProgram.Excel)]
    [InlineData("Deck #3 v1.0.pptx", OfficeProgram.PowerPoint)]
    [InlineData("Notes #4 v1.0.odt", OfficeProgram.Word)]
    public void OpenHandsTheFileToItsProgram(string fileName, OfficeProgram program)
    {
        var path = Copy(fileName);

        _app.Open(path);

        Assert.Equal([(program, path)], _scripting.Opened);
    }

    [Fact]
    public void OpenRefusesAFileNoProgramOpens()
    {
        var path = Copy("Plan #1 v1.0.odg");

        Assert.Throws<IOException>(() => _app.Open(path));
        Assert.Empty(_scripting.Opened);
    }

    [Fact]
    public void OpenFailsAsTheProgramFails()
    {
        _scripting.FailOpen = true;

        Assert.Throws<OfficeScriptException>(() => _app.Open(Copy("Lease #1 v1.0.docx")));
    }

    [Fact]
    public void TheOwnerFileIsTheLock()
    {
        var word = Copy("Lease agreement #1 v1.0.docx");
        var excel = Copy("Fees #2 v1.0.xlsx");
        var shortWord = Copy("ab.docx");
        Assert.False(_app.LockExists(word));

        File.WriteAllText(Path.Combine(_folder, "~$ase agreement #1 v1.0.docx"), "owner"); // Word: the first two characters
        File.WriteAllText(Path.Combine(_folder, "~$Fees #2 v1.0.xlsx"), "owner"); // Excel: the whole name
        File.WriteAllText(Path.Combine(_folder, "~$ab.docx"), "owner"); // Word, for a name too short to lose two

        Assert.True(_app.LockExists(word));
        Assert.True(_app.LockExists(excel));
        Assert.True(_app.LockExists(shortWord));
        Assert.False(_app.LockExists(Copy("Other #3 v1.0.docx")));
        // LibreOffice's lock file isn't Microsoft Office's.
        File.WriteAllText(FakeApp.LockPath(Copy("Third #5 v1.0.docx")), "lock");
        Assert.False(_app.LockExists(Path.Combine(_folder, "Third #5 v1.0.docx")));
    }

    [Fact]
    public void ARunningProgramIsRunningForItsFilesOnly()
    {
        var word = Copy("Lease #1 v1.0.docx");
        var excel = Copy("Fees #2 v1.0.xlsx");
        var app = (IOfficeApp)_app;
        Assert.False(app.IsRunning());
        Assert.False(app.IsRunningFor(word));

        _running.Add("Microsoft Excel");

        Assert.True(app.IsRunning());
        Assert.True(app.IsRunningFor(excel));
        Assert.False(app.IsRunningFor(word)); // Excel running says nothing of a Word copy: it crashed if Word is gone
    }

    [Fact]
    public void ForAFileNoProgramOpensAnyProgramRunningWillDo()
    {
        var app = (IOfficeApp)_app;
        var path = Copy("Plan #1 v1.0.odg");
        Assert.False(app.IsRunningFor(path));

        _running.Add("Microsoft PowerPoint");

        Assert.True(app.IsRunningFor(path));
        Assert.False(_app.LockExists(path));
    }
}
