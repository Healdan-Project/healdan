using System.Diagnostics;
using Healdan.Helper.Core.Office;

namespace Healdan.Helper.Core.Tests.Office;

/// <summary>
/// <see cref="MacOfficeScripting"/> against the real Word, Excel and PowerPoint (spec §12.1), run by hand on a Mac that
/// has them: <c>HEALDAN_MAC_OFFICE=1 dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacOffice"</c>.
/// <para>These tests open windows in the three apps on the screen of whoever runs them, and each app may ask for
/// Automation (once, naming the process that drives it) and for Grant File Access (once, for the folder the files are
/// in). The apps' own prompts have to be answered, so the tests are skipped without the variable: a plain
/// <c>dotnet test</c> never starts Office. Only <see cref="EveryScriptCompiles"/> runs on any Mac that has the apps,
/// as compiling reads their dictionaries and starts nothing.</para>
/// </summary>
[Trait("Category", "MacOffice")]
public sealed class MacOfficeTests : IAsyncLifetime
{
    private static readonly TimeSpan Patient = TimeSpan.FromSeconds(330); // an app may wait for the user (Grant File Access)

    /// <summary>The marker a script built from the document's name would create, if the name ran as AppleScript.</summary>
    private const string Marker = "/tmp/healdan-pwned";

    private readonly string _folder = Directory.CreateTempSubdirectory("healdan-office-").FullName;
    private readonly string _copies;
    private readonly OsaRunner _osa = new();
    private readonly MacOfficeScripting _scripting;
    private readonly List<(OfficeProgram Program, string FileName)> _opened = [];

