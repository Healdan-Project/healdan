// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Copies;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>Waits for something the cycle does in the background, without sleeping longer than it takes.</summary>
public static class Wait
{
    public static async Task Until(Func<bool> condition, string what, TimeSpan? timeout = null)
    {
        var deadline = DateTime.UtcNow + (timeout ?? TimeSpan.FromSeconds(15));
        while (!condition())
        {
            if (DateTime.UtcNow > deadline)
                throw new TimeoutException("Timed out waiting for: " + what);
            await Task.Delay(10);
        }
    }

    /// <summary>
    /// Whether the copy at <paramref name="path"/> is gone with everything <see cref="CopyStore.Delete"/> takes with it.
    /// The store deletes the file, then its record, then its apps' lock and owner files (<see cref="CopyNames.AppFiles"/>),
    /// on the cycle's own thread, so the file alone being gone says nothing yet about the rest: wait for this instead.
    /// </summary>
    public static bool Gone(string path)
    {
        var folder = Path.GetDirectoryName(path)!;
        var name = Path.GetFileName(path);
        return !File.Exists(path)
            && !File.Exists(Path.Combine(folder, CopyNames.RecordName(name)))
            && CopyNames.AppFiles(name).All(file => !File.Exists(Path.Combine(folder, file)));
    }
}
