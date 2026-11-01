# M3.6 "PDFs in CoffeePDF" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Healdan Helper opens PDFs in CoffeePDF and runs M3's cycle for them, checked out on open, a snapshot on each save and checked in on close, as the approved spec describes.

**Architecture:**
- **The Helper.** CoffeePDF becomes a fourth app in M3.5's set (`OfficeApps`), beside LibreOffice, Microsoft Office and the default app:
  - `CoffeePdfApp` opens a copy with `open -b`, and knows CoffeePDF by its process and its lock file;
  - `AppChooser` sends a `.pdf` to CoffeePDF while it's installed with Healdan support on, read from CoffeePDF's `settings.json` at each choice, and to the default app otherwise;
  - one list of each app's lock and owner files (`CopyNames.AppFiles`) serves every place the Helper names them;
  - links take PDFs, CoffeePDF's read-only copies are tracked, and `/v1/closed` deletes a default-app copy;
  - on the channel, `/v1/file` honours `app`, and `readOnly` is true once a copy's saves stop going up.
- **The web page.** `HealdanOpenInApp` offers Open in App for PDFs, in a pull request of its own.
- **No server, API or upstream change.**

**Tech Stack:**
- **Helper:** .NET 10 and Avalonia 12.1.4, as M3.5; xUnit, with Avalonia headless for the windows. No new NuGet packages.
- **Web page:** GWT 2.12 client code in `logicaldoc-gui`, with a plain JUnit 4 test.

**Status:** written 2026-11-01, awaiting the owner's approval.

**Spec:** [`docs/superpowers/specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md`](../specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md), approved by the owner on 2026-11-01. Every § below points into it, and "CoffeePDF §n" into CoffeePDF's v1 spec (`coffeemuse/CoffeePDF`, `docs/superpowers/specs/2026-10-29-coffeepdf-v1-design.md`).
- Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".
- M3's and M3.5's specs and plans still hold for everything M3.6 doesn't change.
- File and line references are at master `1c9aabaa3` (`helper-v0.4.0`). Line numbers drift; find the code by the names given.

## Global Constraints

Every task's requirements include these.

- **Scope.** macOS only: off macOS, CoffeePDF is never installed, so never available (spec §2.2). LibreOffice's and Microsoft Office's flows don't change, beyond spec §2.3's four exceptions.
- **No upstream file changes** (spec §10.1), no server or API change, and no change to the channel's routes or shapes (spec §6.5).
- **CoffeePDF's names**, from CoffeePDF's code (spec §4):

  | What | Value |
  |---|---|
  | App name (record, check-out) | `CoffeePDF` |
  | Bundle identifier | `com.healdan.coffeepdf` |
  | Bundle | `CoffeePDF.app`, in `/Applications` or `~/Applications`, or wherever LaunchServices finds the identifier |
  | Process | `CoffeePDF` |
  | Lock file beside an editable copy | `.~coffeepdf.<copy file name>#` (13 bytes added; `CopyNames.MaxBytes` stays 241) |
  | Temporary file of a save | `.coffeepdf-` + 32 lowercase hex digits + `.tmp`, in the copy's folder, renamed over the copy |
  | Settings folder | `COFFEEPDF_SETTINGS_DIR` when set, else `~/Library/Application Support/CoffeePDF/` on macOS, else `$XDG_CONFIG_HOME/coffeepdf/` (or `~/.config/coffeepdf/`) |
  | Healdan support on | `settings.json`'s `healdan.enabled` is the JSON value `true`; anything else, or no readable file, is off |

- **Available** = installed and support on, both read at each choice (spec §4.2).
- **The user's words** (spec §4.4, §5.1, §5.2):
  - Settings' PDFs row: `CoffeePDF`, or `Default app, read-only`; with CoffeePDF installed and support off, the note *Turn on Healdan support in CoffeePDF's Settings to edit PDFs there.*
  - The Open window's note for a PDF without CoffeePDF: *PDFs open read-only in the computer's default app. Turn on Healdan support in CoffeePDF to edit them there.*
  - A link's refusal: *… wasn't opened: a link opens only documents, spreadsheets, presentations, drawings and PDFs. Open it from the web page, or from Open from Healdan.*
- **Docs** follow the Writing Docs page (`site/content/developer/writing-docs.md`): Title Case for titles, headings and labels, British spelling, Healdan's glossary terms. They name CoffeePDF and link to [coffeemuse/CoffeePDF](https://github.com/coffeemuse/CoffeePDF) (spec §10.3).
- **The release** is Healdan Helper 0.5.0, after 0.5.0-rc builds for the walkthrough (spec §12).

## Review Focus

These are the inputs most likely to hurt a person using M3.6 that no task's main tests reach. Each one has a test in the task named.

1. **A PDF checked in from under CoffeePDF's window.** The record must name CoffeePDF however the copy came, by an open, a link or Save to Healdan, so the close watch and a restart look for CoffeePDF's lock, not LibreOffice's (Tasks 2, 5, 6).
2. **A save lost.** CoffeePDF's rename must count as one save of the copy and never send the temporary file, and the start's sweep must never delete a temporary file while CoffeePDF runs (Task 5).
3. **A copy deleted with changes in it.** The default app's `/v1/closed` and the start's sweep touch only copies whose bytes are Healdan's, and CoffeePDF's exact leftovers (Tasks 4, 5).
4. **Other clients.** `readOnly`'s new meaning reaches the Office add-in's page, as decided, but not the Helper's own windows or its crash handling (Task 6).

## Spec corrections and calls this plan makes

The owner hears about these when this plan is handed over. Task 8 folds them into the spec, each marked "changed in the build".

