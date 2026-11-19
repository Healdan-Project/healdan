# PostgreSQL as Healdan's Database: design

- **Date:** 2026-11-18
- **Tracking issue:** [Healdan-Project/healdan#110](https://github.com/Healdan-Project/healdan/issues/110), and the
  roadmap's backlog row "PostgreSQL as the first-class database" ([`docs/ROADMAP.md`](../../ROADMAP.md) §5,
  principle 9).
- **Status:** designed with the owner on 2026-11-18, choice by choice and then through two playbacks, with a trial
  running beside the session (§4). The owner approved this written spec the same day. Implementation plan:
  [`docs/superpowers/plans/2026-11-18-postgresql.md`](../plans/2026-11-18-postgresql.md); its "Spec Corrections and
  Calls This Plan Makes" lists where it decides what this spec left open. Not built.
- **Inputs:**
  - the [PostgreSQL recon](../../reference/postgresql-recon.md) of 2026-11-03, re-checked on 2026-11-18, whose
    sections this spec cites as "recon §n" and whose questions Q1 to Q15 it answers (§15);
  - the [open-source databases spec](2026-11-01-open-source-databases-design.md), which this spec carries forward;
  - the [Docker stack spec](2026-10-15-docker-compose-stack-design.md) (the start-up tool and the conf volume), the
    [S3 storage spec](2026-11-01-s3-storage-backends-design.md) §12 (one install, one install id) and its
    [plan](../plans/2026-11-03-s3-storage-backends.md)'s Global Constraints (the rules new SQL has followed since);
  - a trial of the real stack and of Healdan's Java tests on PostgreSQL 18, run on 2026-11-18 (§4);
  - this repository at `afa9480d9`, and at `97b48b5cf`, one merge later, for the trial. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner
heard and approved. "CE" is LogicalDOC Community Edition, "the start-up tool" is `DockerInit`, and "the switch" is
the change after which Healdan runs on PostgreSQL only.

## 1. Intent

Healdan moves from MariaDB to PostgreSQL, and doesn't keep both. The owner decided on 2026-10-30 that PostgreSQL
becomes the first-class database and that Healdan moves away from MariaDB in time (principle 9). This session settled
how long "in time" is: PostgreSQL becomes the only supported database in one step, and an existing install is carried
across by a command that checks its own work. (owner)

Why one step: the only MariaDB install known is the owner's semi-production server. No server image has been
published, the repository has no forks, and its only releases are the Helper's. Supporting two databases would mean
writing every migration twice, testing both in CI and carrying two sets of commands in the docs, to protect installs
nobody has. And one database lets Healdan use what PostgreSQL offers, where two would hold it to what both accept.

## 2. Scope

### 2.1 Goals (done when)

1. A fresh `docker compose up -d --build` gives a stack on PostgreSQL 18, and the smoke test passes on it on both
   architectures.
2. The owner's server runs on PostgreSQL, moved by the command, with every row accounted for (§13.2).
3. A new image never starts an unmoved install on an empty database (§8.3).
4. Healdan refuses to start on MariaDB, with a message that names the move.
5. Emoji are kept as typed (§7.5), and LogicalDOC's eight narrow text columns hold what they held on MariaDB (§6.4).
6. Every test in a `healdan-*` module runs on PostgreSQL, in CI and locally, and passes (§10.3).
7. The docs, the tests and the start-up tool name PostgreSQL only, except where they describe the move.

### 2.2 Non-goals

- **MariaDB beside PostgreSQL.** No period in which a new install may choose, and no MariaDB in CI after the switch,
  but for the move's fixture (§10.2).
- **Upgrading CE's tables automatically.** An upstream change to CE's tables is still applied by hand, as Upgrading's
  Changes to LogicalDOC's Tables gives it. The widening of §6.4 is a fixed list, not a start on this.
- **Language-aware sorting.** Lists sort as they do today (§6.2). Sorting names as a dictionary does can be asked for
  query by query later, without changing the database's locale.
- **More than one server node.** The locks that assume one node stay as they are.
- **PostgreSQL's own features for their own sake:** full-text search in the database, JSON columns, row-level
  security. Nothing here needs them.
- **Changing the Message-ID rule** (§7.5), or any change to the Helper's behaviour.
- **A way back.** The old MariaDB volume is kept as a fallback (§9.6), but nothing moves data from PostgreSQL to
  MariaDB.

### 2.3 Anti-goals

- A row lost or changed in the move without the command saying so.
- An image that starts an install empty beside a repository full of documents.
- A "temporary" second database that stays.
- A move that needs SQL typed by hand, or that touches the MariaDB volume.
- A fix whose loss no test or check would notice.

### 2.4 Constraints

- **Mergeable with upstream** (principle 5): two small upstream edits (§11.1), and no upstream SQL, `context.xml` or
  `context.properties` changed.
