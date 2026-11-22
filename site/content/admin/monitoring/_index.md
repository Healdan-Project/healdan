---
title: Monitoring
weight: 9
---

The Monitoring screens show what the server is doing and what has been done in Healdan: the errors the server has
logged, its log files, the Audit Trail of who did what, and the background jobs. They're in
[the Admin app](../the-admin-app/), under **Monitoring** in its left pane, and only administrators see them:

- **[Errors](errors/),** at `/admin/errors`: the errors the server has logged, one row for each kind of fault, kept
  until you resolve it.
- **[Server Log](server-log/),** at `/admin/server-log`: the server's log files, followed live as they're written.
- **[Audit Trail](audit-trail/),** at `/admin/audit`: sign-ins, what was done to documents, folders and accounts, and
  every change made through the Admin app, newest first. It says
  [what counts as looking at a document](audit-trail/#what-counts-as-looking-at-a-document).
- **[Background Jobs](background-jobs/),** at `/admin/jobs`: Healdan's background jobs and LogicalDOC's scheduled
  tasks, with when each last ran and how it went, and what each allows you to do.

## Counts in the Left Pane

**Errors** shows how many unresolved errors the server logged in the last 24 hours, each kind of fault counting once,
and **Background Jobs** how many jobs failed when they last ran or are overdue, as the Dashboard's Needs Attention
counts them. As with **Backends**, the counts are asked for when the Admin app opens and each time you come back to its
window, and every 30 seconds while the Dashboard is showing. Resolving a fault, or an action on a job, asks for them
again, and they follow within a minute or two, as the Dashboard's figures do.

## From the Dashboard

The Dashboard's links open these screens:

- in Needs Attention, the unresolved errors open **Errors**, and the previews given up on, a job that failed and a job
  that hasn't run when it should have open **Background Jobs**;
- in Server Health, the **Server**, **Database** and **Memory** tiles open **Server Log**, and the **Previews** and
  **Background Jobs** tiles open **Background Jobs**.

See [The Dashboard](../the-admin-app/#the-dashboard).

## When Something Goes Wrong

Start with **Errors**. It has each fault with its latest message, its stack and the documents it names, however often
it happened, and **Show in Server Log** opens the log it was written to, searching it for the fault, for what happened
around it. A job that failed says so on **Background Jobs**, with its error. The Install manual's
[Troubleshooting](../../install/troubleshooting/) has what to do when the app doesn't start, which these screens can't
show.

<!-- P5 docs pass 2 -->

## What Healdan Records

Healdan records, in the [Audit Trail](audit-trail/), with who did it, when and from where:

- **a fault** marked resolved, or marked unresolved again, named by its message;
- **a job** asked to run now or to stop, enabled, disabled, or given a new schedule, with the schedule before and
  after.

A change that changes nothing, such as enabling a task that's enabled already, isn't recorded, and nothing that only
reads is: opening these screens, downloading a log and exporting the Audit Trail are recorded nowhere.

## What's Still in the Classic Interface

The Classic Interface's screens for these stay for now:

- **The Log tab** of [System Information](../system/system-information/), with **Download the logs**, which zips the
  logs with the server's settings for whoever helps you with a problem. The Server Log shows the same log, and the
  tasks' logs.
- **[Scheduled Tasks](../system/scheduled-tasks/),** where a task's initial delay and maximum duration are set, and
  its progress shows while it runs. Background Jobs runs, stops, enables, disables and schedules the tasks too.
- **The Last Changes report,** under [Reports](../system/reports/). The Audit Trail lists its rows, and more.

## In This Section

- [Errors](errors/): faults grouped by kind, resolving them, and how long they're kept.
- [Server Log](server-log/): the main log and the tasks' logs, live, and **Download**.
- [Audit Trail](audit-trail/): who did what, what counts as looking at a document, and **Export CSV**.
- [Background Jobs](background-jobs/): every job, and what each allows.

<!-- P5 docs pass 2 -->
