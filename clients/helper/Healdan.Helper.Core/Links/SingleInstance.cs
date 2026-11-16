// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Logging;

namespace Healdan.Helper.Core.Links;

/// <summary>
/// The lock that says a helper is running for this user. The channel (and <c>channel.json</c>) starts only after sign-in,
/// so it can't tell a second start that a first is still starting; this lock is held from the first moment, for the
/// helper's whole life. It is an exclusive file lock (<see cref="FileShare.None"/>, which .NET takes as an advisory lock
/// on Unix), so the system frees it when the process ends, however it ends, and a stale file never blocks a start.
/// </summary>
public sealed class SingleInstance : IDisposable
{
    /// <summary>
    /// What an open that another process's lock refuses fails with: Windows' sharing violation, or, on Unix, .NET's
    /// <c>flock</c> refusal, whose HResult is the errno EWOULDBLOCK (11 on Linux, 35 on macOS). Any other failure
    /// isn't another helper holding the lock.
    /// </summary>
    private static readonly int SharingViolation =
        OperatingSystem.IsWindows() ? unchecked((int)0x80070020) : OperatingSystem.IsMacOS() ? 35 : 11;

    private readonly FileStream? _file;

    private SingleInstance(FileStream? file) => _file = file;

    /// <summary>
    /// The lock, or null when another process holds it, so a helper is already running. When the lock file can't be
    /// opened at all (the folder isn't writable, or the file is another user's), this logs it to <paramref name="log"/>
    /// and returns an instance that holds nothing, so the helper starts as it did before the lock; a second helper
    /// then can't be ruled out.
    /// </summary>
    public static SingleInstance? TryAcquire(HelperPaths paths, HelperLog? log = null)
    {
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
            // The helper may not have made its folder yet on a first start.
            Directory.CreateDirectory(paths.Support);
            return new SingleInstance(new FileStream(Path.Combine(paths.Support, "helper.lock"), options));
        }
        catch (IOException e) when (e.HResult == SharingViolation)
        {
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log?.Warn($"Couldn't take the one-helper lock ({e.Message}), so a second Healdan Helper can't be ruled out");
            return new SingleInstance(null);
        }
    }

    /// <summary>Releases the lock; the file stays.</summary>
    public void Dispose() => _file?.Dispose();
}
