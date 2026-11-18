# PostgreSQL as the First-Class Database: Recon

- **Date:** 2026-11-03, re-checked on 2026-11-18.
- **Tracking issue:** [Healdan-Project/healdan#110](https://github.com/Healdan-Project/healdan/issues/110), and the
  roadmap's backlog row "PostgreSQL as the first-class database" ([`docs/ROADMAP.md`](../ROADMAP.md) §5, principle 9).
- **What this is.** Facts for the item's design session with the owner, gathered against `master` at `56e9815` and
  checked again against `master` at `8569c90`, 584 commits later. File and line references are the re-check's. Nothing
  here is designed or built, and no Java, SQL, POM or Docker file changed. §1.1 lists what the re-check changed, and
  §6 the questions the session has to answer.
- **What was run on 2026-11-03.** Everything ran in a cloud container, from scratch files outside the repository:
  - PostgreSQL 16.15 (Ubuntu 24.04's package) and 18.6 (the official `postgres:18` image), through pgjdbc 42.7.7, the
    driver the WAR ships;
  - CE's DbInit scripts through HSQLDB 2.7.1's `SqlFile`, the class upstream's `DBInit` runs them with, and again
    through `psql`;
  - Healdan's Flyway scripts through Flyway 13.10.3, configured as `HealdanMigrator` configures it;
  - a small Hibernate 7.4.4.Final probe with one entity mapped the way CE maps its entities;
  - every native statement in Healdan's Java, through Spring's `JdbcTemplate` and a copy of CE's
    `NamedParameterStatement` (§4);
  - MariaDB 10.11.14 (Ubuntu's package; the stack runs 11.4) holding CE's `.mariadb` schema and Healdan's tables, and
    pgloader 3.6.10, for the data move.
- **What was run on 2026-11-18.** Everything ran in Docker on a Mac, from scratch files outside the repository:
  - PostgreSQL 18.4 (the `postgres:18` image already on the machine) and MariaDB 11.4.13, the stack's `mariadb:11.4`;
  - CE's DbInit scripts through `psql` and `mariadb`, and a comparison of the two schemas, column by column and table
    by table;
  - Healdan's eleven Flyway scripts, translated, through `psql`, each in a transaction, and the originals through
    `mariadb`;
  - a probe on the WAR's own jars: CE's `LocalSessionFactory` and entity mappings, Spring's
    `HibernateTransactionManager` as `context.xml` names it, and CE's real `queryForInt` (§2.5, §2.6);
  - the collation trial of §2.3 on 200,000 rows, the dump and restore of §5.3, and the identity counter of §5.8;
  - every native statement in `healdan-core`, `healdan-api` and `healdan-storage`, with Healdan's SQL in upstream
    modules (§4).
- **What wasn't.** Healdan itself has never run on PostgreSQL: no start of the application, Healdan test, smoke test
  or Docker image has run against it. On 2026-11-03 the full Maven build needed `maven.healdan.com`, which that
  container couldn't reach. The re-check didn't repeat PostgreSQL 16, `SqlFile` or the pgloader trial, and it didn't
  run Flyway on PostgreSQL, because `flyway-database-postgresql` wasn't cached and nothing was downloaded. Each
  finding says whether it was run or read from the code. A result with no date is from 2026-11-03, and the re-check
  found the code it rests on unchanged in substance.

## 1. Summary

1. **CE's generic scripts load on PostgreSQL without a single error,** on 16, 18.4 and 18.6: all 56 tables, 47
   indexes, 36 foreign keys, 31 sequences and 198 seed rows, the same as the `.mariadb` variant gives on MariaDB. But
   they're narrower than the MariaDB variant in 8 columns (an avatar wider than 4,000 characters doesn't fit), most of
   their dates keep fractions of a second where MariaDB's keep whole seconds, and they lack one column the MariaDB
   variant has (§2.3).
2. **The blockers are in how CE uses Hibernate, not the schema.** CE maps 54 Java `boolean` fields, in 18 mapped
   classes including every access control entry, onto `int` columns. PostgreSQL refuses every insert, update and
   comparison Hibernate makes on them, where MariaDB and HSQLDB convert silently. One Hibernate setting,
   `hibernate.type.preferred_boolean_jdbc_type=INTEGER`, fixes every case tried, with CE's own mappings, and there are
   routes to it that need no upstream edit (§2.5). That isn't enough: on PostgreSQL, CE's `queryForInt` and
   `queryForLong` over an `int` value fail their first read and retry, and Hibernate's PostgreSQL dialect then rolls
   the whole transaction back at commit, without an error. Every save of an existing user does such a read, so every
   edit of a user is lost. The re-check ran this (§2.6).
3. **Healdan's Flyway scripts now need five kinds of change** (the table options, `datetime`, inline indexes, one
   `modify` and one `auto_increment`). Translated, all eleven apply on 18.4. Flyway needs its
   `flyway-database-postgresql` module, which Healdan doesn't ship, and `HealdanMigrator` refuses PostgreSQL by name
   (§3).
4. **Healdan's own SQL in Java is nearly all portable,** and what's been written since the first pass was written with
   PostgreSQL in mind (§4.8). All 466 distinct native statements the re-check sent, with the Java types the code
   binds, passed on PostgreSQL. What fails is the recovery SQL in `HealdanMigrator`'s message and one HQL query, on
   the boolean issue. Two inserts rely on carrying on after a duplicate key, which PostgreSQL doesn't allow inside a
   transaction: one path reaches `PreviewJobs.add` inside a document save, and the re-check reproduced that race
   (§4.2). One rare lock cycle in `healdan-storage` relies on InnoDB's lock timeout, and PostgreSQL has none by
   default (§4.6). A NUL in bound text fails (§4.7). The rest are behaviour differences: trailing spaces, the order of
   NULLs, `lower()` and sorting under the database's locale, and timestamp precision (§4.3).
5. **The container is MariaDB throughout:** `DockerInit` and `ConfigPlan` hard-code its driver, URL, port, dialect and
   the SQLSTATE they read a missing table by, `DockerInit`'s check of CE's tables at every start calls `database()`,
   which PostgreSQL lacks, and `compose.yaml`, the backup and restore steps, seven test scripts and twelve docs pages
   name MariaDB (§5).
6. **Moving an install's data across worked with pgloader** on 2026-11-03, into a schema Healdan's own scripts made,
   after four fix-ups: a column, the narrow columns, the sequences and Flyway's history. pgloader making its own
   schema doesn't work for Healdan as it stands. A move now also has to carry `hd_trash`'s identity counter and the
   install id that every storage backend's marker names (§5.8).

### 1.1 What the Re-Check Changed

- **Confirmed by running what had only been read:** the typed reads' lost transaction (§2.6), the `PreviewJobs` race
  (§4.2), and which collations keep `ld_path`'s index (§2.3).
- **Unchanged:** CE's schema on PostgreSQL (§2.2, §2.3), the 54 boolean fields (§2.5), and the versions tested:
  Hibernate 7.4.4, Spring 6.2.8, pgjdbc 42.7.7, Flyway 13.10.3 and HSQLDB 2.7.1.
- **New since the first pass:**
  - migrations V8 to V11, with a fifth kind of change (§3);
  - `DockerInit`'s check of CE's tables, which fails on `database()` (§5.1);
  - `healdan-storage`, with a lock cycle only InnoDB's timeout ends (§4.6) and files tied to database ids (§5.8);
  - the Trash, whose `hd_trash` key is an identity column (§3.2, §5.8);
  - three more callers of `BasicPlane`, one of them with a contract with the helper (§5.6);
  - more test scripts and docs that assume MariaDB (§5.4, §5.7);
  - one upstream query PostgreSQL refuses, which nothing reaches today (§2.4).
- **Open questions:** Q7 and Q12 are partly answered, the premises of Q3, Q5, Q6, Q8, Q10 and Q11 have moved, and Q14
  and Q15 are new (§6).

## 2. CE's Schema on PostgreSQL

### 2.1 How the Scripts Are Chosen

- **Three plugins declare a `DbInit` extension,** and `DockerInit` runs them in ascending position: core
  (`sql/logicaldoc-core.sql`, position 1, `logicaldoc-core/src/main/resources/plugin.xml:400`), webservice
  (`sql/logicaldoc-webservice.sql`, 20, `logicaldoc-webservice/src/main/resources/plugin.xml:102`) and dropbox
  (`sql/logicaldoc-dropbox.sql`, 1500, `logicaldoc-dropbox/src/main/resources/plugin.xml:96`). Healdan declares none.
- **A variant wins when it exists.** Upstream's `PluginDbInit.init` (`logicaldoc-core/.../core/dbinit/PluginDbInit.java:70-75`)
  runs `<sqlFile>.<dbms>` if the classpath has it, and the generic `<sqlFile>` otherwise. The `dbms` isn't
  `jdbc.dbms` but `DBInit`'s own field, lower-cased (`logicaldoc-util/.../util/dbinit/DBInit.java:29`, `:204-209`).
  The setup wizard, `PluginDbInit`'s only caller, sets it to the engine the admin picked
  (`logicaldoc-webapp/.../web/service/SetupServiceImpl.java:242`, `:260`), whose key is `PostgreSQL`
  (`logicaldoc-gui/.../setup/client/Setup.java:61`), so the wizard looks for `.postgresql` variants. `DockerInit`'s
  `DbInitPlan.resolve` (`docker/tools/src/DbInitPlan.java`) does the same with the `dbms` fixed to `mariadb`
  (`DockerInit.java:51`, `:370`).
- **There's no PostgreSQL variant,** neither `.postgresql` nor `.postgres`. On PostgreSQL every plugin gets its
  generic script. Core's is the one Healdan's HSQLDB tests load, with the test `data.sql`
  (`AbstractHealdanTestCase.getDatabaseScripts`). Dropbox has only the generic one anyway.
- **A failing statement doesn't stop the run.** `DBInit.execute(String)`
  (`logicaldoc-util/.../util/dbinit/DBInit.java`) hands each file to HSQLDB's `SqlFile` with
  `setContinueOnError(true)` on an autocommit connection, so a failure is only logged. `DockerInit.createSchema` then
  checks that every table and column the scripts create exists, and stops if one is missing (§5.1).

### 2.2 What Failed

**Nothing.** Run in that order, through `SqlFile` as `DBInit` runs them and again through `psql` with
`ON_ERROR_STOP=0`, no statement failed on PostgreSQL 16.15 or 18.6. On 2026-11-18, through `psql` on 18.4, none failed
either and the counts below came out the same, with the 14 quota-threshold columns an upstream sync has since added to
`ld_tenant` on both sides.

| Object | In the scripts | On PostgreSQL | On MariaDB, from the `.mariadb` variants |
|---|---|---|---|
| Tables | 55 + 1 (webservice) | 56 | 56 |
| Indexes, primary keys aside | 47 | 47 | |
| Foreign keys | 36 | 36 | |
| Sequences | 30 + 1 | 31 | 31 |
| Seed rows | | 198 | 198, table by table |

The generic script is portable SQL: `bigint`, `int`, `varchar(n)` up to `varchar(100000)`, `float` (double
precision on PostgreSQL), `timestamp`, `create sequence … start with 100 INCREMENT BY 50`, plain `create index` and
`alter table … add constraint … foreign key`.

### 2.3 Where the Generic Script Differs from the MariaDB Variant

Comparing the two schemas column by column (`information_schema.columns` on each side; 949 columns against MariaDB's
950 on 2026-11-18, the difference being `ld_int1`):

| Table and column | Generic, so PostgreSQL | `.mariadb` |
|---|---|---|
| `ld_user.ld_avatar` | `varchar(4000)` | `mediumtext` |
| `ld_systemmessage.ld_messagetext` | `varchar(4000)` | `mediumtext` |
| `ld_filler.ld_automation`, `ld_automationbefore`; `ld_folder.ld_tile`; `ld_menu.ld_automation` | `varchar(10000)` | `mediumtext` |
| `ld_generic.ld_text1`, `ld_legal.ld_content` | `varchar(100000)` | `mediumtext` |
| `ld_history.ld_date`, `ld_user_history.ld_date`, `ld_folder_history.ld_date`, `ld_webservicecall.ld_date` | `timestamp` (microseconds) | `datetime(3)` (milliseconds) |
| Every other date column, 96 in core and 2 in webservice, among them every `ld_creation` and `ld_lastmodified`, `ld_user.ld_lastlogin` and `ld_version.ld_versiondate` | `timestamp` (microseconds) | `datetime` (whole seconds) |
| `ld_patch.ld_date` | `timestamp` | `timestamp` (MariaDB's, which is time-zone converted) |
| `ld_uniquetag.ld_int1` | absent | `bigint` (no Java code reads it) |
| `ld_document.ld_indexed`, `ld_embedded`; the same in `ld_version`; `ld_foldertag.ld_folderid` | nullable | `not null` |

- **The narrow columns are a real limit.** MariaDB's `mediumtext` holds 16 MB, and PostgreSQL enforces `varchar(n)`.
  In the data-move trial (§5.8) a 6,000-character avatar failed with `22001: value too long for type character
  varying(4000)`. An avatar is a base64 image, and a system message can be a whole email. The smallest fix is `text`
  for the 8 columns in the first four rows, which PostgreSQL stores without a limit short of 1 GB, as `alter table …
  alter column … type text`. That's a change to the structure of CE's tables, which Healdan never makes today
  (question Q3).
- **Finer dates.** CE's entities map `ld_creation` and `ld_lastmodified` as `DATETIME(3)` and set them with
  milliseconds (`PersistentObject.java:46`, `:49`), which MariaDB's `datetime` drops and PostgreSQL's `timestamp`
  keeps. That removes an open MariaDB defect, the second-level cache's copy of a row never matching the row (the
  [capability map](logicaldoc-ce-capability-map.md), §6). It also changes ties: Healdan's `VersionResource.java:117`
  relies on a version's date having one-second precision (§4.3).
- **The rest don't matter for PostgreSQL:** a column nothing reads, and nullability the Java code never relies on (it
  always writes a value). The missing column does matter to a data move, as `ld_int1` showed (§5.8).
