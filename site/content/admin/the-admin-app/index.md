---
title: The Admin App
weight: -1
---

The Admin app is where administrators run Healdan, at `/admin/` on your Healdan address. So far it has the
Dashboard: what needs your attention, the server's health and how Healdan is used, on one page. Everything else is
still done in the Classic Interface, which the rest of this guide describes, and the Admin app links there.

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
  and Enter alone chooses the first. For now, choosing one opens the Classic Interface, where clients, matters and
  people are managed, not the one you found.
- **Open Healdan** opens the User app.
- **The ? (Help) button** opens a menu with **Admin Guide**, which opens this page, and **Open-Source Notices**; each
  opens in a new window.
- **Your menu,** under your initials, shows your name and has **Classic Interface** and **Sign Out**.

### The Left Pane

The left pane has what the Admin app has so far: **Dashboard**, and at its foot **Classic Interface**, which opens
the Classic Interface, in the same window, for everything else.

On a window narrower than 1,024 pixels, the left pane folds away behind the **☰** (Navigation) button in the top bar,
which opens it over the page. Esc or choosing a link closes it again. The Admin app is designed for a window 1,280
pixels wide or more, and works at a tablet's 768.

## The Dashboard

The Dashboard is the Admin app's first page, at `/admin/`. Beside its title, "Updated" and a time say when it was last
brought up to date, and **Refresh** asks again at once. It also asks again every 30 seconds while it's showing, and
each time you come back to its window, but not while its tab is hidden. Healdan keeps what it last read for Needs
Attention and Server Health for a minute, and the database's for five, then shows that while it reads again, so a
figure can be a minute or two old, and the database's up to ten.

Each of its three parts loads on its own. One that can't be loaded says so, with **Try Again**, and the others still
work.

### Needs Attention

Needs Attention comes first: what an administrator should act on, each with how many, a line that says more, such as
which Store or since when, and a link to where it's fixed. Until that place is in the Admin app, the link is
**Classic Interface**. With nothing to report, it says "Nothing needs your attention."

| It says, for example | What it means |
|---|---|
| "3 files are stuck moving to storage" | files Healdan gave up moving to their Store's storage backend: see [Stuck Files and Retry](../storage-backends/waiting-and-stuck-files/#stuck-files-and-retry) |
| "1 storage backend can't be reached" | a storage backend Healdan can't reach now, or that's failing for another reason, so Healdan can't use it: see [When a Backend Fails](../storage-backends/when-a-backend-fails/) |
| "12 documents couldn't be indexed, so search won't find their text" | documents the [Indexer](../system/scheduled-tasks/) failed on at its last try, which it goes on trying: see [Search and Indexing](../system/search-and-indexing/) |
| "4 unresolved errors in the last 24 hours" | errors the server logged in the last 24 hours that nobody has resolved, an error logged again and again counting once. The server's log is on the **Log** tab of [System Information](../system/system-information/) |
| "2 previews were given up on" | Office previews Healdan gave up making after their last try: see [Office Previews](../office-previews/) |
| "1 background job failed when it last ran" | a background job that's switched on, Healdan's own work or one of LogicalDOC's [scheduled tasks](../system/scheduled-tasks/), whose last run failed. A scheduled task's run counts as failed when it logged an error while it ran, even one it went on from |
| "1 background job hasn't run when it should have" | a background job that's switched on and not running, whose next run is late by more than twice the time between its runs, and by an hour at least |
| "1 disk is more than 85% full" | the disk of the repository, the search index or the logs, more than 85% full |

When Healdan couldn't check something this time, or is still working it out, it names it, as in "Couldn't check
Stuck Files, Errors.", and never says that nothing needs your attention while it can't tell.

### Server Health

Ten tiles, each with its status in words and a line of detail. A tile opens the screen behind it, which for now is the
Classic Interface. The status is:

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
| Background Jobs | how many background jobs are on schedule, which failed or are overdue, and how many are switched off |
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
the count of people signed in now is made each time the page asks. Days are the server's, in its time zone.

Just after the server starts, Usage says "Usage hasn't been worked out since the server started. It shows here within
a few minutes." If Healdan couldn't work them out, it says so, and keeps the last figures it had, saying when they're
from. Why is in the server's log.

## What's Still in the Classic Interface

Everything else, which the rest of this guide describes there: Stores, clients and matters, workspace templates and
top-level folders, users and groups, permissions, Healdan Settings with its Sign-In and Storage tabs, storage backends,
and the screens [System](../system/) covers, such as scheduled tasks, search and indexing, outgoing email, reports and
the server's log. **Classic Interface**, in your menu or at the foot of the left pane, opens it, on the same sign-in.

## When Something Goes Wrong

- **"You don't have access to Healdan Admin."** Your account isn't in the `admin` group, or was taken out of it
  while the Admin app was open: the app shows this in place of every page at its next request. **Open Healdan** takes
  you to the User app.
- **"You've been signed out."** Your session ended. **Sign In** brings you back to the page you were on.
- **"There's no such page in Healdan Admin."** The address isn't one of the Admin app's pages. **Go to the
  Dashboard** takes you back.
- **"Something went wrong."** A part couldn't be loaded. **Try Again** asks once more. If it keeps failing, look in
  the server's log.