1. **The Open window's rule is tested in the core,** through `AppChooser.OpensReadOnly`, not by a headless window test (spec §11.1). The window takes the rule as a function and has no headless harness of its own.
2. **`HealdanOpenInAppTest` joins `UPSTREAM_TESTS`** (spec §10.1 says it's unchanged). `logicaldoc-gui`'s tests run in CI only when named there, as `HealdanNamesTest` is. No upstream code changes; the workflow is fork-owned.
3. **`"replaces": null` already has a test:** `ChannelHandlerTests.FileBody` sends it in every filing test. Only `"path": null` on `/v1/save-new` gets a new one.
4. **`CopyCycle.ClosedAsync` returns `Task<bool>`,** whether the cycle had anything to do, so the route logs a report it dropped (spec §5.5).
5. **`/v1/file`'s `app` is resolved by `OfficeApps.Filing(name)`,** reached through a new `CopyCycle.Apps`.
6. **`AppChooser` takes Healdan support as a `bool`,** read when the chooser is made, which `OfficeAppSet.Chooser` does at each choice.

## Conventions

- **Running the Helper's tests:** `dotnet test clients/helper/Healdan.Helper.sln`; one class with `--filter "FullyQualifiedName~<Class>"`, several with `|`. The window tests (`[MacFact]`) run only on a Mac.
- **Running a `logicaldoc-gui` test:** with the GUI's siblings installed (`mvn -B -ntp -pl logicaldoc-util -DskipTests install`), `mvn -B -ntp -pl logicaldoc-gui test -Dgwt.skipCompilation=true -Dtest=<Class>`.
- **The docs site's checks:** `python3 -m unittest discover -s .github/scripts/tests`, `hugo --source site --gc --minify --panicOnWarning`, `python3 .github/scripts/check-site-sources.py site` and `python3 .github/scripts/check-site-links.py site/public`.
- **The quick checks:** `python3 .github/scripts/check-logicaldoc-doc-links.py` and `python3 .github/scripts/check-logicaldoc-hosts.py`.
- **Commits:** one per task, in the repo's style: an imperative subject, a body that says why, and the `Co-Authored-By` trailer.
- **Docs ship in the task that changes the behaviour** (CLAUDE.md).
- **Test doubles** live in `Healdan.Helper.Core.Tests/Cycle/`. Tests wait for conditions with `Wait.Until`, never for fixed times, except `Quiet` after a save to show a second one didn't come.

## PRs

Both target `master`.

- **PR A, Tasks 1–8:** the Helper, its docs and the records. CI's `Clients` check runs the Helper's tests on Ubuntu and macOS; the `Docs site` check builds the site.
- **PR B, Task 9:** the web page's Open in App for PDFs, merged once Healdan Helper 0.5.0-rc1 exists (spec §12).
- **Task 10:** the release candidates, the owner's walkthrough and Healdan Helper 0.5.0.

---

### Task 1: CoffeePDF's files beside the copies

The Helper names an app's lock and owner files in four places. Each learns CoffeePDF's lock, through one list, and the stray sweep learns CoffeePDF's temporary files (spec §4.5).

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyNames.cs` (`MaxBytes`' comment; new `CoffeePdfLockName`, `AppFiles`, `IsCoffeePdfTemporary`; `IsAppFile`)
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyStore.cs` (`Moved` :177–179, `Delete` :193–195, `IsOpen` :545–547)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Copies/CopyNamesTests.cs`, `CopyStoreTests.cs`

**Interfaces:**
- Consumes: nothing new.
- Produces (all `internal` in `CopyNames`, which the tests reach through `InternalsVisibleTo`):

```csharp
internal static string CoffeePdfLockName(string copyFileName);           // ".~coffeepdf.<name>#"
internal static IEnumerable<string> AppFiles(string copyFileName);        // LibreOffice's lock, CoffeePDF's lock, Office's owner files
internal static bool IsCoffeePdfTemporary(string fileName);               // ".coffeepdf-" + 32 lowercase hex + ".tmp"
internal static bool IsAppFile(string fileName);                          // now also CoffeePDF's lock and temporary files
```

- [ ] **Step 1: Write the failing tests**

In `CopyNamesTests`:

```csharp
    [Fact]
    public void AppFilesAreEveryAppsLockAndOwnerFiles()
    {
        const string name = "Signed NDA #7 v1.0.pdf";
        var files = CopyNames.AppFiles(name).ToList();

        Assert.Contains(".~lock.Signed NDA #7 v1.0.pdf#", files);
        Assert.Contains(".~coffeepdf.Signed NDA #7 v1.0.pdf#", files);
        Assert.All(CopyNames.OfficeOwnerNames(name), owner => Assert.Contains(owner, files));
        Assert.Equal(files.Distinct(), files);
        Assert.All(files, file => Assert.True(CopyNames.IsAppFile(file), file));
    }

    [Theory]
    [InlineData(".~coffeepdf.Signed NDA #7 v1.0.pdf#", true)]
    [InlineData(".coffeepdf-0123456789abcdef0123456789abcdef.tmp", true)]
    [InlineData(".coffeepdf-0123456789ABCDEF0123456789ABCDEF.tmp", false)] // CoffeePDF's Guid "N" is lower case
    [InlineData(".coffeepdf-0123456789abcdef.tmp", false)]
    [InlineData(".coffeepdf-0123456789abcdef0123456789abcdef0.tmp", false)]
    [InlineData(".coffeepdf-0123456789abcdef0123456789abcdef.pdf", false)]
    [InlineData("coffeepdf-0123456789abcdef0123456789abcdef.tmp", false)]
    [InlineData(".~coffeepdf.Signed NDA #7 v1.0.pdf", false)]
    [InlineData("Signed NDA #7 v1.0.pdf", false)]
    public void CoffeePdfsFilesAreAppFiles(string fileName, bool appFile) =>
        Assert.Equal(appFile, CopyNames.IsAppFile(fileName));

    [Fact]
    public void CoffeePdfsLockFitsTheLongestCopyName()
    {
        var longest = CopyNames.FileName(new string('a', 400) + ".pdf", 123456, "12.34", readOnly: false);
        Assert.True(Encoding.UTF8.GetByteCount(CopyNames.CoffeePdfLockName(longest)) <= 255);
    }
```

In `CopyStoreTests`:

```csharp
    [Fact]
    public void DeleteRemovesCoffeePdfsLockFile()
    {
        var store = Store();
        var (copy, _, _) = store.Create(Doc(1, "Signed NDA.pdf"), false, "CoffeePDF");
        File.WriteAllText(copy, "a pdf");
        var lockFile = Path.Combine(Paths.Copies, ".~coffeepdf." + Path.GetFileName(copy) + "#");
        File.WriteAllText(lockFile, """{"pid": 1}"""); // a crash left it

        store.Delete(copy);

        Assert.False(File.Exists(lockFile));
    }

    [Fact]
    public void OpenToSetAsideSeesCoffeePdfsLockFile()
    {
        var pdf = Write(A, 1, "Signed NDA.pdf");
        var closed = Write(A, 2, "Closed.pdf");
        File.WriteAllText(Path.Combine(Paths.Copies, ".~coffeepdf." + Path.GetFileName(pdf) + "#"), "open in CoffeePDF");

        var open = Store(B).OpenToSetAside(B, A);

        Assert.Equal([Path.GetFileName(pdf)], open.Select(c => c.FileName));
        Assert.True(File.Exists(closed));
    }

    [Fact]
    public void CoffeePdfsFilesAreNotStraysAtASignIn()
    {
        Directory.CreateDirectory(Paths.Copies);
        var temp = Path.Combine(Paths.Copies, ".coffeepdf-0123456789abcdef0123456789abcdef.tmp");
        var lockFile = Path.Combine(Paths.Copies, ".~coffeepdf.Gone #4 v1.0.pdf#");
        File.WriteAllText(temp, "half a save");
        File.WriteAllText(lockFile, "a crash's");

        Assert.Empty(Store(B).SetAside(B, A)); // nothing to announce for them alone

        Assert.True(File.Exists(temp));
        Assert.True(File.Exists(lockFile));
        Assert.False(Directory.Exists(Path.Combine(_root, "Copies from healdan.example")));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CopyNamesTests|FullyQualifiedName~CopyStoreTests"`
Expected: FAIL to compile (`CoffeePdfLockName`, `AppFiles` don't exist).

- [ ] **Step 3: Teach `CopyNames` CoffeePDF's files**

In `CopyNames.cs`, the `MaxBytes` summary's second sentence becomes: "A file name holds 255, and the record's name adds 14 (`.` and `.healdan.json`), LibreOffice's lock file 8 and CoffeePDF's 13, so 241 leaves each room." Beside `LibreOfficeLockName`:

```csharp
    /// <summary>CoffeePDF's lock file for a copy, which it keeps while the copy's window is open (CoffeePDF spec §6.8).</summary>
    internal static string CoffeePdfLockName(string copyFileName) => $".~coffeepdf.{copyFileName}#";

    /// <summary>
    /// Every lock or owner file an app may keep beside a copy (M3.6 spec §4.5): LibreOffice's and CoffeePDF's lock files,
    /// and each owner file Word, Excel or PowerPoint may keep (<see cref="OfficeOwnerNames"/>). A copy deleted, renamed or
    /// set aside looks at these, and a new app adds its own here.
    /// </summary>
    internal static IEnumerable<string> AppFiles(string copyFileName) =>
        [LibreOfficeLockName(copyFileName), CoffeePdfLockName(copyFileName), .. OfficeOwnerNames(copyFileName)];

    private const string CoffeePdfTemporaryPrefix = ".coffeepdf-";

    private const string CoffeePdfTemporarySuffix = ".tmp";

    /// <summary>
    /// CoffeePDF's temporary file for a save (its <c>AtomicFile</c>), written beside the copy and renamed over it:
    /// <c>.coffeepdf-</c>, 32 lowercase hex digits and <c>.tmp</c>.
    /// </summary>
    internal static bool IsCoffeePdfTemporary(string fileName) =>
        fileName.Length == CoffeePdfTemporaryPrefix.Length + 32 + CoffeePdfTemporarySuffix.Length
        && fileName.StartsWith(CoffeePdfTemporaryPrefix, StringComparison.Ordinal)
        && fileName.EndsWith(CoffeePdfTemporarySuffix, StringComparison.Ordinal)
        && fileName.AsSpan(CoffeePdfTemporaryPrefix.Length, 32).IndexOfAnyExcept("0123456789abcdef") < 0;
```

`IsAppFile` becomes:

```csharp
    /// <summary>
    /// Whether a file in the folder is an app's own: LibreOffice's or CoffeePDF's lock file, an owner file of Word, Excel or
    /// PowerPoint, or CoffeePDF's temporary file for a save. The app deletes it by the path it opened, so a copy set aside
    /// leaves it where it is (spec §5.4, M3.6 spec §4.5).
    /// </summary>
    internal static bool IsAppFile(string fileName) =>
        (fileName.StartsWith(".~lock.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || (fileName.StartsWith(".~coffeepdf.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || fileName.StartsWith("~$", StringComparison.Ordinal)
        || IsCoffeePdfTemporary(fileName);
```

- [ ] **Step 4: Make `CopyStore` use the one list**

In `Moved`, replace the LibreOffice lock line and the `OfficeOwnerNames` loop with:

```csharp
        foreach (var file in CopyNames.AppFiles(oldName))
            File.Delete(System.IO.Path.Combine(paths.Copies, file));
```

In `Delete`, the same with `name`. `IsOpen` becomes:

```csharp
    /// <summary>Whether an app's lock or owner file beside the copy says the app has it open there (<see cref="CopyNames.AppFiles"/>).</summary>
    private static bool IsOpen(string folder, string name) =>
        CopyNames.AppFiles(name).Any(file => File.Exists(System.IO.Path.Combine(folder, file)));
```

Update the `ToSetAside` and `SetAside` summaries' "an app's lock and owner files" to "an app's lock, owner and temporary files".

- [ ] **Step 5: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 6: Commit** — "Know CoffeePDF's lock and temporary files beside the copies"

---

### Task 2: CoffeePDF in the set of apps

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Cycle/CoffeePdfApp.cs`, `clients/helper/Healdan.Helper.Core/Settings/PdfFiles.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Settings/AppBundles.cs` (a constant), `Cycle/OfficeApps.cs` (a fifth parameter, `CoffeePdf`, `Filing`, `All`), `Cycle/CopyCycle.cs` (`Apps`)
- Create (tests): `Healdan.Helper.Core.Tests/Cycle/CoffeePdfAppTests.cs`, `Cycle/FakeCoffeePdf.cs`, `Settings/PdfFilesTests.cs`
- Modify (tests): `Cycle/OfficeAppsTests.cs`, `Cycle/TestApps.cs`

**Interfaces:**
- Consumes: Task 1's `CopyNames.CoffeePdfLockName`.
- Produces:

```csharp
public sealed class CoffeePdfApp(Func<string, bool> processRunning, Action<string>? open = null) : IOfficeApp
{
    public const string AppName = "CoffeePDF";
    public const string ProcessName = "CoffeePDF";
    // Name, Open (open -b, or the given opener), IsRunning, LockExists; every other member keeps IOfficeApp's default
}

public static class PdfFiles
{
    public static bool Is(string fileName);   // ".pdf", by the file's own extension, in any case
}

public static class AppBundles { public const string CoffeePdf = "com.healdan.coffeepdf"; /* … */ }

public sealed class OfficeApps(IOfficeApp libreOffice, IOfficeApp? microsoftOffice, IOfficeApp? otherFiles,
    Func<string, string> appFor, IOfficeApp? coffeePdf = null)
{
    public IOfficeApp? CoffeePdf { get; }
    public IOfficeApp Filing(string? name);   // CoffeePDF for "CoffeePDF" when there is one, else LibreOffice
    // All: LibreOffice, Microsoft Office, CoffeePDF, the default app, those that aren't null
}

public sealed class CopyCycle { public OfficeApps Apps { get; } /* … */ }

// Tests:
internal static class TestApps
{
    public static OfficeApps Of(IOfficeApp libreOffice, IOfficeApp? otherFiles = null, IOfficeApp? microsoftOffice = null,
        Func<string, string>? appFor = null, IOfficeApp? coffeePdf = null);
}
public sealed class FakeCoffeePdf
{
    public CoffeePdfApp App { get; }
    public IReadOnlyList<string> Opened { get; }
    public bool Running { get; set; }
    public bool WriteLockOnOpen { get; set; }        // true by default
    public static string LockPath(string copy);
    public static void WriteLock(string copy);
    public static void Close(string copy);           // the window closes: its lock goes
    public static string Save(string copy, string content); // CoffeePDF's save; returns the temporary file's path
    public void Crash();
}
```

- [ ] **Step 1: Write the test doubles**

`TestApps.Of` gains the last parameter, and its default chooser sends PDFs to CoffeePDF when there is one:

```csharp
    /// <summary>
    /// What the helper did before Microsoft Office: every kind of file in <paramref name="libreOffice"/>, PDFs in
    /// <paramref name="coffeePdf"/> when there is one, and anything else in <paramref name="otherFiles"/> (in
    /// <paramref name="libreOffice"/> too when there is none).
    /// </summary>
    public static OfficeApps Of(IOfficeApp libreOffice, IOfficeApp? otherFiles = null, IOfficeApp? microsoftOffice = null,
        Func<string, string>? appFor = null, IOfficeApp? coffeePdf = null) =>
        new(libreOffice, microsoftOffice, otherFiles, appFor ?? (name =>
            coffeePdf is not null && PdfFiles.Is(name) ? CoffeePdfApp.AppName
            : FileKinds.OfFile(name) is null ? DefaultApp.AppName : "LibreOffice"), coffeePdf);
```

`FakeCoffeePdf.cs`:

```csharp
using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// CoffeePDF as the cycle sees it: the real <see cref="CoffeePdfApp"/>, with its launch and its process check faked. Opening
/// an editable copy writes CoffeePDF's lock file, as its window does once the PDF has loaded (CoffeePDF spec §6.8), unless
/// <see cref="WriteLockOnOpen"/> is off; a read-only copy gets none. It changes real files in the copies folder, so the
/// watchers see them. Wrap <see cref="App"/> in <see cref="SeenLocks"/> to wait for the watchers.
/// </summary>
public sealed class FakeCoffeePdf
{
    private readonly ConcurrentQueue<string> _opened = new();
    private volatile bool _running;

    public FakeCoffeePdf() => App = new CoffeePdfApp(name => name == CoffeePdfApp.ProcessName && _running, Open);

    public CoffeePdfApp App { get; }

    /// <summary>Every path the cycle asked CoffeePDF to open, in order.</summary>
    public IReadOnlyList<string> Opened => [.. _opened];

    public bool Running
    {
        get => _running;
        set => _running = value;
    }

    /// <summary>False while the PDF is still loading: CoffeePDF writes its lock file once the window has the PDF.</summary>
    public bool WriteLockOnOpen { get; set; } = true;

    private void Open(string path)
    {
        _opened.Enqueue(path);
        _running = true;
        if (WriteLockOnOpen && (OperatingSystem.IsWindows() || (File.GetUnixFileMode(path) & UnixFileMode.UserWrite) != 0))
            WriteLock(path);
    }

    public static string LockPath(string copy) =>
        Path.Combine(Path.GetDirectoryName(copy)!, $".~coffeepdf.{Path.GetFileName(copy)}#");

    public static void WriteLock(string copy) => File.WriteAllText(LockPath(copy), """{"pid": 1, "opened": "2026-11-01T09:00:00Z"}""");

    /// <summary>The window closes: CoffeePDF removes its lock file.</summary>
    public static void Close(string copy) => File.Delete(LockPath(copy));

    /// <summary>CoffeePDF's save (its <c>AtomicFile</c>): a temporary file in the copy's folder, renamed over the copy.</summary>
    /// <returns>The temporary file's path, which no longer exists.</returns>
    public static string Save(string copy, string content)
    {
        var temp = Path.Combine(Path.GetDirectoryName(copy)!, $".coffeepdf-{Guid.NewGuid():N}.tmp");
        File.WriteAllText(temp, content);
        File.Move(temp, copy, overwrite: true);
        return temp;
    }

    /// <summary>CoffeePDF goes away, leaving its lock files behind, or, for a read-only copy, nothing.</summary>
    public void Crash() => _running = false;
}
```

- [ ] **Step 2: Write the failing tests**

`CoffeePdfAppTests.cs`:

```csharp
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
```

`PdfFilesTests.cs`:

```csharp
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

public sealed class PdfFilesTests
{
    [Theory]
    [InlineData("Signed NDA.pdf", true)]
    [InlineData("SCAN.PDF", true)]
    [InlineData("Signed NDA #7 v1.0 (read-only).pdf", true)]
    [InlineData("pdf", false)] // no extension, as in the web page's Open in App
    [InlineData("a.pdf.docx", false)]
    [InlineData("a.pdfx", false)]
    [InlineData("a.", false)]
    public void APdfIsItsOwnExtension(string fileName, bool pdf) => Assert.Equal(pdf, PdfFiles.Is(fileName));
}
```

In `OfficeAppsTests`, beside the other fields: `private readonly CoffeePdfApp _coffee = new(_ => false, _ => { });`, and:

```csharp
    [Fact]
    public void ACopyOpenedInCoffeePdfStaysItsWhateverTheChooserSaysNow()
    {
        var available = true;
        var apps = new OfficeApps(_libreOffice, _microsoft, _other,
            name => name.EndsWith(".pdf") ? (available ? "CoffeePDF" : "Default") : "LibreOffice", _coffee);
        Assert.Same(_coffee, apps.For("a.pdf"));

        available = false; // Healdan support turned off in CoffeePDF

        Assert.Same(_other, apps.For("a.pdf"));
        Assert.Same(_coffee, apps.Of(Record("CoffeePDF")));
        Assert.Same(_coffee, apps.CoffeePdf);
        Assert.Equal<IOfficeApp>([_libreOffice, _microsoft, _coffee, _other], apps.All);
    }

    [Fact]
    public void FilingTakesCoffeePdfAtItsWordAndLibreOfficeOtherwise()
    {
        var apps = new OfficeApps(_libreOffice, _microsoft, _other, _ => "LibreOffice", _coffee);

        Assert.Same(_coffee, apps.Filing("CoffeePDF"));
        Assert.Same(_libreOffice, apps.Filing("LibreOffice"));
        Assert.Same(_libreOffice, apps.Filing(null));
        Assert.Same(_libreOffice, apps.Filing("Microsoft Office")); // the add-in files through its own route
        Assert.Same(_libreOffice, apps.Filing("coffeepdf"));
        Assert.Same(_libreOffice, new OfficeApps(_libreOffice, null, _other, _ => "LibreOffice").Filing("CoffeePDF")); // none off macOS
    }

    [Fact]
    public void ARecordNamingCoffeePdfIsLibreOfficesWhereThereIsNone() =>
        Assert.Same(_libreOffice, new OfficeApps(_libreOffice, null, _other, _ => "LibreOffice").Of(Record("CoffeePDF")));
```

- [ ] **Step 3: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CoffeePdfAppTests|FullyQualifiedName~PdfFilesTests|FullyQualifiedName~OfficeAppsTests"`
Expected: FAIL to compile.

- [ ] **Step 4: Write `PdfFiles`, the bundle identifier and `CoffeePdfApp`**

`Settings/PdfFiles.cs`:

```csharp
namespace Healdan.Helper.Core.Settings;

/// <summary>
/// PDFs (M3.6 spec §4.3), which CoffeePDF opens: not a <see cref="FileKind"/>, as kinds drive Settings' office choices, the
/// files' icons and LibreOffice as the fallback, and none of those take PDFs in.
/// </summary>
public static class PdfFiles
{
    /// <summary>Whether the file is a PDF, by its own extension in any case. A name with no extension, such as <c>pdf</c>, isn't.</summary>
    public static bool Is(string fileName) => Path.GetExtension(fileName).Equals(".pdf", StringComparison.OrdinalIgnoreCase);
}
```

In `AppBundles`, after `LibreOffice`:

```csharp
    /// <summary>
    /// CoffeePDF's, as its Info.plist names it (CoffeePDF spec §4.3). <see cref="NameOf"/> doesn't know it: it names the
    /// system's default app for an office kind's main format, which CoffeePDF never is.
    /// </summary>
    public const string CoffeePdf = "com.healdan.coffeepdf";
```

`Cycle/CoffeePdfApp.cs`:

```csharp
using System.Diagnostics;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Cycle;

/// <summary>
/// CoffeePDF (M3.6 spec §4.1), the PDF app for macOS that works with Healdan through the helper's channel: opened by its
/// bundle identifier with <c>open -b</c>, its process is <c>CoffeePDF</c>, and it keeps
/// <c>.~coffeepdf.&lt;copy file name&gt;#</c> beside a copy it has open for editing (CoffeePDF spec §6.8). It restores
/// nothing after a crash, so it keeps <see cref="IOfficeApp"/>'s other defaults: a crash gets the crash window LibreOffice's
/// copies get, with no recovery watch, and the check-out names it <c>CoffeePDF</c>.
/// </summary>
/// <param name="processRunning">Whether a process of that name runs, as the platform sees it.</param>
/// <param name="open">Opens a copy in CoffeePDF; null for <c>open -b</c>. Tests pass their own.</param>
public sealed class CoffeePdfApp(Func<string, bool> processRunning, Action<string>? open = null) : IOfficeApp
{
    private static readonly TimeSpan OpenTimeout = TimeSpan.FromSeconds(10);

    /// <summary>What a copy's record, and the check-out, name as its app.</summary>
    public const string AppName = "CoffeePDF";

    /// <summary>Its process's name on macOS, the executable in <c>CoffeePDF.app/Contents/MacOS</c>.</summary>
    public const string ProcessName = "CoffeePDF";

    public string Name => AppName;

    public void Open(string path)
    {
        if (open is not null)
        {
            open(path);
            return;
        }
        if (!OperatingSystem.IsMacOS())
            throw new IOException("CoffeePDF runs only on macOS.");
        // open hands the file to CoffeePDF, which keeps one instance, and returns; a non-zero exit means it couldn't.
        var start = new ProcessStartInfo("open") { UseShellExecute = false, ArgumentList = { "-b", AppBundles.CoffeePdf, path } };
        using var launch = Process.Start(start) ?? throw new IOException("CoffeePDF couldn't be started.");
        if (!launch.WaitForExit(OpenTimeout))
            return;
        if (launch.ExitCode != 0)
            throw new IOException($"CoffeePDF couldn't open the file (open exited with {launch.ExitCode}).");
    }

    public bool IsRunning() => processRunning(ProcessName);

    public bool LockExists(string path) =>
        File.Exists(Path.Combine(Path.GetDirectoryName(path)!, CopyNames.CoffeePdfLockName(Path.GetFileName(path))));
}
```

- [ ] **Step 5: Put CoffeePDF in `OfficeApps`, and give the cycle's apps to the channel**

`OfficeApps`' summary adds: "CoffeePDF (M3.6 spec §4.3) is in the set whatever its availability, so a copy opened in it stays its." Its signature and new members:

```csharp
/// <param name="coffeePdf">CoffeePDF (<see cref="CoffeePdfApp"/>); null where it can't be installed.</param>
public sealed class OfficeApps(IOfficeApp libreOffice, IOfficeApp? microsoftOffice, IOfficeApp? otherFiles, Func<string, string> appFor,
    IOfficeApp? coffeePdf = null)
{
    // … LibreOffice, MicrosoftOffice as they are …

    /// <summary>CoffeePDF, whatever its availability now; null where it can't be installed.</summary>
    public IOfficeApp? CoffeePdf => coffeePdf;

    /// <summary>Every app in the set, LibreOffice first.</summary>
    public IReadOnlyList<IOfficeApp> All { get; } =
        new[] { libreOffice, microsoftOffice, coffeePdf, otherFiles }.OfType<IOfficeApp>().ToList();

    /// <summary>
    /// The app whose window a document filed through the channel's <c>/v1/file</c> is in, by the <c>app</c> its caller names
    /// (M3.6 spec §6.2): CoffeePDF for <c>CoffeePDF</c>, and LibreOffice, the extension's, for anything else or nothing.
    /// </summary>
    public IOfficeApp Filing(string? name) => name == CoffeePdfApp.AppName && coffeePdf is not null ? coffeePdf : libreOffice;

    // … For, Of, IsOtherFiles, Named as they are …
}
```

In `CopyCycle`, beside the constructor:

```csharp
    /// <summary>The apps copies open in, which the channel's filing names its app from (M3.6 spec §6.2).</summary>
    public OfficeApps Apps => _apps;
```

- [ ] **Step 6: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 7: Commit** — "Add CoffeePDF to the helper's set of apps"

---

### Task 3: Whether CoffeePDF is available, and the chooser

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Settings/CoffeePdfSettings.cs`
- Modify: `Settings/InstalledApps.cs` (`CoffeePdf`), `Settings/AppChooser.cs` (a fourth parameter, PDFs, `CoffeePdfAvailable`, `CoffeePdfSupportOff`, `OpensReadOnly`)
- Modify: `clients/helper/Healdan.Helper/Platform/OfficeAppSet.cs` (`Chooser`, `Create`)
- Create (tests): `Healdan.Helper.Core.Tests/Settings/CoffeePdfSettingsTests.cs`
- Modify (tests): `Settings/InstalledAppsTests.cs`, `Settings/AppChooserTests.cs`

**Interfaces:**
- Consumes: Task 2's `CoffeePdfApp`, `PdfFiles`, `AppBundles.CoffeePdf`, `OfficeApps(…, coffeePdf)`.
- Produces:

```csharp
public static class CoffeePdfSettings
{
    public const string FolderVariable = "COFFEEPDF_SETTINGS_DIR";
    public static string Folder();
    public static bool HealdanEnabled(string folder);
}

public sealed record InstalledApps(bool LibreOffice, bool Word, bool Excel, bool PowerPoint, bool CoffeePdf = false);

public sealed class AppChooser(HelperSettings settings, InstalledApps installed, Func<string, string?> systemDefaultApp,
    bool coffeePdfOn = false)
{
    public string For(string fileName);          // a .pdf: "CoffeePDF" while available, else "Default"
    public bool CoffeePdfAvailable { get; }      // installed and on
    public bool CoffeePdfSupportOff { get; }     // installed and off
    public bool OpensReadOnly(string fileName);  // For(fileName) is "Default"
}
```

- [ ] **Step 1: Write the failing tests**

`CoffeePdfSettingsTests.cs`:

```csharp
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Settings;

/// <summary>CoffeePDF's Healdan support, as the helper reads it from CoffeePDF's settings.json (M3.6 spec §4.2).</summary>
public sealed class CoffeePdfSettingsTests : IDisposable
{
    private readonly string _folder = Directory.CreateTempSubdirectory("healdan-coffeepdf-settings-").FullName;

    public void Dispose() => Directory.Delete(_folder, true);

    private void Write(string json) => File.WriteAllText(Path.Combine(_folder, "settings.json"), json);

    [Fact]
    public void OnWhenEnabledIsTrue()
    {
        Write("""{ "healdan": { "enabled": true, "asked": true }, "markup": { "author": "Test Reviewer" } }""");

        Assert.True(CoffeePdfSettings.HealdanEnabled(_folder));
    }

    [Theory]
    [InlineData("""{ "healdan": { "enabled": false, "asked": true } }""")]
    [InlineData("""{ "healdan": { "asked": true } }""")]
    [InlineData("""{ "healdan": { "enabled": "true" } }""")]
    [InlineData("""{ "healdan": { "enabled": 1 } }""")]
    [InlineData("""{ "healdan": { "enabled": null } }""")]
    [InlineData("""{ "healdan": true }""")]
    [InlineData("""{ "markup": { "author": "Test Reviewer" } }""")]
    [InlineData("""[true]""")]
    [InlineData("""{ "healdan": { "enabled": tru""")]
    [InlineData("")]
    public void OffForAnythingElse(string json)
    {
        Write(json);

        Assert.False(CoffeePdfSettings.HealdanEnabled(_folder));
    }

    [Fact]
    public void OffWithNoFileOrFolder()
    {
        Assert.False(CoffeePdfSettings.HealdanEnabled(_folder));
        Assert.False(CoffeePdfSettings.HealdanEnabled(Path.Combine(_folder, "missing")));
    }

    [Fact]
    public void TheFolderIsFoundAsCoffeePdfFindsIt()
    {
        var before = Environment.GetEnvironmentVariable(CoffeePdfSettings.FolderVariable);
        try
        {
            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, _folder);
            Assert.Equal(_folder, CoffeePdfSettings.Folder());

            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, null);
            var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (OperatingSystem.IsMacOS())
                Assert.Equal(Path.Combine(home, "Library", "Application Support", "CoffeePDF"), CoffeePdfSettings.Folder());
            else
                Assert.Equal("coffeepdf", Path.GetFileName(CoffeePdfSettings.Folder()));
        }
        finally
        {
            Environment.SetEnvironmentVariable(CoffeePdfSettings.FolderVariable, before);
        }
    }
}
```

In `InstalledAppsTests`:

```csharp
    [Fact]
    public void CoffeePdfIsFoundLikeTheOthers()
    {
        Assert.True(OnMac("/Applications/CoffeePDF.app").CoffeePdf);
        Assert.True(OnMac($"{Home}/Applications/CoffeePDF.app").CoffeePdf);
        Assert.True(WhereverTheyAre("com.healdan.coffeepdf").CoffeePdf);
        Assert.False(OnMac("/Applications/LibreOffice.app").CoffeePdf);
        Assert.False(InstalledApps.Detect(macOS: false, "/home/user", _ => true, _ => true).CoffeePdf); // never off macOS
    }
```

In `AppChooserTests`, beside `Everything`: `private static readonly InstalledApps WithCoffeePdf = Everything with { CoffeePdf = true };` and:

```csharp
    private static AppChooser ChooserWithCoffeePdf(bool installed, bool on) =>
        new(new HelperSettings(), installed ? WithCoffeePdf : Everything, _ => null, coffeePdfOn: on);

    [Theory]
    [InlineData("Signed NDA.pdf")]
    [InlineData("SCAN.PDF")]
    public void APdfOpensInCoffeePdfOnlyWhileItsInstalledAndOn(string fileName)
    {
        Assert.Equal("CoffeePDF", ChooserWithCoffeePdf(installed: true, on: true).For(fileName));
        Assert.Equal("Default", ChooserWithCoffeePdf(installed: true, on: false).For(fileName));
        Assert.Equal("Default", ChooserWithCoffeePdf(installed: false, on: true).For(fileName)); // a settings file left by an uninstalled CoffeePDF
    }

    [Fact]
    public void CoffeePdfChangesNothingElse()
    {
        var chooser = ChooserWithCoffeePdf(installed: true, on: true);

        Assert.Equal("LibreOffice", chooser.For("a.docx"));
        Assert.Equal("LibreOffice", chooser.For("a.odg"));
        Assert.Equal("Default", chooser.For("photo.png"));
        Assert.Equal("Default", chooser.For("pdf"));
    }

    [Fact]
    public void WhatSettingsSayOfCoffeePdf()
    {
        Assert.True(ChooserWithCoffeePdf(installed: true, on: true).CoffeePdfAvailable);
        Assert.False(ChooserWithCoffeePdf(installed: true, on: true).CoffeePdfSupportOff);
        Assert.False(ChooserWithCoffeePdf(installed: true, on: false).CoffeePdfAvailable);
        Assert.True(ChooserWithCoffeePdf(installed: true, on: false).CoffeePdfSupportOff);
        Assert.False(ChooserWithCoffeePdf(installed: false, on: false).CoffeePdfSupportOff); // not installed: nothing to turn on
        Assert.False(ChooserWithCoffeePdf(installed: false, on: true).CoffeePdfAvailable);
    }

    [Fact]
    public void ReadOnlyIsWhateverGoesToTheDefaultApp()
    {
        Assert.False(ChooserWithCoffeePdf(installed: true, on: true).OpensReadOnly("a.pdf"));
        Assert.True(ChooserWithCoffeePdf(installed: true, on: false).OpensReadOnly("a.pdf"));
        Assert.True(ChooserWithCoffeePdf(installed: true, on: true).OpensReadOnly("photo.png"));
        Assert.False(ChooserWithCoffeePdf(installed: true, on: true).OpensReadOnly("a.docx"));
        // Neither LibreOffice nor Office: the cycle opens an office file read-only in the default app, so the window says so.
        Assert.True(Chooser(installed: new InstalledApps(false, false, false, false)).OpensReadOnly("a.docx"));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CoffeePdfSettingsTests|FullyQualifiedName~InstalledAppsTests|FullyQualifiedName~AppChooserTests"`
Expected: FAIL to compile.

- [ ] **Step 3: Write `CoffeePdfSettings`**

```csharp
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Healdan.Helper.Core.Settings;

/// <summary>
/// Whether CoffeePDF's Healdan support is on (M3.6 spec §4.2), from its <c>settings.json</c>, whose shape CoffeePDF keeps
/// as a contract: <c>{ "healdan": { "enabled": true, … }, … }</c>. The helper reads only <c>healdan.enabled</c>, never
/// writes the file, and reads it at each choice, so turning support on in CoffeePDF takes effect with the next PDF opened.
/// </summary>
public static class CoffeePdfSettings
{
    /// <summary>Overrides CoffeePDF's settings folder, as CoffeePDF itself takes it, for tests and development.</summary>
    public const string FolderVariable = "COFFEEPDF_SETTINGS_DIR";

    /// <summary>
    /// CoffeePDF's settings folder, found as CoffeePDF finds it (its <c>SettingsLocation</c>): <see cref="FolderVariable"/>
    /// when it's set, else <c>~/Library/Application Support/CoffeePDF/</c> on macOS, and <c>$XDG_CONFIG_HOME/coffeepdf/</c>
    /// (or <c>~/.config/coffeepdf/</c>) elsewhere.
    /// </summary>
    public static string Folder()
    {
        if (Environment.GetEnvironmentVariable(FolderVariable) is { Length: > 0 } overridden)
            return overridden;
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (OperatingSystem.IsMacOS())
            return Path.Combine(home, "Library", "Application Support", "CoffeePDF");
        var config = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME") is { Length: > 0 } xdg ? xdg : Path.Combine(home, ".config");
        return Path.Combine(config, "coffeepdf");
    }

    /// <summary>
    /// Whether <c>healdan.enabled</c> is the JSON value <c>true</c> in the folder's <c>settings.json</c>. A missing,
    /// unreadable or malformed file, or any other value, is off.
    /// </summary>
    public static bool HealdanEnabled(string folder)
    {
        try
        {
            using var stream = File.OpenRead(Path.Combine(folder, "settings.json"));
            return JsonNode.Parse(stream) is JsonObject root && root["healdan"] is JsonObject healdan
                && healdan["enabled"] is JsonValue enabled && enabled.GetValueKind() == JsonValueKind.True;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException or ArgumentException)
        {
            return false;
        }
    }
}
```

- [ ] **Step 4: Find CoffeePDF, and choose it for PDFs**

`InstalledApps` gains `bool CoffeePdf = false` as its last positional member, documented "Whether CoffeePDF is installed (M3.6 spec §4.2); never off macOS". The macOS branch of the internal `Detect` adds:

```csharp
            CoffeePdf: directoryExists("/Applications/CoffeePDF.app") || directoryExists(Path.Combine(home, "Applications", "CoffeePDF.app"))
                || Found(AppBundles.CoffeePdf));
```

The other branch is unchanged (`CoffeePdf` stays false). The summary of `Detect(bool, …)` names CoffeePDF beside LibreOffice.

`AppChooser` gains the fourth parameter, documented "Whether CoffeePDF's Healdan support is on (<see cref="CoffeePdfSettings"/>), read when the chooser is made". `For` starts with the PDF case, before the kind check:

```csharp
    public string For(string fileName)
    {
        if (PdfFiles.Is(fileName))
            return CoffeePdfAvailable ? CoffeePdfApp.AppName : DefaultApp.AppName;
        if (FileKinds.OfFile(fileName) is not { } kind)
            return DefaultApp.AppName;
        // … as it is …
    }

    /// <summary>CoffeePDF is installed and its Healdan support is on (M3.6 spec §4.2): new copies of PDFs open in it.</summary>
    public bool CoffeePdfAvailable => installed.CoffeePdf && coffeePdfOn;

    /// <summary>CoffeePDF is installed with its Healdan support off, so Settings say how to turn it on (M3.6 spec §4.4).</summary>
    public bool CoffeePdfSupportOff => installed.CoffeePdf && !coffeePdfOn;

    /// <summary>
    /// Whether a new copy of the file opens read-only, as it goes to the system's default app: what the Open window forces
    /// and says (M3.6 spec §5.2).
    /// </summary>
    public bool OpensReadOnly(string fileName) => For(fileName) == DefaultApp.AppName;
```

The class summary adds: "A PDF is CoffeePDF's while it's installed with Healdan support on, else the default app's (M3.6 spec §4.3)."

- [ ] **Step 5: Wire it into the app**

In `OfficeAppSet`:

```csharp
    /// <summary>
    /// What the chooser says now: the settings, the installed apps, the system's default apps and CoffeePDF's Healdan support
    /// are read at each call, so a change in Settings or CoffeePDF, or a newly installed app, takes effect with the next copy
    /// opened, and Settings show the same answer.
    /// </summary>
    public static AppChooser Chooser(HelperPaths paths) =>
        new(HelperSettings.Load(paths), Installed(), DefaultApps.For, CoffeePdfSettings.HealdanEnabled(CoffeePdfSettings.Folder()));

    public static OfficeApps Create(HelperPaths paths)
    {
        IOfficeApp? microsoftOffice = OperatingSystem.IsMacOS()
            ? new MicrosoftOfficeApp(new MacOfficeScripting(new OsaRunner(), paths.Copies), ProcessRunning)
            : null;
        IOfficeApp? coffeePdf = OperatingSystem.IsMacOS() ? new CoffeePdfApp(ProcessRunning) : null;
        return new OfficeApps(new LibreOfficeApp(), microsoftOffice, new DefaultApp(), name => Chooser(paths).For(name), coffeePdf);
    }
```

The class summary names CoffeePDF.

- [ ] **Step 6: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS. `CopyCycleTests.APdfStillOpensReadOnlyInTheDefaultApp` still passes: its chooser has no CoffeePDF.

- [ ] **Step 7: Commit** — "Open PDFs in CoffeePDF while it's installed with Healdan support on"

---

### Task 4: Opening PDFs in CoffeePDF

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Cycle/CopyCycle.cs` (the link check in `OpenCoreAsync` :699–704; `ClosedAsync` :267–272)
- Modify: `clients/helper/Healdan.Helper.Core/Channel/ChannelHandler.cs` (`closed`: `await` the new return; the log comes in Task 6)
- Create (tests): `Healdan.Helper.Core.Tests/Cycle/CoffeePdfCycleTests.cs`
- Modify (tests): `Cycle/CopyCycleTests.cs` (`ALinkOpensOnlyOfficeKinds`; two default-app tests)
- Modify (docs): `site/content/user/office/opening-documents/index.md` (:137), `site/content/user/office/when-things-go-wrong/index.md` (:186), `site/content/install/office-integration.md` (:66)

**Interfaces:**
- Consumes: Tasks 2 and 3: `CoffeePdfApp`, `PdfFiles`, `TestApps.Of(…, coffeePdf)`, `FakeCoffeePdf`, `SeenLocks`.
- Produces: `public Task<bool> ClosedAsync(string path)` on `CopyCycle`: true when the cycle closed a session or dealt with a default-app copy, false for a path it has nothing to do with. `CoffeePdfCycleTests`' harness, which Task 5 adds to.

- [ ] **Step 1: Write the harness and the failing tests**

`CoffeePdfCycleTests.cs`:

```csharp
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Settings;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// PDFs in CoffeePDF (M3.6 spec §5, §6.1, §7): M3's cycle with CoffeePDF as a copy's app, through the real
/// <see cref="CoffeePdfApp"/> with its launch and process check faked (<see cref="FakeCoffeePdf"/>).
/// </summary>
public sealed class CoffeePdfCycleTests : IDisposable
{
    /// <summary>The trial's timings shortened, in the same order, so the tests run fast.</summary>
    private static readonly CycleTimings Fast = new(
        Settle: TimeSpan.FromMilliseconds(150),
        SizePoll: TimeSpan.FromMilliseconds(25),
        CrashPoll: TimeSpan.FromMilliseconds(100),
        LockPoll: TimeSpan.FromMilliseconds(25),
        RetryDelays: [TimeSpan.FromMilliseconds(50)]);

    /// <summary>Long enough after a save for a second, wrong, upload to have arrived.</summary>
    private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(600);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-coffeepdf-cycle-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeApp _libreOffice = new();
    private readonly FakeOpener _opener = new();
    private readonly FakeCoffeePdf _coffee = new();
    private readonly SeenLocks _coffeePdf;
    private readonly RecordingEvents _events = new();
    private readonly List<CopyCycle> _cycles = [];

    /// <summary>Whether CoffeePDF is installed with Healdan support on, as the chooser reads it at each open.</summary>
    private volatile bool _available = true;

    public CoffeePdfCycleTests()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        _coffeePdf = new SeenLocks(_coffee.App);
    }

    public void Dispose()
    {
        foreach (var cycle in _cycles)
            cycle.Dispose();
        _server.OpenSaves();
        Directory.Delete(_root, true);
    }

    private HelperPaths Paths => HelperPaths.For(_root);

    private CopyStore Store => new(Paths);

    private string CopyPath(string name) => Path.Combine(Paths.Copies, name);

    private string Pdf => CopyPath("Signed NDA #7 v1.0.pdf");

    private string ReadOnlyPdf => CopyPath("Signed NDA #7 v1.0 (read-only).pdf");

    private CopyCycle Cycle()
    {
        var api = new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" };
        var apps = TestApps.Of(_libreOffice, _opener, appFor: name =>
            PdfFiles.Is(name) ? (_available ? CoffeePdfApp.AppName : DefaultApp.AppName)
            : FileKinds.OfFile(name) is null ? DefaultApp.AppName : LibreOfficeApp.AppName, coffeePdf: _coffeePdf);
        var cycle = new CopyCycle(api, new CopyStore(Paths), apps, FakeApi.HereDevice, _events, Fast);
        _cycles.Add(cycle);
        return cycle;
    }

    private static void AssertReadOnlyFile(string path)
    {
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead, File.GetUnixFileMode(path));
    }

    [Fact]
    public async Task APdfOpensInCoffeePdfCheckedOut()
    {
        var cycle = Cycle();

        await cycle.OpenAsync(7, false);

        Assert.Equal([Pdf], _coffee.Opened);
        Assert.Empty(_opener.Opened);
        Assert.Empty(_libreOffice.Opened);
        Assert.Equal("a pdf", File.ReadAllText(Pdf));
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold); // "checked out to you on … (CoffeePDF)"
        var record = Store.Find(Pdf)!.Value.Item2;
        Assert.Equal("CoffeePDF", record.App);
        Assert.True(record.CheckedOutHere);
        Assert.Equal(new CopyState(true, 7, "Signed NDA #7 v1.0", "#7 v1.0", false, Pdf, null), cycle.StateOf(Pdf));
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task APdfLinkOpensInCoffeePdf()
    {
        var cycle = Cycle();

        await cycle.OpenLinkAsync(7, false);

        Assert.Equal([Pdf], _coffee.Opened);
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task APdfLinkWithoutCoffeePdfOpensReadOnlyInTheDefaultApp()
    {
        _available = false;

        await Cycle().OpenLinkAsync(7, false);

        Assert.Equal([ReadOnlyPdf], _opener.Opened);
        Assert.Empty(_coffee.Opened);
        Assert.Equal(0, _server.Count("POST /documents/7/checkout"));
        AssertReadOnlyFile(ReadOnlyPdf);
        Assert.Empty(_events.Errors);
    }

    [Fact]
    public async Task AReadOnlyOpenIsTrackedInCoffeePdf()
    {
        var cycle = Cycle();

        await cycle.OpenLinkAsync(7, true);

        Assert.Equal([ReadOnlyPdf], _coffee.Opened);
        Assert.Equal(0, _server.Count("POST /documents/7/checkout"));
        AssertReadOnlyFile(ReadOnlyPdf);
        Assert.False(File.Exists(FakeCoffeePdf.LockPath(ReadOnlyPdf))); // a read-only copy gets no lock file
        Assert.Equal([cycle.StateOf(ReadOnlyPdf)], cycle.OpenCopies()); // unlike the default app's, it has a session
    }

    [Fact]
    public async Task AReadOnlyCopyInCoffeePdfGoesWhenItsWindowCloses()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, true);

        Assert.True(await cycle.ClosedAsync(ReadOnlyPdf));

        await Wait.Until(() => !File.Exists(ReadOnlyPdf), "the read-only copy deleted");
        Assert.Null(Store.Find(ReadOnlyPdf));
        Assert.False(cycle.StateOf(ReadOnlyPdf).Known);
    }

    [Fact]
    public async Task AReadOnlyCopyGoesWhenCoffeePdfQuits()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, true);
        await Wait.Until(() => _coffeePdf.RunningSeen(ReadOnlyPdf), "the crash check sees CoffeePDF running");

        _coffee.Crash(); // gone with no lock file: for a read-only copy, a close

        await Wait.Until(() => !File.Exists(ReadOnlyPdf), "the read-only copy deleted");
        Assert.Empty(_events.Crashes);
    }

    [Fact]
    public async Task HeldByAnotherPersonItOpensReadOnlyInCoffeePdf()
    {
        _server.Change(7, d => d.Hold = FakeApi.OtherPerson());
        var cycle = Cycle();

        await cycle.OpenAsync(7, false);

        Assert.Equal([ReadOnlyPdf], _coffee.Opened);
        Assert.Equal("Pat Example", cycle.StateOf(ReadOnlyPdf).HoldBy); // the read-only bar names the person (spec §2.2)
        Assert.True(cycle.StateOf(ReadOnlyPdf).ReadOnly);
        AssertReadOnlyFile(ReadOnlyPdf);
    }

    [Fact]
    public async Task ACopyOpenWhenSupportIsTurnedOffStaysCoffeePdfs()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen");

        _available = false; // CoffeePDF §5: it takes effect for each copy as its window closes
        await cycle.OpenAsync(7, false);

        Assert.Equal([Pdf, Pdf], _coffee.Opened); // brought to the front, not opened in the default app
        Assert.Empty(_opener.Opened);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen again");
        FakeCoffeePdf.Close(Pdf);
        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
    }
}
```

In `CopyCycleTests`, `ALinkOpensOnlyOfficeKinds` becomes `ALinkOpensOnlyOfficeKindsAndPdfs`:

```csharp
    [Fact]
    public async Task ALinkOpensOnlyOfficeKindsAndPdfs()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        _server.Add(8, "Photo.png", "an image");
        var cycle = Cycle(otherFiles: _opener);

        await cycle.OpenLinkAsync(8, false);

        Assert.Empty(_opener.Opened);
        Assert.Empty(_app.Opened);
        Assert.Equal(0, _server.Count("GET /documents/8/content"));
        Assert.Empty(CopiesFolder());
        var refusal = Assert.Single(_events.Errors);
        Assert.Contains("a link opens only documents, spreadsheets, presentations, drawings and PDFs", refusal);
        Assert.Contains("Open it from the web page, or from Open from Healdan", refusal);

        // A PDF opens from a link: read-only in the default app, as there's no CoffeePDF here (M3.6 spec §5.1).
        await cycle.OpenLinkAsync(7, false);
        Assert.Equal([CopyPath("Signed NDA #7 v1.0 (read-only).pdf")], _opener.Opened);

        // An office kind still opens from a link.
        await cycle.OpenLinkAsync(5, false);
        Assert.Equal([CopyPath("Lease agreement #5 v1.0.docx")], _app.Opened);
    }