- **Every id is kept** in a move, as files, keys, the read cache and the search index are named by them.
- **One install, one install id:** an old and a new stack, or a copy and its original, never run against the same
  storage backends (the S3 spec's §12).
- **A shipped migration never changes.** PostgreSQL's scripts are new, so they ship once and are then fixed too.
- **One server node,** as today.
- **CI's time to green doesn't grow** by a second smoke run or slower tests (§10).
- **The docs change in the same pull requests** as what they describe.

## 3. What exists

The [recon](../../reference/postgresql-recon.md) has the facts, with file paths. In short:

- **CE's schema loads on PostgreSQL** from its generic scripts without an error, but with eight text columns narrower
  than MariaDB's, finer dates, and one column fewer (recon §2.2, §2.3).
- **CE's use of Hibernate is what fails:** 54 `boolean` fields on `int` columns, which PostgreSQL refuses until one
  Hibernate setting is added (recon §2.5), and typed reads that, on PostgreSQL, quietly roll back the transaction they
  run in, so that every edit of an existing user is lost (recon §2.6).
- **Healdan's eleven Flyway scripts need five kinds of change,** Flyway needs its PostgreSQL module, and
  `HealdanMigrator` refuses PostgreSQL by name (recon §3).
- **Healdan's own SQL passes,** all 466 statements. Two inserts rely on a failed insert, which PostgreSQL doesn't
  allow inside a transaction; one rare lock cycle relies on InnoDB's lock timeout; a NUL in bound text fails; and a
  few behaviours differ (recon §4).
- **Everything around the database is MariaDB's:** the start-up tool, `compose.yaml`, the backup steps, seven test
  scripts and the docs (recon §5).
- **Healdan works around MariaDB's three-byte text** by refusing characters beyond the Basic Multilingual Plane in
  nine places and replacing them with U+FFFD in four (recon §5.6).

## 4. The Trial (2026-11-18)

Until this session, Healdan had never started on PostgreSQL: every finding came from probes beside the app. The owner
approved a throwaway trial, run by two background agents on branches that never merge, while the design went on.

Its patches were the quickest that worked, not this design, and stay on two local branches of the owner's checkout
until the build is done. Nothing below depends on them.

### 4.1 The Stack

The image was built with the translated migrations, Flyway's PostgreSQL module, a `postgresql` branch in
`HealdanMigrator`, the boolean setting in a `hibernate.properties` in `healdan-core`, CE's typed reads converting
numbers in Java, and the start-up tool, `compose.yaml` and the smoke scripts changed to PostgreSQL 18.4 with the
built-in `C.UTF-8` locale.

- **The whole smoke test passed on the first run,** with no fix after those patches: 203 checks through step 8, and
  step 9's 166 browser tests in Chromium and WebKit. Step 5d, the Helper's, wasn't run. The same commit on MariaDB
  passed on the same machine.
- **It passed again with the app as an ordinary role** that owns its database (steps 1 to 8).
- **Flyway applied all eleven scripts on a real start,** and every seed ran.
- **PostgreSQL's own log held two errors in a full run,** both the start-up tool's probes of the empty database.
- **Without the boolean setting the app never starts:** the first seed fails with `column "ld_enabled" is of type
  integer but expression is of type boolean`.
- **Without the typed-reads fix it starts and logs nothing,** in its own log or PostgreSQL's, and twelve smoke checks
  fail on what the app then does: six Office files stay queued for a preview with no attempt made, and a sign-in
  through the provider loses its link to the local account, whose password still gets a token. So no log shows this
  fault, and the smoke test already does.
- **New in CE's code, one thing:** `StatsCollector` counts twelve tables this edition doesn't have. Each count fails
  with `42P01` and its two retries with `25P02`, 36 error lines a run. Each is a call of its own, so the statistics
  are still saved.
- **New in Healdan's code: nothing.**
- **Confirmed from the recon:** a NUL inside a query parameter is a `500` (§4.7); the admins' tag search misses a tag
  with a backslash (§2.4); and CE's `ld_path like '…/%'` plans as an index scan.
- **For the container:** `--locale=C` has to go with the built-in provider's flags, or the database's `datcollate`
  reads `en_US.utf8`; the image's first start runs a temporary server on its socket only, so the health check has to
  go over TCP; and a password with a space, a backslash, `$`, `#` and an accented letter works.
- **Not reached:** most of the Classic Interface's services (a walk of 11 of its RPC methods and 12 of its data
  servlets, and about 170 REST and data calls as `admin`, logged no PostgreSQL error), CMIS, the paths of people who
  aren't admins outside the smoke test, the Docker tests run by hand, and real volumes of data.

### 4.2 The Java Tests

An experimental base class ran `healdan-core`'s, `healdan-api`'s and `healdan-storage`'s suites on PostgreSQL 18.4.
`AbstractHealdanTestCase` made a database for each test, handed upstream's `AbstractTestCase` no scripts, so that its
HSQLDB-only steps return early, loaded CE's core script itself and rewrote the config resource. It needed no upstream
edit.

- **2,321 of 2,367 tests passed.** Five were skipped, by what look like file-permission checks that skip when Maven
  runs as root, and 41 failed.
