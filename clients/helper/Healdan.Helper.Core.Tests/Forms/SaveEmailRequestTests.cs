// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

using System.Globalization;
using System.Text.Json.Nodes;
using Healdan.Helper.Core.Api;
using Healdan.Helper.Core.Forms;
using Healdan.Helper.Core.Ui;

namespace Healdan.Helper.Core.Tests.Forms;

/// <summary>What filing a message asks of the Save window's email mode, and what it answers (M5 spec §7.3).</summary>
public class SaveEmailRequestTests
{
    private static readonly DateTimeOffset Sent = DateTimeOffset.Parse("2026-11-02T14:05:00+01:00");

    private static readonly DateTimeOffset Received = DateTimeOffset.Parse("2026-11-02T14:05:07+01:00");

    private static EmailInspection Inspection(string? subject = "Lease renewal") => new("m1@example.com", subject, "Pat Example <pat@example.com>", Sent,
    [
        new EmailPart(1, "Lease.pdf", "application/pdf", 2048, false, false),
        new EmailPart(2, "logo.png", "image/png", 300, true, false),
        new EmailPart(3, "Forwarded.eml", "message/rfc822", 900, false, true),
    ], [], To: "Robert Example <robert@example.com>, Sam Example <sam@example.com>", Cc: "Ann Example <ann@example.com>", Received: Received);

    [Fact]
    public void AMessageIsShownWithItsAttachmentsAndNotItsInlineParts()
    {
        var request = SaveEmailRequest.For(Inspection(), folderId: 11);

        Assert.Equal(("Lease renewal", "Pat Example <pat@example.com>", Sent, 11L), (request.Subject, request.From, request.Sent, request.FolderId));
        Assert.Equal(("Robert Example <robert@example.com>, Sam Example <sam@example.com>", "Ann Example <ann@example.com>", Received),
            (request.To, request.Cc, request.Received));
        Assert.Equal([1, 3], request.Attachments.Select(a => a.Index));
        Assert.Equal(["Lease renewal"], request.Subjects);
    }

    [Fact]
    public void AMessageWithoutASubjectHasNoneToList()
    {
        var request = SaveEmailRequest.For(Inspection(subject: null), folderId: null);

        Assert.Null(request.Subject);
        Assert.Empty(request.Subjects);
        Assert.Null(request.FolderId);
    }

    [Fact]
    public void ItGoesToTheWindowAsJsonAndBackWhole()
    {
        var request = SaveEmailRequest.For(Inspection(), folderId: 11);

        var json = request.ToJson();
        var back = SaveEmailRequest.FromJson(JsonNode.Parse(json.ToJsonString())!.AsObject());

        Assert.Equal(["subject", "from", "to", "cc", "sent", "received", "attachments", "subjects", "folderId", "refusal"], json.Select(p => p.Key));
        Assert.Equal((request.Subject, request.From, request.Sent, request.FolderId), (back.Subject, back.From, back.Sent, back.FolderId));
        Assert.Equal((request.To, request.Cc, request.Received), (back.To, back.Cc, back.Received));
        Assert.Equal(request.Attachments, back.Attachments);
        Assert.Equal(request.Subjects, back.Subjects);
        Assert.Null(back.Refusal);
    }

    [Fact]
    public void AFileHeresRefusalGoesToTheWindowWithItsFields()
    {
        var refusal = new SubmitOutcome.Refused("Deadline takes a date.", [new FieldError("deadline", "bad-date", "Deadline takes a date.")]);
        var request = SaveEmailRequest.For(Inspection(), folderId: 11, refusal);

        var back = SaveEmailRequest.FromJson(JsonNode.Parse(request.ToJson().ToJsonString())!.AsObject());

        Assert.Equal("Deadline takes a date.", back.Refusal?.Problem);
        Assert.Equal(refusal.Fields, back.Refusal!.Fields);
    }

    [Fact]
    public void WhatTheMessageLacksIsLeftEmpty()
    {
        var request = SaveEmailRequest.FromJson(new JsonObject());

        Assert.Equal(((string?)null, (string?)null, (DateTimeOffset?)null, (long?)null), (request.Subject, request.From, request.Sent, request.FolderId));
        Assert.Equal(((string?)null, (string?)null, (DateTimeOffset?)null), (request.To, request.Cc, request.Received));
        Assert.Empty(request.Attachments);
        Assert.Empty(request.Subjects);
    }