```

And, after `TheDefaultAppsCopiesGoAtTheNextStart`:

```csharp
    [Fact]
    public async Task TheDefaultAppsCopyGoesWhenItsCloseIsReported()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        var cycle = Cycle(otherFiles: _opener);
        await cycle.OpenAsync(7, false);
        var path = CopyPath("Signed NDA #7 v1.0 (read-only).pdf");

        Assert.True(await cycle.ClosedAsync(path));

        Assert.False(File.Exists(path)); // Healdan-Project/healdan#134: not left for the next start
        Assert.Empty(CopiesFolder());
    }

    [Fact]
    public async Task TheDefaultAppsCopyWithChangesIsKeptAndAnUnknownPathIgnored()
    {
        _server.Add(7, "Signed NDA.pdf", "a pdf");
        var cycle = Cycle(otherFiles: _opener);
        await cycle.OpenAsync(7, false);
        var path = CopyPath("Signed NDA #7 v1.0 (read-only).pdf");
        File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        File.WriteAllText(path, "changed somehow");

        Assert.True(await cycle.ClosedAsync(path));
        Assert.True(File.Exists(path)); // not Healdan's bytes: kept, as any read-only copy with changes is

        var stranger = CopyPath("Not a copy #9 v1.0.pdf");
        File.WriteAllText(stranger, "someone's file");
        Assert.False(await cycle.ClosedAsync(stranger));
        Assert.True(File.Exists(stranger));
    }
