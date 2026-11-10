# Checking LogicalDOC's tables against Hibernate's mapping at start: design

- **Date:** 2026-11-10
- **Status:** proposed (Claude, 2026-11-10), for the owner's review. Not built. §13 lists what the owner decides.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The problem

Healdan never changes the structure of LogicalDOC's `ld_` tables on an existing database: Flyway owns only the `hd_`
tables. When an upstream sync maps new columns, an existing database lacks them until an admin runs the SQL that
Upgrading's [Changes to LogicalDOC's Tables](https://docs.healdan.com/install/upgrading/#changes-to-logicaldocs-tables)
gives (Healdan-Project/healdan#217). If they don't, Hibernate fails with `Unknown column` where it reads the table.
After sync #215 that was `ld_tenant`'s 14 `*quotathr` columns: the error showed only in `dms.log`, and nobody could
sign in.

Healdan-Project/healdan#218 (open) makes `DockerInit` refuse to start when the database lacks a table or column that
the DbInit scripts' `create table`s make. That has two gaps:

- **It checks a stand-in for what fails.** Sign-in fails because Hibernate reads a column an entity maps, and the
  scripts and the entities can disagree. Upstream's `0490ffedc` mapped the 14 columns on `Tenant`, but its HSQLDB
  script put `ld_documentsquotathr` and `ld_pagesquotathr` on `ld_folder` and left out `ld_emailaccountsquotathr`, and
  its MariaDB script put `ld_documentsquotathr` on `ld_folder` (Healdan's `4686173ab` put them back). On a database
  made from those scripts, the script check passes and sign-in still fails.
- **It runs only in Docker.** A WAR on someone's own Tomcat gets no check.

## 2. Decision

- **The app checks the tables and columns Hibernate's mapping reads,** at every start, and stops with a clear
  message when one is missing, as a failed Flyway migration stops it. (call)
- **The mapping is recorded where Hibernate builds it,** by a Hibernate `Integrator` in `healdan-core`, which
  Hibernate finds through `META-INF/services`. No upstream file changes. (call, §3)
- **The check runs in `HealdanStartup.run()`,** after the database is known to be supported and to hold LogicalDOC's
  schema, before Flyway and the seeds, and before anything in CE reads an entity. (call, §4)
- **Only what's missing counts:** a mapped table or a mapped column. Types, extra tables, extra columns and Healdan's
  `hd_` tables never do. Column names are compared case-insensitively, table names exactly as the database resolves
  the name Hibernate sends. (call, §5)
- **It sits beside #218's Docker check,** which also covers the tables and columns LogicalDOC's own SQL reads outside
  the mapping. The two share the message's opening, so one Troubleshooting row covers both. (call, §8)
- **It's also the guard at sync time.** Every Healdan test and every smoke run checks the mapping against a database
  made fresh from upstream's scripts, so a script that disagrees with the entities fails CI with the table and column
  named. (call, §9)

## 3. Recording the mapping

Hibernate builds its relational model, the boot `Metadata`, while it builds the session factory, and keeps no
reference to it afterwards. Hibernate's `Integrator` SPI is the supported way to see it:
`integrate(Metadata, BootstrapContext, SessionFactoryImplementor)` (Hibernate 7.4). Hibernate's `IntegratorServiceImpl`
loads integrators as Java services, through its `ClassLoaderService`, so a
`META-INF/services/org.hibernate.integrator.spi.Integrator` file in `healdan-core`'s jar is enough. The jar ships in
`WEB-INF/lib` and is on every Healdan test's classpath. CE's own tests don't have it, so they're unchanged. If the file
weren't found, every Healdan test would fail (§5), so the tests prove it's picked up.

- **`MappingRecorder`** (the integrator) builds a `MappedTables` from `metadata.collectTableMappings()` and records it
  against the session factory. `disintegrate`, which Hibernate calls when the factory closes, forgets it. It reads
  nothing from the database.
- **`MappedTables`** is an immutable snapshot: each table's name with its columns' names, as the mapping spells them.
  It keeps every table that `Table.isPhysicalTable()` accepts (no subselects or abstract union tables) and every
  column that isn't a formula. This is the model Hibernate's own schema validator walks: `Database.getNamespaces()`,
  their tables, and `Table.getColumns()`. It covers entity tables (with the single-table inheritance's discriminator),
  `@CollectionTable`s (`ld_document_ext`, `ld_tag`, the ACL tables and the rest) and `@JoinTable`s
  (`ld_filler_chain`). Filtering out `hd_` happens in the check (§5), so the snapshot stays the whole mapping.

**Rejected:**

- **Hibernate's `hbm2ddl.auto=validate`.** It compares types too, so it would refuse databases that work (CE's
  scripts and Hibernate's idea of a column's type don't agree everywhere). It stops at the first problem with
  Hibernate's wording and no pointer to the fix, and it needs an edit to upstream's `context.xml`.
- **Reading the runtime metamodel at check time** (`MappingMetamodel`'s persisters and their selectables). It needs no
  integrator, but walking identifiers, discriminators, embeddables, to-one keys and each collection's key, element and
  index takes several internal APIs that changed between Hibernate 6 and 7. The boot model gives the same tables in
  one call.
- **Building a second `Metadata` from the entity classes,** in the app or in `DockerInit`. It could differ from the
  session factory's (settings, the classpath scan), and a check of a copy is the stand-in problem again.

## 4. When the check runs

`HealdanStartup.run()` becomes, in order:

1. **Refuse an unsupported database, and stop if LogicalDOC's schema isn't there,** as `migrate()` does first today
   (no `ld_user`: before the setup wizard has made the schema outside Docker, and while Healdan's tests build their
   context before their database). `HealdanMigrator` gains a `probe()` for this, which `migrate()` keeps calling, so
   its behaviour and tests stay.
2. **Check LogicalDOC's tables** (`LogicalDocTablesCheck.check()`). A missing table or column throws
   `HealdanStartupException`.
3. **Migrate Healdan's tables** (Flyway).
4. **Run the seeds,** then register the sign-in alert, as today.

The check is read-only, so a database it refuses is left exactly as it was: Flyway hasn't upgraded `hd_` yet, and no
seed has run. The seeds read CE's tables through Hibernate (groups, templates, the tenant), so the check must come
before them.

**It runs before CE first reads an entity.** `HealdanStartup.start()` is a `@PostConstruct`, so it runs while Spring
creates the singletons, and a `HealdanStartupException` there aborts the refresh. CE's first entity reads come after
that: `ContextInitializer` reads the tenants' ids on `ContextRefreshedEvent`, Quartz starts the scheduled tasks when
the refresh finishes, and requests (sign-in) come once Tomcat has started the webapp. The `@PostConstruct` and
`afterPropertiesSet` methods in `logicaldoc-core`, `-webapp`, `-util` and `-webservice` register listeners and read
the configuration, but none reads an entity. Healdan's own `HealdanStorage.init()` reads no table either. A future CE
bean that read an entity in its own `init` could still read first. Its read would fail with Hibernate's error, and
then either stop the start itself or, if CE logs and goes on, leave this check to stop it.

**Rejected: checking inside the integrator.** Nothing can read an entity before the session factory exists, so that
would be the earliest point. But Healdan's tests build their context before their database, so the integrator would
always find no schema and the tests would have to call the check again. The failure would also come out of the
`sessionFactory` bean, under every bean that depends on it. Keeping Healdan's start failures in `HealdanStartup`, with
Flyway's and the seeds', is clearer, and nothing is lost while CE's inits read no entity.

## 5. What's compared

**The mapping's side** is `MappedTables` for the session factory CE uses (the `sessionFactory` bean, injected). If the
integrator recorded none, the check throws: `Healdan couldn't read Hibernate's mapping, so it can't check LogicalDOC's
tables` and why. A check that can't run never passes silently: a jar without the service file fails every Healdan
test.

**The database's side** is one JDBC call, `DatabaseMetaData.getColumns(catalog, schema, "%", "%")`, with the
connection's own catalog and schema: every column of every table and view in Healdan's database. A `null` catalog
isn't used, as MariaDB's driver then searches every database on the server (the migrator's `tableExists` avoids it for
the same reason). JDBC's metadata, not MariaDB's `information_schema`, keeps the check working on HSQLDB now and
PostgreSQL later.

**The rules:**

- **Only missing things count:** a mapped table the database lacks, or a mapped column a table lacks. A missing table
  is reported once, not column by column.
- **Never compared:** types, lengths, nullability, defaults, keys, indexes and sequences; tables and columns the
  database has besides (such as a column an older version left); and any table whose name starts with `hd_`. Flyway
  owns those: its scripts make them, and its history records which have run.
- **Column names are compared case-insensitively** (`Locale.ROOT`). MariaDB treats column names so everywhere, and
  HSQLDB stores unquoted names in upper case.
- **Table names are compared exactly, after folding the mapped name as the database folds an unquoted name**
  (`storesUpperCaseIdentifiers()` upper, `storesLowerCaseIdentifiers()` lower, otherwise as written). That's how the
  database resolves the name Hibernate sends. On the image's MariaDB (Linux, `lower_case_table_names=0`) it's exact:
  a table created as `LD_TENANT` doesn't answer Hibernate's `ld_tenant`, so the check reports it missing. HSQLDB
  stores and resolves `ld_tenant` as `LD_TENANT`, and PostgreSQL as written in lower case.
- **A quoted table name** would be compared as written, unfolded. The mapping quotes no table or column, and a test
  pins that.

**Not covered (by design):**

- **LogicalDOC's own SQL.** CE's DAOs also read tables with native queries and JDBC (`queryForList("select …")`),
  outside the mapping. A column only such a query reads isn't in the mapping. #218's script check covers those
  columns in Docker, since a fresh database gets them from the scripts (§8).
- **Sequences.** Each entity's `<table>_SEQ` comes with its table, and an Upgrading entry that adds a table adds its
  sequence. A sequence missing on its own fails only inserts into that entity. JDBC has no portable way to list
  sequences, so the check leaves them out. (call, §13 item 3)

## 6. The message

On a refusal, the check logs at error and throws `HealdanStartupException` with:

```
LogicalDOC's tables lack what this version needs: table ld_tenant lacks columns ld_formsquotathr, ld_usersquotathr; table ld_webservicecall is missing. Healdan doesn't change LogicalDOC's tables itself, and signing in, or whatever else reads them, would fail, so the app doesn't start. Make each change Upgrading gives for this version, then start again: https://docs.healdan.com/install/upgrading/#changes-to-logicaldocs-tables
```

- It opens as #218's message does (`LogicalDOC's tables lack what this version needs:`), so the same Troubleshooting
  row covers both. The logger tells them apart: `[docker-init]` before Tomcat, `com.logicaldoc.healdan.db` in it.
- Tables are listed by name, and each table's columns by name, so the message is the same on every start. `column`
  for one, `columns` for several.
- It names every missing table and column. The check runs only on a database that has LogicalDOC's schema
  (`ld_user`), so the list stays short in practice.

On success it logs, at info, `LogicalDOC's tables have every table and column this version reads (<n> tables)`, so
`dms.log` shows the check ran.

The exception reaches the admin as a failed migration's does: in `dms.log`, and, through Spring's failed refresh
(`ContextListener` lets `ContextLoaderListener` rethrow), in Tomcat's output, which is `docker compose logs app`. Tomcat
then doesn't start the webapp, so the container's healthcheck (`/login.jsp`) fails. Quick Start says `docker compose
logs app` names a failed migration's cause, and no test checks it. §11.2's step 9 checks it for this message.

## 7. No way to skip it

There's no setting that skips the check. A failed migration has none either, and #218 has none. Every missing mapped
column fails some read: Hibernate selects every mapped column when it loads an entity or a collection. The fix is
always the Upgrading entry's SQL. If the message names a table or column that no entry gives, that's a fault in
Healdan (the sync that brought it should have added the entry), and Upgrading says to report it. (call)

## 8. Beside #218's Docker check

The two checks compare different things, and neither covers the other:

| | #218's check (`DockerInit`) | This check (`HealdanStartup`) |
|---|---|---|
| Compares the database with | The tables and columns the DbInit scripts create | The tables and columns Hibernate's mapping reads |
| Catches a script and the mapping disagreeing (`0490ffedc`) | No: a fresh database matches its scripts | Yes, on a fresh database too |
| Catches a column only CE's own SQL reads | Yes | No |
| Runs | Docker only, before Tomcat starts | Every install, in the app |
| Its output | The container's, before anything else | `dms.log` and Tomcat's output |

**Keep both.** (call) Merge #218 as it is, then build this beside it. In Docker #218's check runs first. It catches
almost every missing column, with the clearest output. This one catches what the scripts get wrong and covers installs
outside Docker. If the owner prefers one mechanism, close #218 instead: this check alone still covers sign-in, but
nothing then catches a missing column that only CE's own SQL reads, and Docker's output gets only Tomcat's report of
the failure. (§13 item 1)

This spec's docs (§12) assume #218's are merged: its Troubleshooting row, its What Runs bullet and its Known
Limitations wording. If #218 is closed, the build takes those over with this check's wording.

## 9. The guard at sync time

The check also runs wherever a database is made fresh from upstream's scripts, so a sync whose scripts disagree with
its entities fails CI before any admin sees it:

- **Healdan's tests.** `AbstractHealdanTestCase.setUp` calls `HealdanStartup.run()`, so every test in `healdan-core`,
  `healdan-api` and `healdan-storage` checks the mapping against the HSQLDB scripts. With the HSQLDB half of
  `4686173ab` undone, every Healdan test fails in `setUp`, naming `ld_tenant`'s three columns. Before, they failed in
  the seeds with Hibernate's error. That's the plan's mutation check (§11.1).
- **The smoke run.** Its stack is made fresh from the MariaDB scripts, so the app refuses to start when one disagrees
  with the mapping. Step 1's `wait_healthy` then fails, and the failure's output, which prints the app's logs, shows
  the message. Undoing the MariaDB half of `4686173ab` would have failed it.

When it fires in a sync PR, the fix is a merge fix to the script that disagrees, as `4686173ab` was. CLAUDE.md's sync
bullet says so (§12).

**The tests' database.** Healdan's test classpath has `logicaldoc-webservice` (test scope in all three modules), so
the mapping includes `WebserviceCall`'s `ld_webservicecall`. `AbstractHealdanTestCase.getDatabaseScripts()` loads only
`sql/logicaldoc-core.sql` and `data.sql`, so the check would refuse it. Its default becomes `sql/logicaldoc-core.sql`,
`sql/logicaldoc-webservice.sql`, `data.sql`, which nine test classes already override it with. Those overrides go.

## 10. Code

All in `healdan-core`, under `com.logicaldoc.healdan.db` unless named:

| File | What it does |
|---|---|
| `MappingRecorder.java` | The `Integrator`: records a `MappedTables` for each session factory, and forgets it when the factory closes |
| `MappedTables.java` | The snapshot (§3), with `from(Metadata)`, and the record of each session factory's (by the factory's UUID) |
| `LogicalDocTablesCheck.java` | `@Component`, given the `DataSource` and the `SessionFactory`: `check()` reads the database's columns, compares (§5), logs, throws (§6) |
| `MissingTables.java` | The comparison's result, a pure function of the snapshot, the database's columns and the folding rule, and the message's text |
| `HealdanMigrator.java` | Gains `probe()` (§4 step 1); `migrate()` calls it |
| `com/logicaldoc/healdan/HealdanStartup.java` | `run()` in §4's order |
| `src/main/resources/META-INF/services/org.hibernate.integrator.spi.Integrator` | Names `MappingRecorder`. Config, so no licence header |
| `src/test/.../AbstractHealdanTestCase.java` | Loads webservice's script by default (§9) |

`MissingTables` holds no connection and no Hibernate type, so most cases are tested without a database. The new
Java files carry the SPDX header. `healdan-core`'s plugin zip already ships its jar, and the jar keeps its
`META-INF/services`, so the WAR needs nothing new.

## 11. Tests

### 11.1 HSQLDB, in `healdan-core`

`MissingTablesTest` (no database):

- nothing missing gives an empty result; extra tables and extra columns are ignored;
- a missing table is reported once, without its columns; a missing column names its table;
- column names match whatever their case; table names fold by the database's rule, then match exactly;
- `hd_` tables are skipped whatever is missing from them;
- the message: the opening, `column` and `columns`, the order by name, and the docs URL.

`LogicalDocTablesCheckTest` (an `AbstractHealdanTestCase`, against the test database):

- **the test database passes,** and the info line counts more than 40 tables;
- **the snapshot is the mapping:** it holds `ld_tenant` with `ld_formsquotathr`, the collection table
  `ld_document_ext`, the join table `ld_filler_chain`, `ld_webservicecall`, and `Matter`'s `hd_matter` (left out only
  by the check); no mapped table or column is quoted;
- **a dropped column stops the start:** `alter table ld_tenant drop column ld_formsquotathr`, then `run()` throws,
  naming the table, the column and the docs URL;
- **nothing is written first:** with a seed's marker deleted and a column dropped, `run()` throws, the marker stays
  absent, and `hd_schema_history` has the same rows;
- **a missing table is named once:** `drop table ld_filler_chain` (the join table), then the message names the table
  and none of its columns;
- **a collection table's column counts:** dropping a column of `ld_document_ext` is reported;
- **a table under another name is missing:** renaming `ld_tag` to a quoted lowercase `"ld_tag"` (which HSQLDB keeps
  apart from `LD_TAG`) is reported as `table ld_tag is missing`;
- **extra columns and tables pass:** an added `ld_tenant` column and a `create table ld_leftover` change nothing;
- **`hd_` tables never count:** dropping a column of `hd_client` passes the check (and Flyway doesn't look);
- **no schema, no check:** against an empty HSQLDB (as `HealdanMigratorTest` makes one), `run()` returns false and
  throws nothing;
- **no recorded mapping fails:** a `LogicalDocTablesCheck` given a session factory the recorder never saw throws.

These are `healdan-*` test classes, so CI finds them with no list, and `UPSTREAM_TESTS` doesn't change.

**Mutation check (by hand, in the plan):** undo the HSQLDB half of `4686173ab` in a scratch branch. Every Healdan test
then fails in `setUp` with the message naming `ld_tenant`'s `ld_documentsquotathr`, `ld_pagesquotathr` and
`ld_emailaccountsquotathr`.

### 11.2 The Docker smoke run

- **Steps 5 and 8:** `dms.log` has the info line once in step 5 (Healdan's schema and seeds), after the first start on
  a database made fresh from the MariaDB scripts, and twice after step 8's restart, on the existing database.
  `dms_log_count` reads it. This proves the check finds Healdan's database on MariaDB, scoped to its own catalog, with
  nothing missing.
- **A new step 9, the refusal:** stop the app, drop `ld_tenant.ld_formsquotathr` through the `db` service, and start
  the app's image with Tomcat directly (its `catalina.sh run`, as uid 1000, with the stack's volumes and
  environment). That skips `DockerInit`, whose #218 check would refuse first and is tested in `test-init.sh`. Then
  check that Tomcat's output has the message with the docs URL, that `dms.log` has it, and that `/login.jsp` doesn't
  answer. It's the last step, so the stack is removed after it and the column isn't put back. (call, §13 item 4)

`test-init.sh` runs only `DockerInit`, and doesn't change.

## 12. Docs

Same PR as the code, per CLAUDE.md:

- **Install manual:**
  - **Quick Start**, What Happens on First Start: the list Healdan runs at every start gains a first item, LogicalDOC's
    tables checked, and "If either fails" becomes "If any fails".
  - **What Runs**, What the App Does When It Starts: #218's bullet says the app checks too, on every start including
    the first, against the tables and columns this version reads.
  - **Upgrading**: What an Upgrade Changes' "LogicalDOC's tables aren't" bullet says the app checks them as it starts,
    and stops, naming what's missing. Changes to LogicalDOC's Tables adds: if the message names something no change
    here gives, report it, since it's a fault in Healdan.
  - **Troubleshooting**: #218's row for `LogicalDOC's tables lack what this version needs: …` covers both checks. The
    `Unknown column` row (in `dms.log`) stays, saying the start's check normally catches it first.
  - **Known Limitations**: #218's sentence ("The app checks as it starts, and stops if a table or column is missing")
    stays true as written.
- **Developer Guide:**
  - **How Healdan Extends LogicalDOC**, Its own tables: the check, what it compares, the integrator and its service
    file, and `run()`'s order.
  - **Upstream Syncs**: a sync whose scripts disagree with its entities fails Healdan's tests and the smoke run with
    the message, and the fix is a merge fix to the script (§9).
  - **Tests**: `LogicalDocTablesCheckTest` and `MissingTablesTest`, the test database's default scripts, and smoke
    steps 5, 8 and 9.
- **CLAUDE.md:** a bullet under "Healdan's code" beside Flyway's (what the check compares, where it runs, that it has
  no skip, the service file); the sync bullet's merge fix (§9); the test database's scripts under "Tests"; smoke step
  9 under "Docker"'s tests.
- **The Docker spec's §16** (#218's) points here for the app's check.
- **This spec's** status, once built.

## 13. Decisions for the owner

1. **Beside #218, or instead of it?** Recommendation: beside (§8). Merge #218, then build this.
2. **The order in `run()`:** the check before Flyway, so a refused database is left untouched (§4). The other choice,
   after Flyway, would upgrade `hd_` before stopping, and gains nothing.
3. **Sequences left out** (§5). Recommendation: leave them out. A sequence never goes missing without its table, and
   the table is checked.
4. **Smoke step 9** (§11.2) starts Tomcat without `DockerInit`, which nothing else does. Recommendation: include it,
   as it's the only end-to-end test of the refusal, and it settles where a start failure's message lands. Without it,
   the refusal is tested on HSQLDB only.
5. **No setting skips the check** (§7). Recommendation: none, as for a failed migration and #218.

## 14. Upstream edits

None. The integrator is found through `META-INF/services` in `healdan-core`'s jar, and every other change is in
Healdan's own files.

## 15. Later

- **CE's own SQL,** outside Docker: only #218's script check covers a column that only CE's native queries read, and
  only in Docker. A check of the scripts in the app would need the scripts' parser in `healdan-core`.
- **PostgreSQL** (roadmap principle 9): the check uses JDBC's metadata and folds by the database's own rule, so it
  should need no change, but its design session should confirm it.
