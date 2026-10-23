---
title: Quick Start
weight: 1
---

Healdan runs as a Docker Compose stack on one server: a MariaDB database and the Healdan app. This page takes you
from nothing to signing in.

You need Docker with Compose v2.24.4 or newer, and at least 8 GB of memory for Docker. The first start builds
Healdan from source, which takes 5 to 30 minutes depending on your machine and network.

{{% steps %}}

### Get the Code

```bash
git clone https://github.com/Healdan-Project/healdan.git
cd healdan
cp .env.example .env
```

### Set the Database Password

Open `.env` and set `DB_PASSWORD`, in single quotes:

```bash {filename=".env"}
DB_PASSWORD='choose-a-strong-password'
```

[Configuration](../configuration/) lists the other settings, and the rules for passwords with special characters.

### Build and Start

```bash
docker compose up -d --build
docker compose logs -f app
```

### Sign In

When the log shows `Server startup`, open http://127.0.0.1:8080 and sign in as `admin` with the password `admin`.

{{< callout type="warning" >}}
Change the `admin` password straight away.
{{< /callout >}}

{{% /steps %}}

By default Healdan is only reachable from the machine running Docker. To use it from other machines, put a
[reverse proxy](../reverse-proxy/) in front of it.

## What Happens on First Start

On its first start the app creates LogicalDOC's database schema. Then, on this and every later start, Healdan sets
up its own data:

1. **Healdan's tables.** Healdan creates or upgrades its own tables, whose names start `hd_`. It never changes
   LogicalDOC's tables.
2. **One-time setup steps.** These create what Healdan needs inside LogicalDOC:
   - the **Everyone** group, which every user except read-only ones joins and can't leave;
   - the **Healdan profile**, the set of properties every document and folder carries (Class, Subclass, Author,
     Typist, the email fields and more), with the Class "Docs";
   - the **Templates** Store, which only admins can see. It holds the folder trees new matters are copied from;
   - Healdan's default settings, and the default columns of the document lists.

   Each step runs once. If an admin later changes or deletes something a step made, the change stays.
3. **A failure stops the app.** If either fails, Healdan doesn't start, and `docker compose logs app` says why.
   See [Troubleshooting](../troubleshooting/).

Healdan doesn't create any Stores, clients or matters. An admin creates them in the app, and Healdan only accepts
documents inside a matter. The [Admin Guide](../../admin/) covers that.