```

(`File.SetUnixFileMode` isn't available on Windows; the Helper's tests run on Ubuntu and macOS, as the existing `AssertReadOnlyFile` assumes.)

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CoffeePdfCycleTests|FullyQualifiedName~CopyCycleTests"`
Expected: FAIL: `ClosedAsync` returns `Task`, not `Task<bool>` (compile error). Once Step 3's signature compiles, the link and default-app tests fail on behaviour.

- [ ] **Step 3: Let links take PDFs, and close the default app's copies**

In `OpenCoreAsync`, the link check becomes:

```csharp
        if (link && FileKinds.OfFile(doc.Name) is null && !PdfFiles.Is(doc.Name))
        {
            Raise(ev => ev.Error($"{doc.Name} wasn't opened: a link opens only documents, spreadsheets, presentations, drawings "
                + "and PDFs. Open it from the web page, or from Open from Healdan."));
            return;
        }
```

`OpenLinkAsync`'s summary says "only of spec §5.11's kinds and PDFs (final review C2, M3.6 spec §5.1)".

`ClosedAsync` becomes:

```csharp
    /// <summary>
    /// An app's close event for the copy's window: checks it in (or releases it) and deletes the copy, in the background. A
    /// read-only copy opened in the default app has no session, as nothing else says when its window closes, so its report
    /// deletes it now, as the next start would (M3.6 spec §5.5); one with changes is kept. False when the cycle has nothing
    /// to do with the path, so the channel can log a report it dropped.
    /// </summary>
    public Task<bool> ClosedAsync(string path)
    {
        bool known;
        lock (_gate)
            known = Find(path) is not null;
        if (known)
        {
            Close(path);
            return Task.FromResult(true);
        }
        if (Record(path) is not { ReadOnly: true } record || !_apps.IsOtherFiles(_apps.Of(record)))
            return Task.FromResult(false);
        if (!_store.HasUnconfirmedChanges(path))
            _store.Delete(path);
        return Task.FromResult(true);
    }
```

