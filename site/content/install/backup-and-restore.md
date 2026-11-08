---
title: Backup and Restore
weight: 8
---

Back up the database and the app's configuration and repository volumes together. The configuration volume holds
the encryption key, the node id, the key that seals the secrets for signing in through a provider and for S3
storage backends, and in `healdan-storage-keys` the key file of each encrypted storage backend. So documents and a
database restored without their matching configuration lose their encrypted settings, and an encrypted backend's
files can be opened only with its key file or its recovery key: without both, they're lost for good. Keep the
recovery keys apart from the backups (see [Recovery Keys](#recovery-keys)). The app's third volume, the cache, holds
only copies, so it needs no backup.

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
documents. Keep them together. The app's moves between [storage backends](../../admin/storage-backends/) stop with
it, and carry on once it's started again.

## What the Backup Holds

| File | What it holds |
|---|---|
| `logicaldoc-db.sql` | the database: documents, matters, users and settings, each storage backend's settings with its sealed S3 secret, and the record of which backend holds each file |
| `logicaldoc-files.tgz` | the configuration volume, the storage backends' key files and the key that seals secrets among it; and the repository volume: Original's files, staging (saves into a Store on S3 not uploaded yet), Original's `.held` folder of [held copies](../../admin/storage-backends/held-deletes/), the search index and the logs |

Neither holds:

- **The S3 buckets.** Their files stay in the buckets, which the database names. For a copy away from both the
  office and the buckets, see [An Offsite Copy](#an-offsite-copy).
- **The read cache,** which holds only copies of the buckets' files.
- **Other local backends' folders,** such as a share on a NAS. Back each up where it is, or add it to the `tar`
  command. For one that `compose.override.yaml` mounts from the host's `/srv/archive`:

  ```bash
  docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v logicaldoc_logicaldoc-repository:/repository \
    -v /srv/archive:/archive -v "$PWD":/backup busybox tar czf /backup/logicaldoc-files.tgz /conf /repository /archive
  ```

  Restore it with the same `-v /srv/archive:/archive`.

## Restoring Finds Every File

Healdan never deletes a file from a backend at once. It holds every deleted file for the **holding period**, 30 days
to begin with: in the bucket, or in a local backend's `.held` folder, where Healdan looks for a file that's missing
from its place. So a database restored from a backup younger than the holding period finds every file it names, on
every backend. **Keep the holding period at least as long as the oldest backup you'd restore,** on the **Storage**
tab: see [Held Deletes and the Holding Period](../../admin/storage-backends/held-deletes/).

One kind of file comes back newer than the backup: a file LogicalDOC rewrote in place since, such as a version whose
file was replaced with **Replace file**. It comes back with its newest bytes, as it would on a server without storage
backends.

## Recovery Keys

Each encrypted storage backend has a recovery key, which Healdan shows when the backend is added. Keep every one in
the firm's password manager, apart from the backups. The backups hold the key files, so a recovery key kept beside
them is lost with them. If a key file is lost, the recovery key puts it back: see
[Encryption and Recovery Keys](../../admin/storage-backends/encryption-and-recovery-keys/#putting-a-lost-key-file-back).

## The Key for Sealed Secrets

Healdan seals three kinds of secret before it puts them in the database: each sign-in provider's client secret, the
refresh tokens of the Healdan Helpers that signed in through a provider, and the secret of each S3 storage backend's
access key. The key that seals them is `conf/healdan-secret.key` in the configuration volume. Healdan makes it the
first time it has a secret to seal, and only the app's user can read it. A server that uses neither a provider nor an
S3 storage backend never makes one.

A database dump on its own holds no readable secret. The backup above takes the key too, in
`logicaldoc-files.tgz`, so keep the two files where only administrators can read them.

### If the Key File Goes Missing

Once secrets are sealed, Healdan never makes another key on its own: a new key would leave every secret sealed with
the old one unreadable for good, even after the file came back. While the file is missing or empty, each provider
counts as off, as its client secret can't be read, and the log, `repository/logs/dms.log`, says what to do.
Helpers already signed in keep working, and so do S3 storage backends until Healdan restarts or a backend is saved
or tested on the Storage tab: then a backend whose secret can't be read is failing, and the tab says to enter its
secret again. If someone types a provider's client secret on the Sign-In tab, or a backend's secret on the Storage
tab, meanwhile, Healdan says the key file is missing and asks before it makes a new key: say no, and restore the
file.

Restore the file from the backup, and everything works again with no restart: providers at once, and an S3 storage
backend that was failing at its next check, within a minute or two, or when you press Test on the Storage tab:

```bash
docker run --rm -v logicaldoc_logicaldoc-conf:/conf -v "$PWD":/backup busybox \
  tar xzf /backup/logicaldoc-files.tgz -C / conf/healdan-secret.key
```

### If the Key Is Lost

If there's no copy of the key, or a database is restored without the key that matches it, the sealed secrets read
as unset and nothing else is lost:

- a provider whose client secret can't be read stays off until you enter the secret again on the Sign-In tab of
  Healdan Settings;
- an S3 storage backend whose secret can't be read is failing until you enter its secret again on the Storage tab.
  Meanwhile, saves into its Stores wait in staging, and a file that is only in its bucket can't be opened;
- each Healdan Helper that signed in through a provider is asked to sign in again.

When you save the first secret, on either tab, Healdan says the key file is missing and asks whether to make a new
key: make one, then enter each other provider's client secret and each other S3 backend's secret.

Accounts, documents, the files in S3 buckets and the links between accounts and providers are not affected, and
`admin` still signs in with its password.

## Restoring onto a Fresh Stack

**If the server you backed up is still running,** as when you restore onto a second server to try a backup, stop its
app first, and keep it stopped while the second runs. The two would share the same buckets, with the same keys and
the same install id, so each would move, hold and purge files the other relies on. From the first server's folder
with Healdan's `compose.yaml`:

```bash
docker compose stop app
```

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

### Test the Storage Backends

Once it has started, sign in as an administrator, open the **Storage** tab of **Administration → Healdan →
Settings**, and press **Test** on each backend. A backend that fails says why: a key file that's missing or isn't its
own wants its recovery key (see
[Encryption and Recovery Keys](../../admin/storage-backends/encryption-and-recovery-keys/#putting-a-lost-key-file-back)),
and a secret that can't be read, as after a restore without the key that sealed it, wants the secret typed again
with **Edit**.

{{% /steps %}}

## An Offsite Copy

The two files are on the server, or wherever you copy them. If the office burns down, the files in the buckets
survive, but they're of no use without the database, the configuration and the keys. So send each night's two files
offsite too: here, to a second Wasabi bucket with [restic](https://restic.net), which encrypts them with a password
of its own before they leave. The commands run restic's official image, so there's nothing to install.

{{% steps %}}

### Make a Second Bucket and a User for It

At Wasabi, make a bucket for the backups, best in another region than the storage backend's, and a user that can use
only that bucket, as [Cloud Storage](../cloud-storage/#make-a-user-for-healdan) does for Healdan's, with the second
bucket's name in the policy. Don't reuse Healdan's user: then a stolen Healdan key can't touch the backups.

### Write `restic.env`

In the folder that holds the two files, write `restic.env` with the bucket's endpoint and name, a long password of
its own for restic, and the user's keys, and let only yourself read it:

```text {filename="restic.env"}
RESTIC_REPOSITORY=s3:https://s3.eu-west-3.wasabisys.com/ashcombe-reeve-offsite
RESTIC_PASSWORD=<a long password of its own>
AWS_ACCESS_KEY_ID=<the backup user's access key>
AWS_SECRET_ACCESS_KEY=<its secret key>
AWS_DEFAULT_REGION=eu-west-3
```

```bash
chmod 600 restic.env
```

Keep `RESTIC_PASSWORD` in the firm's password manager too: without it, nobody can read the offsite copy.

### Start the Repository, Once

```bash
docker run --rm --env-file restic.env restic/restic:0.18.1 init
```

### Send Each Backup

After each backup, from the same folder:

```bash
docker run --rm --env-file restic.env -v "$PWD":/backup:ro restic/restic:0.18.1 backup --host healdan /backup/logicaldoc-db.sql /backup/logicaldoc-files.tgz
docker run --rm --env-file restic.env restic/restic:0.18.1 forget --keep-daily 30 --prune
```

restic sends only what changed since the last copy. `--host healdan` names every copy alike, as each container has a
new name, so `forget` sees them as one series. It keeps the last copy of each of the last 30 days that had one, to
match a 30-day holding period, and deletes the rest: an older copy names files the holding period has let go. To list
the copies:

```bash
docker run --rm --env-file restic.env restic/restic:0.18.1 snapshots
```

{{% /steps %}}

To restore, get the files back from the offsite copy, into the folder you run this in:

```bash
docker run --rm --env-file restic.env -v "$PWD":/restore restic/restic:0.18.1 restore latest --target /restore
```

They land in a `backup` folder beneath it, as `backup/logicaldoc-db.sql` and `backup/logicaldoc-files.tgz`. For an
older copy, put its ID from `snapshots` in place of `latest`. Then restore them onto a fresh stack as above, from the
`backup` folder.
