// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Tests;

/// <summary>A test's own temporary folder, which the test class makes when it starts and deletes when it's disposed.</summary>
public static class TempFolder
{
    /// <summary>How long a delete is tried again for: a disposed cycle's late writes are done within milliseconds.</summary>
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Deletes the folder and everything in it. A cycle or session disposed just before stops its background work (its
    /// save queues and watchers, a save back, an open) without waiting for it, so a record or a log line that work was
    /// already writing can land while the folder is being deleted, which then fails with "Directory not empty". The
    /// delete is tried again for up to two seconds, and work still writing into the folder then fails the test. A write
    /// that lands only once the delete has worked makes the folder again, and it's left behind.
    /// </summary>
    public static void Delete(string path)
    {
        var deadline = DateTime.UtcNow + Deadline;
        while (true)
        {
            try
            {
                if (Directory.Exists(path))
                    Directory.Delete(path, true);
                return;
            }
            catch (IOException) when (DateTime.UtcNow < deadline)
            {
                Thread.Sleep(50);
            }
        }
    }
}
