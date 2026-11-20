---
title: Continuous Integration
weight: 4
---

`master` takes changes only through pull requests, and merges a pull request once its required checks pass.

- **`master`'s ruleset** (Settings → Rules → Rulesets → `master: require CI`) requires a pull request, with no
  approvals, and every check below but `Docs site`. It doesn't require a branch to be up to date with `master`, as
  that would run every open pull request's checks again after each merge. It blocks force pushes and deleting the
  branch, and nothing bypasses it, an admin included.
- **Merge commits only.** The repository allows no squash or rebase merges, as an upstream sync must be merged so
  that the history it shares with LogicalDOC stays intact.
- **Release tags are fixed.** The `Release tags` ruleset blocks moving, force-pushing and deleting `helper-v*` and
  `v*` tags, with no bypass (see [Releasing the macOS Helper](#releasing-the-macos-helper)).

[Issue #9](https://github.com/Healdan-Project/healdan/issues/9) has the reasons for each.

## The Checks

| Check | Workflow | What it does |
|---|---|---|
| `build` | `build-logicaldoc.yml` | Checks the GUI's Font Awesome icons (`check-fontawesome-icons.py`), for links to LogicalDOC's docs (`check-logicaldoc-doc-links.py`), for LogicalDOC's servers (`check-logicaldoc-hosts.py`), for Maven repositories on LogicalDOC's hosts and on upstream's retired ones (`check-maven-repositories.py`), for Oracle's and SQL Server's drivers (`check-database-drivers.py`) and for the licences of the User app's npm packages (`check-web-licences.py`), compiles every module except the WAR without the GWT compile, and runs Healdan's tests in parallel shards, each with a PostgreSQL of its own (see below) |
| `Test (amd64)`, `Test (arm64)` | `docker-image.yml` | Builds the image natively on each architecture, runs the init tool's unit tests, `WarParsersTest`, `WarStorageTest` and `WarHibernateTest`, and runs `docker/smoke-test.sh` against the image, M2's client flow through the API (`test-api.sh`), the helper's open-save-close cycle (its stack test), provider sign-in through a stand-in Keycloak (`test-signin.sh`) and storage backends against a stand-in Garage (`test-storage.sh`) included, all on PostgreSQL 18, then on amd64 the move from MariaDB (`test-move.sh`). A smoke test that fails uploads its logs and its failed browser tests' traces as an artifact (see [The Smoke Test's Results in the Image CI](#the-smoke-tests-results-in-the-image-ci)) |
| `Clients` | `clients.yml` | Builds and tests the desktop clients in `clients/`: the helper on Ubuntu and macOS, the LibreOffice extension's Python tests and the Office add-in's Node tests on Ubuntu (see below) |
| `Web` | `web.yml` | Builds and tests the User app and the Admin app in `healdan-web/web`: generates the APIs' types, typechecks, lints, runs the tests and builds (see below) |
| `Docs site` | `docs-site.yml` | Runs the script tests, builds the docs site strictly, checks its sources (`check-site-sources.py`) and its internal links (`check-site-links.py`), and on pushes to `master` publishes it |

## How `build` Runs

`build` is the last of several jobs, so that the tests can run side by side:

1. **`checks`** runs the icon, doc-link, hosts, Maven repository, database driver and web licence checks, reads the JDK from
   the `Dockerfile`, and plans the test shards with `.github/scripts/test-shards.py`.
2. **`Tests (1 of N)` … `Tests (N of N)`** each compile every module except the WAR, without the GWT compile, the
   javadoc or the plugin zips (the Docker build makes all of those), then run their share of the tests. They skip
   the User app's Node build too (`-Dhealdan.web.skip=true`), which the `Web` check and the Docker build run.
   Each has a `postgres:18` service container, which its tests share, each test making a database of its own on it
   (see [The Test Database](../tests/#the-test-database)). It's set up as `docker/tests/test-db.sh` sets up the
   local one: the same user and password, UTF-8 with the built-in `C.UTF-8` locale, its data on a tmpfs, and
   `fsync`, `synchronous_commit` and `full_page_writes` off, with 300 connections and the password hashed with one
   SCRAM iteration, through `POSTGRES_INITDB_ARGS`' `-c` flags and `--tmpfs` in the service's `options`. Every test
   makes a database and opens about 40 connections, so the server is set up for speed, as nothing on it has to
   last. It publishes no port: a step reads the container's own address and gives it to the tests in
   `HEALDAN_TEST_DB_URL`. A connection to a published port goes through Docker's proxy, which doubled a
   statement's round trip on a runner (46 microseconds without it, 89 with), about 0.09 seconds a test.
3. **`build`** passes when `checks` and every shard passed. It's the check `master` requires.
4. **`test-times`** saves how long each test class took, for the next run's plan. It isn't part of `build`.

The tests are:

- every test class in a `healdan-*` module. A new class, or a new `healdan-*` module in the reactor, is picked up
  without a list.
- the upstream tests named in the workflow's `UPSTREAM_TESTS`, one per line, which cover Healdan's edits to
  upstream code. A line is a class name, or `Class#method`. When a change edits upstream code, add its test class
  there. A name that no upstream module has fails `checks`.

The plan gives each shard about five minutes of tests, with at most eight shards. Each test class weighs its time in
the newest successful run, the pull request's own or else `master`'s, or 3 seconds per `@Test` until a run records
one. The log of `checks`' plan step lists each shard's classes with their times. A failing shard doesn't stop the
others, so one run shows every failing test.

### The Maven Repository Cache

Each shard restores a copy of Maven's local repository before it builds: the copy for the POMs as they are, or else
the newest copy `master` saved. Only `master`'s runs save one, from a single shard, so a pull request that edits a
POM downloads only what its edit adds. Some dependencies come only from Healdan's copies at
`https://maven.healdan.com/`, through a mirror in `docker/maven-settings.xml`.

Maven notes where each file came from in a `_remote.repositories` file beside it, and uses a cached file only when
that note names one of the build's repositories. Maven 3.9 notes the repository's id, and Maven 3.10 its id and a
hash of its URL. 3.10 accepts 3.9's notes, but 3.9 rejects 3.10's, and `ubuntu-latest`'s images ship both. So each
shard deletes those files after restoring the copy, and Maven then takes every cached file as installed locally and
downloads only what's missing. Before that step, on 29 October 2026, shards on Maven 3.9 restored a copy Maven 3.10
had saved, downloaded every file again, and failed on SourceForge's 403.

The Docker build keeps its own copy, which the `maven:3.9` image both writes and reads, so it doesn't need the step.
It keeps npm's cache for the User app's packages beside it, keyed on `healdan-web/web/package-lock.json`, and only
`master`'s amd64 job saves either.

Maven also keeps each repository's list of an artifact's versions, which it needs for a version range, such as the
one in Bouncy Castle's own POMs, and for a plugin with no version. On each new day, by UTC, it asks every repository
for those lists again. When a connection fails it falls back to the list it has, but when a repository answers "not
found" it doesn't, and the build stops at `No versions available for org.bouncycastle:bcutil-jdk18on:jar:[1.81,1.82)
within specified range`, with no warning before it. Two shards failed that way on 19 November 2026, both on runners in
another cloud than the rest. So a shard whose restore of the copy was an exact hit runs Maven with `-nsu`, which
takes the lists from the cached copy and asks a repository only for what the copy lacks. Only an exact hit: its POMs
are those the copy was saved for, so the copy's lists hold every version they need, where a POM that moves to a
release newer than the copy would find none in them. The saving shard still asks, so each copy it saves has that
day's lists. The Docker build asks too, since what it resolves is what the image ships.

### Room for the Caches

The repository has 10 GB of Actions caches. Past that, GitHub deletes the caches used least recently, whatever
saved them, and `gh cache list --repo Healdan-Project/healdan` shows what's there. The Docker build's own layer
cache, which pushes to `master` write and pull requests read, is the largest. It keeps only the final image's layers
(`mode=min`), of which the WAR's, about 0.4 GB per architecture, is the large one a push changes.

It used to keep every stage's layers (`mode=max`), including the Maven stage's 2.1 GB per architecture (its build
output and a second copy of the WAR), so each push to `master` wrote about 5 GB. On 12 November 2026 two pushes in a
row filled the space, GitHub deleted the shards' Maven repository copy, and the next day every shard of a pull
request downloaded every dependency again, and one failed when a download did. The Maven stage's layers spared the
Maven build to any run that left its inputs alone, changing only `docker/`, the `Dockerfile` or the workflows: such a
pull request, the push to `master` that merges it, a `v*` tag of a commit `master` already built, and any re-run of a
`master` job. Each of those now runs the Maven build again, about 4 to 6 minutes per architecture. The clients no
module builds (`clients/helper/`, `clients/libreoffice/` and `clients/icons/`) stay out of the build context
(`.dockerignore`), so changing one doesn't run it. Before adding a cache that's large or saved often, check it fits
beside the others.

## What a Pull Request Runs

`.github/scripts/code-changed.sh` lists the files a pull request changes and writes three answers. `code` is false
when the pull request changes only docs (`docs/`, `site/`, and Markdown outside a `src/` folder) and the helper, the
LibreOffice extension and the icons (`clients/helper/`, `clients/libreoffice/` and `clients/icons/`). A change under
`clients/office/` counts as code as well as clients, because `healdan-api` packages the Office add-in's pages and
manifest into its jar. A Markdown file there is still docs. `code` gates the Java build's test shards and the Docker
build steps. `clients` is true when the pull request changes anything under `clients/`, `clients.yml` itself, or the
API contract, `healdan-api/src/main/resources/healdan/api/openapi.yaml`, which the helper's contract tests read. The
admin API's contract beside it, `openapi-admin.yaml`, doesn't count, as the helper's tests don't read it. `clients` gates the
client test jobs. `web` is true when the pull request changes anything under `healdan-web/`, `web.yml` itself, or
either contract, which the apps' types are generated from. It gates the `Web` check's app job. A push always gets
all three true.

Every workflow still reports its required checks when its steps are skipped, so `master`'s ruleset can require
them all.

| The pull request changes | `build` shards | Docker `Test` jobs | Client test jobs | `Web` app job |
|---|---|---|---|---|
| Only the helper, the extension or the icons in `clients/` | Skipped | Skipped | Run | Skipped |
| `clients/office/`, other than Markdown | Run | Run | Run | Skipped |
| The API contract, `openapi.yaml` | Run | Run | Run | Run |
| The admin API's contract, `openapi-admin.yaml` | Run | Run | Skipped | Run |
| `healdan-web/`, other than Markdown | Run | Run | Skipped | Run |
| Only the server, or anything else outside `clients/` and the docs | Run | Run | Skipped | Skipped |
| Only docs | Skipped | Skipped | Skipped | Skipped |
| Docs and the helper, extension or icons | Skipped | Skipped | Run | Skipped |
| The server and `clients/` | Run | Run | Run | Skipped |

Pushes to `master` that change only docs or Markdown files don't rebuild the image (`paths-ignore` in `docker-image.yml`
and `build-logicaldoc.yml`). A push always gets every answer true, so one that changes only the helper, the extension
or the icons rebuilds it.

## The Web Check

`web.yml` has no paths filter either, so every pull request runs it and the check can be required. Its jobs are:

1. **`changes`** runs `code-changed.sh`.
2. **`User app (ubuntu-24.04)`** sets up the Node version in `healdan-web/web/.nvmrc` and, in `healdan-web/web`,
   runs `npm ci --ignore-scripts`, `npm run generate` (the APIs' types, from `openapi.yaml` and
   `openapi-admin.yaml`), `npm run typecheck` (each app on its own), `npm run lint`, `npm test` and `npm run build`.
   Despite its name, it covers both apps.
3. **`Web`** is the check to require. It passes when the app job passed, or when it was skipped because nothing under
   `healdan-web/`, nor `web.yml`, nor either contract changed. A cancelled run fails it.

`check-web-licences.py` runs in `build`'s `checks` job, not here, so a package with a licence Healdan can't pass on
fails there. It reads `healdan-web/web/package-lock.json` and fails on a package the app ships (one the lockfile
doesn't mark `dev`) with a licence outside MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC and 0BSD, or with none.
A package used only to build or test may also be BlueOak-1.0.0, CC0-1.0, CC-BY-4.0, Python-2.0 or Unlicense. A
package with another licence needs an entry in the script's `ALLOWED` list, with the licence reviewed and the reason
it's acceptable. An entry whose package the lockfile now gives another licence fails, and so does one the lockfile no
longer has, so the list stays honest.

## The Clients Check

`clients.yml` has no paths filter, so every pull request runs it and the check can be required. Its five check jobs
are:

1. **`changes`** runs `code-changed.sh`.
2. **`Helper (ubuntu-24.04)`** and **`Helper (macos-15)`** run `dotnet test clients/helper/Healdan.Helper.sln`, with
   the .NET SDK that `clients/helper/global.json` pins.
3. **`LibreOffice extension (ubuntu-24.04)`** runs `python3 -m unittest discover -s clients/libreoffice/tests -v`,
   which also builds the extension's `.oxt` and checks that its parts agree.
4. **`Office add-in (ubuntu-24.04)`** sets up Node 22 and runs `node --test 'clients/office/test/*.test.js'`, which
   tests the add-in's scripts in `clients/office/web/`: its logic (`healdan.js`), the ribbon's buttons
   (`commands.js`), the Office.js glue (`office-glue.js`) and the pane (`taskpane.js`), with fakes for `fetch`,
   storage, Office and the page. Nothing is installed. The pattern is quoted for Node to expand: Node 21 and later
   read `--test`'s arguments as file patterns, so a folder fails.
5. **`Clients`** is the check to require. It passes when the three test jobs passed, or when all three were skipped
   because nothing under `clients/`, nor the API contract, changed. A cancelled run fails it.

`Clients` is one of `master`'s required checks.

Four more jobs package the helper, two for the Mac and two for Ubuntu. They run only for a `helper-v*` tag or a run
by hand, never for a pull request, and `Clients` doesn't wait for them (see
[Releasing the macOS Helper](#releasing-the-macos-helper) and [Releasing the Ubuntu Helper](#releasing-the-ubuntu-helper)).

The helper's tests marked `[Trait("Category", "Stack")]` need a running Healdan stack and skip here, because
`HEALDAN_STACK_URL` isn't set. The ones marked `MacKeychain` touch the real macOS keychain, so they run by hand on a Mac. The ones marked
`MacOffice` open Word, Excel and PowerPoint on the screen, so they run by hand on a Mac with Office, and skip without
`HEALDAN_MAC_OFFICE=1`; CI has no Office, so they skip there too. One of them, `EveryScriptCompiles`, starts no
app and needs no variable, but compiles the helper's AppleScripts against the apps' dictionaries, which only a Mac
with Office has, so it too is for a Mac with Office.

### The Helper's Stack Tests in the Image CI

`docker/smoke-test.sh` has a step 5d, after M2's client flow through the API (step 5c), that runs the helper's
stack tests (`dotnet test clients/helper/Healdan.Helper.sln --filter Category=Stack`, with `HEALDAN_STACK_URL` set to
the smoke stack). There are two:

- **`StackCycleTests`** signs in as `admin` through the API, files a document in the Smoke Matter, and opens, saves
  and closes it through the helper's cycle with a fake LibreOffice.
- **`StackProviderSignInTests`** signs in as the helper does through the stand-in Keycloak, provider `smoke`, as
  `sso-helper2`, with a fake browser, and checks `/me` names `sso-helper2`. The step sets
  `HEALDAN_STACK_SIGNIN_PROVIDER=smoke`, `HEALDAN_STACK_SIGNIN_USER`, `HEALDAN_STACK_SIGNIN_PASSWORD` and
  `HEALDAN_STACK_KEYCLOAK=127.0.0.1:18180` (`SMOKE_KEYCLOAK_PORT`'s port) for it. It runs before step 5e, which
  disables `sso-helper`, another person.

- It runs only when `SMOKE_HELPER_TEST=1`, which `docker-image.yml` sets. The workflow sets up the .NET SDK and
  builds the helper's tests first, so a build failure shows on its own. Run locally without the variable, the
  smoke test skips the step.
- `dotnet test` passes when no test ran, so the step passes only if its summaries add up to two passed tests and
  none skipped. A `Stack` test added to the helper changes the two (`HELPER_STACK_TESTS` in the script).

A pull request that changes only the helper, the extension or the icons doesn't build the image, so this test
doesn't run on it. It runs on `master` after the merge, and on pull requests that change the server. That gap is
accepted.

### The Stand-In Sign-In Provider in the Image CI

The smoke stack runs a throwaway Keycloak as a stand-in OpenID Connect provider, and its step 5e signs in through it
with `docker/tests/test-signin.sh` (see [The Stand-In Sign-In Provider](../tests/#the-stand-in-sign-in-provider)).

- **Its image,** `quay.io/keycloak/keycloak:26.7.5`, has amd64 and arm64 builds, so both jobs run it natively. It's
  about 475 MB, and the runners pull it on every run, as they start with no images.
- **It starts beside the app,** in 15 to 25 seconds by its own log on a Mac, well inside the app's start, so step 1
  rarely waits for it. Step 1 prints how long it took.
- **Step 5e** takes under 5 seconds on a Mac, and prints how long it took, so a run's log has CI's times.

### The Stand-In S3 Server in the Image CI

The smoke stack runs a throwaway Garage as a stand-in S3 server, and its step 5f runs storage backends against it with
`docker/tests/test-storage.sh` (see [The Stand-In S3 Server](../tests/#the-stand-in-s3-server)).

- **Its images,** `dxflrs/garage:v2.5.0` and `busybox:1.37`, which sets Garage up, have amd64 and arm64 builds, so
  both jobs run them natively. They're small, about 66 MB and 4 MB, and the runners pull them on every run.
  `docker-image.yml` itself names neither: the overlay does.
- **Garage starts and is set up in a second or two,** by its setup's own count, and the app's container starts once
  it is. Step 1 prints how long it took.
- **Step 5f** takes about 40 seconds on a Mac, and prints how long it took, so a run's log has CI's times. About 20
  of them are the first read to meet the outage it makes, which waits for the S3 client to give up, as the first
  read would on a server; the rest is the script's polls for the mover's work. Step 8 runs the script again after
  the restart.

### The Smoke Test's Results in the Image CI

The smoke test removes its stack when it ends, and the browser tests' container goes when they do, so what a failed
run printed was all that was left of it. A single browser test failing now and then couldn't be explained from that:
nothing said whether the request its page waited on had reached the server. So `docker-image.yml` sets
`SMOKE_RESULTS` for the smoke test, and when the smoke test fails its next step, "Upload the smoke test's results",
uploads the folder (see [What a Failed Run Keeps](../tests/#what-a-failed-run-keeps)):

- **The artifact is `smoke-results-<arch>-attempt-<n>`,** such as `smoke-results-amd64-attempt-1`, on the run's
  page, kept 14 days. It holds Tomcat's access log and the server's log, both whole, and under `e2e-results/` a
  folder for each failed browser test with its trace and screenshot.
  `gh run download <run id> --name smoke-results-amd64-attempt-1` fetches it.
- **Only a failed smoke test uploads.** A run that passes keeps no folder and uploads nothing, and a failure in
  another step uploads nothing either. A smoke test that failed before its stack was up has nothing to upload,
  which the step lets pass.
- **The attempt is in the name** because a failed job run again can't upload under a name its first attempt used.
  When the second attempt passes, the first attempt's artifact is the only record of the failure.
- **It costs a run that passes nothing** but Tomcat writing a line for each request, about 20,000 in a run.
- **Artifacts aren't Actions caches,** so they take none of the 10 GB under [Room for the Caches](#room-for-the-caches).
- **Read it** with `npx playwright show-trace <test's folder>/trace.zip`, and find the request the page waited on in
  `access.log` by its time and path ([The Access Log](../tests/#the-access-log) has the line's form).

### The Move's Test in the Image CI

After the smoke test, the amd64 job's `Move test (amd64)` step runs `docker/tests/test-move.sh` with the image the
smoke test ran, which moves a frozen MariaDB install to PostgreSQL with `move-from-mariadb` and starts the app on the
result (see [The Move's Test](../tests/#the-moves-test)).

- **One architecture is enough.** The command is Java, and the fixture is the same on both.
- **It takes about a minute,** and pulls `mariadb:11.4` for the fixture's database. A 15-minute limit stops a stack
  that never comes up there, not at the job's limit.
- **It runs while the command exists,** and goes with it.

### Disk Space on the amd64 Runner

The amd64 runner starts with 14 to 20 GB free, depending on its image's version, and the Maven build, the image, the
smoke stack (Keycloak included) and the .NET SDK use most of it. On 2026-11-02 a run on an older image ran out of disk
in the smoke test, so `Test (amd64)` now starts with a "Free disk space (amd64)" step. It deletes the runner's Android
SDK, Haskell, Swift and CodeQL, which no step uses, and prints the free space before and after, and how long it took.
The arm64 runner hasn't run short, so its job doesn't.

## Things That Catch Upstream Syncs

- **Font Awesome Pro icons.** The GUI bundles Font Awesome Free 6, but upstream writes against Font Awesome Pro.
  Pro styles and Pro-only icons compile, and only show at runtime as blinking placeholders.
  `.github/scripts/check-fontawesome-icons.py` runs first in `build` and catches them. When a sync brings some in,
  swap them for Free icons in the sync PR itself.
- **Links to LogicalDOC's docs.** `.github/scripts/check-logicaldoc-doc-links.py` runs next to the icon check and
  fails `build` on any tracked file that links to docs, wiki, help or forums.logicaldoc.com, naming the file and line.
  When a sync brings one in, point it at docs.healdan.com in the sync PR. Its `ALLOWED` list holds the few exact
  lines Healdan leaves alone, each with its reason. The docs site's pages are checked by `check-site-sources.py`
  instead, which allows no such link.
- **LogicalDOC's servers.** Healdan never contacts LogicalDOC. CE posted usage statistics to `stat.logicaldoc.com`
  and tested the proxy against `activation.logicaldoc.com`, and a sync can bring a call like those back.
  `.github/scripts/check-logicaldoc-hosts.py` runs after the doc-links check and fails `build` on any host, URL or
  email address at logicaldoc.com, .it, .net or .org, naming the file and line. It skips tests, prose, the docs site
  and the SOAP, CMIS and WebDAV XML namespaces, which are identifiers no one fetches. When a sync brings one in,
  remove the call in the sync PR. The script's `ALLOWED` list holds the exact lines nothing contacts, such as the
  POM's credits, each with its reason.
- **LogicalDOC's Maven repository.** Builds get the artifacts only LogicalDOC's repository has from Healdan's copies
  at `https://maven.healdan.com/` (repository `Healdan-Project/maven`), through a mirror in
  `docker/maven-settings.xml`. `.github/scripts/check-maven-repositories.py` runs after the hosts check and
  fails `build` when a POM or the settings file points a repository at a LogicalDOC host the mirror doesn't cover.
  When a sync bumps a hosted artifact or brings a new one, the build fails with `Could not find artifact … in
  healdan`. In the sync PR, check its licence allows hosting it, import it with `python3 import-artifact.py` in a
  checkout of `Healdan-Project/maven`, push, and re-run CI once
  `python3 import-artifact.py --verify https://maven.healdan.com/` passes. One whose licence doesn't allow hosting
  it is dropped from the build instead, as JAI was. A missing `com.logicaldoc:*` artifact is never imported: it
  means a module or version was left behind, so bump it.
- **Upstream's retired Maven repositories.** Upstream's parent POM also declares `java.net` (`maven.java.net`,
  whose certificate chain no longer validates) and `sourceforge` (`oss.sonatype.org`, Sonatype's retired OSSRH,
  which answers 404). Nothing comes from either, but Maven asks every repository for each version range's metadata,
  so every shard failed a TLS handshake with `java.net` and warned of it dozens of times. A second mirror in
  `docker/maven-settings.xml`, `retired-repositories`, sends both to Maven Central. The same check fails `build`
  when a POM or the settings file has a repository on either host under an id that no mirror to Central, or to
  Healdan's copies, covers. When a sync brings one in, add its id to that mirror's `mirrorOf` in the sync PR.
- **Oracle and SQL Server.** Healdan supports only open-source databases, so the WAR ships neither driver and the
  setup wizard doesn't offer them. `.github/scripts/check-database-drivers.py` runs after the Maven repository
  check and fails `build` on a POM dependency on Oracle's or SQL Server's JDBC driver, or on a tracked file naming
  one of their driver classes, as the wizard's database list did, naming the file and line. It skips tests, prose
  and the docs site. When a sync brings one in, take it out in the sync PR. CE's `isOracle()` and `isSQLServer()`
  code paths and upstream's `.oracle` and `.mssql` SQL variants stay, and the check leaves them alone.
- **Broken POMs.** Upstream sometimes ships a POM that fails Maven's validation, such as a duplicate dependency. If
  CI fails within a minute, check the POMs, the icon check, the doc-links check, the hosts check, the Maven
  repository check and the database driver check first.

## Publishing the Image

`docker-image.yml` builds and tests the image on every push to `master` and every `v*` tag (pushes that only change
docs are skipped), but publishes it to `ghcr.io/healdan-project/healdan` only while the repository variable
`GHCR_PUBLISH` is `true`. It's off today. To switch it on:

1. Set the variable, under Settings → Secrets and variables → Actions → Variables, or with:

   ```bash
   gh variable set GHCR_PUBLISH --body true --repo Healdan-Project/healdan
   ```

   The workflow signs in with its own `GITHUB_TOKEN`, so no secret is needed.
2. After the first publish, open the package (the Healdan-Project organisation → Packages → `healdan`). If it's
   private, make it public under Package settings → Change visibility, so pulls work without `docker login`.

To publish a release, push a tag such as `v9.3.2-1`.

### Once Images Are Published

The image is tagged `latest` and `master` for the latest build of `master`, `sha-<commit>` (the first 7 characters
of the commit's hash) for one commit's build, and `v…` for a release. An install then sets `LOGICALDOC_IMAGE` in
`.env` to one of them and runs `docker compose pull` and `docker compose up -d`, without `--build`, which would
build from the checkout and store the result under the published name. The Install manual gets a page for this, and
its Upgrading page the advice on pinning tags, in the pull request that switches publishing on.

## Releasing the macOS Helper

The helper has versions of its own, such as `1.2.3` or `1.2.3-rc1`. Pushing the tag `helper-v1.2.3` runs
`clients.yml`'s tests, then two more jobs:

1. **`Package (macos-15)`** runs `clients/helper/packaging/mac/package.sh 1.2.3`, which publishes the helper as a
   single self-contained file for Apple silicon, builds `Healdan Helper.app` with the LibreOffice extension's
   `healdan.oxt` and the app's icon inside, signs it with the Developer ID, has Apple notarise it, and wraps it in
   `Healdan-Helper-1.2.3.dmg`, signed and notarised too. It uploads the disk image as the run's artifact. On a tag
   it first waits for an approval: the run shows **Waiting** until the `helper-release` environment's reviewer
   chooses **Review deployments → Approve and deploy**, and only then does the job get the signing secrets.
2. **`Release (macOS)`** attaches the disk image to the tag's GitHub release, and makes the release first if
   there isn't one (a pre-release for a version like `1.2.3-rc1`). It runs only for a pushed tag, and only once
   `xcrun stapler validate` has found the disk image's notarisation ticket. It's the only job with write access to
   the repository, and it sees no signing secret.

Only official releases stay on GitHub. Once `1.2.3`'s disk image is attached, `Release (macOS)` deletes the
releases of `1.2.3-rc1` and the version's other pre-releases, whole, so with their disk images and the Ubuntu `.deb`s
attached to them. Their tags stay, as the record
of what was tested. It also refuses to publish a pre-release of a version that's already released, so re-running an
old `helper-v1.2.3-rc1` run attaches nothing.

A tag can't be moved or deleted once it's pushed: the `Release tags` ruleset blocks it, with no bypass. A tag pushed
on the wrong commit stays, and the next release candidate (`helper-v1.2.3-rc2`) replaces it. Pushing a new tag isn't
restricted, which is why the signing waits for an approval.

`package.sh` tries `hdiutil` three times, as it's sometimes busy on CI's runners. Stapling a ticket and checking it
both fetch the ticket from Apple, whose ticket server dropped the connection on 0.4.0-rc3, 0.6.0 and 0.7.0, on the
last two for longer than three tries 10 seconds apart. So `clients/helper/packaging/mac/stapler.sh` runs each `xcrun stapler staple` and
`xcrun stapler validate`, for the app, the disk image and the job's own check: it tries five times, waiting 15, 30, 60
and then 120 seconds, but only while stapler exits with 68, its code for a lost connection. Any other failure, such
as a ticket Apple doesn't have, ends it at once: `package.sh` stops, and the job's own check takes the disk image for
one that isn't notarised. A connection still lost after the fifth try fails the job, rather than leaving the release
out. A run that fails can be run again with "Re-run failed jobs", which builds, signs and notarises from the start.

Actions → Clients → Run workflow packages any branch or tag by hand, under a version you give or
`0.0.0-dev.<run>`, and attaches nothing to a release. Only a `helper-v*` tag's run is signed: a branch's is built
unsigned.

Signing needs six secrets in the repository's `helper-release` environment (Settings → Environments →
`helper-release` → Environment secrets). Only `helper-v*` tags may use it, so a run on a branch can't sign with the
Developer ID. The environment also has a required reviewer, the repository's owner, whom admins can't bypass, so a
pushed tag gets the secrets only once they approve its run. Each is set with `gh secret set <name> --env helper-release --repo Healdan-Project/healdan`:

| Secret | What it holds |
|---|---|
| `APPLE_DEVELOPER_ID_P12` | The Developer ID Application certificate and its private key, exported from Keychain Access as a `.p12` file, in base64 (`base64 -i developer-id.p12`) |
| `APPLE_DEVELOPER_ID_PASSWORD` | The password the `.p12` file was exported with |
| `APPLE_TEAM_ID` | The Apple developer team's ID, the 10 characters in the certificate's name |
| `NOTARY_KEY` | An App Store Connect team API key (Users and Access → Integrations → App Store Connect API → Team Keys → Generate API Key, with the Developer role; notarytool can't use an individual key), its `.p8` file in base64 |
| `NOTARY_KEY_ID` | That key's ID |
| `NOTARY_ISSUER` | The Issuer ID shown above the team keys |

The job imports the certificate into a keychain of its own, which it deletes at the end, and decodes the key into
the runner's temporary folder, which it also clears. On a branch, or without the first three secrets (as on a fork),
the app gets only an ad-hoc signature, without the hardened runtime, and without the last three it isn't notarised. Either way the job says so,
uploads the disk image as its artifact, and attaches nothing to the release.

On a Mac with the Developer ID certificate in the login keychain, the same script builds a signed package
(`OUT_DIR` sets where it goes, `clients/helper/packaging/mac/out/` by default). A `SIGN_IDENTITY` it can't find
in the keychains stops it, rather than building without a signature:

```bash
SIGN_IDENTITY="Developer ID Application: <name> (<team ID>)" clients/helper/packaging/mac/package.sh 1.2.3
```

`NOTARY_KEY` (the `.p8` file's path), `NOTARY_KEY_ID` and `NOTARY_ISSUER` notarise it as well.
## Releasing the Ubuntu Helper

The same `helper-v1.2.3` tag runs two more jobs, for Ubuntu, beside the Mac's. They need no signing secrets, so there's
no approval to wait for.

1. **`Package (Linux)`** (`package-linux`) runs for a tag or by hand, after the helper's and the extension's tests. It
   runs on an `ubuntu-24.04` runner, in an `ubuntu:26.04` container, so its install check runs on the release the
   package is for. It runs `clients/helper/packaging/linux/package.sh` for `amd64` and for `arm64`, installs the amd64
   `.deb` with `apt-get` (without its Recommends, which only add LibreOffice's windows) and checks with `ldd` that nothing
   the package installed is missing a library (and fails when it finds no file to check), and uploads
   both `.deb`s as the artifact `Healdan-Helper-<version>-linux`.
2. **`Release (Linux)`** (`release-linux`) runs only for a pushed `helper-v*` tag, after both `package-linux` and
   `release-mac`, and attaches the two `.deb`s to the release `release-mac` made. So a Mac build that isn't
   notarised leaves that tag without a release for Ubuntu too: run the tag's jobs again once the Mac's passes. The
   release's notes, which `release-mac` writes, cover both systems: on Ubuntu to install Ubuntu's own LibreOffice first,
   and on both to quit Healdan Helper before installing a new version and open it again after.

A run by hand packages any branch under a version you give, or `0.0.0-dev.<run>`, and attaches nothing. `Clients`
doesn't wait for either job. What the package holds is in [Desktop Clients](../desktop-clients/#the-package).

