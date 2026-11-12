---
title: Tests
weight: 3
---

## Java Tests

The tests use JUnit 4. [Building from Source](../building-from-source/) has the commands.

- **The base class** is `com.logicaldoc.util.junit.AbstractTestCase`. It builds a Spring context from the module's
  test `context.xml` and `context.properties`, with an in-memory HSQLDB loaded from `getDatabaseScripts()`. For
  `logicaldoc-core` that's `sql/logicaldoc-core.sql` plus the test `data.sql`.
- **Each module has its own base class,** such as `AbstractCoreTestCase`, `AbstractWebappTestCase` or
  `AbstractWebserviceTestCase`. Extend the closest one. `healdan-core`'s `AbstractHealdanTestCase` also runs
  Healdan's migrations and setup steps on the test database.
- **Plugin manifests.** Some tests load them from fixture jars in `src/test/resources` through
  `getPluginArchives()`. Those jars are kept by hand; they aren't built from source.
- Tests write their stores, indexes and temporary files under `target/`.
- **The sign-in throttle is on in every `healdan-core` and `healdan-api` test,** as on a real install. A test that
  gets one user's password wrong five times blocks that user for the rest of the test.
- **The demo firm's test.** `DemoFixtureSeedTest` also runs the real manifest and files in `docker/demo/` on the
  test database, which takes about two and a half minutes. A step its user can't do, or a file the manifest names
  that's missing, fails the build. See [A Demo Firm for Screenshots](../development-stack/#a-demo-firm-for-screenshots).

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
`healdan-core`'s test-jar. They run against the same test database, with Healdan's migrations and setup steps.

- **They drive the real `ApiServlet`** through Spring's servlet mocks, with its filters and providers, so a test
  goes through the token check, CORS and the error mapping as a client's request does.
- **Every answer is checked against `openapi.yaml`** (`Contract`), so a test fails when the server and the file
  disagree.
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

### The Storage Tests

`healdan-storage`'s tests extend `AbstractStorageTestCase`, which builds on `healdan-core`'s `AbstractHealdanTestCase`,
taken from `healdan-core`'s test-jar, as the API's tests do.

