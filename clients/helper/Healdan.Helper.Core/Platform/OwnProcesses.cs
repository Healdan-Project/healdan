// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Platform;

/// <summary>
/// Linux's process table, <c>/proc</c>, read for the user's own live processes of a given name. <c>GetProcessesByName</c>
/// sees every user's processes, including a container's, so it can't tell whether the user's own app is running.
/// </summary>
public static class OwnProcesses
{
    /// <summary>
    /// Whether a process named <paramref name="name"/> (its <c>comm</c>, the kernel's name for it, as
    /// <c>GetProcessesByName</c> matches) is running as the user and isn't a zombie or dead. A process whose
    /// <c>status</c> can't be read (it exited meanwhile, or it isn't readable) doesn't count and is skipped. Throws
    /// <see cref="IOException"/> when the user's own uid can't be read or the process list can't be listed at all, so a
    /// caller can't take "can't tell" for "not running"; each caller counts an app as running then.
    /// </summary>
    /// <param name="name">The process name.</param>
    /// <param name="procRoot">The proc folder; <c>/proc</c> but in tests.</param>
    /// <param name="ownUid">The user's real uid; read from <c>self/status</c> in <paramref name="procRoot"/> when null.</param>
    public static bool AnyNamed(string name, string procRoot = "/proc", string? ownUid = null)
    {
        ownUid ??= ReadStatus(Path.Combine(procRoot, "self", "status"))?.Uid
            ?? throw new IOException("The user's own uid can't be read from " + Path.Combine(procRoot, "self", "status"));

        string[] folders;
        try
        {
            folders = Directory.GetDirectories(procRoot);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new IOException($"The process list in {procRoot} can't be read: {e.Message}", e);
        }

        foreach (var folder in folders)
        {
            if (!long.TryParse(Path.GetFileName(folder), out _))
                continue;
            try
            {
                if (File.ReadAllText(Path.Combine(folder, "comm")).TrimEnd('\n') != name)
                    continue;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                continue;
            }

            var status = ReadStatus(Path.Combine(folder, "status"));
            if (status is { Uid: var uid, State: var state } && uid == ownUid && state is not ('Z' or 'X'))
                return true;
        }
        return false;
    }

    /// <summary>
    /// Whether process <paramref name="pid"/> is the user's own: its <c>status</c>'s real uid is the user's. A process that
    /// isn't there or can't be read isn't. Throws <see cref="IOException"/> when the user's own uid can't be read.
    /// </summary>
    /// <param name="procRoot">The proc folder; <c>/proc</c> but in tests.</param>
    /// <param name="ownUid">The user's real uid; read from <c>self/status</c> in <paramref name="procRoot"/> when null.</param>
    public static bool IsOwn(int pid, string procRoot = "/proc", string? ownUid = null)
    {
        ownUid ??= ReadStatus(Path.Combine(procRoot, "self", "status"))?.Uid
            ?? throw new IOException("The user's own uid can't be read from " + Path.Combine(procRoot, "self", "status"));
        return ReadStatus(Path.Combine(procRoot, pid.ToString(), "status"))?.Uid == ownUid;
    }

    /// <summary>A <c>status</c> file's real uid (the first of its <c>Uid:</c> fields) and state letter, or null when it can't be read.</summary>
    private static (string Uid, char State)? ReadStatus(string path)
    {
        string[] lines;
        try
        {
            lines = File.ReadAllLines(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }

        string? uid = null;
        char? state = null;
        foreach (var line in lines)
        {
            if (line.StartsWith("Uid:", StringComparison.Ordinal))
                uid = line["Uid:".Length..].Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
            else if (line.StartsWith("State:", StringComparison.Ordinal))
                state = line["State:".Length..].Trim().FirstOrDefault();
        }
        return uid is not null && state is { } s && s != '\0' ? (uid, s) : null;
    }
}
