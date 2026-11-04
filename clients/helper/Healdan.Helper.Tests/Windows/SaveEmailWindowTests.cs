// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json.Nodes;
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

    /// <summary>
    /// The routes the window reads at folder 11, which Recent Folders lists too; anything else is 404. Folder 11 takes
    /// emails unless <paramref name="canFile"/> is false.
    /// </summary>
    private sealed class FakeServer(bool canFile = true) : HttpMessageHandler
    {
        private string Folder => $$"""
            {"type":"folder","id":11,"name":"Correspondence","kind":"folder","matter":{"id":3,"display":"998.1 – Healdan Demo","client":null},
             "store":{"id":1,"name":"Healdan"},
             "path":[{"id":1,"name":"Healdan","kind":"store"},{"id":10,"name":"998.1 – Healdan Demo","kind":"matter"},{"id":11,"name":"Correspondence","kind":"folder"}],
             "canFile":{{(canFile ? "true" : "false")}}}
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
                "/v1/me/favorites" or "/v1/folders/11/children" => """{"items":[],"offset":0,"limit":50,"total":0}""",
                "/v1/folders/recent" => $$"""{"items":[{{Folder}}],"offset":0,"limit":50,"total":1}""",
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

    /// <summary>The filing the window's Save goes to, as <c>MailFiling</c> would answer it.</summary>
    private sealed class Filing
    {
        /// <summary>Each Save the window sent, in order.</summary>
        public List<string> Saves { get; } = [];

        /// <summary>What each Save comes to, in turn; done once they run out.</summary>
        public Queue<SubmitOutcome> Outcomes { get; } = new();

        /// <summary>While set, a Save waits for it, as a slow filing does.</summary>
        public TaskCompletionSource? Gate { get; set; }

        public async Task<SubmitOutcome> SubmitAsync(JsonObject save)
        {
            Saves.Add(save.ToJsonString());
            if (Gate is { } gate)
                await gate.Task;
            return Outcomes.TryDequeue(out var outcome) ? outcome : SubmitOutcome.Done;
        }
    }

    private static SaveEmailRequest Request(params EmailPart[] attachments) =>
        new("Lease renewal", "Pat Example <pat@example.com>", Sent, attachments, ["Lease renewal"], 11);

    private static EmailPart Part(int index, string name, long size = 2048) => new(index, name, "application/pdf", size, false, false);

    private static FieldError Error(string field, string detail) => new(field, "x", detail);

    /// <summary>
    /// Makes the window at folder 11 over <paramref name="filing"/> and shows it on the UI thread, waits for the folder's
    /// form, runs <paramref name="test"/> there, and closes it.
    /// </summary>
    private static Task Run(SaveEmailRequest request, Func<SaveWindow, Task> test, Filing? filing = null, FakeServer? server = null,
        bool waitForForm = true) => Headless.RunAsync(async () =>
    {
        filing ??= new Filing();
        var window = new SaveWindow(request, new HealdanApi(new Uri("https://healdan.test"), server ?? new FakeServer()), filing.SubmitAsync);
        window.Show();
        try
        {
            if (waitForForm)
                await Until(() => Find<ContentControl>(window, "FormHost").IsVisible);
            await test(window);
        }
        finally
        {
            filing.Gate?.TrySetResult();
            window.Close();
        }
        return 0;
    });

    private static T Find<T>(Window window, string name) where T : Control => window.FindControl<T>(name)!;

    private static void Click(Button button) => button.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    private static bool Shown(Control control) => control.IsVisible && control.GetVisualAncestors().OfType<Control>().All(c => c.IsVisible);

    private static List<CheckBox> AttachmentBoxes(Window window) =>
        [.. Find<StackPanel>(window, "AttachmentList").GetLogicalDescendants().OfType<CheckBox>()];

    /// <summary>The problem shown under the attachment at <paramref name="position"/> in the list, or null while there's none.</summary>
    private static string? AttachmentProblem(Window window, int position) =>
        AttachmentBoxes(window)[position].Parent is StackPanel row && row.Children.OfType<TextBlock>().Single() is { IsVisible: true } problem
            ? problem.Text : null;

    /// <summary>The problems shown under the form's fields.</summary>
    private static List<string?> FieldProblems(Window window) =>
        [.. Find<ContentControl>(window, "FormHost").GetLogicalDescendants().OfType<TextBlock>()
            .Where(t => t.Classes.Contains("problem") && t.IsVisible).Select(t => t.Text)];

    /// <summary>The line by Save: its text while it shows, else null.</summary>
    private static string? Line(Window window) => Find<TextBlock>(window, "SubmitText") is { IsVisible: true } line ? line.Text : null;

    /// <summary>Comments filled, so Save can go on, as the form's Comments is required.</summary>
    private static async Task FillComments(Window window, string text = "Renewal")
    {
        Comments(window).Text = text;
        await Until(() => Find<Button>(window, "SaveButton").IsEnabled);
    }

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

    private static readonly DateTimeOffset Received = DateTimeOffset.Parse("2026-11-02T14:05:09Z");

    [MacFact]
    public Task TheMessageIsShownReadOnlyWithNoNameToType() => Run(Request(Part(1, "Lease.pdf")) with
    {
        To = "Robert Example <robert@example.com>, Sam Example <sam@example.com>",
        Cc = "Ann Example <ann@example.com>",
        Received = Received,
    }, window =>
    {
        Assert.Equal("File to Healdan", window.Title);
        Assert.Equal("Lease renewal", Find<SelectableTextBlock>(window, "EmailSubject").Text);
        Assert.Equal("Pat Example <pat@example.com>", Find<SelectableTextBlock>(window, "EmailFrom").Text);
        Assert.Equal("Robert Example <robert@example.com>, Sam Example <sam@example.com>", Find<SelectableTextBlock>(window, "EmailTo").Text);
        Assert.Equal("Ann Example <ann@example.com>", Find<SelectableTextBlock>(window, "EmailCc").Text);
        Assert.Equal(When.DateAndTime(Sent), Find<SelectableTextBlock>(window, "EmailSent").Text);
        Assert.Equal(When.DateAndTime(Received), Find<SelectableTextBlock>(window, "EmailReceived").Text);
        foreach (var name in new[] { "EmailFrom", "EmailTo", "EmailCc", "EmailSent", "EmailReceived" })
            Assert.True(Shown(Find<SelectableTextBlock>(window, name)), name);

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
        // A line the message lacks isn't shown: this one has no recipients and no Received date.
        foreach (var name in new[] { "ToRow", "CcRow", "ReceivedRow" })
            Assert.False(Shown(Find<StackPanel>(window, name)), name);
        return Task.CompletedTask;
    });

    [MacFact]
    public Task AFolderThatTakesNoEmailsSaysSo() => Run(Request(), window =>
    {
        Assert.Equal("Emails can't be filed in Correspondence. Choose a folder inside a matter that you can add to.",
            Find<TextBlock>(window, "FormNotice").Text);
        Assert.False(Find<Button>(window, "SaveButton").IsEnabled);
        return Task.CompletedTask;
    }, server: new FakeServer(canFile: false), waitForForm: false);

    [MacFact]
    public Task TheRefusalLineClearsOnceAFolderIsChosen() =>
        // As when File Here's folder is gone: the window starts with no folder to file in, and the refusal on the line.
        Run(Request() with { FolderId = null, Refusal = new SubmitOutcome.Refused("No such folder.", []) }, async window =>
        {
            var list = Find<ListBox>(window, "EntryList");
            await Until(() => list.ItemCount > 0); // Recent Folders, which lists folder 11
            Assert.Equal("No such folder.", Line(window));

            list.SelectedIndex = 0;
            await Until(() => Find<ContentControl>(window, "FormHost").IsVisible);
            Assert.Null(Line(window));
        }, waitForForm: false);

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
    public Task ARefusalShowsByTheSaveButtonAndTheWindowStaysOpenUntilASaveFiles()
    {
        var filing = new Filing();
        filing.Outcomes.Enqueue(new SubmitOutcome.Refused("You may not file documents into folder 11.", []));
        filing.Outcomes.Enqueue(new SubmitOutcome.Refused(SubmitOutcome.Refused.CantReach, []));
        return Run(Request(), async window =>
        {
            var save = Find<Button>(window, "SaveButton");
            await FillComments(window);

            Click(save);
            await Until(() => Line(window) == "You may not file documents into folder 11.");
            Assert.True(window.IsVisible);
            Assert.Contains("problem", Find<TextBlock>(window, "SubmitText").Classes);
            Assert.True(save.IsEnabled); // to try again, or choose another folder first
            Assert.Equal("Save", save.Content);

            Click(save);
            await Until(() => Line(window) == "Can't reach Healdan. Try again.");
            Assert.True(window.IsVisible);

            Click(save);
            await Until(() => !window.IsVisible);
            Assert.Equal(3, filing.Saves.Count);
            Assert.Equal("""{"action":"save","folderId":11,"properties":{"comments":"Renewal"},"attachments":[]}""", window.Answer.ToJsonString());
        }, filing);
    }

    [MacFact]
    public Task AnAttachmentsRefusalShowsUnderItsTickAndUntickingItFilesTheRest()
    {
        var filing = new Filing();
        const string refused = "Attachment 1 (setup.exe): Files ending in .exe can't be uploaded.";
        filing.Outcomes.Enqueue(new SubmitOutcome.Refused(refused, [Error("attachments[1]", refused)]));
        return Run(Request(Part(1, "setup.exe"), Part(3, "Lease.pdf")), async window =>
        {
            await FillComments(window);
            Find<CheckBox>(window, "AttachmentsBox").IsChecked = true;

            Click(Find<Button>(window, "SaveButton"));
            await Until(() => AttachmentProblem(window, 0) is not null);
            Assert.Equal(refused, AttachmentProblem(window, 0)); // placed by the field's index, attachments[1]
            Assert.Null(AttachmentProblem(window, 1));
            Assert.Null(Line(window)); // nothing more to say than what's under the tick

            AttachmentBoxes(window)[0].IsChecked = false;
            Assert.Null(AttachmentProblem(window, 0));
            Click(Find<Button>(window, "SaveButton"));
            await Until(() => !window.IsVisible);
            Assert.Equal("""{"action":"save","folderId":11,"properties":{"comments":"Renewal"},"attachments":[3]}""", window.Answer.ToJsonString());
        }, filing);
    }

    [MacFact]
    public Task APropertysRefusalShowsUnderItsFieldAndHoldsSaveUntilItChanges()
    {
        var filing = new Filing();
        filing.Outcomes.Enqueue(new SubmitOutcome.Refused("Comments can't be just a dash.", [Error("comments", "Comments can't be just a dash.")]));
        return Run(Request(), async window =>
        {
            var save = Find<Button>(window, "SaveButton");
            await FillComments(window, "-");

            Click(save);
            await Until(() => FieldProblems(window).Contains("Comments can't be just a dash."));
            Assert.False(save.IsEnabled);
            Assert.Null(Line(window));

            await FillComments(window, "Renewal");
            Click(save);
            await Until(() => !window.IsVisible);
            Assert.Equal(2, filing.Saves.Count);
        }, filing);
    }

    [MacFact]
    public Task WhileItFilesSaveAndCancelAreOffAndTheWindowStaysUp()
    {
        var filing = new Filing { Gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        return Run(Request(), async window =>
        {
            var (save, cancel) = (Find<Button>(window, "SaveButton"), Find<Button>(window, "CancelButton"));
            await FillComments(window);

            Click(save);
            await Until(() => Equals(save.Content, "Filing…"));
            Assert.False(save.IsEnabled);
            Assert.False(cancel.IsEnabled);
            Assert.Equal("Filing the email in Healdan…", Line(window));
            Assert.Contains("muted", Find<TextBlock>(window, "SubmitText").Classes);
            // Nor can the folder change, so a refusal that comes is about the folder still shown.
            Assert.All(FolderAndForm(window), c => Assert.False(c.IsEffectivelyEnabled, c.Name ?? c.GetType().Name));
            window.Close();
            Assert.True(window.IsVisible); // the filing's outcome is the window's, so it waits for it

            filing.Gate!.TrySetResult();
            await Until(() => !window.IsVisible);
            Assert.Equal("save", (string?)window.Answer["action"]);
        }, filing);
    }

    /// <summary>What a Save waiting must keep still: the folder browser, the places, the properties and the attachments.</summary>
    private static List<Control> FolderAndForm(Window window) =>
    [
        Find<ListBox>(window, "EntryList"), Find<Border>(window, "PlacesHost"), Find<Button>(window, "BackButton"),
        Find<ComboBox>(window, "ScopePicker"), Find<TextBox>(window, "SearchBox"), Find<Button>(window, "NewFolderButton"),
        Find<ContentControl>(window, "FormHost"), Comments(window), Find<CheckBox>(window, "AttachmentsBox"),
    ];

    [MacFact]
    public Task AfterARefusalTheFolderAndFormCanChangeAgain()
    {
        var filing = new Filing { Gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        filing.Outcomes.Enqueue(new SubmitOutcome.Refused("You may not file documents into folder 11.", []));
        return Run(Request(Part(1, "Lease.pdf")), async window =>
        {
            await FillComments(window);
            Click(Find<Button>(window, "SaveButton"));
            await Until(() => !Find<ListBox>(window, "EntryList").IsEffectivelyEnabled);

            filing.Gate!.TrySetResult();
            await Until(() => Line(window) == "You may not file documents into folder 11.");
            Assert.All(FolderAndForm(window).Where(c => c.Name != "BackButton"), // Back has no history to go back to
                c => Assert.True(c.IsEffectivelyEnabled, c.Name ?? c.GetType().Name));
            Assert.True(Find<Button>(window, "CancelButton").IsEnabled);
        }, filing);
    }

    [MacFact]
    public Task AFileHeresRefusalShowsAsTheWindowOpens() =>
        Run(Request() with { Refusal = new SubmitOutcome.Refused("Only the Correspondence folder takes emails in this matter.", []) }, window =>
        {
            Assert.Equal("Only the Correspondence folder takes emails in this matter.", Line(window));
            return Task.CompletedTask;
        });

    [MacFact]
    public Task AFileHeresRefusalOfAPropertyShowsUnderItOnceTheFormIsThere() =>
        Run(Request() with
        {
            Refusal = new SubmitOutcome.Refused("Comments is required.", [Error("comments", "Comments is required.")]),
        }, async window =>
        {
            await Until(() => FieldProblems(window).Contains("Comments is required."));
            Assert.Null(Line(window));
        });

    [MacFact]
    public Task CancelAnswersCancel() => Run(Request(), async window =>
    {
        Click(Find<Button>(window, "CancelButton"));
        await Until(() => !window.IsVisible);

        Assert.Equal("""{"action":"cancel"}""", window.Answer.ToJsonString());
    });
}
