---
title: Host Folders Instead of Volumes
weight: 7
---

By default the stack keeps its data in Docker volumes. To keep it in folders on the host instead, for example on
a NAS share, create `compose.override.yaml` next to `compose.yaml`:

```yaml {filename="compose.override.yaml"}
services:
  app:
    volumes:
      - /srv/logicaldoc/conf:/opt/logicaldoc/conf
      - /srv/logicaldoc/repository:/opt/logicaldoc/repository
  db:
    volumes:
      - /srv/logicaldoc/db:/var/lib/mysql
```

Compose reads `compose.override.yaml` by itself, unless `COMPOSE_FILE` is set in `.env`. If it is, add the file to
that list.

When it starts, the app hands its two folders to user and group id 1000. MariaDB manages its own folder. If a
share refuses the change of owner, as an NFS share with `root_squash` does, see
[Troubleshooting](../troubleshooting/).
