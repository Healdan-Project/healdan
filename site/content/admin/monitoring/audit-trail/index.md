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
| Who | who did it, by name, as the Users list names them now: for a User row, who made the change |
| Kind | **Sign-In**, **Document**, **Folder**, **User** or **Administration** (see [The Kinds](#the-kinds)) |
| What | what was done, in words |
| Detail | more: a document's doc #, file name and version; a folder's path; a sign-in's client; what a change was made to |
| From | the network address it came from |

The Time column gives the date and the time to the minute.

- **Who** is the person's name as it is now, so someone who has been renamed shows under their new name. Where no
  account has the row's user any more, it's the person as the row kept them: their name at the time in LogicalDOC's
  history, and their username in Healdan's records.
- **A User row,** a change to an account, names under Who the person who made the change, where the row says who: a
  change made in the Admin app does, as in Who "Sam Okafor" and Detail "Jamie Clarke (jclarke): By sokafor in the
  Admin app". Where a row doesn't name who made the change, its Who is "Not recorded". Its Detail names the account
  the change was made to, and, for an account created, changed, disabled, enabled or deleted, a password changed, an
  API key made, a sign-in as another account, a change of who can sign in as the account, or a legal notice confirmed,
  the note LogicalDOC kept with it, cut to 300 characters. Its What is true whoever made the change, such as "Account
  disabled" or "Password changed".
- **A failed sign-in for a username no account has** names nobody under Who, and its Detail says "Unknown
  username": what was typed isn't shown, exported or searched, as it may be a password typed into the wrong box.
- **From** is empty for a row that kept no address: a client or matter made or edited outside the Admin app, as in the
  Classic Interface, and a recovery key shown or entered on the Classic Interface's Storage tab. For a row made through
  a device signed in to Healdan's API, such as Healdan Helper, it's the address the device signed in from.
- **An administration row's** Detail goes on to each field it changed, with its value before and after, such as
  `name: Wasabi → Wasabi EU`. A secret, such as an S3 access key or secret, shows as changed, with no value, as in
  `secret: changed`: the administration record never holds a secret's value, nor a recovery key.

### Reading Further Back

The list comes 100 rows at a time, the next as you reach the end of the list, or with **Show More** under it. There's
no count of the rows: the list reads back as far as you go, and at the end it says "No older rows." While there's more
to read, "Read back to" beside **Show More** says how far back the rows shown reach, as in "Read back to 3 Nov 2026,
09:12".

How far each read goes depends on the filters:

- **A doc #'s rows** come straight away, however old.
- **A person's or a matter's rows** are found through the database's indexes, wherever they lie, but for a person's
  Folder rows and a matter's Document rows, which no index serves. Those are read back a stretch at a time, and the
  list keeps to the order of time, so the rest of the person's or the matter's rows older than that stretch wait for
  the next read. To find someone's old rows straight away, tick the kinds you want without **Folder**, such as
  **Document** alone; for a matter's, tick **Folder** and **Administration**.
- **The rest,** such as the words or the dates alone, are read back a stretch at a time.

Each stretch looks through only so much, so a page can hold few rows, or none, while there's more to read. The list
then reads on by itself a few times, and then waits for **Show More**. While it has found nothing, it says how far
back it has looked, as in "Nothing found back to 3 Nov 2026, 09:12. Show More reads further back."

The list keeps its place in each of the records it reads, so a row written while you read further back doesn't
repeat or move what's shown: it's at the top the next time the list starts from the newest.

### A Row's Details

Select a row, with a click or the arrow keys, to see it whole in a box under the list: **When**, to the second;
**Who**, **Kind**, **What**, **Detail** and **From**, of which Who, Detail and From say "Not recorded" where the row
kept none; an administration row's **Changes**, a line each; and **Open Document in Healdan**, or **Open Folder in
Healdan**, for a row that names a document or a folder, which opens it in the User app.

## The Kinds

- **Sign-In:** an account's sign-ins, sign-outs, sessions that timed out and failed sign-ins, from LogicalDOC's history
  of accounts. The Detail names the client that signed in, or says "Unknown username" for a failed sign-in for a
  username no account has.
- **Document:** what was done to a document, such as filing it, saving a new version, downloading it or reading it
  (see [What Counts as Looking at a Document](#what-counts-as-looking-at-a-document)), from LogicalDOC's history of
  documents.
- **Folder:** what was done to a folder, from LogicalDOC's history of folders.
- **User:** the rest of an account's history, such as a new password, under the name of who made the change (see
  [The List](#the-list)).
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
  zone. A To Date before the From Date says so under it: "The To Date is before the From Date, so no row can match."
- **Doc #, Name or Address:** a number alone, or a number after `#`, as in `# 10388`, keeps the rows of the document
  with that doc #. Anything else keeps the rows whose file name, folder path, name of what a change was made to or
  address holds what you type, in any case, or whose person, as the row kept them, does: their username, and, in
  LogicalDOC's history, their name at the time. It isn't compared with a person's name now, which a **Person** finds,
  nor with what was typed at a failed sign-in for a username no account has. The list follows once you pause typing.
- **Clear Filters**, there while a filter is set, takes them all off.

The page's address keeps the filters, so a reload or a copied link shows the same list. A link that names a person or
a matter Healdan can't name any more, such as a deleted account, shows them by number, as "Person 7", with the filter
still set, so a deleted account's rows still show. With the dates set, a row whose time is more than an hour out of
step with the rows written around it, as after the server's clock was put back, may be left out.

The list is asked for again each time it's shown, each time you come back to the window, and after each change you
make in the Admin app, so your own changes are there when you look; **Refresh**, at the top, asks for it again at once.
If Healdan can't be read then, the list keeps what it showed, with "Couldn't refresh this, so it shows what was read
last." and **Try Again**.

### A Matter's Rows

A matter keeps the document and folder rows of its own folder and of every folder in it, those in the Trash included,
and the administration rows aimed at the matter itself, or at a folder or a search folder in it. Sign-in, user and
storage rows belong to no matter.

A row stays with the folder it was made in. So a document that has left the matter keeps, in the matter, the rows it
made while it was there, and its rows from before it came into the matter stay with where it was.

## What Counts as Looking at a Document

Reading a document is recorded, as a Document row, "Viewed". Checking that it's the right one isn't. In the User
app:

| Recorded | How |
|---|---|
| Quick Look and the reading pane, where you can turn the pages | a view, as the preview is shown; the reading pane shows a row once it has stayed selected for a moment, so a row the arrow keys pass over isn't viewed |
| Reading a filed email, or previewing one of its attachments | a view of the email |
| Downloading | a download, as before |
| Opening in Word, Excel, LibreOffice or CoffeePDF | Healdan Helper's check-out, and its download of the file |
| Printing | the User app has no Print command, so printing follows a preview or a download, which is recorded |

**Not recorded:** the thumbnail, a document's properties, the list of its versions, and its row in a list.

A view is recorded once per person, per document, per half hour, so going through a list twice with the arrow keys, or
reloading the page, doesn't fill the trail. Healdan remembers the half hour in the server's memory, so a restart can
record one more. A preview still being made records nothing until it's shown, a refusal records nothing, and a view
that fails to be recorded never stops the document being read. A view's **From** is the address the person's session
signed in from, so a device's views carry where the device signed in.

A preview in the Classic Interface is recorded as it always was, and its rows show as "Viewed" too. Views make a
server's history grow faster, and nothing trims it.

## Export CSV

**Export CSV**, at the top beside **Refresh**, saves the rows the filters keep as a CSV file, newest first, named
after the day, in your time zone, such as `audit-trail-2026-11-22.csv`. It's UTF-8, marked so that a spreadsheet reads
it as UTF-8, with these columns, in this order:

| Column | What it holds |
|---|---|
| Time | when, in UTC, in ISO 8601, such as `2026-11-22T10:44:07.120Z` |
| Who | as a row's details show it, but empty where they say "Not recorded" |
| Kind | Sign-In, Document, Folder, User or Administration |
| What, Detail, From | as a row's details show them, Detail and From empty where they say "Not recorded" |
| Changes | an administration row's changed fields, each on a line of its own, as `name: before → after` |

In **Changes**, a value that was none is `(none)`, and a secret is `name: changed`, with no value. A cell that would
start with `=`, `+`, `-`, `@`, a tab or a carriage return starts with an apostrophe (`'`) before it, so a spreadsheet
doesn't read it as a formula.

An export stops at 100,000 rows: the newest 100,000 come, and then a last line that says "Stopped at 100,000 rows:
narrow the filters to export the rest." Narrow the dates, say, and export again.

Export CSV first asks Healdan whether it would answer these filters, and only then downloads the file, so a refusal
is said on the page, under the buttons, as is one Healdan answers in the file's place; should your session have ended,
or you no longer be an administrator, the Admin app says so in place of the screen. Firefox doesn't tell the page that
Healdan answered in the file's place, so there nothing is saved and the page says nothing of it: try again, or reload
the page, which asks you to sign in if you've been signed out. The file's column names come at once, and its rows as
Healdan reads them. One that can't finish ends with a last line that says so: "Stopped before the end: the server
couldn't read the rest. Export again.", or, should you be taken out of the `admin` group while it's being read,
"Stopped before the end: only an administrator can export the Audit Trail."

## How Long Rows Are Kept

Rows are kept for good: nothing deletes them. A document destroyed for good, by the Trash's nightly job or
**Permanently Delete**, takes its own Document rows with it, and its folder's history keeps a Folder row of the
destroy. The administration rows stay, and so does
[the Trash's record](../../the-trash-and-removed-items/#the-record-that-outlives-an-item) of it.

## What Isn't Recorded

The administration record holds the changes made through the Admin app, and clients and matters wherever they're
made. For any other change to be in it, make it in the Admin app. The history rows of documents, folders and accounts
are listed whichever screen made them, and a recovery key shown or entered on the Classic Interface's Storage tab is
listed from that tab's own record.

A row made in a session opened as another account, by an
[allowed impersonator](../../../user/getting-started/your-profile-and-password/#allowed-impersonators), names the
account acted as, under Who, and nothing of who signed in.

A change that changes nothing, such as saving someone's groups as they were, isn't recorded, and neither is anything
that only reads in the Admin app, such as opening a screen, downloading a log or exporting this list.

## Activity Tabs

A matter's page and a person's page each have an **Activity** tab: the Audit Trail's list for that matter or that
person alone, as the **Matter** or **Person** filter keeps it, newest first. Its filters are **Kind**, which for a
matter offers **Document**, **Folder** and **Administration**, as a matter has no sign-in or user rows, and **From
Date** and **To Date**; it has **Refresh** and no **Export CSV**. **Open in the Audit Trail** opens this screen with
the matter or the person, the kinds you ticked and the days, as its filters, for the rest of them and the export. A
tab reads back as the list does (see [Reading Further Back](#reading-further-back)), so on a large server a quiet
matter's or person's older rows can take a **Show More** or two. See [A Matter's
Page](../../clients-and-matters/#a-matters-page) and [A Person's Page](../../users-and-groups/#a-persons-page).

## In the Classic Interface

The Classic Interface's **Last Changes** report, under [Reports](../../system/reports/), lists what was done to
documents and folders, which the Audit Trail lists too, with the rest.
