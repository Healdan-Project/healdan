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

The log menu has **Main Log** first, then each other log that holds anything: a LogicalDOC task's log under the task's
name, such as **Indexer**, and any other under its name in the server's logging configuration. A task such as the
Indexer writes to its own log, not to the main log.

<!-- P5 docs pass 2 -->

A log is its current file and the older files it rolled over to. As Healdan ships, the main log starts a new file once
it reaches 5 MB and keeps the five before it, so it holds about 30 MB: lines older than that are gone from the server.

The page's address keeps the log, the level and the search, as in `/admin/server-log?log=DMS`, so a reload or a copied
link shows the same, and the [Errors](../errors/) screen's **Show in Server Log** opens it this way.

<!-- P5 docs pass 2 -->

## Reading It

Each entry shows its time, its level, its source (the class and line of code that logged it) and its message, with
the lines that follow it, such as a stack trace, under it. A line longer than 10,000 characters is cut there:
**Download** has it whole.

<!-- P5 docs pass 2 -->

- **The level:** **Everything**, **Warnings and Errors**, or **Errors**. Lines that start no entry, such as those at
  the very start of the oldest file, show only under Everything.
- **The search** keeps the entries whose message, source or following lines hold what you type, in any case, as they
  show here.

<!-- P5 docs pass 2 -->

## Following It Live

The Server Log opens on the log's newest entries, following it: it asks for new entries every 3 seconds, and adds
them as they're written. It asks only while the page is showing: not while its tab is hidden, nor once you press
**Pause**, which stops it, and becomes **Follow**, which carries on from where it stopped and catches up. New entries
don't move the place of someone reading further up.

<!-- P5 docs pass 2 -->

When the log rolls over to a new file while you're following it, the page says so, and carries on from the start of
the new file. What was written to the older file in the meantime is there when you go back.

## Going Back

Scroll to the top, or press **Show Earlier**, to load earlier entries, back through the older files, until the start
of the oldest file, which the page says it has reached. Your place in the list is kept as they load.

<!-- P5 docs pass 2 -->

## Download

**Download** saves a zip of the log's files as they are on the server, its current file and the older ones, each
under its own name. The zip is named after the log's file and the time it was made, in UTC, such as
`dms-20261122-104107.zip`. Downloading a log isn't recorded.

<!-- P5 docs pass 2 -->

For a zip of the logs with the server's settings and environment, for whoever helps you with a problem, use
**Download the logs** on the Classic Interface's
[System Information](../../system/system-information/#downloading-the-logs).

## A User Name and Password in a URL

As on the Errors screen, a URL in a line shows without a user name and password written before its host:
`https://user:password@host` shows as `https://host`, and the search finds what shows. The files themselves aren't
changed, so **Download** has them as the server wrote them. See
[A User Name and Password in a URL](../errors/#a-user-name-and-password-in-a-url) for what's recognised.

<!-- P5 docs pass 2 -->
