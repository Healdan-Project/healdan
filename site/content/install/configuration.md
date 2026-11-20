---
title: Configuration
weight: 3
---

Healdan's stack reads its settings from `.env`, in the folder you run `docker compose` from. Only `DB_PASSWORD`
is required. After changing `.env`, run `docker compose up -d`.

## Settings

| Setting | Default | What it does |
|---|---|---|
| `DB_PASSWORD` | (required) | The password of `DB_USER` |
| `DB_NAME` | `logicaldoc` | The database's name. Not `postgres`, `template0` or `template1`, which PostgreSQL has already |
| `DB_USER` | `logicaldoc` | PostgreSQL's role for the app, which owns the database. Not `postgres`, PostgreSQL's administrator, and not a name beginning `pg_` |
| `DB_HOST` / `DB_PORT` | `db` / `5432` | Change these only to use a PostgreSQL outside the stack: see [A PostgreSQL of Your Own](../what-runs/#a-postgresql-of-your-own) |
| `LOGICALDOC_PUBLIC_URL` | unset | The public HTTPS address when Healdan is behind a proxy, for example `https://docs.example.org`. Used in links and emails |
| `LOGICALDOC_BIND` | `127.0.0.1` | The host address the app is published on. `127.0.0.1` means this machine only |
| `LOGICALDOC_PORT` | `8080` | The host port the app is published on |
| `LOGICALDOC_TRUSTED_PROXIES` | the app's Docker networks | The IP addresses or ranges Healdan believes `X-Forwarded-For` and `X-Forwarded-Proto` from, separated by commas: your reverse proxy's. Set it when the proxy runs on another machine. See [Reverse Proxy](../reverse-proxy/) |
| `CATALINA_OPTS` | `-Xmx2g` | Java options, such as the memory limit |
| `TZ` | `UTC` | The time zone |
| `DB_WAIT_SECONDS` | `60` | How long the app waits for the database when it starts |
| `COMPOSE_FILE` | unset | Picks overlays, such as the proxy overlay in [Reverse Proxy](../reverse-proxy/) |
| `PROXY_NETWORK` | `npm_default` | With the proxy overlay: the proxy's Docker network |

## What `.env` Controls

`.env` owns these settings, and writes them into `context.properties` on every start:

- the database connection: `jdbc.url`, `jdbc.username` and `jdbc.password`;
- the public address (`server.url`), when `LOGICALDOC_PUBLIC_URL` is set.

Change those in `.env`. Healdan also writes four settings of its own on every start, so changing them yourself does
nothing: `store`, which makes its storage keep the documents' files, and `jdbc.driver`, `jdbc.dbms` and
`hibernate.dialect`, which name PostgreSQL. Everything else is set in the app's admin screens, and survives restarts,
recreating the containers and rebuilding the image.

## Settings Without a Screen

A few settings have no screen. They're in the configuration volume's `context.properties`. The pages that need one
say so, such as [the sign-in throttle](../../admin/healdan-settings/#changing-the-limits).

Stop the app before you change the file, and start it again afterwards: while the app runs, a settings screen that
saves rewrites the whole file from what the app read when it started. From the folder with Healdan's
`compose.yaml`:

```bash
docker compose stop app
docker run --rm -it -v logicaldoc_logicaldoc-conf:/conf busybox vi /conf/context.properties
docker compose start app
```

- **The volume's name** is the stack's project name, `logicaldoc`, then `_logicaldoc-conf`, as in
  [Backup and Restore](../backup-and-restore/).
- **In `vi`,** find a setting with `/` and its name, press `i` to edit, then `Esc` and `:wq` to save and quit, or
  `:q!` to quit without saving. The app writes each setting as `name=value`.
- **The file is Latin-1.** Write only Latin-1 characters in it, as in `.env`.
- **The file's owner** doesn't matter: the app takes the volume back when it starts.

## Passwords and Special Characters

- **Quote values in `.env`.** Inside single quotes nothing is interpreted: `DB_PASSWORD='p@ss $word #1'`.
- **A password containing a single quote** needs double quotes instead, with every `$` written as `$$`.
- **`DB_PASSWORD` must not start with a space or a tab, contain `${`, or start with `_b64_`.** The configuration
  file can't keep leading spaces, and it expands or decodes the other two when it's read.
- **Use only Latin-1 characters, and no line breaks,** in `DB_PASSWORD`, `DB_USER`, `DB_NAME`, `DB_HOST` and
  `LOGICALDOC_PUBLIC_URL`. The configuration file is stored as Latin-1, so a `€` or a non-Latin domain name can't
  be kept. The app checks what it wrote, and refuses such a value when it starts, naming the setting.

## Changing the Database Password

The stack's PostgreSQL reads `DB_PASSWORD` only when it first creates its data volume, to make the app's role. To
change the password later:

{{% steps %}}

### Open a Database Session

From the folder with Healdan's `compose.yaml`:

```bash
docker compose exec db psql -U postgres
```

This signs in as PostgreSQL's administrator, `postgres`, which needs no password inside the database's container,
and can't sign in from anywhere else.

### Set the New Password

At the `postgres=#` prompt, run this, with your `DB_USER` if you changed it, and type the new password twice. Nothing
shows as you type.

```text
\password logicaldoc
```

psql changes it with `alter role`, and sends PostgreSQL the password already hashed, so it needs no escaping, and the
password itself never reaches a log. Then type `\q` to leave.

### Update `.env`

Put the new password in `.env`, quoted as [Passwords and Special Characters](#passwords-and-special-characters)
says, then run `docker compose up -d`.

{{% /steps %}}

On a PostgreSQL of your own, change the role's password there, in `psql` with `\password` as above, or with
`alter role logicaldoc password '…';`, writing a `'` inside the quotes as `''`. Then update `.env` the same way.