    public MacOfficeTests()
    {
        // The helper's Copies folder, as the scripts are told of it: a file name finds a document only in here.
        _copies = Directory.CreateDirectory(Path.Combine(_folder, "Copies")).FullName;
        _scripting = new MacOfficeScripting(_osa, _copies);
    }

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        // Close what a failed test left open, then the folder; an app that isn't running is left alone.
        foreach (var group in _opened.GroupBy(o => o.Program))
        {
            try
            {
                await _osa.RunAsync(Fixtures.For(group.Key).Close, [.. group.Select(o => o.FileName)], Patient, default);
            }
            catch (OfficeScriptException)
            {
            }
        }
        try
        {
            Directory.Delete(_folder, true);
        }
        catch (IOException)
        {
        }
    }

    [MacOfficeFact(OpensApps = false)]
    public async Task EveryScriptCompiles()
    {
        // The trial lost several rounds to scripts that didn't compile (spec §12.1). The test's own scripts are included.
        var failures = new List<string>();
        var scripts = ScriptsTests.Scripts().ToDictionary(s => "Scripts." + s.Key, s => s.Value);
        foreach (var program in Enum.GetValues<OfficeProgram>())
            scripts[$"Fixtures.{program}.New"] = Fixtures.For(program).New;
        foreach (var program in Enum.GetValues<OfficeProgram>())
        {
            scripts[$"Fixtures.{program}.Edit"] = Fixtures.For(program).Edit;
            scripts[$"Fixtures.{program}.Close"] = Fixtures.For(program).Close;
            scripts[$"Fixtures.{program}.Recents"] = Fixtures.For(program).Recents;
        }
        scripts["Fixtures.Word.EditAt"] = Fixtures.WordEditAt;
        scripts["Fixtures.Word.InsertDocId"] = Fixtures.WordInsertDocId;
        scripts["Fixtures.Word.Footer"] = Fixtures.WordFooter;
        scripts["Fixtures.Word.Mark"] = Fixtures.WordMark;

        foreach (var (name, text) in scripts)
        {
            var error = await CompileAsync(text);
            if (error is not null)
                failures.Add($"{name}: {error}");
        }

        Assert.True(failures.Count == 0, string.Join(Environment.NewLine, failures));
    }

    [MacOfficeFact]
    public async Task WordRoundTrip()
    {
        const OfficeProgram word = OfficeProgram.Word;
        var a = Path.Combine(_folder, "a.docx");
        var b = Path.Combine(_folder, "b.docx");
        var c = Path.Combine(_folder, "c.docx");

        // A new document has no path, and its window's name finds it.
        await _osa.RunAsync(Fixtures.Word.New, ["One line of text."], Patient, default);
        _opened.Add((word, "a.docx"));
        var front = await _scripting.FrontDocumentAsync(word);
        Assert.NotNull(front);
        Assert.Null(front.Path);
        Assert.NotNull(front.WindowName);
        var saved = await _scripting.SaveAsAsync(word, new DocumentRef(front.Path, null, front.WindowName), a, null);
        Assert.Equal(new SaveAsResult("window", false), saved);
        Assert.True(File.Exists(a));

        var current = await _scripting.FrontDocumentAsync(word);
        Assert.NotNull(current);
        Assert.True(SamePath(a, current.Path));
        Assert.Equal("a.docx", current.Name);
        Assert.True(await _scripting.IsOpenAsync(word, a));
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, a));
        Assert.Null(await _scripting.HasUnsavedChangesAsync(word, Path.Combine(_folder, "nothing.docx")));

        // Typing makes it unsaved, and a rename then waits.
        var fingerprint = await _scripting.FingerprintAsync(word, new DocumentRef(a, null, null));
        Assert.NotNull(fingerprint);
        await _osa.RunAsync(Fixtures.Word.Edit, ["a.docx", "Two lines of text."], Patient, default);
        Assert.True(await _scripting.HasUnsavedChangesAsync(word, a));
        Assert.NotEqual(fingerprint, await _scripting.FingerprintAsync(word, new DocumentRef(a, null, null)));
        Assert.Equal(RenameResult.Unsaved, await _scripting.RenameIfUnchangedAsync(word, a, b));
        Assert.False(File.Exists(b));

        // Saved, it renames; the new name is the window's, kept off Open Recent, and a.docx's owner file goes.
        await _scripting.SaveAsync(word, a);
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, a));
        Assert.Equal(RenameResult.Renamed, await _scripting.RenameIfUnchangedAsync(word, a, b));
        _opened.Add((word, "b.docx"));
        Assert.True(File.Exists(b));
        Assert.True(await _scripting.IsOpenAsync(word, b));
        await Eventually(() => OwnerExists(word, "b.docx") && !OwnerExists(word, "a.docx"), "a.docx's owner file to go and b.docx's to come");
        Assert.DoesNotContain("b.docx", await RecentFiles(word));
        Assert.DoesNotContain("a.docx", await RecentFiles(word));

        // The doc ID: nothing without the property, then the field shows the new value and the document stays saved.
        Assert.False(await _scripting.SetDocIdAsync(b, "#1 v1.0"));
        await _osa.RunAsync(Fixtures.WordInsertDocId, ["b.docx", "#1 v1.0"], Patient, default);
        await _scripting.SaveAsync(word, b);
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, b));
        Assert.True(await _scripting.SetDocIdAsync(b, "#1 v1.1"));
        Assert.Contains("#1 v1.1", await _osa.RunAsync(Fixtures.WordFooter, ["b.docx"], Patient, default));
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, b));

        // The add-in's mark finds a document, and a save as keeps what it found and what changed meanwhile.
        await _osa.RunAsync(Fixtures.WordMark, ["b.docx", "mark-1"], Patient, default);
        Assert.True(await _scripting.HasMarkAsync(word, "mark-1"));
        Assert.False(await _scripting.HasMarkAsync(word, "mark-2"));
        var beforeSaveAs = await _scripting.FingerprintAsync(word, new DocumentRef(null, "mark-1", null));
        var unchanged = await _scripting.SaveAsAsync(word, new DocumentRef(null, "mark-1", null), c, beforeSaveAs);
        _opened.Add((word, "c.docx"));
        Assert.Equal(new SaveAsResult("mark", false), unchanged);
        Assert.True(File.Exists(c));
        Assert.True(SamePath(c, (await _scripting.FrontDocumentAsync(word))?.Path));
        await _osa.RunAsync(Fixtures.Word.Edit, ["c.docx", "Typed meanwhile."], Patient, default);
        var changed = await _scripting.SaveAsAsync(word, new DocumentRef(c, null, null), Path.Combine(_folder, "d.docx"), beforeSaveAs);
        _opened.Add((word, "d.docx"));
        Assert.Equal(new SaveAsResult("path", true), changed);

        // Open brings a closed file back, kept off Open Recent.
        await _osa.RunAsync(Fixtures.Word.Close, ["a.docx", "b.docx", "c.docx", "d.docx"], Patient, default);
        Assert.False(await _scripting.IsOpenAsync(word, b));
        await _scripting.OpenAsync(word, b);
        Assert.True(await _scripting.IsOpenAsync(word, b));
        Assert.DoesNotContain("b.docx", await RecentFiles(word));
    }

    [MacOfficeFact]
    public async Task ExcelRoundTrip()
    {
        const OfficeProgram excel = OfficeProgram.Excel;
        var a = Path.Combine(_folder, "a.xlsx");
        var b = Path.Combine(_folder, "b.xlsx");

        await _osa.RunAsync(Fixtures.Excel.New, ["First"], Patient, default);
        _opened.Add((excel, "a.xlsx"));
        var front = await _scripting.FrontDocumentAsync(excel);
        Assert.NotNull(front);
        Assert.Null(front.Path);
        var saved = await _scripting.SaveAsAsync(excel, new DocumentRef(front.Path, null, front.WindowName), a, null);
        Assert.Equal(new SaveAsResult("window", false), saved);
        Assert.True(File.Exists(a));

        var current = await _scripting.FrontDocumentAsync(excel);
        Assert.NotNull(current);
        Assert.True(SamePath(a, current.Path));
        Assert.Equal("a.xlsx", current.Name);
        Assert.True(await _scripting.IsOpenAsync(excel, a));
        Assert.False(await _scripting.HasUnsavedChangesAsync(excel, a));
        Assert.False(await _scripting.HasMarkAsync(excel, "mark-1"));

        var fingerprint = await _scripting.FingerprintAsync(excel, new DocumentRef(a, null, null));
        Assert.NotNull(fingerprint);
        await _osa.RunAsync(Fixtures.Excel.Edit, ["a.xlsx", "Second"], Patient, default);
        Assert.True(await _scripting.HasUnsavedChangesAsync(excel, a));
        Assert.NotEqual(fingerprint, await _scripting.FingerprintAsync(excel, new DocumentRef(a, null, null)));
        Assert.Equal(RenameResult.Unsaved, await _scripting.RenameIfUnchangedAsync(excel, a, b));

        await _scripting.SaveAsync(excel, a);
        Assert.False(await _scripting.HasUnsavedChangesAsync(excel, a));
        Assert.Equal(RenameResult.Renamed, await _scripting.RenameIfUnchangedAsync(excel, a, b));
        _opened.Add((excel, "b.xlsx"));
        Assert.True(File.Exists(b));
        await Eventually(() => OwnerExists(excel, "b.xlsx") && !OwnerExists(excel, "a.xlsx"), "a.xlsx's owner file to go and b.xlsx's to come");
        Assert.DoesNotContain("b.xlsx", await RecentFiles(excel));
        Assert.DoesNotContain("a.xlsx", await RecentFiles(excel));

        await _osa.RunAsync(Fixtures.Excel.Close, ["a.xlsx", "b.xlsx"], Patient, default);
        Assert.False(await _scripting.IsOpenAsync(excel, b));
        await _scripting.OpenAsync(excel, b); // through open -a
        await Eventually(async () => await _scripting.IsOpenAsync(excel, b), "Excel to open b.xlsx");
    }

    [MacOfficeFact]
    public async Task PowerPointRoundTrip()
    {
        const OfficeProgram powerPoint = OfficeProgram.PowerPoint;
        var a = Path.Combine(_folder, "a.pptx");

        await _osa.RunAsync(Fixtures.PowerPoint.New, ["Opening slide"], Patient, default);
        _opened.Add((powerPoint, "a.pptx"));
        var front = await _scripting.FrontDocumentAsync(powerPoint);
        Assert.NotNull(front);
        Assert.Null(front.Path);
        var saved = await _scripting.SaveAsAsync(powerPoint, new DocumentRef(front.Path, null, front.WindowName), a, null);
        Assert.Equal(new SaveAsResult("window", false), saved);
        Assert.True(File.Exists(a));

        var current = await _scripting.FrontDocumentAsync(powerPoint);
        Assert.NotNull(current);
        Assert.True(SamePath(a, current.Path));
        Assert.Equal("a.pptx", current.Name);
        Assert.True(await _scripting.IsOpenAsync(powerPoint, a));
        Assert.False(await _scripting.HasUnsavedChangesAsync(powerPoint, a));
        Assert.False(await _scripting.HasMarkAsync(powerPoint, "mark-1"));
        var fingerprint = await _scripting.FingerprintAsync(powerPoint, new DocumentRef(a, null, null));
        Assert.NotNull(fingerprint);

        // A presentation keeps its name. Save, then Reopen, leaves it saved and open (spec §5.5).
        await Assert.ThrowsAsync<NotSupportedException>(() => _scripting.RenameIfUnchangedAsync(powerPoint, a, Path.Combine(_folder, "b.pptx")));
        await _osa.RunAsync(Fixtures.PowerPoint.Edit, ["a.pptx", "Edited slide"], Patient, default);
        Assert.True(await _scripting.HasUnsavedChangesAsync(powerPoint, a));
        await Assert.ThrowsAsync<OfficeScriptException>(() => _scripting.ReopenAsync(powerPoint, a)); // refuses while it could lose work
        Assert.True(await _scripting.HasUnsavedChangesAsync(powerPoint, a));
        await _scripting.SaveAsync(powerPoint, a);
        await _scripting.ReopenAsync(powerPoint, a);
        await Eventually(async () => await _scripting.IsOpenAsync(powerPoint, a), "PowerPoint to open a.pptx again");
        Assert.False(await _scripting.HasUnsavedChangesAsync(powerPoint, a));
        Assert.NotNull(await _scripting.FingerprintAsync(powerPoint, new DocumentRef(a, null, null)));
    }

    [MacOfficeFact]
    public async Task ANameThatWouldBreakAScript()
    {
        // A script built from this name would stop at the quote and run the shell command. The name is a file name, so it has
        // no slash: the command finds /tmp/healdan-pwned from base64.
        const string name = "a\" & (do shell script \"touch $(echo L3RtcC9oZWFsZGFuLXB3bmVk | base64 -D)\") & \"";
        File.Delete(Marker);

        foreach (var program in Enum.GetValues<OfficeProgram>())
        {
            var fixtures = Fixtures.For(program);
            var own = OfficePrograms.OwnFormat(program);
            var path = Path.Combine(_folder, name + own);
            var renamed = Path.Combine(_folder, name + " v2" + own);

            await _osa.RunAsync(fixtures.New, ["text"], Patient, default);
            _opened.Add((program, name + own));
            _opened.Add((program, name + " v2" + own));
            var front = await _scripting.FrontDocumentAsync(program);
            Assert.NotNull(front);
            var saved = await _scripting.SaveAsAsync(program, new DocumentRef(front.Path, null, front.WindowName), path, null);
            Assert.Equal("window", saved.FoundBy);
            Assert.True(File.Exists(path), $"{program} didn't save {path}");

            var current = await _scripting.FrontDocumentAsync(program);
            Assert.NotNull(current);
            Assert.Equal(name + own, current.Name);
            Assert.True(SamePath(path, current.Path));
            Assert.True(await _scripting.IsOpenAsync(program, path));
            Assert.False(await _scripting.HasUnsavedChangesAsync(program, path));
            Assert.NotNull(await _scripting.FingerprintAsync(program, new DocumentRef(path, name, name)));
            Assert.False(await _scripting.HasMarkAsync(program, name));
            await _scripting.SaveAsync(program, path);

            if (OfficePrograms.RenamesAfterSave(program))
            {
                Assert.Equal(RenameResult.Renamed, await _scripting.RenameIfUnchangedAsync(program, path, renamed));
                Assert.True(File.Exists(renamed));
                if (program == OfficeProgram.Word)
                    Assert.False(await _scripting.SetDocIdAsync(renamed, name));
            }
            else
            {
                await _scripting.ReopenAsync(program, path);
            }

            Assert.False(File.Exists(Marker), $"{program}: the name ran as a script");
        }
    }

    [MacOfficeFact]
    public async Task TwoDocumentsWithTheSameNameInDifferentFolders()
    {
        // A copy set aside keeps its name beside Copies, so Word can have two documents of one name open (spec section 5.2).
        // Each path must find its own document, and a file name must find nothing outside Copies.
        const OfficeProgram word = OfficeProgram.Word;
        var aside = Path.Combine(Directory.CreateDirectory(Path.Combine(_folder, "Aside")).FullName, "same.docx");
        var inCopies = Path.Combine(_copies, "same.docx");
        var solo = Path.Combine(_folder, "Aside", "solo.docx");
        var renamed = Path.Combine(_copies, "renamed.docx");
        _opened.Add((word, "same.docx"));
        _opened.Add((word, "solo.docx"));
        _opened.Add((word, "renamed.docx"));

        await NewSavedWordDocument(aside, "The copy set aside.");
        await NewSavedWordDocument(inCopies, "The copy in Copies.");
        await NewSavedWordDocument(solo, "Only beside Copies.");

        // Each path finds its own document, whichever was opened last.
        var asideText = await _scripting.FingerprintAsync(word, new DocumentRef(aside, null, null));
        var copiesText = await _scripting.FingerprintAsync(word, new DocumentRef(inCopies, null, null));
        Assert.NotNull(asideText);
        Assert.NotNull(copiesText);
        Assert.NotEqual(asideText, copiesText);
        Assert.True(await _scripting.IsOpenAsync(word, aside));
        Assert.True(await _scripting.IsOpenAsync(word, inCopies));

        // Changing the one beside Copies leaves the one in Copies saved, and Save, Rename and the doc ID act on the right one.
        await _osa.RunAsync(Fixtures.WordEditAt, [aside, "Edited beside Copies."], Patient, default);
        Assert.True(await _scripting.HasUnsavedChangesAsync(word, aside));
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, inCopies));
        Assert.Equal(RenameResult.Renamed, await _scripting.RenameIfUnchangedAsync(word, inCopies, renamed));
        Assert.True(File.Exists(renamed));
        Assert.True(await _scripting.HasUnsavedChangesAsync(word, aside)); // not saved by the rename
        Assert.Equal(copiesText, await _scripting.FingerprintAsync(word, new DocumentRef(renamed, null, null))); // the Copies document's content
        Assert.False(await _scripting.SetDocIdAsync(aside, "#1 v1.1")); // neither has the property, and neither is touched
        await _scripting.SaveAsync(word, aside);
        Assert.False(await _scripting.HasUnsavedChangesAsync(word, aside));

        // A file name alone finds a document in Copies, and not one beside it.
        Assert.True(await _scripting.IsOpenAsync(word, Path.Combine(_folder, "Elsewhere", "renamed.docx")));
        Assert.False(await _scripting.IsOpenAsync(word, Path.Combine(_folder, "Elsewhere", "solo.docx")));
        Assert.Null(await _scripting.HasUnsavedChangesAsync(word, Path.Combine(_folder, "Elsewhere", "solo.docx")));
    }

    private async Task NewSavedWordDocument(string path, string text)
    {
        await _osa.RunAsync(Fixtures.Word.New, [text], Patient, default);
        var front = await _scripting.FrontDocumentAsync(OfficeProgram.Word);
        Assert.NotNull(front);
        await _scripting.SaveAsAsync(OfficeProgram.Word, new DocumentRef(front.Path, null, front.WindowName), path, null);
        Assert.True(File.Exists(path));
    }

    /// <summary>The same file, though an app says <c>/tmp</c> where the system says <c>/private/tmp</c> (spec §5.2).</summary>
    private static bool SamePath(string expected, string? reported) =>
        reported is not null && Strip(expected) == Strip(reported);

    private static string Strip(string path) => path.StartsWith("/private/", StringComparison.Ordinal) ? path[8..] : path;

    private bool OwnerExists(OfficeProgram program, string fileName) =>
        OfficePrograms.OwnerNames(program, fileName).Any(owner => File.Exists(Path.Combine(_folder, owner)));

    private async Task<IReadOnlyList<string>> RecentFiles(OfficeProgram program) =>
        (await _osa.RunAsync(Fixtures.For(program).Recents, [], Patient, default)).Split('\n', StringSplitOptions.RemoveEmptyEntries);

    private static Task Eventually(Func<bool> done, string what) => Eventually(() => Task.FromResult(done()), what);

    private static async Task Eventually(Func<Task<bool>> done, string what)
    {
        var until = DateTime.UtcNow + TimeSpan.FromSeconds(20);
        while (!await done())
        {
            Assert.True(DateTime.UtcNow < until, $"Waited 20 seconds for {what}.");
            await Task.Delay(250);
        }
    }

    /// <summary>Compiles a script with <c>osacompile</c>, which reads the apps' dictionaries and starts nothing. Null when it compiles.</summary>
    private static async Task<string?> CompileAsync(string script)
    {
        var start = new ProcessStartInfo("/usr/bin/osacompile")
        {
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        start.ArgumentList.Add("-o");
        start.ArgumentList.Add("/dev/null");
        start.ArgumentList.Add("-");
        using var process = Process.Start(start)!;
        var error = process.StandardError.ReadToEndAsync();
        var output = process.StandardOutput.ReadToEndAsync();
        await process.StandardInput.WriteAsync(script);
        process.StandardInput.Close();
        await process.WaitForExitAsync();
        await output;
        return process.ExitCode == 0 ? null : (await error).Trim();
    }
}