- **34 failed on the tests' own HSQLDB assumptions:** `FixtureBackup.restore`'s `set database referential integrity`
  (25, all `VetoAtomicityTest`'s), names read in upper case (4), `HealdanMigratorTest`'s refusal of PostgreSQL and its
  fixtures under `hsqldb/` (3), and HSQLDB's trigger syntax in `StorageAdminTest` (2). About a day's work.
- **7 failed on a real difference,** all from one rule: on PostgreSQL a failed statement ends its transaction (recon
  §4.2).
  - A save that fails part-way (`SaveTest`, 2; `FilingTest`, 1): CE's failed version write takes the rest of its
    transaction with it, and the API answers a plain `500` where it said what was left of the save.
  - A failed `hd_email` insert that `EmailDocumentListener` logs and swallows (`EmailIndexTest`, 1) now fails the
    save around it, where Healdan promises an index failure never refuses an email.
  - A unique-index violation reaches the caller as `GenericJDBCException`, not `ConstraintViolationException`
    (`SignInDataTest`, 2; `StorageDataTest`, 1). The store still fails and stores nothing, and no production code
    was found that reads the type.
- **Nothing failed on CE's own SQL,** on sorting, on where NULLs sort, on trailing spaces, on the precision of times
  or on isolation. The concurrency tests passed, and so did all 13 files that set HSQLDB's MVCC mode, with the
  statement gone.
- **Without the typed-reads fix, 13 of 52 tests fail** in four classes about users, which pass with it. So tests on
  PostgreSQL see the lost user saves of recon §2.6, which tests on HSQLDB never can.
- **A test costs no more time.** Set-up for each test took 3.73 s on PostgreSQL and 3.95 s on HSQLDB, side by side:
  the Spring context 2.44 s, the migrations and seeds 1.14 s, loading CE's schema 0.10 s, creating the database
  0.02 s and dropping it 0.03 s. A template database bought nothing, as the cost is in the context and the seeds.
  These are samples of 38 and 106 tests on a loaded machine; no full suite was timed cleanly.

## 5. Approaches

Each choice below was put to the owner with its alternatives, and the owner picked.

### 5.1 How Long MariaDB Stays

| Approach | For | Against |
|---|---|---|
| **A clean switch** (picked) | Migrations written once, one database in CI and in the docs, and PostgreSQL's features usable | The owner's server moves through a one-way door, so the move has to be rehearsed |
| An overlap, then MariaDB dropped | Protects installs nobody knows about | Every migration twice, both in CI, both in the docs, for as long as it lasts; no such install is known |
| Both for good | | Against principle 9 |

### 5.2 How an Existing Install Moves

| Approach | For | Against |
|---|---|---|
| **A move command in the image** (picked; recon §5.8's option C) | It checks its own work and refuses to finish on a difference; it can be rehearsed on a copy and tested in CI; no extra tool; anyone who built from source gets a documented path | Code to write and keep until it's removed; MariaDB's driver stays in the image until then |
| A one-off move with pgloader (option A) | Less to build | Four fix-ups by hand in the recon's trial and two more since; pgloader dropped a table's rows and still exited with 0, and strips NUL without saying so, so the checks would be Healdan's to write anyway; nothing for anyone else |
| pgloader's own schema, a CSV dump, a re-import, or no move (options B, D, E, F) | | The recon's §5.8 gives each one's faults; none keeps the schema Healdan's scripts make, the ids and a single database |

### 5.3 CE's Narrow Columns

| Approach | For | Against |
|---|---|---|
| **Widen the eight columns** (picked) | PostgreSQL holds what MariaDB held, so an avatar saves and the move can't fail on a long value; no upstream file is edited | One written exception to the rule that Healdan never changes the structure of CE's tables |
| Keep CE's limits | The rule stays whole | Avatars over 4,000 characters fail to save, and the move stops on any value too long |
| Healdan's own PostgreSQL variant of CE's schema script | One file holds the whole schema | It drifts from upstream each time upstream adds a table, and the start-up tool's check would trust it |

### 5.4 Characters Beyond the Basic Multilingual Plane

| Approach | For | Against |
|---|---|---|
| **Keep them as typed** (picked) | An email's subject keeps its emoji; the check narrows to what PostgreSQL can't store, which also turns a stray NUL from a server error into a refusal | What was stored with U+FFFD stays that way |
| Keep today's limits | Nothing changes for people | The refusals blame a database that no longer has the limit, and need rewording anyway |

### 5.5 The Database's Locale

| Approach | For | Against |
|---|---|---|
| **PostgreSQL's built-in `C.UTF-8`** (picked) | Sorts as MariaDB's `utf8_bin` does today, lower-cases accented letters, keeps CE's folder lookups on their index (recon §2.3), and never changes with an image's operating system | Accented names sort after `Z`, as today |
| A language-aware locale (ICU or glibc's `en_US`) | `éclair` sorts beside `e` | CE's folder lookups scan the table unless Healdan adds an index to CE's folder table; the order can change with the image's operating system, and indexes then need rebuilding |
| Plain `C` | Keeps the index | Lower-cases only ASCII, which breaks the case-insensitive searches |

### 5.6 Where Healdan's Java Tests Run

| Approach | For | Against |
|---|---|---|
| **PostgreSQL only** (picked) | The tests run on the database Healdan runs on, and see what HSQLDB accepts (§4.2); the `hsqldb/` migrations go, so each migration is written once; no slower | Running Healdan's tests needs a PostgreSQL to hand; 34 tests to mend |
| Keep HSQLDB, with PostgreSQL in the smoke test only | Tests run as today | New SQL meets PostgreSQL only where the smoke test reaches it; every migration twice, for good |
| Both | | Twice the test time, and both folders |

## 6. The Database

### 6.1 Version (owner)

PostgreSQL 18, pinned as `postgres:18` in `compose.yaml`, as the stack pins `mariadb:11.4` today. A later move to
PostgreSQL 19 is a documented dump and restore, never automatic: a new major can't open an older one's data folder.
The start-up tool refuses a server older than 18 and, on a newer major than the image pins, logs that it's untested.
(call)

### 6.2 Encoding and Locale (owner)

The database is UTF-8 with PostgreSQL's built-in locale provider and its `C.UTF-8` locale (`pg_c_utf8`). The stack's
`db` service creates it so (`POSTGRES_INITDB_ARGS`, §8.1; the trial used `--encoding=UTF8 --locale-provider=builtin
--builtin-locale=C.UTF-8 --locale=C`). A database's locale can't be changed afterwards without a dump and restore.

On a PostgreSQL the admin runs themself (`DB_HOST`), the docs give the `create database` that makes it the same. The
start-up tool refuses a database that isn't UTF-8, and logs a warning, naming what follows, when its collation isn't
one that sorts by code point: lists sort in another order, and CE's folder lookups don't use their index. (call)

### 6.3 The App's Role (call)

The app connects as an ordinary role that owns its database, as it connects to MariaDB as an ordinary user today, and
not as PostgreSQL's superuser. The stack's `db` service makes the role and the database on its first start, from
`DB_USER`, `DB_PASSWORD` and `DB_NAME`, and leaves the superuser reachable only from inside the `db` container, where
backups and an admin's `psql` run. The owner of a database can create tables in its `public` schema, which is all
the start-up tool and Flyway need. Healdan's tables and CE's stay in `public` (recon §3.3).

### 6.4 CE's Tables, and the One Exception (owner)

CE's tables are made by its generic scripts, as the trial made them: the start-up tool's `DBMS` becomes `postgresql`,
no plugin has a variant for it, so each gets its generic script (recon §2.1).

**Healdan then widens eight columns to `text`,** the ones CE's MariaDB variant has as `mediumtext` and its generic
script as a `varchar` PostgreSQL enforces:

| Table | Columns | Generic script |
|---|---|---|
| `ld_user` | `ld_avatar` | `varchar(4000)` |
| `ld_systemmessage` | `ld_messagetext` | `varchar(4000)` |
| `ld_filler` | `ld_automation`, `ld_automationbefore` | `varchar(10000)` |
| `ld_folder` | `ld_tile` | `varchar(10000)` |
| `ld_menu` | `ld_automation` | `varchar(10000)` |
| `ld_generic` | `ld_text1` | `varchar(100000)` |
| `ld_legal` | `ld_content` | `varchar(100000)` |

- **The rule becomes:** Healdan never changes the structure of CE's `ld_` tables, except that on PostgreSQL it widens
  these eight columns to `text`. The list is fixed, a column is only widened, and nothing is added, renamed or
  dropped. `CLAUDE.md` and the roadmap's §6 say so from the pull request that builds it.
- **Where it runs (call):** a Flyway migration of its own, the next version after the translated ones (§6.5). Flyway
  runs it once on every database, whichever way CE's tables were made (the start-up tool, the move command or the
  setup wizard), and records it. Hibernate maps all eight as `String`, so the mapping doesn't change.
- **The start-up tool's check of CE's tables** compares tables and columns, not types (recon §5.1), so it stays
  honest: it reads CE's generic scripts, which no Healdan file replaces.
- **What stays different from MariaDB, and is accepted (call):** CE's dates keep microseconds where MariaDB's kept
  whole seconds, which ends the second-level cache's mismatch the capability map records (recon §2.3); a few columns
  are nullable where MariaDB's weren't, which no Java relies on; and `ld_uniquetag.ld_int1`, which no Java reads,
  doesn't exist.

### 6.5 Healdan's Tables

- **A `postgresql/` folder** beside the others in `healdan-core/src/main/resources/healdan/db/migration/`, with the
  scripts that have shipped translated one for one, same versions and names, by the recon's five changes (§3.2): no
  table options, `timestamp` for `datetime`, `create index` after the table for an inline index, `alter column … type
  text` for V7's `modify`, and `generated by default as identity` for V11's `auto_increment`. The trial applied all
  eleven on a real start. One for one, not one script for the end state, so that a version means the same on both
  sides of a move (§9.2). (call)
- **Then the widening of §6.4,** as the next version.
- **`flyway-database-postgresql`** joins `healdan-core`'s dependencies at Flyway's version. The plugin zip's
  `org.flywaydb:*` include ships it, and with it `flyway-database-cockroachdb`, which it depends on (the trial).
- **`HealdanMigrator`** takes PostgreSQL. After the switch it takes nothing else, and refuses MariaDB and MySQL with
  a message that names the Moving from MariaDB page. Its recovery message becomes PostgreSQL's: a failed script is
  rolled back whole, so "fix the cause and restart" is usually all, with the steps for a script Flyway ran outside a
  transaction (`success = false`; recon §3.3).
- **The `hsqldb/` folder and `flyway-database-hsqldb` go** when Healdan's tests move to PostgreSQL (§10.3), and
  `HealdanMigrator` stops taking HSQLDB then.
- **The `mariadb/` folder and `flyway-mysql` go** at the switch. A shipped script still never changes; these stop
  shipping. The move command needs neither: it reads the source's history, and never migrates it (§9.2).
- **From then on a migration is one script,** in `postgresql/`.
- **Until the switch merges,** a migration another change adds goes into every folder that exists, `postgresql/`
  included, and the move's expected version and fixture follow (§9.2, §10.2).

## 7. CE and Healdan on PostgreSQL

### 7.1 Booleans (call)

`healdan-core` ships a `hibernate.properties` at its classpath root with
`hibernate.type.preferred_boolean_jdbc_type=INTEGER`. Hibernate reads it when it starts, with no upstream edit, and
binds every Java `boolean` as an integer. The trial proved this route in the WAR and in the tests: without the file,
no test gets past its first seed. A test of its own asserts the setting is in force in the session factory, and every
Healdan test's set-up checks it before the seeds run, so that a second `hibernate.properties` on the classpath, or
Hibernate dropping its incubating setting, is named by the test that fails (changed in the build: this left the naming
to the test of its own, `BooleanBindingTest`; on PostgreSQL a lost setting fails every test in its seeds first, with
an error that doesn't name it, so `AbstractHealdanTestCase.setUp` checks it first and fails naming it; plan, Decision
Log 6).

### 7.2 Typed Reads (call)

CE's `queryForObject` asks the driver for a `Long` from whatever the query returns, and pgjdbc gives one only from a
`bigint`. The failure marks the transaction for rollback before CE's retry reads the value another way (recon §2.6).

- **The fix is an upstream edit** in `HibernatePersistentObjectDAO`: where it reads one value by type
  (`queryForObject`, and `queryForList`'s single-column form), it hands the value the driver returned to a fork-owned
  helper in `logicaldoc-core`, beside `HealdanHooks`, which converts in Java a number held exactly (an integer type,
  `BigInteger` or `BigDecimal`), to a `Long` or an `Integer` only when that type holds it exactly, and to a `Double`,
  a `Float`, a `BigDecimal` or text, and asks the driver for the rest, as before (changed in the build: this said it
  asks the driver only for what it can't convert; a fraction asked for as a whole number, a value out of range, a
  value that is a `Double` or `Float` and anything that isn't a number still go to the driver, so MariaDB and HSQLDB
  answer as they did, and every typed read today's callers make is still answered without the driver on PostgreSQL;
  plan, Decision Log 7). A NULL is null without a typed read. `queryForLong`'s retry then never runs.
- **Its test** is a unit test of the helper with result sets that refuse what pgjdbc refuses, named in CI's
  `UPSTREAM_TESTS`. Healdan's tests on PostgreSQL watch the fault itself: 13 of them fail without the fix (§4.2), and
  one edits a user and reads the edit back (§10.3). The smoke test watches it too (§4.1).
- **Not chosen:** a Healdan subclass of the PostgreSQL dialect that doesn't mark failures the driver raises itself.
  It needs no upstream edit, but it reaches only installs whose `hibernate.dialect` names it, and it leaves the
  failing read in place.

### 7.3 Statements That May Fail Inside a Transaction

On PostgreSQL a statement that fails ends its transaction, whatever Java then catches (recon §4.2, and the trial's
seven real failures, §4.2). The rule for Healdan's code, old and new: **no statement is expected to fail inside a
transaction, and one that may fail without failing its caller runs inside a savepoint.** (call)

- **The two inserts that rely on a duplicate key** stop relying on it, with PostgreSQL's own syntax, which the tests
  can now run: `PreviewJobs.add` becomes `insert … on conflict do nothing`, and `EmailIndex.put` an `insert … on
  conflict … do update`. Each waits for another transaction's insert of the same key and then carries on, which
  closes the race the recon reproduced.
- **`healdan-storage`'s `Savepoints.insertIfAbsent`** (`StoredFiles.recordWrite`, `HeldCopies.hold`) lets a duplicate
  fail inside a savepoint by design. That's safe, but PostgreSQL logs each one as an error, which §10.1's check of
  its log would trip over now and then. At the switch those inserts become `on conflict do nothing` too.
- **`Savepoints` moves from `healdan-storage` to `healdan-core`,** and becomes public, for what's left: work that
  isn't expected to fail, but mustn't fail its caller if it does.
- **Listeners that log and swallow a failure inside CE's save** run their statements inside a savepoint, so that the
  swallow keeps the save, as it does on MariaDB: `EmailDocumentListener`'s `afterStore`, `beforeCheckin` and
  `afterCheckin`, `PreviewDocumentListener`'s `afterFileStore`, `beforeStore` and `afterStore`, and the two
  favourite-names listeners (recon §4.2's list). Only their statements: a guard that is Java alone, such as the
  preview listener's check of the event and the file name, stays outside, as a savepoint is two round trips on every
  save. A savepoint is rolled back on anything the work throws, an `Error` included, as these listeners swallow any
  `Throwable` (changed in the build: this named only `EmailDocumentListener.afterStore` and two of
  `PreviewDocumentListener`'s methods, which the plan widened, and said neither what stays outside the savepoint nor
  that an `Error` rolls it back, which would otherwise leave the work's partial writes in a save that then commits;
  plan, Decision Log 13 and 14). An index failure never refuses an email again, and `EmailIndexTest` says so on
  PostgreSQL.
- **A save or a filing that fails part-way still says what's left.** `VersionResource.confirmStored` reads the
  document back and words the answer, but only when CE swallowed its own failure. On PostgreSQL CE's failed version
  write fails CE's next statement too, and the failure escapes as a plain `500`. So a save, a filing and an email's
  filing run the read-back after such a failure as well, once CE's transaction has ended, and answer with what the
  document then is, whether the failure comes out of CE's `checkin` or, as an `ExecutionException`, out of the
  version future CE hands back (changed in the build: a save whose version isn't recorded fails in the future's
  `get()`, not in `checkin`, so `VersionResource.checkIn`'s catch takes that exception too; plan, Decision Log 17).
  `SaveTest` and `FilingTest` assert the state PostgreSQL leaves.
- **A duplicate that fails a store** is promised only to fail it and store nothing, not to arrive as one exception
  type. `SignInIdentityDAO`'s documentation and the three tests say that.
- **A boolean column is still an `int`,** in Healdan's tables too: the setting of §7.1 binds every Java `boolean` as
  an integer, which a PostgreSQL `boolean` column would refuse.

### 7.4 What Differs, and What Healdan Does About It (call)

PostgreSQL's behaviour stands. Where Healdan's code assumed MariaDB's, the code changes:

- **A lock timeout.** The app's connections set `lock_timeout` to 50 seconds, MariaDB's default lock wait, so the
  lock cycle `FileLocks` describes still ends by failing one of its three (recon §4.6). The start-up tool owns the
  setting (§8.2). On PostgreSQL the timeout fails the statement's whole transaction, which its callers already retry
  or mend. `FileLocks`' comment says PostgreSQL's timeout, not InnoDB's.
- **NULLs in a sort.** Each `order by` on a key that can be NULL says where NULLs go, keeping today's order (recon
  §4.3's sites: `UserResource`, `DeviceHolds`, `ApiViews`, `DocumentResource`, `VersionResource`), unless its rows
  are all NULL or none (changed in the build: `TrashStore`'s sort on `removed` is on a column that can be NULL, but
  each of the Trash's views holds all NULLs or none, so it sorts the same on both databases without a clause; plan,
  Decision Log 20).
- **Trailing spaces.** PostgreSQL compares them, and that stands. Where a person types the value that's compared
  (a legacy doc #, a listing's filter), it's trimmed in Java first (recon §4.3's sites). (changed in the build: this
  also named a sign-in identity's name or email, which isn't trimmed, as it has to match its provider's claim
  exactly, the plan's call 9; and a listing's filter was trimmed already, by the API's `Filters.of`, which also
  refuses a matter that isn't digits, a dot and digits with `400 bad-request` before the listing is reached, so
  nothing changed there; plan, Decision Log 18. The legacy doc # is stripped of whitespace at both ends, which
  changes two edge answers on MariaDB too, §13.1; Decision Log 21.)
- **Finer times.** `VersionResource`'s tie on a version's date no longer has whole seconds to rely on, and is
  ordered so that it doesn't need them.
- **Isolation** stays PostgreSQL's default, `READ COMMITTED`, which is what Healdan's conditional updates already
  rely on (recon §4.3).
- **CE's own known faults stay:** the admins' tag search misses a tag with a backslash (recon §2.4), which goes into
  Known Limitations; the template import's `lower(bigint)`, which nothing can reach; and `StatsCollector`'s counts of
  twelve tables this edition lacks (§4.1), which only fill the log.

### 7.5 Text Healdan Can Keep (owner)

Characters beyond the Basic Multilingual Plane are kept as typed. What PostgreSQL can't store is NUL, and a lone
surrogate isn't a character at all.

- **`BasicPlane` gives way to a check of those two** (call: `StorableText`, in the same package). The nine places that
  refuse text refuse only text holding a NUL or a lone surrogate, with a message that says the text has a character
  Healdan can't keep and doesn't name emoji or the database. The four that replace put a space for NUL, as the email
  code does today, and U+FFFD for a lone surrogate, and leave everything else alone.
- **The API refuses a NUL in a query parameter** with a `400`, where the recon found a `500` (§4.7). (call)
- **Message-IDs keep today's rule,** to the character: `MessageIds` on the server and in the Helper still replace
  each character beyond the plane with U+FFFD, because the two have to agree and `hd_email` is indexed by the result.
  The replacement moves into `MessageIds` itself. The Helper's code doesn't change, only a comment that blames
  MariaDB.
- **What's stored stays:** a title filed with U+FFFD keeps it, as the character it stood for was never saved.
- **The User app's `favorites.invalid` string** and the API docs' three lines that blame the database are reworded.

## 8. The Container

### 8.1 `compose.yaml`

- **The `db` service** is `postgres:18`, with the locale flags of §6.2, the role of §6.3, a `pg_isready` health check
  over TCP (the image's first start runs a temporary server on its socket only, which a check on the socket would
  take for the real one), `TZ`, and its data in a volume with a **new name**, `pg-data`, mounted at
  `/var/lib/postgresql`, where the 18 image keeps its data (recon §5.2). The MariaDB volume, `db-data`, is no longer
  named in `compose.yaml`, so nothing mounts or removes it.
- **The `app` service** defaults `DB_PORT` to `5432`. `DB_HOST`, `DB_NAME`, `DB_USER` and `DB_PASSWORD` keep their
  names and meanings, and `.env.example` says PostgreSQL.
- **`compose.dev.yaml`** publishes PostgreSQL's port on `DEV_DB_PORT`, by default `5432`.

### 8.2 The Start-Up Tool

`DockerInit` and `ConfigPlan` become PostgreSQL's, as the trial patched them (recon §5.1's table):

- the driver, the URL (`jdbc:postgresql://`, port `5432`) and `DBMS = postgresql`;
- a missing table read by SQLSTATE `42P01`, and the columns of CE's tables read with `current_schema()`;
- **`jdbc.driver`, `jdbc.dbms` (`postgresql`, the spelling CE's `isPostgreSQL()` and `StatsCollector` expect) and
  `hibernate.dialect` (`org.hibernate.dialect.PostgreSQLDialect`) are set on every start,** as `jdbc.url` is, and
  read back, where today they're seeded once. A conf volume that came from MariaDB is so put right at its first
  start after the move. (call)
- the lock timeout of §7.4, in a key the tool owns;
- the checks of §6.1 and §6.2: the server's version, the database's encoding and its collation.

### 8.3 The Guard Against Starting an Install Empty (call)

After the switch, `compose.yaml`'s `db` is an empty PostgreSQL. An install that pulls the new version and starts it
without moving would get a new, empty schema beside its conf and repository volumes, and a new install id that its
storage backends' markers refuse.

So the start-up tool looks before it changes anything: before it rewrites the conf volume's database keys, which it
does on every start, and before it creates a schema. It stops, changing nothing, in three cases:

- **The conf volume's `context.properties` names MariaDB** (`jdbc.url` or `jdbc.dbms`), and the PostgreSQL database
  the environment names doesn't hold a finished move, or doesn't answer. It says that the install's data is in
  MariaDB and where the move is described.
- **The database holds an unfinished move** (§9.5). It says to run the move again.
- **The database is empty, and the repository volume already holds an install's files:** Original's folder has a
  storage marker or a document. This is the same mistake with the conf volume lost or made afresh, and a restore
  whose files came back before its database. It says to restore the database first, or to start a new install on
  empty volumes. The documented restore loads the database before the app's first start, so it never meets this.

A fresh install passes all three: the entrypoint copies the image's defaults into its conf volume before the tool
runs, and those name neither database, and its repository is empty.

### 8.4 The Setup Wizard (call)

The container never runs it. It starts on PostgreSQL, where it starts on MariaDB today, in the same edited lines of
`Setup.java`. Its MariaDB and MySQL entries stay, as the owner decided for MySQL on 2026-11-01: `HealdanMigrator`
refuses both.

## 9. Moving from MariaDB

### 9.1 What the Admin Does

With the install at the last MariaDB version (§9.2), and a backup taken:

```text
docker compose down                      (on the old version: stops the app and MariaDB, keeps every volume)
git checkout <a version with PostgreSQL>
docker compose build
docker compose -f compose.yaml -f compose.move.yaml run --rm app move-from-mariadb
    Source: MariaDB 11.4, Healdan schema version 11 ... ok
    Target: PostgreSQL 18.4, empty ... ok
    Copying 75 tables ...
    Sequences 40 of 40, identity counters 1 of 1 ... ok
    Comparing: rows and checksums match in 75 of 75 tables
    Foreign keys rebuilt: 36 of 36
    Move complete.
docker compose -f compose.yaml -f compose.move.yaml stop mariadb
docker compose up -d
```

- **The old stack is taken down first, not only its app.** The old version's MariaDB is the service `db`, the name
  the new version gives PostgreSQL. Left running, Compose would replace it while the overlay's MariaDB started on the
  same volume, two servers on one data folder. `down` without `-v` removes the containers and keeps the volumes, and
  the page says which flag never to add.
- **`compose.move.yaml`** is an overlay that runs `mariadb:11.4` on the old `db-data` volume as a service named
  `mariadb`, and tells the command where it is. An install whose MariaDB is its own server sets the source's host,
  port, name, user and password in the environment instead. Its MariaDB outlives the command, so the page stops it
  afterwards.
- **`move-from-mariadb`** is an argument the entrypoint knows, and acts on before anything else: before it changes
  a volume's owner, seeds the conf volume or makes the repository's folders. It runs the command as the app user, in
  place of the start-up tool and Tomcat, and exits with its status.
- The numbers above are examples.

### 9.2 What It Requires

- **The source at the last MariaDB version.** A git tag, `mariadb-final`, marks the last commit that runs on MariaDB
  (§13.1). The install is upgraded to it and started once, so that its Flyway history is at that commit's last
  version and its seeds have run. The command reads `hd_schema_history` and refuses a source at any other version, or
  with a failed row. It also runs the start-up tool's check of CE's tables on the source, against the `.mariadb`
  plan, and refuses a source that lacks a column.
- **The target empty,** or holding only an unfinished move (§9.5): PostgreSQL 18 or later, UTF-8.
- **The app stopped,** which the page says and the command can't see.
- **The same `TZ` on both sides:** neither database's dates carry a zone.

### 9.3 What It Does

1. **Marks the target** as a move in progress.
2. **Makes the schema as a fresh install's:** CE's generic scripts as the start-up tool runs them, then Healdan's
   migrations through `HealdanMigrator`'s Flyway settings, the widening included. It runs no seed and never starts
   the app, so the install id and every `seed.` marker come from the source (recon §5.8).
3. **Empties the tables** of the rows the scripts inserted, and drops the foreign keys, keeping their definitions.
   It doesn't touch `hd_schema_history`, which is the target's own: nine of the eleven scripts' checksums differ
   between the two folders (recon §3.2), so the source's history is never copied.
4. **Copies each table** that both sides have, column by column by name, in batches, reading the source through a
   read-only connection:
   - a date goes across as the wall-clock time it is, with no zone conversion;
   - a table or a column only the source has isn't copied, and the report lists it. `ld_uniquetag.ld_int1` is the
     one known (recon §2.3);
   - a column only the target has stops the move: the source isn't at the version §9.2 asks for;
   - a text value holding a NUL, which PostgreSQL can't store, stops the move, and the report names every one: the
     table, the row's key and the column. Nothing is stripped quietly. Run again with an option that says so, the
     command puts a space for each NUL, as Healdan's email code does, lists every value it changed, and compares
     those values as changed. So the admin decides, and types no SQL.
5. **Carries the counters:** each sequence's next value, from MariaDB's `next_not_cached_value` (which may leave a
   gap, never a repeat), and each identity column's, `hd_trash.hd_id` today, from its highest value.
6. **Rebuilds the foreign keys,** which checks every reference.
7. **Compares both sides,** table by table: the row count, and a checksum over every copied value of every row, worked
   out the same way from each database's rows, in an order that doesn't depend on either database's sorting. It also
   checks that no counter can hand out an id its table already holds. For a sequence that's by the arithmetic of
   CE's id generator, which makes fifty ids from each value of a sequence (`hibernate.id.optimizer=hilo`, over
   sequences that step by 50), not by comparing the sequence's value with the highest id; for an identity column it's
   the value itself.
8. **Marks the move finished** and the schema initialised (the start-up tool's marker), last, and prints its report.

### 9.4 What It Never Does

It never writes to MariaDB, never reads or writes the conf, repository or cache volumes (nor does the entrypoint on
its way to it, §9.1), and never contacts a storage backend. The ids it keeps are what tie the database to all of
those: files in Original's folder and on every backend, key files, encrypted files' associated data, the read cache
and the search index (recon §5.8).

### 9.5 When It Fails

Any failed check, error or interruption leaves the target marked unfinished and the command's exit status non-zero.
The start-up tool won't start the app on it (§8.3). Running the command again starts over: it removes what the
unfinished move made, and only that. A target that holds anything else is refused.

### 9.6 After the Move

- **The first start** finds the schema and the marker, sets the conf volume's database keys (§8.2), checks CE's
  tables, and starts Tomcat. Flyway has nothing to apply and no seed is new.
- **The way back** is the old version and the old volume, which nothing has touched, and it's safe only until the new
  stack's mover and purge have run: after that, going back behaves like restoring a backup, with the S3 spec's
  holding period as its limit (recon §5.8). The page says so, and says the two stacks must never run at once.
- **The backup job changes with the database** (§12): a job that runs `mariadb-dump` backs up nothing after the move.
- **The old volume is removed by hand,** when the admin is content. The page gives the command.

### 9.7 How Long the Command Stays (owner)

Until the owner says so: about a month after the owner's server has moved. A follow-up change then removes the
command, its overlay, its test and its fixture, drops MariaDB's driver from the WAR (an upstream POM edit, as SQL
Server's was) and adds the driver to `check-database-drivers.py`. The Moving from MariaDB page stays, saying to check
out the last version that had the command, move, and then upgrade.

## 10. Tests and CI

### 10.1 The Smoke Test and the Docker Tests

- **`docker/smoke-test.sh` and the scripts it runs** use `psql` where they use `mariadb` (recon §5.4's table), as the
  trial patched them. PostgreSQL replaces MariaDB in each architecture's job, so the image workflow gains no second
  run.
- **New checks:** an emoji in a matter's name, a document's name and an email's subject is kept; a NUL in a query
  parameter is a `400`; and a user's edit is still there when read back (§7.2), beside the twelve checks that already
  fail without the typed-reads fix (§4.1).
- **PostgreSQL's log is checked too (call).** A statement PostgreSQL refuses is an `ERROR` line in the `db` service's
  log, whatever the Java around it swallowed, and the trial's full run had two. The smoke test fails on an `ERROR`
  line it doesn't expect, with a short list of those it does: the ones its own steps provoke, and `StatsCollector`'s
  twelve missing tables (§4.1). Nothing Healdan does in the ordinary way may be on that list, which is why §7.3 takes
  every expected duplicate out of the log. That watches CE's own SQL, which nobody has read through, on every run. It
  can't see the typed reads' fault, which never reaches the server. The list is settled over several runs before the
  check may fail a build.
- **`test-init.sh`'s step that runs Upgrading's SQL** follows the page (§12): with no change listed for PostgreSQL
  yet, it has nothing to run until an upstream sync adds one.
- **`test-init.sh`, `test-image.sh` and `test-overlays.sh`,** which are run by hand, move to PostgreSQL, with the
  guard of §8.3 and the checks of §6.1 and §6.2 added to `test-init.sh`.
- **`docker/tools`' unit tests** take PostgreSQL's values, and gain tests of the move's logic that need no database:
  matching columns, carrying values and the checksum.

### 10.2 The Move's Test

`docker/tests/test-move.sh` moves a frozen fixture and starts the app on the result.

- **The fixture** is made once, from the `mariadb-final` commit: a smoke stack's MariaDB dump, conf volume and
  repository volume, after rows that try the move were added (an avatar over 4,000 characters, dates with
  milliseconds, accented text, a sealed refresh token, documents on a local storage backend, a Trash item, advanced
  sequences). It's small, and it never changes, as MariaDB's side never does again.
- **It checks** the report; that a source at an older schema version is refused; that a failed comparison leaves the
  target unfinished and the app refusing to start; that a second run starts over; and, on the started app, that a
  person signs in, a document's bytes come back, the next doc # and the next ids follow the old ones, a delete makes
  a Trash item, and the storage backend's marker is accepted.
- **In CI** it runs in the image workflow's amd64 job, after the smoke test, while the command exists.

### 10.3 Healdan's Java Tests (owner)

Every test in a `healdan-*` module runs on PostgreSQL, and none on HSQLDB.

- **The base class** is the trial's, made permanent: `AbstractHealdanTestCase` makes a database for each test on a
  PostgreSQL server named by the environment, with a default on the local machine; hands upstream's `AbstractTestCase`
  no scripts; loads CE's core script and the test data itself; points the test's configuration at the database; and
  drops it afterwards. No upstream file is edited. A server it can't reach fails the test at once, with a message
  that says how to start one.
- **Each test's database has the locale of §6.2,** whatever the server's default is, so the tests sort and compare as
  a real install does.
- **`healdan-api`'s and `healdan-storage`'s tests** follow through the base class, and take pgjdbc at test scope.
- **The 34 tests of §4.2 are mended:** a PostgreSQL form of `FixtureBackup.restore`, names in lower case, PostgreSQL
  triggers for the two injected faults, and `HealdanMigratorTest`'s fixtures under `postgresql/`. The 26 statements
  that set HSQLDB's MVCC mode are deleted. The seven real failures pass through §7.3's changes.
- **No Healdan test opens an HSQLDB database.** `HealdanMigratorTest` and `HealdanStartListenerTest`, which make
  HSQLDB data sources of their own, are rewritten for PostgreSQL, beyond the 34.
- **New tests for what only PostgreSQL shows:** the boolean setting in force (§7.1), a user's edit kept (§7.2), each
  of §7.3's cases, the lock timeout ending a wait (§7.4), and the text check with an emoji, a NUL and a lone surrogate
  (§7.5).
- **In CI,** each `Tests (n of N)` job runs a `postgres:18` service container, which its tests share: every test has
  a database of its own. It runs with the settings the local server has, `fsync`, `synchronous_commit` and
  `full_page_writes` off, 300 connections and its data on a tmpfs (changed in the build: this didn't say, and the
  plan's service had PostgreSQL's defaults; every test makes a database and loads CE's schema statement by statement,
  and CI's time doesn't grow, §2.4; plan, Decision Log 4). `test-shards.py` and the timing cache are unchanged.
- **Locally,** a PostgreSQL in Docker, started by one command that `CLAUDE.md` and the Developer Guide's Tests page
  give.
- **Upstream's tests stay on HSQLDB:** the classes in CI's `UPSTREAM_TESTS` extend upstream's base classes and never
  touch an `hd_` table.
- **HSQLDB's jars stay on Healdan's test classpath,** though nothing of Healdan's runs on it: upstream's
  `AbstractTestCase`, which every Healdan database test extends, names HSQLDB's `SqlFile` and its error class, and
  the base class loads CE's script through `SqlFile`, as the start-up tool does.
- **Between this and the switch,** the stack still runs MariaDB, which only the smoke test covers, as only the smoke
  test covers it today.

## 11. Files

### 11.1 Upstream Files (each a small hook into Healdan's code, listed in this spec)

| File | Edit | Why |
|---|---|---|
| `logicaldoc-core/.../core/HibernatePersistentObjectDAO.java` | Its single-value typed reads hand the driver's value to a fork-owned helper (§7.2) | On PostgreSQL the typed read fails and rolls back the caller's transaction |
| `logicaldoc-gui/.../setup/client/Setup.java` | The wizard starts on PostgreSQL, in the lines Healdan already edits (§8.4) | PostgreSQL is the database Healdan runs on |

Nothing else upstream changes: not `context.xml`, not `context.properties`, not CE's SQL scripts. The removal of §9.7
later edits `logicaldoc-webapp/pom.xml`.

### 11.2 Fork-Owned Files

- `healdan-core`: the `postgresql/` migrations and the widening; `hibernate.properties`; `HealdanMigrator`; the text
  check and its callers; `Savepoints`, and the statements and listeners of §7.3 and §7.4; `pom.xml` (Flyway's
  modules, and pgjdbc for the tests); the test base class, its helpers and the mended tests.
- `logicaldoc-core`: the typed-reads helper and its test, fork-owned, beside `HealdanHooks`.
- `healdan-api`, `healdan-storage`: their callers of the text check, the NUL refusal, the read-back after a failed
  save and the sorts (§7.3, §7.4), `FileLocks`' comment, `Savepoints`' callers, and their tests.
- `healdan-web`: one string.
- `docker/`: `DockerInit`, `ConfigPlan`, the move command, the entrypoint, the role's script, the test scripts and the
  fixture; `compose.yaml`, `compose.dev.yaml`, `compose.move.yaml`, `.env.example`.
- `.github/workflows/`: the `Tests` jobs' PostgreSQL service, and the image workflow's move test.
- `clients/helper`: one comment.

## 12. Docs (in the same pull requests)

- **Install manual.**
  - **Moving from MariaDB to PostgreSQL** (new): who needs it, the backup, upgrading to `mariadb-final` (with the
    changes to CE's tables a MariaDB install may still owe, moved here from Upgrading), the move, what the report
    means, what to do when it stops, the way back and its limit, the backup job, and removing the old volume.
  - **What Runs:** the stack's table and Supported Databases (PostgreSQL 18; a PostgreSQL of your own, and the
    `create database` for it).
  - **Backup and Restore:** `pg_dump -Fc` and `pg_restore`, as the recon checked them (§5.3).
  - **Upgrading:** Changes to LogicalDOC's Tables starts afresh for PostgreSQL, since every PostgreSQL database is
    made by a version that already has every change before it; and a section on moving to a new PostgreSQL major.
  - **Configuration** (the `DB_*` table, and changing the database password with `alter role`), **Quick Start**, the
    manual's first page, **Troubleshooting** (the volume's name, the migration that fails, the missing column, the
    guard's message), **Known Limitations** (PostgreSQL only; the tag search and a backslash; the emoji limit gone)
    and **Host Folders.**
- **Admin Guide:** the System page.
- **User Guide:** Copying, Moving and Deleting, where it says a name can't hold an emoji.
- **Developer Guide:** Development Stack (the port), Tests, Building from Source and Continuous Integration (§10:
  the PostgreSQL that Healdan's tests need, and the move's test), How Healdan Extends LogicalDOC (the migrations
  folder, the exception of §6.4, the text check, §7.3's rule), API (the refusals' wording, the NUL refusal) and
  Desktop Clients (why Message-IDs keep their rule).
- **`CLAUDE.md`:** the database, persistence, Flyway, Docker, build-and-test, tests and upstream-edits paragraphs, and
  the rules for new SQL, which become PostgreSQL's (§7.3).
- **The roadmap:** principle 9, the backlog row and §6's schema rule.

## 13. Delivery and Acceptance (call)

### 13.1 Order

1. **Healdan runs on PostgreSQL, and its tests run there,** with MariaDB still the stack's database: the `postgresql/`
   migrations, Flyway's module, `HealdanMigrator`, the Hibernate setting, the typed reads, §7.3's and §7.4's changes
   written so that they work on MariaDB too (a savepoint where `on conflict` would go), the test base class, the
   mended tests, CI's service container, and the removal of `hsqldb/`. A running server behaves as before, but for
   two edge answers of the legacy doc # lookup (changed in the build: the lookup strips whitespace at both ends, §7.4,
   so on MariaDB one typed with a leading space or a trailing tab now finds its document, and a legacy number stored
   with a leading space is no longer found by it; plan, Decision Log 21).
2. **The switch,** in one pull request, as it can't be half done: the container, the start-up tool and its guard, the
   widening, the text check, `on conflict` in §7.3's two inserts, the move command with its test and fixture, the
   smoke test and the Docker tests, the wizard, the removal of `mariadb/`, and the docs. `mariadb-final` is tagged on
   the commit of `master` it merges onto, and the fixture is made from that commit.
3. **The records:** `CLAUDE.md`, the roadmap, this spec's corrections.
4. **Later, on the owner's word:** the removal of §9.7.

### 13.2 Acceptance (owner)

1. **A rehearsal, before the switch merges,** on a throwaway lab VM, apart from the server. (owner)
   - **The VM is a clone of the server's own,** which the owner made on 2026-11-18: the same stack, MariaDB volume,
     conf and repository volumes, and so the same install id. Nothing is copied anywhere else. For a rehearsal on
     newer data, the owner clones again or the server's latest backup is restored onto it.
   - **A clone can't change the server's bucket, whatever is started on it.** (owner: testing on the clone mustn't
     change the S3 bucket.) A clone has the server's install id, keys and settings, so its mover and purge would act
     on the server's own bucket (the S3 spec's §12), and it would send the server's email. So the first thing done on
     a clone, or after a backup is restored onto one, before its app is ever started, takes the means away: each S3
     backend's access key is replaced with one that doesn't exist, its secret is removed and its endpoint is pointed
     at a name that doesn't resolve, and the email host likewise. This was done on the clone on 2026-11-18. The
     backend then reads as failing in a rehearsal, which is expected: a file read from S3 is the real move's check
     (step 2), not the rehearsal's.
   - **And its stack has no route out:** a rehearsal's stack is on an internal Docker network, a second lock that
     doesn't depend on the first. Building the image still needs the network, which the build has and the stack
     doesn't. Between rehearsals the clone's app stays stopped.
   - **Wiping the clone and seeding the demo firm was the owner's fallback, and isn't needed.** The rehearsal's worth
     is the server's own rows, and invented data is what the move's test already covers (§10.2).
   - **It's brought to `mariadb-final` first,** by an ordinary upgrade on that internal network, as the server will
     be.
   - **It runs the move, then a first start,** with the switch's image, as many times as it takes. It checks the
     move's report; queries on both sides; and what only a start shows: the conf volume's database keys rewritten,
     the check of CE's tables, the sealed secrets opening, the storage markers accepted, a local account signing in,
     a document read from Original's folder, and a search.
2. **The real move,** on the owner's server, which is the acceptance: a backup, the upgrade to `mariadb-final`, the
   move, the first start, and a walkthrough: signing in through the provider, the Helper's saved sign-in still
   working, opening, saving and closing a document from the Helper, filing an email, a search, a delete and a restore
   from the Trash, a file read from each storage backend, the Classic Interface's screens the owner administers with
   (users and groups, templates, Stores, Healdan Settings), as the trial reached few of its services (§4.1), and the
   nightly backup job changed to `pg_dump` with one of its dumps restored to a scratch database.
3. **The old volume is kept** until the owner removes it.

## 14. Left to the Builder

- Where the lock timeout is set: the URL's `options`, or the pools' connection-init keys, both of which
  `context.properties` has.
- How the `db` service makes the role and hides the superuser (an init script mounted from the checkout is enough
  while installs build from source).
- How the move marks a target unfinished and finished, how it orders its work within the steps of §9.3, its batch
  size, and how the checksum treats a MariaDB `float`.
- The new text check's name, the API's code for a NUL (an existing `400` code), and the name of the move's option
  that replaces NULs.
- What tells the start-up tool that a repository volume holds an install (§8.3).
- The counters' arithmetic (§9.3, step 7), confirmed with one query on a real install before it's relied on.
- Each site of §7.3 and §7.4, from the recon's lists and the trial's failures, and how the read-back after a failed
  save finds CE's transaction ended.
- How a test names its PostgreSQL server, and the command that starts one locally.
- Whether the start-up tool's probes of an empty database can stop raising errors in PostgreSQL's log.
- The fixture's exact rows.

## 15. Decisions Made in This Session

| Recon question | Decision |
|---|---|
| Q1, the major and following new ones | PostgreSQL 18, pinned; a new major by dump and restore (owner, §6.1) |
| Q2, where the Hibernate fixes live | `hibernate.properties` in `healdan-core`; an upstream edit for the typed reads (call, §7.1, §7.2) |
| Q3, CE's narrow columns | Widened, as the rule's one exception, by a Flyway migration (owner; where it runs is a call, §6.4) |
| Q4, how an install gets PostgreSQL | It's the only database: `compose.yaml`'s `db` service (owner, §5.1, §8.1) |
| Q5, the collation | The built-in `C.UTF-8` (owner, §6.2) |
| Q6, characters beyond the plane | Kept as typed; Message-IDs unchanged (owner, §7.5) |
| Q7, who owns the database keys | The start-up tool, on every start; `jdbc.dbms` is `postgresql` (call, §8.2) |
| Q8, CI on PostgreSQL | The smoke test on PostgreSQL in place of MariaDB, and the move's test (call, §10.1, §10.2); Healdan's Java tests on PostgreSQL only (owner, §10.3) |
| Q9, how far CE's own SQL is checked | By the trial (§4), then on every run by Healdan's tests, the smoke test and its check of PostgreSQL's log (call, §10), and by the walkthrough's pass through the Classic Interface (§13.2) |
| Q10, how installs move | A command in the image, kept until the owner removes it (owner, §9) |
| Q11, the backup pages | PostgreSQL's steps only; MariaDB's survive on the move's page (call, §12) |
| Q12, the same behaviour on both | No: PostgreSQL's stands, and the code that assumed MariaDB's changes (call, §7.4) |
| Q13, queuing without a failed insert | `on conflict` in the two inserts, and a savepoint wherever a failure is swallowed inside a save (call, §7.3) |
| Q14, what ends a lock cycle | A 50-second `lock_timeout` (call, §7.4) |
| Q15, NUL in text | Refused or replaced by the text check; a `400` from the API (owner's choice of §5.4; the rest a call, §7.5) |
