# Docker Image and Compose Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build this fork into a Docker image and run it with a MariaDB compose stack. `cp .env.example .env`, set `DB_PASSWORD`, run `docker compose up -d`, and you can log in, with no setup wizard.

**Architecture:**
- A multi-stage `Dockerfile`.
  - **`build`:** Maven builds the WAR.
  - **`tools`:** compiles and tests a small Java init tool against the WAR's libraries.
  - **`runtime`:** Tomcat 11 / JRE 21 with the exploded WAR at `/`. `context.properties` and `log.xml` sit in a persistent `conf/` directory on Tomcat's `common.loader`.
- **`entrypoint.sh`:** fixes volume ownership, then runs the init tool (`DockerInit`) as uid 1000, then `exec`s Tomcat.
  - DockerInit seeds and updates `context.properties` with LogicalDOC's own `ContextProperties`.
  - It waits for MariaDB, and on first boot runs the same `DbInit` scripts the setup wizard runs, using upstream's `DBInit`.
- **Compose:** `compose.yaml` is production. `compose.dev.yaml` and `compose.proxy.yaml` are overlays.

**Tech Stack:**
- Docker BuildKit (`# syntax=docker/dockerfile:1`, `COPY --exclude`, cache mounts) and Docker Compose v2 (`!reset` / `!override`)
- Maven 3.9 / JDK 21, Tomcat 11.0 / JRE 21, MariaDB 11.4
- bash, plain Java 21 (no test framework for the tool), curl, jq, python3 (test fixtures only)

