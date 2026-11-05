// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Tests.Cycle;

namespace Healdan.Helper.Core.Tests.Api;

/// <summary>The three email calls (M5, spec §6): what the client sends and how it reads the answers.</summary>
public class HealdanApiEmailTests
{
    private const string DocJson = """
        {"type":"document","docNumber":1234,"name":"Lease.eml","size":10,"version":"1.1","fileVersion":"1.0",
         "reference":"1234.1.0","folder":{"id":5,"name":"Documents","kind":"folder"},"matter":null,
         "dateFiled":"2026-10-23T14:05:00Z","lastModified":"2026-10-23T14:05:00Z","hold":null,"canEdit":true}
        """;

    private static readonly byte[] Eml = "Message-ID: <abc@example.com>\r\nSubject: Lease\r\n\r\nHello\r\n"u8.ToArray();

    private readonly FakeHandler _handler = new();

    private HealdanApi Api() => new(new Uri("https://healdan.example"), _handler) { Token = "hd_tok" };

    /// <summary>One part of a <c>multipart/form-data</c> body, as the server would read it.</summary>
    private sealed record FormPart(string Name, string? FileName, string? ContentType, string Text);

    private static List<FormPart> Parts(SeenRequest request)
    {
        Assert.StartsWith("multipart/form-data", request.ContentType);
        var boundary = Regex.Match(request.ContentType!, "boundary=\"?([^\";]+)\"?").Groups[1].Value;
        var parts = new List<FormPart>();
        foreach (var raw in request.BodyText.Split("--" + boundary))
        {
            var part = raw.StartsWith("\r\n", StringComparison.Ordinal) ? raw[2..] : raw;
            var split = part.IndexOf("\r\n\r\n", StringComparison.Ordinal);
            if (split < 0) continue; // the preamble, or the closing "--"
            var headers = part[..split];
            var text = part[(split + 4)..];
            if (text.EndsWith("\r\n", StringComparison.Ordinal)) text = text[..^2];
            parts.Add(new FormPart(
                Regex.Match(headers, "name=\"?([^\";\r\n]+)\"?").Groups[1].Value,
                Regex.Match(headers, "filename=\"?([^\";\r\n]+)\"?") is { Success: true } f ? f.Groups[1].Value : null,
                Regex.Match(headers, "Content-Type: *([^\r\n]+)", RegexOptions.IgnoreCase) is { Success: true } c ? c.Groups[1].Value : null,
                text));
        }
        return parts;
    }

    // --- inspect -----------------------------------------------------------------------------------------------------

