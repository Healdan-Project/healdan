# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

This repo, Healdan (`Healdan-Project/healdan` on GitHub), is a hard fork of LogicalDOC Community Edition (LGPLv3), a Java document management system. It started from 9.3.1 and follows upstream's releases; the root POM's version is the one it's on. It's detached from GitHub's fork network. The plan is to take it in its own direction (a new UI, bespoke features, replacements for features LogicalDOC sells as paid extras) while still merging upstream core and security fixes.

- Upstream is `https://github.com/logicaldoc/community.git`. A fresh clone needs `git remote add upstream <that url>`.
- Sync upstream by merging `upstream/master` into a dedicated branch and opening a PR to `master`. Merge, never squash or rebase, so the shared history stays intact. Keep sync PRs free of feature work.
- Healdan's own modules (`healdan-*`) follow upstream's version: their POM parent and `plugin.xml` version match the root POM's. A module left behind makes Maven fetch stock LogicalDOC jars, which lack the fork's classes.
  - When a clean sync merge brings a LogicalDOC release bump, `.github/scripts/upstream-sync.sh` adds a "Follow upstream to <version>" commit. After a conflicted merge it doesn't, so whoever resolves the conflicts bumps them too.
- Upstream changes `logicaldoc-core` and `logicaldoc-gui` the most. To keep syncs cheap, put new work in new plugin modules and avoid editing upstream files where possible.
- Upstream sometimes ships POMs that fail Maven validation (for example, a duplicate dependency). If CI fails within a minute, check POM errors, the icon check and the doc-links check (next bullets) first.
- The GUI bundles Font Awesome Free 6, but upstream writes the GUI against Font Awesome Pro. Pro styles (`fal`, `fa-light`, `fa-thin`, …) and Pro-only icon names compile fine and only show up at runtime, as blinking missing-icon placeholders.
  - `.github/scripts/check-fontawesome-icons.py` catches them. It runs first in `build-logicaldoc.yml`, and `build` must pass before a PR can merge. Run it locally with `python3 .github/scripts/check-fontawesome-icons.py`.
  - When it flags lines an upstream sync brought in, swap them for Free icons in the sync PR itself. That's a merge fix, not feature work.
- Outside the docs site's interim pages, nothing links to LogicalDOC's docs (docs, wiki, help or forums.logicaldoc.com). `.github/scripts/check-logicaldoc-doc-links.py` runs next to the icon check and fails `build` on one, naming the file and line. When a sync brings one in, point it at docs.healdan.com in the sync PR, a merge fix like the icons. Its `ALLOWED` list holds the few exact lines Healdan leaves alone, each with its reason (the `SystemInfo` defaults `HealdanSystemInfo` replaces).
- `README.md` opens with Healdan's notice that it's a modified version of LogicalDOC CE, which the LGPL asks for, then points to the docs site. It's Healdan's own now: when a sync brings an edit to upstream's README, keep Healdan's. `Build_this_Sources.md` and `docker/README.md` are short pointers to the site.

## Roadmap

- `docs/ROADMAP.md` holds Healdan's direction: who it's for, the glossary, the milestone order, and what's decided versus open for each milestone. Read it before starting feature work.
  - Healdan's *Store* and *Workspace* don't mean what LogicalDOC's `Store` and workspace mean. See its glossary.
  - Each milestone gets a design spec in `docs/superpowers/specs/` before anyone builds it. Update the milestone's status in the roadmap as it moves.
- `docs/reference/logicaldoc-ce-capability-map.md` records what CE already has, what's only hidden by GUI feature gates, what's half built and what's missing, with file paths. Check it before re-researching.

## Build and test

You need JDK 25 and Maven 3.9+. The JDK is the one the `Dockerfile`'s `MAVEN_IMAGE` and `TOMCAT_IMAGE` name; CI's `build` check reads it from there and fails if the two differ. `source.level` in `logicaldoc-parent/pom.xml` sets the Java language level, and the GWT client compiles at source level 17. Some dependencies aren't on Maven Central. They come from `https://logicaldoc.sourceforge.net/maven/` and `https://repo.terracotta.org/maven2`, which `docker/maven-settings.xml` lists. CI and the Docker build copy it to `~/.m2/settings.xml`; copy it there for local builds.

