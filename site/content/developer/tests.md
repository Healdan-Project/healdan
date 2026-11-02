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
- **Its token endpoint checks what a real one does:** the client's id and secret, a code used once, the redirect
  address the code was issued for, a verifier that matches the PKCE challenge, and refresh tokens that turn over on
  each use.
- **It can misbehave on purpose:** a bad signature, the wrong issuer or audience, an expired token, a refused or
  unanswered refresh, and the like, for the checks Healdan makes.
- **Plain HTTP.** While it runs it allows providers over plain HTTP, which only the smoke stack's overlay allows
  outside the tests. `SignInTestHooks` sets that by hand, and moves the clocks that time pending sign-ins, the
  helper's codes and the re-checks. A test that uses it resets it when it ends, or the setting leaks into the next
  test class.

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
- Their `context.properties` is `healdan-core`'s test copy, which the build copies in.
- **`AddinResourceTest`** checks the Office add-in's pages and manifest: the files served, the Content Security Policy,
  and a manifest that names the server's own address. `/api/addin/` isn't JSON API, so `Contract` and
  `RouteCoverageTest` leave it out, as they do `/docs`.

## Docker Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | the init tool's unit tests |
| `docker/tests/test-init.sh` | the init tool against a throwaway MariaDB |
| `docker/tests/test-image.sh` | the image: a prompt stop during init, file ownership, the `/setup` block, Admin > Restart, settings that persist |
| `docker/tests/test-overlays.sh` | the dev and proxy overlays |
| `docker/tests/test-fulltext.sh` | full-text search: a `.docx` and an `.eml` are indexed and found by their words |
| `docker/tests/test-previews.sh` | Office and drawing previews: converted ahead of time, text, markup and thumbnails, no fetching or macros while converting, a broken file failing cleanly, leftover processes cleaned up |
| `docker/tests/test-proxies.sh` | which forwarded addresses Tomcat believes with `LOGICALDOC_TRUSTED_PROXIES` unset: a header from the Docker host, but not a private address in the chain or a header sent from inside the app's container. It reads the sign-in throttle's per-address counters, so it needs a running stack: `BASE=http://127.0.0.1:8080 APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-proxies.sh` |
| `docker/tests/test-api.sh` | the API through the image's real filter chain, as a new non-admin user: M2's client flow (sign in, find the smoke matter, file a `.docx`, check it out, save twice, download two versions, sign out), the docs page, a session cookie refused without a token, the sign-in throttle, and LogicalDOC's `loginApiKey` refusing the token. Then M3's additions: searching inside the matter, a new folder and a favourite, a stamped `.docx`, a second device refused with `held-elsewhere`, moving the check-out, `ifVersion` and a repeated `saveId`, and the `#1234 v2.1` lookup. Run it against a fresh stack with the smoke overlay: `BASE=http://127.0.0.1:8080 docker/tests/test-api.sh` |
| `docker/tests/test-signin.sh` | provider sign-in through the stand-in Keycloak (see below), with `curl` and a cookie jar: the web page's sign-in ending at `frontend.jsp` signed in, a local account linked by email losing its password and its device token, the gate refusing someone in none of the mapped groups, the helper's sign-in handing a code to a pretend loopback address and the code exchanged for a device token, and a person disabled at Keycloak losing that token at its next re-check. Run it against a fresh stack with the sign-in overlay: `BASE=http://127.0.0.1:8080 KEYCLOAK=127.0.0.1:18180 docker/tests/test-signin.sh` |
| `docker/smoke-test.sh` | end to end: build, start, REST login, the API answering, the forwarded addresses Tomcat believes (`test-proxies.sh`, step 2b), the GUI's loaders sent with `Cache-Control: no-cache` (step 2), uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, M2's client flow through the API (`test-api.sh`, step 5c), the helper's open-save-close cycle (step 5d, only with `SMOKE_HELPER_TEST=1` and the .NET SDK), provider sign-in through the stand-in Keycloak (`test-signin.sh`, step 5e), the Office add-in's manifest naming the stack (step 2), and data surviving a restart, which replaces an older image's `cachecontrol` default (step 8). With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one |

### The Stand-In Sign-In Provider

The smoke stack signs in through a real OpenID Connect provider, a throwaway Keycloak, as well as with passwords.
`docker/smoke-test.sh` adds the overlay `docker/tests/compose.signin.yaml`, which:

- **runs Keycloak 26.7.5** (`quay.io/keycloak/keycloak`) in development mode, which imports the realm `healdan` from
  `docker/tests/keycloak/healdan-realm.json` each time its container starts. The realm has a confidential client
  `healdan` that requires PKCE `S256` and puts the person's groups in the ID token's `groups` claim, the groups
  `healdan-users` and `healdan-other`, and three people: `sso-user` and `sso-helper` in `healdan-users`, and
  `sso-outsider` in `healdan-other`. Their passwords are throwaway ones, in the realm file.
- **publishes it on `127.0.0.1:18180` only** (`SMOKE_KEYCLOAK_PORT` changes the port). Keycloak names itself
  `http://keycloak:8080` in its discovery document and tokens, the address the app reaches it at on the stack's
  network. `test-signin.sh`'s `curl` sends that name to the published port with
  `--connect-to keycloak:8080:127.0.0.1:18180`. The smoke test waits for its health in step 1, through the
  container's health check, since Keycloak answers it on its management port, 9000, which isn't published.
- **sets Healdan up to use it.** It sets `HEALDAN_SIGNIN_ALLOW_HTTP=true`, as Keycloak's address is plain HTTP, and
  the provider's issuer, client id and secret in `HEALDAN_SMOKE_SIGNIN_ISSUER`, `HEALDAN_SMOKE_SIGNIN_CLIENT_ID` and
  `HEALDAN_SMOKE_SIGNIN_CLIENT_SECRET`. With all three set, the `smoke-signin` setup step adds the provider `smoke`,
  linking by email, maps `healdan-users` to a new group `Smoke SSO`, makes a local account `sso-user` with
  Keycloak's `sso-user`'s email and a password, so the first sign-in links it, and sets `signin.recheck.minutes` to
  `0`, so a device token is checked with Keycloak on every request. Production never sets these variables, and
  without them the step does nothing.

Step 5e times itself, and step 1 prints how long Keycloak took to start, by its own log.

### When a Smoke Check Fails

The server's warnings go to its own log, `repository/logs/dms.log` in the repository volume, not to the container's
output. So when a check fails, or the script stops early, `docker/smoke-test.sh` prints the server's log before it
removes the stack. It's at the end of the script's output, after `SOME CHECKS FAILED` when a check failed, and in CI
at the end of the `Smoke test` step of the `Test (amd64)` or `Test (arm64)` job:

- **The app container's last 150 lines of output:** the entrypoint, the init tool and Tomcat's start.
- **Keycloak's last 40 lines of output,** for the stand-in sign-in provider.
- **The run's warnings and errors from `dms.log`,** the last 200 lines of them. Each keeps its exception's message
  and `Caused by` lines, without the stack frames.
- **`dms.log`'s last 40 lines.**

`dms.log` covers the whole run. Step 8 replaces the container but keeps the volume, so its restart doesn't lose the
earlier steps' lines. Every `FAIL` line ends with its time in UTC, and the smoke stack logs in UTC, so the log's
lines from around a failure are easy to find.

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
- **Against a stack,** the tests marked `Stack` need `HEALDAN_STACK_URL`; `docker/smoke-test.sh` runs them.

## Script and Docs Site Tests

The scripts in `.github/scripts/` and the docs site have Python tests, which need only Python 3 and, for the
site's build test, Hugo:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```