    [Fact]
    public async Task InspectPostsTheEmailAsTheFilePartAlone()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"messageId":"abc@example.com","subject":"Lease","from":null,"sent":null,"attachments":[],"copies":[]}""");

        await Api().InspectEmailAsync(Eml);

        var seen = _handler.Requests[0];
        Assert.Equal(HttpMethod.Post, seen.Method);
        Assert.Equal("https://healdan.example/api/v1/emails/inspect", seen.Uri.ToString());
        Assert.Equal("Bearer hd_tok", seen.Authorization);
        var part = Assert.Single(Parts(seen));
        Assert.Equal("file", part.Name);
        Assert.Equal("message.eml", part.FileName);
        Assert.Equal(Encoding.UTF8.GetString(Eml).TrimEnd('\r', '\n'), part.Text.TrimEnd('\r', '\n'));
    }

    [Fact]
    public async Task InspectReadsWhatTheServerFound()
    {
        _handler.Enqueue(HttpStatusCode.OK, $$"""
            {"messageId":"abc@example.com","subject":"Lease","from":"Ann Smith <ann@example.com>","sent":"2026-11-02T09:15:00Z",
             "attachments":[
               {"index":0,"name":"Contract.pdf","type":"application/pdf","size":2048,"inline":false,"message":false},
               {"index":1,"name":"logo.png","type":"image/png","size":300,"inline":true,"message":false},
               {"index":2,"name":"Re Lease.eml","type":"message/rfc822","size":900,"inline":false,"message":true}],
             "copies":[{{DocJson}}]}
            """);

        var found = await Api().InspectEmailAsync(Eml);

        Assert.Equal("abc@example.com", found.MessageId);
        Assert.Equal("Lease", found.Subject);
        Assert.Equal("Ann Smith <ann@example.com>", found.From);
        Assert.Equal(DateTimeOffset.Parse("2026-11-02T09:15:00Z"), found.Sent);
        Assert.Equal(
            [new EmailPart(0, "Contract.pdf", "application/pdf", 2048, false, false),
             new EmailPart(1, "logo.png", "image/png", 300, true, false),
             new EmailPart(2, "Re Lease.eml", "message/rfc822", 900, false, true)],
            found.Attachments);
        Assert.Equal(1234, Assert.Single(found.Copies).DocNumber);
    }

    [Fact]
    public async Task InspectReadsAMessageWithNoIdNoSenderAndNoDate()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"messageId":null,"subject":null,"from":null,"sent":null,"attachments":[],"copies":[]}""");

        var found = await Api().InspectEmailAsync(Eml);

        Assert.Null(found.MessageId);
        Assert.Null(found.Subject);
        Assert.Null(found.From);
        Assert.Null(found.Sent);
        Assert.Empty(found.Attachments);
        Assert.Empty(found.Copies);
    }

    [Fact]
    public async Task InspectOfAFileThatIsNotAnEmailIsAnApiException()
    {
        _handler.Enqueue(HttpStatusCode.UnprocessableEntity, """
            {"type":"about:blank","title":"Unprocessable Entity","status":422,"detail":"Wrong fields.","code":"validation",
             "fields":[{"field":"file","code":"not-an-email","detail":"This isn't an email message."}]}
            """);

        var e = await Assert.ThrowsAsync<ApiException>(() => Api().InspectEmailAsync(Eml));

        Assert.Equal(422, e.Status);
        Assert.Equal("validation", e.Code);
        Assert.Equal("not-an-email", e.Body.GetProperty("fields")[0].GetProperty("code").GetString());
    }

    // --- file --------------------------------------------------------------------------------------------------------

    [Fact]
    public async Task FileSendsTheEmailPartAndTheFilePart()
    {
        _handler.Enqueue(HttpStatusCode.Created, $$"""{"email":{{DocJson}},"attachments":[],"skipped":[]}""");

        await Api().FileEmailAsync(Eml, new NewEmail(5, new JsonObject { ["docclass"] = "Letters" }, [0, 2]));

        var seen = _handler.Requests[0];
        Assert.Equal(HttpMethod.Post, seen.Method);
        Assert.Equal("https://healdan.example/api/v1/emails", seen.Uri.ToString());
        Assert.Equal("Bearer hd_tok", seen.Authorization);
        var parts = Parts(seen);
        Assert.Equal(["email", "file"], parts.Select(p => p.Name));

        var email = parts[0];
        Assert.Equal("application/json", email.ContentType);
        var json = JsonNode.Parse(email.Text)!.AsObject();
        Assert.Equal(["folderId", "properties", "attachments"], json.Select(p => p.Key));
        Assert.Equal(5, (long?)json["folderId"]);
        Assert.Equal("Letters", (string?)json["properties"]!["docclass"]);
        Assert.Equal([0, 2], json["attachments"]!.AsArray().Select(n => (int)n!));

        var file = parts[1];
        Assert.Equal("message.eml", file.FileName);
        Assert.Contains("Message-ID: <abc@example.com>", file.Text);
    }

    [Fact]
    public async Task FileReadsTheEmailTheAttachmentsAndTheSkippedOnes()
    {
        _handler.Enqueue(HttpStatusCode.Created, $$"""
            {"email":{{DocJson}},
             "attachments":[{{DocJson.Replace("1234", "1235", StringComparison.Ordinal)}}],
             "skipped":[{"index":3,"code":"already-filed","document":{{DocJson.Replace("1234", "1100", StringComparison.Ordinal)}} }]}
            """);

        var filed = await Api().FileEmailAsync(Eml, new NewEmail(5, new JsonObject(), [1, 3]));

        Assert.Equal(1234, filed.Email.DocNumber);
        Assert.Equal(1235, Assert.Single(filed.Attachments).DocNumber);
        var skipped = Assert.Single(filed.Skipped);
        Assert.Equal(3, skipped.Index);
        Assert.Equal("already-filed", skipped.Code);
        Assert.Equal(1100, skipped.Document.DocNumber);
    }

    [Fact]
    public async Task AMessageAlreadyFiledIsAnApiExceptionThatCarriesTheCopy()
    {
        _handler.Enqueue(HttpStatusCode.Conflict, $$"""
            {"type":"about:blank","title":"Conflict","status":409,"detail":"This message is already in 1.1 – Smoke Matter as #1234.",
             "code":"already-filed","document":{{DocJson}} }
            """);

        var e = await Assert.ThrowsAsync<ApiException>(() => Api().FileEmailAsync(Eml, new NewEmail(5, new JsonObject(), [])));

        Assert.Equal(409, e.Status);
        Assert.Equal("already-filed", e.Code);
        Assert.Equal("This message is already in 1.1 – Smoke Matter as #1234.", e.Detail);
        Assert.Equal(1234, e.Body.GetProperty("document").GetProperty("docNumber").GetInt64());
    }

    // --- copies ------------------------------------------------------------------------------------------------------

    [Fact]
    public async Task CopiesRepeatsMessageIdForEachIdAndEscapesIt()
    {
        _handler.Enqueue(HttpStatusCode.OK, """{"items":[]}""");

        await Api().EmailCopiesAsync(["a+b&c=d#e@example.com", "<second@example.com>", "plain@example.com"]);

        var seen = _handler.Requests[0];
        Assert.Equal(HttpMethod.Get, seen.Method);
        Assert.Equal("Bearer hd_tok", seen.Authorization);
        Assert.Equal("/api/v1/emails", seen.Uri.AbsolutePath);
        Assert.Equal(
            "?messageId=a%2Bb%26c%3Dd%23e%40example.com&messageId=%3Csecond%40example.com%3E&messageId=plain%40example.com",
            seen.Uri.Query);
        Assert.Empty(seen.Body);
    }

    [Fact]
    public async Task CopiesReadsEachMessagesCopiesAndTheirAddresses()
    {
        _handler.Enqueue(HttpStatusCode.OK, $$"""
            {"items":[
               {"messageId":"abc@example.com","copies":[
                  {"document":{{DocJson}},"address":"https://healdan.example/frontend.jsp?docId=77"},
                  {"document":{{DocJson.Replace("1234", "1300", StringComparison.Ordinal)}},"address":"https://healdan.example/frontend.jsp?docId=88"}]},
               {"messageId":"none@example.com","copies":[]}]}
            """);

        var answer = await Api().EmailCopiesAsync(["abc@example.com", "none@example.com"]);

        Assert.Equal(["abc@example.com", "none@example.com"], answer.Items.Select(i => i.MessageId));
        var first = answer.Items[0];
        Assert.Equal([1234L, 1300L], first.Copies.Select(c => c.Document.DocNumber));
        Assert.Equal(["https://healdan.example/frontend.jsp?docId=77", "https://healdan.example/frontend.jsp?docId=88"],
            first.Copies.Select(c => c.Address));
        Assert.Empty(answer.Items[1].Copies);
    }

    [Fact]
    public async Task CopiesOfNoIdsOrTooManyAreTheServersBadRequest()
    {
        _handler.Enqueue(HttpStatusCode.BadRequest,
            """{"type":"about:blank","title":"Bad Request","status":400,"detail":"Send messageId 1 to 100 times.","code":"bad-request"}""");

        var e = await Assert.ThrowsAsync<ApiException>(() => Api().EmailCopiesAsync([]));

        Assert.Equal(400, e.Status);
        Assert.Equal("bad-request", e.Code);
    }
}

