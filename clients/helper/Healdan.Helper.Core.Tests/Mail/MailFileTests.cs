// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Text;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Logging;
using Healdan.Helper.Core.Mail;
using Healdan.Helper.Core.Tests.Cycle;
using Healdan.Helper.Core.Tests.Ui;

namespace Healdan.Helper.Core.Tests.Mail;

/// <summary>
/// <c>mail/file</c> (M5 spec §7.2, §7.3): one message filed, straight into a suggested folder when its form needs nothing
/// typed (File Here), otherwise through the Save window's email mode (File…). The server is <see cref="FakeApi"/>, and the
/// window answers what the test sets (<see cref="FakeWindows"/>).
/// </summary>
public sealed class MailFileTests : IDisposable
{
    private static readonly DateTimeOffset Sent = DateTimeOffset.Parse("2026-11-02T14:05:00Z");
    private const string Matter = "1.1 – Smoke Matter";

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-mail-file-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeWindows _windows = new();
    private readonly FakeNotifier _notifier = new();

    public MailFileTests() => _server.AddFolder(11, "Correspondence");

    public void Dispose() => TempFolder.Delete(_root);

    private string Log => File.Exists(HelperPaths.For(_root).Log) ? File.ReadAllText(HelperPaths.For(_root).Log) : "";

    private MailFiling Mail() => new(new HealdanApi(new Uri("https://healdan.test"), _server) { Token = "hd_test" },
        _windows, _notifier, new HelperLog(HelperPaths.For(_root).Log));

    private static byte[] Eml(string messageId = "m1@example.com", string subject = "Lease renewal") => Encoding.UTF8.GetBytes(
        $"Message-ID: <{messageId}>\r\nSubject: {subject}\r\nFrom: Pat Example <pat@example.com>\r\nTo: robert@example.com\r\n\r\nHello.\r\n");

    private static EmailPart Part(int index, string name, bool inline = false, bool message = false) =>
        new(index, name, message ? "message/rfc822" : "application/pdf", 1536, inline, message);

    /// <summary>A document the fake server's own answer names, in folder 11 of the smoke matter.</summary>
    private static DocumentView Doc(long n, string name) => new(
        "document", n, name, 10, "1.0", "1.0", $"{n}.1.0", new FolderRef(11, "Correspondence", "folder"), new MatterRef(3, Matter),
        Sent, Sent, null, true);

    /// <summary>Comments is required in folder 11's form, and nothing fills it: a File Here there can't file without typing.</summary>
    private void CommentsRequired() =>
        _server.SetForm(11, Forms.Forms.Form(f => f["comments"]["required"] = true));

