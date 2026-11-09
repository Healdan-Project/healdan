# S3, and a storage backend per Store: design

- **Backlog item:** "S3, and a storage backend per Store" in [`docs/ROADMAP.md`](../../ROADMAP.md) §5. It stayed in the backlog until the owner decided when to build it (owner, 2026-11-01), which the owner did on 2026-11-07.
- **Date:** 2026-11-01
- **Status:** design agreed with the owner on 2026-11-01, question by question and then section by section, and the written spec approved by the owner the same day. Implementation plan: [`docs/superpowers/plans/2026-11-03-s3-storage-backends.md`](../plans/2026-11-03-s3-storage-backends.md), approved by the owner on 2026-11-07; its "Questions for the Owner" list where this spec and master disagree, and its "Spec Corrections and Calls" where it departs from this spec. **Built** on 2026-11-07 to 2026-11-09, after the trial (§16.2), in [Healdan-Project/healdan#209](https://github.com/Healdan-Project/healdan/pull/209) (PR A: the module, the tables, the backends, encryption and `HealdanStorage`), [#211](https://github.com/Healdan-Project/healdan/pull/211) (PR B: recording what's there, Store tracking, the mover, failing backends and outages), [#212](https://github.com/Healdan-Project/healdan/pull/212) (PR C: the Storage tab, Docker, Garage on the smoke stack, and the docs) and PR D (the records). Where the build changed what this spec says, the section says so and is marked "changed in the build": the owner's answers to the plan's nine questions, the plan's calls, the build's rulings (the plan's decision log) and the owner's code reviews of PRs B and C. The owner's walkthrough (§16.3, the plan's Task 22) is next.
- **Inputs:**
  - the roadmap's glossary, M1's Stores decisions and the backlog row;
  - [`docs/reference/logicaldoc-ce-capability-map.md`](../../reference/logicaldoc-ce-capability-map.md) §3.2;
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §4 (Stores) and the [M1.5 spec](2026-10-22-m1-5-office-previews-design.md) (previews and thumbnails beside the file);
  - the [M4 spec](2026-11-01-m4-oidc-sign-in-design.md) §10 and §13, for settings in a Healdan Settings tab and secrets encrypted at rest;
  - the backlog row for PostgreSQL, which also changes `DockerInit` and backups;
  - a design conversation with the owner on 2026-11-01;
  - a survey of this repository at `ae36d6e4a`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard and approved section by section. Anything unmarked follows from those, or is detail the builder can change without changing what gets built.

**Words.** *Store*, *workspace* and *matter* mean Healdan's things here (roadmap glossary). A **storage backend**, or **backend**, is a place file bytes go: a local folder or an S3 bucket. LogicalDOC calls that a `Store`; this spec writes "CE's `Store` interface" when it means the Java type, and never calls a backend a "store". "CE" is LogicalDOC Community Edition. A **file** is anything CE keeps for a document: a version's own bytes, and the conversion, thumbnail, tile, mobile thumbnail and summary made from it. A file is named by its **resource name**: the file version, plus a suffix for the derived ones (`1.2`, `1.2-thumb.png`, `1.2-conversion.pdf`).

## 1. Intent

A Store is both an organisation and a location (roadmap glossary): Personal and Client Work, say, and where their files live. Today every Store shares CE's one folder (M1 §4). This work lets each Store keep its files in its own place: a local or NFS folder, or an S3 bucket. The owner's own setup is Personal on local disk and Client Work on Wasabi (owner).

**Why the owner wants it (owner):**

- **Offsite durability.** Client Work survives a fire, theft or NAS failure at the office.
- **Capacity and cost.** Bulk goes to cheap cloud storage, so the server's disk stays small.
- **Some Stores never touch the cloud.** Nothing of Personal's, not even a preview, goes to Wasabi.

**The approach (owner, from three):** a Healdan class that implements CE's `Store` interface becomes CE's single storage bean, through the `store=` key `DockerInit` sets. Every CE caller keeps calling the same bean, and Healdan's class sends each file to the right backend, from a record of where every file is. No CE Java changes.

## 2. Goals, non-goals, anti-goals

### 2.1 Goals (done when)

The walkthrough in §16.3 passes on the owner's server against the owner's Wasabi bucket. In short:

1. **Set up.** An admin adds a Wasabi backend in Healdan Settings' Storage tab, following the Install pages, saves its recovery key, and Test passes (§11).
2. **A Store moves.** Switching Client Work to Wasabi moves its existing files there in the background, and the tab shows the progress (owner, §7).
3. **Saves don't wait.** A save into a Wasabi Store returns at local speed and is uploaded behind it (owner, §6.1).
4. **Wasabi holds only ciphertext** (owner, §9).
5. **Personal stays home.** Nothing of a local Store's reaches Wasabi, and a matter moved from Client Work to Personal brings its files back (owner, §7).
6. **Outages.** With the internet down, saves still work, recently read files open, and the uploads catch up afterwards (owner, §6.5).
7. **Restores.** A restore from a backup younger than the holding period finds every file (owner, §8, §12).
8. **In CI.** The automated tests in §16.1 run, including the whole flow against a stand-in S3 server on the Docker stack, on amd64 and arm64.
9. **Documented.** The docs pages in §15.3 are written.

### 2.2 Non-goals

- **A backend per matter or per folder.** A backend belongs to a Store (owner: a Store *is* a location). A matter that needs another place moves to another Store (call).
- **Choosing a backend per document**, or showing users where a document is stored. It's an admin concern, and it lives in the Storage tab (call).
- **Other backend kinds:** SMB, Azure Blob, Google Cloud Storage, WebDAV. S3 covers Wasabi and most self-hosted servers (call).
- **Compression.** PDFs and Office files are already compressed, and compression would get in the way of ranged reads (call).
- **Turning encryption on or off for a backend that holds files,** or rotating its key in place. Either is a new backend and a move (call).
- **Healdan running backups.** Backups stay documented commands, as today, with an offsite recipe added (call, §12).
- **Self-hosted S3 for the owner.** The owner's places are local disk or NFS, and Wasabi (owner). Garage is tested only as CI's stand-in (§3.2). Other S3 services should work but aren't promised.
- **LogicalDOC clusters.** Healdan runs one node, so the cache and the mover's bookkeeping are per server (call).
- **CE's own multi-backend screens.** CE's hidden Storage settings panel and per-folder backend picker (feature 52) stay hidden (owner, §11). (changed in the build: CE's Stores screen wasn't hidden. Administration → Settings → Stores, menu 105, listed Original's folder as "Store 1" from `store.1.dir` and let an admin change it, after which every save into Original would fail on its missing marker. The `storage-hide-stores` seed turns the menu off, as `hide-dropbox` does Dropbox's, on a server whose store is `HealdanStorage`; plan question 1.)

### 2.3 Anti-goals

- **A Personal file, or anything made from one, on the cloud** (owner). A file goes only to its own Store's backend, plus staging and the cache, which are on the server (§6).
- **A file that exists nowhere** because a move deleted its source before the copy was safe (§7).
- **A cloud provider able to read the files** of an encrypted backend (owner, §9).
- **A key in the database.** A database dump and the bucket together read nothing (§9).
- **Writing into an unmounted mount point** when an NFS share isn't there (§10.2).
- **Editing CE's Java, Spring files or `context.properties`.** The `store=` key does it (§3.1).
- **Home-made cryptography.** Tink's streaming AEAD, not a hand-rolled format (§9).

## 3. Constraints

### 3.1 Facts from the code

- **One bean, chosen by a key.** `logicaldoc-core/src/main/resources/context/context-core.xml:21` is `<bean id="store" class="${store}" />`, and `context.properties:458` is `store=com.logicaldoc.core.store.FSStore`. So a Healdan class replaces CE's backend by configuration. Every caller in CE and Healdan gets the bean through `Store.get()`, `Context.get(Store.class)` or injection: about 25 classes, including downloads, thumbnails, conversions, WebDAV, CMIS, SOAP, the API and M1.5's preview worker.
- **Nothing reads the files around the bean.** Only the setup wizard (`SetupServiceImpl`, `Setup.java`), CE's hidden Storage screen (`StoresDataServlet`, `SettingServiceImpl.removeStore`) and `ConfigPlan` read `store.1.dir`, and none of them reads a document's file. (changed in the build: that screen wasn't hidden, §2.2.)
- **CE's `Store` interface** (`logicaldoc-core/.../core/store/Store.java`) takes a `StoreResource` (doc id, file version, optional suffix) for `store`, `getStream`, `writeToStream` (with an optional byte range), `getBytes`, `writeToFile`, `exists`, `size` and `delete`. It takes a doc id alone for `delete(docId)`, `listResources(docId, fileVersion)` and `moveResourcesToStore`.
- **Every file of a document sits together.** `AbstractStore.computeRelativePath` puts doc 12345's files in `123/45/doc/`, named `<fileVersion>` or `<fileVersion>-<suffix>`. The suffixes are `conversion.pdf`, `thumb.png`, `tile.png`, `mobile.png` and `summary.html` (`StoreResource`). M1.5's previews use the same names.
- **CE rewrites files in place.** `DocumentManager.replaceFile` (~line 183) deletes a file version's derived files and stores new bytes under the same name. Previews and thumbnails are made again under the same names.
- **CE writes a new document's file after its database row.** `DocumentManager.create` (~line 1039) stores the document, then calls `storeFile`, which calls `store.store(file, resource)` (~line 1062), in the same thread.
- **CE checks the size after a write.** `AbstractStore.checkWriteAfterStore` compares `size(resource)` with the file's length, when the `WRITECHECK` aspect is on.
- **CE matches file versions by prefix.** `FSStore.listResources` keeps names that `startsWith(fileVersion)`, so listing `1.2` also lists `1.20` to `1.29`. `replaceFile` and `deleteVersion` (~line 1618) delete what it lists. Healdan's class matches a file version exactly (§6.3). (changed in the build: CE's bug was fixed before this work was built, in [Healdan-Project/healdan#153](https://github.com/Healdan-Project/healdan/pull/153): `FSStore.listResources` has matched a file version exactly since 2026-11-01, and `replaceFile` has taken a null file version as the current one since 2026-11-02. `FSStoreFileVersionTest` runs in CI's `UPSTREAM_TESTS`, and the capability map's §6 records both. Healdan's class still matches exactly, from its rows; plan question 2.)
- **The unused multi-backend parts.**
  - `AbstractStore.id` is fixed at 1.
  - The `Store` extension point has no implementations, so `newStore(id)` returns null.
  - `Folder.stores` (`ld_folder_store`) maps a folder to a backend number per *cluster node*. `DocumentManager.getStore(Folder)` resolves it through the parents, and its only consumer, `enforceFilesIntoFolderStore`, ends in `FSStore.moveResourcesToStore`, which throws.
  - Nothing records which backend holds a document or version.
- **Dead keys.** `store.1.encryption` and `store.1.compression` are in `context.properties`, but no CE code reads them.
- **Healdan's rules.** Healdan never changes the structure of CE's `ld_` tables. Its own tables are Flyway's, in `healdan-core`. V1 to V5 have shipped, and M4 takes V6. (changed in the build: M4 shipped V6 and V7 and M5 V8, so the storage tables are V9, `V9__storage.sql`, and the owner's review of PR C added V10, `V10__storage_file_backend.sql`, an index on each file's backend; plan question 3.)
- **M4's secrets.** M4 encrypts secrets in `hd_setting` with AES-GCM, under a key in the configuration folder (M4 §13), and settings live behind a tab in Healdan Settings (M4 §10). (changed in the build: M4 had landed, and its `SecretBox` seals the S3 secrets too, §9; plan question 5.)
- **`DockerInit`.** `ConfigPlan` seeds keys once, re-applies environment keys on every start, takes `IMAGE_KEYS` from the image, and replaces earlier defaults listed in `REPLACED_DEFAULTS` (CLAUDE.md, Docker).

### 3.2 Facts from outside

- **Wasabi bills each object for at least 90 days** on pay-as-you-go: deleting one sooner still bills the rest ([Wasabi's docs](https://docs.wasabi.com/docs/how-does-wasabis-minimum-storage-duration-policy-work)). So holding deletes for 30 days (§8) costs nothing extra.
- **The AWS SDK for Java 2.x** adds CRC32 checksums to uploads by default from 2.30.0 ([AWS's docs](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)), and some S3-compatible services reject them ([GitLab's fix](https://gitlab.com/gitlab-org/gitlab-runner/-/merge_requests/6691) for the same change in Go's SDK). Setting the SDK's checksums to "when required" avoids it. Whether Wasabi needs it is checked in the trial (§16.2). (changed in the build: the trial found Wasabi and Garage both take a `Content-MD5` with the SDK's checksums "when required", and refuse a wrong one, storing nothing.)
- **MinIO is no longer an option for CI.** Its community images stopped being published in October 2025, and the project has since been archived ([linuxiac](https://linuxiac.com/minio-ends-active-development/), [glukhov.org](https://glukhov.org/data-infrastructure/object-storage/minio-dead/)).
- **Garage** (AGPL-3.0) implements the object and multipart calls Healdan needs, but not versioning or server-side encryption, which Healdan doesn't use ([Garage's compatibility page](https://garagehq.deuxfleurs.fr/reference_manual/s3_compatibility.html)). It runs as a separate test container, so its licence doesn't touch Healdan's code. Its ranged reads aren't confirmed by its docs, so the trial checks them (§16.2). (changed in the build: the trial found them exact.)
- **Licences.** The AWS SDK for Java 2.x and Tink are Apache 2.0, which suits the LGPL-3.0 (principle 8).

### 3.3 The setup (owner)

- **Places:** local disk or NFS, and Wasabi. No self-hosted S3.
- **First use:** split Stores by place from the start: Personal on local disk, Client Work on Wasabi.
- **The first move is the big one.** Client Work's existing files move from today's folder to Wasabi.

## 4. The pieces (call)

| Piece | Where | Does |
|---|---|---|
| `HealdanStorage` | `healdan-storage` (new plugin module) | Implements CE's `Store` interface; CE's `store` bean. Looks up each file's row and sends the call to a backend, staging or the cache (§6) |
| Backends | `healdan-storage` | A local folder and an S3 bucket, behind one small interface (put, get with a range, size, delete), so tests swap in an in-memory fake (§10) |
| Encryption | `healdan-storage` | Tink's streaming AEAD over any backend (§9) |
| Staging and the cache | `healdan-storage` | Folders on the server (§6) |
| The mover | `healdan-storage` | One background worker with parallel transfers (§7) |
| Held deletes and the purge | `healdan-storage` | §8 |
| The Storage tab's server side | `healdan-storage` | An RPC servlet at `/frontend/healdanstorage`, registered with `@WebServlet` (CLAUDE.md) (§11) |
| The Storage tab | `logicaldoc-gui`, Healdan's settings package | §11 |
| The tables | `healdan-core`'s Flyway folder, both databases | One migration history for all of Healdan (§5) |
| The recording seed and the smoke seed | `healdan-storage`, run in `healdan-core`'s seed order | §5.3, §16.1. They live with the code they use: the smoke seed makes an encrypted backend. (changed in the build: with `storage-settings`, which writes the settings' defaults, this install's id and the alert's template, and `storage-hide-stores`, which hides CE's Stores screen; plan calls 7 and 8, question 1) |
| The `store=` key, and the cache volume's owner | `docker/tools` (`ConfigPlan`), `docker/entrypoint.sh` | §12 |
| The download filter | `healdan-storage` | (changed in the build: plan question 7) `DownloadErrorFilter`, a `@WebFilter` over CE's download routes, which answers an outage with §6.5's message |
| The outage on the API | `healdan-api` | (changed in the build: plan question 7) `503 storage-unreachable` (§6.5) |
| Seams | `healdan-core` | (changed in the build: plan question 6) a start hook (`HealdanStartListener`, called from `HealdanStartup.start()`), a folder-restore hook (`FolderTreeListener`, §5.4), the seeds' names in `SeedRunner.ORDER`, the S3 secrets in `SecretBox`'s count, `OutgoingEmail` for the alert, `StorageUnreachableException` (§6.5), and `PreviewWorker` waiting out an outage |

- **`healdan-storage`** has JPF id `healdan-storage` and requires `healdan-core`. Its code is under `com.logicaldoc.healdan.storage`, so CE's Spring and Hibernate scans and `HealdanContext`'s find it with no edits, as they do `healdan-api`'s (CLAUDE.md). It's a module of its own because of its libraries, and to keep storage apart from the rules in `healdan-core` (call).
- **Libraries,** pinned in `healdan-storage`'s POM at their current releases when the plan is written: the AWS SDK for Java 2.x's `s3` module with its plain HTTP client (no Netty, no native CRT), and Tink. The plan records how much they add to the image (expected 10 to 15 MB). (changed in the build: `software.amazon.awssdk:s3` 2.57.8 with `url-connection-client`, the JDK's own HTTP client, as the module's default client would bring an `httpclient5` the WAR already ships at another version, plan call 1; and Tink 1.24.0, used without keysets, so the plugin zip leaves out Tink's Gson and Protobuf, as `TinkClasspathTest` shows it may, call 2. The WAR gains 33 jars, about 11.8 MB.)
- **M4's encryption of secrets** (M4 §13) encrypts the S3 secret. It's being built now, so it lands first. (changed in the build: it had landed; plan question 5.)

## 5. Records (call)

### 5.1 Tables

A new Flyway migration in `healdan-core`, for MariaDB and HSQLDB, numbered the next free version when it's built (V7 at the time of writing, after M4's V6). Its SQL runs unchanged on PostgreSQL too (§14). (changed in the build: V9, after M5's V8, and V10, the owner's index on each file's backend from the review of PR C. The MariaDB scripts keep Healdan's convention, `datetime` and `utf8_bin`, and the HSQLDB ones `timestamp`; both use only `bigint`, `int`, `varchar`, timestamps and sequences, so the HSQLDB scripts are the ones a PostgreSQL folder copies; plan question 3.)

| Table | Holds |
|---|---|
| `hd_storage_backend` | Each backend: id, name, kind (local or S3), the path (local), the endpoint, region, bucket, key prefix, path-style flag and access key (S3), the secret (encrypted as M4's are), and whether it's encrypted and its recovery key confirmed |
| `hd_storage_store` | Each Store's backend: the Store's folder id and a backend id. A Store with no row uses backend 1 |
| `hd_storage_file` | One row per file: doc id and resource name (the key), the backend that holds it now (or staging), the Store it belongs to, its plaintext size, a write counter, when it was stored, and for the mover its attempts, last error and whether it's stuck. (changed in the build: staging is backend 0, which has no `hd_storage_backend` row, plan call 4; the write counter is a write id, `hd_write`, a random 64-bit number each write gives the row, call 21, §6.1; and the row also holds since when it has waited to reach its Store's backend, `hd_waiting`, which the mover lists by and the alert reads, and when it's next tried) |
| `hd_storage_held` | Deletes waiting out the holding period: the backend, doc id and resource name, where the copy is (an object key, or a path under `.held/`), and its purge date |
| `hd_storage_event` | (changed in the build: plan question 8) Each showing and each entering of a backend's recovery key (§9): the backend, the event, when, and the user's id and name, kept for good, a removed backend's too |

**Settings** in `hd_setting`: the holding period (`storage.holdDays`, 30), the cache's size (`storage.cacheGb`, 20) and the mover's parallel transfers (`storage.transfers`, 4). (changed in the build: also the mover's sweep, `storage.sweepSeconds` (300), and the retest of a failing backend, `storage.retestSeconds` (60), which the tab doesn't show and only the smoke seed changes, plan call 12; the day the alert last went, `storage.alert.sent`; and this install's id, `storage.installId`, made once, which every backend's marker names, call 23.)

### 5.2 Backend 1, "Original"

- Backend 1 is today's folder: `store.1.dir`, `/opt/logicaldoc/repository/docs/` in the image, in CE's layout, unchanged. The migration adds its row, named Original.
- Its path always comes from the configuration, not the row, so an install's own `store.1.dir` keeps working.
- **Staging** is a built-in local backend in `/opt/logicaldoc/repository/staging/`, in CE's layout. The tab doesn't list it. (changed in the build: it's backend 0 in the rows, plan call 4. The system property `healdan.storageStaging`, which `docker/setenv.sh` sets, names its folder, or else it's `staging/` beside Original's, call 9; it can't be, hold or sit inside Original's folder or the key files' folder.)

### 5.3 Recording what's already there

- On the first start after the upgrade, a seed step (`storage-record-original`) walks Original's folder and adds a row for every file, with its size and its document's Store. It skips names starting with `.`, as `FSStore` did. (changed in the build: it also writes Original's marker, §10.2. It stops the start instead, saying how to write the marker by hand, when Original's folder has no marker and no file but the database has documents: that's a share that didn't mount, and marking its empty mount point would lose §10.2's guard for good.)
- A file whose document isn't in `ld_document` gets a row with no Store, which counts as Original, and a warning in the log. (changed in the build: one warning for all of them, naming the first five, and each at debug, so a large repository's start doesn't flood the log. A file at a path CE's layout wouldn't make for its digits, and one in a folder the seed can't read, gets no row, and is read from Original as any file with no row is, §6.3.)
- From then on, every write adds or updates a row. So every file has exactly one row, and the mover never has to guess.
- The seed inserts only rows that are missing, so it can't clash with a save made while it runs. (changed in the build: no save can run beside it, as seeds run while the Spring context starts, before Tomcat opens its connectors and before CE's scheduler starts, so it takes no file's lock. Inserting only missing rows lets a start that fails part-way resume at the next.)

### 5.4 A file's Store, and where it belongs

- A row's Store is the Store holding its document's folder: the LogicalDOC workspace above it. A file belongs on its Store's backend.
- **Keeping it current:**
  - `HealdanStorage` sets it on every write.
  - When a document moves to a folder in another Store, or a matter moves to another Store, Healdan's listeners (and `HealdanHooks`' move hooks, where CE moves without a listener) update the rows, so the mover acts at once. (changed in the build: the listeners are `StoreTrackingDocumentListener` and `StoreTrackingFolderListener`, at position 120, after Healdan's rules. They write after CE's commit, in a transaction of their own, whose failure is logged, never thrown into CE's commit. CE's folder restore tells no listener, so `HealdanHooksImpl.afterFolderRestore` calls a new `healdan-core` interface, `FolderTreeListener`, with the tree's documents, plan call 6; every other move fires a listener. A Store is the top-level workspace above a folder, Templates included and CE's Default workspace not, found by walking `ld_parentid`, never from `ld_path`, which CE's folder restore leaves stale, call 10.)
  - **A nightly check** recomputes every document's Store from the folder tree, fixes any row that's wrong, and logs each fix. It's the backstop for the anti-goal in §2.3. (changed in the build: at 03:17 server time, and never in the first 10 minutes after a start. Each fix is checked against the document's Store read again before it's made, as a move may have committed since the folders were read, and logged at debug, with their count at info. It also marks as waiting any row its backend leaves misplaced, §7.)
- Before a Store switches backend, its rows' Stores are checked the same way.

## 6. Saves and reads (call)

### 6.1 Saves

When CE stores a file, `HealdanStorage` finds the document's Store and that Store's backend.

- **A local backend:** the file is written straight there, as today (encrypted, if the backend is).
- **An S3 backend:** the file is written to staging (owner). The row says "staging", the save returns, and the mover is woken. Staging sits in the repository volume, so the nightly backup covers anything not yet uploaded.
- **A Store that can't be told yet,** for a path where CE writes before the document has a folder: staging, and the mover puts it right. (changed in the build: a document in CE's Default workspace, which is no Store, is staged the same way, and the mover takes it to Original, where a file with no Store belongs.)
- Previews and thumbnails are written the same way, and M1.5's worker reads the original from staging, so they're made at local speed.
- **The write counter** goes up by one on every write. (changed in the build: each write gives the row a new write id, a random 64-bit number, plan call 21. A counter starts again at 1 when a row is deleted and made again, as Replace File and M1.5 do with derived files, and a database restored in place winds counters back while the cache keeps its files, so a stale transfer or a cache entry could have matched another file's bytes. A write id never repeats.)
- (changed in the build: plan call 24) **Checked first.** A write that reaches a backend not yet checked since the start runs the backend's check itself first, and a failing backend refuses it (§9, §10.3). Saves into an S3 Store land in staging, so they don't notice.
- **CE's size check** (§3.1) reads the row's plaintext size, so it passes on an encrypted backend.

### 6.2 Overwrites

When CE rewrites a file in place (§3.1), the new bytes are written as in §6.1. If the old ones were on another backend, that copy goes to held deletes (§8). A purge never removes an S3 object that a row points at again, so the mover can upload the new bytes under the same key.

### 6.3 Reads and lists

1. The row says which backend holds the file, staging included.
2. For an S3 backend, the cache is checked first (§6.4).
3. On a miss, the whole file is fetched, decrypted and put in the cache, and the read is served from there.
4. A file larger than a tenth of the cache isn't cached. Its ranges are read straight from the bucket, decrypting only the segments they cover (§9), so a large video still starts playing quickly.

(changed in the build) **A known outage fails at once.** While a backend is known to be unreachable, found so by a check, a save, a read or a transfer less than `storage.retestSeconds` ago, or with a check of it under way, a read the cache can't serve fails at once with §6.5's message, rather than waiting out the S3 client's attempts, which held a read up to two minutes for a server that stalls. A read refused for a finding more than 5 seconds old asks for an early retest of the backend, so one that's back is found within seconds. (Plan decision log, Task 16; the owner's review of PR C made a retest round's findings count from its end.)

- (changed in the build: plan call 11) **A file with no row** is read from Original's folder, where only CE's own store could have put it, and warned of once.
- **A missing local file:** a local backend looks in its `.held/` folder when a file isn't in its place (§8), so a restored database finds a file deleted after its backup.
- **`listResources(docId, fileVersion)`** comes from the rows, not from listing a folder or a bucket. It matches the file version exactly: `1.2` lists `1.2` and `1.2-thumb.png`, not `1.20` (§3.1).
- **`getTotalSize`** sums the rows.
- **`moveResourcesToStore`** returns 0, and **`getStoreDefinitions`** returns an empty map, so CE's hidden screens stay empty. (changed in the build: the Stores screen is hidden by the `storage-hide-stores` seed, §2.2.)
- **`test`** tests every backend with its own test object and records nothing (§10).

### 6.4 The cache (owner: capped)

- **Where:** a new Docker volume, `logicaldoc-cache`, at `/opt/logicaldoc/cache`. Backups skip it, as they name only the other volumes, and it survives restarts. (changed in the build: the system property `healdan.storageCache` names it, or else it's `cache/` beside Original's folder, plan call 9.)
- **What:** plain files, read from S3 backends: files, previews and thumbnails alike. They're on the owner's own hardware, as the local Stores are.
- **Size:** 20 GB by default, a Storage tab setting, with the least recently read files dropped first.
- **Keys:** an entry is named by its backend, resource and write counter, so a rewritten file never serves its old bytes. Deletes drop their entries. (changed in the build: by its write id, plan call 21, as `<backendId>/<docId>/<resource>@<write>`.)
- (changed in the build: plan question 9) **An upload's staged copy becomes its entry.** Once the mover has pointed the row at the bucket, it renames the staged copy aside and moves it into the cache, rather than deleting it or holding it, so previews and a file opened soon after its save don't fetch it back, and the cache's cap bounds the disk. One over a tenth of the cache is deleted.
- **After a restart,** the cache rebuilds its index from the folder. (changed in the build: and deletes the temporary files a crash left at its top, leaving any other file there, logged.)

### 6.5 Outages

- **Saves** into an S3 Store are unaffected (owner, §6.1).
- **Cached files** open.
- **An uncached file** fails with a message that says why, along the lines of "This file is in Client Work's cloud storage, which can't be reached right now". Previews and thumbnails fall back to their placeholders. (changed in the build: the message is that one, from `healdan-core`'s `StorageUnreachableException`, or "This file is in Client Work's storage, which can't be reached right now." for a local backend, such as a share whose folder can't be read, plan call 13. The API answers it `503 storage-unreachable`, with the message as `detail`, which the helper shows. The web page's downloads and shared links (`/download` and `/download-ticket`) get a `503` page with it through `DownloadErrorFilter`, as CE's servlets send `200` before they read the file, question 7; the filter holds back a response's first 1,024 bytes, so a gzip header or a range's boundaries written before the read don't start the answer, the owner's review of PR B. `/convertpdf` serves CE's placeholder PDF, Task 13's ruling. A file read by range straight from the bucket that fails after its first bytes arrives cut off, and an email's attachment, a ZIP export and the web page's thumbnails don't show the message, which Known Limitations says. M1.5's preview worker postpones a preview that meets an outage without spending one of its tries.)
- **Search, properties and browsing** never touch the bucket.
- **Never on S3:** the full-text index (the extracted text of every document), logs, the database and the configuration all stay on the server.

## 7. The mover (owner: in the background; call: the rest)

One background worker with parallel transfers (4 by default, a setting). It's woken by a staged save, a document or matter changing Store, or a Store switching backend, and it also sweeps every 5 minutes. It finds rows whose backend isn't their Store's, and for each: (changed in the build: it's woken by Retry, a nightly fix and a backend found OK again too, and sweeps every `storage.sweepSeconds`. It lists only rows marked as waiting, `hd_waiting`, through that column's index, rather than reading the whole table on every wake; every write, Store change and Store switch keeps the mark, and the nightly check mends it, the owner's review of PR B.)

1. **Reads** the file from where it is, decrypting if needed. (changed in the build: first it reads the row again, and works the document's Store out again from the folders, so a row left in a Store its document has left never sends the file to that Store's backend. The file is read into a copy in staging, noting the row's write id.)
2. **Writes** it to the target, encrypting if the target is encrypted.
   - **S3:** each upload carries a `Content-MD5`, so the bucket refuses a corrupted upload. Files over 64 MB go up in 16 MB parts, and a failed multipart upload is aborted. (changed in the build: over 64 MiB, in 16 MiB parts sent one at a time, as four transfers at once already fill the line, the trial found; then the object's size is read back with `HEAD`, plan call 16.)
   - **Local:** the file is synced to disk and its size checked. (changed in the build: under a temporary name beside its key, where a save's bytes can't be overwritten, moved into place only at step 3, call 20.)
3. **Points the row at the target,** only if the write counter hasn't changed since step 1. If it has, the file was rewritten meanwhile: the mover puts its copy in held deletes and tries again later. (changed in the build, plan call 20: holding the file's lock, in a short transaction of its own, the mover checks that the row is still the write it read, on the source, and that the row's Store's backend is still the target. If so, it moves its copy into place and points the row at the target. If not, it deletes its own copy, which no row ever pointed at: holding it, as this said, would have moved the live key, a save's new bytes on a local backend, under `.held/` for the purge. A transfer whose backend the Storage tab rebuilt meanwhile, as its settings changed, commits nothing either, the owner's review of PR C.)
4. **Holds the source copy** (§8). A source is never removed before the target is verified and the row updated. (changed in the build: once the row's transaction has committed, still holding the lock, so a commit that fails leaves the source as it was. A staged source becomes the read cache's entry instead, §6.4.)

- **Order:** staged files first, as they're only on the server; then moves, newest documents first. No two transfers work on the same file.
- **Retries:** each file retries with growing gaps. After 10 failures it's marked **stuck**, with its last error, and left alone until an admin presses **Retry** in the Storage tab. (changed in the build: the gaps are 1, 2, 4, 8, 16, 32, 60, 60 and 60 minutes.)
- **A backend that can't be reached** pauses its transfers, and the mover tests it again every minute rather than spending retries. (changed in the build: every `storage.retestSeconds`, a minute by default. A refusal of one object, such as S3's access denied, is checked against the backend at once: if the backend still passes its check, the refusal is the file's and costs it a try, so it can stick and the alert sees it; otherwise the backend pauses.)
- **Progress:** the Storage tab shows, per Store, the files and size waiting to move, and the stuck files with their errors (§11). (changed in the build: files with no Store, which belong on Original, show on an Other Files line when any wait or are stuck.)
- **The alert:** if any file has waited more than 24 hours, or a backend has failed for that long, Healdan emails the admins once a day until it clears, through CE's outgoing email, as M4's `admin` alert does (M4 §7.5). Without outgoing email there's no alert, and the tab still shows it. (changed in the build: it's checked hourly, and sends at most one email a day, recording the day once at least one email was taken, so a mail server that was down is tried again within the hour. It goes to the members of the `admin` group who are enabled, not expired and have an email, through M4's sender, `OutgoingEmail`, which throws where CE's would drop an email without a word. A file's 24 hours count from its `hd_waiting`, so a Store switched an hour ago raises nothing, however old its files are.)
- **Switching a Store to another backend** is the same work for every file in it. So is the owner's first move of Client Work to Wasabi. (changed in the build: the switch checks the Store's rows' Stores first, then writes the Store's row and marks its files as waiting, `StoredFiles.storeBackendChanged`, in a short transaction of its own, and wakes the mover once that has committed. Measured on MariaDB, marking 100,000 files took 2.1 seconds.)

## 8. Held deletes (owner: hold, every backend)

Every removal waits out a **holding period** before anything is deleted: a deleted version, a document purged from the trash, a replaced preview, and a source copy after a move.

- **S3:** nothing is touched. A row in `hd_storage_held` records the object and its purge date.
- **Local:** the file is moved into a `.held/` folder at the backend's root, under the same path, which is an instant rename on the same disk. Reads look there when a file is missing (§6.3).
- **The purge:** a daily task deletes what's past its date, then its row. It skips an S3 object a row points at again, and drops that held row. (changed in the build: never in the first 10 minutes after a start. Each copy is purged holding its file's lock, and an S3 object whose file the mover is moving now is left for the next purge, so the purge's delete and the mover's upload of the same key can't straddle each other, plan call 22. A backend that is failing or can't be reached is skipped, its rows kept, as S3 can't tell a missing bucket from a missing object; and a local copy is deleted only from `.held/`. A document's folder that a hold or a purge leaves empty is removed, as `FSStore` removed it.)
- **The period:** 30 days by default, from 1 to 365, a Storage tab setting. It should be at least as long as the oldest backup the admin would restore (§12).
- **The cost (owner accepted):** a matter moved off Wasabi leaves its encrypted copies there until the period ends.
- **Removing a backend** is allowed only once no Store uses it and no file is recorded or held on it. (changed in the build: Original can be renamed, never removed. A removal deletes the backend's row for good, so its sealed secret leaves the database, then its key file, logging one it can't delete rather than refusing, then its check object and its own marker, so that another backend may use the place; the owner's review of PR C set that order.)

## 9. Encryption (owner: Healdan encrypts; call: the rest)

- **Per backend,** chosen when it's created: on by default for S3, off for local folders, and available for a local folder too (an NFS share on someone else's box, say). It can't change once the backend holds files (§2.2).
- **The format:** Tink's streaming AEAD, AES-256-GCM with HKDF, in 1 MiB segments. A ranged read decrypts only the segments it needs. Each file's doc id and resource name are its associated data, so one object can't be swapped for another.
- **The key:** 256 bits per backend, made by Healdan when the backend is created and kept in a file in the configuration folder (the `conf` volume), readable only by the app's user. It's never in the database. The file's name and layout are the builder's. (changed in the build: `backend-<id>.key`, the key's 32 bytes, mode 600, in a folder of mode 700, `healdan-storage-keys/` in the `conf` volume, which the system property `healdan.storageKeys` names, or else beside `SecretBox`'s key file. A key is made under a lock file in the folder, never over another, and Enter Recovery Key keeps a key file it replaces that holds another key, as `backend-<id>.key.replaced-<millis>`.)
- **The recovery key:**
  - It's the key, written as 52 characters in groups. (changed in the build: in Crockford's base32, which has no I, L, O or U, in 13 groups of 4, read whatever the case, with spaces and dashes ignored, plan call 15.)
  - It's shown when the backend is created. The backend isn't used until the admin types back its last group, to prove it was saved. (changed in the build: until then the tab doesn't offer it to Stores, and Show Recovery Key asks for the last group too.)
  - Admins can show it again from the backend's menu. Each showing is recorded in the history, with who and when. (changed in the build: CE's history takes only its own events, so a fifth table, `hd_storage_event`, keeps every showing, the add's included, and every entering, for good, with the user's id and name; the tab shows the latest, plan question 8.)
  - **Enter Recovery Key** rebuilds a lost key file from it.
  - Lose both the key file and the recovery key, and the backend's files can't be read. The Install and Admin pages say so plainly.
- **A wrong key is caught.** Each encrypted backend holds a small check object. At start, and on Test, Healdan decrypts it. If that fails, the backend is marked failing and refuses writes, so a botched restore can't write files under the wrong key. (changed in the build: every backend is checked at start, before the mover's first pass, and a write that reaches one not yet checked runs the check itself first, plan call 24. An encrypted backend with no check object fails too. Test reads the key file afresh, so a deleted one fails the backend; nothing else re-reads key files, so until a Test or a restart a cached key goes on working and loses nothing.)
- **Not encrypted:** staging and the cache, which are on the server (§6). The S3 secret is encrypted separately, as M4's secrets are. (changed in the build: `SecretBox` counts the S3 secrets among the values it has sealed, so a new key for sealed secrets is made only once an admin has typed a secret and confirmed the new key, after the add's or save's checks have passed; M4's new-key question, its log messages and the Backup and Restore page name the storage backends' secrets too, plan question 5 and the owner's review of PR C.)

## 10. Backends (call)

### 10.1 S3

**Settings:**

- a name;
- the endpoint, e.g. `https://s3.us-east-2.wasabisys.com`;
- the region and the bucket;
- an optional key prefix, so one bucket can serve several backends or installs; (changed in the build: at most 255 characters, and none of its folders may start with a digit or a dot, as CE's layout starts every key with a digit, so another backend's prefix could otherwise sit among this one's files;)
- the access key and the secret: the secret is set or replaced, never shown;
- path-style addressing, on or off.

**The client:**

- **Library:** the AWS SDK for Java 2.x (§4).
- **Checksums:** set to "when required" for any endpoint that isn't AWS's (§3.2), with a `Content-MD5` on every upload and part instead (§7).
- (changed in the build: the trial's and the build's rulings) **Attempts and failures.** The client makes 2 attempts, not the SDK's 4, with a 10-second connection timeout and a 60-second read timeout, and gives each attempt of an upload the longer of 10 minutes and its bytes at 64 KiB a second. A network failure, a `5xx`, a `429`, a `SlowDown` or a `RequestTimeout` means the backend can't be reached; a `401`, a `403` or a missing bucket fails the backend; any other refusal is the file's, one failed try. A missing bucket is found with a `GET` or a list, as a `HEAD` can't tell it from a missing object.
- **Object keys:** CE's layout under the prefix, e.g. `healdan/123/45/doc/1.2-thumb.png`. (changed in the build: and, under the same prefix, the marker `.healdan-backend`, the check object `.healdan-check` and Test's objects `.healdan-test-<id>`, which can't clash with CE's layout, plan call 23.)
- **Test:** puts a test object, reads it back whole and by a range, lists it, deletes it, then checks the key (§9). (changed in the build: and reads the marker. On settings not yet saved it skips the marker and the key, which the add writes afterwards, before it tests the backend in full.)
- (changed in the build: plan call 23) **No two backends share a place.** An S3 backend's bucket and prefix can't equal or hold another's at the same S3 service: every AWS host is one service, as AWS's bucket names are one namespace across its regions, and any other endpoint is its host and port, so two servers on one host are two services (the owner's review of PR C). The marker names this install and the backend, and one naming another install or backend fails the backend, so two installs can't share a place either. A server restored from a backup has the same install id, so the restore steps stop the first server before the second starts.

**The docs recommend, for Wasabi:** a user limited to the one bucket, and a lifecycle rule that clears incomplete multipart uploads after 7 days. Versioning isn't needed (§8).

### 10.2 Local folders

- **Settings:** a name and a path inside the container. (changed in the build: an absolute path of at most 255 characters, which can't be, hold or sit inside Original's folder, staging's, the cache's, the configuration folder, the key files' folder or another backend's folder, its links followed, plan call 23.)
- **The marker:** at creation Healdan writes a `.healdan-backend` file at the path. If it's missing at start or before a write, the backend is failing and refuses writes. That stops Healdan writing into an empty mount point when an NFS share didn't mount. (changed in the build: it names this install, `storage.installId`, and the backend, and one naming another install or backend fails the backend too, which catches two backends given one folder, plan call 14.)
- **Original** gets its marker on the first start after the upgrade. (changed in the build: from the recording seed, which refuses to mark a folder that looks unmounted, §5.3.)
- **Docker:** a folder other than Original's is a mount the admin adds in `compose.override.yaml` (§12).

### 10.3 Failing backends

A backend is **failing** when its marker is missing, its key check fails, or it can't be reached. The tab shows it with its last error and since when. Writes to a failing local backend fail, as today when a disk is gone. Saves into an S3 Store don't notice, as they land in staging. The mover pauses its transfers (§7). (changed in the build: it's failing too when its key's check object is missing, its secret won't open, its bucket isn't found, or it can't be built. Saves and reads report what they meet, so one that fails after its last check passed, as an unmounted share or a deleted bucket, shows as failing at once, and the alert sees it; a backend shows OK until something uses or tests it, as only failing backends are checked again, every `storage.retestSeconds`. While its outage is known, its uncached reads fail at once, §6.3. The owner's review of PR B.)

## 11. The Storage tab (owner: a Healdan Settings tab; call: the layout)

A **Storage** tab in Healdan Settings, beside M4's Sign-In tab, for admins only. Its servlet (§4) checks the admin role on every call. Its labels are Healdan's own, as the Healdan Settings panel's are, so no message bundle changes.

**Backends.** A list: name, kind (Local Folder or S3), where (the path, or the endpoint and bucket), encrypted or not, files and size, and status (OK, or Failing with the last error and since when).

- **Add Backend:** the kind and its settings, then Test, then, for an encrypted one, the recovery key and its confirmation (§9). (changed in the build: the add tests the settings, saves the row, writes the marker, makes an encrypted backend's key file and check object, tests the backend in full, and records the recovery key's showing; a step that fails undoes those before it, so the place is free to try again. A backend's name must differ from every other's, ignoring case.)
- **Edit:** the name, and for S3 the endpoint, region, keys and path style, to follow a renamed endpoint or rotated keys. The bucket, prefix, path and encryption can't change once files are there. (changed in the build: encryption is chosen only when the backend is added, even for an empty one, as its recovery key is shown only then. The bucket, prefix and path can't change once a file is recorded or held on the backend, or while a Store uses it. Save runs Test first, and asks before saving over a problem. A save that changes more than the name builds the backend afresh from its row, while the reads and transfers under way on the old one finish there; a rename, Original's included, and a confirmed recovery key keep it as it is, the owner's review of PR C.)
- **Test**, **Show Recovery Key**, **Enter Recovery Key**, and **Remove** (only when empty, §8).
- **Original** can be renamed, not removed. Its path comes from the configuration.

**Stores.** A list: each Store, its backend (a drop-down), what's waiting to move (files and size), and its stuck files.

- **Changing the drop-down** confirms first, along the lines of: "214 files (3.1 GB) will move to Wasabi in the background. Until each has moved, it's read from where it is now."
- **Stuck files** open in a list with their errors, and **Retry**.
- **Which Stores:** every Store but CE's Default, which holds nothing (M1 §4), so Templates is listed too. A new Store starts on Original.

**Settings:** the holding period (30 days), the cache's size (20 GB) and parallel transfers (4).

**Nothing else changes.** Moving a matter to another Store keeps M1's rules and screens, and the mover follows. Documents don't show where they're stored.

(changed in the build) **Off on CE's own store.** On a server whose store isn't `HealdanStorage`, as outside Docker by default, the tab says storage backends are off and refuses every change, as every other storage bean then does nothing.

## 12. Docker, backups and restores (call)

**`DockerInit`.**

- `ConfigPlan` gets a list of keys Healdan owns, set on every start whatever the file holds, beside `IMAGE_KEYS`. Its one entry is `store=com.logicaldoc.healdan.storage.HealdanStorage`. The `tools` target's tests cover it. (changed in the build: `ConfigPlan.HEALDAN_KEYS`, and `DockerInit` checks the key was stored.)
- So the image's `context.properties`, which is upstream's, isn't edited, and an existing install moves to `HealdanStorage` on its first start.
- `healdan-storage`'s test `context.properties` sets the same key, so its tests run CE through `HealdanStorage`. `healdan-core`'s and `healdan-api`'s tests keep CE's backend: they can't use `healdan-storage`, which depends on `healdan-core`. (changed in the build: `AbstractStorageTestCase` writes `healdan-core`'s test `context.properties` again as `context-storage.properties`, with `store` swapped.)

**Compose and the entrypoint.**

- `compose.yaml` gains the `logicaldoc-cache` volume, mounted at `/opt/logicaldoc/cache`.
- The entrypoint hands it to the app's user, as it does the other two.
- (changed in the build: plan question 6 and call 9) The `Dockerfile` makes the cache's folder and declares its volume, and `docker/setenv.sh` names staging's, the cache's and the key files' folders as system properties (`healdan.storageStaging`, `healdan.storageCache` and `healdan.storageKeys`), as M4's `healdan.secretKey` is, since `ContextProperties` doesn't say where it was loaded from.
- **Extra local backends** are mounts in `compose.override.yaml`. The entrypoint doesn't change their owner, since an NFS share with `root_squash` refuses, so the Host Folders page says how to make them writable by user 1000.

**Backups.** The Backup and Restore page says what its two files now hold:

- **`logicaldoc-db.sql`:** the database, including each backend's settings and encrypted secret.
- **`logicaldoc-files.tgz`:** the configuration, including each backend's key, and the repository: Original's files, staging, and Original's `.held/` folder.
- **Not in either:** the cache, the S3 buckets, and other local backends unless their mounts are added to the `tar` command.

It then says:

- **A restore younger than the holding period finds every file** (§8). So keep the holding period at least as long as the oldest backup you'd restore.
- **Keep the recovery keys in a password manager,** apart from the backups (§9).
- **Offsite:** a recipe that sends the two files to a second Wasabi bucket with restic, which encrypts them with its own password. That covers the office burning down: the files are on Wasabi, and the database, configuration and keys are in the offsite backup. (changed in the build: the recipe runs restic's own image, `restic/restic:0.18.1`, so nothing is installed, and its commands were run against Garage, not yet against Wasabi.)
- **After restoring onto a new server,** open the Storage tab and press Test on each backend. (changed in the build: and stop the first server before the restored one starts: both have the same install id, so each would move, hold and purge files the other relies on, plan call 23.)
- **A file CE rewrote in place since the backup** comes back with its newest bytes, as on a local folder today (§6.2).

**The backup stops the app,** so the mover stops with it, and picks up after.

## 13. Security (calls)

- **Admins only.** The Storage tab's servlet checks the admin role on every call. Nothing about storage is in the JSON API.
- **Never logged or shown again:** the S3 secret, file keys, recovery keys. Logged: backend changes, a Store's backend changing, recovery keys shown, moves started and finished, stuck files, purges. (changed in the build: and recovery keys entered. Each move, each purged copy and each nightly fix is logged at debug, with totals at info, the mover's at most once a minute, so a Store's move doesn't roll the log away; stuck files are logged at warn.)
- **The endpoint** must be `https://`, except when it's an address on a private network, which the docs warn against. The Test call reaches only the endpoint the admin typed. (changed in the build: `http://` only when every address its host resolves to is private, 10/8, 172.16/12, 192.168/16 or fc00::/7, or loopback, and with path style on, so the host checked is the one connected to. The rule is checked again whenever the backend is built, not only when it's saved, and a host that can't be looked up counts as one that can't be reached.)
- **The cache and staging** are plain files in volumes only the app's user can read (§6).
- **Least privilege in Wasabi:** the docs give a policy limiting the user to the bucket (§10.1).

## 14. Overlap with other work

- **PostgreSQL** (backlog). No order between them. The storage tables use SQL that runs unchanged on MariaDB, HSQLDB and PostgreSQL, so that item just adds a copy of the migration to its `postgresql/` folder. Both items edit the Backup and Restore page and `DockerInit`, but in different places: the database commands and JDBC keys there; the volumes, the `store` key and the holding period here. Whichever lands second updates the page. (changed in the build: the SQL keeps the plan's PostgreSQL rules, in its Global Constraints: no Java `boolean` bound to an `int` column, an insert that may find its key taken run in a savepoint, a read before a write rather than an `UPDATE` that may match nothing, none of MariaDB's own syntax, and NULLs' order spelled out; plan question 3. This work landed first.)
- **M4.** This work reuses M4's encryption of secrets (M4 §13) and puts its tab beside M4's Sign-In tab. M4 is being built now, so it lands first. (changed in the build: M4 had landed; plan question 5.)
- **M3.6** (PDFs in CoffeePDF) touches the helper, not the server's storage. No overlap.

## 15. Upstream files, fork-owned files and docs pages

### 15.1 Upstream files

| File | Edit | Why |
|---|---|---|
| `pom.xml` | `<module>healdan-storage</module>`, after `healdan-api` | The new module is built |
| `logicaldoc-webapp/pom.xml` | An `artifactItem` for `healdan-storage`'s plugin zip, beside `healdan-core`'s and `healdan-api`'s. The antrun step already unzips every `*-plugin.zip` | The plugin ships in the WAR |

**Not edited:** CE's Java, `context-core.xml`, `context.properties` and the message bundles (§3.1, §11, §12). CE's hidden Storage screen and folder picker stay hidden, and `HealdanStorage` gives them nothing to list (§6.3). (changed in the build: PRs A to C edited exactly these two upstream files, both in PR A, and PR D none. CE's Stores screen wasn't hidden, and the `storage-hide-stores` seed hides it, with no upstream edit, §2.2.)

### 15.2 Fork-owned files

- `healdan-storage` (new): `HealdanStorage`, the backends, encryption, staging, the cache, the mover, held deletes, the listeners (§5.4), the `storage-record-original` and smoke seeds (§5.3, §16.1), the Storage tab's servlet, `plugin.xml`, the assembly, the POM, its test `context.properties`, and its tests; (changed in the build: it has no test `context.properties` of its own: the build copies `healdan-core`'s, and `AbstractStorageTestCase` writes it again as `context-storage.properties`, §12;)
- `healdan-core`: the migration for both databases, and whatever lets `healdan-storage`'s seeds join its seed order (§17);
- `logicaldoc-gui`'s Healdan settings package: the Storage tab, its bean and its service interface;
- `docker/tools/src/ConfigPlan.java` and its tests, `docker/entrypoint.sh` and `compose.yaml` (§12);
- `docker/tests/compose.storage.yaml` (new), `docker/tests/test-storage.sh` (new), and step 5e in `docker/smoke-test.sh` (§16.1); (changed in the build: step 5f, as M4's `test-signin.sh` is 5e, plan question 4;)
- `CLAUDE.md` (Docker, Healdan's code, the upstream edits list), `docs/ROADMAP.md`, and the capability map's §3.2.
- (changed in the build: plan questions 1, 5, 6 and 7) Also the `Dockerfile` (the cache's folder and volume), `docker/setenv.sh` (the folders' system properties), `docker/tools/src/DockerInit.java` (it checks `store` was stored), `docker/tools/test/WarStorageTest.java` with its line in `docker/tools/build.sh`, `docker/tests/test-image.sh`, and `docker/tests/garage/` (Garage's configuration and setup script); `healdan-core`'s seams (§4), with `AdminSignInAlert`, which now sends through `OutgoingEmail`'s public way to send a templated email, `HealdanMigratorTest`, which pins the schema's version, and its test-jar's `PluginFixtures` and `SignInTestHooks`; `healdan-api`'s `ProblemMapper`, `DocumentResource` and `openapi.yaml` (§6.5); the `storage-settings` and `storage-hide-stores` seeds; and the GUI's `ProviderDialog`, whose new-key question the Storage tab now shares (`NewKeyQuestion`).

### 15.3 Docs pages (the docs site, `site/content/`)

**New:**

- `admin/storage-backends/`:
  - what a backend is, and Original;
  - adding a local folder or an S3 bucket, and Test;
  - encryption and the recovery key, and what losing it means;
  - choosing a Store's backend, and how moves work;
  - waiting and stuck files, and Retry;
  - held deletes and the holding period;
  - the cache;
  - what happens during an outage, and the 24-hour alert;
  - removing a backend.
- `install/cloud-storage.md`: setting up Wasabi:
  - the bucket and the region's endpoint;
  - a user limited to the bucket;
  - the rule for incomplete uploads;
  - the 90-day minimum, and why the holding period suits it;
  - other S3 services should work, but only Wasabi and Garage are tested.

**Changed:**

- `admin/stores/index.md`: "Where Files Are Kept" rewritten.
- `admin/healdan-settings/index.md`: the Storage tab.
- `install/backup-and-restore.md`: what the backup holds, the holding period, recovery keys, the restic recipe, Test after a restore (§12).
- `install/host-folders.md`: the cache volume, and folders for extra local backends.
- `install/what-runs.md`: the cache volume.
- `install/upgrading.md`: the first start after the upgrade records every existing file, which takes longer on a large repository.
- `install/troubleshooting.md`: a failing backend, a missing marker, stuck files, the alert.
- `install/known-limitations.md`:
  - a backend per Store, not per matter;
  - encryption can't be added to a backend that holds files;
  - a file CE rewrites in place keeps only its newest bytes, as today.
- `developer/tests.md` and `developer/continuous-integration.md`: the Garage overlay and step 5e.
- `developer/how-healdan-extends-logicaldoc.md`: replacing CE's `store` bean through the `store=` key.

(changed in the build) The Admin Guide's `admin/storage-backends/` is a section of eight pages: Adding a Backend, Choosing a Store's Backend, Encryption and Recovery Keys, Held Deletes and the Holding Period, Removing a Backend, The Read Cache, Waiting and Stuck Files, and When a Backend Fails. Known Limitations also says which of CE's screens don't show the outage message and that CE's Stores screen is hidden. The smoke step the Developer Guide names is 5f. These changed too: `install/configuration.md` (the settings `.env` owns, and those with no screen), `developer/building-from-source.md` (the per-module test commands and the test-jar rule `healdan-storage` shares with `healdan-api`), plan question 6; and `developer/api.md` (`503 storage-unreachable`), question 7.

## 16. Verification

### 16.1 Automated

**`healdan-storage`'s tests** (JUnit 4, HSQLDB, on `AbstractHealdanTestCase`). CI's shards find them by themselves (CLAUDE.md). S3 is the in-memory fake, and local backends sit under `target/`. (changed in the build: three layers, plan call 17. `MemoryBackend`, an in-memory backend with faults on demand, which acts as a bucket when asked to; `FakeS3`, a JDK HTTP and HTTPS server in the tests that answers `S3Backend`'s calls as they leave the SDK and refuses what S3 refuses, so a refused `Content-MD5`, an aborted multipart upload and a timeout are tested in CI; and Garage on the smoke stack. No Testcontainers, as the test shards don't run Docker. `BackendContract` runs the same checks on every backend.)

- **CE's real flows through `HealdanStorage`:** create, check in, replace a file, delete a version (with `1.2` beside `1.20`, §3.1), purge from the trash, copy, and M1.5's previews and thumbnails.
- **Saves:** a local Store's file goes straight to its backend; an S3 Store's goes to staging; a Store that can't be told yet goes to staging.
- **The mover:**
  - staged files first;
  - a verified copy before the row moves, and the source held after;
  - a rewrite mid-copy caught by the write counter (changed in the build: the write id, §6.1);
  - retries, then stuck, then Retry;
  - a failing backend pausing its transfers;
  - a Store switching backend.
- **Moves of Store:** a matter moving between Stores, a document moving between matters in different Stores, and the nightly check fixing a wrong row.
- **Held deletes:** S3 and local; reads finding a file in `.held/`; the purge, and its skip of an object a row points at again.
- **The cache:** hits, the size cap, the least recently read dropped first, rewritten files, large files read by range.
- **Encryption:**
  - round trips, and ranges across segment edges;
  - a wrong key refused, and the backend failing;
  - a swapped object refused;
  - the recovery key's confirmation, and Enter Recovery Key.
- **Local backends:** a missing marker refuses writes.
- **The recording seed:** every existing file recorded, including an orphan.
- **The tab's servlet:** admins only, the secret never read back, Remove refused while a backend holds files.

**`docker/tools`:** `ConfigPlan` sets `store=` on every start, over any value. (changed in the build: and `WarStorageTest` builds the S3 client as `S3Backend` does, and runs Tink's streaming AEAD, on the WAR's own libraries, so a jar the plugin zip leaves out fails the image's build.)

**Smoke test, step 5e** (`docker/tests/test-storage.sh`, with the `docker/tests/compose.storage.yaml` overlay, which `docker/smoke-test.sh` adds): (changed in the build: step 5f, as M4's `test-signin.sh` is 5e, plan question 4.)

- **The overlay** adds a Garage container and a one-shot service that sets up its layout, a bucket and a key with fixed test values before the app starts. It sets `HEALDAN_SMOKE_STORAGE=true`, and a smoke seed adds an encrypted S3 backend on Garage, with its recovery key marked as confirmed, and a "Smoke Cloud" Store on it with matter 1.2. (changed in the build: Garage v2.5.0, set up through its admin API from `busybox`, as the trial did, plan call 18. The seed adds the backend through `StorageAdmin`'s add, as the Storage tab does, then confirms its recovery key; Smoke Cloud has matters 1.2 and 1.3, so that 1.3 keeps files on Garage for step 8 to read back, call 19; and the seed sets `storage.sweepSeconds` and `storage.retestSeconds` to 5, call 12.)
- **The script checks that:**
  1. a document uploaded through the API into matter 1.2 arrives in Garage, and the object holds none of the document's text (read with `curl --aws-sigv4`);
  2. downloading it through the API gives the same bytes back;
  3. with Garage stopped, a save still succeeds and waits in staging, and once Garage is back the mover uploads it;
  4. moving matter 1.2 to the local Smoke Store brings its files home, and Garage still holds its copies (held);
  5. step 8's restart keeps all of it.

  (changed in the build) An upload is cached once it reaches Garage (§6.4), so the files it reads back through Garage are moved there from the Smoke Store first, and the checks that claim a read from the cache stop Garage. It also checks that, with Garage stopped, an uncached file answers `503` through the API, `/download` and a shared link, and `/convertpdf` of an uncached PDF serves CE's placeholder (§6.5); and at step 8, that a file is read through Garage for the first time, and one from the cache rebuilt from its folder with Garage stopped.
- It runs on amd64 and arm64, as the rest of the smoke test does. The plan checks Garage's image covers both.

### 16.2 The trial (the plan's first task)

A short trial against the owner's Wasabi bucket, and against Garage, before anything else is built, as M3's and M3.5's trials did:

- uploads with the SDK's checksums "when required" and a `Content-MD5`;
- ranged reads;
- multipart uploads, and aborting one;
- listing and deleting;
- the speed of a save's upload and of a cold read, from the owner's server.

Anything it finds goes into the plan's rulings before the build starts.

(changed in the build) The trial ran on 2026-11-07, on the owner's server, against Garage v2.5.0 and a throwaway Wasabi bucket, and every check passed on both: a right `Content-MD5` taken and a wrong one refused, no SDK checksum headers sent, exact ranges, multipart uploads and their abort, listing, `HEAD` and deletes, and request bodies streamed rather than held in memory. Its rulings (among them two attempts rather than the SDK's four, how failures map, and a missing bucket found with `GET`, §10.1) and its speeds are in the plan's decision log.

### 16.3 Manual acceptance (owner)

Against the owner's server and the owner's Wasabi bucket:

1. **Set up.** Following the Install pages, add a Wasabi backend in the Storage tab. Save the recovery key, confirm it, and Test passes.
2. **The first move.** Switch Client Work to Wasabi. The tab shows its files waiting, then none. Wasabi's console shows the objects, and a downloaded object is unreadable.
3. **Saves.** Save a document into a Client Work matter from Word through the helper. The save is as quick as before, and the file reaches Wasabi within minutes.
4. **Personal stays home.** Save into Personal. Nothing new appears in Wasabi.
5. **Outage.** Take the server's internet away. A save into Client Work still works, a recently opened Client Work document opens, an old one fails with the clear message, and the tab shows Wasabi failing. Bring the internet back, and the waiting file uploads.
6. **Moving out.** Move a matter from Client Work to Personal. Its files come back to the server, and its Wasabi copies stay until the holding period ends (shortened to 1 day for the walkthrough), then go.
7. **Restore.** Restore the night's backup onto a fresh stack. Every document opens, Client Work's from Wasabi, after Test passes.
8. **Recovery key.** On that fresh stack, delete the backend's key file. The backend fails. Enter the recovery key, and it works again.

## 17. Left to the builder

- Table and column names and types, within §5.1.
- The key file's name and layout (§9), and the cache's layout on disk (§6.4).
- The exact messages, within §6.5 and §11.
- The Storage tab's layout, within §11.
- How the rows' Stores are kept current on CE's move paths: listeners, `HealdanHooks`' hooks, or both, so long as the nightly check backs them up (§5.4). `healdan-core` registers `HealdanHooks`' one implementation today, so reaching it from `healdan-storage` may need a small seam there.
- How `healdan-storage`'s seeds join `SeedRunner.ORDER`, which lives in `healdan-core` (§4).
- The mover's gaps between retries and its sweep's timing, within §7.
- The libraries' versions (§4).
- **Found while designing:** CE's prefix match in `FSStore.listResources` (§3.1) could delete another version's files when a version was deleted or a file replaced. (changed in the build: it was fixed before this was built, as a separate fix to CE's `FSStore`, in [Healdan-Project/healdan#153](https://github.com/Healdan-Project/healdan/pull/153), §3.1; `HealdanStorage` matches a file version exactly from its rows as well. Plan question 2.)

(changed in the build: what the builder chose.) The tables of §5.1 in `V9__storage.sql`, with V10's index (§5.1), `hd_storage_file` and `hd_storage_held` read and written through JDBC, with no second-level cache, and the other three Hibernate entities, as M4's tables are (plan call 3); the key file `backend-<id>.key` in `healdan-storage-keys/` (§9), and the cache's entries as `<backendId>/<docId>/<resource>@<write>` (§6.4); the outage's messages in `StorageUnreachableException` (§6.5); the tab's layout, as the Admin Guide's Storage Backends pages show it; listeners at position 120 and a `FolderTreeListener` hook in `healdan-core`, which `HealdanHooksImpl.afterFolderRestore` calls (§5.4); the seeds' names in `SeedRunner.ORDER`, `storage-settings`, `storage-record-original` and `storage-hide-stores` after `signin-settings`, and `smoke-storage` after `smoke-signin` (plan call 7); retries after 1, 2, 4, 8, 16, 32, 60, 60 and 60 minutes, a sweep every 300 seconds and a failing backend's retest every 60 (§7); and the AWS SDK for Java 2.57.8 and Tink 1.24.0 (§4).

## 18. Decisions made in this session

**Owner (2026-11-01):**

- **First use:** split Stores by place from the start, not everything on S3, and not S3 as an offsite copy (§1, §3.3).
- **Places:** local disk or NFS, and Wasabi (§3.3).
- **Why:** offsite durability, capacity and cost, and keeping some Stores off the cloud. The last is a hard rule: nothing of a local Store's, not even a preview, goes to the cloud (§1, §2.3).
- **Moves:** in the background. The move takes effect at once, each file is copied, checked, then its source removed, and until then it's read from where it is. A whole Store switching backend works the same way (§7).
- **Encryption:** Healdan encrypts before a file leaves the server, with a key Healdan keeps and a recovery key the admin keeps offsite (§9).
- **Restores:** Healdan holds every delete for a holding period, on every backend, rather than relying on bucket versioning (§8).
- **Settings:** a Storage tab in Healdan Settings, not CE's hidden screens, and not configuration files (§11).
- **Saves:** land on the server first and upload in the background, rather than waiting for Wasabi (§6.1).
- **Reads:** a capped cache on the server (§6.4).
- **The approach:** a Healdan class behind CE's `store=` key, with its own S3 client, rather than rclone underneath or CE's own multi-backend model (§1, §4).
- **The roadmap:** this stays in the backlog, with this spec linked, until the owner decides when to build it. (The owner decided to on 2026-11-07, and it was built as the backlog item.)

**Calls (approved section by section; open to the owner's review of this spec):**

- a new `healdan-storage` module, with the tables in `healdan-core`'s Flyway folder (§4, §5.1);
- each Store's backend recorded in `hd_storage_store`, not CE's `ld_folder_store`, which is keyed by cluster node (§3.1, §5.1). The roadmap's glossary names CE's per-folder mapping for a Store's location; its row changes when this is built (changed in the build: it names `hd_storage_store` now);
- one row per file, and the existing files recorded on the first start (§5.1, §5.3);
- backend 1, Original, as today's folder in CE's layout (§5.2);
- a row's Store kept current by listeners and hooks, and checked nightly (§5.4);
- staging in the repository volume, and the cache in a volume of its own (§6.1, §6.4);
- a file version matched exactly in lists (§6.3);
- the mover's verification, write counter, order, retries and stuck files (§7) (changed in the build: a write id, not a counter, and a copy the mover can't commit is discarded, not held, plan calls 20 and 21);
- a daily email to admins when files have waited, or a backend has failed, for 24 hours (§7);
- local held deletes in a `.held/` folder, with reads looking there (§8);
- Tink's streaming AEAD, a key per backend in the configuration folder, and a confirmed recovery key (§9);
- a check object that catches a wrong key (§9);
- the AWS SDK for Java 2.x, checksums "when required" outside AWS, and `Content-MD5` (§10.1);
- a marker file in every local backend (§10.2) (changed in the build: in every S3 backend too, each naming its install and backend, and no two backends sharing a place, plan calls 14 and 23);
- an `https://` endpoint, except on a private network (§13) (changed in the build: and then only with path style on);
- `ConfigPlan` owning the `store=` key (§12);
- no compression; no backend per matter; encryption fixed at creation (§2.2);
- Garage as CI's stand-in, and a trial against Wasabi first (§3.2, §16).