/// <summary>The fake server's email routes, which later tasks' tests stand on, driven by the real client.</summary>
public class FakeApiEmailTests
{
    private static byte[] Eml(string id, string subject = "Lease") =>
        Encoding.UTF8.GetBytes($"Message-ID: <{id}>\r\nSubject: {subject}\r\n\r\nHello\r\n");

    private readonly FakeApi _server = new();

    private HealdanApi Api() => new(new Uri("http://localhost:8080"), _server) { Token = "hd_tok" };

    [Fact]
    public async Task CopiesAnswerEachIdOnceWithoutBracketsWhateverBracketsItWasAskedWith()
    {
        _server.AddEmailCopy("<abc@example.com>", 50, 9);

        var answer = await Api().EmailCopiesAsync(["abc@example.com", "<abc@example.com>", "none@example.com"]);

        Assert.Equal(["abc@example.com", "none@example.com"], answer.Items.Select(i => i.MessageId));
        var copy = Assert.Single(answer.Items[0].Copies);
        Assert.Equal(50, copy.Document.DocNumber);
        Assert.Equal(9, copy.Document.Folder.Id);
        // The address names the document's internal id, as the server's does, never its doc #.
        var id = _server.Doc(50).Id;
        Assert.NotEqual(50, id);
        Assert.EndsWith($"/frontend.jsp?docId={id}", copy.Address);
        Assert.Empty(answer.Items[1].Copies);
    }

    [Fact]
    public async Task CopiesCountTheValuesSentNotTheDistinctIds()
    {
        _server.AddEmailCopy("abc@example.com", 50, 9);

        // 101 values that are one id are still 101 values, as the server counts them.
        var e = await Assert.ThrowsAsync<ApiException>(() => Api().EmailCopiesAsync([.. Enumerable.Repeat("abc@example.com", 101)]));
        Assert.Equal((400, "bad-request"), (e.Status, e.Code));

        var answer = await Api().EmailCopiesAsync([.. Enumerable.Repeat("abc@example.com", 100)]);
        Assert.Equal("abc@example.com", Assert.Single(answer.Items).MessageId);
    }