- **Text.** The `.mariadb` tables are `utf8` (3 bytes a character) with `utf8_bin`. A PostgreSQL database in UTF-8
  stores any character but NUL (U+0000, which no PostgreSQL text type holds), emoji included (see §5.6 for Healdan's
  workaround). Equality and `LIKE` are case-sensitive on both. Sorting isn't the same: `utf8_bin` sorts by code
  point, as PostgreSQL's `C` collation does, while the `postgres` image creates its databases in `en_US.utf8`, which
  sorts as a dictionary does (PostgreSQL's rows checked on 18.6 and again on 18.4):

  | Collation | `order by` of apple, Banana, banana, Zebra, éclair | `lower('École ÆØÅ')` |
  |---|---|---|
  | MariaDB `utf8_bin` | Banana Zebra apple banana éclair | école æøå |
  | PostgreSQL `C` | Banana Zebra apple banana éclair | École ÆØÅ (only ASCII is lowered) |
  | PostgreSQL `C.UTF-8` (glibc), and 18's built-in `pg_c_utf8` | Banana Zebra apple banana éclair | école æøå |
  | PostgreSQL `en_US.utf8` (the image's default) | apple banana Banana éclair Zebra | école æøå |

- **The collation also decides an index.** CE finds a folder's subtree with `ld_path like '<path>/%'`
  (`HibernateFolderDAO.java:1710`, `:1875`, `:2022`, `:2045`, `:2082`, `:2096`, `FolderSearch.java:386`,
  `FolderServiceImpl.java:271`, `:289`) on the plain index `LD_FLD_PATH` (`logicaldoc-core.sql:392`), which MariaDB
  uses for it. PostgreSQL serves a `LIKE` prefix from a plain B-tree index only under a collation that sorts as `C`
  does. On 18.4, with 200,000 folders, `C` and the built-in provider's `pg_c_utf8` used the index for such a query,
  while glibc's `C.UTF-8` and `en_US.utf8` scanned the table, and would need a `varchar_pattern_ops` index, another
  change to CE's tables. ICU's collations weren't run; PostgreSQL's source treats them as glibc's. So glibc's
  `C.UTF-8` and `pg_c_utf8`, alike in the table above, differ here.

### 2.4 PostgreSQL in CE's Java

- **The dialect.** `logicaldoc-webapp/src/main/resources/context.xml` passes `hibernate.dialect` from
  `context.properties`, and an empty value lets Hibernate detect it: the probe, with none set, got `PostgreSQLDialect`. The
  setup wizard writes `org.hibernate.dialect.PostgreSQLDialect` (`logicaldoc-gui/.../setup/client/Setup.java:222-224`),
  and the container seeds `org.hibernate.dialect.MariaDBDialect` (`docker/tools/src/ConfigPlan.java:71`).
- **`jdbc.dbms` is spelt three ways.** `PersistentObjectDAO.isPostgreSQL()` tests `"postgresql"`
  (`logicaldoc-core/.../core/PersistentObjectDAO.java:700-702`), and so does `StatsCollector` (`:477`). The wizard
  writes `"postgres"` (`logicaldoc-webapp/.../web/service/SetupServiceImpl.java:108-111`), so on an install it made,
  `isPostgreSQL()` is false. Its scripts don't follow that spelling: `PluginDbInit` is given the engine's key, so the
  wizard looks for `.postgresql` variants whatever `jdbc.dbms` says (§2.1). `HibernateDocumentDAO.deleteOrphaned`
  tests `contains("postgre")` (`:1189`), which takes either.
- **MariaDB isn't recognised either.** `isMariaDB()` tests `"maria"` (`PersistentObjectDAO.java:696-698`), but the
  container writes `"mariadb"`, so on Healdan today `isMariaDB()` and `isMySQL()` are both false.
- **It doesn't matter much, because almost nothing asks.** Of those methods only `isOracle()` is ever called (14
  places, in `HibernateFolderDAO`, `Search`, `SearchTool`, `StatsCollector` and four webapp classes).
  `isPostgreSQL()`, `isMySQL()`, `isMariaDB()` and `isHsql()` have no callers, but for `isMySQL()` asking
  `isMariaDB()`. So CE takes the same generic branches on MariaDB and on PostgreSQL already. The two string tests are
  harmless: `deleteOrphaned` builds `ld_id || '.' || ld_customid` for PostgreSQL, but its `CONCAT(ld_id, CONCAT('.',
  ld_customid))` works there too (checked); and `StatsCollector` measures the database with `pg_database_size` only
  when `jdbc.dbms` is `postgresql`, falling back to the database folder otherwise, as it does on MariaDB today.
- **Ids come from sequences.** `PersistentObject`'s id is `@GeneratedValue(strategy = AUTO)`, which Hibernate 7
  serves from `<table>_SEQ` with steps of 50 and, from `hibernate.id.optimizer=hilo`, the hilo optimiser. PostgreSQL
  stores the unquoted `ld_user_SEQ` as `ld_user_seq`, and Hibernate's `nextval('ld_user_SEQ')` resolves to it: the
  probe's inserts got their ids that way on 16 and 18.
- **`ld_sequence`,** CE's table of named counters, is ordinary rows that `HibernateSequenceDAO`
  (`logicaldoc-core/.../core/sequence/HibernateSequenceDAO.java`) reads and writes through Hibernate under a JVM
  lock. It has no database-specific SQL.
- **CE has met PostgreSQL before,** and its workaround now costs a transaction.
  `HibernatePersistentObjectDAO.queryForLongFromIntegerOrBigDecimal` (`:556-568`) exists because "with postgresql
  the returned value could be an Integer or a BigDecimal": `queryForLong` reads again as `Integer`, then `BigDecimal`,
  when a read as `Long` is refused. On Hibernate 7 that retry hides a rollback (§2.6).
- **CE's own native SQL wasn't audited.** It has about 300 calls to `jdbcUpdate`, `queryFor…` and `query` in core,
  webapp, webservice, webdav and cmis. A grep for MariaDB-only constructs (`ifnull`, `group_concat`, `date_format`,
  `limit a, b`, `insert ignore`, `on duplicate key` and the like) found none, and a grep for Java booleans bound as
  parameters found none either. CE writes its SQL to run on HSQLDB, Oracle and SQL Server as well, which keeps it
  mostly portable, but only a run on PostgreSQL would show the rest (question Q9). Two exceptions are known:
  - The [capability map](logicaldoc-ce-capability-map.md) records one a grep can't find (§6):
    `HibernateDocumentDAO.findDocIdByTag` (`:246-254`) writes the tag into its SQL through
    `SqlUtil.doubleQuotesAndBackslashes`, and PostgreSQL doesn't take a backslash in a literal as an escape, so the
    admins' tag search misses tags that contain one.
  - An upstream sync has since brought `TemplateServiceImpl.importFromFile` (`:665`), which selects `lower(A.ld_id)`
    from a `bigint` column. MariaDB converts it, and PostgreSQL refuses it: `function lower(bigint) does not exist`
    (run). `importTemplate` calls `HealdanImports.refuse()` first (`TemplateServiceImpl.java:627`), so nothing reaches
    it today.

### 2.5 Booleans: the First Blocker

CE maps a Java `boolean` straight onto an `int not null` column, with no converter, for example `User.enabled` on
`ld_enabled` (`logicaldoc-core/.../core/security/user/User.java:136-137`) and `AbstractDocument.immutable` on
`ld_immutable` (`.../core/document/AbstractDocument.java:80-81`). There are 54 such fields in 18 mapped classes
(entities, mapped superclasses and embeddables): `ExtendedAccessControlEntry` (19, every permission), `User` and
`AbstractDocument` (5 each), `FolderAccessControlEntry` and `Attribute` (4), and 13 more. Nothing in CE changes how
Hibernate binds them: no converter, `TypeContributor` or setting (`LocalSessionFactory` only scans for entities).

Hibernate 7's PostgreSQL dialect binds a `boolean` as SQL `boolean`, and PostgreSQL has no implicit cast from it to
`integer`. The probe's entity, an id from `ld_hprobe_SEQ` and a `boolean` on an `int not null` column, gave:

| Operation | PostgreSQL 16 and 18, Hibernate's defaults | PostgreSQL, `preferred_boolean_jdbc_type=INTEGER` | MariaDB 10.11, defaults |
|---|---|---|---|
| `persist` | `column "ld_enabled" is of type integer but expression is of type boolean` | ok | ok |
| HQL `where enabled = true` | `operator does not exist: integer = boolean` | ok | ok |
| HQL `where enabled = :b`, a `Boolean` | `operator does not exist: integer = boolean` | ok | ok |
| HQL `where enabled = 1` | Hibernate: `Cannot compare left expression of type 'java.lang.Boolean' with right expression of type 'java.lang.Integer'` | ok | the same Hibernate error |
| HQL `update … set enabled = false` | `column "ld_enabled" is of type integer but expression is of type boolean` | ok | ok |

The same happens one level down: pgjdbc's `setBoolean` into an `int` column fails on PostgreSQL with `42804`, and
comparing one with `42883`; the MariaDB driver accepts both. So CE on PostgreSQL would fail at its first permission
check, user save or document save. With the fix below, a user save still loses its changes, for another reason (§2.6).

The re-check ran the table's queries on 18.4 and MariaDB 11.4.13 through CE's own `LocalSessionFactory` and its `User`
and `Document` mappings, with the same results. A user loads, because reading an `int` as a `boolean` works, and
saving one fails at the flush with `column "ld_enabled" is of type integer but expression is of type boolean`. With
the setting below, all of them pass.

- **The fix the probe proved** is Hibernate's `hibernate.type.preferred_boolean_jdbc_type=INTEGER`
  (`org.hibernate.cfg.MappingSettings.PREFERRED_BOOLEAN_JDBC_TYPE`, still in 7.4.4). Hibernate then binds booleans as
  integers and writes `1` and `0`, on PostgreSQL and on MariaDB alike, and on MariaDB it also lets HQL compare a
  boolean with `1`, which it refuses today.
- **Where it can be set.** `context.xml`'s `hibernateProperties` list only the keys it names, so a
  `context.properties` key doesn't reach Hibernate. But Hibernate's `Environment` reads a `hibernate.properties` at
  the classpath's root, and the first probe picked the setting up from one (`HHH000205: Loaded properties from
  resource hibernate.properties`). A `hibernate.properties` in `healdan-core`'s jar would need no upstream edit; one
  line in `context.xml` would be the upstream alternative. The re-check's probe passed the setting through the
  `hibernateProperties` of CE's `LocalSessionFactory`, as that line would. The jar's file hasn't run under CE's
  session factory.
- **Two more routes need no upstream edit** (read from the jar, not run). `Environment` also copies the JVM's system
  properties, so a `-Dhibernate.type.preferred_boolean_jdbc_type=INTEGER` in `docker/setenv.sh` sets it for the
  container only. And when the setting is unset, Hibernate asks the dialect's `getPreferredSqlTypeCodeForBoolean()`,
  which is public and not final: a Healdan subclass of `PostgreSQLDialect` returning `Types.INTEGER`, named by
  `hibernate.dialect` in `context.properties` (which `context.xml:90` passes through and `ConfigPlan` seeds), would
  apply on PostgreSQL only and leave MariaDB's binding (`BIT`) alone. It reaches only installs whose
  `hibernate.dialect` names it, though: the wizard writes the stock dialect, and an empty value detects it. The
  setting itself is `@Incubating` in 7.4.4, Hibernate's mark for an API that may change without a deprecation
  (question Q2).
- **HSQLDB doesn't catch it.** HSQLDB 2.7.1 accepts the boolean binding even with `sql.syntax_pgs=true`; only
  `sql.enforce_types=true` refused a literal comparison, and it still accepted the bound parameter (§5.5).
- **Healdan's own entities** (`Client`, `Matter`, `ApiToken`, `HoldDevice`, `SignInIdentity`, `SignInGroup`, and since
  the first pass `StorageBackend`, `StoreBackend` and `StorageEvent`) have no boolean fields. `StorageBackend`'s three
  flags are `int` fields with `boolean` accessors, for this reason (`StorageBackend.java:16-17`).

### 2.6 CE's Typed Reads Roll Back the Transaction

Run on 2026-11-18, on PostgreSQL 18.4 and MariaDB 11.4.13, with the WAR's jars (Hibernate 7.4.4, Spring 6.2.8, pgjdbc
42.7.7), CE's `LocalSessionFactory` and `User` mapping, `HibernateTransactionManager` and CE's real `queryForInt`,
with the second-level cache off. On 2026-11-03 this was only read from the code. How Hibernate gets there is still
read from its bytecode.

- **The read.** CE's `queryForInt` is `queryForLong` (`HibernatePersistentObjectDAO.java:522-530`), and `queryForLong`
  first asks for a `Long` (`:537-544`), through `rs.getObject(1, Long.class)` inside the session's `doReturningWork`
  (`:598-616`; upstream now calls `rs.getObject(1)` first, for dates, and still ends there). pgjdbc gives a `Long`
  only from a `bigint`. From an `int`, or a `numeric` such as PostgreSQL's `sum()` of a `bigint`, it throws
  `conversion to class java.lang.Long from int4 not supported`, which CE rethrows as its `PersistenceException`, an
  `SQLException`.
- **The rollback.** Hibernate's `JdbcCoordinatorImpl.coordinateWork` hands an `SQLException` from the work to
  `afterFailedStatementExecution`, which marks the transaction rollback-only when the dialect's `causesRollback` says
  so. `PostgreSQLDialect`'s always does; the base `Dialect`'s, which MariaDB's and HSQLDB's dialects keep, never does.
  `queryForLong` then catches the failure and reads again as `Integer` or `BigDecimal` (`:556-568`), so the caller
  gets the right number and carries on.
- **The commit.** Spring's `HibernateTransactionManager` checks only its own rollback flags, so it commits.
  Hibernate's commit then rolls back instead, and throws only under JPA transaction compliance, which nothing sets.
  The caller sees success, and the transaction's writes are gone.
- **What the probe saw.** Each row is one transaction that changes a user's name, optionally reads, and commits:

  | The read in the transaction | PostgreSQL, with §2.5's setting | MariaDB, Hibernate's defaults |
  |---|---|---|
  | none | change kept | change kept |
  | `queryForInt` of `ld_enabled` (`int`) | returns 1, the transaction turns `MARKED_ROLLBACK`, the commit raises nothing, **the change is lost** | change kept |
  | `queryForInt` of `ld_tenantid` (`bigint`) | change kept | change kept |
  | `queryForInt` of `count(*)` | change kept | change kept |

- **Where it bites.** `HibernateUserDAO.store` reads `select ld_enabled from ld_user …` with `queryForInt` for every
  existing user, inside its `@Transactional` save (`HibernateUserDAO.java:278-279`; `ld_enabled` is `int`). So every
  edit of an existing user is lost on PostgreSQL, the boolean setting or not: an admin's edits, enabling and
  disabling, password changes, and Healdan's own updates in `SignInAccounts` and `SignInAdmin`. CE's other 80 or so
  `queryForInt` and `queryForLong` calls weren't checked column by column. Nor were `queryForObject` and
  `queryForList` given a type the driver refuses: they throw rather than retry, and leave the same mark (question Q9).
  Healdan's own eight such calls read `bigint` columns or `count(*)`, which don't trip it.
- **HSQLDB and MariaDB can't show it:** their dialects never mark a failed statement, and their drivers convert.

## 3. Healdan's Own Tables

### 3.1 The MariaDB Scripts, Unchanged

Run through `psql`, the scripts in `healdan-core/src/main/resources/healdan/db/migration/mariadb/` fail in layers,
each hiding the next. V1 to V7 ran unchanged on PostgreSQL 16 on 2026-11-03. On 2026-11-18, with the first pass's
translation applied, V8 and V11 still failed on 18.4, with the errors the table gives for them.

| Layer | Statements | PostgreSQL's error |
|---|---|---|
| The table options `ENGINE=InnoDB DEFAULT CHARSET=… COLLATE …` | every `create table` (19: V11's two are `utf8mb4_bin`, the rest `utf8_bin`) | `syntax error at or near "ENGINE"` |
| `datetime` and `datetime(3)` | 14 tables, and `hd_api_token.hd_checked` (V6) | `type "datetime" does not exist` |
| An inline `index` in `create table` | 5: V2's `hd_preview_job_due`, V8's `hd_email_messageid`, V11's three | `type "hd_preview_job_due" does not exist`, and V8's the same |
| V7's `alter table hd_api_token modify hd_refresh text` | 1 | `syntax error at or near "modify"` |
| V11's `hd_id bigint auto_increment primary key` | 1 | `syntax error at or near "auto_increment"` |
| Everything that uses a table that wasn't created | the rest | `relation "…" does not exist` |

### 3.2 The Translation

Five changes make all eleven apply, and nothing else needed changing:

1. **Drop the table options.** The encoding is the database's, and collation is question Q5.
2. **`datetime` becomes `timestamp`, and `datetime(3)` (V4, V11) becomes `timestamp(3)`.** PostgreSQL's `timestamp`
   keeps microseconds where MariaDB's `datetime` keeps whole seconds, as HSQLDB's `timestamp` in Healdan's own HSQLDB
   scripts does, so the tests already run with the finer clock.
3. **Each inline index becomes a `create index` after its table,** as the HSQLDB scripts have it: for V2, `create
   index hd_preview_job_due on hd_preview_job (hd_attempts, hd_nexttry);`.
4. **V7's `modify` becomes `alter table hd_api_token alter column hd_refresh type text;`.** PostgreSQL's `text` has no
   65,535-byte limit, so the cap the comment and `ApiTokens.MAX_REFRESH` describe becomes the code's alone.
5. **V11's `bigint auto_increment primary key` becomes `bigint generated by default as identity primary key`,** as its
   HSQLDB script has it. `TrashStore.java:125-127` already copes with pgjdbc returning every column as the generated
   keys.

Already portable: both orders of `create sequence … increment by 50 start with 100`, `unique (…)`, the inline foreign
key, `char(1)`, `bigint`, `int`, `varchar(n)`, `alter table … add column`, the `insert`s of V1 and V9 and V7's `update
… where ld_deleted = 1`. The scripts have no `ON DUPLICATE KEY`, backticks, quoted identifiers or booleans. PostgreSQL
folds `hd_client_SEQ` to `hd_client_seq`, as with CE's.

Flyway 13.10.3 applied the translated V1 to V7 on 16.15 and 18.6 ("Successfully applied 7 migrations"), and the same
run on MariaDB applied the originals. On 2026-11-18 the translated V1 to V11 applied on 18.4 through `psql`, each in a
transaction, and the originals on MariaDB 11.4.13 through `mariadb`. V5 and V10 need no change, so each would have the
same checksum on both; the other nine differ (§5.8).

The S3 storage spec says its migration's SQL "runs unchanged on PostgreSQL too"
([spec](../superpowers/specs/2026-11-01-s3-storage-backends-design.md) §5.1 and §14). As built, V9's MariaDB script
has the table options and `datetime` of §3.1, so it needs changes 1 and 2, as every script with a `create table` does.

### 3.3 Flyway and `HealdanMigrator`

- **Flyway needs its PostgreSQL module.** With the two modules `healdan-core/pom.xml:22-36` ships
  (`flyway-mysql` and `flyway-database-hsqldb`), Flyway stops before it starts: `No Flyway database plugin found to
  handle jdbc:postgresql`. With `org.flywaydb:flyway-database-postgresql:13.10.3` added, it runs. The plugin zip
  includes `org.flywaydb:*` (`healdan-core/src/main/assembly/assemblyplugin.xml:21`), so the dependency alone would
  ship it.
- **`HealdanMigrator.vendorFolder`** (`healdan-core/.../healdan/db/HealdanMigrator.java:159-176`) knows MariaDB and
  HSQLDB, and throws for anything else: "Healdan's tables support MariaDB and HSQLDB only; found PostgreSQL …".
  pgjdbc reports the product as `PostgreSQL`, so a branch returning `postgresql` would pick
  `classpath:healdan/db/migration/postgresql`. `HealdanMigratorTest.testRefusesUnsupportedDatabase` (`:147-154`)
  expects the refusal, and the Install manual's Troubleshooting page has a row for it.
- **`tableExists`** (`:187-196`) tries the lower-case name first, which is how PostgreSQL stores unquoted names, in
  the connection's own catalogue and schema (`current_schema()`, `public` by default). That's enough while CE's
  tables are in that schema. When they aren't, as under pgloader's own schema (§5.8), `migrate` finds no `ld_user`
  and returns `SKIPPED_NO_CE_SCHEMA` (`:90`, `:98-99`), and `HealdanStartup.run` logs "LogicalDOC's schema doesn't
  exist yet" and skips the migrations, the seeds and the preview worker on every start, while CE runs on through the
  search path (read from the code). So the schema the tables live in is a decision, not a detail.
- **The recovery message is MariaDB's.** `RECOVERY` (`:51-53`) says to `delete from hd_schema_history where success
  = 0`. On PostgreSQL Flyway's `success` column is `boolean`, and that statement fails: `operator does not exist:
  boolean = integer`. Nor is there usually anything to recover: PostgreSQL's DDL is transactional, so Flyway runs
  each script in a transaction unless told otherwise. A probe V8 that created a table, inserted a row and then failed
  ("Migration of schema "public" to version "8 - probe" failed! Changes successfully rolled back.") left no table, no
  row and no failed history row. A script Flyway runs outside a transaction, because it's marked
  `executeInTransaction=false` or holds a statement PostgreSQL refuses inside one (such as `create index
  concurrently`), still leaves a failed row and whatever it had done, as on MariaDB (read from Flyway 13.10.3's
  `DbMigrate`). So PostgreSQL needs a message of its own: usually "fix the cause and restart", and the recovery
  steps, with `success = false`, for the rest. Troubleshooting's row says the same as the message.

### 3.4 Where Healdan Refuses to Start on PostgreSQL Today

1. **The container can't reach PostgreSQL at all.** `ConfigPlan.jdbcUrl` builds `jdbc:mariadb://…` (`:110-114`), and
   `DockerInit` loads `org.mariadb.jdbc.Driver` for the schema (`:49`, `:408`). `.env` has no way to name another
   database.
2. **`HealdanMigrator` refuses it** before Flyway runs, through `vendorFolder` (§3.3), on every start once CE's
   schema exists. A server configured by hand, outside the container, meets it late: its first start finds no
   `ld_user` and skips Healdan, the setup wizard then writes CE's generic schema and seed rows into PostgreSQL, and
   only the context refresh at the end of setup (`SetupServiceImpl.java:67`) meets the refusal, leaving CE's tables
   and no `hd_` ones (read from the code).
3. **Behind those,** `DockerInit`'s check of CE's tables would fail on `database()` (§5.1), CE itself would fail at
   its first boolean (§2.5) and lose its user saves (§2.6), and Flyway would fail at its missing module (§3.3).

## 4. Healdan's SQL in Java

On 2026-11-03, 48 of the 236 source files in `healdan-core/src/main/java` and `healdan-api/src/main/java` sent SQL.
Each native statement was copied into a harness and run on the combined schema of §2 and §3 with the Java types the
code binds, through Spring's `JdbcTemplate`, a copy of CE's `NamedParameterStatement` and its typed reads, and raw
JDBC where the code uses it.

On 2026-11-18, 77 of the 403 source files in the Healdan modules call a query API: 60 of 229 in `healdan-core`, 8 of
110 in `healdan-api`, 9 of 62 in `healdan-storage` and none of `healdan-web`'s 2. 65 of them hold native SQL text. A
delegated audit compiled `logicaldoc-util`, `logicaldoc-core` and the three server modules from the repository and
drove their real classes where it could, with CE's DAO helpers on a stub Hibernate `Session`, on the translated schema
of §2 and §3 with rows of its own. Writes ran in transactions that were rolled back, and the same cases ran on MariaDB
11.4.13 for comparison. HQL ran through Hibernate 7.4.4 where it looked database-sensitive, and was read otherwise.

Findings marked *checked* were reproduced by hand on 2026-11-03, and those marked *run* came from the audit of
2026-11-18. Neither harness had a real Hibernate session around its JDBC, so what §4.2 says about statements inside
CE's saves is read from the code; §2.6's probe had one. `Propagation`'s and `PermissionRules`' writes,
`ConversionPages.fill`'s statements, and Healdan's hooks in the webapp and CMIS modules ran as copies with the same
argument types, not through their classes.

### 4.1 What Fails

| Where | What | On PostgreSQL | Smallest fix |
|---|---|---|---|
| `healdan-core/.../db/HealdanMigrator.java:159-176`, `healdan-core/pom.xml:22-36` | the product check and Flyway's modules | §3.3: refused, then "No Flyway database plugin found" | a `postgresql` branch and folder, and `flyway-database-postgresql` |
| `healdan-core/.../db/HealdanMigrator.java:51-53` | the recovery SQL in the message, `success = 0` | `operator does not exist: boolean = integer` (*checked*) | `success = false`, which works on both: MariaDB's column is `tinyint(1)` |
| `healdan-api/.../api/UserResource.java:50-58` | HQL `_entity.enabled = :enabled` bound to `Boolean.TRUE`, on CE's `User.enabled` | `operator does not exist: integer = boolean`. No HQL spelling avoids it: `= true` fails the same way, a bare `_entity.enabled` gives `argument of WHERE must be type boolean, not type integer`, and `= 1` fails in Hibernate | §2.5's Hibernate setting, with which it passes. The rest of the query is portable: Hibernate renders `concat` as `||` and the limit as `fetch first ? rows only` |

Every native statement in the code passes. On 2026-11-03 that was 175 runs. On 2026-11-18 the source held 276
statements (228 in `healdan-core`, 46 in `healdan-storage` and 2 in `healdan-api`, against 120 at `56e9815` by the
same count), of which 147 were new or changed. With their dynamic forms and Healdan's SQL in upstream modules they
came to 466 distinct texts, and each succeeded on PostgreSQL. The only errors were the expected ones: §4.1's recovery
SQL, duplicates the audit provoked and what followed them (§4.2), NUL bytes (§4.7), and a `::` given to CE's helper to
show §4.5's trap. They included `DocNumberCounter`'s `select … for update` on a kept connection, `limit ?` and `limit
… offset …`, `escape '!'`, `insert … select` with parameters, `RETURN_GENERATED_KEYS`, `cast(… as decimal)` over doc
numbers that aren't numeric, batches with nulls and a list of 10,000 ids. Of 35 HQL checks, only `UserResource`'s
failed on PostgreSQL, on its boolean; with §2.5's setting all 35 passed, as they did on MariaDB.

### 4.2 A Failed Statement Ends the Transaction

On PostgreSQL an error inside a transaction aborts it: every later statement fails with `25P02: current transaction
is aborted, commands ignored until end of transaction block`, and a commit rolls everything back. MariaDB carries on
(*checked*: after a duplicate key inside a transaction, MariaDB still held the first row, and PostgreSQL refused the
next `select`).

- **`PreviewJobs.add`** (`healdan-core/.../preview/PreviewJobs.java:70-78`) inserts a job and catches
  `DuplicateKeyException` as "Already queued". Spring maps PostgreSQL's `23505` to the same exception, so the catch
  still fires, but if a transaction surrounds the call, that transaction is lost. Outside one it's harmless. It has
  three callers (read from the code):
  - `OfficeThumbnailBuilder.queue` (`:80-84`) expects duplicates. It's reached from `ThumbnailServlet`,
    `DocumentServiceImpl` and the SOAP and REST document services, which run without a Spring transaction: CE's
    `DocumentManager` and `ThumbnailManager` are plain components, while its DAOs are `@Transactional`.
  - `PreviewDocumentListener.afterFileStore` (`:47-54`) runs once the document's own save has returned
    (`DocumentManager.storeFile`, `:1063-1077`), so inside a transaction only when its caller has one. CE's folder
    copy does: `HibernateFolderDAO.copy` (`:1513`, `@Transactional` on the class) creates every copied document in one
    transaction (`:1602`), but their ids are new.
  - `PreviewJobs.requeue` (`:88-94`), from `PreviewDocumentListener.afterStore` (`:57-68`) when a file is replaced,
    updates the job and calls `add` when no row changed. It always runs inside `HibernateDocumentDAO.store`'s
    transaction (`:343-344`), and `PreviewJobs`' `JdbcTemplate` shares its connection. `DocumentManager.replaceFile`
    deletes the version's conversion and thumbnails before that save (`:200-203`), so a thumbnail request meanwhile
    queues the same job through `OfficeThumbnailBuilder`. If that insert lands between `requeue`'s update and its
    insert, PostgreSQL's `23505` aborts the save: the new file is already in the store, and the document's record and
    history roll back. On MariaDB the update's gap lock on the missing key makes the thumbnail's insert wait instead.
    The window is two statements wide.
    The audit reproduced it with two sessions (*run*): `requeue`'s update found no row, the other session inserted and
    committed, `add` swallowed `23505`, and the transaction's next statement failed. On MariaDB the other session's
    insert waited.
- **`EmailIndex.put`** (`healdan-core/.../email/EmailIndex.java:104-110`) has the same pattern: it inserts, catches
  `DuplicateKeyException` and updates. Inside a transaction the update then fails with `25P02` (*run*). Nothing loses
  data this way today (read from the code): `EmailDocumentListener.afterCheckin` (`:191`) runs from `DocumentManager`
  (`:426`) with no transaction open, `EmailIndexWorker` (`:239`) has none, and `EmailDocumentListener.afterStore`
  (`:145`), which is inside the save, always has a new id.
- **No fix that only rewrites the insert closes that race.** `insert … select … where not exists (…)` passed on both
  databases in one session, but under `READ COMMITTED` its `not exists` can't see another session's uncommitted row,
  so it waits and then fails on the primary key (*run*); on MariaDB the `select` takes next-key locks, and two such
  inserts can deadlock. On HSQLDB 2.7.1 that form needs a `from` (`select … from (values(0)) where not exists …`);
  without one it fails with `unexpected token: WHERE` (*checked*). PostgreSQL's `on conflict (hd_docid,
  hd_fileversion) do nothing` waits for the other insert and then skips (*run*). A savepoint around the insert works
  on all three databases, as `healdan-storage`'s `Savepoints.insertIfAbsent` (`:99`) shows (*run*), though that class
  is package-private in a module that depends on `healdan-core`. pgjdbc's `autosave=always`, a `jdbc.url` parameter
  that sets a savepoint before every statement, would also work, at a cost per statement.
- **Swallowed failures inside a save.** `PreviewDocumentListener.beforeStore` (`:91-100`) and `afterStore` (`:57-68`)
  run inside `HibernateDocumentDAO.store` (`:322-323`, `:343-344`), and log and swallow any failure. `beforeStore`
  reads a page count with `JdbcTemplate` on the save's connection whenever a document is indexed
  (`ConversionPages.keepStoredPages`). `EmailDocumentListener.afterStore` (`:139-150`),
  `FavoriteNamesDocumentListener.afterStore` (`:47-58`) and `FavoriteNamesFolderListener.afterStore` (`:43-53`) now do
  the same. Every statement they run passed, so only an unexpected failure would cost the whole save on PostgreSQL,
  where MariaDB carries on. `TokenUserListener` (`healdan-core/.../token/TokenUserListener.java:38-58`) and now
  `ThrottleUserListener.beforeSave` (`:54-63`) swallow too, but what they call goes through the `@Transactional`
  `HibernateUserDAO` or an HQL `bulkUpdate`, and a failure in either marks the user save rollback-only on any
  database. So their catches don't keep the user save on MariaDB either (read from the code).
- Not affected: `ProviderRecheck` and `SignInAccounts` run their own transactions or retry whole ones, and
  `CheckoutResource`'s retries are on Hibernate's stale-row exceptions, outside any transaction.

### 4.3 What Behaves Differently

- **Trailing spaces.** MariaDB's `utf8_bin` ignores trailing spaces in `=`, and PostgreSQL doesn't (*checked* and
  *run*: `ld_name = 'admin '` finds the group on MariaDB and nothing on PostgreSQL). Unique indexes follow the same
  rule. Healdan compares text that may arrive untrimmed in `LegacyDocNumbers.java:27-28`, `StoredRows.java:384`,
  `SearchFolderService.java:344`, `HibernateSignInIdentityDAO.java:36`, `SignInAccounts.java:246`, `:721`, and now in
  the folder listing's filters (`FolderListing.java:496`, `:515`). `MatterService.java:409`,
  `PermissionRules.java:916`, `HealdanSettings.java:92`, `:103`, `:114`, `StoredFiles`' `hd_resource` and
  `HeldCopies`' `hd_location` compare names the code builds itself, which can't carry one.
- **NULLs in `order by`.** PostgreSQL sorts NULLs last when ascending and first when descending, and MariaDB does the
  opposite (*checked* and *run*). The nullable keys are `UserResource.java:58` (first name, name), `_entity.date desc`
  with one row in `DeviceHolds.java:350`, `ApiViews.java:306` and `DocumentResource.java:584`, where a NULL date would
  become the latest, and `VersionResource.java:120`. CE fills those fields when it creates the rows, so it's latent.
  SQL's `nulls last` and `nulls first` would make them the same, but MariaDB refuses both (*checked* on 11.4.13,
  2026-11-07: `ERROR 1064`). Sorting on `case when x is null then 1 else 0 end` first does it in standard SQL, as
  `ListingSql.nullsLast` (`:79`) now does, and HQL's `nulls last` ran on both databases through Hibernate (*run*).
- **`lower()` and sorting follow the database's locale** (§2.3). `lower()` serves the case-insensitive searches in
  `MatterSearch.java:136`, `ClientSearch.java:23`, `CommentsMatch.java:77`, `SearchFolderService.java:315-316`,
  `SignInAccounts.java:246`, `:721`, `UserResource.java:51-53`, `FolderListing.java:476`, `:480`,
  `RecentDocuments.java:65`, `EmailIndexWorker.java:112` and the seeds. Text sorts in `HealdanSettings.java:124`,
  `HibernateSignInIdentityDAO.java:49`, `HibernateSignInGroupDAO.java:32`, `UserResource.java:58`, and now in lists
  people see: Title and Author (`FolderListing.java:563`, `:590`) and the Trash's names (`TrashStore.java:48`). Under
  `en_US.utf8`, `éclair.pdf` sorts beside `e`, where MariaDB puts it after `Zeta` (*run*).
- **Timestamps keep milliseconds** on PostgreSQL where MariaDB's `datetime` drops them: `hd_api_token.hd_lastused`
  came back 123 ms apart, and a time of `.123456789` is stored as `:30` on MariaDB's `datetime`, `.123457` in
  `timestamp` and `.123` in `timestamp(3)` (*run*). CE's own dates do the same (§2.3), and
  `VersionResource.java:117`'s comment assumes a version's date has one-second precision. Nothing failed.
- **Isolation.** PostgreSQL's default is `READ COMMITTED` and MariaDB's `REPEATABLE READ`. Nothing in `context.xml` or
  `context.properties` sets it. The audits found no Healdan statement that needs a repeatable snapshot, but didn't
  prove it. For writes, PostgreSQL's default is the closer match: its `REPEATABLE READ` fails an update of a row
  another transaction changed since the snapshot with `40001`, where InnoDB's updates the latest row, as `READ
  COMMITTED` does (*run*). Healdan relies on the latter, for example in `ApiTokens.touch`'s conditional update
  (`ApiTokens.java:302-309`), and nothing in Healdan or CE retries a `40001`. `OncePerMatter.java:443-444` asks the
  connection for its isolation level rather than its vendor.
- **Rows without an `order by`** come back in another order (`Propagation.java:304`, `AclMath.java:92`,
  `PermissionRules.java:764`, `:926`, `StoredProfile.java:58`). Each result goes into a map or set, so nothing
  changes.
- **Dates and days came out the same.** The listing's date filters and Today returned the same documents on both
  databases under UTC, America/New_York and Asia/Tokyo (*run*).

### 4.4 The Same on Both

- **Update counts.** The MariaDB URL (`ConfigPlan.java:114`) doesn't set `useAffectedRows`, so MariaDB's driver
  reports matched rows, as PostgreSQL does. An update to the same value returned 1 on both (*run*). Code that reads
  the count (`HealdanSettings`, `SearchFolderRuleDAO`, `Propagation`, `PreviewJobs`, `ConversionPages`,
  `DocNumberCounter`, `ApiTokens`) behaves the same.
- **No SQLSTATE or vendor error code** is read anywhere in `healdan-core`, `healdan-api` or `healdan-storage`
  (`DockerInit` reads two, §5.1), and pgjdbc sets the session's time zone to the JVM's (*run*).
- **None of the usual MariaDB-isms** is in Healdan's native SQL: no sequences, `ON DUPLICATE KEY`, `INSERT IGNORE`,
  `REPLACE`, backticks, double-quoted strings, `LIMIT a, b`, `IFNULL`, `IF()`, date arithmetic, integer division, or
  `GROUP BY` and `DISTINCT … ORDER BY` that PostgreSQL would refuse. `length()` has arrived in `ListingSql.java:93`,
  where it counts bytes on MariaDB and characters on PostgreSQL, but only over digits and an emptied remainder, so the
  two agree (*run*).

### 4.5 Traps for New Code

- **CE's typed reads are stricter on PostgreSQL.** CE's helpers read with `ResultSet.getObject(1, type)`, and pgjdbc
  refuses a `Long` from an `int` or `numeric` column, an `Integer` from `count(*)`, which is `bigint`, and a `String`
  from either (*run*). MariaDB's driver converts them. `queryForInt` and `queryForLong` hide the refusal by reading
  again, which on PostgreSQL costs the transaction (§2.6); `queryForObject` and `queryForList` throw. Every call in
  Healdan asks for a type that matches its column, as new code has to. That isn't enough for `queryForInt`, which
  always asks for a `Long` first: an `int` column read through it costs the transaction.
- **Spring's `JdbcTemplate` is safer.** Its `queryForObject(sql, Long.class)` reads with `getLong`, so it takes
  `int4`, `int8` and `numeric`; an `Integer` fails only above the `int` range, with `22003` (*run*).
- **`::` casts.** CE's `NamedParameterStatement` reads PostgreSQL's `x::text` as a parameter named `text` (*run*:
  `ld_id::text` becomes `ld_id:?`, and the statement fails with `No value specified for parameter 1`), so SQL given to
  CE's helpers needs `cast(x as text)`.

### 4.6 A Lock Cycle Nothing Ends

`FileLocks` (`healdan-storage/.../storage/FileLocks.java:32-38`) records one cycle it can't rule out:
`StoreTracking`'s update of several documents' rows, a CE transaction and the mover's commit, waiting on row locks and
one of the JVM's file locks. It says that only InnoDB's lock-wait timeout ends it, by failing one of them, and that
the failed one is retried or mended. PostgreSQL's deadlock detector can't see a JVM lock, and its `lock_timeout` is 0
by default (*run*), so those threads would wait for good. It takes a stripe shared with the transaction's next file (1
in 1,024) as well as the coincidence, and it wasn't reproduced. The smallest fix is a `lock_timeout` for the pool,
through `jdbc.url` (`options=-c lock_timeout=50s`) or the pool's `connectionInitSql` settings, after which the failure
is `55P03` (question Q14).

### 4.7 NUL in Bound Text

PostgreSQL's text types can't hold NUL even as a bound parameter: a value with one fails with `22021: invalid byte
sequence for encoding "UTF8": 0x00`, where MariaDB finds no rows or stores it (*run*, through `FolderListing`'s
filters, `MatterSearch`, `CommentsMatch`, `LegacyDocNumbers`, `HealdanSettings.get`, `StoredRows.liveChild` and
`PreviewJobs.failed`'s `hd_lasterror`). A query parameter such as `?author=%00` reaches it (`FolderResource.java:262`,
`SearchResource.java:106`), so it would be a 500 (read from the code). Those reads run outside a transaction, so
nothing is lost. The email code already replaces NUL (`MessageIds.java:44`, `EmailFields.java:243`) (question Q15).

### 4.8 Code Written with PostgreSQL in Mind

The [S3 storage plan](../superpowers/plans/2026-11-03-s3-storage-backends.md)'s Global Constraints turned §2.5 and §4
into rules for new SQL, and the storage module, the User app's listings and the Trash follow them:

- `Savepoints` (`healdan-storage`), used by `StoredFiles.recordWrite` and `HeldCopies.hold`, keeps the transaction
  alive through a real conflict (*run*).
- `TrashStore.java:125-127` takes the generated key from pgjdbc's answer, which holds every column.
- `ListingSql` has no `::`, guards its casts and substrings with `case`, and spells out where NULLs go. It casts to
  `decimal(18,0)`, as MariaDB refuses `cast … as bigint`.
- `StorageBackend` keeps its flags as `int` fields (§2.5).

The exceptions are small. `EmailIndex.put` (§4.2) comes from M5, whose plan predates the rule, and
`TrashStore.java:369` compares a timestamp with `=`, against the letter of the rule, on a cursor value read back from
the database.

Not tested: a start of the whole application, HTTP routes, the HQL that only binds ints, longs, strings, dates and
lists (read instead), CE's own SQL that Healdan calls, the cycle of §4.6, and performance at real volumes (the audit's
fixture had about 20 documents).

## 5. Around the Database

### 5.1 `DockerInit` and `ConfigPlan`

Every MariaDB assumption in `docker/tools/src/`:

| Where | What | On PostgreSQL |
|---|---|---|
| `DockerInit.java:49`, `:408` | `DRIVER = "org.mariadb.jdbc.Driver"` for `DBInit` | `org.postgresql.Driver` |
| `DockerInit.java:51`, `:370` | `DBMS = "mariadb"`, which picks the `.mariadb` variants | `postgresql` finds no variant, so the generic scripts run (§2.1). The check below needs this too: left on `mariadb`, its plan expects `ld_uniquetag.ld_int1`, which the generic script lacks |
| `DockerInit.java:57`, `missingTables` (`:495-507`) | `NO_SUCH_TABLE = "42S02"`, the SQLSTATE MariaDB gives a missing table; any other is rethrown | PostgreSQL gives `42P01`, so on an empty database the first-start check would throw instead of creating the schema |
| `DockerInit.java:484-493`, `Db.columns` | `information_schema.columns … where table_schema = database()`, for the check of CE's tables at every later start (`checkLogicalDocTables`, `:329-340`) and after a fresh schema (`:415-418`) | `42883: function database() does not exist` (run). It isn't an `InitException`, so the start ends in a stack trace. On a fresh database it comes after `DBInit` and before `recordCompletion` (`:419`), so the next start finds `ld_user` without `docker_init` and reports a partial schema (`:100`). `current_database()` matches no rows, since `table_schema` is `public`; `current_schema()` finds them (both run) |
| `DbInitPlan.java:104-111`, `:158-162`, `:184-189` | reads the scripts as MariaDB does: `"` opens a string, `\` escapes, `#` starts a comment, and table names match as written | the generic scripts are unaffected: the plan gives 56 tables and 949 columns, all lower-case, as PostgreSQL has them. A Healdan `.postgresql` variant with quoted identifiers would be misread without an error |
| `ConfigPlan.java:110-114` | `jdbc:mariadb://` and port `3306` | `jdbc:postgresql://`, `5432` |
| `ConfigPlan.java:69-71` | seeds `jdbc.driver`, `jdbc.dbms`, `hibernate.dialect` | the PostgreSQL values. These are written only when the conf volume is first seeded (`DockerInit.java:203-207`), so an install moving across keeps MariaDB's unless `DockerInit` takes them over (question Q7) |

