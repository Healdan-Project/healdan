using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Tests.Copies;

public class CopyStoreTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-copies-").FullName;
    private HelperPaths Paths => HelperPaths.For(_root);

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

    public void Dispose() => Directory.Delete(_root, true);

    private static DocumentView Doc(long number, string name = "Lease agreement.docx", string fileVersion = "2.1") =>
        new("document", number, name, 10, "2.1", fileVersion, $"{number}.{fileVersion}",
            new FolderRef(5, "Documents", "folder"), null, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, true);

    private static string Hash(string path) => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant();

    [Fact]
    public void PathsLiveUnderTheRoot()
    {
        var p = Paths;
        Assert.Equal(Path.Combine(_root, "Copies"), p.Copies);
        Assert.Equal(Path.Combine(_root, "settings.json"), p.Settings);
        Assert.Equal(Path.Combine(_root, "device.json"), p.Device);
        Assert.Equal(Path.Combine(_root, "channel.json"), p.Channel);
        Assert.Equal(Path.Combine(_root, "helper.log"), p.Log);
    }

    [Fact]
    public void RecordSurvivesReload()
    {
        var (path, rec, isExisting) = Store().Create(Doc(1234), false, "LibreOffice");
        Assert.False(isExisting);
        Assert.Equal(Path.Combine(Paths.Copies, "Lease agreement #1234 v2.1.docx"), path);

        var found = Assert.Single(Store().All());
        Assert.Equal(path, found.Path);
        Assert.Equal(1234, found.Record.DocNumber);
        Assert.Equal("LibreOffice", found.Record.App);
        Assert.Equal(rec, found.Record);
        Assert.NotNull(Store().Find(path));
        Assert.Null(Store().Find(Path.Combine(Paths.Copies, "nope.docx")));
    }

    [Fact]
    public void RecordIsAHiddenFileBesideTheCopy()
    {
        var (path, _, _) = Store().Create(Doc(1), false, "LibreOffice");
        Assert.True(File.Exists(Path.Combine(Paths.Copies, ".Lease agreement #1 v2.1.docx.healdan.json")));
        Assert.False(File.Exists(path), "Create doesn't write the copy's content");
        Assert.Empty(Directory.GetFiles(Paths.Copies, "*.tmp*"));
    }

    [Fact]
    public void FolderIsPrivate()
    {
        if (OperatingSystem.IsWindows()) return;
        Store().Create(Doc(1), false, "LibreOffice");
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute,
            File.GetUnixFileMode(Paths.Copies));
        Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute,
            File.GetUnixFileMode(Paths.Support));
    }

    [Fact]
    public void DeleteLeavesOthers()
    {
        var store = Store();
        var (a, _, _) = store.Create(Doc(1), false, "LibreOffice");
        var (b, _, _) = store.Create(Doc(2, "Other.docx"), false, "LibreOffice");
        File.WriteAllText(a, "a");
        File.WriteAllText(b, "b");
        File.WriteAllText(Path.Combine(Paths.Copies, $".~lock.{Path.GetFileName(a)}#"), "lock");
        var otherLock = Path.Combine(Paths.Copies, $".~lock.{Path.GetFileName(b)}#");
        File.WriteAllText(otherLock, "lock");

        store.Delete(a);

        Assert.False(File.Exists(a));
        Assert.Empty(Directory.GetFiles(Paths.Copies, "*#1 v*", SearchOption.TopDirectoryOnly));
        Assert.False(File.Exists(Path.Combine(Paths.Copies, $".~lock.{Path.GetFileName(a)}#")));
        Assert.True(File.Exists(b));
        Assert.True(File.Exists(otherLock));
        Assert.Equal(b, Assert.Single(store.All()).Path);
        Assert.True(Directory.Exists(Paths.Copies));
    }

    [Fact]
    public void DeleteRemovesWordOwnerFiles()
    {
        var store = Store();
        var (a, _, _) = store.Create(Doc(1), false, "Word");
        File.WriteAllText(a, "a");
        var name = Path.GetFileName(a);
        var owner = Path.Combine(Paths.Copies, "~$" + name[2..]);
        File.WriteAllText(owner, "o");
        store.Delete(a);
        Assert.False(File.Exists(owner));
    }

    [Fact]
    public void UnconfirmedChanges()
    {
        var store = Store();
        var (path, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        Assert.False(store.HasUnconfirmedChanges(path), "no copy file yet");

        File.WriteAllText(path, "downloaded");
        store.Save(rec with { ConfirmedHash = Hash(path) });
        Assert.False(store.HasUnconfirmedChanges(path));

        File.WriteAllText(path, "edited");
        Assert.True(store.HasUnconfirmedChanges(path));

        store.Save(store.Find(path)!.Value.Item2 with { ConfirmedHash = Hash(path) });
        Assert.False(store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public void ACopyWithNoConfirmedHashCountsAsUnconfirmed()
    {
        var store = Store();
        var (path, _, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "something");
        Assert.True(store.HasUnconfirmedChanges(path));
    }

    [Fact]
    public void CreateKeepsAnExistingCopyWithUnconfirmedChanges()
    {
        var store = Store();
        var (path, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "mine");
        store.Save(rec with { PendingKind = "minor" });

        var (again, record, kept) = store.Create(Doc(1, fileVersion: "2.2"), false, "LibreOffice");
        Assert.True(kept);

        Assert.Equal(path, again);
        Assert.Equal("minor", record.PendingKind);
        Assert.Equal("mine", File.ReadAllText(path));
        Assert.Single(store.All());
    }

    [Fact]
    public void CreateReadOnlyLeavesAnEditedCopyAlone()
    {
        var store = Store();
        var (edited, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(edited, "unsent edits");
        store.Save(rec with { CheckedOutHere = true });

        var (path, record, existing) = store.Create(Doc(1), true, "LibreOffice");

        Assert.False(existing);
        Assert.EndsWith("Lease agreement #1 v2.1 (read-only).docx", path);
        Assert.True(record.ReadOnly);
        Assert.Equal("unsent edits", File.ReadAllText(edited));
        Assert.Equal(2, store.All().Count);
    }

    [Fact]
    public void CreateLeavesAConfirmedCopyOfTheSameDocumentAlone()
    {
        var store = Store();
        var (old, rec, _) = store.Create(Doc(1), true, "LibreOffice");
        File.WriteAllText(old, "old");
        store.Save(rec with { ConfirmedHash = Hash(old), CheckedOutHere = true, LastSaveId = "s1" });

        var (fresh, _, isExisting) = store.Create(Doc(1, fileVersion: "2.2"), false, "LibreOffice");
        Assert.False(isExisting);

        Assert.EndsWith("Lease agreement #1 v2.2.docx", fresh);
        Assert.True(File.Exists(old));
        var kept = store.Find(old)!.Value.Item2;
        Assert.True(kept.CheckedOutHere);
        Assert.Equal("s1", kept.LastSaveId);
        Assert.Equal(2, store.All().Count);
    }

    [Fact]
    public void CreateKeepsAnUnrecordedFileAtTheTargetPath()
    {
        var store = Store();
        Directory.CreateDirectory(Paths.Copies);
        var target = Path.Combine(Paths.Copies, "Lease agreement #1 v2.1.docx");
        File.WriteAllText(target, "the user's work");

        var (path, rec, unrecorded) = store.Create(Doc(1), false, "LibreOffice");
        Assert.True(unrecorded);

        Assert.Equal(target, path);
        Assert.Null(rec.ConfirmedHash);
        Assert.Equal("the user's work", File.ReadAllText(target));
        Assert.True(store.HasUnconfirmedChanges(target));
    }

    [Fact]
    public void CreateOverAConfirmedRecordKeepsCheckOutAndSaveId()
    {
        var store = Store();
        var (path, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "x");
        store.Save(rec with { ConfirmedHash = Hash(path), CheckedOutHere = true, LastSaveId = "s9" });

        var (again, record, confirmedAgain) = store.Create(Doc(1), false, "LibreOffice");
        Assert.False(confirmedAgain);

        Assert.Equal(path, again);
        Assert.True(record.CheckedOutHere);
        Assert.Equal("s9", record.LastSaveId);
        Assert.Equal("x", File.ReadAllText(path));
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"docNumber\":1}")]
    [InlineData("[]")]
    public void AnIncompleteRecordIsSkipped(string body)
    {
        var store = Store();
        store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(Path.Combine(Paths.Copies, ".Partial #9 v1.0.docx.healdan.json"), body);
        Assert.Single(store.All());
    }

    [Fact]
    public void RenameWritesTheNewRecordBeforeMovingTheFile()
    {
        var store = Store();
        var (path, _, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "x");
        // Make the move fail: a directory already sits at the new name.
        Directory.CreateDirectory(Path.Combine(Paths.Copies, "Lease agreement #1 v2.2.docx"));

        Assert.ThrowsAny<IOException>(() => store.Rename(path, "2.2"));

        Assert.True(File.Exists(path));
        Assert.NotNull(store.Find(path));
        Assert.Single(store.All());
    }

    [Fact]
    public void RenameMovesRecord()
    {
        var store = Store();
        var (path, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "x");
        store.Save(rec with { ConfirmedHash = Hash(path) });

        var moved = store.Rename(path, "2.2");

        Assert.Equal(Path.Combine(Paths.Copies, "Lease agreement #1 v2.2.docx"), moved);
        Assert.False(File.Exists(path));
        Assert.Equal("x", File.ReadAllText(moved));
        var single = Assert.Single(store.All());
        Assert.Equal(moved, single.Path);
        Assert.Equal("2.2", single.Record.FileVersion);
        Assert.False(store.HasUnconfirmedChanges(moved));
        Assert.Null(store.Find(path));
    }

    [Fact]
    public void RenameOntoAnotherCopyIsRefusedAndChangesNothing()
    {
        var store = Store();
        var (a, recA, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(a, "a");
        store.Save(recA with { ConfirmedHash = Hash(a), CheckedOutHere = true });
        var (b, recB, _) = store.Create(Doc(1, fileVersion: "2.2"), false, "LibreOffice");
        File.WriteAllText(b, "b");
        store.Save(recB with { ConfirmedHash = Hash(b), LastSaveId = "s7" });

        Assert.Throws<IOException>(() => store.Rename(a, "2.2"));

        Assert.Equal("a", File.ReadAllText(a));
        Assert.Equal("b", File.ReadAllText(b));
        Assert.True(store.Find(a)!.Value.Item2.CheckedOutHere);
        Assert.Equal("s7", store.Find(b)!.Value.Item2.LastSaveId);
        Assert.Equal("2.1", store.Find(a)!.Value.Item2.FileVersion);
        Assert.Equal(2, store.All().Count);
    }

    [Fact]
    public void AConfirmedSaveKeepsTheCopyAtItsPath()
    {
        // R19: the app has the copy open under the name it was opened with, so a save moves the record's versions on
        // but never the file.
        var store = Store();
        var (path, rec, _) = store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(path, "saved as 2.2");
        store.Save(rec with { Version = "2.2", FileVersion = "2.2", ConfirmedHash = Hash(path), CheckedOutHere = true });

        Assert.Equal(Path.Combine(Paths.Copies, "Lease agreement #1 v2.1.docx"), path);
        var found = Assert.Single(store.All());
        Assert.Equal(path, found.Path);
        Assert.Equal("2.2", found.Record.FileVersion);
        Assert.Equal("2.2", store.Find(path)!.Value.Item2.Version);
        Assert.False(store.HasUnconfirmedChanges(path));
        Assert.False(File.Exists(Path.Combine(Paths.Copies, ".Lease agreement #1 v2.2.docx.healdan.json")));
    }

    [Fact]
    public void RenameKeepsReadOnlyMark()
    {
        var store = Store();
        var (path, _, _) = store.Create(Doc(1), true, "LibreOffice");
        File.WriteAllText(path, "x");
        Assert.EndsWith("v2.2 (read-only).docx", store.Rename(path, "2.2"));
    }

    [Fact]
    public void ACorruptRecordIsSkipped()
    {
        var store = Store();
        store.Create(Doc(1), false, "LibreOffice");
        File.WriteAllText(Path.Combine(Paths.Copies, ".Broken #9 v1.0.docx.healdan.json"), "{ not json");
        File.WriteAllText(Path.Combine(Paths.Copies, ".Empty #9 v1.0.docx.healdan.json"), "null");

        Assert.Single(store.All());
    }

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

    [Fact]
    public void LongNamesGetRecordsThatFit()
    {
        var (path, _, _) = Store().Create(Doc(7, new string('x', 400) + ".docx"), false, "LibreOffice");
        File.WriteAllText(path, "x");
        Assert.Single(Store().All());
        Assert.True(File.Exists(Path.Combine(Paths.Copies, "." + Path.GetFileName(path) + ".healdan.json")));
    }

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
}
