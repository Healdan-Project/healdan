// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using Healdan.Helper.Core.Platform;

namespace Healdan.Helper.Core.Tests.Platform;

/// <summary>Which of Linux's processes are the user's own and alive, read from a fake <c>/proc</c> folder.</summary>
public sealed class OwnProcessesTests : IDisposable
{
    private const string Own = "1000";
    private readonly string _proc = Path.Combine(Path.GetTempPath(), "healdan-proc-" + Guid.NewGuid().ToString("N"));

    public OwnProcessesTests() => Directory.CreateDirectory(_proc);

    public void Dispose() => TempFolder.Delete(_proc);

    /// <summary>A process folder as the kernel lays it out: <c>comm</c> and a <c>status</c> with its name, state and uids.</summary>
    private void Add(string pid, string comm, string uid, string state = "S (sleeping)")
    {
        var folder = Directory.CreateDirectory(Path.Combine(_proc, pid)).FullName;
        File.WriteAllText(Path.Combine(folder, "comm"), comm + "\n");
        File.WriteAllText(
            Path.Combine(folder, "status"),
            $"Name:\t{comm}\nUmask:\t0022\nState:\t{state}\nTgid:\t{pid}\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\nGid:\t{uid}\t{uid}\t{uid}\t{uid}\n");
    }

    [Fact]
    public void AnotherUsersProcessDoesntCount()
    {
        Add("9208", "soffice.bin", "33017");

        Assert.False(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Fact]
    public void TheUsersOwnProcessCounts()
    {
        Add("9208", "soffice.bin", "33017");
        Add("4321", "soffice.bin", Own);

        Assert.True(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Theory]
    [InlineData("Z (zombie)")]
    [InlineData("X (dead)")]
    public void AZombieDoesntCount(string state)
    {
        Add("9203", "soffice.bin", Own, state);

        Assert.False(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Fact]
    public void AProcessThatWentAwayDoesntCount()
    {
        Directory.CreateDirectory(Path.Combine(_proc, "4321"));
        File.WriteAllText(Path.Combine(_proc, "4321", "comm"), "soffice.bin\n");

        Assert.False(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Fact]
    public void OtherNamesDontCount()
    {
        Add("4321", "soffice", Own);

        Assert.False(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Fact]
    public void NonNumericFoldersAreSkipped()
    {
        Add("self", "soffice.bin", Own);
        Add("sys", "soffice.bin", Own);
        File.WriteAllText(Path.Combine(_proc, "version"), "Linux version 7.0\n");

        Assert.False(OwnProcesses.AnyNamed("soffice.bin", _proc, Own));
    }

    [Fact]
    public void TheOwnUidIsReadFromSelfWhenNotGiven()
    {
        Add("self", "healdan-helper", Own);
        Add("4321", "soffice.bin", Own);
        Add("4322", "other", "33017");

        Assert.True(OwnProcesses.AnyNamed("soffice.bin", _proc));
        Assert.False(OwnProcesses.AnyNamed("other", _proc));
    }

    [LinuxFact]
    public void FindsTheTestsOwnProcessInTheRealProc()
    {
        var comm = File.ReadAllText("/proc/self/comm").Trim();

        Assert.True(OwnProcesses.AnyNamed(comm));
        Assert.False(OwnProcesses.AnyNamed("no-such-process-" + Guid.NewGuid().ToString("N")[..4]));
    }
}

/// <summary>A test that reads the real <c>/proc</c>: it skips itself off Linux.</summary>
public sealed class LinuxFactAttribute : FactAttribute
{
    public LinuxFactAttribute()
    {
        if (!OperatingSystem.IsLinux())
            Skip = "Linux's /proc is needed.";
    }
}