/// <summary>
/// A test that drives the real Word, Excel and PowerPoint. Without <c>HEALDAN_MAC_OFFICE=1</c>, or off a Mac with the
/// apps, it skips itself, as <c>StackFact</c> does without a stack. <see cref="OpensApps"/> false is for a test that
/// only reads their dictionaries.
/// </summary>
public sealed class MacOfficeFactAttribute : FactAttribute
{
    public MacOfficeFactAttribute()
    {
        if (!OperatingSystem.IsMacOS())
            Skip = "Word, Excel and PowerPoint for Mac are needed.";
        else if (Enum.GetValues<OfficeProgram>().Any(p => !Directory.Exists(OfficePrograms.BundlePath(p))))
            Skip = "Word, Excel and PowerPoint aren't all installed.";
        else if (OpensApps && Environment.GetEnvironmentVariable("HEALDAN_MAC_OFFICE") != "1")
            Skip = "HEALDAN_MAC_OFFICE=1 isn't set. This test opens Word, Excel and PowerPoint on your screen.";
    }

    /// <summary>Whether the test starts the apps; true unless it only compiles scripts against their dictionaries.</summary>
    public bool OpensApps
    {
        get => _opensApps;
        set
        {
            _opensApps = value;
            if (!value && Skip is not null && Skip.StartsWith("HEALDAN_MAC_OFFICE", StringComparison.Ordinal))
                Skip = null;
        }
    }

