---
title: Audit Trail
weight: 3
---

**Audit Trail**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/audit`, lists
what was done in Healdan, newest first, in one list: sign-ins, what was done to documents, folders and accounts, and
the administration record, which holds every change made through the Admin app, with what it changed. Only
administrators see it, and reading or exporting it isn't recorded.

## The List

| Column | What it shows |
|---|---|
| Time | when it was done |
| Who | who did it, by name, as the Users list names them now |
| Kind | **Sign-In**, **Document**, **Folder**, **User** or **Administration** (see [The Kinds](#the-kinds)) |
| What | what was done, in words |
| Detail | more: a document's doc #, file name and version; a folder's path; a sign-in's client; what a change was made to |
| From | the network address it came from |

The Time column gives the date and the time to the minute.

- **Who** is the person's name as it is now, so someone who has been renamed shows under their new name. Where no
  account has the row's user any more, it's the username the row kept.
- **From** is empty for a row that kept no address: a client or matter made or edited outside the Admin app, as in the
  Classic Interface, and a recovery key shown or entered on the Classic Interface's Storage tab. For a row made through
  a device signed in to Healdan's API, such as Healdan Helper, it's the address the device signed in from.
- **An administration row's** Detail goes on to each field it changed, with its value before and after, such as
  `name: Wasabi → Wasabi EU`. A secret, such as an S3 access key or secret, shows as changed, with no value, as in
  `secret: changed`: Healdan never records a secret's value, nor a recovery key.

### Reading Further Back

The list comes 100 rows at a time, the next as you reach the end of the list, or with **Show More** under it. There's
no count of the rows: the list reads back as far as you go, and at the end it says "No older rows."

Some filters find old rows straight away, and the rest go back a stretch at a time:

- **Straight away, however old:** a doc #'s rows; a person's, but for what they did to folders; and a matter's
  folder and administration rows.
- **A stretch at a time:** the rest, such as the words or the dates alone, and a matter's document rows. Each read
  looks through only so much, so a page can hold few rows, or none, while there's more to read. The list then reads on
  by itself a few times, and then waits for **Show More**. While it has found nothing, it says "Nothing found yet in
  what has been read. Show More reads further back."

### A Row's Details

Select a row, with a click or the arrow keys, to see it whole in a box under the list: **When**, to the second;
**Who**, **Kind**, **What**, **Detail** and **From**, each "Not recorded" where the row kept none; an administration
row's **Changes**, a line each; and **Open Document in Healdan**, or **Open Folder in Healdan**, for a row that names a
document or a folder, which opens it in the User app.

## The Kinds

- **Sign-In:** an account's sign-ins, sign-outs, sessions that timed out and failed sign-ins, from LogicalDOC's history
  of accounts. The Detail names the client that signed in.
- **Document:** what was done to a document, such as filing it, saving a new version, downloading it or reading it
  (see [What Counts as Looking at a Document](#what-counts-as-looking-at-a-document)), from LogicalDOC's history of
  documents.
- **Folder:** what was done to a folder, from LogicalDOC's history of folders.
- **User:** the rest of an account's history, such as a new password.
- **Administration:** Healdan's administration record: each change made through the Admin app, and each client and
  matter made or edited elsewhere, as in the Classic Interface, with the fields it changed; and each time a storage
  backend's recovery key was shown or entered. One shown or entered in the Admin app is one row, the administration
  record's; one shown or entered on the Classic Interface's Storage tab is listed from that tab's own record.

See [What Isn't Recorded](#what-isnt-recorded) for what the administration record leaves out.

## Filters

The filters above the list all apply together:

- **Kind** keeps the rows of the kinds you tick: **Sign-In**, **Document**, **Folder**, **User** and
  **Administration**. With none ticked, as it starts, every kind shows.
- **Person**, found as you type by name, username or email among the people the Users list has, a disabled account
  marked, keeps the rows they did and the rows about them: their account's own history, their sign-ins included, and
  the administration rows aimed at them, such as their groups changed. For the rows of an account the Users list
  doesn't have, such as a deleted one, type the username it had in **Doc #, Name or Address** instead.
- **Matter**, under your install's word for a matter, found as you type by its number, its name or its client's name,
  keeps its rows (see [A Matter's Rows](#a-matters-rows)).
- **From Date** and **To Date** keep the rows of those days, each day starting at midnight in your browser's time
  zone.
- **Doc #, Name or Address:** a number alone, or a number after `#`, as in `# 10388`, keeps the rows of the document
  with that doc #. Anything else keeps the rows whose file name, folder path, name of what a change was made to,
  address, or username as the row kept it, holds what you type, in any case. It's the username, not the person's
  name: choose a **Person** for that. The list follows once you pause typing.
- **Clear Filters**, there while a filter is set, takes them all off.

