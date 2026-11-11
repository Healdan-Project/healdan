---
title: Emails
weight: 4
---

To file an email, save it as an `.eml` or `.msg` file and upload it into a matter's folder like any other document.
Healdan treats every `.eml` and `.msg` file as an email. From Outlook on your Mac you can file a message without
saving it first: see [Filing Email from Outlook](../../office/filing-email/).

## Reading an Email

Select a filed email and press **Space**, or dock the preview as the reading pane (see
[Previews](../../documents/previews/)). The new interface shows it as a message, not as a file.

- **The top of it** is the subject, then **From**, **To**, **Cc**, **Sent** and **Received**. A line the message
  doesn't have is left out.
- **The attachments** are chips under them, each with its size. Click a PDF's or a picture's to see it in place of the
  message, with **Back to Message** to return and **Download** to save the file. Healdan knows one by its type or by
  its name, as some mail programs send every file as a plain file. Any other file, a TIFF or a HEIC included,
  downloads when you click its chip.
- **Then the message itself,** as the sender wrote it: their fonts, colours, tables and links. Healdan takes out
  anything that could run or fetch something on its own: scripts, forms, embedded pages and plug-ins. A link opens in
  a new window, and the page it opens can't reach back into Healdan.

An `.msg` file reads the same way, with two differences. Its **Received** is its Sent, as it keeps only one date.
(Its body is its HTML, or else its text: never the RTF that Outlook keeps beside them.)
And Healdan can't tell its inline pictures from its attachments, so a picture the message shows shows as
"[Picture not shown]" in the body, and the file is among the attachments.

A message that is signed shows its body and attachments as any other does. One that is encrypted has no body to show:
the page says so, and its attachments aren't listed.

### Pictures from the Internet

Pictures that the message fetches from the internet are blocked: "Pictures from the internet are blocked." is
over the message. A sender can use such a picture to learn that, and when, you opened their message. **Show
Pictures** reloads the message with them, for that viewing only: the next time you open the message, they're blocked
again.

Pictures sent inside the message always show, up to 2 MB of them in all. Any more show as "[Picture not shown]".

### Save to Matter

**Save to Matter** on an attachment's chip files it as a document of its own, in the same folder as the email,
with its own doc #. It takes the properties you filed the email with, so a property you filled in applies to it
too (the email's own From, To, Subject and dates stay the email's, and so does its legacy doc #), and otherwise the
folder's, as any upload does. A value the email has that a new document can't take now, such as an author whose
account has since been deleted, is left out rather than refusing the save. Healdan links it to the email, as it does when you file email from Outlook. The chip then says **Saved as # 10412**, as it does when you open an email
whose attachment was filed already.

- **You need to be able to write to the folder,** and to download the email.
- **Saving it again files another copy,** as uploading a file twice does, but a message attached to the email can
  be in the matter only once: saving one the matter holds already is refused, naming the copy it holds.
- **Two attachments with the same name** are told apart by their size, and then by the order they were saved in, so
  saving one doesn't mark the other as saved.

## What Healdan Reads from It

When an email is filed, Healdan fills in its properties from the message:

- **From, To and CC.** Several recipients are separated by commas.
- **Subject.**
- **Sent and Received.** For a `.eml` file without a Received time, Healdan uses its delivery date. A `.msg` file
  keeps only one date, so its Received is the same as its Sent.
- **Message-ID.** The identifier the sender's mail program gave the message, which tells one message from
  another. Healdan keeps it without its angle brackets, every pair of them, as some mail programs add their own. A
  message with no Message-ID, or one too long to keep (over 998 characters), has this property empty.

## Once in Each Matter

A matter holds each message once. Healdan tells one message from another by its Message-ID.

- **Filing it again into the same matter is refused,** in any of its folders and however the message arrives:
  uploaded in the browser, saved over WebDAV, sent through the API, copied, moved in from another matter, or restored
  from the trash, with a folder or on its own. The refusal names the copy the matter holds, for example "This message
  is already in 998.1 as #12345."
- **Folders count the same way.** Copying a folder with its documents within the matter it's in is refused when it
  holds an email, as the copy would hold a second copy of the message. So is moving or merging a folder into a matter
  when it would bring along a message the matter holds, or two copies of one message.
- **Another matter can hold its own copy,** so one message can be filed in every matter it concerns.
- **A copy you can't see doesn't count.** If the matter's copy is in a folder you can't open, your filing goes
  through, so a refusal never tells you about a document you can't read.
- **The trash doesn't count.** Once a copy is deleted, the message can be filed again; the deleted copy then can't
  be restored into the same matter while the new one is there.
- **Moving a copy within its matter** is never refused, nor is saving a new version of the same message. A new
  version that is another message counts as that message from then on, so it's refused when the matter holds that
  message already. One that is no message counts as none.
- **A message with no Message-ID** is never treated as a copy of another.
- **Only an email counts.** A document that isn't an `.eml` or `.msg` file is never a copy of a message, even when
  its Message-ID property was filled in by hand.

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