```bash
mvn -B -ntp -DskipTests install                          # full reactor build (what the Docker image runs)
mvn -B -ntp -pl logicaldoc-webapp -am -DskipTests package # deployable WAR -> logicaldoc-webapp/target/*.war
mvn -pl logicaldoc-core test                              # one module's tests (sibling modules must be installed first)
mvn -pl logicaldoc-core test -Dtest=DocumentManagerTest   # one test class
mvn -pl logicaldoc-core test -Dtest='DocumentManagerTest#testCopyToFolder' # one method
mvn -pl healdan-core test -Dtest=FilingRulesTest          # Healdan's tests, or one class of them
mvn -pl healdan-core -DskipTests install                  # healdan-core with its test-jar, which healdan-api's tests need
mvn -pl healdan-api test -Dtest=FolderResourceTest        # the API's tests, or one class of them
dotnet test clients/helper/Healdan.Helper.sln             # the desktop helper's tests (.NET 10 SDK; global.json pins it)
dotnet test clients/helper/Healdan.Helper.sln --filter "Category=MacKeychain" # the real macOS keychain, by hand on a Mac
python3 -m unittest discover -s clients/libreoffice/tests -v # the LibreOffice extension's tests (standard library only)
clients/libreoffice/build.sh                              # the LibreOffice extension, packaged as clients/libreoffice/healdan.oxt (reproducible)
dotnet run --project clients/helper/tools/IconRender      # the extension's toolbar images and icon, from the helper's icons (by hand, on a Mac)
clients/helper/packaging/mac/package.sh 1.2.3             # Healdan Helper.app and its .dmg, on a Mac; signed only with SIGN_IDENTITY set
HEALDAN_HELPER_DEV_ROOT=/tmp/hh dotnet run --project clients/helper/Healdan.Helper # a Debug helper with its files apart; no links, notifications or .oxt
```

- `logicaldoc-parent` is a reactor module, and the root POM inherits from it, so dependency and plugin versions live there.
- `logicaldoc-gui` runs a GWT compile for each GWT module (frontend, login, setup), which is slow. Skip it with `-Dgwt.skipCompilation=true` when you aren't touching the UI; the module's Java still compiles, which `healdan-core` needs.
  - Up to `test` that's enough. For `package` or `install`, add `-Dmaven.antrun.skip=true` and `-pl '!logicaldoc-webapp'`, as CI's `build` check does. The GUI's antrun step fails without the GWT output, and skipping antrun leaves every plugin zip unfinished, so the WAR built from them is broken.
- Spring-based tests fail in `setUp` unless `~/logicaldoc-dev.properties` exists. An empty file works; CI creates one.
- `healdan-core`'s and `healdan-api`'s tests run against the installed jars of the modules they use. After changing `logicaldoc-core`, `logicaldoc-webapp` or `logicaldoc-gui`, re-install that module (`-pl <module> -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install`) before running them.
- `healdan-api`'s tests also need `healdan-core` installed, test-jar included: they reuse its `AbstractHealdanTestCase`, fixtures and test `context.properties`. Re-install it after changing `healdan-core`.
  - `-DskipTests` still compiles tests, so it builds and installs that test-jar. `-Dmaven.test.skip=true` doesn't, and `healdan-api`'s `with-tests` profile then leaves the test-jar dependency out, so the build works but `healdan-api`'s tests can't run until `healdan-core` is installed with `-DskipTests`.
- CI's `build` check compiles every module except the WAR, skipping the GWT compile, and runs Healdan's tests in parallel shards. The Docker image CI does the GWT compile and the WAR. Upstream modules' other tests don't run in CI.
  - The tests are every test class in a `healdan-*` module (found without a list) and the upstream tests named in the workflow's `UPSTREAM_TESTS`, which cover Healdan's edits to upstream code. Add a test class there when a change edits upstream code. A name no upstream module has fails the check.
  - The `checks` job runs the quick checks, then `.github/scripts/test-shards.py` splits the tests into shards of about five minutes (at most eight). Each class weighs its time in the newest successful run, which the `test-times` job caches, or 3 s per `@Test` until it has one. Each `Tests (n of N)` job installs the modules, without javadoc or plugin zips, and runs its share. The `build` job, the check master requires, passes when they all did, or when a docs-only pull request skipped them.
  - Its Maven repository cache falls back to master's newest copy, and only master saves it (from one shard), so a POM edit downloads only what it adds (`logicaldoc.sourceforge.net` sometimes refuses requests).