    private bool _opensApps = true;
}

/// <summary>
/// The tests' own AppleScript: it makes, edits and closes the documents the tests then drive through
/// <see cref="MacOfficeScripting"/>. Fixed text like the helper's own, with every value passed in as an argument.
/// </summary>
internal sealed record Fixtures(string New, string Edit, string Close, string Recents)
{
    public static Fixtures Word { get; } = new(
        New: """"
            on run argv
                tell application "Microsoft Word"
                    with timeout of 120 seconds
                        activate
                        set d to make new document
                        set content of text object of d to item 1 of argv
                        return name of active window
                    end timeout
                end tell
            end run
            """",
        Edit: """"
            on run argv
                set fname to item 1 of argv
                tell application "Microsoft Word"
                    with timeout of 120 seconds
                        repeat with i from 1 to (count documents)
                            set xd to document i
                            if (name of xd) is fname then set content of text object of xd to item 2 of argv
                        end repeat
                        return "edited"
                    end timeout
                end tell
            end run
            """",
        Close: CloseNamed("Microsoft Word", "documents", "document"),
        Recents: Recent("Microsoft Word"));

    public static Fixtures Excel { get; } = new(
        New: """"
            on run argv
                tell application "Microsoft Excel"
                    with timeout of 120 seconds
                        activate
                        make new workbook
                        set value of range "A1" of active sheet to item 1 of argv
                        return name of active workbook
                    end timeout
                end tell
            end run
            """",
        Edit: """"
            on run argv
                set fname to item 1 of argv
                tell application "Microsoft Excel"
                    with timeout of 120 seconds
                        repeat with i from 1 to (count workbooks)
                            set xd to workbook i
                            if (name of xd) is fname then set value of range "A1" of active sheet of xd to item 2 of argv
                        end repeat
                        return "edited"
                    end timeout
                end tell
            end run
            """",
        Close: CloseNamed("Microsoft Excel", "workbooks", "workbook"),
        Recents: Recent("Microsoft Excel"));