- **They run LogicalDOC through Healdan's storage.** The base class takes `healdan-core`'s test `context.properties`,
  which the build copies in, writes it again as `context-storage.properties` with `store` set to `HealdanStorage`, and
  registers the module's `plugin.xml` with `PluginFixtures.archive("healdan-storage")`. So `CeFlowsTest` creates,
  checks in, replaces, deletes, purges and copies documents through LogicalDOC's own code, as a server does.
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
`MemoryBackendS3LikeTest` and `S3BackendTest` each run. `TinkClasspathTest` checks that encryption runs without the
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
| `docker build --target tools .` | the init tool's unit tests, among them `DbInitPlanTest`, which reads the tables and columns of the WAR's own DbInit scripts, `ld_tenant`'s 14 quota thresholds among them, and `PluginInstallTest`, which installs LogicalDOC's plugins as the init tool does, checks that each one started and that the installs edited a copy of `web.xml`, which the image then ships, then starts them again as Tomcat does after it, which must find them all installed and ask for no restart. Then two checks on the WAR's own libraries, which Maven's tests can't do: they have every library's dependencies, and the WAR has only what the plugin zips ship. `WarParsersTest` parses a `.pptx`, an `.xlsx` in Excel's Accounting format and an `.xls` with the server's parsers. `WarStorageTest` sends one request to a closed loopback port with the S3 client, and puts 3 MiB through Tink's streaming AEAD |
| `docker/tests/test-init.sh` | the init tool against a throwaway MariaDB, LogicalDOC's plugins installed on the first run and not again, and a start refused while LogicalDOC's tables lack what the scripts create: `ld_tenant` without the 14 quota thresholds upstream sync #215 brought, then with them added by Upgrading's own SQL, read from its page, and `ld_webservicecall` renamed away |
| `docker/tests/test-image.sh` | the image: a prompt stop during init, file ownership, the `/setup` block, Admin > Restart, settings that persist |
| `docker/tests/test-overlays.sh` | the dev and proxy overlays |
| `docker/tests/test-fulltext.sh` | full-text search: a `.docx` and an `.eml` are indexed and found by their words |
| `docker/tests/test-previews.sh` | Office and drawing previews: converted ahead of time, text, markup and thumbnails, no fetching or macros while converting, a broken file failing cleanly, leftover processes cleaned up |
| `docker/tests/test-proxies.sh` | which forwarded addresses Tomcat believes with `LOGICALDOC_TRUSTED_PROXIES` unset: a header from the Docker host, but not a private address in the chain or a header sent from inside the app's container. It reads the sign-in throttle's per-address counters, so it needs a running stack: `BASE=http://127.0.0.1:8080 APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-proxies.sh` |
| `docker/tests/test-api.sh` | the API through the image's real filter chain, as the non-admin `apiuser` that the `smoke-api-user` setup step makes (`test-web.sh` signs in as `webuser`, made by `smoke-web-user`, since this script leaves `apiuser` blocked by the throttle for 15 minutes): LogicalDOC's SOAP services answering 404, M2's client flow (sign in, find the smoke matter, file a `.docx`, check it out, save twice, download two versions, sign out), the docs page, a session cookie refused without a token, the sign-in throttle, and LogicalDOC's `loginApiKey` refusing the token. Then M3's additions: searching inside the matter, a new folder and a favourite, a stamped `.docx`, a second device refused with `held-elsewhere`, moving the check-out, `ifVersion` and a repeated `saveId`, and the `#1234 v2.1` lookup. Then M5's: an `.eml` inspected, filed with its attachment and found by its Message-ID, and `already-filed` for filing it again and for uploading it into the matter. Run it against a fresh stack with the smoke overlay: `BASE=http://127.0.0.1:8080 docker/tests/test-api.sh` |
| `docker/tests/test-web.sh` | the User app in a browser, against a stack with the smoke overlay (see [The User App's Tests](#the-user-apps-tests)): Playwright's specs in Chromium and WebKit, then `curl` checks of the web session's forgery refusals. Needs Docker, curl and Python 3: `BASE=http://127.0.0.1:18080 docker/tests/test-web.sh`, which `docker/smoke-test.sh` runs as step 9, the last. It needs the Indexer set to every few seconds (see [The User App's Tests](#the-user-apps-tests)) |
| `docker/tests/test-signin.sh` | provider sign-in through the stand-in Keycloak (see below), with `curl` and a cookie jar: the web page's sign-in ending at the User app, signed in, a local account linked by email losing its password and its device token, the gate refusing someone in none of the mapped groups, the helper's sign-in handing a code to a pretend loopback address and the code exchanged for a device token, and a person disabled at Keycloak losing that token at its next re-check. Run it against a fresh stack with the sign-in overlay: `BASE=http://127.0.0.1:8080 KEYCLOAK=127.0.0.1:18180 docker/tests/test-signin.sh` |
| `docker/tests/test-storage.sh` | storage backends against the stand-in Garage (see below): a file filed in Smoke Cloud reaching Garage encrypted, files moved in from Smoke moving up and not cached, reads through Garage and from the read cache, then with Garage stopped a save waiting in staging, an uncached file answering `503` through the API, `/download` and a shared link, and `/convertpdf` of an uncached PDF serving LogicalDOC's placeholder, and a matter moved to Smoke bringing its files home while Garage keeps them held. With `after-restart` it checks the same rows after a restart, a first read through Garage, and a read from the cache rebuilt from its folder with Garage stopped. It stops and starts Garage itself, and needs curl 8.0.0 or later, jq, sha256sum and docker: `docker/smoke-test.sh` runs it, passing it the stack's containers, as step 5f and again in step 8 |
| `docker/smoke-test.sh` | end to end: build, start, REST login, the API answering, the forwarded addresses Tomcat believes (`test-proxies.sh`, step 2b), the sign-in page showing no alert on the first start, such as "The application has to be restarted", the GUI's loaders sent with `Cache-Control: no-cache` and a Content-Security-Policy with `object-src 'none'` (step 2), uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, the stack running on Healdan's storage (its setup steps recorded and Original's marker written, step 5), M2's client flow through the API (`test-api.sh`, step 5c), the User app in Chromium and WebKit (`test-web.sh`, step 9, the last), the helper's open-save-close cycle and its sign-in through the stand-in Keycloak (step 5d, only with `SMOKE_HELPER_TEST=1` and the .NET SDK), provider sign-in through the stand-in Keycloak (`test-signin.sh`, step 5e), storage backends against a stand-in S3 server, Garage (`test-storage.sh`, step 5f, and again after step 8's restart), the Office add-in's manifest naming the stack (step 2), and data surviving a restart, which checks LogicalDOC's tables, replaces an older image's `cachecontrol` default and makes a non-breaking space in `security.csp` plain (step 8). With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one |

`docker/tests/bulk-documents.sh` isn't a test: it fills a folder on a throwaway stack with generated `.docx`, `.pdf`,
`.xlsx`, `.eml` and `.odt` files, five uploads at a time, to measure big folders and searches. Give it the stack's
address, a device token of someone who may file there, the folder's id and how many:
`docker/tests/bulk-documents.sh http://127.0.0.1:8080 hd_… 1234 2000`. A fifth argument, a word, goes into every
file, for a search that finds them all.

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

### When a Smoke Check Fails

The server's warnings go to its own log, `repository/logs/dms.log` in the repository volume, not to the container's
output. So when a check fails, or the script stops early, `docker/smoke-test.sh` prints the server's log before it
removes the stack. It's at the end of the script's output, after `SOME CHECKS FAILED` when a check failed, and in CI
at the end of the `Smoke test` step of the `Test (amd64)` or `Test (arm64)` job:

- **The app container's last 150 lines of output:** the entrypoint, the init tool and Tomcat's start.
- **Keycloak's last 40 lines of output,** for the stand-in sign-in provider.
- **Garage's last 40 lines of output, and its setup's,** for the stand-in S3 server.
- **The run's warnings and errors from `dms.log`,** the last 200 lines of them. Each keeps its exception's message
  and `Caused by` lines, without the stack frames.
- **`dms.log`'s last 40 lines.**

`dms.log` covers the whole run. Step 8 replaces the container but keeps the volume, so its restart doesn't lose the
earlier steps' lines. Every `FAIL` line ends with its time in UTC, and the smoke stack logs in UTC, so the log's
lines from around a failure are easy to find.

## The User App's Tests

The app in `healdan-web/web` has two kinds of tests, apart from Maven. [The Web App](../the-web-app/) says how the
code is laid out.

- **Unit tests,** run by `npm test` in `healdan-web/web`. Vitest and Testing Library run each `packages/ui`
  component, each view against a small fake API typed from `openapi.yaml`, and the string catalogue's mapping of
  the API's problem codes. Every component and view also gets an axe check for accessibility, which can't check
  colour contrast under jsdom, so `tokens.test.ts` checks it from the colours. Node's own test
  runner tests the build's scripts. CI runs them in the `Web` check.
- **Browser tests,** Playwright's specs in `healdan-web/web/e2e/`, in Chromium and WebKit. `docker/tests/test-web.sh`
  runs them in Playwright's Docker image, `mcr.microsoft.com/playwright:v<version>-noble`, where `<version>` is the
  `@playwright/test` version in `package-lock.json`, so the image's browsers are the ones that version expects.
  The script copies the workspace in without `node_modules`, installs its packages inside the container, and
  runs the specs against `BASE`. They sign in as `webuser`, whom the `smoke-web-user` setup step makes on a stack
  with the smoke overlay (its password is `SmokeWebUserSeed.PASSWORD`), and do not depend on anything an earlier
  smoke step left. They check that a link into the app, followed signed out, comes back to its page after signing
  in, that signing in lands on **Recent Documents**, the way to the Classic Interface and back, and **Sign Out**. `finding.spec.ts` files three documents into
  matter 1.1's **Documents** folder through the API with a device token, under a word unique to the run, and then,
  through the page: opens the matter by its number in the Matters scope, adds it to My Matters and opens it from the
  tree, finds a document by that word and by its `#`, filters by **Picture**, and copies a document's link and follows
  it. The Indexer reads new documents into the index every 30 minutes, so `smoke-test.sh` sets it to every 5 seconds in
  `context.properties` and restarts the app at the start of step 9. Step 9 is the last, so that no other step runs with a fast Indexer, which would read storage document C through Garage and fill the read cache that step 8 checks it never did. The spec asks again until the document shows. Run by hand, `test-web.sh` has no such setting: make it yourself (`sed -i` the `schedule.delay.IndexerTask` and `schedule.interval.IndexerTask` lines of the conf volume's `context.properties` to `5000` and restart the app) and set `HEALDAN_INDEXER_FAST=1`, which only silences the script's warning, or `finding.spec` waits 80 seconds for a document the index doesn't have and fails. WebKit has no
  permission to read the clipboard, so the spec keeps what the page passes to `navigator.clipboard.writeText` on
  `window` and reads the link from there; Chromium's real clipboard is read as well. `working.spec.ts` files a one-page
  PDF into the same folder, and through the page selects it, presses Space and waits for Quick Look to draw page 1 of 1
  (dark pixels in the canvas), then checks that the page saw no `securitypolicyviolation` event and logged no policy
  error, which is how a change to PDF.js's settings or its version is caught under `AppServlet`'s policy, and that Esc
  gives the focus back to the row. It also files an `.eml` with an HTML body, a tracking picture on a host under `.invalid`, a link and a PDF attached, and through the page opens it in Quick Look: the body renders in its frame, no request reaches the tracker until **Show Pictures** and one does after, the link opens a page of its own with no `opener`, and **Save to Matter** on the attachment makes its chip show the new doc #. `filing.spec.ts` drops three files on the folder's list (Playwright can't drag real ones, so it dispatches `dragenter`, `dragover` and `drop` with a `DataTransfer` built in the page), one of them an `.eml` the matter already holds, and checks that the tray says **Already in this matter as #**, that the other two are filed and show in the list, and that one file dropped on a row and saved as **Version 2** is version 2.0 of that document. `organising.spec.ts` files six documents and makes two folders through the API, adds the matter to My Matters, and through the page: makes a folder with **New Folder**; renames a document in place with F2, where another extension is refused beside the field and Esc leaves the name alone; moves a document with **More › Move To…**; drags two selected rows onto a folder in the tree (dispatching `dragstart` on the row and then `dragenter`, `dragover` and `drop` with one `DataTransfer` built in the page, so the row's own `dragstart` fills what the drop reads) and checks the folder lights up; and deletes two with the question, which it cancels first, then confirms. It needs the smoke fixture's Everyone group to hold the Rename, Move and Delete permissions. Afterwards the script checks with `curl`
  that the web session refuses a cross-site sign-out and one without the `X-Healdan-Client` header, and still
  answers `GET`s.
  - `keyboard.spec.ts` walks rows 3 to 9 of the spec's manual acceptance by keyboard alone: after the sign-in page it
    presses keys (`page.keyboard`) and never clicks. A control is reached by Tab (`tabTo` in `helpers.ts`, which presses Tab until
    the control has the focus and fails after a bound); `focus()` only starts a step at a row, which Tab and the arrow keys
    reach (row 4 proves Tab gets into the list). It files its own documents and an email into a folder of its own, inside
    `E2E`, a folder of Documents that this spec and `a11y.spec.ts` share so they add no row to Documents' own list (where
    `filing.spec.ts` and `working.spec.ts` look for their documents, among the first rows the list draws). It walks:
    - **Row 3:** `/` to the search box; a suggestion chosen with the arrow keys; a `#` and `#n.N`; a matter number in the
      Matters scope, picked from the scope menu; a word search and its results; the funnel, Filters and a tick.
    - **Row 4:** Tab into the list, and each column sorted from its header by Enter, both ways.
    - **Row 5:** Space, the arrow keys and Esc in Quick Look, and docking the reading pane.
    - **Row 6:** an email in Quick Look, **Show Pictures**, **Save to Matter**, and Enter on the saved attachment, with the
      notice asserted.
    - **Row 7:** Enter to open in the app, **Download** (the download event asserted), **Check Out** and **Release**, and
      **Edit** with Enter to save and Esc to cancel.
    - **Row 8:** **Upload** by Enter, and Option-Enter for the form that comes first.
    - **Row 9:** **New Folder**, F2, **More › Move To…** and Delete, each with where the focus lands asserted.

    It leaves out **Copy Link**, as headless browsers' clipboards differ between engines (`finding.spec.ts` covers it), and
    row 9's move into another matter: the smoke fixture's only matter outside Smoke Cloud is 1.1, and a move into Smoke
    Cloud would write through Garage after step 8 has checked its read cache. A step that can't be done by keys is a bug in the
    app.
  - `a11y.spec.ts` runs axe on each main view in the light theme and again in the dark one (a browser context with
    Playwright's `colorScheme`, signed in once per theme): Recent's four lists, a matter, a folder with details and the
    properties form, Filters (with one in use, and while the counts are held open with `page.route`, since busy states keep
    AA contrast and the last answer's counts must stay, never zeros), the list while it is asked for again, the filing tray and the File Documents form, the rename field, the
    delete question, the Help menu, search results for a word with their Filters, a document's page, Quick Look on a PDF and
    on an email, the reading pane, New Folder, More and Move To… (a matter's client starts clear of it), the person menu, Signed-in Devices (whole in its dialog, with
    no sideways scroll, every Revoke inside it and a name of at most two lines at 1280, and again at 768) with the Revoke question
    and its refusal (and a real revoke, whose focus must stay in the dialog), at 768 × 1024 the folded top bar with the left
    pane, the empty region and the details opened over the list, and last the signed-out screen (the session is ended with
    `DELETE /api/v1/session` from the page). Colour contrast is checked here, as jsdom can't. axe-core is the workspace's own
    development dependency: `e2e/axe.ts` reads its file from `node_modules` and runs it with `page.evaluate`, which the
    browser's debugging protocol does and the page's Content-Security-Policy doesn't govern, so the policy stays as the app
    ships it. No spec turns it off: `addScriptTag` with the source in it would be blocked by `script-src 'self'`, and
    `bypassCSP` would test a page the app never serves.
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