**Spec:** `docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md` (issue coffeemuse/logicaldoc-community#4)

## Global Constraints

**Images**
- Base images exactly: `maven:3.9-eclipse-temurin-21` (build), `tomcat:11.0-jre21-temurin` (runtime), `mariadb:11.4` (db).

**Users and paths**
- App user `logicaldoc`, uid/gid **1000**. The Tomcat JVM always runs as 1000, never as root.
- `LDOCHOME=/opt/logicaldoc`. The volumes are `/opt/logicaldoc/conf` and `/opt/logicaldoc/repository`. The repository subdirs are `docs/ index/ users/ plugins/ impex/in/ impex/out/ logs/ db/`.
- The app is served at `/` on container port **8080**.

**Environment variables**
- App env var names exactly: `DB_HOST` (default `db`), `DB_PORT` (`3306`), `DB_NAME` (`logicaldoc`), `DB_USER` (`logicaldoc`), `DB_PASSWORD` (required), `LOGICALDOC_PUBLIC_URL` (unset), `CATALINA_OPTS` (`-Xmx2g` via compose), `TZ` (`UTC`), `DB_WAIT_SECONDS` (`60`).
- Compose-only names: `LOGICALDOC_BIND` (`127.0.0.1`), `LOGICALDOC_PORT` (`8080`), `PROXY_NETWORK` (`npm_default`), `JDWP_BIND` (`127.0.0.1`), `DEV_DB_PORT` (`3306`), `COMPOSE_FILE`.

**Config keys**
- Env-owned keys are re-applied on every boot: `jdbc.url`, `jdbc.username`, `jdbc.password`, and `server.url` (only when `LOGICALDOC_PUBLIC_URL` is set).
- Seed-once keys are applied only when `LDOCHOME` is absent from `conf/context.properties`.

**Compose**
- Compose project name `logicaldoc`. Volumes `db-data`, `logicaldoc-conf`, `logicaldoc-repository`. Network alias for the proxy overlay: `logicaldoc`.

**Repo rules**
- Upstream files: the only one edited is `logicaldoc-webservice/src/main/resources/plugin.xml` (one line, its own commit). Everything else is new files, plus a short Docker section in `CLAUDE.md` (fork-owned).
- Host-side scripts (`docker/smoke-test.sh`, `docker/tests/*.sh`) must run under **macOS bash 3.2**. That means no `mapfile`, no associative arrays, and no expanding possibly-empty arrays under `set -u`. `entrypoint.sh` runs inside the container (bash 5).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

These are the most likely ways the stack bites a real user that the spec implies but no single happy-path test exercises. Each line names the input, the expected behavior, and the task whose tests pin it.

1. **`DB_PASSWORD` with `$ # \ , = : !`, spaces (including leading/trailing) or non-ASCII.** It must work end to end when single-quoted in `.env`. `${…}` or a `_b64_` prefix must be refused with a clear message, because LogicalDOC expands or decodes those on read.
   - Pinned by `ConfigPlanTest` (Task 4), `ConfigFileTest` and `test-init.sh` (Task 5), and `smoke-test.sh` (Task 7).
2. **Volumes arriving owned by another uid** (NAS bind mount, restored backup). Ownership is repaired at start and the app becomes healthy. Pinned by `test-image.sh` (Task 6).
3. **DB starting after the app** (host reboot), or wrong DB credentials. The app waits up to `DB_WAIT_SECONDS`. Bad credentials fail fast with "database authentication failed", and an unreachable host fails with "not reachable". Pinned by `test-init.sh` (Task 5).
4. **`conf` volume lost while the DB is kept.** There's a loud warning, no re-initialization, and the app still starts. Pinned by `test-init.sh` (Task 5) and `test-image.sh` (Task 6).
5. **Settings changed in the admin UI** (seed-once keys). They survive restarts, container recreation and image rebuilds. A first boot that crashes before seeding still gets seeded on the next boot. Pinned by `ConfigFileTest` and `test-init.sh` (Task 5), and `test-image.sh` (Task 6).

---

## File map

| File | Responsibility |
|---|---|
| `logicaldoc-webservice/src/main/resources/plugin.xml` | fix the DbInit `sqlFile` name |
| `.dockerignore` | keep the build context small and stop non-source edits from invalidating the Maven layer |
| `Dockerfile` | `build`, `tools` and `runtime` stages |
| `docker/maven-settings.xml` | extra Maven repositories (same as CI) |
| `docker/tools/src/DbInitPlan.java` | pure: parse plugin.xml DbInit extensions, order them, pick the DBMS variant, list created tables |
| `docker/tools/src/ConfigPlan.java` | pure: seed-once and env-owned keys, env validation |
| `docker/tools/src/DockerInit.java` | main: apply config with `ContextProperties`, wait for the DB, create and verify the schema |
| `docker/tools/test/Check.java` | tiny assertion helper |
| `docker/tools/test/DbInitPlanTest.java` | unit tests for `DbInitPlan` |
| `docker/tools/test/ConfigPlanTest.java` | unit tests for `ConfigPlan` |
| `docker/tools/test/ConfigFileTest.java` | round-trip tests through LogicalDOC's `ContextProperties` |
| `docker/tools/build.sh` | compile, test and package the tool (runs in the `tools` stage) |
| `docker/entrypoint.sh` | ownership, defaults, DockerInit, exec Tomcat |
| `docker/restart.sh` | Admin → Restart target |
| `docker/setenv.sh` | Tomcat JVM options |
| `docker/server.xml` | Tomcat: connector, RemoteIpValve, RewriteValve |
| `docker/rewrite.config` | blocks `/setup` |
| `compose.yaml` / `compose.dev.yaml` / `compose.proxy.yaml` / `.env.example` | the stack |
| `docker/tests/lib.sh` | shared bash helpers for the test scripts |
| `docker/tests/test-init.sh` | DockerInit against a throwaway MariaDB |
| `docker/tests/test-image.sh` | image behaviors with plain `docker run` |
| `docker/tests/test-overlays.sh` | dev and proxy overlays |
| `docker/smoke-test.sh` | end-to-end acceptance test of the compose stack |
| `docker/README.md` | user documentation |
| `CLAUDE.md` | pointer to the Docker docs and tests |

---

### Task 1: Fix the webservice DbInit SQL filename

**Files:**
- Modify: `logicaldoc-webservice/src/main/resources/plugin.xml:104`

**Interfaces:**
- Produces: the webservice plugin declares `sql/logicaldoc-webservice.sql`. Task 5's DockerInit fails loudly on any declared script that's missing, so this fix is a precondition for schema creation.

- [ ] **Step 1: Show the defect**

Run:
```bash
grep -n 'sqlFile' logicaldoc-webservice/src/main/resources/plugin.xml
ls logicaldoc-webservice/src/main/resources/sql/
```
Expected:
- `plugin.xml` contains `value="sql/logicaldoc-webservices.sql"`.
- The directory lists only `logicaldoc-webservice.sql*` (no "s").

- [ ] **Step 2: Fix the value**

In `logicaldoc-webservice/src/main/resources/plugin.xml`, change
```xml
	<parameter id="sqlFile" value="sql/logicaldoc-webservices.sql" />
```
to
```xml
	<parameter id="sqlFile" value="sql/logicaldoc-webservice.sql" />
```
(Keep the existing indentation; only the filename changes.)

- [ ] **Step 3: Verify**

Run:
```bash
f=$(grep -o 'sql/logicaldoc-webservice[a-z]*\.sql' logicaldoc-webservice/src/main/resources/plugin.xml)
ls "logicaldoc-webservice/src/main/resources/$f"
```
Expected: the file is listed. There's no "No such file" error.

- [ ] **Step 4: Commit**

```bash
git add logicaldoc-webservice/src/main/resources/plugin.xml
git commit -F - <<'EOF'
Fix webservice DbInit script name in plugin.xml

The DbInit extension pointed at sql/logicaldoc-webservices.sql, but the
scripts are named logicaldoc-webservice.sql[.<dbms>]. The setup wizard
silently ran an empty script, so ld_webservicecall was never created.
Worth offering upstream.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Maven build stage

**Files:**
- Create: `.dockerignore`, `docker/maven-settings.xml`, `Dockerfile` (build stage only)

**Interfaces:**
- Produces:
  - Docker stage `build`, containing the exploded WAR at `/build/ROOT`. That includes `/build/ROOT/WEB-INF/lib/*.jar`, `/build/ROOT/WEB-INF/classes/context.properties` and `/build/ROOT/WEB-INF/classes/log.xml`.
  - The Maven layer is not invalidated by edits under `docker/`.

- [ ] **Step 1: Create `.dockerignore`**

```
# Keep the build context to sources. docker/ is needed, but the build stage copies it separately.
.git
.github
.claude
docs
**/target
**/gwt-unitCache
**/.settings
**/.project
**/.classpath
**/*.bak
.env
.env.*
compose*.yaml
```

- [ ] **Step 2: Create `docker/maven-settings.xml`**

This is the same repository block as `.github/workflows/build-logicaldoc.yml`:
```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
  <!-- Same repositories as .github/workflows/build-logicaldoc.yml; some dependencies are not on Maven Central. -->
  <profiles>
    <profile>
      <id>with-extra-repos</id>
      <repositories>
        <repository>
          <id>central</id>
          <url>https://repo1.maven.org/maven2</url>
        </repository>
        <!-- LogicalDOC repository (hosts the *-jakarta Jackrabbit artifacts, etc.) -->
        <repository>
          <id>logicaldoc</id>
          <url>https://logicaldoc.sourceforge.net/maven/</url>
        </repository>
        <!-- Terracotta repository (org.terracotta.internal:statistics, etc.) -->
        <repository>
          <id>terracotta</id>
          <url>https://repo.terracotta.org/maven2</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>with-extra-repos</activeProfile>
  </activeProfiles>
</settings>
```

- [ ] **Step 3: Create `Dockerfile` with the build stage**

```dockerfile
# syntax=docker/dockerfile:1

# LogicalDOC Community (coffeemuse fork): build from source, run on Tomcat 11.
# See docker/README.md for usage and docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md for the design.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG TOMCAT_IMAGE=tomcat:11.0-jre21-temurin

# ---- build: the same Maven build CI runs, producing the exploded WAR in /build/ROOT ----
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
COPY docker/maven-settings.xml /root/.m2/settings.xml
# docker/ is excluded so editing container files does not re-run the Maven build.
COPY --exclude=docker . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -ntp -DskipTests install
RUN mkdir -p /build/ROOT \
 && cd /build/ROOT \
 && jar xf /src/logicaldoc-webapp/target/logicaldoc-webapp-*.war
```

- [ ] **Step 4: Build the stage (the first run takes 15–30 min)**

Run in the background and capture the log:
```bash
docker build --target build --progress=plain -t logicaldoc-build:test . > /tmp/ld-build.log 2>&1; echo "exit=$?"
```
Expected: `exit=0`, and the log contains `BUILD SUCCESS`.
- If the log shows a Maven POM validation error within the first minute, see `CLAUDE.md` ("upstream sometimes ships POMs that fail Maven validation").
- If the GWT compile is killed (exit 137), raise Docker Desktop's memory limit to at least 8 GB.

- [ ] **Step 5: Verify the layout the later tasks rely on**

Run:
```bash
docker run --rm logicaldoc-build:test bash -c '
  cd /build/ROOT
  ls WEB-INF/classes/context.properties WEB-INF/classes/log.xml login.jsp setup.jsp
  ls WEB-INF/lib | grep -E "^(mariadb-java-client|hsqldb|sqltool|logicaldoc-util|logicaldoc-core|logicaldoc-webservice|logicaldoc-dropbox)"
  for j in WEB-INF/lib/*.jar; do
    entries=$(jar tf "$j")
    grep -qxE "context\.properties|log\.xml" <<<"$entries" && echo "SHADOW: $j"
    grep -qx "plugin.xml" <<<"$entries" && echo "plugin.xml in $(basename "$j")"
  done
  jar tf WEB-INF/lib/logicaldoc-core-*plugin*.jar | grep "sql/logicaldoc-core.sql"
  jar tf WEB-INF/lib/logicaldoc-webservice-*plugin*.jar | grep "sql/logicaldoc-webservice.sql"
'
```
Expected:
- The four files are listed. Jars exist for the MariaDB driver, hsqldb, sqltool, util, core, webservice and dropbox.
- There are **no** `SHADOW:` lines. A jar with a root-level `context.properties`/`log.xml` would hide the conf-dir copy from Tomcat's class loader. If any appear, stop and report it.
- `plugin.xml` appears in the core, webservice, dropbox and other plugin jars.
- The core jar lists `sql/logicaldoc-core.sql` plus `.mariadb`. The webservice jar lists `sql/logicaldoc-webservice.sql` plus `.mariadb`.

- [ ] **Step 6: Commit**

```bash
git add .dockerignore docker/maven-settings.xml Dockerfile
git commit -F - <<'EOF'
Add Maven build stage for the Docker image

Builds the full reactor like CI does, with a BuildKit cache for the
local Maven repository, and unpacks the WAR to /build/ROOT. docker/ is
excluded from that layer so container-only edits stay cheap.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: DbInitPlan (which SQL scripts to run)

**Files:**
- Create: `docker/tools/test/Check.java`, `docker/tools/test/DbInitPlanTest.java`, `docker/tools/src/DbInitPlan.java`, `docker/tools/build.sh`
- Modify: `Dockerfile` (append the `tools` stage)

**Interfaces:**
- Produces (all in the default package):
  - `record DbInitPlan.Entry(String pluginId, int position, String sqlFile)`
  - `static List<Entry> DbInitPlan.parse(InputStream pluginXml) throws Exception`. It returns `[]` for non-JPF files and throws `IllegalArgumentException` naming the plugin when a DbInit extension lacks `position` or `sqlFile`.
  - `static List<Entry> DbInitPlan.order(Collection<Entry>)`, ascending by position and stable.
  - `static String DbInitPlan.resolve(Entry, String dbms, Predicate<String> exists)`. It returns `<sqlFile>.<dbms lower-case>` if that exists, else `<sqlFile>`. Otherwise it throws `IllegalStateException` naming the plugin and the file.
  - `static List<String> DbInitPlan.createdTables(String sql)`: names as written, de-duplicated, in order, ignoring `--` comments, with `if not exists` handled.
  - `Check.eq / isTrue / throwsWith / done`
  - `docker/tools/build.sh <lib dir> <defaults context.properties> <out jar>`
  - Docker stage `tools`, which produces `/build/docker-init.jar`.

- [ ] **Step 1: Write `docker/tools/test/Check.java`**

```java
import java.util.Objects;

/** Minimal assertions for the container tool's tests, which run without JUnit or Maven. */
final class Check {

    private static int failures;

    private Check() {
    }

    static void eq(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual))
            fail(what + ": expected <" + expected + "> but was <" + actual + ">");
    }

    static void isTrue(boolean condition, String what) {
        if (!condition)
            fail(what);
    }

    static void throwsWith(String fragment, Runnable body, String what) {
        try {
            body.run();
            fail(what + ": expected an exception mentioning \"" + fragment + "\"");
        } catch (RuntimeException e) {
            if (e.getMessage() == null || !e.getMessage().contains(fragment))
                fail(what + ": exception message <" + e.getMessage() + "> lacks \"" + fragment + "\"");
        }
    }

    static void fail(String message) {
        failures++;
        System.err.println("FAIL " + message);
    }

    /** Ends a test class: prints a summary and exits non-zero if anything failed. */
    static void done(String suite) {
        if (failures > 0) {
            System.err.println(suite + ": " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println(suite + ": OK");
    }
}
```

- [ ] **Step 2: Write `docker/tools/test/DbInitPlanTest.java`**

```java
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

public class DbInitPlanTest {

    // Same shape as logicaldoc-core's plugin.xml: internal DTD subset, DbInit plus unrelated extensions.
    private static final String CORE = """
            <?xml version="1.0" ?>
            <!DOCTYPE plugin [
            <!ELEMENT plugin ANY>
            ]>
            <plugin id="logicaldoc-core" version="9.3.1">
              <extension plugin-id="logicaldoc-core" point-id="DbInit" id="coreDbInit">
                <parameter id="position" value="1" />
                <parameter id="sqlFile" value="sql/logicaldoc-core.sql" />
              </extension>
              <extension plugin-id="logicaldoc-core" point-id="Feature" id="f53">
                <parameter id="name" value="Feature_53" />
              </extension>
            </plugin>
            """;

    public static void main(String[] args) throws Exception {
        parsesCoreDbInitExtension();
        ignoresForeignManifests();
        rejectsDbInitWithoutSqlFile();
        ordersByPosition();
        prefersDbmsVariant();
        fallsBackToGenericScript();
        failsWhenScriptMissing();
        extractsCreatedTables();
        Check.done("DbInitPlanTest");
    }

    private static List<DbInitPlan.Entry> parse(String xml) {
        try {
            return DbInitPlan.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void parsesCoreDbInitExtension() {
        Check.eq(List.of(new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql")), parse(CORE),
                "core DbInit entry, Feature extension ignored");
    }

    private static void ignoresForeignManifests() {
        String eclipseStyle = """
                <?xml version="1.0"?>
                <plugin><extension point="org.eclipse.ui.views"/></plugin>
                """;
        Check.eq(List.of(), parse(eclipseStyle), "Eclipse-style plugin.xml yields nothing");
        Check.eq(List.of(), parse("<?xml version=\"1.0\"?><project/>"), "non-plugin root yields nothing");
    }

    private static void rejectsDbInitWithoutSqlFile() {
        String broken = """
                <?xml version="1.0"?>
                <plugin id="logicaldoc-broken">
                  <extension plugin-id="logicaldoc-core" point-id="DbInit" id="x">
                    <parameter id="position" value="5" />
                  </extension>
                </plugin>
                """;
        Check.throwsWith("logicaldoc-broken", () -> parse(broken), "DbInit without sqlFile names the plugin");
    }

    private static void ordersByPosition() {
        var core = new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql");
        var ws = new DbInitPlan.Entry("logicaldoc-webservice", 20, "sql/logicaldoc-webservice.sql");
        var dropbox = new DbInitPlan.Entry("logicaldoc-dropbox", 1500, "sql/logicaldoc-dropbox.sql");
        Check.eq(List.of(core, ws, dropbox), DbInitPlan.order(List.of(dropbox, core, ws)), "ascending position");
    }

    private static void prefersDbmsVariant() {
        var core = new DbInitPlan.Entry("logicaldoc-core", 1, "sql/logicaldoc-core.sql");
        Set<String> present = Set.of("sql/logicaldoc-core.sql", "sql/logicaldoc-core.sql.mariadb");
        Check.eq("sql/logicaldoc-core.sql.mariadb", DbInitPlan.resolve(core, "MariaDB", present::contains),
                "variant preferred, dbms lower-cased");
    }

    private static void fallsBackToGenericScript() {
        var dropbox = new DbInitPlan.Entry("logicaldoc-dropbox", 1500, "sql/logicaldoc-dropbox.sql");
        Check.eq("sql/logicaldoc-dropbox.sql",
                DbInitPlan.resolve(dropbox, "mariadb", Set.of("sql/logicaldoc-dropbox.sql")::contains),
                "generic script when there is no variant");
    }

    private static void failsWhenScriptMissing() {
        var ws = new DbInitPlan.Entry("logicaldoc-webservice", 20, "sql/logicaldoc-webservices.sql");
        Check.throwsWith("logicaldoc-webservice", () -> DbInitPlan.resolve(ws, "mariadb", Set.<String>of()::contains),
                "missing script names the plugin");
        Check.throwsWith("sql/logicaldoc-webservices.sql",
                () -> DbInitPlan.resolve(ws, "mariadb", Set.<String>of()::contains), "missing script names the file");
    }

    private static void extractsCreatedTables() {
        String sql = """
                -- create table ld_commented_out (x int);
                create table ld_bookmark (ld_id bigint not null);
                CREATE  TABLE ld_webservicecall (ld_id bigint);
                create table if not exists ld_extra (ld_id bigint);
                insert into ld_menu values (1);
                create table ld_bookmark (dup int);
                """;
        Check.eq(List.of("ld_bookmark", "ld_webservicecall", "ld_extra"), DbInitPlan.createdTables(sql),
                "created tables in order, de-duplicated, comments ignored");
    }
}
```

- [ ] **Step 3: Run the test on the host to see it fail**

The host has JDK 27. `--release 21` keeps the code within Java 21 APIs.
```bash
rm -rf /tmp/ldtools && mkdir -p /tmp/ldtools
javac --release 21 -encoding UTF-8 -d /tmp/ldtools docker/tools/test/Check.java docker/tools/test/DbInitPlanTest.java
```
Expected: compilation FAILS with `cannot find symbol ... DbInitPlan`.

- [ ] **Step 4: Write `docker/tools/src/DbInitPlan.java`**

```java
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Works out which DbInit SQL scripts to run, the way the setup wizard's PluginDbInit does: every plugin's DbInit
 * extensions against logicaldoc-core, in ascending position, preferring the &lt;sqlFile&gt;.&lt;dbms&gt; variant.
 * Pure logic: no database and no LogicalDOC classes.
 */
public final class DbInitPlan {

    /** One DbInit extension: the declaring plugin, its position and the SQL resource it names. */
    public record Entry(String pluginId, int position, String sqlFile) {
    }

    private static final Pattern CREATE_TABLE = Pattern
            .compile("(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z0-9_]+)");

    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    private DbInitPlan() {
    }

    /** Reads the DbInit extensions declared against logicaldoc-core in one plugin.xml. */
    public static List<Entry> parse(InputStream pluginXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        // Third-party jars can ship a plugin.xml too; never fetch a DTD over the network.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Element root = factory.newDocumentBuilder().parse(pluginXml).getDocumentElement();

        List<Entry> entries = new ArrayList<>();
        if (!"plugin".equals(root.getTagName()))
            return entries;
        String pluginId = root.getAttribute("id");
        NodeList extensions = root.getElementsByTagName("extension");
        for (int i = 0; i < extensions.getLength(); i++) {
            Element ext = (Element) extensions.item(i);
            if (!"logicaldoc-core".equals(ext.getAttribute("plugin-id"))
                    || !"DbInit".equals(ext.getAttribute("point-id")))
                continue;
            Map<String, String> params = new HashMap<>();
            NodeList parameters = ext.getElementsByTagName("parameter");
            for (int j = 0; j < parameters.getLength(); j++) {
                Element p = (Element) parameters.item(j);
                params.put(p.getAttribute("id"), p.getAttribute("value").trim());
            }
            String position = params.get("position");
            String sqlFile = params.get("sqlFile");
            if (position == null || position.isEmpty() || sqlFile == null || sqlFile.isEmpty())
                throw new IllegalArgumentException("Plugin " + pluginId + " declares DbInit extension '"
                        + ext.getAttribute("id") + "' without both position and sqlFile");
            entries.add(new Entry(pluginId, Integer.parseInt(position), sqlFile));
        }
        return entries;
    }

    /** Sorts by ascending position; entries with equal positions keep their order. */
    public static List<Entry> order(Collection<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::position));
        return sorted;
    }

    /** The resource to run for an entry: the DBMS variant if present, else the generic script. */
    public static String resolve(Entry entry, String dbms, Predicate<String> exists) {
        String variant = entry.sqlFile() + "." + dbms.toLowerCase(Locale.ROOT);
        if (exists.test(variant))
            return variant;
        if (exists.test(entry.sqlFile()))
            return entry.sqlFile();
        throw new IllegalStateException("Plugin " + entry.pluginId() + " declares DbInit script "
                + entry.sqlFile() + " but neither it nor " + variant + " is on the classpath");
    }

    /** Names of the tables a script creates, in order of first appearance, ignoring -- comments. */
    public static List<String> createdTables(String sql) {
        Matcher m = CREATE_TABLE.matcher(LINE_COMMENT.matcher(sql).replaceAll(""));
        LinkedHashSet<String> names = new LinkedHashSet<>();
        while (m.find())
            names.add(m.group(1));
        return new ArrayList<>(names);
    }
}
```

- [ ] **Step 5: Run the test on the host to see it pass**

```bash
rm -rf /tmp/ldtools && mkdir -p /tmp/ldtools
javac --release 21 -encoding UTF-8 -d /tmp/ldtools docker/tools/src/DbInitPlan.java docker/tools/test/Check.java docker/tools/test/DbInitPlanTest.java \
  && java -cp /tmp/ldtools DbInitPlanTest
```
Expected: `DbInitPlanTest: OK`

- [ ] **Step 6: Write `docker/tools/build.sh`**

```bash
#!/usr/bin/env bash
# Compiles the container init tool against the WAR's libraries, runs its tests and packages it.
# Usage: build.sh <WEB-INF/lib dir> <default context.properties> <output jar>
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
lib=$1
defaults=$2
jar=$3
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

javac --release 21 -encoding UTF-8 -cp "$lib/*" -d "$work/classes" "$here"/src/*.java
javac --release 21 -encoding UTF-8 -cp "$work/classes:$lib/*" -d "$work/test-classes" "$here"/test/*.java

run_test() { java -cp "$work/test-classes:$work/classes:$lib/*" "$@"; }
run_test DbInitPlanTest

jar cf "$jar" -C "$work/classes" .
echo "built $jar"
```
(`$defaults` is consumed by `ConfigFileTest` in Task 5.)

- [ ] **Step 7: Append the `tools` stage to `Dockerfile`**

Append after the build stage:
```dockerfile

# ---- tools: compile, test and package the container init tool against the WAR's own libraries ----
FROM build AS tools
COPY docker/tools /tools
RUN bash /tools/build.sh /build/ROOT/WEB-INF/lib /build/ROOT/WEB-INF/classes/context.properties /build/docker-init.jar
```

- [ ] **Step 8: Run the tests in the image build**

```bash
docker build --target tools --progress=plain -t logicaldoc-tools:test . 2>&1 | grep -E 'DbInitPlanTest|FAIL|built /build|ERROR' ; echo "exit=${PIPESTATUS[0]}"
```
Expected: `DbInitPlanTest: OK`, `built /build/docker-init.jar`, `exit=0`. The build stage must come from cache, with no Maven run.

- [ ] **Step 9: Commit**

```bash
git add docker/tools Dockerfile
git commit -F - <<'EOF'
Add DbInitPlan: pick DbInit scripts like the setup wizard

Pure logic for reading plugin.xml DbInit extensions, ordering them by
position, choosing the .mariadb variant and listing the tables each
script creates. Tests run in a new tools build stage.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: ConfigPlan (which config keys the container sets)

**Files:**
- Create: `docker/tools/test/ConfigPlanTest.java`, `docker/tools/src/ConfigPlan.java`
- Modify: `docker/tools/build.sh` (run the new test)

**Interfaces:**
- Consumes: `Check` (Task 3).
- Produces:
  - `ConfigPlan.HOME = "/opt/logicaldoc"`
  - `static Map<String,String> ConfigPlan.seedKeys(String id)`, an ordered map of the 15 seed-once keys from the spec
  - `static Map<String,String> ConfigPlan.envKeys(Map<String,String> env)`. It throws `IllegalArgumentException` mentioning the variable for a missing or empty `DB_PASSWORD`, a password containing `${` or starting with `_b64_`, and a non-numeric `DB_PORT`.
  - `static int ConfigPlan.dbWaitSeconds(Map<String,String> env)`: default 60, and it throws `IllegalArgumentException` mentioning `DB_WAIT_SECONDS` for anything that isn't a positive integer.

- [ ] **Step 1: Write `docker/tools/test/ConfigPlanTest.java`**

```java
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConfigPlanTest {

    public static void main(String[] args) {
        seedKeysUseAbsoluteRepositoryPaths();
        seedKeysConfigureMariaDb();
        envKeysUseDefaults();
        envKeysHonourOverrides();
        blankValuesFallBackToDefaults();
        passwordIsRequired();
        passwordKeptVerbatim();
        passwordRejectsSyntaxLogicalDocInterprets();
        portMustBeNumeric();
        publicUrlOptionalAndNormalised();
        dbWaitSecondsValidated();
        Check.done("ConfigPlanTest");
    }

    private static Map<String, String> env(String... pairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            env.put(pairs[i], pairs[i + 1]);
        return env;
    }

    private static void seedKeysUseAbsoluteRepositoryPaths() {
        Map<String, String> seed = ConfigPlan.seedKeys("abc");
        for (String key : List.of("store.1.dir", "index.dir", "conf.userdir", "conf.plugindir", "conf.importdir",
                "conf.exportdir", "conf.logdir", "conf.dbdir")) {
            String v = seed.get(key);
            Check.isTrue(v != null && v.startsWith("/opt/logicaldoc/repository/") && v.endsWith("/"),
                    key + " is an absolute repository dir, was " + v);
        }
        Check.eq("/opt/logicaldoc/repository/docs/", seed.get("store.1.dir"), "store.1.dir");
        Check.eq("/opt/logicaldoc/repository/impex/in/", seed.get("conf.importdir"), "conf.importdir");
        Check.eq("/opt/logicaldoc", seed.get("LDOCHOME"), "LDOCHOME");
        Check.eq("abc", seed.get("id"), "id");
        Check.eq("true", seed.get("initialized"), "initialized");
        Check.eq(15, seed.size(), "number of seed keys");
    }

    private static void seedKeysConfigureMariaDb() {
        Map<String, String> seed = ConfigPlan.seedKeys("x");
        Check.eq("org.mariadb.jdbc.Driver", seed.get("jdbc.driver"), "driver");
        Check.eq("mariadb", seed.get("jdbc.dbms"), "dbms");
        Check.eq("org.hibernate.dialect.MariaDBDialect", seed.get("hibernate.dialect"), "dialect");
        Check.eq("SELECT 1", seed.get("jdbc.validationQuery"), "validation query");
    }

    private static void envKeysUseDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw"));
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", keys.get("jdbc.url"), "default url");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "default user");
        Check.eq("pw", keys.get("jdbc.password"), "password");
        Check.isTrue(!keys.containsKey("server.url"), "server.url untouched when LOGICALDOC_PUBLIC_URL is unset");
    }

    private static void envKeysHonourOverrides() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", "mariadb.lan", "DB_PORT",
                "3307", "DB_NAME", "docs", "DB_USER", "ldoc"));
        Check.eq("jdbc:mariadb://mariadb.lan:3307/docs", keys.get("jdbc.url"), "overridden url");
        Check.eq("ldoc", keys.get("jdbc.username"), "overridden user");
    }

    private static void blankValuesFallBackToDefaults() {
        Map<String, String> keys = ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_HOST", " ", "DB_USER", ""));
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", keys.get("jdbc.url"), "blank host uses the default");
        Check.eq("logicaldoc", keys.get("jdbc.username"), "blank user uses the default");
    }

    private static void passwordIsRequired() {
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env()), "missing password");
        Check.throwsWith("DB_PASSWORD", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "")), "empty password");
    }

    private static void passwordKeptVerbatim() {
        String awkward = " sm0ke,p=a\\ss $x #1 é:! ";
        Check.eq(awkward, ConfigPlan.envKeys(env("DB_PASSWORD", awkward)).get("jdbc.password"),
                "password is neither trimmed nor altered");
    }

    private static void passwordRejectsSyntaxLogicalDocInterprets() {
        Check.throwsWith("${", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "a${b}c")), "placeholder syntax");
        Check.throwsWith("_b64_", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "_b64_abc")), "base64 prefix");
    }

    private static void portMustBeNumeric() {
        Check.throwsWith("DB_PORT", () -> ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "DB_PORT", "33o6")),
                "non-numeric port");
    }

    private static void publicUrlOptionalAndNormalised() {
        Check.eq("https://docs.example.org/",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://docs.example.org"))
                        .get("server.url"),
                "trailing slash appended");
        Check.eq("https://docs.example.org/",
                ConfigPlan.envKeys(env("DB_PASSWORD", "pw", "LOGICALDOC_PUBLIC_URL", "https://docs.example.org/"))
                        .get("server.url"),
                "trailing slash kept");
    }

    private static void dbWaitSecondsValidated() {
        Check.eq(60, ConfigPlan.dbWaitSeconds(env()), "default wait");
        Check.eq(5, ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "5")), "explicit wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "soon")),
                "non-numeric wait");
        Check.throwsWith("DB_WAIT_SECONDS", () -> ConfigPlan.dbWaitSeconds(env("DB_WAIT_SECONDS", "0")),
                "zero wait");
    }
}
```

- [ ] **Step 2: Run on the host to see it fail**

```bash
rm -rf /tmp/ldtools && mkdir -p /tmp/ldtools
javac --release 21 -encoding UTF-8 -d /tmp/ldtools docker/tools/src/DbInitPlan.java docker/tools/test/Check.java docker/tools/test/ConfigPlanTest.java
```
Expected: compilation FAILS with `cannot find symbol ... ConfigPlan`.

- [ ] **Step 3: Write `docker/tools/src/ConfigPlan.java`**

```java
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decides which context.properties keys the container manages. Pure logic over an environment map; DockerInit
 * applies the result with LogicalDOC's own ContextProperties.
 */
public final class ConfigPlan {

    public static final String HOME = "/opt/logicaldoc";

    private static final String REPO = HOME + "/repository";

    private ConfigPlan() {
    }

    /** Written once, when the container first sees context.properties; LogicalDOC owns them afterwards. */
    public static Map<String, String> seedKeys(String id) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("store.1.dir", REPO + "/docs/");
        keys.put("index.dir", REPO + "/index/");
        keys.put("conf.userdir", REPO + "/users/");
        keys.put("conf.plugindir", REPO + "/plugins/");
        keys.put("conf.importdir", REPO + "/impex/in/");
        keys.put("conf.exportdir", REPO + "/impex/out/");
        keys.put("conf.logdir", REPO + "/logs/");
        keys.put("conf.dbdir", REPO + "/db/");
        keys.put("LDOCHOME", HOME);
        keys.put("jdbc.driver", "org.mariadb.jdbc.Driver");
        keys.put("jdbc.dbms", "mariadb");
        keys.put("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect");
        keys.put("jdbc.validationQuery", "SELECT 1");
        keys.put("initialized", "true");
        // Pre-set so the core plugin's install marker is stable from the first boot.
        keys.put("id", id);
        return keys;
    }

    /** Re-applied on every boot, so the environment stays the source of truth for them. */
    public static Map<String, String> envKeys(Map<String, String> env) {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("jdbc.url", jdbcUrl(env));
        keys.put("jdbc.username", value(env, "DB_USER", "logicaldoc"));
        keys.put("jdbc.password", password(env));
        String publicUrl = value(env, "LOGICALDOC_PUBLIC_URL", "");
        if (!publicUrl.isEmpty())
            keys.put("server.url", publicUrl.endsWith("/") ? publicUrl : publicUrl + "/");
        return keys;
    }

    /** How long to wait for the database before giving up. */
    public static int dbWaitSeconds(Map<String, String> env) {
        String raw = value(env, "DB_WAIT_SECONDS", "60");
        if (!raw.matches("\\d{1,6}") || Integer.parseInt(raw) == 0)
            throw new IllegalArgumentException("DB_WAIT_SECONDS must be a positive number of seconds, got '" + raw + "'");
        return Integer.parseInt(raw);
    }

    private static String jdbcUrl(Map<String, String> env) {
        String port = value(env, "DB_PORT", "3306");
        if (!port.matches("\\d{1,5}"))
            throw new IllegalArgumentException("DB_PORT must be a port number, got '" + port + "'");
        return "jdbc:mariadb://" + value(env, "DB_HOST", "db") + ":" + port + "/" + value(env, "DB_NAME", "logicaldoc");
    }

    private static String password(Map<String, String> env) {
        String password = env.get("DB_PASSWORD");
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("DB_PASSWORD is not set; copy .env.example to .env and set it");
        // LogicalDOC expands ${...} and decodes a _b64_ prefix when it reads jdbc.password through getString().
        if (password.contains("${"))
            throw new IllegalArgumentException("DB_PASSWORD must not contain '${'");
        if (password.startsWith("_b64_"))
            throw new IllegalArgumentException("DB_PASSWORD must not start with '_b64_'");
        return password;
    }

    /** Blank counts as unset, so an empty line in .env falls back to the default. */
    private static String value(Map<String, String> env, String name, String fallback) {
        String v = env.get(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
```

- [ ] **Step 4: Run on the host to see it pass**

```bash
rm -rf /tmp/ldtools && mkdir -p /tmp/ldtools
javac --release 21 -encoding UTF-8 -d /tmp/ldtools docker/tools/src/DbInitPlan.java docker/tools/src/ConfigPlan.java docker/tools/test/Check.java docker/tools/test/DbInitPlanTest.java docker/tools/test/ConfigPlanTest.java \
  && java -cp /tmp/ldtools ConfigPlanTest && java -cp /tmp/ldtools DbInitPlanTest
```
Expected: `ConfigPlanTest: OK` and `DbInitPlanTest: OK`.

- [ ] **Step 5: Run it in the tools stage too**

In `docker/tools/build.sh`, change
```bash
run_test DbInitPlanTest
```
to
```bash
run_test DbInitPlanTest
run_test ConfigPlanTest
```
Run:
```bash
docker build --target tools --progress=plain -t logicaldoc-tools:test . 2>&1 | grep -E 'PlanTest|FAIL|ERROR'; echo "exit=${PIPESTATUS[0]}"
```
Expected: both suites `OK`, `exit=0`.

- [ ] **Step 6: Commit**

```bash
git add docker/tools
git commit -F - <<'EOF'
Add ConfigPlan: container-managed context.properties keys

Seed-once keys (absolute repository paths, MariaDB driver/dialect,
LDOCHOME, id) and env-owned keys (jdbc.*, server.url), with validation
for the DB_PASSWORD forms LogicalDOC would reinterpret.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: DockerInit (apply config, wait for DB, create schema)

**Files:**
- Create: `docker/tools/test/ConfigFileTest.java`, `docker/tools/src/DockerInit.java`, `docker/tests/lib.sh`, `docker/tests/test-init.sh`
- Modify: `docker/tools/build.sh` (run `ConfigFileTest`)

**Interfaces:**
- Consumes:
  - `DbInitPlan.*` (Task 3), `ConfigPlan.*` (Task 4)
  - `com.logicaldoc.util.config.ContextProperties(File)`, with `getProperty`, `getString`, `setProperty` and `write()`
  - `com.logicaldoc.util.dbinit.DBInit(List<String>)`, with `setDriver/setUrl/setUsername/setPassword` and `execute()`
- Produces:
  - `java -cp '<WEB-INF/lib>/*:docker-init.jar' DockerInit`. It exits 0 when ready and 1 with `[docker-init] ERROR: …` on stderr otherwise.
  - It reads `/opt/logicaldoc/conf/context.properties`, which must already exist (the entrypoint copies the defaults).
  - Messages later tasks grep for:
    - `[docker-init] schema present, skipping database initialization`
    - `[docker-init] schema created (N tables)`
    - `[docker-init] plan: <position> <pluginId> -> <resource>`
    - `[docker-init] seeding …`
    - `context.properties was just created, but the database already has a schema`
    - `database authentication failed`
    - `not reachable`
  - `static boolean DockerInit.applyConfig(File, Map<String,String> envKeys) throws IOException`. It returns true when it seeded the file, meaning `LDOCHOME` was absent or blank.
  - `docker/tests/lib.sh`: `pass`, `fail`, `check <what> <cmd…>`, `wait_healthy <container> <seconds>` (fails at once for a container without a healthcheck), `lacks <pattern> <text>`, `http_code <url>`, `finish`.

- [ ] **Step 1: Write `docker/tools/test/ConfigFileTest.java`**

```java
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

import com.logicaldoc.util.config.ContextProperties;

/** Round-trips config edits through LogicalDOC's own ContextProperties, starting from the shipped defaults. */
public class ConfigFileTest {

    private static final String AWKWARD = " sm0ke,p=a\\ss $x #1 é:! ";

    public static void main(String[] args) throws Exception {
        File conf = new File(Files.createTempDirectory("configfile").toFile(), "context.properties");
        Files.copy(new File(args[0]).toPath(), conf.toPath(), StandardCopyOption.REPLACE_EXISTING);

        boolean seeded = DockerInit.applyConfig(conf,
                ConfigPlan.envKeys(Map.of("DB_PASSWORD", AWKWARD, "LOGICALDOC_PUBLIC_URL", "https://docs.example.org")));
        Check.isTrue(seeded, "first call seeds the shipped defaults");

        ContextProperties reread = new ContextProperties(conf);
        Check.eq(AWKWARD, reread.getProperty("jdbc.password"), "password via getProperty (raw readers)");
        Check.eq(AWKWARD, reread.getString("jdbc.password"), "password via getString (Spring placeholders)");
        Properties plain = new Properties();
        try (FileInputStream in = new FileInputStream(conf)) {
            plain.load(in);
        }
        Check.eq(AWKWARD, plain.getProperty("jdbc.password"), "password via java.util.Properties");
        Check.isTrue(reread.getProperty("id").matches("[0-9a-f-]{36}"), "id is a UUID: " + reread.getProperty("id"));
        long idLines = Files.readAllLines(conf.toPath(), StandardCharsets.ISO_8859_1).stream()
                .filter(l -> l.matches("^id\\s*[=:].*")).count();
        Check.eq(1L, idLines, "id appears exactly once");
        Check.eq("/opt/logicaldoc/repository/index/", reread.getProperty("index.dir"), "index.dir seeded");
        Check.eq("jdbc:mariadb://db:3306/logicaldoc", reread.getProperty("jdbc.url"), "jdbc.url applied");
        Check.eq("https://docs.example.org/", reread.getProperty("server.url"), "server.url applied");
        Check.eq("true", reread.getProperty("webservice.enabled"), "unmanaged key preserved");

        // A later boot applies env keys only: a seed-once key the admin changed must survive.
        reread.setProperty("conf.importdir", "/srv/custom-in/");
        reread.write();
        boolean seededAgain = DockerInit.applyConfig(conf, ConfigPlan.envKeys(Map.of("DB_PASSWORD", "rotated")));
        ContextProperties later = new ContextProperties(conf);
        Check.isTrue(!seededAgain, "second call does not seed");
        Check.eq("/srv/custom-in/", later.getProperty("conf.importdir"), "admin-changed seed key preserved");
        Check.eq("rotated", later.getProperty("jdbc.password"), "env key re-applied");
        Check.done("ConfigFileTest");
    }
}
```

- [ ] **Step 2: Wire it into `build.sh` and watch it fail**

In `docker/tools/build.sh`, change
```bash
run_test ConfigPlanTest
```
to
```bash
run_test ConfigPlanTest
run_test ConfigFileTest "$defaults"
```
Run:
```bash
docker build --target tools --progress=plain -t logicaldoc-tools:test . 2>&1 | grep -E 'error:|FAIL|Test' ; echo "exit=${PIPESTATUS[0]}"
```
Expected: FAILS to compile with `cannot find symbol ... DockerInit`, and `exit=1`.

- [ ] **Step 3: Write `docker/tools/src/DockerInit.java`**

```java
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.xml.sax.SAXException;

import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.util.dbinit.DBInit;

/**
 * Container init, run by entrypoint.sh before Tomcat starts: applies the container-managed context.properties
 * keys, waits for the database and creates the schema on first boot with the setup wizard's own DBInit.
 */
public final class DockerInit {

    static final File CONFIG = new File(ConfigPlan.HOME + "/conf/context.properties");

    private static final String DRIVER = "org.mariadb.jdbc.Driver";

    private static final String DBMS = "mariadb";

    /** SQLSTATE MariaDB reports for a table that does not exist. */
    private static final String NO_SUCH_TABLE = "42S02";

    private DockerInit() {
    }

    public static void main(String[] args) {
        try {
            run(System.getenv());
        } catch (IllegalArgumentException | IllegalStateException | InitException e) {
            System.err.println("[docker-init] ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void run(Map<String, String> env) throws Exception {
        Map<String, String> envKeys = ConfigPlan.envKeys(env);
        int waitSeconds = ConfigPlan.dbWaitSeconds(env);
        boolean seeded = applyConfig(CONFIG, envKeys);

        Db db = new Db(envKeys.get("jdbc.url"), envKeys.get("jdbc.username"), envKeys.get("jdbc.password"));
        db.await(waitSeconds);
        if (db.missingTables(List.of("ld_user")).isEmpty()) {
            log("schema present, skipping database initialization");
            if (seeded)
                warnConfigLoss();
            return;
        }
        createSchema(db);
    }

    /**
     * Seeds the container's paths and DB settings the first time (recognised by a missing LDOCHOME, which the
     * shipped defaults lack), then applies the env-owned keys. Returns true when this call seeded the file.
     */
    static boolean applyConfig(File file, Map<String, String> envKeys) throws IOException {
        ContextProperties config = new ContextProperties(file);
        String home = config.getProperty("LDOCHOME");
        boolean seed = home == null || home.isBlank();
        if (seed) {
            log("seeding " + file);
            ConfigPlan.seedKeys(UUID.randomUUID().toString()).forEach(config::setProperty);
        }
        envKeys.forEach(config::setProperty);
        config.write();
        return seed;
    }

    private static void createSchema(Db db) throws Exception {
        ClassLoader cl = DockerInit.class.getClassLoader();
        List<DbInitPlan.Entry> entries = new ArrayList<>();
        for (URL manifest : Collections.list(cl.getResources("plugin.xml"))) {
            try (InputStream in = manifest.openStream()) {
                entries.addAll(DbInitPlan.parse(in));
            } catch (SAXException e) {
                log("ignoring unparseable " + manifest + ": " + e.getMessage());
            }
        }
        List<DbInitPlan.Entry> ordered = DbInitPlan.order(entries);
        if (ordered.stream().noneMatch(e -> e.pluginId().equals("logicaldoc-core")))
            throw new InitException("no DbInit extension of logicaldoc-core found on the classpath");

        List<String> scripts = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        for (DbInitPlan.Entry entry : ordered) {
            String script = DbInitPlan.resolve(entry, DBMS, name -> cl.getResource(name) != null);
            log("plan: " + entry.position() + " " + entry.pluginId() + " -> " + script);
            scripts.add(script);
            try (InputStream in = cl.getResourceAsStream(script)) {
                // Same encoding upstream's DBInit reads the scripts with.
                tables.addAll(DbInitPlan.createdTables(new String(in.readAllBytes(), Charset.forName("Cp1252"))));
            }
        }

        log("creating schema");
        DBInit init = new DBInit(scripts);
        init.setDriver(DRIVER);
        init.setUrl(db.url);
        init.setUsername(db.user);
        init.setPassword(db.password);
        init.execute();

        List<String> missing = db.missingTables(tables);
        if (!missing.isEmpty())
            throw new InitException("schema initialization incomplete, missing tables " + missing
                    + "; remove the database volume and start again");
        log("schema created (" + tables.size() + " tables)");
    }

    private static void warnConfigLoss() {
        String rule = "*".repeat(100);
        System.err.println(rule);
        System.err.println("[docker-init] WARNING: context.properties was just created, but the database already has a schema.");
        System.err.println("[docker-init] The encryption key and node id are new, so settings encrypted before will not decrypt.");
        System.err.println("[docker-init] Restore /opt/logicaldoc/conf from your backup if you have one (see docker/README.md).");
        System.err.println(rule);
    }

    private static void log(String message) {
        System.out.println("[docker-init] " + message);
    }

    /** Thin JDBC helper; every call opens its own short-lived connection. */
    private static final class Db {

        final String url;

        final String user;

        final String password;

        Db(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        void await(int seconds) throws InitException, InterruptedException {
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (true) {
                try (Connection c = connect(); Statement s = c.createStatement()) {
                    s.execute("select 1");
                    return;
                } catch (SQLException e) {
                    // SQLSTATE class 28 = invalid authorization: retrying will not help.
                    if (e.getSQLState() != null && e.getSQLState().startsWith("28"))
                        throw new InitException("database authentication failed for user '" + user + "' at " + url
                                + ": " + e.getMessage());
                    if (System.nanoTime() > deadline)
                        throw new InitException("database not reachable at " + url + " after " + seconds + "s: "
                                + e.getMessage());
                    log("waiting for database at " + url + " (" + e.getMessage() + ")");
                }
                Thread.sleep(2000);
            }
        }

        List<String> missingTables(List<String> tables) throws SQLException {
            List<String> missing = new ArrayList<>();
            try (Connection c = connect(); Statement s = c.createStatement()) {
                for (String table : tables) {
                    try {
                        s.executeQuery("select 1 from " + table + " where 1=0").close();
                    } catch (SQLException e) {
                        if (!NO_SUCH_TABLE.equals(e.getSQLState()))
                            throw e;
                        missing.add(table);
                    }
                }
            }
            return missing;
        }
    }

    private static final class InitException extends Exception {

        private static final long serialVersionUID = 1L;

        InitException(String message) {
            super(message);
        }
    }
}
```

- [ ] **Step 4: Run the tool tests**

```bash
docker build --target tools --progress=plain -t logicaldoc-tools:test . 2>&1 | grep -E 'Test: OK|FAIL|error:|built /build' ; echo "exit=${PIPESTATUS[0]}"
```
Expected: `DbInitPlanTest: OK`, `ConfigPlanTest: OK`, `ConfigFileTest: OK`, `built /build/docker-init.jar`, `exit=0`.
- If `ConfigFileTest` fails on the password round trip (for example, `ContextProperties.write()` drops leading or trailing spaces), the fix belongs in `ConfigPlan.password`. It should refuse what the format can't carry, with a clear message and a matching `ConfigPlanTest` case. Then document the restriction in the README in Task 9. Do not change upstream classes.

- [ ] **Step 5: Write `docker/tests/lib.sh`**

```bash
# Shared helpers for the Docker test scripts (bash 3.2 compatible). Source it; it runs nothing itself.

FAILED=0

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; FAILED=1; }

# check <description> <command...>: runs the command and records PASS or FAIL.
check() {
  local what=$1
  shift
  if "$@"; then pass "$what"; else fail "$what"; fi
}

# wait_healthy <container> <timeout seconds>: waits for Docker's health status to become "healthy".
wait_healthy() {
  local cid=$1 deadline=$((SECONDS + $2)) status
  while :; do
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo missing)
    case $status in
      healthy) return 0 ;;
      none | missing) echo "container '$cid' has no health status ($status)"; return 1 ;;
    esac
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "container $cid is '$status' after $2s"
      return 1
    fi
    sleep 5
  done
}

# lacks <pattern> <text>: succeeds when the text does not contain the pattern.
lacks() { ! grep -q "$1" <<<"$2"; }

# http_code <url> [curl args...]: prints the HTTP status code only.
http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# finish: prints the overall result and exits non-zero if any check failed.
finish() {
  if [ "$FAILED" = 0 ]; then echo "ALL PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
}
```

- [ ] **Step 6: Write `docker/tests/test-init.sh`**

```bash
#!/usr/bin/env bash
# Integration test for DockerInit against a throwaway MariaDB: validation, waiting for a late database,
# schema creation, idempotent re-runs, config-loss warning and credential errors (spec §6, §9).
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

TOOLS=logicaldoc-tools:test
NET=ldtest-init
DB=ldtest-init-db
CONF=ldtest-init-conf
PW=' sm0ke,p=a\ss $x #1 é:! '

cleanup() {
  docker rm -f "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build --target tools -t "$TOOLS" . >/dev/null
docker network create "$NET" >/dev/null
docker volume create "$CONF" >/dev/null

# init [docker run options...]: runs DockerInit like the entrypoint does (copying defaults on first use).
init() {
  docker run --rm --network "$NET" -v "$CONF:/opt/logicaldoc/conf" \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" "$@" "$TOOLS" bash -c '
      [ -f /opt/logicaldoc/conf/context.properties ] || cp /build/ROOT/WEB-INF/classes/context.properties /opt/logicaldoc/conf/
      exec java -cp "/build/ROOT/WEB-INF/lib/*:/build/docker-init.jar" DockerInit'
}

start_db() {
  docker run -d --name "$DB" --network "$NET" -e MARIADB_DATABASE=logicaldoc -e MARIADB_USER=logicaldoc \
    -e MARIADB_PASSWORD="$PW" -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4 >/dev/null
}

echo "== 1. missing DB_PASSWORD"
out=$(init -e DB_PASSWORD= 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "names DB_PASSWORD" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== 2. database starts after the app; conf left unseeded by run 1 is seeded now"
log2=$(mktemp)
init -e DB_WAIT_SECONDS=180 >"$log2" 2>&1 &
pid=$!
sleep 8
start_db
wait "$pid" && rc=0 || rc=$?
out=$(cat "$log2"); rm -f "$log2"
check "exits 0" test "$rc" = 0
check "waited for the database" grep -q 'waiting for database' <<<"$out"
check "seeded the config" grep -q 'seeding /opt/logicaldoc/conf/context.properties' <<<"$out"
check "core uses the mariadb variant" grep -q 'plan: 1 logicaldoc-core -> sql/logicaldoc-core.sql.mariadb' <<<"$out"
check "webservice uses the mariadb variant" grep -q 'plan: 20 logicaldoc-webservice -> sql/logicaldoc-webservice.sql.mariadb' <<<"$out"
check "dropbox uses the generic script" grep -q 'plan: 1500 logicaldoc-dropbox -> sql/logicaldoc-dropbox.sql$' <<<"$out"
check "schema created" grep -q 'schema created' <<<"$out"
check "ld_webservicecall exists" docker exec "$DB" sh -c \
  'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "select 1 from ld_webservicecall where 1=0"'
[ "$FAILED" = 0 ] || { echo "$out"; }

echo "== 3. second run is a no-op"
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "no config-loss warning" lacks 'WARNING' "$out"

echo "== 4. conf volume lost, database kept"
docker volume rm -f "$CONF" >/dev/null && docker volume create "$CONF" >/dev/null
out=$(init 2>&1) && rc=0 || rc=$?
check "exits 0" test "$rc" = 0
check "skips initialization" grep -q 'schema present, skipping' <<<"$out"
check "warns about lost config" grep -q 'context.properties was just created, but the database already has a schema' <<<"$out"

echo "== 5. wrong password fails fast"
start=$SECONDS
out=$(init -e DB_PASSWORD=wrong -e DB_WAIT_SECONDS=120 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says authentication failed" grep -q 'database authentication failed' <<<"$out"
check "does not wait out the timeout" test $((SECONDS - start)) -lt 60

echo "== 6. unreachable database times out"
out=$(init -e DB_HOST=ldtest-nowhere -e DB_WAIT_SECONDS=4 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "says not reachable" grep -q 'not reachable' <<<"$out"

finish
```

- [ ] **Step 7: Run it**

```bash
chmod 0755 docker/tests/test-init.sh
bash docker/tests/test-init.sh
```
Expected: every line `PASS`, ending in `ALL PASSED`.
- If "schema created" fails with missing tables, read the DBInit/SqlFile errors in the printed output. Those are real schema problems: report them and don't paper over them.

- [ ] **Step 8: Commit**

```bash
git add docker/tools docker/tests/lib.sh docker/tests/test-init.sh
git commit -F - <<'EOF'
Add DockerInit: config seeding and first-boot schema creation

Applies container-managed keys with LogicalDOC's own ContextProperties,
waits for MariaDB (failing fast on bad credentials), and creates the
schema with upstream's DBInit using the plugin.xml DbInit plan, then
verifies every created table exists. Integration test runs it against
a throwaway MariaDB.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: Runtime image

**Files:**
- Create: `docker/entrypoint.sh`, `docker/restart.sh`, `docker/setenv.sh`, `docker/server.xml`, `docker/rewrite.config`, `docker/tests/test-image.sh`
- Modify: `Dockerfile` (append the `runtime` stage)

**Interfaces:**
- Consumes: `/build/ROOT` (Task 2), `/build/docker-init.jar` and the DockerInit messages (Task 5), `docker/tests/lib.sh` (Task 5).
- Produces:
  - Image target `runtime` (the default target, since it's the last stage). Entrypoint `/opt/logicaldoc/bin/entrypoint.sh`, CMD `["catalina.sh","run"]`, port 8080, volumes `/opt/logicaldoc/conf` and `/opt/logicaldoc/repository`.
  - Healthcheck on `/login.jsp`.
  - `/setup` and `/setup.jsp` return 403.

- [ ] **Step 1: Write the failing test `docker/tests/test-image.sh`**

```bash
#!/usr/bin/env bash
# Image-level checks with plain docker (spec §4, §6, §9): entrypoint guard, ownership repair, uid, /setup
# block, restart, settings persistence and config-loss handling.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

IMAGE=logicaldoc-community:test
NET=ldtest-image
DB=ldtest-image-db
APP=ldtest-image-app
CONF=ldtest-image-conf
REPO=ldtest-image-repo
PW='image test pw'

cleanup() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  docker volume rm -f "$CONF" "$REPO" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build -t "$IMAGE" .
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -e MARIADB_DATABASE=logicaldoc -e MARIADB_USER=logicaldoc \
  -e MARIADB_PASSWORD="$PW" -e MARIADB_RANDOM_ROOT_PASSWORD=1 mariadb:11.4 >/dev/null

start_app() {
  docker run -d --name "$APP" --network "$NET" --restart unless-stopped -p 127.0.0.1::8080 \
    -e DB_HOST="$DB" -e DB_PASSWORD="$PW" \
    -v "$CONF:/opt/logicaldoc/conf" -v "$REPO:/opt/logicaldoc/repository" "$IMAGE" >/dev/null
}
remove_app() { docker rm -f "$APP" >/dev/null; }
app_logs_have() { docker logs "$APP" 2>&1 | grep -q "$1"; }

echo "== refuses to start without DB_PASSWORD"
out=$(docker run --rm "$IMAGE" 2>&1) && rc=0 || rc=$?
check "exits 1" test "$rc" = 1
check "explains why" grep -q 'DB_PASSWORD is not set' <<<"$out"

echo "== volumes owned by root (like a NAS bind mount) are handed to uid 1000"
docker volume create "$CONF" >/dev/null
docker volume create "$REPO" >/dev/null
docker run --rm -v "$CONF:/c" -v "$REPO:/r" busybox sh -c 'touch /r/root-file && chown -R 0:0 /c /r'
start_app
check "becomes healthy" wait_healthy "$APP" 900
base="http://$(docker port "$APP" 8080/tcp | head -1)"
check "repository content owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/repository/root-file)" = 1000
check "context.properties owned by uid 1000" test "$(docker exec "$APP" stat -c %u /opt/logicaldoc/conf/context.properties)" = 1000
check "Tomcat (PID 1) runs as uid 1000" test "$(docker exec "$APP" stat -c %u /proc/1)" = 1000
check "schema created on first boot" app_logs_have 'schema created'

echo "== pages"
check "login page 200" test "$(http_code "$base/login.jsp")" = 200
check "/setup is 403" test "$(http_code "$base/setup")" = 403
check "/setup.jsp is 403" test "$(http_code "$base/setup.jsp")" = 403
check "app generated its encryption key in the conf volume" \
  docker exec "$APP" grep -qE '^encryption\.key *= *[^ ]' /opt/logicaldoc/conf/context.properties

echo "== Admin > Restart: restart.sh stops Tomcat and the restart policy brings it back"
before=$(docker inspect -f '{{.RestartCount}}' "$APP")
docker exec -u 1000 "$APP" /opt/logicaldoc/bin/restart.sh || true
deadline=$((SECONDS + 120))
while [ "$(docker inspect -f '{{.RestartCount}}' "$APP")" = "$before" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 2; done
check "container restarted" test "$(docker inspect -f '{{.RestartCount}}' "$APP")" -gt "$before"
check "healthy after restart" wait_healthy "$APP" 900

echo "== settings changed by the admin survive container recreation"
remove_app
docker run --rm -v "$CONF:/c" busybox sed -i -E \
  's|^conf\.importdir *=.*|conf.importdir=/opt/logicaldoc/repository/custom-in/|' /c/context.properties
start_app
check "healthy after recreation" wait_healthy "$APP" 900
check "conf.importdir kept" docker exec "$APP" grep -qE '^conf\.importdir *= */opt/logicaldoc/repository/custom-in/' /opt/logicaldoc/conf/context.properties
check "no re-initialization" app_logs_have 'schema present, skipping'

echo "== conf volume lost, database kept"
remove_app
docker volume rm -f "$CONF" >/dev/null && docker volume create "$CONF" >/dev/null
start_app
check "healthy with a fresh conf volume" wait_healthy "$APP" 900
check "warns about lost config" app_logs_have 'context.properties was just created, but the database already has a schema'

[ "$FAILED" = 0 ] || docker logs --tail 150 "$APP" 2>&1
finish
```

- [ ] **Step 2: Run it to see it fail**

```bash
bash docker/tests/test-image.sh
```
Expected: FAIL. With no `runtime` stage yet, the final image is the `tools` stage, which has no entrypoint or healthcheck. So the DB_PASSWORD checks fail, and `wait_healthy` fails at once with "has no health status". The script ends with `SOME CHECKS FAILED`, and the trap cleans up.

- [ ] **Step 3: Write `docker/entrypoint.sh`**

```bash
#!/usr/bin/env bash
# Container entrypoint: repairs volume ownership, seeds config from the image defaults, runs DockerInit
# (container-managed config keys, wait for the DB, first-boot schema) and then replaces itself with Tomcat
# running as the logicaldoc user.
set -euo pipefail

readonly LDOC_HOME=/opt/logicaldoc
readonly CONF=$LDOC_HOME/conf
readonly REPO=$LDOC_HOME/repository
readonly APP_UID=1000
readonly APP_GID=1000

log() { echo "[entrypoint] $*"; }
die() { echo "[entrypoint] ERROR: $*" >&2; exit 1; }

[ -n "${DB_PASSWORD:-}" ] || die "DB_PASSWORD is not set; copy .env.example to .env and set it"

export HOME=$LDOC_HOME

if [ "$(id -u)" = 0 ]; then
  as_app() { setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups "$@"; }
  # Bind mounts and restored backups can arrive owned by another user; the app must write both volumes.
  for dir in "$CONF" "$REPO"; do
    mkdir -p "$dir"
    if [ "$(stat -c %u "$dir")" != "$APP_UID" ] || [ -n "$(find "$dir" -maxdepth 1 ! -user "$APP_UID" -print -quit)" ]; then
      log "changing ownership of $dir to uid $APP_UID"
      chown -R "$APP_UID:$APP_GID" "$dir"
    fi
  done
else
  # Started with --user: assume the volumes are already writable.
  as_app() { "$@"; }
fi

if [ ! -f "$CONF/context.properties" ]; then
  log "no $CONF/context.properties yet, starting from the image defaults"
  as_app cp "$LDOC_HOME/defaults/context.properties" "$CONF/context.properties"
fi
if [ ! -f "$CONF/log.xml" ]; then
  as_app cp "$LDOC_HOME/defaults/log.xml" "$CONF/log.xml"
fi
as_app mkdir -p "$REPO"/docs "$REPO"/index "$REPO"/users "$REPO"/plugins "$REPO"/impex/in "$REPO"/impex/out \
  "$REPO"/logs "$REPO"/db

as_app java -cp "$CATALINA_HOME/webapps/ROOT/WEB-INF/lib/*:$LDOC_HOME/lib/docker-init.jar" DockerInit

log "starting: $*"
if [ "$(id -u)" = 0 ]; then
  exec setpriv --reuid="$APP_UID" --regid="$APP_GID" --init-groups "$@"
fi
exec "$@"
```

- [ ] **Step 4: Write `docker/restart.sh`, `docker/setenv.sh`, `docker/rewrite.config` and `docker/server.xml`**

`docker/restart.sh`:
```sh
#!/bin/sh
# Run by Admin > Restart (LDOCHOME/bin/restart.sh). Tomcat is PID 1, so stopping it stops the container and
# the restart policy (restart: unless-stopped) starts it again.
kill -TERM 1
```

`docker/setenv.sh`:
```sh
#!/bin/sh
# Sourced by catalina.sh. CATALINA_OPTS from the environment (heap size and so on) is kept and extended.
CATALINA_OPTS="${CATALINA_OPTS:-} -Dfile.encoding=UTF-8"
```

`docker/rewrite.config`:
```
# Installed as conf/Catalina/localhost/rewrite.config. The setup wizard is disabled: the container configures
# the database from the environment and creates the schema itself; re-running the wizard would overwrite that.
RewriteRule ^/setup(\.jsp)?(/.*)?$ - [NC,F]
```

`docker/server.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Tomcat configuration for the LogicalDOC container image (see docker/README.md). -->
<Server port="-1" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.startup.VersionLoggerListener" />
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener" />

  <Service name="Catalina">
    <Connector port="8080" protocol="HTTP/1.1" connectionTimeout="20000" URIEncoding="UTF-8" />

    <Engine name="Catalina" defaultHost="localhost">
      <!-- Behind a reverse proxy: trust X-Forwarded-For/-Proto from private and loopback addresses only
           (Tomcat's default internalProxies), so the app sees https and the real client address. -->
      <Valve className="org.apache.catalina.valves.RemoteIpValve"
             remoteIpHeader="X-Forwarded-For" protocolHeader="X-Forwarded-Proto" />

      <Host name="localhost" appBase="webapps" unpackWARs="false" autoDeploy="false">
        <!-- Rules: conf/Catalina/localhost/rewrite.config -->
        <Valve className="org.apache.catalina.valves.rewrite.RewriteValve" />
        <Valve className="org.apache.catalina.valves.ErrorReportValve" showReport="false" showServerInfo="false" />
      </Host>
    </Engine>
  </Service>
</Server>
```

Make the scripts executable, so git records the mode:
```bash
chmod 0755 docker/entrypoint.sh docker/restart.sh docker/setenv.sh docker/tests/test-image.sh
```

- [ ] **Step 5: Append the `runtime` stage to `Dockerfile`**

```dockerfile

# ---- runtime: Tomcat 11 serving the exploded WAR at / ----
FROM ${TOMCAT_IMAGE} AS runtime
ARG VERSION=local
ARG REVISION=unknown
LABEL org.opencontainers.image.title="LogicalDOC Community (coffeemuse fork)" \
      org.opencontainers.image.source="https://github.com/coffeemuse/logicaldoc-community" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="LGPL-3.0-only"

# Thumbnails and previews: ImageMagick (images; uses Ghostscript for PS/PDF input), Poppler (PDF).
# curl serves the healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends imagemagick poppler-utils ghostscript curl \
 && rm -rf /var/lib/apt/lists/*

# Ubuntu base images ship an "ubuntu" user with uid 1000; replace it with ours.
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && (groupdel ubuntu 2>/dev/null || true) \
 && groupadd -g 1000 logicaldoc \
 && useradd -u 1000 -g 1000 -d /opt/logicaldoc -M -s /usr/sbin/nologin logicaldoc

RUN rm -rf "$CATALINA_HOME"/webapps/* "$CATALINA_HOME"/webapps.dist
COPY --from=build --chown=1000:1000 /build/ROOT "$CATALINA_HOME/webapps/ROOT"
COPY --from=tools --chown=1000:1000 /build/docker-init.jar /opt/logicaldoc/lib/docker-init.jar
COPY docker/server.xml "$CATALINA_HOME/conf/server.xml"
COPY docker/rewrite.config "$CATALINA_HOME/conf/Catalina/localhost/rewrite.config"
COPY --chmod=0755 docker/setenv.sh "$CATALINA_HOME/bin/setenv.sh"
COPY --chmod=0755 --chown=1000:1000 docker/entrypoint.sh docker/restart.sh /opt/logicaldoc/bin/

# context.properties and log.xml must leave WEB-INF/classes: Tomcat's webapp class loader looks there before
# common.loader, where the live, writable copies in /opt/logicaldoc/conf are found.
RUN mkdir -p /opt/logicaldoc/defaults /opt/logicaldoc/conf /opt/logicaldoc/repository \
 && mv "$CATALINA_HOME/webapps/ROOT/WEB-INF/classes/context.properties" \
       "$CATALINA_HOME/webapps/ROOT/WEB-INF/classes/log.xml" /opt/logicaldoc/defaults/ \
 && sed -i 's|^common.loader=.*|&,"/opt/logicaldoc/conf"|' "$CATALINA_HOME/conf/catalina.properties" \
 && grep -q '"/opt/logicaldoc/conf"' "$CATALINA_HOME/conf/catalina.properties" \
 && chown -R 1000:1000 /opt/logicaldoc "$CATALINA_HOME/logs" "$CATALINA_HOME/temp" "$CATALINA_HOME/work"

VOLUME ["/opt/logicaldoc/conf", "/opt/logicaldoc/repository"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
  CMD curl -fsS -o /dev/null http://localhost:8080/login.jsp || exit 1
ENTRYPOINT ["/opt/logicaldoc/bin/entrypoint.sh"]
CMD ["catalina.sh", "run"]
```

- [ ] **Step 6: Run the image test**

```bash
bash docker/tests/test-image.sh 2>&1 | tee /tmp/ld-test-image.log | grep -E '^(PASS|FAIL|==|ALL|SOME)'
```
Expected: all `PASS`, then `ALL PASSED`. The first boot takes a few minutes.
- If the app fails to start, the script prints the last 150 log lines. Common causes and their fixes:
  - **A JVM `InaccessibleObjectException` naming a module/package.** Add only that `--add-opens` flag to `docker/setenv.sh`, with a comment naming the failure. Candidates are listed in `logicaldoc-parent/pom.xml` (surefire `argLine`).
  - **`context.properties` read from the wrong place**, so the paths still show `${user.home}`. Check the WAR has no jar shadowing it (Task 2, Step 5) and that `catalina.properties` contains the conf path.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile docker/entrypoint.sh docker/restart.sh docker/setenv.sh docker/server.xml docker/rewrite.config docker/tests/test-image.sh
git commit -F - <<'EOF'
Add Tomcat 11 runtime image with entrypoint

Exploded WAR at /, live context.properties and log.xml in a persistent
conf dir on common.loader, ownership repair for bind mounts, DockerInit
before Tomcat, JVM as uid 1000, proxy-aware RemoteIpValve, /setup
blocked, Admin > Restart wired to the restart policy.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: Compose stack and end-to-end smoke test

**Files:**
- Create: `docker/smoke-test.sh`, `compose.yaml`, `.env.example`

**Interfaces:**
- Consumes: the image (Task 6) and `docker/tests/lib.sh` (Task 5).
- Produces:
  - Compose project `logicaldoc` with services `db` and `app` and volumes `db-data`, `logicaldoc-conf`, `logicaldoc-repository`.
  - `docker/smoke-test.sh` exits 0 on success. CI reuses it later.

- [ ] **Step 1: Write the failing test `docker/smoke-test.sh`**

```bash
#!/usr/bin/env bash
# End-to-end acceptance test for the compose stack (spec §10). Runs as its own compose project with its own
# env file and removes everything it created on exit. Usage: docker/smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
. docker/tests/lib.sh

PROJECT=logicaldoc-smoke
PORT=${SMOKE_PORT:-18080}
BASE="http://127.0.0.1:$PORT"
WORK=$(mktemp -d)
ENV_FILE=$WORK/smoke.env
# Awkward on purpose: exercises .env quoting, context.properties escaping and the JDBC login.
PW=' sm0ke,p=a\ss $x #1 é:! '

compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f compose.yaml "$@"; }
cleanup() {
  [ "$FAILED" = 0 ] || compose logs --tail 150 app || true
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

printf "DB_PASSWORD='%s'\nLOGICALDOC_PORT=%s\n" "$PW" "$PORT" > "$ENV_FILE"

# Fixtures: a 16x16 PNG and a one-page PDF with a correct xref table.
python3 - "$WORK" <<'PY'
import pathlib, struct, sys, zlib
out = pathlib.Path(sys.argv[1])
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
raw = b"".join(b"\x00" + b"\xff\x00\x00" * 16 for _ in range(16))
out.joinpath("smoke.png").write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
                                      + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
content = b"BT /F1 24 Tf 30 100 Td (smoke test) Tj ET"
objects = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = []
for number, body in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf += b"%d 0 obj\n" % number + body + b"\nendobj\n"
xref = len(pdf)
pdf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
for offset in offsets:
    pdf += b"%010d 00000 n \n" % offset
pdf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
out.joinpath("smoke.pdf").write_bytes(bytes(pdf))
PY

login() { curl -fsS "$BASE/services/rest/auth/login?u=admin&pw=admin" | tr -d '"[:space:]'; }
app_logs_have() { compose logs app 2>&1 | grep -q "$1"; }
is_png() { [ "$(od -An -tx1 -N4 "$1" | tr -d ' \n')" = 89504e47 ]; }

echo "== 1. build and start"
compose up -d --build
check "app healthy" wait_healthy "$(compose ps -q app)" 900
[ "$FAILED" = 0 ] || exit 1

echo "== 2. login page"
check "GET /login.jsp is 200" test "$(http_code "$BASE/login.jsp")" = 200

echo "== 3. REST login and default workspace"
sid=$(login)
check "REST login returns a session id" grep -Eq '^[0-9a-f-]{36}$' <<<"$sid"
ws_id=$(curl -fsS -H 'Accept: application/json' "$BASE/services/rest/folder/getDefaultWorkspace?sid=$sid" | jq -r .id)
ws_path=$(curl -fsS "$BASE/services/rest/folder/getPathString?folderId=$ws_id&sid=$sid" | tr -d '"')
check "default workspace resolved" test -n "$ws_id" -a -n "$ws_path"

echo "== 4. upload a PDF and a PNG; thumbnails are generated"
for f in smoke.pdf smoke.png; do
  doc_id=$(curl -fsS -H 'Accept: application/json' -F "folderId=$ws_id" -F "filename=$f" \
    -F "filedata=@$WORK/$f" "$BASE/services/rest/document/upload?sid=$sid")
  check "$f uploaded" grep -Eq '^[0-9]+$' <<<"$doc_id"
  curl -fsS -o "$WORK/$f.thumb" "$BASE/services/rest/document/thumbnail/thumb$ws_path/$f?sid=$sid" || true
  check "$f thumbnail is a PNG" is_png "$WORK/$f.thumb"
done

echo "== 5. webservice schema present"
check "ld_webservicecall exists" compose exec -T db sh -c \
  'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE" -Nse "select 1 from ld_webservicecall where 1=0"'

echo "== 6. setup wizard blocked"
check "GET /setup is 403" test "$(http_code "$BASE/setup")" = 403

echo "== 7. data survives down/up"
compose down
compose up -d
check "app healthy again" wait_healthy "$(compose ps -q app)" 900
sid=$(login)
names=$(curl -fsS -H 'Accept: application/json' "$BASE/services/rest/document/list?folderId=$ws_id&sid=$sid" | jq -r '.[].fileName')
check "smoke.pdf still listed" grep -qx smoke.pdf <<<"$names"
check "smoke.png still listed" grep -qx smoke.png <<<"$names"
check "no re-initialization" app_logs_have 'schema present, skipping'

finish
```

- [ ] **Step 2: Run it to see it fail**

```bash
chmod 0755 docker/smoke-test.sh
bash docker/smoke-test.sh
```
Expected: FAIL immediately. Compose reports that `compose.yaml` can't be found (`no configuration file provided` or `open compose.yaml: no such file`).

- [ ] **Step 3: Write `compose.yaml`**

```yaml
# LogicalDOC Community: production baseline (home lab behind your own reverse proxy).
# Overlays: compose.dev.yaml (development), compose.proxy.yaml (Nginx Proxy Manager). See docker/README.md.
name: logicaldoc

services:
  db:
    image: mariadb:11.4
    restart: unless-stopped
    environment:
      MARIADB_DATABASE: ${DB_NAME:-logicaldoc}
      MARIADB_USER: ${DB_USER:-logicaldoc}
      MARIADB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
      MARIADB_RANDOM_ROOT_PASSWORD: "1"
      TZ: ${TZ:-UTC}
    # LogicalDOC's MariaDB scripts declare utf8/utf8_bin per table; this is the default for anything else.
    command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"]
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 30s
    volumes:
      - db-data:/var/lib/mysql

  app:
    build:
      context: .
    image: logicaldoc-community:local
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment:
      DB_HOST: ${DB_HOST:-db}
      DB_PORT: ${DB_PORT:-3306}
      DB_NAME: ${DB_NAME:-logicaldoc}
      DB_USER: ${DB_USER:-logicaldoc}
      DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set in .env}
      LOGICALDOC_PUBLIC_URL: ${LOGICALDOC_PUBLIC_URL:-}
      CATALINA_OPTS: ${CATALINA_OPTS:--Xmx2g}
      DB_WAIT_SECONDS: ${DB_WAIT_SECONDS:-60}
      TZ: ${TZ:-UTC}
    ports:
      - "${LOGICALDOC_BIND:-127.0.0.1}:${LOGICALDOC_PORT:-8080}:8080"
    volumes:
      - logicaldoc-conf:/opt/logicaldoc/conf
      - logicaldoc-repository:/opt/logicaldoc/repository
    stop_grace_period: 60s

volumes:
  db-data:
  logicaldoc-conf:
  logicaldoc-repository:
```

- [ ] **Step 4: Write `.env.example`**

```bash
# Copy to .env and adjust: cp .env.example .env
# Wrap values containing $ # \ or spaces in single quotes. Nothing inside single quotes is interpreted.

# --- Required ---------------------------------------------------------------
# Database password, used to create the MariaDB user on first start.
# Must not contain '${' or start with '_b64_' (LogicalDOC would reinterpret those).
DB_PASSWORD=

# --- Database (defaults shown) ----------------------------------------------
#DB_NAME=logicaldoc
#DB_USER=logicaldoc
#DB_HOST=db
#DB_PORT=3306

# --- Web access -------------------------------------------------------------
# Host address and port the UI is published on. 127.0.0.1 = reachable from this machine only.
#LOGICALDOC_BIND=127.0.0.1
#LOGICALDOC_PORT=8080
# Public https URL when behind a reverse proxy; used in links and emails.
#LOGICALDOC_PUBLIC_URL=https://docs.example.org

# --- Runtime ----------------------------------------------------------------
#CATALINA_OPTS=-Xmx2g
#TZ=UTC

# --- Overlays (pick one COMPOSE_FILE line) ------------------------------------
# Development: UI on the LAN, Java debugger, MariaDB port.
#COMPOSE_FILE=compose.yaml:compose.dev.yaml
#JDWP_BIND=127.0.0.1
#DEV_DB_PORT=3306
# Nginx Proxy Manager or another containerized proxy on a shared Docker network.
#COMPOSE_FILE=compose.yaml:compose.proxy.yaml
#PROXY_NETWORK=npm_default
```

- [ ] **Step 5: Keep `.env` out of git**

Append to `.gitignore`:
```
.env
```

- [ ] **Step 6: Validate the compose file, then run the smoke test**

```bash
DB_PASSWORD=x docker compose -f compose.yaml config --quiet && echo "config ok"
bash docker/smoke-test.sh 2>&1 | tee /tmp/ld-smoke.log | grep -E '^(PASS|FAIL|==|ALL|SOME)'
```
Expected: `config ok`, then all `PASS` and `ALL PASSED`.
- If the REST login or `getPathString` response isn't a bare string (for example, it's XML), print it with `curl -s …` and adjust only the parsing in the script. Don't change the server.
- If a thumbnail check fails, look for ImageMagick/Poppler errors in `/opt/logicaldoc/repository/logs/dms.log` (`docker compose -p logicaldoc-smoke exec app tail -200 /opt/logicaldoc/repository/logs/dms.log`). That's a real runtime-tool problem, not a test problem.

- [ ] **Step 7: Commit**

```bash
git add compose.yaml .env.example docker/smoke-test.sh .gitignore
git commit -F - <<'EOF'
Add compose stack and end-to-end smoke test

MariaDB 11.4 plus the app, published on 127.0.0.1 by default, with
named volumes and healthchecks. smoke-test.sh builds and boots an
isolated project, logs in over REST, uploads a PDF and PNG and checks
their thumbnails, checks the webservice schema and /setup block, and
verifies data survives down/up.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 8: Dev and proxy overlays

**Files:**
- Create: `docker/tests/test-overlays.sh`, `compose.dev.yaml`, `compose.proxy.yaml`

**Interfaces:**
- Consumes: `compose.yaml` (Task 7), `docker/tests/lib.sh`.
- Produces:
  - **Dev overlay:** UI on `0.0.0.0:${LOGICALDOC_PORT}`, JDWP on `${JDWP_BIND:-127.0.0.1}:5005`, MariaDB on `127.0.0.1:${DEV_DB_PORT:-3306}`.
  - **Proxy overlay:** no host ports, and app on the external network `${PROXY_NETWORK:-npm_default}` with alias `logicaldoc`.

- [ ] **Step 1: Write the failing test `docker/tests/test-overlays.sh`**

```bash
#!/usr/bin/env bash
# Checks compose.dev.yaml and compose.proxy.yaml: the rendered config, then a live stack with each.
set -euo pipefail
cd "$(dirname "$0")/../.."
. docker/tests/lib.sh

PROJECT=logicaldoc-overlays
NET=ldtest-proxy
WORK=$(mktemp -d)
ENV_FILE=$WORK/test.env
printf "DB_PASSWORD='overlay test'\nPROXY_NETWORK=%s\nLOGICALDOC_PORT=18081\nDEV_DB_PORT=13306\n" "$NET" > "$ENV_FILE"

compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
cleanup() {
  compose -f compose.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT
cleanup
docker network create "$NET" >/dev/null

echo "== rendered config: dev overlay"
dev=$(compose -f compose.yaml -f compose.dev.yaml config --format json)
check "app ports are LAN UI + local debugger" test \
  "$(jq -c '[.services.app.ports[] | "\(.host_ip):\(.published):\(.target)"] | sort' <<<"$dev")" = \
  '["0.0.0.0:18081:8080","127.0.0.1:5005:5005"]'
check "Tomcat started with jpda" test "$(jq -c .services.app.command <<<"$dev")" = '["catalina.sh","jpda","run"]'
check "JPDA_ADDRESS set" test "$(jq -r .services.app.environment.JPDA_ADDRESS <<<"$dev")" = '*:5005'
check "db published on localhost" test \
  "$(jq -c '[.services.db.ports[] | "\(.host_ip):\(.published):\(.target)"]' <<<"$dev")" = '["127.0.0.1:13306:3306"]'

echo "== rendered config: proxy overlay"
proxy=$(compose -f compose.yaml -f compose.proxy.yaml config --format json)
check "no host ports" test "$(jq '.services.app.ports // [] | length' <<<"$proxy")" = 0
check "alias logicaldoc on the proxy network" test "$(jq -c '.services.app.networks.proxy.aliases' <<<"$proxy")" = '["logicaldoc"]'
check "proxy network is external and named from PROXY_NETWORK" test \
  "$(jq -c '[.networks.proxy.name, .networks.proxy.external]' <<<"$proxy")" = "[\"$NET\",true]"

echo "== live: proxy overlay"
compose -f compose.yaml -f compose.proxy.yaml up -d --build
check "app healthy" wait_healthy "$(compose -f compose.yaml ps -q app)" 900
check "reachable as logicaldoc:8080 from the proxy network" test \
  "$(docker run --rm --network "$NET" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}' http://logicaldoc:8080/login.jsp)" = 200
check "nothing published on the host" test "$(http_code http://127.0.0.1:18081/login.jsp || true)" = 000

echo "== live: dev overlay"
compose -f compose.yaml -f compose.dev.yaml up -d
check "app healthy" wait_healthy "$(compose -f compose.yaml ps -q app)" 900
check "UI on the published port" test "$(http_code http://127.0.0.1:18081/login.jsp)" = 200
check "JDWP handshake on 5005" python3 - <<'PY'
import socket, sys
s = socket.create_connection(("127.0.0.1", 5005), timeout=10)
s.sendall(b"JDWP-Handshake")
sys.exit(0 if s.recv(14) == b"JDWP-Handshake" else 1)
PY
check "MariaDB on 127.0.0.1:13306" python3 -c 'import socket; socket.create_connection(("127.0.0.1", 13306), timeout=5)'

finish
```

- [ ] **Step 2: Run it to see it fail**

```bash
chmod 0755 docker/tests/test-overlays.sh
bash docker/tests/test-overlays.sh
```
Expected: FAIL immediately, with compose reporting that `compose.dev.yaml` doesn't exist.

- [ ] **Step 3: Write `compose.dev.yaml`**

```yaml
# Development overlay: UI on the LAN, Java debugger, MariaDB port. Use with
#   docker compose -f compose.yaml -f compose.dev.yaml up -d --build
# or COMPOSE_FILE=compose.yaml:compose.dev.yaml in .env. See docker/README.md.
services:
  app:
    # Tomcat's own debug launcher, so only the Tomcat JVM (not the init tool) opens the debug port.
    command: ["catalina.sh", "jpda", "run"]
    environment:
      JPDA_ADDRESS: "*:5005"
    ports: !override
      - "0.0.0.0:${LOGICALDOC_PORT:-8080}:8080"
      # Anyone who can reach this port can run code in the JVM; keep it on localhost unless you need it.
      - "${JDWP_BIND:-127.0.0.1}:5005:5005"

  db:
    ports:
      - "127.0.0.1:${DEV_DB_PORT:-3306}:3306"
```

- [ ] **Step 4: Write `compose.proxy.yaml`**

```yaml
# Reverse-proxy overlay for Nginx Proxy Manager or any containerized proxy: no host port, the app joins the
# proxy's Docker network as "logicaldoc". Use with COMPOSE_FILE=compose.yaml:compose.proxy.yaml in .env and
# PROXY_NETWORK set to the proxy's network. See docker/README.md.
services:
  app:
    ports: !reset []
    networks:
      default:
      proxy:
        aliases:
          - logicaldoc

networks:
  proxy:
    name: ${PROXY_NETWORK:-npm_default}
    external: true
```

- [ ] **Step 5: Run the overlay test**

```bash
bash docker/tests/test-overlays.sh 2>&1 | tee /tmp/ld-overlays.log | grep -E '^(PASS|FAIL|==|ALL|SOME)'
```
Expected: all `PASS`, then `ALL PASSED`.

- [ ] **Step 6: Commit**

```bash
git add compose.dev.yaml compose.proxy.yaml docker/tests/test-overlays.sh
git commit -F - <<'EOF'
Add dev and reverse-proxy compose overlays

compose.dev.yaml publishes the UI on the LAN, a JDWP debugger (local by
default) and MariaDB. compose.proxy.yaml drops the host port and joins
an external proxy network (Nginx Proxy Manager's by default) under the
alias logicaldoc.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 9: Documentation, CLAUDE.md pointer and manual checks

**Files:**
- Create: `docker/README.md`
- Modify: `CLAUDE.md` (add a "Docker" section after "Build and test")

**Interfaces:**
- Consumes: everything above. The README must match the actual variable names, defaults, file names and messages.

- [ ] **Step 1: Write `docker/README.md`**

````markdown
# Running LogicalDOC Community with Docker

This directory holds the container build and compose stack for this fork. The design is in
[the spec](../docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md).

## Quick start

You need Docker with Compose v2.24 or newer. The first build runs the full Maven build, including the GWT
compile: allow 15–30 minutes and at least 8 GB of memory for Docker.

```bash
git clone https://github.com/coffeemuse/logicaldoc-community.git
cd logicaldoc-community
cp .env.example .env
```

Set `DB_PASSWORD` in `.env`, in single quotes (`DB_PASSWORD='…'`). Then:

```bash
docker compose up -d --build
docker compose logs -f app
```

On first start the app creates the database schema. When the log shows `Server startup`, open
http://127.0.0.1:8080 and sign in as `admin` / `admin`. **Change that password straight away.**

By default the UI is only reachable from the machine running Docker. To use it from elsewhere, put a reverse
proxy in front (see below) or use the dev overlay on a trusted LAN.

## What runs

| Service | What | Data |
|---|---|---|
| `db` | MariaDB 11.4 | volume `logicaldoc_db-data` |
| `app` | Tomcat 11 + LogicalDOC, built from this checkout | volumes `logicaldoc_logicaldoc-conf` (context.properties, log.xml) and `logicaldoc_logicaldoc-repository` (documents, search index, user data, logs) |

What the container does at startup:

- **First start:** it writes `context.properties`, waits for MariaDB and creates the schema using the same SQL scripts as LogicalDOC's setup wizard.
- **Later starts:** it only re-applies the settings that come from `.env`.
- **The setup wizard** (`/setup`) is disabled and returns HTTP 403, because the database is configured from `.env`.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DB_PASSWORD` | (required) | MariaDB password for `DB_USER` |
| `DB_NAME` | `logicaldoc` | database name |
| `DB_USER` | `logicaldoc` | database user |
| `DB_HOST` / `DB_PORT` | `db` / `3306` | change only to use an external MariaDB |
| `LOGICALDOC_PUBLIC_URL` | unset | public URL behind a proxy, e.g. `https://docs.example.org` |
| `LOGICALDOC_BIND` | `127.0.0.1` | host address the UI port is published on |
| `LOGICALDOC_PORT` | `8080` | host port for the UI |
| `CATALINA_OPTS` | `-Xmx2g` | JVM options, e.g. heap size |
| `TZ` | `UTC` | time zone |
| `DB_WAIT_SECONDS` | `60` | how long the app waits for the database at startup |
| `COMPOSE_FILE` | unset | select overlays, see below |
| `PROXY_NETWORK` | `npm_default` | proxy overlay: the proxy's Docker network |
| `JDWP_BIND` | `127.0.0.1` | dev overlay: debugger address |
| `DEV_DB_PORT` | `3306` | dev overlay: host port for MariaDB |

### Which settings `.env` controls

`.env` owns these settings and writes them into `context.properties` on every start:
- the database connection: `jdbc.url`, `jdbc.username` and `jdbc.password`
- the public URL (`server.url`), when `LOGICALDOC_PUBLIC_URL` is set

Change them in `.env`, then run `docker compose up -d`. Everything else belongs to LogicalDOC: change it in the
admin UI, and it survives restarts, container recreation and image rebuilds.

### Passwords and special characters

- **Quote values in `.env`.** In single quotes, nothing is interpreted: `DB_PASSWORD='p@ss $word #1'`.
- **Passwords containing a single quote** need double quotes instead, with every `$` written as `$$`.
- **`DB_PASSWORD` must not contain `${` and must not start with `_b64_`.** LogicalDOC treats those specially when it reads its configuration, so the container refuses them at startup.
- **MariaDB only uses `DB_PASSWORD` when it first creates its data volume.** To change it later:
  1. Change it in the database:
     ```bash
     docker compose exec db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" -e "ALTER USER CURRENT_USER() IDENTIFIED BY \"new-password\""'
     ```
  2. Put the new value in `.env`.
  3. Run `docker compose up -d`.

## Behind Nginx Proxy Manager

Nginx Proxy Manager (NPM) runs in its own container, so it reaches LogicalDOC over a shared Docker network
rather than a port on the host.

1. **Find NPM's network.** Run `docker network ls`. If NPM was started from a folder named `npm`, the network is `npm_default`. To confirm, run:
   ```bash
   docker inspect <npm-container> --format '{{json .NetworkSettings.Networks}}'
   ```
2. **Edit `.env` and restart.** Set:
   ```bash
   COMPOSE_FILE=compose.yaml:compose.proxy.yaml
   PROXY_NETWORK=npm_default
   LOGICALDOC_PUBLIC_URL=https://docs.example.org
   ```
   Then run `docker compose up -d`. The app no longer publishes a host port. It's reachable on that network as `logicaldoc:8080`.
3. **Add a Proxy Host in NPM** (Hosts → Proxy Hosts → Add Proxy Host):
   - **Details tab:**
     - Domain Names: `docs.example.org`
     - Scheme: `http`
     - Forward Hostname / IP: `logicaldoc`
     - Forward Port: `8080`
     - Turn on **Block Common Exploits** and **Websockets Support**. LogicalDOC pushes UI events over a WebSocket at `/wk-event`.
   - **SSL tab:** request a new Let's Encrypt certificate, and turn on **Force SSL** and **HTTP/2 Support**.
   - **Advanced tab**, under Custom Nginx Configuration, so large uploads and long conversions aren't cut off:
     ```nginx
     client_max_body_size 0;
     proxy_request_buffering off;
     proxy_read_timeout 300s;
     proxy_send_timeout 300s;
     ```
4. **Save, then open** `https://docs.example.org`.

NPM sends `X-Forwarded-For` and `X-Forwarded-Proto`. Tomcat trusts those headers from private network
addresses, so LogicalDOC sees HTTPS and the real client IP.

**Troubleshooting:**

| Symptom | Cause |
|---|---|
| `network npm_default declared as external, but could not be found` | `PROXY_NETWORK` doesn't match NPM's network |
| 502 Bad Gateway | NPM isn't on that network, or the app isn't healthy. Check `docker compose ps`; `docker network inspect <network>` should list both containers |
| UI loads but live updates fail | turn on Websockets Support |
| 413 Request Entity Too Large | add the Advanced snippet |

## Other reverse proxies

Use these when the proxy runs on the same host, without the proxy overlay. The app listens on `127.0.0.1:8080`.

**Caddy.** It handles WebSockets and forwarded headers itself:

```
docs.example.org {
    reverse_proxy 127.0.0.1:8080
}
```

**nginx:**

```nginx
# In the http block:
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    http2 on;
    server_name docs.example.org;
    # ssl_certificate / ssl_certificate_key ...

    client_max_body_size 0;
    proxy_request_buffering off;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
    }
}
```

**A proxy on another machine.** Set `LOGICALDOC_BIND` to this host's LAN address and point the proxy at it.
Forwarded headers are only trusted from private and loopback addresses.

In every case, set `LOGICALDOC_PUBLIC_URL` to the public HTTPS URL.

## Development

```bash
echo 'COMPOSE_FILE=compose.yaml:compose.dev.yaml' >> .env
docker compose up -d --build
```

The dev overlay does three things:

- **Publishes the UI on all interfaces**, so other devices on your LAN can try a branch at `http://<this machine's IP>:8080`. There's no TLS, so only use it on a trusted network.
- **Starts Tomcat with a Java debugger on `127.0.0.1:5005`.** Attach with IntelliJ (Run → Edit Configurations → Remote JVM Debug, `localhost:5005`) or with `jdb -attach localhost:5005`.
  - To debug from another machine, set `JDWP_BIND=0.0.0.0`.
  - Anyone who can reach the port can run code in the JVM.
- **Publishes MariaDB on `127.0.0.1:3306`**, or `DEV_DB_PORT` if that's busy, with the `.env` credentials.

**Rebuilding and resetting:**
- Rebuild after a code change with `docker compose up -d --build app`.
- Maven dependencies are cached between builds, but every build re-runs the reactor build, including the GWT compile. Changes only under `docker/` skip the Maven step.
- Start over with an empty database and repository with `docker compose down -v`.

## Backup and restore

Back up the database and both app volumes together. `conf` holds the encryption key and node id, so documents
and a database restored without their matching `conf` lose encrypted settings.

```bash
docker compose stop app
docker compose exec db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb-dump -u"$MARIADB_USER" --single-transaction "$MARIADB_DATABASE"' > logicaldoc-db.sql
docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
  -v "$PWD":/backup busybox tar czf /backup/logicaldoc-files.tgz /conf /repository
docker compose start app
```

**Restore onto a fresh stack:**
1. Start the database: `docker compose up -d db`, and wait until `docker compose ps` shows it healthy.
2. Load the dump:
   ```bash
   docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"' < logicaldoc-db.sql
   ```
3. Restore the files:
   ```bash
   docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
     -v "$PWD":/backup busybox tar xzf /backup/logicaldoc-files.tgz -C /
   ```
4. Start everything: `docker compose up -d`.

Compose may warn that the two app volumes already exist. That's expected: it uses them.

## Upgrading

```bash
git pull
docker compose up -d --build
```

Volumes are kept. **The container never migrates the database schema.** If an upstream merge changes the
schema (look for changes under `*/src/main/resources/sql/`), apply that SQL by hand. Back up first.

## Host folders instead of volumes

Create `compose.override.yaml`:

```yaml
services:
  app:
    volumes:
      - /srv/logicaldoc/conf:/opt/logicaldoc/conf
      - /srv/logicaldoc/repository:/opt/logicaldoc/repository
  db:
    volumes:
      - /srv/logicaldoc/db:/var/lib/mysql
```

Compose reads `compose.override.yaml` automatically unless `COMPOSE_FILE` is set; if it is, add the file to that
list. The app hands its two folders to uid 1000 on start. MariaDB manages its own folder.

## Troubleshooting

| Log message | Meaning |
|---|---|
| `DB_PASSWORD is not set` | set it in `.env` |
| `database authentication failed` | `.env` doesn't match the password MariaDB was created with (see "Passwords") |
| `database not reachable … after 60s` | MariaDB didn't come up. Check `docker compose logs db`, or raise `DB_WAIT_SECONDS` on slow disks |
| `schema initialization incomplete` | the first-boot schema creation failed part-way. Run `docker compose down`, delete the `logicaldoc_db-data` volume, and start again |
| `context.properties was just created, but the database already has a schema` | the `conf` volume was lost. Restore it from backup |

## Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | unit tests for the init tool |
| `docker/tests/test-init.sh` | init tool against a throwaway MariaDB |
| `docker/tests/test-image.sh` | image behavior: ownership, `/setup` block, restart, settings persistence |
| `docker/tests/test-overlays.sh` | dev and proxy overlays |
| `docker/smoke-test.sh` | end to end: build, boot, REST login, uploads and thumbnails, data surviving a restart |
````

- [ ] **Step 2: Add the Docker section to `CLAUDE.md`**

Insert after the "Build and test" section (before "## Architecture"):
```markdown
## Docker

- `Dockerfile` (stages `build` → `tools` → `runtime`) and `compose.yaml` are this fork's own. `docker/README.md` is the user guide; the design is `docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md`.
- The container never runs the setup wizard. `docker/tools/src/DockerInit.java` seeds `context.properties` and creates the schema by running the `DbInit` scripts from every `plugin.xml`. A plugin that adds a `DbInit` extension is picked up automatically.
- Tests:
  - `docker build --target tools .` for the init tool's unit tests
  - `docker/tests/test-*.sh`
  - `docker/smoke-test.sh` for end to end
```

- [ ] **Step 3: Manual checks in the built-in browser**

Start the real stack with a local `.env`:
```bash
cp .env.example .env
sed -i '' "s|^DB_PASSWORD=.*|DB_PASSWORD='manual-check'|" .env
docker compose up -d --build
```
Wait until `docker compose ps` shows `app` healthy, then check each of the following:
1. **UI and login.** Open `http://127.0.0.1:8080` in the built-in browser and sign in as `admin` / `admin`. Expected: the main UI loads.
2. **Admin restart.** Go to Administration → System → Restart and confirm. Expected:
   - `docker compose ps` briefly shows `app` restarting, then healthy.
   - `docker inspect -f '{{.RestartCount}}' $(docker compose ps -q app)` has increased.
   - The UI is usable again after logging in.
3. **Setup wizard blocked.** Open `http://127.0.0.1:8080/setup`. Expected: HTTP 403.
4. **Debugger.** Switch to the dev overlay with `COMPOSE_FILE=compose.yaml:compose.dev.yaml docker compose up -d`, then run `jdb -attach localhost:5005` and type `threads`. Expected: Tomcat threads are listed. `exit` detaches.

Clean up with `docker compose down -v && rm .env`. `.env` is git-ignored since Task 7.

- [ ] **Step 4: Run every automated test once more on the final tree**

```bash
docker build --target tools -q . >/dev/null && echo "tools ok"
bash docker/tests/test-init.sh | tail -1
bash docker/tests/test-image.sh | tail -1
bash docker/tests/test-overlays.sh | tail -1
bash docker/smoke-test.sh | tail -1
```
Expected: `tools ok`, then `ALL PASSED` four times.

- [ ] **Step 5: Commit**

```bash
git add docker/README.md CLAUDE.md
git commit -F - <<'EOF'
Document the Docker stack

User guide covering quick start, configuration, Nginx Proxy Manager
and other proxies, development, backup/restore, upgrades, host folders
and troubleshooting. CLAUDE.md points at it and the test scripts.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 6: Update the issue checklist**

```bash
gh issue view 4 --repo coffeemuse/logicaldoc-community --json body -q .body > /tmp/issue4.md
```
Tick "Design spec", "Implementation plan" and "Implementation and local verification" in `/tmp/issue4.md`, then:
```bash
gh issue edit 4 --repo coffeemuse/logicaldoc-community --body-file /tmp/issue4.md
```
