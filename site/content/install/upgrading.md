---
title: Upgrading
weight: 9
---

{{< callout type="info" >}}
These docs describe Healdan as it is on its `master` branch. If your server runs an older build, some pages may
describe features or settings it doesn't have yet.
{{< /callout >}}

{{< callout type="warning" >}}
**An install still on MariaDB** moves its data to PostgreSQL before it upgrades: see
[Moving from MariaDB to PostgreSQL](../moving-from-mariadb/), whose first section says how to tell. Upgrading as
below without moving stops as the app starts, and changes nothing.
{{< /callout >}}

[Back up](../backup-and-restore/) first. Then, from the folder you cloned Healdan into, fetch the new version:

```bash
git pull
```

If [Changes to LogicalDOC's Tables](#changes-to-logicaldocs-tables) lists one your database doesn't have yet, make
it now. Then fetch the Tomcat image the new version is built on, which brings its Java, and build and start it:

```bash
docker pull tomcat:11.0.29-jre25-temurin
docker compose up -d --build
```

To update PostgreSQL to the newest release of its version, 18, too, run `docker compose pull --ignore-buildable`
before that. A plain `docker compose pull` fails, because the app's image is built here and can't be pulled. A new
major version of PostgreSQL is another matter: see
[Moving to a New PostgreSQL Major Version](#moving-to-a-new-postgresql-major-version).

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
    storage reads them. Healdan sets three more on every start the same way: `jdbc.driver`, `jdbc.dbms` and
    `hibernate.dialect`, which name PostgreSQL.
- **Browsers load the new interface** the next time they open Healdan. A tab left open during the upgrade keeps the
  old one until it's reloaded.
- **The User app arrives,** Healdan's browser app at `/app/`. Signing in lands there from now on, by password or
  through a provider, and a link into the app followed while signed out comes back to that page after signing in. To
  keep an install landing in LogicalDOC's screens, an administrator sets **Start In** to **Classic Interface** under
  **Administration → Healdan → Settings** (see [Healdan Settings](../../admin/healdan-settings/)). The setting is
  `ui.start`, and anything but `classic` means the app.
- **Healdan's own tables are upgraded** when the app starts. If an upgrade step fails, PostgreSQL undoes it, and the
  app stops instead of running on a half-upgraded database, and the log says which step failed. See
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

## Getting a Newer Java

Healdan's image is built on Tomcat's, which brings Java. Each version of Healdan names the image of one Tomcat
version, the one in the commands on this page. While that's Tomcat's newest version, Docker Hub rebuilds its image
on each new release of Java, but `docker compose up -d --build` on its own reuses the copy of the image your server
already has, so it doesn't bring a newer Java. That's why an upgrade pulls Tomcat's image first. To get a newer Java
without changing your version of Healdan, run the same two commands without `git pull`. The build reuses what it
already made for Healdan itself:

```bash
docker pull tomcat:11.0.29-jre25-temurin
docker compose up -d --build
```

Once a newer version of Tomcat is out, the older one's image isn't rebuilt, so pulling it again brings no newer Java.
A later fix to Java then comes with a version of Healdan that names a newer Tomcat image, so upgrade to it as at the
top of this page.

If `.env` sets `LOGICALDOC_IMAGE` to a published image, your server doesn't build Healdan. Run `docker compose pull`
and `docker compose up -d` instead, as `--build` would store a build of your checkout under the published name.

To see which Java the app runs:

```bash
docker compose exec app java -version
```

### The Base64 Option in `.env` {#cve-2026-114938-java4shell}

In November 2026 this page suggested adding `-XX:-UseBASE64Intrinsics` to `CATALINA_OPTS` in `.env`, to turn off
the part of Java that CVE-2026-114938 ("Java4Shell") affects until Tomcat's image had the fix. Builds of Healdan
from 21 November 2026 have it. If you added the option, take it out of `.env` once `java -version` shows 25.0.5.1 or
later, and run `docker compose up -d`. It does no harm left in, but Java decodes Base64 more slowly with it.

## Moving to a New PostgreSQL Major Version

Healdan's `compose.yaml` names PostgreSQL's major version, `postgres:18`, and
`docker compose pull --ignore-buildable` brings only that version's newest release. A new major version, such as 19,
can't open an older one's data. So when a new version of Healdan names a newer one, the database moves across by a
dump and a restore, never by the change of image alone: the new PostgreSQL would start on an empty database, and
Healdan would refuse to start on that beside your documents.

PostgreSQL's image keeps each major version's data in a folder of its own in the `db` service's volume, such as
`/var/lib/postgresql/18/docker` for 18. So the new version starts on a new, empty folder, and the old one stays as it
was, beside it, until you remove it. Run these from the folder with Healdan's `compose.yaml`.

{{% steps %}}

### Back Up the Database, and Take the Stack Down

```bash
docker compose stop app
docker compose exec -T db sh -c 'pg_dump -U postgres -Fc "$HEALDAN_DB_NAME"' > logicaldoc-db.dump
docker compose down
```

That's [Backup and Restore](../backup-and-restore/)'s database backup, with the app left stopped. If you haven't
backed up the files lately, do that too.

### Fetch the New Version

```bash
git pull
docker pull tomcat:11.0.29-jre25-temurin
docker compose build
```

### Start the New Database

```bash
docker compose up -d db
```

Wait until `docker compose ps` shows it healthy. Its first start makes the app's role and an empty database, in the
new version's folder.

### Load the Database

```bash
docker compose exec -T db sh -c 'pg_restore -U "$HEALDAN_DB_USER" -d "$HEALDAN_DB_NAME" --no-owner --exit-on-error --single-transaction' < logicaldoc-db.dump
```

It prints nothing when it works, as in [Restoring onto a Fresh Stack](../backup-and-restore/#restoring-onto-a-fresh-stack).

### Start Everything

```bash
docker compose up -d
```

### Remove the Old Version's Data

When Healdan has run well on the new version for a while, and a backup of it works, remove the old version's folder.
First see which folders there are, and which version the database runs:

```bash
docker compose exec db ls /var/lib/postgresql
docker compose exec db psql -U postgres -Atc 'show server_version'
```

The first lists a folder for each major version the volume has held, such as `18  19`. The second prints the
version that runs, in the form `18.4 (Debian 18.4-1.pgdg13+1)`: the number before the first dot names the live
database's folder, `18` there, and after a move to 19 it begins `19.`. Only when the list shows two folders,
remove the one that isn't the running version's, here 18's:

```bash
docker compose exec db rm -rf /var/lib/postgresql/18
```

Never remove the folder of the version that runs: that deletes the database.

{{% /steps %}}

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

Each change gives its SQL to `psql`, PostgreSQL's own client, in the `db` service's container, signed in as the
app's role, whose tables they are. If `DB_HOST` points at a PostgreSQL of your own (see
[Supported Databases](../what-runs/#supported-databases)), run each change's SQL there instead, in Healdan's
database, as that role. The stack's `db` then holds none of Healdan's tables, so a check there prints `0`, and a
change fails with `relation "ld_tenant" does not exist`.

**No change is listed yet.** Every PostgreSQL database was made by a version that already had every change before it:
a new install's by its first start, and one moved from MariaDB by the move, which makes the tables as a new install's
are. An install still on MariaDB makes those it lacks before it moves: see
[Changes to LogicalDOC's Tables Before That Version](../moving-from-mariadb/#changes-to-logicaldocs-tables-before-that-version).

If the new version has already tried to start without a change, it stopped, and its log says what the database
lacks. Docker starts it again and again until it's there. Make the change the same way, the app stopped first, then
start it again:

```bash
docker compose start app
```

## The First Start on Healdan's Storage

The upgrade that brings [storage backends](../../admin/storage-backends/) switches the `store` setting to Healdan's
own storage, as above. Its first start does a few things once:

- **It records every file already in the repository,** with its size and its document's Store, so that Healdan knows
  where each file is. This adds to that start's time on a large repository: on the tests' database it recorded about
  9,450 small files a second, so 100,000 files took about 11 seconds. Your server will differ, so take that as a rough
  guide. A start that's stopped part-way records the rest at the next.
- **It writes a marker, `.healdan-backend`,** in Original's folder, `/opt/logicaldoc/repository/docs`. If that folder
  has neither a marker nor any file while the database has documents, as when a [host folder](../host-folders/)
  didn't mount, the app stops instead of marking an empty folder: see [Troubleshooting](../troubleshooting/).
- **It hides LogicalDOC's own Stores screen** under **Administration → Settings**, as Healdan's own storage screens
  take its place: the Admin app's **Backends** and **Stores**, and the **Storage** tab of Healdan Settings.
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

From a version with the Admin app's Search Index, steps 2 and 4 can be one reindex instead: choose **Reindex…** there,
then **Everything**, and leave **Empty the Index First** unticked. It indexes every document again straight through,
without waiting for the Indexer's runs, while searches keep working. Do step 3 first, as a reindex leaves out the
documents set not to be indexed. See [Reindex](../../admin/system/search-and-indexing/#reindex).

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
  echo "select name, value from hd_setting where name like 'email.index%'" | docker compose exec -T db sh -c 'psql -U "$HEALDAN_DB_USER" -d "$HEALDAN_DB_NAME"'
  ```

  `email.index` is `done` once every email filed before the upgrade has been read, and `email.index.cursor` is the
  last document it has read so far.

## Document Permissions with Nothing Ticked

Until November 2026, a document's **Security** tab saved a row with nothing ticked. It looked as if it kept that person
or group out, and LogicalDOC's folder list and search did hide the document from them, but they could still open it,
by its doc #, a link or the User app, with whatever its folder gave them. Healdan now refuses to save such a row (see
[Permissions](../../admin/permissions/#one-off-grants-on-a-document)).

The first start after the upgrade deletes the rows of that kind already there, once. Its log names each row before
it goes, with its document's id and doc # and its group's id and name, in a line beginning `Removing a document
permission row that granted nothing:`, then says how many, in a line beginning `Document permission rows that granted
nothing, removed:`. A person's own group is named `_user_` and the person's user id. Who can open each document
doesn't change. A document those rows hid shows in the Classic Interface's folder list and in searches to the people
who could already open it. If one of them should be kept from someone, move it to a folder they can't read.

## What Was Already in the Trash

The upgrade that brings the Trash's three places (In the Trash, Removed and destroyed) makes an item of everything
already deleted, once, on its first start, so that the Trash, restores and removal all see it. It reads the
Classic Interface's two deleted states, which Healdan's Trash view and the nightly job didn't know before:

- **What is in LogicalDOC's Trash becomes In the Trash.** Each folder deleted from a live folder, with what it held,
  is one item, and so is each document deleted from a live folder. Each item shows the person LogicalDOC recorded as
  deleting it and the time it last changed as the time it was deleted.
- **What was already removed counts as removed on the upgrade's first start.** An administrator sees those items in
  **Removed** at once, and can restore any of them. The nightly job destroys what is left once **Removed Items
  Kept** days have passed, 30 by default (see [Healdan Settings](../../admin/healdan-settings/)). So everything
  removed before the upgrade is destroyed that many days after it, unless an administrator restores it first.
  Nobody is shown as having removed those items, as LogicalDOC didn't record who did.
- **Rows LogicalDOC left in the Trash under a folder that was removed** go to Removed with that folder.

To give yourself longer, raise **Removed Items Kept** (1 to 365 days) on the **Healdan Settings** page before the
upgrade's first night. The setting arrives with the upgrade, so open it as soon as the new version has started, before
2:47 in the morning, when the job first runs. Take a [backup](../backup-and-restore/) first, as destroying is for
good.

The start's log says how many items it made, in a line beginning `Trash items made for what was already deleted:`.

## Reindexing from the Admin App

The upgrade that brings the Admin app's [Search Index](../../admin/system/search-and-indexing/) adds two tables of
Healdan's own, which keep the reindexes started there. The app's first start makes them, as it upgrades Healdan's own
tables on every start, so there's nothing to do by hand. LogicalDOC's tables don't change.
