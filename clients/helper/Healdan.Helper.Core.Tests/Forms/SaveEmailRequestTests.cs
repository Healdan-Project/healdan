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

    private static EmailInspection Inspection(string? subject = "Lease renewal") => new("m1@example.com", subject, "Pat Example <pat@example.com>", Sent,
    [
        new EmailPart(1, "Lease.pdf", "application/pdf", 2048, false, false),
        new EmailPart(2, "logo.png", "image/png", 300, true, false),
        new EmailPart(3, "Forwarded.eml", "message/rfc822", 900, false, true),
    ], []);

    [Fact]
    public void AMessageIsShownWithItsAttachmentsAndNotItsInlineParts()
    {
        var request = SaveEmailRequest.For(Inspection(), folderId: 11);

        Assert.Equal(("Lease renewal", "Pat Example <pat@example.com>", Sent, 11L), (request.Subject, request.From, request.Sent, request.FolderId));
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

        Assert.Equal(["subject", "from", "sent", "attachments", "subjects", "folderId"], json.Select(p => p.Key));
        Assert.Equal((request.Subject, request.From, request.Sent, request.FolderId), (back.Subject, back.From, back.Sent, back.FolderId));
        Assert.Equal(request.Attachments, back.Attachments);
        Assert.Equal(request.Subjects, back.Subjects);
    }

    [Fact]
    public void WhatTheMessageLacksIsLeftEmpty()
    {
        var request = SaveEmailRequest.FromJson(new JsonObject());

        Assert.Equal(((string?)null, (string?)null, (DateTimeOffset?)null, (long?)null), (request.Subject, request.From, request.Sent, request.FolderId));
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

    [Fact]
    public void SignedOutTheEmailIsNotFiledAndTheUserIsToldSo() =>
        Assert.Equal("Healdan Helper isn't signed in, so the email wasn't filed. Sign in, then file it again from Outlook.",
            WindowAnswers.NotReadyNotice("mail/file"));
}
