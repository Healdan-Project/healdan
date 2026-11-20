# PostgreSQL as Healdan's Database: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Healdan runs on PostgreSQL 18 only. Its tests run there, a command in the image moves an existing MariaDB install across and checks its own work, and the owner's server is moved with it.

**Architecture:**
- **`healdan-core`** gains a `postgresql/` Flyway folder, a `hibernate.properties` that binds booleans as integers, a public `Savepoints`, and `StorableText` in place of `BasicPlane`. Its test base class makes a PostgreSQL database for each test.
- **`logicaldoc-core`** gains one fork-owned helper, `HealdanTypedReads`, which CE's base DAO calls from two lines.
- **`docker/tools`** becomes PostgreSQL's: `ConfigPlan` and `DockerInit` name PostgreSQL, own the database keys on every start and guard against starting an install empty. Beside them, `MoveFromMariaDb` copies a MariaDB database into a schema Healdan's own scripts made, and compares the two.
- **`compose.yaml`**'s `db` service is `postgres:18` on a new volume, and `compose.move.yaml` is the overlay that runs the old MariaDB for a move.

**Tech Stack:** Java 25, Spring 6.2, Hibernate 7.4, Flyway 13 with `flyway-database-postgresql`, pgjdbc 42.7, PostgreSQL 18, MariaDB 11.4 (the move's source only), JUnit 4, Docker Compose, bash, GitHub Actions, Hugo.

**Spec:** [`docs/superpowers/specs/2026-11-18-postgresql-design.md`](../specs/2026-11-18-postgresql-design.md), approved by the owner on 2026-11-18. Read it with this plan: tasks cite its sections. The facts it rests on are in the [PostgreSQL recon](../../reference/postgresql-recon.md), cited as "recon §n".

**Status:** written on 2026-11-18, after the spec's approval, from three surveys of the code and the trial's two branches. **Approved** by the owner the same day, for subagent-driven building in a fresh session. PR A (Tasks 1 to 9) was built on 2026-11-18 and merged on 2026-11-19 (Healdan-Project/healdan#277). PR B, the switch (Tasks 10 to 22), was built on 2026-11-19 and is open as a draft pull request (Healdan-Project/healdan#280), which stays a draft until the owner's go; #278 and #279 merged meanwhile and were merged into it (Decision Log 48 and 50). Task 23's rehearsal on the owner's clone, Steps 1 to 6, passed on 2026-11-19 (Decision Log 51). On 2026-11-20, on the owner's word, `mariadb-final` was tagged and PR B merged (Step 7, Decision Log 52), the owner's server moved and the owner's walkthrough passed (Task 25, Decision Log 53), and the records were written (Task 24). Left: the check of PostgreSQL's log made to fail a build, in a pull request of its own once it has held for five runs, and Task 26, the move command's removal, at the owner's word. Tasks 23 to 26 aren't done.

## Global Constraints

Every task's requirements include these.

- **The database** (spec §6.1, §6.2): PostgreSQL 18, the image `postgres:18`, encoding UTF-8, the built-in locale provider with `C.UTF-8`. Wherever a database is created, by the stack, by a test or by the docs, it is created with exactly that: `initdb`'s `--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C`, or `create database … template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'`.
- **Upstream edits are only these** (spec §11.1):
  - `logicaldoc-core/src/main/java/com/logicaldoc/core/HibernatePersistentObjectDAO.java`: two lines that call `HealdanTypedReads` (Task 3);
  - `logicaldoc-gui/src/main/java/com/logicaldoc/gui/setup/client/Setup.java`: the wizard's default, in the lines Healdan already edits (Task 20).

  No CE SQL script, `context.xml` or `context.properties` changes. Any other upstream edit needs the owner's say-so and a line in the spec's §11.1. Keep each upstream file's line endings as they are (`file <path>` says which).
- **Until the switch merges (PR A), the stack still runs MariaDB.** Everything PR A changes in production code has to work on MariaDB and on PostgreSQL: no `on conflict`, no other PostgreSQL-only syntax, and no change a running MariaDB server would notice. The smoke test, on MariaDB, still passes after every task of PR A.
- **A shipped migration never changes.** `mariadb/V1` to `V11` aren't edited, only deleted at the switch. The `postgresql/` scripts are fixed once PR A merges. A migration that another change adds to `master` before the switch goes into every folder that exists, and the move's expected version and its fixture follow (spec §6.5).
- **The rules for Healdan's SQL** (spec §7.3):
  - no statement is expected to fail inside a transaction, and one that may fail without failing its caller runs inside a savepoint (`Savepoints`, Task 6);
  - a flag is an `int` column and an `int` field, never a `boolean` column: the Hibernate setting binds every Java `boolean` as an integer;
  - SQL given to CE's DAO helpers never uses `::`, which CE's `NamedParameterStatement` reads as a parameter: write `cast(x as text)`.
- **The move never loses or changes a row without saying so** (spec §2.3, §9): every id is kept; it never writes to MariaDB; it never reads or writes the conf, repository or cache volumes; it never contacts a storage backend; and its report names tables, keys and columns, never a row's values.
- **The rehearsal clone** (spec §13.2): its app is never started with a route out, and after any new clone or restored backup the S3 backend's credentials and endpoint, and the email host, are replaced before its app is ever started.
- **No address of the owner's server, the lab or the clone** goes into a commit, a pull request, an issue or the docs.
- **Docs change in the same pull request as what they describe,** in the site's style (the Developer Guide's Writing Docs page): Title Case headings, British spelling, Healdan's glossary.
- **Licence headers:** every new source file opens with the two SPDX lines in its own comment syntax, year 2026. Flyway migrations, and files copied from LogicalDOC, don't.
- **CI's time to green doesn't grow** by a second smoke run or by slower tests (spec §2.4).

## Review Focus

What the spec implies and a task's happy-path tests wouldn't otherwise meet, most likely first. Each has its test in the task named.

1. **A move interrupted part-way,** by a killed container or a lost connection, and then run again. A person expects the second run to start over cleanly, and the app never to start on the half-made database between the two (Task 18, `test-move.sh`'s second case; Task 14's guard).
2. **A value PostgreSQL can't hold, other than a NUL:** MariaDB's zero date, a number beyond the target column's range, text longer than the target column allows. A person expects the move to stop and name the table, the row's key and the column, not a stack trace and never a quiet change (Task 16, `MoveValuesTest`; Task 18's second case).
3. **An install started on the new version without moving, in each way that can happen:** `DB_HOST` still naming the MariaDB server, so nothing answers as PostgreSQL; the conf volume lost; a restore whose files came back before its database. A person expects one clear message that says what to do, within seconds, and nothing changed (Task 14).
4. **A PostgreSQL of the admin's own** that's older than 18, isn't UTF-8, sorts with another locale, or whose role can't create tables. A person expects a refusal or a warning that names the cause, before any table is made (Task 13).
5. **A lock held longer than the timeout** while the app runs. A person expects the waiting request to fail and a retry to work, never a thread that waits for good (Task 13: the setting, and `LockTimeoutTest`).

## Spec Corrections and Calls This Plan Makes

Where this plan decides what the spec left open, or departs from it.

1. **The lock timeout is a setting of the app's role in its database** (`alter role … in database … set lock_timeout = '50s'`), which the start-up tool applies and reads back at every start (Task 13). The spec's §14 offered the JDBC URL's `options` or the pools' connection-init keys. A role setting needs no URL-encoding and no pool that honours a key, covers Flyway's and the start-up tool's own connections, and can be read back and tested.
2. **The API refuses text it can't keep in request bodies too,** through `ApiJson`'s mapper (Task 10). The spec's §7.5 names query parameters only; a `\u0000` in a JSON string would otherwise still be a `500`.
3. **The start-up tool also refuses a role that can't create tables** (Task 13), which the spec's §8.2 doesn't list and Review Focus 4 needs.
4. **Tests that still fail after the base class moves are marked `@Ignore("PostgreSQL: plan Task N")`** in Task 4, and each of Tasks 5 to 8 takes its own marks off. CI stays green at every commit, and each task shows its tests failing first. Task 8's last step checks none is left.
5. **PR A keeps MariaDB working** by using a savepoint where the spec's §7.3 says `on conflict`; Task 12, in PR B, makes the change (spec §13.1 already says so for the two inserts; this plan extends it to `healdan-storage`'s `insertIfAbsent`).
6. **The move reads dates and times as text** and parses them itself (Task 16), so that MariaDB's zero date is caught whatever the driver would have made of it.
7. **The move's markers are a table, `docker_move`,** beside the start-up tool's `docker_init` (Task 14, Task 17). The spec's §14 left the form open.
8. **`VersionResource`'s order needs no change,** only its comment (Task 8): it already breaks a tie on a version's date by id.
9. **A sign-in identity isn't trimmed** (Task 8), though the recon lists it among the untrimmed comparisons: it has to match its provider's claim exactly.
10. **`EmailFiling` isn't changed for part-way failures** (Task 7): it already undoes what it filed, and its test passed on PostgreSQL in the trial.
11. **The counters' arithmetic was confirmed on the owner's data** on 2026-11-18, read-only, on the clone: `ld_document`'s highest id was 5,007,455, from the sequence value 100,150 (100,150 × 50 = 5,007,500), with MariaDB's `next_not_cached_value` at 150,100 (Task 16's test uses these figures). The spec's §14 asked for this before the check was relied on.
12. **The spec's "nine places that refuse" are eleven call sites in nine classes,** two more refuse silently (`SignInAccounts`), and the replacements are five call sites in four classes (Task 10). `openapi.yaml` has four descriptions to reword, not three.
13. **HSQLDB's MVCC statement is in twelve test files, 26 times,** not thirteen files (Task 4).

## Conventions

- **Shorthands:**
  - `HC/` = `healdan-core/src/main/java/com/logicaldoc/healdan/` and `HCT/` = its tests (`healdan-core/src/test/java/com/logicaldoc/healdan/`).
  - `API/` = `healdan-api/src/main/java/com/logicaldoc/healdan/api/` and `APIT/` = its tests.
  - `ST/` = `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/` and `STT/` = its tests.
  - `DT/` = `docker/tools/src/` and `DTT/` = `docker/tools/test/`.
- **The test database** (from Task 1): `docker/tests/test-db.sh start` before any `healdan-*` test, and `stop` when done. From a Maven container, add `--network healdan-test -e HEALDAN_TEST_DB_URL=jdbc:postgresql://healdan-test-db:5432/`.
- **Running Maven** (other sessions share `~/.m2`; run long builds in the background). `~/.m2/settings.xml` is a copy of `docker/maven-settings.xml`, and `~/logicaldoc-dev.properties` exists (an empty file works).
  - `healdan-core`: `mvn -B -ntp -pl healdan-core test -Dtest=<Class>`, then `mvn -B -ntp -pl healdan-core -DskipTests install` (with its test-jar) before `healdan-api`'s or `healdan-storage`'s tests.
  - After editing `logicaldoc-core`: `mvn -B -ntp -pl logicaldoc-core -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install` before Healdan's tests.
  - The whole `healdan-core` suite takes about half an hour, `healdan-api`'s and `healdan-storage`'s less. Never run two builds on one module's `target/` at once, and delete `target/jacoco.exec` after a killed run.
- **`docker/tools`' tests** are home-made, not JUnit: a class with a `main`, `Check.eq`, `Check.isTrue`, `Check.throwsWith` and `Check.done("<Suite>")`, compiled by `build.sh`'s glob and run only by a `run_test <Class>` line there. `docker build --target tools .` runs them all on the WAR's own libraries.
- **The smoke test:** `bash docker/smoke-test.sh` on a fresh stack; on a Mac add `WEB_NETWORK=bridge`. A second run beside another sets `SMOKE_PROJECT`, `SMOKE_PORT`, `SMOKE_KEYCLOAK_PORT` and `SMOKE_GARAGE_PORT`.
- **Java sources are read as ISO-8859-1:** write non-ASCII characters as `\u` escapes.
- **The trial's branches** are local and never pushed: `worktree-agent-a0c76d3c6e1269309` (the stack) and `worktree-agent-a398a3377c824f806` (the tests). Read them with `git show <branch>:<path>` or `git diff 97b48b5cf <branch> -- <path>`; never check them out or merge them. They're a starting point where a task says so, and throwaway otherwise.
- **Commits:** one per task, imperative subject, a body saying why, the `Co-Authored-By` trailer.

## PRs

All target `master` ([[stacked-prs]]: CI runs only for pull requests to `master`).

- **PR A · Tasks 1–9.** Healdan runs on PostgreSQL and its Java tests run there. The stack is still MariaDB's, and a running server behaves as before. It can merge as soon as it's green.
- **PR B · Tasks 10–22.** The switch. Stacked on PR A, a draft until Task 23's rehearsal passes; `mariadb-final` is tagged on the commit it merges onto.
- **Task 23** is the gate between them and the merge: the rehearsal on the owner's clone, and the owner's go.
- **PR C · Task 24.** The records.
- **Task 25** is the owner's server and the acceptance. **Task 26** is a pull request of its own, later.

Expected costs, replaced by each pull request's measured figures once it has them:

| PR | `build` | `Tests` jobs | Docker image and smoke test |
|---|---|---|---|
| A | measured, over three runs each side: the workflow 16.2 to 17.0 minutes on HSQLDB and 20.2 to 20.7 on PostgreSQL; the service container starts in 12 to 16 s in each shard | measured, and not the "no slower" this row expected: the longest job 950 to 995 s before and 1,182 to 1,200 s after, Healdan's classes 13% slower, 0.3 s a test ("Measured", under the Decision Log, has where it went and what #278 took back) | measured: no change, 21.8 to 23.4 minutes before and 21.7 to 22.9 after |
| B | none | a few new tests | the smoke test on PostgreSQL in place of MariaDB, about as long (the trial: 8.0 minutes of browser tests against 7.1); the move's test, a few minutes, on amd64 only. CI's measured figures are in the pull request's description, and the local ones in the Decision Log (Measured, 2026-11-19) |

---

## PR A · Healdan Runs on PostgreSQL, and Its Tests Run There

### Task 1: A PostgreSQL for tests, and Healdan's tables on it

Spec §6.5, §10.3.

**Files:**
- Create: `docker/tests/test-db.sh`
- Create: `HCT/TestDatabase.java`
- Create: `healdan-core/src/main/resources/healdan/db/migration/postgresql/V1__core_tables.sql` to `V11__trash.sql` (the eleven names `mariadb/` has)
- Modify: `healdan-core/pom.xml` (after `flyway-database-hsqldb`, 32-36: `org.flywaydb:flyway-database-postgresql:${flyway.version}`; with the test dependencies, 166-175: `org.postgresql:postgresql:42.7.7`, test scope, with a comment that it's the version `logicaldoc-webapp/pom.xml:140` ships)
- Modify: `HC/db/HealdanMigrator.java` (`vendorFolder` 159-175)
- Modify: `.github/workflows/build-logicaldoc.yml` (the `tests` job, 165-249)
- Test: `HCT/db/PostgresMigrationsTest.java`

**Interfaces:**
- Produces:

```java
/** The PostgreSQL server Healdan's tests run on, and a database for each test. Its user is the test container's superuser. */
public final class TestDatabase {
	static final String HOW_TO_START = "Healdan's tests need a PostgreSQL. Start one with: docker/tests/test-db.sh start";
	/** HEALDAN_TEST_DB_URL, the server's JDBC URL without a database; by default jdbc:postgresql://localhost:5432/ */
	public static String serverUrl();
	/** HEALDAN_TEST_DB_USER, by default healdan */
	public static String user();
	/** HEALDAN_TEST_DB_PASSWORD, by default healdan-test */
	public static String password();
	/** Makes an empty database with the Global Constraints' encoding and locale, whatever the server's default is.
	 *  @return its name, unique to this JVM and call
	 *  @throws IllegalStateException with HOW_TO_START when no server answers */
	public static String create();
	/** Drops a database this class made, forcing off any connection left; does nothing for null */
	public static void drop(String name);
	/** Loads classpath SQL scripts as upstream's AbstractTestCase does: through HSQLDB's SqlFile, read as Cp1252 */
	public static void load(Connection connection, List<String> scripts) throws SQLException, IOException;
	/** A plain data source on one of this server's databases */
	public static DataSource dataSource(String database);
}
```

  `docker/tests/test-db.sh start|stop`: `start` runs `postgres:18` as the container `healdan-test-db` on the Docker network `healdan-test` (making it if it's missing), publishes `127.0.0.1:${HEALDAN_TEST_DB_PORT:-5432}:5432`, keeps its data on a tmpfs, sets `POSTGRES_USER=healdan`, `POSTGRES_PASSWORD=healdan-test` and the Global Constraints' `POSTGRES_INITDB_ARGS`, and runs with `-c fsync=off -c synchronous_commit=off -c full_page_writes=off -c max_connections=300`. It waits for `pg_isready` over TCP and prints the two ways to reach it: `HEALDAN_TEST_DB_URL=jdbc:postgresql://localhost:5432/` from the host, and `jdbc:postgresql://healdan-test-db:5432/` from a Maven container started with `--network healdan-test`. `stop` removes the container. Starting it when it's running says so and exits 0.

- [ ] **Step 1: Write `test-db.sh` and start it**

Run: `docker/tests/test-db.sh start`
Expected: `healdan-test-db` running, and `docker exec healdan-test-db psql -U healdan -Atc "select datlocprovider, datlocale, datcollate from pg_database where datname = 'template1'"` prints `b|C.UTF-8|C`.

- [ ] **Step 2: Write the failing test**

`PostgresMigrationsTest` is plain JUnit 4, with no Spring context. `@Before` makes a database with `TestDatabase.create()` and loads `sql/logicaldoc-core.sql` into it with `TestDatabase.load`; `@After` drops it.
  - `testADatabaseHasTheLocaleHealdanRunsOn`: `select datlocprovider, datlocale, datcollate, pg_encoding_to_char(encoding) from pg_database where datname = current_database()` is `b`, `C.UTF-8`, `C`, `UTF8`.
  - `testEveryScriptApplies`: `new HealdanMigrator(dataSource).migrate()` returns `Status.MIGRATED`; `currentVersion()` is `11`; `hd_schema_history` has eleven rows, all with `success` true.
  - `testTheTablesMatchHsqldbs`: migrate an in-memory HSQLDB with the `hsqldb/` scripts too (`MigrationTestSupport` shows how), and compare both databases' `information_schema.columns` for tables named `hd_%`, names lower-cased, `hd_schema_history` left out: the same tables, each with the same columns.
  - `testWhatTheTranslationChanged`:
    - `hd_api_token.hd_refresh` has `data_type` `text` (V7);
    - `hd_trash.hd_id` has `is_identity` `YES` (V11);
    - every column `mariadb/` declares `datetime(3)` (V4's and V11's) has `datetime_precision` 3, and every other date column 6;
    - `pg_indexes` has an index named for each inline `index` of `mariadb/`: `hd_preview_job_due` (V2), `hd_email_messageid` (V8) and V11's three.
  - `testARerunDoesNothing`: a second `migrate()` leaves eleven rows.

- [ ] **Step 3: Run it to see it fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=PostgresMigrationsTest`
Expected: FAIL: `TestDatabase` doesn't exist; then, once it does, `HealdanMigrator` refuses with "found PostgreSQL".

- [ ] **Step 4: Write `TestDatabase`, the scripts and the migrator's branch**
  - `create()` runs, on the server's `postgres` database: `create database <name> template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C'`. `drop` runs `drop database if exists <name> with (force)`.
  - **The scripts are `mariadb/`'s, translated one for one by five changes and no other** (recon §3.2):
    1. the table options `ENGINE=… DEFAULT CHARSET=… COLLATE …` go;
    2. `datetime` becomes `timestamp`, and `datetime(3)` `timestamp(3)`;
    3. an inline `index name (columns)` becomes a `create index name on table (columns);` after its table;
    4. V7's `alter table hd_api_token modify hd_refresh text` becomes `alter table hd_api_token alter column hd_refresh type text`;
    5. V11's `hd_id bigint auto_increment primary key` becomes `hd_id bigint generated by default as identity primary key`.

    Keep each script's comments, with a line added where a comment speaks of MariaDB's behaviour. The trial's translation, which applied on PostgreSQL 18.4 through Flyway, is on the local branch `worktree-agent-a0c76d3c6e1269309` under the same paths: start from it (`git show <branch>:<path>`), and check each script against its `mariadb/` original, statement by statement.
  - `vendorFolder` returns `postgresql` when the product name contains `postgresql`. Its refusal, for anything else, says `Healdan's tables support MariaDB, PostgreSQL and HSQLDB only; found …`. `HealdanMigratorTest.testRefusesUnsupportedDatabase` (147) drops PostgreSQL from its refused list.

- [ ] **Step 5: Run the tests**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PostgresMigrationsTest,HealdanMigratorTest'`
Expected: PASS.

- [ ] **Step 6: Give CI's test jobs a PostgreSQL**

In `build-logicaldoc.yml`'s `tests` job, beside `runs-on`:

```yaml
    services:
      postgres:
        image: postgres:18
        env:
          POSTGRES_USER: healdan
          POSTGRES_PASSWORD: healdan-test
          POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C"
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -h 127.0.0.1 -U healdan" --health-interval 5s --health-timeout 5s --health-retries 20
```

The defaults of `TestDatabase` reach it at `localhost:5432`, so the Test step's command (247-249) doesn't change.

- [ ] **Step 7: Commit**

```bash
git add docker/tests/test-db.sh healdan-core .github/workflows/build-logicaldoc.yml
git commit -m "PostgreSQL: Healdan's tables, and a database for tests"
```

### Task 2: Booleans bound as integers

Spec §7.1.

**Files:**
- Create: `healdan-core/src/main/resources/hibernate.properties`
- Test: `HCT/db/BooleanBindingTest.java`; `DTT/WarHibernateTest.java` (with a `run_test WarHibernateTest` line in `docker/tools/build.sh`)

- [ ] **Step 1: Write the failing tests**
  - `BooleanBindingTest extends AbstractHealdanTestCase`:
    - `testBooleansBindAsIntegers`: the session factory's type configuration prefers `java.sql.Types.INTEGER` for booleans (`SessionFactoryImplementor.getTypeConfiguration().getPreferredSqlTypeCodeForBoolean()`).
    - `testAUsersFlagIsWrittenAsANumber`: a user saved with `enabled` false reads back, by JDBC, as `0` in `ld_enabled`, and as `1` once enabled.
  - `WarHibernateTest` (home-made, on the WAR's own `WEB-INF/lib`): `ClassLoader.getResources("hibernate.properties")` has exactly one element, and loaded as `Properties` it holds `hibernate.type.preferred_boolean_jdbc_type` = `INTEGER`. So a jar that one day brings a `hibernate.properties` of its own fails the image build.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=BooleanBindingTest`
Expected: `testBooleansBindAsIntegers` FAILS with the dialect's own type code. `testAUsersFlagIsWrittenAsANumber` passes on HSQLDB already, and is there for PostgreSQL (Task 4).

- [ ] **Step 3: Add the file**

```properties
# Healdan: LogicalDOC maps Java booleans onto int columns. PostgreSQL refuses a boolean there, so Hibernate
# binds every boolean as an integer, on every database (docs/superpowers/specs/2026-11-18-postgresql-design.md, 7.1).
hibernate.type.preferred_boolean_jdbc_type=INTEGER
```

Hibernate reads a `hibernate.properties` at the classpath's root when it starts. It's configuration, so it carries no licence header.

- [ ] **Step 4: Run the tests, then the image's**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=BooleanBindingTest`, then `mvn -B -ntp -pl healdan-core -DskipTests install` and `docker build --target tools .`
Expected: PASS, `WarHibernateTest` among the tools' suites.

- [ ] **Step 5: Check MariaDB still agrees**

The setting reaches the running stack, which is MariaDB's until PR B. Run: `bash docker/smoke-test.sh`
Expected: every check passes.

- [ ] **Step 6: Commit**

```bash
git add healdan-core/src/main/resources/hibernate.properties healdan-core/src/test docker/tools
git commit -m "Hibernate: bind booleans as integers, as PostgreSQL needs"
```

### Task 3: Typed reads that don't cost a transaction

Spec §7.2. One of the two upstream edits.

**Files:**
- Create: `logicaldoc-core/src/main/java/com/logicaldoc/core/HealdanTypedReads.java` (fork-owned, beside `HealdanHooks.java`)
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/HibernatePersistentObjectDAO.java` (LF endings): `processQueryForListRecord`, `resultSet.getObject(1, requiredType)` at ~495; `queryForObject`, `rs.getObject(1, requiredType)` at ~610
- Modify: `.github/workflows/build-logicaldoc.yml` (`UPSTREAM_TESTS`, 98-162: add `HealdanTypedReadsTest`)
- Test: `logicaldoc-core/src/test/java/com/logicaldoc/core/HealdanTypedReadsTest.java`

**Interfaces:**
- Produces:

```java
/** Reads one value as the type a caller asks for, converting in Java what MariaDB's driver converts and PostgreSQL's refuses */
public final class HealdanTypedReads {
	/**
	 * @param rs the result set, on a row
	 * @param value its first column, as rs.getObject(1) returned it
	 * @param type the type asked for
	 * @return null for SQL NULL; the value itself when it is already of the type; a number converted to Long, Integer,
	 *         Double, Float, BigDecimal or String; otherwise what rs.getObject(1, type) gives
	 */
	public static <R> R read(ResultSet rs, Object value, Class<R> type) throws SQLException;
}
```

- [ ] **Step 1: Write the failing test**

`HealdanTypedReadsTest` is plain JUnit 4. Its result set is a `java.lang.reflect.Proxy` whose `getObject(int, Class)` throws `SQLException("conversion to class java.lang.Long from int4 not supported")`, as pgjdbc does, and counts its calls.
  - `testAnIntColumnReadAsALong`: `read(rs, Integer.valueOf(1), Long.class)` is `1L`, and the driver wasn't asked.
  - `testASumReadAsALong`: `read(rs, new BigDecimal("42"), Long.class)` is `42L`.
  - `testACountReadAsAnInteger`: `read(rs, Long.valueOf(7), Integer.class)` is `7`.
  - `testANumberReadAsText`: `read(rs, Long.valueOf(7), String.class)` is `"7"`.
  - `testTheTypeItAlreadyIs`: a `String` for `String.class`, and a `java.sql.Timestamp` for `Timestamp.class`, come back as they are.
  - `testNullIsNull`: `read(rs, null, Long.class)` is null, and the driver wasn't asked.
  - `testWhatItCantConvertGoesToTheDriver`: `read(rs, "abc", Long.class)` throws the proxy's `SQLException`: the driver was asked once.

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest=HealdanTypedReadsTest`
Expected: FAIL to compile, `HealdanTypedReads` doesn't exist.

- [ ] **Step 3: Write `HealdanTypedReads`, and call it from the two lines**

In `HibernatePersistentObjectDAO`, each `…getObject(1, requiredType)` in the `else` after the `Timestamp` case becomes `HealdanTypedReads.read(<the result set>, obj, requiredType)`, with a `// Healdan:` comment on the line above saying why in a few words. Nothing else in the file changes: `queryForLong`'s retry stays, and no longer has anything to catch.

- [ ] **Step 4: Run the test, re-install the module, and run a class that reads through it**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest=HealdanTypedReadsTest`, then `mvn -B -ntp -pl logicaldoc-core -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install`, then `mvn -B -ntp -pl healdan-core test -Dtest=PermissionRulesTest`
Expected: PASS, all three.

- [ ] **Step 5: Commit**

```bash
git add logicaldoc-core .github/workflows/build-logicaldoc.yml
git commit -m "Typed reads: convert numbers in Java, so PostgreSQL keeps the transaction"
```

### Task 4: Healdan's tests move to PostgreSQL

Spec §10.3. After this task no test of a `healdan-*` module opens an HSQLDB database through the base class, and CI runs them on its PostgreSQL service.

**Files:**
- Modify: `HCT/AbstractHealdanTestCase.java`
- Modify: `healdan-core/src/test/resources/context.properties` (1-8), `healdan-core/src/test/resources/context.xml` (34)
- Modify: the eleven overrides of `getDatabaseScripts()`: `HCT/security/PermissionRulesTest.java:92`, `HCT/trash/TrashRecordingTest.java:63`, `HCT/trash/TrashRulesTest.java:71`, `HCT/profile/DocumentProfileTest.java:61`, `HCT/rules/ChannelFilingTest.java:40`, `HCT/rules/ChannelTreePermissionsTest.java:80`, `HCT/docnumber/DocNumberTest.java:66`, `HCT/email/EmailDocumentListenerTest.java:59`, `HCT/email/EmailIndexWorkerTest.java:66`, `HCT/email/EmailIndexTest.java:69`, `HCT/channels/SearchFolderToleranceTest.java:77`
- Modify: `ST/AbstractStorageTestCase.java:40-51` (its `getConfigResource()`)
- Modify: `HCT/VetoAtomicityTest.java` (186-195, 294, 307: one database for the class)
- Modify: the twelve files that set HSQLDB's MVCC mode, 26 statements: `HCT/profile/PropagationTest.java:587`, `HCT/email/OncePerMatterConcurrencyTest.java:204`, `APIT/SaveTest.java` (179, 203, 229, 253, 276, 295, 313, 335), `APIT/CheckoutResourceTest.java` (101, 119, 316, 336, 358, 382), `APIT/PropertiesResourceTest.java` (531, 552), `APIT/FilingTest.java` (198, 215), `STT/HeldDeletesTest.java:409`, `STT/HoldRollbackTest.java:65`, `STT/FileLocksTest.java:47`, `STT/mover/MoverTest.java:113`, `STT/seed/SmokeStorageSeedTest.java:74`, `STT/web/StorageAdminTest.java:120`; and the sentence about it in `APIT/BackgroundWrite.java:36`
- Modify: `healdan-api/pom.xml` (215-224) and `healdan-storage/pom.xml` (187-196): `org.postgresql:postgresql:42.7.7`, test scope
- Test: `HCT/db/TypedReadsOnPostgresTest.java`

**Interfaces:**
- Consumes: `TestDatabase` (Task 1).
- Produces, for every later test:

```java
public abstract class AbstractHealdanTestCase extends AbstractTestCase {
	/** Upstream's database steps are HSQLDB's, and do nothing without scripts: so it gets none, and setUp loads them */
	@Override protected final List<String> getDatabaseScripts();   // always List.of()
	/** The scripts that make the test's database; what subclasses override */
	protected List<String> databaseScripts();                      // List.of("sql/logicaldoc-core.sql", "data.sql")
	@Override protected final String getConfigResource();          // baseConfigResource(), written again for this test's database
	/** The config resource of the test; what subclasses override */
	protected String baseConfigResource();                         // "context.properties"
	/** This test's database, on TestDatabase's server */
	protected final String database();
}
```

- [ ] **Step 1: Write the test that only PostgreSQL fails**

`TypedReadsOnPostgresTest extends AbstractHealdanTestCase`, `testAnIntReadKeepsTheTransaction` (recon §2.6's probe): in one `TransactionTemplate`, load user 1, change its first name, and call `UserDAO.get().queryForInt("select ld_enabled from ld_user where ld_id = 1")`; after the commit, the name read by JDBC is the new one. `testAUserEditIsKept`: `UserDAO.get().store(user)` of an existing user with a new name is read back changed.

- [ ] **Step 2: Move the base class**
  - `setUp`: `database = TestDatabase.create()`; `super.setUp()`; `TestDatabase.load(getConnection(), databaseScripts())`; then the store's clean-up and `HealdanStartup.run()` as now.
  - A new `tearDown`: `super.tearDown()`, then `TestDatabase.drop(database)` in a `finally`.
  - `getConfigResource()` writes `target/test-classes/<base>` again as `target/test-classes/pg-<base>`, with `jdbc.url` the test's database, `jdbc.username` and `jdbc.password` `TestDatabase`'s, and returns that name. For the base `context.properties` it starts from `pg-context.properties` when that exists, as a test may read what an earlier one wrote there, which is how the tests behave today.
  - `context.properties`: `jdbc.driver=org.postgresql.Driver` (a new key), `jdbc.dbms=postgresql`, `hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect`, `jdbc.url` a placeholder the base class always replaces. `context.xml:34` reads `${jdbc.driver}`.
  - The eleven subclasses override `databaseScripts()`, and `AbstractStorageTestCase` `baseConfigResource()`.
  - `VetoAtomicityTest` keeps one context for its class, so it keeps one database too: its `tearDown` doesn't drop it, and `closeSharedContext` drops the owner's.
  - Delete the 26 MVCC statements, and each test's comment about them. PostgreSQL reads what's committed while a transaction is open without being asked.

- [ ] **Step 3: Run the three suites**

Run, with `docker/tests/test-db.sh start` done: `mvn -B -ntp -pl healdan-core test`, then `mvn -B -ntp -pl healdan-core -DskipTests install`, `mvn -B -ntp -pl healdan-api test` and `mvn -B -ntp -pl healdan-storage test` (in the background: the first takes about half an hour).
Expected: the trial's result (spec §4.2): every test passes but those of the next step. Anything else failing is a finding: stop and report it before going on.

- [ ] **Step 4: Mark what later tasks mend, and nothing else**

Each gets `@Ignore("PostgreSQL: plan Task N")`, so that CI is green and each task shows its tests going from failing to passing:
  - **Task 5** (the tests' own HSQLDB assumptions): `VetoAtomicityTest` (the class); `HealdanStorageTest.testInitReadsNothing`; `StorageDataTest.testMigrationMadeTheTables`; `EmailIndexWorkerTest.testResumesFromItsCursor` and `testAFullBatchThenNothingIsDone`; `StorageAdminTest.testSaveSettingsInOneTransaction` and `testFailedAddRecordsNothing`; and whichever of `HealdanMigratorTest`'s tests still expect HSQLDB.
  - **Task 6:** `EmailIndexTest.testAnIndexFailureNeverRefusesTheEmail`.
  - **Task 7:** `SaveTest.testUnstoredSaveIsAnError` and `testUnrecordedVersionIsAnError`; `FilingTest.testUnrecordedFirstVersionIsAnError`.
  - **Task 8:** the two tests of `SignInDataTest` and the one of `StorageDataTest` that assert `ConstraintViolationException`.

- [ ] **Step 5: Run the suites again, then commit**

Expected: PASS, with only those tests skipped.

```bash
git add healdan-core healdan-api healdan-storage
git commit -m "Tests: Healdan's Java tests run on PostgreSQL"
```

### Task 5: The tests' own HSQLDB assumptions, and the end of `hsqldb/`

Spec §10.3, §6.5.

**Files:**
- Modify: `HCT/support/FixtureBackup.java` (`restore` 117-144, `read` 168-182), `HCT/VetoAtomicityTest.java`
- Modify: `STT/HealdanStorageTest.java:433-448`, `STT/StorageDataTest.java:73-76`, `HCT/email/EmailIndexWorkerTest.java:250-260` (`copiesOf`), `STT/web/StorageAdminTest.java` (776-782, 1042-1049)
- Modify: `HCT/db/HealdanMigratorTest.java`, `HCT/db/MigrationTestSupport.java:27`, `HCT/HealdanStartListenerTest.java` (18, 147-165), `HCT/db/PostgresMigrationsTest.java`
- Move: `healdan-core/src/test/resources/healdan/db/failing/hsqldb/V2__broken.sql` and `…/misnamed/hsqldb/V2_misnamed.sql` to `…/failing/postgresql/` and `…/misnamed/postgresql/`
- Delete: `healdan-core/src/main/resources/healdan/db/migration/hsqldb/` (eleven scripts)
- Modify: `healdan-core/pom.xml` (remove `flyway-database-hsqldb`, 32-36), `HC/db/HealdanMigrator.java` (`HSQLDB_LOCATION` 40, `vendorFolder`)

- [ ] **Step 1: Take the `@Ignore`s of Task 5 off, and see each fail**

Run each class named in Task 4's Step 4 for Task 5.
Expected: FAIL, each for the reason the spec's §4.2 gives.

- [ ] **Step 2: Mend each**
  - `FixtureBackup.restore`: in place of HSQLDB's `set database referential integrity false`, the restore's connection runs `set session_replication_role = replica` before its deletes and inserts and `set session_replication_role = origin` in the `finally` (the test server's user is a superuser). `read` lists the tables of `current_schema()`.
  - `HealdanStorageTest.testInitReadsNothing` and `StorageDataTest.testMigrationMadeTheTables` read `information_schema.tables` with lower-case names and `table_schema = current_schema()`.
  - `EmailIndexWorkerTest.copiesOf` puts `ld_id` and `ld_customid`, the keys the row map has on PostgreSQL.
  - `StorageAdminTest`'s two injected faults become a function and a trigger, dropped where the HSQLDB trigger was:

    ```sql
    create function hd_test_refuse() returns trigger language plpgsql as
      $$ begin raise exception 'refused by the test' using errcode = '45000'; end $$;
    create trigger hd_test_refuse_cache before update on hd_setting
      for each row when (new.name = 'storage.cacheGb') execute function hd_test_refuse();
    ```

  - `HealdanMigratorTest` and `HealdanStartListenerTest.testNoCeSchemaCallsNone` make their own databases with `TestDatabase.create()` and `TestDatabase.dataSource`, and drop them. `MigrationTestSupport` configures `classpath:healdan/db/migration/postgresql`. In `HealdanMigratorTest`: `testRefusesUnsupportedDatabase` refuses Oracle, Microsoft SQL Server, MySQL and HSQLDB by fake metadata; `testAcceptsMariadbThroughTheMysqlDriver` checks `defaultLocation` for MariaDB's and PostgreSQL's metadata; `testFailingScriptFailsStartup` also asserts that the failed script left no table behind, as PostgreSQL rolls it back.
  - `PostgresMigrationsTest.testTheTablesMatchHsqldbs` goes, with the folder it compared with.

- [ ] **Step 3: Delete `hsqldb/`, Flyway's HSQLDB module and the migrator's branch**

`vendorFolder` refuses HSQLDB with the rest: `Healdan's tables support MariaDB and PostgreSQL only; found …`. The `hsqldb` and `sqltool` test dependencies of the three modules stay: upstream's `AbstractTestCase` names `SqlFile` and its error class, and `TestDatabase.load` uses it.

- [ ] **Step 4: Run the three suites**

Expected: PASS. `grep -rn "hsqldb" healdan-*/src/test/java` finds only `TestDatabase`'s `SqlFile` import. `grep -rn 'Ignore("PostgreSQL: plan Task 5' healdan-*` finds nothing.

- [ ] **Step 5: Commit**

```bash
git add -A healdan-core healdan-api healdan-storage
git commit -m "Tests: no HSQLDB assumption left, and no hsqldb migrations"
```

### Task 6: Failures Healdan lets pass inside a save

Spec §7.3. In PR A the two inserts use a savepoint, which MariaDB has too; `on conflict` comes in Task 12.

**Files:**
- Move: `ST/Savepoints.java` to `HC/util/Savepoints.java`, made public
- Modify: `ST/HeldCopies.java` (66, 70, 86), `ST/StoredFiles.java` (140): the import
- Modify: `HC/preview/PreviewJobs.java` (`add` 70-78), `HC/email/EmailIndex.java` (`put` 104-110)
- Modify: `HC/email/EmailDocumentListener.java` (`afterStore` 138-152, `beforeCheckin` 164-182, `afterCheckin` 190-196), `HC/preview/PreviewDocumentListener.java` (`afterFileStore` 46-54, `afterStore` 56-68, `beforeStore` 90-100), `HC/navigation/FavoriteNamesDocumentListener.java` (`afterStore` 46-59), `HC/navigation/FavoriteNamesFolderListener.java` (`afterStore` 42-54)
- Test: `HCT/util/SavepointsTest.java` (new), `HCT/preview/PreviewJobsTest.java`, `HCT/preview/PreviewDocumentListenerTest.java`, `HCT/email/EmailIndexTest.java`

**Interfaces:**
- Produces:

```java
package com.logicaldoc.healdan.util;

/** Statements that may fail inside a caller's transaction, run so that the transaction survives them */
public final class Savepoints {
	/** Runs work inside a savepoint when the caller's transaction holds the data source's connection; as it is otherwise.
	 *  @throws RuntimeException what the work throws, once the savepoint is rolled back */
	public static <T> T run(JdbcTemplate jdbc, Supplier<T> work);
	public static void run(JdbcTemplate jdbc, Runnable work);
	public static boolean inTransaction(JdbcTemplate jdbc);
	/** @return true if the row was inserted, false if one with its key was there */
	public static boolean insertIfAbsent(JdbcTemplate jdbc, String sql, Object... arguments);
}
```

- [ ] **Step 1: Write the failing tests, and take Task 6's `@Ignore` off**
  - `SavepointsTest extends AbstractHealdanTestCase`:
    - `testAFailureInsideKeepsTheTransaction`: in a `TransactionTemplate`, insert a setting row; `Savepoints.run` a statement that fails (`insert` of the same primary key), catching what it throws; insert another row. After the commit both rows are there.
    - `testOutsideATransactionItJustRuns`: `inTransaction` is false, and `run` returns the work's value.
    - `testInsertIfAbsent`: true for a new key, false for one that's there, in a transaction that then commits its other work.
  - `PreviewJobsTest#testAddTwiceInsideATransactionKeepsIt`: in a `TransactionTemplate`, `add` the same job twice, then `update hd_setting …`; the commit keeps the job and the update.
  - `PreviewDocumentListenerTest#testAReplacedFilesQueueFailureKeepsTheSave`: with `hd_preview_job` renamed away, `DocumentManager.replaceFile` on a document still leaves its record at the new file (the save committed), and a warning in the log.
  - `EmailIndexTest#testPutTwiceInsideATransactionKeepsIt`: as `PreviewJobsTest`'s, with `put`; the row holds the second Message-ID.
  - `EmailIndexTest#testAnIndexFailureNeverRefusesTheEmail` (258-274) as it is, its `@Ignore` gone.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='SavepointsTest,PreviewJobsTest,PreviewDocumentListenerTest,EmailIndexTest'`
Expected: `SavepointsTest` fails to compile. The others FAIL with PostgreSQL's `current transaction is aborted`, or with Spring's `UnexpectedRollbackException`.

- [ ] **Step 3: Move `Savepoints`, and use it**
  - `PreviewJobs.add` is `Savepoints.insertIfAbsent(jdbc, <the insert>, …)`, its result unused. `EmailIndex.put` inserts with `insertIfAbsent`, and updates when it returns false.
  - Each listener method named above runs the body of its `try` inside `Savepoints.run(new JdbcTemplate(Context.get(DataSource.class)), …)`. Its `catch` stays as it is: the savepoint is what makes the swallow keep the save.
  - `TokenUserListener` and `ThrottleUserListener` aren't changed: what they call goes through CE's transactional DAO, whose failure marks the user's save for rollback on any database (recon §4.2), so their catches never kept the save, and a savepoint wouldn't either.

- [ ] **Step 4: Run the tests, re-install, and run the storage module's**

Run: the command of Step 2, then `mvn -B -ntp -pl healdan-core -DskipTests install` and `mvn -B -ntp -pl healdan-storage test -Dtest='StorageDataTest,HeldDeletesTest,HealdanStorageTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A healdan-core healdan-storage
git commit -m "Savepoints around what a save lets fail, so PostgreSQL keeps the save"
```

### Task 7: A save that fails part-way still says what's left

Spec §7.3.

**Files:**
- Modify: `API/ProblemMapper.java` (a static beside `toResponse`, 75-107), `API/VersionResource.java` (`checkIn`'s catch, 336-345), `API/Filer.java` (`file`'s inner catch, 166-171)
- Test: `APIT/SaveTest.java` (`testUnrecordedVersionIsAnError` 483-513, `testUnstoredSaveIsAnError` 515-552), `APIT/FilingTest.java` (`testUnrecordedFirstVersionIsAnError` 229-255), `APIT/ProblemMapperTest.java`

**Interfaces:**
- Produces:

```java
// ProblemMapper
/** @return whether toResponse would answer this as an unexpected failure, 500 server-error: it holds no ApiProblem,
 *  AlreadyFiledException, StorageUnreachableException, HealdanVetoException, JsonProcessingException or
 *  WebApplicationException, and the client hasn't gone away */
static boolean unexpected(Throwable exception);
```

- [ ] **Step 1: Take Task 7's `@Ignore`s off, and make the three tests say what's true on PostgreSQL**

Today each asserts one fixed sentence, which is what HSQLDB left behind. On PostgreSQL CE's failed version write takes its transaction with it, so what's left may differ. Each test keeps `500` and `server-error`, and then asserts that the answer **agrees with the document read back**:
  - if the document's `fileVersion` moved, the detail is `confirmStored`'s "wasn't stored completely: its content is the file sent, as version …" sentence, and the timeline lacks that version;
  - if it didn't, the detail is "Document #%d's save wasn't stored: its content is as it was.";
  - for a filing, the detail is "Document #%d was filed, but its version 1.0 isn't in its timeline. Its content is the file sent." and the document's content is what was sent;
  - the sentence about the check-out matches the hold the document then has;
  - and the save sent again is `201`, with the version after the one the document is at.

  `ProblemMapperTest#testUnexpected`: true for a bare `PersistenceException` and for an `IllegalStateException` wrapping one; false for an `ApiProblem`, and for a `PersistenceException` whose cause is a `HealdanVetoException` or a `StorageUnreachableException`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='SaveTest,FilingTest,ProblemMapperTest'`
Expected: FAIL: the three answer "Something went wrong on the server."

- [ ] **Step 3: Read back after a failure CE raised**
  - `checkIn`: in the catch, a failure that isn't `overtaken` and is `ProblemMapper.unexpected` calls `confirmStored(docId, docNumber, before, user)` before it's rethrown. `confirmStored` throws the problem that says what's left, and returns only when the save is whole after all, in which case the failure itself is thrown as before. A refusal (a veto, a storage outage) is rethrown untouched.
  - `Filer.file`: in the inner catch, for an `unexpected` failure, look the document up by its doc # as `reportStored` does. If it's there, `VersionResource.confirmStored(id, docNumber, null, user)`. If it isn't, nothing was filed, and the failure is rethrown as it is.
  - `EmailFiling` isn't changed: it undoes what it filed (`undo`, 492-537), and `EmailFilingTest#testAllOrNothing` passed on PostgreSQL in the trial. If Step 4 shows otherwise, stop and report.

- [ ] **Step 4: Run the API's tests for saves and filing**

Run: `mvn -B -ntp -pl healdan-api test -Dtest='SaveTest,FilingTest,ProblemMapperTest,EmailFilingTest,CheckoutResourceTest'`
Expected: PASS. Write in the decision log which state PostgreSQL leaves in each of the three cases.

- [ ] **Step 5: Commit**

```bash
git add healdan-api
git commit -m "API: a save that fails part-way says what's left, on PostgreSQL too"
```

### Task 8: Duplicates, sorts with NULLs, and typed text

Spec §7.3 (duplicates), §7.4.

**Files:**
- Modify: `HC/signin/SignInIdentityDAO.java:59-61` (the documentation)
- Modify: `API/UserResource.java:57-58`, `HC/token/DeviceHolds.java:350`, `API/ApiViews.java:306`, `API/DocumentResource.java:584`, `API/VersionResource.java:117-120`
- Modify: `API/DocumentResource.java` (`find` 45, `byLegacy` 119-125), `HC/listing/FolderListing.java:515-518`
- Test: `HCT/signin/SignInDataTest.java` (126-131, 304), `STT/StorageDataTest.java:175`, `APIT/UserResourceTest.java`, `APIT/DocumentResourceTest.java`, `APIT/FolderResourceTest.java`

- [ ] **Step 1: Take Task 8's `@Ignore`s off, and write the tests**
  - The three duplicate tests assert `assertThrows(RuntimeException.class, …)` and then that nothing was stored: the identity's or group's rows are as they were, and the Store still has its first backend. On PostgreSQL the failure arrives as Hibernate's `GenericJDBCException`, the commit's second flush of the same insert meeting the ended transaction (spec §4.2).
  - `UserResourceTest#testAUserWithNoFirstNameSortsFirst`: a user whose first name is null comes before `Alice`, as on MariaDB.
  - `DocumentResourceTest#testALegacyNumberTypedWithASpace`: `GET /v1/documents?legacyDocNumber=ABC-1%20` finds the document whose legacy number is `ABC-1`.
  - `FolderResourceTest#testAMatterFilterTypedWithSpaces`: `matter=998%20.1` filters as `998.1` does.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=SignInDataTest`, and `mvn -B -ntp -pl healdan-api test -Dtest='UserResourceTest,DocumentResourceTest,FolderResourceTest'`
Expected: FAIL: the user with no first name sorts last on PostgreSQL, and the two typed values find nothing.

- [ ] **Step 3: Make the changes**
  - Each `order by` on a key that can be NULL says where NULLs go, keeping MariaDB's order: `lower(_entity.firstName) nulls first, lower(_entity.name) nulls first, _entity.id` in `UserResource`; `_entity.date desc nulls last, _entity.id desc` in `DeviceHolds`, `ApiViews` and `DocumentResource`; `_entity.versionDate desc nulls last, _entity.id desc` in `VersionResource`. HQL's `nulls first` and `nulls last` run on both databases (recon §4.3).
  - `VersionResource`'s comment at 117 says a version's date may tie with another's, without the "one-second precision": the order already breaks ties by id.
  - `find` and `byLegacy` strip the legacy number they're given; `FolderListing` strips the client and the matter either side of the dot (517-518).
  - A sign-in identity isn't trimmed: it has to match its provider's claim exactly, which PostgreSQL's comparison now does.
  - `SignInIdentityDAO`'s documentation says a duplicate fails the store and stores nothing, and names no exception type.

- [ ] **Step 4: Run the tests, then all three suites**

Expected: PASS, and `grep -rn 'Ignore("PostgreSQL: plan Task' healdan-*` finds nothing.

- [ ] **Step 5: Commit**

```bash
git add healdan-core healdan-api healdan-storage
git commit -m "PostgreSQL: where NULLs sort, typed text trimmed, and what a duplicate promises"
```

### Task 9: PR A's docs, and the pull request

Spec §12.

**Files:**
- Modify: `site/content/developer/tests.md` (`## Java Tests` 6-15, `### The API's Tests` 51-54, `### The Storage Tests` 120), `site/content/developer/building-from-source.md` (`## What You Need` 9-20, `## Commands` 25-35), `site/content/developer/continuous-integration.md` (23, 33), `site/content/developer/how-healdan-extends-logicaldoc.md`
- Modify: `CLAUDE.md` (24; the commands at 46-52; 233; the "Tests" section 268-278; the upstream-edits paragraph)
- Modify: `docs/superpowers/specs/2026-11-18-postgresql-design.md` (corrections from the build, each marked "changed in the build")

- [ ] **Step 1: Write the pages**
  - **Tests:** Healdan's Java tests run on a PostgreSQL server, a database for each test, made with the locale a real install has; `docker/tests/test-db.sh start` and `stop`; the three environment variables and their defaults; running Maven in a container on the `healdan-test` network; that the server's data is on a tmpfs, so stopping it clears whatever a killed run left; that upstream's own tests still run on HSQLDB.
  - **Building from Source:** Docker, for the test database, joins What You Need; the test commands are preceded by `docker/tests/test-db.sh start`.
  - **Continuous Integration:** each `Tests (n of N)` job has a `postgres:18` service container, which its tests share.
  - **How Healdan Extends LogicalDOC:** the `postgresql/` migrations, beside `mariadb/` until the switch; `hibernate.properties`; `HealdanTypedReads` and its two lines in CE's base DAO; `Savepoints` and the rule of spec §7.3.
  - **`CLAUDE.md`:** the same facts where it has them: line 24's "HSQLDB for tests" becomes PostgreSQL for Healdan's tests; the `mvn … test` commands gain the test database's line; the migrations' folders at 233; the "Tests" section's base-class paragraphs; and `HibernatePersistentObjectDAO` in the upstream-edits paragraph, citing the spec's §11.1.

- [ ] **Step 2: Build the site and run its checks**

Run: `hugo --source site --panicOnWarning`, `python3 .github/scripts/check-site-sources.py`, `python3 .github/scripts/check-site-links.py` and `python3 -m unittest discover -s .github/scripts/tests`
Expected: no warning, and every check passes.

- [ ] **Step 3: Run what CI runs, then open the pull request**

Run: the six quick checks `build` runs (`.github/scripts/check-fontawesome-icons.py`, `check-logicaldoc-doc-links.py`, `check-logicaldoc-hosts.py`, `check-maven-repositories.py`, `check-database-drivers.py` and `check-web-licences.py`), the three suites on PostgreSQL, and `bash docker/smoke-test.sh` (still MariaDB).
Expected: PASS. Then commit, push, and open **PR A** to `master`: its body says that a running server behaves as before, and lists the upstream edit.

```bash
git add site CLAUDE.md docs
git commit -m "Docs: Healdan's tests on PostgreSQL"
```

## PR B · The Switch

PR B is stacked on PR A and isn't merged until Task 23's rehearsal has passed. Until its last task, expect the smoke test to fail between tasks: the switch can't be half done (spec §13.1).

### Task 10: Text Healdan can keep

Spec §7.5.

**Files:**
- Create: `HC/util/StorableText.java`; delete `HC/util/BasicPlane.java`
- Modify, the refusals: `HC/token/ApiTokens.java:520`, `HC/web/HealdanSettingsServiceImpl.java:446`, `HC/matter/ClientService.java:107`, `HC/matter/MatterService.java` (75, 79, 338, 365), `HC/signin/SignInProviders.java:710`, `HC/searchfolder/SearchFolderService.java` (81, 376), `API/CheckoutResource.java:282`, `API/Uploads.java:183`, `ST/web/BackendDrafts.java` (164, 195); and the two silent ones, `HC/signin/SignInAccounts.java` (733, 799)
- Modify, the replacements: `HC/signin/SignInAccounts.java:785`, `HC/email/FileNames.java` (85, 90), `HC/email/EmailFields.java:243`; and `HC/email/MessageIds.java:44`, which keeps its rule
- Create: `API/TextFilter.java`; modify `API/ApiServer.java` (`providers()` 85-97), `API/ApiJson.java`
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (102, 296, 1038, 2035-2036)
- Modify: `healdan-web/web/packages/ui/src/strings/en-US.ts:241`
- Modify: `clients/helper/Healdan.Helper.Core/Mail/MessageIds.cs:13-14` (the comment only)
- Test: `HCT/util/StorableTextTest.java` (new), `APIT/TextFilterTest.java` (new), and the tests of the next step

**Interfaces:**
- Produces:

```java
package com.logicaldoc.healdan.util;

/** Text PostgreSQL can store: anything but NUL, and a lone surrogate isn't a character at all */
public final class StorableText {
	/** What a lone surrogate becomes: U+FFFD */
	public static final char REPLACEMENT = '�';
	/** The words every refusal ends with */
	public static final String CANT_KEEP = "characters Healdan can't keep";
	/** @return true unless the text holds a NUL or a surrogate without its pair; true for null */
	public static boolean holds(String text);
	/** @return the text with a space for each NUL and U+FFFD for each lone surrogate; everything else as it is */
	public static String clean(String text);
}
```

  `TextFilter` is a `ContainerRequestFilter` that answers `ApiProblem.badRequest("A parameter of the request has a character Healdan can't keep.")` (`400 bad-request`) when a decoded query parameter's value doesn't `hold`.

- [ ] **Step 1: Write the failing tests**
  - `StorableTextTest` (plain JUnit): `holds` is true for `"Fee note 🎉"` (an emoji, a surrogate pair), for accented text and for null; false for `"a\u0000b"`, `"a\uD83Cb"` (a high surrogate alone) and `"a\uDF89b"` (a low one alone). `clean("a\u0000b")` is `"a b"`; `clean("a\uD83Cb")` is `"a�b"`; `clean` leaves the emoji as it is.
  - `TextFilterTest extends ApiTestCase`: `GET /v1/search?q=sm%00oke`, `GET /v1/folders/{id}/children?author=a%00b` and `GET /v1/users?q=%00` are `400 bad-request`; the same requests with an emoji are `200`. A JSON body whose string holds `\u0000`, on `PATCH /v1/documents/{n}` (a comment) and `POST /v1/matters`, is `400`.
  - Each test the survey found asserting a refusal now asserts that an emoji is **kept** and a NUL **refused** with the new words: `ApiTokensTest#testNames`, `AuthResourceTest#testDeviceName`, `HealdanSettingsServiceImplTest#testSaveRefusesBadValues`, `ClientServiceTest#testNameMustFitTheDatabase`, `MatterServiceTest#testRefusesNamesTheDatabaseCannotStore` and `#testRefusesDescriptionsTheDatabaseCannotStore`, `SignInProvidersTest#testRefusals`, `#testGroupRefusals` and `#testOptionRefusals`, `SignInAccountsTest#testUsernameTakenRefused`, `SearchFolderServiceTest#testUpdateChangesOnlyNameAndRule`, `FolderRenameTest#testNamesThatArentAllowed`.
  - `DocumentChangesTest#testRenameChecksTheName` and `FavoriteNamesTest#testTooLongAndInvalid` hold literal emoji, which the build, reading sources as ISO-8859-1, turns into control characters: so they never tested an emoji. Write the emoji as `📄`, and assert it's kept.
  - Each test asserting a replacement asserts the emoji is kept: `FileNamesTest` (41, 50, 61, 99), `EmailDocumentListenerTest#testEmojiInHeadersBecomesReplacementCharacter` and `#testMessyEmailsNeverBlockUpload`, `MessagePartsTest#testNamesAreSafe`.
  - **Unchanged, and run to prove it:** `MessageIdsTest#testACharacterBeyondTheBasicPlaneBecomesTheReplacementCharacter` and the Helper's `MessageIdsTests`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='StorableTextTest,ClientServiceTest,MatterServiceTest,FileNamesTest'`
Expected: FAIL: `StorableText` doesn't exist; then emoji are still refused or replaced.

- [ ] **Step 3: Write `StorableText`, and change every caller**
  - In each refusal's message, `emoji or other characters the database can't store` becomes `StorableText.CANT_KEEP`: "A device name can't contain characters Healdan can't keep.", and so on. Nothing else in the sentences changes.
  - `MessageIds.normalize` keeps today's result to the character: the replacement of every character beyond the Basic Multilingual Plane moves into `MessageIds` as a private method, with a comment that the Helper's `MessageIds.cs` does the same and `hd_email` is indexed by the result.
  - `TextFilter` goes into `ApiServer.providers()` before `TokenFilter`. `ApiJson`'s mapper gets a `String` deserialiser that throws a `JsonMappingException` for text that doesn't `hold`, which `ProblemMapper` already answers as `400`.
  - `en-US.ts:241` reads `"The name has a character Healdan can't keep."`. `openapi.yaml`'s four descriptions say what's refused without naming emoji or the database.
  - **Java sources are read as ISO-8859-1** (`logicaldoc-parent/pom.xml:82`): write every non-ASCII character in Java as a `\u` escape, and before committing run `grep -rnP '[^\x00-\x7F]' --include='*.java' healdan-*/src` on the files touched.

- [ ] **Step 4: Run the suites and the clients' tests that touch it**

Run: the three Maven suites; `cd healdan-web/web && npm run generate && npm run typecheck && npm test`; `dotnet test clients/helper/Healdan.Helper.sln --filter MessageIds`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A healdan-core healdan-api healdan-storage healdan-web clients/helper
git commit -m "Text: keep emoji, refuse only what PostgreSQL can't store"
```

### Task 11: The widening, and PostgreSQL only

Spec §6.4, §6.5.

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/postgresql/V13__widen_logicaldoc_text.sql` (the next free version: the Admin app's `V12__admin.sql` took 12; if another change has taken 13, the next after it)
- Delete: `healdan-core/src/main/resources/healdan/db/migration/mariadb/` (twelve scripts)
- Modify: `healdan-core/pom.xml` (remove `flyway-mysql`, 27-31), `HC/db/HealdanMigrator.java` (`RECOVERY` 51-53, `vendorFolder`)
- Test: `HCT/db/PostgresMigrationsTest.java`, `HCT/db/HealdanMigratorTest.java`, `HCT/db/WidenedColumnsTest.java` (new)

**Interfaces:**
- Produces: `HealdanMigrator.MOVE_PAGE = "https://docs.healdan.com/install/moving-from-mariadb/"`; schema version 13 on every PostgreSQL database.

- [ ] **Step 1: Write the failing tests**
  - `PostgresMigrationsTest#testLogicaldocsNarrowColumnsAreText`: after `migrate()`, `data_type` is `text` for `ld_user.ld_avatar`, `ld_systemmessage.ld_messagetext`, `ld_filler.ld_automation`, `ld_filler.ld_automationbefore`, `ld_folder.ld_tile`, `ld_menu.ld_automation`, `ld_generic.ld_text1` and `ld_legal.ld_content`, and for no other `ld_` column.
  - `WidenedColumnsTest extends AbstractHealdanTestCase`, `testALongAvatarIsKept`: a user stored through `UserDAO` with a 6,000-character avatar reads back whole.
  - `HealdanMigratorTest`: `testRefusesMariadbAndNamesTheMove` (fake metadata for MariaDB, and for MariaDB through MySQL's driver: the message holds `MOVE_PAGE`); `testRefusesUnsupportedDatabase` gains nothing new; `testFailingScriptFailsStartup` expects the new recovery text; the version expected wherever a test says 12 becomes 13.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PostgresMigrationsTest,HealdanMigratorTest,WidenedColumnsTest'`
Expected: FAIL: the columns are `character varying`, the avatar is refused with `value too long for type character varying(4000)`, and MariaDB is still accepted.

- [ ] **Step 3: Write the script, and narrow the migrator**

```sql
-- LogicalDOC's generic schema script gives these eight columns a varchar that PostgreSQL enforces, where its MariaDB
-- script has mediumtext. They are widened to text, and nothing else of LogicalDOC's tables is ever changed
-- (docs/superpowers/specs/2026-11-18-postgresql-design.md, 6.4).
alter table ld_user alter column ld_avatar type text;
alter table ld_systemmessage alter column ld_messagetext type text;
alter table ld_filler alter column ld_automation type text;
alter table ld_filler alter column ld_automationbefore type text;
alter table ld_folder alter column ld_tile type text;
alter table ld_menu alter column ld_automation type text;
alter table ld_generic alter column ld_text1 type text;
alter table ld_legal alter column ld_content type text;
```

  - `vendorFolder` returns `postgresql` or refuses. For MariaDB, by its product name or its version string, the refusal is: `Healdan runs on PostgreSQL, and this database is MariaDB. Move it across first: <MOVE_PAGE>`. For anything else: `Healdan's tables support PostgreSQL only; found …`.
  - `RECOVERY` becomes PostgreSQL's (recon §3.3): `. PostgreSQL rolled the failed script back, so fix the cause and restart. If the script ran outside a transaction, also drop what it created and delete its failed row (delete from hd_schema_history where success = false)`.

- [ ] **Step 4: Run the tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A healdan-core
git commit -m "PostgreSQL only: widen LogicalDOC's eight text columns, and refuse MariaDB"
```

### Task 12: `on conflict` where a duplicate is expected

Spec §7.3, §10.1 (nothing Healdan does in the ordinary way logs an error in PostgreSQL).

**Files:**
- Modify: `HC/preview/PreviewJobs.java` (`add`), `HC/email/EmailIndex.java` (`put`), `ST/StoredFiles.java:140` (`recordWrite`), `ST/HeldCopies.java:70` (`hold`), `HC/util/Savepoints.java` (`insertIfAbsent` goes)
- Test: `HCT/preview/PreviewJobsTest.java`, `HCT/email/EmailIndexTest.java`, `STT/StorageDataTest.java`, `HCT/util/SavepointsTest.java`

- [ ] **Step 1: Pin the behaviour with two tests**
  - `PreviewJobsTest#testAnAddThatMeetsAnotherSessionsInsert`, the race the recon reproduced (§4.2): transaction A runs `requeue`'s update, which finds no row; a second connection inserts the same job and commits; A calls `add`, runs another statement and commits. A's other statement is kept, and the job is there once.
  - `EmailIndexTest#testPutMeetsAnotherSessionsRow`: the same shape for `put`; the row ends with A's Message-ID.

- [ ] **Step 2: Run them**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='PreviewJobsTest,EmailIndexTest'`
Expected: PASS already, through Task 6's savepoints. They pin what mustn't change while the statements do. What this task changes is PostgreSQL's log, where each duplicate caught in a savepoint is an `ERROR` line, and which Task 19's check reads.

- [ ] **Step 3: Write the statements**
  - `add`: `insert into hd_preview_job (…) values (?, ?, 0, ?, ?) on conflict (hd_docid, hd_fileversion) do nothing`.
  - `put`: `insert into hd_email (hd_docid, hd_messageid, hd_messageidhash) values (?, ?, ?) on conflict (hd_docid) do update set hd_messageid = excluded.hd_messageid, hd_messageidhash = excluded.hd_messageidhash`.
  - `recordWrite` and `hold`: their inserts end `on conflict do nothing`, and where the code read `insertIfAbsent`'s boolean it reads whether the update count is 1.
  - `Savepoints.insertIfAbsent` and its test go; `run` and `inTransaction` stay, for Task 6's listeners and `HeldCopies.hold`'s outer `run`.

- [ ] **Step 4: Run the three suites**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-core healdan-storage
git commit -m "PostgreSQL: on conflict where a duplicate is expected"
```

### Task 13: The start-up tool on PostgreSQL

Spec §8.2, §6.1, §6.2, §7.4 (the lock timeout).

**Files:**
- Modify: `DT/ConfigPlan.java` (`seedKeys` 58-77, `HEALDAN_KEYS` 51, `jdbcUrl` 110-115), `DT/DockerInit.java` (`DRIVER` 49, `DBMS` 51, `NO_SUCH_TABLE` 57, `run` 80-117, `Db` 438-510, `plannedScripts` 349-382)
- Create: `DT/DbChecks.java`
- Modify: `ST/FileLocks.java` (the comment at 28-38)
- Test: `DTT/DbChecksTest.java` (new, with a `run_test DbChecksTest` line in `docker/tools/build.sh` after `ConfigFileTest`'s); `DTT/ConfigPlanTest.java` (50-75), `DTT/ConfigFileTest.java` (49), `DTT/DbInitPlanTest.java` (281-300); `HCT/db/LockTimeoutTest.java` (new)

**Interfaces:**
- Produces:

```java
// ConfigPlan
/** Set on every start, whatever the file holds, and read back: the store bean, and the database the app runs on */
public static final Map<String, String> HEALDAN_KEYS = Map.of(
        "store", "com.logicaldoc.healdan.storage.HealdanStorage",
        "jdbc.driver", "org.postgresql.Driver",
        "jdbc.dbms", "postgresql",
        "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
// jdbcUrl: "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT (default "5432") + "/" + DB_NAME

// DbChecks: pure, no connection
final class DbChecks {
	static final int OLDEST = 18;            // refused below this major
	static final int TESTED = 18;            // a newer major is logged as untested
	static final String LOCK_TIMEOUT = "50s";
	/** @return the refusal for a server too old, or null. versionNum is PostgreSQL's server_version_num, e.g. 180004 */
	static String tooOld(int versionNum);
	/** @return the note for a major newer than TESTED, or null */
	static String untested(int versionNum);
	/** @return the refusal for a database that isn't UTF8, or null */
	static String wrongEncoding(String encoding);
	/** @return the warning for a database that doesn't sort by code point, or null. provider is pg_database.datlocprovider
	 *  ('b' built-in, 'c' libc, 'i' ICU), collate its datcollate, locale its datlocale (null for libc) */
	static String otherLocale(String provider, String collate, String locale);
	/** @return the refusal for a role that can't create tables in the schema, or null */
	static String cannotCreate(boolean hasCreate, String role, String schema);
}

// DockerInit
static final String DRIVER = "org.postgresql.Driver";
static final String DBMS = "postgresql";
private static final String NO_SUCH_TABLE = "42P01";
/** @param dbms the variant to prefer: DBMS for the app's database, "mariadb" for a move's source */
static List<String> plannedScripts(ClassLoader cl, boolean fresh, String dbms);
// Db loses `private` (the move uses it, Task 17) and gains:
//   int versionNum(); String encoding(); String[] locale(); boolean canCreate(); void setLockTimeout(); String lockTimeout();
```

- [ ] **Step 1: Write the failing tests**
  - `DbChecksTest` (home-made, as `ConfigPlanTest` is: `main`, `Check.eq`, `Check.isTrue`, `Check.done("DbChecksTest")`):
    - `tooOld(170006)` contains `"PostgreSQL 18 or later"` and `"17"`; `tooOld(180004)` and `tooOld(190001)` are null.
    - `untested(190001)` contains `"19"` and `"hasn't been tested"`; `untested(180004)` is null.
    - `wrongEncoding("LATIN1")` contains `"UTF8"` and `"LATIN1"`; `wrongEncoding("UTF8")` is null.
    - `otherLocale("b", "C", "C.UTF-8")` is null; `otherLocale("c", "C", null)` is null (plain `C` still sorts by code point, and the spec's §5.5 rules it out only as a choice for the stack); `otherLocale("c", "en_US.utf8", null)` and `otherLocale("i", "C", "en-GB")` contain `"sort"` and `"folder"`.
    - `cannotCreate(false, "healdan", "public")` contains both names and `"create"`; `cannotCreate(true, …)` is null.
  - `ConfigPlanTest`: `seedKeys` no longer holds `jdbc.driver`, `jdbc.dbms` or `hibernate.dialect`; `HEALDAN_KEYS` holds the four values above; `envKeys` gives `jdbc:postgresql://db:5432/logicaldoc` by default and `jdbc:postgresql://pg.example:6543/healdan` from `DB_HOST`, `DB_PORT`, `DB_NAME`.
  - `ConfigFileTest`: a file that holds MariaDB's three values comes out of `applyConfig` with PostgreSQL's, and they read back through `ContextProperties`.
  - `DbInitPlanTest`: `prefersDbmsVariant` and `fallsBackToGenericScript` pass `"postgresql"` and expect the generic script for core, and still expect `.mariadb` when asked for `"mariadb"`.
  - `LockTimeoutTest extends AbstractHealdanTestCase`, `testAWaitEndsAtTheTimeout` (Review Focus 5): connection A holds `select … from hd_setting where name = ? for update` in an open transaction; connection B runs `set lock_timeout = '1s'` and then updates that row. B fails within five seconds with SQLSTATE `55P03`; once A commits, B's update, tried again in a new transaction, goes through.

- [ ] **Step 2: Run them to see them fail**

Run: `docker build --target tools .`
Expected: FAIL in `DbChecksTest` (the class doesn't exist), then in the three changed tests.

- [ ] **Step 3: Write `DbChecks`, and change `ConfigPlan` and `DockerInit`**
  - The three database keys leave `seedKeys` and join `HEALDAN_KEYS`. `applyHealdanKeys` and `verifyHealdanKeys` already set and read back whatever that map holds.
  - `Db.columns()` reads `where table_schema = current_schema()`.
  - `Db`'s new reads: `show server_version_num`; `select pg_encoding_to_char(encoding), datlocprovider, datcollate, datlocale from pg_database where datname = current_database()`; `select has_schema_privilege(current_user, current_schema(), 'CREATE')`.
  - `Db.setLockTimeout()` runs `alter role current_user in database <current_database()> set lock_timeout = '50s'`, the database's name quoted as an identifier. `Db.lockTimeout()` opens a new connection and returns `show lock_timeout`.
  - In `run`, after `db.await` and before anything is created: throw `InitException` for `tooOld`, `wrongEncoding` and `cannotCreate`; `log` `untested` and `otherLocale`; call `setLockTimeout`, then throw `InitException` unless `lockTimeout()` is `50s`.
  - `plannedScripts` takes the variant; its two callers pass `DBMS`.
  - `FileLocks`' comment says the cycle it describes ends at the database's lock timeout, 50 seconds, which the start-up tool sets, and that on PostgreSQL the timeout fails the waiting statement's whole transaction; it no longer names InnoDB.

- [ ] **Step 4: Run the tools' tests**

Run: `docker build --target tools .`
Expected: PASS, every suite printing its `done` line.

- [ ] **Step 5: Check it against a real PostgreSQL, by hand**

`docker/tests/test-init.sh` is rewritten in Task 19. Until then, with a `postgres:18` started as Task 15's `db` service is, run the `tools` image's `DockerInit` against it and check: the schema is created; `psql -c 'show lock_timeout'` as the app's role prints `50s`; a database made with `encoding 'LATIN1' template template0 locale 'C'` is refused by name; one made with `locale 'en_US.utf8'` starts with the warning; and a role without `CREATE` on `public` is refused before any table exists (Review Focus 4).

- [ ] **Step 6: Commit**

```bash
git add docker/tools
git commit -m "Start-up tool: PostgreSQL's driver, keys and checks"
```

### Task 14: The guard against starting an install empty

Spec §8.3. Review Focus 3.

**Files:**
- Create: `DT/StartGuard.java`
- Modify: `DT/DockerInit.java` (`run` 80-117; `Db`)
- Test: `DTT/StartGuardTest.java` (new, with its `run_test` line in `build.sh`)

**Interfaces:**
- Consumes: `Db` and `InitException` (Task 13).
- Produces:

```java
final class StartGuard {
	static final String MOVE_PAGE = "https://docs.healdan.com/install/moving-from-mariadb/";
	static final String RESTORE_PAGE = "https://docs.healdan.com/install/backup-and-restore/#restoring-onto-a-fresh-stack";
	/** The table a move marks its target with, docker_move (started timestamp not null, finished timestamp): one row,
	 *  whose finished is null until every check has passed */
	static final String MOVE_MARKER = "docker_move";

	enum Verdict { START, MOVE_FIRST, MOVE_AGAIN, RESTORE_FIRST }

	/** @param url and dbms as the conf volume's context.properties holds them before this start changes it; either may be null */
	static boolean namesMariaDb(String url, String dbms);

	/**
	 * @param confNamesMariaDb the conf volume is a MariaDB install's
	 * @param reachable PostgreSQL answered
	 * @param move null when the database has no MOVE_MARKER table, false when it holds an unfinished move, true a finished one
	 * @param empty the database has neither ld_user nor the start-up tool's marker
	 * @param repositoryHoldsFiles Original's folder has any entry
	 */
	static Verdict decide(boolean confNamesMariaDb, boolean reachable, Boolean move, boolean empty, boolean repositoryHoldsFiles);

	/** The message for a verdict other than START: what's wrong, what to do, and the page */
	static String message(Verdict verdict);

	/** @return whether the folder exists and has any entry: a storage marker or a document's folder */
	static boolean holdsFiles(File originalFolder);
}
// Db gains: Boolean move()   (reads MOVE_MARKER's finished column; null when the table is missing)
```

- [ ] **Step 1: Write the failing test**

`StartGuardTest`:
  - `namesMariaDb("jdbc:mariadb://db:3306/logicaldoc", "mariadb")`, `namesMariaDb("jdbc:mysql://db/x", null)` and `namesMariaDb(null, "MariaDB")` are true; `namesMariaDb("jdbc:hsqldb:mem:logicaldoc", "hsqldb")`, `namesMariaDb("jdbc:postgresql://db:5432/logicaldoc", "postgresql")` and `namesMariaDb(null, null)` are false.
  - `decide`, one line each:

    | conf names MariaDB | reachable | move | empty | repository holds files | verdict |
    |---|---|---|---|---|---|
    | true | false | null | true | true | `MOVE_FIRST` |
    | true | true | null | true | true | `MOVE_FIRST` |
    | true | true | false | false | true | `MOVE_AGAIN` |
    | false | true | false | false | false | `MOVE_AGAIN` |
    | true | true | true | false | true | `START` |
    | false | true | null | true | true | `RESTORE_FIRST` |
    | false | true | null | true | false | `START` (a fresh install) |
    | false | true | null | false | true | `START` (an ordinary start, and a restore done in the documented order) |
    | false | true | true | false | true | `START` (every start after a move) |

  - `message(MOVE_FIRST)` contains `"MariaDB"` and `MOVE_PAGE`; `message(MOVE_AGAIN)` contains `"move-from-mariadb"` and `MOVE_PAGE`; `message(RESTORE_FIRST)` contains `"restore"` and `RESTORE_PAGE`. None contains a password or a JDBC URL.
  - `holdsFiles` is false for a missing folder and an empty one, true for one holding only `.healdan-backend`.

- [ ] **Step 2: Run it to see it fail**

Run: `docker build --target tools .`
Expected: FAIL, `StartGuard` doesn't exist.

- [ ] **Step 3: Write `StartGuard`, and put it first in `run`**

`run`'s order becomes: the trusted proxies; `envKeys` and the wait, as now; then, **before `applyConfig`**:
  1. read `jdbc.url` and `jdbc.dbms` from `CONFIG` as written (the entrypoint has copied the image's defaults there, which name HSQLDB);
  2. connect. When the conf names MariaDB, a database that doesn't answer within 15 seconds is `reachable = false`, not a wait of `DB_WAIT_SECONDS` and "cannot reach the database": the install's `DB_HOST` may still be its MariaDB server;
  3. `decide`, and for any verdict but `START` throw `InitException(message(verdict))`. Nothing has been written.

  Then `applyConfig` and the rest as today. `holdsFiles` is given `REPO/docs`, which the entrypoint has just made.

- [ ] **Step 4: Run the tools' tests**

Run: `docker build --target tools .`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docker/tools
git commit -m "Start-up tool: never start an install on an empty database"
```

### Task 15: The stack's database service

Spec §8.1, §6.3.

**Files:**
- Modify: `compose.yaml` (the `db` service 7-25, `DB_PORT` 40, the volumes 59), `compose.dev.yaml` (1, 15-17), `.env.example` (5, 14, 38, 41)
- Create: `docker/postgres/10-healdan-role.sh`

**Interfaces:**
- Produces, for Tasks 17 to 19: the service `db` (`postgres:18`) with the environment `HEALDAN_DB_NAME`, `HEALDAN_DB_USER` and `HEALDAN_DB_PASSWORD` inside it; the volume `pg-data`; the superuser `postgres`, reachable only through the container's socket (`docker compose exec db psql -U postgres`).

- [ ] **Step 1: Write the role's script**

`docker/postgres/10-healdan-role.sh` runs once, when the image initialises an empty data folder. With `psql -v ON_ERROR_STOP=1` and its variables passed by `-v`, never pasted into SQL:

```sql
create role :"u" login password :'p';
create database :"d" owner :"u" template template0 encoding 'UTF8' locale_provider builtin builtin_locale 'C.UTF-8' locale 'C';
alter role :"admin" password null;
```

It stops with a message, before the first statement, when `HEALDAN_DB_USER` is the superuser's name.

- [ ] **Step 2: Change `compose.yaml`**

```yaml
  db:
    image: postgres:18
    restart: unless-stopped
    environment:
      # The image's superuser. The role script leaves it without a password, so it's reachable only from inside
      # this container (docker compose exec db psql -U postgres). The app connects as HEALDAN_DB_USER.
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
      POSTGRES_DB: postgres
      # The built-in provider's C.UTF-8: code-point order, Unicode lower(), LIKE prefixes on their index, and no
      # change with the image's operating system. --locale=C goes with it, or datcollate reads en_US.utf8.
      POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C"
      HEALDAN_DB_NAME: ${DB_NAME:-logicaldoc}
      HEALDAN_DB_USER: ${DB_USER:-logicaldoc}
      HEALDAN_DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
      TZ: ${TZ:-UTC}
    healthcheck:
      # Over TCP: the image's first start runs a temporary server on its socket only
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$$HEALDAN_DB_USER\" -d \"$$HEALDAN_DB_NAME\""]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    volumes:
      - pg-data:/var/lib/postgresql
      - ./docker/postgres/10-healdan-role.sh:/docker-entrypoint-initdb.d/10-healdan-role.sh:ro
```

`DB_PORT` defaults to `5432`. In `volumes:`, `pg-data` replaces `db-data`, which `compose.yaml` no longer names. `compose.dev.yaml` publishes `"127.0.0.1:${DEV_DB_PORT:-5432}:5432"`. `.env.example` says PostgreSQL and `5432`.

- [ ] **Step 3: Check it on a fresh stack**

Run, with a scratch project and password: `docker compose -p pgstack up -d db`, then:

```bash
docker compose -p pgstack exec db psql -U postgres -Atc "select datlocprovider, datlocale, datcollate, pg_encoding_to_char(encoding) from pg_database where datname = 'logicaldoc'"
docker compose -p pgstack exec db psql -U postgres -Atc "select rolsuper from pg_roles where rolname = 'logicaldoc'"
docker compose -p pgstack exec -e PGPASSWORD="$DB_PASSWORD" db psql -h 127.0.0.1 -U postgres -c 'select 1'
```

Expected: `b|C.UTF-8|C|UTF8`; `f`; and the third refused with `password authentication failed`. Then `docker compose -p pgstack down -v`.

- [ ] **Step 4: Commit**

```bash
git add compose.yaml compose.dev.yaml .env.example docker/postgres
git commit -m "Stack: PostgreSQL 18 as the db service, with an ordinary role for the app"
```

### Task 16: The move's logic

Spec §9.3 (steps 4, 5 and 7). Review Focus 2. Pure Java with no connection, so `docker/tools`' tests can run it.

**Files:**
- Create: `DT/MovePlan.java`, `DT/MoveValues.java`, `DT/MoveChecksum.java`, `DT/MoveCounters.java`
- Test: `DTT/MovePlanTest.java`, `DTT/MoveValuesTest.java`, `DTT/MoveChecksumTest.java`, `DTT/MoveCountersTest.java` (each with its `run_test` line)

**Interfaces:**
- Produces:

```java
final class MovePlan {
	/** Never copied: the target's own Flyway history, and the two markers */
	static final Set<String> OWN = Set.of("hd_schema_history", "docker_init", "docker_move");

	record TablePlan(String table, List<String> columns, List<String> sourceOnlyColumns) {}
	record Plan(List<TablePlan> copied, List<String> sourceOnlyTables, List<String> problems) {}

	/**
	 * Matches tables and columns by name, whatever their case. A table or column only the target has is a problem: the
	 * source isn't at the version the move asks for. One only the source has is listed, and not copied.
	 * @param source and target: table name to its column names, in the tables' own order
	 */
	static Plan plan(Map<String, List<String>> source, Map<String, List<String>> target);
}

final class MoveValues {
	/** Where a value is: for messages and reports, which never hold the value itself */
	record Where(String table, String key, String column) {}

	/** A value PostgreSQL can't hold as it is */
	static final class Unstorable extends Exception {
		Unstorable(Where where, String why);
		Where where();
	}

	/** What to do with a NUL in text: REFUSE it, or REPLACE it with a space and record where */
	enum Nul { REFUSE, REPLACE }

	/** The target column: PostgreSQL's data_type from information_schema.columns, and character_maximum_length or 0 */
	record Target(String type, int maxLength) {}

	/**
	 * @param value as read from MariaDB: a String, a Number or null. Dates and times are read as text
	 * @param replaced gains the place of every NUL replaced
	 * @return what to bind for the target column
	 */
	static Object carry(Object value, Target target, Where where, Nul nul, List<Where> replaced) throws Unstorable;

	/** MariaDB's text form of a datetime or timestamp, with or without fractions, as a wall-clock time */
	static LocalDateTime dateTime(String text, Where where) throws Unstorable;
}

final class MoveChecksum {
	/** The canonical form of one carried value: its kind and its value, the same from either database */
	static String canonical(Object value);
	/** SHA-256 over a row's canonical values, in the plan's column order */
	static byte[] row(List<Object> values);
	/** A table's checksum: its row digests added as 256-bit numbers, so the order rows come in doesn't matter */
	static final class Sum {
		void add(byte[] rowDigest);
		long rows();
		String hex();
	}
}

final class MoveCounters {
	/** The lowest id CE's generator can make next from a sequence: next * increment - (increment - 1) */
	static long lowestNextId(long next, int increment);
	/** @return whether no id the sequence can still make is at or below the table's highest */
	static boolean safe(long next, int increment, long highestId);
}
```

- [ ] **Step 1: Write the failing tests**
  - `MovePlanTest`:
    - `{ld_user: [ld_id, ld_name], LD_UNIQUETAG: [ld_tag, ld_int1]}` against `{ld_user: [ld_id, ld_name], ld_uniquetag: [ld_tag]}` copies both tables, lists `ld_uniquetag.ld_int1` as source-only, and has no problem.
    - A target column the source lacks, `ld_tenant.ld_pagesquotathr`, is a problem that names the table and the column.
    - A target table the source lacks is a problem; a source table the target lacks is listed, not a problem.
    - `hd_schema_history`, `docker_init` and `docker_move` are in neither list.
  - `MoveValuesTest`:
    - `carry("a\u0000b", text, where, REFUSE, list)` throws `Unstorable` whose message has the table, key and column and not `a` or `b`; with `REPLACE` it returns `"a b"` and `list` holds `where`.
    - `carry` of a 4,001-character string into `Target("character varying", 4000)` throws; into `Target("text", 0)` returns it.
    - `carry(3_000_000_000L, Target("integer", 0), …)` throws; `carry(42L, Target("integer", 0), …)` returns a number equal to 42.
    - `dateTime("2026-11-18 10:21:44")` is `2026-11-18T10:21:44`; `dateTime("2026-11-18 10:21:44.123")` keeps `.123`; `dateTime("0000-00-00 00:00:00")` throws `Unstorable` (MariaDB's zero date).
    - `carry(null, anyTarget, …)` is null.
  - `MoveChecksumTest`:
    - `canonical(1L)` equals `canonical(1)` and differs from `canonical("1")` and from `canonical(null)`; `canonical(null)` differs from `canonical("")`.
    - `canonical` of `LocalDateTime` `10:21:44` equals that of `10:21:44.000`; `canonical(new BigDecimal("1.50"))` equals `canonical(new BigDecimal("1.5"))`.
    - `row(["a", "bc"])` differs from `row(["ab", "c"])`.
    - A `Sum` of rows A, B, C has the same `hex()` as one of C, A, B, and another than one of A, B; `rows()` is 3.
  - `MoveCountersTest`, with figures read from the owner's data on 2026-11-18:
    - `lowestNextId(150100, 50)` is `7_504_951`; `safe(150100, 50, 5_007_455)` is true.
    - `lowestNextId(100, 50)` is `4951`: the first id a fresh install makes.
    - `safe(100150, 50, 5_007_455)` is false: that value's ids, 5,007,451 to 5,007,500, include the highest.

- [ ] **Step 2: Run them to see them fail**

Run: `docker build --target tools .`
Expected: FAIL to compile, the four classes don't exist.

- [ ] **Step 3: Write the four classes**
  - `carry` decides by the target's `data_type`: `text`, `character varying` and `character` take text; `integer`, `smallint` and `bigint` take whole numbers within their range; `double precision`, `real` and `numeric` take numbers; `timestamp without time zone` takes `dateTime`'s result. Any other target type is `Unstorable` with the type's name: the scripts make none today, and a new one should stop a move, not be guessed at.
  - `canonical` prefixes each form with one letter for its kind, so that no two kinds can meet: whole numbers as decimal, `BigDecimal` with trailing zeros stripped, floating point through `Double.toString` of the `double`, times in ISO form without trailing zero fractions, text as it is.

- [ ] **Step 4: Run the tools' tests**

Run: `docker build --target tools .`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add docker/tools
git commit -m "Move: matching tables, carrying values, checksums and counters"
```

### Task 17: The move command

Spec §9. Review Focus 1.

**Files:**
- Create: `DT/MoveFromMariaDb.java`, `compose.move.yaml`
- Modify: `docker/entrypoint.sh` (a dispatch after line 20, before the chown loop at 27), `DT/DockerInit.java` (`createSchema` 401-421 shares its script run with the move)

**Interfaces:**
- Consumes: Task 13's `plannedScripts(cl, fresh, dbms)` and `Db`; Task 14's `StartGuard.MOVE_MARKER`; Task 16's four classes; Task 11's `HealdanMigrator`, whose public `migrate()` applies PostgreSQL's scripts, the widening included.
- Produces:

```java
final class MoveFromMariaDb {
	/** The last version of the mariadb/ folder: a source at any other is refused */
	static final String SOURCE_VERSION = "12";
	static final String REPLACE_NUL = "--replace-nul";
	/** Exit statuses: 0 moved, 1 refused or failed (the target is left marked unfinished, or untouched) */
	public static void main(String[] args);
}
// DockerInit: static void runScripts(List<String> scripts, String url, String user, String password)  (createSchema's DBInit run)
```

  The environment it reads: the target from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, as the start-up tool does; the source from `MOVE_SOURCE_HOST` (default `mariadb`), `MOVE_SOURCE_PORT` (`3306`), `MOVE_SOURCE_NAME`, `MOVE_SOURCE_USER` and `MOVE_SOURCE_PASSWORD` (each defaulting to its `DB_*`).

  `compose.move.yaml`:

```yaml
# Run with compose.yaml, once, to move an install from MariaDB: see the Install manual's Moving from MariaDB to PostgreSQL.
services:
  mariadb:
    image: mariadb:11.4
    environment:
      MARIADB_DATABASE: ${DB_NAME:-logicaldoc}
      MARIADB_USER: ${DB_USER:-logicaldoc}
      MARIADB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
      TZ: ${TZ:-UTC}
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    volumes:
      - db-data:/var/lib/mysql
  app:
    depends_on:
      mariadb:
        condition: service_healthy
volumes:
  db-data:
```

- [ ] **Step 1: Write the command**

`main` does spec §9.3's steps in this order, printing one line as each ends:
  1. **The source:** connect read-only. Refuse unless `hd_schema_history`'s last row by `installed_rank` is `SOURCE_VERSION` and every row succeeded, and unless the source has every table and column the `.mariadb` plan creates (`DockerInit.createdColumns` over `plannedScripts(cl, false, "mariadb")`, against `information_schema.columns where table_schema = database()`). The refusal says to upgrade to `mariadb-final` and start once.
  2. **The target:** run Task 13's version and encoding checks. Empty, with no `docker_move`: go on. A `docker_move` whose `finished` is null: drop every table and sequence in the schema, then go on. Anything else, a finished move included: refuse, changing nothing.
  3. `create table docker_move (started timestamp not null, finished timestamp)`, and insert `started`.
  4. **The schema:** `runScripts(plannedScripts(cl, true, DBMS), …)`, then `new HealdanMigrator(dataSource).migrate()` on a `DriverManagerDataSource`. No seed runs, and the app never starts.
  5. Read every foreign key's table, name and `pg_get_constraintdef`, and drop them. `truncate` every table but `MovePlan.OWN`.
  6. **The copy:** `MovePlan.plan` from both sides' `information_schema.columns`; any problem stops the move. For each table: read the source in the plan's column order, dates and times as text; `MoveValues.carry` each value; insert in batches of 1,000; add each carried row to the source's `MoveChecksum.Sum`. One transaction a table. An `Unstorable` stops the move, after the whole table has been read, so the report lists every such place in it. With `--replace-nul`, NULs are replaced and their places collected.
  7. **The counters:** for each sequence in the target's `pg_sequences` that isn't an identity column's, find the source's sequence of the same name, whatever its case, and `setval(<seq>, <next_not_cached_value>, false)`; a target sequence with no source sequence stops the move. For each identity column, `setval(pg_get_serial_sequence(…), <the column's highest value>)` when the table has rows: V11's `hd_trash.hd_id`, and `hd_admin_event.hd_id` and `hd_error.hd_id`, which the Admin app's `V12__admin.sql` adds.
  8. Add the foreign keys back from their definitions. A failure names the constraint.
  9. **The comparison:** for each copied table, read the target in the same column order into a second `Sum`; the row counts and `hex()` must match. Each sequence must be `MoveCounters.safe` against its table's highest id, the table being the sequence's name without `_seq`. Any difference stops the move and names the table.
  10. `create table docker_init …` as `Db.recordCompletion` does, set `docker_move.finished`, print `Move complete.`, exit 0.

  The report follows spec §9.1's sketch, then lists: tables and columns only the source has; with `--replace-nul`, every place a NUL was replaced. Places are table, key and column. No value, password or URL with a password is ever printed.

  Any exception: print `[move] ERROR: ` and its message, leave `docker_move` unfinished, exit 1.

- [ ] **Step 2: The entrypoint's dispatch**

After `docker/entrypoint.sh`'s `DB_PASSWORD` check and before it touches a volume:

```bash
# A move reads one database and writes another: it never touches the volumes, so it runs before anything here does.
if [ "${1:-}" = move-from-mariadb ]; then
  shift
  run_as=(env)
  [ "$(id -u)" = 0 ] && run_as=(setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups)
  exec "${run_as[@]}" java -cp "$CATALINA_HOME/webapps/ROOT/WEB-INF/lib/*:$LDOC_HOME/lib/docker-init.jar" MoveFromMariaDb "$@"
fi
```

- [ ] **Step 3: Run it by hand**

Task 18 automates this. For now: a MariaDB stack made from `master` with the smoke overlay, taken down with `docker compose down`; this branch's image built; then `docker compose -f compose.yaml -f compose.move.yaml run --rm app move-from-mariadb`.
Expected: `Move complete.` and exit 0; `docker compose up -d` then starts the app healthy on the moved database, and `docker volume ls` still shows the MariaDB volume.

- [ ] **Step 4: Commit**

```bash
git add docker/tools docker/entrypoint.sh compose.move.yaml
git commit -m "Move: the move-from-mariadb command and its overlay"
```

### Task 18: The move's fixture and test

Spec §10.2. Review Focus 1 and 2.

**Files:**
- Create: `docker/tests/move-fixture/make.sh`, `docker/tests/move-fixture/mariadb.sql.gz`, `docker/tests/move-fixture/volumes.tgz`, `docker/tests/move-fixture/expected.env`, `docker/tests/move-fixture/README.md`
- Create: `docker/tests/test-move.sh`
- Modify: `.github/workflows/docker-image.yml` (a step after the smoke test's, line 215)

**Interfaces:**
- Consumes: Task 17's command and overlay; `docker/tests/lib.sh`'s `check` helpers, as the other test scripts use them.

- [ ] **Step 1: Make the fixture, once**

`make.sh` is run by hand from a checkout of `master` as it is when this task runs, which still runs MariaDB. It:
  1. starts a stack with the smoke overlay (`docker/tests/compose.smoke.yaml`) under its own project name;
  2. through the API as `apiuser`, files three documents, saves a second version of one, deletes one (a Trash item) and files one email;
  3. adds a local storage backend and a Store on it through the Storage tab's service, and files a document there;
  4. with SQL, sets `ld_user.ld_avatar` of `apiuser` to 6,000 characters, gives one `ld_history` row a date with milliseconds, and a matter's name an accented letter;
  5. stops the app, writes `mariadb-dump --single-transaction` to `mariadb.sql.gz`, and the conf and repository volumes to `volumes.tgz`;
  6. writes `expected.env`: the highest doc #, the id and SHA-256 of one document's file, the row counts of `ld_document`, `ld_version`, `ld_history`, `hd_matter`, `hd_trash` and `hd_storage_file`, and `apiuser`'s password;
  7. takes its stack down with its volumes.

  `README.md` says which commit the fixture came from, that it's frozen, and that it's remade only if a MariaDB migration or a change to CE's tables reaches `master` before the switch merges. Commit the three data files: together they should be a few megabytes; if `volumes.tgz` is over 10 MB, leave the search index out of it and say so in the README.

- [ ] **Step 2: Write `test-move.sh`**

It takes the image from `LOGICALDOC_IMAGE` (default `logicaldoc-community:local`), runs under the project `${MOVE_PROJECT:-healdan-move}` on port `${MOVE_PORT:-18090}`, and removes its stack and volumes when it ends, as `smoke-test.sh` does. Its cases, each a `check`:
  1. **Refused, an older source:** the fixture loaded with its last `hd_schema_history` row deleted. The move exits 1, says `mariadb-final`, and the target has no table.
  2. **Stops part-way, then starts over** (Review Focus 1): the fixture loaded whole, with one `ld_version.ld_versiondate` set to `0000-00-00 00:00:00`. The move exits 1 and names `ld_version`, the row's id and `ld_versiondate` (Review Focus 2). `docker compose up -d app` then exits with the start-up tool's `MOVE_AGAIN` message and the app isn't running. With the date mended, the move run again exits 0.
  3. **A NUL:** before case 2's second run, one `ld_document.ld_comment` holds a NUL. Without `--replace-nul` the move exits 1 and lists the place; the second run is given `--replace-nul`, and its report lists that one place.
  4. **The report:** `Move complete.`; no table in the "only the source has" list but for `ld_uniquetag.ld_int1` as a column.
  5. **The data,** by `psql`: each count in `expected.env`; `length(ld_avatar)` of `apiuser` is 6,000; the `ld_history` date keeps its milliseconds; `select count(*) from docker_move where finished is not null` is 1.
  6. **The app on it:** `docker compose up -d` reaches healthy. `apiuser` signs in with `expected.env`'s password; the recorded document's file has the recorded SHA-256; a newly filed document's doc # is the recorded highest plus one, and its id is above every id in the fixture; deleting it answers with a Trash item; and the local backend's file is read back.
  7. **Untouched:** the MariaDB volume still exists, and a row count in it is as it was.

- [ ] **Step 3: Run it**

Run: `docker build -t logicaldoc-community:local . && bash docker/tests/test-move.sh`
Expected: every check `ok`, exit 0, in a few minutes.

- [ ] **Step 4: Add it to CI**

In `.github/workflows/docker-image.yml`, after the smoke test's step:

```yaml
      - name: Move test (amd64)
        if: steps.changes.outputs.code == 'true' && matrix.arch == 'amd64'
        env:
          LOGICALDOC_IMAGE: logicaldoc-community:ci
        run: bash docker/tests/test-move.sh
```

- [ ] **Step 5: Commit**

```bash
git add docker/tests/move-fixture docker/tests/test-move.sh .github/workflows/docker-image.yml
git commit -m "Move: a frozen MariaDB fixture, and its test in CI"
```

### Task 19: The smoke test and the Docker tests on PostgreSQL

Spec §10.1.

**Files:**
- Modify: `docker/smoke-test.sh` (`db_query` 323-325; 327; 376-395; 470-471; `cleanup` 124-141), `docker/tests/test-proxies.sh` (18-21), `docker/tests/test-previews.sh` (38-41, 312-320), `docker/tests/test-storage.sh` (71-74)
- Modify: `docker/tests/test-init.sh`, `docker/tests/test-image.sh` (31-32), `docker/tests/test-overlays.sh` (14, 33-34, 60)
- Create: `docker/tests/db-log-expected.txt`

**Interfaces:**
- Consumes: Task 15's `db` service; Task 10's text check; Task 13's and 14's start-up tool.
- Produces: `db_query` in every script as

```bash
db_query() {
  compose exec -T db sh -c 'PGPASSWORD="$HEALDAN_DB_PASSWORD" psql -X -q -At -F "$(printf "\t")" -h 127.0.0.1 -U "$HEALDAN_DB_USER" -d "$HEALDAN_DB_NAME" -c "$1"' sh "$1"
}
```

  (the sub-scripts use `docker exec "$DB_CONTAINER"` in place of `compose exec -T db`, as they do today).

- [ ] **Step 1: Change the helpers and the SQL that was MariaDB's**
  - `smoke-test.sh:327`: `… and success` (the column is `boolean`).
  - `test-previews.sh:312`: `cast(extract(epoch from hd_nexttry - now()) as int)` for `timestampdiff(second, now(), hd_nexttry)`.
  - `smoke-test.sh:470-471` goes through `db_query`.

- [ ] **Step 2: Change the emoji checks to expect the emoji** (spec §7.5)

At 376-395: the email's `fileName` is `Re_ 🎉 Sale.eml` and its `emailsubject` `Re: 🎉 Sale`, with the emoji the fixture's subject has; `no_4byte_char` and its check go. Add:
  - a matter named `Smoke 🎉 Matter` is created through the API and read back with its emoji;
  - a document filed as `Notes 🎉.txt` keeps its name;
  - `GET /api/v1/search?author=a%00b` answers `400`, with the code Task 10 gives.

- [ ] **Step 3: Add the user-edit check and the lock timeout's** (spec §7.2, §7.4)

In step 5: through the Classic Interface's REST service as `admin`, change `apiuser`'s first name, read the user back, and `check` the new name; then put it back. And `db_query 'show lock_timeout'` is `50s`.

- [ ] **Step 4: Check PostgreSQL's log**

`check_db_log` reads `compose logs --no-log-prefix db`, keeps the lines holding `ERROR:`, drops those matching a pattern in `docker/tests/db-log-expected.txt` (one extended regular expression a line, each with a comment above it saying which step or task of CE's raises it), and `check`s that none is left, printing those that are. It's called at the end of step 7, as step 8's `compose down` removes the container's log, and again at the end of step 9. `cleanup` prints the `db` service's last 100 lines with the others.

`db-log-expected.txt` starts with `StatsCollector`'s twelve missing tables (spec §4.1) and whatever the steps provoke on purpose. **Until the list has held for five consecutive runs on both architectures, `check_db_log` prints what it finds and passes;** the pull request's description records the runs, and the last commit of PR B makes it fail the build (spec §10.1).

- [ ] **Step 5: Run the smoke test**

Run: `bash docker/smoke-test.sh` (on a Mac, `WEB_NETWORK=bridge`)
Expected: every check passes, and step 9's browser tests.

- [ ] **Step 6: The tests run by hand**
  - `test-init.sh`: `postgres:18` started as Task 15's service is (44-48); the plan's lines are the three generic scripts (72-74); `current_schema()` (95, 122); `alter table … drop column` once per column (100-101); `alter table … rename to` (126, 131); `to_regclass('ld_webservicecall')` for `show tables like` (130). Section 3c's SQL from Upgrading has nothing to run until the page lists a change for PostgreSQL: it's skipped with a line saying so when the page has no SQL block. New sections: Task 13's refusals and warning; each of Task 14's verdicts against a real database and conf volume.
  - `test-image.sh` 31-32 and `test-overlays.sh` 14, 33-34 and 60: PostgreSQL's image, variables and port (`DEV_DB_PORT=15432`, `"127.0.0.1:15432:5432"`).

  - The demo stack, which the docs' screenshots use: `docker compose -p healdan-demo -f compose.yaml -f docker/demo/compose.demo.yaml up -d` reaches healthy on PostgreSQL with the demo firm seeded; then take it down with its volumes.

  Run all three scripts; expected: every check `ok`.

- [ ] **Step 7: Commit**

```bash
git add docker/smoke-test.sh docker/tests
git commit -m "Tests: the smoke test and the Docker tests on PostgreSQL"
```

### Task 20: The setup wizard starts on PostgreSQL

Spec §8.4.

**Files:**
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/setup/client/Setup.java:257-258` (LF line endings)

- [ ] **Step 1: Change the default**

```java
		// Healdan: PostgreSQL is the database it runs on
		dbEngine.setValue(POSTGRESQL);
```

The engine list at 216-224 stays as it is: the owner kept MySQL's entry on 2026-11-01, and MariaDB's stays the same way.

- [ ] **Step 2: Compile it**

The setup module is GWT client code, which no JUnit test reaches. Run: `docker build .`
Expected: the image builds, its GWT compile of the `setup` module included.

- [ ] **Step 3: Commit**

```bash
git add logicaldoc-gui/src/main/java/com/logicaldoc/gui/setup/client/Setup.java
git commit -m "Setup wizard: start on PostgreSQL"
```

### Task 21: The Install manual

Spec §12. Every page is written for an admin, in the site's style, and no page tells anyone to type SQL for a move.

**Files:**
- Create: `site/content/install/moving-from-mariadb.md`
- Modify: `site/content/install/what-runs.md`, `backup-and-restore.md`, `upgrading.md`, `configuration.md`, `quick-start.md`, `_index.md`, `troubleshooting.md`, `known-limitations.md`, `host-folders.md`

- [ ] **Step 1: Write Moving from MariaDB to PostgreSQL**

Its sections, in order:
  1. **Who Needs This:** an install made before the version that brought PostgreSQL. A new install doesn't.
  2. **Before You Start:** a backup, by the old page's `mariadb-dump` steps, which this page keeps; the app is down for the move; the same `TZ` before and after.
  3. **Upgrade to the Last MariaDB Version:** check out the tag `mariadb-final`, rebuild and start once. Under it, **Changes to LogicalDOC's Tables Before That Version**: the section Upgrading has today (Quota Thresholds, November 2026), moved here whole, with its MariaDB commands.
  4. **Move:** spec §9.1's five commands, each in its own block, with the warning that `docker compose down` never takes `-v` here.
  5. **What the Report Says:** each line of the report; the lists of what only the source had; what `--replace-nul` does and when to use it.
  6. **If It Stops:** each refusal and what to do: an older source, a value PostgreSQL can't hold (the table, the key, the column), a difference in the comparison. Run it again: it starts over.
  7. **Start Healdan,** and what its first start does.
  8. **Going Back:** the old version and the old volume; safe only until the new stack has moved or purged files, after which it behaves like restoring a backup; never both stacks at once.
  9. **Change Your Backups:** a job that runs `mariadb-dump` backs up nothing now; link to Backup and Restore.
  10. **Remove the Old Volume,** when you're content: `docker volume rm <project>_db-data`.
  11. **A MariaDB of Your Own:** the `MOVE_SOURCE_*` variables.

- [ ] **Step 2: Change the other pages**
  - **What Runs:** the stack's table (`db` is `postgres:18`, the volume `pg-data`); Supported Databases: PostgreSQL 18, and a PostgreSQL of your own, with the role and the `create database` of the Global Constraints.
  - **Backup and Restore:** `pg_dump -Fc` and `pg_restore --no-owner --exit-on-error`, run as the superuser through the `db` container (recon §5.3); the restore loads the database before the app's first start, and says why (the start-up tool's guard).
  - **Upgrading:** Changes to LogicalDOC's Tables keeps its introduction, with `psql` in its commands, and lists no change yet: every PostgreSQL database was made by a version that had them all. A new section, Moving to a New PostgreSQL Major: a dump, a new volume, a restore; never by changing the image's tag alone.
  - **Configuration:** the `DB_*` table (`DB_PORT` 5432); Changing the Database Password with `alter role … password` and `.env`.
  - **Quick Start** and the manual's first page: PostgreSQL where they say MariaDB. The status notice doesn't change.
  - **Troubleshooting:** the rows for the database's volume, a failed migration (Task 11's message) and a missing column, reworded for PostgreSQL; new rows for the guard's three messages (Task 14) and the start-up tool's refusals (Task 13).
  - **Known Limitations:** PostgreSQL only, with the move for MariaDB installs; the admins' tag search misses a tag with a backslash; the emoji limit goes.
  - **Host Folders:** the database volume's name and path.

- [ ] **Step 3: Build the site and run its checks**

Run: `hugo --source site --panicOnWarning`, `python3 .github/scripts/check-site-sources.py` and `python3 .github/scripts/check-site-links.py`
Expected: no warning; every check passes. `grep -rn -i "mariadb" site/content` finds only the move's page and the lines that point to it.

- [ ] **Step 4: Commit**

```bash
git add site/content/install
git commit -m "Install manual: PostgreSQL, and moving from MariaDB"
```

### Task 22: The other guides, `CLAUDE.md`, and the pull request

Spec §12.

**Files:**
- Modify: `site/content/admin/system/_index.md`; `site/content/user/documents/copying-moving-and-deleting/index.md:168`
- Modify: `site/content/developer/development-stack.md`, `tests.md`, `continuous-integration.md`, `how-healdan-extends-logicaldoc.md`, `api.md` (463, 482, 590), `desktop-clients.md:642`
- Modify: `CLAUDE.md`, `docker/README.md` if it names MariaDB, `docs/reference/logicaldoc-ce-capability-map.md` (§6's MariaDB defects)
- Modify: `docs/superpowers/specs/2026-11-01-open-source-databases-design.md` (its Status line points to this spec)

- [ ] **Step 1: Write them**
  - **Admin Guide, System:** the database is PostgreSQL.
  - **User Guide:** a name can hold an emoji.
  - **Developer Guide:** Development Stack (the published port, 5432); Tests (`test-move.sh`, the move's fixture, `test-init.sh`'s new sections, the check of PostgreSQL's log and its list); Continuous Integration (the move's test on amd64); How Healdan Extends LogicalDOC (one migrations folder; the widening as the rule's one exception; `StorableText`; the start-up tool's guard); API (the refusals' wording, `400` for a NUL in a parameter or a body); Desktop Clients (Message-IDs keep their replacement because the Helper and the server have to agree, not because of the database).
  - **`CLAUDE.md`:** the database paragraph (PostgreSQL only; the move command and when it goes); the Docker section (`DockerInit`'s keys, checks and guard; the `db` service; `compose.move.yaml`; `test-move.sh`; the manual tests); Flyway (one folder, `postgresql/`; the widening as the exception to "never changes the structure of CE's `ld_` tables"); the rules for new SQL (spec §7.3, replacing "runs on PostgreSQL too"); `Setup.java` in the upstream-edits paragraph; the sync rule for CE's tables (the SQL an existing **PostgreSQL** database needs).

- [ ] **Step 2: Build the site, run every check, run the whole smoke test and the move's test**

Expected: PASS, on a clean checkout of the branch.

- [ ] **Step 3: Commit, push, and open PR B as a draft**

```bash
git add site CLAUDE.md docker/README.md docs
git commit -m "Docs: PostgreSQL in the guides and CLAUDE.md"
```

**PR B** targets `master`, stacked on PR A, and stays a draft until Task 23 passes. Its body lists the upstream edit, the smoke runs that settled `db-log-expected.txt`, and that merging it ends MariaDB.

## The Gate, the Records and the Move

### Task 23: The rehearsal on the clone, the tag, and the merge

Spec §13.2 (step 1), §13.1. The owner's clone is a throwaway lab VM, a copy of the server's own. Its address is in the session's notes, never in the repository.

- [ ] **Step 1: Before anything is started on the clone**

Check, on the clone and nowhere else (the clone has the server's host name and ssh key, so check its address first):
  - its app container is stopped;
  - `select hd_accesskey, hd_secret is null, hd_endpoint from hd_storage_backend where hd_kind = 's3'` shows an access key that doesn't exist, no secret, and an endpoint under `.invalid`;
  - the conf volume's `default.smtp.host` is `smtp.invalid`.

  After a fresh clone or a restored backup these are done again first (the Global Constraints).

  Before anything else, too: whether the server's MariaDB data is in the named volume `db-data` or in a host folder
  (`compose.override.yaml`'s `db` service), which decides which of the Moving page's forms of the commands the rest
  of the rehearsal follows.

- [ ] **Step 2: An internal network for the clone's stack**

On the clone, an untracked `compose.rehearsal.yaml`:

```yaml
networks:
  default:
    internal: true
```

Every `docker compose` command below that starts a container names it with `-f`. The stack then publishes no port, so its checks run through `docker compose exec`. Building still reaches the network.

- [ ] **Step 3: Bring the clone to the last MariaDB version**

Check out the commit of `master` that PR B will merge onto. `docker compose -f compose.yaml -f compose.rehearsal.yaml up -d --build`; wait for healthy; from `dms.log`, Flyway's version is 12 and no seed failed; then `docker compose down` (never `-v`). Record the build's wall-clock time.

- [ ] **Step 4: Rehearse the move**

Check out PR B's head, `docker compose build`, and run the move with `-f compose.yaml -f compose.move.yaml -f compose.rehearsal.yaml`.
Expected: `Move complete.` Record the report: the tables and rows, the time it took, what only the source had, and any NUL.

Also record, as the move's own comparison can't show them:
  - the wall-clock time of each build: the new version's `docker compose build`, and, as the review suggests, the same build tried while the old stack still runs (`git checkout <PR B's head> && docker compose build`, then `down`, then the move), which would cut the page's downtime to the copy;
  - the JVM's peak memory during the copy (`docker stats` on the move's container), for 1,000-row batches of the server's widest rows;
  - the `Source` line's time zone against `TZ` in the clone's `.env` (`UTC` when it sets none): they must name the same zone.

If it stops, that's what the rehearsal is for: mend the cause (in the command, or with `--replace-nul` and the owner's say), remove only the `pg-data` volume, and run it again.

- [ ] **Step 5: Compare, then start**
  - Queries on both databases: the counts of `ld_document`, `ld_version`, `ld_folder`, `ld_user`, `ld_history`, `hd_matter`, `hd_client`, `hd_email`, `hd_trash`, `hd_storage_file` and `hd_setting`; the doc # counter; `storage.installId`.
  - `docker compose -f compose.yaml -f compose.rehearsal.yaml up -d`; healthy.
  - What only a start shows (spec §13.2): the conf volume's three database keys are PostgreSQL's; the start-up tool logged its check of CE's tables; Flyway applied nothing; no seed ran; `dms.log` has no `ERROR` since the start but the S3 backend's, which reads as failing, as expected; the local backend's marker was accepted; `curl` inside the app container gets `200` from `/login.jsp` and `302` from `/app/`; a local account the owner names for the clone signs in, reads a document from Original's folder, and a search finds it.
  - PostgreSQL's log, by Task 19's list.
  - The session's `move-date-check.sh` (in its notes, not the repository): for every `datetime` and `timestamp` column of every base table but Flyway's history and the start-up tool's marker, the count of values and the sum of each value's microseconds since 1970, worked out by MariaDB on the source and by PostgreSQL on the target, compared column by column. Every column must agree.
  - A document's dates read in the app on the clone (its timeline and a version's date), whose `TZ` is the server's own, against the same dates on the old version: every automated run is in UTC.
  - A search for a name with an accented letter, typed without and with the accent and in another case, finds what it found on the old version.
  - The tables the test's fixture leaves empty, which the rehearsal copies for the first time: `ld_tag`, `ld_foldertag` and `ld_recipient` (the three with no primary key that hold people's data), notes, bookmarks, links, `hd_signin_identity`, `hd_searchfolder` and `hd_storage_held`. Each is in the `Comparing` line's count, and a few of their rows are read back in the app.

- [ ] **Step 6: The check of PostgreSQL's log starts failing**

Count the runs of the draft pull request's CI on both architectures whose `PostgreSQL's log` lines report 0 unexpected (the smoke test prints one after step 7 and one after step 9). When the list in `docker/tests/db-log-expected.txt` has held for five consecutive runs on each architecture, change `check_db_log`'s `return 0` to `[ "$unexpected" = 0 ]`, so that an unexpected line fails the run, in a commit of this pull request, before it's marked ready. A run with an unexpected line restarts the count once the list or the code is put right. Record the runs counted.

- [ ] **Step 7: The owner's go**

Show the owner the report and the checks. On their word: tag `mariadb-final` on the commit of `master` PR B merges onto and push the tag; mark PR B ready; once its checks pass, merge it.

### Task 24: The records

**Files:**
- Modify: `docs/ROADMAP.md` (principle 9; the backlog row; §6's schema rule, now in force), `docs/superpowers/specs/2026-11-18-postgresql-design.md` (Status; corrections from the build), this plan (Status; the decision log), `docs/reference/postgresql-recon.md` (a line at its top: what was built, and where)
- Modify: the session's notes on the owner's server (its database, its backup job, the upgrade procedure's SQL commands)

- [ ] **Step 1: Write them, build the site's checks that read `docs/`, and open PR C**

Principle 9 reads that Healdan runs on PostgreSQL. The backlog row says built, with the pull requests. PR C targets `master`.

### Task 25: The owner's server moves, and the walkthrough

Spec §13.2 (steps 2 and 3). The acceptance. Done with the owner, at a time they choose.

- [ ] **Step 1: Upgrade the server to `mariadb-final`,** by the upgrade procedure in the session's notes, and let its nightly backup run, or take one by hand.

- [ ] **Step 2: Move it,** by the Moving from MariaDB page, exactly as written: the page is under test too. Keep the report.

- [ ] **Step 3: Start it, and walk through** (the owner, with the session watching the logs):

  | # | Check | Passes when |
  |---|---|---|
  | 1 | Sign in through the provider | The app opens, with the owner's matters |
  | 2 | The Helper, signed in before the move | It opens a document without signing in again |
  | 3 | Open, save twice and close a document from the Helper | Two new versions, the check-out released |
  | 4 | File an email from Outlook | It's filed once, and its emoji, if it has one, is kept |
  | 5 | A search, and a doc # typed with `#` | Both find what they did before |
  | 6 | Delete a document, then restore it from the Trash | It's back, and searchable |
  | 7 | Read a file from each storage backend | The local one and the S3 one both open |
  | 8 | The Classic Interface: users and groups, templates, Stores, Healdan Settings | Each screen opens and saves a change |
  | 9 | An avatar larger than 4,000 characters | It saves |
  | 10 | PostgreSQL's log and `dms.log` since the start | No error but those on Task 19's list |

- [ ] **Step 4: Change the server's backup job to `pg_dump`,** run it once, and restore that dump into a scratch database to prove it.

- [ ] **Step 5: Record it:** the roadmap's row (accepted, with the date), and the notes on the server. The MariaDB volume stays until the owner removes it.

### Task 26: Removing the move command (later, on the owner's word)

Spec §9.7. About a month after Task 25, when the owner says.

**Files:**
- Delete: `docker/tools/src/MoveFromMariaDb.java`, `MovePlan.java`, `MoveValues.java`, `MoveChecksum.java`, `MoveCounters.java` and their four tests; `compose.move.yaml`; `docker/tests/test-move.sh`; `docker/tests/move-fixture/`
- Modify: `docker/entrypoint.sh` (the dispatch), `docker/tools/build.sh` (the `run_test` lines), `.github/workflows/docker-image.yml` (the Move test step), `DT/StartGuard.java` (its messages say to move with the last version that had the command)
- Modify: `logicaldoc-webapp/pom.xml` (144-150: MariaDB's driver goes; an upstream edit, listed in the spec's §11.1), `.github/scripts/check-database-drivers.py` (29-37: `org.mariadb.jdbc` and its driver class) with `.github/scripts/tests/test_check_database_drivers.py`
- Modify: `site/content/install/moving-from-mariadb.md` (check out the tag that last had the command, move, then upgrade), `CLAUDE.md`, the spec's §11.1
- Modify: `HC/admin/dashboard/DashboardService.java` (`size`'s MariaDB branch, which can't run since the switch) and the comments that still speak of MariaDB in `HC/admin/errors/ErrorStore.java`, `HC/admin/dashboard/UsageSummary.java`, `HC/admin/index/IndexCounts.java` and `HCT/admin/index/IndexCountsTest.java`, with `CLAUDE.md`'s line for `DashboardService` and the Developer Guide's row for the Database tile. They came with the Admin app, written while `master` ran on MariaDB, and were left at its merge into PR B so as not to edit code the Admin app's next phases build on (Decision Log 50). Sooner, in a pull request of the Admin app's, is as good.

- [ ] **Step 1: Tag the last commit that has the command** (`move-from-mariadb-final`), with the owner's word, before the removal merges.
- [ ] **Step 2: Remove, run every check and the smoke test, and open the pull request.**

## Decision Log

Filled in as the build goes: each ruling a task needed that this plan didn't give, with its number, date and reason.

1. **Task 1, 2026-11-18.** `hd_schema_history` holds twelve rows after a first migration, not the eleven Task 1's Step 2 expects: Flyway's `0` baseline row, which `baselineOnMigrate(true)` writes because LogicalDOC's tables are there first, then V1 to V11. `PostgresMigrationsTest` asserts the history exactly.
2. **Before Task 1, 2026-11-18.** The Global Constraints' "`Savepoints`, Task 5" meant Task 6, which moves `Savepoints` to `healdan-core`. They say Task 6 now.
3. **Before Task 1, 2026-11-18.** The smoke test on MariaDB ran where the tasks' own steps run it, after Task 2 (Step 5) and on the final head (Task 9, Step 3), and CI runs it on both architectures for the pull request, rather than after every task as the Global Constraints say. A task that broke the stack would still fail the final run.
4. **Task 1's review, made in Task 4, 2026-11-18.** CI's `postgres:18` service runs with the settings `test-db.sh` gives the local server, `fsync`, `synchronous_commit` and `full_page_writes` off, 300 connections and its data on a tmpfs, through `POSTGRES_INITDB_ARGS`' `-c` flags and `--tmpfs` in the service's `options`, where Task 1's YAML left PostgreSQL's defaults. Every test makes a database and loads LogicalDOC's schema statement by statement, and the settings are there so that CI's time to green doesn't grow, as the spec's §2.4 requires. The spec's §10.3 says so now.
5. **Task 2, 2026-11-18.** Hibernate 7.4.4 has no `TypeConfiguration.getPreferredSqlTypeCodeForBoolean()`. `BooleanBindingTest` reads `getTypeConfiguration().getCurrentBaseSqlTypeIndicators().getPreferredSqlTypeCodeForBoolean()`, which answers from the bound session factory's options.
6. **Task 2's review, made in Task 4, 2026-11-18.** On PostgreSQL a lost boolean setting fails every test in its set-up's seeds, before `BooleanBindingTest` can name it. So `AbstractHealdanTestCase.setUp` checks the setting before the seeds run, and fails with a message that names it and `hibernate.properties`: that's what the spec's §7.1 means by "named by the test that fails". The spec's §7.1 says so now.
7. **Task 3, 2026-11-18.** `HealdanTypedReads` converts in Java only a number held exactly (an integer type, `BigInteger` or `BigDecimal`): to `Long` or `Integer` only when that type holds it exactly, and to `Double`, `Float`, `BigDecimal` or text in plain notation. A fraction asked for as a whole number, a value out of range, a value that is a `Double` or `Float`, and anything that isn't a number go to the driver as before. So MariaDB and HSQLDB answer as they did, and on PostgreSQL every typed read today's callers make is answered without the driver. "Already of the type" means the exact class. The spec's §7.2 says so now.
8. **Task 4, 2026-11-18.** Task 4's marks took two of `HealdanMigratorTest`'s tests, not the three of the spec's §4.2: Task 1 had already made `vendorFolder` accept PostgreSQL, so only the two whose fixture scripts were only under `hsqldb/` failed.
9. **Task 4's review, 2026-11-18.** `AbstractHealdanTestCase.getConfigResource()` starts from `pg-context.properties` only while that file is at least as new as `target/test-classes/context.properties`, where Task 4's Step 2 says "when that exists": that wording would hide an edit of the test `context.properties` from every later local run without `clean`. Within a run a test still reads what an earlier one wrote.
10. **Task 4, an observation, 2026-11-18.** PostgreSQL's server log gets about ten `relation "ld_generic" does not exist` errors per test: LogicalDOC's context reads its settings before the base class loads the schema, as it did on HSQLDB, which logged nothing. Nothing fails. Loading the scripts before `super.setUp()` would stop them, but would change what LogicalDOC reads as its context starts, so the order Task 4 gives stays. The final review is to confirm it.
11. **The merges, 2026-11-18.** Healdan-Project/healdan#272 merged while PR A was being built, and `origin/master` was merged in after Task 4 (`440d92993`), a merge that changes no file. It was merged in again after Task 8 (`f08477c1c`), bringing Healdan-Project/healdan#274 to #276.
12. **Task 5, 2026-11-18.** The failing script's fixture is `failing/postgresql/V9999__broken.sql`, which creates a table and then fails, where Task 5 moved `V2__broken.sql` as it was. That script had shared version 2 with `V2__preview_jobs.sql` since 2026-10-22, so Flyway refused the pair before running either, and `testFailingScriptFailsStartup` passed without the broken script running, on HSQLDB too. The test now asserts SQL state 42601, no `hd_broken` table and no extra `hd_schema_history` row: PostgreSQL rolls the failed script back and records no failed row, as recon §3.3 found. (`HealdanMigratorTest` lost two tests to Task 4's marks: see 8.)
13. **Task 6, 2026-11-18.** `PreviewDocumentListener`'s three methods keep their guard, which is Java alone (the event, an Office file name), outside the savepoint, and run only their statements inside it, where Task 6's Step 3 says "the body of its `try`". The spec's §7.3 asks for the statements in a savepoint; a savepoint is two round trips, on MariaDB too, and these methods run on every save of a document. The email and favourite-names listeners put the whole of their `try` inside, as its first step can be a statement. The spec's §7.3 says so now.
14. **Task 6, 2026-11-18.** `Savepoints.run` rolls the savepoint back on any `Throwable` the work throws, an `Error` included, where Task 6's interface says `RuntimeException`: the email and preview listeners swallow `Throwable`, so an `Error` would otherwise leave the work's partial writes in a save that then commits. `SavepointsTest#testAnErrorRollsBackTheWork` pins it. A checked exception (only `PreviewDocumentListener.afterStore`'s `PersistenceException`) crosses the savepoint in a private wrapper and is rethrown as itself, so each listener logs what it logged before. The spec's §7.3 says so now.
15. **Task 6, a known limit, 2026-11-18.** `PreviewDocumentListener.requeueReplaced`'s savepoint holds one Hibernate read (`VersionDAO.findByFileVersion`). Inside an outer transaction that had dirty `Version` rows, the read could flush them into the savepoint, and a failed requeue would then roll that flush back. No route does so today: `DocumentManager.replaceFile` and its callers open no outer transaction.
16. **Task 7, what PostgreSQL leaves in the three cases (Task 7's Step 4), 2026-11-18.** The state is the one HSQLDB and MariaDB leave. What differs is that LogicalDOC's failure no longer stays inside LogicalDOC: the write fails in its flush, LogicalDOC's DAO carries on, its next statement fails with "current transaction is aborted", and that failure escapes.
    - `SaveTest.testUnrecordedVersionIsAnError`: the document is at 1.1 with the file sent, its timeline has only 1.0, and it has no hold. The failure arrives as an `ExecutionException` from LogicalDOC's version future. The API answers "Document #N's save wasn't stored completely: its content is the file sent, as version 1.1, but that version isn't in its timeline. It isn't checked out to you. Send the save again." The save sent again is `201`, as 1.2.
    - `SaveTest.testUnstoredSaveIsAnError`: the document is at 1.0 with its content as it was, its timeline has only 1.0, and the caller's check-out still holds. The API answers "Document #N's save wasn't stored: its content is as it was. It's still checked out to you. Send the save again." The save sent again is `201`, as 1.1.
    - `FilingTest.testUnrecordedFirstVersionIsAnError`: the document is filed at 1.0 with the file sent, its timeline is empty, and it has no hold. The API answers "Document #N was filed, but its version 1.0 isn't in its timeline. Its content is the file sent. It isn't checked out to you. Don't file it again."
    - `EmailFilingTest#testAllOrNothing` passes on PostgreSQL: an attachment whose version record fails goes through `Filer`'s read-back, `EmailFiling` undoes what it filed, and the answer is the one it gave before.
17. **Task 7, 2026-11-18.** `VersionResource.checkIn`'s catch takes `ExecutionException` too, which Task 7 didn't expect: in the first case of 16 the failure comes out of LogicalDOC's version future (`checkedIn.get()`), not out of `checkin`, so without it the read-back in the catch would never have run for that case. The spec's §7.3 says so now.
18. **Task 8, 2026-11-18.** `FolderListing` isn't changed, and `FolderResourceTest#testAMatterFilterTypedWithSpaces` pins what the API already does, where Task 8 has `FolderListing` strip the client and the matter either side of the dot, and `matter=998%20.1` filter as `998.1` does. The API's `Filters.of` already trims every listing filter's value, and refuses a matter that isn't digits, a dot and digits with `400 bad-request`, as `openapi.yaml`'s pattern says, before `FolderListing` is reached. So the spec's §7.4 rule (a listing's filter is trimmed in Java first) already held, no value with a trailing space reaches SQL on either database, and turning the `400` into a `200` would have changed what a running MariaDB server answers. The test: spaces around the value filter as the bare value does, and a space inside is `400`. The spec's §7.4 says so now.
19. **Task 8, 2026-11-18.** Task 8's "`find` 45" under `API/DocumentResource.java` is `LegacyDocNumbers.find`, in `healdan-core`. It and `DocumentResource.byLegacy` strip the legacy number they're given.
20. **Task 8, 2026-11-18.** No other `order by` on a key that can be NULL needed a clause. `TrashStore`'s sort on `removed` is on a column that can be NULL, but each of the Trash's views holds all NULLs or none, so its order is the same on both databases. The spec's §7.4 says so now.
21. **Task 8, 2026-11-18.** The legacy doc # lookup strips whitespace at both ends, as Task 8 and the spec's §7.4 say. On a running MariaDB server that changes two edge answers before the switch: a lookup typed with a leading space or a trailing tab now finds the document (MariaDB's comparison ignored trailing spaces only), and a legacy number stored with a leading space is no longer found by this lookup. The spec's §13.1 says so now.
22. **The whole-branch review, 2026-11-18.** On MariaDB too, a save or a filing that LogicalDOC fails part-way by throwing now answers with what's left of the document (the sentences of `VersionResource.confirmStored`), where it answered the plain `500` ("Something went wrong on the server."). Its cause, which then reaches no other log line, is logged at error as the chain of its exceptions' class names, with a database failure's SQL state and code and never a message, which can quote the row; the stack is at debug. The status and the code are unchanged, and `openapi.yaml` already allowed the wording. `SaveTest.testUnstoredSaveIsAnError` checks the log line. The spec's §13.1 says so now.
23. **The whole-branch review, 2026-11-18.** The three part-way tests of 16 pin the state it records as well as checking the answer against the document, where Task 7 had them accept either state: the plan was written before the state was known, and a regression that flipped it would otherwise pass.
24. **The whole-branch review, 2026-11-18.** `Savepoints.run` rolls the savepoint back when its release fails, and throws the release's failure. On PostgreSQL that's what happens when the work swallowed a failed statement of its own: the transaction is aborted, so the release fails, and without the rollback the caller's transaction would stay dead, the case the class is there to prevent. On MariaDB a release fails only when the savepoint is gone, and the rollback then fails harmlessly. `SavepointsTest#testAFailureTheWorkSwallowedStillRollsBack` pins it.
25. **The pull request's review, 2026-11-18.** Ten findings, each fixed in PR A:
    - The setup wizard's database type offers no embedded database and starts on External (`Setup.java`, an upstream edit the spec's §11.1 lists): the WAR's default `jdbc.dbms=hsqldb` made Embedded the wizard's choice, and `HealdanMigrator`, which refuses HSQLDB, then stopped the context's refresh at the wizard's last step and every start after it.
    - CI's shards pass `-nsu` only when the Maven copy's restore was an exact hit. With `-nsu` on every shard but the saving one, a pull request whose POMs move to a release newer than the copy (Bouncy Castle's own POMs ask for a range) would find no version in the copy's lists on every shard, never pass, and so never let master save a newer copy.
    - `VersionResource.confirmStored` looks for the file of the version the document moved to before it says "its content is the file sent" (the Open Point below on part-way answers): LogicalDOC stores the version before its file and puts the document back only on an `IOException`. Without the file, a save says the version's file wasn't stored, and a filing says to delete the document and file it again.
    - `LegacyDocNumbers` compares `trim(ld_stringvalue)`, on both databases, so a number stored with spaces at either end is still found (the Open Point below on text stored as given). Decision Log 21's "a legacy number stored with a leading space is no longer found" no longer holds.
    - `HealdanMigrator`'s recovery message on PostgreSQL is `RECOVERY_POSTGRESQL`, the wording Task 5 gives the switch: PostgreSQL rolls a failed script back, and `success` is a boolean (the Open Point below on `RECOVERY`).
    - `docker/tests/test-db.sh start` prints the port the running container publishes, not the one `HEALDAN_TEST_DB_PORT` names now.
    - The two favourite-names listeners lose their savepoint (Decision Log 13): their `afterStore` finds the row before the store in the dictionary (their `beforeStore` read it) or in the session's map (the rules' `afterStore` records it), so it reached no database, and the savepoint was two round trips on every store.
    - `ProblemMapper.toResponse` and `unexpected` share one classification, `kind`, so the two can't drift apart.
    - `DocumentResource.byLegacy` no longer strips the number before `LegacyDocNumbers.find`, which does.
    - pgjdbc's version is `postgresql.version` in `logicaldoc-parent/pom.xml`, which `logicaldoc-webapp/pom.xml` and the three modules' test dependencies use (an upstream edit the spec's §11.1 lists).
26. **Task 13, 2026-11-19 (the first Open Point below).** The stack's JDBC URL ends `?logServerErrorDetail=false` (`ConfigPlan.jdbcUrl`), and the start-up tool's and the move's own connections use the same URL (Task 17). pgjdbc otherwise puts the server's detail into every `SQLException`'s message, and for a check, NOT NULL, length or duplicate-key failure the detail quotes the row's values, where Healdan's rule is that file names, subjects and comments never reach its log at info or above. PostgreSQL's own log still has the detail. Nothing in Healdan reads the detail from a message: a search of the `healdan-*` modules and `docker/tools` for the detail's words, `getServerErrorMessage`, `getConstraintName` and the like found only log text and comments. The spec's §8.2 says so now.
27. **Task 10, 2026-11-19 (the second Open Point below).** `SearchFolderService.appendValue` compares `trim(x.ld_stringvalue)`, as entry 25 did for legacy numbers, so a value stored with spaces at either end still matches; the rule's own value was already stripped when it was saved. `SearchFolderServiceTest#testClassStoredWithATrailingSpaceMatches` pins it. `StoredRows.liveChild` isn't changed: it predicts what LogicalDOC's own `ld_name = ?` or `like ?` finds on the same database, so it has to compare as the database does. The spec's §7.4 says so now.
28. **Task 10, 2026-11-19.** The API answers `400 bad-request` for a NUL or a lone surrogate in any text a JSON body carries, values read as `JsonNode` trees included (a property's value, a favourite's name), through two deserialisers in `ApiJson`, and `ProblemMapper` names the field. So a body can no longer reach the resources' own `422 invalid` checks with such text, and `openapi.yaml` says the rule once, in its header. A NUL in a multipart part's file name stays `422 invalid`, as a control character, as before. `TextFilter` is pre-matching, so a request with a NUL in a query parameter is `400` when it isn't signed in too, not `401`. `StorableText` gained `cut`, which shortens text to a column's length without splitting a surrogate pair: once emoji are kept, the sites that cut after replacing could split one. The brief's two body routes (`PATCH /v1/documents/{n}` with a comment, `POST /v1/matters`) don't exist, so the tests use a save's comment, a new folder's name and a check-out's app. The spec's §7.5 says so now.
29. **Task 11, 2026-11-19.** `HealdanMigrator` names the Moving from MariaDB page to MariaDB alone, known by its product name or by a version string that names it through MySQL's driver. MySQL itself, like any other database, gets "Healdan's tables support PostgreSQL only; found …", where the spec's §6.5 had MariaDB and MySQL both name the page: Healdan never ran on MySQL, so there is nothing to move from it. LogicalDOC's generic script makes no `text` column of its own, so the widening's test asserts exactly the eight. Three of `HealdanMigratorTest`'s tests now load LogicalDOC's real schema, as V12 alters real columns. The spec's §6.5 says so now.
30. **Task 12, 2026-11-19.** `StoredFiles.recordWrite` and `HeldCopies.hold` name their conflict keys, `on conflict (hd_docid, hd_resource) do nothing` and `on conflict (hd_backendid, hd_location) do nothing` (V9's primary keys), where the plan said a bare `on conflict do nothing`: a unique index added later then fails loudly instead of being swallowed. `PreviewJobs.add` and `EmailIndex.put` name theirs too, and `EmailIndex.put` is one upsert. `Savepoints.insertIfAbsent` and its test are gone, as those four were its only callers. `hold`'s duplicate path has no test: reaching it needs a second thread blocked on the insert, which can't be done without sleeps or a hook. The spec's §7.3 says so now.
31. **Task 13, 2026-11-19.** The start-up tool's probes ask `to_regclass` and never select from a table that may be missing, so a start writes no `ERROR` line to PostgreSQL's log, the question the spec's §14 left open, and the trial's two lines are gone; `NO_SUCH_TABLE` went with them. The checks of the server and the database are one method, `DockerInit.checkDatabase`, which the move shares (Task 17's review). A refused `alter role` stops the start as an `InitException` naming the role, not as a stack trace. The two notes, a major newer than 18 and a collation that doesn't sort by code point, print as `WARNING:` lines, and the encoding refusal gives the `create database` statement to run (corrected in the whole-branch review: the role refusal gives `alter database … owner to` or `grant create on schema`, and the locale case is a warning, which gives the `create database` statement for a dump and restore). Step 5 was run by hand as an ordinary role that owns its database: such a role may set its own `lock_timeout` there. The spec's §8.2 says so now.
32. **Task 14, 2026-11-19.** The guard connects before `applyConfig`, and nothing is written, to the conf volume or the database, before its verdict. When the conf names MariaDB, a database that doesn't answer in 15 seconds (`StartGuard.ANSWER_SECONDS`) counts as not reachable, whatever `DB_WAIT_SECONDS` says, about 17 seconds in practice, as each connection attempt gets only the time left. The conf is read with a plain `Properties.load`, not `ContextProperties`, whose constructor touches the file, so a refused start leaves the conf's bytes and modification time as they were; `context-override.properties` beside it is read the same way, as `ContextProperties` would take the two keys from it. `holdsFiles` counts a folder that exists but can't be listed as holding files. A conf that names MySQL counts as MariaDB's, a `docker_move` table with no row as an unfinished move, and a sign-in the database refuses stops the start with its own message whatever the conf names. The three messages were reworded after the review: the restore message covers a lost database volume, and the move-first message no longer claims the database is empty. A database setting with a line break, or beyond Latin-1, is refused before the guard connects. The config-loss warning comes straight after the conf is seeded. The entrypoint's own writes before the tool runs (ownership, the image's defaults into an empty conf volume, the repository's folders) are unchanged, and touch no existing `context.properties`. The spec's §8.3 says so now.
33. **Task 15, 2026-11-19.** The `postgres` image's own `pg_hba.conf` trusts loopback TCP, so taking the superuser's password away didn't shut it out of `psql -h 127.0.0.1` inside the container, as the plan's Step 3 expected. `POSTGRES_INITDB_ARGS` therefore gains initdb's own `--auth-host=scram-sha-256 --auth-local=trust` after the four encoding and locale flags, which stay as the Global Constraints give them: the socket stays trusted, and every TCP connection asks for a password. The role script asserts no `host … trust` line is left. It exits non-zero when `psql` fails (at first it swallowed failures, which left the superuser with the app's password after a bad `DB_NAME`); before any statement it refuses a `DB_USER` that is the superuser's name or begins `pg_`, a `DB_NAME` of `postgres`, `template0` or `template1`, and an empty setting; and it sets `log_min_error_statement = panic` first, so a failing `create role` never puts the password into the log. The health check is healthy only when the app's role can use its database (`select 1` over the socket, after `pg_isready` over TCP), so a first start the script refused never reads healthy. The spec's §6.3 and §8.1 say so now.
34. **Task 16, 2026-11-19.** `MoveValues.carry` refuses a target type it doesn't know even for a null, so one pass over the columns finds it before a row moves; pads a `character(n)` value to n, as PostgreSQL stores it, so the checksums agree; and refuses a floating-point number for a `numeric` column, and any Java class it doesn't expect, naming the class or the type and never the value. Text's length is counted in code points. A name one side gives more than once, whatever the case, and a lone surrogate stop the move too. The spec's §9.3 says so now.
35. **Task 17, 2026-11-19.** MariaDB's driver in the WAR (Connector/J 3.5.4) misreads a `datetime(3)` value under a tenth of a second through `getString` (`.069` arrives as `.69000`), and a comparison of what was read with what was written can't see a change made on the read. So dates are read as `cast(col as char)`; rows are read in MariaDB's binary protocol, as its text protocol rounds floats; and each table is also checked, in the same snapshot, against MariaDB's own `count` of every column and `sum(crc32(…))` of every column but the floating-point ones. An identity column's counter is set from the higher of the column's highest value and MariaDB's `AUTO_INCREMENT − 1`, where the spec's §9.3 step 5 says from its highest value: a gap, never a repeat, when the last rows were deleted. The spec's §9.3 says so now.
36. **Task 17, 2026-11-19.** What the move reports. After a refusal it reads every remaining table, writing nothing, so the report names every place in one run, as the spec's §9.3 step 4 asks. A foreign key that can't be rebuilt lists its orphan rows by table, key and column, ids and never content, twenty at most. The list of what only the source had and the list of every replaced NUL print before the finish is committed, and `Move complete.` is the last line; "Foreign keys rebuilt" comes before "Comparing", in the order the steps run, where the spec's §9.1 sketch has them the other way round. The Source line shows the source's time zone. A failure while reading or inserting rows prints the table, the SQLSTATE and the exception's class, never the driver's message. A `docker_move` table with no row counts as unfinished, and is looked up in `current_schema()`. A session advisory lock refuses a second move at once. `main` reaches an exit status whatever happens, so no connection or driver thread keeps the JVM up. `MovePlan` carries the source's spelling of each table's name. The spec's §9.1, §9.3 and §9.5 say so now.
37. **Task 18, 2026-11-19.** The fixture was made from `84c802acb`, the commit this pull request merges onto, by `make.sh`, which takes the MariaDB-era checkout and image from its environment, and it's about 53 KB. It holds a date at `.069` of a second (entry 35's fault), a sealed sign-in client secret that must still open, an accented matter made through New Matter's service, so that its folder and its row agree, and an encrypted local storage backend with a file. The test gained a case the brief didn't have and the spec's §10.2 asks for: a comparison that fails, through a test-only event trigger that changes a value on the target, so the command's own comparison refuses and leaves the target unfinished. Every move run's raw output must hold nothing but the command's lines, so a stack trace fails it, and MariaDB's sequences are part of what stays untouched. The spec's §10.2 says so now.
38. **Task 19, 2026-11-19.** The check of PostgreSQL's log prints what it finds and passes; it fails only when it couldn't read the log (a failed or empty read, or no "ready to accept connections" line) or its list is missing. Making it fail on an unexpected line is left for after the rehearsal, as the pull request stays a draft until then and the list has to hold over several runs on both architectures first. (Made a step in the whole-branch review, Decision Log 46: an unexpected line prints, and passes, until the list has held for five consecutive runs of the draft pull request's CI on each architecture; then, before the pull request is marked ready, a commit of it makes the check fail on one, Task 23's Step 6.) `db-log-expected.txt` holds `StatsCollector`'s two patterns, taken from a scratch stack with its delay shortened, as no smoke run lives the 30 minutes it waits. The check of a user's edit is Disable and Enable of `apiuser` over GWT-RPC: `changeStatus` goes through `UserDAO.store` and the typed read of `ld_enabled`, recon §2.6's fault, and LogicalDOC has no REST user service, while SOAP is removed. The emoji matter is made over GWT-RPC, as the API can't create matters, and takes its client's next free number, 1.5 today, so every smoke stack has it from step 5 on. `test-move.sh` runs Compose with `--progress quiet`, so its check of the raw output doesn't depend on how Compose prints its progress. The scripts run by hand take their names from the environment. No fault was found on PostgreSQL in the app, the start-up tool or the move, and PostgreSQL's log held no `ERROR` line in any whole run. The spec's §10.1 says so now.
39. **Task 21, 2026-11-19, from running every procedure in the Install manual.** The spec's §9.1, §9.6 and §12 say so now.
    - Going back needs one more step than the spec's §9.6 says. The new version's first start writes PostgreSQL's driver, `jdbc.dbms` and dialect into the conf volume, and `mariadb-final`, which seeds those three only once, then fails with `No suitable driver`. The page's Going Back gives the command that puts them back. Making `mariadb-final` own those keys would need a change to `master` before the tag, outside this pull request.
    - The spec's `stop mariadb` (§9.1) leaves the move's container, which holds `db-data` and which every later `up` warns about. The page uses `rm --stop --force mariadb`, and Going Back takes the stack down with both Compose files named.
    - `compose.move.yaml` doesn't cover an install whose MariaDB data is a host folder. The page has a section for it, in which the override keeps both folders on `db` while the install moves, run for real on Linux folders inside Docker's VM; a real Linux host's own paths are still to be tried.
    - The recon's §5.3 backup command dumps `$POSTGRES_DB`, which is `postgres`. The pages dump `$HEALDAN_DB_NAME`, and restore as the app's role with `--no-owner --single-transaction`, so the app owns its tables.
    - A new PostgreSQL major initialises its own versioned folder in the same volume, as the 18 image lays it out, where the plan said a new volume.
    - The stack's password change uses `psql`'s `\password`, so nothing typed needs escaping, and no failed statement can log the password.
    - The move's output is kept with `tee`, outside the checkout the image is built from, as `run --rm` removes the container and its log.
40. **Task 22, 2026-11-19 (the third Open Point below).** An install set up outside Docker on PostgreSQL gets none of the switch's protections: the checks of the server and the database, the guard, the database keys, the 50-second lock timeout as a setting of the role, and `logServerErrorDetail=false` are all the start-up tool's work, and the Docker stack is the only install Healdan supports and its manuals describe. So no code changes. The Developer Guide's Building from Source says what such an install doesn't get, and how to set the lock timeout and the URL parameter by hand.
41. **The build, 2026-11-19.** The controller ran the three whole suites in a second worktree on Task 12's head (`afca5d5e7`): `healdan-core` 1,304 tests, `healdan-api` 628 and `healdan-storage` 475, with no failures and five skipped; each task ran its own focused tests. On the code-complete head, `ee91b0fe6`, the controller ran them again in a second checkout, on PostgreSQL 18: `healdan-core` 1,305 tests (1 skipped), `healdan-api` 628 and `healdan-storage` 475 (4 skipped), all passed with no failure and no error; the five skipped are the tests that skip themselves when Maven runs as root. The suites run again in CI on the pull request's head. `origin/master` didn't move during the build (`84c802acb`).
42. **The whole-branch review, 2026-11-19 (its Important 1, and its item 7, ruled option B).** The move makes Healdan's tables at version 12 and no later one: `HealdanMigrator.migrateTo(version, locations…)`, which shares `migrate`'s code and errors and sets Flyway's `target`, and `MoveFromMariaDb.TARGET_VERSION`, `"12"`, beside `SOURCE_VERSION`; the move checks the version reached and refuses another (`versionRefusal`). It used to run every migration on the empty target, then empty the tables and copy the source's rows in, so a later migration that changes or adds rows, as V7's `update` and V9's `insert` did, would have run on empty tables, been emptied, and been recorded as applied: a moved install would never have got it, and no test would have tripped. Now the app's first start applies it to the moved rows, as on any upgrade, and the Move test exercises move-then-upgrade for every later migration. Twelve migrations shipped in about five weeks, so one inside the command's lifetime is likely. Healdan's own migrations are then no longer a concern for the move; a change to CE's tables still is (the Open Points below). `HealdanMigratorTest#testMigrateToRunsNothingAfterItsVersion` migrates a fresh database to 12 beside the failing `V9999__broken.sql`, which isn't run or recorded, while `migrate` with the same locations still fails on it; `MigrationTestSupport.migrateTo` calls the new method, so tests and the move share one way. `MoveFromMariaDbTest` pins the constant to the widening's script, and its message says what to do when a V13 ships: nothing, the constant stays 12. The report's Schema line names the version. The spec's §6.5, §9.3, §9.6 and §9.7 say so now.
43. **The whole-branch review, 2026-11-19 (its Minor 1).** A move never leaves an unfinished `docker_move` beside the start-up tool's marker, as it makes `docker_init` in the transaction that marks it finished; so an unfinished move with `docker_init` present is outside interference, on a database Healdan may have started on. The move refuses it, changing nothing, with a message that says what it found and that it won't empty such a database (`TargetState.STARTED`, decided in `targetState`, which `MoveFromMariaDbTest` tests); before, it emptied it as any unfinished move. The start-up tool's guard still says `MOVE_AGAIN` there. The Moving page's If It Stops has the message. The spec's §9.5 says so now.
44. **The whole-branch review, 2026-11-19 (deferred minors of Tasks 13, 15, 17 and 21).** `Move complete.` comes after both connections are closed, and a failure to close one is a warning, so a finished move can no longer end with an `ERROR` and status 1 (`MoveFromMariaDb.close`, tested with a connection whose close fails). The refusals of a finished target and of a target holding something else say how to empty it on volumes and with host folders, and name the Moving page's Starting the Move Over. `ConfigPlan.jdbcUrl` refuses a `DB_HOST` or `DB_NAME` that could cut `?logServerErrorDetail=false` off (a `?`, `#`, `%`, `/` or space), by the rule the move checks its source with, now one rule in `ConfigPlan` (`HOST`, `DATABASE`). The role script's check of `pg_hba.conf` covers every TCP form (`host`, `hostssl`, `hostnossl`, `hostgssenc` and `hostnogssenc`), checked against a running `db` service. With them: `compose.move.yaml` runs MariaDB with the old `db` service's character-set flags (Minor 2), `test-init.sh` removes its database container's anonymous volume (`docker rm -fv`), `.dockerignore` leaves out the files Backup and Restore writes beside `compose.yaml`, and the Moving page writes its backup to `~/healdan-backup`, outside the checkout (Important 3), run for real on a scratch stack of the last MariaDB version. Comments that spoke of MariaDB as a database Healdan runs on now say what's true on PostgreSQL (Minor 7).
45. **The whole-branch review, 2026-11-19 (its Minors 12 and 3).** `test-move.sh` starts the app on the fixture's volumes against the empty PostgreSQL before the first move, and checks the guard's move-first refusal: its message, the app stopped with status 1 and Tomcat never started, the conf file's SHA-256 as it was, and no table in the database. It adds about 4 s, PostgreSQL's first start included, which the first move used to pay. After the finished move it counts every base table on both sides, apart from the move's own report, with one query a side made from each catalogue, and compares the two sorted lists, apart from Flyway's history and the markers, each side's own (`MovePlan.OWN`): the report's "N of N" lines print one number twice, so the test's checks of them carry nothing. The existing checks stay. Each was seen failing first: the move-first check on a conf that no longer names MariaDB (the guard said restore first), and the counts on a row deleted from the target and on MariaDB's own `docker_init`. The test now runs 83 checks in about 35 s.
46. **The whole-branch review, 2026-11-19 (its Important 2).** The check of PostgreSQL's log can't fail on what it's there to catch until its `return 0` changes, and that change had no step. Task 23's Step 6 is it: the runs of the draft pull request's CI on both architectures whose `PostgreSQL's log` lines report 0 unexpected are counted, and when the list has held for five consecutive runs on each, a commit of this pull request makes an unexpected line fail the run, before it's marked ready. `CLAUDE.md`, the spec's §10.1 and the comment in `docker/smoke-test.sh` say it in the same words. Task 23 also gained what the rehearsal checks that the move's own comparison can't show (the review's recommendations).
47. **The whole-branch review, 2026-11-19.** The questions the review left for the owner are in Open Points for the Owner Before the Merge, below: what the move does about a change to CE's tables, holding `master` until the tag exists, a warning for a plain `C` or `POSIX` database, and `log_error_verbosity`.
48. **The merges, 2026-11-19.** Healdan-Project/healdan#278, a template database for each test JVM and a test server whose password is cheap to check, merged while this pull request was a draft, and `origin/master` (`ad54ff872`) was merged in after the review's commit (`afa48042c`). One conflict, in this plan's Expected Costs table, where both sides had replaced PR A's estimate with what was measured: `master`'s row for PR A stands, as it also says what #278 took back, and this branch's row for PR B. #278 changes no table and not the stack, so the move's fixture, made from `84c802acb`, stays valid; `mariadb-final` goes on the commit this merges onto, which is no longer the commit the fixture came from (entry 37 and the spec's §10.2 called it that).
49. **The pull request's review, 2026-11-19 (`86a8448df`, made by a separate review session).** Four changes. The role script no longer exits on a TCP rule of `pg_hba.conf` whose method is `trust`, as entry 33 had it: the image never runs its scripts again on a data folder it has made, so a restart after that exit would have started the server on the file as it was. Such a rule is made `scram-sha-256` first, before anything else in the script can stop it, matching the method field only, and the script exits only if one is still there. `DockerInit`'s `missingTables` looks `ld_user` and `docker_init` up in `current_schema()`, as `move()`, `columns()` and `HealdanMigrator` do, and its probes share one connection, which `run()` and the move close; `lockTimeout()` still reads on a new one, as a role's setting reaches only connections made after it. The move works out a row's key only for a value it refuses or a NUL it replaces, not for every row it copies. `StartGuard.MOVE_PAGE` is `HealdanMigrator`'s, the move asks the new `StorableText.hasLoneSurrogate`, and `SearchFolderService`'s documentation no longer says "both databases". On that commit `test-move.sh` (83 checks) and `test-init.sh` (91) passed locally. In CI every check passed but `Test (amd64)`, whose smoke test failed one WebKit browser test ("Measured", below).
50. **The merges, 2026-11-20 (UTC).** CI passed every check on `da317439e`. An hour later Healdan-Project/healdan#279, the Admin app's foundation and Dashboard, merged, and `origin/master` (`3390247e0`) was merged in here (`1e5e92a73`). It brought `V12__admin.sql` to both migration folders, the version this branch's widening had, with three tables (`hd_admin_event`, `hd_error`, `hd_error_hour`) that an install on `master` now has on MariaDB. What followed is what Task 11 says for a version another change has taken, what #279 had already written into this plan's Tasks 11, 17 and 23, and what the fixture's README says for a MariaDB migration.
    - The widening is `V13__widen_logicaldoc_text.sql`, and `mariadb/V12__admin.sql` went with its folder. The move takes a source at version 12 (`SOURCE_VERSION`) and makes its target at 13 (`TARGET_VERSION`): the twelve scripts the source ran, translated, then the widening. Where entries 37 and 42 say 11 and 12, read 12 and 13; the spec and the docs say so now. A PostgreSQL database an earlier build of this pull request made has the widening recorded as version 12, and this build refuses to start on it, as Flyway finds another script at 12. None is an install: only this pull request's tests and scratch stacks made one.
    - The fixture was made again by `make.sh` from `3390247e0`, with that commit's own image. It holds the app's own rows in the Admin app's tables (the record of its client and its matters, and an error it logged for want of a mail server, with a stack of many lines), and `make.sh` adds an error with SQL: resolved, with an accented message, first seen at `.069` of a second, counted in two hours. `test-move.sh` counts the three tables, reads that error back and checks the two new identity columns' counters: 90 checks, where it had 83.
    - The move needed no change. Its plan matches tables and columns by name, it finds identity columns in PostgreSQL's catalogue, and a `char(64)`, a `datetime(3)` and a plain `datetime` were already among what it carried. A read-only audit of #279's code found nothing else that fails, or is expected to fail, on PostgreSQL: `ErrorStore` updates first and inserts when no row changed, with one writer, `ErrorWriter`'s thread, and `AdminEvents.recordAfter` runs in a savepoint.
    - Left as it came: `DashboardService.size`'s branch for MariaDB, which can't run now, and comments in `ErrorStore`, `UsageSummary`, `IndexCounts` and `IndexCountsTest` that speak of both databases. Task 26 lists them, so that this pull request edits none of the code the Admin app's next phases build on. A captured error's lone surrogate is stored as `?` by the driver, not as U+FFFD (`CapturedError.fit` takes out only NULs): harmless, and noted for the Admin app.
    - Five conflicts: `CLAUDE.md`'s Docker tests, where both sides had edited the same long lines, merged word by word; this plan's rehearsal step (version 12, with this branch's timing note); `ApiServer`'s Javadoc (the text filter, then the admin filter); `PostgresMigrationsTest` (41 date columns, thirteen scripts); and the Developer Guide's Tests page (The Move's Test, then The Web Apps' Tests). A stale `target/classes` kept the old `V12__widen_logicaldoc_text.sql` beside the new one, so a local build after this merge needs `healdan-core/target` removed first; CI builds afresh.
    - On `a907d14c0`, locally: the image builds, with its tools tests; `test-move.sh` passes its 90 checks in 46 s and `test-init.sh` its 91; the smoke test passes whole, 239 checks and all 194 browser tests, the Admin app's among them, in Chromium and WebKit, with no `ERROR` line in PostgreSQL's log, run with Healdan-Project/healdan#281's change to `test-web.sh` applied for the run and not committed ("Measured, 2026-11-20", below); and `healdan-web`'s 31 tests pass. The other three suites and CI are in the pull request's description.
    - `mariadb-final` goes on the commit this merges onto, `3390247e0` today: the second of the points for the owner, below, which this merge was an instance of.
51. **Task 23, Steps 1 to 6: the rehearsal on the owner's clone, 2026-11-19 (2026-11-20 UTC), on the owner's go for the rehearsal alone.** The clone is a copy of the owner's server with its real rows: 53 live documents, 102 versions, one local backend and one encrypted S3 backend. It passed. Step 7, the tag, marking the pull request ready and the merge, stays the owner's.
    - **Cut off, checked first (Step 1) and held throughout.** The app was stopped; the S3 backend's row had an access key that doesn't exist, no secret and an `.invalid` endpoint; `default.smtp.host` was `smtp.invalid`; MariaDB's data was in the named volume, so the page's plain commands applied. The stack ran only with `compose.rehearsal.yaml`'s internal network, checked to be internal before each start of the app, and a request out from the app's container failed each time it was tried. The S3 backend read as failing ("the secret … doesn't open") and nothing was written to it. Each sign-in as `admin` made the app try its admin sign-in alert, and each attempt failed at `smtp.invalid`: nothing was sent.
    - **Step 3, to the last MariaDB version.** From the clone's commit (`97b48b5cf`, version 11) to `3390247e0`: the page's check of LogicalDOC's tables asked for nothing by hand, the build took 5m03s, and the first start applied one migration (version 12), ran no seed and logged no error. The owner's server needs the same, and no more.
    - **Step 4, the move, on `efdf39f71`.** The new version built in 4m21s while the old stack still ran, the old app healthy throughout. `Move complete.` came in 14 s for the whole command, PostgreSQL's image pulled and both databases started within it: 80 tables made, 78 copied, 5,499 rows, 40 sequences, 3 identity counters, 37 foreign keys, rows and checksums matching in 78 of 78; `ld_uniquetag.ld_int1` alone on the source only; no NUL, so no `--replace-nul`. The move's container peaked at 161 MiB (`docker stats`, in a run too short for more than five samples). Its `Source` line read `time zone CST (the server's, -06:00)` where `.env` has `TZ=America/Chicago`: the same zone, by MariaDB's short name for it, which the Moving page now says.
    - **Step 5, both databases.** The eleven tables' counts, the doc # counter and `storage.installId` were the same; each of the 78 tables had the same rows on both sides, counted by one query a side; and the 7,349 values of the 144 `datetime` and `timestamp` columns were the same to the microsecond, each sum worked out by its own database.
    - **Step 5, the start.** Healthy in 20 s. The start-up tool named the conf volume's MariaDB, set Healdan's three keys, and found PostgreSQL 18 with the role's 50 s lock timeout and LogicalDOC's tables whole. Flyway applied nothing at version 13, no seed ran, and `dms.log` had no `ERROR` in 34 minutes. Backends 0 and 1 passed their checks and the S3 one failed, as meant. Inside the container `/login.jsp` answered 200 and `/app/` 302.
    - **Step 5, through the app, before and after.** With `admin`'s password reset on the clone alone, on the owner's word, the same reads were made on the old version and on the new, and kept on the clone: 13 documents (the newest, the oldest, linked ones, emails, some on each backend) with their versions, history, links and tags; the lists; eight searches by words of those documents' names; and six files. All 237 answers were the same byte for byte, 136 history dates with their milliseconds among them, read in the server's own zone. The six saved-search folders in matters listed exactly what their rules give when worked out in SQL (29, 8, 2, 1, 1 and 0 documents).
    - **Step 5, PostgreSQL's log,** read after LogicalDOC's scheduled tasks ran, 30 minutes after the start: 36 `ERROR` lines, every one the list's (`StatsCollector`'s twelve missing tables and their 24 retries), none unexpected, no `FATAL`.
    - **What this data couldn't show.** No document, folder or tag there has an accented letter, so that search wasn't tried. `ld_tag`, `ld_foldertag`, `ld_recipient` and `ld_note` are empty there too, so no real row of them moved; bookmarks, links, sign-in identities, saved searches and held copies did (8, 2, 3, 8 and 165 rows). Bookmarks and sign-in identities are other people's, which `admin` doesn't see in the app, so they were compared in the database only.
    - **How long the server is down, by the Moving page as written:** from the old stack's `down` to the new version's start, which is the build (4m21s), the move (14 s) and the start (20 s): about five minutes. Building before the `down`, which worked here, would leave under a minute. The page's order is unchanged, for the owner to rule. Its build doesn't pull Tomcat's image as Upgrading does, so Java stayed 25.0.5, with the Base64 option on.
    - **Step 6.** Whole CI runs with both readings of PostgreSQL's log clean: on arm64 four in a row (`800aac791`, `86a8448df`, `da317439e`, `efdf39f71`); on amd64 `800aac791`, then `86a8448df`, which didn't reach its second reading, then two in a row. Fewer than five on either, so `check_db_log` still prints and passes, unchanged.
    - **The clone is left** with its app stopped, PostgreSQL up, MariaDB's volume as it was, this pull request's head checked out, and `admin`'s password the rehearsal's.
52. **Task 23, Step 7, 2026-11-20, on the owner's word.** The owner read the rehearsal's report and said to proceed. `mariadb-final` is an annotated tag on `3390247e0`, pushed. It isn't under the `Release tags` ruleset (`helper-v*`, `v*`), so only care keeps it where it is. PR B was marked ready and merged with a merge commit (`040b9ceb9`), whose first parent is the tagged commit. Step 6's five clean runs hadn't been reached, four on arm64 and two on amd64, and the owner chose to merge and tighten after: `check_db_log` still prints and passes on `master`, whose own runs count towards the five, and a pull request of its own then makes it fail on an unexpected line. `docker/smoke-test.sh`'s comment above the function still says that change comes before this pull request is marked ready; the pull request that changes the line corrects it. The Moving page's order, the old stack down and then the build, is unchanged: the owner hasn't ruled on building first (the fifth point for the owner, below).
53. **Task 25, 2026-11-20: the owner's server moved, and the owner's walkthrough passed.** Done on the owner's word that morning, with nobody signed in and nothing checked out.
    - **Step 1.** The page's backup, the app stopped for 14 s. `mariadb-final` built in 4m59s: one migration, to version 12, no seed, no error, all three storage backends passing. Nothing by hand.
    - **Step 2, by the page as written.** The old stack down; the new version built in 4m23s; `Move complete.` in 16 s: 78 tables, 5,466 rows, 40 sequences, 3 identity counters, 37 foreign keys, rows and checksums matching in 78 of 78, `ld_uniquetag.ld_int1` alone on the source only, no NUL, and `time zone CST (the server's, -06:00)` where `.env` has `TZ=America/Chicago`. Compared outside the move while both databases were up: every table's rows, the doc # counter, `storage.installId`, and the 7,248 values of the 144 date columns, all the same.
    - **Step 3, the start.** Healthy in 21 s, and down for 5m55s in all, from the old stack's `down`. The start-up tool's lines were the page's; Flyway applied nothing at version 13; no seed ran; all three backends passed, the S3 one with its marker and key.
    - **Step 4, the backup job.** The server's nightly script writes `pg_dump`'s `logicaldoc-db.dump` and checks it with `pg_restore --list`. Run once, its dump was restored into a throwaway PostgreSQL with no network: the same rows in every one of its 81 tables as the live database, owned by the app's role. The MariaDB script and its restore notes are kept beside the new ones.
    - **The walkthrough.** The owner's nine rows passed. The tenth: PostgreSQL's log held no `ERROR`, `FATAL` or `WARNING` line a quarter of an hour after the last start, before `StatsCollector`'s expected lines, which come at 30 minutes; `dms.log` held two `ERROR` lines, both LogicalDOC's own refusal of a folder copied into the folder it is already in (`FolderServiceImpl.copyFolder`, "Not Allowed"), which it logs at error on any database. An avatar over 4,000 characters is in `ld_user`, the widened column's first.
    - **Left on the server** until the owner removes them: MariaDB's volume, which is the way back, the page's backup in the home folder, and the move's report.

**Measured, 2026-11-18,** for the Expected Costs table above. Task 4, run locally, the three suites at once on a shared machine: `healdan-core`'s 1,260 tests in 1:02 h, `healdan-api`'s 617 in 39:40 and `healdan-storage`'s 474 in 21:49. Like for like, five `healdan-core` classes (63 tests) took 4:07 on HSQLDB at `643975b21` and 4:08 on PostgreSQL. CI, over three runs each side (`master` on HSQLDB, runs 44324399335, 44418322300 and 44419255933; PostgreSQL, 44450184916, 44458784245 and 44461247011): the `build` workflow took 16.2 to 17.0 minutes before and 20.2 to 20.7 after, its longest `Tests` job 950 to 995 s and 1,182 to 1,200 s, the eight shards' Test steps together 6,455 to 6,595 s and 6,976 to 7,784 s, and the 238 Healdan classes in all six runs 5,682 to 5,786 s and 6,137 to 6,863 s, 0.3 s a test more. The image workflow didn't change. Locally the two databases were level, as the five classes show, which is why the build didn't see it: the cost is in the 40 connections a test opens and the 2,650 statements it sends, and shows on a runner.

**Measured, 2026-11-19,** for the follow-up, #278: a template database for each JVM, one SCRAM iteration for the test server's password, and CI's tests reaching the server at its container's address. On six runners (`pgbench`, one client), a statement's round trip took 46 µs at the container's address and 89 µs through Docker's proxy for the published port; a new connection took 2.3 ms with the password hashed once and 5.1 ms with PostgreSQL's 4,096 iterations, and 0.5 ms more through the proxy. For a test's 38 connections and about 1,800 statements, that is 0.11 s for the iterations and 0.09 s for the proxy. The template took 0.11 s off a test's set-up locally, and 850 of its statements. One CI run with the template and the one iteration, half its shards at the container's address (44501501045): the workflow 18.6 minutes, the longest `Tests` job 1,062 s, the Test steps together 6,477 s and Healdan's classes 5,737 s, 2.38 s a test. Its runners were faster than the three PostgreSQL runs' (their Maven install took 34.8 s on average, against 37.4 to 46.5 s), so that run flatters the change: the runners differ by more from run to run than the change is worth, and the benchmark is the figure to trust. Not done: the seeds in the template, another 0.4 s a test locally, which would change the ids and settings a test sees.

**Measured, 2026-11-19,** for the Expected Costs table above, PR B's local figures; CI's are in the pull request's description. The smoke test on PostgreSQL, with the image prebuilt, took 548 to 635 s, its browser tests 6.3 to 7.9 minutes (the trial: 8.0). `test-move.sh` ran its 76 checks in about 35 s (83 after the whole-branch review's two checks, Decision Log 45, also about 35 s); `test-init.sh` ran 91 checks, `test-image.sh` 36 and `test-overlays.sh` 14. One of four local smoke runs failed three WebKit browser tests, two focus checks and a sign-in page slow to load, with another session's build running beside it; the same tests passed in the other three, and PR A's build saw the same local flake. On the build's last commit, `800aac791`, three more local runs each failed one WebKit test, a different one each time, with every other check passing and no error in the app's log or PostgreSQL's; `master`'s own smoke test, on MariaDB, run on the same machine straight after, passed all 166. In CI the whole smoke test passed at `800aac791` on both architectures, and at `86a8448df` it passed on arm64 and failed one WebKit test on amd64 (`keyboard.spec.ts:289`: a folder's rows still "Loading…" after 30 s, with no error in either log). Every browser failure so far is in WebKit, which runs after Chromium against the same stack; the cause isn't known, and a separate session is on it. CI's times, one run at `800aac791` against `master`'s newest: the `Docker image` workflow 24m40s against 22m08s; in `Test (amd64)` the smoke test 758 s against 665 s on MariaDB, the tools step 360 s against 291 s, the Move test 71 s, the image build 59 s against 106 s; `Test (arm64)` 1,299 s against 1,322 s; `Build LogicalDOC (Maven)` 18m14s against 20m39s, its shards' Test steps 7,154 s in all, the longest 989 s. So the time to all green grew by about two and a half minutes in that run, all in `Test (amd64)`.

**Measured, 2026-11-20 (UTC),** after the merge of #278, and what became of the browser-test failures. CI on `da317439e` passed every check: the `Docker image` workflow took 24m50s (`Test (amd64)` 1,460 s, of which its tools step 367 s, its smoke test 774 s and its Move test 75 s; `Test (arm64)` 1,483 s, its smoke test 906 s), and `Build LogicalDOC (Maven)` 17m53s, its shards 14m08s to 17m26s. `master`'s own run on `3390247e0` took 22m46s and 19m40s. PostgreSQL's log held no `ERROR` line in any reading on either architecture in the three runs so far (at `86a8448df` amd64's second reading wasn't reached). A separate session found the cause of the local browser-test failures, and it isn't PostgreSQL: with `WEB_NETWORK=bridge`, `test-web.sh`'s proxy reached the app through `host.docker.internal`, and Docker Desktop's network answers no new connection for 60 seconds from a source port whose last connection the server was first to close. About one connection in 700 then opened 11 to 67 seconds late, on MariaDB as on PostgreSQL, and WebKit, which sends every save on a new connection, sometimes waited on one past a test's 30 seconds. Healdan-Project/healdan#281 has the measurements and the fix, which sends the proxy straight to the app's container; with it applied, the smoke test passed whole here on `a907d14c0` (Decision Log 50). CI doesn't take that route (its browser container uses the host's network), so its one failure, at `86a8448df` on amd64, isn't explained by it: one failed job in six on PostgreSQL so far, the other five passing every browser test.

**A follow-up outside this plan, found in Task 5, 2026-11-18.** `healdan-web/pom.xml` gives `maven-resources-plugin` no version, so every Maven run warns. It did before PR A.

### Open Points for PR B

Found in PR A's build. PR A's review fixed three of them in PR A after all (Decision Log 25), and PR B settled the other three (Decision Log 26, 27 and 40), so all six are struck through.

- ~~**pgjdbc puts the server's detail into every `PSQLException`'s message** (`logServerErrorDetail`, on by default), found in Task 7. For a check, NOT NULL or length failure the detail is "Failing row contains (...)", and for a duplicate "Key (...)=(...) already exists", with the row's values: file names, which may be an email's subject, and comments. Whatever logs such an exception at info or above then logs them, `ProblemMapper`'s "API request failed" and LogicalDOC's own `log.error` among them, against Healdan's rule for what its log may hold. MariaDB's messages don't quote rows. `logServerErrorDetail=false` on the stack's JDBC URL would stop it at the driver, in the PR B task that seeds the stack's `context.properties`; PostgreSQL's own server log is separate.~~ Done in PR B: the stack's URL ends `?logServerErrorDetail=false` (Decision Log 26).
- ~~**Text properties are stored as given,** so a legacy doc # saved with a trailing space matches a stripped lookup on MariaDB, but not on PostgreSQL once the data has moved: strip it on write, or add a line to Known Limitations.~~ Done in PR A: the lookup compares the stored number trimmed (Decision Log 25).
- ~~**Two more comparisons of text that may arrive untrimmed,** which the recon's §4.3 lists and neither the spec's §7.4 nor this plan treats: `StoredRows.liveChild` (a folder's child by name) and `SearchFolderService.appendValue`. On PostgreSQL a name with a trailing space is another name.~~ Done in PR B: `appendValue` compares the stored value trimmed, and `liveChild` compares as LogicalDOC's own lookup does (Decision Log 27).
- ~~**A part-way answer can claim more than is known** (the whole-branch review). LogicalDOC's `checkin` commits the document at the new version and then stores the file, recovering only from an `IOException`. A `RuntimeException` from the store that isn't an outage, such as a JDBC failure inside `HealdanStorage.write` (a lock timeout), leaves the document at the new version with no readable file, and the answer, through `confirmStoredAfter` (22), says "its content is the file sent". Before PR A it was the plain `500`. "Send the save again" is still right and repairs it. A `lock_timeout` on PostgreSQL makes it likelier: either check `Store.exists` before that sentence, or let the original failure through when the file isn't there.~~ Done in PR A: `confirmStored` looks for the file first (Decision Log 25).
- ~~**`HealdanMigrator.RECOVERY` is MariaDB's wording** (`where success = 0`), which the spec's §6.5 changes at the switch.~~ Done in PR A: PostgreSQL has `RECOVERY_POSTGRESQL` (Decision Log 25).
- ~~**An install set up outside Docker on PostgreSQL now gets past the migrator** before the switch's checks exist: the spec's §13.1 puts the migrator in PR A, and the stack itself can't be pointed at PostgreSQL.~~ Done in PR B: the checks are the start-up tool's, and the Developer Guide's Building from Source says what an install outside Docker sets by hand (Decision Log 40).

### Open Points for the Owner Before the Merge

Left by the whole-branch review (Decision Log 47). None is ranked.

PR B merged on 2026-11-20 (Decision Log 52), which settles the second point: `mariadb-final` is on `3390247e0`. The first stands for as long as the move command does, and the third, fourth and fifth are still the owner's to rule.

1. **What the move does about a change to CE's tables.** Healdan's own later migrations no longer concern the move (Decision Log 42). An upstream sync that adds a table or a column to CE's generic scripts gives the target something the version-12 source lacks: the move refuses, and the Move test, a step of the required `Test (amd64)`, fails, for as long as the command stays. If the sync also changes the `.mariadb` script, the move refuses earlier, at its check of the source, with a message that blames the source's version and points at `mariadb-final`'s Upgrading page, which then isn't true; no code changes for that now. A sync that drops or renames a column is listed under "Only the source has these", and the test's check of the report's last lines fails. A change that only adds rows to CE's scripts trips nothing, and doesn't reach a moved install. Until the owner rules, such a sync is held. The options:
   - **Nothing: the failing test as a tripwire.** Costs nothing now. The first sync that adds a column blocks, with every other upstream change in it, security fixes included, until one of the others is built under pressure.
   - **C1, CE's side tolerated:** a target-only column that's nullable or has a default is listed and left, a target-only table left as the scripts made it. New branches in the plan, the emptying and the report, in the tool where every branch is a way to be wrong; a moved database matches one after Upgrading's `add column`, but not after any backfill, so the page would send movers to Upgrading's list. Medium risk.
   - **C2, CE's side frozen:** the move makes CE's tables from copies of the generic scripts as they were at `mariadb-final`, kept beside the fixture, so a moved database is an ordinary one of that version, and the start-up check and Upgrading's SQL take over. About 1,000 lines of frozen LGPL scripts, and the Move test applies Upgrading's SQL before its app start. Low risk; the first start after a move becomes two steps once a sync has landed.
   - **D, shorten the question:** hold schema changes and the weekly sync until the owner's server has moved (Task 25), then let the first change that needs it bring Task 26 forward, with a tag keeping the command. No code; a sync held for weeks is a security fix held for weeks, and going back and moving again is served by the tag, not `master`.
2. **`master` must not move before this merges.** `mariadb-final` is to be tagged on the commit this merges onto, `3390247e0` today: Healdan-Project/healdan#278 and #279 merged while this was a draft and were merged in here (Decision Log 48 and 50). #278 changed no table. #279 brought a MariaDB migration, `V12__admin.sql`, which cost this pull request a renumbered migration, the move's two versions, a fixture made again from `3390247e0` and every check run again (Decision Log 50). A merge to `master` meanwhile moves the tag's commit; one that brings a Healdan migration costs that again, and one that touches CE's tables also needs MariaDB-era Upgrading SQL this branch has deleted. So the weekly upstream sync and other merges, above all any with a migration, are best held until the tag exists.
3. **A warning for a database whose character type is plain `C` or `POSIX`.** There `lower()` folds only ASCII, so a search that ignores case misses accented letters (spec §5.5). Task 13 made `DbChecks.otherLocale("c", "C", null)` give no warning on purpose, as such a database sorts by code point; the review suggests a warning that names the consequence. Left as planned.
4. **`log_error_verbosity=terse` on the `db` service.** It would keep row values out of PostgreSQL's own log too, which keeps the server's `DETAIL` and the failing statement, at a cost in debugging. Today the Install manual's Troubleshooting says to read the log before posting lines from it.
5. **Whether the Moving page should build the new version before the old stack goes down.** The page takes the old stack down first, so an install is down for the build: 4m21s on the owner's clone and 4m23s on the owner's server, of 5m55s down in all. The rehearsal ran the same build with the old stack still up, the old app healthy throughout (Decision Log 51), which would leave the move and the start, under a minute. Left as written.
