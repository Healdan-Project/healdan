using System.Text.Json;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Copies;
using Healdan.Helper.Core.Cycle;

namespace Healdan.Helper.Core.Tests.Cycle;

/// <summary>
/// The open-save-close cycle against a real stack with the smoke fixture (matter 1.1 and its Documents folder), signed
/// in as the smoke user: <c>HEALDAN_STACK_USER</c> and <c>HEALDAN_STACK_PASSWORD</c>, or <c>admin</c>/<c>admin</c>.
/// </summary>
[Trait("Category", "Stack")]
public sealed class StackCycleTests : IDisposable
{
    private static readonly CycleTimings Timings = new(
        Settle: TimeSpan.FromMilliseconds(400),
        SizePoll: TimeSpan.FromMilliseconds(50),
        CrashPoll: TimeSpan.FromMilliseconds(500),
        LockPoll: TimeSpan.FromMilliseconds(100),
        RetryDelays: [TimeSpan.FromMilliseconds(500)]);

    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(60);

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-stack-").FullName;

    public void Dispose() => Directory.Delete(_root, true);

    private static string? Env(string name) =>
        Environment.GetEnvironmentVariable(name) is { Length: > 0 } value ? value : null;

    [StackFact]
    public async Task OpenSaveCloseAgainstStack()
    {
        var api = new HealdanApi(new Uri(Env("HEALDAN_STACK_URL")!));
        var deviceId = Guid.NewGuid().ToString();
        await api.SignInAsync(Env("HEALDAN_STACK_USER") ?? "admin", Env("HEALDAN_STACK_PASSWORD") ?? "admin",
            "Healdan Helper stack test", deviceId);
        try
        {
            var filed = await FileADocumentAsync(api);
            Assert.Equal("1.0", filed.Version);

            var events = new RecordingEvents();
            var app = new FakeApp();
            using (var cycle = new CopyCycle(api, new CopyStore(HelperPaths.For(_root)), app, deviceId, events, Timings))
            {
                await cycle.OpenAsync(filed.DocNumber, false);
                Assert.Empty(events.Errors);
                var path = Assert.Single(app.Opened);
                Assert.Equal("filed by the helper's stack test", await File.ReadAllTextAsync(path));

                FakeApp.Write(path, "the first save");
                await Wait.Until(() => events.LastTitle(path)?.EndsWith(" v1.1") == true, "v1.1", Patience);
                FakeApp.Write(path, "the second save");
                await Wait.Until(() => events.LastTitle(path)?.EndsWith(" v1.2") == true, "v1.2", Patience);
                await Wait.Until(() => app.LockSeen(path), "the lock file seen", Patience);
                FakeApp.Close(path);
                await Wait.Until(() => !File.Exists(path), "the copy deleted after the check-in", Patience);

                Assert.Empty(events.Errors);
                Assert.Empty(events.Ended);
                Assert.Equal(0, events.SignIns);
            }

            var doc = await api.DocumentAsync(filed.DocNumber);
            Assert.Null(doc.Hold);
            Assert.Equal("1.2", doc.FileVersion);
            var versions = await api.GetJsonAsync($"v1/documents/{filed.DocNumber}/versions");
            var saves = versions.GetProperty("items").EnumerateArray()
                .Where(v => v.GetProperty("version").GetString() == v.GetProperty("fileVersion").GetString())
                .Select(v => v.GetProperty("version").GetString())
                .ToList();
            Assert.Contains("1.1", saves);
            Assert.Contains("1.2", saves);
            var latest = Path.Combine(_root, "latest.txt");
            await api.DownloadAsync(filed.DocNumber, latest);
            Assert.Equal("the second save", await File.ReadAllTextAsync(latest));
        }
        finally
        {
            await api.SignOutAsync();
        }
    }

    /// <summary>Files a small document in the smoke matter's Documents folder, as docker/tests/test-api.sh does.</summary>
    private async Task<DocumentView> FileADocumentAsync(HealdanApi api)
    {
        var matters = await api.GetJsonAsync("v1/search?scope=matters&q=1.1");
        var matter = matters.GetProperty("items")[0].GetProperty("folderId").GetInt64();
        var children = await api.GetJsonAsync($"v1/folders/{matter}/children?type=folder");
        var documents = children.GetProperty("items").EnumerateArray()
            .First(i => i.GetProperty("name").GetString() == "Documents")
            .GetProperty("id").GetInt64();
        var source = Path.Combine(_root, "source.txt");
        await File.WriteAllTextAsync(source, "filed by the helper's stack test");
        return await api.FileAsync(source, new NewDocument(documents, $"Helper cycle {Guid.NewGuid():N}.txt", new JsonObject(), false));
    }
}