    public static Fixtures PowerPoint { get; } = new(
        New: """"
            on run argv
                tell application "Microsoft PowerPoint"
                    with timeout of 120 seconds
                        activate
                        make new presentation
                        set d to active presentation
                        try
                            make new slide at end of d with properties {layout:slide layout title slide}
                            set content of text range of text frame of shape 1 of slide 1 of d to item 1 of argv
                        end try
                        return name of d
                    end timeout
                end tell
            end run
            """",
        Edit: """"
            on run argv
                set fname to item 1 of argv
                tell application "Microsoft PowerPoint"
                    with timeout of 120 seconds
                        repeat with i from 1 to (count presentations)
                            set xd to presentation i
                            if (name of xd) is fname then
                                try
                                    set content of text range of text frame of shape 1 of slide 1 of xd to item 2 of argv
                                end try
                                set saved of xd to false
                            end if
                        end repeat
                        return "edited"
                    end timeout
                end tell
            end run
            """",
        Close: CloseNamed("Microsoft PowerPoint", "presentations", "presentation"),
        Recents: """"
            on run argv
                return ""
            end run
            """");

    /// <summary>Changes the text of the document at a path, so it has unsaved changes (arguments: POSIX path, text).</summary>
    public static string WordEditAt { get; } = """"
        on run argv
            set p to item 1 of argv
            tell application "Microsoft Word"
                with timeout of 120 seconds
                    repeat with i from 1 to (count documents)
                        set xd to document i
                        if (posix full name of xd) is p then set content of text object of xd to item 2 of argv
                    end repeat
                    return "edited"
                end timeout
            end tell
        end run
        """";

    /// <summary>Adds the custom document property <c>Healdan Doc ID</c> and a footer field that shows it (arguments: file name, value).</summary>
    public static string WordInsertDocId { get; } = """"
        on run argv
            set fname to item 1 of argv
            set v to item 2 of argv
            tell application "Microsoft Word"
                with timeout of 120 seconds
                    repeat with i from 1 to (count documents)
                        set xd to document i
                        if (name of xd) is fname then
                            make new custom document property at xd with properties {name:"Healdan Doc ID", value:v, document property type:property type string}
                            set f to get footer (section 1 of xd) index header footer primary
                            set r to text object of f
                            collapse range r direction collapse end
                            create new field text range r field type field doc property field text "\"Healdan Doc ID\""
                        end if
                    end repeat
                    return "inserted"
                end timeout
            end tell
        end run
        """";

    /// <summary>The text of the document's primary footer (argument: file name).</summary>
    public static string WordFooter { get; } = """"
        on run argv
            set fname to item 1 of argv
            tell application "Microsoft Word"
                with timeout of 120 seconds
                    repeat with i from 1 to (count documents)
                        set xd to document i
                        if (name of xd) is fname then return content of text object of (get footer (section 1 of xd) index header footer primary)
                    end repeat
                    return ""
                end timeout
            end tell
        end run
        """";

    /// <summary>Marks the document as the add-in does, with the custom document property <c>HealdanMark</c> (arguments: file name, mark).</summary>
    public static string WordMark { get; } = """"
        on run argv
            set fname to item 1 of argv
            set v to item 2 of argv
            tell application "Microsoft Word"
                with timeout of 120 seconds
                    repeat with i from 1 to (count documents)
                        set xd to document i
                        if (name of xd) is fname then
                            make new custom document property at xd with properties {name:"HealdanMark", value:v, document property type:property type string}
                        end if
                    end repeat
                    return "marked"
                end timeout
            end tell
        end run
        """";

    public static Fixtures For(OfficeProgram program) => program switch
    {
        OfficeProgram.Word => Word,
        OfficeProgram.Excel => Excel,
        _ => PowerPoint,
    };

    /// <summary>Closes, without saving, every open document whose file name is one of the arguments.</summary>
    private static string CloseNamed(string app, string collection, string item) => $$"""
        on run argv
            if application "{{app}}" is not running then return "not running"
            tell application "{{app}}"
                with timeout of 120 seconds
                    repeat with i from (count {{collection}}) to 1 by -1
                        set xd to {{item}} i
                        try
                            if argv contains (name of xd) then close xd saving no
                        end try
                    end repeat
                    return "closed"
                end timeout
            end tell
        end run
        """;

    /// <summary>The names on the app's recently used list, one a line.</summary>
    private static string Recent(string app) => $$"""
        on run argv
            tell application "{{app}}"
                with timeout of 120 seconds
                    set recentNames to name of every recent file
                end timeout
            end tell
            set AppleScript's text item delimiters to linefeed
            set recentText to recentNames as text
            set AppleScript's text item delimiters to ""
            return recentText
        end run
        """;
}
