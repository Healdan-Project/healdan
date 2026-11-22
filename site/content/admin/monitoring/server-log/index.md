---
title: Server Log
weight: 2
---

**Server Log**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/server-log`,
shows the server's log files: the main log, `dms.log`, and the logs some of LogicalDOC's tasks write, in
`repository/logs/` in the repository volume. It reads the files as LogicalDOC writes them, so it holds what they hold.
It doesn't show the container's own output, such as the start-up tool's messages before the server starts, which
`docker compose logs app` has: see the Install manual's [Troubleshooting](../../../install/troubleshooting/).

## Choosing a Log

The **Log** menu has **Main Log** first, then each other log that holds anything, by name: a LogicalDOC task's log
under the task's name, such as **Indexer**, and any other under its name in the server's logging configuration. A task
such as the Indexer writes to its own log, not to the main log.

A log is its current file and the older files it rolled over to, read as one. As Healdan ships, the main log starts a
new file once it reaches 5 MB and keeps the five before it, so it holds about 30 MB: lines older than that are gone
from the server.

The page's address keeps the log, the level and the search, as in `/admin/server-log?log=DMS`, so a reload or a copied
link shows the same, and the [Errors](../errors/) screen's **Show in Server Log** opens it this way. An address that
names a log the server doesn't have says so, as in "The server has no log called Archive. Pick one from the Log menu."

## Reading It

The entries show in a box of their own, the oldest at the top and the newest at the bottom. Each shows its time, to the
second, in your browser's time zone, with its day for one before today; its level in words, such as **Warn** or
**Error**; its source, the class and line of code that logged it, such as `Mover:322`, whose whole name shows when you
point at it; and its message, with the lines that follow it, such as a stack trace, under it. A line that starts no
entry, as the rest of a stack can be, shows indented, on its own. A line longer than 10,000 characters is cut there:
**Download** has it whole.

Above the box:

- **Level:** **Everything**, which the page starts on, **Warnings and Errors**, or **Errors**. Lines that start no
  entry, such as those at the very start of the oldest file, show only under Everything.
- **Search the Log** keeps the entries whose message, source or following lines hold what you type, in any case. It
  searches what the page shows, so it never finds a URL's user name or password, which don't show, and it searches
  a source by its whole name, such as `com.logicaldoc.healdan.preview`. The box follows once you pause typing.

Choosing another log, level or search starts again from the newest entries. With a level or a search, each read looks
through only so much of the log, about 5 MB, so it can find few entries, or none. The page then goes on back by
itself, a few reads in a row, and then waits for **Show Earlier**. So "No lines match these filters in what was read."
means only that, until the page says it has reached the start of the log.

## Following It Live

The Server Log opens on the log's newest entries, following it, as **Following Live** beside the search says. It asks
for new entries every 3 seconds, and adds them at the bottom as they're written, asking again at once while it has more
to catch up on. It asks only while the page is showing: not while its tab is hidden, and it asks at once when you come
back. **Pause** stops it: the page then says **Paused**, and the button becomes **Follow**, which asks at once from
where it stopped and takes the box back to the newest entries.

At the bottom, the box keeps to the newest entries as they come. Scrolled further up, the entry you're reading stays
where it is, whatever comes, and nothing takes the focus.

The page holds 1,000 entries at most. While you're at the bottom, the oldest go as new ones come, and **Show Earlier**
reads them again. While you're reading further up, it pauses following instead, so the lines you're reading stay, and
says "Following paused, so the earlier lines stay. Follow goes back to the newest." Going back past 1,000 entries with
Show Earlier pauses it the same way, leaving out the newest, which **Follow** reads again. Choosing another log, level
or search after such a pause follows again; a pause you chose stays.

If a read of the new entries fails, the page keeps what it shows and says "Couldn't read the newest lines, so this shows
what was read last. Healdan keeps trying.", and it does, every 3 seconds.

When the log rolls over to a new file while you're following it, the page says "The log rolled over to a new file, so
this starts again at its top. Show Earlier reads the lines before it.", and carries on from the start of the new file.
What was written to the older file in the meantime is there when you go back.

## Going Back

Scroll to the top of the box, or press **Show Earlier** there, to read earlier entries, back through the older files.
Your place in the box is kept as they come. A read that finds few entries goes on back by itself a few times, as with a
level or a search, then waits for Show Earlier again. At the start of the oldest file, the box says "This is the start
of the log: it holds nothing older." If a read of earlier entries fails, it says "Couldn't read the earlier lines.",
and Show Earlier tries again.

## Download

**Download**, beside **Pause** or **Follow**, saves a zip of the log's files as they are on the server, its current
file and the older ones, each under its own name. The zip is named after the log's file and the time it was made, in
UTC, such as `dms-20261122-104107.zip`. Downloading a log isn't recorded.

For a zip of the logs with the server's settings and environment, for whoever helps you with a problem, use
**Download the logs** on the Classic Interface's
[System Information](../../system/system-information/#downloading-the-logs).

## A User Name and Password in a URL

As on the Errors screen, a URL in a line shows without a user name and password written before its host:
`https://user:password@host` shows as `https://host`, and the search finds what shows. The files themselves aren't
changed, so **Download** has them as the server wrote them. See
[A User Name and Password in a URL](../errors/#a-user-name-and-password-in-a-url) for what's recognised.
