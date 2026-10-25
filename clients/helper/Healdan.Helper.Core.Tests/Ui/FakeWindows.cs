using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Cycle;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Ui;

/// <summary>The helper's windows as the tests drive them: each answers what the test set, and every call is recorded.</summary>
public sealed class FakeWindows : IWindows
{
    public volatile bool IsWaiting;

    public bool Waiting => IsWaiting;

    /// <summary>What the Sign In window does with the prompt: by default the user closes it.</summary>
    public Func<SignInPrompt, Task<bool>> OnSignIn { get; set; } = _ => Task.FromResult(false);

    public ConcurrentQueue<SignInPrompt> SignIns { get; } = new();

    public ConcurrentQueue<(string Route, JsonObject Request)> Asked { get; } = new();

    public ConcurrentQueue<long> HeldElsewheres { get; } = new();

    public ConcurrentQueue<CopyState> Crashes { get; } = new();

    public JsonObject Answer { get; set; } = new() { ["action"] = "cancel" };

    public Task<bool> SignInAsync(SignInPrompt prompt)
    {
        SignIns.Enqueue(prompt);
        return OnSignIn(prompt);
    }

    public Task<JsonObject> OpenDialogAsync(JsonObject request) => Ask("open-dialog", request);

    public Task<JsonObject> SaveNewAsync(JsonObject request) => Ask("save-new", request);

    public Task<JsonObject> PropertiesAsync(JsonObject request) => Ask("properties", request);

    public void HeldElsewhere(long docNumber, string deviceName, string? app, DateTimeOffset? lastSaved) =>
        HeldElsewheres.Enqueue(docNumber);

    public void Crashed(CopyState copy) => Crashes.Enqueue(copy);

    /// <summary>How many times a dropped link brought the waiting window up.</summary>
    public int Brought => Volatile.Read(ref _brought);

    private int _brought;

    public void BringWaiting() => Interlocked.Increment(ref _brought);

    private Task<JsonObject> Ask(string route, JsonObject request)
    {
        Asked.Enqueue((route, request));
        return Task.FromResult((JsonObject)Answer.DeepClone());
    }
}
