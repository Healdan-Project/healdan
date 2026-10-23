---
title: Emails
weight: 4
---

To file an email, save it as an `.eml` or `.msg` file and upload it into a matter's folder like any other document.
Healdan treats every `.eml` and `.msg` file as an email.

## What Healdan Reads from It

When an email is filed, Healdan fills in its properties from the message:

- **From, To and CC.** Several recipients are separated by commas.
- **Subject.**
- **Sent and Received.** For a `.eml` file without a Received time, Healdan uses its delivery date. A `.msg` file
  keeps only one date, so its Received is the same as its Sent.

## Its Name

An email is named after its subject, with its original extension, so a message filed as `message.eml` with the
subject "Re: Demo plan" becomes `Re_ Demo plan.eml`.

- Characters that can't be in a file name (`/ \ : * ? " < > |`) become `_`. The Subject property keeps the subject
  as it was.
- An email with no subject keeps the name it was uploaded with.
- You can rename it afterwards, as any document.

## Finding Emails

Healdan reads an email's headers, its text and its attachments' names, so full-text search finds words in them. The
search box's **Emails** and **Docs & Emails** scopes include emails; the **Documents** scope leaves them out. See
[Searching](../../finding-documents/searching/).
