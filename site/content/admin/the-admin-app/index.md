---
title: The Admin App
weight: -1
---

The Admin app is where administrators run Healdan, at `/admin/` on your Healdan address. So far it has the Dashboard:
what needs your attention, the server's health and how Healdan is used, on one page; the Clients and Matters lists,
where clients and matters are made, with a page for each client and matter (see
[Clients and Matters](../clients-and-matters/)); each matter's workspace, its folders and who can use them; the
workspace templates (see [Workspace Templates](../workspace-templates/)); the Stores, with a page for each (see
[Stores](../stores/)); the storage backends, with a page for each (see [Storage Backends](../storage-backends/)); and
the people who sign in and the groups they're in, with a page for each person and group (see
[Users and Groups](../users-and-groups/)); and the errors the server has logged, its log files, the Audit Trail of who
did what and the background jobs (see [Monitoring](../monitoring/)). Everything else is still done in the Classic
Interface, which the rest of this guide describes, and the Admin app links there.

{{< callout type="warning" >}}
An administrator can read every document: an account in the `admin` group skips every permission check. Give each
administrator an ordinary account for their everyday work, and keep one in `admin` for administration. See
[Users and Groups](../users-and-groups/#day-to-day-accounts-shouldnt-be-admins).
{{< /callout >}}

## Who Can Use It

Only members of the `admin` group can use it. Healdan asks at every page load and every request, so someone taken
out of the group loses the app at their next one.

- **Anyone else** who opens `/admin/` sees "You don't have access to Healdan Admin.", with **Open Healdan**, which
  takes them to the User app. The server sends them none of the Admin app's files.
- **A group given Administration screens** in the Classic Interface (see
  [Giving a Settings Screen to Others](../system/#giving-a-settings-screen-to-others)) doesn't get the Admin app. Its
  members keep using those screens there.
- **Signed out,** `/admin/` goes to the sign-in page, and back to the page you asked for once you've signed in.

## Getting There

- **From the User app:** open your menu, under your initials in the top bar, and choose **Admin**. It shows only for
  administrators, just above **Classic Interface**.
- **Straight there:** open `/admin/` on Healdan's address, such as `https://docs.example.org/admin/`, and bookmark it.

The Admin app uses the same sign-in as the User app and the Classic Interface, so you don't sign in again, and
signing out of any of them signs you out of all three. Signing in still takes administrators where **Start In** says,
as it does everyone: see [Healdan Settings](../healdan-settings/).

Like the User app, it needs a secure address: an `https://` address, or the machine Healdan runs on. At a plain
`http://` address from another machine it shows only "Healdan needs a secure address.", with a link to the Classic
Interface. See [Reverse Proxy](../../install/reverse-proxy/).

## Finding Your Way Around

### The Top Bar

- **Healdan's mark, Healdan and the Admin tag** take you back to the Dashboard.
- **Find a client, matter or person:** type two characters or more, or a client's number. It lists up to five clients,
  five matters and five people, each under its heading: clients by name or number; matters by name, or by number,
  such as `1001.1`, with a client's number listing that client's matters; and people by username or name, a disabled
  one marked **Disabled**. Each matter shows its client beside it. The arrow keys and Enter, or a click, choose one,
  and Enter alone chooses the first. A client opens [its page](../clients-and-matters/#a-clients-page), a matter
  [its page](../clients-and-matters/#a-matters-page) and a person [their page](../users-and-groups/#a-persons-page),
  here in the Admin app.
- **Open Healdan** opens the User app.
- **The ? (Help) button** opens a menu with **Admin Guide**, which opens this page, and **Open-Source Notices**; each
  opens in a new window.
- **Your menu,** under your initials, shows your name and has **Classic Interface** and **Sign Out**.

### The Left Pane

The left pane has what the Admin app has so far: **Dashboard**; under **Firm**, **Clients**, **Matters**, which
takes your install's word for matters, such as **Projects**, and **Workspace Templates**; under **People**, **Users**
and **Groups**; under **Storage**, **Stores** and **Backends**; under **Monitoring**, **Errors**, **Server Log**,
**Audit Trail** and **Background Jobs**; and at its foot **Classic Interface**, which opens the Classic Interface, in
the same window, for everything else. The entry for the screen you're on is marked, and so is a list's entry while one
of its pages is open, such as **Matters** for a matter's page.

<!-- P5 docs pass 2 -->

An entry shows a count where something there needs attention, as Needs Attention counts it (below): **Backends**, how
many storage backends can't be reached or are failing; **Errors**, how many unresolved errors the server logged in the
last 24 hours, each kind of fault counting once; and **Background Jobs**, how many jobs failed or are overdue. The
counts are asked for when the Admin app opens and each time you come back to its window, and every 30 seconds while the
Dashboard is showing.

<!-- P5 docs pass 2 -->

On a window narrower than 1,024 pixels, the left pane folds away behind the **☰** (Navigation) button in the top bar,
which opens it over the page. Esc or choosing a link closes it again. The Admin app is designed for a window 1,280
pixels wide or more, and works at a tablet's 768.

## The Dashboard

The Dashboard is the Admin app's first page, at `/admin/`. Beside its title, "Updated" and a time say when Healdan read
the oldest figure it shows, and **Refresh** asks again at once. It also asks again every 30 seconds while it's
showing, and each time you come back to its window, but not while its tab is hidden. Healdan keeps what it last read
for Needs Attention and Server Health for a minute, and the database's for five, then shows that while it reads
again, so a figure can be a minute or two old, and the database's up to ten, as "Updated" then says. A part that
Healdan has been reading for longer than that, as a folder on a share that stopped answering can make it, says it
couldn't be read, and the [Server Log](../monitoring/server-log/) names it.

Each of its three parts loads on its own. One that can't be loaded says so, with **Try Again**, and the others still
work.

### Needs Attention

Needs Attention comes first: what an administrator should act on, each with how many, a line that says more, such as
which Store or since when, and a link to where it's fixed. Stuck files link to **Stores**, where each Store's stuck
files are, or to **Backends** when only files with no Store are stuck, as those are listed there; a storage backend
that can't be reached, or a disk more than 85% full, links to **Backends** too (the disk to the Classic Interface on a
server that doesn't use storage backends). Unresolved errors link to **Errors**, and previews given up on and a
background job that failed or is overdue to **Background Jobs**. Documents that couldn't be indexed link to
**Classic Interface** until their place is in the Admin app. With nothing to report, it says "Nothing needs your
attention."

<!-- P5 docs pass 2 -->

| It says, for example | What it means |
|---|---|
| "3 files are stuck moving to storage" | files Healdan gave up moving to their Store's storage backend: see [Stuck Files and Retry](../storage-backends/waiting-and-stuck-files/#stuck-files-and-retry) |
| "1 storage backend can't be reached" | a storage backend Healdan can't reach now, or that's failing for another reason, so Healdan can't use it: see [When a Backend Fails](../storage-backends/when-a-backend-fails/) |
| "12 documents couldn't be indexed, so search won't find their text" | documents the [Indexer](../system/scheduled-tasks/) failed on at its last try, which it goes on trying: see [Search and Indexing](../system/search-and-indexing/) |
| "4 unresolved errors in the last 24 hours" | errors the server logged in the last 24 hours that nobody has resolved, an error logged again and again counting once. A refused sign-in, such as one with a password that has to be changed first or a disabled account's, isn't counted: it's a refusal, not a fault. See [Errors](../monitoring/errors/) |
| "2 previews were given up on" | Office previews Healdan gave up making after their last try: see [Office Previews](../office-previews/) |
| "1 background job failed when it last ran" | a background job that's switched on, Healdan's own work or one of LogicalDOC's scheduled tasks, whose last run failed: see [Background Jobs](../monitoring/background-jobs/). A scheduled task's run counts as failed when it logged an error while it ran, even one it went on from |
| "1 background job hasn't run when it should have" | a background job that's switched on and not running, whose next run is late by more than twice the time between its runs, and by an hour at least |
| "1 disk is more than 85% full" | the disk of the repository, the search index or the logs, more than 85% full |

When Healdan couldn't check something this time, or is still working it out, it names it, as in "Couldn't check
Stuck Files, Errors.", and never says that nothing needs your attention while it can't tell.

### Server Health

Ten tiles, each with its status in words and a line of detail. A tile opens the screen behind it: **Storage
Backends** and **Disk** open **Backends** (**Disk** the Classic Interface on a server that doesn't use storage
backends); **Server**, **Database** and **Memory** open **Server Log**; **Previews** and **Background Jobs** open
**Background Jobs**; and the others, for now, the Classic Interface. The status is:

<!-- P5 docs pass 2 -->

- **OK** when all is well;
- **Needs Attention** when something needs acting on;
- **Off** when what the tile shows isn't set up or is switched off, such as **Outgoing Email** with no mail server;
- **Unavailable**, in place of the status, when Healdan couldn't read what the tile shows, or is still working it
  out: the line under it says which, in place of the detail. The other tiles are still whole.

| Tile | What it shows |
|---|---|
| Server | how long the server has been up, and Healdan's version |
| Database | the database's product, its version and its size |
| Disk | the space used and the size of the repository's disk, and of the search index's and the logs' where they're on other disks, with a bar for the fullest. **Needs Attention** when one is more than 85% full |
| Memory | how much of the memory Java may take (its heap) is used, with a bar for how full |
| Sign-In | whether each [sign-in provider](../../install/sign-in/) people can sign in through answers, each asked at most every 5 minutes. **Off** with none |
| Storage Backends | how many [storage backends](../storage-backends/) can be reached, and any stuck files. **Off** on a server that doesn't use them |
| Search Index | how many documents wait to be indexed, and how many couldn't be |
| Previews | how many [previews](../office-previews/) wait to be made, and how many were given up on |
| Background Jobs | how many background jobs are on schedule, which failed or are overdue, and how many are switched off, such as the storage backends' jobs on a server that doesn't use them |
| Outgoing Email | whether [outgoing email](../system/outgoing-email/) is set up, and whether a send failed in the last 24 hours: an error the server logged sending that nobody has resolved. **Off** when it isn't set up |

### Usage

How Healdan is used:

- **Totals:** **Documents**, with how many were filed in the last 7 days; **Stored**, the size of the documents'
  files, with how much of it was filed in the last 7 days; and **Matters**, **Clients** and **People**, each with how
  many were added in the last 30 days. Under **People**, how many are signed in now. They count what's live: documents
  that aren't shortcuts or in the Trash, matters that aren't deleted or in the Trash, and people whose accounts are
  enabled, not system accounts such as `_system`. **Stored** counts the current version of each document's file, not
  older versions, previews or the Trash.
- **Documents Filed, Last 30 Days:** a bar for each day, today last. A screen reader reads the numbers as a table.
- **Storage by Store:** each Store but Default and Templates, the largest first: the size of its files, the storage
  backend they're on (and whether it's encrypted), how many documents it holds, and a bar for its share.
- **Busiest Matters This Week:** up to five matters most documents were filed in during the last 7 days, with how
  many.
  "Nothing was filed in the last 7 days." when there were none.

Healdan works these out in the background, when it starts and then 15 minutes after the last time, and "As of" and a
time say when. So they can be some minutes behind, and **Refresh** shows the latest Healdan has, not new figures. Only
the count of people signed in now is made each time the page asks. Days are yours: each starts at midnight in your
browser's time zone, as in the User app. The first time Healdan is asked for a time zone since it started, Usage
says it hasn't been worked out yet, and has it within the next refresh of the page.

Just after the server starts, Usage says "Usage hasn't been worked out since the server started. It shows here within
a few minutes." If Healdan couldn't work them out, it says so, and keeps the last figures it had, saying when they're
from. Why is in the [Server Log](../monitoring/server-log/).

## What's Still in the Classic Interface

Everything else, which the rest of this guide describes there: the permissions of documents, and of a Store beyond
who can see it; Healdan Settings with its Sign-In tab, where an account is unlinked from its sign-in provider; for
people, the Security settings under **Access Control**, making read-only accounts and deleting accounts (see
[In the Classic Interface](../users-and-groups/#in-the-classic-interface)); and the screens [System](../system/)
covers, such as search and indexing, outgoing email and reports. Its Scheduled Tasks, System Information's **Log** tab
and the Last Changes report still work there too, beside the Admin app's Background Jobs, Server Log and Audit Trail
(see [What's Still in the Classic Interface](../monitoring/#whats-still-in-the-classic-interface)).
**Classic Interface**, in your menu or at the foot of the left pane, opens it, on the same sign-in. Its own ways of
making and changing clients, matters, templates, users and groups still work there too, and so does Healdan Settings'
**Storage** tab, which does what the Admin app's storage screens do (see
[The Storage Tab in the Classic Interface](../storage-backends/#the-storage-tab-in-the-classic-interface)), and so
does its Trash, which can restore a deleted Store too (see [Deleted Stores](../stores/#deleted-stores)).

## When Something Goes Wrong

- **"You don't have access to Healdan Admin."** Your account isn't in the `admin` group, or was taken out of it
  while the Admin app was open: the app shows this in place of every page at its next request. **Open Healdan** takes
  you to the User app.
- **"Healdan couldn't check your access just now. Try again in a moment."** Healdan couldn't ask whether you're in
  `admin`, usually because its database didn't answer. Your access hasn't changed: reload the page in a moment.
- **"You've been signed out."** Your session ended. **Sign In** brings you back to the page you were on.
- **"There's no such page in Healdan Admin."** The address isn't one of the Admin app's pages. **Go to the
  Dashboard** takes you back.
- **"Something went wrong."** A part couldn't be loaded. **Try Again** asks once more. If it keeps failing, look for
  the fault on [Errors](../monitoring/errors/), or in the [Server Log](../monitoring/server-log/).
