---
title: Backup and Restore
weight: 5
---

Back up the database and both of the app's volumes together. The configuration volume holds the encryption key
and the node id, so documents and a database restored without their matching configuration lose their encrypted
settings.

## Backing Up

Run these from the folder with Healdan's `compose.yaml`. The app stops for the length of the backup.

```bash
docker compose stop app
docker compose exec db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb-dump -u"$MARIADB_USER" --single-transaction "$MARIADB_DATABASE"' > logicaldoc-db.sql
docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
  -v "$PWD":/backup busybox tar czf /backup/logicaldoc-files.tgz /conf /repository
docker compose start app
```

That leaves two files: `logicaldoc-db.sql`, the database, and `logicaldoc-files.tgz`, the configuration and the
documents. Keep them together.

## Restoring onto a Fresh Stack

{{% steps %}}

### Start the Database

```bash
docker compose up -d db
```

Wait until `docker compose ps` shows it healthy.

### Load the Database

```bash
docker compose exec -T db sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" mariadb -u"$MARIADB_USER" "$MARIADB_DATABASE"' < logicaldoc-db.sql
```

### Restore the Files

```bash
docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
  -v "$PWD":/backup busybox tar xzf /backup/logicaldoc-files.tgz -C /
```

### Start Everything

```bash
docker compose up -d
```

Compose may warn that the app's two volumes already exist. That's expected: it uses them.

{{% /steps %}}
