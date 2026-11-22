---
title: Errors
weight: 1
---

**Errors**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/errors`, lists the
errors the server has logged: everything logged at error level or above, by Healdan or by LogicalDOC. Each kind of
fault is one row, however often it happened, and Healdan keeps them in its database as they happen, so they're still
there after a restart, and after the log file they were written to has rolled over.

Healdan catches them from the moment its own start has run: an error logged before that, as the server starts, is
only in the [Server Log](../server-log/). In a storm of errors, with more than a thousand waiting to be written at
once, Healdan leaves the rest out rather than slow the server down, so a count here can be lower than the log's.

## What Counts as One Fault

The same error, from the same place in the code, is one fault, whatever numbers and quoted names its message holds,
such as which document or which file it was about. So an error logged for fifty documents is one row that happened
fifty times, not fifty rows. An error that one of LogicalDOC's scheduled tasks logs is a fault of that task's own,
apart from the same error in another task.

A refused sign-in isn't a fault, and isn't listed: a sign-in refused because its password has to be changed first,
say, or because the account is disabled, is a refusal, not a fault.

## The List

The list has the faults last seen in its window, the one last seen first, 100 at a time, the next coming as you
reach the end. Beside its title it says how many errors and how many kinds of fault the filters keep, such as
"14 Errors of 4 Kinds".

| Column | What it shows |
|---|---|
| What Happened | the fault's latest message, on one line, with **Resolved** after it once someone has marked it resolved |
| Area | the part of the server it came from, by the code that logged it (below) |
| Times | how many times it happened in the window |
| First Seen | when it first happened |
| Last Seen | when it last happened |

The buttons on the right of the title's line set the window: **1 Hour**, **24 Hours**, which the list starts on,
**7 Days** or **30 Days**. Times, and the count beside the title, go by whole hours of the clock, in UTC, from the start
of the hour the window begins in: so 1 Hour's count covers the current hour and the whole hour before it, and can
include errors up to two hours old.

The filters above the list all apply together:

- **Message or Logger** keeps the faults whose latest message, or the name of the logger it was logged through, holds
  what you type, in any case. It compares the message as Healdan keeps it, before a URL's user name and password are
  taken out, where the Server Log's search finds what shows. The list follows once you pause typing.
- **Area** keeps one area's faults: **Previews**, **Storage**, **Search Index** (reading documents' text included),
  **Email**, **Sign-In**, **Background Jobs**, or **Server** for anything else. **Every Area** keeps them all.
- **Unresolved Only** keeps the faults nobody has marked resolved since they last happened.
- **Clear Filters**, there while a filter is set, takes them all off.

With no faults in the window, the list says so, as in "No errors in the last 24 hours.", and with none that the
filters keep, "No errors match these filters."

The page's address keeps the window, the filters and the fault that's open, so a reload or a copied link shows the
same. The list and the open fault are asked for again each time you come back to the window. If Healdan can't be read
then, the page keeps what it showed, with "Couldn't refresh this, so it shows what was read last." and **Try Again**.

## A Fault's Details

Select a fault, with a click or the arrow keys, to open its details below the list, under **Error Details**. Enter, or
a double-click, opens them and takes you into them, and the × (**Close Details**) closes them and puts you back on the
fault's row. They show:

- **Level**, **Error** or **Fatal**, as it was first logged; **Area**; **Logger**, the logger it was first logged
  through; **Seen**, how many times it has happened in all, such as "31 times in all"; **First Seen** and
  **Last Seen**; and **Status**, **Unresolved**, or who resolved it and when, such as
  "Resolved by sokafor, 22 Nov 2026, 10:41".
- **Documents Named:** the documents its latest message names, each by its doc # and file name, such as
  "# 10412 Witness Statement.docx"; by its file name and the id the message gives, such as
  "Board Minutes.xlsx (id 5013)", for one with no doc #; or as "Id 4999, no longer there" for one Healdan no longer
  has. It says "None" when the message names none.
- **Latest Message** and **Stack**, each as it was logged, or "It was logged with no stack."

Under them are:

- **Mark Resolved**, which becomes **Reopen** once the fault is resolved (see [Resolving a Fault](#resolving-a-fault));
- **Show in Server Log** (see [Show in Server Log](#show-in-server-log));
- **Copy Details**, which copies the message, the facts, the documents named and the stack as plain text, to paste
  into an issue or a message, and says "Copied the error's details". A message can name documents, folders or people,
  so read what you paste before you post it anywhere. Where the browser gives Healdan no clipboard, it isn't there, and
  where the browser refuses the copy, the page says so: select the details and copy them yourself.

A fault the server has deleted since, as it deletes a resolved fault that hasn't happened for 90 days, says so in
place of its details: "This error isn't there any more: the server deletes a resolved error once it hasn't been seen
for 90 days." So does one that **Mark Resolved** or **Reopen** finds deleted meanwhile, and the focus the button had
goes to the details.

## Resolving a Fault

**Mark Resolved** marks the fault resolved, by you, now. It changes nothing on the server but that mark: use it once
you've dealt with the cause, or found the fault harmless. The button becomes **Reopen**, the fault's row shows
**Resolved**, and, with **Unresolved Only** ticked, it leaves the list at once. The Dashboard's Needs Attention, the
left pane's count and, for an error sending email, the Dashboard's **Outgoing Email** tile follow at once: the server
counts the change the next time they're read, which the Errors screen asks for straight away (see
[Counts in the Left Pane](../#counts-in-the-left-pane)).

A resolved fault that happens again is unresolved again, of itself, so nothing that recurs stays hidden. **Reopen**
marks a resolved fault unresolved again, and forgets who resolved it and when.

Resolving and reopening are recorded in the [Audit Trail](../audit-trail/), with who did it and when, as "Marked an
error resolved" and "Marked an error unresolved", the fault's message, as this screen shows it, in the row's Detail.
Resolving a fault that's resolved already, or reopening one that isn't, changes and records nothing.

## Show in Server Log

**Show in Server Log** opens the [Server Log](../server-log/) on the log the fault was written to, searching it for
the first line of the fault's latest message, up to 100 characters, so you can read what happened around it. That's the
main log, unless the code that logged it writes to a log of its own, as a LogicalDOC task such as the Indexer does. It
isn't offered for a fault whose lines are in none of the logs the Server Log lists.

## How Long Faults Are Kept

- **The count of each hour** is kept for 31 days, so the 30 Days window is always whole.
- **A resolved fault** that hasn't happened for 90 days is deleted. Should it happen again, it starts afresh.
- **An unresolved fault** is kept, however old. One last seen more than 30 days ago is in no window, so it isn't
  listed, but it comes back, with when it was first seen and how many times it has happened in all, if it happens
  again.

## A User Name and Password in a URL

Where a message or a stack holds a URL written plainly with a user name, or a user name and password, before its
host, as in `https://user:password@host`, the Errors screen shows it without them, as `https://host`, and so too a URL
that follows another's host directly, as in a list of hosts joined by commas. So do the
[Server Log](../server-log/)'s lines and the errors [Background Jobs](../background-jobs/) shows. What isn't recognised
shows as it was logged:

- a password that itself holds a `/`, `?`, `#`, a space, a quote or an angle bracket, written as it is;
- a URL escaped, as in JSON, or percent-encoded;
- a URL cut short before its `@`, as a message is when it's longer than Healdan keeps.

So take what these screens show as holding what the server logged. Anything else in a message shows as it was logged
too, and so does an email address, which isn't a URL.

The log files themselves aren't changed: the Server Log's **Download** has them as the server wrote them.
