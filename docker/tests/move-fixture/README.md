# The Move's Fixture

`docker/tests/test-move.sh` moves this fixture from MariaDB to PostgreSQL with the `move-from-mariadb` command, and
starts the app on the result (the PostgreSQL spec's §10.2). It's an install on MariaDB:

- `mariadb.sql.gz`: its database, from `mariadb-dump --single-transaction`;
- `volumes.tgz`: its conf and repository volumes, as `conf/` and `repository/`, without the logs, which the app writes
  afresh at every start (the folder is there, empty); the search index is in it, as it's small;
- `expected.env`: what the test expects of them: doc #s, ids, row counts, the files' SHA-256s and the dates;
- `make.sh`: what made them.

## It's Frozen

It was made on 2026-11-20 (UTC), from commit `3390247e0`, `master`'s tip then, which runs on MariaDB at schema
version 12, with that commit's own image:

```bash
git worktree add ../mariadb-era 3390247e0
docker build -t pgb-mariadb:local ../mariadb-era
MOVE_FIXTURE_CHECKOUT=../mariadb-era MOVE_FIXTURE_IMAGE=pgb-mariadb:local \
  MOVE_FIXTURE_PROJECT=pgb-fixture MOVE_FIXTURE_PORT=38091 docker/tests/move-fixture/make.sh
```

MariaDB's side never changes again, so neither does the fixture. It's made again only if a MariaDB migration or a
change to LogicalDOC's tables reaches `master` before the switch to PostgreSQL merges: then run `make.sh` as above from
the new last MariaDB commit, and commit the three files it writes. That has happened once: the first fixture, of
2026-11-19 from `84c802acb`, was at version 11, and the Admin app's `V12__admin.sql` reached `master` the same day. A
MariaDB migration also moves the move's `SOURCE_VERSION` and `TARGET_VERSION` on by one, and the version of the
script that widens LogicalDOC's text columns, which comes after every script MariaDB ran.

## What's in It

`make.sh` runs the MariaDB commit's stack with its smoke overlay, and the sign-in overlay's settings without its
Keycloak. Then:

- **Through the API, as `apiuser`:** three documents in matter 1.1, a second version of doc # 1, doc # 3 deleted (a
  Trash item), and an email, with an accented subject and a Message-ID.
- **A Store on a storage backend:** the Store Dépôt Local, made through LogicalDOC's REST service with Everyone
  reading it; matter 1.5, Dépôt Müller, in it, through New Matter's service; the encrypted local backend Move Local,
  added, its recovery key confirmed and given to the Store through the Storage tab's service; and doc # 5 filed in
  the matter, which the mover takes to the backend. The backend's folder is in the repository volume, and its key
  file in the conf volume.
- **A sealed secret:** the sign-in provider `smoke`, whose client secret the `smoke-signin` seed seals under the conf
  volume's key. `GET /api/v1/server` lists it only while that secret opens.
- **With SQL, the app stopped:**
  - `apiuser`'s avatar, a data URL of 6,000 characters, where LogicalDOC's generic script has 4,000;
  - three history dates of doc # 1 at `.069`, `.760` and `.000` of a second, and its version 1.0's plain `datetime`;
  - every client address the app recorded, here the Docker network's gateway, put to `127.0.0.1`.
- **The Admin app's tables,** which `V12__admin.sql` made: the administration record of the client and the matters
  the stack made, the app's own rows; an error the app logged by itself (no mail server answers), with a stack of many
  lines; and, with SQL, an error marked resolved, with an accented message, first seen at `.069` of a second, and its
  counts for two hours.
- **Counters past their first values:** the sequences of every table written to, and the identity columns of
  `hd_trash`, `hd_admin_event` and `hd_error`, so that the test can tell the next ids follow the old ones.

## It's Public

It holds only invented data: the smoke stack's published test passwords (`apiuser`'s is in `expected.env`), the
database password `make.sh` gives, and keys made for this stack alone. `make.sh` ends by checking that no address but
`127.0.0.1` (and LogicalDOC's multicast default, `228.8.8.8`) and not the machine's name is in what it wrote.