The rest works as it is: the `docker_init` marker (`create table docker_init (completed timestamp not null)`), the
`select 1` wait and validation query, the SQLSTATE class `28` test for a wrong password, and `DbInitPlan.resolve`.
`docker/tools/test/ConfigPlanTest.java:52-54`, `:60`, `:69` and `:75`, `ConfigFileTest.java:49`,
`DbInitPlanTest.java:283-300` and `docker/tests/test-init.sh:72-73` check the MariaDB values, URLs and plan lines.

### 5.2 `compose.yaml`, `.env` and the Dev Overlay

- **The `db` service** (`compose.yaml:7-25`) is `mariadb:11.4` with `MARIADB_*` variables, MariaDB's `healthcheck.sh`,
  the `--character-set-server` flags and a volume at `/var/lib/mysql`. A PostgreSQL service takes `POSTGRES_DB`,
  `POSTGRES_USER` and `POSTGRES_PASSWORD`, a `pg_isready` health check, and its volume where the image keeps its data:
  `postgres:18` sets `PGDATA=/var/lib/postgresql/18/docker` and declares the volume `/var/lib/postgresql`, where
  earlier majors used `/var/lib/postgresql/data` (checked with `docker image inspect`). The image makes
  `POSTGRES_USER` a superuser, where the app reaches MariaDB today as an ordinary user, with a random root password
  (`compose.yaml:11-14`). An ordinary PostgreSQL role has to own the database, or be granted `CREATE` on `public`,
  which PostgreSQL 15 and later no longer give everyone, for `DockerInit` and Flyway to create tables. The trials
  here ran as the superuser (§7).
