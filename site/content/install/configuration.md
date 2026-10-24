---
title: Configuration
weight: 3
---

Healdan's stack reads its settings from `.env`, in the folder you run `docker compose` from. Only `DB_PASSWORD`
is required. After changing `.env`, run `docker compose up -d`.

## Settings

| Setting | Default | What it does |
|---|---|---|
| `DB_PASSWORD` | (required) | The MariaDB password for `DB_USER` |
| `DB_NAME` | `logicaldoc` | The database's name |
| `DB_USER` | `logicaldoc` | The database user |
| `DB_HOST` / `DB_PORT` | `db` / `3306` | Change these only to use a MariaDB outside the stack |
| `LOGICALDOC_PUBLIC_URL` | unset | The public HTTPS address when Healdan is behind a proxy, for example `https://docs.example.org`. Used in links and emails |
| `LOGICALDOC_BIND` | `127.0.0.1` | The host address the app is published on. `127.0.0.1` means this machine only |
| `LOGICALDOC_PORT` | `8080` | The host port the app is published on |
| `CATALINA_OPTS` | `-Xmx2g` | Java options, such as the memory limit |
| `TZ` | `UTC` | The time zone |
| `DB_WAIT_SECONDS` | `60` | How long the app waits for the database when it starts |
| `COMPOSE_FILE` | unset | Picks overlays, such as the proxy overlay in [Reverse Proxy](../reverse-proxy/) |
| `PROXY_NETWORK` | `npm_default` | With the proxy overlay: the proxy's Docker network |

## What `.env` Controls

`.env` owns these settings, and writes them into `context.properties` on every start:

- the database connection: `jdbc.url`, `jdbc.username` and `jdbc.password`;
- the public address (`server.url`), when `LOGICALDOC_PUBLIC_URL` is set.

Change those in `.env`. Everything else is set in the app's admin screens, and survives restarts, recreating the
containers and rebuilding the image.

A few settings have no screen. They're in the configuration volume's `context.properties`: change them there, then
restart the app with `docker compose restart app`. The pages that need one say so, such as
[the sign-in throttle](../../admin/healdan-settings/#changing-the-limits).

## Passwords and Special Characters

- **Quote values in `.env`.** Inside single quotes nothing is interpreted: `DB_PASSWORD='p@ss $word #1'`.
- **A password containing a single quote** needs double quotes instead, with every `$` written as `$$`.
- **`DB_PASSWORD` must not start with a space or a tab, contain `${`, or start with `_b64_`.** The configuration
  file can't keep leading spaces, and it expands or decodes the other two when it's read.
- **Use only Latin-1 characters, and no line breaks,** in `DB_PASSWORD`, `DB_USER`, `DB_NAME`, `DB_HOST` and
  `LOGICALDOC_PUBLIC_URL`. The configuration file is stored as Latin-1, so a `€` or a non-Latin domain name can't
  be kept. The app checks what it wrote, and refuses such a value when it starts, naming the setting.

## Changing the Database Password

MariaDB only reads `DB_PASSWORD` when it first creates its data volume. To change the password later:

{{% steps %}}

### Open a Database Session

Run this, and enter the current password when asked. Use your `DB_USER` if you changed it.

```bash
docker compose exec db mariadb -u logicaldoc -p
```

### Set the New Password

At the `MariaDB` prompt:

```sql
SET PASSWORD = PASSWORD('new-password');
```

Inside the quotes, write a `'` as `''` and a `\` as `\\`. Nothing else needs escaping.

### Update `.env`

Put the new password in `.env`, then run `docker compose up -d`.

{{% /steps %}}
