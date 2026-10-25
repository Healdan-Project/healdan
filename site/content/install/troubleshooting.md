---
title: Troubleshooting
weight: 9
---

Start with the app's log:

```bash
docker compose logs app
```

Healdan's own log is `repository/logs/dms.log`, in the repository volume.

## Messages and What They Mean

| Message | What it means |
|---|---|
| `DB_PASSWORD is not set` | Set it in `.env`. |
| `DB_PASSWORD must not …` or `… cannot be stored in context.properties` | The value breaks one of the rules in [Passwords and Special Characters](../configuration/#passwords-and-special-characters). |
| `database authentication failed` | `.env` doesn't match the password MariaDB was created with. See [Changing the Database Password](../configuration/#changing-the-database-password). |
| `database not reachable … after 60s` | MariaDB didn't come up. Check `docker compose logs db`, or raise `DB_WAIT_SECONDS` on slow disks. |
| `schema initialization incomplete` or `… its initialization did not finish` | Creating the schema on first start failed or was interrupted. Run `docker compose down`, delete the `logicaldoc_db-data` volume, and start again. |
| `could not change ownership of … uid 1000 cannot write to it` | A [host folder](../host-folders/) refuses the change of owner, as an NFS share with `root_squash` does. On the NAS, give the folder to user and group id 1000, or make it writable for them. |
| `context.properties was just created, but the database already has a schema` | The configuration volume was lost. [Restore it](../backup-and-restore/) from a backup. |
| `Command … is not allowed. Add it to allowed-commands.txt` (in `dms.log`) | Add the program to `conf/allowed-commands.txt` in the configuration volume. It's read again on every call, so you don't need to restart. |
| `sun.misc.Unsafe::invokeCleaner has been called by org.apache.lucene.store.MMapDirectory` (once, at start) | Harmless. The search library uses a Java feature that Java 25 warns about. |
| `Healdan's database migration failed: …` | An upgrade of Healdan's tables failed, and the app stopped. Back up first. Fix the cause, drop the `hd_` tables that step created, delete its failed row (`delete from hd_schema_history where success = 0`), and start again. |
| `Seed step '…' failed: …` | One of Healdan's one-time setup steps failed, and the app stopped. Fix the cause and start again. The message also gives the SQL that skips the step instead. |
| `Healdan's tables support MariaDB and HSQLDB only` | Healdan needs MariaDB. MySQL isn't supported. |

## Office Previews Say "Not Available"

See [Office Previews](../what-runs/#office-previews) in What Runs.
