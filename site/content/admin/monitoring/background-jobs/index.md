---
title: Background Jobs
weight: 4
---

**Background Jobs**, under **Monitoring** in [the Admin app](../../the-admin-app/)'s left pane, at `/admin/jobs`,
lists every job the server runs in the background, in one list: Healdan's own jobs first, then LogicalDOC's six
[scheduled tasks](../../system/scheduled-tasks/). Each shows when it last ran and how that went, when it runs next,
and what you can do with it now.

## The List

| Column | What it shows |
|---|---|
| Job | its name |
| Last Run | when its last run began, and ended |
| Result | **Succeeded**, **Failed** with its error, **Done** or **Running**, or that it hasn't run since the server started |
| Next Run | when it runs next, where that's known |
| Enabled | whether it runs at all |

<!-- P5 docs pass 2 -->

- **Failed:** the error is the server's, as it was logged, but for a user name and password written in a URL (see
  [Errors](../errors/#a-user-name-and-password-in-a-url)). A LogicalDOC task's run counts as failed when it logged an
  error while it ran, even one it went on from.
- **Done** is for a job whose work is done for good, as the Email Index's is once its first pass has ended.
- **A LogicalDOC task's schedule** shows in words, such as every 30 minutes, or as its cron expression.
- **Overdue:** a job that's switched on and not running, whose next run is late by more than twice the time between its
  runs, and by an hour at least, says so. The Dashboard's Needs Attention counts it.
- **Switched off:** one of Healdan's jobs that doesn't run on this server, such as the storage jobs on a server that
  doesn't use storage backends, is listed as switched off.

<!-- P5 docs pass 2 -->

While a job is running, the list asks again every 30 seconds, while the page is showing, and it asks each time you come
back to its window.

### After a Restart

What the list knows of each job's runs is kept in the server's memory, so it starts afresh with the server: after a
restart, a job shows no last run until it has run again.

## The Jobs

| Job | What it does | Allows |
|---|---|---|
| Previews | makes [Office previews](../../office-previews/) as files are filed | nothing |
| Email Index | records, once, the Message-ID of each email filed before Healdan began to keep them | nothing |
| Trash Destroyer | each night, destroys what was [removed](../../the-trash-and-removed-items/) long enough ago | Run Now |
| Storage Mover | moves files to their Store's [storage backend](../../storage-backends/) | Run Now |
| Storage Retests | checks again each backend that isn't OK | Run Now |
| Storage Purge | once a day, deletes the [held copies](../../storage-backends/held-deletes/) whose time has come | Run Now |
| Storage Alert | sends the [24-hour alert](../../storage-backends/when-a-backend-fails/#the-24-hour-alert) email | nothing |
| Storage Nightly Check | each night, checks each file is recorded under its document's Store, for the mover | Run Now |
| LogicalDOC's tasks | see [Scheduled Tasks](../../system/scheduled-tasks/) | Run Now, Stop, Enable, Disable, a schedule |

<!-- P5 docs pass 2 -->

## What Each Job Allows

Each job offers only what it allows now. Should something change meanwhile, Healdan refuses, with a sentence that says
why, such as for a job that's already running.

- **Healdan's jobs:** **Run Now**, for the Trash Destroyer, the Storage Mover, the Storage Retests, the Storage Purge
  and the Storage Nightly Check, while it's switched on and isn't running. It runs at once, in the background, as a
  scheduled run does, and the list shows its end and its result. The Trash Destroyer still destroys only what's due.
  Previews, the Email Index and the Storage Alert allow nothing here, and none of Healdan's jobs is stopped, disabled or
  given a schedule.
- **LogicalDOC's tasks:** **Run Now** while it's enabled and idle; **Stop** while it runs; **Enable** or **Disable**;
  and, always, its schedule (see [A Task's Schedule](#a-tasks-schedule)).

<!-- P5 docs pass 2 -->

**Stop** and **Disable** ask first, naming the task, and **Run Now** and **Enable** don't.

- **Stop** asks the task's run to stop. It shows as running until the run stops, at its next check.
- **Disable** stops the task running on its schedule. A run that's going on carries on to its end, unless you stop it.
  **Enable** lets it run on its schedule again, and the list shows its next run. Both are saved in the server's
  configuration, so they last across a restart, as the Classic Interface's do.

<!-- P5 docs pass 2 -->

Leave the Indexer enabled: without it, new documents can't be found by their words.

## A Task's Schedule

**Schedule…**, for a LogicalDOC task, changes when it runs, starting from the schedule it has:

- **every** so many seconds, minutes or hours, from one run's start to the next: a second at least, and a year at most;
- or **a cron expression,** in Quartz's form: seconds, minutes, hours, day of the month, month, day of the week, and a
  year if you want one, such as `0 0 3 * * ?` for three in the morning every day, in the server's time zone. Healdan
  refuses one Quartz doesn't take, and one that never comes round again.

<!-- P5 docs pass 2 -->

It's saved in the server's configuration, as the Classic Interface's Scheduled Tasks saves it, and the list shows the
task's next run. The task's other settings stay as they are, among them its initial delay and its maximum duration,
which are set there, and so does whether it's enabled.

## What Healdan Records

Each action is recorded in the [Audit Trail](../audit-trail/), with who did it and when, named by the job: Run Now and
Stop as asked for, Enable and Disable, and a new schedule, with the schedule before and after, such as
`every 1800 seconds` and `cron 0 0 3 * * ?`. Enabling a task that's enabled already, disabling one that's disabled, or
saving the schedule it has changes nothing, and isn't recorded.

<!-- P5 docs pass 2 -->