The page's address keeps the filters, so a reload or a copied link shows the same list. A link that names a person or
a matter Healdan can't name any more, such as a deleted account, shows them by number, as "Person 7", with the filter
still set. With the dates set, a row whose time is more than an hour out of step with the rows written around it, as
after the server's clock was put back, may be left out.

The list is asked for again each time you come back to the window. If Healdan can't be read then, the list keeps what
it showed, with "Couldn't refresh this, so it shows what was read last." and **Try Again**.

### A Matter's Rows

A matter keeps the document and folder rows of its own folder and of every folder in it, those in the Trash included,
and the administration rows aimed at the matter itself, or at a folder or a search folder in it. Sign-in, user and
storage rows belong to no matter.

A row stays with the folder it was made in. So a document that has left the matter keeps, in the matter, the rows it
made while it was there, and its rows from before it came into the matter stay with where it was.

## What Counts as Looking at a Document

Reading a document is recorded, as a Document row. Checking that it's the right one isn't. In the User app:

| Recorded | How |
|---|---|
| Quick Look and the reading pane, where you can turn the pages | a view, as the preview is shown |
| Reading a filed email, or previewing one of its attachments | a view of the email |
| Downloading | a download, as before |
| Opening in Word, Excel, LibreOffice or CoffeePDF | Healdan Helper's check-out, and its download of the file |
| Printing | the User app has no Print command, so printing follows a preview or a download, which is recorded |

**Not recorded:** the thumbnail, a document's properties, the list of its versions, and its row in a list.

A view is recorded once per person, per document, per half hour, so going through a list twice with the arrow keys, or
reloading the page, doesn't fill the trail. Healdan remembers the half hour in the server's memory, so a restart can
record one more. A preview still being made records nothing until it's shown, a refusal records nothing, and a view
that fails to be recorded never stops the document being read.

Views make a server's history grow faster, and nothing trims it.

## Export CSV

**Export CSV**, beside the title, saves the rows the filters keep as a CSV file, newest first, named after the day,
in your time zone, such as `audit-trail-2026-11-22.csv`. It's UTF-8, marked so that a spreadsheet reads it as UTF-8,
with these columns:

| Column | What it holds |
|---|---|
| Time | when, in UTC, in ISO 8601, such as `2026-11-22T10:44:07.120Z` |
| Who, What, Detail, From | as the list shows them |
| Kind | Sign-In, Document, Folder, User or Administration |
| Changes | an administration row's changed fields, each on a line of its own, as `name: before → after` |

In **Changes**, a value that was none is `(none)`, and a secret is `name: changed`, with no value. A cell that would
start with `=`, `+`, `-`, `@`, a tab or a carriage return starts with an apostrophe (`'`) before it, so a spreadsheet
doesn't read it as a formula.

An export stops at 100,000 rows: the newest 100,000 come, and then a last line that says "Stopped at 100,000 rows:
narrow the filters to export the rest." Narrow the dates, say, and export again.

Export CSV is a plain download, so should Healdan refuse it, as when your session has ended, the page says nothing:
the browser's downloads show it, as a download that failed or as a small file holding Healdan's answer.

## How Long Rows Are Kept

Rows are kept for good: nothing deletes them. A document destroyed for good, by the Trash's nightly job or
**Permanently Delete**, takes its own Document rows with it, and its folder's history keeps a Folder row of the
destroy. The administration rows stay, and so does
[the Trash's record](../../the-trash-and-removed-items/#the-record-that-outlives-an-item) of it.

## What Isn't Recorded

The administration record holds every change made through the Admin app, and the clients and matters made or edited
elsewhere, as in the Classic Interface. Other changes made in the Classic Interface aren't in the administration record,
such as those on Healdan Settings, with its Sign-In and Storage tabs, and on LogicalDOC's own screens, such as its
users, groups and settings. Make a change in the Admin app to have it recorded there. The history rows of documents,
folders and accounts are listed whichever screen made them. A recovery key shown or entered on the Storage tab is listed
all the same, from that tab's own record.

A change that changes nothing, such as saving someone's groups as they were, isn't recorded, and neither is anything
that only reads in the Admin app, such as opening a screen, downloading a log or exporting this list.

## Activity Tabs

A matter's page and a person's page each have an **Activity** tab: the Audit Trail's list for that matter or that
person alone, as the **Matter** or **Person** filter keeps it, newest first. Its one filter is **Kind**, which for a
matter offers **Document**, **Folder** and **Administration**, as a matter has no sign-in or user rows, and it has no
**Export CSV**. **Open in the Audit Trail** opens this screen with the matter or the person, and the kinds you ticked,
as its filters, for the rest of them and the export. See [A Matter's Page](../../clients-and-matters/#a-matters-page)
and [A Person's Page](../../users-and-groups/#a-persons-page).

## In the Classic Interface

The Classic Interface's **Last Changes** report, under [Reports](../../system/reports/), lists what was done to
documents and folders, which the Audit Trail lists too, with the rest.