    [Fact]
    public async Task AFolderWhoseFormNeedsNothingTypedIsFiledAtOnce()
    {
        var outcome = await Mail().FileAsync(Eml(), 11);

        Assert.Equal(["outcome", "email", "attachments", "skipped"], outcome.Select(p => p.Key));
        Assert.Equal("filed", (string?)outcome["outcome"]);
        var email = outcome["email"]!.AsObject();
        Assert.Equal(["docNumber", "name", "matter", "folder"], email.Select(p => p.Key));
        Assert.Equal((100L, "Lease renewal.eml", Matter, "Correspondence"),
            ((long)email["docNumber"]!, (string)email["name"]!, (string)email["matter"]!, (string)email["folder"]!));
        Assert.Equal((0, 0), ((int)outcome["attachments"]!, (int)outcome["skipped"]!));

        // No window and no inspect: the folder's form, then the filing, with nothing typed and no attachments.
        Assert.Empty(_windows.Asked);
        Assert.Equal(["GET /folders/11/form", "POST /emails", "filed 100"], _server.Calls);
        var sent = Assert.Single(_server.EmailsReceived);
        Assert.Equal((11L, "{}", 0), (sent.FolderId, sent.Properties.ToJsonString(), sent.Attachments.Count));
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AFolderWhoseFormNeedsAPropertyTypedOpensTheSaveWindowAtIt()
    {
        CommentsRequired();
        _server.InspectEmail = _ => new EmailInspection("m1@example.com", "Lease renewal", "Pat Example <pat@example.com>", Sent, [], []);

        var outcome = await Mail().FileAsync(Eml(), 11);

        var (route, request) = Assert.Single(_windows.Asked);
        Assert.Equal("save-email", route);
        Assert.Equal(11L, SaveEmailRequest.FromJson(request).FolderId);
        Assert.Equal("cancelled", (string?)outcome["outcome"]); // the window's answer, Cancel by default
        Assert.DoesNotContain("POST /emails", _server.Calls);
    }

    [Fact]
    public async Task WithNoFolderTheWindowShowsTheMessageAndItsAnswerIsFiled()
    {
        _server.InspectEmail = _ => new EmailInspection("m1@example.com", "Lease renewal", "Pat Example <pat@example.com>", Sent,
            [Part(1, "Lease.pdf"), Part(2, "logo.png", inline: true), Part(3, "Forwarded.eml", message: true)], []);
        _server.FileEmail = (_, _) => new FiledEmail(Doc(200, "Lease renewal.eml"), [Doc(201, "Lease.pdf")],
            [new FiledEmail.SkippedPart(3, "already-filed", Doc(150, "Forwarded.eml"))]);
        _windows.Answer = new JsonObject
        {
            ["action"] = "save",
            ["folderId"] = 11,
            ["properties"] = new JsonObject { ["comments"] = "Renewal" },
            ["attachments"] = new JsonArray(1, 3),
        };

        var outcome = await Mail().FileAsync(Eml(), null);

        // The window shows the subject, From and Sent, and the attachments with the inline logo left out.
        var (route, json) = Assert.Single(_windows.Asked);
        Assert.Equal("save-email", route);
        var request = SaveEmailRequest.FromJson(json);
        Assert.Equal(("Lease renewal", "Pat Example <pat@example.com>", Sent, (long?)null), (request.Subject, request.From, request.Sent, request.FolderId));
        Assert.Equal([1, 3], request.Attachments.Select(a => a.Index));
        Assert.Equal(["Lease renewal"], request.Subjects);

        // Its answer is what's filed: the folder, what was typed, and the ticked attachments by their index.
        Assert.Equal(["POST /emails/inspect", "POST /emails"], _server.Calls);
        var sent = Assert.Single(_server.EmailsReceived);
        Assert.Equal((11L, """{"comments":"Renewal"}"""), (sent.FolderId, sent.Properties.ToJsonString()));
        Assert.Equal([1, 3], sent.Attachments);
        Assert.Equal("filed", (string?)outcome["outcome"]);
        Assert.Equal(200L, (long?)outcome["email"]!["docNumber"]);
        Assert.Equal((1, 1), ((int)outcome["attachments"]!, (int)outcome["skipped"]!));
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task CancelFilesNothing()
    {
        var outcome = await Mail().FileAsync(Eml(), null);

        Assert.Equal("""{"outcome":"cancelled"}""", outcome.ToJsonString());
        Assert.Equal(["POST /emails/inspect"], _server.Calls);
        Assert.Empty(_server.EmailsReceived);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AMessageTheMatterAlreadyHoldsIsAnOutcomeWithItsCopy()
    {
        _server.AddEmailCopy("m1@example.com", 50, 11);

        var outcome = await Mail().FileAsync(Eml(), 11);

        Assert.Equal(["outcome", "email"], outcome.Select(p => p.Key));
        Assert.Equal("already-filed", (string?)outcome["outcome"]);
        var email = outcome["email"]!.AsObject();
        Assert.Equal((50L, "Message.eml", Matter, "Correspondence"),
            ((long)email["docNumber"]!, (string)email["name"]!, (string)email["matter"]!, (string)email["folder"]!));
        Assert.Empty(_notifier.Notices); // not a failure: the pane shows the copy
    }

    [Fact]
    public async Task AlreadyFiledAfterTheWindowIsAnOutcomeToo()
    {
        _server.AddEmailCopy("m1@example.com", 50, 11);
        _windows.Answer = new JsonObject { ["action"] = "save", ["folderId"] = 11, ["properties"] = new JsonObject(), ["attachments"] = new JsonArray() };

        var outcome = await Mail().FileAsync(Eml(), null);

        Assert.Equal("already-filed", (string?)outcome["outcome"]);
        Assert.Equal(50L, (long?)outcome["email"]!["docNumber"]);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AnyOtherFailureIsToldWithTheProblemsDetailAndThrown()
    {
        _server.RefuseNextFiling();

        var e = await Assert.ThrowsAsync<ApiException>(() => Mail().FileAsync(Eml(), 11));

        Assert.Equal(403, e.Status);
        var notice = Assert.Single(_notifier.Notices);
        Assert.Equal("Healdan Helper", notice.Title);
        Assert.Contains("You can't add documents to this folder.", notice.Message);
    }

    [Fact]
    public async Task ARefusalOfSeveralFieldsNamesEachOfThem()
    {
        // The fake refuses each ticked attachment as one this message hasn't, as the server refuses an extension or a size.
        _windows.Answer = new JsonObject { ["action"] = "save", ["folderId"] = 11, ["properties"] = new JsonObject(), ["attachments"] = new JsonArray(4, 5) };

        await Assert.ThrowsAsync<ApiException>(() => Mail().FileAsync(Eml(), null));

        var notice = Assert.Single(_notifier.Notices);
        Assert.Equal("The email couldn't be filed in Healdan: Part 4 isn't an attachment of this email. Part 5 isn't an attachment of this email.",
            notice.Message);
    }

    [Fact]
    public async Task AServerThatCantBeReachedIsToldAndThrown()
    {
        _server.Down = true;

        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().FileAsync(Eml(), 11));

        var notice = Assert.Single(_notifier.Notices);
        Assert.Equal("Healdan Helper", notice.Title);
        Assert.Contains("couldn't be filed", notice.Message);
        Assert.Contains("still in Outlook", notice.Message);
    }

    [Fact]
    public async Task NothingAboutTheMessageIsLogged()
    {
        _server.InspectEmail = _ => new EmailInspection("secret-id@example.com", "Secret subject", "Secret Sender <secret@example.com>", Sent,
            [Part(1, "secret-plan.pdf")], []);
        _windows.Answer = new JsonObject { ["action"] = "save", ["folderId"] = 11, ["properties"] = new JsonObject(), ["attachments"] = new JsonArray(1) };
        _server.FileEmail = (_, _) => new FiledEmail(Doc(200, "Secret subject.eml"), [Doc(201, "secret-plan.pdf")], []);

        await Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), null);
        await Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), 11);
        _server.Down = true;
        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), 11));

        Assert.Contains("#200", Log); // the doc # is what the log names
        Assert.DoesNotContain("ecret", Log);
        Assert.DoesNotContain("example.com", Log);
    }
}