`OpenOtherAsync`'s summary changes "nothing watches the copy; `ResumeAsync` deletes it at the next start" to "nothing watches the copy; an app's close report deletes it (`ClosedAsync`), or else `ResumeAsync` at the next start". `DefaultApp`'s summary says the same.

In `ChannelHandler`, the `closed` case keeps its behaviour for now (`await cycle.ClosedAsync(closed);` still compiles, discarding the result); Task 6 adds the log.

- [ ] **Step 4: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 5: Docs**

- `user/office/opening-documents/index.md`, the paragraph at :137: a PDF opens in CoffeePDF, checked out like any document you can edit, when [CoffeePDF](https://github.com/coffeemuse/CoffeePDF) is installed with Healdan support on (in CoffeePDF's Settings); otherwise it opens read-only in your Mac's usual app for PDFs. Other files still open read-only in your Mac's usual app for them. A link opens a PDF too now.
- `user/office/when-things-go-wrong/index.md`, the entry at :186: the message now reads "… a link opens only documents, spreadsheets, presentations, drawings and PDFs", and the link was to some other kind of file.
- `install/office-integration.md`, "Other files" at :66: a PDF opens in CoffeePDF on a Mac where it's installed with Healdan support on; other files, and PDFs elsewhere, open read-only in the Mac's default app, from Open from Healdan or, for a PDF, from a link.

Run the docs site's checks (Conventions). Expected: PASS.

- [ ] **Step 6: Commit** — "Open PDFs from links, track CoffeePDF's read-only copies, and delete a default-app copy on its close"

---

### Task 5: Saves, closes, crashes and restarts in CoffeePDF

The cycle needs no change for most of this; the tests show it, against CoffeePDF's exact patterns. The one change is the start's sweep of CoffeePDF's leftovers (spec §6.4).

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Cycle/CopyCycle.cs` (`ResumeAsync` :279–293: the sweep)
- Modify (tests): `Healdan.Helper.Core.Tests/Cycle/CoffeePdfCycleTests.cs`
- Modify (docs): `site/content/developer/desktop-clients.md` ("The Cycle" and the copies), `site/content/user/office/when-things-go-wrong/index.md` (a crash in CoffeePDF)

**Interfaces:**
- Consumes: Task 1's `CopyNames.IsCoffeePdfTemporary`; Task 2's `OfficeApps.CoffeePdf`; Task 4's harness.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Write the tests**

In `CoffeePdfCycleTests`:

```csharp
    [Fact]
    public async Task CoffeePdfsSaveGoesUpOnceAndItsTemporaryFileNever()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);

        var temp = FakeCoffeePdf.Save(Pdf, "marked up");

        await Wait.Until(() => _server.Saves.Count == 1, "the save");
        await Task.Delay(Quiet);
        Assert.Equal("marked up", Assert.Single(_server.Saves).Content);
        Assert.False(File.Exists(temp));
        Assert.NotEqual("#7 v1.0", (await cycle.WaitForConfirmationAsync(Pdf, TimeSpan.FromSeconds(10))).DocRef);
    }

    [Fact]
    public async Task ALockThatComesLateIsNeitherACloseNorACrash()
    {
        _coffee.WriteLockOnOpen = false; // the PDF still loading (CoffeePDF spec §6.8)
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);

        await Wait.Until(() => _coffeePdf.RunningSeen(Pdf), "the crash check sees CoffeePDF running");
        await Wait.Until(() => _coffeePdf.Asked(Pdf) >= 20, "twenty looks for the lock file");
        Assert.Equal([cycle.StateOf(Pdf)], cycle.OpenCopies());
        Assert.Empty(_events.Crashes);
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold);

        FakeCoffeePdf.WriteLock(Pdf);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen");
        FakeCoffeePdf.Close(Pdf);

        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
    }

    [Fact]
    public async Task ACrashOffersTheCopyAndReopenOpensItInCoffeePdf()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf) && _coffeePdf.RunningSeen(Pdf), "the lock file and CoffeePDF seen");

        _coffee.Crash();

        await Wait.Until(() => _events.Crashes.Contains(Pdf), "Crashed");
        Assert.True(File.Exists(FakeCoffeePdf.LockPath(Pdf)));
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold);

        await cycle.OpenAsync(7, false); // the crash window's Reopen

        Assert.Equal([Pdf, Pdf], _coffee.Opened);
        Assert.False(cycle.IsCrashed(Pdf));
        Assert.Empty(_events.Reopens); // no save-back: CoffeePDF restores nothing (M3.6 spec §4.1)
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf), "the lock file seen again");
        FakeCoffeePdf.Close(Pdf);
        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
        Assert.False(File.Exists(FakeCoffeePdf.LockPath(Pdf)));
    }

    [Fact]
    public async Task SendAndCheckInAfterACrashTakesTheStaleLockWithIt()
    {
        var cycle = Cycle();
        await cycle.OpenAsync(7, false);
        await Wait.Until(() => _coffeePdf.LockSeen(Pdf) && _coffeePdf.RunningSeen(Pdf), "the lock file and CoffeePDF seen");
        _coffee.Crash();
        await Wait.Until(() => _events.Crashes.Contains(Pdf), "Crashed");

        await cycle.ClosedAsync(Pdf); // the crash window's Send and Check In

        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
        Assert.False(File.Exists(FakeCoffeePdf.LockPath(Pdf))); // CopyNames.AppFiles (M3.6 spec §4.5)
    }

    [Fact]
    public async Task ARestartWatchesACopyStillOpenInCoffeePdf()
    {
        var first = Cycle();
        await first.OpenAsync(7, false);
        first.Dispose();

        var restarted = Cycle();
        await restarted.ResumeAsync();
        var looks = _coffeePdf.Asked(Pdf); // LockSeen still holds from the first cycle, so count the new cycle's looks

        Assert.Equal([restarted.StateOf(Pdf)], restarted.OpenCopies());
        Assert.Empty(_events.Crashes);
        FakeCoffeePdf.Save(Pdf, "after the restart");
        await Wait.Until(() => _server.Saves.Count == 1, "the save");
        await Wait.Until(() => _coffeePdf.Asked(Pdf) > looks + 1, "the restarted watcher sees the lock file");
        FakeCoffeePdf.Close(Pdf);
        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
    }

    [Fact]
    public async Task ARestartAfterCoffeePdfCrashedOffersTheCopy()
    {
        var first = Cycle();
        await first.OpenAsync(7, false);
        first.Dispose();
        _coffee.Crash();

        await Cycle().ResumeAsync();

        Assert.Contains(Pdf, _events.Crashes);
        Assert.True(File.Exists(Pdf));
        Assert.Equal(FakeApi.Here("CoffeePDF"), _server.Doc(7).Hold);
    }

    [Fact]
    public async Task ARestartAfterTheWindowClosedChecksTheCopyIn()
    {
        var first = Cycle();
        await first.OpenAsync(7, false);
        first.Dispose();
        FakeCoffeePdf.Close(Pdf); // closed while the helper wasn't running

        await Cycle().ResumeAsync();

        await Wait.Until(() => _server.Doc(7).Hold is null && !File.Exists(Pdf), "checked in and deleted");
    }

    [Fact]
    public async Task ARestartDeletesAReadOnlyCopyOnceCoffeePdfIsGone()
    {
        var first = Cycle();
        await first.OpenAsync(7, true);
        first.Dispose();

        var second = Cycle();
        await second.ResumeAsync(); // CoffeePDF still running: it may have the copy open
        Assert.True(File.Exists(ReadOnlyPdf));
        second.Dispose(); // so it's the next start that deletes it, not this cycle's close watch

        _coffee.Crash();
        await Cycle().ResumeAsync();
        Assert.False(File.Exists(ReadOnlyPdf));
    }

    [Fact]
    public async Task ACopyFiledFromCoffeePdfStaysOpenThroughARestart()
    {
        var first = Cycle();
        var temp = Path.Combine(_root, "filed.pdf");
        File.WriteAllText(temp, "a new pdf");
        var state = await first.AdoptNewAsync(temp, new NewDocument(9, "Letter.pdf", new System.Text.Json.Nodes.JsonObject(), true),
            null, first.Apps.Filing("CoffeePDF"));
        File.WriteAllText(state.Path, "a new pdf"); // CoffeePDF writes the copy it filed (CoffeePDF spec §6.6)
        FakeCoffeePdf.WriteLock(state.Path);
        _coffee.Running = true;
        Assert.Equal("CoffeePDF", Store.Find(state.Path)!.Value.Item2.App);
        first.Dispose();

        var restarted = Cycle();
        await restarted.ResumeAsync();
        await Task.Delay(Quiet);

        Assert.True(File.Exists(state.Path)); // not checked in from under CoffeePDF's window
        Assert.NotNull(_server.Doc(state.DocNumber).Hold);
        Assert.Contains(restarted.OpenCopies(), c => c.Path == state.Path);
    }

    [Fact]
    public async Task TheStartSweepsCoffeePdfsLeftoversOnlyWhileItIsntRunning()
    {
        Directory.CreateDirectory(Paths.Copies);
        var leftover = CopyPath(".coffeepdf-0123456789abcdef0123456789abcdef.tmp");
        var lookalike = CopyPath(".coffeepdf-not-ours.tmp");
        File.WriteAllText(leftover, "half a save");
        File.WriteAllText(lookalike, "someone else's");

        _coffee.Running = true;
        await Cycle().ResumeAsync();
        Assert.True(File.Exists(leftover)); // it may be a save going on now

        _coffee.Running = false;
        await Cycle().ResumeAsync();
        Assert.False(File.Exists(leftover));
        Assert.True(File.Exists(lookalike)); // only CoffeePDF's exact names
    }
