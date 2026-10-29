# Helper: Copies Come Back to Their Server (#85) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each local copy's record names its server; a start sets aside any copy naming another server and brings back the signed-in server's; the menu shows a line per server with copies waiting.

**Architecture:** All the file moves live in `CopyStore` (Core), keyed on the new `CopyRecord.Server`. `HelperSession` calls them at the two places `Copies` could get another server's copy: a sign-in to another server (`SetCopiesAside`, already there) and every start before the resume (`BeginAsync`). `CopyCycle` doesn't change: it never sees another server's copy. The menu reads a list from Core and only draws it.

**Tech Stack:** C# on .NET 10, xUnit, Avalonia 12 (menu only); Hugo docs.

**Spec:** [`docs/superpowers/specs/2026-10-24-m3-office-integration-design.md`](../specs/2026-10-24-m3-office-integration-design.md) §5.4 "The copies belong to one server" (and the one clause in §5.2). Issue: [Healdan-Project/healdan#85](https://github.com/Healdan-Project/healdan/issues/85).

## Global Constraints

- The invariant: while the helper runs, `Copies` holds only the signed-in server's copies.
- A server's address is the form `settings.json` holds: scheme, host, any port and any path, no trailing slash (`https://healdan.example`), compared without regard to case.
- Moves never overwrite anything, and nothing is deleted except a `Copies from …` folder left empty.
- A copy moves first, then its record; if the record can't follow, the copy moves back. An app's lock file (`.~lock.<name>#`) and Word owner files (`~$…`) never move.
- A record without a server belongs to the server in `settings.json`; at a sign-in to another server it goes aside with the old server written in.
- Testable code goes in `Healdan.Helper.Core`; `Healdan.Helper` only draws (CLAUDE.md, "Healdan's clients").
- Match the surrounding code: dense `///` doc comments that say why, `System.IO.Path` inside `CopyStore`, British spelling.
- No real hostnames in code, tests, docs, commits or the PR: `healdan.example`, `healdan.test`, `second.test`, `localhost`.
- Docs follow the Developer Guide's Writing Docs page: Title Case headings and labels, British spelling.
- Each commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

Run the helper's tests from the repository root with `dotnet test clients/helper/Healdan.Helper.sln`, narrowed with `--filter "FullyQualifiedName~<Class>"`.

## Files

| File | What changes |
|---|---|
| `clients/helper/Healdan.Helper.Core/ServerAddress.cs` | New: `Of`, `Same`, `Name` for server addresses |
| `clients/helper/Healdan.Helper.Core/Copies/CopyRecord.cs` | `Server` |
| `clients/helper/Healdan.Helper.Core/Copies/AsideCopies.cs` | New: `AsideCopies`, `BroughtBack`, `StayedAside` |
| `clients/helper/Healdan.Helper.Core/Copies/CopyNames.cs` | `IsAppFile` |
| `clients/helper/Healdan.Helper.Core/Copies/CopyStore.cs` | Server-aware `Create`, `SetAside` by record, `MoveCopy`, `BringBack`, `Aside` |
| `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs` | Stores with a server, `SetCopiesAside` by record, `SettleCopies` in `BeginAsync`, `CopiesAside` |
| `clients/helper/Healdan.Helper/Windows/AppWindows.cs` | `ShowFolder` |
| `clients/helper/Healdan.Helper/Tray/TrayMenu.cs` | A line per server with copies waiting |
| `clients/helper/Healdan.Helper.Core.Tests/ServerAddressTests.cs` | New |
| `clients/helper/Healdan.Helper.Core.Tests/Copies/CopyStoreTests.cs` | New tests; the old `SetAside` test replaced |
| `clients/helper/Healdan.Helper.Core.Tests/Session/HelperSessionTests.cs` | New tests |
| `site/content/user/office/installing-the-helper/index.md` | Settings → Server, the menu table, If Healdan Can't Be Reached |
| `site/content/user/office/editing-and-versions/index.md` | The server-change paragraph |
| `site/content/developer/desktop-clients.md` | The Local Copies bullet |

---

### Task 1: Records Name Their Server

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/ServerAddress.cs`
- Create: `clients/helper/Healdan.Helper.Core.Tests/ServerAddressTests.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyRecord.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyStore.cs` (class declaration, `Create`)
- Modify: `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs` (`Address`, `SameServer`, `BeginAsync`'s `new CopyStore`)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Copies/CopyStoreTests.cs`, `clients/helper/Healdan.Helper.Core.Tests/Session/HelperSessionTests.cs`

**Interfaces:**
- Produces: `ServerAddress.Of(Uri) : string`, `ServerAddress.Same(Uri, Uri) : bool`, `ServerAddress.Same(string?, string?) : bool`, `ServerAddress.Name(string) : string`; `CopyRecord.Server : string?` (last parameter, default null); `new CopyStore(HelperPaths paths, string? server = null)`, whose `Create` writes `server` into the record. Test helpers in `CopyStoreTests`: constants `A`, `B`, `Store(string? server = A)`, `Write(string? server, long number, string name = "Lease agreement.docx", string content = "x") : string`, `RecordIn(string folder, string copyPath) : CopyRecord`.

- [ ] **Step 1: Write the failing tests**

Create `clients/helper/Healdan.Helper.Core.Tests/ServerAddressTests.cs`:

```csharp
namespace Healdan.Helper.Core.Tests;

public class ServerAddressTests
{
    [Theory]
    [InlineData("https://healdan.example/", "https://healdan.example")]
    [InlineData("http://localhost:8080", "http://localhost:8080")]
    [InlineData("https://healdan.example/dms/", "https://healdan.example/dms")]
    public void AnAddressIsTheSchemeHostPortAndPath(string typed, string address) =>
        Assert.Equal(address, ServerAddress.Of(new Uri(typed)));

    [Fact]
    public void AddressesMatchWithoutRegardToCase()
    {
        Assert.True(ServerAddress.Same("https://Healdan.Example", "https://healdan.example"));
        Assert.False(ServerAddress.Same("http://healdan.example", "https://healdan.example"));
        Assert.False(ServerAddress.Same(null, "https://healdan.example"));
        Assert.True(ServerAddress.Same(new Uri("https://healdan.example/"), new Uri("https://HEALDAN.example")));
    }

    [Theory]
    [InlineData("https://healdan.example", "healdan.example")]
    [InlineData("http://localhost:8080", "localhost:8080")]
    [InlineData("https://healdan.example/dms", "healdan.example")]
    [InlineData("not an address", "not an address")]
    public void ANameIsTheHostAndAnyPort(string address, string name) => Assert.Equal(name, ServerAddress.Name(address));
}
```

In `CopyStoreTests.cs`, add `using System.Text.Json;` and `using System.Text.Json.Nodes;`, replace `private CopyStore Store() => new(Paths);` with these helpers, and add the two tests:

```csharp
    private const string A = "https://healdan.example";
    private const string B = "http://localhost:8080";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private CopyStore Store(string? server = A) => new(Paths, server);

    /// <summary>A copy with content, made by a store for <paramref name="server"/> (null: from before records named one).</summary>
    private string Write(string? server, long number, string name = "Lease agreement.docx", string content = "x")
    {
        var (path, _, _) = Store(server).Create(Doc(number, name), false, "LibreOffice");
        File.WriteAllText(path, content);
        return path;
    }

    /// <summary>The record beside <paramref name="copyPath"/>'s file name in <paramref name="folder"/>, read as written.</summary>
    private static CopyRecord RecordIn(string folder, string copyPath) =>
        JsonSerializer.Deserialize<CopyRecord>(
            File.ReadAllText(Path.Combine(folder, "." + Path.GetFileName(copyPath) + ".healdan.json")), Json)!;

    [Fact]
    public void CreateWritesTheStoresServer()
    {
        var (path, record, _) = Store(A).Create(Doc(1), false, "LibreOffice");

        Assert.Equal(A, record.Server);
        Assert.Equal(A, RecordIn(Paths.Copies, path).Server);
        Assert.Equal(A, Store(B).Find(path)!.Value.Item2.Server); // the record says, whichever store reads it
    }

    [Fact]
    public void ARecordFromBeforeServersWereKeptReadsWithNone()
    {
        var (path, _, _) = Store(A).Create(Doc(1), false, "LibreOffice");
        var recordPath = Path.Combine(Paths.Copies, "." + Path.GetFileName(path) + ".healdan.json");
        var json = JsonNode.Parse(File.ReadAllText(recordPath))!.AsObject();
        json.Remove("server");
        File.WriteAllText(recordPath, json.ToJsonString());

        Assert.Null(Store(A).Find(path)!.Value.Item2.Server);
    }
```

In `HelperSessionTests.cs`, add `using Healdan.Helper.Core.Copies;` and this test after `SigningInAgainToTheSameServerKeepsItsCopies`:

```csharp
    [Fact]
    public async Task ACopyRecordsTheServerItCameFrom()
    {
        SignedInBefore();
        var session = Make();
        await session.StartAsync();

        var path = await OpenAsync(session);

        Assert.Equal(Account, new CopyStore(Paths).Find(path)!.Value.Item2.Server);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~ServerAddressTests|FullyQualifiedName~CopyStoreTests|FullyQualifiedName~ACopyRecordsTheServerItCameFrom"`
Expected: build errors: `ServerAddress` doesn't exist, `CopyRecord` has no `Server`, `CopyStore` takes one argument.

- [ ] **Step 3: Write `ServerAddress`**

Create `clients/helper/Healdan.Helper.Core/ServerAddress.cs`:

```csharp
namespace Healdan.Helper.Core;

/// <summary>
/// A server's address as <c>settings.json</c> and each copy's record hold it, such as <c>https://healdan.example</c>:
/// the scheme, host, any port and any path, with no trailing slash. Two addresses name the same server when they match
/// without regard to case (spec §5.4).
/// </summary>
public static class ServerAddress
{
    public static string Of(Uri server) => server.GetLeftPart(UriPartial.Path).TrimEnd('/');

    public static bool Same(Uri a, Uri b) => Same(Of(a), Of(b));

    public static bool Same(string? a, string? b) =>
        a is not null && b is not null && string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The server as the user knows it, its host and any port, such as <c>localhost:8080</c>, as links and notices name
    /// it; the address itself when it isn't one.
    /// </summary>
    public static string Name(string address) => Uri.TryCreate(address, UriKind.Absolute, out var uri) ? uri.Authority : address;
}
```

- [ ] **Step 4: Add `Server` to the record and the store**

In `CopyRecord.cs`, add after the `SentKind` `<param>`:

```csharp
/// <param name="Server">
/// The address of the server the copy came from (<see cref="ServerAddress"/>), written when the copy is made. Null in a
/// record from before it was kept: that copy belongs to the server in <c>settings.json</c> (spec §5.4).
/// </param>
```

and make the last two parameters:

```csharp
    string? SentKind = null,
    string? Server = null);
```

In `CopyStore.cs`, change the class declaration and its summary:

```csharp
/// <summary>
/// The local copies in the helper's one private folder, and the record kept beside each (spec §5.4), for the server at
/// <paramref name="server"/>'s address, which each new copy's record names. Never deletes the folder, or anything but
/// the files of the copy it is told to delete.
/// </summary>
public sealed class CopyStore(HelperPaths paths, string? server = null)
```

and in `Create`, end the `new CopyRecord(...)` with the server:

```csharp
        var record = new CopyRecord(doc.DocNumber, doc.Name, doc.Version, doc.FileVersion, readOnly, false, null, null, app, null,
            CopyNames.FileName(doc.Name, doc.DocNumber, doc.FileVersion, readOnly), Server: server);
```

- [ ] **Step 5: Give the session's stores their server**

In `HelperSession.cs`, replace the two private helpers:

```csharp
    private static string Address(Uri server) => ServerAddress.Of(server);

    private static bool SameServer(Uri a, Uri b) => ServerAddress.Same(a, b);
```

and in `BeginAsync`:

```csharp
            cycle = new CopyCycle(api, new CopyStore(_paths, Address(api.Server)), _app, _device.Id, this, _timings, _otherFiles);
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~ServerAddressTests|FullyQualifiedName~CopyStoreTests|FullyQualifiedName~HelperSessionTests"`
Expected: all pass, the existing ones too.

- [ ] **Step 7: Commit**

```bash
git add clients/helper
git commit -m "Record each copy's server in the helper (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Set Copies Aside by Their Record's Server

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Copies/AsideCopies.cs`
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyNames.cs` (add `IsAppFile`)
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyStore.cs` (replace `SetAside`, split `Save`, add `MoveCopy`, `AsideFolder`, `IsPart`)
- Modify: `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs` (`SetCopiesAside`, new `TellSetAside`)
- Test: `CopyStoreTests.cs` (replace `SetAsideMovesEveryCopyToAFolderNamedForItsServer`), `HelperSessionTests.cs`

**Interfaces:**
- Consumes: Task 1's `ServerAddress`, `CopyRecord.Server`, `CopyStore(paths, server)` and test helpers.
- Produces: `record AsideCopies(string Server, string Folder, int Count)` with `string Line`; `CopyStore.SetAside(string keep, string unrecorded) : IReadOnlyList<AsideCopies>`; `internal static CopyStore.MoveCopy(string from, string to, string fileName, CopyRecord? rewrite = null)`; `internal static CopyNames.IsAppFile(string fileName) : bool`; private `HelperSession.TellSetAside(IReadOnlyList<AsideCopies> moved, string to)`.

- [ ] **Step 1: Write the failing tests**

In `CopyStoreTests.cs`, delete `SetAsideMovesEveryCopyToAFolderNamedForItsServer` and add:

```csharp
    [Fact]
    public void SetAsideMovesEachCopyToTheFolderForItsServer()
    {
        Assert.Empty(Store(B).SetAside(B, A)); // nothing to move
        var a = Write(A, 1);
        var b = Write(B, 2, "Other.docx");
        var old = Write(null, 3, "Old.docx"); // from before records named their server
        var stray = Path.Combine(Paths.Copies, "Stray #4 v1.0.docx");
        File.WriteAllText(stray, "no record");
        var lockFile = Path.Combine(Paths.Copies, $".~lock.{Path.GetFileName(a)}#");
        File.WriteAllText(lockFile, "lock");
        var part = Path.Combine(Paths.Copies, ".healdan-1.part");
        File.WriteAllText(part, "half a download");

        var moved = Store(B).SetAside(B, A);

        var aside = Path.Combine(_root, "Copies from healdan.example");
        Assert.Equal(new AsideCopies("healdan.example", aside, 2), Assert.Single(moved));
        foreach (var name in new[] { Path.GetFileName(a), Path.GetFileName(old), "Stray #4 v1.0.docx" })
            Assert.True(File.Exists(Path.Combine(aside, name)), name);
        Assert.Equal(A, RecordIn(aside, a).Server);
        Assert.Equal(A, RecordIn(aside, old).Server); // written in as it moved, so it can come back there
        Assert.Equal(b, Assert.Single(Store(B).All()).Path);
        Assert.True(File.Exists(lockFile)); // the app deletes its lock file by the path it opened
        Assert.True(File.Exists(part)); // the resume sweeps it
        Assert.True(Directory.Exists(Paths.Copies)); // the folder itself stays
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute, File.GetUnixFileMode(aside));
        Assert.Empty(Store(B).SetAside(B, A)); // nothing left to move
    }

    [Fact]
    public void AtAStartOnlyCopiesNamingAnotherServerMove()
    {
        var old = Write(null, 1);
        var stray = Path.Combine(Paths.Copies, "Stray #4 v1.0.docx");
        File.WriteAllText(stray, "no record");
        var b = Write(B, 2, "Other.docx");

        var moved = Store(A).SetAside(A, A);

        Assert.Equal(new AsideCopies("localhost:8080", Path.Combine(_root, "Copies from localhost-8080"), 1), Assert.Single(moved));
        Assert.False(File.Exists(b));
        Assert.True(File.Exists(old)); // the server in settings.json's, which is this one
        Assert.True(File.Exists(stray));
    }

    [Fact]
    public void ACopyWhoseNameIsTakenAsideGoesToTheNextNumberedFolder()
    {
        Write(A, 1);
        Store(B).SetAside(B, A);
        Write(A, 1);
        Write(A, 2, "Other.docx");

        var moved = Store(B).SetAside(B, A);

        var first = Path.Combine(_root, "Copies from healdan.example");
        Assert.Equal(new AsideCopies("healdan.example", first, 2), Assert.Single(moved));
        Assert.True(File.Exists(Path.Combine(_root, "Copies from healdan.example 2", "Lease agreement #1 v2.1.docx")));
        Assert.True(File.Exists(Path.Combine(first, "Other #2 v2.1.docx")));
    }

    [Fact]
    public void ACopyWhoseRecordCantFollowMovesBack()
    {
        var a = Write(A, 1);
        var to = Path.Combine(_root, "Elsewhere");
        Directory.CreateDirectory(to);
        Directory.CreateDirectory(Path.Combine(to, "." + Path.GetFileName(a) + ".healdan.json")); // in the record's way

        Assert.ThrowsAny<Exception>(() => CopyStore.MoveCopy(Paths.Copies, to, Path.GetFileName(a)));

        Assert.Equal("x", File.ReadAllText(a));
        Assert.Equal(a, Assert.Single(Store().All()).Path);
        Assert.False(File.Exists(Path.Combine(to, Path.GetFileName(a))));
    }
```

In `HelperSessionTests.SigningInToAnotherServerSetsTheOldServersCopiesAside`, add after the last assertion:

```csharp
        Assert.Contains("brings them back when you sign in to healdan.test again", _notifier.All);
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CopyStoreTests|FullyQualifiedName~SigningInToAnotherServer"`
Expected: build errors: no `AsideCopies`, `SetAside` takes one argument, no `MoveCopy`.

- [ ] **Step 3: Add `AsideCopies` and `IsAppFile`**

Create `clients/helper/Healdan.Helper.Core/Copies/AsideCopies.cs`:

```csharp
namespace Healdan.Helper.Core.Copies;

/// <summary>One server's copies set aside beside <c>Copies</c> (spec §5.4).</summary>
/// <param name="Server">The server as the user knows it, its host and any port, such as <c>healdan.example</c>.</param>
/// <param name="Folder">The folder holding them, the first by name when they're in several.</param>
/// <param name="Count">How many copies; files without a record that moved with them aren't counted.</param>
public sealed record AsideCopies(string Server, string Folder, int Count)
{
    /// <summary>The menu's line for them.</summary>
    public string Line => Count == 1 ? $"1 copy waiting for {Server}" : $"{Count} copies waiting for {Server}";
}
```

In `CopyNames.cs`, after `WordOwnerNames`:

```csharp
    /// <summary>
    /// Whether a file in the folder is an app's own: LibreOffice's lock file or Word's owner file. The app deletes it by
    /// the path it opened, so a copy set aside leaves it where it is (spec §5.4).
    /// </summary>
    internal static bool IsAppFile(string fileName) =>
        (fileName.StartsWith(".~lock.", StringComparison.Ordinal) && fileName.EndsWith('#'))
        || fileName.StartsWith("~$", StringComparison.Ordinal);
```

- [ ] **Step 4: Split `Save` so a record can be written into any folder**

In `CopyStore.cs`, replace the body of `Save` after its argument check, and add `Write` below it:

```csharp
        EnsureFolders();
        Write(r, paths.Copies);
    }

    /// <summary>
    /// Writes the record whole into <paramref name="folder"/>, beside the copy its <see cref="CopyRecord.FileName"/>
    /// names, through a temporary file renamed over the old one, so a crash leaves either.
    /// </summary>
    private static void Write(CopyRecord r, string folder)
    {
        var target = System.IO.Path.Combine(folder, CopyNames.RecordName(r.FileName));
        var temp = System.IO.Path.Combine(folder, $".{Guid.NewGuid():N}.tmp");
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(r, Json));
            if (!OperatingSystem.IsWindows())
                File.SetUnixFileMode(temp, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temp, target, overwrite: true);
        }
        finally
        {
            File.Delete(temp);
        }
    }
```

(`Save` keeps its `<summary>` and its `ArgumentException` check; only the lines after the check move into `Write`.)

- [ ] **Step 5: Replace `SetAside`**

In `CopyStore.cs`, replace the whole `SetAside(string server)` method, summary included, with:

```csharp
    /// <summary>The start of the name of each folder beside Copies that holds copies set aside (spec §5.4).</summary>
    private const string AsidePrefix = "Copies from ";

    /// <summary>
    /// Spec §5.4: moves each copy whose record doesn't name <paramref name="keep"/>, with its record, into the folder
    /// beside Copies for the server its record names (<see cref="MoveCopy"/>), and returns where each server's copies
    /// went. A record without a server, from before records named one, counts as <paramref name="unrecorded"/>'s and has
    /// it written in as it moves, so it can come back there. When <paramref name="unrecorded"/> isn't
    /// <paramref name="keep"/>, anything else in the folder goes to that server's folder too, such as a copy with no
    /// usable record, except an app's lock and owner files (<see cref="CopyNames.IsAppFile"/>), which the app deletes
    /// by the path it opened, and a download's temporary part, which the resume sweeps. Copies itself stays, so an app's
    /// access to it does too. A move that fails throws; the copies moved before it stay moved.
    /// </summary>
    /// <param name="keep">The address of the server whose copies stay, such as <c>https://healdan.example</c>.</param>
    /// <param name="unrecorded">The address of the server that records without one, and files without a record, belong to.</param>
    public IReadOnlyList<AsideCopies> SetAside(string keep, string unrecorded)
    {
        if (!Directory.Exists(paths.Copies))
            return [];
        var moved = new List<(string Server, string Folder, bool Copy)>();
        var stays = new HashSet<string>(StringComparer.Ordinal);
        foreach (var (path, record) in All())
        {
            var name = System.IO.Path.GetFileName(path);
            var owner = record.Server ?? unrecorded;
            if (ServerAddress.Same(owner, keep))
            {
                stays.Add(name);
                stays.Add(CopyNames.RecordName(name));
                continue;
            }
            var folder = AsideFolder(owner, [name, CopyNames.RecordName(name)]);
            MoveCopy(paths.Copies, folder, name, record.Server is null ? record with { Server = owner } : null);
            moved.Add((owner, folder, true));
        }
        if (!ServerAddress.Same(unrecorded, keep))
        {
            var strays = Directory.EnumerateFileSystemEntries(paths.Copies)
                .Select(entry => System.IO.Path.GetFileName(entry))
                .Where(name => !stays.Contains(name) && !CopyNames.IsAppFile(name) && !IsPart(name))
                .Order(StringComparer.Ordinal)
                .ToList();
            if (strays.Count > 0)
            {
                var folder = AsideFolder(unrecorded, strays);
                foreach (var name in strays)
                {
                    var from = System.IO.Path.Combine(paths.Copies, name);
                    if (Directory.Exists(from))
                        Directory.Move(from, System.IO.Path.Combine(folder, name));
                    else
                        File.Move(from, System.IO.Path.Combine(folder, name));
                }
                moved.Add((unrecorded, folder, false));
            }
        }
        lock (_hashes)
            _hashes.Clear();
        return moved
            .GroupBy(m => ServerAddress.Name(m.Server), StringComparer.OrdinalIgnoreCase)
            .Select(g => new AsideCopies(g.Key, g.Select(m => m.Folder).Min(StringComparer.Ordinal)!, g.Count(m => m.Copy)))
            .OrderBy(a => a.Server, StringComparer.Ordinal)
            .ToList();
    }

    /// <summary>
    /// Moves one copy and its record from <paramref name="from"/> to <paramref name="to"/>, never over anything: the copy
    /// first, then its record, written as <paramref name="rewrite"/> when that's given. If the record can't follow, the
    /// copy moves back, so neither is left without the other. A record with no copy, from a download that never
    /// finished, moves alone.
    /// </summary>
    internal static void MoveCopy(string from, string to, string fileName, CopyRecord? rewrite = null)
    {
        var copy = System.IO.Path.Combine(from, fileName);
        var record = System.IO.Path.Combine(from, CopyNames.RecordName(fileName));
        var movedCopy = System.IO.Path.Combine(to, fileName);
        var movedRecord = System.IO.Path.Combine(to, CopyNames.RecordName(fileName));
        var hasCopy = File.Exists(copy);
        if (hasCopy)
            File.Move(copy, movedCopy);
        var written = false;
        try
        {
            if (rewrite is null)
                File.Move(record, movedRecord);
            else
            {
                Write(rewrite, to);
                written = true;
                File.Delete(record);
            }
        }
        catch
        {
            if (written)
                File.Delete(movedRecord);
            if (hasCopy)
                File.Move(movedCopy, copy);
            throw;
        }
    }

    /// <summary>
    /// The folder beside Copies for <paramref name="server"/>'s copies set aside, <c>Copies from &lt;host[-port]&gt;</c>,
    /// or the next numbered one where none of <paramref name="names"/> is taken, made private when it isn't there yet.
    /// The records say exactly which server, so servers with the same host and port share it.
    /// </summary>
    private string AsideFolder(string server, IReadOnlyCollection<string> names)
    {
        var name = AsidePrefix + string.Concat(ServerAddress.Name(server).Select(c => c is '/' or ':' or '\\' || char.IsControl(c) ? '-' : c));
        for (var n = 1; ; n++)
        {
            var folder = System.IO.Path.Combine(paths.Support, n == 1 ? name : $"{name} {n}");
            if (File.Exists(folder))
                continue;
            if (!Directory.Exists(folder))
            {
                if (OperatingSystem.IsWindows())
                    Directory.CreateDirectory(folder);
                else
                    Directory.CreateDirectory(folder, PrivateMode);
                return folder;
            }
            if (names.All(x => !System.IO.Path.Exists(System.IO.Path.Combine(folder, x))))
                return folder;
        }
    }

    /// <summary>A download's temporary part (<c>.healdan-*.part</c>), which the resume sweeps.</summary>
    private static bool IsPart(string name) =>
        name.StartsWith(".healdan-", StringComparison.Ordinal) && name.EndsWith(".part", StringComparison.Ordinal);
```

- [ ] **Step 6: Set aside by record at a sign-in to another server**

In `HelperSession.cs`, replace `SetCopiesAside`, summary included, with:

```csharp
    /// <summary>
    /// A sign-in to another server than the settings name (spec §5.4): every copy that doesn't name the new server is
    /// set aside first (<see cref="CopyStore.SetAside"/>), and a record without a server, from before records named one,
    /// counts as the old server's. None goes to the new server, where the same doc # can be another document. The user
    /// is told where they are. Returns why the sign-in can't go on, or null.
    /// </summary>
    private string? SetCopiesAside(Uri server)
    {
        if (HelperSettings.Load(_paths).Server is not { } previous)
            return null;
        string old;
        try
        {
            var parsed = ParseAddress(previous);
            if (SameServer(parsed, server))
                return null;
            old = Address(parsed);
        }
        catch (UriFormatException)
        {
            old = previous; // the copies came from somewhere, but not from this one
        }
        var to = HealdanLink.HostAndPort(server);
        try
        {
            TellSetAside(new CopyStore(_paths, Address(server)).SetAside(Address(server), old), to);
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            var from = ServerAddress.Name(old);
            _log.Warn($"The copies from {from} couldn't be set aside: {e.Message}");
            return $"Healdan Helper couldn't move the copies from {from} out of the way, so it hasn't signed in to {to}: {e.Message}";
        }
    }

    /// <summary>Tells the user where each server's copies went, and that they come back (spec §5.4).</summary>
    private void TellSetAside(IReadOnlyList<AsideCopies> moved, string to)
    {
        foreach (var group in moved)
        {
            var folder = Path.GetFileName(group.Folder);
            _log.Info($"Set aside for {to}: the copies from {group.Server} are in {folder}");
            _notifier.Notify(Helper, $"The copies from {group.Server} are now in the {folder} folder, beside Healdan Helper's "
                + $"Copies folder. Healdan Helper won't send them to {to}, and brings them back when you sign in to {group.Server} again.");
        }
    }
```

- [ ] **Step 7: Run the tests to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CopyStoreTests|FullyQualifiedName~HelperSessionTests"`
Expected: all pass. If `ACopyWhoseRecordCantFollowMovesBack` fails because `File.Move` onto a directory doesn't throw on this platform, put a read-only empty folder at `to` instead (Unix only) and check the copy is back.

- [ ] **Step 8: Commit**

```bash
git add clients/helper
git commit -m "Set the helper's copies aside by the server their records name (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Bring Copies Back, and List Them

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Copies/AsideCopies.cs` (add `BroughtBack`, `StayedAside`)
- Modify: `clients/helper/Healdan.Helper.Core/Copies/CopyStore.cs` (`All` through `Records`; add `BringBack`, `Aside`, `IsOpen`, `AsideFolders`, `Records`)
- Test: `CopyStoreTests.cs`

**Interfaces:**
- Consumes: Task 2's `AsideCopies`, `SetAside`, `MoveCopy`, `AsidePrefix`.
- Produces: `record BroughtBack(int Count, IReadOnlyList<StayedAside> Stayed)`; `record StayedAside(string Title, string DocRef, string Folder, string Why)`; `CopyStore.BringBack(string server) : BroughtBack`; `CopyStore.Aside() : IReadOnlyList<AsideCopies>`.

- [ ] **Step 1: Write the failing tests**

In `CopyStoreTests.cs`:

```csharp
    [Fact]
    public void BringBackMovesOnlyTheServersCopiesBack()
    {
        var a = Write(A, 1, content: "edited");
        var b = Write(B, 2, "Other.docx");
        Store("https://third.test").SetAside("https://third.test", "https://third.test");

        var back = Store(A).BringBack(A);

        Assert.Equal(1, back.Count);
        Assert.Empty(back.Stayed);
        Assert.Equal("edited", File.ReadAllText(a));
        Assert.Equal(a, Assert.Single(Store(A).All()).Path);
        Assert.False(Directory.Exists(Path.Combine(_root, "Copies from healdan.example"))); // emptied, so removed
        Assert.True(File.Exists(Path.Combine(_root, "Copies from localhost-8080", Path.GetFileName(b))));
        Assert.Equal(0, Store(A).BringBack(A).Count);
    }

    [Fact]
    public void ACopyStaysAsideWhileOpenOrWhileItsNameIsTaken()
    {
        var locked = Write(A, 1);
        var owned = Write(A, 2, "Other.docx");
        var taken = Write(A, 3, "Third.docx");
        Store(B).SetAside(B, A);
        var aside = Path.Combine(_root, "Copies from healdan.example");
        var lockFile = Path.Combine(aside, $".~lock.{Path.GetFileName(locked)}#"); // opened from that folder in LibreOffice
        File.WriteAllText(lockFile, "lock");
        var ownerFile = Path.Combine(aside, "~$" + Path.GetFileName(owned)[2..]); // or in Word
        File.WriteAllText(ownerFile, "owner");
        File.WriteAllText(taken, "a file of that name, with no record");

        var back = Store(A).BringBack(A);

        Assert.Equal(0, back.Count);
        Assert.Equal(3, back.Stayed.Count);
        Assert.All(back.Stayed, s => Assert.Equal(aside, s.Folder));
        Assert.Contains(back.Stayed, s => s.DocRef == "#1 v2.1" && s.Why.Contains("open in an app"));
        Assert.Contains(back.Stayed, s => s.DocRef == "#2 v2.1" && s.Why.Contains("open in an app"));
        Assert.Contains(back.Stayed, s => s.DocRef == "#3 v2.1" && s.Why.Contains("already has a file with its name"));
        Assert.True(Directory.Exists(aside));

        File.Delete(lockFile);
        File.Delete(ownerFile);
        File.Delete(taken);
        Assert.Equal(3, Store(A).BringBack(A).Count);
        Assert.False(Directory.Exists(aside));
    }

    [Fact]
    public void ASetAsideRecordWithoutAServerStaysAsideUncounted()
    {
        var old = Write(null, 1);
        // as the set-aside before records named their server left it: everything moved, the record as it was
        var aside = Path.Combine(_root, "Copies from healdan.example");
        Directory.Move(Paths.Copies, aside);

        var back = Store(A).BringBack(A);

        Assert.Equal(0, back.Count);
        Assert.Empty(back.Stayed);
        Assert.True(File.Exists(Path.Combine(aside, Path.GetFileName(old))));
        Assert.Empty(Store(A).Aside());
    }

    [Fact]
    public void AsideCountsEachServersCopies()
    {
        Write(A, 1);
        Write(A, 2, "Other.docx");
        Write(B, 3, "Third.docx");
        Store("https://third.test").SetAside("https://third.test", "https://third.test");

        var aside = Store().Aside();

        Assert.Equal(2, aside.Count);
        Assert.Equal(new AsideCopies("healdan.example", Path.Combine(_root, "Copies from healdan.example"), 2), aside[0]);
        Assert.Equal(new AsideCopies("localhost:8080", Path.Combine(_root, "Copies from localhost-8080"), 1), aside[1]);
        Assert.Equal("2 copies waiting for healdan.example", aside[0].Line);
        Assert.Equal("1 copy waiting for localhost:8080", aside[1].Line);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CopyStoreTests"`
Expected: build errors: no `BringBack`, no `Aside`.

- [ ] **Step 3: Add the result types**

Append to `AsideCopies.cs`:

```csharp
/// <summary>What <see cref="CopyStore.BringBack"/> did: how many copies came back, and which stayed aside.</summary>
public sealed record BroughtBack(int Count, IReadOnlyList<StayedAside> Stayed);

/// <summary>A set-aside copy that didn't come back (spec §5.4).</summary>
/// <param name="Title">Its title, as its window shows it, for the notice.</param>
/// <param name="DocRef">Its <c>#1234 v2.1</c>, for the log.</param>
/// <param name="Folder">The folder it stays in.</param>
/// <param name="Why">Why, as the clause the notice ends with.</param>
public sealed record StayedAside(string Title, string DocRef, string Folder, string Why);
```

- [ ] **Step 4: Read records from any folder**

In `CopyStore.cs`, replace `All` with:

```csharp
    /// <summary>Every copy with a readable record; a damaged record is skipped.</summary>
    public IReadOnlyList<(string Path, CopyRecord Record)> All() =>
        Directory.Exists(paths.Copies) ? Records(paths.Copies).Select(r => (PathOf(r), r)).ToList() : [];
```

and add below `Read`:

```csharp
    /// <summary>Each readable record in <paramref name="folder"/>, in name order, read before anything moves.</summary>
    private static List<CopyRecord> Records(string folder) =>
        Directory.EnumerateFiles(folder, ".*.healdan.json").Order(StringComparer.Ordinal).Select(Read).OfType<CopyRecord>().ToList();

    /// <summary>The folders beside Copies holding copies set aside, in name order.</summary>
    private List<string> AsideFolders() =>
        Directory.Exists(paths.Support)
            ? Directory.EnumerateDirectories(paths.Support, AsidePrefix + "*").Order(StringComparer.Ordinal).ToList()
            : [];

    /// <summary>Whether an app's lock or owner file beside the copy says the app has it open there.</summary>
    private static bool IsOpen(string folder, string name) =>
        File.Exists(System.IO.Path.Combine(folder, CopyNames.LibreOfficeLockName(name)))
        || CopyNames.WordOwnerNames(name).Any(owner => File.Exists(System.IO.Path.Combine(folder, owner)));
```

- [ ] **Step 5: Add `BringBack` and `Aside`**

In `CopyStore.cs`, after `SetAside`'s helpers:

```csharp
    /// <summary>
    /// Spec §5.4: moves each set-aside copy whose record names <paramref name="server"/> back into Copies with its record
    /// (<see cref="MoveCopy"/>), from every <c>Copies from</c> folder beside it, and says how many came back and which
    /// stayed. One stays aside while an app's lock or owner file beside it says it's open there, and when Copies already
    /// has something of its name; a move that fails leaves it aside too. Each is tried again at the next start. A folder
    /// left empty is removed. A record without a server is never brought back.
    /// </summary>
    public BroughtBack BringBack(string server)
    {
        var count = 0;
        var stayed = new List<StayedAside>();
        foreach (var folder in AsideFolders())
        {
            foreach (var record in Records(folder))
            {
                if (!ServerAddress.Same(record.Server, server))
                    continue;
                var name = record.FileName;
                var title = CopyNames.Title(record.DocumentName, record.DocNumber, record.FileVersion, record.ReadOnly);
                var docRef = CopyNames.DocRef(record.DocNumber, record.FileVersion);
                var target = System.IO.Path.Combine(paths.Copies, name);
                if (IsOpen(folder, name))
                    stayed.Add(new StayedAside(title, docRef, folder,
                        "it's open in an app. Close it, and it comes back the next time Healdan Helper starts"));
                else if (System.IO.Path.Exists(target) || System.IO.Path.Exists(RecordPath(target)))
                    stayed.Add(new StayedAside(title, docRef, folder,
                        "Healdan Helper's Copies folder already has a file with its name, so it stays there for you to file"));
                else
                {
                    try
                    {
                        EnsureFolders();
                        MoveCopy(folder, paths.Copies, name);
                        count++;
                    }
                    catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                    {
                        stayed.Add(new StayedAside(title, docRef, folder, e.Message.TrimEnd('.')));
                    }
                }
            }
            try
            {
                if (!Directory.EnumerateFileSystemEntries(folder).Any())
                    Directory.Delete(folder);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // Removed at a later start instead.
            }
        }
        if (count > 0)
            lock (_hashes)
                _hashes.Clear();
        return new BroughtBack(count, stayed);
    }

    /// <summary>
    /// Spec §5.4: the copies set aside, by server, with the first folder holding each server's, for the menu's line per
    /// server. Only records are read, never a copy, as the menu asks at each rebuild. A record without a server isn't
    /// counted.
    /// </summary>
    public IReadOnlyList<AsideCopies> Aside()
    {
        try
        {
            return AsideFolders()
                .SelectMany(folder => Records(folder).Where(r => r.Server is not null)
                    .Select(r => (Server: ServerAddress.Name(r.Server!), Folder: folder)))
                .GroupBy(c => c.Server, StringComparer.OrdinalIgnoreCase)
                .Select(g => new AsideCopies(g.Key, g.Select(c => c.Folder).Min(StringComparer.Ordinal)!, g.Count()))
                .OrderBy(a => a.Server, StringComparer.Ordinal)
                .ToList();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return []; // asked again at the next rebuild
        }
    }
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~CopyStoreTests|FullyQualifiedName~CopyCycle"`
Expected: all pass (the cycle's tests run through `All`, which now goes through `Records`).

- [ ] **Step 7: Commit**

```bash
git add clients/helper
git commit -m "Bring set-aside copies back, and list them by server (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Settle the Copies at Every Start

**Files:**
- Modify: `clients/helper/Healdan.Helper.Core/Session/HelperSession.cs` (class summary, `CopiesAside`, `BeginAsync`, new `SettleCopies`)
- Test: `HelperSessionTests.cs`

**Interfaces:**
- Consumes: `CopyStore.SetAside`, `BringBack`, `Aside`; `TellSetAside` (Task 2).
- Produces: `HelperSession.CopiesAside() : IReadOnlyList<AsideCopies>` for the menu (Task 5).

- [ ] **Step 1: Write the failing tests**

In `HelperSessionTests.cs`, add `using Healdan.Helper.Core.Api;`, this helper beside `OpenAsync`, and the four tests after `ACopyRecordsTheServerItCameFrom`:

```csharp
    private static DocumentView View(long number, string name = "Lease agreement.docx") =>
        new("document", number, name, 10, "2.1", "2.1", $"{number}.2.1",
            new FolderRef(5, "Documents", "folder"), null, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, true);

    [Fact]
    public async Task CopiesSetAsideComeBackWhenTheirServerSignsInAgain()
    {
        SignedInBefore();
        var session = Make(signOutFlush: TimeSpan.FromMilliseconds(300));
        await session.StartAsync();
        var path = await OpenAsync(session);
        _server.Down = true;
        FakeApp.Write(path, "an edit that can't go up");
        await Wait.Until(() => _notifier.All.Contains("A save is waiting for Healdan"), "the save waiting");
        FakeApp.Close(path); // its window closes with the save still waiting
        _windows.OnSignOutHere = (_, _) => Task.FromResult(true);
        await session.SignOutAsync();
        _server.Down = false;

        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;
        await session.SignInAgainAsync();
        Assert.True(session.Started);
        Assert.Equal(new AsideCopies("healdan.test", Path.Combine(_root, "Copies from healdan.test"), 1),
            Assert.Single(session.CopiesAside()));
        await session.SignOutAsync();

        _windows.OnSignIn = async prompt => await prompt.SignInAsync(Account, "robert", Password) is null;
        await session.SignInAgainAsync();

        Assert.True(session.Started);
        Assert.Empty(session.CopiesAside());
        Assert.Contains("The copy set aside from healdan.test is back", _notifier.All);
        await Wait.Until(() => _server.Saves.Any(s => s.DocNumber == 5 && s.Content == "an edit that can't go up"),
            "the kept save sent to its own server");
        Assert.DoesNotContain(_http.Calls, c => c.Contains("second.test/api/v1/documents/5", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ACopyNamingAnotherServerIsSetAsideAtTheStartWhenTheSettingsAreLost()
    {
        SignedInBefore();
        var first = Make();
        await first.StartAsync();
        var path = await OpenAsync(first);
        first.Dispose(); // Quit: the copy stays, checked out on healdan.test
        File.Delete(Paths.Settings);
        _keychain.Items.Clear();
        _windows.OnSignIn = async prompt => await prompt.SignInAsync("https://second.test", "robert", Password) is null;

        var second = Make();
        await second.StartAsync();
        await Wait.Until(() => second.Started, "started on second.test");

        Assert.True(File.Exists(Path.Combine(_root, "Copies from healdan.test", Path.GetFileName(path))));
        Assert.Contains("won't send them to second.test", _notifier.All);
        Assert.DoesNotContain(_http.Calls, c => c.Contains("second.test/api/v1/documents/5", StringComparison.Ordinal));
    }

    [Fact]
    public async Task AStartThatCantSetAsideAnotherServersCopyDoesntStart()
    {
        if (OperatingSystem.IsWindows())
            return;
        SignedInBefore();
        var (path, _, _) = new CopyStore(Paths, "https://second.test").Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "second.test's document 5");
        File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserExecute); // nothing can leave it
        try
        {
            var session = Make();
            await session.StartAsync();
            await Wait.Until(() => session.StartFailed, "the start refused");

            Assert.False(session.Started);
            Assert.Contains("hasn't started", _notifier.All);
            Assert.True(File.Exists(path));
            Assert.DoesNotContain(_http.Calls, c => c.Contains("healdan.test/api/v1/documents/5", StringComparison.Ordinal));
        }
        finally
        {
            File.SetUnixFileMode(Paths.Copies, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        }
    }

    [Fact]
    public async Task ASetAsideCopyOpenInAnAppComesBackOnceClosed()
    {
        var (path, _, _) = new CopyStore(Paths, Account).Create(View(5), false, "LibreOffice");
        File.WriteAllText(path, "edited while away");
        new CopyStore(Paths, "https://second.test").SetAside("https://second.test", Account);
        var aside = Path.Combine(_root, "Copies from healdan.test");
        var lockFile = Path.Combine(aside, $".~lock.{Path.GetFileName(path)}#");
        File.WriteAllText(lockFile, "opened from that folder");
        SignedInBefore();

        var first = Make();
        await first.StartAsync();
        await Wait.Until(() => first.Started, "started");
        Assert.Contains("open in an app", _notifier.All);
        Assert.Equal(new AsideCopies("healdan.test", aside, 1), Assert.Single(first.CopiesAside()));
        Assert.False(File.Exists(path));
        first.Dispose();

        File.Delete(lockFile);
        var second = Make();
        await second.StartAsync();
        await Wait.Until(() => second.Started, "started again");

        Assert.True(File.Exists(path)); // its check-out had ended, so it's kept for Not Yet in Healdan
        Assert.False(Directory.Exists(aside));
        Assert.Contains("is back", _notifier.All);
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~HelperSessionTests"`
Expected: build error: `HelperSession` has no `CopiesAside`. (With a stub returning `[]`, the four tests fail on their assertions: nothing is set aside at the start, and nothing comes back.)

- [ ] **Step 3: Settle the copies before the resume**

In `HelperSession.cs`, in `BeginAsync`'s `try`, replace the `cycle = new CopyCycle(...)` line from Task 1 with:

```csharp
            var store = new CopyStore(_paths, Address(api.Server));
            SettleCopies(store, api.Server);
            cycle = new CopyCycle(api, store, _app, _device.Id, this, _timings, _otherFiles);
```

Change `BeginAsync`'s summary to:

```csharp
    /// <summary>
    /// Under <see cref="_lifecycle"/>: the copies settled (<see cref="SettleCopies"/>), their resume, then the channel,
    /// then links. The menu and windows see the cycle only once its resume has finished; a settling or a resume that
    /// fails is told to the user, and nothing starts.
    /// </summary>
```

and add after `BeginAsync`:

```csharp
    /// <summary>
    /// Before each resume (spec §5.4): sets aside any copy that names another server, which only a lost settings.json or
    /// a move that failed leaves in Copies, then brings back the signed-in server's. A setting aside that fails throws,
    /// so the helper doesn't start; a copy that can't come back stays aside, and the user is told why.
    /// </summary>
    private void SettleCopies(CopyStore store, Uri server)
    {
        var address = Address(server);
        var name = HealdanLink.HostAndPort(server);
        TellSetAside(store.SetAside(address, address), name);
        BroughtBack back;
        try
        {
            back = store.BringBack(address);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Warn($"The copies set aside from {name} couldn't be brought back: {e.Message}");
            _notifier.Notify(Helper, $"Healdan Helper couldn't bring back the copies set aside from {name}: {e.Message}");
            return;
        }
        if (back.Count > 0)
        {
            _log.Info($"Brought back {back.Count} copies set aside from {name}");
            _notifier.Notify(Helper, back.Count == 1
                ? $"The copy set aside from {name} is back, and Healdan Helper looks after it again."
                : $"The {back.Count} copies set aside from {name} are back, and Healdan Helper looks after them again.");
        }
        foreach (var stayed in back.Stayed)
        {
            var folder = Path.GetFileName(stayed.Folder);
            _log.Warn($"The copy of {stayed.DocRef} stayed in {folder}: {stayed.Why}");
            _notifier.Notify(stayed.Title, $"It stayed in the {folder} folder: {stayed.Why}.");
        }
    }
```

- [ ] **Step 4: Give the menu its list**

After `KeptCopies()` in `HelperSession.cs`:

```csharp
    /// <summary>
    /// Spec §5.4: the copies set aside for each server, for the menu's line per server, whether signed in or not. Read at
    /// each rebuild, from the records alone.
    /// </summary>
    public IReadOnlyList<AsideCopies> CopiesAside() => new CopyStore(_paths).Aside();
```

and in the class summary, change "then the copies' resume, which sweeps the downloads a stop left half done" to "then the copies' resume, after another server's copies are set aside and this one's brought back (spec §5.4), which sweeps the downloads a stop left half done".

- [ ] **Step 5: Run the tests to see them pass**

Run: `dotnet test clients/helper/Healdan.Helper.sln --filter "FullyQualifiedName~HelperSessionTests"`
Expected: all pass. Run it three times: these tests wait on the cycle, and a flaky wait shows up as an occasional failure.

- [ ] **Step 6: Commit**

```bash
git add clients/helper
git commit -m "Set aside and bring back the helper's copies at every start (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: The Menu's Line per Server

**Files:**
- Modify: `clients/helper/Healdan.Helper/Windows/AppWindows.cs` (add `ShowFolder` after `OpenWebPage`)
- Modify: `clients/helper/Healdan.Helper/Tray/TrayMenu.cs` (class summary; the rebuild, after the Not Yet in Healdan block)

**Interfaces:**
- Consumes: `HelperSession.CopiesAside()`, `AsideCopies.Line`, `AsideCopies.Folder`.

`TrayMenu` has no tests (nor does anything in the app drive its menu), so this task is checked by building, by `AsideCopies.Line`'s test in Task 3, and by hand in Task 7.

- [ ] **Step 1: Show a folder from the menu**

In `AppWindows.cs`, after `OpenWebPage`:

```csharp
    /// <summary>
    /// Shows a folder in Finder (or the file manager), off the UI thread, for the menu's line per server with copies set
    /// aside (spec §5.4). Nothing in it is opened. When it can't be shown, the user is told.
    /// </summary>
    public void ShowFolder(string folder) => _ = Task.Run(() =>
    {
        try
        {
            SystemOpener.Open(folder);
        }
        catch (Exception e)
        {
            log.Error($"{folder} couldn't be shown", e);
            notifier.Notify("Healdan Helper", "Healdan Helper couldn't show the folder.");
        }
    });
```

- [ ] **Step 2: Draw the lines**

In `TrayMenu.cs`'s rebuild, after the `if (kept.Count > 0) { ... }` block and before the separator above Open from Healdan:

```csharp
        var aside = _session.CopiesAside();
        if (aside.Count > 0)
        {
            items.Add(new NativeMenuItemSeparator());
            foreach (var group in aside)
                items.Add(Item(group.Line, () => _windows.ShowFolder(group.Folder)));
        }
```

In the class summary, change "the copies Not Yet in Healdan (spec §5.4: kept with changes that can't go up), Open from Healdan" to "the copies Not Yet in Healdan (spec §5.4: kept with changes that can't go up), a line for each server with copies waiting (spec §5.4: set aside), Open from Healdan".

- [ ] **Step 3: Build and run every helper test**

Run: `dotnet build clients/helper/Healdan.Helper.sln && dotnet test clients/helper/Healdan.Helper.sln`
Expected: builds with no warnings from the changed files; every test passes.

- [ ] **Step 4: Commit**

```bash
git add clients/helper
git commit -m "Show the helper's copies waiting for another server in its menu (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: The Guides

**Files:**
- Modify: `site/content/user/office/installing-the-helper/index.md`
- Modify: `site/content/user/office/editing-and-versions/index.md`
- Modify: `site/content/developer/desktop-clients.md`

- [ ] **Step 1: Installing the Helper**

In Settings' **Server** bullet, after "The helper never sends them to the new server, where the same doc # can be a different document.", add:

```markdown
  They come back, with any saves still to send, when you sign in to that server again. Meanwhile the menu shows how
  many are waiting.
```

In The Helper's Menu table, after the Not Yet in Healdan row:

```markdown
| "2 copies waiting for healdan.example" | Copies left from another server, set aside when you signed in to this one. Choose it to see their folder. They come back when you sign in to that server again: see [Settings](#settings) |
```

In If Healdan Can't Be Reached, change the end of the **Saves that never reached Healdan** bullet from "they're moved to a folder of their own, as for the [Server](#settings) setting, and never sent." to:

```markdown
  they're moved to a folder of their own, as for the [Server](#settings) setting, and wait there until you sign in to
  the same server again.
```

Rewrap the changed paragraphs to the file's line length.

- [ ] **Step 2: Editing and Versions**

Change the paragraph "When you sign in to another server, the copies left from the old one move to a folder of their own beside `Copies`, such as `Copies from healdan.example`, and the helper never sends them to the new server." to:

```markdown
When you sign in to another server, the copies left from the old one move to a folder of their own beside `Copies`,
such as `Copies from healdan.example`, and the helper never sends them to the new server. They come back when you sign
in to the old server again.
```

- [ ] **Step 3: The Developer Guide's Local Copies**

In `desktop-clients.md`, replace the bullet that starts "**The copies belong to the server in `settings.json`.**" with:

```markdown
- **The copies belong to one server.** While the helper runs, `Copies/` holds only the signed-in server's copies.
  Each record names its server (`CopyRecord.Server`, as `settings.json` holds it; a record without one belongs to the
  server in `settings.json`). A sign-in to another server, and every start before the resume, set aside each copy
  naming another server into `Copies from <host[-port]>/` beside `Copies/` (`CopyStore.SetAside`), numbered when a
  name is taken there; a start then brings back the signed-in server's (`CopyStore.BringBack`), except one open in an
  app there or whose name is taken in `Copies/`. A copy moves first, then its record, and moves back if the record
  can't follow. Lock and owner files stay, as the app deletes them by the path it opened. A setting aside that fails
  refuses the sign-in, or the start. The menu has a line per server with copies waiting (`CopyStore.Aside`).
```

- [ ] **Step 4: Check the docs site**

Run:

```bash
hugo --source site --panicOnWarning --destination "$(mktemp -d)"
python3 .github/scripts/check-site-sources.py
python3 .github/scripts/check-site-links.py
python3 -m unittest discover -s .github/scripts/tests
```

Expected: the build has no warnings, and each script passes. If `check-site-links.py` needs the built site, pass it the destination the way `.github/workflows/docs-site.yml` does.

- [ ] **Step 5: Commit**

```bash
git add site
git commit -m "Say in the guides that set-aside copies come back (#85)" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Check It Whole, and Open the Pull Request

- [ ] **Step 1: Every helper and extension test**

Run: `dotnet test clients/helper/Healdan.Helper.sln && python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: all pass.

- [ ] **Step 2: By hand, against a local stack (macOS)**

With the stack up (`docker compose -f compose.yaml -f docker/tests/compose.smoke.yaml up -d`, which gives matter 1.1 a Documents folder), upload a `.odt` into it in the browser, then:

```bash
HEALDAN_HELPER_DEV_ROOT=/tmp/hh dotnet run --project clients/helper/Healdan.Helper
```

A Debug helper shows no notifications, so each notice is the menu's status line, and it installs no extension, so this check needs no save from LibreOffice.

1. Sign in to `http://localhost:8080` as `admin` and open the document from Open from Healdan. Leave it open in LibreOffice.
2. Sign Out: the copy stays, checked out. Close the document in LibreOffice.
3. Sign in to `http://127.0.0.1:8080`, which counts as another server. Check the status line, that `/tmp/hh/Copies from localhost-8080/` holds the copy and its record, and that the menu shows **1 copy waiting for localhost:8080**. Choose the line: Finder shows that folder.
4. Sign Out, and sign in to `http://localhost:8080` again. Check the status line says the copy is back, that the folder and the menu line are gone, and that the web page shows the document checked in, as a closed window's copy is.

If no stack can be run here, say so in the pull request rather than claiming the check.

- [ ] **Step 3: Push and open the pull request**

Write the body to `pr-85.md` in the session's scratchpad, then:

```bash
git push -u origin HEAD
gh pr create --repo Healdan-Project/healdan --base master --title "Helper: set-aside copies come back to their server (#85)" --body-file "<scratchpad>/pr-85.md"
```

The body says what changed (records name their server; a start sets aside and brings back; the menu line; the guides and §5.4), how it was tested (the new tests, and the check by hand or why it wasn't done), and "Closes #85.", ending with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```