    [Fact]
    public async Task CopiesOfNoIdsAreABadRequest()
    {
        var e = await Assert.ThrowsAsync<ApiException>(() => Api().EmailCopiesAsync([]));

        Assert.Equal("bad-request", e.Code);
    }

    [Fact]
    public async Task InspectReadsTheHeadersAndGivesTheCopies()
    {
        _server.AddEmailCopy("abc@example.com", 50, 9);

        var found = await Api().InspectEmailAsync(Eml("abc@example.com"));

        Assert.Equal("abc@example.com", found.MessageId);
        Assert.Equal("Lease", found.Subject);
        Assert.Empty(found.Attachments);
        Assert.Equal(50, Assert.Single(found.Copies).DocNumber);
    }

    [Fact]
    public async Task InspectCanBeAnsweredByTheTest()
    {
        _server.InspectEmail = _ => new EmailInspection("x@example.com", null, null, null, [new EmailPart(0, "a.pdf", "application/pdf", 1, false, false)], []);

        var found = await Api().InspectEmailAsync(Eml("abc@example.com"));

        Assert.Equal("x@example.com", found.MessageId);
        Assert.Equal("a.pdf", Assert.Single(found.Attachments).Name);
    }

    [Fact]
    public async Task FilingStoresTheEmailAndItsCopyIsFoundAfterwards()
    {
        var filed = await Api().FileEmailAsync(Eml("abc@example.com", "Lease"), new NewEmail(9, new JsonObject(), []));

        Assert.Equal("Lease.eml", filed.Email.Name);
        Assert.Empty(filed.Attachments);
        Assert.Empty(filed.Skipped);
        var answer = await Api().EmailCopiesAsync(["abc@example.com"]);
        Assert.Equal(filed.Email.DocNumber, Assert.Single(answer.Items[0].Copies).Document.DocNumber);
        Assert.Contains("POST /emails", _server.Calls);
    }

    [Fact]
    public async Task FilingAMessageTheFolderHoldsIsAlreadyFiledAndNamesTheCopy()
    {
        _server.AddEmailCopy("abc@example.com", 50, 9);

        var e = await Assert.ThrowsAsync<ApiException>(
            () => Api().FileEmailAsync(Eml("abc@example.com"), new NewEmail(9, new JsonObject(), [])));

        Assert.Equal(409, e.Status);
        Assert.Equal("already-filed", e.Code);
        Assert.Equal(50, e.Body.GetProperty("document").GetProperty("docNumber").GetInt64());

        var other = await Api().FileEmailAsync(Eml("abc@example.com"), new NewEmail(10, new JsonObject(), []));
        Assert.NotEqual(50, other.Email.DocNumber); // another folder doesn't hold it
    }

    [Fact]
    public async Task ATickedAttachmentTheFakeDoesNotKnowIsInvalid()
    {
        var e = await Assert.ThrowsAsync<ApiException>(
            () => Api().FileEmailAsync(Eml("abc@example.com"), new NewEmail(9, new JsonObject(), [2])));

        Assert.Equal(422, e.Status);
        Assert.Equal("not-an-attachment", e.Body.GetProperty("fields")[0].GetProperty("code").GetString());
    }

    [Fact]
    public async Task FilingCanBeAnsweredByTheTestAndRefusedOnce()
    {
        NewEmail? seen = null;
        byte[]? seenFile = null;
        _server.FileEmail = (email, file) =>
        {
            (seen, seenFile) = (email, file);
            var doc = _server.Add(70, "Re.eml", "x");
            return new FiledEmail(
                new DocumentView("document", 70, doc.Name, 1, "1.0", "1.0", "70.1.0", new FolderRef(9, "Documents", "folder"), null,
                    DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, true), [], []);
        };

        var filed = await Api().FileEmailAsync(Eml("abc@example.com"), new NewEmail(9, new JsonObject(), [1]));

        Assert.Equal(70, filed.Email.DocNumber);
        Assert.Equal([1], seen!.Attachments);
        Assert.Contains("Message-ID: <abc@example.com>", Encoding.UTF8.GetString(seenFile!));

        _server.RefuseNextFiling();
        var e = await Assert.ThrowsAsync<ApiException>(() => Api().FileEmailAsync(Eml("abc@example.com"), new NewEmail(9, new JsonObject(), [])));
        Assert.Equal(403, e.Status);
    }
}