```

- [ ] **Step 2: Run them**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CoffeePdfCycleTests"`
Expected: every test passes except `TheStartSweepsCoffeePdfsLeftoversOnlyWhileItIsntRunning`, which fails on `Assert.False(File.Exists(leftover))`. If any other fails, it has found a gap in spec §3's reading of the cycle: stop, and take it to the owner before changing the cycle.

- [ ] **Step 3: Sweep CoffeePDF's leftovers at the start**

In `ResumeAsync`, after the `.healdan-*.part` loop:

```csharp
        // CoffeePDF's save leaves its temporary file only when CoffeePDF stopped part-way, but one may be a save going on
        // now, so they go only while CoffeePDF isn't running (M3.6 spec §6.4).
        if (_apps.CoffeePdf is { } coffeePdf && !SafeIsRunning(coffeePdf, _store.Folder))
        {
            foreach (var temp in Directory.EnumerateFiles(_store.Folder, ".coffeepdf-*.tmp"))
            {
                if (!CopyNames.IsCoffeePdfTemporary(Path.GetFileName(temp)))
                    continue;
                try
                {
                    File.Delete(temp);
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                {
                    // Tried again at the next start.
                }
            }
        }
```

`ResumeAsync`'s summary adds "and CoffeePDF's leftover temporary files go while it isn't running".

