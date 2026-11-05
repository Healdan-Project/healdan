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
/// <c>mail/file</c> (M5 spec §7.2, §7.3, §10): one message filed, straight into a suggested folder when its form needs
/// nothing typed (File Here), otherwise through the Save window's email mode (File…), whose Save files through the window
/// and shows a refusal in it. The server is <see cref="FakeApi"/>, and the window clicks Save with what the test sets
/// (<see cref="FakeWindows"/>).
/// </summary>
public sealed class MailFileTests : IDisposable
{
    private static readonly DateTimeOffset Sent = DateTimeOffset.Parse("2026-11-02T14:05:00Z");
    private const string Matter = "1.1 – Smoke Matter";

    private readonly string _root = Directory.CreateTempSubdirectory("healdan-mail-file-").FullName;
    private readonly FakeApi _server = new();
    private readonly FakeWindows _windows = new();
    private readonly FakeNotifier _notifier = new();

    public MailFileTests()
    {
        _server.AddFolder(11, "Correspondence");
        _server.AddFolder(12, "Drafts");
    }

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

    private static JsonObject Save(long folderId, params int[] attachments) => new()
    {
        ["action"] = "save",
        ["folderId"] = folderId,
        ["properties"] = new JsonObject(),
        ["attachments"] = new JsonArray([.. attachments.Select(i => (JsonNode)i)]),
    };

    /// <summary>The user clicks Save with each of these in turn, until one is done.</summary>
    private void Saves(params JsonObject[] saves)
    {
        foreach (var save in saves)
            _windows.EmailSaves.Enqueue(save);
    }

    /// <summary>Comments is required in folder 11's form, and nothing fills it: a File Here there can't file without typing.</summary>
    private void CommentsRequired() =>
        _server.SetForm(11, Forms.Forms.Form(f => f["comments"]["required"] = true));

    private void OneAttachment() => _server.InspectEmail = _ =>
        new EmailInspection("m1@example.com", "Lease renewal", "Pat Example <pat@example.com>", Sent, [Part(1, "setup.exe")], []);

    private SaveEmailRequest WindowRequest() => SaveEmailRequest.FromJson(Assert.Single(_windows.Asked, a => a.Route == "save-email").Request);

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

        var outcome = await Mail().FileAsync(Eml(), 11, openAt: 12);

