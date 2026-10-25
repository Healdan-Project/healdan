using System.Collections.Concurrent;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>Remembers the notices the helper raised.</summary>
public sealed class FakeNotifier : INotifier
{
    public ConcurrentQueue<(string Title, string Message)> Notices { get; } = new();

    public void Notify(string title, string message) => Notices.Enqueue((title, message));

    /// <summary>Every notice as one string, for <c>Assert.Contains</c>.</summary>
    public string All => string.Join("\n", Notices.Select(n => n.Title + ": " + n.Message));
}
