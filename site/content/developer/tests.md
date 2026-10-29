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

## Docker Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | the init tool's unit tests |
| `docker/tests/test-init.sh` | the init tool against a throwaway MariaDB |
| `docker/tests/test-image.sh` | the image: a prompt stop during init, file ownership, the `/setup` block, Admin > Restart, settings that persist |
| `docker/tests/test-overlays.sh` | the dev and proxy overlays |
| `docker/tests/test-fulltext.sh` | full-text search: a `.docx` and an `.eml` are indexed and found by their words |
| `docker/tests/test-previews.sh` | Office previews: converted ahead of time, text, markup and thumbnails, no fetching or macros while converting, a broken file failing cleanly, leftover processes cleaned up |
| `docker/tests/test-proxies.sh` | which forwarded addresses Tomcat believes with `LOGICALDOC_TRUSTED_PROXIES` unset: a header from the Docker host, but not a private address in the chain or a header sent from inside the app's container. It reads the sign-in throttle's per-address counters, so it needs a running stack: `BASE=http://127.0.0.1:8080 APP_CONTAINER=<id> DB_CONTAINER=<id> docker/tests/test-proxies.sh` |
| `docker/tests/test-api.sh` | the API through the image's real filter chain, as a new non-admin user: an Office add-in's flow (sign in, find the smoke matter, file a `.docx`, check it out, save twice, download two versions, sign out), the docs page, a session cookie refused without a token, the sign-in throttle, and LogicalDOC's `loginApiKey` refusing the token. Then M3's additions: searching inside the matter, a new folder and a favourite, a stamped `.docx`, a second device refused with `held-elsewhere`, moving the check-out, `ifVersion` and a repeated `saveId`, and the `#1234 v2.1` lookup. Run it against a fresh stack with the smoke overlay: `BASE=http://127.0.0.1:8080 docker/tests/test-api.sh` |
| `docker/smoke-test.sh` | end to end: build, start, REST login, the API answering, the forwarded addresses Tomcat believes (`test-proxies.sh`, step 2b), uploads into a seeded matter, thumbnails, Healdan's schema, doc #s and filing rule, the API's add-in flow (`test-api.sh`, step 5c), the helper's open-save-close cycle (step 5d, only with `SMOKE_HELPER_TEST=1` and the .NET SDK), and data surviving a restart. With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building one |

### When a Smoke Check Fails

The server's warnings go to its own log, `repository/logs/dms.log` in the repository volume, not to the container's
output. So when a check fails, or the script stops early, `docker/smoke-test.sh` prints the server's log before it
removes the stack. It's at the end of the script's output, after `SOME CHECKS FAILED` when a check failed, and in CI
at the end of the `Smoke test` step of the `Test (amd64)` or `Test (arm64)` job:

- **The app container's last 150 lines of output:** the entrypoint, the init tool and Tomcat's start.
- **The run's warnings and errors from `dms.log`,** the last 200 lines of them. Each keeps its exception's message
  and `Caused by` lines, without the stack frames.
- **`dms.log`'s last 40 lines.**

`dms.log` covers the whole run. Step 8 replaces the container but keeps the volume, so its restart doesn't lose the
earlier steps' lines. Every `FAIL` line ends with its time in UTC, and the smoke stack logs in UTC, so the log's
lines from around a failure are easy to find.

## Desktop Client Tests

The helper's and the LibreOffice extension's tests run with `dotnet test` and Python's `unittest`, apart from
Maven: see [Desktop Clients](../desktop-clients/#tests) for what they cover, and
[Building from Source](../building-from-source/#the-desktop-clients) for the commands.

## Script and Docs Site Tests

The scripts in `.github/scripts/` and the docs site have Python tests, which need only Python 3 and, for the
site's build test, Hugo:

```bash
python3 -m unittest discover -s .github/scripts/tests -v
```