        var request = WindowRequest();
        Assert.Equal(11L, request.FolderId); // File Here's folder, whatever openAt says
        Assert.Null(request.Refusal); // nothing was refused: there's something to type
        Assert.Equal("cancelled", (string?)outcome["outcome"]); // the window's Cancel, as the user clicked no Save
        Assert.DoesNotContain("POST /emails", _server.Calls);
    }

    [Fact]
    public async Task WithNoFolderTheWindowShowsTheMessageAndFilesItsSave()
    {
        _server.InspectEmail = _ => new EmailInspection("m1@example.com", "Lease renewal", "Pat Example <pat@example.com>", Sent,
            [Part(1, "Lease.pdf"), Part(2, "logo.png", inline: true), Part(3, "Forwarded.eml", message: true)], []);
        _server.FileEmail = (_, _) => new FiledEmail(Doc(200, "Lease renewal.eml"), [Doc(201, "Lease.pdf")],
            [new FiledEmail.SkippedPart(3, "already-filed", Doc(150, "Forwarded.eml"))]);
        _windows.Answer = Save(11, 1, 3);
        _windows.Answer["properties"] = new JsonObject { ["comments"] = "Renewal" };

        var outcome = await Mail().FileAsync(Eml(), null);

        // The window shows the subject, From and Sent, and the attachments with the inline logo left out, at Recent Folders.
        var request = WindowRequest();
        Assert.Equal(("Lease renewal", "Pat Example <pat@example.com>", Sent, (long?)null), (request.Subject, request.From, request.Sent, request.FolderId));
        Assert.Equal([1, 3], request.Attachments.Select(a => a.Index));
        Assert.Equal(["Lease renewal"], request.Subjects);

        // Its Save is what's filed: the folder, what was typed, and the ticked attachments by their index; and it's done.
        Assert.Equal(["POST /emails/inspect", "POST /emails"], _server.Calls);
        var sent = Assert.Single(_server.EmailsReceived);
        Assert.Equal((11L, """{"comments":"Renewal"}"""), (sent.FolderId, sent.Properties.ToJsonString()));
        Assert.Equal([1, 3], sent.Attachments);
        Assert.IsType<SubmitOutcome.Filed>(Assert.Single(_windows.EmailOutcomes));
        Assert.Equal("filed", (string?)outcome["outcome"]);
        Assert.Equal(200L, (long?)outcome["email"]!["docNumber"]);
        Assert.Equal((1, 1), ((int)outcome["attachments"]!, (int)outcome["skipped"]!));
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task FileOpensTheWindowAtTheFolderThePaneSuggests()
    {
        await Mail().FileAsync(Eml(), null, openAt: 12);

        Assert.Equal(12L, WindowRequest().FolderId);
        Assert.Equal(["POST /emails/inspect"], _server.Calls); // nothing is read of it, or filed, before the user chooses
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
        Assert.Empty(_windows.Asked);
        Assert.Empty(_notifier.Notices); // not a failure: the pane shows the copy
    }

    [Fact]
    public async Task AlreadyFiledFromTheWindowClosesItAsFiled()
    {
        _server.AddEmailCopy("m1@example.com", 50, 11);
        _windows.Answer = Save(11);

        var outcome = await Mail().FileAsync(Eml(), null);

        Assert.IsType<SubmitOutcome.Filed>(Assert.Single(_windows.EmailOutcomes)); // done: the window closes
        Assert.Equal("already-filed", (string?)outcome["outcome"]);
        Assert.Equal(50L, (long?)outcome["email"]!["docNumber"]);
        Assert.Empty(_notifier.Notices);
    }

    [Theory]
    [InlineData(409, "filing-rule", "Only the Correspondence folder takes emails in this matter.", null)]
    [InlineData(403, "forbidden", "You may not file documents into folder 11.", null)]
    [InlineData(413, "too-large", "The file is larger than 100 MB, the most Healdan takes.", null)]
    [InlineData(422, "validation", "Attachment 1 (setup.exe): Files ending in .exe can't be uploaded.", "attachments[1]")]
    [InlineData(422, "validation", "Comments takes at most 4000 characters.", "comments")]
    public async Task ARefusalShowsInTheWindowWhichStaysOpenForAnotherTry(int status, string code, string detail, string? field)
    {
        OneAttachment();
        _server.RefuseNextEmail(status, code, detail, field is null ? null : new JsonObject
        {
            ["fields"] = new JsonArray(new JsonObject { ["field"] = field, ["code"] = "forbidden-extension", ["detail"] = detail }),
        });
        Saves(Save(11, 1), Save(12)); // refused; then another folder, with the attachment unticked

        var outcome = await Mail().FileAsync(Eml(), null);

        var outcomes = _windows.EmailOutcomes.ToList();
        var refused = Assert.IsType<SubmitOutcome.Refused>(outcomes[0]);
        Assert.Equal(detail, refused.Problem);
        Assert.Equal(field is null ? [] : [field], refused.Fields.Select(f => f.Field));
        Assert.IsType<SubmitOutcome.Filed>(outcomes[1]);
        Assert.Equal([11L, 12L], _server.EmailsReceived.Select(e => e.FolderId));
        Assert.Equal("filed", (string?)outcome["outcome"]); // the route's outcome is the filing, never the refusal
        Assert.Empty(_notifier.Notices); // the window said it
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AnOutageInTheWindowSaysCantReachHealdanAndStaysOpen(bool fiveHundred)
    {
        if (fiveHundred)
            _server.RefuseNextEmail(503, "unavailable", "Healdan is busy.");
        else
            _server.DropNextEmail();
        Saves(Save(11), Save(11));

        var outcome = await Mail().FileAsync(Eml(), null);

        var refused = Assert.IsType<SubmitOutcome.Refused>(_windows.EmailOutcomes.First());
        Assert.Equal(("Can't reach Healdan. Try again.", 0), (refused.Problem, refused.Fields.Count));
        Assert.Equal("filed", (string?)outcome["outcome"]); // the second Save went through
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task ATokenRefusedInTheWindowSaysToSignInAgainAndStaysOpen()
    {
        _server.RefuseNextEmail(401, "unauthenticated", "The token is unknown, expired or revoked.");
        Saves(Save(11), Save(11)); // signed in again meanwhile, the second Save files

        var outcome = await Mail().FileAsync(Eml(), null);

        var refused = Assert.IsType<SubmitOutcome.Refused>(_windows.EmailOutcomes.First());
        Assert.Equal(("Sign in to Healdan again, then choose Save.", 0), (refused.Problem, refused.Fields.Count));
        Assert.Equal("filed", (string?)outcome["outcome"]);
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task AMessageOverTheUploadLimitIsRefusedAsTooLargeWithTheServersWords()
    {
        _server.InspectRefusal = (413, "too-large", "The email is larger than the 10 MB limit.");

        var e = await Assert.ThrowsAsync<MailTooLargeException>(() => Mail().FileAsync(Eml(), null));

        Assert.Equal("The email is larger than the 10 MB limit.", e.Message);
        Assert.Empty(_windows.Asked);
        Assert.Single(_notifier.Notices); // the user is told, as for any refusal before a window
    }

    [Fact]
    public async Task ATokenRefusedBeforeTheWindowIsToldAndThrown()
    {
        _server.Unauthorized = true;

        var e = await Assert.ThrowsAsync<ApiException>(() => Mail().FileAsync(Eml(), null)); // inspect
        Assert.Equal(401, e.Status);
        // File Here: the form's 401 would open the window, but inspect's comes first, before any window is up.
        Assert.Equal(401, (await Assert.ThrowsAsync<ApiException>(() => Mail().FileAsync(Eml(), 11))).Status);

        Assert.Empty(_windows.Asked);
        Assert.Equal(2, _notifier.Notices.Count);
        Assert.All(_notifier.Notices, n => Assert.Equal("The email wasn't filed: sign in to Healdan again, then file it once more.", n.Message));
    }

    [Fact]
    public async Task AFailureOfTheHelpersOwnInTheWindowSaysSoAndStaysOpen()
    {
        // A Save with no folder is no answer the window gives: the filing fails before it reaches Healdan.
        Saves(new JsonObject { ["action"] = "save" }, Save(11));

        var outcome = await Mail().FileAsync(Eml(), null);

        var refused = Assert.IsType<SubmitOutcome.Refused>(_windows.EmailOutcomes.First());
        Assert.Equal(("Healdan Helper couldn't file the email. Try again.", 0), (refused.Problem, refused.Fields.Count));
        Assert.Contains("ERROR Filing an email from the Save window failed (InvalidOperationException", Log);
        Assert.Equal("filed", (string?)outcome["outcome"]);
        Assert.Single(_server.EmailsReceived); // the first never reached Healdan
    }

    [Fact]
    public async Task GivingUpAfterARefusalIsCancelled()
    {
        _server.RefuseNextEmail(403, "forbidden", "You may not file documents into folder 11.");
        Saves(Save(11)); // refused, then the user closes the window

        var outcome = await Mail().FileAsync(Eml(), null);

        Assert.Equal("""{"outcome":"cancelled"}""", outcome.ToJsonString());
    }

    [Theory]
    [InlineData(409, "filing-rule", "Only the Correspondence folder takes emails in this matter.")]
    [InlineData(403, "forbidden", "You may not file documents into folder 11.")]
    [InlineData(413, "too-large", "The file is larger than 100 MB, the most Healdan takes.")]
    public async Task FileHereRefusedOpensTheWindowAtTheFolderWithTheRefusal(int status, string code, string detail)
    {
        _server.RefuseNextEmail(status, code, detail);
        Saves(Save(12)); // the user chooses another folder

        var outcome = await Mail().FileAsync(Eml(), 11);

        Assert.Equal(["GET /folders/11/form", "POST /emails", "POST /emails/inspect", "POST /emails", "filed 100"], _server.Calls);
        var request = WindowRequest();
        Assert.Equal(11L, request.FolderId);
        Assert.Equal(detail, request.Refusal?.Problem);
        Assert.Equal("filed", (string?)outcome["outcome"]);
        Assert.Equal([11L, 12L], _server.EmailsReceived.Select(e => e.FolderId));
        Assert.Empty(_notifier.Notices);
    }

    [Fact]
    public async Task FileHereRefusedForAFieldCarriesTheFieldToTheWindow()
    {
        _server.RefuseNextEmail(422, "validation", "Deadline takes a date such as 2026-10-24.", new JsonObject
        {
            ["fields"] = new JsonArray(new JsonObject { ["field"] = "deadline", ["code"] = "bad-date", ["detail"] = "Deadline takes a date such as 2026-10-24." }),
        });

        await Mail().FileAsync(Eml(), 11);

        var refusal = WindowRequest().Refusal!;
        Assert.Equal(("deadline", "Deadline takes a date such as 2026-10-24."), (refusal.Fields.Single().Field, refusal.Fields.Single().Detail));
    }

    [Fact]
    public async Task FileHereIntoAFolderThatIsGoneOpensTheWindowSayingSo()
    {
        var outcome = await Mail().FileAsync(Eml(), 99);

        var request = WindowRequest();
        Assert.Equal((99L, "No such folder."), (request.FolderId, request.Refusal?.Problem));
        Assert.Equal("cancelled", (string?)outcome["outcome"]);
        Assert.DoesNotContain("POST /emails", _server.Calls);
        Assert.Empty(_notifier.Notices);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AnOutageOnFileHereIsToldAndThrown(bool fiveHundred)
    {
        if (fiveHundred)
            _server.RefuseNextEmail(503, "unavailable", "Healdan is busy.");
        else
            _server.DropNextEmail();

        var e = await Assert.ThrowsAnyAsync<Exception>(() => Mail().FileAsync(Eml(), 11));

        Assert.True(fiveHundred ? e is ApiException { Status: 503 } : e is HttpRequestException, e.GetType().Name);
        Assert.Empty(_windows.Asked); // the pane says Can't Reach Healdan
        var notice = Assert.Single(_notifier.Notices);
        Assert.Equal("Healdan Helper", notice.Title);
        Assert.Contains("couldn't be filed", notice.Message);
        Assert.Contains("still in Outlook", notice.Message);
    }

    [Fact]
    public async Task AServerThatCantBeReachedBeforeTheWindowIsToldAndThrown()
    {
        _server.Down = true;

        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().FileAsync(Eml(), null)); // inspect
        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().FileAsync(Eml(), 11));   // the folder's form

        Assert.Empty(_windows.Asked);
        Assert.Equal(2, _notifier.Notices.Count);
        Assert.All(_notifier.Notices, n => Assert.Contains("may be unreachable", n.Message));
    }

    [Fact]
    public async Task NothingAboutTheMessageIsLogged()
    {
        _server.InspectEmail = _ => new EmailInspection("secret-id@example.com", "Secret subject", "Secret Sender <secret@example.com>", Sent,
            [Part(1, "secret-plan.pdf")], []);
        const string named = "Attachment 1 (secret-plan.pdf): Files ending in .pdf can't be uploaded.";
        _server.RefuseNextEmail(422, "validation", named, new JsonObject
        {
            ["fields"] = new JsonArray(new JsonObject { ["field"] = "attachments[1]", ["code"] = "forbidden-extension", ["detail"] = named }),
        });
        Saves(Save(11, 1), Save(11, 1));
        _server.FileEmail = (_, _) => new FiledEmail(Doc(200, "Secret subject.eml"), [Doc(201, "secret-plan.pdf")], []);

        await Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), null);
        await Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), 11);
        _server.Down = true;
        await Assert.ThrowsAsync<HttpRequestException>(() => Mail().FileAsync(Eml("secret-id@example.com", "Secret subject"), 11));

        Assert.Contains("#200", Log); // the doc # is what the log names
        Assert.Contains("422 validation", Log); // and a refusal by its status and code
        Assert.DoesNotContain("ecret", Log);
        Assert.DoesNotContain("example.com", Log);
    }
}
