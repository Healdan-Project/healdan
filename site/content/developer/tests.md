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
- **Each test makes a database of its own** (`TestDatabase`, in `healdan-core`'s test-jar), from `template0`, in
  UTF-8 with PostgreSQL's built-in `C.UTF-8` locale, the encoding and locale Healdan uses on PostgreSQL, whatever
  the server's own default. So the tests sort and compare text as Healdan does there. Each name carries a number
  unique to the JVM that made it, so `healdan-core`'s, `healdan-api`'s and `healdan-storage`'s tests can run at once
  against one server.
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
- **Without a server,** each test fails as it starts, with `Healdan's tests need a PostgreSQL. Start one with:
  docker/tests/test-db.sh start`.
- **The driver.** PostgreSQL's JDBC driver, pgjdbc, is a test dependency of `healdan-core`, `healdan-api` and
  `healdan-storage`, at the version the WAR ships (`postgresql.version` in `logicaldoc-parent/pom.xml`). `healdan-core`'s test `context.properties` names it in
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
| `docker/tests/test-web.sh` | the User app in a browser, against a stack with the smoke overlay (see [The User App's Tests](#the-user-apps-tests)): Playwright's specs in Chromium and WebKit, then `curl` checks of the web session's forgery refusals. Needs Docker, curl and Python 3: `BASE=http://127.0.0.1:18080 docker/tests/test-web.sh`, which `docker/smoke-test.sh` runs as step 9, the last. It needs the Indexer set to every few seconds (see [The User App's Tests](#the-user-apps-tests)) |
| `docker/tests/test-signin.sh` | provider sign-in through the stand-in Keycloak (see below), with `curl` and a cookie jar: the web page's sign-in ending at the User app, signed in, a local account linked by email losing its password and its device token, the gate refusing someone in none of the mapped groups, the helper's sign-in handing a code to a pretend loopback address and the code exchanged for a device token, and a person disabled at Keycloak losing that token at its next re-check. Run it against a fresh stack with the sign-in overlay: `BASE=http://127.0.0.1:8080 KEYCLOAK=127.0.0.1:18180 docker/tests/test-signin.sh` |
| `docker/tests/test-storage.sh` | storage backends against the stand-in Garage (see below): a file filed in Smoke Cloud reaching Garage encrypted, files moved in from Smoke moving up and not cached, reads through Garage and from the read cache, then with Garage stopped a save waiting in staging, an uncached file answering `503` through the API, `/download` and a shared link, and `/convertpdf` of an uncached PDF serving LogicalDOC's placeholder, and a matter moved to Smoke bringing its files home while Garage keeps them held. With `after-restart` it checks the same rows after a restart, a first read through Garage, and a read from the cache rebuilt from its folder with Garage stopped. It stops and starts Garage itself, and needs curl 8.0.0 or later, jq, sha256sum and docker: `docker/smoke-test.sh` runs it, passing it the stack's containers, as step 5f and again in step 8 |
| `docker/smoke-test.sh` | end to end: build, start, REST login, the API answering, the forwarded addresses Tomcat believes (`test-proxies.sh`, step 2b), the sign-in page showing no alert on the first start, such as "The application has to be restarted", the GUI's loaders sent with `Cache-Control: no-cache`, a Content-Security-Policy with `object-src 'none'` and Tomcat's JVM having its Base64 intrinsics off, though the stack's `CATALINA_OPTS` asks for them (step 2), uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, the stack running on Healdan's storage (its setup steps recorded and Original's marker written, step 5), PostgreSQL itself in step 5 (the app's role waiting 50 seconds for a lock; an email's subject and file name, a new matter's name and a document's name keeping their emoji, the matter made through the Classic Interface's New Matter service as client 1's next free number, 1.5 on today's stack, which stays for the later steps; a NUL in a query parameter answered `400 bad-request`; and `apiuser` disabled and enabled again through the Classic Interface's service, each edit read back from the database and the API, as LogicalDOC's typed reads lost such an edit on PostgreSQL without Healdan's fix), PostgreSQL's own log after steps 7 and 9 (see [PostgreSQL's Log](#postgresqls-log)), M2's client flow through the API (`test-api.sh`, step 5c), the User app in Chromium and WebKit (`test-web.sh`, step 9, the last), the helper's open-save-close cycle and its sign-in through the stand-in Keycloak (step 5d, only with `SMOKE_HELPER_TEST=1` and the .NET SDK), provider sign-in through the stand-in Keycloak (`test-signin.sh`, step 5e), storage backends against a stand-in S3 server, Garage (`test-storage.sh`, step 5f, and again after step 8's restart), the Office add-in's manifest naming the stack (step 2), and data surviving a restart, which checks LogicalDOC's tables, replaces an older image's `cachecontrol` default and makes a non-breaking space in `security.csp` plain (step 8). With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one. Two can run on one machine at once (see [Two Smoke Tests at Once](#two-smoke-tests-at-once)) |

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
- **It fails** when it can't read the log, when the log is empty or isn't PostgreSQL's (it has no `database system
  is ready to accept connections`), and when the list can't be read, so a clean log is one it read.
- **An unexpected line doesn't fail the run yet.** It prints every one, and passes, until the list has held over
  several runs on both architectures. The switch to failing is the function's last line, `return 0`, becoming
  `[ "$unexpected" = 0 ]`.

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

When a browser test in step 9 fails, `docker/tests/test-web.sh` also prints the page of each failed test as Playwright
saw it (its `error-context.md`), before the container that ran them goes. It's in the step's output, after the line
that names the failed tests.

`dms.log` covers the whole run. Step 8 replaces the container but keeps the volume, so its restart doesn't lose the
earlier steps' lines. Every `FAIL` line ends with its time in UTC, and the smoke stack logs in UTC, so the log's
lines from around a failure are easy to find.

### The Move's Test

`docker/tests/test-move.sh` tests `move-from-mariadb`, the command in the image that carries an install's data from
MariaDB to PostgreSQL, as the Install manual's [Moving from MariaDB to
PostgreSQL](../../install/moving-from-mariadb/) gives it: `docker compose run` with `compose.move.yaml`'s MariaDB. It
moves a frozen install, the fixture in `docker/tests/move-fixture/`.

- **The fixture** is a MariaDB-era install: its database's dump (`mariadb.sql.gz`), its conf and repository volumes
  (`volumes.tgz`) and what the test expects of them (`expected.env`), about 53 KB in all. `make.sh` made it once, from
  `84c802acb`, the last commit on `master` that runs on MariaDB, with that commit's own image, and added rows that
  try the move: an avatar of 6,000 characters, dates at fractions of a second, an accented matter in a Store on an
  encrypted local storage backend with a file on it, a sealed sign-in secret, a Trash item, and counters past their
  first values. Its `README.md` says what's in it and when it's made again. It holds only invented data and the smoke
  stack's published test passwords, and `make.sh` checks it names no address but loopback's.
- **The cases,** in the order a move would meet them: a source at an older schema version, refused before anything
  is written; a zero date, which stops the move part-way, and the app refusing to start on what it left; a NUL, which
  stops it unless `--replace-nul` is given; a value a test-only trigger changes on the target, which only the move's
  own comparison sees, so it refuses and leaves the target unfinished; and a run with `--replace-nul`, which starts
  over and finishes. Each run's output must hold the command's own lines and nothing else, so a stack trace fails it.
- **Then the result:** the report; the rows read back with `psql` (the avatar whole, the dates to the millisecond,
  the NUL a space, the accented name, the documents' sequence going on from MariaDB's); MariaDB untouched by every
  run, its sequences included; PostgreSQL's log with no `ERROR` line; and the app on the moved database, where
  `apiuser` signs in, a file reads back from Original's folder and one through the encrypted backend's key and
  marker, the sign-in provider's sealed secret opens, a new document takes the next doc # and ids after the
  fixture's, and its delete makes a Trash item after the fixture's.
- **Its settings:** `LOGICALDOC_IMAGE` names the image (`logicaldoc-community:local`, which `docker compose build`
  tags), `MOVE_PROJECT` the Compose project (`healdan-move`) and `MOVE_PORT` the app's port on `127.0.0.1` (18090). It
  claims its project as the smoke test does, refuses one that's in use, and removes everything it made. A run takes
  about a minute. CI runs it on amd64 only, after the smoke test (see
  [Continuous Integration](../continuous-integration/#the-moves-test-in-the-image-ci)).

The command and its test are kept for a while after the switch to PostgreSQL, and then removed. While they're here, a
change that adds a table or a column to the schema, Healdan's or LogicalDOC's, fails this test: the move makes its
target with the new scripts, and refuses a table or column that its MariaDB source lacks.

## The User App's Tests

The app in `healdan-web/web` has two kinds of tests, apart from Maven. [The Web App](../the-web-app/) says how the
code is laid out.

- **Unit tests,** run by `npm test` in `healdan-web/web`. Vitest and Testing Library run each `packages/ui`
  component, each view against a small fake API typed from `openapi.yaml`, and the string catalogue's mapping of
  the API's problem codes. Every component and view also gets an axe check for accessibility, which can't check
  colour contrast under jsdom, so `tokens.test.ts` checks it from the colours. Node's own test
  runner tests the build's scripts, and `scripts/workspace.test.mjs` checks the workspace's settings: that `.npmrc`
  sets `ignore-scripts` and `save-exact`, and that `.nvmrc` names the Node that `healdan-web/pom.xml` downloads. CI
  runs them in the `Web` check.
- **Browser tests,** Playwright's specs in `healdan-web/web/e2e/`, in Chromium and WebKit. `docker/tests/test-web.sh`
  runs them in Playwright's Docker image, `mcr.microsoft.com/playwright:v<version>-noble`, where `<version>` is the
  `@playwright/test` version in `package-lock.json`, so the image's browsers are the ones that version expects.
  The script copies the workspace in without `node_modules`, installs its packages inside the container, and
  runs the specs against `BASE`. They sign in as `webuser`, whom the `smoke-web-user` setup step makes on a stack
  with the smoke overlay (its password is `SmokeWebUserSeed.PASSWORD`), and do not depend on anything an earlier
  smoke step left. They check that a link into the app, and a doc # link with its version (`/app/doc/<n>.1`, for a
  document `signin.spec.ts` files through the API first), followed signed out, come back to their page after signing
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
  sign-out and one without the `X-Healdan-Client` header, and still answers `GET`s; each `curl` has `--max-time 30`.
  Playwright stops after 10 failures (`maxFailures` in `playwright.config.ts`), so a stack that came up healthy but
  broken fails step 9 in minutes rather than the job's timeout.
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
    - **Row 4:** Tab into the list, and each column sorted from its header by Enter, both ways.
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
    form, that document's details with its **Check-Out Info** and the **Versions** tab, Filters (with one in use, and
    while the counts are held open with `page.route`, since busy states keep AA contrast and the last answer's counts
    must stay, never zeros), the list while it is asked for again, the filing tray and the File Documents form, the
    rename field, the delete question, the Help menu, the delete question for a folder with its line of what it holds, the toast
    with **Undo**, the move picker for a folder with its greyed choices, the question before a folder leaves its matter, the Trash
    with an item and with it selected, its **Remove** and **Empty Trash** questions and the empty Trash, search results for a word with their Filters (Title keeps 200
    pixels, and text that doesn't fit ends in an ellipsis), a document's page, Quick Look on a PDF and on an email, the
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
  - `resizing.spec.ts` files a document into a folder of its own inside `E2E`, and through the page drags the left
    pane's divider with the mouse and checks the pane's width, its value and that the list's Title column narrowed (the
    virtualiser measured the narrower list); reloads and finds the width kept; moves it by keys; checks that a window
    800 pixels wide has no divider and that the width comes back at 1280; and puts it back with Enter. It does the same
    for the details' divider, with End, Home and Enter. Each test has a browser context of its own, so its storage
    starts empty.
  - Each spec's own calls to the API (filing, checking, cleaning up) go through `apiContext` in `helpers.ts`, which
    sends every request on a connection of its own (`Connection: close`). Playwright's request context otherwise keeps
    connections for later requests, for as long as they stay open, and Tomcat closes one that has waited 20 seconds: a
    request sent on one at that moment fails with `socket hang up`, as `keyboard.spec.ts`'s sign-out after its tests
    did. A new spec makes its context with `apiContext` too.
  - It runs in `docker/smoke-test.sh` as step 9, on amd64 and arm64. On a Mac, Docker Desktop doesn't reach the
    host's ports from the default `--network host`: set `WEB_NETWORK=bridge`. The browser then still opens
    `http://127.0.0.1:<port>`, which a small proxy in its container forwards to `host.docker.internal`, because the web
    session refuses a request whose `Origin` isn't the server's own address. A failing spec leaves its trace and screenshot in
    the container, which is removed, so reproduce it with `npx playwright test` against the stack, using a
    Playwright version's browsers installed with `npx playwright install`.

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
