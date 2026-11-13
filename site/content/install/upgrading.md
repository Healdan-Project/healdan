---
title: Upgrading
weight: 9
---

{{< callout type="info" >}}
These docs describe Healdan as it is on its `master` branch. If your server runs an older build, some pages may
describe features or settings it doesn't have yet.
{{< /callout >}}

[Back up](../backup-and-restore/) first. Then, from the folder you cloned Healdan into, fetch the new version:

```bash
git pull
```

If [Changes to LogicalDOC's Tables](#changes-to-logicaldocs-tables) lists one your database doesn't have yet, make
it now. Then build and start the new version:

```bash
docker compose up -d --build
```

To update MariaDB too, run `docker compose pull --ignore-buildable` before that. A plain `docker compose pull`
fails, because the app's image is built here and can't be pulled.

## What an Upgrade Changes

- **Your data stays.** The volumes are kept.
- **New default settings are added** to your `context.properties` when the app starts. Values you already have
  aren't changed, apart from four kinds:
  - the release number and copyright years (`product.release` and `product.year`), which the new image updates;
  - a setting still holding an older image's default that the new image replaces. If you changed it yourself, your
    value stays. So far that's `cachecontrol`, which tells browsers how long to keep Healdan's files;
  - non-breaking spaces in the Content-Security-Policy (`security.csp`), which tells browsers what Healdan's pages
    may load. They become ordinary spaces, in a policy you changed too, and the rest of it stays. Until November
    2026 the default had one after `object-src`, so browsers ignored that part of the policy, and their consoles
    showed "Unrecognized Content-Security-Policy directive";
  - `store`, which says what keeps the documents' files. Healdan sets it to its own storage on every start, whatever
    the file holds, so changing it yourself does nothing. Until November 2026 it was LogicalDOC's. The first start
    after the upgrade that changes it moves nothing: the files stay in the repository volume, where Healdan's
    storage reads them.
- **Browsers load the new interface** the next time they open Healdan. A tab left open during the upgrade keeps the
  old one until it's reloaded.
- **The User app arrives,** Healdan's browser app at `/app/`. Signing in lands there from now on, by password or
  through a provider, and a link into the app followed while signed out comes back to that page after signing in. To
  keep an install landing in LogicalDOC's screens, an administrator sets **Start In** to **Classic Interface** under
  **Administration → Healdan → Settings** (see [Healdan Settings](../../admin/healdan-settings/)). The setting is
  `ui.start`, and anything but `classic` means the app.
- **Healdan's own tables are upgraded** when the app starts. If an upgrade step fails, the app stops instead of
  running on a half-upgraded database, and the log says which step failed. See
  [Troubleshooting](../troubleshooting/).
- **LogicalDOC's tables aren't.** A change to them is made by hand, before the new version's first start: see
  [Changes to LogicalDOC's Tables](#changes-to-logicaldocs-tables). If one is missing, the app stops as it starts,
  and its log names the tables and columns the database lacks.

## The Build Needs AVX2, Since the User App

Healdan's build has needed AVX2 on an x86-64 processor (the x86-64-v3 level) since the User app arrived, in November
2026. A server whose processor offers less, as a virtual machine with its hypervisor's default processor type often
does, builds as before until then, and then stops while building `healdan-web` with "Illegal instruction". The app
that was running keeps running. [The Build Stops with "Illegal
Instruction"](../troubleshooting/#the-build-stops-with-illegal-instruction) says how to check the processor, and
what to change.

## Changes to LogicalDOC's Tables

Healdan upgrades its own tables when it starts, but never LogicalDOC's. So when a new version needs a change to them,
run its SQL by hand, after the backup and before the new version's first start. If you don't, the app stops as it
starts, before anyone can sign in, and its log says what the database lacks, such as
`LogicalDOC's tables lack what this version needs: table ld_tenant lacks columns …`. Each change has a check that shows
whether your database has it, as one made by a version that already had the change does. Its SQL leaves alone what
the database already has, so it's safe to run again.

Run the commands from the folder with Healdan's `compose.yaml`. Stop the app and start the database first, so a
change doesn't have to wait for the app to let go of the table:

```bash
docker compose stop app
docker compose up -d db
```

The commands reach the stack's own `db` service. If `DB_HOST` points at a MariaDB of your own (see
[Supported Databases](../what-runs/#supported-databases)), run each change's SQL there instead, in Healdan's
database. The stack's `db` then holds none of Healdan's tables, so a check there prints `0`, and a change fails with
`Table '…ld_tenant' doesn't exist`.

### Quota Thresholds, November 2026

Versions of `master` from 9 November 2026 on have 14 more columns on LogicalDOC's `ld_tenant` table, for quota
thresholds. Without them, LogicalDOC can't read that table, and nobody could sign in, so the new version stops as
it starts.

To see whether your database has them:

```bash
echo "select count(*) from information_schema.columns where table_schema = database() and table_name = 'ld_tenant' and column_name like '%quotathr'" | docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -N -u"$MARIADB_USER" "$MARIADB_DATABASE"'
```

It prints `14` when the database has them all, as one made since then does. Anything less means it needs them. To
add the ones it lacks:

```bash
docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"' <<'SQL'
alter table ld_tenant
  add column if not exists ld_formsquotathr int, add column if not exists ld_usersquotathr int,
  add column if not exists ld_sessionsquotathr int, add column if not exists ld_documentsquotathr int,
  add column if not exists ld_workflowsquotathr int, add column if not exists ld_storagequotathr int,
  add column if not exists ld_rousersquotathr int, add column if not exists ld_reportsquotathr int,
  add column if not exists ld_apicallsquotathr int, add column if not exists ld_ticketsquotathr int,
  add column if not exists ld_stampsquotathr int, add column if not exists ld_importfoldersquotathr int,
  add column if not exists ld_emailaccountsquotathr int, add column if not exists ld_pagesquotathr int;
SQL
```

It prints nothing when it works. The check then prints `14`.

If the new version has already tried to start without them, it stopped, and its log says
`table ld_tenant lacks columns …`. Docker starts it again and again until they're there. Add them the same way, the
app stopped first, then start it again:

```bash
docker compose start app
```

## The First Start on Healdan's Storage

The upgrade that brings [storage backends](../../admin/storage-backends/) switches the `store` setting to Healdan's
own storage, as above. Its first start does a few things once:

- **It records every file already in the repository,** with its size and its document's Store, so that Healdan knows
  where each file is. This adds to that start's time on a large repository: on the tests' database it recorded about
  9,450 small files a second, so 100,000 files took about 11 seconds. MariaDB on your server will differ, so take that
  as a rough guide. A start that's stopped part-way records the rest at the next.
- **It writes a marker, `.healdan-backend`,** in Original's folder, `/opt/logicaldoc/repository/docs`. If that folder
  has neither a marker nor any file while the database has documents, as when a [host folder](../host-folders/)
  didn't mount, the app stops instead of marking an empty folder: see [Troubleshooting](../troubleshooting/).
- **It hides LogicalDOC's own Stores screen** under **Administration → Settings**, as the **Storage** tab of Healdan
  Settings takes its place.
- **Nothing moves.** Every Store stays on Original, the repository's own folder, until you give it another backend.

## Presentations and Spreadsheets Whose Text Wasn't Read

Until November 2026, Healdan couldn't read the text of PowerPoint presentations (`.pptx`, `.ppsx`), older Excel
workbooks (`.xls`, `.xlt`), or newer ones (`.xlsx`, `.xlsm`) with a cell in a format such as Excel's Accounting.
Unless **Ignore content error** was on (see below), a full-text search didn't find them at all, not even by their
names, and only a search by their properties did. The log showed
`NoClassDefFoundError: com/zaxxer/sparsebits/SparseBitSet` at each run of the Indexer.

After the upgrade, the Indexer reads them at its next run, as it kept trying them, unless one of two settings on
[Search and Indexing](../../admin/system/search-and-indexing/) was on:

- **On error, mark unindexable** gave up on them. On its **Indexing History** tab, click the **Result** column's
  header to bring the failures together, select those documents, and choose **Mark indexable** from the right-click
  menu. **Reschedule all for indexing** doesn't reach documents marked unindexable.
- **Ignore content error** indexed them without their text, so a full-text search found them by their names but not
  their words. [Reschedule all for indexing](../../admin/system/search-and-indexing/#indexing-again) to have them
  read.

## Words Indexed Under a Wrong Stem

Until November 2026, LogicalDOC's code that cuts a word down to its stem for the full-text index (so that "leases"
finds "lease") kept one working copy per language, which every thread that indexed or searched used at once. Two
threads using it together could confuse each other. The Indexer, which works on two documents at a time, could
store a word under the wrong stem or fail a document, and a search that ran while another did could answer
with no hits. A search from the User app, which asks for its list and its Filters' counts together, met this most
often.

The fix gives each thread its own copy, so no new damage is done. Documents indexed before it may hold a few words
under a wrong stem, and a search for those words won't find them. If **On error, mark unindexable** was on (see
[above](#presentations-and-spreadsheets-whose-text-wasnt-read)), a document the fault made fail was given up on and
isn't in the index at all. After the upgrade, rebuild the index once:

1. Open **Administration → Settings → Search and Indexing**, the
   [page's Indexing Again section](../../admin/system/search-and-indexing/#indexing-again).
2. Click **Reschedule all for indexing** and answer **Yes** to "Do you want to reindex all documents?". It marks every
   indexed document to be indexed again.
3. If **On error, mark unindexable** was on, also bring back the documents it gave up on: on the **Indexing History**
   tab, click the **Result** column's header to bring the failures together, select those documents, and choose
   **Mark indexable** from the right-click menu. **Reschedule all for indexing** doesn't reach them.
4. Leave the server running. The Indexer works through the documents on its next runs, and each one's new entry
   replaces its old.

This costs the Indexer's time. Its **Batch** is 500 documents to begin with, taken every 30 minutes, about 1,000
documents an hour, so 50,000 documents take about two days. [Finding New Documents
Sooner](../../admin/system/scheduled-tasks/#finding-new-documents-sooner) says how to run it more often, and **Batch** is
on [Search and Indexing](../../admin/system/search-and-indexing/). Also:

- Searches keep working meanwhile, but until the Indexer has reached a document, a search may still miss it. Choose a
  quiet time. The **Indexing queue** tab lists what is still waiting. **Drop index** would do the same after emptying
  the index, which makes searches find less than they should for longer, so it isn't needed.
- The Indexer reads each file again. On a Store kept on an S3 backend, every file is read back from its bucket, which
  costs the bucket's downloads and fills and turns over the [read cache](../../admin/storage-backends/).
- The **Indexing History** tab gets a row for each document.

Rebuilding the index doesn't change the User app's **Edit Date**, which is when a document's file last changed. It does
change the Classic Interface's **Last modified** for every document, as the Indexer stores each one it indexes again.

## Emails Filed Before the Outlook Add-in

The upgrade that brings [Filing Email from Outlook](../../user/office/filing-email/) also starts keeping each email's
Message-ID, which is how Healdan knows that a matter already holds a message. The emails already in Healdan get
theirs in the background:

- **A minute after the app starts,** Healdan reads the emails already filed, 50 at a time with a second's pause
  between batches, in the order they were filed. A restart carries on where it stopped. Until it has read an email,
  that email doesn't count when the same message is filed into its matter again, and the Outlook pane doesn't show it
  as filed.
- **Emails already filed twice in one matter** stay as they are. Once both are read, a folder move, merge, copy or
  restore that would bring the two copies into a matter together is refused, saying the message is already there.
  Delete one copy, or move it to another matter, first.
- **To see whether it has finished,** look for `email.index` in Healdan's `hd_setting` table. Run this from the
  folder with Healdan's `compose.yaml`:

  ```bash
  echo "select name, value from hd_setting where name like 'email.index%'" | docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"'
  ```

  `email.index` is `done` once every email filed before the upgrade has been read, and `email.index.cursor` is the
  last document it has read so far.
