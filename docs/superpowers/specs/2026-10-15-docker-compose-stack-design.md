# Docker image and compose stack: design

- **Issue:** [coffeemuse/logicaldoc-community#4](https://github.com/coffeemuse/logicaldoc-community/issues/4)
- **Date:** 2026-10-15
- **Status:** approved; refined while planning (see plan `docs/superpowers/plans/2026-10-15-docker-compose-stack.md`)

## 1. Intent

The goal is to make this fork easy to deploy and run with Docker. One compose stack covers two uses:

- **Home-lab production.** Data persists, and the stack sits behind the user's existing reverse proxy, normally Nginx Proxy Manager. We don't bundle TLS.
- **Dev/test.** You can reach the UI from the LAN to try out branches, attach a Java debugger, and inspect the database.

Publishing images to GHCR is a later goal. The image must be ready for it (configured by environment variables, OCI labels, works without compose), but publishing isn't part of this work. PostgreSQL is a long-term goal and also out of scope. MariaDB is the only database for now, but the environment variable names stay DB-neutral.

### Success criteria

1. A new user can get from a fresh clone to logging in with only:
   - `cp .env.example .env`
   - setting `DB_PASSWORD`
   - `docker compose up -d`

   There's no manual `/setup` step.
2. Documents, the search index, config and the DB survive `docker compose down` / `up`, image rebuilds and container recreation.
3. Changing DB credentials or the public URL in `.env`, then restarting, takes effect.
4. The stack runs behind Nginx Proxy Manager over HTTPS, and WebSockets and large uploads work.
5. `docker/smoke-test.sh` passes on the developer's machine (arm64).

### Assumptions

- Single host, single instance (no clustering).
- Fresh installs only. There's no migration path from an existing LogicalDOC installation.

## 2. Background: facts about the app that shape the design

All of these were checked in the source:

- **Config location.** `context.properties` is located **only through the classpath** (`ContextProperties`: `-Dld.config`, default `context.properties`, resolved with `ClassLoader.getResource`). `log.xml` is found the same way (`LogConfigurator`). No other code refers to either file by path.
- **The app rewrites `context.properties`.**
  - It does so on first boot, when it generates `id` and `encryption.key`, and also from the Settings UI and when the admin password changes.
  - It writes `context.properties.tmp` next to the file, then atomically renames it over the target, keeping up to 10 dated backups.
  - So the file must live in a **writable, persistent directory**. A single-file bind mount silently loses writes.
- **The schema is only created by the setup wizard.**
  - `SetupServiceImpl` runs `PluginDbInit`. That collects the `DbInit` extensions of `logicaldoc-core` from every plugin's `plugin.xml` and sorts them by `position`.
  - For each extension it uses the `<sqlFile>.<dbms>` variant if one exists, otherwise `<sqlFile>`.
  - It runs them through `com.logicaldoc.util.dbinit.DBInit`, which uses HSQLDB `SqlFile` with Cp1252 encoding and continues on error.
  - `DBInit` is standalone: it takes a driver, URL, user, password and a list of classpath resources, and needs no Spring context.
- **Upstream bug.** `logicaldoc-webservice/src/main/resources/plugin.xml` declares `sql/logicaldoc-webservices.sql`, but the files are named `logicaldoc-webservice.sql[.mariadb|…]`. As a result the wizard never creates `ld_webservicecall`.
- **Path keys are not expanded.** `store.1.dir`, `index.dir` and `conf.*dir` are read raw (`getProperty`), so values like `${user.home}` are never substituted. Seeded values must be literal absolute paths.
- **`LDOCHOME` is an ordinary config key** used by:
  - `ApplicationListener`: the PID file `LDOCHOME/bin/pid`
  - `SystemServiceImpl`: Admin → Restart runs `LDOCHOME/bin/restart.sh`
  - `LogDownload`: zips `LDOCHOME/conf`
- **Servlet 6.1 / WebSocket 2.2** (`web.xml`, provided-scope APIs) means **Tomcat 11** is required. The app is context-path agnostic, and it rewrites `META-INF/context.xml` (SameSite cookie setting) on every boot.
- **External tools Community Edition actually runs:**
  - ImageMagick `convert`, for image thumbnails
  - Poppler `pdftoppm`, `pdftotext` and `pdftohtml`
  - Ghostscript, which ImageMagick uses for PDF/PS input

  The config keys for LibreOffice, Tesseract, ClamAV and openssl have no server implementation in this repo.
- **What the scripts contain.** The core and webservice scripts create tables (`ld_webservicecall` is the webservice one). The dropbox script creates no tables and only inserts menu rows.
- **The MariaDB scripts set their own charset.** They declare `ENGINE=InnoDB CHARSET=utf8 COLLATE utf8_bin` on every table, so the server's default charset doesn't affect the schema.
- **First-boot double install.** The core plugin's install marker is `{conf.plugindir}/{plugin}/install-{id}`. On first boot `id` is empty when the marker is checked and is generated afterwards, so core installs again on the second boot. Pre-seeding `id` avoids this.
- **`/setup/**` login.** It's protected by `adminAuthenticationManager` using the `adminpasswd` key, whose default is the SHA-256 of `admin`. That key is updated when the admin user changes their password.
- **Entry points:** the login page is `/login.jsp`; REST login is `POST /services/rest/auth/login` (JSON `{"username","password"}`) or `GET /services/rest/auth/login?u=&pw=`.

## 3. Files

All new except the one-line `plugin.xml` fix, so upstream merges won't conflict.

```
Dockerfile                         multi-stage build (repo root)
.dockerignore                      excludes target/, .git, gwt-unitCache, IDE files
compose.yaml                       base stack = home-lab production
compose.dev.yaml                   dev overlay
compose.proxy.yaml                 Nginx Proxy Manager / shared-network overlay
.env.example                       every supported variable, documented
docker/
  maven-settings.xml               extra Maven repos (same block as CI)
  server.xml                       Tomcat config: RemoteIpValve, RewriteValve
  rewrite.config                   blocks /setup (installed to conf/Catalina/localhost/)
  entrypoint.sh                    ownership, default config files, DockerInit, privilege drop, exec Tomcat
  restart.sh                       target of Admin → Restart
  setenv.sh                        Tomcat JVM options
  tools/src/DbInitPlan.java        pure: which DbInit scripts to run (plugin.xml → ordered resources, created tables)
  tools/src/ConfigPlan.java        pure: seed-once and env-owned keys, env validation
  tools/src/DockerInit.java        main: apply config via ContextProperties, wait for DB, create + verify schema
  tools/test/*.java                unit and round-trip tests (no JUnit), run by tools/build.sh
  tools/build.sh                   compile, test and package the tool in the image's `tools` stage
  tests/lib.sh, tests/test-*.sh    init-tool, image and overlay integration tests
  smoke-test.sh                    end-to-end acceptance test
  README.md                        deploy, proxy (NPM first), dev, backup, upgrade
logicaldoc-webservice/src/main/resources/plugin.xml   fix sqlFile name (own commit)
```

## 4. The image (`Dockerfile`)

### Stage `build` (`maven:3.9-eclipse-temurin-21`)

1. Copy `docker/maven-settings.xml` to `/root/.m2/settings.xml`.
2. Copy the source and run `mvn -B -ntp -DskipTests install`, the same command CI runs. The run uses a BuildKit cache mount on `/root/.m2/repository`, so dependency downloads persist across builds.
3. Unpack `logicaldoc-webapp/target/logicaldoc-webapp-*.war` to `/build/ROOT`.
4. Stage `tools` (from `build`): run `docker/tools/build.sh`. It compiles the tool against `/build/ROOT/WEB-INF/lib/*`, runs its tests (a failing test fails the image build), and packages `/build/docker-init.jar`.

### Stage `runtime` (`tomcat:11.0-jre21-temurin`, Ubuntu-based, multi-arch)

- **Packages:** `apt-get install --no-install-recommends imagemagick poppler-utils ghostscript curl`.
- **Tomcat:** remove the default webapps, copy `/build/ROOT` to `$CATALINA_HOME/webapps/ROOT` (the app is served at `/`), and install `docker/server.xml` and `docker/rewrite.config`.
- **Default config files:**
  - Move `WEB-INF/classes/context.properties` and `WEB-INF/classes/log.xml` to `/opt/logicaldoc/defaults/`.
  - They must be **removed** from `WEB-INF/classes`, because Tomcat's webapp class loader searches `WEB-INF/classes` before its parent.
- **Classpath:** add `/opt/logicaldoc/conf` to `common.loader` in `catalina.properties`, so the live `context.properties` and `log.xml` are found there and rewritten in place. This matches upstream's installer layout.
- **Scripts and tools:**
  - `entrypoint.sh` goes to `/usr/local/bin/logicaldoc-entrypoint.sh`, root-owned, because root runs it and uid 1000 must not be able to change it.
  - `restart.sh` goes under `/opt/logicaldoc/bin` and `docker-init.jar` under `/opt/logicaldoc/lib`.
  - `setenv.sh` goes in Tomcat's `bin/`.
  - `allowed-commands.txt` (`convert`, `pdftoppm`, `pdftotext`, `pdftohtml`) goes into `defaults/`. LogicalDOC's `Exec` refuses any command not listed in the classpath `/allowed-commands.txt`.
- **User:** create `logicaldoc` (uid/gid 1000). It owns `/opt/logicaldoc` and the Tomcat dirs the webapp writes to (`webapps/ROOT`, `work`, `temp`, `logs`).
- **Volumes:** `/opt/logicaldoc/conf` and `/opt/logicaldoc/repository`.
- **Ports and health:** `EXPOSE 8080`. `HEALTHCHECK` runs `curl -fsS http://localhost:8080/login.jsp`.
- **OCI labels:** `org.opencontainers.image.source`, `.revision` and `.version` (from build args), `.licenses=LGPL-3.0-only` and `.title`.
- **Entrypoint and default command:** `ENTRYPOINT ["/opt/logicaldoc/bin/entrypoint.sh"]`, `CMD ["catalina.sh","run"]`.

The container starts as root. Only the entrypoint's preparation steps (the ownership fix) run as root, and the JVM always runs as `logicaldoc`.

### Filesystem layout

```
/opt/logicaldoc/                 = LDOCHOME
  conf/          VOLUME  context.properties (+ .yyyyMMdd backups), log.xml
  repository/    VOLUME  docs/ index/ users/ plugins/ impex/in impex/out logs/
  defaults/              pristine context.properties, log.xml from the WAR; allowed-commands.txt
  bin/                   restart.sh, pid (written by the app)
  lib/                   docker-init.jar
```

## 5. Configuration

### Environment variables

| Variable | Context key(s) | Default | Notes |
|---|---|---|---|
| `DB_HOST` | `jdbc.url` | `db` | |
| `DB_PORT` | `jdbc.url` | `3306` | |
| `DB_NAME` | `jdbc.url` | `logicaldoc` | |
| `DB_USER` | `jdbc.username` | `logicaldoc` | |
| `DB_PASSWORD` | `jdbc.password` | — | **Required.** The entrypoint exits with an error if it's unset. It must not contain `${` or start with `_b64_`, because LogicalDOC's `getString()` expands or decodes those. |
| `LOGICALDOC_PUBLIC_URL` | `server.url` | unset | When unset, the key isn't touched. |
| `CATALINA_OPTS` | (JVM) | `-Xmx2g` | Set in compose and `.env.example`. |
| `TZ` | (container) | `UTC` | |
| `DB_WAIT_SECONDS` | (init only) | `60` | How long DockerInit waits for the database. |

Compose-only variables:

| Variable | Default | Purpose |
|---|---|---|
| `LOGICALDOC_BIND` | `127.0.0.1` | host address for the published port |
| `LOGICALDOC_PORT` | `8080` | host port |
| `PROXY_NETWORK` | `npm_default` | external network for `compose.proxy.yaml` |
| `JDWP_BIND` | `127.0.0.1` | debugger bind address (dev overlay) |
| `DEV_DB_PORT` | `3306` | host port for MariaDB (dev overlay) |
| `COMPOSE_FILE` | unset | lets users select overlays once in `.env` |

`jdbc.url` is `jdbc:mariadb://${DB_HOST}:${DB_PORT}/${DB_NAME}`.

### Who owns which keys

- **Env-owned keys** are re-applied on **every** boot, so `.env` is the source of truth for them: `jdbc.url`, `jdbc.username`, `jdbc.password`, and `server.url` when `LOGICALDOC_PUBLIC_URL` is set.
- **Seed-once keys** are written only while `LDOCHOME` is absent from `conf/context.properties`. The shipped defaults lack it and the seed adds it. So a first boot that crashes before seeding still gets seeded on the next boot. After that the keys belong to the app and its Settings UI:

  | Key | Value |
  |---|---|
  | `store.1.dir` | `/opt/logicaldoc/repository/docs/` |
  | `index.dir` | `/opt/logicaldoc/repository/index/` |
  | `conf.userdir` | `/opt/logicaldoc/repository/users/` |
  | `conf.plugindir` | `/opt/logicaldoc/repository/plugins/` |
  | `conf.importdir` | `/opt/logicaldoc/repository/impex/in/` |
  | `conf.exportdir` | `/opt/logicaldoc/repository/impex/out/` |
  | `conf.logdir` | `/opt/logicaldoc/repository/logs/` |
  | `conf.dbdir` | `/opt/logicaldoc/repository/db/` |
  | `LDOCHOME` | `/opt/logicaldoc` |
  | `jdbc.driver` | `org.mariadb.jdbc.Driver` |
  | `jdbc.dbms` | `mariadb` |
  | `hibernate.dialect` | `org.hibernate.dialect.MariaDBDialect` |
  | `jdbc.validationQuery` | `SELECT 1` |
  | `initialized` | `true` |
  | `id` | a newly generated UUID |

Properties are edited by DockerInit with LogicalDOC's own `ContextProperties(File)`, using `setProperty` and `write()`. Escaping and encoding (the shipped file is Latin-1 with CRLF line endings) then match exactly what the app writes. Values are written literally (no `${env.…}` references), which keeps every reader of the key, raw (`getProperty`) or expanded (`getString`), consistent.

## 6. First boot and every boot (`entrypoint.sh`)

The entrypoint runs `set -euo pipefail`. Steps, in order:

1. **Preconditions.** Fail fast with a clear message if `DB_PASSWORD` is empty.
2. **Ownership** (as root). If `conf/` or `repository/` isn't owned by uid 1000, `chown -R` it. This makes bind mounts to NAS paths work without manual setup.
3. **Default files.**
   - If `conf/context.properties`, `conf/log.xml` or `conf/allowed-commands.txt` is missing, copy it from `defaults/`.
   - Re-add any line of the default `allowed-commands.txt` that the conf copy lacks. Local additions stay.
   - If `chown` is refused (NFS with root_squash, some CIFS mounts), continue with a warning when uid 1000 can write the directory. Otherwise exit with instructions to give the share to uid/gid 1000.
   - Create the `repository/` subdirectories.
4. **Run `DockerInit`** as `logicaldoc`: `java -cp '<webapp>/WEB-INF/lib/*:/opt/logicaldoc/lib/docker-init.jar' DockerInit`. It reads everything from the environment and behaves as follows:
   - **Validate** the env vars (§5). Errors exit 1 with `[docker-init] ERROR: …`.
   - **Apply config.**
     - It adds every key of `defaults/context.properties` that the conf file lacks, so settings upstream adds over time reach existing installs. It never overwrites a value.
     - It applies the seed-once keys if `LDOCHOME` is absent, then the env-owned keys, and writes once.
     - It then re-reads the file. LogicalDOC writes it one byte per character, so an env-owned value outside Latin-1, or one containing a line break, comes back changed. That exits 1, naming the variable.
   - **Wait for the DB.** It retries a connection with `select 1` every 2 s, for up to `DB_WAIT_SECONDS` (default 60). If the DB is still unreachable, it exits 1 ("not reachable"). An authentication error (SQLSTATE class 28) exits 1 at once ("database authentication failed").
   - **Detect the schema.** It probes for `ld_user` and for the completion marker table `docker_init`.
     - If both exist, it prints "schema present, skipping" and exits 0.
     - If neither exists, it goes on to create the schema.
     - If only one exists, an earlier first start failed or was interrupted. It exits 1 ("… its initialization did not finish …; remove the database volume").
     - Without the marker, the restart policy would hide a failed init on the next boot.
   - **Create the schema.**
     - It enumerates `ClassLoader.getResources("plugin.xml")` and parses each file with the JDK's DOM parser.
     - It collects `<extension plugin-id="logicaldoc-core" point-id="DbInit">` entries and sorts them by `position`.
     - For each one it prefers the `<sqlFile>.mariadb` resource if it exists. If neither the variant nor the base file exists, it exits 1 with an error naming the plugin and file. This catches problems like the webservice filename bug instead of silently skipping them.
     - It prints the ordered plan, then runs it with upstream's `com.logicaldoc.util.dbinit.DBInit` (`setDriver`/`setUrl`/`setUsername`/`setPassword`, then `execute()`). That's the same engine, encoding and continue-on-error behaviour as the setup wizard.
   - **Verify, then mark complete.** Only after the check below passes does it create `docker_init` and insert a timestamp. For every script it ran, it extracts each `create table <name>` (case-insensitive regex) and checks with `select 1 from <name> where 1=0` that the table exists. Scripts that only insert rows, like dropbox's, have nothing to check. If any table is missing, it exits 1 with: "schema initialization incomplete; remove the DB volume and retry".
   - **Config-loss warning.** If the schema already existed but this run just seeded `context.properties`, DockerInit prints a loud warning: the encryption key and node id are new, so restore `conf/` from backup. It doesn't block startup.
5. **Start Tomcat.** `exec setpriv --reuid=1000 --regid=1000 --init-groups "$@"`, which by default runs `catalina.sh run`. The JVM becomes PID 1 under `logicaldoc` and gets signals directly.

**JVM flags.** `CATALINA_OPTS` defaults to `-Xmx2g`. The image's `$CATALINA_HOME/bin/setenv.sh` adds `-Dfile.encoding=UTF-8`. `--add-opens` flags start empty. A flag is added only when a startup or smoke-test failure proves it's needed, and each one gets a comment naming the failure. Candidates are the surefire set in `logicaldoc-parent/pom.xml`.

**Restart.** `restart.sh` sends SIGTERM to PID 1. Tomcat shuts down cleanly, the container exits, and `restart: unless-stopped` brings it back.

**`/setup` blocked.** `server.xml` adds `org.apache.catalina.valves.rewrite.RewriteValve` to the `localhost` Host. Its rules file is installed at `$CATALINA_BASE/conf/Catalina/localhost/rewrite.config` and contains `RewriteRule ^/setup(/.*)?$ - [F]`. This avoids touching the webapp's own `META-INF/context.xml`, which the app rewrites. Config is env-managed and the schema is created automatically. Re-running the wizard would rewrite paths and DB settings and could re-initialize the DB.

**Proxy awareness.** `server.xml` adds a `RemoteIpValve` on the Engine, using Tomcat's default `internalProxies` (private and loopback ranges) and `protocolHeader=X-Forwarded-Proto`. Behind a TLS proxy the app then sees `https`, the correct client IP and secure cookies.

## 7. Compose

### `compose.yaml` (production baseline)

- **`db`**
  - Image `mariadb:11.4`.
  - Environment: `MARIADB_DATABASE=${DB_NAME}`, `MARIADB_USER=${DB_USER}`, `MARIADB_PASSWORD=${DB_PASSWORD}` and `MARIADB_RANDOM_ROOT_PASSWORD=1`.
  - Command: `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`.
  - Healthcheck: `healthcheck.sh --connect --innodb_initialized`.
  - Volume `db-data:/var/lib/mysql`. No published port. `restart: unless-stopped`.
- **`app`**
  - `build: .`, `image: logicaldoc-community:local`. The OCI `VERSION`/`REVISION` build args default to `local`/`unknown`; the future CI image build passes real values.
  - An explicit `environment:` block that passes the §5 variables through, using `${VAR:-default}` interpolation from `.env`.
  - `depends_on: db: condition: service_healthy`.
  - `ports: ["${LOGICALDOC_BIND:-127.0.0.1}:${LOGICALDOC_PORT:-8080}:8080"]`.
  - Volumes `logicaldoc-conf:/opt/logicaldoc/conf` and `logicaldoc-repository:/opt/logicaldoc/repository`.
  - Healthcheck: the image's own, with `start_period: 300s`.
  - `restart: unless-stopped`, `stop_grace_period: 60s`.
- **Volumes:** named `db-data`, `logicaldoc-conf` and `logicaldoc-repository`. The README shows how to switch them to bind mounts.

### `compose.dev.yaml`

- `app.ports` is replaced (with `!reset`) by `0.0.0.0:${LOGICALDOC_PORT:-8080}:8080`, so the UI is reachable from the LAN, plus `${JDWP_BIND:-127.0.0.1}:5005:5005`.
- `app.command: ["catalina.sh", "jpda", "run"]` with `JPDA_ADDRESS=*:5005`. This is Tomcat's own debug launcher, so only the Tomcat JVM, and not the DB-init JVM, opens the debug port.
- `db.ports` gets `127.0.0.1:${DEV_DB_PORT:-3306}:3306`.
- Usage: `docker compose -f compose.yaml -f compose.dev.yaml up --build`, or `COMPOSE_FILE=compose.yaml:compose.dev.yaml` in `.env`. Reset with `docker compose down -v`.

### `compose.proxy.yaml` (Nginx Proxy Manager and other containerized proxies)

- `app.ports: !reset []`, so no host port is published.
- `app.networks`: `default`, plus external `proxy` (named `${PROXY_NETWORK:-npm_default}`) with alias `logicaldoc`.
- Usage: `COMPOSE_FILE=compose.yaml:compose.proxy.yaml`.

## 8. Documentation (`docker/README.md`)

1. **Quick start**: clone, `.env`, `up -d`, open `http://127.0.0.1:8080`, log in as `admin`/`admin`, and **change the password immediately**. Changing it also re-keys `/setup` auth, which is blocked anyway.
2. **Configuration reference**: the variable tables from §5 and which keys are env-owned versus seed-once.
3. **Reverse proxy: Nginx Proxy Manager** (primary):
   - Find NPM's network name (`docker network ls`) and set `PROXY_NETWORK`.
   - Enable `compose.proxy.yaml`.
   - **Details tab:** add a Proxy Host with scheme `http`, forward host `logicaldoc`, port `8080`, Websockets Support **on**, and Block Common Exploits **on**.
   - **SSL tab:** Let's Encrypt certificate, Force SSL, HTTP/2 support.
   - **Advanced tab:**
     ```
     client_max_body_size 0;
     proxy_read_timeout 300s;
     proxy_send_timeout 300s;
     proxy_request_buffering off;
     ```
   - Set `LOGICALDOC_PUBLIC_URL=https://<domain>` and restart.
   - Troubleshooting: 502 errors (wrong network or alias), WebSocket errors (toggle off), 413 errors (body size).
4. **Other proxies**: short Caddy and plain nginx snippets, and the "proxy on another host" option (`LOGICALDOC_BIND=<LAN IP>`).
5. **Dev workflow**: the dev overlay, attaching a debugger to 5005, connecting to MariaDB on 3306, resetting the stack, and the cost of the GWT compile on rebuilds.
6. **Backup and restore**:
   - `docker compose exec db mariadb-dump …` plus a tar of the `logicaldoc-conf` and `logicaldoc-repository` volumes.
   - Why `conf` matters (encryption key, node id).
   - The restore order.
7. **Upgrade**: `git pull && docker compose up -d --build`.
   - Caveat: there's no schema migration tool, and the entrypoint only *creates* schemas. Upstream merges that change the schema need manual SQL.
8. **Bind mounts**: switching named volumes to host paths. Ownership is fixed automatically (uid 1000).

## 9. Error handling summary

| Situation | Behaviour |
|---|---|
| `DB_PASSWORD` unset | Entrypoint exits 1 before touching anything |
| DB unreachable after `DB_WAIT_SECONDS` | `DockerInit` exits 1; the container restarts under compose |
| Wrong DB credentials | `DockerInit` exits 1 at once ("database authentication failed") |
| A declared SQL resource is missing | Exits 1, naming the plugin and file |
| Schema incomplete after init | Exits 1 with instructions to drop the DB volume |
| `conf` lost but DB intact | Loud warning; startup continues |
| `/setup` requested | HTTP 403 |
| Tomcat crash or Admin → Restart | Container exits; `restart: unless-stopped` restarts it |

## 10. Verification

`docker/smoke-test.sh` runs against an isolated compose project (`-p logicaldoc-smoke`, with its own temporary `.env` and a free port). It cleans up with `down -v` on exit. Steps:

1. Generate an `.env` whose `DB_PASSWORD` contains `,` `=` `\` `$` `#` and a space, then `docker compose up -d --build` and wait for `app` to be healthy (timeout 10 min).
2. Check that `GET /login.jsp` returns 200.
3. Log in through REST with the seeded admin user, get a sid, and list the root folder.
4. Upload a small PDF and a PNG (fixtures generated by the script) to the root folder through REST. Then fetch `GET /services/rest/document/thumbnail/thumb/<path>` for each, which generates the thumbnail on demand. Check each response is a non-empty PNG (magic bytes). This proves Poppler, Ghostscript and ImageMagick work.
5. Query the DB (`docker compose exec db mariadb …`) for `ld_webservicecall`, which proves the `plugin.xml` fix.
6. Check that `GET /setup` returns 403.
7. `docker compose down` (keeping volumes) then `up -d`. Wait for healthy, check both documents are still listed, and check the app logs contain "schema present, skipping".
8. Report PASS/FAIL per step and exit non-zero on any failure.

Supporting integration tests, which also pin the Review Focus cases in the plan:

- `docker build --target tools .`: unit and `ContextProperties` round-trip tests for the init tool.
- `docker/tests/test-init.sh`: DockerInit against a throwaway MariaDB. It covers validation, a late DB, schema creation, re-runs, config loss and credential errors.
- `docker/tests/test-image.sh`: the image with plain `docker run`. It covers the entrypoint guard, ownership repair, uid, `/setup`, restart, settings persistence and config loss.
- `docker/tests/test-overlays.sh`: the rendered config and a live stack for the dev and proxy overlays.

Manual checks, done once with the built-in browser on localhost:

- The UI loads and you can log in.
- Admin → Restart brings the container back.
- The dev overlay's JDWP port accepts a debugger (`jdb -attach 5005`).
- `compose.proxy.yaml` attaches to a test external network and resolves `logicaldoc`.

Platform: the smoke test runs on arm64 (the developer's machine). amd64 is verified only when the CI follow-up exists.

## 11. Out of scope

- GHCR publishing and a CI image build
- PostgreSQL
- LibreOffice, OCR and antivirus
- clustering
- a bundled TLS proxy
- schema migrations on upgrade
- automated backups
- setting the admin password from env
- migrating existing LogicalDOC installs

## 12. Follow-ups (separate work)

1. A CI workflow that builds the image on PRs and publishes multi-arch (amd64 + arm64) images to GHCR on `master`/tags. It reuses `smoke-test.sh`.
2. Offer the `plugin.xml` SQL filename fix upstream (logicaldoc/community).
3. A PostgreSQL support and migration track (long-term goal).
