using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// <see cref="MacOfficeScripting"/> against a runner that records what it is asked and answers what the test says, so
/// the parsing, the timeouts and the way values travel are checked on every runner. The scripts themselves run against
/// the real apps in <c>MacOfficeTests</c>, by hand.
/// </summary>
public sealed class MacOfficeScriptingTests
{
    private const string Separator = "\u001F";

    private readonly FakeOsaRunner _runner = new();
    private readonly MacOfficeScripting _scripting;

    public MacOfficeScriptingTests() => _scripting = new MacOfficeScripting(_runner);

    [Fact]
    public async Task ArgumentsNeverReachTheScriptText()
    {
        const string path = "/tmp/a\"; do shell script \"x.docx";
        const string newPath = "/tmp/b.docx";
        _runner.Answer = "path" + Separator + "#text";

        await _scripting.SaveAsAsync(OfficeProgram.Word, new DocumentRef(path, null, null), newPath, null);

        var call = Assert.Single(_runner.Calls);
        Assert.Contains(path, call.Args);
        Assert.Contains(newPath, call.Args);
        Assert.DoesNotContain("/tmp/a", call.Script);
        Assert.DoesNotContain("x.docx", call.Script);
        Assert.DoesNotContain(newPath, call.Script);
        Assert.DoesNotContain("do shell script", call.Script);
    }

    [Theory]
    [InlineData("missing", RenameResult.Missing)]
    [InlineData("unsaved", RenameResult.Unsaved)]
    [InlineData("renamed", RenameResult.Renamed)]
    [InlineData("renamed-changed", RenameResult.RenamedChanged)]
    [InlineData("renamed-but-lost", RenameResult.Renamed)] // the file was written; the cycle finds the copy by its new name
    public async Task RenameParsesEachAnswer(string answer, RenameResult expected)
    {
        _runner.Answer = answer;

        Assert.Equal(expected, await _scripting.RenameIfUnchangedAsync(OfficeProgram.Word, "/c/a.docx", "/c/b.docx"));
    }

