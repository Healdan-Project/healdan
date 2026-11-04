// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Mail;

namespace Healdan.Helper.Core.Tests.Mail;

/// <summary>The folders offered for filing a message (M5 spec §6.7): where its thread went, then the ones used lately.</summary>
public class SuggestionsTests
{
    private static readonly DateTimeOffset Day = DateTimeOffset.Parse("2026-11-01T09:00:00Z");

    private readonly Dictionary<long, FolderView> _folders = [];
    private readonly List<long> _read = [];

    private FolderView Folder(long id, bool canFile = true, string? name = null, long matter = 3)
    {
        var folder = new FolderView("folder", id, name ?? $"Folder {id}", "folder", new MatterRef(matter, $"998.{matter} – Healdan Demo"),
            new StoreRef(1, "Healdan"), [new FolderRef(1, "Healdan", "store"), new FolderRef(id, name ?? $"Folder {id}", "folder")], canFile);
        _folders[id] = folder;
        return folder;
    }

    /// <summary>A copy of a message in folder <paramref name="folder"/>, filed <paramref name="days"/> days after a fixed day.</summary>
    private static DocumentView Copy(long folder, int days) => new(
        "document", 100 + days, "Message.eml", 10, "1.0", "1.0", $"{100 + days}.1.0", new FolderRef(folder, $"Folder {folder}", "folder"), null,
        Day.AddDays(days), Day.AddDays(days), null, true);

    private IReadOnlyList<long> Build(IReadOnlyList<DocumentView> thread, IReadOnlyList<FolderView> recent, IReadOnlySet<long>? heldIn = null) =>
        [.. Suggestions.Build(thread, id =>
        {
            _read.Add(id);
            return _folders.GetValueOrDefault(id);
        }, recent, heldIn).Select(f => f.Id)];

    [Fact]
    public void TheThreadsFoldersComeNewestFiledFirst()
    {
        Folder(11);
        Folder(12);
        Folder(13);

        // Given oldest first, and a folder with a newer and an older copy counts by its newest.
        var thread = new[] { Copy(11, 1), Copy(12, 5), Copy(13, 3), Copy(11, 2) };

        Assert.Equal([12, 13, 11], Build(thread, []));
    }

    [Fact]
    public void AFolderThePersonCannotFileIntoIsLeftOut()
    {
        Folder(11);
        Folder(12, canFile: false);
        var recent = new[] { Folder(14), Folder(15, canFile: false) };

        Assert.Equal([11, 14], Build([Copy(12, 9), Copy(11, 1)], recent));
    }

    [Fact]
    public void AThreadFolderThatCannotBeReadIsLeftOut()
    {
        Folder(11); // folder 12 was deleted, or isn't the person's to see: the lookup gives none

        Assert.Equal([11], Build([Copy(12, 9), Copy(11, 1)], []));
    }

    [Fact]
    public void AFolderInAMatterThatHoldsTheMessageIsLeftOut()
    {
        Folder(11);
        Folder(12, matter: 4);
        var recent = new[] { Folder(14), Folder(15, matter: 4), Folder(16, matter: 5) };

        // The message is in matter 3 already: File Here into any of its folders could only answer already-filed.
        Assert.Equal([12, 15, 16], Build([Copy(11, 2), Copy(12, 1)], recent, heldIn: new HashSet<long> { 3 }));
        Assert.Equal([16], Build([Copy(11, 2), Copy(12, 1)], recent, heldIn: new HashSet<long> { 3, 4 }));
    }

    [Fact]
    public void RecentFoldersFollowWithoutRepeats()
    {
        Folder(11);
        var recent = new[] { Folder(14), Folder(11), Folder(15), Folder(14) };

        Assert.Equal([11, 14, 15], Build([Copy(11, 1)], recent));
    }

    [Fact]
    public void AtMostFiveAreOffered()
    {
        foreach (var id in new long[] { 11, 12, 13 })
            Folder(id);
        var recent = new[] { Folder(14), Folder(15), Folder(16), Folder(17) };

        var offered = Build([Copy(11, 1), Copy(12, 2), Copy(13, 3)], recent);

        Assert.Equal(5, Suggestions.Max);
        Assert.Equal([13, 12, 11, 14, 15], offered); // the thread's first, then recent ones until five
    }

    [Fact]
    public void NoThreadAndNoRecentFoldersGiveNone()
    {
        Assert.Empty(Build([], []));
    }

    [Fact]
    public void EachThreadFolderIsReadOnce()
    {
        Folder(11);
        Folder(12);

        Build([Copy(11, 1), Copy(12, 2), Copy(11, 3), Copy(12, 4), Copy(11, 5)], []);

        Assert.Equal([11, 12], _read.Order());
    }

    [Fact]
    public void AFolderIsWrittenFromItsMatterDown()
    {
        var folder = new FolderView("folder", 12, "Drafts", "folder", new MatterRef(3, "998.1 – Healdan Demo"), new StoreRef(1, "Healdan"),
            [new FolderRef(1, "Healdan", "store"), new FolderRef(10, "998.1 – Healdan Demo", "matter"), new FolderRef(11, "Documents", "folder"), new FolderRef(12, "Drafts", "folder")],
            true);

        Assert.Equal("998.1 – Healdan Demo › Documents › Drafts", Suggestions.Written(folder));
    }
}
