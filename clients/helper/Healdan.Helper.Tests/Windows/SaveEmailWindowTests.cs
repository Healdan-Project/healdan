// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Text;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.LogicalTree;
using Avalonia.VisualTree;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Browse;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Windows;

namespace Healdan.Helper.Tests.Windows;

/// <summary>
/// The Save window's email mode (M5 spec §7.3, plan correction 10), as the user's clicks drive it, against a server the
/// test plays: the window opens at folder 11, whose form has a required Comments that nothing fills. For a Mac, as the
/// app's other window tests are.
/// </summary>
public sealed class SaveEmailWindowTests
{
    private static readonly DateTimeOffset Sent = DateTimeOffset.Parse("2026-11-02T14:05:00Z");

    /// <summary>The routes the window reads at folder 11; anything else is 404.</summary>
    private sealed class FakeServer : HttpMessageHandler
    {
        private const string Folder = """
            {"type":"folder","id":11,"name":"Correspondence","kind":"folder","matter":{"id":3,"display":"998.1 – Healdan Demo","client":null},
             "store":{"id":1,"name":"Healdan"},
             "path":[{"id":1,"name":"Healdan","kind":"store"},{"id":10,"name":"998.1 – Healdan Demo","kind":"matter"},{"id":11,"name":"Correspondence","kind":"folder"}],
             "canFile":true}
            """;

