using System.Security.Cryptography;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Tests.Copies;

public class CopyStoreTests : IDisposable
{
    private readonly string _root = Directory.CreateTempSubdirectory("healdan-copies-").FullName;
    private HelperPaths Paths => HelperPaths.For(_root);
    private CopyStore Store() => new(Paths);

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
    public void SetAsideMovesEveryCopyToAFolderNamedForItsServer()
    {
        var store = Store();
        Assert.Null(store.SetAside("healdan.example")); // nothing to move
        var (path, _, _) = store.Create(Doc(1234), false, "LibreOffice");
        File.WriteAllText(path, "x");

        var aside = store.SetAside("localhost:8080")!;

        Assert.Equal(Path.Combine(_root, "Copies from localhost-8080"), aside);
        Assert.True(File.Exists(Path.Combine(aside, Path.GetFileName(path))));
        Assert.True(File.Exists(Path.Combine(aside, "." + Path.GetFileName(path) + ".healdan.json")));
        if (!OperatingSystem.IsWindows())
            Assert.Equal(UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute, File.GetUnixFileMode(aside));
        Assert.True(Directory.Exists(Paths.Copies)); // the folder itself stays
        Assert.Empty(Directory.GetFileSystemEntries(Paths.Copies));
        Assert.Empty(store.All());

        store.Create(Doc(1234), false, "LibreOffice");
        Assert.Equal(Path.Combine(_root, "Copies from localhost-8080 2"), store.SetAside("localhost:8080"));
    }

    [Fact]
    public void LongNamesGetRecordsThatFit()
    {
        var (path, _, _) = Store().Create(Doc(7, new string('x', 400) + ".docx"), false, "LibreOffice");
        File.WriteAllText(path, "x");
        Assert.Single(Store().All());
        Assert.True(File.Exists(Path.Combine(Paths.Copies, "." + Path.GetFileName(path) + ".healdan.json")));
    }
}
