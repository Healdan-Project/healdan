---
title: Background Jobs
weight: 4
---

**Background Jobs**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/jobs`,
lists every job the server runs in the background, in one table, in two groups: **Healdan's Jobs**, then
**LogicalDOC's Tasks**, the six [scheduled tasks](../../system/scheduled-tasks/). Each shows when it last ran since the
server started and how that went, when it runs next, and the buttons for what you can do with it now.

## The List

| Column | What it shows |
|---|---|
| Job | its name |
| Last Run | when its last run began, or **Never** |
| Result | **Succeeded**, **Failed**, **Done** or **Running**, or **Not Run Yet** when it hasn't run since the start |
| Next Run | when it runs next, or **None** where it won't or that isn't known, with **Overdue** after it when it's late |
| Enabled | **Yes**; **No** for a LogicalDOC task that's disabled; **Off on This Server** for a job that doesn't run here |
| Schedule | a LogicalDOC task's, in words, such as "Every 30 minutes", or **Cron:** and its expression |

The buttons are at the end of each row.

- **Failed:** the error shows in a row of its own under the job's, after **Last Error**, as the server logged it, but
  for a user name and password written in a URL (see [Errors](../errors/#a-user-name-and-password-in-a-url)). A
  LogicalDOC task's run counts as failed when it logged an error while it ran, even one it went on from.
- **Done** is for a job whose work is done for good, as the Email Index's is once its first pass has ended.
- **Overdue:** a job that's switched on and not running, whose next run is late by more than twice the time between its
  runs, and by an hour at least. The Dashboard's Needs Attention counts it.
- **Off on This Server:** one of Healdan's jobs that doesn't run on this server, such as the storage jobs on a server
  that doesn't use storage backends.

While a job is running, the list asks again every 30 seconds, while the page is showing. It asks again shortly after
**Run Now** or **Stop**, too, and each time you come back to its window. If Healdan can't be read then, the list keeps
what it showed, with "Couldn't refresh this, so it shows what was read last." and **Try Again**.

### After a Restart

What the list knows of each job's runs is kept in the server's memory, so it starts afresh with the server: after a
restart, a job shows no last run until it has run again. The Email Index's **Done** stays, as Healdan keeps in its
database that the first pass has ended.

## The Jobs

Healdan's jobs, in the list's order:

- **Previews** makes the [Office previews](../../office-previews/), one file at a time, as Office files are filed and
  saved.
- **Email Index** gives each email filed before Healdan kept Message-IDs its Message-ID, so that it counts as filed in
  its matter. It shows **Done** once its first pass has ended.
- **Trash Destroyer**, each night at 02:47, server time, destroys what was
  [removed](../../the-trash-and-removed-items/) long enough ago.
- **Storage Mover** moves files to their Store's [storage backend](../../storage-backends/) as soon as there's work for
  it, and looks for more every 5 minutes to begin with.
- **Storage Retests** checks again each storage backend that isn't OK, every minute to begin with.
- **Storage Purge**, once a day, deletes the [held copies](../../storage-backends/held-deletes/) whose time has come.
- **Storage Alert**, each hour, sends the [24-hour alert](../../storage-backends/when-a-backend-fails/#the-24-hour-alert)
  email when it's due, one a day at most.
- **Storage Nightly Check**, each night at 03:17, server time, checks that each file is recorded under its document's
  Store, and leaves one that isn't on its Store's backend for the Storage Mover.

LogicalDOC's tasks are described on [Scheduled Tasks](../../system/scheduled-tasks/).

## What Each Job Allows

Each row offers only what its job allows now. Should something change meanwhile, Healdan refuses and says why, such as
"This job is running already.", and the list is read again first, so the row shows the job as the refusal finds it:
the reason is under the job's row for **Run Now** and **Enable**, until the list shows the job has changed, and in the
question for **Stop** and **Disable**.

- **Healdan's jobs:** **Run Now**, for the Trash Destroyer, the Storage Mover, the Storage Retests, the Storage Purge
  and the Storage Nightly Check, while it's switched on and isn't running. It runs at once, in the background, as a
  scheduled run does, and the list shows its end and its result. The Trash Destroyer still destroys only what's due.
  Previews, the Email Index and the Storage Alert allow nothing here, and none of Healdan's jobs is stopped, disabled or
  given a schedule.
- **LogicalDOC's tasks:** **Run Now** while it's enabled and idle, and **Stop** in its place while it runs; **Enable**
  or **Disable**, whichever applies; and, always, **Schedule…** (see [A Task's Schedule](#a-tasks-schedule)). Run Now
  isn't offered while the server is set not to run scheduled tasks.

**Run Now** and **Enable** go at once, and **Stop** and **Disable** ask first, naming the task, as in "Stop Indexer?"
and "Disable Indexer?". Once Healdan has answered, the page says so, as in "Asked Indexer to run now".

- **Stop** asks the task's run to stop, which it does at its next check: until then it shows as running. The task
  runs again when its schedule next comes round, while it's enabled.
- **Disable** stops the task running on its schedule. A run that's going on carries on to its end, unless you stop it.
  **Enable** lets it run on its schedule again, and the list shows its next run. Both are saved in the server's
  configuration, so they last across a restart, as the Classic Interface's do.

Leave the Indexer enabled: without it, new documents can't be found by their words.

## A Task's Schedule

**Schedule…**, for a LogicalDOC task, opens the task's schedule, as in "Schedule of Indexer", starting from the
schedule it has. Under **Runs**:

- **Every So Often:** **Every**, a whole number, with its **Unit**, **Seconds**, **Minutes** or **Hours**, counted
  from one run's start to the next: a second at least, and a year at most, which is 31,536,000 seconds, 525,600
  minutes or 8,760 hours.
- **When a Cron Expression Says:** a **Cron Expression** in Quartz's form: seconds, minutes, hours, day of the month,
  month, day of the week, and a year if you want one, such as `0 0 3 * * ?` for three in the morning every day, in the
  server's time zone. Healdan refuses one Quartz doesn't take, and one that never comes round again, and says so beside
  the field.

**Save** stays greyed out until what you've chosen differs from the task's schedule, with **Every** in range or an
expression typed. The schedule is saved in the server's configuration, as the Classic Interface's Scheduled Tasks
saves it, the page says so, as in "Saved the schedule of Indexer", and the list shows the task's new schedule and its
next run. The task's other settings stay as they are, among them its initial delay and its maximum duration, which
are set there, and so does whether it's enabled.

## What Healdan Records

Each action is recorded in the [Audit Trail](../audit-trail/), with who did it and when, its Detail the job's name on
the server, such as `IndexerTask` or `trash-destroyer`: Run Now as "Asked a job to run" and Stop as "Asked a job to
stop", once asked for, "Enabled a job", "Disabled a job", and "Changed a job's schedule", with the schedule before and
after, such as `every 1800 seconds` and `cron 0 0 3 * * ?`. Enabling a task that's enabled already, disabling one
that's disabled, or saving the schedule it has changes nothing, and isn't recorded.
