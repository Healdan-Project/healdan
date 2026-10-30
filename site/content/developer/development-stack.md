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

## A Demo Firm for Screenshots

The docs' screenshots come from a demo stack that holds an invented law firm, Ashcombe Reeve LLP: six users,
seven clients, twelve matters and about sixty documents, with six weeks of their work on them. Run it as a stack
of its own:

```bash
LOGICALDOC_IMAGE=healdan-demo:local LOGICALDOC_PORT=8091 docker compose -p healdan-demo -f compose.yaml -f docker/demo/compose.demo.yaml up -d --build
```

The overlay sets `HEALDAN_DEMO_FIXTURE=true`, so Healdan's first start fills the empty stack from
`docker/demo/manifest.json`. Everything is dated over the six weeks before that start, at times in London's time
zone, which the overlay gives the stack because the firm is in England. The previews and
thumbnails of Office files follow a few minutes later; those of PDFs, emails and images are made the first time
someone opens them.

The overlay also names the project `healdan-demo`, so a forgotten `-p` can't start the demo on the `logicaldoc`
project of a production stack. Keep `-p` in the command anyway: `-p` and the `COMPOSE_PROJECT_NAME` variable both
override the overlay's name.

| User | Name | Role |
|---|---|---|
| `hashcombe` | Helen Ashcombe | Partner |
| `treeve` | Tom Reeve | Partner |
| `pnair` | Priya Nair | Associate, the day-to-day user in most screenshots |
| `jclarke` | Jamie Clarke | Paralegal |
| `cdoyle` | Chris Doyle | Legal assistant |
| `sokafor` | Sam Okafor | IT and office manager, an admin |

Each signs in with the password `Demo-Firm-2026`. Remove the stack with `down -v` and the same `-p`.

- **Changing the firm.** Edit `docker/demo/manifest.json`, and the documents' text sources in
  `docker/demo/sources/`. Then rebuild their files with `python3 docker/demo/build.py`, which runs LibreOffice
  inside a built Healdan image: `healdan-demo:local`, which the command above builds, or any other
  built image that `--image` names. It stops and lists any file that LibreOffice didn't write. Commit the sources
  and files together.
- **Restart once for the sign-in page.** After the first start the sign-in page says "The application has to be
  restarted", because the plugins ask for a restart on a fresh stack. `docker compose -p healdan-demo restart app`
  clears it, and doesn't fill the firm a second time.
- **Browsing adds to it.** Opening a document logs a Viewed event dated then, so a stack someone has browsed
  shows today's date in some documents' History. Take screenshots from a stack that was used as little as you can.
- **It refuses real data.** The step runs only on a repository with no documents in it and no users besides CE's
  own `admin` and `_system`, and a failure stops startup. A firm that failed partway leaves its first users
  behind, so the next start stops at that check. Start over with `down -v`.

{{< callout type="warning" >}}
Never use the demo overlay on a server with real data.
{{< /callout >}}
