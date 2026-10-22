# Running LogicalDOC Community with Docker

This directory holds the container build and compose stack for this fork. The design is in
[the spec](../docs/superpowers/specs/2026-10-15-docker-compose-stack-design.md).

## Quick start

You need Docker with Compose v2.24.4 or newer (the dev overlay uses `!override`). The first build runs the full
Maven build, including the GWT compile: allow 5–30 minutes depending on your machine and network, and at least
8 GB of memory for Docker.

```bash
git clone https://github.com/Healdan-Project/healdan.git
cd healdan
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

To skip the build, run a published image instead (next section).

## Using a published image

CI publishes images for amd64 and arm64 to `ghcr.io/healdan-project/healdan` once publishing is
switched on (see "Continuous integration"), so a server doesn't have to run the Maven build. You still use this
repository's `compose.yaml`. In `.env`, set:

```bash
LOGICALDOC_IMAGE=ghcr.io/healdan-project/healdan:latest
```

Then pull and start:

```bash
docker compose pull
docker compose up -d
```

| Tag | Image |
|---|---|
| `latest`, `master` | the latest build of `master` |
| `sha-<commit>` | the build of one commit (first 7 characters of its hash) |
| `v…` | a release tag |

- **Pin a `v…` or `sha-…` tag** to decide when to upgrade. `latest` moves with every merge to `master`.
- **Don't add `--build`** to `docker compose up` while `LOGICALDOC_IMAGE` is set. Compose would build from your
  checkout and store the result under the published name.
- **To go back to building**, remove `LOGICALDOC_IMAGE` from `.env` and run `docker compose up -d --build`. Your
  data stays in the volumes either way.

## What runs

| Service | What | Data |
|---|---|---|
| `db` | MariaDB 11.4 | volume `logicaldoc_db-data` |
| `app` | Tomcat 11 + LogicalDOC, built from this checkout | volumes `logicaldoc_logicaldoc-conf` (`context.properties`, `log.xml`, `allowed-commands.txt`) and `logicaldoc_logicaldoc-repository` (documents, search index, user data, logs) |

What the container does at startup:

- **First start:** it writes `context.properties`, waits for MariaDB and creates the schema using the same SQL scripts as LogicalDOC's setup wizard.
- **Later starts:** it only re-applies the settings that come from `.env`.
- **The setup wizard** (`/setup`) is disabled and returns HTTP 403, because the database is configured from `.env`.

The image includes ImageMagick, Poppler and Ghostscript for thumbnails and previews. LogicalDOC only runs
external programs listed in `conf/allowed-commands.txt`. The container keeps the image's entries (`convert`, the
Poppler tools and the script Admin > Restart runs) in that file on every start; add your own lines there.
Ghostscript is deliberately not listed, because ImageMagick calls it directly.

On every start the container also adds any setting that's in the image's default `context.properties` but
missing from yours, such as one introduced by an upstream update. It never changes a value you already have.

### Healdan's first start

Healdan's plugin (`healdan-core`) sets up its own data each time the app starts, once LogicalDOC's schema exists.

1. **Migrations.** Flyway creates or upgrades Healdan's own tables (`hd_…`) and records each script it runs in
   `hd_schema_history`. This runs on every start, so a new Healdan release upgrades its tables by itself. It never
   touches LogicalDOC's tables.
2. **Seeds.** One-time steps create what Healdan needs inside LogicalDOC:
   - the **Everyone** group, with every user in it;
   - the **Healdan profile**: the template every document and folder uses (Class, Subclass, Author, Typist, the
     email fields and more), with the Class "Docs";
   - the **Templates** Store, a LogicalDOC workspace only admins can see, which holds the folder trees new
     matters are copied from;
   - Healdan's default settings, and default columns for the document lists;
   - re-indexing of existing Word, Excel, PowerPoint and email files, whose text Healdan can read.

   Each step records `seed.<name>` in the `hd_setting` table when it finishes and never runs again. If an admin
   later deletes or changes something a step made, that change stays.
3. **A failure stops the app.** If a migration or a seed step fails, the app doesn't start.
   `docker compose logs app` shows the cause and how to recover (see "Troubleshooting").

No Stores, clients or matters are seeded. An admin creates Stores, clients, templates (folder trees inside the
Templates Store) and matters in the UI. Healdan only accepts documents inside a matter.

## Known limitations

- **The container never migrates LogicalDOC's database schema** (see "Upgrading"). Healdan's own tables are the
  exception: they're upgraded on every start.

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
| `LOGICALDOC_IMAGE` | `logicaldoc-community:local` | image the app runs; set it to a published image to pull instead of building |
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
- **`DB_PASSWORD` must not start with a space or tab, contain `${`, or start with `_b64_`.** LogicalDOC's
  configuration file can't keep leading whitespace, and it expands or decodes the other two when it reads the
  file.
- **Use only Latin-1 characters and no line breaks** in `DB_PASSWORD`, `DB_USER`, `DB_NAME`, `DB_HOST` and
  `LOGICALDOC_PUBLIC_URL`. LogicalDOC stores its configuration as Latin-1, so a `€` or a non-Latin domain name
  can't be kept. The container checks what it wrote and refuses such values at startup, naming the variable.
- **MariaDB only uses `DB_PASSWORD` when it first creates its data volume.** To change it later:
  1. Open a database session and enter the current password when asked:
     ```bash
     docker compose exec db mariadb -u logicaldoc -p
     ```
     (use your `DB_USER` if you changed it).
  2. At the `MariaDB` prompt, run:
     ```sql
     SET PASSWORD = PASSWORD('new-password');
     ```
     Inside the quotes, write a `'` as `''` and a `\` as `\\`. Nothing else needs escaping: no shell is involved.
  3. Put the new password in `.env`, then run `docker compose up -d`.

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
- Maven dependencies are cached between builds, but every source change re-runs the reactor build, including the GWT compile. Changes only under `docker/`, to the `Dockerfile` or to top-level docs skip the Maven step.
- Start over with an empty database and repository with `docker compose down -v`.

**A matter to upload into (the smoke overlay).** A fresh stack has no matter, and Healdan refuses documents
anywhere else. For a stack you'll throw away, add the smoke overlay. To keep it apart from a stack you already
run, give it its own project name, image tag and port, and remove it with `down -v` and the same `-p`:

```bash
LOGICALDOC_IMAGE=healdan-test:local LOGICALDOC_PORT=8090 docker compose -p healdan-test -f compose.yaml -f docker/tests/compose.smoke.yaml up -d --build
```

The overlay sets `HEALDAN_SMOKE_FIXTURE=true`, so Healdan's first start also creates:
- the Store "Smoke";
- a template, "Smoke template", with a Documents folder;
- client 1, "Smoke Client";
- matter "1.1 – Smoke Matter", owned by `admin`, with that Documents folder. The Everyone group can read it,
  upload into it and download from it.

`docker/smoke-test.sh` uses the same overlay. Without the variable the step does nothing, so it runs on a later
start if you add the overlay then. Never use it on a server with real data.

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

When building from source:

```bash
git pull
docker compose up -d --build
```

With a published image (`LOGICALDOC_IMAGE`), change its tag in `.env` if you pinned one, then:

```bash
docker compose pull
docker compose up -d
```

To update MariaDB when you build from source, run `docker compose pull --ignore-buildable` (a plain
`docker compose pull` fails, because the local image can't be pulled).

Volumes are kept, and settings new in the image's defaults are added to your `context.properties` on start.
Healdan's own tables are upgraded on start (see "Healdan's first start"). **The container never migrates
LogicalDOC's database schema.** If an upstream merge changes it (look for changes under
`*/src/main/resources/sql/`), apply that SQL by hand. Back up first.

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

| Message | Meaning |
|---|---|
| `DB_PASSWORD is not set` | set it in `.env` |
| `DB_PASSWORD must not …` / `… cannot be stored in context.properties` | the value breaks one of the rules under "Passwords and special characters" |
| `database authentication failed` | `.env` doesn't match the password MariaDB was created with (see "Passwords") |
| `database not reachable … after 60s` | MariaDB didn't come up. Check `docker compose logs db`, or raise `DB_WAIT_SECONDS` on slow disks |
| `schema initialization incomplete` or `… its initialization did not finish` | the first-boot schema creation failed or was interrupted. Run `docker compose down`, delete the `logicaldoc_db-data` volume, and start again |
| `could not change ownership of … uid 1000 cannot write to it` | a bind-mounted share (e.g. NFS with root_squash) refuses `chown`. On the NAS, give the folder to uid/gid 1000 or make it writable for them |
| `context.properties was just created, but the database already has a schema` | the `conf` volume was lost. Restore it from backup |
| `Command … is not allowed. Add it to allowed-commands.txt` (in `repository/logs/dms.log`) | add the program to `conf/allowed-commands.txt` in the conf volume. It's re-read on every call, so no restart is needed |
| `sun.misc.Unsafe::invokeCleaner has been called by org.apache.lucene.store.MMapDirectory` (once, at startup) | harmless on JDK 25. Lucene 8.11 unmaps index files through an API that JDK 25 warns about and a later JDK removes, so check it before moving past JDK 25 |
| `Healdan's database migration failed: …` | a script for Healdan's tables failed, and the app stopped. Fix the cause, drop the `hd_` tables that script created, delete its failed row (`delete from hd_schema_history where success = 0`), and start again. Back up first |
| `Seed step '…' failed: …` | one of Healdan's first-start steps failed, and the app stopped. Fix the cause and start again. The message also gives the SQL that skips the step instead |
| `Healdan's tables support MariaDB and HSQLDB only` | Healdan's tables need MariaDB. MySQL isn't supported |

## Tests

| Command | What it checks |
|---|---|
| `docker build --target tools .` | unit tests for the init tool |
| `docker/tests/test-init.sh` | init tool against a throwaway MariaDB |
| `docker/tests/test-image.sh` | image behavior: prompt stop during init, ownership, `/setup` block, Admin > Restart, settings persistence |
| `docker/tests/test-overlays.sh` | dev and proxy overlays |
| `docker/smoke-test.sh` | end to end: build, boot, REST login, uploads into a seeded matter (`docker/tests/compose.smoke.yaml` sets `HEALDAN_SMOKE_FIXTURE`) and thumbnails, Healdan's schema, doc #s and filing rule, data surviving a restart. With `SMOKE_BUILD=0 LOGICALDOC_IMAGE=<image>` it tests an existing image instead of building |

## Continuous integration

`.github/workflows/docker-image.yml` builds the image natively on amd64 and arm64 runners for every pull request
and every push to `master` or a `v*` tag (pushes that only change docs are skipped). On each architecture it runs
the init tool's unit tests (the `tools` target), builds the image and runs `docker/smoke-test.sh` against it.
`Test (amd64)` and `Test (arm64)` are required checks on `master`, which is why pull requests always run them, even
docs-only ones.

**Publishing to GHCR is off until you switch it on.** Pushes to `master` and `v*` tags then publish the tested
image as one multi-arch image under the tags above. To switch it on:

1. Set the repository variable `GHCR_PUBLISH` to `true`, under Settings → Secrets and variables → Actions →
   Variables, or with:
   ```bash
   gh variable set GHCR_PUBLISH --body true --repo Healdan-Project/healdan
   ```
   The workflow signs in with its own `GITHUB_TOKEN`, so no secret is needed.
2. After the first publish, open the package (the Healdan-Project organization → Packages → `healdan`). If it's
   private, make it public under Package settings → Change visibility, so pulls work without `docker login`.

To publish a release, push a tag such as `v9.3.1-1`.