    [Fact]
    public void AnAttachmentIsNamedWithItsSize()
    {
        var part = new EmailPart(1, "Lease.pdf", "application/pdf", 2048, false, false);

        Assert.Equal("Lease.pdf (2.0 KB)", SaveEmailRequest.Label(part, CultureInfo.InvariantCulture));
    }

    [Fact]
    public void SaveAnswersTheFolderWhatWasTypedAndTheTickedAttachments()
    {
        var answer = WindowAnswers.SaveEmail(11, new JsonObject { ["comments"] = "Renewal" }, [1, 3]);

        Assert.Equal("""{"action":"save","folderId":11,"properties":{"comments":"Renewal"},"attachments":[1,3]}""", answer.ToJsonString());
    }

    private static FieldError Error(string field, string detail) => new(field, "x", detail);

    private static readonly Func<string, bool> FormHasDeadline = name => name == "deadline";

    private static readonly Func<int, bool> OffersOneAndThree = index => index is 1 or 3;

    [Fact]
    public void ARefusalWithoutFieldsShowsOnTheProblemLine()
    {
        var placed = new SubmitOutcome.Refused("Only the Correspondence folder takes emails.", []).Place(FormHasDeadline, OffersOneAndThree);

        Assert.Equal(("Only the Correspondence folder takes emails.", 0, 0), (placed.General, placed.OnFields.Count, placed.OnAttachments.Count));
    }

    [Fact]
    public void EachFieldErrorShowsWhereItBelongs()
    {
        var placed = new SubmitOutcome.Refused("Some fields need correcting.",
        [
            Error("deadline", "Deadline takes a date."),
            Error("attachments[3]", "Attachment 3 (setup.exe): Files ending in .exe can't be uploaded."),
            Error("attachments[1]", "Attachment 1 (Lease.pdf): The file is empty."),
        ]).Place(FormHasDeadline, OffersOneAndThree);

        Assert.Equal([("deadline", "Deadline takes a date.")], placed.OnFields);
        // By the field's index, never the words, which show whole under the tick.
        Assert.Equal([(3, "Attachment 3 (setup.exe): Files ending in .exe can't be uploaded."), (1, "Attachment 1 (Lease.pdf): The file is empty.")],
            placed.OnAttachments);
        Assert.Null(placed.General); // every one has its place, so the line has nothing to add
    }

    [Fact]
    public void FieldErrorsWithNoPlaceOfTheirOwnShowOnTheProblemLine()
    {
        var placed = new SubmitOutcome.Refused("Some fields need correcting.",
        [
            Error("pages", "Pages takes a whole number."),                                // not on this form
            Error("attachments[9]", "The email has no attachment 9."),                   // not offered
            Error("attachments[2]", "Part 2 is shown in the message, so it isn't an attachment."), // nor this one
            Error("attachments", "An attachment's index is missing."),                   // no index at all
            Error("attachments[x]", "Attachment x: something."),                         // one that doesn't parse
            Error("file", "Files ending in .eml can't be uploaded."),                      // the email itself
        ]).Place(FormHasDeadline, OffersOneAndThree);

        Assert.Empty(placed.OnFields);
        Assert.Empty(placed.OnAttachments);
        Assert.Equal("Pages takes a whole number. The email has no attachment 9. Part 2 is shown in the message, so it isn't an attachment. "
            + "An attachment's index is missing. Attachment x: something. Files ending in .eml can't be uploaded.", placed.General);
    }

    [Theory]
    [InlineData("attachments[0]", 0)]
    [InlineData("attachments[12]", 12)]
    [InlineData("attachments", null)]
    [InlineData("attachments[]", null)]
    [InlineData("attachments[-1]", null)]
    [InlineData("attachments[1] ", null)]
    [InlineData("docclass", null)]
    public void AnAttachmentsIndexIsReadFromItsFieldsName(string field, int? index) =>
        Assert.Equal(index, SubmitOutcome.Refused.AttachmentIndex(field));

    [Fact]
    public void SignedOutTheEmailIsNotFiledAndTheUserIsToldSo() =>
        Assert.Equal("Healdan Helper isn't signed in, so the email wasn't filed. Sign in, then file it again from Outlook.",
            WindowAnswers.NotReadyNotice("mail/file"));
}