- [ ] **Step 4: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 5: Docs**

- `developer/desktop-clients.md`: in "The Cycle", CoffeePDF as the app for PDFs while it's installed with Healdan support on (read from its `settings.json` at each choice), its lock file `.~coffeepdf.<copy file name>#`, its saves by a temporary file renamed over the copy, which the folder watch counts as one save, its read-only copies tracked like LibreOffice's, a crash's crash window with no save-back, and the start deleting `.coffeepdf-…​.tmp` leftovers only while CoffeePDF isn't running. Where the page lists the files beside copies, add CoffeePDF's lock and temporary files.
- `user/office/when-things-go-wrong/index.md`: CoffeePDF quitting unexpectedly gives the same crash window as LibreOffice: Reopen opens the PDF in CoffeePDF again, and Send and Check In checks in what was last saved.

Run the docs site's checks. Expected: PASS.

- [ ] **Step 6: Commit** — "Show CoffeePDF's saves, closes, crashes and restarts, and sweep its leftovers at the start"

---

### Task 6: The channel

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Cycle/CopyState.cs` (`SavesStopped`), `Cycle/CopyCycle.cs` (both `StateOf`s :2024–2025 and the two-argument one), `Channel/ChannelHandler.cs` (`FileAsync`, `closed`, `Json`)
- Modify (tests): `Healdan.Helper.Core.Tests/Channel/ChannelHandlerTests.cs`; any existing test that compares a whole `CopyState` of a copy whose saves stopped (see Step 4)
- Modify (docs): `site/content/developer/desktop-clients.md` (the channel's routes)

**Interfaces:**
- Consumes: Task 2's `CopyCycle.Apps`, `OfficeApps.Filing`; Task 4's `ClosedAsync` returning `Task<bool>`; `FakeCoffeePdf`.
- Produces:

```csharp
public sealed record CopyState(bool Known, long DocNumber, string Title, string DocRef, bool ReadOnly, string Path, string? HoldBy,
    bool Pending = false, bool SavesStopped = false);