    [Fact]
    public async Task RenameRefusesAnAnswerItDoesntKnow()
    {
        _runner.Answer = "perhaps";

        await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.RenameIfUnchangedAsync(OfficeProgram.Excel, "/c/a.xlsx", "/c/b.xlsx"));
    }

    [Fact]
    public async Task RenamePassesTheNamesAndTheFormat()
    {
        _runner.Answer = "renamed";

        await _scripting.RenameIfUnchangedAsync(OfficeProgram.Word, "/c/Lease #1 v1.0.docm", "/c/Lease #1 v1.1.DOCM");

        var call = Assert.Single(_runner.Calls);
        Assert.Equal(["/c/Lease #1 v1.0.docm", "Lease #1 v1.0.docm", "/c/Lease #1 v1.1.DOCM", "Lease #1 v1.1.DOCM", "docm"], call.Args);
    }

    [Fact]
    public async Task APowerPointCopyKeepsItsName()
    {
        Assert.False(OfficePrograms.RenamesAfterSave(OfficeProgram.PowerPoint));

        await Assert.ThrowsAsync<NotSupportedException>(() => _scripting.RenameIfUnchangedAsync(OfficeProgram.PowerPoint, "/c/a.pptx", "/c/b.pptx"));
        Assert.Empty(_runner.Calls);
    }

    [Fact]
    public async Task SaveAsAndRenameGetTheLongTimeout()
    {
        _runner.Answer = "path" + Separator + "#text";
        await _scripting.SaveAsAsync(OfficeProgram.Word, new DocumentRef("/c/a.docx", null, null), "/c/b.docx", null);
        _runner.Answer = "renamed";
        await _scripting.RenameIfUnchangedAsync(OfficeProgram.Excel, "/c/a.xlsx", "/c/b.xlsx");
        _runner.Answer = "#text";
        await _scripting.FingerprintAsync(OfficeProgram.Word, new DocumentRef("/c/a.docx", null, null));

        Assert.Equal([TimeSpan.FromSeconds(330), TimeSpan.FromSeconds(330), TimeSpan.FromSeconds(20)], _runner.Calls.Select(c => c.Timeout));
    }

    [Fact]
    public async Task EveryScriptThatMayMeetGrantFileAccessGetsTheLongTimeout()
    {
        _runner.Answer = "ok";
        await _scripting.OpenAsync(OfficeProgram.Word, "/c/a.docx");
        _runner.Answer = "reopened";
        await _scripting.ReopenAsync(OfficeProgram.PowerPoint, "/c/a.pptx");

        Assert.All(_runner.Calls, c => Assert.Equal(TimeSpan.FromSeconds(330), c.Timeout));
        Assert.Equal(2, _runner.Calls.Count);
    }

    [Fact]
    public async Task EveryOtherScriptGetsTwentySeconds()
    {
        _runner.Answer = "yes";
        await _scripting.IsOpenAsync(OfficeProgram.Excel, "/c/a.xlsx");
        await _scripting.HasMarkAsync(OfficeProgram.PowerPoint, "mark-1");
        _runner.Answer = "saved";
        await _scripting.HasUnsavedChangesAsync(OfficeProgram.Word, "/c/a.docx");
        await _scripting.SaveAsync(OfficeProgram.Word, "/c/a.docx");
        _runner.Answer = "";
        await _scripting.FrontDocumentAsync(OfficeProgram.Word);

        Assert.Equal(5, _runner.Calls.Count);
        Assert.All(_runner.Calls, c => Assert.Equal(TimeSpan.FromSeconds(20), c.Timeout));
    }

    [Fact]
    public async Task FingerprintHashesTheScriptsText()
    {
        _runner.Answer = "Hello";

        var fingerprint = await _scripting.FingerprintAsync(OfficeProgram.Word, new DocumentRef("/c/a.docx", null, null));

        Assert.Equal("185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969", fingerprint);
    }

    [Fact]
    public async Task FingerprintIsNullWhenNoDocumentIsOpen()
    {
        _runner.Answer = "";

        Assert.Null(await _scripting.FingerprintAsync(OfficeProgram.Word, new DocumentRef(null, null, null)));
    }

    [Fact]
    public async Task FingerprintGivesEachWayOfFindingTheDocument()
    {
        _runner.Answer = "#";

        await _scripting.FingerprintAsync(OfficeProgram.Excel, new DocumentRef("/c/a.xlsx", "mark-1", "Book1"));
        await _scripting.FingerprintAsync(OfficeProgram.Excel, new DocumentRef(null, null, null));

        Assert.Equal(["/c/a.xlsx", "a.xlsx", "mark-1", "Book1"], _runner.Calls[0].Args);
        Assert.Equal(["", "", "", ""], _runner.Calls[1].Args);
    }

    [Fact]
    public async Task SetDocIdIsFalseWithoutTheProperty()
    {
        _runner.Answer = "no-property";

        Assert.False(await _scripting.SetDocIdAsync("/c/a.docx", "#1 v1.1"));
    }

    [Theory]
    [InlineData("ok", true)]
    [InlineData("no-property", false)]
    [InlineData("missing", false)]
    public async Task SetDocIdParsesEachAnswer(string answer, bool expected)
    {
        _runner.Answer = answer;

        Assert.Equal(expected, await _scripting.SetDocIdAsync("/c/Lease #1 v1.0.docx", "#1 v1.1"));
        Assert.Equal(["/c/Lease #1 v1.0.docx", "Lease #1 v1.0.docx", "#1 v1.1"], _runner.Calls.Single().Args);
    }

    [Fact]
    public async Task WordOpensThroughWordAndOthersThroughOpenA()
    {
        _runner.Answer = "ok";

        await _scripting.OpenAsync(OfficeProgram.Word, "/c/Lease #1 v1.0.docx");

        var call = Assert.Single(_runner.Calls);
        Assert.Contains("tell application \"Microsoft Word\"", call.Script);
        Assert.Contains("add to recent files false", call.Script);
        Assert.Equal(["/c/Lease #1 v1.0.docx"], call.Args);
        Assert.Empty(_runner.Processes);

        await _scripting.OpenAsync(OfficeProgram.Excel, "/c/Fees #3 v1.0.xlsx");

        Assert.Single(_runner.Calls);
        var process = Assert.Single(_runner.Processes);
        Assert.Equal("/usr/bin/open", process.File);
        Assert.Equal(["-a", "Microsoft Excel", "/c/Fees #3 v1.0.xlsx"], process.Args);
        Assert.Equal(TimeSpan.FromSeconds(20), process.Timeout);
    }

    [Fact]
    public async Task PowerPointOpensThroughOpenAToo()
    {
        await _scripting.OpenAsync(OfficeProgram.PowerPoint, "/c/Deck #4 v1.0.pptx");

        Assert.Equal(["-a", "Microsoft PowerPoint", "/c/Deck #4 v1.0.pptx"], Assert.Single(_runner.Processes).Args);
        Assert.Empty(_runner.Calls);
    }

    [Theory]
    [InlineData("missing", null)]
    [InlineData("unsaved", true)]
    [InlineData("saved", false)]
    public async Task HasUnsavedChangesParsesEachAnswer(string answer, bool? expected)
    {
        _runner.Answer = answer;

        Assert.Equal(expected, await _scripting.HasUnsavedChangesAsync(OfficeProgram.Word, "/c/a.docx"));
    }

    [Theory]
    [InlineData("yes", true)]
    [InlineData("no", false)]
    public async Task IsOpenAndHasMarkParseYesAndNo(string answer, bool expected)
    {
        _runner.Answer = answer;

        Assert.Equal(expected, await _scripting.IsOpenAsync(OfficeProgram.Word, "/c/a.docx"));
        Assert.Equal(expected, await _scripting.HasMarkAsync(OfficeProgram.Word, "mark-1"));
    }

    [Fact]
    public async Task AnEmptyMarkIsNeverOnADocument()
    {
        _runner.Answer = "yes";

        Assert.False(await _scripting.HasMarkAsync(OfficeProgram.Word, ""));
        Assert.Empty(_runner.Calls);
    }

    [Fact]
    public async Task FrontDocumentReadsItsThreeNames()
    {
        _runner.Answer = $"/c/Lease #1 v1.0.docx{Separator}Lease #1 v1.0.docx{Separator}Lease #1 v1.0.docx";
        var saved = await _scripting.FrontDocumentAsync(OfficeProgram.Word);

        _runner.Answer = $"{Separator}{Separator}Document1";
        var unsaved = await _scripting.FrontDocumentAsync(OfficeProgram.Word);

        _runner.Answer = "";
        var none = await _scripting.FrontDocumentAsync(OfficeProgram.Word);

        Assert.Equal(new FrontDocument("/c/Lease #1 v1.0.docx", "Lease #1 v1.0.docx", "Lease #1 v1.0.docx"), saved);
        Assert.Equal(new FrontDocument(null, "", "Document1"), unsaved); // Word has no name for a document never saved
        Assert.Null(none);
        Assert.All(_runner.Calls, c => Assert.Empty(c.Args));
    }

    [Fact]
    public async Task FrontDocumentRefusesAnAnswerItCantReadAsThreeNames()
    {
        _runner.Answer = "/c/a.docx" + Separator + "a.docx";

        await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.FrontDocumentAsync(OfficeProgram.Word));
    }

    [Fact]
    public async Task SaveAsTellsHowItFoundTheDocumentAndWhetherItChanged()
    {
        var before = await FingerprintOf("#hello");

        _runner.Answer = "mark" + Separator + "#hello";
        var same = await _scripting.SaveAsAsync(OfficeProgram.Excel, new DocumentRef(null, "mark-1", "Book1"), "/c/a.XLSX", before);

        _runner.Answer = "front" + Separator + "#hello, and more";
        var edited = await _scripting.SaveAsAsync(OfficeProgram.Excel, new DocumentRef(null, null, null), "/c/a.xlsx", before);

        _runner.Answer = "window" + Separator + "#anything";
        var noBaseline = await _scripting.SaveAsAsync(OfficeProgram.Excel, new DocumentRef(null, null, "Book1"), "/c/a.xlsx", null);

        Assert.Equal(new SaveAsResult("mark", false), same);
        Assert.Equal(new SaveAsResult("front", true), edited);
        Assert.Equal(new SaveAsResult("window", false), noBaseline); // nothing to compare with
        Assert.Equal(["", "", "mark-1", "Book1", "/c/a.XLSX", "xlsx"], _runner.Calls[0].Args);
    }

    private async Task<string> FingerprintOf(string text)
    {
        _runner.Answer = text;
        var fingerprint = await _scripting.FingerprintAsync(OfficeProgram.Excel, new DocumentRef("/c/a.xlsx", null, null));
        _runner.Calls.Clear();
        return fingerprint!;
    }

    [Fact]
    public async Task SaveAsWithNoDocumentToSaveSaysSo()
    {
        _runner.Answer = "missing";

        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.SaveAsAsync(OfficeProgram.Word, new DocumentRef(null, null, null), "/c/b.docx", null));
        Assert.DoesNotContain("/c/b.docx", failure.Message); // never the arguments
    }

    [Fact]
    public async Task SaveSaysSoWhenTheDocumentIsntOpen()
    {
        _runner.Answer = "missing";

        await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.SaveAsync(OfficeProgram.Word, "/c/a.docx"));
    }

    [Fact]
    public async Task OnlyPowerPointReopens()
    {
        await Assert.ThrowsAsync<NotSupportedException>(() => _scripting.ReopenAsync(OfficeProgram.Word, "/c/a.docx"));
        await Assert.ThrowsAsync<NotSupportedException>(() => _scripting.ReopenAsync(OfficeProgram.Excel, "/c/a.xlsx"));
        Assert.Empty(_runner.Calls);

        _runner.Answer = "reopened";
        await _scripting.ReopenAsync(OfficeProgram.PowerPoint, "/c/Deck #4 v1.0.pptx");
        Assert.Equal(["/c/Deck #4 v1.0.pptx", "Deck #4 v1.0.pptx"], Assert.Single(_runner.Calls).Args);
    }

    [Theory]
    [InlineData("unsaved")]
    [InlineData("missing")]
    public async Task ReopenLeavesAPresentationThatCouldLoseWorkAlone(string answer)
    {
        _runner.Answer = answer;

        await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.ReopenAsync(OfficeProgram.PowerPoint, "/c/a.pptx"));
    }

    [Fact]
    public async Task SomeoneElsesExtensionIsRefused()
    {
        await Assert.ThrowsAsync<ArgumentException>(() => _scripting.SaveAsAsync(OfficeProgram.Word, new DocumentRef(null, null, "Document1"), "/c/b.xlsx", null));
        await Assert.ThrowsAsync<ArgumentException>(() => _scripting.RenameIfUnchangedAsync(OfficeProgram.Excel, "/c/a.xlsx", "/c/b.docx"));
        Assert.Empty(_runner.Calls);
    }

    [Theory]
    [InlineData(OfficeProgram.Word)]
    [InlineData(OfficeProgram.Excel)]
    [InlineData(OfficeProgram.PowerPoint)]
    public async Task EachProgramRunsItsOwnScripts(OfficeProgram program)
    {
        var app = OfficePrograms.ProcessName(program);
        var reference = new DocumentRef("/c/a", "mark-1", "Window");
        var path = "/c/a" + OfficePrograms.OwnFormat(program);

        _runner.Answer = "";
        await _scripting.FrontDocumentAsync(program);
        _runner.Answer = "yes";
        await _scripting.IsOpenAsync(program, path);
        await _scripting.HasMarkAsync(program, "mark-1");
        _runner.Answer = "saved";
        await _scripting.HasUnsavedChangesAsync(program, path);
        await _scripting.SaveAsync(program, path);
        _runner.Answer = "#text";
        await _scripting.FingerprintAsync(program, reference);
        _runner.Answer = "path" + Separator + "#text";
        await _scripting.SaveAsAsync(program, reference, path, null);

        Assert.Equal(7, _runner.Calls.Count);
        Assert.All(_runner.Calls, c => Assert.Contains($"application \"{app}\"", c.Script));
        Assert.Equal(7, _runner.Calls.Select(c => c.Script).Distinct().Count());
    }

    [Fact]
    public void OwnerNamesCoverWordsTwoForms()
    {
        var word = OfficePrograms.OwnerNames(OfficeProgram.Word, "Lease #1 v1.0.docx").ToList();
        var excel = OfficePrograms.OwnerNames(OfficeProgram.Excel, "Fees #3 v1.0.xlsx").ToList();

        Assert.Equal(["~$ase #1 v1.0.docx", "~$Lease #1 v1.0.docx"], word);
        Assert.Equal(["~$Fees #3 v1.0.xlsx"], excel);
        Assert.Equal(["~$Deck #4 v1.0.pptx"], OfficePrograms.OwnerNames(OfficeProgram.PowerPoint, "Deck #4 v1.0.pptx"));
    }

    [Fact]
    public async Task WhatTheRunnerThrowsReachesTheCaller()
    {
        _runner.Failure = new OfficeScriptException("Word got an error.");

        var failure = await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.SaveAsync(OfficeProgram.Word, "/c/a.docx"));
        Assert.Equal("Word got an error.", failure.Message);
    }

    [Fact]
    public async Task ACancelledCallReachesTheRunner()
    {
        using var cancel = new CancellationTokenSource();
        _runner.Answer = "yes";

        await _scripting.IsOpenAsync(OfficeProgram.Word, "/c/a.docx", cancel.Token);

        Assert.Equal(cancel.Token, Assert.Single(_runner.Calls).Cancel);
    }
}

/// <summary>An <see cref="IOsaRunner"/> that records what it is asked and answers what the test set.</summary>
internal sealed class FakeOsaRunner : IOsaRunner
{
    public sealed record Call(string Script, IReadOnlyList<string> Args, TimeSpan Timeout, CancellationToken Cancel);

    public sealed record ProcessCall(string File, IReadOnlyList<string> Args, TimeSpan Timeout);

    public List<Call> Calls { get; } = [];

    public List<ProcessCall> Processes { get; } = [];

    /// <summary>What every script answers.</summary>
    public string Answer { get; set; } = "";

    /// <summary>When set, every call throws it.</summary>
    public Exception? Failure { get; set; }

    public Task<string> RunAsync(string script, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct)
    {
        Calls.Add(new Call(script, args, timeout, ct));
        return Failure is null ? Task.FromResult(Answer) : Task.FromException<string>(Failure);
    }

    public Task RunProcessAsync(string file, IReadOnlyList<string> args, TimeSpan timeout, CancellationToken ct)
    {
        Processes.Add(new ProcessCall(file, args, timeout));
        return Failure is null ? Task.CompletedTask : Task.FromException(Failure);
    }
}
