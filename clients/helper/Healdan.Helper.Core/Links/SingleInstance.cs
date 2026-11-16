// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

namespace Healdan.Helper.Core.Links;

/// <summary>
/// The lock that says a helper is running for this user. The channel (and <c>channel.json</c>) starts only after sign-in,
/// so it can't tell a second start that a first is still starting; this lock is held from the first moment, for the
/// helper's whole life. It is an exclusive file lock (<see cref="FileShare.None"/>, which .NET takes as an advisory lock
/// on Unix), so the system frees it when the process ends, however it ends, and a stale file never blocks a start.
/// </summary>
public sealed class SingleInstance : IDisposable
{
    private readonly FileStream _file;

    private SingleInstance(FileStream file) => _file = file;

    /// <summary>The lock, or null when another process holds it, so a helper is already running.</summary>
    public static SingleInstance? TryAcquire(HelperPaths paths)
    {
        // The helper may not have made its folder yet on a first start.
        Directory.CreateDirectory(paths.Support);
        var options = new FileStreamOptions
        {
            Mode = FileMode.OpenOrCreate,
            Access = FileAccess.ReadWrite,
            Share = FileShare.None,
        };
        if (!OperatingSystem.IsWindows())
            options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
        try
        {
            return new SingleInstance(new FileStream(Path.Combine(paths.Support, "helper.lock"), options));
        }
        catch (IOException)
        {
            return null;
        }
    }

    /// <summary>Releases the lock; the file stays.</summary>
    public void Dispose() => _file.Dispose();
}
