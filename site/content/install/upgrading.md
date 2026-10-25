---
title: Upgrading
weight: 7
---

{{< callout type="info" >}}
These docs describe Healdan as it is on its `master` branch. If your server runs an older build, some pages may
describe features or settings it doesn't have yet.
{{< /callout >}}

[Back up](../backup-and-restore/) first. Then, from the folder you cloned Healdan into:

```bash
git pull
docker compose up -d --build
```

To update MariaDB too, run `docker compose pull --ignore-buildable` before that. A plain `docker compose pull`
fails, because the app's image is built here and can't be pulled.

## What an Upgrade Changes

- **Your data stays.** The volumes are kept.
- **New default settings are added** to your `context.properties` when the app starts. Values you already have
  aren't changed.
- **Healdan's own tables are upgraded** when the app starts. If an upgrade step fails, the app stops instead of
  running on a half-upgraded database, and the log says which step failed. See
  [Troubleshooting](../troubleshooting/).
- **LogicalDOC's tables aren't.** See [Known Limitations](../known-limitations/).
