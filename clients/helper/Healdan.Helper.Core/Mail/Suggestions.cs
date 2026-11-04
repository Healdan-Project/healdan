// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Api;

namespace Healdan.Helper.Core.Mail;

/// <summary>
/// The folders the pane offers as File Here for a message (M5 spec §6.7): where its thread's messages are filed, newest
/// first, then the folders the person used lately. Only folders they may file into (<see cref="FolderView.CanFile"/>: the
/// server's own test for it), and at most <see cref="Max"/>.
/// </summary>
public static class Suggestions
{
    /// <summary>The most folders offered.</summary>
    public const int Max = 5;

    /// <summary>
    /// The thread's copies' folders, newest filed first, where the person may file; then <paramref name="recent"/> folders;
    /// no repeats, and at most <see cref="Max"/>. A folder <paramref name="folder"/> can't give (gone, or not the person's to
    /// see) is left out. It is asked for once for each folder, and not at all for those that come after the <see cref="Max"/>th.
    /// </summary>
    /// <param name="threadCopies">The documents that are copies of the thread's messages, in any order.</param>
    /// <param name="folder">A folder by its id; null when there is none to be had.</param>
    /// <param name="recent">The person's recent folders, most recent first.</param>
    public static IReadOnlyList<FolderView> Build(IReadOnlyList<DocumentView> threadCopies, Func<long, FolderView?> folder, IReadOnlyList<FolderView> recent)
    {
        List<FolderView> offered = [];
        foreach (var id in ThreadFolderIds(threadCopies))
        {
            if (offered.Count >= Max)
                return offered;
            if (folder(id) is { CanFile: true } found)
                offered.Add(found);
        }
        foreach (var candidate in recent)
        {
            if (offered.Count >= Max)
                break;
            if (candidate.CanFile && offered.All(o => o.Id != candidate.Id))
                offered.Add(candidate);
        }
        return offered;
    }

    /// <summary>The folders of the thread's copies, each once, the one holding the newest copy first: the order <see cref="Build"/> offers them in.</summary>
    public static IReadOnlyList<long> ThreadFolderIds(IReadOnlyList<DocumentView> threadCopies) =>
        [.. threadCopies.OrderByDescending(c => c.DateFiled).Select(c => c.Folder.Id).Distinct()];

    /// <summary>
    /// The folder written out for the person, from its matter down: <c>998.1 – Healdan Demo › Documents › Drafts</c>. The
    /// Store isn't part of it, and the matter is named as people know it.
    /// </summary>
    public static string Written(FolderView folder) =>
        string.Join(" › ", folder.Path.Where(p => p.Kind != "store")
            .Select(p => p.Kind == "matter" && folder.Matter is { } matter ? matter.Display : p.Name));
}
