---
title: Errors
weight: 1
---

**Errors**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/errors`, lists the
errors the server has logged: everything logged at error level or above, by Healdan or by LogicalDOC. Each kind of
fault is one row, however often it happened, and Healdan keeps them in its database as they happen, so they're still
there after a restart, and after the log file they were written to has rolled over.

<!-- P5 docs pass 2 -->

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

The list has the faults last seen in its window, the latest first, with how many errors and how many kinds of fault
there are under its title, such as "14 errors of 4 kinds".

| Column | What it shows |
|---|---|
| What Happened | the fault's latest message |
| Area | the part of the server it came from, by the code that logged it (below) |
| Times | how many times it happened in the window |
| First Seen | when it first happened, in the window or before |
| Last Seen | when it last happened |

<!-- P5 docs pass 2 -->

The window is **1 Hour**, **24 Hours**, **7 Days** or **30 Days**. Times, and the count under the title, go by whole
hours of the clock, in UTC, from the start of the hour the window begins in: so 1 Hour's count covers the current hour
and the whole hour before it, and can include errors up to two hours old.

<!-- P5 docs pass 2 -->

The filters above the list all apply together:

- **The text filter** keeps the faults whose latest message, or the name of the logger it was logged through, holds
  what you type, in any case. It compares the message as Healdan keeps it, before a URL's user name and password are
  taken out, where the Server Log's search finds what shows. The list follows once you pause typing.
- **Area** keeps one area's faults: **Previews**, **Storage**, **Search Index** (reading documents' text included),
  **Email**, **Sign-In**, **Background Jobs**, or **Server** for anything else.
- **Unresolved** keeps the faults nobody has marked resolved since they last happened.

<!-- P5 docs pass 2 -->

The page's address keeps the window, the filters and the fault that's open, so a reload or a copied link shows the
same.

## A Fault's Details

Select a fault to open its details, below the list: its latest message, its stack trace, the documents its latest
message names, by doc # and file name where Healdan still has them, the level it was first logged at and the logger
it came through, how many times it has happened in all, and, once someone has resolved it, who and when. Under them
are:

- **Mark Resolved**, or **Reopen** for a fault that's resolved (see [Resolving a Fault](#resolving-a-fault));
- **Show in Server Log** (see [Show in Server Log](#show-in-server-log));
- **Copy Details**, which copies the message, the stack and the facts as plain text, to paste into an issue or a
  message. A message can name documents, folders or people, so read what you paste before you post it anywhere.

<!-- P5 docs pass 2 -->

## Resolving a Fault

**Mark Resolved** marks the fault resolved, by you, now. It changes nothing on the server but that mark: use it once
you've dealt with the cause, or found the fault harmless. It leaves the **Unresolved** list at once, and the Dashboard's
Needs Attention, the left pane's count and, for an error sending email, the Dashboard's **Outgoing Email** tile within a
minute or two, as the Dashboard's figures follow.

A resolved fault that happens again is unresolved again, of itself, so nothing that recurs stays hidden. **Reopen**
marks a resolved fault unresolved again, and forgets who resolved it and when.

<!-- P5 docs pass 2 -->

Resolving and reopening are recorded in the [Audit Trail](../audit-trail/), with who did it and when, named by the
fault's message. Resolving a fault that's resolved already, or reopening one that isn't, changes and records nothing.

## Show in Server Log

**Show in Server Log** opens the [Server Log](../server-log/) on the log the fault was written to, searching it for the
fault, so you can read what happened around it. That's the main log, unless the code that logged it writes to a log of
its own, as a LogicalDOC task such as the Indexer does. It isn't offered for a fault whose lines are in none of the logs
the Server Log lists.

<!-- P5 docs pass 2 -->

## How Long Faults Are Kept

- **The count of each hour** is kept for 31 days, so the 30 Days window is always whole.
- **A resolved fault** that hasn't happened for 90 days is deleted. Should it happen again, it starts afresh.
- **An unresolved fault** is kept, however old. One last seen more than 30 days ago is in no window, so it isn't
  listed, but it comes back, with when it was first seen and how many times it has happened in all, if it happens
  again.

<!-- P5 docs pass 2 -->

## A User Name and Password in a URL

Where a message or a stack holds a URL with a user name, or a user name and password, written before its host, as in
`https://user:password@host`, the Errors screen shows it without them, as `https://host`. So do the
[Server Log](../server-log/)'s lines and the errors [Background Jobs](../background-jobs/) shows. Only what's written
plainly as such a URL is recognised: a password with a character such as `/`, `?` or `#` in it, or a URL written some
other way, isn't, and shows as it was logged. Anything else in a message shows as it was logged too, and so does an
email address, which isn't a URL.

The log files themselves aren't changed: the Server Log's **Download** has them as the server wrote them.

<!-- P5 docs pass 2 -->
