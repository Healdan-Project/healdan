---
title: Running a Development Stack
weight: 2
---

The [Docker stack](../../install/quick-start/) has a development overlay. Turn it on in `.env`:

```bash
echo 'COMPOSE_FILE=compose.yaml:compose.dev.yaml' >> .env
docker compose up -d --build
```

## What the Overlay Does

- **Publishes the app on all interfaces,** so other devices on your network can try a branch at
  `http://<this machine's IP>:8080`. There's no TLS, so use it only on a network you trust.
- **Starts Tomcat with a Java debugger on `127.0.0.1:5005`.** Attach with IntelliJ (Run → Edit Configurations →
  Remote JVM Debug, `localhost:5005`) or with `jdb -attach localhost:5005`. To debug from another machine, set
  `JDWP_BIND=0.0.0.0`, but anyone who can reach the port can run code in the JVM.
- **Publishes MariaDB on `127.0.0.1:3306`,** or on `DEV_DB_PORT` if that port is busy, with the credentials in
  `.env`.

## Rebuilding and Starting Over

- After a code change, rebuild with `docker compose up -d --build app`.
- Maven's dependencies are cached between builds, but every source change runs the whole build again, the GWT
  compile included. Changes only under `docker/`, to the `Dockerfile` or to top-level docs skip the Maven step.
- To start over with an empty database and repository, run `docker compose down -v`.

## A Matter to Upload Into

Healdan refuses documents outside a matter, and a fresh stack has none. For a stack you'll throw away, add the
smoke overlay. To keep it apart from a stack you already run, give it its own project name, image tag and port:

```bash
LOGICALDOC_IMAGE=healdan-test:local LOGICALDOC_PORT=8090 docker compose -p healdan-test -f compose.yaml -f docker/tests/compose.smoke.yaml up -d --build
```

Remove it with `down -v` and the same `-p`. The overlay sets `HEALDAN_SMOKE_FIXTURE=true`, so Healdan's first
start also creates:

- the Store "Smoke";
- a template, "Smoke template", with a Documents folder;
- client 1, "Smoke Client";
- matter "1.1 – Smoke Matter", owned by `admin`, with that Documents folder. The Everyone group can read it,
  upload into it and download from it.

Without the variable that step does nothing, so it runs on a later start if you add the overlay then.

{{< callout type="warning" >}}
Never use the smoke overlay on a server with real data.
{{< /callout >}}
