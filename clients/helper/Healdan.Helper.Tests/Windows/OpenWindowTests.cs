// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// Open from Healdan's read-only choice (spec §5.8, #146), as the user's clicks drive it, against a server the test
/// plays: Recent Documents lists its documents, and each document's details can be held back to stand for a slow
/// answer. For a Mac, as the app's other window tests are.
/// </summary>
public sealed class OpenWindowTests
{
    /// <summary>The few routes the window reads, for the documents <see cref="Add"/> gave it; anything else is 404.</summary>
    private sealed class FakeServer : HttpMessageHandler
    {
        private readonly List<JsonObject> _docs = [];
        private readonly Dictionary<long, TaskCompletionSource> _held = [];

        public void Add(long n, bool canEdit = true, bool canOpen = true) => _docs.Add(new JsonObject
        {
            ["type"] = "document", ["docNumber"] = n, ["name"] = $"Lease {n}.docx", ["size"] = 100, ["version"] = "1.0",
            ["fileVersion"] = "1.0", ["reference"] = $"{n}.1.0",
            ["folder"] = new JsonObject { ["id"] = 11, ["name"] = "Documents", ["kind"] = "folder" }, ["matter"] = null,
            ["dateFiled"] = "2026-10-01T09:00:00Z", ["lastModified"] = "2026-10-01T09:00:00Z", ["hold"] = null,
            ["canEdit"] = canEdit, ["canOpen"] = canOpen
        });

        /// <summary>The document's own read waits until <see cref="ReleaseAll"/>, as a slow server's would.</summary>
        public void HoldBack(long n) => _held[n] = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        public void ReleaseAll()
        {
            foreach (var held in _held.Values)
                held.TrySetResult();
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            path = path[path.IndexOf("/v1/", StringComparison.Ordinal)..];
            var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
            JsonNode? body = path switch
            {
                "/v1/me" => new JsonObject
                {
                    ["id"] = 1, ["username"] = "jane", ["name"] = "Jane Smith", ["email"] = null, ["language"] = null, ["admin"] = false
                },
                "/v1/me/favorites" => Page([]),
                "/v1/documents/recent" => Page([.. _docs.Select(d => d.DeepClone())]),
                _ when parts is ["v1", "documents", var n, "versions"] && Doc(n) is not null => Page(
                [
                    new JsonObject
                    {
                        ["version"] = "1.0", ["fileVersion"] = "1.0", ["kind"] = "save", ["date"] = "2026-10-01T09:00:00Z",
                        ["by"] = new JsonObject { ["id"] = 1, ["name"] = "Jane Smith" }, ["comment"] = null, ["event"] = null
                    }
                ]),
                _ when parts is ["v1", "documents", var n, "properties"] && Doc(n) is not null => Page([]),
                _ when parts is ["v1", "documents", var n] && Doc(n) is { } doc => doc.DeepClone(),
                _ => null
            };
            if (parts is ["v1", "documents", var number] && long.TryParse(number, out var held) && _held.TryGetValue(held, out var gate))
                await gate.Task.WaitAsync(ct);
            if (body is null)
                return new HttpResponseMessage(HttpStatusCode.NotFound)
                {
                    Content = new StringContent("""{"type":"about:blank","title":"Not Found","status":404,"code":"not-found","detail":"Not here."}""",
                        Encoding.UTF8, "application/problem+json")
                };
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json") };
        }

        private JsonObject? Doc(string n) => _docs.FirstOrDefault(d => d["docNumber"]!.ToString() == n);

        private static JsonObject Page(JsonArray items) =>
            new() { ["items"] = items, ["offset"] = 0, ["limit"] = 50, ["total"] = items.Count };
    }

    /// <summary>Makes the window over <paramref name="server"/> and shows it on the UI thread, runs <paramref name="test"/> there, and closes it.</summary>
    private static Task Run(FakeServer server, Func<OpenWindow, Task> test) => Headless.RunAsync(async () =>
    {
        var window = new OpenWindow(new HealdanApi(new Uri("https://healdan.test"), server), fromApp: false, _ => false);
        window.Show();
        try
        {
            await Until(() => Find<ListBox>(window, "EntryList").ItemCount > 0);
            await test(window);
        }
        finally
        {
            server.ReleaseAll();
            window.Close();
        }
        return 0;
    });

    private static T Find<T>(Window window, string name) where T : Control => window.FindControl<T>(name)!;

    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    private static bool Shown(Control control) => control.IsVisible && control.GetVisualAncestors().OfType<Control>().All(c => c.IsVisible);

    /// <summary>The Properties tab shows once the selected document's details are here.</summary>
    private static bool DetailsShown(Window window) => Find<ScrollViewer>(window, "PropertiesTab").IsVisible;

    private static async Task Until(Func<bool> condition)
    {
        var until = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            Assert.True(DateTime.UtcNow < until, "It didn't happen within 15 seconds.");
            await Task.Delay(10);
        }
    }

    [MacFact]
    public Task TheReadOnlyBoxIsTheChosenDocumentsWhileItsDetailsLoad()
    {
        var server = new FakeServer();
        server.Add(1, canEdit: false); // the user may not edit it
        server.Add(2);
        server.HoldBack(2);
        return Run(server, async window =>
        {
            var list = Find<ListBox>(window, "EntryList");
            var box = Find<CheckBox>(window, "ReadOnlyBox");
            list.SelectedIndex = 0;
            await Until(() => DetailsShown(window));
            Assert.True(box is { IsChecked: true, IsEnabled: false });

            // #2's details don't come: a double-click's second click lands before them
            list.SelectedIndex = 1;
            Assert.True(box is { IsChecked: false, IsEnabled: true });
            Click(Find<Button>(window, "OpenButton"));

            Assert.Equal<(long, bool, string?)?>((2, false, null), window.Chosen);
        });
    }

    [MacFact]
    public Task ADocumentTheHelperCantOpenSaysSoAndOpensNothing()
    {
        var server = new FakeServer();
        server.Add(3, canEdit: false, canOpen: false); // no Download permission, or protected by a password
        return Run(server, async window =>
        {
            var list = Find<ListBox>(window, "EntryList");
            list.SelectedIndex = 0;
            await Until(() => DetailsShown(window));

            Assert.True(Shown(Find<StackPanel>(window, "NoOpenLine")));
            Assert.False(Shown(Find<StackPanel>(window, "NoEditLine"))); // it doesn't open read-only either
            Assert.False(Find<Button>(window, "OpenButton").IsEnabled);
            list.RaiseEvent(new KeyEventArgs { RoutedEvent = InputElement.KeyDownEvent, Key = Key.Enter });
            Assert.Null(window.Chosen);
            Assert.True(window.IsVisible);
        });
    }
}
