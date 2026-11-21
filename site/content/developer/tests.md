---
title: Tests
weight: 3
---

## Java Tests

The tests use JUnit 4. [Building from Source](../building-from-source/) has the commands.

- **The base class** is `com.logicaldoc.util.junit.AbstractTestCase`. It builds a Spring context from the module's
  test `context.xml` and `context.properties`. For LogicalDOC's own modules that context has an in-memory HSQLDB,
  loaded from `getDatabaseScripts()`: for `logicaldoc-core` that's `sql/logicaldoc-core.sql` plus the test
  `data.sql`.
- **Each module has its own base class,** such as `AbstractCoreTestCase`, `AbstractWebappTestCase` or
  `AbstractWebserviceTestCase`. Extend the closest one.
- **Healdan's tests run on PostgreSQL,** a database for each test (see [The Test Database](#the-test-database)).
  `healdan-core`'s `AbstractHealdanTestCase` makes the test's database before the Spring context starts, and drops it
  when the test ends. It hands `AbstractTestCase` no scripts, so its HSQLDB steps do nothing, and loads LogicalDOC's
  schema and test data itself, from `databaseScripts()`, which a subclass overrides in place of
  `getDatabaseScripts()`. Then it empties the store (`store.1.dir`) and runs Healdan's migrations and setup steps.
  Ids restart with each test's database, while the store under `target/` stays, so a file an earlier test left would
  be taken for one of this test's documents.
- **The test's configuration** is its `context.properties`, which the base class writes again as
  `pg-context.properties`, with the test's database in `jdbc.url` and the server's user and password. A subclass
  names another file to start from with `baseConfigResource()`. LogicalDOC writes a changed setting back to its
  file, and a later test in the run reads it, so the next test starts from the `pg-` copy, unless the build has
  copied an edited `context.properties` in since.