        private const string Form = """
            {"items":[
              {"name":"matter","label":"Matter","type":"string","required":true,"editable":false,"value":"998.1 – Healdan Demo","source":"inherited","inheritedFrom":{"id":10,"name":"998.1 – Healdan Demo","kind":"matter"},"options":null},
              {"name":"author","label":"Author","type":"user","required":true,"editable":true,"value":{"id":1,"name":"Jane Smith"},"source":"default","inheritedFrom":null,"options":null},
              {"name":"comments","label":"Comments","type":"string","required":true,"editable":true,"value":null,"source":null,"inheritedFrom":null,"options":null}
            ],"offset":0,"limit":10,"total":3}
            """;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var path = request.RequestUri!.AbsolutePath;
            path = path[path.IndexOf("/v1/", StringComparison.Ordinal)..];
            string? body = path switch
            {
                "/v1/me" => """{"id":1,"username":"jane","name":"Jane Smith","email":null,"language":null,"admin":false}""",
                "/v1/me/favorites" or "/v1/folders/recent" or "/v1/folders/11/children" => """{"items":[],"offset":0,"limit":50,"total":0}""",
                "/v1/folders/11" => Folder,
                "/v1/folders/11/form" => Form,
                _ => null
            };
            return Task.FromResult(body is null
                ? new HttpResponseMessage(HttpStatusCode.NotFound)
                {
                    Content = new StringContent("""{"type":"about:blank","title":"Not Found","status":404,"code":"not-found","detail":"Not here."}""",
                        Encoding.UTF8, "application/problem+json")
                }
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body, Encoding.UTF8, "application/json") });
        }
    }

    private static SaveEmailRequest Request(params EmailPart[] attachments) =>
        new("Lease renewal", "Pat Example <pat@example.com>", Sent, attachments, ["Lease renewal"], 11);

    private static EmailPart Part(int index, string name, long size = 2048) => new(index, name, "application/pdf", size, false, false);

    /// <summary>
    /// Makes the window at folder 11 and shows it on the UI thread, waits for the folder's form, runs <paramref name="test"/>
    /// there, and closes it.
    /// </summary>
    private static Task Run(SaveEmailRequest request, Func<SaveWindow, Task> test) => Headless.RunAsync(async () =>
    {
        var window = new SaveWindow(request, new HealdanApi(new Uri("https://healdan.test"), new FakeServer()));
        window.Show();
        try
        {
            await Until(() => Find<ContentControl>(window, "FormHost").IsVisible);
            await test(window);
        }
        finally
        {
            window.Close();
        }
        return 0;
    });

    private static T Find<T>(Window window, string name) where T : Control => window.FindControl<T>(name)!;

    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    private static bool Shown(Control control) => control.IsVisible && control.GetVisualAncestors().OfType<Control>().All(c => c.IsVisible);

    private static List<CheckBox> AttachmentBoxes(Window window) =>
        [.. Find<StackPanel>(window, "AttachmentList").Children.OfType<CheckBox>()];

    /// <summary>Comments, the form's only text box of its own (Author's picker has one in its template, which isn't logical).</summary>
    private static TextBox Comments(Window window) =>
        Find<ContentControl>(window, "FormHost").GetLogicalDescendants().OfType<TextBox>().Single();

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
    public Task TheMessageIsShownReadOnlyWithNoNameToType() => Run(Request(Part(1, "Lease.pdf")), window =>
    {
        Assert.Equal("File to Healdan", window.Title);
        Assert.Equal("Lease renewal", Find<SelectableTextBlock>(window, "EmailSubject").Text);
        Assert.Equal("Pat Example <pat@example.com>", Find<SelectableTextBlock>(window, "EmailFrom").Text);
        Assert.Equal(When.DateAndTime(Sent), Find<SelectableTextBlock>(window, "EmailSent").Text);
        Assert.True(Shown(Find<SelectableTextBlock>(window, "EmailFrom")));
        Assert.True(Shown(Find<SelectableTextBlock>(window, "EmailSent")));

        // M1 names an email after its subject, so there's no name to type, nor a format or a local copy to choose.
        Assert.False(Shown(Find<TextBox>(window, "NameBox")));
        Assert.False(Shown(Find<ComboBox>(window, "FormatPicker")));
        Assert.False(Shown(Find<Button>(window, "LocalButton")));
        Assert.Equal("Email Properties", Find<TextBlock>(window, "PropertiesHeading").Text);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task TheAttachmentsBoxStartsUntickedAndTicksEveryAttachment() =>
        Run(Request(Part(1, "Lease.pdf"), Part(3, "Schedule.pdf", 3 * 1024 * 1024)), window =>
        {
            var all = Find<CheckBox>(window, "AttachmentsBox");
            var boxes = AttachmentBoxes(window);

            Assert.Equal("Also file attachments as separate documents", ((TextBlock)all.Content!).Text);
            Assert.Equal("Also file attachments as separate documents", AutomationProperties.GetName(all));
            Assert.False(all.IsChecked);
            Assert.Equal(["Lease.pdf (2.0 KB)", "Schedule.pdf (3.0 MB)"], boxes.Select(b => ((TextBlock)b.Content!).Text));
            Assert.Equal(["Lease.pdf (2.0 KB)", "Schedule.pdf (3.0 MB)"], boxes.Select(AutomationProperties.GetName));
            Assert.All(boxes, b => Assert.False(b.IsChecked));

            all.IsChecked = true;
            Assert.All(boxes, b => Assert.True(b.IsChecked));

            all.IsChecked = false;
            Assert.All(boxes, b => Assert.False(b.IsChecked));
            return Task.CompletedTask;
        });

    [MacFact]
    public Task AMessageWithoutAttachmentsHasNoAttachmentsBox() => Run(Request(), window =>
    {
        Assert.False(Shown(Find<CheckBox>(window, "AttachmentsBox")));
        return Task.CompletedTask;
    });

    [MacFact]
    public Task SaveStaysOffUntilTheRequiredPropertiesAreFilledAndAnswersTheTickedAttachments() =>
        Run(Request(Part(1, "Lease.pdf"), Part(2, "Notes.pdf"), Part(3, "Schedule.pdf")), async window =>
        {
            var save = Find<Button>(window, "SaveButton");
            Assert.False(save.IsEnabled); // Comments is required, and nothing fills it

            Comments(window).Text = "Renewal";
            await Until(() => save.IsEnabled); // the box tells the form after the change, as typing does

            Find<CheckBox>(window, "AttachmentsBox").IsChecked = true;
            AttachmentBoxes(window)[1].IsChecked = false; // each can be unticked
            Click(save);
            await Until(() => !window.IsVisible);

            Assert.Equal("""{"action":"save","folderId":11,"properties":{"comments":"Renewal"},"attachments":[1,3]}""", window.Answer.ToJsonString());
        });

    [MacFact]
    public Task AttachmentsUntickedAreNotFiled() => Run(Request(Part(1, "Lease.pdf")), async window =>
    {
        Comments(window).Text = "Renewal";
        await Until(() => Find<Button>(window, "SaveButton").IsEnabled);
        Click(Find<Button>(window, "SaveButton"));
        await Until(() => !window.IsVisible);

        Assert.Equal("""{"action":"save","folderId":11,"properties":{"comments":"Renewal"},"attachments":[]}""", window.Answer.ToJsonString());
    });

    [MacFact]
    public Task CancelAnswersCancel() => Run(Request(), async window =>
    {
        Click(Find<Button>(window, "CancelButton"));
        await Until(() => !window.IsVisible);

        Assert.Equal("""{"action":"cancel"}""", window.Answer.ToJsonString());
    });
}
