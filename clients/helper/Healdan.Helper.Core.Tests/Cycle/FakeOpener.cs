using System.Collections.Concurrent;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>The system's default app, as the cycle sees it: it opens what it's given and keeps no lock file.</summary>
public sealed class FakeOpener : IOfficeApp
{
    public string Name => DefaultApp.AppName;

    public ConcurrentQueue<string> Opened { get; } = new();

    public void Open(string path) => Opened.Enqueue(path);

    public bool IsRunning() => false;

    public bool LockExists(string path) => false;
}
