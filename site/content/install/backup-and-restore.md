---
title: Backup and Restore
weight: 6
---

Back up the database and both of the app's volumes together. The configuration volume holds the encryption key,
the node id and the key that seals the secrets for signing in through a provider, so documents and a database
restored without their matching configuration lose their encrypted settings.

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

## The Key for Sign-In Secrets

When you set up signing in through a provider, Healdan seals two kinds of secret before it puts them in the
database: each provider's client secret, and the refresh tokens of the Healdan Helpers that signed in through a
provider. The key that seals them is `conf/healdan-secret.key` in the configuration volume. Healdan makes it the
first time it has a secret to seal, and only the app's user can read it. A server that doesn't use a provider never
makes one.

A database dump on its own holds no readable secret. The backup above takes the key too, in
`logicaldoc-files.tgz`, so keep the two files where only administrators can read them.

If the key is lost, or a database is restored without the key that matches it, the sealed secrets read as unset and
nothing else is lost:

- a provider whose client secret can't be read stays off until you enter the secret again in Healdan Settings;
- each Healdan Helper that signed in through a provider is asked to sign in again.

Accounts, documents and the links between accounts and providers are not affected, and `admin` still signs in with
its password.

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
