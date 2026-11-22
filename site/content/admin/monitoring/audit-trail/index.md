---
title: Audit Trail
weight: 3
---

**Audit Trail**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/audit`, lists
what was done in Healdan, newest first, in one list: sign-ins, what was done to documents, folders and accounts, and
the administration record, which holds every change made through the Admin app, with what it changed. Only
administrators see it, and reading or exporting it isn't recorded.

<!-- P5 docs pass 2 -->

## The List

| Column | What it shows |
|---|---|
| Time | when it was done |
| Who | who did it, by name, as the Users list names them now |
| Kind | **Sign-In**, **Document**, **Folder**, **User** or **Administration** (see [The Kinds](#the-kinds)) |
| What | what was done, in words |
| Detail | more: a document's doc #, file name and version; a folder's path; a sign-in's client; what a change was made to |
| From | the network address it came from |

<!-- P5 docs pass 2 -->

- **Who** is the person's name as it is now, so someone who has been renamed shows under their new name. Where no
  account has the row's user any more, it's the username the row kept.
- **From** is empty for a row that kept no address: a client or matter made or edited outside the Admin app, as in the
  Classic Interface, and a recovery key shown or entered on the Classic Interface's Storage tab. For a row made through
  a device signed in to Healdan's API, such as Healdan Helper, it's the address the device signed in from.
- **An administration row** shows each field it changed, with its value before and after. A secret, such as an S3
  access key or secret, shows as changed, with no value: Healdan never records a secret's value, nor a recovery key.

More rows load as you scroll down, and with **Show More**, and the end of the list says so. There's no count of the
rows: the list reads back as far as you go.

<!-- P5 docs pass 2 -->

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

<!-- P5 docs pass 2 -->

See [What Isn't Recorded](#what-isnt-recorded) for what the administration record leaves out.

## Filters

The filters above the list all apply together:

- **Kind** keeps the rows of the kinds you choose. Every kind shows to begin with.
- **A person,** found as you type among the people the Users list has, keeps the rows they did and the rows about them:
  their account's own history, their sign-ins included, and the administration rows aimed at them, such as their
  groups changed. For the rows of an account the Users list doesn't have, such as a deleted one, type the username it
  had in **Doc #, name or address** instead.
- **A matter,** found as you type by its number, name or client, keeps its rows (see
  [A Matter's Rows](#a-matters-rows)).
- **The dates,** from and to, keep the rows of those days, each day starting at midnight in your browser's time zone.
- **Doc #, name or address:** a number alone keeps the rows of the document with that doc #; anything else keeps the
  rows whose file name, folder path, the name of what a change was made to, address, or username as the row kept it,
  holds what you type, in any case. It's the username, not the person's name: choose a person for that. The list
  follows once you pause typing.

<!-- P5 docs pass 2 -->

The page's address keeps the filters, so a reload or a copied link shows the same list. With the dates set, a row
whose time is more than an hour out of step with the rows written around it, as after the server's clock was put back,
may be left out.

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

<!-- P5 docs pass 2 -->

A view is recorded once per person, per document, per half hour, so going through a list twice with the arrow keys, or
reloading the page, doesn't fill the trail. Healdan remembers the half hour in the server's memory, so a restart can
record one more. A preview still being made records nothing until it's shown, a refusal records nothing, and a view
that fails to be recorded never stops the document being read.

Views make a server's history grow faster, and nothing trims it.

## Export CSV

**Export CSV** saves the rows the filters keep as a CSV file, newest first, named after the day, in your time zone,
such as `audit-trail-2026-11-22.csv`. It's UTF-8, marked so that a spreadsheet reads it as UTF-8, with these
columns:

<!-- P5 docs pass 2 -->

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

## How Long Rows Are Kept

Rows are kept for good: nothing deletes them. A document destroyed for good, by the Trash's nightly job or
**Permanently Delete**, takes its own Document rows with it, and its folder's history keeps a Folder row of the
destroy. The administration rows stay, and so does
[the Trash's record](../../the-trash-and-removed-items/#the-record-that-outlives-an-item) of it.

<!-- P5 docs pass 2 -->

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
person alone, as the matter or person filter keeps it, without **Export CSV**. To export it, open the Audit Trail with
that filter. See [A Matter's Page](../../clients-and-matters/#a-matters-page) and
[A Person's Page](../../users-and-groups/#a-persons-page).

<!-- P5 docs pass 2 -->

## In the Classic Interface

The Classic Interface's **Last Changes** report, under [Reports](../../system/reports/), lists what was done to
documents and folders, which the Audit Trail lists too, with the rest.