- `.github/scripts/code-changed.sh` writes two outputs for a pull request (a push gets both true):
  - `code` is false when it changes only docs (`docs/`, `site/`, Markdown outside `src/`), `clients/`, or both. The Java and Docker workflows then skip their build steps and still report the required checks.
  - `clients` is true when it changes anything under `clients/`, `clients.yml`, or the API contract (`healdan-api`'s `openapi.yaml`, which the helper's contract tests read). `clients.yml` otherwise skips its test jobs.
- `.github/workflows/clients.yml` runs the helper's `dotnet test` on `ubuntu-24.04` and `macos-15`, and the extension's Python tests on `ubuntu-24.04`. Its `Clients` job passes when they did, or were skipped because nothing under `clients/` changed. `Clients` goes into master's ruleset after its first green run.
  - A `helper-v1.2.3` tag (or a run by hand) also runs `package-mac`, which packages the macOS helper with `clients/helper/packaging/mac/package.sh`, and `release-mac`, which attaches the `.dmg` to the tag's release, only for a pushed tag and once `xcrun stapler validate` passes on it. They never run for pull requests, and `Clients` doesn't need them.
  - Signing and notarising need six secrets (`APPLE_DEVELOPER_ID_P12`, `APPLE_DEVELOPER_ID_PASSWORD`, `APPLE_TEAM_ID`, `NOTARY_KEY`, `NOTARY_KEY_ID`, `NOTARY_ISSUER`; the Developer Guide's CI page says what each holds). Without them the package is built unsigned and only uploaded as an artifact.
- Helper tests with `[Trait("Category", "Stack")]` need `HEALDAN_STACK_URL` and skip without it. `docker/smoke-test.sh` step 5d runs them against the smoke stack, only with `SMOKE_HELPER_TEST=1` (CI sets it, after setting up .NET), and fails unless one passed and none skipped. A `clients/`-only pull request doesn't build the image, so that test runs on master after the merge and on pull requests that change the server.
- `logicaldoc-webservicesamples` isn't in the reactor.

## Docker

- `Dockerfile` (stages `build` → `tools` → `runtime`) and `compose.yaml` are this fork's own. The docs site's Install manual (`site/content/install/`) is the user guide, and its Developer Guide covers the dev overlay, tests and CI; the design is `docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md`.
- The container never runs the setup wizard. `docker/tools/src/DockerInit.java` seeds `context.properties` and, on a fresh database, creates the schema by running the `DbInit` scripts from every `plugin.xml`, so a plugin's `DbInit` extension is picked up for new installs. Existing databases are never migrated, apart from Healdan's own `hd_` tables, which Flyway upgrades on every start (see "Healdan's code"). A plugin or upstream change that adds SQL to CE's tables needs it applied by hand there.
- On every start, keys in the image's default `context.properties` that the conf volume lacks are added. Values are never overwritten, except `product.release` and `product.year` (`ConfigPlan.IMAGE_KEYS`): they describe the image, so they're always taken from it.
- `docker/server.xml`'s `RemoteIpValve` believes `X-Forwarded-For` and `-Proto` only from `LOGICALDOC_TRUSTED_PROXIES`, or, when it's unset, the Docker networks the container is on. `DockerInit` (`TrustedProxies`) works the list out and checks it on every start, and `setenv.sh` passes it to Tomcat as `healdan.trustedProxies`.
- LogicalDOC only runs external programs listed in a classpath `allowed-commands.txt`. The lines in `docker/allowed-commands.txt` are re-added to the conf volume's copy on every start; a new external tool needs a line there.
- Tests (the Developer Guide's Tests page describes each):
  - `docker build --target tools .` for the init tool's unit tests
  - `docker/tests/test-*.sh`
  - `docker/smoke-test.sh` for end to end. Its step 2b runs `docker/tests/test-proxies.sh`, which forwarded addresses Tomcat believes. Its step 5c runs `docker/tests/test-api.sh`, the Office add-in's flow through `/api/v1` with a Bearer token, through CE's real filter chain (sign in, file, check out, save, download, the throttle, LogicalDOC's `loginApiKey` refusing the token, sign out). That script needs a fresh stack with the smoke overlay (it signs in as `admin`/`admin`, and leaves a username blocked for 15 minutes). Its step 5d, only with `SMOKE_HELPER_TEST=1` (CI sets it, after setting up .NET), runs the helper's stack test (`dotnet test clients/helper/Healdan.Helper.sln --filter Category=Stack` with `HEALDAN_STACK_URL`): `admin` opens, saves twice and closes a document through the helper's cycle.
- **The smoke fixture overlay.** Healdan refuses documents outside a matter, and a fresh stack has none. `docker/tests/compose.smoke.yaml` sets `HEALDAN_SMOKE_FIXTURE=true`, which makes the `smoke-fixture` seed create Store "Smoke", a template, client 1 and matter 1.1 with a Documents folder. `docker/smoke-test.sh` adds the overlay itself. Add it (`-f compose.yaml -f docker/tests/compose.smoke.yaml`) to a throwaway stack that needs somewhere to upload. Production never sets the variable.
- CI: `.github/workflows/docker-image.yml` builds on native amd64 and arm64 runners and runs the `tools` target and `docker/smoke-test.sh` on each. Only pushes write the build cache, which pull requests read. BuildKit doesn't save the Maven build's cache mount, so `actions/cache` and `buildkit-cache-dance` keep its dependencies between runs; only master's amd64 job saves them. It publishes to `ghcr.io/healdan-project/healdan` from `master` and `v*` tags only while the repository variable `GHCR_PUBLISH` is `true`.

## Docs site

- `site/` is the source of https://docs.healdan.com. It's Hugo 0.168.0 (the standard edition) with the Hextra theme vendored in `site/_vendor/`, so building needs neither Go nor the network. Preview with `hugo server --source site`. The design is `docs/superpowers/specs/2026-10-23-docs-site-design.md`.
- **Docs change in the same PR as the code they describe.** A change to something a manual describes updates its pages, and a new feature adds them. Each milestone's spec names the pages it adds or changes, and its plan has tasks for them.
- Each manual is a top-level folder (`install/`, `user/`, `admin/`, `developer/`) with `cascade: { type: docs }`, and the left nav shows only the current one. `site/layouts/_partials/sidebar.html` overrides Hextra's for the phone menu, with each change marked `Healdan:`; re-apply them when upgrading Hextra.
- The writing rules are on the Developer Guide's Writing Docs page (`site/content/developer/writing-docs.md`): Title Case for titles, headings and labels, British spelling, Healdan's glossary terms.
  - Quote a front-matter value containing ` #`, and end a heading that ends in `#` with `\#`. YAML and Markdown drop them otherwise.
- A page not written yet holds `{{< interim href="…" title="…" >}}`, which links to LogicalDOC's nearest page. It's the only place `site/content/` may link to LogicalDOC's docs.
- The `Docs site` check (`.github/workflows/docs-site.yml`) runs `python3 -m unittest discover -s .github/scripts/tests`, a strict build (`--panicOnWarning`), `check-site-sources.py` and `check-site-links.py`. It publishes to GitHub Pages from `master` only while the repository variable `DOCS_PUBLISH` is `true`.
- Build, test and CI facts are in this file and in the Developer Guide. A change updates both.

## Architecture

**Modules.** `logicaldoc-util` sits at the bottom. `logicaldoc-core` builds on it, and the feature modules build on core: `webservice` (SOAP via CXF plus REST), `webdav`, `cmis`, `dropbox`, `android` and `ios`. `logicaldoc-webapp` is the WAR that bundles everything. `logicaldoc-gui` (GWT client) depends only on util. `logicaldoc-i18n` holds the resource bundles.

**Plugin system (JPF).** Almost every module is a plugin, and adding a plugin takes four pieces:
- `src/main/resources/plugin.xml` declares the plugin's ID, its dependencies and the extensions it contributes.
- A class extending `com.logicaldoc.util.plugin.LogicalDOCPlugin` registers servlets at install time with `addServlet(...)`. That edits `web.xml`, so a restart is required. It can also register scheduled tasks and loggers.
- `src/main/assembly/assemblyplugin.xml` packages a `*-plugin.zip` (classifier `plugin`).
- An `artifactItem` in `logicaldoc-webapp/pom.xml` (maven-dependency-plugin) copies that zip in, and an antrun step unzips it into the WAR. Without this entry, the plugin isn't shipped.

Core's `plugin.xml` defines the extension points other plugins hook into: `DbInit`, `Parser`, `Language`, `ResourceBundle`, `Task`, `DocumentListener`, `FolderListener`, `UserListener`, `TicketListener`, `Authentication`, `ThumbnailBuilder`, `FormatConverter`, `Search`, `Feature`, `SystemInfo`, `TokenFilter`, `Store`, `Aspect` and `TokenProvider`. `logicaldoc-dropbox` is the smallest complete example of a plugin.

**Spring wiring.** `web.xml` boots `com.logicaldoc.util.spring.MainContext`. It component-scans `com.logicaldoc` for `@PluginContext` classes and imports the webapp's `context.xml`, which defines the datasource, session factory and transactions.
- Each module has a `XxxContext` class annotated `@PluginContext` + `@ComponentScan` + `@ImportResource("classpath:context/context-xxx.xml")`.
- Runtime settings come from `context.properties`, read through `ContextProperties`, which `Context.get().getConfig()` returns.
- Beans are fetched with `Context.get(Type.class)`, and DAOs expose a static `XxxDAO.get()`.
- **Both Spring and Hibernate only scan `com.logicaldoc`.** Code in any other package root isn't discovered unless you extend the scans.

**Persistence.**
- Hibernate 7 uses JPA-annotated `@Entity` classes, which `com.logicaldoc.util.spring.LocalSessionFactory` finds by classpath scan. The `*.hbm.xml` mapping location in `context.xml` is configured, but no such files exist.
- DAOs follow one pattern: an interface `XxxDAO extends PersistentObjectDAO<T>` and an implementation `HibernateXxxDAO extends HibernatePersistentObjectDAO<T>`.
- Every `PersistentObject` has a `tenantId` (multi-tenant).
- CE has no migration tool (Healdan's own tables use Flyway, below). CE's schema is plain SQL in `sql/logicaldoc-<module>.sql`, with DBMS-specific variants (`.mysql`, `.mariadb`, `.oracle`, `.mssql`) that `PluginDbInit` picks when one exists. Plugins add tables through a `DbInit` extension with a `sqlFile` parameter.
- The second-level cache is Infinispan (`infinispan.xml`, or `infinispan-cluster.xml` when clustering is on).

**UI ↔ server.** The UI uses GWT 2.12 and SmartGWT 14.1, with GWT modules `frontend`, `login`, `setup` and `common` under `com.logicaldoc.gui`.
- RPC calls go from interfaces in `gui/**/client/services` (`@RemoteServiceRelativePath`) to `logicaldoc-webapp/.../web/service/*ServiceImpl`, mapped in `logicaldoc-webapp/src/main/webapp/WEB-INF/web.xml` under `/frontend/*`.
- Grid and list data come from `web/data/*DataServlet`, which serves XML for SmartGWT DataSources at `/data/*.xml`.
- Other ways into the server are SOAP and REST at `/services/*` (`logicaldoc-webservice`, REST endpoints in `webservice/rest/endpoint`), WebDAV at `/webdav/*`, and CMIS.

**Edition feature gating.**
- The GUI shows or hides features with `Feature.enabled(n)`, which checks for the name `Feature_<n>`.
- Those names come from `Feature` extensions declared in plugins' `plugin.xml` (collected by `SystemInfo`).
- The GUI already contains client code for enterprise-only features whose server implementations aren't in this repo, for example the `ai`, `google`, `onlyoffice`, `saml` and `whatsapp` service interfaces.
- Replacing a paid extra therefore means three things: implement that RPC interface on the server, register its servlet, and declare the matching `Feature_<n>` extension.

## Healdan's code

- **`healdan-core`** is Healdan's plugin module (JPF id `healdan-core`, which requires `logicaldoc-core`). It holds Healdan's server rules and all its data, under `com.logicaldoc.healdan`, so CE's Spring and Hibernate scans find it with no edits.
  - Its GUI code is in `logicaldoc-gui` under `com.logicaldoc.gui.frontend.client.healdan`, reached from one-line hooks in upstream GUI classes.
  - Its servlets register with `@WebServlet`, never `addServlet`, because the Docker image rebuilds `web.xml`. RPC paths are `/frontend/healdan*` and data paths `/data/healdan*`.
  - Its listeners enforce the rules on every route. Extension ids in its `plugin.xml` must be unique: JPF silently drops the whole plugin on a duplicate.
- **`healdan-api`** is Healdan's JSON API, the HTTP layer at `/api/*` (JPF id `healdan-api`, which requires `healdan-core`; M2's spec is `docs/superpowers/specs/2026-10-23-m2-api-foundation-design.md`). Its code is under `com.logicaldoc.healdan.api`, and `HealdanContext`'s scan finds its beans, so it has no `@PluginContext` of its own. Domain code (tokens, read models) stays in `healdan-core`. The docs site's Developer Guide has a page for API clients (`site/content/developer/api.md`).
  - `ApiServlet` (`@WebServlet("/api/*")`) runs a CXF JAX-RS server on its own `ExtensionManagerBus`, not CXF's Spring bus, which would expose every `/services/rest` server under `/api/rest` too. Its resources are the Spring beans in the package that carry `@Path`.
  - `/api/**` is `security="none"` in `context-security.xml`: the API's `TokenFilter` signs requests in from `Authorization: Bearer hd_…` device tokens only, never a cookie, `sid`, Basic or `X-API-KEY`.
  - Device tokens work only at `/api`. `HealdanTokenAuthenticator` accepts one only inside `ApiTokens.openSession`, which hands CE's chain a one-time key, and refuses every other `hd_` key, so CE's routes (`loginApiKey`, `X-API-KEY`, Basic) never take a token. Letting a token in elsewhere, such as WebDAV, needs a narrow allowance there.
  - `healdan-api/src/main/resources/healdan/api/openapi.yaml` (OpenAPI 3.1, served at `/api/v1/openapi.yaml`, browsable at `/api/docs/`) is hand-written and is the contract: `ApiTestCase` checks every response a test gets against it, and `RouteCoverageTest` fails when the routes and the file differ. A new endpoint goes into it in the same change. A list schema built with `allOf` needs `unevaluatedProperties: false` (see `Page`).
  - Errors are RFC 9457 problems with stable `code`s (`ApiProblem`); unreadable means `404`, as in the old UI.
- **Flyway owns the `hd_` tables, and only those.**
  - Scripts are `healdan-core/src/main/resources/healdan/db/migration/{mariadb,hsqldb}/V<n>__<name>.sql`, and the history table is `hd_schema_history`.
  - A change ships as a new script; never edit one that has shipped.
  - Healdan never changes the structure of CE's `ld_` tables, and declares no `DbInit`. Only MariaDB and HSQLDB are supported.
  - A failed migration stops startup.
- **Seeds in `hd_setting`.** After the migrations, idempotent Java seed steps (`com.logicaldoc.healdan.seed`, in `SeedRunner.ORDER`) create what Healdan needs in CE: the Everyone group, the Healdan profile template, the Templates Store, default columns and settings.
  - Each step writes a `seed.<name>` marker to `hd_setting` and never runs again. Healdan's settings live in `hd_setting` too.
  - A shipped step never changes: new defaults or fixes ship as a new step name.
  - A failed seed stops startup. To re-run a step on a test stack, delete its marker row and restart.
- **Healdan's clients** live under `clients/`: the Healdan Helper (`clients/helper/`, .NET 10) and the LibreOffice extension (`clients/libreoffice/`, Python with UNO and the standard library only). The Developer Guide's Desktop Clients page (`site/content/developer/desktop-clients.md`) has how they work: the cycle, the local copies, the channel and its routes. M3's spec is `docs/superpowers/specs/2026-10-24-m3-office-integration-design.md`.
  - Put anything testable in `Healdan.Helper.Core`, which has no windows and which `Healdan.Helper.Core.Tests` drives, and the extension's logic in `healdan_logic.py`, which the tests run without LibreOffice.
  - On macOS the helper never runs `unopkg`, which can't install the extension there: it opens its `.oxt` in LibreOffice for the user to accept.
  - The helper's macOS icon is the docs site's `site/static/favicon.svg` on macOS's rounded square, which `clients/helper/packaging/mac/make-icon.swift` draws each time `package.sh` runs. Changing the favicon changes it too, and fails `MarkTests` until About's mark (`Windows/HealdanMark.axaml`) is redrawn.
  - The menu bar icon is the same mark, drawn in one colour at run time from `HealdanMark`'s shapes (`Tray/TrayIcons.cs`), so it follows the favicon with About's mark.
  - The helper's icons are Tabler's, in `clients/icons/tabler/`. `python3 clients/icons/write-helper-icons.py` writes `HelperIcons.axaml` from them, and `IconsTests` fails until it's run after an icon is added or changed. A new icon also goes in `IconsTests`' `SpecNames`, the spec's list.
  - The extension's toolbar and menu images are in `clients/libreoffice/healdan/icons/`, rendered from the helper's icons and mark by `clients/helper/tools/IconRender` (outside the solution, so CI doesn't build it; run it by hand on a Mac after an icon changes, and commit the output). The package's own `THIRD-PARTY-NOTICES.txt` carries Tabler's licence. Bump `description.xml`'s version when the extension's content changes: the helper installs only a newer one over an installed copy.
  - `clients/helper/Healdan.Helper/THIRD-PARTY-NOTICES.txt` is About's Open-Source Notices. `NoticesTests` fails when its `Packages:` lines and the packages the helper restores differ, ids and versions both, so a package added or moved needs its notice.
- **`HealdanHooks`** (`logicaldoc-core/.../core/HealdanHooks.java`, fork-owned) lets CE code that bypasses the listeners call Healdan before it writes. Examples are the JDBC restores, `deleteTree` and `applySecurityToTree`, and the moves, renames, updates, merges and copies that write before a listener could refuse. `healdan-core` registers the implementation.
  - Every Healdan veto throws `HealdanVetoException` or a subclass. The document and folder DAOs roll back on it alone; a plain `PersistenceException` still commits.
- **Upstream edits.** Each milestone's spec lists every upstream file it edits, with the reason: §13 of `docs/superpowers/specs/2026-10-17-m1-move-in-design.md` for M1, §9 of the M1.5 spec, §13 of `docs/superpowers/specs/2026-10-23-m2-api-foundation-design.md` for M2, and §13.1 of `docs/superpowers/specs/2026-10-24-m3-office-integration-design.md` for M3 (GUI hooks for Open in App, and its label in three English bundles; no server-side edits). Keep each edit a small hook into new code, and list it in the milestone's spec.
- **Credits.** The login page and About credit LogicalDOC as well as Healdan (`logicaldoc-gui/.../common/client/healdan/HealdanCredits.java`). Keep LogicalDOC's credit through any rebrand, and never call Healdan a registered trademark: it isn't one.

## Tests

- Tests use JUnit 4. The base class `com.logicaldoc.util.junit.AbstractTestCase` builds a Spring context from the module's test `context.xml` and `context.properties`.
- That context includes an in-memory HSQLDB loaded from `getDatabaseScripts()`. For core, that's `sql/logicaldoc-core.sql` plus the test `data.sql` fixture data.
- Each module has its own base class, such as `AbstractCoreTestCase`, `AbstractWebappTestCase` or `AbstractWebserviceTestCase`. Extend the closest one. `healdan-core`'s `AbstractHealdanTestCase` also runs Healdan's migrations and seeds on the test database.
- `healdan-api`'s `ApiTestCase` extends `AbstractHealdanTestCase` (from `healdan-core`'s test-jar) and drives the real `ApiServlet`, filters included, through Spring's servlet mocks. Its `context.properties` is copied at build time from `../healdan-core/src/test/resources`.
- The sign-in throttle is on in every `healdan-core` and `healdan-api` test (the `throttle-signin` seed runs on each fresh test database), so a test that fails one user's password five times blocks that user for the rest of the test.
- Some tests load plugin manifests from fixture jars in `src/test/resources` through `getPluginArchives()`. Those jars are hand-maintained fixtures, not built from source.
- Tests write their stores, indexes and temp files under `target/`.
