---
title: Moving from MariaDB to PostgreSQL
weight: 10
---

Healdan runs on PostgreSQL. An install that keeps its data in MariaDB moves it across once, with a command that
Healdan's image brings. The move copies every row and keeps every id, so the documents' files, the search index, the
encryption keys and the storage backends stay as they are, and need nothing doing.

## Who Needs This

An install set up before Healdan moved to PostgreSQL, in November 2026. To check, run this from the folder with
Healdan's `compose.yaml`, on the version you run now:

```bash
docker compose ps db
```

If the `IMAGE` column says `mariadb:11.4`, your install needs this page. If it says `postgres:18` and Healdan runs,
it doesn't, and neither does a new install.

**If you upgraded without moving,** the app stopped as it started, and its log says
`this install's conf volume names MariaDB as its database, …`. Nothing was changed, and MariaDB's data is as it was.
Run `docker compose down`, then go back to the last MariaDB version as
[Upgrade to the Last MariaDB Version](#upgrade-to-the-last-mariadb-version) says, take the backup in
[Before You Start](#before-you-start), and carry on from [Move](#move). On a stack with host folders, the old
version must find MariaDB's folder on the `db` service: if you changed `compose.override.yaml` to the new form in
[Host Folders Instead of Volumes](../host-folders/), with only PostgreSQL's line, put MariaDB's line back beside it,
as [Before You Move the Data](#before-you-move-the-data) shows, before the old version starts. Without it, the old
version would start an empty MariaDB, and make a new, empty install on it beside your files.

## Before You Start

- **If your stack keeps its data in host folders,** read
  [If MariaDB's Data Is in a Host Folder](#if-mariadbs-data-is-in-a-host-folder) now: the backup and several steps
  below take another form there, and a note before each says so.
- **Take a backup** on the version you run now, with MariaDB's own tools, from the folder with Healdan's
  `compose.yaml`. It goes into a folder of its own in your home folder, `healdan-backup`, outside the checkout the
  image is built from: a backup in the checkout would go into each build, and make it slower. With host folders, use
  [The Backup's Files](#the-backups-files)' `tar` in place of the fourth command, which would make empty volumes and
  archive them:

  ```bash
  mkdir -p ~/healdan-backup
  docker compose stop app
  docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb-dump -u"$MARIADB_USER" --single-transaction "$MARIADB_DATABASE"' > ~/healdan-backup/logicaldoc-db.sql
  docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
    -v ~/healdan-backup:/backup busybox tar czf /backup/logicaldoc-files.tgz /conf /repository
  docker compose start app
  ```

  Keep `logicaldoc-db.sql` and `logicaldoc-files.tgz` together, as [Backup and Restore](../backup-and-restore/)
  says. They restore only onto a version that runs on MariaDB.
- **Healdan is down for the move,** from the old stack's `down` until the new version has started. That's as long as
  the new version's build, 5 to 30 minutes as on the first install, and the copy, which moved about 40,000 rows a
  second on a test machine.
- **Keep the same time zone.** MariaDB and PostgreSQL both keep dates without a zone, so the move copies each as the
  clock time it is. Leave `TZ` in `.env` as it is until Healdan has started on PostgreSQL. The move's report shows
  the zone it read the dates in.
- **Take the stack's own MariaDB out of `.env`.** If `.env` sets `DB_HOST` or `DB_PORT` to the stack's own MariaDB,
  `db` and `3306`, as `.env.example` once offered, delete both lines. The old version's defaults are the same, and
  the new version's are PostgreSQL's. Left in, the move and the new version look for PostgreSQL on MariaDB's port,
  and stop with `database not reachable at jdbc:postgresql://db:3306/…`. If they name a MariaDB of your own, see
  [A MariaDB of Your Own](#a-mariadb-of-your-own) instead.
- **Take MariaDB's settings out of `compose.override.yaml`.** If you have that file, and its `db` service has
  anything for MariaDB, such as a `command:` with MariaDB's options, a `healthcheck:` that runs MariaDB's
  `healthcheck.sh`, or `MARIADB_` variables, take it out before [Start Healdan](#start-healdan). The new version's
  `docker compose up -d` reads the file, and PostgreSQL won't start with MariaDB's options, nor pass its health
  check. The old version's own `compose.yaml` has what MariaDB needs. Keep a host folder's mount: see
  [Before You Move the Data](#before-you-move-the-data).

## Upgrade to the Last MariaDB Version

The move takes the database of one version only: the last that runs on MariaDB, which Git knows as the tag
`mariadb-final`. From the folder you cloned Healdan into, fetch it:

```bash
git fetch --tags
git checkout mariadb-final
```

Git says you're in a "detached HEAD" state: that's expected for a tagged version. If
[Changes to LogicalDOC's Tables Before That Version](#changes-to-logicaldocs-tables-before-that-version) lists one
your database doesn't have yet, make it now. Then build and start it, once:

```bash
docker compose up -d --build
docker compose logs -f app
```

When the log shows `Server startup`, sign in to check it works, then press Ctrl+C to leave the log. That start
brings Healdan's own tables up to that version, which is the one the move reads.

### Changes to LogicalDOC's Tables Before That Version

Healdan upgrades its own tables when it starts, but never LogicalDOC's. So a version that needs a change to them has
it made by hand, after the backup and before that version's first start. Without it, the app stops as it starts,
before anyone can sign in, and its log says what the database lacks, such as
`LogicalDOC's tables lack what this version needs: table ld_tenant lacks columns …`. Each change has a check that shows
whether your database has it, as one made by a version that already had the change does. Its SQL leaves alone what
the database already has, so it's safe to run again.

These commands are MariaDB's, for an install on `mariadb-final`. Run them from the folder with Healdan's
`compose.yaml`. Stop the app and start the database first, so a change doesn't have to wait for the app to let go of
the table:

```bash
docker compose stop app
docker compose up -d db
```

The commands reach the stack's own `db` service. If `DB_HOST` points at a MariaDB of your own, run each change's SQL
there instead, in Healdan's database. The stack's `db` then holds none of Healdan's tables, so a check there prints
`0`, and a change fails with `Table '…ld_tenant' doesn't exist`.

#### Quota Thresholds, November 2026

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

## Move

Run these from the folder you cloned Healdan into, once `mariadb-final` has started and you've signed in.

{{< callout type="warning" >}}
**On a stack that keeps its data in host folders,** prepare as
[If MariaDB's Data Is in a Host Folder](#if-mariadbs-data-is-in-a-host-folder) says before Move the Data, and use
that section's form of the commands it names. With these as they stand, the move's MariaDB would start on an empty
volume.
{{< /callout >}}

{{% steps %}}

### Take the Old Stack Down

```bash
docker compose down
```

This stops the app and MariaDB, removes their containers and keeps every volume. **Never add `-v` here:** it would
delete the volumes, and MariaDB's data and the documents with them.

`stop` isn't enough, as it leaves the containers. The old version's `db` container is MariaDB's, under the name the
new version gives PostgreSQL, so it has to go, and `down` removes it. The move then runs MariaDB again under another
name, `mariadb`, on the same data.

### Fetch the New Version and Build It

```bash
git checkout master
git pull
docker compose build
```

The build takes 5 to 30 minutes, as on the first install.

### Move the Data

```bash
docker compose -f compose.yaml -f compose.move.yaml run --rm app move-from-mariadb 2>&1 | tee ~/move-report.txt
```

This starts PostgreSQL, the new `db` service, on a new volume, `logicaldoc_pg-data`, and MariaDB, as the service
`mariadb`, on its old volume, `logicaldoc_db-data`. The move only reads MariaDB. It copies the data, checks every
table, and prints its report, which `tee` also saves as `move-report.txt` in your home folder, outside the checkout
the image is built from. Keep that file: `--rm` removes the container, and its log with it.

It worked when the last line is `Move complete.` If it isn't, see [If It Stops](#if-it-stops).

### Remove the Move's MariaDB

```bash
docker compose -f compose.yaml -f compose.move.yaml rm --stop --force mariadb
```

This stops the MariaDB the move used and removes its container. Its volume stays, for
[Going Back](#going-back).

{{% /steps %}}

## What the Report Says

A move of a small install, with most of the tables' lines left out:

```text
[move] Source: MariaDB 11.4.13, Healdan schema version 12, time zone UTC (the server's, +00:00) ... ok
[move] Target: PostgreSQL 18.4, empty ... ok
[move] Schema: made as a fresh install's, by LogicalDOC's scripts and Healdan's migrations to version 13 (80 tables) ... ok
[move] Copying 78 tables ...
[move]   hd_admin_event: 4 rows
[move]   hd_api_token: 1 row
         …
[move]   ld_workingtime: 0 rows
[move] Copied 78 tables, 742 rows ... ok
[move] Sequences 40 of 40, identity counters 3 of 3 ... ok
[move] Foreign keys rebuilt: 37 of 37
[move] Comparing: rows and checksums match in 78 of 78 tables
[move] Counters: none can hand out an id its table already holds ... ok
[move] Only the source has these, which weren't copied:
[move]   column ld_uniquetag.ld_int1
[move] Move complete.
```

Compose's own lines, about the containers it starts, come before it.

| Line | What it says |
|---|---|
| `Source:` | MariaDB's version, the version of Healdan's tables (12, the last on MariaDB), and the time zone the dates were read in. Check that zone, as [Checking the Time Zone](#checking-the-time-zone) says |
| `Target:` | PostgreSQL's version, and that its database was empty. After a move that stopped part-way, it says `an unfinished move's tables removed` |
| `Schema:` | PostgreSQL's tables, made as a new install's are, with Healdan's own at version 13, the one that goes with MariaDB's 12. Healdan's first start brings them up to date |
| `Copying` | each table, with its rows. A large table also says how many it has copied every 100,000 rows |
| `Copied` | the tables and rows in all |
| `Sequences`, `identity counters` | the counters that hand out ids, each set to carry on after the ids MariaDB handed out |
| `Foreign keys rebuilt` | the links between tables, checked on every row as they're made |
| `Comparing` | every table read back from PostgreSQL: the same rows, and a checksum of every value the same as MariaDB's |
| `Counters` | no counter can hand out an id that a row already has |
| `Only the source has these` | tables, columns and counters that MariaDB has and PostgreSQL's tables don't, which stay in MariaDB. `column ld_uniquetag.ld_int1` is always there: a column LogicalDOC's MariaDB tables have, and nothing reads. Anything else is something added to the database outside Healdan |
| `A space replaced each NUL in …` | with `--replace-nul` only: every value it changed, by table, row and column (see below) |
| `Move complete.` | the move finished, and PostgreSQL is marked as holding it |

### Checking the Time Zone

The `Source` line's zone is MariaDB's, which takes it from `TZ` in `.env`, as the old stack's did, such as
`time zone UTC (the server's, +00:00)`. It has to be the zone your install ran with: `TZ` in `.env`, or `UTC` when
`.env` doesn't set it. If it isn't, don't start Healdan. Put `TZ` back as the install had it, then remove what the
move made and move again. With host folders, PostgreSQL's data is in its folder, not the volume these commands
remove: use [Starting the Move Over](#starting-the-move-over)'s commands instead. On volumes:

```bash
docker compose -f compose.yaml -f compose.move.yaml down
docker volume rm logicaldoc_pg-data
```

That volume holds only the move. Then run [Move the Data](#move-the-data) and the steps after it again.

### Characters PostgreSQL Can't Keep, and `--replace-nul`

PostgreSQL's text can't hold the NUL character, code zero, which shows as nothing. Text that Healdan or LogicalDOC
read out of a file, such as an email's, can have one. The move never drops one quietly: it lists every value that
has one, by table, row and column, and stops:

```text
[move]     table ld_document, row ld_id=4951, column ld_comment: the text holds a NUL character, which PostgreSQL's text types can't hold
         …
[move] ERROR: 1 value in MariaDB holds a NUL character, which PostgreSQL's text can't hold, so the move stopped: …
```

Run the move again with `--replace-nul`, which puts a space in place of each NUL:

```bash
docker compose -f compose.yaml -f compose.move.yaml run --rm app move-from-mariadb --replace-nul 2>&1 | tee ~/move-report.txt
```

Its report lists every value it changed, under `A space replaced each NUL in …`, before `Move complete.` That list
is the only record of what changed, so keep `~/move-report.txt`. Nothing else is changed: a NUL is the only thing
`--replace-nul` touches.

## If It Stops

When the move can't finish, it prints a line beginning `[move] ERROR:` that says why and what to do. The places it
means are listed with it: under it, or, for values PostgreSQL can't hold, above it, where the copy found them. It
never changes MariaDB. A move that stops before it writes to PostgreSQL, as when it can't connect or a check of
either database refuses, leaves PostgreSQL as it was. Some of those errors say so, with `Nothing was changed` or
`changed nothing`, and the others don't. A move that stops later leaves PostgreSQL marked as holding a move that
didn't finish, which Healdan won't start on. Either way, put the cause right, then run
[Move the Data](#move-the-data) again: it removes what an unfinished move made and starts over.

| The error says | What to do |
|---|---|
| `the source's Healdan schema is at version …, and the move takes only version 12` | The install isn't at the last MariaDB version. Go back to it, as below, follow [Upgrade to the Last MariaDB Version](#upgrade-to-the-last-mariadb-version), and move again |
| `the source's Healdan schema history has a migration that didn't succeed (version …)` | An upgrade of Healdan's tables failed on MariaDB. Go back, and start `mariadb-final`: it stops with `Healdan's database migration failed: …`, and that version's own Troubleshooting page, `site/content/install/troubleshooting.md` in its checkout, says what to do. Once it starts, move again |
| `the source has no hd_schema_history table, …` | The move's MariaDB isn't the install's. On a stack with [host folders](#if-mariadbs-data-is-in-a-host-folder) or [a MariaDB of your own](#a-mariadb-of-your-own), see those sections. Otherwise, check `DB_NAME` in `.env` |
| `the source, …, is …, not MariaDB: the move reads only a Healdan install's MariaDB` | `MOVE_SOURCE_HOST` or `MOVE_SOURCE_PORT` names another kind of server: see [A MariaDB of Your Own](#a-mariadb-of-your-own) |
| `the source's LogicalDOC tables lack what the release tagged mariadb-final has: …` | Go back, make the change the message names, as [Changes to LogicalDOC's Tables Before That Version](#changes-to-logicaldocs-tables-before-that-version) gives it, start `mariadb-final` once, and move again |
| `database not reachable at jdbc:postgresql://… after 60s: …` | The move couldn't reach PostgreSQL. Check that `.env` doesn't set `DB_HOST` or `DB_PORT` to MariaDB's, as [Before You Start](#before-you-start) says, and see `docker compose logs db` |
| `database authentication failed for user '…' at jdbc:postgresql://…` | PostgreSQL's role was made with another password than `.env`'s `DB_PASSWORD`, as when `.env` changed after the move's first run made it. See [Changing the Database Password](../configuration/#changing-the-database-password) |
| `the database server is PostgreSQL …, and Healdan needs PostgreSQL 18 or later: …`, `the database's encoding is …`, `the role '…' can't create tables in the schema '…'` or `the role '…' has no schema to create tables in` | The move checks PostgreSQL as Healdan's start does. See the same message in [Troubleshooting](../troubleshooting/#messages-and-what-they-mean) |
| `another move-from-mariadb is running against the target database, …` | Wait for that one to end. One move at a time |
| `what the move read from MariaDB's table … isn't what MariaDB holds, …` | Run the move again. If it stops the same way, [report it](https://github.com/Healdan-Project/healdan/issues) with the lines |
| `… a NUL character, which PostgreSQL's text can't hold …` | See [Characters PostgreSQL Can't Keep](#characters-postgresql-cant-keep-and---replace-nul) |
| `… in MariaDB can't go into PostgreSQL as …` | Each line above it names a value by table, row and column, with the reason: MariaDB's empty date, `0000-00-00`, text longer than PostgreSQL's column takes, or a number out of the column's range. Healdan doesn't write these, so something else did. Each has to be mended in MariaDB, on the old version: [ask for help](https://github.com/Healdan-Project/healdan/issues) with the lines, which name places, never what a value holds |
| `… foreign keys don't hold on the copied rows …`, or `doesn't hold` | Some rows refer to rows their tables lack, such as a document whose folder has gone. Each is listed by table and id, with the column that refers: `table ld_document, row ld_id=4952: ld_folderid=999999 isn't in ld_folder`. Go back, mend those rows on the old version, or [ask for help](https://github.com/Healdan-Project/healdan/issues) with the lines, and move again |
| `can't connect to the source, …` | The move couldn't sign in to MariaDB. Check that `DB_NAME`, `DB_USER` and `DB_PASSWORD` in `.env` are the ones the old version ran with, or see [A MariaDB of Your Own](#a-mariadb-of-your-own) |
| `the target database already holds a finished move from MariaDB` | It's moved already: [start Healdan](#start-healdan). To move again from the start, as after the wrong time zone, PostgreSQL's database has to be emptied first, as [Checking the Time Zone](#checking-the-time-zone) shows |
| `the target database already holds … tables, …, which no move from MariaDB made` | PostgreSQL's database holds something else. The message says what to do, on volumes or with host folders, as [Starting the Move Over](#starting-the-move-over) gives it |
| `the target database holds a move from MariaDB that didn't finish, and the start-up tool's marker, docker_init, …` | Something other than the move changed PostgreSQL's database, and Healdan may have started on it, so the move won't empty it. Keep it as it is, take a backup of it as [Backup and Restore](../backup-and-restore/) says, and [ask for help](https://github.com/Healdan-Project/healdan/issues) with the lines |
| `the target doesn't hold what the source holds` or `a counter could hand out an id already in use` | Run the move again. If it stops the same way, keep both volumes as they are, and [report it](https://github.com/Healdan-Project/healdan/issues) with the lines |
| `reading … failed` or `writing … failed`, or no error at all, as when the server lost power | Run the move again |

**To go back to the old version** to mend something there:

```bash
docker compose -f compose.yaml -f compose.move.yaml down
git checkout mariadb-final
docker compose up -d --build
```

If `DB_HOST` and `DB_PORT` named a MariaDB of your own, put both back in `.env` before the last command. With host
folders, the override's `db` service has to have MariaDB's folder: see
[If MariaDB's Data Is in a Host Folder](#if-mariadbs-data-is-in-a-host-folder). Then take the old stack down and
[move](#move) again from the start. PostgreSQL's unfinished move stays meanwhile, and the next move starts over on it.

## Start Healdan

```bash
docker compose up -d
docker compose logs -f app
```

Its first start on PostgreSQL:

- **Puts the configuration's database settings right,** as the log says:
  `set Healdan's own: hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect, jdbc.dbms=postgresql, jdbc.driver=org.postgresql.Driver`.
- **Checks LogicalDOC's tables,** as every start does.
- **Brings Healdan's own tables up to date.** The move made them at version 13, the one that goes with MariaDB's
  12, and the first start makes any later version's changes on the moved rows, as every upgrade does. On a version
  with none, it has nothing to do.
- **Runs no setup step again.** The move brought the record of every step that had run.
- **Finds everything else as it was:** the documents' files, the storage backends and their markers, the
  encryption keys and the search index all belong to the same ids, so nothing moves or is indexed again.

When the log shows `Server startup`, sign in, open a few documents, and file one.

## Going Back

The move only read MariaDB, so the old version and its volume are as they were. To go back to them, take the new
version down, check out the old one, put the configuration's database settings back to MariaDB's, and start it.

Before you do:

- **With host folders,** the `sed` below has to run on the configuration's folder, and the override has to keep
  MariaDB's folder on the `db` service: use [Going Back with Host Folders](#going-back-with-host-folders)' `sed`
  instead. Without MariaDB's folder, the old version would start an empty MariaDB, and make a new, empty install on it
  beside your files.
- **If `DB_HOST` and `DB_PORT` named a MariaDB of your own,** put both back in `.env` before the old version starts.

```bash
docker compose -f compose.yaml -f compose.move.yaml down
git checkout mariadb-final
docker run --rm -v logicaldoc_logicaldoc-conf:/conf busybox sed -i \
  -e 's/^jdbc\.driver=.*/jdbc.driver=org.mariadb.jdbc.Driver/' -e 's/^jdbc\.dbms=.*/jdbc.dbms=mariadb/' \
  -e 's/^hibernate\.dialect=.*/hibernate.dialect=org.hibernate.dialect.MariaDBDialect/' /conf/context.properties
docker compose up -d --build
```

The first command names `compose.move.yaml` too, so that it also removes the move's MariaDB if it's still there, as
when [Remove the Move's MariaDB](#remove-the-moves-mariadb) was skipped: the old version's `db` mustn't start on the
same data while it runs. Without the `sed`, the old version can't reach MariaDB: its log says `No suitable driver`.
The build takes as long as it did before.

After going back:

- **What was done on PostgreSQL stays there.** Documents filed and changes made since the move aren't in MariaDB.
- **It's only as safe as restoring a backup** once the new version has moved files between
  [storage backends](../../admin/storage-backends/) or removed held copies. MariaDB then names files as they were,
  and finds them only within the holding period: see
  [Restoring Finds Every File](../backup-and-restore/#restoring-finds-every-file).
- **Never run both at once.** They have the same install id, keys and buckets, so each would move, hold and remove
  files the other relies on.
- **To move again later,** start from an empty PostgreSQL database. That deletes everything filed or changed on
  PostgreSQL before you went back, and the doc #s it handed out are handed out again. With the new version checked
  out and the stack down, remove its volume, `docker volume rm logicaldoc_pg-data`, or with host folders empty its
  folder, as [Starting the Move Over](#starting-the-move-over) says, and follow [Move](#move) again.

## Change Your Backups

A backup job that runs `mariadb-dump` backs up nothing now: `docker compose exec db` reaches PostgreSQL, which
answers `mariadb-dump: not found`, and the job leaves an empty file. Change it to
[Backup and Restore](../backup-and-restore/)'s commands, which save PostgreSQL's database as `logicaldoc-db.dump`.

Keep the last MariaDB backup, in `~/healdan-backup`. It restores only onto the old version. This site no longer
gives the commands, but the old version's own copy of Backup and Restore does: with `mariadb-final` checked out,
it's `site/content/install/backup-and-restore.md` in the checkout, under Restoring onto a Fresh Stack. Its commands
read the two files from the folder with `compose.yaml`, so copy them there first, and delete the copies once the
restore has worked.

## Remove the Old Volume

When you're content with Healdan on PostgreSQL, and its backups work, remove MariaDB's data. With host folders, it's
a folder, not this volume: follow [Removing MariaDB's Folder](#removing-mariadbs-folder) instead. On volumes:

```bash
docker volume rm logicaldoc_db-data
```

After that, going back means restoring the last MariaDB backup onto the old version.

## If MariaDB's Data Is in a Host Folder

A stack set up as [Host Folders Instead of Volumes](../host-folders/) keeps its data in folders on the host, which
`compose.override.yaml`, beside `compose.yaml`, mounts. On the old version it has MariaDB's folder on the `db`
service:

```yaml {filename="compose.override.yaml, as the old version had it"}
services:
  app:
    volumes:
      - /srv/logicaldoc/conf:/opt/logicaldoc/conf
      - /srv/logicaldoc/repository:/opt/logicaldoc/repository
      - /srv/logicaldoc/cache:/opt/logicaldoc/cache
  db:
    volumes:
      - /srv/logicaldoc/db:/var/lib/mysql
```

The page's commands that name a volume take another form for such a stack, with your own folders in place of these.

### The Backup's Files

[Before You Start](#before-you-start)'s `tar` reads the configuration and repository folders, and writes into the
same `healdan-backup` folder:

```bash
docker run --rm -v /srv/logicaldoc/conf:/conf -v /srv/logicaldoc/repository:/repository \
  -v ~/healdan-backup:/backup busybox tar czf /backup/logicaldoc-files.tgz /conf /repository
```

### Before You Move the Data

1. **Give PostgreSQL a new, empty folder, and keep MariaDB's.** With the new version checked out, change the `db`
   service in `compose.override.yaml` to this, leaving the `app` service as it is:

   ```yaml {filename="compose.override.yaml, while you move"}
   services:
     app:
       volumes:
         - /srv/logicaldoc/conf:/opt/logicaldoc/conf
         - /srv/logicaldoc/repository:/opt/logicaldoc/repository
         - /srv/logicaldoc/cache:/opt/logicaldoc/cache
     db:
       volumes:
         - /srv/logicaldoc/postgresql:/var/lib/postgresql
         - /srv/logicaldoc/db:/var/lib/mysql
   ```

   The first line is PostgreSQL's. PostgreSQL never reads the second: it keeps MariaDB's folder where the old version's
   `db` looks for it, so that [going back](#going-back) finds MariaDB's data. Without it, the old version would
   start an empty MariaDB, and make a new, empty install on it, signing in as `admin` with `admin`, beside your
   files.
2. **Take MariaDB's settings out** of the override's `db` service, but for the two mounts, as
   [Before You Start](#before-you-start) says.
3. **Point the move's MariaDB at its folder,** in a file of its own beside `compose.yaml`:

   ```yaml {filename="compose.move-folders.yaml"}
   services:
     mariadb:
       volumes:
         - /srv/logicaldoc/db:/var/lib/mysql
   ```

### The Move's Commands

[Move the Data](#move-the-data) and [Remove the Move's MariaDB](#remove-the-moves-mariadb) name both files:

```bash
docker compose -f compose.yaml -f compose.override.yaml -f compose.move.yaml -f compose.move-folders.yaml run --rm app move-from-mariadb 2>&1 | tee ~/move-report.txt
docker compose -f compose.yaml -f compose.override.yaml -f compose.move.yaml -f compose.move-folders.yaml rm --stop --force mariadb
```

With `--replace-nul`, it goes after `move-from-mariadb`. Without the two files, the move's MariaDB would start on an
empty volume, and the move would stop, saying `the source has no hd_schema_history table, …`. The other commands,
such as `docker compose up -d` and `docker compose down`, read `compose.override.yaml` by themselves.

### Starting the Move Over

PostgreSQL's data is in its folder, so `docker volume rm logicaldoc_pg-data` leaves it there, and the next move would
refuse, saying `the target database already holds a finished move from MariaDB`. Wherever the page removes that
volume, as [Checking the Time Zone](#checking-the-time-zone) and Going Back's last point do, take the stack down and
empty PostgreSQL's folder instead. PostgreSQL 18 keeps its data in the folder `18` inside it:

```bash
docker compose -f compose.yaml -f compose.move.yaml down
docker run --rm -v /srv/logicaldoc/postgresql:/pg busybox rm -rf /pg/18
```

### Going Back with Host Folders

[Going Back](#going-back)'s `sed` runs on the configuration's folder:

```bash
docker run --rm -v /srv/logicaldoc/conf:/conf busybox sed -i \
  -e 's/^jdbc\.driver=.*/jdbc.driver=org.mariadb.jdbc.Driver/' -e 's/^jdbc\.dbms=.*/jdbc.dbms=mariadb/' \
  -e 's/^hibernate\.dialect=.*/hibernate.dialect=org.hibernate.dialect.MariaDBDialect/' /conf/context.properties
```

The override's second `db` line, above, gives the old version MariaDB's folder. Leave the override as it is: the old
version reads it too.

### Removing MariaDB's Folder

In place of [Remove the Old Volume](#remove-the-old-volume), when you're content, take the stack down:

```bash
docker compose down
```

Take MariaDB's line, `- /srv/logicaldoc/db:/var/lib/mysql`, out of `compose.override.yaml`, which leaves the `db`
service as [Host Folders Instead of Volumes](../host-folders/) has it, and delete `compose.move-folders.yaml`. Then
empty MariaDB's folder, mounting only that folder, and start again:

```bash
docker run --rm -v /srv/logicaldoc/db:/db busybox find /db -mindepth 1 -delete
docker compose up -d
```

The folder itself stays, empty, for you to remove on the host if you like.

## A MariaDB of Your Own

If `DB_HOST` pointed at a MariaDB server of your own, rather than the stack's, leave `compose.move.yaml` out: it would
start the stack's own MariaDB, on its volume, which holds none of Healdan's data. Tell the move where yours is
instead, with these variables:

| Variable | Default | What it is |
|---|---|---|
| `MOVE_SOURCE_HOST` | `mariadb` | MariaDB's host name or address, which the app's container must reach |
| `MOVE_SOURCE_PORT` | `3306` | its port |
| `MOVE_SOURCE_NAME` | `DB_NAME`, or `logicaldoc` | Healdan's database in it |
| `MOVE_SOURCE_USER` | `DB_USER`, or `logicaldoc` | the user the old version signed in as |
| `MOVE_SOURCE_PASSWORD` | `DB_PASSWORD` | that user's password |

First point `DB_HOST` and `DB_PORT` in `.env` at PostgreSQL: delete both for the stack's own, or set them to a
PostgreSQL of your own, made as [A PostgreSQL of Your Own](../what-runs/#a-postgresql-of-your-own) says. The move writes to the
database they name. Then give the move MariaDB's address. When its password isn't `DB_PASSWORD`, put it in your shell
first, so it isn't on the command line: `read -rs` waits for it, showing nothing, until you press Enter.

```bash
read -rs MOVE_SOURCE_PASSWORD; export MOVE_SOURCE_PASSWORD
docker compose run --rm -e MOVE_SOURCE_HOST=mariadb.example.org -e MOVE_SOURCE_PASSWORD app move-from-mariadb 2>&1 | tee ~/move-report.txt
```

`-e MOVE_SOURCE_PASSWORD` with no value passes it on from your shell. The `Source` line's time zone is that
server's: it has to be the one your install ran with, as above. Skip [Remove the Move's MariaDB](#remove-the-moves-mariadb)
and [Remove the Old Volume](#remove-the-old-volume): your MariaDB is yours to keep or retire.
