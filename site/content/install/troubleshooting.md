---
title: Troubleshooting
weight: 11
---

Start with the app's log:

```bash
docker compose logs app
```

Healdan's own log is `repository/logs/dms.log`, in the repository volume.

## The Build Stops with "Illegal Instruction"

When `docker compose up -d --build` stops while building `healdan-web` with these lines, the processor lacks
instructions the build needs:

```text
Illegal instruction (core dumped)
npm error code 132
```

The User app is built with Vite, whose bundler, Rolldown, runs a native program that needs AVX2 (the x86-64-v3 level)
on an x86-64 processor. Only the build needs it: the image Healdan runs from doesn't. The app that was running keeps
running, as Compose replaces it only once a build has worked.

To see whether the processor has AVX2, run this on the machine:

```bash
grep -q -w avx2 /proc/cpuinfo && echo "AVX2: yes" || echo "AVX2: no"
```

- **On a virtual machine,** the hypervisor decides which instructions the guest sees, and the default types offer
  too few. In Proxmox, open the VM's **Hardware**, edit **Processors** and set **Type** to `host`, or to `x86-64-v3`
  if you move VMs between hosts. Shut the VM down and start it again, then build again. Other hypervisors have a
  setting like it, often called host passthrough.
- **On a processor without AVX2,** such as Intel's before 2013 and some low-power chips, build the image on another
  x86-64 machine that has it, with `docker build -t logicaldoc-community:local .` in a checkout of the same commit.
  Copy it across with `docker save logicaldoc-community:local | ssh <server> docker load`, then start the stack on
  the server with `docker compose up -d`, without `--build`, so Compose uses the image it has.

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
| `Healdan's tables support MariaDB and HSQLDB only` | Healdan needs MariaDB. See [Supported Databases](../what-runs/#supported-databases). |
| `LogicalDOC's tables lack what this version needs: …` | The database lacks a change to LogicalDOC's tables that this version needs, such as `table ld_tenant lacks columns ld_formsquotathr, …`, so the app stopped as it started, before it could fail where it reads them. Healdan never makes the change itself. Make it as Upgrading's [Changes to LogicalDOC's Tables](../upgrading/#changes-to-logicaldocs-tables) says, then start the app again. |
| `Unknown column '…'`, as `Unknown column 't1_0.ld_apicallsquotathr'` (in `dms.log`) | The database lacks a change to LogicalDOC's tables that this version needs, so what reads that table fails, such as signing in. Make it as Upgrading's [Changes to LogicalDOC's Tables](../upgrading/#changes-to-logicaldocs-tables) says, then start the app again. |
| `Seed step 'storage-record-original' failed: Original's folder looks unmounted: …` | The first start on Healdan's storage found Original's folder empty, with no marker, while the database has documents, and stopped rather than mark an empty folder. Check that the folder the message names is there, such as a [host folder](../host-folders/) that didn't mount, and start again. The message also says how to write the marker by hand, if every document really has lost its file. |
| `Storage backend … can't be reached, so reads of its files the read cache doesn't hold fail at once …` (in `dms.log`) | A storage backend's outage: see [A Storage Backend Is Failing](#a-storage-backend-is-failing). |

## The App Signs People Out, or Says It Refused Something

The User app at `/app/` uses the Classic Interface's own sign-in. These are what people tell you they see, and where
to look.

- **"You've been signed out."** The session ended: it timed out, the person signed out in another tab, or an
  administrator ended it. **Sign In** brings them back to the page they were on. If it happens again straight after
  every sign-in, the proxy may be removing the browser's `Sec-Fetch-Site` header: see
  [Reverse Proxy](../reverse-proxy/).
- **"Healdan needs a secure address."** The User app was opened at a plain `http://` address from another machine,
  where browsers don't send the `Sec-Fetch-Site` header its sign-in needs. Use the `https://` address the proxy
  serves (`LOGICALDOC_PUBLIC_URL`), or the Classic Interface, which the page links to: see
  [Reverse Proxy](../reverse-proxy/).
- **"Healdan refused that from this page."** The request didn't look like the page's own: its `Origin` header named
  another address than Healdan's, or a change was sent without the app's own header. Nobody is signed out by it.
  Check that `LOGICALDOC_PUBLIC_URL` is the address people type, that the proxy passes `Origin` on, and that people don't reach Healdan under two different host names, such as by IP address and by name.
- **"Not found, or you can't see it."** The document, folder or page doesn't exist, or the person can't read it.
  Healdan says the same for both, so a reader can't tell which documents exist. If someone swears it exists, check
  their permission on its folder in the Classic Interface, and that it isn't in the Trash.
- **"You don't have permission to do that."** The person can see the document or folder, but not do what they
  asked: delete or move it, write into the folder, or change its properties. Give them the permission on the
  folder, or have someone who has it do the work.
- **"Files are unavailable for now. Try again in a few minutes."** A storage backend can't be reached: see
  [A Storage Backend Is Failing](#a-storage-backend-is-failing).

## Office Previews Say "Not Available"

See [Office Previews](../what-runs/#office-previews) in What Runs.

## A Storage Backend Is Failing

The **Storage** tab of **Administration → Healdan → Settings** shows each storage backend's status. A failing one
says since when and why; the Admin Guide's [When a Backend Fails](../../admin/storage-backends/when-a-backend-fails/)
lists the reasons. A backend that's OK isn't checked on a timer, so press **Test** to check one now.

- **"Can't be reached".** The server can't reach the S3 service, or the service is down. To see whether the app's
  container reaches it, from the folder with Healdan's `compose.yaml`, with the backend's endpoint:

  ```bash
  docker compose exec app curl -sS -o /dev/null -w '%{http_code}\n' https://s3.eu-west-1.wasabisys.com
  ```

  Any HTTP status, such as `403`, means it answers. An error, such as `Could not resolve host`, means it doesn't:
  check the server's network, DNS and firewall. Meanwhile saves into the backend's Stores wait on the server, and
  files in the read cache open. The first read of another file to meet the outage waits for the S3 client to give
  up, about 20 seconds when nothing answers at the address and up to two minutes when the server takes the
  connection and stalls. While the outage is known, the reads after it fail at once with "This file is in …'s
  cloud storage, which can't be reached right now." Healdan checks the backend again every minute, and sooner when
  someone asks for a file, and carries on by itself once it answers.
- **"Bucket not found".** The bucket was deleted or renamed. A bucket deleted while the server runs shows first as
  "can't be reached", and as "bucket not found" after the next check, within a minute.
- **"Access denied … check its access key and secret".** The S3 service refused the backend's keys: put the access
  key and secret right with **Edit**, or the user's policy at the service (see
  [Cloud Storage](../cloud-storage/#make-a-user-for-healdan)).
- **"Enter the secret again in Edit".** The backend's S3 secret can't be read, as after Healdan's secret key was made
  again: see [If the Key Is Lost](../backup-and-restore/#if-the-key-is-lost).
- **A key file that's missing or isn't the backend's.** Restore the configuration volume, or enter the backend's
  recovery key: see
  [Encryption and Recovery Keys](../../admin/storage-backends/encryption-and-recovery-keys/#putting-a-lost-key-file-back).

## A Storage Backend's Marker Is Missing

Every local storage backend holds a marker file, `.healdan-backend`, which Healdan wrote in its folder when it added
it. A backend whose marker is missing fails, and refuses saves into its Stores, rather than write into what may be an
empty mount point. Usually the share behind it didn't mount: mount it on the host, then press **Test** on the
backend, or wait a minute for Healdan to check it again. If the marker itself was deleted, restore it from the
folder's backup.

A marker that names another backend or install means two backends, or two servers, were given the same folder or
bucket: give each its own.

## Files Are Stuck

A file the mover failed to move ten times in a row, over about four hours, is stuck, and the Stores list on the
**Storage** tab counts it. Click the count to see each stuck file's last error, put the cause right, and press
**Retry**: see [Waiting and Stuck Files](../../admin/storage-backends/waiting-and-stuck-files/). An outage never makes
a file stuck: the mover waits for the backend instead.

## An Email Says Storage Needs Attention

Healdan emails the `admin` group once a day while a file has waited more than 24 hours to reach its Store's backend,
or a backend has been failing for more than 24 hours. Open the **Storage** tab: a backend that says Failing, or a
Store with files waiting, shows which. Put it right as above, and the emails stop. See
[The 24-Hour Alert](../../admin/storage-backends/when-a-backend-fails/#the-24-hour-alert).
