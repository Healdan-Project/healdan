---
title: Host Folders Instead of Volumes
weight: 10
---

By default the stack keeps its data in Docker volumes. To keep it in folders on the host instead, for example on
a NAS share, create `compose.override.yaml` next to `compose.yaml`:

```yaml {filename="compose.override.yaml"}
services:
  app:
    volumes:
      - /srv/logicaldoc/conf:/opt/logicaldoc/conf
      - /srv/logicaldoc/repository:/opt/logicaldoc/repository
      - /srv/logicaldoc/cache:/opt/logicaldoc/cache
  db:
    volumes:
      - /srv/logicaldoc/db:/var/lib/mysql
```

Compose reads `compose.override.yaml` by itself, unless `COMPOSE_FILE` is set in `.env`. If it is, add the file to
that list.

When it starts, the app hands its three folders to user and group id 1000: the configuration, the repository and
the read cache of [storage backends](../../admin/storage-backends/the-read-cache/), which holds only copies. MariaDB
manages its own folder. If a share refuses the change of owner, as an NFS share with `root_squash` does, see
[Troubleshooting](../troubleshooting/).

## Folders for More Storage Backends

A [storage backend](../../admin/storage-backends/) that's a local folder other than Original's, such as a share on a
NAS for one Store's files, is a folder you mount into the app's container in `compose.override.yaml`:

```yaml {filename="compose.override.yaml"}
services:
  app:
    volumes:
      - /srv/archive:/mnt/archive
```

Then add the backend with its path in the container, `/mnt/archive`: see
[Adding a Backend](../../admin/storage-backends/adding-a-backend/#a-local-folder).

- **Make it writable by user and group id 1000,** on the host or the NAS: the app runs as uid 1000, and doesn't
  change the owner of a backend's folder, as an NFS share with `root_squash` would refuse. For a folder on the host,
  `sudo chown -R 1000:1000 /srv/archive` does it. On a NAS, give the share's folder to uid 1000, or map the share's
  users to it.
- **Mount the share on the host before Docker starts,** as Docker otherwise mounts the empty folder beneath it. Healdan
  writes a marker in the backend's folder, and refuses to save into one without it, so an unmounted share fails
  rather than filling up the host's disk: see
  [When a Backend Fails](../../admin/storage-backends/when-a-backend-fails/#a-local-folder-that-fails).
- **Back it up** where it is, or add it to the backup's `tar` command: see
  [Backup and Restore](../backup-and-restore/#what-the-backup-holds).