- **Booleans first.** Once the context has started, and before it loads the schema, the base class checks that
  Hibernate binds Java booleans as integers (see
  [PostgreSQL](../how-healdan-extends-logicaldoc/#postgresql)), and fails naming
  `healdan-core`'s `hibernate.properties` if it doesn't. Without the setting, every test would fail in its first
  setup step, with an error that doesn't name the cause.
- **Plugin manifests.** Some tests load them from fixture jars in `src/test/resources` through
  `getPluginArchives()`. Those jars are kept by hand; they aren't built from source.
- Tests write their stores, indexes and temporary files under `target/`.
- **The sign-in throttle is on in every `healdan-core` and `healdan-api` test,** as on a real install. A test that
  gets one user's password wrong five times blocks that user for the rest of the test, unless the test then gives
  that user a new password, or creates the account after the failures: either clears its username's count.
- **The demo firm's test.** `DemoFixtureSeedTest` also runs the real manifest and files in `docker/demo/` on the
  test database, which takes about two and a half minutes. A step its user can't do, or a file the manifest names
  that's missing, fails the build. See [A Demo Firm for Screenshots](../development-stack/#a-demo-firm-for-screenshots).

### The Test Database

The tests in Healdan's modules need a PostgreSQL 18 server. `docker/tests/test-db.sh` runs one in Docker:

```bash
docker/tests/test-db.sh start   # postgres:18 as the container healdan-test-db, on 127.0.0.1:5432
docker/tests/test-db.sh stop    # removes the container
```

- **`start`** runs the container on the Docker network `healdan-test`, which it makes if it's missing, and publishes
  the server on `127.0.0.1` only, at port 5432, or the port `HEALDAN_TEST_DB_PORT` names. It waits until the server
  answers, then prints how to reach it from this machine and from a container. Run again while the server is up, it
  says so and leaves it running.
- **Its data is on a tmpfs,** and the server runs without `fsync`, `synchronous_commit` and `full_page_writes`, with
  300 connections, as nothing on it has to outlast the container. A run that was killed leaves its databases behind;
  `stop` clears them with the rest. It removes the server for every run using it, so don't stop it while another
  run's tests may still be going.
- **Its password is hashed with one SCRAM iteration,** where PostgreSQL's default is 4,096 (`-c scram_iterations=1`
  to `initdb`, which hashes it). Each test opens about 40 connections, most of them LogicalDOC's, which reads each
  setting of its context on a connection of its own, and the hashing was most of what a connection cost. The
  password is checked as before. A server started before `start` passed the setting keeps its old hash, as `start`
  leaves a running server as it is: `stop` and `start` it.
- **Each test has a database of its own** (`TestDatabase`, in `healdan-core`'s test-jar), in UTF-8 with PostgreSQL's
  built-in `C.UTF-8` locale, the encoding and locale Healdan uses on PostgreSQL, whatever the server's own default.
  So the tests sort and compare text as Healdan does there. Each name carries a number unique to the JVM that made
  it, so `healdan-core`'s, `healdan-api`'s, `healdan-storage`'s and `healdan-web`'s tests can
  run at once against one server.
- **It's a copy of a template.** The first test in a JVM to need a given list of scripts makes the template: an
  empty database from `template0`, LogicalDOC's scripts loaded into it, then Healdan's migrations. Every test's
  database is a copy of it (`create database … template`), which takes about 20 ms where loading the scripts and
  migrating took about 130. The JVM drops its templates as it ends; one that was killed leaves them for `stop`.
  - **A row the scripts or the migrations insert carries the template's time,** not the test's.
  - **The setup steps still run in every test,** in the test's own context, so a test sees what they made as it
    did before.
- **The template's tables are parked,** in a schema of their own, `healdan_parked`, which no unqualified name
  reaches, beside an empty `public`. LogicalDOC's context has to start with none of its tables in sight, as it did
  when every test began on an empty database: Healdan then finds no LogicalDOC schema as it starts, so it runs no
  setup step before the base class has emptied the store, and starts no worker. Once the context is up, the base
  class drops the empty `public` and renames the parked schema to it (`TestDatabase.unpark`), which fails if
  anything was made in `public` meanwhile. `TestDatabaseTest` pins both.
- **Three environment variables** say where the server is, and their defaults reach the one `start` runs:

  | Variable | Default | What it is |
  |---|---|---|
  | `HEALDAN_TEST_DB_URL` | `jdbc:postgresql://localhost:5432/` | the server's JDBC address, without a database |
  | `HEALDAN_TEST_DB_USER` | `healdan` | a user who can create databases |
  | `HEALDAN_TEST_DB_PASSWORD` | `healdan-test` | its password |

  With `HEALDAN_TEST_DB_PORT` set, set `HEALDAN_TEST_DB_URL` to the address `start` prints. The user of the server
  `start` runs is a superuser, which `VetoAtomicityTest` needs: it keeps one context and one database for its whole
  class, and its restores turn foreign keys off with `session_replication_role`.
- **Maven in a container** reaches the server on the `healdan-test` network: start the container with
  `--network healdan-test` and set `HEALDAN_TEST_DB_URL=jdbc:postgresql://healdan-test-db:5432/`, as `start`
  prints.
- **On a Linux host,** a connection to the published port goes through Docker's proxy, which about doubles each
  statement's round trip. CI's jobs give the tests the container's own address for that reason (see
  [Continuous Integration](../continuous-integration/)). Maven in a container on the `healdan-test` network
  doesn't go through the proxy either.
- **Without a server,** each test fails as it starts, with `Healdan's tests need a PostgreSQL. Start one with:
  docker/tests/test-db.sh start`.
- **The driver.** PostgreSQL's JDBC driver, pgjdbc, is a test dependency of `healdan-core`, `healdan-api`,
  `healdan-storage` and `healdan-web`, at the version the WAR ships (`postgresql.version` in `logicaldoc-parent/pom.xml`). `healdan-core`'s test `context.properties` names it in
  `jdbc.driver`, which the test `context.xml` reads as `${jdbc.driver}`, with `jdbc.dbms=postgresql`, PostgreSQL's
  Hibernate dialect, and a `jdbc.url` the base class always replaces.
- **LogicalDOC's own tests run on HSQLDB,** in memory, as its base classes make it, and need no server. So do the
  upstream tests CI runs among Healdan's (`UPSTREAM_TESTS`, see [Continuous Integration](../continuous-integration/)).
  HSQLDB's jars are on Healdan's test class path too: LogicalDOC's base class names its classes, and `TestDatabase`
  loads LogicalDOC's scripts with HSQLDB's `SqlFile`, as LogicalDOC's tests do.

In CI, each test job has a PostgreSQL of its own, set up as `start` sets up the local one (see
[How `build` Runs](../continuous-integration/#how-build-runs)).

### The Fake Sign-In Provider

Provider sign-in's tests, in `healdan-core` and `healdan-api`, run against `FakeProvider`, an OpenID Connect provider
in the test itself. It's in `healdan-core`'s test-jar, so `healdan-api`'s tests use it too.

- **It's a small JDK HTTP server** on `127.0.0.1` at a random port. It serves the discovery document, its keys and a
  token endpoint, and signs its ID tokens with Nimbus. Its authorisation endpoint isn't called over HTTP: the test
  reads the address a sign-in would send the browser to, and gets back the code the provider would send.
- **Its token endpoint checks what a real one does:** the client's id and secret, sent by a method its discovery
  document lists (`client_secret_basic` unless a test lists `client_secret_post`), a code used once, the redirect
  address the code was issued for, a verifier that matches the PKCE challenge, and refresh tokens that turn over on
  each use.
- **It can misbehave on purpose:** a bad signature, the wrong issuer or audience, an expired token, a refused or
  unanswered refresh, ID tokens on a refresh that are signed differently from a sign-in's (or not at all, as
  Microsoft's documentation describes Entra ID's), a discovery document it answers with an error, refresh tokens
  longer than a device token holds, a refresh answered with a body a test gives, answers padded past the size cap
  or trickled out a byte at a time, and the like, for the checks Healdan makes.
- **Plain HTTP.** While it runs it allows providers over plain HTTP, which only the smoke stack's overlay allows
  outside the tests. `SignInTestHooks` sets that by hand, sets the TLS provider calls trust (`providerTls`, which
  `FakeProvider.trust()` calls for one over HTTPS, so they trust its certificate only), and moves the clocks that
  time pending sign-ins, the helper's codes and the re-checks. A test that uses it resets it when it ends, or the
  setting leaks into the next test class.

### The API's Tests

`healdan-api`'s tests extend `ApiTestCase`, which builds on `healdan-core`'s `AbstractHealdanTestCase`, taken from
`healdan-core`'s test-jar. So they run on the same PostgreSQL server, each test with a database of its own and
Healdan's migrations and setup steps on it.

- **They drive the real `ApiServlet`** through Spring's servlet mocks, with its filters and providers, so a test
  goes through the token check, CORS and the error mapping as a client's request does.
- **Every answer is checked against `openapi.yaml`** (`Contract`), so a test fails when the server and the file
  disagree. Only a test-only route under `/api/v1/test/` can skip it, with `ApiCall.withoutContract()`, for an answer
  no client reads, such as one to a client that went away.
- **`RouteCoverageTest`** fails when the file describes an operation the server doesn't have, or the server answers
  a `/v1` route the file leaves out. `OpenApiFileTest` checks that the file parses.
- **The admin API has a file of its own,** `openapi-admin.yaml` (see the [API](../api/#the-admin-apps-routes)).
  `Contract` checks every answer under `/api/v1/admin` against it instead, and `RouteCoverageTest` compares the
  routes with both files, failing on a `/v1/admin` path in `openapi.yaml` or another in `openapi-admin.yaml`. A route
  the admin file describes before the server answers it, so the Admin app can be built against it, goes in
  `RouteCoverageTest`'s `DESCRIBED_AHEAD`, which fails once the server answers it, so the list never hides a route
  that's missing. It's empty now: the server answers every route the file describes.
  `OpenApiFileTest` checks that the admin file parses, uses the web session only and refers only to itself, and that
  its copies of `Page`, `FieldError` and the `Problem` response are the first file's, but for their words, and its
  `Problem` the first file's with fewer members. It also checks that every value of a string enum, in both files, is
  a string: YAML reads a bare `off`, `on`, `yes` or `no` as a boolean, as the Dashboard's tile status `off` would be.
  `DocsResourceTest` checks that the served `openapi.yaml` holds no admin route and that `openapi-admin.yaml` isn't
  served.
- **`AdminFilterTest`** checks the admin API's refusals through test-only resources under `/api/v1/admin/test/`,
  which count their calls: an administrator's web session gets through, a device token is
  `403 web-session-only`, someone not in `admin` `403 admins-only` (and someone taken out of it at their next
  request), no session `401`, and a route with no `@AdminRoute` is `403 admins-only` to everyone signed in, matrix
  parameters in the address or not. It also sends every admin route the server has an administrator's device token
  and a non-administrator's web session, and checks the filter refuses both. `AdminRoutesTest` scans the API's package for resource classes, test-only ones included, and fails on a route
  under `/v1/admin` whose class has no `@AdminRoute`, that is `@Public` or that locates a sub-resource, and on the
  annotation outside `/v1/admin`.
- **`ApiPluginXmlTest`** checks that JPF accepts the module's `plugin.xml`, which it would otherwise drop without an
  error.
- Their `context.properties` is `healdan-core`'s test copy, which the build copies in. Its public address,
  `server.url`, is `http://localhost:8080`, which is where `ApiCall` sends a request unless a test names another
  with `server(...)`: the sign-in pages send a start on any other name to the public address first.
- **`AddinResourceTest`** checks the Office add-in's pages and manifest: the files served, the Content Security Policy,
  and a manifest that names the server's own address. `/api/addin/` isn't JSON API, so `Contract` and
  `RouteCoverageTest` leave it out, as they do `/docs`.

### The Trash's Tests

The Trash's tests are in `healdan-core` (`com.logicaldoc.healdan.trash`) and `healdan-api`, with the upstream edits' tests
in `UPSTREAM_TESTS` (see [Continuous Integration](../continuous-integration/)). They use `HealdanFixtures` and
`AbstractRulesTestCase`'s layout, and call the job's `runOnce` themselves, as no real start happens in a test.

- **`TrashStoreTest`** checks the record's DAO: items, members, their states and the Trash's lists, and that an item of
  thousands of members changes state or moves in a few statements. It counts them on a data source of its own.
- **`TrashRecordingTest`** deletes a document and a folder by each route, the API's, the Classic Interface's RPC, WebDAV's,
  CMIS's and SOAP's, a merge's emptied source and `FolderTool`'s, and checks that each makes one item with the right
  members, that a matter's own folder's item names its matter, that a folder delete leaves a colleague's deleted document
  where it was, that a CMIS delete that stops short keeps only what it took, and that a delete whose item can't be
  written is refused.
- **`TrashRulesTest`** checks the rules on a whole tree: Delete or Move needed on everything inside, hidden items
  included, with no name in the refusal; a check-out or an immutable document inside; and a non-admin restoring only from
  the Trash.
- **`TrashRestoreTest` and `TrashRemovalTest`** check a restore bringing back only what that delete took, back where it
  was, a removed subfolder staying removed, the queue for indexing, and the removals of every route, a folder's
  removal taking its contents.
- **`TrashExistingSeedTest`** checks the seed that makes items of what was already deleted.
- **`TrashDestroyerTest`** checks `runOnce`: what was removed more than the days before is destroyed, documents and folders
  deepest first, with Healdan's rows and the record kept; what is within the days isn't; a matter's own folder and a Store
  stay removed; and a failure, an `Error` such as a missing class or service provider included, leaves the rest of its
  item, and only that, for the next night. It counts the queries for folders that take their security from another,
  which scan `ld_folder`, in JdbcTemplate's debug log: one an item, and a folder that starts taking its security from one
  during the run keeps it. `TrashStartupTest` starts the timer with its waits shortened, once in America/Chicago with
  the first run on a night whose clocks skip 02:47 (skipped if the JDK's time zone data has no such night ahead).
  `TrashTestHooks` is in the test-jar so that other modules' tests can fail a destroy.
- **`ReadableCountsTest`**, in `com.logicaldoc.healdan.rules`, checks `StoredRows`' counts of the folders and documents,
  deleted or not, that a person can read, which the Trash's views and a folder's contents give: publishing dates, a
  publisher, and an administrator's counts, which take in only documents still stored.
- **`FolderChangesTest` and `TrashResourceTest`** in `healdan-api` check the folder delete, move and contents, a move into
  another client's matter re-stamping its Client and Matter, the Trash's three views and who may read them, restore,
  remove and empty, each answer against `openapi.yaml`.
- **`HealdanTrashHooksTest`**, in `logicaldoc-webapp`, checks the Classic Interface's removals through the hooks, and
  that its delete takes a document before its shortcuts. Its folder moves are `RpcPermissionGapsTest`'s.
  `CeFlowsTest`, in `healdan-storage`, checks that a document the Trash destroys is held, and that a failed destroy
  strands no held file.

The upstream tests this adds to `UPSTREAM_TESTS`, one for each upstream file the Trash edits, are
`HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows` (the folder DAO's delete) and `#testRestore` (CE's own
restore path, left unchanged when the hook answers null), `HealdanTrashHooksTest` (the RPC services' removals and the
document delete's order in `DocumentServiceImpl` and `FolderServiceImpl`),
`DocumentManagerTest#testDestroyDocumentNamesItsOwnFile` and `#testDestroyDocumentInsideATransaction` (`destroyDocument`),
and `ResourceServiceImplTest#testMoveFolder` (WebDAV's move). `RpcPermissionGapsTest`, `SoapPermissionGapsTest` and
`HealdanHooksTest` were already there (`HealdanHooksTest#testFolderRestoreTakesTheScope` covers the restore's scope, and
`#testFolderDeleteTreeStoppedAtAnImmutableDocumentCallsTheAfterHook` `deleteTree`'s early return), and now cover `FolderServiceImpl.move`, `SoapFolderService.move` and the new hooks.

### The Admin App's Server Tests

The administration record's, the error capture's, the job status's, the Dashboard's, and the clients', matters',
folder access's, Stores', workspaces' and templates' tests are in `healdan-core` (`com.logicaldoc.healdan.admin` and
the packages under it), `healdan-api` and `healdan-storage`. No test makes a real start, so they call
`ErrorWriter.drainOnce()` and `UsageSummary.refreshNow()` themselves, and the tests of the threads start them;
`DashboardServiceTest` calls `forget()`, which drops what the Dashboard keeps, before it reads again.

- **`AdminEventsTest`** checks the record: who, what, when and from where, the detail's fields in the order of their
  names, no address outside an admin request, the record rolled back with its change and committed with it, a failed
  `record` failing its caller, a failed `recordAfter` logging no value and leaving its caller's transaction to commit,
  a secret with no value, and text too long for its column cut to fit. `ClientServiceTest` and `MatterServiceTest`
  check that their creates and edits are recorded, and `ClientServiceTest` that a rename whose record fails is rolled
  back.
- **`ErrorCaptureTest`** runs the appender on a Log4j context of its own (`capture-log4j.xml`): errors on additive and
  non-additive loggers, one appender however often it's attached, a new one after a reload, a storm of 5,000 that
  keeps the first 1,000, counts the rest and makes nobody wait, its own package's loggers and the writer's thread left
  out, detaching, and the caps.
- **`ErrorWriterTest`** checks the writer: the queue kept while the database is down and written once it's back, an
  error refused itself left out after three tries, and its count started again when the database goes down between
  them, a database that fails every write (a timeout, a lock, a full disk, a read-only database, an exhausted pool)
  leaving nothing out, which failures count as a refusal, the prune once a day, and, at a real start, the appender on
  the JVM's own logging, for a logger nothing else in the fork uses.
- **`ErrorStoreTest`** checks the tables: one row per fault with its counts by the hour, an older error written late,
  the caps and text the database would refuse, resolving, reopening and a resolved fault seen again, the list's time
  windows, filters and paging, and the prune. `ErrorFingerprintTest` checks the fingerprint, the logger's name in it
  when the error was logged from another class, and the documents a message names, and `ErrorAreasTest` the areas.
- **`JobsTest`** checks the job status: a worker's report, start, end, result and error, the error its exception's class
  and message, capped; reporting that never throws; a first run known before it's made; Healdan's jobs first, in their
  order, then LogicalDOC's six tasks; a Healdan job that says it doesn't run here switched off; the email index `done`
  once its setting says so; a task's error, the latest its logger logged since its start, `Task.run`'s stop not a
  failure, and two tasks failing alike each with their own; and overdue, never while running or switched off.
  `PreviewWorkerTest`, `EmailIndexWorkerTest` and `TrashStartupTest` check that their jobs report, and
  `StorageJobsTest`, in `healdan-storage`, that the storage jobs are listed after `healdan-core`'s, switched off where
  LogicalDOC's Store isn't `HealdanStorage`, and that the mover, the purge and the retests report under their names, a
  purge that fails saying why.
- **`DashboardServiceTest`** checks Needs Attention and Server Health over fakes of each source: each attention code,
  several backends and Stores, the ten tiles in order and those that need attention, a part that fails leaving the
  rest whole, a source that can't be read unchecked, a slow one still being worked out, storage backends off, the
  database's tile on PostgreSQL, Sign-In and Outgoing Email, and a second read coming from what's kept.
  `DashboardScreensTest` pins where the links go: each shipped screen's Admin address, the Dashboard, Clients, Matters
  and Workspace Templates, and the Classic Interface for every other. `CachedTest`
  checks what's kept: one ask per period however many read, the last answer while it's asked again, none older than
  two periods, a failure kept like an answer, `forget()` and a clock set back. `WordsTest` checks the tiles' words,
  and `ProviderClientTest` that `answers` fetches a provider's discovery document afresh and remembers a failure for
  30 seconds.
- **`UsageSummaryTest`** checks the Usage: nothing before the first summary, the totals, 30 days of documents,
  storage by Store with the backends the storage port names and a port that fails, the busiest matters, each matter's
  figures, the stamps read trimmed, the people signed in, reading the summary running no query (counted on a
  `CountingDataSource`), a failed refresh keeping the last summary, stopping during a refresh logging no error, and
  the summary's own thread.
- **`IndexCountsTest`** checks the failed, the waiting and the indexed, beside shortcuts, the Trash, skipped and
  archived documents; the latest of 200 tries deciding; two events at the same moment taken in the order they were
  written; and the failed list's order, matters and paging.
- **`AdminDashboardResourceTest` and `AdminFindResourceTest`**, in `healdan-api`, check the Dashboard's route, the
  usage route, before the first summary and after a failed refresh too, the find's clients, live matters and people,
  at most five of each, wildcards and the escape character `!` taken as they are, a blank query `422`, and the
  refusals of a non-administrator and of a device token, each answer against `openapi-admin.yaml`. `ListingSqlTest`
  checks the `like` patterns the lists and searches share.
- **`AdminClientsResourceTest`** checks the clients' routes: the list's columns before and after the summary, `q` by
  a number's start and a name's part, each sort both ways with ties by number and blanks last, paging, the next
  number, a create and its record, a rename that changes only the name and records nothing when the name is the same,
  each refusal changing and recording nothing, in the check order, a create that loses a race for its number answered
  as a used one, no delete, and the refusals of a non-administrator and of a device token.
- **`AdminMattersResourceTest`** checks the matters' routes the same way: the list's columns, a matter made since the
  summary, a deleted owner and template, `q` and each filter, alone and together, each sort, paging, and 10,000
  matters paged, sorted and searched; the next number, which a matter in the Trash keeps taken; a matter's page with
  its counts of documents that failed to index and previews given up on; the create, its refusals, the permission
  rules among them, and its race; the check; the edit, with `not-editable` members and a description cleared; the
  move, into the same Store, onto a name taken in another case, and with a document checked out; and the delete, its
  Trash item and counts, a restore that brings the matter back to its Store, and its refusals. `MatterCheckTest`, in
  `healdan-core`, checks each problem code and when it's looked for, their order, that the create refuses what the
  check reports, that the check writes nothing, and that only an administrator may ask. `MatterServiceTest` checks
  that a create or edit whose record fails is rolled back, and `IndexCountsTest` and `PreviewJobsTest` the counts for
  one matter.
- **`FolderAccessServiceTest`**, in `healdan-core`, checks a workspace's folder access: rows from its own permissions
  and from the folder it follows, rows that grant nothing left out, the owner's row, exact flags saved, P1 and P2
  refused inside a matter and saved in a template, the owner's row kept on the matter's folder, the cap on `guest` and
  read-only people, groups and people that no longer exist, a person named by id or by their own group, an empty
  list, Follow Parent, what's in scope, the records, and a refused save changing and recording nothing. Its
  `testApplyToSubfoldersOpensUpARestrictedFolderBelow` pins what LogicalDOC's `applySecurityToTree` does to a
  restricted folder below, a search folder and a folder in the Trash. `AccessLevelTest` checks the levels' exact
  matches, and `AdminFolderAccessResourceTest`, in `healdan-api`, the routes, their check order and their refusals.
- **`StoreServiceTest`**, in `healdan-core`, checks New Store and who can see a Store: a Store a matter can be made in
  at once, its record, a create whose record fails leaving no folder, the create's refusals, Read given and other
  permissions kept, each row as Can See or Custom, a change of nothing neither written nor recorded, a Store that
  followed another folder's permissions getting its own, what's a Store, and the refusals, P2's among them: a group a
  matter lets in refused while it can still read the matter, the owner passing while they can read the Store, a matter
  that already let in more blocking no other change, and only live matters with their own permissions checked.
  `AdminStoresResourceTest`, in `healdan-api`, checks the Stores' routes, their check order and refusals, P2's among
  them, and `AdminGroupsResourceTest` the groups' list: by name, without people's own or deleted groups, `q` and
  paging. Each also checks the refusals of a non-administrator and of a device token.
- **`WorkspaceAdminTest`**, in `healdan-core`, checks a workspace's folders: what's in scope, a search folder directly
  in the Templates Store among what isn't; a matter's tree and a template's, in order, hidden folders kept and aliases
  and deleted ones left out, search folders last; New Folder at a matter's top level, deeper and in a template, taking
  its parent's permissions and the matter's stamps, and its refusals, a name a hidden folder or a search folder has
  among them; renames, a change of case and the folder's own name, and their refusals; moves, a folder narrowed to fit
  its new parent inside a matter, and the refusals (the top folders, another matter, a template into a matter, a Store,
  into itself, a name taken, a document checked out); the values a folder sets, local, inherited and the default,
  and setting them, passed down to a folder and a document, back to inheriting, and Client refused; and search folders,
  their rule's round trip, a change recording only what changed, and their refusals. Each refusal writes nothing.
  `TemplateAdminTest` checks the list's counts, an empty template following the Templates Store, a copy (its folders,
  permissions of their own and followed, values, a search folder's rule, the template it copies unchanged), the
  create's refusals, the rename, and the problems: none for a clean template, a folder wider than its parent, a folder
  that keeps following a folder above its parent, and a group that can't read a Store, the template still renamed and
  added to and New Matter refusing it. `AdminWorkspaceResourceTest` and `AdminTemplatesResourceTest`, in
  `healdan-api`, check each route's answer, its check order and refusals, the records, the deletes' Trash items and
  their restore (a matter's folder comes back; a template's, and a template's folder, are `409 place-gone`), a matter
  made from a deleted template unchanged but for its template, and the refusals of a non-administrator and of a
  device token.

### The Storage Tests

`healdan-storage`'s tests extend `AbstractStorageTestCase`, which builds on `healdan-core`'s `AbstractHealdanTestCase`,
taken from `healdan-core`'s test-jar, as the API's tests do, so they run on the same PostgreSQL server, a database for
each test.

- **They run LogicalDOC through Healdan's storage.** The base class takes `healdan-core`'s test `context.properties`,
  which the build copies in, and writes it again as `context-storage.properties` with `store` set to
  `HealdanStorage`. That's its `baseConfigResource()`, which `AbstractHealdanTestCase` then writes again with the
  test's database. It registers the module's `plugin.xml` with `PluginFixtures.archive("healdan-storage")`. So
  `CeFlowsTest` creates, checks in, replaces, deletes, purges and copies documents through LogicalDOC's own code, as
  a server does.
  `healdan-core`'s and `healdan-api`'s tests keep LogicalDOC's own store, as neither module can depend on
  `healdan-storage`.
- **Their folders are under `target/`.** The build sets `healdan.storageStaging`, `healdan.storageCache` and
  `healdan.storageKeys` there, as `docker/setenv.sh` does in the image, and `healdan.secretKey` in `target/conf/`, a
  configuration folder that no local backend may share. Each test starts with them empty.
- **Tests run the background work themselves.** As a real start never happens in a test, nothing runs on a timer:
  a test calls `Mover.runOnce()`, the purge's and the nightly check's `run()`, or a backend's check, when it needs
  one. The tests of the timers themselves, such as `StorageStartupTest`, start them with their waits shortened.

CI's runners have no S3 service, and the test shards don't run Docker, so storage is tested in three layers:

| Layer | Where | What it proves |
|---|---|---|
| `MemoryBackend`, and `LocalBackend` under `target/` | most of the module's tests | `HealdanStorage`, the mover, held deletes, the read cache, Store tracking and the Storage tab's server side. `MemoryBackend` keeps bytes in a map and takes faults on demand: a refused call, a failure after so many bytes, an outage, a slow call. `MemoryBackend.s3Like` behaves as a bucket does: a staged copy is at its key at once, and a hold sends nothing. `GatedBackend` wraps another and holds its calls at a gate, so a test acts at a known point of a transfer without sleeping. `LocalBackend` writes real files: the marker, `.held`, syncs and renames, and encryption at rest |
| `FakeS3`, an S3 server inside the test (below) | `S3BackendTest` | the S3 client's requests as they leave Healdan, and its answers to every failure |
| Garage, a real S3 server | the smoke test's step 5f (see [The Stand-In S3 Server](#the-stand-in-s3-server)) | the whole flow through the image: uploads, moves, reads, an outage and a restart |

`BackendContract` holds the tests every backend must pass, which `LocalBackendTest`, `MemoryBackendTest`,
`MemoryBackendS3LikeTest` and `S3BackendTest` each run. `HoldRollbackTest` checks that a delete whose transaction
rolls back puts each copy back from `.held/`, before the transaction's file marks clear, over the transaction's own
write but never another's, and that a transaction of its own inside it keeps its own; `CeFlowsTest#testDestroyRolledBack`
does the same for LogicalDOC's destroy on a local Store and an S3 one. `HoldRollbackTest` also checks what mends a
copy left under `.held/`: the purge's retry of a put back that failed, a due older hold that puts the row's copy back
rather than purge it, and a hold whose row can't be written. `TinkClasspathTest` checks that encryption runs without the
`gson` and `protobuf` classes Tink brings, which the plugin zip leaves out.

`StorageAdministrationImplTest` checks the storage port the Admin app reads (see
[How Healdan Extends LogicalDOC](../how-healdan-extends-logicaldoc/#healdans-code)): that `healdan-core` finds one by
type, and that it answers what the Storage tab's overview does, field for field, with no secret in it.

#### The Fake S3 Server

`FakeS3` is a small JDK HTTP server on `127.0.0.1` at a random port, over plain HTTP (`start()`) or HTTPS
(`startTls()`), that holds its buckets in memory. It takes path-style requests only, as Healdan sends them, and
answers the calls `S3Backend` makes: putting, getting (with a range), heading, deleting and listing objects, and
uploads in parts (create, upload a part, complete, abort).

- **It refuses what S3 refuses:** a `Content-MD5` that doesn't match the bytes (`400 BadDigest`), a body whose length
  or SHA-256 isn't the one the request signed, another access key or region, a missing bucket, key or upload, a part
  list that doesn't match the parts, and a part under 5 MiB but the last. It doesn't check signatures:
  `refuseSignatures()` refuses as a wrong secret would.
- **It sees both shapes of request.** Over plain HTTP the S3 client sends `aws-chunked` bodies in signed chunks, as
  Garage gets them, which `FakeS3` decodes before any check. Over HTTPS it sends a plain body, as Wasabi gets it.
  `testContentMd5OnEveryUpload` checks, in both shapes, that every upload and part carries a `Content-MD5` and no
  `x-amz-checksum-*` header.
- **It records every request,** in `requests()`, and misbehaves on demand until `behave()`: it fails, stalls, delays,
  drops the connection, corrupts or cuts off a body, answers without a body or a length, or denies access. Close it
  in an `@After` method.
- **It holds a put of one key until the test lets it go** (`holdPut()`, `release()`), then answers it under the rules
  in force then. `StorageAdminTest` stops a backend's add or save at its marker this way, to check what other writes
  do meanwhile.
- **No test needs real DNS.** `FakeDns` answers the endpoint rule's address lookups from a map while it's open, so
  `EndpointsTest` checks which `http://` addresses count as private.

### The Web Module's Tests

`healdan-web`'s Java tests check the servlets that send the apps, against small fixture builds under
`src/test/resources/healdan/app/`, one for each app. Run them with `-Dhealdan.web.skip=true`, which leaves the Node
build out (see [Building from Source](../building-from-source/#commands)).

- **`AppServletTest`** drives `AppServlet` through Spring's servlet mocks, with no database: the files and their
  caching, the pages, the sign-in redirect and the return path, and that no path, however it's escaped, reaches the
  Admin app's build beside the User app's.
- **`AdminAppServletTest`** extends `AbstractHealdanTestCase`, from `healdan-core`'s test-jar, so it runs on the test
  database, with real people signed in to real web sessions: the check under test is the one the server makes. It
  checks the spec's table (signed out, signed in without `admin`, and an administrator, for a page and for a file),
  that a file that's there and one that isn't get the same refusal, the policy's text, the caching, `GET` and `HEAD`
  only, an administrator taken out of `admin` refused at the next load, a check that fails answering `503` to try
  again with the return path kept, and a server built without the apps.
- Like the API's tests, they take `healdan-core`'s test `context.properties`, which the build copies in, and
  `healdan-core`'s Spring scan finds the API's beans, so the module's test class path is `healdan-api`'s. The build
  sets `healdan.secretKey` under `target/`.

## Docker Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | the init tool's unit tests, among them `DbInitPlanTest`, which reads the tables and columns of the WAR's own DbInit scripts, `ld_tenant`'s 14 quota thresholds among them, and `PluginInstallTest`, which installs LogicalDOC's plugins as the init tool does, checks that each one started and that the installs edited a copy of `web.xml`, which the image then ships, then starts them again as Tomcat does after it, which must find them all installed and ask for no restart. Then three checks on the WAR's own libraries, which Maven's tests can't do: a module's tests have every library's dependencies but only that module's libraries, and the WAR has only what the plugin zips ship, from every plugin. `WarParsersTest` parses a `.pptx`, an `.xlsx` in Excel's Accounting format and an `.xls` with the server's parsers. `WarStorageTest` sends one request to a closed loopback port with the S3 client, and puts 3 MiB through Tink's streaming AEAD. `WarHibernateTest` checks that the libraries hold one `hibernate.properties`, `healdan-core`'s, with `hibernate.type.preferred_boolean_jdbc_type=INTEGER`: Hibernate reads the first it finds, so a jar that brought its own would drop the setting |
| `docker/tests/test-init.sh` | run by hand: the init tool against a throwaway PostgreSQL, started as `compose.yaml`'s `db` service is, its role script included. The checks of the server and the database, the role's 50-second `lock_timeout` set and read back on a new connection, LogicalDOC's plugins installed on the first run and not again, and a start refused while LogicalDOC's tables lack what the scripts create: `ld_tenant` without the 14 quota thresholds upstream sync #215 brought, then with them put back and the SQL of each change Upgrading's [Changes to LogicalDOC's Tables](../../install/upgrading/#changes-to-logicaldocs-tables) lists run as the page gives it (none yet, so it says it skipped that), and `ld_webservicecall` renamed away. Section 7 refuses a database that isn't UTF-8 and a role that can't create tables, making nothing in either, and starts one that doesn't sort by code point, with a warning. Section 8 is the guard against starting an install empty: a conf volume that names MariaDB, with PostgreSQL not answering (it gives up after 15 seconds, whatever `DB_WAIT_SECONDS` says) or answering with no move; a move that didn't finish; a finished one, where the start goes on and puts the conf volume's database settings right; and an empty database beside a repository that holds an install's files. Each refusal leaves the conf file as it was, byte for byte, and the database's tables and the role's settings too. `TEST_INIT_TOOLS` names the tools image it builds, and `TEST_INIT_PREFIX` its network, container and volumes, so two runs can share a machine |
| `docker/tests/test-image.sh` | run by hand: the image. `move-from-mariadb` runs before the entrypoint touches a volume, and leaves all three empty; then a prompt stop during init, file ownership, the `/setup` block, Admin > Restart, settings that persist. `TEST_IMAGE` names the image it builds, and `TEST_IMAGE_PREFIX` its network, containers and volumes |
| `docker/tests/test-overlays.sh` | run by hand: the dev and proxy overlays, the dev overlay's PostgreSQL port answering as PostgreSQL does before any sign-in. `OVERLAYS_PROJECT`, `OVERLAYS_NETWORK`, `OVERLAYS_PORT` and `OVERLAYS_DB_PORT` name its project, the proxy's network and the two ports, and `LOGICALDOC_IMAGE` the image it builds |
| `docker/tests/test-move.sh` | the move from MariaDB (see [The Move's Test](#the-moves-test)): the frozen fixture moved with `move-from-mariadb` in each way that matters, then the app started on the result. CI runs it on amd64 after the smoke test: `docker/tests/test-move.sh`, with `LOGICALDOC_IMAGE` naming a built image |
| `docker/tests/test-fulltext.sh` | full-text search: a `.docx` and an `.eml` are indexed and found by their words |
| `docker/tests/test-previews.sh` | Office and drawing previews: converted ahead of time, text, markup and thumbnails, no fetching or macros while converting, a broken file failing cleanly, leftover processes cleaned up |
| `docker/tests/test-proxies.sh` | which forwarded addresses Tomcat believes with `LOGICALDOC_TRUSTED_PROXIES` unset: a header from the Docker host, but not a private address in the chain or a header sent from inside the app's container. It reads the sign-in throttle's per-address counters, so it needs a running stack: `BASE=http://127.0.0.1:8080 APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-proxies.sh` |
| `docker/tests/test-api.sh` | the API through the image's real filter chain, as the non-admin `apiuser` that the `smoke-api-user` setup step makes (`test-web.sh` signs in as `webuser`, made by `smoke-web-user`, since this script leaves `apiuser` blocked by the throttle for 15 minutes): LogicalDOC's SOAP services answering 404, M2's client flow (sign in, find the smoke matter, file a `.docx`, check it out, save twice, download two versions, sign out), the docs page, a session cookie refused without a token, the sign-in throttle, and LogicalDOC's `loginApiKey` refusing the token. Then M3's additions: searching inside the matter, a new folder and a favourite, a stamped `.docx`, a second device refused with `held-elsewhere`, moving the check-out, `ifVersion` and a repeated `saveId`, and the `#1234 v2.1` lookup. Then M5's: an `.eml` inspected, filed with its attachment and found by its Message-ID, and `already-filed` for filing it again and for uploading it into the matter. Run it against a fresh stack with the smoke overlay: `BASE=http://127.0.0.1:8080 docker/tests/test-api.sh` |
| `docker/tests/test-web.sh` | the User app and the Admin app in a browser, against a stack with the smoke overlay (see [The Web Apps' Tests](#the-web-apps-tests)): Playwright's specs in Chromium and WebKit, then `curl` checks of the web session's forgery refusals and the Admin app's refusals. Needs Docker, curl and Python 3: `BASE=http://127.0.0.1:18080 docker/tests/test-web.sh`, which `docker/smoke-test.sh` runs as step 9, the last. It needs the Indexer set to every few seconds (see [The Web Apps' Tests](#the-web-apps-tests)). With `WEB_RESULTS=<folder>` it keeps each failed test's trace and screenshot (see [What a Failed Run Keeps](#what-a-failed-run-keeps)) |
| `docker/tests/test-signin.sh` | provider sign-in through the stand-in Keycloak (see below), with `curl` and a cookie jar: the web page's sign-in ending at the User app, signed in, a local account linked by email losing its password and its device token, the gate refusing someone in none of the mapped groups, the helper's sign-in handing a code to a pretend loopback address and the code exchanged for a device token, and a person disabled at Keycloak losing that token at its next re-check. Run it against a fresh stack with the sign-in overlay: `BASE=http://127.0.0.1:8080 KEYCLOAK=127.0.0.1:18180 docker/tests/test-signin.sh` |
| `docker/tests/test-storage.sh` | storage backends against the stand-in Garage (see below): a file filed in Smoke Cloud reaching Garage encrypted, files moved in from Smoke moving up and not cached, reads through Garage and from the read cache, then with Garage stopped a save waiting in staging, an uncached file answering `503` through the API, `/download` and a shared link, and `/convertpdf` of an uncached PDF serving LogicalDOC's placeholder, and a matter moved to Smoke bringing its files home while Garage keeps them held. With `after-restart` it checks the same rows after a restart, a first read through Garage, and a read from the cache rebuilt from its folder with Garage stopped. It stops and starts Garage itself, and needs curl 8.0.0 or later, jq, sha256sum and docker: `docker/smoke-test.sh` runs it, passing it the stack's containers, as step 5f and again in step 8 |
| `docker/smoke-test.sh` | end to end: build, start, REST login, the API answering, the forwarded addresses Tomcat believes (`test-proxies.sh`, step 2b), the sign-in page showing no alert on the first start, such as "The application has to be restarted", the GUI's loaders sent with `Cache-Control: no-cache`, a Content-Security-Policy with `object-src 'none'` and Tomcat's JVM having its Base64 intrinsics off, though the stack's `CATALINA_OPTS` asks for them (step 2), uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, the stack running on Healdan's storage (its setup steps recorded and Original's marker written, step 5), PostgreSQL itself in step 5 (the app's role waiting 50 seconds for a lock; an email's subject and file name, a new matter's name and a document's name keeping their emoji, the matter made through the Classic Interface's New Matter service as client 1's next free number, 1.5 on today's stack, which stays for the later steps; a NUL in a query parameter answered `400 bad-request`; and `apiuser` disabled and enabled again through the Classic Interface's service, each edit read back from the database and the API, as LogicalDOC's typed reads lost such an edit on PostgreSQL without Healdan's fix), PostgreSQL's own log after steps 7 and 9 (see [PostgreSQL's Log](#postgresqls-log)), M2's client flow through the API (`test-api.sh`, step 5c), the User app in Chromium and WebKit (`test-web.sh`, step 9, the last), the helper's open-save-close cycle and its sign-in through the stand-in Keycloak (step 5d, only with `SMOKE_HELPER_TEST=1` and the .NET SDK), provider sign-in through the stand-in Keycloak (`test-signin.sh`, step 5e), storage backends against a stand-in S3 server, Garage (`test-storage.sh`, step 5f, and again after step 8's restart), the Office add-in's manifest naming the stack (step 2), and data surviving a restart, which checks LogicalDOC's tables, replaces an older image's `cachecontrol` default and makes a non-breaking space in `security.csp` plain (step 8). Its stack's Tomcat writes an access log, which the image doesn't have (see [The Access Log](#the-access-log)): step 2 checks it's written, and step 8 that the restart kept it. With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one. With `SMOKE_RESULTS=<folder>` a run that fails keeps its logs and its failed browser tests' traces there (see [What a Failed Run Keeps](#what-a-failed-run-keeps)). Two can run on one machine at once (see [Two Smoke Tests at Once](#two-smoke-tests-at-once)) |

`docker/tests/bulk-documents.sh` isn't a test: it fills a folder on a throwaway stack with generated `.docx`, `.pdf`,
`.xlsx`, `.eml` and `.odt` files, five uploads at a time, to measure big folders and searches. Give it the stack's
address, a device token of someone who may file there, the folder's id and how many:
`docker/tests/bulk-documents.sh http://127.0.0.1:8080 hd_… 1234 2000`. A fifth argument, a word, goes into every
file, for a search that finds them all.

`docker/tests/test-db.sh` isn't a test either: it runs the PostgreSQL that Healdan's Java tests use (see
[The Test Database](#the-test-database)).

### The Stand-In Sign-In Provider

The smoke stack signs in through a real OpenID Connect provider, a throwaway Keycloak, as well as with passwords.
`docker/smoke-test.sh` adds the overlay `docker/tests/compose.signin.yaml`, which:

- **runs Keycloak 26.7.5** (`quay.io/keycloak/keycloak`) in development mode, which imports the realm `healdan` from
  `docker/tests/keycloak/healdan-realm.json` each time its container starts. The realm has a confidential client
  `healdan` that requires PKCE `S256` and puts the person's groups in the ID token's `groups` claim, the groups
  `healdan-users` and `healdan-other`, and four people: `sso-user`, `sso-helper` and `sso-helper2` in
  `healdan-users`, and `sso-outsider` in `healdan-other`. Their passwords are throwaway ones, in the realm file.
  `sso-helper2` is the helper's stack test's (step 5d), so step 5e disabling `sso-helper` doesn't touch it.
- **publishes it on `127.0.0.1:18180` only** (`SMOKE_KEYCLOAK_PORT` changes the port). Keycloak names itself
  `http://keycloak:8080` in its discovery document and tokens, the address the app reaches it at on the stack's
  network. `test-signin.sh`'s `curl` sends that name to the published port with
  `--connect-to keycloak:8080:127.0.0.1:18180`. The smoke test waits for its health in step 1, through the
  container's health check, since Keycloak answers it on its management port, 9000, which isn't published.
- **sets Healdan up to use it.** It sets `HEALDAN_SIGNIN_ALLOW_HTTP=true`, as Keycloak's address is plain HTTP, and
  the provider's issuer, client id and secret in `HEALDAN_SMOKE_SIGNIN_ISSUER`, `HEALDAN_SMOKE_SIGNIN_CLIENT_ID` and
  `HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET`. With all three set, and the smoke overlay's `HEALDAN_SMOKE_FIXTURE=true`,
  which ties the step to the smoke test's stack, the `smoke-signin` setup step adds the provider `smoke`,
  linking by email, maps `healdan-users` to a new group `Smoke SSO`, makes a local account `sso-user` with
  Keycloak's `sso-user`'s email and a password, so the first sign-in links it, and sets `signin.recheck.minutes` to
  `0`, so a device token is checked with Keycloak on every request. Production never sets these variables, and
  without all four the step does nothing.

Step 5e times itself, and step 1 prints how long Keycloak took to start, by its own log. Step 5d's
`StackProviderSignInTests` signs in through it too, as the helper does (see
[The Helper's Stack Tests in the Image CI](../continuous-integration/#the-helpers-stack-tests-in-the-image-ci)).

### The Stand-In S3 Server

The smoke stack keeps a Store's files in a real S3 server, a throwaway Garage, as well as in Original's folder.
`docker/smoke-test.sh` adds the overlay `docker/tests/compose.storage.yaml`, which:

- **runs Garage v2.5.0** (`dxflrs/garage`), a single node set up by `docker/tests/garage/garage.toml`, with fixed test
  values. Its data is in a volume of its own, so step 8's restart keeps the objects, as the app's volumes keep the
  rows.
- **sets it up before the app starts.** A one-shot `busybox:1.37` service, `garage-setup`, shares Garage's network
  and runs `docker/tests/garage/setup.sh` against Garage's admin API, as Garage's image has no shell: it applies the
  one-node layout, imports the fixed test key, and makes the bucket `healdan-smoke` and allows the key on it, each
  only if it isn't done, as step 8 runs it again on the kept volume. The app waits for it to finish, and step 1
  prints how long Garage took to start and be set up.
- **publishes Garage's S3 API on `127.0.0.1:13900` only** (`SMOKE_GARAGE_PORT` changes the port), for
  `test-storage.sh`'s `curl`, which reads the bucket with `--aws-sigv4`. The app reaches Garage at
  `http://garage:3900` on the stack's network, an address the endpoint rule allows, as it's private.
- **sets Healdan up to use it.** It sets `HEALDAN_SMOKE_STORAGE=true`, and Garage's endpoint, region, bucket, access
  key and secret in `HEALDAN_SMOKE_S3_ENDPOINT`, `HEALDAN_SMOKE_S3_REGION`, `HEALDAN_SMOKE_S3_BUCKET`,
  `HEALDAN_SMOKE_S3_ACCESS_KEY` and `HEALDAN_SMOKE_S3_SECRET`. With them, and the smoke overlay's
  `HEALDAN_SMOKE_FIXTURE=true`, the `smoke-storage` setup step adds the encrypted S3 backend `Smoke Garage`, with the
  prefix `healdan/`, through the same add the Storage tab uses, and confirms its recovery key. It then makes the
  Store `Smoke Cloud` on it, readable by Everyone as `Smoke` is, with matters 1.2 and 1.3, and sets
  `storage.sweepSeconds` and `storage.retestSeconds`, which have no screen, to 5, so the checks wait seconds, not
  minutes. Production never sets these variables.

Step 5f runs `test-storage.sh`, which stops and starts Garage itself, and step 8 runs it again with `after-restart`.
It needs curl 8.0.0 or later: only from 8.0.0 does `--aws-sigv4` send the `x-amz-content-sha256` header, without which
Garage refuses a request. Step 5f prints how long it took. The first read to meet the outage waits about 20 seconds
for the S3 client to give up, and later reads fail at once, as they would on a server.

### Two Smoke Tests at Once

`docker/smoke-test.sh` runs its stack as a Compose project of its own, `logicaldoc-smoke`, which names the stack's
containers, volumes and network, and publishes the app, Keycloak and Garage on `127.0.0.1` ports `18080`, `18180` and
`13900`. CI uses those. A second run on the same machine, such as another Claude session's, gives itself another
project name and three other ports:

```bash
SMOKE_PROJECT=smoke-b SMOKE_PORT=28080 SMOKE_KEYCLOAK_PORT=28180 SMOKE_GARAGE_PORT=23900 docker/smoke-test.sh
```

- **The project's name is lowercase letters and digits joined by `-` or `_`.** It also names the image the run
  builds, `<project>:local`, unless `LOGICALDOC_IMAGE` names one, so a second run's build doesn't retag the image
  that the first run's step 8 starts its app from again. The image stays after the run, so
  `SMOKE_PROJECT=<project> SMOKE_BUILD=0 docker/smoke-test.sh` runs the tests again without building it, and says
  when it was built, and `docker image rm <project>:local` removes it. With `SMOKE_BUILD=0` and no such image, it
  refuses: `docker compose build` tags `logicaldoc-community:local`, which `LOGICALDOC_IMAGE` has to name.
- **It claims the project and its ports before it builds anything,** with a container, `<project>-claim`, which
  Docker makes only once, so of two runs started together one gets it. The claim holds the three ports through the
  build and stops just before the stack takes them, and it keeps the project claimed until the run removes its stack.
  The run sets its exit trap only once it has the claim, so a run only ever removes the stack it made.
- **It refuses a claimed project,** which is another run's, possibly still building, or was left by a run stopped
  before it could remove its stack. Once no run is using it,
  `docker rm -f <project>-claim && (cd / && docker compose -p <project> down -v --remove-orphans)` removes it: from a
  folder with no `compose.yaml`, Compose needs only the name. It also refuses a project that has containers or volumes
  and no claim, which is another stack, such as a development stack, and offers no command to remove it.
- **It refuses a port setting that isn't a number from 1 to 65535, and a port something already answers on,** such as
  another run's stack or claim. It names every setting to change at once.
- **The builds take turns at Maven.** The `Dockerfile`'s Maven cache mount is locked, as each build deletes the
  reactor's own artifacts from it when it ends. A second build waits there for the first, unless it builds the same
  files, which BuildKit builds once for both.
- **Step 5d builds the helper's tests in the checkout,** so two runs from one checkout can't both set
  `SMOKE_HELPER_TEST=1`. Runs from separate worktrees can.
- **Each run that sets `SMOKE_RESULTS` names a folder of its own.** The script refuses one that holds anything.

In two runs side by side on a Mac, each stack used up to about 2 GB of Docker's memory, besides step 9's browser
container.

### PostgreSQL's Log

A statement PostgreSQL refuses is an `ERROR` line in its own log, whatever the Java around it caught, and some of
LogicalDOC's code catches failures and says nothing. So after step 7, and again after step 9, `docker/smoke-test.sh`
reads the `db` service's whole log (`check_db_log`) and compares each `ERROR` line, with the first line of the
statement PostgreSQL logged after it, to the patterns in `docker/tests/db-log-expected.txt`:

```text
PostgreSQL's log, steps 1 to 7: 62 lines read, 0 ERROR lines, 0 expected and 0 unexpected
```

- **The list** holds what may appear, each pattern with its reason: today only LogicalDOC's `StatsCollector`, which
  30 minutes after a start, then every 12 hours, counts twelve tables this edition doesn't have, each count failing
  and then asked again twice in the transaction it ended. A run meets them only if its app has been up that long,
  and the patterns were taken from a stack with the task's delay shortened. Nothing
  Healdan does in the ordinary way may be on the list: a line no pattern matches is a finding, not a pattern to add.
  A line's `DETAIL`, which can quote a row's values, is left out.
- **A line no pattern matches fails the run.** After how many lines each pattern matched (the `…` here), the check
  prints each unexpected line, the first 50, with the first line of its statement:

  ```text
  PostgreSQL's log, steps 1 to 7: 70 lines read, 4 ERROR lines, 3 expected and 1 unexpected
    …
    unexpected, which fail the run:
      2026-11-20 17:05:43.256 UTC [107] ERROR:  relation "hd_no_such_table" does not exist at character 21 STATEMENT:  select hd_name from hd_no_such_table where hd_id = 1
  FAIL  PostgreSQL's log, steps 1 to 7, has no ERROR line but those docker/tests/db-log-expected.txt expects (17:05:43 UTC)
  ```

  The statement is the place to start: find the code that sent it, and put it right there.
- **It fails too** when it can't read the log, when the log is empty or isn't PostgreSQL's (it has no `database
  system is ready to accept connections`), and when the list can't be read, so a clean log is one it read.

The start-up tool asks whether a table exists with `to_regclass`, never by reading it, so a start leaves no `ERROR`
line, and `test-move.sh` checks the moves left none either.

### When a Smoke Check Fails

The server's warnings go to its own log, `repository/logs/dms.log` in the repository volume, not to the container's
output. So when a check fails, or the script stops early, `docker/smoke-test.sh` prints the server's log before it
removes the stack. It's at the end of the script's output, after `SOME CHECKS FAILED` when a check failed, and in CI
at the end of the `Smoke test` step of the `Test (amd64)` or `Test (arm64)` job:

- **The app container's last 150 lines of output:** the entrypoint, the init tool and Tomcat's start.
- **Keycloak's last 40 lines of output,** for the stand-in sign-in provider.
- **Garage's last 40 lines of output, and its setup's,** for the stand-in S3 server.
- **PostgreSQL's last 100 lines of output,** the `db` service's log.
- **The run's warnings and errors from `dms.log`,** the last 200 lines of them. Each keeps its exception's message
  and `Caused by` lines, without the stack frames.
- **The last three API requests that failed with a `500`,** with their stack frames (80 lines each at most), which say
  where in the server they failed. A request whose client went away before its answer was sent isn't one: the API
  logs it in one line at info, with its frames at debug, and the dump skips one that an older server logged as a
  failure.
- **`dms.log`'s last 40 lines.**
- **The requests Tomcat took 2 seconds or more to answer,** from [its access log](#the-access-log), the last 40 of
  them, after a line that counts the log's requests. When step 9 was the first step to fail, only that step's: a
  browser test that waited on a request finds it there with its time, or learns that the server answered at once or
  not at all. Otherwise the whole run's, which has slow requests of its own (a start's first pages, the previews,
  step 5f's outage): a step that failed earlier may have broken the stack for step 9 too.

When a browser test in step 9 fails, `docker/tests/test-web.sh` also prints the page of each failed test as Playwright
saw it (its `error-context.md`), before the container that ran them goes. It's in the step's output, after the line
that names the failed tests.

`dms.log` and the access log cover the whole run. Step 8 replaces the container but keeps the volume, so its restart
doesn't lose the earlier steps' lines. Every `FAIL` line ends with its time in UTC, and the smoke stack logs in UTC,
so the logs' lines from around a failure are easy to find.

### The Access Log

The image's Tomcat writes no access log (`docker/server.xml` has no `AccessLogValve`), so on a server nothing records
each request. The smoke stack's does, so that a failed run can say whether a request reached the server, when, and
how long the answer took:

- **`docker/smoke-test.sh` makes the stack's `server.xml` from the image's own** (`smoke_server_xml`): it reads the
  file out of the image it tests and adds two valves to its `Host`. `docker/tests/compose.accesslog.yaml`, an overlay
  only the smoke test adds, mounts the result over the image's. So the stack still runs the `server.xml` the image
  ships, the two can't drift, and `docker/server.xml` stays as it is. The overlay's mount is the one place that
  names Tomcat's folder: the script reads the image's file there, and stops when the image's `CATALINA_HOME` isn't
  that folder.
- **The file is `docker/tests/.smoke-<project>.server.xml`,** in the checkout, which a Docker engine in a VM shares
  as the stack mounts other files from it, where the temporary folder may not be shared (Colima shares only the home
  folder). `docker/tests/.gitignore` leaves it out, and the run removes it at its end.
- **The log is `repository/logs/access.log`,** beside `dms.log` in the repository volume, which step 8's restart
  keeps. Tomcat writes a request's line when its answer ends, and the log is unbuffered, so a failed run reads every
  request answered so far.
- **A line is one request:** its start and end (UTC), the client's port, the thread that served it, the method, the
  path with its query, the status, the bytes sent and the time taken in microseconds. Tomcat ends a path that has no
  query with `-`. Requests on one connection share a client's port.

  ```text
  2026-11-20 07:15:02.123 07:15:02.180 rp=51234 http-nio-8080-exec-7 GET /api/v1/me- 200 412 57012us
  2026-11-20 07:15:02.201 07:15:02.215 rp=51234 http-nio-8080-exec-3 GET /api/v1/users?q=web 200 388 13544us
  ```

- **A request still being served after 10 seconds is reported while it runs.** The second valve, Tomcat's
  `StuckThreadDetectionValve`, writes a warning to the app's output with the request and its thread's stack, and
  another when it ends. So a request the access log has no line for is one Tomcat never had, or one it warned of.
- **Step 2 checks that the log is written in that form,** and step 8 that it still begins with the first start's
  first request, so a run that needs the log finds it.

A stack started by hand has no access log. To give it one, make the file as `smoke_server_xml` does and add the
overlay with `SMOKE_SERVER_XML` naming the file.

### What a Failed Run Keeps

What the script prints is the end of each log. With `SMOKE_RESULTS=<folder>`, a run that fails also keeps what
can't be printed, in that folder:

| In the folder | What it is |
|---|---|
| `logs/` | the server's logs folder, whole: Tomcat's [access log](#the-access-log) (`access.log`), `dms.log` and the files it rolled over into (`dms.log.1` to `.5`, the higher the newer), and each log a task writes of its own, such as the Indexer's `indexertask.log`, whose lines `dms.log` doesn't get |
| `app-output.log` | the app container's whole output, where Tomcat's own warnings go, a request still being served after 10 seconds among them |
| `e2e-results/` | when a browser test failed: a folder for each failed test, with its `trace.zip`, its screenshot (`test-failed-1.png`) and its `error-context.md` |

LogicalDOC's `log.xml` keeps five rolled-over files of 5 MB each, so a run whose `dms.log` grew past about 30 MB has
lost its first lines. `app-output.log` is the container step 8 started; the first one's output is gone with it.

```bash
SMOKE_RESULTS=/tmp/smoke-results docker/smoke-test.sh
```

- **The folder is empty or not there yet, in a folder that is there and that Docker can mount.** The script refuses
  one that holds anything, so nothing in it is another run's, and makes only the folder named. It checks Docker can
  mount the folder it's in by making a file there that a container has to see: a Docker engine in a VM sees only the
  host's folders it shares (Docker Desktop's `/Users`, `/private`, `/tmp` and `/var/folders` by default, Colima's
  home folder only). A run that passes keeps nothing, and leaves no folder it made. `docker/tests/lib.sh`'s
  `results_folder` and `results_finish` hold these rules for both scripts.
- **A trace is Playwright's record of one test:** every action with the page before and after it, the console, and
  each request the page and the spec made, with its times and its answer. Open it with
  `npx playwright show-trace <folder>/trace.zip` in `healdan-web/web`, or drop it on
  [trace.playwright.dev](https://trace.playwright.dev), which reads it in the browser and sends it nowhere. Playwright
  records a trace for every test and keeps only a failed one's (`trace: 'retain-on-failure'` in
  `playwright.config.ts`).
- **A request the page waited on is in both.** The trace has when the browser sent it and whether an answer came; the
  access log has when Tomcat began to serve it and for how long. A request in the trace with no line in the access
  log was never answered: it didn't reach Tomcat, or Tomcat was still serving it when the run ended, which
  `app-output.log` then has a warning of if it ran 10 seconds.
- **`docker/tests/test-web.sh` takes `WEB_RESULTS=<folder>`** for a run by hand, on the same terms: empty or not
  there yet, in a folder that is and that Docker can mount, each failed test's folder copied into it, and no folder
  left by a run that passes. It makes the folder just before the browsers' container starts. The smoke test sets it
  to `e2e-results` in its own folder.
- **The browser tests run 30 minutes at most.** `test-web.sh` then stops Playwright as Ctrl-C does, so it ends its
  tests and the results are still copied, and says so. A run in CI takes about 8 to 11 minutes, and a hang would
  otherwise run until the job's limit cancelled it, with nothing kept.
- **In CI** the image workflow sets `SMOKE_RESULTS` and uploads the folder when the smoke test fails (see
  [The Smoke Test's Results in the Image CI](../continuous-integration/#the-smoke-tests-results-in-the-image-ci)).
- **Everything in it is the smoke stack's:** invented documents, and accounts whose passwords are in this
  repository. The access log has each request's query, which on this stack is test data too.

### The Move's Test

`docker/tests/test-move.sh` tests `move-from-mariadb`, the command in the image that carries an install's data from
MariaDB to PostgreSQL, as the Install manual's [Moving from MariaDB to
PostgreSQL](../../install/moving-from-mariadb/) gives it: `docker compose run` with `compose.move.yaml`'s MariaDB. It
moves a frozen install, the fixture in `docker/tests/move-fixture/`.

- **The fixture** is a MariaDB-era install: its database's dump (`mariadb.sql.gz`), its conf and repository volumes
  (`volumes.tgz`) and what the test expects of them (`expected.env`), about 55 KB in all. `make.sh` made it from
  `3390247e0`, `master`'s tip then, which runs on MariaDB at schema version 12, with that commit's own image, and
  added rows that try the move: an avatar of 6,000 characters, dates at fractions of a second, an accented matter in
  a Store on an encrypted local storage backend with a file on it, a sealed sign-in secret, a Trash item, an error in
  the Admin app's tables beside the app's own rows there, and counters past their first values. Its `README.md` says
  what's in it and when it's made again. It holds only invented data and the smoke
  stack's published test passwords, and `make.sh` checks it names no address but loopback's and LogicalDOC's own
  multicast default, `228.8.8.8`.
- **The cases,** in the order a move would meet them: the app started on the fixture before any move, which the
  start-up tool refuses, as its conf volume names MariaDB, changing neither the conf file nor the empty database; a
  source at an older schema version, refused before anything is written; a zero date, which stops the move part-way,
  and the app refusing to start on what it left; a NUL, which stops it unless `--replace-nul` is given; a value a
  test-only trigger changes on the target, which only the move's own comparison sees, so it refuses and leaves the
  target unfinished; and a run with `--replace-nul`, which starts over and finishes. Each run's output must hold the
  command's own lines and nothing else, so a stack trace fails it.
- **Then the result:** the report; every table's rows counted on both sides, by one query a side made from each
  database's catalogue, and the two lists compared; the rows read back with `psql` (the avatar whole, the dates to the
  millisecond, the NUL a space, the accented name, the Admin app's error with its counts for each hour, the documents'
  sequence going on from MariaDB's and the Admin app's identity columns past the ids moved); MariaDB untouched
  by every run, its sequences included; PostgreSQL's log with no `ERROR` line; and the app on the moved database,
  where `apiuser` signs in, a file reads back from Original's folder and one through the encrypted backend's key and
  marker, the sign-in provider's sealed secret opens, a new document takes the next doc # and ids after the fixture's,
  and its delete makes a Trash item after the fixture's.
- **Its settings:** `LOGICALDOC_IMAGE` names the image (`logicaldoc-community:local`, which `docker compose build`
  tags), `MOVE_PROJECT` the Compose project (`healdan-move`) and `MOVE_PORT` the app's port on `127.0.0.1` (18090). It
  claims its project as the smoke test does, refuses one that's in use, and removes everything it made. A run takes
  about a minute. CI runs it on amd64 only, after the smoke test (see
  [Continuous Integration](../continuous-integration/#the-moves-test-in-the-image-ci)).

The command and its test are kept for a while after the switch to PostgreSQL, and then removed. While they're here,
an upstream sync that adds, drops or renames a table or a column in LogicalDOC's scripts fails this test: the move
makes LogicalDOC's tables with the new scripts and refuses a table or column that its MariaDB source lacks, and the
report's last lines, which the test checks, list one only the source has. Hold such a sync, and ask the owner.
Healdan's own migrations don't trip it: the move takes Healdan's tables to version 13, the one that goes with its
source's 12, and no further, and the app's first start applies any later one to the moved rows, as on every upgrade,
which this test's start of the app checks.

## The Web Apps' Tests

The apps in `healdan-web/web` have two kinds of tests, apart from Maven ([The Web Module's
Tests](#the-web-modules-tests) are Maven's). [The Web App](../the-web-app/) says how the code is laid out.

- **Unit tests,** run by `npm test` in `healdan-web/web`. Vitest and Testing Library run each package's and each
  app's tests (`vitest.config.ts`'s projects, `apps/*` and `packages/*`): each `packages/ui` component, the shell's
  own, each of the User app's views against a small fake API typed from `openapi.yaml`, the Admin app's against its
  own (`apps/admin/src/test/fakeApi.ts`), and the string catalogue's mapping of the API's problem codes. Every
  component and view also gets an axe check for accessibility, which can't check
  colour contrast under jsdom, so `tokens.test.ts` checks it from the colours. Node's own test
  runner tests the build's scripts: `scripts/check-dist.test.mjs` gives `check-dist.mjs` builds of each app that pass
  and builds that fail, the Admin app's code in a User app's file or PDF.js in an Admin app's build among them, and
  `scripts/workspace.test.mjs` checks the workspace's settings: that `.npmrc` sets `ignore-scripts` and
  `save-exact`, that `.nvmrc` names the Node that `healdan-web/pom.xml` downloads, that every app has its own type
  check, and that ESLint refuses an app's import of another's code. CI runs them in the `Web` check.
- **Browser tests,** Playwright's specs in `healdan-web/web/e2e/`, in Chromium and WebKit. `docker/tests/test-web.sh`
  runs them in Playwright's Docker image, `mcr.microsoft.com/playwright:v<version>-noble`, where `<version>` is the
  `@playwright/test` version in `package-lock.json`, so the image's browsers are the ones that version expects.
  The script copies the workspace in without `node_modules`, installs its packages inside the container, and
  runs the specs against `BASE`. They sign in as `webuser`, whom the `smoke-web-user` setup step makes on a stack
  with the smoke overlay (its password is `SmokeWebUserSeed.PASSWORD`), through `helpers.ts`' `signIn`, which signs
  in as someone else when a spec names them, and do not depend on anything an earlier smoke step left. They check
  that a link into the app, and a doc # link with its version (`/app/doc/<n>.1`, for a document `signin.spec.ts`
  files through the API first), followed signed out, come back to their page after signing
  in, that signing in lands on **Recent Documents**, the way to the Classic Interface and back, and **Sign Out**. `finding.spec.ts` files three documents into
  matter 1.1's **Documents** folder through the API with a device token, under a word unique to the run, and then,
  through the page: opens the matter by its number in the Matters scope, adds it to My Matters and opens it from the
  tree, finds a document by that word and by its `#`, filters by **Picture** after reading the counts the panel shows
  (**Picture, 1** and **Other, 2**), and copies a document's link and follows it. The Indexer reads new documents into the index every 30 minutes, so `smoke-test.sh` sets it to every 5 seconds in
  `context.properties` and restarts the app at the start of step 9. Step 9 is the last, so that no other step runs with a fast Indexer, which would read storage document C through Garage and fill the read cache that step 8 checks it never did. The spec asks again until the document shows. Run by hand, `test-web.sh` has no such setting: make it yourself (`sed -i` the `schedule.delay.IndexerTask` and `schedule.interval.IndexerTask` lines of the conf volume's `context.properties` to `5000` and restart the app) and set `HEALDAN_INDEXER_FAST=1`, which only silences the script's warning, or `finding.spec` waits 80 seconds for a document the index doesn't have and fails. WebKit has no
  permission to read the clipboard, so the spec keeps what the page passes to `navigator.clipboard.writeText` on
  `window` and reads the link from there; Chromium's real clipboard is read as well. `working.spec.ts` files a one-page
  PDF into the same folder, and through the page selects it, presses Space and waits for Quick Look to draw page 1 of 1
  (dark pixels in the canvas), then checks that the page saw no `securitypolicyviolation` event and logged no policy
  error, which is how a change to PDF.js's settings or its version is caught under `AppServlet`'s policy, and that Esc
  gives the focus back to the row. It also files an `.eml` with an HTML body, a tracking picture on a host under `.invalid`, a link and a PDF attached, and through the page opens it in Quick Look: the body renders in its frame, no request reaches the tracker until **Show Pictures** and one does after, the link opens a page of its own with no `opener`, and **Save to Matter** on the attachment makes its chip show the new doc #. It files a text file too, and with the page showing its details checks it out and saves a snapshot to it through the API, with the device token, as the helper does after Open in App. After each, once the page has every answer it asked for, it fires `focus` on `window`, as coming back from Excel to the browser's window would, and waits for the details' **Check-Out Info** to name the token as its **Computer**, and the row's marker to say **Checked out by you**, then for the details to show **Version 1.1**, in the **Versions** tab too. `filing.spec.ts` drops three files on the folder's list (Playwright can't drag real ones, so it dispatches `dragenter`, `dragover` and `drop` with a `DataTransfer` built in the page), one of them an `.eml` the matter already holds, and checks that the tray says **Already in this matter as #**, that the other two are filed and show in the list, and that one file dropped on a row and saved as **Version 2** is version 2.0 of that document. `organising.spec.ts` files six documents and makes two folders through the API, all inside the specs' shared `E2E` folder, adds the matter to My Matters, and through the page: makes a folder with **New Folder**; renames a document in place with F2, where another extension is refused beside the field and Esc leaves the name alone; moves a document with **More › Move To…**; drags two selected rows onto a folder in the tree (dispatching `dragstart` on the row and then `dragenter`, `dragover` and `drop` with one `DataTransfer` built in the page, so the row's own `dragstart` fills what the drop reads) and checks the folder lights up; and deletes two with the question, which it cancels first (the focus back in the row), then confirms. It needs the smoke fixture's Everyone group to hold the Rename, Move and Delete permissions, which the `smoke-organising` seed step gives it after `smoke-fixture`. `folders.spec.ts` makes its own folders inside `E2E` through the API and renames them: by F2 in a list, where a taken name is refused beside the field and the tree follows; from the tree's ⋯ and from the ⋯ beside the open folder's name, where the heading, the tree and the breadcrumb follow; by right-click › Rename on a list row; and once by keyboard alone. It also moves folders, in a folder of its own inside that one: by the dispatched drag events onto a folder of the tree (which lights up), by **Move To…** (whose picker greys out the folder itself and **Move Here** with its reason, **Already there**), once by keyboard alone (Tab, the arrow keys and Enter, to the picker's last choice), by a pointer's own drag of one tree folder onto another (Playwright's `dragTo`, from the start of the folder's name, as the middle of a long one is under the row's ⋯), and out of the matter into matter 1.4, **Second Matter**, which the `smoke-second-matter` seed step makes: the question names the matter and what its documents will take, **Cancel** leaves the folder, **Move** moves it, and a document in it then shows matter 1.4 through the API. It also checks that `webuser`, who isn't an administrator, sees Rename, **Move To…** and Delete greyed out on matter 1.1's top-level Documents folder, and that F2 and Delete there show their notes. Afterwards the script signs in with `curl`, checking
  for LogicalDOC's `SID` header so a failed sign-in says so, and checks that the web session refuses a cross-site
  sign-out and one without the `X-Healdan-Client` header, and still answers `GET`s, then that the Admin app answers
  `webuser` `403` at `/admin/`, and a signed-out request for `/admin/assets/x.js`, a name that stands for any of its
  build's files, anything but `200`; each `curl` has `--max-time 30`. Playwright stops after 10 failures
  (`maxFailures` in `playwright.config.ts`), so a stack that came up healthy but broken fails step 9 in minutes rather
  than the job's timeout.
  - `trash.spec.ts` checks the Trash (folders and Trash spec §7). It makes two folders, each with a document, and two documents
    of their own, in a folder inside `E2E`, and through the page: deletes a folder from the menu a right-click opens (the
    question says what it holds) and presses **Undo** on the toast, which puts the folder and its document back; deletes it
    again, opens **Trash** from the left pane, selects its row and presses **Restore**, and follows the toast's **Open** to
    the folder it went back to; deletes another and removes it with **Remove…**, whose question says an administrator can
    restore it for the days the administrators set, and checks through the API, with a device token of the smoke stack's
    `admin` (`ADMIN_PASSWORD`, which `test-web.sh` passes: the stack's published password), that the Removed view lists it
    with who removed it and when it is destroyed, and that `webuser` is refused the view; and deletes two documents and
    empties the Trash (**Cancel**, which has the focus, removes nothing), which then says **Nothing in the Trash.**
  - `keyboard.spec.ts` walks rows 3 to 11 by keyboard alone: rows 3 to 9 of M6's manual acceptance, then rows 10 and 11
    of its own, for folders and the Trash. After the sign-in page it presses keys (`page.keyboard`) and never clicks. A
    control is reached by Tab (`tabTo` in `helpers.ts`, which presses Tab until the control has the focus and fails after
    a bound). Not every walk starts where the page does: `focus()` starts a step
    at a row, which Tab and the arrow keys reach (row 4 proves Tab gets into the list), at a column header (Shift+Tab and
    the arrow keys reach it from a row), and at a menu item once Enter has opened the menu (its first key is ArrowDown),
    and some `tabTo` walks start from the folder's star, the search box or an attachment's **Preview**, each with a comment
    saying why. Those reaches by Shift+Tab and the menus' arrow keys aren't themselves walked. It files its own documents
    and an email into a folder of its own, inside `E2E`, a folder of Documents that this spec, `organising.spec.ts` and
    `a11y.spec.ts` share so they add no row to Documents' own list (where `filing.spec.ts` and `working.spec.ts` look for
    their documents, among the first rows the list draws). It walks:
    - **Row 3:** `/` to the search box; a suggestion chosen with the arrow keys (typed again until the Indexer has read the
      document, as the box asks once for words that settle); a `#` and `#n.N`; a matter number in the
      Matters scope, picked from the scope menu; a word search and its results; the funnel, Filters and a tick.
    - **Row 4:** Tab into the list, and each column sorted from its header by Enter, both ways (A to Z or smallest first
      at the first press, but **Edit Date** newest first). Then the columns by keys: the arrow keys from a row to the
      headings, whose focus is the heading's name; Shift+F10 for a heading's menu and its **Resize Column** (three
      arrows widen the column 30 pixels, and Enter ends the resize with the focus back on the name); Tab from a row to
      the **Columns** button, Enter for its panel, Tab into its columns, the arrows and Space to show **Client**, **Move
      Up**, and Esc, with the focus back on the button; and **Reset Columns**, which End reaches in a heading's menu.
    - **Row 5:** Space, the arrow keys and Esc in Quick Look, and docking the reading pane.
    - **Row 6:** an email in Quick Look, **Show Pictures**, **Save to Matter**, and Enter on the saved attachment, with the
      notice asserted.
    - **Row 7:** Enter to open in the app, **Download** (the download event asserted), **Check Out** and **Release**
      (the details' **Check-Out Info** coming and going), and **Edit Properties**, reached by Tab through the tab strip,
      with Enter to save and Esc to cancel.
    - **Row 8:** **Upload** by Enter, and Option-Enter for the form that comes first.
    - **Row 9:** **New Folder**, F2, **More › Move To…** and Delete, each with where the focus lands asserted.
    - **Row 10:** Delete on a folder row and Enter in the question; F6 to the toast (the region of notifications takes
      the focus, and Tab goes on to **Undo**) and Enter on **Undo**; Delete again, the **Trash** entry at the foot of the
      left pane by Tab, a row of the Trash by the arrow keys, and **Restore** in the details by Tab and Enter.
    - **Row 11:** Delete on a folder of the left pane's tree (Tab to the tree, the arrow keys and Right to open a folder),
      Enter in the question, and where the focus lands asserted: on the remaining row of the open folder's list, by name. Row 10 ends by asserting that the Trash leaves the focus on a row of its list, or the list itself.

    It leaves out **Copy Link**, as headless browsers' clipboards differ between engines (`finding.spec.ts` covers it), and
    row 9's move into another matter, which `folders.spec.ts` covers into matter 1.4 (a move into Smoke Cloud would
    write through Garage after step 8 has checked its read cache). A step that can't be done by keys is a bug in the
    app.
  - `context-menu.spec.ts` checks the right-click menus. It files three documents and makes two folders through the API,
    in a folder of its own inside `E2E`, adds one of the documents to Favorites and the matter to My Matters (the move
    picker lists My Matters), and through the page: renames a document from the menu a right-click opens on its row, which
    selects the row first; opens the menu with Shift+F10 on the focused row, closes it with Esc (the focus back in the
    row) and moves the document with **Move To…**, by keys; and removes the favorite from its menu in **My Favorites**.
    Afterwards it removes the bookmark, if a failed run left it, and takes the matter out of My Matters again.
  - `favorite-names.spec.ts` checks a favourite's own name. It makes a folder of its own inside `E2E` through the API,
    and through the page adds it to **My Favorites** with its star, checks the favourite's tooltip says what it is and
    where (the folder's name and matter 1.1), renames it from its menu with **Rename Favorite**, Enter and a new name,
    and checks the new name in **My Favorites** and through the API, the tooltip unchanged and the focus back on the
    favourite, then gives it the folder's name back with **Use Original Name**. Afterwards it removes the bookmark,
    if a failed run left it; the folder stays.
  - `checked-out.spec.ts` checks **Checked Out to Me** and releasing another computer's check-out. It files a `.docx`
    with a device token, in a folder of its own inside `E2E`, and checks it out for Word with that token, as the
    helper does when Word opens it. Through the page it opens **Checked Out to Me** from its entry, and checks the
    row's **Computer** (the token's name, and Word) and that the entry's badge equals the list's row count (the
    list's `aria-rowcount`, as other runs may have documents checked out to `webuser`). It then opens the warning with
    **Release…**, checks that **Cancel** has the focus and cancels, the document still checked out on the token's
    computer, then releases it with **Release Document** and checks that the row leaves the list, that the badge
    drops by one, and that the token's save with `ifHeld: true` is then `409 not-held`, as the computer's next save
    would be. Afterwards it releases the document, if a failed run left it held, and signs the token out.
  - `a11y.spec.ts` runs axe on each main view in the light theme and again in the dark one (a browser context with
    Playwright's `colorScheme`, signed in once per theme): Recent's four lists, **Checked Out to Me** with a document
    the spec checked out on its token, and the warning before releasing it (closed with Esc, so nothing is released), a
    matter, a folder with details (its toolbar buttons all one height, and a document the spec checked out through the
    API, and releases at the end, whose row shows its marker and is 34 pixels high, as a free row is), the properties
    form, alone and with Author's list of people open (Author emptied first, so the list opens when the people come;
    then, with someone picked, Esc closes the list and leaves the form open), that document's details with its **Check-Out Info** and the
    **Versions** tab, a heading's menu and the **Columns** panel, with a column of **More Columns** shown (and the
    **Columns** button drawn as the toolbar's **More** is, its background, border, corners and colour, in each theme),
    Filters (with one in use, and
    while the counts are held open with `page.route`, since busy states keep AA contrast and the last answer's counts
    must stay, never zeros), the list while it is asked for again, the filing tray and the File Documents form, alone
    and with Author's list open in it (Author emptied first; Esc then closes the list and leaves the dialog open), the
    rename field, the delete question, the Help menu, the delete question for a folder with its line of what it holds, the toast
    with **Undo**, the move picker for a folder with its greyed choices, the question before a folder leaves its matter, the Trash
    with an item and with it selected, its **Remove** and **Empty Trash** questions and the empty Trash, search results for a word with their Filters (Title keeps 200
    pixels, and text that doesn't fit ends in an ellipsis), the search box's suggestions for the smoke fixture's matter
    with the whole page around them (and that their layer covers the window and the list starts under the field,
    `expectListUnder`), a document's page, Quick Look on a PDF and on an email, the
    reading pane, New Folder, More and Move To… (a matter's client starts clear of it, drawn smaller), the person menu,
    Signed-in Devices (whole in its dialog, with no sideways scroll, every Revoke inside it and a name of at most two
    lines at 1280, and again at 768) with the Revoke question and its refusal (and a real revoke, whose focus must stay
    in the dialog), at 768 × 1024 the folded top bar with the left pane, the empty region and the details opened over
    the list, and last the signed-out screen (the session is ended with `DELETE /api/v1/session` from the page). Colour
    contrast is checked here, as jsdom can't. axe-core is the workspace's own development dependency: `e2e/axe.ts` reads
    its file from `node_modules` and runs it with `page.evaluate`, which the browser's debugging protocol does and the
    page's Content-Security-Policy doesn't govern, so the policy stays as the app ships it. No spec turns it off:
    `addScriptTag` with the source in it would be blocked by `script-src 'self'`, and `bypassCSP` would test a page the
    app never serves.
  - `admin-access.spec.ts` checks the way into the Admin app (Admin app spec §6.1, §6.2 and §6.4). It follows
    `/admin/?x=1` signed out, signs in as the smoke stack's `admin` (`ADMIN_PASSWORD`) and checks it comes back to that
    address, with the Dashboard; reads the build's entry script from that page, under `/admin/assets/`, and checks
    the administrator gets it with `Cache-Control: private`; finds **Admin** just above **Classic Interface** in
    `admin`'s person menu in the User app, which opens the Dashboard, and not in `webuser`'s; checks that `webuser`
    gets `403` at `/admin/` and the no-access page, with **Open Healdan** to `/app/`, passing axe, and for the real
    script, asked with `webuser`'s own session (`page.request`), a `403`, `no-store`, without the file's bytes; and
    that a request for it with no session isn't `200`, and holds none of its bytes either. It changes nothing on the
    stack.
  - `admin-matters.spec.ts` takes a matter from nothing to the User app (Admin app spec §17.1). As `admin`, with names
    of its own run, it makes a Store that Everyone can see, a workspace template with a folder and a restricted
    subfolder, a client, and a matter from the template; edits the matter; and changes a folder's access in the
    Workspace Editor. Then it signs in as `webuser` and checks in the User app that the matter's restricted folder
    isn't visible and the other folder is. Like every admin spec, it changes nothing global: it makes its own clients,
    matters, Stores and templates, and changes neither `webuser`, Everyone nor `Smoke Garage`.
  - `admin-a11y.spec.ts` runs axe on the Admin app as `admin`, in the light theme and again in the dark one, as
    `a11y.spec.ts` does: the Dashboard once its ten tiles and Usage's figures are drawn (it waits for Usage's chart, so
    a summary that failed fails it), the find's list for the smoke fixture's client and matter, the Help menu, the
    person menu, at 768 × 1024 the Dashboard (with no sideways scroll) and the left pane opened over it (Esc closes it,
    and the focus goes back to **☰**), Phase 2's screens (the Clients list, the Matters list, a matter's page on its
    Overview and Workspace tabs, and Workspace Templates, with the smoke fixture's client 1 and matter 1.1, which it
    only reads, so it changes nothing and needs the smoke overlay), and last the signed-out screen. This is where the
    Dashboard's colour contrast is checked. The find's list is checked with the whole page around it, as the User app's
    search box's is: `@healdan/ui`'s `ComboBox` draws the list right after the box, inside the find's search landmark,
    and keeps the page around it shown to a screen reader while it's open (The Web App page's The Search Box and Search
    Results), so nothing hidden holds a link (axe's `aria-hidden-focus`). `e2e/helpers.ts`'s `expectListUnder` then
    checks that the list's layer covers the window and the list starts at the find's left edge, 4 pixels below it, as
    wide as the box, though the find's field is positioned and padded.
  - `resizing.spec.ts` files a document into a folder of its own inside `E2E`, and through the page drags the left
    pane's divider with the mouse and checks the pane's width, its value and that the list's Title column narrowed (the
    virtualiser measured the narrower list); reloads and finds the width kept; moves it by keys; checks that a window
    800 pixels wide has no divider and that the width comes back at 1280; and puts it back with Enter. It does the same
    for the details' divider, with End, Home and Enter. Each test has a browser context of its own, so its storage
    starts empty.
  - `columns.spec.ts` checks the list's columns (list columns spec §8.3). It files three documents and a PDF into a
    folder of its own inside `E2E`, and each test has a browser context of its own, so its layouts start from the
    defaults. Through the page it resizes **Recent Documents**' Matter by a drag of its heading's edge and by **Resize
    Column** and the arrows, and finds the widths kept after a reload; adds **Client** from the **Columns** panel, which
    stays open with its focus when coming back to the window asks for the list again, moves it by a pointer's drag of
    its heading and by **Move Left**, and finds the order kept; hides **Doc #** beside the docked reading pane and finds
    the folder's own list unchanged; sorts **Recent Documents** by **Title** and back by **Last Used**, the rows drawn in
    the order the server's rules give the whole list the API returns; puts the defaults back with the panel's **Reset to
    Default** and the heading menu's **Reset Columns**; keeps widths set in a window 1,920 pixels wide in one 1,280
    wide, where the list scrolls sideways, every column keeps its width and rows stay 34 pixels high; and, in a folder of
    its own holding a document of each of three Classes, shows **Class** from the **Columns** panel, finds each
    document's Class in its cell, and sorts the folder by it with clicks on its heading, A to Z and then Z to A, checking
    the order of the server's answer and of the rows drawn, and that the address holds `sort=class`, then finds both kept
    after a reload. Class is picked from a list, which on a fresh stack holds only the default Class, **Docs**, and the
    JSON API has no route that changes it, so that test first adds the Classes the list lacks as `admin`, through
    LogicalDOC's REST service for an attribute's options (`/services/rest/documentMetadata/addAttributeOption`), with
    the password `test-web.sh` passes as `ADMIN_PASSWORD`. The list's values are unique, so it adds only what's missing,
    and they stay on the stack, as the documents do. Every document has a Class (one with none of its own inherits its
    folder's, or the default), so no row is without one. On the way it checks what only a browser shows: an edge is 24
    pixels wide and centred on the heading's edge, its line and a heading drag's drop line are the accent's, a heading
    doesn't brighten while the pointer is on its edge (a `:has()` rule), and a heading's first press drags it, with no
    sort after. WebKit sends a drag's `dragover` on a timer, so the spec moves the pointer a pixel each way while it
    waits for the drop line.
  - Each spec's own calls to the API (filing, checking, cleaning up) go through `apiContext` in `helpers.ts`, which
    sends every request on a connection of its own (`Connection: close`). Playwright's request context otherwise keeps
    connections for later requests, for as long as they stay open, and Tomcat closes one that has waited 20 seconds: a
    request sent on one at that moment fails with `socket hang up`, as `keyboard.spec.ts`'s sign-out after its tests
    did. A new spec makes its context with `apiContext` too.
  - Each spec signs in through `signIn` in `helpers.ts`, which waits for the sign-in page to mark its body before it
    touches the form. The page draws the password form first, asks the server for its sign-in providers, and with one
    folds the form behind a link when the answer comes, so a helper that looks at the page can find the field a moment
    before it is folded away, then wait to fill it until the test's time runs out, with no sign-in sent. Once the
    question is settled, answered or not, the page's `HealdanSignInButtons` sets the body's `data-healdan-signin` to
    `form` or `folded`, and with `folded` the helper opens the form with the link (`data-healdan-signin-link`). A new
    spec signs in with `signIn` too.
  - It runs in `docker/smoke-test.sh` as step 9, on amd64 and arm64. On a Mac, Docker Desktop doesn't reach the
    host's ports from the default `--network host`: set `WEB_NETWORK=bridge`. The browser's container then joins the
    network of the container that publishes the stack's port (another `WEB_NETWORK` names the network to join), and
    the browser still opens `http://127.0.0.1:<port>`, which a small proxy in its container forwards to that
    container's own address, because the web session refuses a request whose `Origin` isn't the server's own address.
    The proxy goes through the host (`host.docker.internal`) only when no container publishes the port, as for a
    server running on the Mac itself, and the script then warns. Docker Desktop's network remembers a connection's source port for 60 seconds when the
    server was the first to close it, as Tomcat is after each of `apiContext`'s calls, and answers no new connection
    from that port meanwhile; Linux uses a source port again within that time, so about one connection in 700 opened
    11 to 67 seconds late. WebKit sends every `POST` and `PATCH` on a new connection, so a save sometimes waited and
    its test failed at 30 seconds, a different test each run, in WebKit only. The browser's container has the label
    `healdan.web-browsers`, so `docker/smoke-test.sh`'s cleanup removes one that a stopped run left on its stack's
    network, which Compose couldn't remove otherwise. A failing spec leaves its trace and screenshot in the container,
    which is removed: set `WEB_RESULTS=<folder>` to have them copied out first (see
    [What a Failed Run Keeps](#what-a-failed-run-keeps)), or reproduce it with `npx playwright test` against the
    stack, using a Playwright version's browsers installed with `npx playwright install`.

## Desktop Client Tests

The helper's, the LibreOffice extension's and the Office add-in's tests run with `dotnet test`, Python's `unittest`
and `node --test`, apart from Maven: see [Desktop Clients](../desktop-clients/#tests) for what they cover, and
[Building from Source](../building-from-source/#the-desktop-clients) for the commands.

- **In CI,** `clients.yml` runs the helper's tests on Ubuntu and macOS, and the extension's and the add-in's on Ubuntu,
  each in its own job under the `Clients` check ([The Clients Check](../continuous-integration/#the-clients-check)).
  The add-in's tests are `node --test 'clients/office/test/*.test.js'`, with the pattern quoted, since Node 21 and
  later read `--test`'s arguments as file patterns. A change under `clients/office/` other than Markdown runs the
  server's tests and the image's too, because `healdan-api` packages the add-in's pages.
- **On Linux only,** a few tests are marked `[LinuxFact]`, defined in `OwnProcessesTests.cs`: they read the real
  `/proc` for the user's own processes, and skip on other systems. The Mac-only ones are `[MacFact]`.
- **By hand on a Mac,** the tests marked `MacKeychain` use the real keychain, and those marked `MacOffice` drive the
  real Word, Excel and PowerPoint, which open on the screen and ask for Automation and Grant File Access (grant the
  `healdan-office-tests` folder, one level up from where the prompt opens, and an app asks no more). The
  `MacOffice` tests skip unless `HEALDAN_MAC_OFFICE=1` is set, apart from `EveryScriptCompiles`, which starts nothing
  and runs without it wherever Word, Excel and PowerPoint are installed, as compiling reads their dictionaries. CI
  has neither, so they skip there.
- **Against a stack,** the tests marked `Stack` need `HEALDAN_STACK_URL`, and the provider sign-in's also
  `HEALDAN_STACK_SIGNIN_PROVIDER`; `docker/smoke-test.sh` runs them.

## Script and Docs Site Tests

The scripts in `.github/scripts/` and the docs site have Python tests, which need only Python 3 and, for the
site's build test, Hugo:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```