- **The `app` service** defaults `DB_PORT` to `3306` (`compose.yaml:40`), and `.env.example` says the password
  creates "the MariaDB user".
- **`compose.dev.yaml:16-17`** publishes MariaDB's port on `DEV_DB_PORT` (default `3306`), and
  `docker/tests/test-overlays.sh:60` checks it.
- **Upgrades differ.** A PostgreSQL major version can't open an older major's data directory: moving from 18 to 19
  takes a dump and restore or `pg_upgrade`. The Upgrading page's "To update MariaDB too, run `docker compose pull
  --ignore-buildable`" wouldn't carry over as it is (question Q1).

### 5.3 Backup and Restore

The Install manual's [Backup and Restore](../../site/content/install/backup-and-restore.md) runs `mariadb-dump
--single-transaction` and loads with `mariadb`, both through `MARIADB_*`. PostgreSQL's equivalents, checked on 18.6
between two containers (69 tables, Flyway's history and the sequences all came back), and on 18.4 between two
databases (75 tables and 41 sequences, the identity's among them):

```bash
docker compose exec db sh -c 'pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB"' > logicaldoc-db.dump
```

```bash
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error' < logicaldoc-db.dump
```

`pg_dump` takes a consistent snapshot without a flag, and `--no-owner` lets a restore under another user name work.
A plain-SQL dump (`pg_dump` without `-Fc`, loaded with `psql`) works too; the custom format is smaller and
restores selectively. The configuration and repository volumes are backed up as they are today.
A restore on that page now also applies Upgrading's changes to CE's tables, whose commands are MariaDB's (§5.7).

### 5.4 CI and the Smoke Test

Where the tests talk to MariaDB directly:

| Where | What |
|---|---|
| `docker/smoke-test.sh:323-327` (`db_query`) | runs `mariadb` with `MYSQL_PWD="$MARIADB_PASSWORD"`; `:327` tests `success = 1`, which needs `success` (or `success = true`) on PostgreSQL |
| `docker/smoke-test.sh:376-395` | expects U+FFFD for an emoji, which is Healdan's MariaDB workaround (§5.6) |
| `docker/smoke-test.sh:470-471` | `mariadb` again, for `ld_webservicecall` |
| `docker/tests/test-previews.sh:38-40`, `:312` | `mariadb`, and `timestampdiff(second, now(), hd_nexttry)`, which PostgreSQL lacks (`extract(epoch from hd_nexttry - now())`) |
| `docker/tests/test-proxies.sh:18-20` | `mariadb`; its query itself is portable |
| `docker/tests/test-storage.sh:71-72` | `mariadb`, new since the first pass; its queries are portable |
| `docker/tests/test-init.sh:44-48`, `:72-73`, `docker/tests/test-image.sh:31-32` | start `mariadb:11.4` containers, and check the `.mariadb` plan lines |
| `docker/tests/test-init.sh:95`, `:122`, `:126`, `:130-131` | `database()`, `rename table` and `show tables like`, which PostgreSQL lacks |
| `docker/tests/test-init.sh:114-116` | takes the SQL for `ld_tenant` from the Upgrading page with `sed` and runs it, so a second block for PostgreSQL that opens the same way would be swept in |

`docker-image.yml` builds the image once in each architecture's job, amd64 and arm64, and runs the smoke test against
it (`SMOKE_BUILD: '0'`, so `compose up -d --no-build`). A PostgreSQL run can reuse that image as a second step in the
same job, which adds a stack start and the test's own run, about 14 minutes of a 24 to 27 minute job on a recent run,
but no build; a separate matrix entry would build the image again (question Q8). `SMOKE_PROJECT` and the port
variables now let a second stack run beside the first (`docker/smoke-test.sh:17-31`), though two runs from one
checkout can't both do step 5d.

### 5.5 The HSQLDB Test Setup

- **The tests already use CE's generic scripts.** `AbstractHealdanTestCase` and `AbstractCoreTestCase` load
  `sql/logicaldoc-core.sql` and `data.sql` into an in-memory HSQLDB for each test, and `AbstractHealdanTestCase` runs
  the `hsqldb/` migrations and the seeds. So CE's schema in the tests is the one PostgreSQL would get.
- **HSQLDB is as lenient as MariaDB where it matters.** The boolean binding that PostgreSQL refuses passes on HSQLDB
  2.7.1 in its default mode and with `sql.syntax_pgs=true`; with `sql.enforce_types=true` too, only a literal
  `= true` failed (`42562: incompatible data types in combination`), and the bound parameter still passed. A
  PostgreSQL syntax mode wouldn't stand in for PostgreSQL.
- **The base class is HSQLDB's.** Upstream's `AbstractTestCase` (`logicaldoc-util/.../util/junit/AbstractTestCase.java`)
  makes the database in `setUp`, before every test (`:133`), checks it with `CALL NOW()` (`:264`; PostgreSQL: `now()
  is not a procedure`) and drops it with `shutdown` (`:318`). A test run on PostgreSQL needs a fresh database for each test some other way, such as a
  template database copied per test, and an upstream edit or a Healdan base class around it.
- **Speed, roughly.** Loading the core script took about 0.65 s into a new PostgreSQL database and 0.45 s into
  HSQLDB, each including a JVM start, so the database itself costs a few tenths of a second more per load, before
  Flyway and the seeds. CE's test `data.sql` loads on PostgreSQL without an error.
- **Tests now imitate MariaDB's reads.** 13 Java files, up from 6, use HSQLDB's `SET DATABASE TRANSACTION CONTROL
  MVCC`, so that a read sees what's committed while a transaction is open, as MariaDB does. A base class for
  PostgreSQL would have to account for them.

### 5.6 Text Beyond the Basic Multilingual Plane

CE's MariaDB tables are `utf8`, 3 bytes a character, so Healdan keeps characters beyond the Basic Multilingual Plane,
such as an emoji, out of what it writes (`healdan-core/.../healdan/util/BasicPlane.java`, called from 13 other
classes, up from 10).

- **Nine refuse the text.** `MatterService`, `ClientService`, `SearchFolderService`, `SignInProviders`,
  `HealdanSettingsServiceImpl`, `ApiTokens`, `CheckoutResource` and now `Uploads.checkText` (`:183`, for document and
  folder names and favourites' names) and `BackendDrafts` (`:164`, `:195`) call `BasicPlane.holds` and turn away a
  name, description or label, most with a message saying the database can't store it. The API's docs repeat that
  wording (§5.7).
- **Four replace each such character with U+FFFD** (`replaceOthers`): `EmailFields`, `FileNames`, `SignInAccounts`'
  name claims and now `MessageIds` (`:44`). `SignInAccounts` also won't take such a username or email from a provider.
- **The Message-ID's replacement is a contract.** The helper's `clients/helper/Healdan.Helper.Core/Mail/MessageIds.cs`
  normalises a Message-ID to the same characters, and `hd_email` stores the result, so dropping the replacement on
  PostgreSQL changes what the helper and the server have to agree on.

A PostgreSQL database in UTF-8 stores these characters, so on PostgreSQL both are choices, not needs, and the
refusals' message would no longer be true (question Q6). V11's `hd_trash` tables are already `utf8mb4_bin`, though
their text comes from CE's `utf8` columns. The smoke test's emoji checks cover only the replacement.

### 5.7 Docs Pages That Name MariaDB

- Install manual: `_index.md`, `quick-start.md`, `what-runs.md` (the stack table and Supported Databases),
  `configuration.md` (the `DB_*` table and Changing the Database Password, which runs `mariadb`),
  `backup-and-restore.md`, `upgrading.md`, `troubleshooting.md` (three rows), `known-limitations.md` and
  `host-folders.md`.
- Admin Guide: `admin/system/_index.md`.
- Developer Guide: `development-stack.md` (the published port) and `tests.md` (`test-init.sh`'s row).
- `CLAUDE.md`'s database, persistence, Flyway and upstream-edits paragraphs, and `.env.example`. The `README`s don't
  name it.

Others depend on MariaDB without naming it, or are new since the first pass:

- **Upgrading's Changes to LogicalDOC's Tables** (`upgrading.md:66-124`) gives its checks and fixes through `mariadb`
  and `database()` (`:97`, `:104`) and quotes MariaDB's error text. `CLAUDE.md` has every upstream sync that changes
  CE's tables add to it, so it grows, and a PostgreSQL install needs its own form of each check.
- **`troubleshooting.md`:** `:51` (the `logicaldoc_db-data` volume), `:56` (`success = 0`) and `:60` (`Unknown
  column`). **`configuration.md:90`:** `SET PASSWORD = PASSWORD(…)` is MariaDB's own syntax.
- **The 4-byte limit:** `developer/api.md:463`, `:481`, `:590` and
  `user/documents/copying-moving-and-deleting/index.md:168` blame the database for refusing an emoji, and
  `developer/desktop-clients.md:642` gives the U+FFFD rule.

### 5.8 Moving an Existing Install's Data

The trial below ran on 2026-11-03, on the seven migrations of the day, and wasn't repeated. What a move has to cover
since then follows it.

A trial on a MariaDB 10.11 database holding CE's `.mariadb` schema, Healdan's seven migrations and test rows: a
6,000-character avatar, a history date with milliseconds, accented text, a 5,000-character sealed refresh token, a
client, a matter, settings, the doc # counter at 42, and advanced sequences.

**pgloader making its own schema** (`create tables, create indexes, reset sequences, foreign keys`) loaded every row,
but not into a schema Healdan could use:

- the tables went into a schema named after the MariaDB database, `logicaldoc`, with the database's `search_path` set
  to `public, logicaldoc`;
- `int` became `bigint`, `datetime` became `timestamp with time zone`, and `tinyint(1)` became `boolean`, so the
  schema no longer matched what Healdan's scripts create, which later migrations would be written against;
- none of the 37 sequences came across: MariaDB's `SEQUENCE` objects aren't tables to pgloader, and its `reset
  sequences` only handles serial columns. Hibernate's first insert would have nothing to draw from;
- Flyway, run on its own with `HealdanMigrator`'s settings, then looked in `public`, found no history there, and
  quietly made a second, empty set of `hd_` tables in `public`, which the app's unqualified names would find first: 0
  clients where `logicaldoc.hd_client` held 1. Healdan itself would fail more quietly still: its migrator finds no
  `ld_user` in `public` and skips the migrations, the seeds and the preview worker on every start (§3.3).

**pgloader into a schema Healdan's scripts made** (`data only, truncate, disable triggers`, excluding
`hd_schema_history`, with `ALTER SCHEMA 'logicaldoc' RENAME TO 'public'`) worked, after four fix-ups:

1. **`ld_uniquetag.ld_int1`.** pgloader stopped: `failed to find column "public"."ld_uniquetag"."ld_int1"`. Adding
   the column for the load and dropping it afterwards got past it.
2. **The narrow columns.** `ld_user` failed with `22001: value too long for type character varying(4000)` on the
   avatar, and pgloader dropped the table's whole batch, 0 of 2 rows, and still exited with status 0: a move has to
   check pgloader's error count, not its exit status. With §2.3's narrow columns as `text`, every row came across.
3. **Sequences.** pgloader leaves PostgreSQL's at their fresh values (`ld_user_seq` at 100), which would hand out ids
   already in use. Copying MariaDB's `next_not_cached_value` for each of the 37 into `setval(…, false)` fixed it;
   the values jump ahead by MariaDB's sequence cache (50,100 for a sequence that had last given out 150), which only
   leaves a gap.
4. **Flyway's history.** Copying `hd_schema_history` from MariaDB fails the next start: "Validate failed: Migration
   checksum mismatch for migration version 1 … Applied to database: 147451898, Resolved locally: -289501512", as six
   of the seven scripts differ. Leaving it out works when the PostgreSQL schema is made first, with Flyway, so its
   own history is there; Flyway's `repair` would be the other way, as it realigns the recorded checksums with the
   scripts. Either way the MariaDB install has to be at the same version as the image that makes the PostgreSQL
   schema, so upgraded first: otherwise PostgreSQL's history claims migrations the copied rows never went through,
   and CE's tables have columns the old install lacks. The trial's MariaDB was made fresh from today's scripts, so it
   couldn't show this.

After that, every table's row count matched MariaDB's, the history date kept `10:15:30.123`, the accented text,
avatar and refresh token came across whole, and the doc # counter read 42. pgloader needs a superuser on the
PostgreSQL side for `disable triggers`. The `docker_init` marker table comes across with the rest, which keeps
`DockerInit` from making a new schema. (The trial's first attempt at accented text showed mojibake on both sides,
because the `mariadb` client had written it as Latin-1; pgloader copied the bytes faithfully.) pgloader's MySQL
defaults remove NUL characters from text (`remove-null-characters`), which PostgreSQL can't store (§2.3), so a row
count can match while a value differs.

**What a move also has to cover now** (read from the code, unless marked *run*):

- **Scale.** Eleven migrations, not seven, and 40 sequences on MariaDB, not 37. New tables: `hd_email`, the five
  `hd_storage_*`, `hd_trash` and `hd_trash_member`.
- **`hd_trash.hd_id` is an identity column, not a sequence,** so fix-up 3 misses it. After a row was loaded with its
  id, the next insert was given 1 and failed with `23505`, and after `setval(pg_get_serial_sequence('hd_trash',
  'hd_id'), …)` it went through (*run*). Until then, a delete whose Trash record can't be written is refused
  (`TrashRecorder.notRecorded`, `:362`; `TrashDocumentListener.java:25-27`).
- **The install id has to survive.** `storage.installId` in `hd_setting` is written into every backend's marker
  (`BackendCommon.java:20-21`), and a marker naming another install fails the backend (`LocalBackend.java:198-199`;
  staging's, `:96-99`).
- **So the PostgreSQL schema can't be made by starting the app on the real volumes.** The seed would make a new
  install id (`StorageSettingsSeed.java:71`), which disagrees with the markers. Scratch volumes, or `DBInit` and
  Flyway alone, avoid that.
- **Files outside the database are named by its ids:** `backend-<id>.key` (`BackendKeys.java:96`), the associated data
  of encrypted files, `<docId>/<resource>` (`FileCipher.java:66-67`), S3 object keys and
  `hd_storage_held.hd_location`, and the read cache's `<backendId>/<docId>/<resource>@<write>` (`ReadCache.java:35`,
  `:156`). A sealed `hd_secret` needs the conf volume's key. Options A to D keep all of this as long as they keep the
  ids and the volumes.
- **There's no casual way back.** The old and new stacks share an install id, so they must never run together (the S3
  spec's §12). Once the new stack's mover and purge have run, returning to MariaDB behaves like restoring a backup,
  which is safe only within the holding period (Backup and Restore, Restoring Finds Every File).
- **`DockerInit`'s check helps.** It stops a MariaDB install that lacks a column the scripts create (§5.1), which
  narrows fix-up 4's worry about versions for CE's tables.

**The options, with their trade-offs, and no decision:**

| Option | For | Against |
|---|---|---|
| **A. pgloader into a schema Healdan's scripts made** (the trial) | Tried, and it keeps every id, so the repository's files and the full-text index stay valid. pgloader is packaged by Debian and Ubuntu and has a Docker image | An extra tool in the move, run from outside the stack; the fix-ups above and what the list after them adds; a superuser (which the image's `POSTGRES_USER` already is, §5.2); its exit status hides row failures, so the move needs its own checks |
| **B. pgloader making its own schema** | One command | The schema differs from Healdan's (§5.8's list), no sequences, and with CE's tables outside `public` Healdan skips its migrations and seeds (§3.3), and `DockerInit`'s check, looking in `public`, would find no tables (§5.1). It would need as much fixing as A, and the result would differ from a fresh install |
| **C. A Healdan copy command** in the image (Java, beside `DockerInit`): make the PostgreSQL schema with Healdan's scripts, copy each table over JDBC from the tables' own metadata, copy the sequences and the identity's counter, compare row counts | No extra tool, and testable in CI, though its tests need both databases, where `docker/tools`'s others need none; it can refuse to start on a mismatch and say why | Code to write and keep up as CE's tables change (reading the metadata keeps that small); not tried |
| **D. Dump to CSV and load with `COPY`** (`mariadb-dump --tab`, then `\copy`) | Only the databases' own tools | `mariadb-dump`'s SQL isn't PostgreSQL's, so CSV it is, with its escaping; several tables have their columns in another order in the generic script (`ld_document_acl`'s permission bits and `ld_legal`'s title and category among them), so every `\copy` needs a column list or loads values into the wrong columns without an error; many manual steps per table; the same sequence and history fix-ups; not tried |
| **E. Re-import the documents into a fresh PostgreSQL install,** through the API or the planned bulk import | Nothing database-specific | Every document gets a new doc #, and its old one becomes the legacy doc # (the roadmap's principle 1), so every number cited in emails and stamped in footers becomes a legacy one; loses ids, version records, history, audit and settings; the index is rebuilt; new document ids can't decrypt what encrypted backends hold, and existing folders and buckets read as another install's, so their files would have to come out first; more a fresh start than a move |
| **F. No move:** PostgreSQL for new installs, MariaDB kept for existing ones | No migration code | Two databases to support and test for as long as MariaDB stays, against principle 9's "move away from MariaDB in time" |

Whatever moves the data, the stack's `TZ` should be the same on both sides (MariaDB's `datetime` and PostgreSQL's
`timestamp` both store no zone), the app is stopped for the copy, and `context.properties`' `jdbc.driver`,
`jdbc.dbms` and `hibernate.dialect` change with `jdbc.url` (§5.1).

## 6. Open Questions for the Design Session

1. **Q1. Which PostgreSQL major, and how does the stack follow new ones?** Both 16 and 18 behaved the same here. The
   stack pins `mariadb:11.4`; a PostgreSQL image needs a dump and restore or `pg_upgrade` to change major (§5.2).
2. **Q2. Where do the Hibernate fixes live?** For booleans (§2.5): a `hibernate.properties` in `healdan-core`'s jar
   (no upstream edit), a line in `context.xml`'s `hibernateProperties` (an upstream edit, but visible beside the
   other settings), a `-D` in `docker/setenv.sh` (the container only), or a Healdan subclass of `PostgreSQLDialect`
   named by `hibernate.dialect` (PostgreSQL only, but only where that key names it). The first two apply on MariaDB
   too. For the rollback CE's typed reads cause (§2.6): an upstream edit to `queryForLong`, or the same dialect
   subclass not marking failures the driver raises itself, which leave PostgreSQL's transaction intact. Tests on
   MariaDB and HSQLDB can't check any of these, since both accept CE's code as it is.
   Nothing has been set since. The re-check ran both failures, and the boolean setting, through CE's own mappings.
3. **Q3. Does Healdan widen CE's 8 narrow columns on PostgreSQL,** and how: a Healdan-owned
   `logicaldoc-core.sql.postgresql` variant (which upstream never edits, so no sync conflicts, but which then drifts
   from the generic script as upstream adds tables, and which the wizard's `PluginDbInit` would pick up on every
   PostgreSQL install whatever `jdbc.dbms` says, and `DbInitPlan` once `DockerInit`'s `DBMS` is `postgresql`), the
   generic script followed by a Healdan script of `alter … type text` statements, or neither, accepting the limits?
   Either of the first two changes the structure of CE's tables, which Healdan's rules rule out today. And does the
   missing `ld_uniquetag.ld_int1` matter beyond a move?
   `DbInitPlan` would pick up a `.postgresql` variant and trust it, so `DockerInit`'s check couldn't show drift from
   upstream; the generic script followed by `alter … type text` keeps the check honest, as it doesn't compare types
   (§5.1).
4. **Q4. How does an install get PostgreSQL:** a variable in `.env` (`DB_TYPE`, say) that `DockerInit`, `ConfigPlan`
   and `compose.yaml` read, a compose overlay that swaps the `db` service, or a separate compose file? And when, if
   ever, does PostgreSQL become the default for new installs?
5. **Q5. Which collation?** PostgreSQL 17 and later's built-in `pg_c_utf8` sorts by code point as MariaDB's
   `utf8_bin` does today, lower-cases accented letters, and keeps CE's `ld_path like 'prefix/%'` on its index (§2.3).
   Plain `C` keeps the index but lower-cases only ASCII, which would break the case-insensitive searches of §4.3.
   glibc's `C.UTF-8` sorts and lower-cases as `pg_c_utf8` does but loses the index. `en_US.utf8`, the image's default,
   or an ICU locale such as `en-GB`, sorts as people expect, and loses it too. Only `C` and the built-in provider
   sort the same across upgrades: glibc and ICU collations can change with a new operating system or ICU version,
   which a `postgres:18` image moving to a newer Debian brings, and then need their indexes rebuilt.
   The User app and the Trash now sort on `lower(…)` (§4.3), so the choice shows in the lists people read.
6. **Q6. Does Healdan still refuse or replace characters beyond the Basic Multilingual Plane on PostgreSQL?** Seven
   classes refuse them, with a message that blames the database, and three replace them with U+FFFD (§5.6). Keeping
   both makes the two databases hold the same text, and a move back possible; dropping them allows emoji in matter
   and client names, subjects and file names, and changes the refusals' messages and tests.
   Nine classes now refuse and four replace, and Message-IDs have to match the helper's, so dropping the replacement
   changes a client contract as well (§5.6).
7. **Q7. Who owns `jdbc.driver`, `jdbc.dbms` and `hibernate.dialect`** in `context.properties`: still seeded once, or
   applied from the environment on every start like `jdbc.url`, so that an install can move across? The list of keys
   Healdan owns now exists (`ConfigPlan.HEALDAN_KEYS`, `:51`, applied and read back at `DockerInit.java:253-262` and
   `:312-318`), but it holds constants, so values that depend on the database fit `ConfigPlan.envKeys` (`:80`) better,
   which `DockerInit` also writes and verifies at every start (`:208`, `:298-306`). And which spelling of `jdbc.dbms`:
   `postgresql`, which `isPostgreSQL()` and `StatsCollector` expect, or the wizard's `postgres`? Neither selects a
   script variant (§2.1).
8. **Q8. How much CI on PostgreSQL?** The smoke test on PostgreSQL on one architecture or both, as a second step
   that reuses each job's image or as jobs of its own (§5.4); Healdan's own tests on HSQLDB only, or also on a
   PostgreSQL service in CI (needing a base class that makes a fresh database per test, §5.5); and for how long both
   databases stay in CI.
   The cost of a run, and a second stack beside the first, are now known quantities (§5.4), and tests that set
   HSQLDB's MVCC mode add to what a PostgreSQL base class must handle (§5.5).
9. **Q9. How far does the check of CE's own SQL go** before PostgreSQL is called supported: the smoke test on
   PostgreSQL alone, a run of CE's upstream tests on PostgreSQL, or a read of its 330 native SQL calls, starting from
   the typed reads of §2.6 and the escaped literals of §2.4?
   The [mapped-tables check](../superpowers/specs/2026-11-10-mapped-tables-check-design.md), proposed and not built,
   would cover the columns Hibernate maps, not CE's native SQL. An upstream sync has already brought one statement
   PostgreSQL refuses (§2.4).
10. **Q10. How do existing installs move across** (§5.8's options A to F), and does the move ship as a documented
    procedure, a command in the image, or not at all for now? Does MariaDB then stay supported for a time, and how
    long?
   §5.8 now lists the storage and Trash state a move has to keep, and option E has got worse.
11. **Q11. What do the backup and restore pages say** during the overlap: both databases' steps, in tabs, or one page
    for each? The S3 storage work landed first, so the PostgreSQL work is what has to update those pages and
    `DockerInit` (the S3 spec's §14). Upgrading's Changes to LogicalDOC's Tables needs the same answer, since every
    sync that changes CE's tables adds to it (§5.7).
12. **Q12. Does Healdan make its SQL behave the same on both databases where §4 found it doesn't:** trim text before
    it's compared or stored (trailing spaces), say where NULLs sort when a key can be NULL (in a form MariaDB
    accepts, §4.3), and set an
    isolation level (bearing in mind that PostgreSQL's `REPEATABLE READ` isn't InnoDB's, §4.3)? Or does it accept
    PostgreSQL's behaviour once it's the first-class database?
   New code already has answers: the S3 plan's Global Constraints trim text in Java, say where NULLs sort, and don't
   rely on fractions of a second or on `REPEATABLE READ`, and M6 and the Trash follow them (§4.8). The older sites of
   §4.3 are as they were.
13. **Q13. How does `PreviewJobs.add` queue a job without relying on a failed insert,** when `requeue` always calls it
    inside a document save (§4.2): a branch with PostgreSQL's `on conflict do nothing`, a savepoint around the insert,
    pgjdbc's `autosave`, or moving the requeue to after the save commits? `insert … select … where not exists`
    narrows the race but doesn't close it.
   `healdan-storage`'s `Savepoints` is a working savepoint, but it would have to move to `healdan-core`.
   `EmailIndex.put` needs the same answer (§4.2).
14. **Q14. What ends a lock cycle on PostgreSQL?** `FileLocks` relies on InnoDB's lock-wait timeout for one rare
    cycle, and PostgreSQL has none by default. A `lock_timeout` for the pool would do it, but its value, and where
    it's set, are open (§4.6).
15. **Q15. Does Healdan refuse or strip NUL in text it binds?** PostgreSQL fails on it where MariaDB doesn't, so a
    stray `%00` in a query parameter would be a 500 (§4.7).

## 7. Reproducing This

The scratch harnesses aren't in the repository. To repeat the main checks:

1. Start PostgreSQL (`docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=… -e POSTGRES_USER=healdan -e
   POSTGRES_DB=logicaldoc postgres:18`), which creates the empty database `logicaldoc`. `POSTGRES_USER` is a superuser
   (§5.2).
2. Run `logicaldoc-core.sql`, `logicaldoc-webservice.sql` and `logicaldoc-dropbox.sql` in that order, through
   `org.hsqldb.cmdline.SqlFile` (HSQLDB's `sqltool` 2.7.1) with `setContinueOnError(true)` on an autocommit pgjdbc
   connection, as `DBInit` does, or through `psql -v ON_ERROR_STOP=0`. Strip the scripts' carriage returns first if
   you pipe them in.
3. Translate the `mariadb/` migrations as §3.2 says and apply them through `psql`, each in a transaction, or with
   Flyway 13.10.3 plus `flyway-database-postgresql`, with `table("hd_schema_history")`, `baselineOnMigrate(true)`,
   `baselineVersion("0")` and `validateMigrationNaming(true)`.
4. For §2.5 and §2.6, copy `WEB-INF/lib` out of a built image and put it on a JDK 25 classpath. In a container, mount
   it anywhere but `/lib`, which hides the image's own libraries. Build CE's `LocalSessionFactory` on the database,
   with and without `hibernate.type.preferred_boolean_jdbc_type=INTEGER`, and subclass
   `HibernatePersistentObjectDAO<User>`. In a `TransactionTemplate` over `HibernateTransactionManager`, load user 1,
   change its name, call `queryForInt("select ld_enabled from ld_user where ld_id = 1")` and let it commit. Then read
   the name back with plain JDBC.
5. For §2.3's index, make a database per collation (`locale_provider builtin builtin_locale 'C.UTF-8'` for
   `pg_c_utf8`), fill a table with paths, index it, `analyze`, and `explain` a `like '<prefix>/%'`.