// ChannelHandler.Json: "readOnly" = ReadOnly || SavesStopped
```

- [ ] **Step 1: Write the failing tests**

In `ChannelHandlerTests`, a field `private readonly FakeCoffeePdf _coffee = new();`, and the cycle is built with `TestApps.Of(_app, coffeePdf: _coffee.App)`. Then:

```csharp
    private CopyRecord RecordOf(string path) => new CopyStore(HelperPaths.For(_root)).Find(path)!.Value.Item2;

    [Fact]
    public async Task FileFromCoffeePdfIsCoffeePdfsCopy()
    {
        var body = FileBody(TempFile("%PDF-1.7 the window's content"));
        body["format"] = "pdf";
        body["app"] = "CoffeePDF";

        var state = await Call("file", body);

        var path = (string)state["path"]!;
        Assert.Equal(CopyPath("Memo #100 v1.0.pdf"), path);
        Assert.Equal("CoffeePDF", RecordOf(path).App); // so the close watch and a restart look for CoffeePDF's lock
    }

    [Theory]
    [InlineData("LibreOffice")]
    [InlineData(null)]
    [InlineData("Pages")]
    [InlineData("Microsoft Office")]
    public async Task FileForAnyOtherAppIsLibreOffices(string? app)
    {
        var body = FileBody(TempFile("content"));
        if (app is null)
            body.Remove("app");
        else
            body["app"] = app;

        var state = await Call("file", body);

        Assert.Equal("LibreOffice", RecordOf((string)state["path"]!).App);
    }

    [Fact]
    public async Task SaveNewWithANullPathIsANewDocument()
    {
        await Call("save-new", new JsonObject
        {
            ["app"] = "CoffeePDF", ["name"] = "Scan", ["formats"] = new JsonArray("pdf"), ["path"] = null
        });

        var (_, request) = Assert.Single(_windows.Asked);
        Assert.Null(request["path"]);
        Assert.Null(request["docNumber"]);
    }

    [Fact]
    public async Task ACopyWhoseCheckOutEndedIsReadOnly()
    {
        var path = await OpenAsync();
        _server.Change(5, d => d.Hold = null); // the web page released it
        Save(path, "my edits");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");

        var state = await Call("state", PathBody(path));

        Assert.True((bool?)state["readOnly"]);
        Assert.Equal("Lease agreement #5 v1.0", (string?)state["title"]); // its name doesn't change
        Assert.Null((string?)state["holdBy"]);
        Assert.False(_cycle.StateOf(path).ReadOnly); // the helper's own windows read the record as before
    }

    [Fact]
    public async Task ACopyWhoseSavesCantGoOverNewerContentIsReadOnly()
    {
        var path = await OpenAsync();
        _server.Change(5, d =>
        {
            d.Files["1.1"] = "from the web page"u8.ToArray();
            d.Version = d.FileVersion = "1.1";
        });
        Save(path, "mine");
        await Wait.Until(() => _events.Ended.Contains(path), "CheckoutEnded");

        var state = await Call("state", PathBody(path));

        Assert.True((bool?)state["readOnly"]); // still checked out here, but its saves don't go up
    }

    [Fact]
    public async Task AnOpenCopyIsntReadOnly()
    {
        var path = await OpenAsync();

        Assert.False((bool?)(await Call("state", PathBody(path)))["readOnly"]);
    }

    [Fact]
    public async Task ClosedForACopyTheHelperDoesntKnowIsLogged()
    {
        var stranger = CopyPath("Not a copy #9 v1.0.pdf");
        Directory.CreateDirectory(Path.GetDirectoryName(stranger)!);
        File.WriteAllText(stranger, "someone's file");

        Assert.Equal("{}", (await Call("closed", PathBody(stranger))).ToJsonString());

        await Wait.Until(() => File.ReadAllText(HelperPaths.For(_root).Log).Contains("Ignored /v1/closed"), "the log line");
        Assert.True(File.Exists(stranger));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~ChannelHandlerTests"`
Expected: `FileFromCoffeePdfIsCoffeePdfsCopy`, the two read-only tests and the log test FAIL; the others pass already.

- [ ] **Step 3: Implement**

`CopyState` gains, after `Pending`:

```csharp
/// <param name="SavesStopped">
/// The copy isn't read-only, but its saves won't go up any more: its check-out ended here, or newer content was stored
/// (M3.6 spec §6.3). The channel answers it as read-only; the helper's own windows read <see cref="ReadOnly"/>.
/// </param>
public sealed record CopyState(bool Known, long DocNumber, string Title, string DocRef, bool ReadOnly, string Path, string? HoldBy,
    bool Pending = false, bool SavesStopped = false)
```

In `CopyCycle`, the static `StateOf(string, CopyRecord, string?)` sets `SavesStopped: !r.ReadOnly && !r.CheckedOutHere`, and `StateOf(string path, bool? pending)` reads the session's `Ended` under the lock and adds it:

```csharp
    private CopyState StateOf(string path, bool? pending)
    {
        Session? s;
        string? holdBy;
        var ended = false;
        lock (_gate)
        {
            s = Find(path);
            holdBy = s?.HoldBy;
            ended = s?.Ended == true;
            if (s is not null)
                path = s.Path;
        }
        var record = Record(path);
        if (record is null)
            return CopyState.Unknown(path);
        var state = StateOf(path, record, holdBy);
        return state with
        {
            Pending = pending ?? (s is not null && PendingOf(s)),
            SavesStopped = state.SavesStopped || (!record.ReadOnly && ended)
        };
    }
```

In `ChannelHandler`:
- `Json` writes `["readOnly"] = s.ReadOnly || s.SavesStopped`, and its summary says why (M3.6 spec §6.3).
- `FileAsync` reads the app before filing, and passes it:

```csharp
            var named = Optional(body, "app");
            var app = cycle.Apps.Filing(named);
            if (named is not null && named != app.Name)
                log.Warn($"/v1/file named an app the channel doesn't file for ({named}); filed as {app.Name}'s");
            // …
                var state = await cycle.AdoptNewAsync(tempPath, new NewDocument(folderId, name, properties, true), replaces, app);
```

  and the notice for an unreadable file becomes `$"{name} couldn't be filed: the copy {app.Name} wrote for it couldn't be read. It's still open, so save it again."` (declare `app` before the inner `try`, so the `catch` sees it).
- The `closed` case:

```csharp
            case "closed":
                if (Copy(body, route) is { } closed && !await cycle.ClosedAsync(closed))
                    log.Info("Ignored /v1/closed for a copy Healdan Helper isn't looking after");
                return new JsonObject();
```

The class summary's list of routes is unchanged.

- [ ] **Step 4: Run the Helper's tests**

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS. If an existing test compared a whole `CopyState` of a copy whose check-out ended or whose save hit newer content, it fails on `SavesStopped`: add `SavesStopped: true` to its expected value, and nothing else. `AddinHandlerTests` may assert `readOnly` for such a copy through `ChannelHandler.Json`: it is now true, as spec §6.3 decided.

- [ ] **Step 5: Docs**

`developer/desktop-clients.md`, the channel's routes: `/v1/file`'s `app` names the app whose window is filed (`CoffeePDF`, else LibreOffice); `readOnly` in a copy's state is also true once its saves have stopped going up, with its `title` unchanged; `/v1/closed` for a read-only copy in the default app deletes it, and one the helper has nothing to do with is logged and ignored. Say these are the two meanings CoffeePDF's `ChannelRoutes` follows (spec §6.5).

Run the docs site's checks. Expected: PASS.

- [ ] **Step 6: Commit** — "File CoffeePDF's documents as its copies, and answer read-only once a copy's saves stop"

---

### Task 7: Settings and the Open window

**Files:**
- Modify: `clients/helper/Healdan.Helper/Windows/SettingsWindow.axaml.cs` (the PDFs row; a shared row builder), `Windows/OpenWindow.axaml.cs` (the rule and its note), `Windows/AppWindows.cs` (:121, the rule)
- Modify (tests): `clients/helper/Healdan.Helper.Tests/Windows/SettingsWindowTests.cs`
- Modify (docs): `site/content/user/office/installing-the-helper/index.md` (:241), `site/content/install/known-limitations.md`

**Interfaces:**
- Consumes: Task 3's `AppChooser.CoffeePdfAvailable`, `CoffeePdfSupportOff`, `OpensReadOnly`; `PdfFiles`.
- Produces: `internal const string PdfSupportOffNote` on `SettingsWindow`; `OpenWindow(HealdanApi api, bool fromApp, Func<string, bool> opensReadOnly)`.

- [ ] **Step 1: Write the failing tests**

In `SettingsWindowTests`, `Run` gains `bool coffeePdfOn = false`, passed as the chooser's fourth argument (`new AppChooser(HelperSettings.Load(Paths), apps, systemDefault ?? (_ => null), coffeePdfOn)`). Then:

```csharp
    private static readonly InstalledApps WithCoffeePdf = Everything with { CoffeePdf = true };

    [MacFact]
    public Task PdfsShowCoffeePdfWhileItsOn() => Run(window =>
    {
        var texts = Texts(RowOf(window, "PDFs"));
        Assert.Contains("CoffeePDF", texts);
        Assert.Contains(".pdf", texts);
        Assert.DoesNotContain(SettingsWindow.PdfSupportOffNote, texts);
        Assert.Null(PopUp(window, "PDFs")); // CoffeePDF's own preference is the switch
        return Task.CompletedTask;
    }, installed: WithCoffeePdf, coffeePdfOn: true);

    [MacFact]
    public Task WithCoffeePdfOffPdfsOpenReadOnlyAndTheNoteSaysHowToTurnItOn() => Run(window =>
    {
        var texts = Texts(RowOf(window, "PDFs"));
        Assert.Contains("Default app, read-only", texts);
        Assert.Contains(SettingsWindow.PdfSupportOffNote, texts);
        return Task.CompletedTask;
    }, installed: WithCoffeePdf, coffeePdfOn: false);

    [MacFact]
    public Task WithoutCoffeePdfPdfsOpenReadOnlyWithNoNote() => Run(window =>
    {
        var texts = Texts(RowOf(window, "PDFs"));
        Assert.Contains("Default app, read-only", texts);
        Assert.DoesNotContain(SettingsWindow.PdfSupportOffNote, Texts(window));
        return Task.CompletedTask;
    });
```

- [ ] **Step 2: Run them to see them fail** (on a Mac)

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~SettingsWindowTests"`
Expected: FAIL to compile (`PdfSupportOffNote`).

- [ ] **Step 3: The PDFs row**

In `SettingsWindow.axaml.cs`, pull the grid out of `KindRow` into a builder both rows use, so the PDFs row looks like the kinds':

```csharp
    /// <summary>Turn on Healdan support in CoffeePDF to have PDFs open there (M3.6 spec §4.4).</summary>
    internal const string PdfSupportOffNote = "Turn on Healdan support in CoffeePDF's Settings to edit PDFs there.";

    /// <summary>A row of the kinds' group: its icon, its label with its extensions and any note under them, and the app.</summary>
    private static Control Row(string iconName, string label, string extensions, string? note, Control value, bool divided)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Classes = { "row" } };
        var icon = Icons.View(iconName);
        icon.Classes.Add("folder");
        icon.Margin = new Thickness(0, 0, 8, 0);
        icon.VerticalAlignment = VerticalAlignment.Center;
        var name = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        name.Children.Add(new TextBlock { Text = label });
        name.Children.Add(new TextBlock { Text = extensions, Classes = { "muted" }, FontSize = 11 });
        if (note is not null)
            name.Children.Add(new TextBlock { Text = note, Classes = { "muted" }, FontSize = 11, TextWrapping = TextWrapping.Wrap });
        Grid.SetColumn(name, 1);
        Grid.SetColumn(value, 2);
        grid.Children.Add(icon);
        grid.Children.Add(name);
        grid.Children.Add(value);
        return divided ? new Border { Classes = { "divided" }, Child = grid } : grid;
    }

    /// <summary>The app as text, for a row with nothing to choose between.</summary>
    private static TextBlock AppText(string app)
    {
        var text = new TextBlock { Text = app, VerticalAlignment = VerticalAlignment.Center };
        text.Bind(TextBlock.ForegroundProperty, text.GetResourceObservable("HdSecondaryBrush")); // follows a theme switch
        return text;
    }

    /// <summary>
    /// PDFs (M3.6 spec §4.4), as text: CoffeePDF while it's installed with Healdan support on, else the default app,
    /// read-only, with a note when CoffeePDF is installed and its support is off.
    /// </summary>
    private static Control PdfRow(AppChooser chooser) =>
        Row("file-type-pdf", "PDFs", ".pdf", chooser.CoffeePdfSupportOff ? PdfSupportOffNote : null,
            AppText(chooser.CoffeePdfAvailable ? CoffeePdfApp.AppName : "Default app, read-only"), divided: true);
```

`KindRow` keeps its pop-up logic and ends with `return Row(FileIcons.For(kind), kind.Label, string.Join(" ", kind.Extensions), null, value, divided);`, using `AppText(app)` for the text case. After the kinds' loop in the constructor: `KindsGroup.Children.Add(PdfRow(chooser));`.

- [ ] **Step 4: The Open window's rule**

`OpenWindow`'s constructor becomes `internal OpenWindow(HealdanApi api, bool fromApp, Func<string, bool> opensReadOnly)`, keeping the function in a field `_opensReadOnly`. The three `FileKinds.Of(…) is null` tests (in `ShowDetails`, `VersionChanged` and `OpenChosen`) become `_opensReadOnly(name)`, and `ShowDetails` sets the note's text:

```csharp
        KindNote.IsVisible = _opensReadOnly(doc.Name);
        KindNote.Text = PdfFiles.Is(doc.Name)
            ? "PDFs open read-only in the computer's default app. Turn on Healdan support in CoffeePDF to edit them there."
            : "Only office documents can be edited from here: this one opens read-only.";
```

`VersionChanged`'s summary says "as does a file that opens in the default app (M3.6 spec §5.2)". In `AppWindows`, line 121: `new OpenWindow(api, fromApp, name => OfficeAppSet.Chooser(paths).OpensReadOnly(name))`. The rule itself is tested in the core (Task 3, `ReadOnlyIsWhateverGoesToTheDefaultApp`; plan correction 1).

- [ ] **Step 5: Run the Helper's tests** (on a Mac, so the window tests run)

Run: `dotnet test clients/helper/Healdan.Helper.sln`
Expected: PASS.

- [ ] **Step 6: Docs**

- `user/office/installing-the-helper/index.md`, at :241: Settings' "Open Each Kind of File In" ends with PDFs, which open in CoffeePDF when it's installed with Healdan support on, and read-only in your Mac's usual app for PDFs otherwise; there's nothing to choose in the helper, as CoffeePDF's own Settings turn its Healdan support on. Where to get CoffeePDF: [coffeemuse/CoffeePDF](https://github.com/coffeemuse/CoffeePDF).
- `install/known-limitations.md`: PDFs are edited only in CoffeePDF, on macOS; everywhere else they open read-only.

Run the docs site's checks. Expected: PASS.

- [ ] **Step 7: Commit** — "Show PDFs in Settings, and let the Open window edit them in CoffeePDF"

---

### Task 8: Records

**Files:**
- Modify: `CLAUDE.md` (the "Healdan's clients" paragraph: M3.6's spec beside M3's and M3.5's; CoffeePDF as the app for PDFs, with its lock file and its `settings.json`; the "Upstream edits" list: M3.6 edits no upstream file)
- Modify: `docs/superpowers/specs/2026-11-01-m3-6-pdfs-in-coffeepdf-design.md` (this plan's corrections and anything else the build changed, each marked "changed in the build"; §10.2 adds `.github/workflows/build-logicaldoc.yml`; the status line)
- Modify: `docs/ROADMAP.md` (M3.6's row and section: built, acceptance next)
- Modify: `docs/reference/logicaldoc-ce-capability-map.md`, only if its Office-integration entry lists the helper's apps: add CoffeePDF for PDFs.

- [ ] **Step 1: Write them,** from what Tasks 1–7 actually built.
- [ ] **Step 2: Run the docs site's checks and the quick checks** (Conventions). Expected: PASS.
- [ ] **Step 3: Commit** — "Record M3.6's build"

Open PR A (Tasks 1–8) only with the owner's go-ahead.

---

### Task 9: Open in App for PDFs on the web page (PR B)

Merged once Healdan Helper 0.5.0-rc1 exists (spec §12), on a branch of its own from master.

**Files:**
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/healdan/HealdanOpenInApp.java` (`EXTENSIONS`, javadoc)
- Create (test): `logicaldoc-gui/src/test/java/com/logicaldoc/gui/common/client/healdan/HealdanOpenInAppTest.java`
- Modify: `.github/workflows/build-logicaldoc.yml` (`UPSTREAM_TESTS`: `HealdanOpenInAppTest`; plan correction 2)
- Modify (docs): `site/content/user/office/opening-documents/index.md` (Open in App now on PDFs)

**Interfaces:**
- Consumes: nothing from PR A at build time; at run time, a Helper that takes PDF links.
- Produces: `HealdanOpenInApp.handles("x.pdf")` is true.

- [ ] **Step 1: Write the failing test**

```java
package com.logicaldoc.gui.common.client.healdan;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which documents the web page's Open in App offers (M3 spec 8, M3.6 spec
 * 5.1): the helper's office kinds, and PDFs.
 */
public class HealdanOpenInAppTest {

	@Test
	public void testOffersPdfsInAnyCase() {
		assertTrue(HealdanOpenInApp.handles("Signed NDA.pdf"));
		assertTrue(HealdanOpenInApp.handles("SCAN.PDF"));
	}

	@Test
	public void testStillOffersTheOfficeKinds() {
		assertTrue(HealdanOpenInApp.handles("Lease.docx"));
		assertTrue(HealdanOpenInApp.handles("Plan.odg"));
	}

	@Test
	public void testOffersNothingElse() {
		assertFalse(HealdanOpenInApp.handles("Photo.png"));
		assertFalse(HealdanOpenInApp.handles("pdf"));
		assertFalse(HealdanOpenInApp.handles("a."));
		assertFalse(HealdanOpenInApp.handles(null));
	}
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -B -ntp -pl logicaldoc-gui test -Dgwt.skipCompilation=true -Dtest=HealdanOpenInAppTest`
Expected: FAIL in `testOffersPdfsInAnyCase`.

- [ ] **Step 3: Offer PDFs**

`EXTENSIONS` adds `"pdf"`, and its comment becomes "The kinds of file the helper opens (spec 5.11), and PDFs, which it opens in CoffeePDF or read-only in the default app (M3.6 spec 5.1), in lower case". `handles`' `@return` says "true for a Writer, Calc, Impress or Draw kind of file, or a PDF, whatever the case of its extension". Add `HealdanOpenInAppTest` to `UPSTREAM_TESTS` after `HealdanNamesTest`.

- [ ] **Step 4: Run it, and the quick checks**

Run: `mvn -B -ntp -pl logicaldoc-gui test -Dgwt.skipCompilation=true -Dtest=HealdanOpenInAppTest`, then `python3 .github/scripts/check-fontawesome-icons.py` and the quick checks.
Expected: PASS.

- [ ] **Step 5: Docs**

`user/office/opening-documents/index.md`: Open in App shows on PDFs too, and opens them as the helper does from a link.

Run the docs site's checks. Expected: PASS.

- [ ] **Step 6: Commit** — "Offer Open in App for PDFs"

Open PR B only with the owner's go-ahead, and merge it only once Healdan Helper 0.5.0-rc1 is released.

---

### Task 10: Release candidates, the owner's acceptance and the release

Not code. In order:

- [ ] **Step 1:** once PR A is merged, tag `helper-v0.5.0-rc1` on master; `clients.yml` packages, signs and notarises it and attaches the disk image.
- [ ] **Step 2:** merge PR B, and the owner upgrades their server to master (the image now offers Open in App for PDFs).
- [ ] **Step 3:** the owner installs Healdan Helper 0.5.0-rc1 and CoffeePDF built from its `main`, with Healdan support on, and runs spec §11.2's walkthrough: CoffeePDF §2.5 steps 1 to 7 (step 3 adds a markup before Save as New Version), then rows H1 to H4. Each row is recorded in this plan's decision log; failures become a punch list, fixed in further release candidates.
- [ ] **Step 4:** tag `helper-v0.5.0`; the roadmap's M3.6 row becomes **done**, with the date and the pull requests; CoffeePDF's session releases CoffeePDF v1.0.

## Decision log

Empty until the build starts.

## Follow-ups

Empty until the build starts.
