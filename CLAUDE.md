# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

This repo, Healdan (`Healdan-Project/healdan` on GitHub), is a hard fork of LogicalDOC Community Edition (version 9.3.1, LGPLv3), a Java document management system. It's detached from GitHub's fork network. The plan is to take it in its own direction (a new UI, bespoke features, replacements for features LogicalDOC sells as paid extras) while still merging upstream core and security fixes.

- Upstream is `https://github.com/logicaldoc/community.git`. A fresh clone needs `git remote add upstream <that url>`.
- Sync upstream by merging `upstream/master` into a dedicated branch and opening a PR to `master`. Merge, never squash or rebase, so the shared history stays intact. Keep sync PRs free of feature work.
- Upstream changes `logicaldoc-core` and `logicaldoc-gui` the most. To keep syncs cheap, put new work in new plugin modules and avoid editing upstream files where possible.
- Upstream sometimes ships POMs that fail Maven validation (for example, a duplicate dependency). If CI fails within a minute, check POM errors first.
- `README.md` and `Build_this_Sources.md` are upstream's and out of date. They mention `build/poms` and JDK 11, which don't apply here.

## Build and test

You need JDK 21 (`source.level` in `logicaldoc-parent/pom.xml`; the GWT client compiles at source level 17) and Maven 3.9+. Some dependencies aren't on Maven Central. They come from `https://logicaldoc.sourceforge.net/maven/` and `https://repo.terracotta.org/maven2`, which `.github/workflows/build-logicaldoc.yml` writes into `~/.m2/settings.xml`. Copy that block for local builds.

```bash
mvn -B -ntp -DskipTests install                          # full reactor build (what CI runs)
mvn -B -ntp -pl logicaldoc-webapp -am -DskipTests package # deployable WAR -> logicaldoc-webapp/target/*.war
mvn -pl logicaldoc-core test                              # one module's tests (sibling modules must be installed first)
mvn -pl logicaldoc-core test -Dtest=DocumentManagerTest   # one test class
mvn -pl logicaldoc-core test -Dtest='DocumentManagerTest#testCopyToFolder' # one method
```

- `logicaldoc-parent` is a reactor module, and the root POM inherits from it, so dependency and plugin versions live there.
- `logicaldoc-gui` runs a GWT compile for each GWT module (frontend, login, setup), which is slow. Leave it out with `-pl` when you aren't touching the UI.
- CI uses `-DskipTests`, so a green CI run proves compilation and packaging only, not test results.
- `logicaldoc-webservicesamples` isn't in the reactor.

## Docker

- `Dockerfile` (stages `build` → `tools` → `runtime`) and `compose.yaml` are this fork's own. `docker/README.md` is the user guide; the design is `docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md`.
- The container never runs the setup wizard. `docker/tools/src/DockerInit.java` seeds `context.properties` and, on a fresh database, creates the schema by running the `DbInit` scripts from every `plugin.xml`, so a plugin's `DbInit` extension is picked up for new installs. Existing databases are never migrated: a plugin or upstream change that adds SQL needs it applied by hand there.
- On every start, keys in the image's default `context.properties` that the conf volume lacks are added (values are never overwritten).
- LogicalDOC only runs external programs listed in a classpath `allowed-commands.txt`. The lines in `docker/allowed-commands.txt` are re-added to the conf volume's copy on every start; a new external tool needs a line there.
- Tests:
  - `docker build --target tools .` for the init tool's unit tests
  - `docker/tests/test-*.sh`
  - `docker/smoke-test.sh` for end to end
- CI: `.github/workflows/docker-image.yml` builds on native amd64 and arm64 runners and runs the `tools` target and `docker/smoke-test.sh` on each. It publishes to `ghcr.io/healdan-project/healdan` from `master` and `v*` tags only while the repository variable `GHCR_PUBLISH` is `true`.

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
- There's no migration tool. The schema is plain SQL in `sql/logicaldoc-<module>.sql`, with DBMS-specific variants (`.mysql`, `.mariadb`, `.oracle`, `.mssql`) that `PluginDbInit` picks when one exists. Plugins add tables through a `DbInit` extension with a `sqlFile` parameter.
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

## Tests

- Tests use JUnit 4. The base class `com.logicaldoc.util.junit.AbstractTestCase` builds a Spring context from the module's test `context.xml` and `context.properties`.
- That context includes an in-memory HSQLDB loaded from `getDatabaseScripts()`. For core, that's `sql/logicaldoc-core.sql` plus the test `data.sql` fixture data.
- Each module has its own base class, such as `AbstractCoreTestCase`, `AbstractWebappTestCase` or `AbstractWebserviceTestCase`. Extend the closest one.
- Some tests load plugin manifests from fixture jars in `src/test/resources` through `getPluginArchives()`. Those jars are hand-maintained fixtures, not built from source.
- Tests write their stores, indexes and temp files under `target/`.
