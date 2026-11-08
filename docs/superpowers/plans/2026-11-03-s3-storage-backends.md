# "S3, and a storage backend per Store" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each Store keeps its files on its own storage backend, a local or NFS folder or an S3 bucket, as the approved spec describes: Personal on the server's disk and Client Work on Wasabi for the owner, with saves landing on the server first, moves in the background, Healdan's own encryption for what leaves the server, held deletes, a capped read cache, and a Storage tab in Healdan Settings.

**Architecture:**
- **`healdan-storage`** (new plugin module, JPF id `healdan-storage`, `com.logicaldoc.healdan.storage`) holds all of it:
  - `HealdanStorage`, which implements CE's `Store` interface and becomes CE's single `store` bean through the `store=` key. It looks up each file's row and sends the call to a backend, staging or the cache;
  - the backends behind one small interface: a local folder, an S3 bucket through the AWS SDK for Java 2.x, and, in tests only, an in-memory fake;
  - encryption with Tink's streaming AEAD, a key file per encrypted backend in the configuration volume, and a recovery key;
  - staging and the read cache, folders on the server;
  - the mover, held deletes and the purge, the nightly check, the failing-backend checks and the alert, and the lock per file that keeps saves, the mover and the purge apart (call 20);
  - the listeners that keep each file's Store current;
  - the Storage tab's RPC servlet at `/frontend/healdanstorage`;
  - a filter over CE's download routes that shows the outage message (question 7);
  - the `storage-record-original` and `smoke-storage` seeds.
- **`healdan-core`** gets the tables (one Flyway script for both databases) and small seams (question 6): the seed names in `SeedRunner.ORDER`, a start hook in `HealdanStartup.start()`, a folder-restore hook in `HealdanHooksImpl`, the storage secrets in `SecretBox`'s count and in M4's new-key messages, a public way to send a templated email (`OutgoingEmail`), and `PreviewWorker` waiting out an unreachable backend.
- **`healdan-api`** answers an unreachable backend as `503 storage-unreachable` (question 7).
- **The old web page:** a Storage tab in Healdan Settings, beside M4's Sign-In tab, and the outage message on its downloads (question 7).
- **Docker:** `ConfigPlan` sets `store=` on every start; a `logicaldoc-cache` volume; the paths in `docker/setenv.sh`.
- **CI** tests the logic against an in-memory backend, the S3 client against a fake S3 server inside the Java tests, and the whole flow against Garage on the smoke stack, on amd64 and arm64.

**Tech Stack:**
- **Server:** JDK 25, Spring 6.2.8, Hibernate 7, Flyway 13.10.3, JUnit 4 with HSQLDB, as M4.
  - New, all Apache 2.0, pinned at their current releases on 2026-11-03:
    - `software.amazon.awssdk:s3` **2.57.8** (released 2026-10-30), with `software.amazon.awssdk:url-connection-client` **2.57.8**, the JDK's own HTTP client, and `apache5-client`, `apache-client` and `netty-nio-client` excluded (call 1). The SDK's `crt-core` is a 20 KB interface jar; the native CRT (`aws-crt`) isn't a dependency.
    - `com.google.crypto.tink:tink` **1.24.0** (released 2026-10-16).
  - The fake S3 server in tests is `com.sun.net.httpserver.HttpServer`, as M4's `FakeProvider`.
- **GUI:** GWT 2.12 and SmartGWT 14.1, as M1.
- **Stand-in S3:** `dxflrs/garage:v2.5.0` (AGPL-3.0, a separate test container, so its licence doesn't touch Healdan's code; released 2026-10-24, with amd64 and arm64 images on Docker Hub, checked on 2026-11-03), only in the smoke stack, set up by a one-shot `busybox:1.37` service (amd64 and arm64, checked the same day).

**What the libraries add to the image** (spec §4 expected 10 to 15 MB), from `mvn dependency:copy-dependencies` on a scratch POM with the exclusions above:

| Jars | Count | Size |
|---|---|---|
| The AWS SDK: `s3`, its protocol, auth, region, retry and checksum jars, its shaded Jackson, and `url-connection-client` | 30 | 8.38 MB |
| Tink | 1 | 2.84 MB |
| Tink's `gson` 2.13.2 and `protobuf-java` 4.33.6 (call 2) | 2 | 2.08 MB |
| `reactive-streams` 1.0.4, `error_prone_annotations` 2.41.0 | 2 | 0.03 MB |
| `slf4j-api` and `jsr305`, which the WAR already ships, so the plugin zip leaves them out | 2 | 0.06 MB |
| **What the WAR gains** | **35** | **about 13.3 MB**, or about 11.3 MB if call 2 leaves `gson` and `protobuf-java` out |

The jars are already compressed, so the image's pull grows by about the same. Task 1 measures the real figure from the built plugin zip, and states it in PR A.

**Status:** written on 2026-11-03, after the spec's approval on 2026-11-01. The owner answered questions 1 to 9 on 2026-11-03, and the tasks follow the answers. **Approved** by the owner on 2026-11-07, for subagent-driven building. The trial (Task 0) ran the same day and passed on Garage and on Wasabi; its findings and rulings are in the decision log, and Tasks 5, 12 and 16 follow them. A pre-flight scan the same day read every task against the others, the Global Constraints and the code; its rulings are folded into the tasks they change, and listed in the decision log. PR A (Tasks 1 to 8) was built on 2026-11-07, subagent-driven, each task reviewed against its brief before the next began, then the branch as a whole, and opened as [Healdan-Project/healdan#209](https://github.com/Healdan-Project/healdan/pull/209). The owner's code review of it the same day brought one fix commit: the Javadoc that broke the Docker build, the plugin zip's jar names, savepoints around the storage rows' inserts (a file's lock ends before its transaction commits), one read path for streams and channels, and smaller fixes. PR B (Tasks 9 to 13) was built on 2026-11-08 in the same way; what its reviews changed is noted in Task 13 and carried into Tasks 14, 16 and 19. PRs C and D are next.

**Spec:** [`docs/superpowers/specs/2026-11-01-s3-storage-backends-design.md`](../specs/2026-11-01-s3-storage-backends-design.md), approved by the owner on 2026-11-01. Every § below points into it.
- Where the two disagree, the spec wins, except for the owner's answers to the questions and the calls under "Spec corrections and calls this plan makes".
- M4's spec and plan still hold for `SecretBox`, the Healdan Settings panel and the alert's email.

## Global Constraints

Every task's requirements include these.

- **Upstream edits are only these two** (spec §15.1):
  - `pom.xml`: `<module>healdan-storage</module>`, after `healdan-api`;
  - `logicaldoc-webapp/pom.xml`: an `artifactItem` for `healdan-storage`'s plugin zip, after `healdan-api`'s (~613). The antrun step already unzips every `*-plugin.zip` (~691).

  No CE Java, `context-core.xml`, `context.properties` or message bundle changes. Question 1's answer is a seed, not an upstream edit; any other upstream edit needs the owner's say-so and a line in the spec's §15.1.
- **`HealdanStorage` is never a Spring component.** Only `<bean id="store" class="${store}"/>` makes it: a `@Component` would make a second `Store` bean, and `Store.get()` (`Context.get(Store.class)`) would fail. Every other `healdan-storage` bean, and its download filter (Task 13), does nothing unless `Store.get()` is a `HealdanStorage`, so a WAR still on `FSStore` (outside Docker, or before PR C) is untouched.
- **`HealdanStorage.init()` reads no table and is safe to call again.** Spring calls it before Flyway has run, and CE's `SettingServiceImpl.saveStoreSettings` calls `Store.get().init()` again.
- **One lock per file** (call 20). `FileLocks` is a striped lock in the JVM, keyed by `(docId, resource)`. `HealdanStorage` holds it while it writes or deletes a file's bytes and updates its row; the mover holds it for its commit (the row's check, the rename into place, the row's update, then the source's hold, or the staged copy's rename aside for the cache); the purge holds it while it checks and deletes a held S3 object. Every per-file `StoredFiles` write holds it; two bulk writes don't (pre-flight, 2026-11-07): the recording seed's inserts, which run before any save can (Task 9), and `setStore`'s update of a moved tree's rows, whose race with a mover's commit at worst leaves a row misplaced for the next pass, which the nightly check backs up (Tasks 2 and 10). Healdan runs one node (spec §2.2), so a lock in the JVM is enough.
- **SQL that runs on PostgreSQL too** (roadmap principle 9; [PostgreSQL recon](../../reference/postgresql-recon.md) §2.5 and §4). Healdan's tests run on HSQLDB and its stack on MariaDB, and neither refuses what PostgreSQL does, so every task's review checks these by reading:
  - **No `boolean` on an `int` column.** On PostgreSQL, Hibernate binds a Java `boolean` as SQL `boolean`, as pgjdbc's `setBoolean` does, and PostgreSQL won't store either in an `int` column or compare one with it (recon §2.5). Healdan's entities have no `boolean` field today. `StorageBackend`'s three flags are `int` fields, as their columns are, with `boolean` accessors, which Hibernate doesn't map because Healdan's entities annotate fields; HQL compares them with `1`. `StoredFiles` binds `hd_stuck` as `stuck ? 1 : 0` and reads it as `rs.getInt(…) == 1`, as `SearchFolderRuleDAO` does `hd_onlymine`.
  - **No statement that's expected to fail inside a transaction, unless it runs in a savepoint.** On PostgreSQL a failed statement aborts its transaction, and the commit then rolls all of it back, even when Java caught the error (recon §4.2). So no insert that catches a duplicate key to mean "already there" outside `Savepoints` (`healdan-storage`, PR A's review): `Savepoints.run` runs statements in a savepoint when the caller's transaction holds the connection, and rolls back only to it on a failure, and `Savepoints.insertIfAbsent` is the one insert that may find its key taken. `recordWrite`'s and `HeldCopies.hold`'s pattern is to read the row by its key under the file's lock, then insert when there's none and update otherwise; the insert is `insertIfAbsent`, and becomes an update when another transaction took the key since the read, as a file's lock ends before its transaction commits. Never an `UPDATE` that can match nothing, which on MariaDB's REPEATABLE READ takes a gap lock until commit, so inside CE's long transactions, such as a folder copy, it would stall every concurrent save of a new file and could deadlock with one through the file locks (changed in the build, Task 6). No read that tries one type and then another. `HealdanStorage`'s writes can run inside CE's transactions (`HibernateFolderDAO.copy` creates every copied document in one), and the store-tracking listeners read inside CE's save before they write after its commit (Task 10), so a failure there that's logged and swallowed would still lose CE's whole save.
  - **Typed reads ask for the column's own type** wherever SQL goes through CE's DAO helpers or `ResultSet.getObject(i, type)`: `bigint` columns and `count(*)` as `Long`, `int` columns as `Integer`, and never CE's `queryForInt` on an `int` column, which asks for a `Long` first and, on PostgreSQL, loses the transaction when it retries (recon §2.6 and §4.5). Spring's `JdbcTemplate`, which `StoredFiles` and `HeldCopies` use, reads `Long.class` and `Integer.class` with `getLong` and `getInt`, which pgjdbc converts.
  - **No MariaDB-isms:** none of recon §4.4's list (`ON DUPLICATE KEY`, `INSERT IGNORE`, `REPLACE`, `IFNULL`, `IF()`, `LIMIT a, b`, backticks, date arithmetic); `cast(x as …)`, never PostgreSQL's `::`, which CE's `NamedParameterStatement` reads as a parameter. `limit ?` and `limit ? offset ?` run on all three.
  - **The same answers on both:**
    - an `order by` on a key that can be NULL (`hd_storeid`, `hd_waiting` and `hd_nexttry` among them) says where NULLs go, as `case when x is null then 1 else 0 end, x`: the two databases put NULLs at opposite ends, and MariaDB has no `nulls first` or `nulls last` (checked on 11.4.13, 2026-11-07);
    - text compared or kept unique in SQL (a backend's name, bucket, prefix or path) is trimmed in Java first, as MariaDB's `utf8_bin` ignores trailing spaces and PostgreSQL doesn't (recon §4.3);
    - nothing relies on fractions of a second, which MariaDB's `datetime` drops and PostgreSQL's `timestamp` keeps: times are compared with `<` or `<=`, never `=`;
    - nothing relies on `REPEATABLE READ`, MariaDB's default: PostgreSQL's is `READ COMMITTED`, and a conditional update such as `moved`'s `where hd_write = ?` behaves the same under both.
- **Code location:** `healdan-storage`'s `com.logicaldoc.healdan.storage` and its packages `backend`, `crypto`, `mover`, `seed` and `web`; the GUI's `frontend.client.healdan.settings` (the tab), `.beans` and `.services`.
- **The plugin:** JPF id `healdan-storage`, version the root POM's (9.3.2 today), requiring `logicaldoc-core` and `healdan-core`; class `com.logicaldoc.healdan.storage.HealdanStoragePlugin`. Extensions, each id unique (JPF drops the whole plugin on a duplicate):
  - `DocumentListener`, id `healdanStorageDocuments`, class `com.logicaldoc.healdan.storage.StoreTrackingDocumentListener`, position `120`;
  - `FolderListener`, id `healdanStorageFolders`, class `com.logicaldoc.healdan.storage.StoreTrackingFolderListener`, position `120`.
- **Places** (call 9):

  | What | Docker | System property (`docker/setenv.sh`) | Default without it |
  |---|---|---|---|
  | Original, backend 1 | `/opt/logicaldoc/repository/docs/` | none: `store.1.dir`, always | |
  | Staging | `/opt/logicaldoc/repository/staging/` | `healdan.storageStaging` | `staging/` beside `store.1.dir` |
  | The cache | `/opt/logicaldoc/cache/` | `healdan.storageCache` | `cache/` beside `store.1.dir` |
  | Backends' key files | `/opt/logicaldoc/conf/healdan-storage-keys/` | `healdan.storageKeys` | `healdan-storage-keys/` beside `SecretBox`'s key file |

  Tests set all three under `target/`, as they do `healdan.secretKey`.
- **Rows:** `hd_storage_file.hd_backendid` is `0` for staging, which has no `hd_storage_backend` row. Backend 1's row, Original, is made by the migration. Each write gives its row a new `hd_write`, a random 64-bit id (call 21): unlike a counter, it never repeats when a row is deleted and made again, or when a database is restored, so the mover's check and the cache's keys can trust it. Backend ids are `long` everywhere, as `PersistentObject`'s are.
- **Object keys:** the backend's prefix, then CE's layout: `healdan/123/45/doc/1.2-thumb.png`. A prefix is stored without a leading `/` and with one trailing `/`. The marker object is `<prefix>.healdan-backend` (call 23), the check object `<prefix>.healdan-check`, and Test's objects `<prefix>.healdan-test-<uuid>`. None can clash with CE's layout, whose names start with a digit. A prefix and a local path are at most 255 characters (Task 14), so a held location (a prefix, at most 30 characters of CE's folders for a doc id, and a resource of at most 255) fits `hd_storage_held.hd_location`'s 700 (Task 2).
- **A local backend's root** holds `.healdan-backend` (the marker, holding this install's id and the backend's id), `.held/` (held deletes, in CE's layout), and, if encrypted, `.healdan-check`.
- **Settings in `hd_setting`:**

  | Key | Default | Range | In the tab |
  |---|---|---|---|
  | `storage.holdDays` | `30` | 1 to 365 | yes |
  | `storage.cacheGb` | `20` | 1 to 10000 | yes |
  | `storage.transfers` | `4` | 1 to 16 | yes |
  | `storage.sweepSeconds` | `300` | 5 to 3600 | no; the smoke seed sets `5` (call 12) |
  | `storage.retestSeconds` | `60` | 5 to 3600 | no; the smoke seed sets `5` |
  | `storage.alert.sent` | none | | no: the day the last alert went |
  | `storage.installId` | a random UUID, made once | | no: names this install in every backend's marker (call 23) |

- **Timings:**
  - every backend is checked at start, before the mover's first pass, and its transfers wait until its first check passes; a write that reaches a backend still unchecked runs that check itself first, holding the backend's check lock, and is refused only if the check fails, so seeds and tests, which run before `start()`, can write (call 24, changed by the pre-flight scan, 2026-11-07);
  - the mover sweeps every `storage.sweepSeconds` and is woken by a staged save, a document or matter changing Store, a Store switching backend, and Retry;
  - a failing backend is tested again every `storage.retestSeconds`;
  - a file's retries wait 1, 2, 4, 8, 16, 32, 60, 60 and 60 minutes; the tenth failure marks it stuck;
  - the alert: a file waiting (`hd_waiting`, since its row's backend stopped matching its Store's), or a backend failing, for more than 24 hours; at most one email a day;
  - the purge runs daily, and the nightly check at 03:17 server time, and neither in the first 10 minutes after a start;
  - every S3 call has a 10-second connection timeout and a 60-second read timeout, which tests shorten.
- **Sizes:** S3 uploads over 64 MiB go up in 16 MiB parts; Tink's segments are 1 MiB; a file over a tenth of the cache's size isn't cached (§6.3).
- **Encryption** (§9):
  - Tink's streaming AEAD with `AesGcmHkdfStreamingParameters`: 32-byte key, 32-byte derived keys, HKDF-SHA256, 1 MiB ciphertext segments (Tink's `AES256_GCM_HKDF_1MB`);
  - the associated data is the UTF-8 of `<docId>/<resource name>`; the check object's is `healdan-check`;
  - a key file is the key's 32 bytes, mode 600 in a folder of mode 700;
  - the recovery key is the same 32 bytes in Crockford's base32: 52 characters in 13 groups of 4, read case-insensitively, with spaces and hyphens ignored (call 15).
- **The endpoint** (§13) is `https://`, or `http://` only when every address its host resolves to is private (10/8, 172.16/12, 192.168/16, fc00::/7) or loopback, as Garage's `http://garage:3900` is on the smoke stack.
- **Never logged, sent to a client or shown back:** the S3 secret, key bytes and recovery keys (but for Show Recovery Key, to an admin), and sealed values. **Logged** (§13): backend changes, a Store's backend changing, recovery keys shown, moves started and finished, stuck files, purges, and the nightly check's fixes.
- **Docs ship in the PR with the code they describe** (CLAUDE.md), and so do CLAUDE.md's build, test, CI and Docker facts, which change together with the Developer Guide's. The pages the spec names have their own tasks, 18 to 20; a page or fact that an earlier task changes is in that task (Tasks 1, 13, 14, 15 and 16). Title Case for titles and labels, British spelling.
- **CI time.** Each PR states how many seconds it adds to `build`, the `Tests` jobs and the Docker smoke test.

## Review Focus

These are the inputs most likely to hurt someone that a task's main tests could miss. Each has a test in the task named.

1. **A file that exists nowhere:** a source removed before the target is verified; a held S3 object purged while the mover uploads it again; a save landing on the key the mover is writing, holding or deleting; a file deleted and made again during its transfer; a crash between the copy and the row's update; two backends, or two servers, sharing a bucket and prefix (Tasks 7, 11 and 14).
2. **A local Store's file reaching S3:** a Store resolved wrongly (Templates, the Default workspace, an alias, a document with no folder yet, an `ld_path` CE left stale), a document or matter moving, a folder restored into another Store, a Store changing while its file's upload waits, and the nightly check as the backstop (Tasks 6, 10 and 11).
3. **The wrong key:** a key file swapped or rebuilt from a mistyped recovery key, writes that then go on under it, and writes in the minutes after a start (Tasks 4, 11 and 12).
4. **CE's own flows through `HealdanStorage`:** exact file versions (`1.2` beside `1.20`), `replaceFile`'s deletes and a second replace, `destroyDocument` after its rows are gone, aliases, a 0-byte file, and CE's size check on an encrypted backend (Task 6).
5. **An empty mount point written to** when an NFS share didn't mount, at any start or the first after the upgrade (Tasks 3 and 9).
6. **Secrets:** the S3 secret in a log, an answer, the GUI or an exception's message; a key made over sealed values while the key file is missing (Tasks 2, 5 and 14).
7. **An outage spent as failures:** retries used up, files marked stuck, or previews given up, while a backend is merely unreachable; a download that silently arrives empty (Tasks 11 and 13).

## Questions for the Owner

Each is a place where the spec and master disagree, found while checking every class, file, key and line the spec names against master at `56e9815`. The owner answered all nine on 2026-11-03, each marked **Owner**; questions 2 to 6 accept the plan's default, and 1, 8 and 9 change it.

1. **CE's Stores screen isn't hidden.** Spec §2.2, §11 and §15.1 call CE's Storage settings "hidden", and say `HealdanStorage` gives them nothing to list. On master, Administration → Settings → Stores (menu 105, `SettingsMenu.addStores`, enabled for admins) opens `RepositoriesPanel`, whose Storage grid (`StoresPanel`) lists "Store 1" from `store.1.dir` in `context.properties` (`StoresDataServlet.printStores`), not from the bean. `admin` can change the path and Save, which writes `store.1.dir` and calls `Store.get().init()`; Test answers false, as `newStore` returns null. Only feature 52 (`Feature.MULTI_STORE`) hides Add Store. Options:
   - (a) Leave it. It shows Original's folder as "Store 1", and changing it moves no files, as today, but with `HealdanStorage` a new path has no marker, so every save into Original then fails.
   - **(b) A seed that turns menu 105 off,** which also hides that screen's Folders tab (`conf.dbdir`, `exportdir`, `importdir`, `logdir`, `plugindir` and `userdir`, paths inside the container, which in Docker only break things when changed).
   - (c) A GUI hook in `RepositoriesPanel` that drops the Storage grid: an upstream edit, and a line in §15.1.

   **Owner (2026-11-03): (b).** A `storage-hide-stores` seed, as `hide-dropbox` hides Dropbox's menu: it deletes menu 105's ACL rows and marks the menu deleted, which every menu query leaves out, admins' included. Its `enabled()` is false unless `Store.get()` is a `HealdanStorage`, as the other storage seeds' are, so the screen goes exactly when its edits would start to hurt (Task 9). `store.1.dir` and the six folders are then set only in `context.properties`, which Known Limitations says (Task 19).
2. **CE's prefix match is already fixed.** Spec §3.1 ("Until this work is built, CE's bug stands") and §17's last bullet predate [Healdan-Project/healdan#153](https://github.com/Healdan-Project/healdan/pull/153): `3eedc64` (2026-11-01) made `FSStore.listResources` match a file version exactly, and a follow-up (2026-11-02) fixed `replaceFile`'s null file version; `FSStoreFileVersionTest` is in `UPSTREAM_TESTS`, and the capability map's §6 records both. `HealdanStorage` still matches exactly, from its rows. **Default:** Task 21 rewrites those two passages to say so. **Owner (2026-11-03): the default.**
3. **The migration's number and portability.** Spec §3.1 and §5.1 say V1 to V5 shipped and expect V7. M4 shipped V6 (`V6__signin.sql`) and V7 (`V7__refresh_token_width.sql`), and M5's plan, approved for building on 2026-11-03, takes V8 (`V8__email_index.sql`), so this is **V9** (`V9__storage.sql`): the next free version, as §5.1 says. If this is built before M5, the two swap. §5.1 and §14 also say its SQL "runs unchanged" on PostgreSQL; Healdan's MariaDB scripts use `datetime` and `ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin`, and its HSQLDB scripts `timestamp`. **Default:** the two scripts keep that convention, and use only `bigint`, `int`, `varchar`, timestamps and sequences, so the HSQLDB script is the one PostgreSQL's folder copies. **Owner (2026-11-03): the default.** (The owner accepted V8; M5's plan, merged afterwards the same day, took V8, so this is V9, the next free version the owner's answer meant.)
4. **Smoke step 5e is taken.** Spec §15.2 and §16.1 name the storage step "5e"; M4's `test-signin.sh` is step 5e. **Default:** the storage step is **5f**. **Owner (2026-11-03): the default.**
5. **M4 has landed, and its key guard needs the storage secrets.** Spec §4 and §14 say M4 is "being built now". It was accepted on 2026-11-02, and `SecretBox` (`healdan-core`'s `signin` package) is there to reuse. Its R37 guard makes a key only while nothing stored is sealed, and `SecretBox.sealedIn` counts only providers' client secrets and helpers' refresh tokens. Left alone, an admin typing an S3 secret while the key file is missing would make a new key silently, and every sealed value would be lost. Counting them isn't enough on its own: M4's new-key question (`ProviderDialog.askNewKey`), `SecretBox`'s two log messages and the Backup and Restore page's key sections all say a new key loses only providers' client secrets and helpers' refresh tokens, so an admin confirming a new key on the Sign-In tab would take every S3 backend down unwarned. **Default:** `sealedIn` also counts `hd_storage_backend.hd_secret` (Task 2), an edit to M4's class; that question, those messages and those sections name the storage backends' secrets too, and a backend whose secret won't open fails, saying to enter it again (Tasks 12 and 14). **Owner (2026-11-03): the default.**
6. **Files the spec's §15.2 doesn't list.** The build also changes:
   - `Dockerfile`: the cache folder in its `mkdir` (~65) and `VOLUME` (~83);
   - `docker/setenv.sh`: the three paths above;
   - `healdan-core`'s `SeedRunner.ORDER`, `HealdanStartup.start()` (a start hook), `HealdanHooksImpl.afterFolderRestore` (a hook, §17's "small seam"), `SecretBox.sealedIn` and its messages (question 5), `OutgoingEmail` (a public way for another module to send a templated email), `PreviewWorker.failed` (an unreachable backend postpones a preview without spending a try), `HealdanMigratorTest` (which pins the schema's version), and its test-jar's `PluginFixtures` (an archive for another Healdan plugin) and `SignInTestHooks` (a public way to capture sent emails);
   - the GUI's `ProviderDialog` (M4's new-key question, question 5);
   - `healdan-api`'s `ProblemMapper`, `DocumentResource` and `openapi.yaml`, and the Developer Guide's API page (question 7);
   - `docker/tests/garage/` (Garage's configuration and setup script);
   - docs pages §15.3 doesn't name: the Install manual's `what-runs.md` and `upgrading.md` say a value already in `context.properties` is never changed but for two kinds, which `store` now joins, and its `configuration.md` that `.env` owns the only settings written on every start; the Developer Guide's `building-from-source.md` holds the per-module test commands and the test-jar rule `healdan-storage` now shares with `healdan-api`.

   **Default:** all of them, as Tasks 1, 2, 6, 10, 11, 12, 13, 14, 15 and 16 list. **Owner (2026-11-03): the default.**
7. **Where the outage message shows** (§6.5). The helper and the add-ins read files through `/api/v1/documents/{docNumber}/content` and `/thumbnail`, and the helper already shows a problem's `detail` ("#12 couldn't be opened: …", `CopyCycle`). The web page's download goes through CE's `DownloadServlet` and `ServletUtil.downloadDocument`, which checks `exists` and `size` (answered from the rows, so they pass), opens the response's stream and sets `200` (~288–302), and only then reads the file. A read that fails there leaves try-with-resources to close the stream, which commits a `200` with an empty body; `DownloadServlet`'s `sendError` then meets a committed response and is swallowed (~803–808). The browser saves an empty file and shows no error. (`docker/server.xml`'s `ErrorReportValve` has `showReport="false"`, so even an uncommitted error would be a bare page.) Options:
   - (a) The API only: `503 storage-unreachable` with the message as `detail`, a new code in `openapi.yaml` (Task 13). The web page saves empty files during an outage, which Known Limitations would have to say, against §6.5's "fails with a message that says why".
   - **(b) As (a), and `DownloadErrorFilter`**: a `@WebFilter` in `healdan-storage` over CE's download routes (`/download`, `/download/*`, `/convertpdf/*`) that hands CE a response whose stream commits nothing until its first byte. A read that fails before any byte is answered `503` with the message, escaped, on a small page. No upstream edit, about a day's work (Task 13). A file read by range straight from the bucket (over a tenth of the cache) that fails after its first bytes still arrives cut off, which Known Limitations says.

   **Owner (2026-11-03): (b).**
8. **"Recorded in the history"** (§9, Show Recovery Key). CE's user history takes only `UserEvent` values, an upstream enum, and §5.1 lists four tables. Options:
   - (a) Each showing logged with who and when (§13), and `hd_storage_backend` keeping only the last showing (`hd_keyshown`, `hd_keyshownby`), which the tab shows. Logs rotate, so earlier showings are lost.
   - **(b) A fifth table, `hd_storage_event`,** which the tab lists.
   - (c) A `UserEvent` appended upstream, with a line in §15.1.

   **Owner (2026-11-03): (b).** A recovery key reads every file on its backend, so every showing is kept, as §9 says. `hd_storage_event` is a Hibernate entity, `StorageEvent`, in V9, recording each Show Recovery Key and each Enter Recovery Key: the backend, the event, when, and the user's id and name (kept as typed, since a user can be deleted later). Nothing deletes its rows, a removed backend's included. `hd_storage_backend` has no `hd_keyshown` or `hd_keyshownby`: the tab shows the latest from the events (Tasks 2, 14 and 17). Each event is logged as well (§13).
9. **Is a staged copy held after its upload?** §8 holds "a source copy after a move", and §5.2 calls staging "a built-in local backend", which would keep every Client Work upload on the server's disk for the holding period, against §1's "the server's disk stays small". A restore doesn't need it: a backup taken while a file was staged holds both the row saying "staging" and the staged file. Options:
   - (a) Deleted once its upload is verified and the row points at the bucket. Previews, thumbnails and the indexer, which read a new file seconds to minutes after its save, then usually fetch it back from the bucket, against §6.1's "made at local speed", and a file saved minutes before an outage can't be opened during it.
   - **(b) Moved into the read cache,** as that write's entry.
   - (c) Held for the holding period, as §8 reads.

   **Owner (2026-11-03): (b).** Once the row points at the bucket, holding the file's lock, the mover renames the staged copy aside within staging (instant, so a save that lands in staging meanwhile is never the copy taken; call 20); then, without the lock, it hands it to `ReadCache.adopt`, which copies it into the cache volume as `<backendId>/<docId>/<resource>@<write>` and deletes it from staging (Tasks 8 and 11). A file over a tenth of the cache isn't cached, as any read of one isn't, so it's simply deleted. The cache's size cap bounds the disk, as §1 wants. Copies on real backends are held as §8 says.

## Spec Corrections and Calls This Plan Makes

The owner hears about these when this plan is handed over. Task 21 folds them into the spec, each marked "changed in the build".

1. **The HTTP client is `url-connection-client`.** The `s3` module's default synchronous client is now `apache5-client`, which brings `httpclient5` 5.6.4 and `httpcore5` 5.4.3; the WAR pins `httpclient5` and `httpcore5` at 5.2.1 (`logicaldoc-parent`'s `httpclient.version`), and one `WEB-INF/lib` can't hold both. The JDK's `HttpURLConnection` client is 33 KB and brings nothing. It follows the JVM's proxy properties, as M4's provider calls do (M4 R13). The trial (Task 0) checks that a 64 MiB put streams rather than buffers.
2. **Tink without keysets.** Healdan builds the key object itself (`AesGcmHkdfStreamingKey` from the key file's bytes) and the primitive with `AesGcmHkdfStreaming.create(key)`, so no keyset, JSON or registry is used. Tink 1.24.0 depends on `gson` 2.13.2 and `protobuf-java` 4.33.6, while `logicaldoc-parent` pins `gson` at 2.3.1 for the whole reactor. Task 1 lists the WAR's jars: where the WAR ships either at another version, the plugin zip leaves it out, and `TinkClasspathTest` proves the encryption runs with Tink's `gson` and `protobuf` classes hidden.
3. **Rows through JDBC.** `hd_storage_file` and `hd_storage_held` are read and written with `JdbcTemplate`, as `hd_preview_job` is, by `(docId, resource)`, with no Hibernate entity and no second-level cache: one row a file means many rows and bulk updates. `hd_storage_backend`, `hd_storage_store` and `hd_storage_event` are Hibernate entities, as M4's tables are.
4. **Staging is backend `0`** in the rows, with no `hd_storage_backend` row (§5.2).
5. **A start hook in `healdan-core`.** `HealdanStartup.start()`, its `@PostConstruct`, calls each `HealdanStartListener` bean when `run()` returns true, beside starting M1.5's preview worker and M5's email-index worker. Tests call `run()` themselves (`AbstractHealdanTestCase.setUp`), so, as today, they start no thread; `run()` itself doesn't start the preview worker. `healdan-storage`'s listener checks every backend, then starts the mover, the purge, the nightly check, the retests and the alert (call 24). Seeds run inside `run()`, before the listener, and tests never call `start()`, so their writes don't wait for it: a backend's first write checks it (call 24; pre-flight, 2026-11-07). A Spring event would reach `@EventListener` beans only after every singleton was made, which is after `HealdanStartup`'s `@PostConstruct`.
6. **A folder-restore hook in `healdan-core`** (§17). CE restores a folder tree with JDBC and calls only `HealdanHooks.afterFolderRestore`, whose one implementation is `healdan-core`'s `HealdanHooksImpl`. It now also calls each `FolderTreeListener` bean, a new `healdan-core` interface, with the tree's documents, which `afterFolderRestore` already works out (`StoredRows.tree` and `documentIds`), so `healdan-storage` re-reads the Stores of a tree restored into another Store. Every other move fires a listener: `DocumentManager.moveToFolder` stores the document, a folder move stores the folder, and a merge moves each document with `moveToFolder` (`HibernateFolderDAO.moveDocumentsOnMerge`).
7. **The seeds join `SeedRunner.ORDER` by name** (§17): `storage-settings` right after `signin-settings` (before M5's `profile-message-id`), then `storage-record-original` and `storage-hide-stores` (question 1), and `smoke-storage` right after `smoke-signin` (before `demo-fixture`; pre-flight, 2026-11-07, as master moved since the plan was checked). A name with no bean is skipped, so `healdan-core`'s own tests are untouched. Each storage seed's `enabled()` is false unless `Store.get()` is a `HealdanStorage`, so a server still on `FSStore` records nothing it would then let go stale.
8. **A `storage-settings` seed** sets the settings above with `setIfMissing`, and adds the alert's message template, `storage.alert`, as M4's `signin-settings` adds `signin.adminalert`.
9. **The places are system properties** that `docker/setenv.sh` sets, as M4's `healdan.secretKey` is, because `ContextProperties` doesn't say where it was loaded from.
10. **A Store is the top-level workspace above a folder,** Templates included and CE's Default workspace not (§11). `StoreOf` finds it by walking `ld_parentid`, as `MatterLocator` and `Propagation` do, and never reads `ld_path`: CE's folder restore re-parents a tree with JDBC and leaves its `ld_path` as it was (`HibernateFolderDAO.restore`, ~862–872; only `move` rewrites paths, and only under the old prefix), so a path can name the wrong Store for good, and a nightly check reading the same column would never notice. Bulk work (the recording seed, the nightly check, a Store's check before a switch) reads `ld_folder`'s `(ld_id, ld_parentid)` once and resolves each folder's top in memory.
11. **A file with no row is read from Original,** and logged at warning level. Only `FSStore` wrote before the switch, so an unrecorded file can only be there: one in a folder the recording seed couldn't read (Task 9). A write always makes a row.
12. **Two hidden settings for the smoke stack,** `storage.sweepSeconds` and `storage.retestSeconds`, as M4's `signin.recheck.minutes`. Without them, step 5f's outage check would wait a minute or more for the retest, and up to five for the sweep.
13. **The outage exception lives in `healdan-core`,** as `com.logicaldoc.healdan.util.StorageUnreachableException extends IOException`, because `healdan-api` maps it and `healdan-core`'s `PreviewWorker` waits it out, and neither can depend on `healdan-storage`. Its message is the one §6.5 gives, naming the Store.
14. **The marker holds this install's id and the backend's id.** A marker naming another install or backend fails the backend too, which catches two backends given one folder. S3 backends get one too (call 23).
15. **The recovery key's alphabet is Crockford's base32,** which has no I, L, O or U, so a key read aloud or copied by hand survives. The confirmation is the last group of four.
16. **Each upload is verified twice:** S3 refuses a body whose `Content-MD5` doesn't match (on a single put and on each part), and the mover then reads the object's size back with `HEAD` before it points the row at it. A local write goes to a temporary name in the same folder, is synced and its size checked, then is moved into place: at once for a save, and for the mover only at its commit (call 20).
17. **Three layers of tests** (see "How CI Tests It"): an in-memory backend, a fake S3 server in the Java tests, and Garage on the smoke stack. No Testcontainers: the `Tests` shards don't run Docker.
18. **The smoke overlay sets Garage up with Garage's admin API** from a one-shot `busybox` container, as Garage's image has no shell. Task 0 confirms the calls on v2.5.0, or switches to its CLI through `docker compose exec`.
19. **The smoke seed adds matters 1.2 and 1.3 to Smoke Cloud,** where §16.1 names 1.2 only. Check 5 moves 1.2 home, so 1.3 keeps files on Garage for step 8's restart to read back through the bucket (Task 16).
20. **The mover commits under the file's lock, and discards a copy it couldn't commit** (§7 step 3 changed). A save into a local Store writes straight to the key a transfer onto that backend is writing: the mover's rename into place could replace the save's new bytes, and §7's "puts its copy in held deletes" would then move the live key, the save's bytes or its own, into `.held/`, for the purge to delete. So the mover writes a local target under a temporary name of its own (an S3 target at its key, which saves never write: an S3 Store's saves land in staging), and then, holding the file's lock, checks that the row's write id is the one it read and that the row's Store's backend is still the target. If both hold, it moves its file into place, points the row at the target, and holds the source, or renames the staged copy aside for the cache (question 9). If not, it deletes its own copy, which no row ever pointed at, so no backup can need it; an S3 object stays only if a held row names its key. Saves, deletes and the purge take the same lock, so nothing acts between the check and the update.
21. **A write id, not a write counter** (§5.1, §6.4 and §7 changed). The counter restarts at 1 when a row is deleted and made again: Replace File deletes a version's derived files and M1.5 makes them again, and deleting version 1.3 and checking in again makes a new 1.3. A stale transfer's "unchanged" check would then match the new file, point its row at the old bytes, and delete the new ones. A database restored in place also winds counters and CE's document ids back while the cache volume, which backups skip, keeps its plaintext, so a new document could be served a lost one's cached bytes. A random 64-bit `hd_write` per write never repeats, and the mover's check and the cache's keys use it.
22. **The purge and the mover don't cross** (§6.2 kept). The purge deletes a held S3 object only holding its file's lock, and only if no row points at it and no transfer of that file is under way (the mover's set of files in transfer, in memory). Otherwise the purge's check and its delete could straddle the mover's upload of the same key and the row's update, leaving the row pointing at nothing.
23. **Backends can't share a place** (§10 added to). Nothing else would stop two S3 backends, or two installs, using one bucket and prefix: the mover would write one's objects over the other's, the purge (which checks rows on its own backend only) would delete the other's live files, and a second encrypted backend would overwrite the first's check object. The tab refuses an S3 backend whose endpoint host, bucket and prefix equal another's or contain it (an empty prefix contains every other), and a local path that is, holds, or is inside Original's folder, staging, the cache, the configuration folder or another backend's. Each S3 backend gets a marker object, `<prefix>.healdan-backend`, holding this install's id (`storage.installId`, made once by the settings seed) and the backend's id; a marker naming another install or backend fails the backend, as call 14's does for a folder. A server restored from a backup has the same install id, so the restore steps say to stop the first server before the second starts (Tasks 19 and 22).
24. **Backends are checked at start, before the mover** (§9 kept). Spec §9 decrypts the check object "at start", so that a botched restore can't write files under the wrong key. `StorageStartup` checks every backend before the mover's first pass, and a backend holds its transfers until its first check passes; only the purge and the nightly check wait 10 minutes after a start. A write that reaches a backend still unchecked runs the check itself first, holding that backend's check lock, and is refused only if the check fails (pre-flight, 2026-11-07): seeds, `demo-fixture` among them, run before `start()`, and tests never call it, so refusing every write until the start's check would stop a fresh demo stack's start and fail every test that writes. Nothing is written under an unchecked key either way; the cost is the first save after a start waiting for one marker read and, if encrypted, one check-object decrypt.

## Conventions

- **Running Maven:** `~/logicaldoc-dev.properties` must exist (an empty file works). After changing an upstream module, re-install that module (CLAUDE.md); after a `healdan-core` change: `mvn -B -ntp -pl healdan-core -DskipTests install` (with the test-jar, which `healdan-storage`'s tests need, as `healdan-api`'s do). Then `mvn -B -ntp -pl healdan-storage test -Dtest=<Class>`.
  - **Prefer one reactor** for `healdan-storage`'s tests once a task has changed `healdan-core` or `logicaldoc-gui` (pre-flight, 2026-11-07): `mvn -B -ntp -pl healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='<classes>' -Dsurefire.failIfNoSpecifiedTests=false package`. It builds `healdan-core` and its test-jar from the working tree in the same run, so another session's install of `healdan-core` into the shared `~/.m2` can't swap the jar mid-run, and `-Dtest` may name `healdan-core`'s test classes too. It's slower than a bare `-pl healdan-storage test`.
- **The GUI:** `mvn -B -ntp -pl logicaldoc-gui -DskipTests install`, which runs the GWT compile; when only the server's Java changed, `-Dgwt.skipCompilation=true -Dmaven.antrun.skip=true` (both, for `install`, as CLAUDE.md says).
- **The smoke test:** `docker/smoke-test.sh` on a fresh stack; `docker build --target tools .` for `ConfigPlan`'s tests.
- **Commits:** one per task, with an imperative subject, a body that says why, and the `Co-Authored-By` trailer.

## How CI Tests It

CI has no S3 service and no Wasabi account. The plan tests each layer where it can run:

| Layer | Where | What it proves | Runs in |
|---|---|---|---|
| `MemoryBackend` (test sources) | `healdan-storage`'s tests | `HealdanStorage`, the mover, held deletes, the cache, Store tracking and the tab's server side, with failures injected on demand (refuse, fail after N bytes, unreachable, slow) | `Tests (n of N)` shards, found without a list (CLAUDE.md) |
| `LocalBackend` under `target/` | the same | real files: the marker, `.held/`, sync and rename, encryption at rest | the same |
| `FakeS3` (JDK `HttpServer`, test sources) | `S3BackendTest` | the SDK's requests as they leave Healdan: `Content-MD5` present and checked, no `x-amz-checksum-*` or `x-amz-sdk-checksum-algorithm` headers, path-style addresses, ranged `GET`s, multipart with 16 MiB parts and its abort, `HEAD`, errors and timeouts mapped to "unreachable" | the same |
| Garage v2.5.0 | `docker/tests/test-storage.sh`, smoke step 5f | a real S3 server: SigV4, the whole flow through the API, the mover, an outage, a move of Store, held copies, a restart | `docker-image.yml`'s Test (amd64) and Test (arm64) |
| Wasabi | the trial (Task 0) and the owner's walkthrough (Task 22) | what only Wasabi can show: its checksums, speeds, lifecycle rule | the owner's server, by hand |

`FakeS3` is the layer the spec doesn't name. Without it, `S3Backend`'s wire behaviour would be tested only on the Docker stack, after the image's build, and its failure paths (a refused MD5, an aborted multipart, a timeout) not at all.

## PRs

All target `master`, each stacked on the one before. PRs A and B change nothing a running server does: the Docker image keeps `FSStore` until PR C, and every `healdan-storage` bean waits for `HealdanStorage` (Global Constraints), as does `DownloadErrorFilter`, which the WAR packs from PR B and which passes every request through untouched unless `Store.get()` is a `HealdanStorage`. The exceptions are PR B's Task 13 edits outside `healdan-storage`, which run on `FSStore` too and are harmless there (pre-flight, 2026-11-07): `PreviewWorker` postpones a job whose read throws `StorageUnreachableException`, which `FSStore` never throws; `ProblemMapper` maps that exception; and `DocumentResource` opens a file's stream before its answer starts, so a read that fails is a `500`, never a `200` cut short.

- **Before PR A, Task 0:** the trial, on the owner's server. Its findings go into the decision log before Task 1 starts.
- **PR A, Tasks 1–8:** the module, the tables, the backends, encryption, and `HealdanStorage` with staging, held deletes and the cache. Its tests run CE's flows through it.
- **PR B, Tasks 9–13:** recording what's there, Store tracking, the mover, failing backends and the alert, and the API's outage answer.
- **PR C, Tasks 14–20:** the Storage tab, the Docker switch, Garage on the smoke stack, and the docs. Usable by an admin from here.
- **PR D, Task 21:** CLAUDE.md's "Healdan's code" and upstream-edits lists, the capability map, the spec's corrections and the roadmap. CLAUDE.md's build, test, CI and Docker facts change in the tasks that change them (Tasks 1, 15 and 16), with the Developer Guide's.
- **Task 22:** the owner's server upgraded, and the owner's walkthrough (§16.3).

Expected costs, to be replaced by each PR's measured figures:

| PR | `build` | `Tests` jobs | Docker image and smoke test |
|---|---|---|---|
| A | the new module's compile, a few seconds | about +300 s of runner time across the shards (Spring-context tests, about 3 s each) | the WAR gains about 13 MB of jars; `WarStorageTest` adds a few seconds to the `tools` target |
| B | none | about +200 s | none |
| C | none | about +60 s | step 5f about 60 s, mostly the outage and the move; Garage starts in about a second, beside the app |
| D | none | none | none |

---

### Task 0: The trial

Spec §16.2. Against the owner's Wasabi bucket and against Garage, before anything else is built, as M3's and M3.5's trials were. Nothing from it is merged: its lessons go into the decision log, and the tasks below change where it says.

**Files (not merged):** a throwaway `S3Trial.java`, run as a single-file program in the Dockerfile's `MAVEN_IMAGE` (`maven:3.9-eclipse-temurin-25`) with the pinned jars from Task 1's dependency list, and a scratch `garage.toml`.

- [x] **Step 1: Garage.** On the owner's server, `docker run` `dxflrs/garage:v2.5.0` with a one-node `garage.toml` (`replication_factor = 1`, `s3_region = "garage"`, an `[admin]` token). Set it up the way Task 16 will: through the admin API from `busybox` (find the node's id, assign and apply a one-node layout, import a key with fixed test values in Garage's own form, `GK` and 24 hex digits with a 64-hex-digit secret, create the bucket, allow the key on it), or else with the `garage` CLI. Record the exact calls.
- [x] **Step 2: The client.** With `url-connection-client`, `requestChecksumCalculation(WHEN_REQUIRED)`, `responseChecksumValidation(WHEN_REQUIRED)`, path-style on, against Garage and then Wasabi (the region's endpoint, e.g. `https://s3.us-east-2.wasabisys.com`), check and record:
  1. a put with `Content-MD5` succeeds, and one with a wrong `Content-MD5` is refused (`BadDigest`);
  2. no request carries `x-amz-checksum-*` or `x-amz-sdk-checksum-algorithm` (an `ExecutionInterceptor` prints the headers);
  3. a ranged `GET` (`bytes=1048000-1049999`) returns exactly those bytes, on both;
  4. a 100 MiB multipart upload in 16 MiB parts, each with its `Content-MD5`, completes; another is aborted part-way, and `ListMultipartUploads` no longer shows it;
  5. `ListObjectsV2` with a prefix, `HEAD`, and a single-object `DELETE` (never `DeleteObjects`, which the SDK always checksums);
  6. a 64 MiB put doesn't grow the JVM's heap by 64 MiB (`-Xmx256m` and a heap reading), so the JDK client streams the body;
  7. with the network to the endpoint cut (`iptables` on the server, or a wrong port), how long the SDK takes to fail, and with which exception, so Task 5 maps it to "unreachable".
- [x] **Step 3: Speeds, from the owner's server to Wasabi:** a 1 MiB, a 20 MiB and a 200 MiB upload; a cold read of the same, whole and its first 1 MiB. Record them in the decision log, with the server's upload bandwidth.
- [x] **Step 4: Rulings.** Anything that failed becomes a ruling before Task 1: Garage's ranged reads failing means step 5f reads only whole files and `FakeS3` alone covers ranges; Wasabi refusing `Content-MD5` with `WHEN_REQUIRED` means the SDK's own checksums on Wasabi, with Garage's behaviour recorded; an SDK that buffers bodies means another client and a new size figure. Then delete the trial's bucket objects and Garage container.

**CI:** none; this runs by hand on the owner's server.

**Done** on 2026-11-07: every check passed on both, and the decision log's "The trial" holds the findings and the rulings.

---

## PR A: the module, the backends and `HealdanStorage`

### Task 1: The module and its libraries

**Files:**
- Create: `healdan-storage/pom.xml` (parent `logicaldoc` 9.3.2; dependencies as `healdan-api`'s, plus `software.amazon.awssdk:s3` and `url-connection-client` at the property `awssdk.version` **2.57.8**, with `<exclusions>` for `software.amazon.awssdk:apache5-client`, `apache-client` and `netty-nio-client`; `com.google.crypto.tink:tink` at `tink.version` **1.24.0**; test dependencies on `healdan-core`'s test-jar in a `with-tests` profile, as `healdan-api`'s; surefire's `systemPropertyVariables` for `healdan.secretKey`, `healdan.storageStaging`, `healdan.storageCache` and `healdan.storageKeys` under `${project.build.directory}`)
- Create: `healdan-storage/src/main/resources/plugin.xml` (the id, version and requires above; the two listener extensions arrive in Task 10)
- Create: `healdan-storage/src/main/assembly/assemblyplugin.xml` (`WEB-INF/lib` gains `com.logicaldoc:healdan-storage`, `software.amazon.awssdk:*` (which takes the SDK's `eventstream` jar too: in 2.57.8 it's `software.amazon.awssdk:eventstream`), `org.reactivestreams:*`, `com.google.crypto.tink:*`, `com.google.errorprone:error_prone_annotations`, and `com.google.code.gson:gson` and `com.google.protobuf:protobuf-java` unless step 4 leaves them out)
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/HealdanStoragePlugin.java` (extends `LogicalDOCPlugin`; registers nothing: the servlet uses `@WebServlet`)
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/HealdanStorage.java` (a stub extending `FSStore`, so the test context, which names it in `store=`, starts; Task 6 replaces it)
- Create: `docker/tools/test/WarStorageTest.java`; Modify: `docker/tools/build.sh` (`run_test WarStorageTest`, after `WarParsersTest`)
- Modify (upstream): `pom.xml` (`<module>healdan-storage</module>` after `healdan-api`), `logicaldoc-webapp/pom.xml` (the `artifactItem` after `healdan-api`'s)
- Modify: `healdan-core/src/test/java/com/logicaldoc/healdan/PluginFixtures.java` (`public static String archive(String pluginId)`, for another Healdan plugin's `plugin.xml` on the classpath)
- Create: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/AbstractStorageTestCase.java`, `StorageModuleTest.java`, `TinkClasspathTest.java`
- Docs: `CLAUDE.md` ("Build and test": `healdan-storage`'s test commands, and that its tests need `healdan-core` installed with its test-jar, as `healdan-api`'s do; "Docker": `WarStorageTest` beside `WarParsersTest` in the `tools` target's tests; "Plugin system": the line saying `WarParsersTest` catches a missing jar for POI only also names `WarStorageTest` for the AWS SDK and Tink), `site/content/developer/building-from-source.md` (the same, beside `healdan-api`'s commands, its test-jar bullet and its `-Dmaven.test.skip=true` note) and `site/content/developer/tests.md` (the `docker build --target tools .` row names `WarStorageTest`)

**Interfaces:**
- Produces: `AbstractStorageTestCase extends AbstractHealdanTestCase`, whose `getPluginArchives()` adds `PluginFixtures.archive("healdan-storage")`, and whose `getConfigResource()` names `context-storage.properties`: `healdan-core`'s test `context.properties` (copied in by `testResources`, as `healdan-api` does) with `store=com.logicaldoc.healdan.storage.HealdanStorage`, which the class writes into `target/test-classes/` before the context starts (CE reads the file by name). Before `super.setUp()`, where the seeds run, it empties Original's folder (`store.1.dir`, `target/store`), staging, the cache and the keys folder: each test's database starts CE's document ids at 100 again, so a file an earlier test left in Original would be recorded as the new test's. From Task 3 to Task 9 it also writes Original's marker after `super.setUp()` (Task 3 adds that, Task 9 takes it out); this task's class doesn't.

- [x] **Step 1: Write the failing tests**
  - `StorageModuleTest extends AbstractStorageTestCase`:
    - `testPluginLoads`: JPF lists `healdan-storage` as active, requiring `healdan-core`.
    - `testStoreBeanIsCEs`: `Context.get().getBeansOfType(Store.class)` has exactly one bean, which is `Store.get()` and a `HealdanStorage`. (`Store.get()` *is* `Context.get(Store.class)`, so comparing the two can't fail.) Until Task 6 the class is this task's stub extending `FSStore`, so the test context starts.
  - `TinkClasspathTest` (plain JUnit): encrypts and decrypts 3 MiB through `AesGcmHkdfStreaming.create(key)` in a class loader that hides `com.google.gson` and `com.google.protobuf`. It fails if Tink loads either on that path.
  - `WarStorageTest` (`docker/tools`, a `main` as `WarParsersTest` is, run on the WAR's own `WEB-INF/lib`): builds an `S3Client` exactly as `S3Backend` will (Task 5: `url-connection-client`, `WHEN_REQUIRED` for both checksums, path style, static credentials, a fixed region), sends one `GetObject` to a closed port on `127.0.0.1` and expects an `SdkClientException` whose cause is a `ConnectException` (so signing, the request pipeline and the error path all load), then encrypts and decrypts 3 MiB with Tink's streaming AEAD as `FileCipher` will (Task 4). Any `NoClassDefFoundError` or `ClassNotFoundException` fails it. Maven's test classpath has every transitive jar, so only the WAR can show one the plugin zip drops (#202).
- [x] **Step 2: Run them to see them fail**

  Run: `mvn -B -ntp -pl healdan-storage test -Dtest='StorageModuleTest,TinkClasspathTest'`
  Expected: FAIL (no module).
- [x] **Step 3: Write the module, then run the tests:** PASS.
- [x] **Step 4: The jars.**
  - Run: `mvn -B -ntp -pl healdan-storage dependency:tree -Dscope=runtime` and `mvn -B -ntp -pl logicaldoc-webapp -am dependency:tree -Dscope=runtime -Dgwt.skipCompilation=true`.
  - For each jar the plugin brings, record whether the WAR already ships it and at which version. A jar the WAR ships is left out of the assembly; one it ships at another version is a ruling here (`gson`, `protobuf-java` and `error_prone_annotations` are the candidates), which call 2's test must cover.
  - Then `mvn -B -ntp -pl healdan-storage -DskipTests package` and `unzip -l healdan-storage/target/healdan-storage-*-plugin.zip | grep WEB-INF/lib`. State in PR A the jars the WAR gains and their total size.
  - Then `docker build --target tools .`: `WarStorageTest` passes on the WAR's `WEB-INF/lib`. If it names a missing class, add that jar's group to the assembly and build again.
- [x] **Step 5: Docs, then commit:** "Add the healdan-storage module and its libraries"

**CI:** `StorageModuleTest` and `TinkClasspathTest` run in the `Tests` shards. The Docker image build packs the plugin zip into the WAR from this task on, and its `tools` target runs `WarStorageTest` on amd64 and arm64.

---

### Task 2: The tables, the settings and the secret box's count

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/mariadb/V9__storage.sql`, `…/hsqldb/V9__storage.sql` (question 3: after M5's V8)
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/StorageBackend.java`, `StorageBackendDAO.java`, `HibernateStorageBackendDAO.java`, `StoreBackend.java`, `StoreBackendDAO.java`, `HibernateStoreBackendDAO.java`, `StorageEvent.java`, `StorageEventDAO.java`, `HibernateStorageEventDAO.java` (question 8), `StoredFiles.java` (`hd_storage_file`, JDBC), `HeldCopies.java` (`hd_storage_held`, JDBC), `StorageSettings.java`
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/seed/StorageSettingsSeed.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/SeedRunner.java` (`ORDER` gains `"storage-settings"`, `"storage-record-original"` and `"storage-hide-stores"` right after `"signin-settings"`, before M5's `"profile-message-id"`, and `"smoke-storage"` right after `"smoke-signin"`, before `"demo-fixture"`; call 7)
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SecretBox.java` (`sealedIn` also counts `select count(*) from hd_storage_backend where hd_secret is not null`)
- Modify: `healdan-core/src/test/java/com/logicaldoc/healdan/db/HealdanMigratorTest.java` (it pins the schema's version, which M5's V8 moves to 8: `fromVersion("8")` and "8 migrations applied, schema version 8" become 9, as V7's commit `fce24950b` moved them from 6 to 7)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/StorageDataTest.java`, `seed/StorageSettingsSeedTest.java`; `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInProvidersTest.java` (one test, beside `testNoKeyIsMadeOverStoredSecrets`, which has the database: `SecretBoxTest` has none)

**Interfaces:**
- Produces:

```java
@Entity @Table(name = "hd_storage_backend")
public class StorageBackend extends PersistentObject {
	String name; String kind;          // "local" | "s3"
	String path;                       // local; null for Original, whose path is store.1.dir
	String endpoint; String region; String bucket; String prefix; int pathStyle; String accessKey;
	String secret;                     // sealed by SecretBox; never leaves the server
	int encrypted; int keyConfirmed;   // 0 or 1, as the columns, with boolean accessors (Global Constraints: PostgreSQL)
}
@Entity @Table(name = "hd_storage_event")
public class StorageEvent extends PersistentObject { // question 8: never deleted
	long backendId; String event;      // "key-shown" | "key-entered"
	Date date; long userId; String userName;
}
@Entity @Table(name = "hd_storage_store")
public class StoreBackend extends PersistentObject { long storeId; long backendId; } // unique storeId

public record StoredFile(long docId, String resource, long backendId /* 0 = staging */, Long storeId, long size,
		long write, Date stored, Date waiting, int attempts, String lastError, boolean stuck, Date nextTry) {}

@Component
public class StoredFiles {                                    // JdbcTemplate over hd_storage_file
	public Optional<StoredFile> find(long docId, String resource);
	public List<StoredFile> list(long docId);                  // every resource of a document
	public long recordWrite(long docId, String resource, long backendId, Long storeId, long size); // insert or update; a new random write id, returned
	public boolean moved(long docId, String resource, long write, long toBackend); // only if hd_write is still `write`; clears hd_waiting
	public void setStore(Collection<Long> docIds, Long storeId); // sets hd_waiting, if empty, on rows it leaves misplaced
	public void delete(long docId, String resource);
	public List<StoredFile> misplaced(int limit, Collection<Long> unavailableBackends); // backend isn't the Store's: staged first, then newest docs
	// waiting and stuck counts per Store, sizes, failures and Retry, for the tab and the mover
}
@Component
public class HeldCopies { /* JdbcTemplate over hd_storage_held: hold(backendId, location, docId, resource, until), due(now), drop(backendId, location) */ }
```

- Every per-file `StoredFiles` write (`recordWrite`, `moved`, `delete`, a failure's record) runs holding the file's lock (Global Constraints), so `recordWrite`'s read-then-insert-or-update can't race another write of the same file to the insert, and the SQL stays the same on every database. `setStore` is a bulk update and takes no file's lock: its race with a mover's commit at worst leaves a row misplaced for the next pass, which the nightly check backs up (Task 10).
- `hd_waiting` is when a row's backend stopped matching its Store's: set, if empty, by `setStore` and by a Store switching backend; cleared by `moved`. `recordWrite` clears it when the write lands on the backend the row's Store uses (Original's for no Store, or a Store with no `hd_storage_store` row), and sets it to now, only if empty, when the write lands anywhere else, staging included; otherwise a save that lands in place would leave a stale value, and the alert would fire a day later. The alert measures from it (Task 12), not from `hd_stored`, which a Store switch leaves months old.
- `misplaced(limit, unavailableBackends)` (the mover passes the backends `BackendHealth` has paused or not yet checked, Task 11) leaves out, in its SQL rather than after it, rows whose source or target backend is in the set, rows marked stuck (it keeps only `hd_stuck = 0`), and rows not yet due (`hd_nexttry is null or hd_nexttry <= ?`); a row's target is its Store's backend, Original's for no Store or a Store with no row. The backend clause is left out when the set is empty, as `not in ()` isn't valid SQL. Filtering after the query would let one paused backend's rows fill every page and starve the rest.
- `StorageBackendDAO.remove(id)` deletes the row for good, not through CE's `ld_deleted`, as M4's `HibernateSignInGroupDAO` does, so a removed backend's sealed secret leaves the database and `sealedIn`'s count.

- [x] **Step 1: Write the failing tests**
  - `StorageDataTest extends AbstractStorageTestCase`:
    - `testMigrationMadeTheTables`: the five tables exist (`INFORMATION_SCHEMA`), `hd_schema_history` has version 9, and backend 1 is a `local` backend named `Original`, not encrypted, with no path.
    - `testOneRowPerFile`: a second insert of `(docId, resource)` fails; `recordWrite` twice leaves one row, with a new write id each time.
    - `testMovedOnlyIfUnchanged`: `moved(…, write = w1, …)` after a second write changes nothing and answers false; so does it after the row is deleted and written again, whose new write id is never `w1`.
    - `testWaiting`: a staged write, a write that lands on a backend other than its Store's, and a `setStore` that leaves a row misplaced, set `hd_waiting`, keeping an earlier value; a `recordWrite` that lands on the backend its Store uses clears it (a save landing in place during a move home), and so does `moved`.
    - `testOneBackendPerStore`: a second `StoreBackend` for one Store fails to store.
    - `testRemoveForGood`: after `StorageBackendDAO.remove`, no row is left, deleted or not, so no sealed secret either.
    - `testMisplacedOrder`: staged rows come before moves, and moves newest document first.
    - `testMisplacedLeavesOut`: rows whose source or target backend is in the set, stuck rows, and rows whose `hd_nexttry` is still ahead are left out, while a row with no `hd_nexttry` and one that's due are listed; with an empty set the query runs (no `not in ()`); with a limit of 2 and one unavailable backend's rows first in order, two rows of other backends still come back.
  - `StorageSettingsSeedTest`: the seven settings' defaults (Global Constraints), `storage.installId` made once and kept, and the template `storage.alert` in English; an admin's value is kept when the step runs again (its marker deleted).
    - `testSettingsRanges`: `StorageSettings` refuses `storage.holdDays` outside 1 to 365, `storage.cacheGb` outside 1 to 10000, `storage.transfers` outside 1 to 16, and `storage.sweepSeconds` and `storage.retestSeconds` outside 5 to 3600, and takes each end of each range. The ranges are tested only here, in the task that makes `StorageSettings` (pre-flight): Tasks 7 and 14 don't repeat it.
  - `SignInProvidersTest.testStorageSecretsCountAsSealed` (`healdan-core`, beside `testNoKeyIsMadeOverStoredSecrets`): with no key file and one `hd_storage_backend` row whose `hd_secret` is sealed, `seal` throws `KeyMissingException` naming one sealed value.
- [x] **Step 2: Run them to see them fail**

  Run: `mvn -B -ntp -pl healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='StorageDataTest,StorageSettingsSeedTest' -Dsurefire.failIfNoSpecifiedTests=false package` (one reactor, Conventions, so `healdan-storage` sees this task's V9) and `mvn -B -ntp -pl healdan-core test -Dtest=SignInProvidersTest`
  Expected: FAIL.
- [x] **Step 3: Write the migration.** MariaDB's (HSQLDB's is the same, `hd_location`'s `varchar(700)` included, with `timestamp` for `datetime` and no table options). Every key and index stays under InnoDB's 3,072-byte limit under `utf8mb4` (4 bytes a character, 8 a `bigint`), should `utf8` ever mean it: `hd_storage_held`'s primary key is 8 + 700 × 4 = 2,808 bytes (2,108 under `utf8mb3`; at `varchar(1000)` it was 3,008 under `utf8mb3`, and would fail under `utf8mb4`), and `hd_storage_file`'s is 8 + 255 × 4 = 1,028; the others hold only `bigint`, `int` and `datetime` columns (pre-flight, 2026-11-07):

```sql
-- S3 storage backends (spec section 5): each backend, each Store's backend, one row a file, and held deletes.
create table hd_storage_backend (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_name varchar(100) not null,
	hd_kind varchar(10) not null,
	hd_path varchar(1000),
	hd_endpoint varchar(1000), hd_region varchar(100), hd_bucket varchar(255), hd_prefix varchar(255),
	hd_pathstyle int not null, hd_accesskey varchar(255), hd_secret varchar(4000),
	hd_encrypted int not null, hd_keyconfirmed int not null,
	primary key (ld_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_backend_SEQ start with 100 increment by 50;
insert into hd_storage_backend (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted, ld_tenantid,
	hd_name, hd_kind, hd_pathstyle, hd_encrypted, hd_keyconfirmed)
	values (1, current_timestamp, current_timestamp, 1, 0, 1, 'Original', 'local', 0, 0, 0);

create table hd_storage_store (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_storeid bigint not null, hd_backendid bigint not null,
	primary key (ld_id), unique (hd_storeid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_store_SEQ start with 100 increment by 50;

-- hd_backendid 0 is staging
create table hd_storage_file (
	hd_docid bigint not null, hd_resource varchar(255) not null,
	hd_backendid bigint not null, hd_storeid bigint, hd_size bigint not null, hd_write bigint not null,
	hd_stored datetime not null, hd_waiting datetime,
	hd_attempts int not null, hd_lasterror varchar(4000), hd_stuck int not null, hd_nexttry datetime,
	primary key (hd_docid, hd_resource)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_storage_file_store on hd_storage_file (hd_storeid, hd_backendid);
create index hd_storage_file_due on hd_storage_file (hd_stuck, hd_nexttry);
create index hd_storage_file_waiting on hd_storage_file (hd_waiting);

-- hd_location: the object key, or the path under the backend's .held/ folder. Holding a location again moves its
-- purge date on, so a location is held once at a time. 700 characters keep the key under InnoDB's 3,072 bytes in
-- utf8mb4, and hold the longest location: a 255-character prefix, CE's folders for a doc id and a resource.
create table hd_storage_held (
	hd_backendid bigint not null, hd_location varchar(700) not null,
	hd_docid bigint not null, hd_resource varchar(255) not null,
	hd_held datetime not null, hd_purge datetime not null,
	primary key (hd_backendid, hd_location)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create index hd_storage_held_purge on hd_storage_held (hd_purge);

-- Each showing and entering of a backend's recovery key (question 8), kept for good.
create table hd_storage_event (
	ld_id bigint not null, ld_lastmodified datetime not null, ld_creation datetime not null,
	ld_recordversion bigint not null, ld_deleted int not null, ld_tenantid bigint not null,
	hd_backendid bigint not null, hd_event varchar(20) not null, hd_date datetime not null,
	hd_userid bigint not null, hd_username varchar(255) not null,
	primary key (ld_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;
create sequence hd_storage_event_SEQ start with 100 increment by 50;
create index hd_storage_event_backend on hd_storage_event (hd_backendid, hd_date);
```

  Neither JDBC table needs a sequence, and write ids are made in Java, so the application's SQL is the same on every database.
- [x] **Step 4: Write the entities, the row classes, the settings and the seed, and edit `SecretBox`, `SeedRunner` and `HealdanMigratorTest`.** Run step 2's commands again: PASS, and `mvn -B -ntp -pl healdan-core test -Dtest='SignInProvidersTest,SecretBoxTest,HealdanMigratorTest,SeedsTest'`: PASS.
- [x] **Step 5: Commit:** "Add the storage tables and settings, and count storage secrets as sealed"

**CI:** all in the `Tests` shards. `healdan-core`'s own tests run V9 on every fresh test database from here, which also checks it on HSQLDB with nothing of `healdan-storage` present.

---

### Task 3: Backends: the interface, local folders and the in-memory fake

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/backend/Backend.java`, `BackendFailingException.java`, `BackendUnreachableException.java`, `LocalBackend.java`
- Create (tests): `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/backend/MemoryBackend.java`, `BackendContract.java`, `LocalBackendTest.java`, `MemoryBackendTest.java`
- Modify (tests): `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/AbstractStorageTestCase.java` (after `super.setUp()`, writes Original's marker with `new LocalBackend(1, installId, store.1.dir, true)`, the install id read from `storage.installId`, which Task 2's `storage-settings` seed made, as the recording seed will; otherwise every write into Original fails `check()` from Task 6 on, as only Task 9's seed writes Original's marker. Task 9 takes this out)

**Interfaces:**
- Produces:

```java
/** A place file bytes go (spec section 10): keys are CE's relative paths, with no prefix */
public interface Backend {
	long id();
	void put(String key, Path file, byte[] md5) throws IOException;   // saves: whole, verified, in place; md5 of the bytes as stored
	Staged stage(String key, Path file, byte[] md5) throws IOException; // the mover: verified, not yet in place (call 20)
	InputStream get(String key) throws IOException;                    // FileNotFoundException when missing
	SeekableByteChannel open(String key) throws IOException;           // for ranges
	long size(String key) throws IOException;                          // -1 when missing
	void delete(String key) throws IOException;                        // missing is fine
	List<String> list(String prefix, int max) throws IOException;      // S3's Test only (the recording seed walks the folder itself)
	void check() throws BackendFailingException;                       // marker, or reachability; cheap
	Optional<String> held(String key);                                 // local: the key's path under .held/ if there
	String hold(String key) throws IOException;                        // local: moves it under .held/; S3: leaves it
	void purgeHeld(String location) throws IOException;
}
/** A copy the mover wrote and verified, which no row points at yet */
public interface Staged {
	void commit() throws IOException;                                  // local: moved into place; S3: already there
	void discard() throws IOException;                                 // local: deleted; S3: the object deleted (call 20)
}
```

`LocalBackend(long id, String installId, Path root, boolean create)`: `create` writes the marker (`<installId> <id>\n`) and makes `.held/`; otherwise `check()` fails when `.healdan-backend` is missing or names another install or id, and every `put` and `stage` calls `check()` first. `put` writes `<key>.healdan-tmp-<uuid>` in the key's folder, `FileChannel.force(true)`, checks the size, then `Files.move(…, ATOMIC_MOVE)`, then syncs the folder. `stage` does all of that but the move, which `commit` does. `hold` is a `Files.move(…, REPLACE_EXISTING)` into `.held/<key>` on the same disk: a key is held once at a time (Task 2's table), so a newer held copy replaces an older one, as CE rewrites derived files under the same names. `LocalBackend.probe(root)` is Test for a folder not saved yet (Task 14): it exists, is writable (a probe file written and deleted) and holds no marker.

`MemoryBackend` keeps bytes in a map and takes faults: `refuseNext(n)`, `refuse(Op... ops)` (each of `Op.GET`, `OPEN`, `PUT`, `DELETE` and `LIST` refused until cleared, the others working, as Task 12's `BackendTestTest` needs), `failAfterBytes(n)`, `unreachable(boolean)`, `slow(Duration)`, `corruptNextPut()`; it counts calls per key.

- [x] **Step 1: Write the failing tests**
  - `BackendContract` (abstract; `LocalBackendTest`, `MemoryBackendTest` and Task 5's `S3BackendTest` extend it): put then get, `size`, `exists` through `size`, overwrite, delete (twice), `open` and read at 0, in the middle and across the end, `list` by prefix, a missing key (`FileNotFoundException`, size -1), 0-byte and 50 MiB files.
  - `LocalBackendTest` also:
    - `testMissingMarkerRefusesWrites`: with the marker deleted (an NFS share that didn't mount), `check()` and `put` fail with `BackendFailingException`, and nothing is written under the root.
    - `testMarkerOfAnotherBackend`: a marker naming backend `7`, or another install, fails backend `3`.
    - `testNoHalfFiles`: a put whose source throws half-way leaves no file at the key and no temporary file.
    - `testStageCommitDiscard`: a staged copy isn't at its key, and a file already there is untouched, until `commit`; `discard` leaves nothing.
    - `testHoldAndRead`: `hold` moves the file under `.held/` with the same path; `held(key)` finds it.
    - `testHoldTwice`: holding a key, writing it again and holding it again keeps the newer copy in `.held/`, and throws nothing.
    - `testProbe`: `probe` passes an empty writable folder, and refuses a missing one, a read-only one, and one holding a marker.
    - `testDotNamesNeverListed`: `.held/`, `.healdan-backend` and temporary files never appear in `list`.
  - `MemoryBackendTest` also: `testRefuseOneOperation`: with `refuse(Op.DELETE)`, deletes fail and puts, gets and opens work; with `refuse(Op.OPEN)`, ranged opens fail and whole gets work.
  - `StorageModuleTest` (Task 1) still passes with the marker written in `AbstractStorageTestCase`, and Original's folder holds `.healdan-backend` naming this install and `1`.
- [x] **Step 2: Run them to see them fail.** Run: `mvn -B -ntp -pl healdan-storage test -Dtest='LocalBackendTest,MemoryBackendTest'`. Expected: FAIL.
- [x] **Step 3: Write the interface and the two backends, and the marker in `AbstractStorageTestCase`.** Run the tests, and `StorageModuleTest`: PASS.
- [x] **Step 4: Commit:** "Add the storage backend interface, local folders and an in-memory fake"

**CI:** the `Tests` shards; local backends sit under `target/`.

---

### Task 4: Encryption and keys

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/crypto/BackendKeys.java`, `RecoveryKey.java`, `FileCipher.java`, `KeyCheck.java`
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/crypto/FileCipherTest.java`, `BackendKeysTest.java`, `RecoveryKeyTest.java`, `KeyCheckTest.java`

**Interfaces:**
- Produces:

```java
@Component
public class BackendKeys {                    // the folder from -Dhealdan.storageKeys (Global Constraints)
	public byte[] make(long backendId) throws IOException;    // 32 random bytes; refuses to overwrite a file
	public Optional<byte[]> read(long backendId);             // empty when missing or not 32 bytes
	public void restore(long backendId, byte[] key) throws IOException; // Enter Recovery Key
	public void delete(long backendId) throws IOException;    // Remove, once the backend is empty
}
public final class RecoveryKey {
	public static String format(byte[] key);                  // 13 groups of 4, Crockford base32
	public static byte[] parse(String typed);                 // case, spaces and hyphens ignored; IllegalArgumentException
	public static String lastGroup(byte[] key);
}
public final class FileCipher {               // Tink's streaming AEAD over one backend's key
	public FileCipher(byte[] key);
	public void encrypt(Path plain, Path cipher, String associated) throws IOException;
	public InputStream decrypting(InputStream cipher, String associated) throws IOException;
	public SeekableByteChannel decrypting(SeekableByteChannel cipher, String associated) throws IOException;
	public static String associated(long docId, String resource);   // "<docId>/<resource>"
}
public final class KeyCheck { /* writes and reads the check object: a fixed plaintext under "healdan-check" */ }
```

`make` and `restore` write `backend-<id>.key.new` with mode 600, move it into place and sync the folder, under a lock file in the folder, as `SecretBox` makes its key (M4 R37). The folder is made with mode 700.

- [x] **Step 1: Write the failing tests**
  - `FileCipherTest`:
    - round trips of 0 bytes, 1 byte, 1,048,520 and 1,048,521 bytes (the first segment's edge: Tink's 40-byte header, 1 + 32 + 7, and its 16-byte tag leave the first segment 1,048,520 bytes of plaintext, and every later one 1,048,560), 2,097,080 and 2,097,081 bytes (the second's), and 5 MiB;
    - ranges through the seekable channel: the first byte, the last, ranges across the first segment's edge (1048500 to 1048540) and the second's (2097060 to 2097100), and one inside the last segment;
    - `testCiphertextHoldsNoPlaintext`: a file of one repeated token encrypts to bytes that don't contain it;
    - `testWrongKeyRefused`: decrypting under another key throws (`IOException`), whole and ranged;
    - `testSwappedObjectRefused`: a file encrypted for `12/1.2` doesn't decrypt as `12/1.3` or `13/1.2`;
    - `testTruncatedRefused`: a ciphertext missing its last segment throws.
  - `BackendKeysTest`: the file is 32 bytes, `rw-------`, its folder `rwx------` (POSIX only, `Assume` elsewhere); `make` refuses an existing file; of two concurrent `make`s, one makes the key and the other throws, as the file then exists, and the file holds the first's key (`make` never overwrites); `restore` replaces a missing file.
  - `RecoveryKeyTest`: `format` gives 52 characters in 13 groups; `parse(format(k))` is `k`, also lower-cased, unspaced and with `O` for `0` and `I`/`L` for `1`, as Crockford reads them; a wrong length or character is refused; so is a last character other than `0` or `G`, as the last character carries one bit of the key and four spare bits that must be zero; so every one-character typo is either refused here or changes the key, which `KeyCheck` catches (with Task 12).
  - `KeyCheckTest`: the check object decrypts under the right key and fails under another.
- [x] **Step 2: Run them to see them fail.** Run: `mvn -B -ntp -pl healdan-storage test -Dtest='FileCipherTest,BackendKeysTest,RecoveryKeyTest,KeyCheckTest'`. Expected: FAIL.
- [x] **Step 3: Write them.** The key object is `AesGcmHkdfStreamingKey.create(params, SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()))` with the parameters in the Global Constraints, and the primitive `AesGcmHkdfStreaming.create(key)` (call 2). Run the tests: PASS.
- [x] **Step 4: Commit:** "Encrypt a backend's files with Tink's streaming AEAD, under a key file and a recovery key"

**CI:** the `Tests` shards; keys under `target/`.

---

### Task 5: The S3 backend, against a fake S3 server

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/backend/S3Backend.java`, `S3Settings.java`, `S3Channel.java` (a `SeekableByteChannel` over ranged `GET`s, its size from `HEAD`), `Endpoints.java` (the endpoint rule)
- Create (tests): `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/backend/FakeS3.java`, `S3BackendTest.java`, `EndpointsTest.java`

**Interfaces:**
- Produces: `S3Backend(long id, String installId, S3Settings settings, String secret, boolean create)`, as `LocalBackend` takes `create` (Task 3): with it, the backend writes the marker object, `<prefix>.healdan-backend` (`<installId> <id>\n`), when none exists, and refuses, with a `BackendFailingException`, a bucket and prefix whose marker names another install or backend; nothing else writes an S3 marker (Tasks 14 and 16 build the backend with `create` true when they add one). `S3Settings` is the endpoint, region, bucket, prefix, path style and access key, and the timeouts and attempts (10 s, 60 s and 2 attempts, Task 0; tests give 1 s and 1 attempt, so `testUnreachable` takes seconds, not the minutes of the SDK's default four 60-second waits); it implements `Backend`. Its client:
  - `S3Client.builder().httpClient(UrlConnectionHttpClient.builder().connectionTimeout(…).socketTimeout(…).build())`;
  - `.endpointOverride(endpoint)`, `.region(Region.of(region))`, `.forcePathStyle(pathStyle)`, static credentials;
  - `.overrideConfiguration(o -> o.retryStrategy(b -> b.maxAttempts(attempts)))`, 2 in use: the SDK's default is 4 (Task 0);
  - `.requestChecksumCalculation(WHEN_REQUIRED)` and `.responseChecksumValidation(WHEN_REQUIRED)` for any endpoint that isn't `*.amazonaws.com` (§10.1);
  - single puts up to 64 MiB with `contentMD5`; larger files in 16 MiB parts, each with its `contentMD5`, aborted on any failure;
  - `stage` uploads to the key, `commit` does nothing, and `discard` deletes the object (call 20);
  - `check()` reads the marker object, `<prefix>.healdan-backend`, with a `GET`, which creating the backend (`create` true) writes: missing, or naming another install or backend, is a `BackendFailingException` (call 23), and so is a missing bucket, as "bucket not found": `GET` and `ListObjectsV2` answer `404 NoSuchBucket`, while `HEAD` answers a missing bucket with `404 NoSuchKey`, as for a missing object (Task 0), so nothing that must tell the two apart uses `HEAD`;
  - `hold` returns the object key and touches nothing (§8).
- `Endpoints.check(URI endpoint, Function<String, InetAddress[]> resolver)` applies the endpoint rule (Global Constraints) to the addresses the resolver gives for the host; production passes the JDK's (`InetAddress::getAllByName`), and tests a map, so no test needs real DNS.
- An `SdkClientException` (in the trial, every network failure: a `java.net` `ConnectException`, `SocketTimeoutException` or `UnknownHostException` as its cause) or an `S3Exception` with a `5xx` status, after the client's attempts, is a `BackendUnreachableException`. Any other `S3Exception` is a `BackendFailingException` naming the cause: a `403` (`AccessDenied`, or Wasabi's `SignatureDoesNotMatch` for a wrong secret) as "access denied", and `NoSuchBucket` as "bucket not found", with no secret or signature in its message.

- [x] **Step 1: Write the failing tests**
  - `FakeS3`: path-style `PUT` (checking `Content-MD5` when sent, `400 BadDigest` otherwise), `GET` with `Range`, `HEAD`, `DELETE`, `ListObjectsV2` with a prefix, and multipart (`CreateMultipartUpload`, `UploadPart`, `CompleteMultipartUpload`, `AbortMultipartUpload`). It records every request's headers, can answer `503`, stall, or drop the connection on demand, and holds bytes in memory. Over plain HTTP the SDK sends `Content-Encoding: aws-chunked` bodies in signed chunks (`x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD`, the body's length in `x-amz-decoded-content-length`), which `FakeS3` decodes before checking `Content-MD5`, and `Expect: 100-continue`, which it must answer at once (check that the JDK's `HttpServer` does). It also serves HTTPS (`HttpsServer` with a test certificate, which `S3Backend` trusts only through a package-private seam that tests set, as M4's `SignInTestHooks` sets what provider calls trust), where the SDK sends a plain body with `UNSIGNED-PAYLOAD`, as Wasabi gets (Task 0).
  - `S3BackendTest extends BackendContract`, against `FakeS3`, and also:
    - `testContentMd5OnEveryUpload`: over HTTP and over HTTPS, every `PUT` and part carries `Content-MD5`; none carries `x-amz-checksum-*` or `x-amz-sdk-checksum-algorithm`; a round trip returns the same bytes.
    - `testCorruptUploadRefused`: with `FakeS3` flipping a byte in transit, the put fails and no object exists.
    - `testMultipart`: a 70 MiB file goes up in 4 parts of 16 MiB and a last of 6 MiB.
    - `testMarker`: building with `create` true writes the marker object, and again over its own marker changes nothing; with `create` true over a marker naming another install or backend, it's refused and the marker is untouched; with `create` false, a marker naming another install or backend, or none, fails `check()`, and no marker is written; a bucket that doesn't exist fails it with "bucket not found".
    - `testMultipartAbortedOnFailure`: the third part failing aborts the upload; `FakeS3` holds no open upload.
    - `testRanges`: `open` reads bytes 1048000 to 1049999 with one ranged `GET`.
    - `testPrefix`: with prefix `healdan`, the key `123/45/doc/1.2` is stored as `healdan/123/45/doc/1.2`.
    - `testUnreachable`: a stalled or dropped connection, and a `503` after retries, are `BackendUnreachableException`.
    - `testSecretNeverInMessages`: no exception message, `toString` or log line from a `403` contains the secret.
  - `EndpointsTest`, with a resolver from a map (no real DNS): `https://s3.us-east-2.wasabisys.com` passes; `http://` to a public name or address fails; `http://10.0.0.5:3900` and `http://localhost:3900` pass; a name that resolves to a private address passes and one that resolves to any public address, among private ones, fails; a URL with a path, query or user info fails.
- [x] **Step 2: Run them to see them fail.** Run: `mvn -B -ntp -pl healdan-storage test -Dtest='S3BackendTest,EndpointsTest'`. Expected: FAIL.
- [x] **Step 3: Write `S3Backend`, `S3Channel` and `Endpoints`,** applying Task 0's rulings. Run the tests: PASS.
- [x] **Step 4: Commit:** "Add the S3 backend, tested against a fake S3 server"

**CI:** the `Tests` shards, with `FakeS3` on a loopback port. Garage covers the same client against a real server from Task 16.

---

### Task 6: `HealdanStorage`: CE's flows, saves and reads

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/HealdanStorage.java` (replacing Task 1's stub), `Backends.java` (builds and caches each backend from its row, with its cipher, and staging; `forget(id)` drops one, for Task 14; package-private `use(long id, Backend backend)` registers a backend for tests, such as a `MemoryBackend` as an S3-kind backend), `StoreOf.java` (call 10), `Staging.java`, `FileLocks.java` (call 20)
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/util/StorageUnreachableException.java` (call 13; thrown from Task 8 on)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/HealdanStorageTest.java`, `CeFlowsTest.java`, `StoreOfTest.java`

**Interfaces:**
- Consumes: `StoredFiles`, `HeldCopies`, `StoreBackendDAO`, `StorageBackendDAO`, `StorageSettings` (Task 2); `Backend`s (Tasks 3 and 5); `FileCipher`, `BackendKeys` (Task 4).
- Produces: `public class HealdanStorage extends AbstractStore`, keeping CE's deletion log and its `WRITECHECK` size check, and overriding every other `Store` method:
  - `store(InputStream|File, resource)`: `store(File)` first refuses an empty file with `AbstractStore.checkNotEmpty`, as `FSStore.store(File)` does: CE's `replaceFile` and `checkin` rely on that refusal, and nothing else stops a 0-byte replace through SOAP, REST, WebDAV or CMIS from wiping a version. Then, holding the file's lock (call 20), it finds the Store (`StoreOf.of(docId)`: empty when the document has no row or folder yet) and its backend; a local backend is written straight away (encrypted if it is), an S3 backend's file goes to staging and its row is left misplaced, for the mover (Task 11 adds the wake); then `recordWrite`. A previous copy on another backend is held directly (§6.2): `Backend.hold(key)` (Task 3), then `HeldCopies.hold(backendId, location, docId, resource, now + storage.holdDays)` (Task 2); Task 7 gathers these calls into `Held`. CE's `checkWriteAfterStore` reads `size()`, the row's plaintext size.
  - `getStream`, `writeToStream` (with and without a range), `getBytes` (both), `writeToFile`, `getString`: from the row's place; S3 through the cache from Task 8.
  - `exists`, `size`: from the row, never the backend; with no row, from Original's folder (call 11).
  - `listResources(docId, fileVersion)`: from the rows, exact (`1.2` and `1.2-…`, not `1.20`).
  - `delete(resource)`, `delete(docId)`: holding each file's lock, the copies are held as above (`Backend.hold`, then `HeldCopies.hold`), then the rows go; CE's history records the deletion as today.
  - `getTotalSize`: the rows' sizes summed. `moveResourcesToStore`: 0. `getStoreDefinitions`: empty. `newStore`: null, as today. `test()`: each backend's `check()`, recording nothing, until Task 12 makes it `BackendTest`. `getParameterNames`: empty.
  - `init()`: nothing that reads a table (Global Constraints).

- [x] **Step 1: Write the failing tests**
  - `StoreOfTest`: the Store comes from walking `ld_parentid`; a folder whose `ld_path` names another Store (as CE's restore leaves it) still gives its real Store, and so does one with no path; a document in a matter in Store S is S; in Templates, the Templates Store; in the Default workspace, none; an alias resolves to its target's document (`StoreResource` already follows `docRef`); an unknown doc id, none; the bulk form, over every folder at once, agrees with the single one.
  - `HealdanStorageTest` (with a `MemoryBackend` registered as an S3-kind backend through `Backends.use`, and a `LocalBackend` under `target/`):
    - `testLocalStoreWritesStraightThere`: a file of a document in a Store on a local backend is on that backend, and its row says so.
    - `testS3StoreGoesToStaging`: a file of a document in a Store on an S3 backend is in staging, its row says backend 0 and is left misplaced (`misplaced` lists it), and the S3 backend got no call.
    - `testReplacedCopyHeld`: a file rewritten after its Store moved to another backend lands on the new one, and the old copy is held on the old one: a local copy under `.held/`, an S3 object left in place, each with a `hd_storage_held` row whose purge date is `storage.holdDays` ahead.
    - `testUnknownStoreGoesToStaging`: a file of a document with no row yet goes to staging, with no Store.
    - `testWriteIds`: three writes of one resource give three different write ids; deleting it and writing it again gives a fourth.
    - `testEmptyFileRefused`: `store(File)` of 0 bytes throws, as `FSStore`'s does, and changes no file and no row.
    - `testSizeIsPlaintext`: on an encrypted local backend, `size()` is the file's length, and CE's size check passes with `WRITECHECK` on.
    - `testExistsAndSizeNeverTouchTheBackend`: with the S3 backend unreachable, `exists` and `size` answer from the row.
    - `testNoRowReadsOriginal` (call 11): a file only in Original's folder, with no row, reads, and a warning is logged.
    - `testInitReadsNothing`: `init()` with no Healdan tables doesn't fail, and may run twice.
  - `CeFlowsTest`, CE's own code through `HealdanStorage` (§16.1), each on a local Store and on an S3 Store:
    - `DocumentManager.create`, then `checkin` (a new file version);
    - `replaceFile` on version 1.2 with 1.20 present: 1.20's files stay, and 1.2's derived files are held; a second `replaceFile` of 1.2 within the holding period works too (Task 3's `testHoldTwice`);
    - `replaceFile` and `checkin` with a 0-byte file: refused, and the version's bytes and row unchanged;
    - `deleteVersion` of 1.2 with 1.20 present;
    - `destroyDocument` (the trash purge): every file held, every row gone, though CE deleted `ld_document` first;
    - `copyToFolder` into another Store: on a local target Store, the copy's files are on its backend; on an S3 one, they're staged with the target Store; either way nothing is written to the source Store's backend;
    - M1.5's preview and thumbnail for a `.docx` through `PreviewWorker`: made from the staged original, and stored beside it;
    - a ranged read through `writeToStream(resource, out, start, length)`, as CE's `ServletUtil` serves a `Range` header.
- [x] **Step 2: Run them to see them fail.** Run: `mvn -B -ntp -pl healdan-storage test -Dtest='StoreOfTest,HealdanStorageTest,CeFlowsTest'`. Expected: FAIL.
- [x] **Step 3: Write them.** Run the tests: PASS. Also run `StorageModuleTest`: the one `Store` bean is now a `HealdanStorage`. Install `healdan-core` first (Conventions) for `StorageUnreachableException`, or use the one-reactor form.
- [x] **Step 4: Commit:** "Send CE's file calls to each Store's backend through HealdanStorage"

**CI:** the `Tests` shards: CE's real `DocumentManager`, DAOs and M1.5's worker, on HSQLDB, with `store=` set to `HealdanStorage` in `healdan-storage`'s test context only.

---

### Task 7: Held deletes and the purge

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/Held.java`, `Purge.java`
- Modify: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/HealdanStorage.java` (Task 6's two direct holds, in `store` and `delete`, become `Held.hold`; a read of a local backend's file missing from its place falls back to `.held/`), and `backend/LocalBackend.java` if the fallback lives there rather than in `HealdanStorage` (it may use `LocalBackend.held(key)`, Task 3)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/HeldDeletesTest.java`

**Interfaces:**
- Consumes: `Backend.hold`, `held`, `purgeHeld` (Task 3); `HeldCopies.hold`, `due`, `drop`, `storage.holdDays` (Task 2); `FileLocks` (Task 6).
- Produces: `Held.hold(backendId, docId, resource)`: a local copy moves under `.held/`, replacing an earlier held copy of the same key, and an S3 object stays; either way a `hd_storage_held` row records where and its purge date (now plus `storage.holdDays`). It replaces Task 6's direct `Backend.hold` and `HeldCopies.hold` calls, which do the same.
- Produces: `Purge`, a Spring bean, with `setInTransfer(BiPredicate<Long, String>)`, the predicate given a held copy's doc id and resource: "never" (`(docId, resource) -> false`) until Task 11's `StorageStartup` sets it to `Mover.moving` before it starts the purge. `Purge.run()`: for each held copy that's due, holding its file's lock (call 22), deletes it, then its row; it skips, and drops the row of, an S3 object that a `hd_storage_file` row points at again (same backend and key); it skips, keeping the row for the next purge, an S3 object whose file `inTransfer` says is being moved; and a local `.held/` copy is deleted only from `.held/`. Nothing starts the purge in this task: Task 11's `StorageStartup` does, 10 minutes after a start; tests call `run()`.
- Reads: a local backend's file missing from its place is read from `.held/` (§6.3), so a restored database finds a file deleted after its backup.

- [x] **Step 1: Write the failing tests** (`HeldDeletesTest`):
  - `testLocalHeldThenPurged`: a deleted version's file moves to `.held/`, stays readable through a row restored by hand (a restored database), and is gone after the purge past its date.
  - `testS3HeldUntouched`: deleting on an S3 backend makes no backend call; the purge deletes the object after its date.
  - `testPurgeSkipsRepointedObject`: an object held, then pointed at again by a row (the mover re-uploaded under the same key, §6.2), survives the purge, and its held row is dropped.
  - `testPurgeSkipsInTransfer`: with an `inTransfer` predicate that answers true for a due held S3 object's file, the purge leaves the object and its held row; with "never", it deletes both.
  - `testReplacedPreviewHeld`: `replaceFile` holds the old thumbnail, and a second `replaceFile` within the period holds the newer one in its place.

  `testPurgeWaitsForTransfer` and `testMovedSourceHeld` need the mover, so they're Task 11's, in `MoverTest`. `storage.holdDays`' range is tested once, in Task 2's `testSettingsRanges`.
- [x] **Step 2: Run them to see them fail.** Run: `mvn -B -ntp -pl healdan-storage test -Dtest=HeldDeletesTest`. Expected: FAIL.
- [x] **Step 3: Write `Held` and `Purge`, and the `.held/` fallback.** Run `HeldDeletesTest`, and Task 6's `HealdanStorageTest` and `CeFlowsTest`, whose holds now go through `Held`: PASS.
- [x] **Step 4: Commit:** "Hold every delete for the holding period, on every backend"

**CI:** the `Tests` shards.

---

### Task 8: The read cache

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/ReadCache.java`
- Modify: `HealdanStorage.java` (S3 reads through the cache; ranges of large files straight from the bucket; a `BackendUnreachableException` from a read becomes `StorageUnreachableException` naming the row's Store)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/ReadCacheTest.java`; `HealdanStorageTest.java` (one test)

**Interfaces:**
- Produces: `ReadCache(Path folder, LongSupplier capBytes)` in `-Dhealdan.storageCache`; an entry is `<backendId>/<docId>/<resource>@<write>`, plain bytes, named by the row's write id (call 21), so neither a rewrite nor a database restored in place, which hands out document ids again, can be served another file's bytes; a size cap in bytes, which production gives as `storage.cacheGb` × 2^30 (read each time, so a saved setting applies at once) and tests in MiB; least recently read dropped first (the access time kept in memory, and the files' modification times on a restart, when the index is rebuilt from the folder, §6.4). A read fetches the whole object, decrypts it into a temporary file in the cache folder, moves it into place, and serves from there. A file larger than a tenth of the cap isn't cached: its ranges are read through `S3Channel` and decrypted segment by segment.
- `ReadCache.adopt(backendId, docId, resource, write, Path staged)` (question 9): the mover's staged copy, once uploaded, becomes that write's entry, copied into the cache volume (staging is on the repository volume, so a rename can't cross) and deleted from staging; a file over a tenth of the cap is just deleted.
- An unreachable backend on a miss: `ReadCache`, which knows no Store, throws `BackendUnreachableException`, and `HealdanStorage` turns it into a `StorageUnreachableException` (Task 6's class in `healdan-core`) naming the row's Store: "This file is in <Store>'s cloud storage, which can't be reached right now." (§6.5).

- [x] **Step 1: Write the failing tests** (`ReadCacheTest`, with a `MemoryBackend` that counts `get`s):
  - `testSecondReadHits`: the second read makes no backend call.
  - `testCap`: with a 10 MiB cap, reading 12 files of 1 MiB (a tenth of the cap, so cached) leaves the 10 most recently read.
  - `testLeastRecentlyReadDroppedFirst`: with the same cap and size, reading A, B, A, then nine others drops B, not A.
  - `testRewrittenFileNeverStale`: after a rewrite (a new write id), the next read fetches again and serves the new bytes.
  - `testReusedDocIdNeverHits`: an entry for doc X's earlier row is never served for a new row of doc X with another write id, as after a database restored in place.
  - `testLargeFileReadByRange`: a 3 MiB file with a 20 MiB cap isn't cached, and a 1 KiB range costs one ranged read.
  - `testIndexRebuiltOnRestart`: a new `ReadCache` on the same folder serves the earlier entries.
  - `testDeleteDropsEntry`.
  - `testAdoptedCopyHits`: a staged copy handed to `adopt` is served with no backend call, and is gone from staging; one over a tenth of the cap is deleted, not cached.
  - `testUnreachableMiss`: an unreachable backend and no entry throw `BackendUnreachableException`.
  - `HealdanStorageTest.testUnreachableUncached`, through `HealdanStorage`: a document in Store S on an unreachable S3 backend, with no cache entry, throws `StorageUnreachableException` whose message names S; a cached file still reads (§6.5).
- [x] **Step 2–3: Run them to see them fail, write `ReadCache`, run them:** PASS.

  Run: `mvn -B -ntp -pl healdan-storage test -Dtest='ReadCacheTest,HealdanStorageTest'` (with `healdan-core` installed for `StorageUnreachableException`, or the one-reactor form, Conventions).
- [x] **Step 4: Commit:** "Serve S3 reads through a capped cache on the server"

**CI:** the `Tests` shards, with the cache under `target/`.

---
## PR B: recording, Store tracking and the mover

### Task 9: Recording what's already there

**Carried from PR A's build (2026-11-07):**
- `StorageModuleTest.testOriginalHoldsItsMarker` passes today only because `AbstractStorageTestCase` writes the marker; when this task takes that out, point the test at the recording seed.
- Rows record the file's plaintext size: `ReadCache` checks every fetch against the row's size and fails the read on a mismatch.

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/seed/RecordOriginalSeed.java` (`storage-record-original`), `HideStoresSeed.java` (`storage-hide-stores`, question 1)
- Modify (tests): `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/AbstractStorageTestCase.java` (no longer writes Original's marker after `super.setUp()`, which Task 3 added: this seed now writes it during `super.setUp()`, on the emptied folder and a database with no documents)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/seed/RecordOriginalSeedTest.java`, `HideStoresSeedTest.java`

**Interfaces:**
- Consumes: `StoredFiles` (Task 2), `StoreOf` (Task 6), `LocalBackend` (Task 3).
- Produces: the seed (§5.3). `enabled()` is false unless `Store.get()` is a `HealdanStorage` (call 7), read through a package-private `Supplier<Store>` that tests set. It:
  1. writes Original's marker (this install's id and `1`) if there's none (§10.2), unless Original's folder looks unmounted: no marker, no file (dot names aside), and `ld_document` has documents not deleted (`ld_deleted = 0`). Then the seed fails, and so stops startup, with a message saying Original's folder looks unmounted, naming `store.1.dir`, and saying how to write the marker by hand if every document really has lost its file (the file `.healdan-backend` holding `<storage.installId> 1`). Otherwise the first start with an NFS share that didn't mount would mark the empty mount point and record nothing, and §10.2's guard would be gone for good (Review Focus 5);
  2. walks `store.1.dir`, skipping every name that starts with `.` (so `.held/` and the marker) and doc id 0 (CE's own `test()` leftovers), reading each path `<digits>/…/doc/<resource>` as the doc id the digits make (`AbstractStore.computeRelativePath` in reverse). A folder it can't read (on an NFS share whose owners the entrypoint can't change, say) is logged and skipped, not thrown: a seed that fails stops every start, and would walk the whole folder again each time. Its files get no row and still read through call 11; the log names each such folder, and the totals count them;
  3. inserts, in batches of 500, a row for each file that has none: backend 1, its size, its document's Store (`StoreOf`, in bulk), a new write id;
  4. gives a file whose document isn't in `ld_document` no Store, and logs a warning naming the doc id and resource;
  5. logs its progress every 10,000 files, and the totals at the end.

  It never updates a row, and takes no file's lock (pre-flight, 2026-11-07): seeds run while the Spring context starts, before Tomcat opens its connectors and before CE's scheduler starts, so no save can run beside it. A start that fails part-way resumes at the next, as only missing rows are inserted.
- Produces: `storage-hide-stores` (question 1), as `healdan-core`'s `hide-dropbox` hides Dropbox: it deletes the ACL rows of menu 105 (`Menu.STORES`, CE's Stores screen) and marks the menu deleted, which every menu query leaves out, admins' included. `enabled()` is false unless `Store.get()` is a `HealdanStorage`, read through the same package-private `Supplier<Store>` seam as `RecordOriginalSeed`'s.

- [x] **Step 1: Write the failing tests** (`RecordOriginalSeedTest`, and `HideStoresSeedTest`: menu 105 is gone for `admin`, its ACL rows are gone, and other settings menus stay; disabled without `HealdanStorage`, through its `Supplier<Store>`):
  - `testEveryFileRecorded`: files of three documents in two Stores, with versions and derived files, give one row each, with their sizes and Stores.
  - `testOrphan`: a file of doc 99999, not in `ld_document`, gets a row with no Store, and a warning.
  - `testSkipsDotNamesAndTestFile`: `.held/…`, the marker and `0/doc/test` get no row.
  - `testKeepsExistingRows`: a row already there (from an earlier start whose run failed part-way) keeps its backend, size and write id.
  - `testUnreadableFolderSkipped` (POSIX only): a `chmod 000` folder under Original's is logged and skipped, every other file is recorded, and the seed finishes.
  - `testMarkerWritten`: on an empty folder and a database with no documents (a fresh install), and on a folder holding files with no marker (an upgrade), the marker is written.
  - `testUnmountedOriginalStopsStart`: with no marker, no file, and a document not deleted in `ld_document`, the seed fails with the unmounted message, writes no marker and records nothing; with the same document deleted (`ld_deleted = 1`), it writes the marker.
  - `testDisabledWithoutHealdanStorage` (through the seed's package-private `Supplier<Store>`).
  - The earlier tasks' tests still pass with `AbstractStorageTestCase` leaving the marker to this seed: run `StorageModuleTest`, `HealdanStorageTest` and `CeFlowsTest`.
- [x] **Step 2–3: Run them to see them fail, write the seed, run them:** PASS.

  Run: `mvn -B -ntp -pl healdan-storage test -Dtest='RecordOriginalSeedTest,HideStoresSeedTest,StorageModuleTest,HealdanStorageTest,CeFlowsTest'`
- [x] **Step 4: Measure it:** time the seed over 100,000 small files under `target/` and record files a second in PR B, for `install/upgrading.md`'s sentence (Task 19).
- [x] **Step 5: Commit:** "Record every file in Original's folder on the first start, and hide CE's Stores screen"

**CI:** the `Tests` shards (the 100,000-file measurement is run once by hand, not in CI).

---

### Task 10: Keeping each file's Store current

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/StoreTrackingDocumentListener.java`, `StoreTrackingFolderListener.java`, `StoreTracking.java`, `NightlyCheck.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/rules/FolderTreeListener.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/rules/HealdanHooksImpl.java` (`afterFolderRestore` ends by calling each `FolderTreeListener` bean with the documents it already works out, call 6)
- Modify: `healdan-storage/src/main/resources/plugin.xml` (the two listener extensions, Global Constraints)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/StoreTrackingTest.java`, `NightlyCheckTest.java`; `healdan-core/src/test/java/com/logicaldoc/healdan/rules/FolderTreeListenerTest.java`

**Interfaces:**
- Produces:

```java
/** Told after CE moved a folder tree with JDBC, where no FolderListener runs (healdan-core) */
public interface FolderTreeListener {
	void afterTreeRestored(long folderId, long parentId, List<Long> documentIds) throws PersistenceException;
}
@Component
public class StoreTracking implements FolderTreeListener {
	public void documentChanged(long docId);      // re-reads its Store; rows updated, and left misplaced for the mover
	public void treeChanged(long folderId, Collection<Long> documentIds); // the tree's Store, found once by its parents; one update
	public int checkStore(long storeId);          // before a Store switches backend (§5.4); returns rows fixed
}
@Component
public class NightlyCheck { public int run(); }  // every row's Store recomputed from ld_folder's parent ids, read once; each fix logged
```

- No Store comes from `ld_path` (call 10). A moved folder's documents come from `StoredRows.tree` and `documentIds`, which walk parent ids, as `afterFolderRestore` already does.
- Nothing here wakes the mover, which arrives in Task 11: a row whose Store changed is left misplaced (`setStore` sets its `hd_waiting`), and Task 11 adds the `wake()` calls to `StoreTracking` and `NightlyCheck`. `setStore` is a bulk update without the files' locks (Task 2).
- The document listener acts in `afterStore` when the transaction's event is `MOVED` or `RESTORED`, or the document's Store differs from its rows'; the folder listener when a folder's parent changed (`MOVED`, `RESTORED`). Both update rows only after the transaction commits (`TransactionSynchronization`), and do nothing unless `Store.get()` is a `HealdanStorage`.

- [x] **Step 1: Write the failing tests**
  - `FolderTreeListenerTest` (`healdan-core`): `afterFolderRestore` calls a registered listener once, after its own work, with the folder, the parent and the tree's documents.
  - `StoreTrackingTest` (Stores A on a local backend and B on an S3 `MemoryBackend`):
    - `testMatterMovesStore`: moving a matter's workspace from A to B (`FolderDAO.move`, as an admin, M1's rule) gives its documents' rows Store B, still on A's backend, so `misplaced` lists them, with `hd_waiting` set.
    - `testDocumentMovesBetweenStores`: `DocumentManager.moveToFolder` from a matter in A to one in B.
    - `testMergeAcrossStores`: merging a folder in A into one in B (`moveDocumentsOnMerge`).
    - `testRestoreIntoAnotherStore`: a folder deleted from A and restored under a matter in B (`HibernateFolderDAO.restore`) through the hook gives its documents' rows Store B, though its `ld_path` still names A, and a document saved there afterwards is written as B's.
    - `testRolledBackMoveChangesNothing`: a move that Healdan's rules veto leaves the rows' Stores alone.
    - `testCopyIntoAnotherStore`: `copyToFolder` stages the copy with Store B (Task 6), writes nothing to A's backend, and leaves the copy's rows misplaced toward B, with none of A's rows misplaced.
  - `NightlyCheckTest`: a row given a wrong Store by hand is fixed, the fix is logged with the doc id, resource and both Stores, and the row is left misplaced toward its real Store's backend; a folder whose `ld_path` names another Store doesn't change the answer; a correct repository gives 0 fixes.
- [x] **Step 2–3: Run them to see them fail, write the listeners, the hook and the check, run them:** PASS. Also run `afterFolderRestore`'s existing tests: `mvn -B -ntp -pl healdan-core -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='FolderTreeListenerTest,PropagationTest,PermissionRulesTest,DeleteTreeCacheTest,FilingRulesTest' -Dsurefire.failIfNoSpecifiedTests=false package`, and `healdan-storage`'s in one reactor (Conventions): PASS.
- [x] **Step 4: Commit:** "Keep each file's Store current through moves, merges and restores, and check it nightly"

**CI:** the `Tests` shards: CE's real folder and document DAOs, with Healdan's rules on.

---

### Task 11: The mover

**Carried from PR A's build (2026-11-07):**
- Build `ReadCache` at start, in `StorageStartup`, so the first saves after a start don't wait for its index to be walked under their file's lock.
- The mover commits in short transactions of its own and never holds a file's lock while it waits on a row lock that an open CE transaction may hold (a folder copy releases each file's lock before its transaction commits), or the two can deadlock through a shared stripe until InnoDB's lock-wait timeout. `moved` and `recordFailure` are conditional `UPDATE`s that can match nothing: run them only in those short transactions.
- A staged row with no Store (a document with no folder yet when it was saved) has its Store worked out again before it's moved.
- Rows record the plaintext size, as `ReadCache`'s size check needs.
- Set `Purge`'s in-transfer test before starting it; the purge already marks a backend unhealthy for the rest of a run when it's unreachable.

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/mover/Mover.java`, `Transfer.java`, `RetrySchedule.java`, `StorageStartup.java`
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/BackendHealth.java` (its state, moved here from Task 12 by the pre-flight scan; Task 12 adds its other causes)
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/HealdanStartListener.java`
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/HealdanStartup.java` (in `start()`, when `run()` returns true, beside `previewWorker.start()` and M5's `emailIndexWorker.start()`, each `HealdanStartListener` bean's `started()`; not in `run()`, which every Healdan test's `setUp` calls, call 5)
- Modify: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/HealdanStorage.java` (a staged save wakes the mover; a write to a failing backend fails, and one to an unchecked backend checks it first, through `BackendHealth`), `StoreTracking.java` and `NightlyCheck.java` (each wakes the mover when it leaves a row misplaced; Tasks 6 and 10 left them misplaced without a wake), `Purge.java` (its in-transfer test is `Mover.moving` from here, Task 7)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/mover/MoverTest.java`, `RetryScheduleTest.java`, `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/BackendHealthTest.java`; `healdan-core/src/test/java/com/logicaldoc/healdan/HealdanStartListenerTest.java`

**Interfaces:**
- Produces:

```java
public interface HealdanStartListener { void started(); }   // healdan-core: after the migrations and seeds ran, at a real start

@Component
public class Mover {
	public void start();                         // a coordinator thread, healdan-storage-mover, and storage.transfers workers
	public void wake();
	public void retry(long storeId);             // stuck files back to waiting (the tab's Retry)
	public void resize(int transfers);           // the tab's saved setting, at once
	public boolean moving(long docId, String resource); // the purge asks (call 22)
	public int runOnce();                        // tests: one pass, waiting for its transfers
	@PreDestroy public void stop();
}
```

- `BackendHealth` (moved here from Task 12, pre-flight): per backend, unchecked, OK or failing, with the last error and since when (§10.3), kept in memory and logged on each change. Its check is the backend's own `check()` (Tasks 3 and 5: the marker, and for S3 its reachability); a `BackendUnreachableException` makes the backend failing as unreachable, which pauses its transfers, and any other failure failing. It checks every backend at start, from `StorageStartup`, before the mover's first pass (call 24), and each failing backend again every `storage.retestSeconds`; one found OK again resumes its transfers. A failing backend refuses writes, though saves into an S3 Store still land in staging. A write that reaches a backend still unchecked runs the check itself first, holding that backend's check lock (one per backend, so concurrent first writes check once), and is refused only if the check fails (call 24): seeds and tests run before `start()`. `unavailable()` gives the paused and unchecked backends, for `misplaced`. Task 12 adds the other causes, each with its own wording and test (the marker missing or naming another install or backend, the bucket not found, the check object, the secret), and Test and the alert.
- One pass takes `StoredFiles.misplaced(limit, BackendHealth.unavailable())`: rows whose backend isn't their Store's backend (no Store: Original), not stuck, due, and not on a paused or unchecked backend, all left out in the SQL (Task 2). Staged files come first, then moves, newest documents first (§7). No file is in two transfers at once; the files in transfer are kept in a set, which `moving` answers from.
- A transfer (§7, changed by calls 20 and 21):
  1. checks that the row is still misplaced toward the target, its Store's backend being the target, and skips it if not: a file whose Store turned local after the pass began is never uploaded;
  2. reads the file from where it is, decrypting if that backend is encrypted, into a temporary file in staging, noting the row's write id;
  3. stages it on the target (`Backend.stage`), encrypting if the target is, with call 16's verification: an S3 target's object is at its key, which saves never write; a local target's file waits under a temporary name beside the key, where a save's bytes can't be overwritten;
  4. holding the file's lock: if the row's write id is still the one from step 2 and its Store's backend is still the target, commits the staged copy (a local file is moved into place), points the row at the target with `moved(docId, resource, write, target)`, and holds the source copy (Task 7), but for staging, whose copy it renames aside within staging, to hand to `ReadCache.adopt` once the lock is released (question 9); otherwise discards its staged copy (an S3 object is kept only if a held row names its key) and leaves the row alone, for the next pass if it's still misplaced.
- A failure counts an attempt, records the error, and sets the next try (`RetrySchedule`); the tenth marks the file stuck (§7). `BackendUnreachableException` counts nothing: it pauses that backend's transfers until `BackendHealth`'s retest finds it again.
- The mover is woken (Global Constraints' timings) by a staged save (`HealdanStorage`), a document or matter changing Store (`StoreTracking`), a nightly fix (`NightlyCheck`), and from Task 14 a Store switching backend and Retry. Tasks 6 and 10 leave those rows misplaced; this task adds the `wake()` calls.
- `StorageStartup` sets `Purge`'s in-transfer test to `Mover.moving` (`setInTransfer`, Task 7) before it starts the purge, so the purge never deletes a held object the mover is uploading again (call 22).
- `StorageStartup implements HealdanStartListener` checks every backend first (`BackendHealth`, call 24), then starts the mover and the retests at once, and the purge (Task 7) and the nightly check (Task 10) after 10 minutes; Task 12 adds the alert. `Mover.stop()` is a `@PreDestroy`, so no transfer outlives the context.

- [x] **Step 1: Write the failing tests**
  - `HealdanStartListenerTest` (`healdan-core`): `start()` calls a listener once, after the seeds; `run()` alone, as every test's `setUp` calls it, calls none; neither does `start()` when CE's schema doesn't exist yet.
  - `RetryScheduleTest`: the gaps in the Global Constraints, and stuck at the tenth failure.
  - `MoverTest` (`MemoryBackend`s, with `runOnce`):
    - `testStagedFirst`: with a staged file and a move both waiting and one transfer, the staged file goes first.
    - `testVerifiedBeforeRowMoves`: with `corruptNextPut`, the row stays on its source and the source copy stays readable.
    - `testSourceHeldAfter`: local to S3, and S3 to local: the source copy is held (§8), and the row points at the target.
    - `testStagedCopyCachedAfterUpload`: a staged file is gone from staging once uploaded, and its first read is a cache hit with no backend call (question 9).
    - `testSaveInStagingDuringCommitKept`: a save that lands in staging right after a file's upload commits is kept, and uploaded by the next pass.
    - `testRewriteMidCopyCaught`: a save of the file while its upload is slow leaves the row on the save's bytes and discards the mover's copy; the next pass moves the new bytes.
    - `testSaveDuringMoveHomeKept`: while a file moves from S3 to a local backend, a save writes it straight to that backend; the mover's staged copy never replaces it, nothing goes to `.held/`, and the row stays on the save's bytes, already in place.
    - `testDeleteAndRecreateMidCopy`: a file deleted and written again while its transfer is slow (Replace File, then M1.5 making the conversion again) keeps its new bytes and new write id; the old transfer's copy is discarded, not committed.
    - `testStoreTurnsLocalMidTransfer`: a file whose Store turns local while its upload waits in the pass is never uploaded; one whose Store turns local while its upload runs has its object discarded, and the row stays local.
    - `testCrashBeforeRowUpdate`: a transfer that dies after staging its copy leaves the row on the source, which still reads; the next pass finishes it.
    - `testRetriesThenStuckThenRetry`: ten refusals mark the file stuck with the last error; `retry` puts it back, and it moves.
    - `testUnreachablePausesWithoutSpendingRetries`: with the target unreachable, attempts stay 0 and the backend is paused; once it's back, the file moves.
    - `testParallelTransfers`: with `storage.transfers` 4 and a slow backend, four transfers run at once, never two on one file; after `resize(2)`, two.
    - `testStoreSwitch`: switching Store B's backend from Original to S3 moves every file of B, and none of A (§7).
    - `testLocalStoreNeverToS3` (Review Focus 2): files of a Store on a local backend are never written to the S3 backend, in each of these orders: the Store turned local before the pass; during the upload; between the upload and the commit; and a `recordWrite` racing the commit.
    - `testPurgeWaitsForTransfer` (from Task 7): with a due held object's file being uploaded again to the same key (a slow `MemoryBackend`), the purge leaves the object and its held row; once the row points at it, the next purge drops the held row only.
    - `testMovedSourceHeld` (from Task 7): the source of a move is held, not deleted, with its `hd_storage_held` row.
    - `testWoken` (the wakes Tasks 6 and 10 left to this task): with the mover started and a sweep of an hour, a staged save, a matter moved from Store A to B, and a nightly fix each start a pass at once; a copy into B (`copyToFolder`) is uploaded, with no move from A.
  - `BackendHealthTest` (Task 12 adds its causes' tests):
    - `testUncheckedUntilStart`: before `StorageStartup`, the mover doesn't touch an unchecked backend (its rows aren't listed); the first write into it runs its check first, and goes on once that passes; a first write into a local backend whose marker is missing is refused, writes nothing, and leaves it failing; `StorageStartup` checks every backend before the mover's first pass.
    - `testFirstWritesCheckOnce`: two writes reaching one unchecked backend at once run its check once.
    - `testUnreachableThenBack`: failing while unreachable, its transfers paused; OK at the first retest after it's back, and the mover resumes.
    - `testS3StoreSavesWhileFailing`: a save into a Store on a failing S3 backend lands in staging.
- [x] **Step 2–3: Run them to see them fail, write the mover, `BackendHealth`, the wakes and the start hook, run them:** PASS.

  Run (one reactor, Conventions, as this task changes `healdan-core`): `mvn -B -ntp -pl healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='MoverTest,RetryScheduleTest,BackendHealthTest,HealdanStartListenerTest,HeldDeletesTest,StoreTrackingTest,NightlyCheckTest,HealdanStorageTest' -Dsurefire.failIfNoSpecifiedTests=false package`
- [x] **Step 4: Commit:** "Move files between backends in the background, verified before the source is held"

**CI:** the `Tests` shards, with `runOnce` so no test waits on the sweep.

---

### Task 12: Failing backends, Test, and the alert

**Files:**
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/BackendTest.java`, `StorageAlert.java`
- Modify: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/BackendHealth.java` (Task 11 made it, with its states, the pause and resume, the retests, the first-write check and the unreachable cause; this task adds the other causes), `mover/StorageStartup.java` (starts the alert beside the retests), `HealdanStorage.java` (`test()` runs `BackendTest` on every backend, in place of Task 6's `check()`; Task 11 already refuses writes to a failing backend)
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/signin/OutgoingEmail.java` (a public `sendTemplate(User, String template, Map<String, Object> dictionary)`, which builds the email and runs its package-private `fill` then `send`, so another module's alert sends through M4's sender, which throws where CE's `EMailSender` would drop an email silently); `healdan-core/src/test/java/com/logicaldoc/healdan/signin/SignInTestHooks.java` (a public `captureEmails(List<EMail>)`, undone by `reset()`, as `OutgoingEmail.sender` is package-private)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/BackendHealthTest.java` (Task 11's class; this task's tests join it), `BackendTestTest.java`, `StorageAlertTest.java`

**Interfaces:**
- Consumes: `BackendHealth`'s states, pause and resume, retests and first-write check (Task 11, call 24); `MemoryBackend.refuse(Op...)` (Task 3).
- Produces:
  - `BackendHealth`'s other causes, each with its own wording in the status (§10.3): the marker missing or naming another install or backend; an S3 backend's bucket not found (`NoSuchBucket`, Task 0); the check object missing or not decrypting (§9); the S3 secret not opening (`SecretBox.open` gives null, as after a new secret key: "enter the secret again in Edit"). Unreachable is Task 11's. Each cause is found at whichever check runs first (`StorageStartup`'s at start, or an unchecked backend's first write, call 24), at each retest while failing, and on Test. A failing backend refuses writes and holds its transfers (Task 11). A key file deleted while its backend is OK isn't noticed until Test or a restart: the cached key keeps working and loses nothing, and nothing re-reads key files on a timer (pre-flight, 2026-11-07).
  - `BackendTest.run(Backend backend, boolean checkMarker)`: for a local backend, checks the marker, writes a test object, reads it back whole and by a range, and deletes it (`list` hides its dot name, and only S3 lists); for S3 (§10.1), checks the marker object, puts a test object, reads it back whole and by a range, lists it and deletes it. Then, if the backend is encrypted, it checks the key (§9). With `checkMarker` false it skips the marker check, for a backend not yet saved, whose marker the add then writes (Task 14's Test before saving). It records no row and holds nothing.
  - `StorageAlert`, which `StorageStartup` starts beside the retests (this task's edit to Task 11's class): once a day, if a row's `hd_waiting` is more than 24 hours old, or a backend has failed for more than 24 hours, emails the members of the `admin` group who have an email, with the template `storage.alert` (Task 2), through `OutgoingEmail.sendTemplate`; nothing when `OutgoingEmail.isSetUp()` is false (M4's correction 3, R38). `storage.alert.sent` holds the day it last went, written only once every email was taken.

- [x] **Step 1: Write the failing tests**
  - `BackendHealthTest` (joining Task 11's `testUncheckedUntilStart`, `testFirstWritesCheckOnce`, `testUnreachableThenBack` and `testS3StoreSavesWhileFailing`):
    - `testMissingMarker`: a local backend whose marker is deleted is failing with "marker missing", and a document saved into its Store fails with an `IOException`, as a missing disk does today.
    - `testWrongKey`: an encrypted backend whose key file holds another key is failing at its check, refuses writes, and the mover doesn't write to it (Review Focus 3); so is one still unchecked when a first write reaches it, whose check then fails before anything is written under that key.
    - `testCheckObjectMissing`: an encrypted backend with files and no check object is failing.
    - `testSecretUnopened`: an S3 backend whose secret was sealed under a lost key is failing, saying to enter the secret again.
  - `BackendTestTest`: Test passes on a `LocalBackend` (Original included), a `MemoryBackend` and `FakeS3`, leaves no object and no row, and fails, naming the step, when ranges or deletes are refused (`MemoryBackend.refuse(Op.OPEN)`, `refuse(Op.DELETE)`); with `checkMarker` false, a bucket or folder with no marker passes, and with it true fails.
  - `StorageAlertTest` (capturing the email through `SignInTestHooks.captureEmails`): no email before 24 hours; one after; none a second time that day; another the next day while it lasts; none once it clears; none without outgoing email; a Store switched to S3 an hour ago raises nothing though its rows' `hd_stored` is months old, and files still waiting 25 hours after the switch raise it.
- [x] **Step 2–3: Run them to see them fail, write them, run them:** PASS.

  Run (one reactor, Conventions, as this task changes `healdan-core`'s `OutgoingEmail` and `SignInTestHooks`): `mvn -B -ntp -pl healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='BackendHealthTest,BackendTestTest,StorageAlertTest,MoverTest' -Dsurefire.failIfNoSpecifiedTests=false package`
- [x] **Step 4: Commit:** "Fail a backend that's unmounted, under the wrong key or unreachable, and alert the admins after a day"

**CI:** the `Tests` shards.

---

### Task 13: Outages: the API, the web page's downloads and previews

**Carried from PR A's build (2026-11-07):**
- An outage on `HealdanStorage`'s cache-miss retry (after an evicted entry) or mid-stream, after the read call returns, can still escape as a raw `BackendUnreachableException`; the API's and the web page's outage answers must catch both.
- `S3Channel` makes one ranged `GET` per read call: read through buffers of at least 1 MiB.

**Changed in the build (2026-11-08):**
- `HealdanStorage` turns every `BackendUnreachableException` that leaves it into `StorageUnreachableException`, also those its streams and channels throw mid-read, as `healdan-api` and `healdan-core` can't see `healdan-storage`'s class. `StorageUnreachableException.in(Throwable)` finds one in a cause chain.
- `TicketDownload` takes `response.getWriter()` before its `sendError(401)`, and `ConvertPdf`'s catch writes `pdf/notavailable.pdf` into the stream `ServletUtil.downloadDocument` has closed; the steps below left both out. The filter refuses the writer once the stream is taken, and drops a failed attempt's held steps when the servlet writes again after an outage. So `/convertpdf` serves CE's placeholder during an outage (spec §6.5: previews fall back to their placeholders), and `/download` and `/download-ticket` answer `503`. The test's stand-ins repeat the real servlets' code.

**Files:**
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/ProblemMapper.java` (a `StorageUnreachableException`, or one in the cause chain, is `503 storage-unreachable` with its message as `detail`)
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/web/DownloadErrorFilter.java` (question 7's default: `@WebFilter` over `/download`, `/download/*`, `/convertpdf/*` and `/download-ticket`, handing CE a response whose stream commits nothing until its first byte; a read that fails with `StorageUnreachableException` before then is answered `503` with the message, escaped, on a small page, whatever error the servlet then sends), test `DownloadErrorFilterTest.java`
  - It passes every request through untouched unless `Store.get()` is a `HealdanStorage` (Global Constraints), so a WAR on `FSStore`, as in PR B, is unchanged.
  - The routes, checked at `a7961fac7` in `logicaldoc-webapp/src/main/webapp/WEB-INF/web.xml` (pre-flight, 2026-11-07): `/download` and `/download/*` (`DownloadServlet`) and `/convertpdf/*` (`ConvertPdf`) reach `ServletUtil.downloadDocument`, which opens the response's stream and sets `200` before it reads the file. `/download-ticket` (`TicketDownload`, shared links) has its own `downloadDocument` in the same order: it opens the file's stream, sets the headers, opens the response's stream, then reads, and closes the response's stream in `finally`, so a read that fails on its first byte commits an empty `200`; one that fails while opening is caught in `doGet` and answered `401 Ticket … not authorized`. The filter answers both with the `503` page. The ticket's own password challenge (`401` with `WWW-Authenticate`, no read) passes through untouched, as the filter acts only when a read failed.
  - Left out, for Task 19's Known Limitations: `/download-attachment` (`DownloadAttachmentServlet`) reads and parses the whole email before it touches the response and serves the attachment from a temporary file, so a failed read never commits a `200`; it's answered `500` through `ServletUtil.sendError`, which `ErrorReportValve` shows as a bare page. `/zip-export` (`ExportZip`, through core's `ZipExport`) logs and skips a file it can't read, so the ZIP arrives without it. `/thumbnail` (`ThumbnailServlet`) reaches `ServletUtil.downloadDocument` too, but its answer is an image, where no message can show.
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/preview/PreviewWorker.java` (`failed`: a `StorageUnreachableException` in the cause chain postpones that job by `UNAVAILABLE_WAIT` without counting a try, as LibreOffice being unavailable does, but without pausing the other jobs, whose files may be local; otherwise an outage of about 71 minutes uses up a job's 4 tries and its preview is given up for good), test `PreviewWorkerTest.java` (one test)
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/DocumentResource.java` (`content` and `thumbnail` open the stream before the answer starts, not inside the `StreamingOutput`, so a read that fails is never a `200` with a cut-off body; `thumbnail` answers a read that fails as it answers a thumbnail that isn't there yet, `404`, so clients show their placeholder, §6.5)
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (the `503` on `/v1/documents/{docNumber}/content`, the only route that returns a document's bytes, and the code in `Problem`'s description)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/ProblemMapperTest.java`, `DocumentResourceTest.java` (one test each)
- Docs: `site/content/developer/api.md` (the status table gains `503 storage-unreachable`)

- [x] **Step 1: Write the failing tests.**
  - `ProblemMapperTest.testStorageUnreachable`: the exception, wrapped in an `IOException` and a `PersistenceException`, maps to `503`, code `storage-unreachable`, `detail` the exception's message; nothing else of the exception reaches the answer.
  - `DocumentResourceTest.testUnreadableFileIsNot200`: with the stored file replaced by a folder of the same name (it exists, and `getStream` fails), `content` answers `500 server-error`, not a `200`. `healdan-api`'s tests keep CE's `MockStore`, which can't fail a read any other way; the `503` itself is checked on the stack (step 5f).
  - `DownloadErrorFilterTest`, around stand-in servlets with Spring's servlet mocks and no dependency on `logicaldoc-webapp` (CI's install leaves that module out, and a dependency on it would make the reactor build it before `healdan-storage`, whose plugin zip it packs): one repeats `ServletUtil.downloadDocument`'s order (open the response's stream, set `200`, read the file, close), then `sendError(500)` as `DownloadServlet` does; another repeats `TicketDownload`'s (open the file's stream, set the headers, open the response's stream, read, close in `finally`, then `sendError(401)`). With a read that throws `StorageUnreachableException`: one that fails before its first byte is answered `503` with the message, escaped, and no `Content-Disposition`, from both; one that fails after its first bytes is cut off, as before; a read that works is untouched, byte for byte; a `401` with `WWW-Authenticate` and no read passes through; and with `Store.get()` not a `HealdanStorage`, the filter changes nothing. The real `/download` and its `503` are checked on the stack (Task 16's step 5f, check 4).
  - `PreviewWorkerTest.testStorageUnreachablePostpones`: a job whose read throws `StorageUnreachableException` is postponed with its tries unspent, and the next job in the queue still runs.
- [x] **Step 2–3: Run them to see them fail, write the mapping, the eager opens, the contract, the filter and the worker's change, run them and `RouteCoverageTest`:** PASS.

  Run, in one reactor (Conventions): `mvn -B -ntp -pl healdan-api,healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='PreviewWorkerTest,ProblemMapperTest,DocumentResourceTest,RouteCoverageTest,DownloadErrorFilterTest' -Dsurefire.failIfNoSpecifiedTests=false package`
- [x] **Step 4: Docs, then commit:** "Answer an unreachable storage backend with its message, on the API and the web page"

**CI:** the `Tests` shards. Step 5f (Task 16) checks the real `503` through the API with Garage stopped. The helper shows the `detail` as it does today ("#12 couldn't be opened: …"), so `Clients` has nothing to run.

---
## PR C: the Storage tab, Docker and Garage, with the docs

### Task 14: The Storage tab's server side

**Carried from PR B's build (2026-11-08):**
- `Mover.wake()` and `Mover.retry()` signal after the caller's transaction ends, and not on a rollback, so the switch and Retry call them inside their own transactions.
- `BackendTest.run(backend, false)` skips the key check as well as the marker, so `testNew` passes on an unsaved encrypted backend. The full Test after the add, and the tab's Test, is `BackendTest.run(entry, keys, true)` (a `Backends.Entry` and `BackendKeys`), which checks the key read from the key file as it is on disk. The plain `run(backend, true)` passes no cipher and checks no key.
- `testBackend` records Test's outcome in `BackendHealth`: after `Backends.forget(id)` it calls `health.check(id)`, or the tab shows OK while Test says the key file is gone.
- `Backends.forget` closes the backend's S3 client, so each transfer in flight on it fails with a `RuntimeException` and spends one of its tries. Defer the close until they end, or have the mover treat a closed client as stopped, without counting a try.
- `Mover.retry(long)` retries a Store's stuck files, so it can't reach a stuck file with no Store, and the tab lists none. The stuck list and Retry need to cover them.
- The Store switch writes `hd_storage_store` in a short transaction of its own. `StoredFiles.moved` reads the row's Store's backend in a subquery, which on MariaDB at REPEATABLE READ takes a shared lock on that row. So while a write to it stays open, each mover commit for that Store's files waits holding the file's lock, saves of the file wait behind it, and after 50 seconds the commit fails and spends a try. Otherwise have `moved` compare against the backend `commitHolding` already read. PostgreSQL and HSQLDB take no such lock.
- A backend that still has rows in `hd_storage_file` can't be deleted, or its rows go first. The mover's listing passes a row on a backend id `BackendHealth` doesn't know, the hand-out then skips it, and a full page of such rows makes the coordinator list again and again.
- The switch calls `StoredFiles.storeBackendChanged(storeId)` once a Store's backend changes, in the same short transaction (owner's review of PR B, 2026-11-08). Deleting a Store's `hd_storage_store` row calls it too, as its files then belong in Original, which `storeBackend` then names. The mover lists only rows that wait (`hd_waiting is not null`), so a switch that skipped it would leave the Store's files where they are until the nightly check, which marks any misplaced row left without the mark (`markWaiting`).
- **Measured before PR C (2026-11-08): a Store switch over 100,000 rows on MariaDB is fine as it is.** MariaDB 11.4.13 in Docker, default buffer pool, 120,000 rows (25,000 documents of four files each in the Store, and another Store's 20,000), using the code's own SQL:
  - `storeBackendChanged`'s two updates took 2.1 s;
  - the mover's first listing of 100 took 0.45 s cold and 0.15 s warm, sorting the waiting rows (`hd_storage_file_due`'s range, then a filesort);
  - draining the switch 100 rows at a time took 1,001 listings, 78 s in all, falling from 155 ms to 2 ms as the rows moved.

  So the switch adds no index and no Flyway script, and the listing stays one query. Spread over the hours the transfers take, 78 s of database time is small.
- An encrypted backend without its check object fails its check whether it holds files or not (Task 12), so the add writes the check object before anything else uses the backend.
- When S3 refuses one object's request as access denied, the mover checks the backend at once. If the check passes (for a write, with a test object put and deleted), the refusal counts as one failed try for that file, and the stuck file shows its error in the tab; otherwise the backend is failing.

**Files:**
- Create (GUI module, shared with the server): `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/services/HealdanStorageService.java` (`@RemoteServiceRelativePath("healdanstorage")`), `HealdanStorageServiceAsync.java`; `…/healdan/beans/GUIStorageOverview.java`, `GUIStorageBackend.java`, `GUIStoreBackend.java`, `GUIStuckFile.java`, `GUIStorageEvent.java`
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/web/HealdanStorageServiceImpl.java` (`@WebServlet("/frontend/healdanstorage")`, extends `healdan-core`'s `HealdanRemoteService`), `StorageAdmin.java` (the rules, testable without the servlet)
- Modify (question 5): `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/ProviderDialog.java` (`askNewKey` says a new key also loses the storage backends' S3 secrets, to enter again in the Storage tab), `healdan-core/src/main/java/com/logicaldoc/healdan/signin/SecretBox.java` (its error and warning name the storage backends' secrets and the Storage tab too)
- Docs (question 5): `site/content/install/backup-and-restore.md` ("The Key for Sign-In Secrets", "If the Key File Goes Missing" and "If the Key Is Lost": the key seals the S3 backends' secrets too, and a new key means entering them again)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/web/HealdanStorageServiceTest.java`, `StorageAdminTest.java`

**Interfaces:**
- Produces, on `HealdanStorageService`, every method admin-only (`HealdanRemoteService.admin`, as M4's), §11:

```java
GUIStorageOverview load() throws ServerException;     // backends (status, files, size), Stores (backend, waiting, stuck), settings,
                                                      // and whether HealdanStorage is CE's store bean (else the tab says storage backends are off)
String[] testNew(GUIStorageBackend b, String secret) throws ServerException;      // Test before saving: empty, or the problems
GUIStorageBackend addBackend(GUIStorageBackend b, String secret) throws ServerException; // an encrypted one carries its recovery key, once
void confirmRecoveryKey(long backendId, String lastGroup) throws ServerException;
GUIStorageBackend saveBackend(GUIStorageBackend b, String secret) throws ServerException; // null or blank secret keeps the stored one
String[] testBackend(long backendId) throws ServerException;
String showRecoveryKey(long backendId) throws ServerException;      // logged, and a key-shown event (question 8)
void enterRecoveryKey(long backendId, String typed) throws ServerException; // checked against the check object before anything is written; a key-entered event
GUIStorageEvent[] keyEvents(long backendId) throws ServerException; // every showing and entering of its key, newest first
void removeBackend(long backendId) throws ServerException;
long[] previewStoreSwitch(long storeId, long backendId) throws ServerException; // files and bytes that will move; the GUI words the question
void setStoreBackend(long storeId, long backendId) throws ServerException;
GUIStuckFile[] stuckFiles(long storeId) throws ServerException;     // document, resource, attempts, last error
void retry(long storeId) throws ServerException;
void saveSettings(int holdDays, int cacheGb, int transfers) throws ServerException;
```

- `GUIStorageBackend` carries everything in `StorageBackend` but the secret, plus `hasSecret`, the status, its error and since when, files and size, and when and by whom its key was last shown (from its events); `recoveryKey` is set only in `addBackend`'s answer. It also carries `newKey`, as M4's `GUISignInProvider` does: false unless the admin has confirmed, in `BackendDialog` (Task 17), that a new secret key may be made (see the rules).
- **The rules** (`StorageAdmin`, §8, §9, §10, §11):
  - S3 backends are encrypted unless the admin turns it off; local ones aren't unless turned on;
  - an S3 prefix and a local path are at most 255 characters, after trimming, or the backend is refused before anything is saved: a held location (Task 2's `hd_location`, `varchar(700)`) then always fits;
  - adding (§11) runs, in order: Test on the unsaved settings (`testNew`: for a local folder, `LocalBackend.probe`, as it has no marker or id yet; for S3, `BackendTest.run(backend, false)` (Task 12) on an `S3Backend` built from the unsaved settings with `create` false, which skips the marker check, as the add writes the marker, rather than repeating its steps here); the row saved; the marker written (a `LocalBackend` with `create` true, or an `S3Backend` with `create` true, Task 5, which also refuses a bucket and prefix whose marker names another install or backend); for an encrypted backend, its key file and check object; the full Test (`BackendTest.run(entry, keys, true)`, Task 12's overload that reads the key file from disk), the key check included; then the recovery key. An encrypted backend isn't offered to Stores until `confirmRecoveryKey` gets the key's last group;
  - backends can't share a place (call 23): an S3 backend whose endpoint host, bucket and prefix equal or contain another's (an empty prefix contains every other) is refused, and so is a local path that is, holds, or is inside Original's folder, staging, the cache, the configuration folder or another backend's;
  - the bucket, prefix, path and encryption can't change once a file is recorded or held on the backend; the name, and for S3 the endpoint, region, keys and path style, can;
  - saving, confirming a recovery key, entering one, removing and Test (`testBackend`) call `Backends.forget(id)` (Test first, so it reads the key file as it now is, and a deleted one fails the backend; pre-flight 1.16), so the next call builds the backend and cipher from the row and key file as they now are, as M4's provider save calls `ProviderClient.forget`; saving the settings calls `Mover.resize`;
  - Original can be renamed, not removed, and has no path field;
  - Remove needs no Store using the backend and no row or held copy on it, deletes its key file, and deletes its row for good (`StorageBackendDAO.remove`), so its sealed secret leaves the database;
  - the Store list is every top-level workspace but CE's Default (Templates included), with Original for a Store that has no row;
  - switching a Store's backend runs `StoreTracking.checkStore` first, sets `hd_waiting` (if empty) on the rows it leaves misplaced and clears it on the rows already on the new backend, as Task 2's `setStore` does (a row a switch puts in place is never listed by `misplaced`, so `moved` would never clear it, and the alert would fire for good), then wakes the mover;
  - saving the settings goes through `StorageSettings`, which refuses a value out of range (Task 2 tests the ranges);
  - the secret is sealed with `SecretBox`; a `KeyMissingException` is answered as M4's Sign-In tab answers it (M4 R37), as the GUI's `SecretKeyMissingException` with the count of sealed values, and `BackendDialog` (Task 17) asks the same question, naming the providers' and the storage backends' secrets alike, then saves again with `newKey` true; only a save whose `GUIStorageBackend.newKey` is true calls `sealWithNewKey`;
  - the endpoint rule (Global Constraints).

- [ ] **Step 1: Write the failing tests**
  - `HealdanStorageServiceTest` (through the servlet with a session, as `HealdanSettingsSignInTest` does):
    - `testAdminsOnly`: `author` gets a `ServerException` from every method.
    - `testSecretNeverComesBack`: after `addBackend(…, "s3cr3t")`, no answer, serialised, contains `s3cr3t`; `hasSecret` is true; `hd_secret` starts `v1:`.
    - `testRecoveryKeyFlow`: against `FakeS3`, `addBackend` returns a 52-character key; the backend isn't offered to Stores; a wrong last group is refused; the right one confirms it.
    - `testShowRecoveryKeyRecorded`: the answer is the key; a `key-shown` event names the backend and the admin, and `keyEvents` lists it; a second showing adds a second event; a log line names the admin and the backend, and holds no key; removing the backend leaves its events.
    - `testEnterRecoveryKey`: with the key file deleted, `testBackend` (Test) fails the backend (a cached key keeps working until Test or a restart, Task 12); a mistyped key is refused and writes no file; the right one rebuilds the file, the backend is OK, and the next write is encrypted under it.
    - `testAddLocalFolder`: a new local folder passes Test before saving, gets its marker when saved, and passes the full Test.
  - `StorageAdminTest`:
    - `testRemoveRules`: refused while a Store uses the backend, while a row is on it, and while a held copy is; then allowed: the key file is gone, and the row with it, deleted or not; Original can't be removed.
    - `testNoSharedPlace`: a second S3 backend on the same endpoint and bucket with the same prefix, an empty one, or one inside the other's is refused; a local path inside Original's folder, staging, the cache, the configuration folder or another backend's is refused.
    - `testFixedOnceUsed`: changing the bucket, prefix, path or encryption is refused once a row exists; the name and keys change.
    - `testEditRebuildsBackend`: after `saveBackend` with new keys or a new endpoint, the next call uses them, not the cached ones.
    - `testStoreList`: Templates is listed, the Default workspace isn't, and a new Store shows Original.
    - `testSwitchPreviewAndSwitch`: the preview counts the Store's files and size; the switch checks the rows' Stores and wakes the mover.
    - `testLongPrefixOrPathRefused`: an S3 prefix or a local path of 256 characters is refused, with nothing saved; 255 is taken.
    - `testNewRunsBackendTest`: `testNew` on S3 settings with no marker in the bucket passes (the marker check skipped), and reports the step that failed when `FakeS3` refuses a range or a delete.
    - `testAddOverAnotherInstallsMarker`: adding an S3 backend whose bucket and prefix hold a marker naming another install is refused, and leaves no row, key file or check object.
    - `testEndpointRule`, `testKeyMissingForSecret` (as M4's: a save with `newKey` false is answered with the sealed count and makes no key; the same save with `newKey` true makes one). The settings' ranges are Task 2's `testSettingsRanges`, not repeated here.
- [ ] **Step 2–3: Run them to see them fail, write the service, run them:** PASS.

  Run (one reactor, Conventions, as this task changes `logicaldoc-gui` and `healdan-core`'s `SecretBox`): `mvn -B -ntp -pl healdan-storage -am -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dmaven.javadoc.skip=true -Dtest='HealdanStorageServiceTest,StorageAdminTest,SecretBoxTest,SignInProvidersTest' -Dsurefire.failIfNoSpecifiedTests=false package`
- [ ] **Step 4: Docs, then commit:** "Serve the Storage tab's settings, admins only"

**CI:** the `Tests` shards.

---

### Task 15: Docker: the `store` key, the cache volume and the paths

**Files:**
- Modify: `docker/tools/src/ConfigPlan.java` (`HEALDAN_KEYS`, spec §12: `Map.of("store", "com.logicaldoc.healdan.storage.HealdanStorage")`), `docker/tools/src/DockerInit.java` (applies them on every start after `IMAGE_KEYS`, and checks they were stored), `docker/tools/test/ConfigPlanTest.java`, `ConfigFileTest.java`
- Modify: `compose.yaml` (`logicaldoc-cache:/opt/logicaldoc/cache`, and the volume), `Dockerfile` (`/opt/logicaldoc/cache` in the `mkdir` ~65 and `VOLUME` ~83), `docker/entrypoint.sh` (`CACHE`, and the ownership loop takes it), `docker/setenv.sh` (`-Dhealdan.storageStaging=/opt/logicaldoc/repository/staging -Dhealdan.storageCache=/opt/logicaldoc/cache -Dhealdan.storageKeys=/opt/logicaldoc/conf/healdan-storage-keys`)
- Modify: `docker/tests/test-image.sh` ("cache content owned by uid 1000", beside the repository's check)
- Docs: `CLAUDE.md` ("Docker": `store` is set on every start whatever the file holds, a third exception beside `IMAGE_KEYS` and `REPLACED_DEFAULTS`; the cache volume and its owner), `site/content/install/what-runs.md`, `upgrading.md` and `configuration.md` (each says a value already in `context.properties` is never changed, or that `.env` owns the only settings re-applied on every start; `store` now is too, and setting it yourself does nothing)

- [ ] **Step 1: Write the failing tests**
  - `ConfigPlanTest`: `HEALDAN_KEYS` holds `store` and only that; `ConfigFileTest`: a conf file with `store=com.logicaldoc.core.store.FSStore`, and one with any other value, has `HealdanStorage` after a start; the file's other keys are untouched.
  - `test-image.sh`: a file made as root in the cache volume is owned by uid 1000 after the start.
- [ ] **Step 2: Run them to see them fail.** Run: `docker build --target tools .` and `docker/tests/test-image.sh`. Expected: FAIL.
- [ ] **Step 3: Write them,** then run both, and `docker/smoke-test.sh`: the stack starts on `HealdanStorage`, the `seed.storage-record-original` row is in `hd_setting` and Original's `.healdan-backend` is in `/opt/logicaldoc/repository/docs/`, and every existing step passes. A fresh smoke stack has no documents when the seed runs (`smoke-fixture`, after it, makes folders only), so there's nothing to record; recording an upgrade's files is `RecordOriginalSeedTest`'s (Task 9) and the owner's upgrade's (Task 22).
- [ ] **Step 4: Docs, then commit:** "Run the image on HealdanStorage, with a cache volume"

**CI:** `docker-image.yml`: the `tools` target's tests, `test-image.sh`, and the whole smoke test now through `HealdanStorage`, on amd64 and arm64.

---

### Task 16: Garage on the smoke stack

**Carried from PR A's build (2026-11-07):**
- A `PUT` into a missing bucket is answered before `100 Continue`, so the client sees a bare `404`, which `S3Backend` probes with a list to name "bucket not found". Check it against Garage: a backend whose bucket is deleted shows as failing, not as files' failures.

**Carried from PR B's build (2026-11-08):**
- `DownloadErrorFilter` is a `@WebFilter`, found because `web.xml` is `metadata-complete="false"`, so check 4 is the first proof it's registered. With Garage stopped, also check that a shared link (`/download-ticket`) answers `503` with the message, and that `/convertpdf` serves CE's `notavailable.pdf`.
- `DocumentResource.content` opens the file before the answer starts, but a large uncached file's first read happens inside its `StreamingOutput`. If the stack can time it, check that a read failing there before its first byte is still a `503`; if not, Known Limitations' sentence on large files arriving cut off covers it.
- Reads during an outage don't fail fast. Each uncached read waits out the S3 client's attempts (about 20 seconds for an unroutable host, 2 minutes for a stalled one), holding a Tomcat thread, even while `BackendHealth` already knows the backend is unreachable, and the web page's thumbnails multiply it. Time it with Garage stopped. If it's long, have `HealdanStorage` throw the named outage at once while `status(id)`'s unreachable state is younger than `retestSeconds`, warning once per backend per outage.

**Files:**
- Create: `docker/tests/compose.storage.yaml`, `docker/tests/garage/garage.toml`, `docker/tests/garage/setup.sh`, `docker/tests/test-storage.sh`
- Create: `healdan-storage/src/main/java/com/logicaldoc/healdan/storage/seed/SmokeStorageSeed.java` (`smoke-storage`)
- Modify: `docker/smoke-test.sh` (`compose()` adds `-f docker/tests/compose.storage.yaml`; step **5f** after 5e (question 4); step 8 runs `test-storage.sh after-restart`). It runs the script as `GARAGE_CONTAINER="$(compose ps -q garage)" APP_CONTAINER=… DB_CONTAINER=… bash docker/tests/test-storage.sh`, as it gives `test-previews.sh` its containers: a separate script has neither `compose()` nor its `-p` and `--env-file`, so a bare `docker compose` there would reach project `logicaldoc`, a developer's own stack, or fail on `.env`. Step 8 looks the three containers up again with `compose ps -q` after its `down` and `up`, as the containers' ids are new, and passes them to `after-restart` the same way.
- Docs: `CLAUDE.md` ("Docker": the storage overlay, `HEALDAN_SMOKE_STORAGE`, and smoke step 5f with what it checks)
- Test: `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/seed/SmokeStorageSeedTest.java`

- [ ] **Step 1: The overlay**

```yaml
# Smoke test only: Garage as a stand-in S3 server (S3 spec section 16.1), set up with fixed test values before the
# app starts, and the switch that makes Healdan add an encrypted backend on it and a Store that uses it.
services:
  garage:
    image: dxflrs/garage:v2.5.0
    volumes:
      - ./docker/tests/garage/garage.toml:/etc/garage.toml:ro
      - garage-data:/var/lib/garage
    ports:
      - "127.0.0.1:13900:3900"
  garage-setup:
    image: busybox:1.37
    network_mode: "service:garage"
    depends_on: [garage]
    volumes:
      - ./docker/tests/garage/setup.sh:/setup.sh:ro
    command: ["sh", "/setup.sh"]
  app:
    depends_on:
      garage-setup:
        condition: service_completed_successfully
    environment:
      HEALDAN_SMOKE_STORAGE: "true"
      HEALDAN_SMOKE_S3_ENDPOINT: http://garage:3900
      HEALDAN_SMOKE_S3_REGION: garage
      HEALDAN_SMOKE_S3_BUCKET: healdan-smoke
      HEALDAN_SMOKE_S3_ACCESS_KEY: GK0123456789abcdef01234567
      HEALDAN_SMOKE_S3_SECRET: <64 fixed hex digits>
volumes:
  garage-data:
```

  - `garage.toml`: one node, `replication_factor = 1`, `db_engine = "sqlite"`, `s3_region = "garage"`, a fixed `rpc_secret` and `admin_token`, the S3 API on 3900 and the admin API on 3903.
  - `setup.sh` (Task 0's calls, listed in the decision log; with `network_mode: "service:garage"` it reaches the admin API at `http://localhost:3903`, with the `admin_token` as a Bearer token): waits for the admin API, then, each only if not done (step 8 runs it again on the kept volume), assigns and applies the one-node layout, imports the fixed key, creates `healdan-smoke` and allows the key on it.
  - Garage's data is a named volume, so step 8's `down` and `up` keep the objects, as the app's volumes keep the rows.
- [ ] **Step 2: Write the failing seed test, then the seed.** `SmokeStorageSeedTest`, with the variables given through the seed's `Supplier` and `FakeS3` as the endpoint: with `HEALDAN_SMOKE_STORAGE` and `HEALDAN_SMOKE_FIXTURE` both `true`, the step adds an encrypted S3 backend `Smoke Garage` (its marker object written by building `S3Backend` with `create` true, Task 5; key file made, check object written, recovery key confirmed), a Store `Smoke Cloud` readable by Everyone as `Smoke` is, mapped to it, and matters 1.2 and 1.3 (call 19) under client 1 from `Smoke template`; and sets `storage.sweepSeconds` and `storage.retestSeconds` to `5` (call 12). Without either variable, `enabled()` is false. It follows `SmokeFixtureSeed`'s pattern and runs after it (`smoke-storage` follows `smoke-signin` in `ORDER`).

  Run: `mvn -B -ntp -pl healdan-storage test -Dtest=SmokeStorageSeedTest`. Expected: FAIL, then PASS.
- [ ] **Step 3: Write `test-storage.sh`.** It needs `curl` 8.0.0 or later (for `--aws-sigv4 "aws:amz:garage:s3"`, which adds the `x-amz-content-sha256` header Garage refuses to do without only from curl 8.0.0; Ubuntu 22.04's 7.81 and Debian 12's 7.88 get a `400`), `jq`, `sha256sum` and `docker`. It takes `GARAGE_CONTAINER`, `APP_CONTAINER` and `DB_CONTAINER`, reads rows with `docker exec "$DB_CONTAINER" mariadb …`, stops and starts Garage with `docker stop` and `docker start "$GARAGE_CONTAINER"`, and polls at most 60 s for each change. Its checks, each a `check` line as in `test-api.sh` (§16.1):
  An upload's staged copy becomes its cache entry once it reaches Garage (question 9), so a file read back through Garage has to arrive there by a move instead: the mover reads a moved file from Original, not from staging, and caches nothing.
  1. **Encrypted in Garage.** File A, a text file holding a random token, is uploaded through `/api/v1` into matter 1.2. Within 60 s its row points at the Garage backend and its object is listed in `healdan-smoke`; the object, fetched with SigV4, doesn't contain the token.
  2. **Moved up.** Plain-text files B and C are uploaded into matter 1.1 (Smoke, on Original), then moved into matter 1.3 with CE's `PUT /services/rest/document/move` as `admin`. Within 60 s both rows point at Garage, and neither is in the cache.
  3. **The same bytes back.** `GET /api/v1/documents/<n>/content` of B (through Garage, decrypted) gives the uploaded file's SHA-256. Then Garage is stopped, and A's (from the cache, which with Garage stopped is the only place it can come from) gives its own. A check that claims a read from the cache stops Garage first, so it can fail.
  4. **An outage.** Garage still stopped. File D uploaded into matter 1.2 answers `2xx`, and its row says staging. C, never read (plain text, so no preview job reads it, and CE's indexer first runs 30 minutes after a start), answers `503 storage-unreachable` whose `detail` names Smoke Cloud, and the web page's `/download` of it answers `503` too (Task 13). A and B, cached, still download. Garage started; within 60 s D's row points at Garage.
  5. **Moving home.** Matter 1.2's workspace moves to the Smoke Store through `/services/rest/folder/move` as `admin` (M1: admins move matters between Stores). Within 60 s the rows of A and D point at Original, the files are under `/opt/logicaldoc/repository/docs/` in the app container, Garage still lists their objects, and `hd_storage_held` has a row for each.
  6. **Staying in the cloud.** File E uploaded into matter 1.3 reaches Garage, as check 1.

  `after-restart` (step 8, with the three containers looked up again): the rows are unchanged; A and D download from Original; C downloads through Garage, its first read since the restart and never cached; then Garage is stopped, and E downloads from the cache, rebuilt from its folder, which with Garage stopped it can only come from; Garage is started again, and the held objects are still listed.
- [ ] **Step 4: Run the smoke test**

  Run: `docker/smoke-test.sh`. Expected: every check passes. Record step 5f's seconds, and Garage's start time, in PR C.
- [ ] **Step 5: Commit:** "Run storage backends against Garage on the smoke stack"

**CI:** `docker-image.yml`'s Test (amd64) and Test (arm64), which pull Garage's and busybox's images for their own architecture. `docker-image.yml` itself doesn't change.

---

### Task 17: The Storage tab

**Files:**
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/StorageTab.java`, `BackendDialog.java`, `RecoveryKeyDialog.java`, `StuckFilesDialog.java`
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/settings/HealdanSettingsPanel.java` (`tabs.addTab(new StorageTab())` after the Sign-In tab, ~74)

- [ ] **Step 1: Write the tab** (§11; its labels are Healdan's own strings, as the Sign-In tab's are, so no message bundle changes):
  - **Backends:** a `ListGrid` of name, kind (Local Folder or S3), where (the path, or the endpoint and bucket), encrypted, files and size, and status (OK, or Failing with the last error and since when); Add Backend, Edit, Test, Show Recovery Key, Enter Recovery Key and Remove.
  - **`BackendDialog`:** the kind; for S3 the name, endpoint, region, bucket, prefix, access key, secret (a `PasswordItem`, empty, hinted "set" or "not set"), path style and Encrypt; for a local folder the name, path and Encrypt; Test before Save. Fields that can't change once files are there are read-only then. A save answered with `SecretKeyMissingException` is handled as `ProviderDialog` handles it (~412–445): the dialog asks whether to make a new secret key, with the question `ProviderDialog.askNewKey` asks (Task 14 makes it name the storage backends' secrets too) and the sealed count from the exception, and on yes saves again with `GUIStorageBackend.newKey` set; on no, nothing is saved (Task 14's rule).
  - **`RecoveryKeyDialog`:** shows the key in its groups, says plainly that losing both it and the key file loses the backend's files, and asks for the last group back; Show Recovery Key reuses it without the question.
  - **Key History** (question 8): from a backend's menu, a list of `keyEvents`: each showing and entering of its key, with who and when, newest first. The backends' list shows when the key was last shown.
  - **Stores:** each Store, its backend (a drop-down of confirmed backends), waiting files and size, and stuck files (a link to `StuckFilesDialog`, with Retry). Changing the drop-down asks first, with a sentence the tab builds from `previewStoreSwitch`'s counts and the backend's name: "214 files (3.1 GB) will move to Wasabi in the background. …".
  - **Settings:** the holding period (days), the cache (GB) and parallel transfers, and Save.
  - **Names and messages are text, never HTML:** backend, Store and file names, and S3's error messages in the status and the stuck files' list, which an author's file name or a server can fill. The grids `setEscapeHTML(true)` and the questions escape what they quote (`SafeHtmlUtils.htmlEscape`), as the Sign-In tab does.
  - When `load()` says `HealdanStorage` isn't CE's store bean, the tab says so and shows nothing else.
- [ ] **Step 2: Build the GUI, and check the tab on a stack.** Run: `mvn -B -ntp -pl logicaldoc-gui -DskipTests install`, then on a smoke stack with the Garage overlay:
  - add a second S3 backend on Garage with prefix `second`, Test passes, save and confirm its recovery key;
  - switch the Smoke Store to it; the confirmation counts its files; the waiting count falls to none;
  - stop Garage: the backend shows Failing with its error; start it: OK again;
  - show the recovery key; delete its key file in the conf volume; press Test: the backend fails (a cached key keeps working until Test or a restart, Task 12); enter the key; it's OK; Key History lists the showing and the entering;
  - Administration → Settings has no Stores entry (question 1);
  - remove a backend that holds files: refused; the one with nothing: removed;
  - a stuck file whose name holds `<b>x</b>` shows those characters, not bold text: upload it, then mark its row stuck with SQL (`update hd_storage_file set hd_stuck = 1, hd_attempts = 10, hd_lasterror = '<b>x</b> refused' where …`, through `docker exec … mariadb`), as ten real failures take about four hours;
  - last, as it loses the stack's other sealed secrets: move `/opt/logicaldoc/conf/healdan-secret.key` aside, then enter a backend's secret again in Edit and save: the dialog asks before making a new key, naming the providers' and the storage backends' secrets; No saves nothing; Yes saves.

  Take the docs' pictures (Tasks 18 and 19) on a demo stack with the Garage overlay, signed in as the Writing Docs page says, after adding the Garage backend through the tab (the smoke seed needs `HEALDAN_SMOKE_FIXTURE`, so it stays off there).
- [ ] **Step 3: Commit:** "Set storage backends up in Healdan Settings' Storage tab"

**CI:** the Docker image's GWT compile; the server side's tests are Task 14's.

---

### Task 18: Docs: the Admin Guide

**Carried from PR A's build (2026-11-07):**
- Say that undoing a Replace File on an S3 Store works only until the mover uploads the new file: the held copy is the object at the same key, which the upload replaces. Local Stores keep the old bytes under `.held/`.

**Carried from PR C's build (2026-11-08):**
- This task takes the Storage tab's pictures that Task 17's step 2 named, on a demo stack with the Garage overlay, after adding the Garage backend through the tab. Task 19 takes any its own pages need there too.
- The outage page says that once a backend is known to be unreachable, reads of its uncached files fail at once with the outage message rather than waiting on the network. The next read after five seconds starts an early retest, so a backend that's back is found within seconds of someone asking for a file.

**Files:**
- Create: `site/content/admin/storage-backends/_index.md` and its pages (§15.3), with a card in `site/content/admin/_index.md`:
  - what a backend is, and Original;
  - adding a local folder or an S3 bucket, and Test;
  - encryption and the recovery key, and what losing it means;
  - choosing a Store's backend, and how moves work;
  - waiting and stuck files, and Retry;
  - held deletes and the holding period;
  - the cache;
  - what happens during an outage, and the 24-hour alert;
  - removing a backend.
- Modify: `site/content/admin/healdan-settings/index.md` (the Storage tab), `site/content/admin/stores/index.md` ("Where Files Are Kept" rewritten: a Store's backend, set in the Storage tab; LogicalDOC's own Stores screen is hidden, question 1).

- [ ] **Step 1: Write them,** with Task 17's pictures, in the Writing Docs page's style (Title Case titles and labels, British spelling, Healdan's glossary: *storage backend*, never "store", for a backend).
- [ ] **Step 2: Check them.** Run: `hugo --source site --panicOnWarning`, `python3 .github/scripts/check-site-sources.py` and `python3 .github/scripts/check-site-links.py`. Expected: PASS.
- [ ] **Step 3: Commit:** "Docs: storage backends in the Admin Guide"

**CI:** the `Docs site` check.

---

### Task 19: Docs: the Install manual

**Carried from PR B's build (2026-11-08):**
- Task 9 measured the first start's recording at 9,450 files a second, over 100,000 small files on the tests' HSQLDB. MariaDB on a server will differ, so `install/upgrading.md` gives it as a rough figure.
- A download during an outage gets the `503` page with `download.gzip=true` and for several ranges at once too: the download filter holds the answer's first 1,024 bytes, which cover the gzip header and the ranges' boundaries (owner's review of PR B). Known Limitations needs no line for them.
- `how-healdan-extends-logicaldoc.md` says that `Held.hold` may build a backend's S3 client while it holds a file's lock, inside CE's transaction. For an `http://` endpoint that's a DNS lookup, and the 30-second memory of a failed build bounds it.

**Carried from PR C's build (2026-11-08):**
- `install/backup-and-restore.md` names `healdan-storage-keys` and the backends' recovery keys where it says the conf volume holds encrypted settings: without both the key files and the recovery keys, an encrypted backend's files can't be opened.
- `install/host-folders.md`'s "its two folders" becomes three, with the cache volume.
- `install/troubleshooting.md`'s failing backend: reads of uncached files fail at once while the outage is known, and the first to meet it waits out the client (about 20 seconds for a stopped server, up to two minutes for one that stalls).
- A deleted bucket shows first as "can't be reached", and after the next retest as "bucket not found" (Task 16).

**Files:**
- Create: `site/content/install/cloud-storage.md` (§15.3: the bucket and the region's endpoint; a user limited to the bucket, with the policy; the lifecycle rule for incomplete multipart uploads after 7 days; Wasabi's 90-day minimum, and why the holding period suits it; other S3 services should work, but only Wasabi and Garage are tested), with a card in `site/content/install/_index.md`
- Modify:
  - `install/backup-and-restore.md` (§12: what the two files now hold, the holding period against the oldest backup, recovery keys in a password manager, the offsite recipe with restic to a second Wasabi bucket, Test after a restore, a file CE rewrote in place; and, for a restore onto a second server, to stop the first one before the second starts, as both would share the buckets, keys and install id, call 23);
  - `install/host-folders.md` (the cache volume; folders for extra local backends in `compose.override.yaml`, made writable by uid 1000, as the entrypoint won't change an NFS share's owner);
  - `install/what-runs.md` (the cache volume);
  - `install/upgrading.md` (the first start records every existing file, at Task 9's measured rate);
  - `install/troubleshooting.md` (a failing backend, a missing marker, stuck files, the alert);
  - `install/known-limitations.md` (a backend per Store, not per matter; encryption can't be added to a backend that holds files; a file CE rewrites in place keeps only its newest bytes; a large file read by range straight from the bucket can arrive cut off if the bucket drops mid-read; during an outage, the web page's downloads and shared links show the outage message and Convert to PDF shows CE's "not available" placeholder (Task 13), but three of its routes don't: an email's attachment (`/download-attachment`) gets a bare error page, a ZIP export (`/zip-export`) leaves out the files it can't read and says nothing, and thumbnails (`/thumbnail`) show as broken images; with LogicalDOC's Stores screen hidden, `store.1.dir` and its six folder paths are set only in `context.properties`, question 1).

- [ ] **Step 1: Write them,** and test the restic recipe once against a throwaway bucket on Garage (`restic -r s3:http://127.0.0.1:13900/…`), so its commands are known to run.
- [ ] **Step 2: Check them,** as Task 18. Expected: PASS.
- [ ] **Step 3: Commit:** "Docs: cloud storage, backups and limits in the Install manual"

**CI:** the `Docs site` check.

---

### Task 20: Docs: the Developer Guide

**Files:**
- Modify: `site/content/developer/tests.md` (`healdan-storage`'s tests and their three layers, `FakeS3`, the Garage overlay and step 5f), `site/content/developer/continuous-integration.md` (Garage's and busybox's images in the smoke test, and its time), `site/content/developer/how-healdan-extends-logicaldoc.md` (replacing CE's `store` bean through the `store=` key that `ConfigPlan` owns; the module's four plugin pieces; the start and restore hooks)

- [ ] **Step 1: Write them. Step 2: Check them,** as Task 18. **Step 3: Commit:** "Docs: storage backends in the Developer Guide"

**CI:** the `Docs site` check.

---

## PR D: records

### Task 21: Records

**Files:**
- Modify: `CLAUDE.md`:
  - "Healdan's code" gains `healdan-storage`: `HealdanStorage` behind `store=`, never a component; the rows (`hd_storage_file`, backend 0 is staging, write ids); the lock per file; the places and their properties; key files, markers and recovery keys; the hooks it uses in `healdan-core`;
  - "Upstream edits" gains this spec's §15.1.

  (Its build, test, Docker and smoke-test facts changed with Tasks 1, 15 and 16.)
- Modify: `docs/reference/logicaldoc-ce-capability-map.md` §3.2 (Healdan's `HealdanStorage` replaces the bean; CE's Stores screen, which lists "Store 1" from `store.1.dir`, is hidden by the `storage-hide-stores` seed, question 1).
- Modify: `docs/superpowers/specs/2026-11-01-s3-storage-backends-design.md` (each question's answer and each call above, marked "changed in the build"; §3.1's and §17's prefix-match text, question 2; Status).
- Modify: `docs/ROADMAP.md` (the glossary's Store row, whose "Call" names CE's per-folder mapping, now `hd_storage_store`; the backlog row, or the milestone it became).

- [ ] **Step 1: Write them, run the checks, and commit:** "Record the storage backends' build"

  Run: `python3 -m unittest discover -s .github/scripts/tests && python3 .github/scripts/check-logicaldoc-doc-links.py && python3 .github/scripts/check-logicaldoc-hosts.py`
  Expected: PASS.

---

### Task 22: The owner's acceptance

- [ ] **Step 1:** Once PRs A to D are merged, the owner backs up (the database and both volumes, as today), upgrades, and checks the first start's log: V9 applied, Original's files recorded, `HealdanStorage` in use.
- [ ] **Step 2:** The owner's walkthrough, spec §16.3, rows 1 to 8, against the owner's server and Wasabi bucket, following the Install pages as written. Row 6 shortens the holding period to 1 day. Row 7's fresh stack starts only once the owner's server is stopped (call 23), and the owner's server starts again only once that stack is taken down. Record each row's result and any punch-list item in the spec's §16.3 and this plan's decision log.
- [ ] **Step 3:** The owner keeps each backend's recovery key in a password manager, and the next backup holds the new key files.
- [ ] **Step 4: Commit the results** (the spec's §16.3 and the decision log; no hostnames, addresses or names from the owner's server): "Record the storage backends' walkthrough"

---

## Decision Log

Every ruling the build makes that the plan or the spec didn't settle goes here, in task order, with what it costs if it was wrong. The rulings this plan made while it was written are the questions' defaults and the calls above.

**Writing the plan (2026-11-03)**

- **The spec's names were checked against master at `56e9815`.** These held: `context-core.xml:21` (`<bean id="store" class="${store}" />`); `logicaldoc-webapp/src/main/resources/context.properties:458` (`store=com.logicaldoc.core.store.FSStore`, with `store.1.encryption` and `store.1.compression` read by no CE code); CE's `Store` interface and its methods (it also has `getString`, a ranged `getBytes`, `isEnabled`, `getParameters`, `init`, `destroy` and `newStore`, which `HealdanStorage` overrides too); `AbstractStore.computeRelativePath`, `checkWriteAfterStore` and `id = 1`; `StoreResource`'s five suffixes; `DocumentManager.replaceFile` (~183), `create` (~1015) and `storeFile` (~1063, `store.store` ~1066), `deleteVersion` (~1598), `getStore(Folder)` (~2012, falling back to `store.write`) and `enforceFilesIntoFolderStore` (~1966); `FSStore.moveResourcesToStore` throwing; the `Store` extension point with no implementation; `Folder.stores` and `ld_folder_store`; 26 classes reaching the bean through `Store.get()`, `Context.get(Store.class)` or injection, none casting it to `FSStore`; `ConfigPlan`'s `IMAGE_KEYS` and `REPLACED_DEFAULTS`; the entrypoint's ownership loop over the two volumes; `SeedRunner.ORDER`; `HealdanHooks`' single registered implementation; the Healdan Settings panel's Sign-In tab; the backup's two files; and every docs page §15.3 names to change. The rest are questions 1 to 9.
- **The libraries' versions** are those Maven Central listed as latest on 2026-11-03; Garage's is Docker Hub's latest release tag.

**Reviewing the plan (2026-11-03)**

- **A code review of this plan, against master at `56e9815`,** found races and wrong claims that the first draft would have built in. Each is fixed above, and the design changes are calls 20 to 24:
  - a save onto the key a transfer was writing or holding, a staged copy's delete taking a save, and the purge deleting an object the mover had just uploaded again (call 20, call 22);
  - the write counter repeating after a delete or a restore (call 21);
  - the Store read from `ld_path`, which CE's folder restore leaves stale (call 10 rewritten);
  - a file whose Store turned local still uploaded (Task 11's step 1 and its commit check);
  - two backends or servers sharing a bucket and prefix (call 23);
  - the key check put off 10 minutes after a start (call 24);
  - `FSStore`'s refusal of empty files dropped, a second local hold of one key throwing, cached backends never rebuilt, Test refusing every new local folder, M1.5's previews given up during an outage, and question 7's premise (Tasks 3, 6, 12, 13 and 14);
  - the start hook placed in `run()`, which tests call (call 5 rewritten), test commands naming classes that don't exist or a pinned schema version, test figures that contradicted the plan's own rules, the smoke script's containers and curl version, the alert's email seam, M4's new-key messages, and CLAUDE.md's facts landing a PR late.

**The owner's answers (2026-11-03)**

- **Questions 1 to 9 answered,** each recorded under its question:
  - 1: CE's Stores screen hidden by a seed (Task 9);
  - 2 to 6: the plan's defaults;
  - 7: the download filter (Task 13);
  - 8: an event table, `hd_storage_event`, in place of `hd_keyshown` and `hd_keyshownby` (Tasks 2, 14 and 17);
  - 9: an uploaded staged copy becomes its read-cache entry (Tasks 8 and 11). Smoke step 5f's checks change with it: an upload is cached once it reaches Garage, so the files read back through Garage arrive by a move from Original (Task 16).

**PostgreSQL guardrails (2026-11-07)**

- **The owner asked for rules that keep this work cheap to carry onto PostgreSQL,** if it's built before that backlog item: the Global Constraints' "SQL that runs on PostgreSQL too", from the [PostgreSQL recon](../../reference/postgresql-recon.md). They changed one interface: `StorageBackend`'s `pathStyle`, `encrypted` and `keyConfirmed` were `boolean` fields on `int` columns, the recon's first blocker (§2.5), and are now `int` fields with `boolean` accessors. Checking them found that MariaDB 11.4.13 refuses `nulls first` and `nulls last`, which the recon's §4.3 had offered as the fix for NULLs' order; the recon now says so. If a rule is wrong, the PostgreSQL item finds it when it runs `healdan-storage` there, and the fix is in `healdan-storage`'s SQL.

**The trial (2026-11-07)**

- **Task 0 ran on the owner's server,** in `maven:3.9-eclipse-temurin-25` (JDK 25.0.4.1), with the SDK jars Task 1 names (32 jars, 8.6 MB, as the table above expected), against Garage v2.5.0 on a Docker network of its own and against a throwaway Wasabi bucket in `us-central-1`. Every check passed on both. Nothing from it is merged.
- **Garage's setup through its admin API v2, from `busybox:1.37`'s `wget` alone, works, and running it again changes nothing** (call 18 stands). Every call carries the `admin_token` as a Bearer token:
  - `GetClusterStatus`, whose first `"id"` is the node's;
  - while `GetClusterLayout` says `"version": 0`, `UpdateClusterLayout` with `{"roles":[{"id":"<node>","zone":"dc1","capacity":1000000000,"tags":[]}]}`, then `ApplyClusterLayout` with `{"version":1}`;
  - `GetKeyInfo?id=<key>`, or else `ImportKey` with `accessKeyId`, `secretAccessKey` and `name`;
  - `GetBucketInfo?globalAlias=<bucket>`, or else `CreateBucket` with `globalAlias`, then `AllowBucketKey` with `bucketId`, `accessKeyId` and `permissions` `read`, `write` and `owner` all `true`.

  `/health` answers `503` until the layout is applied. Task 16's `setup.sh` is these calls.
- **Step 2's checks, on both:**
  1. `Content-MD5` with `WHEN_REQUIRED` is accepted when right; a wrong one is refused with `400 BadDigest`, on a single put and on a part, and nothing is stored. Files of 0 and 1 bytes go up and come back.
  2. No request in either run carried `x-amz-checksum-*` or `x-amz-sdk-checksum-algorithm`.
  3. Ranged reads are exact: `bytes=1048000-1049999`, an open-ended range, and one starting on a 1 MiB boundary, each with the right `Content-Range`.
  4. 100 MiB in seven 16 MiB parts, each with its `Content-MD5`, completes and reads back the same. An upload aborted part-way is listed by `ListMultipartUploads` before the abort and not after, and leaves no object.
  5. `ListObjectsV2` by prefix lists dot-named keys such as `.healdan-backend`; `HEAD` works; after a single `DELETE`, `HEAD` is `404`; deleting a missing key succeeds.
  6. A 64 MiB put from a file streams: it succeeded with a 32 MiB heap, which peaked at 8 MiB (Garage) and 11 MiB (Wasabi).
  7. Every network failure is an `SdkClientException`, never an `S3Exception`, with a `java.net` cause: `ConnectException` for a refused port (0.3 s), `SocketTimeoutException` "Connect timed out" for an unroutable address or Wasabi's host on a wrong port, which drops rather than refuses (40 s), `UnknownHostException` (0.6 s), and `SocketTimeoutException` "Read timed out" for a server that accepts and never answers. Each took the SDK's default 4 attempts. A wrong secret is an `S3Exception` `403` after one attempt: `AccessDenied` on Garage, `SignatureDoesNotMatch` on Wasabi. `HEAD` on a missing bucket answers `404 NoSuchKey` on both, as for a missing object; `GET` and `ListObjectsV2` answer `404 NoSuchBucket`.
- **What leaves the client depends on the scheme.** Over `https://` (Wasabi) the SDK sends a plain body with `x-amz-content-sha256: UNSIGNED-PAYLOAD`. Over `http://` (Garage, and any S3 server the endpoint rule lets through on a private address) it sends `Content-Encoding: aws-chunked` with signed chunks (`STREAMING-AWS4-HMAC-SHA256-PAYLOAD`, `x-amz-decoded-content-length`, no trailer). Both send `Expect: 100-continue` with a body. Garage and Wasabi accept all of it.
- **Step 3's speeds, from the owner's server to Wasabi's `us-central-1`,** one run each:

  | What | Time | Rate |
  |---|---|---|
  | A small call, once warm (the first, with DNS and TLS, 326 ms) | 32 ms | |
  | Upload 1 MiB | 0.20 s | 43 Mbit/s |
  | Upload 20 MiB | 0.67 s | 252 Mbit/s |
  | Upload 200 MiB, 16 MiB parts one at a time | 6.0 s | 279 Mbit/s |
  | Upload 200 MiB, 16 MiB parts four at a time | 2.7 s | 631 Mbit/s |
  | Read 200 MiB whole (first byte in 36 ms) | 5.3 s | 316 Mbit/s |
  | Read the first 1 MiB of a file by range | 0.05 to 0.09 s | |

  The server's upload bandwidth is at least 631 Mbit/s, and one stream doesn't fill it. A typical document reaches Wasabi in well under a second once the mover takes it.
- **Step 4's rulings:**
  - Ruling: `url-connection-client` stays, and Task 1's libraries and size figure stand — it streams bodies over both schemes (check 6) — costs if wrong: nothing the trial could see.
  - Ruling: `WHEN_REQUIRED` with an explicit `Content-MD5` on Wasabi too, without the SDK's own checksums — both servers refuse a bad digest and store nothing (checks 1, 2 and 4) — costs if wrong: a provider that ignored `Content-MD5` would rest on the mover's `HEAD` size check alone.
  - Ruling: the S3 client makes **2 attempts**, not the SDK's default 4 (`retryStrategy(b -> b.maxAttempts(2))`, tried on both: an unroutable endpoint then fails in 20.1 s, not 40.3 s) — with 4, a read during an outage would wait 40 s on a dropped connection, or 4 minutes on a stalled server at the 60-second read timeout, before §6.5's message, and the mover's own retries and the failing-backend retests already cover longer outages — costs if wrong: a throttled or flaky request fails where a third try would have passed; the mover tries it again, and only a person's read that misses the cache sees it. Task 5 changed.
  - Ruling: an `SdkClientException` or an `S3Exception` with a `5xx` status is unreachable, and any other `S3Exception` failing, named by its error code — the two never overlapped in the trial — costs if wrong: a provider that answered an outage with a `4xx` would spend files' tries, which Retry gives back. Task 5 changed.
  - Ruling: **a missing bucket is found with `GET` or a list, never `HEAD`** — `HEAD` can't tell a missing bucket from a missing object on either server (check 7) — so `check()` and Test, which read the marker with `GET`, say "bucket not found", and `HEAD` stays for objects in a bucket just written to — costs if wrong: nothing; it's how both servers answer. Tasks 5 and 12 changed.
  - Ruling: `FakeS3` decodes `aws-chunked` bodies and answers `Expect: 100-continue`, since it serves plain HTTP on loopback, and also serves HTTPS, so `testContentMd5OnEveryUpload` and a round trip run in both shapes — the HTTPS shape is the one Wasabi gets, and without it CI would test only Garage's; the SDK's 2.30 release showed that an upgrade can change what leaves the client — costs if wrong: about half a day in Task 5. Task 5 changed.
  - Ruling: a large file's parts go up one at a time — four transfers at once (`storage.transfers`) already fill the line, as four parts at once did — costs if wrong: a single 200 MiB file on its own takes 6 s rather than 3 s.
  - None of step 4's fallbacks was needed: Garage's ranged reads work, so step 5f may read ranges, and Wasabi takes `Content-MD5` with `WHEN_REQUIRED`.
- **The trial made one mistake of its own:** its first 0-byte put failed with `BadDigest` because its hand-typed MD5 of nothing was wrong (`1B2M2Y8AsgTpgAmY7PhCfg==` is right). Computed, it passed on both. Tests compute digests; they never copy them.
- **Cleaned up:** the Wasabi bucket emptied, with no upload left open, and Garage's container, network and data removed from the owner's server.

**The pre-flight scan (2026-11-07)**

- **A review read every task against the others, the Global Constraints and the code at `a7961fac7`,** before Task 1, and found the problems below: tasks that needed a later task's class, tests that couldn't pass or couldn't fail, a key too long for InnoDB under `utf8mb4`, routes the outage page missed, and names master had moved since `56e9815`. Each ruling is folded into the tasks it changes, and calls 5, 7 and 24 say where they changed.
- **Task 1:**
  - Ruling: Task 1 creates `HealdanStorage` as a stub extending `FSStore`, which Task 6 replaces — the test context names it — costs if wrong: nothing.
  - Ruling: `testStoreBeanIsCEs` counts the `Store` beans, which must be one, and checks it's a `HealdanStorage` — `Store.get()` is `Context.get(Store.class)`, so comparing the two can't fail — costs if wrong: nothing.
  - Ruling: `docker/tools` gains `WarStorageTest`, which on the WAR's own `WEB-INF/lib` builds an `S3Client` as `S3Backend` will, sends one request to a closed loopback port, and runs Tink's streaming AEAD — Maven's test classpath has every transitive jar, so only the WAR shows one the zip drops (#202) — costs if wrong: an hour, and a few seconds in the tools build.
- **Tasks 1, 3 and 9:** Ruling: from Task 3 until Task 9, `AbstractStorageTestCase` writes Original's marker after `super.setUp()`, with the install id the `storage-settings` seed made, and Task 9 takes it out, as its seed then writes it — Task 3's writes check the marker and only Task 9's seed writes one, so every local write in PR A's tests would fail — costs if wrong: one helper method moved in Task 9.
- **Task 2:**
  - Ruling: `misplaced(int limit, Collection<Long> unavailableBackends)` leaves out, in its SQL, rows on a paused or unchecked backend (the clause dropped when the set is empty), stuck rows and rows not yet due — filtering after the query lets one paused backend's rows fill every page and starve the rest — costs if wrong: nothing.
  - Ruling: `recordWrite` clears `hd_waiting` when the write lands on the backend its Store uses, and sets it, if empty, anywhere else, staging included — a save landing in place would otherwise leave a stale value, and the alert would fire a day later — costs if wrong: nothing.
  - Ruling: `hd_storage_held.hd_location` is `varchar(700)` in both scripts (2,108 bytes in the key under `utf8mb3`, 2,808 under `utf8mb4`), and Task 14 refuses an S3 prefix or a local path over 255 characters, so every location fits; no other V9 key or index holds a long `varchar` (`hd_storage_file`'s key is 1,028 bytes under `utf8mb4`) — at 1,000 characters the key sat 64 bytes under InnoDB's 3,072 under `utf8mb3`, and fails under `utf8mb4` — costs if wrong: nothing; CE's keys are under 100 characters.
  - Ruling: `testStorageSecretsCountAsSealed` goes in `SignInProvidersTest`, beside `testNoKeyIsMadeOverStoredSecrets`, and the step runs that class — `SecretBoxTest` has no database — costs if wrong: nothing.
  - Ruling: the settings' ranges are tested once, in `StorageSettingsSeedTest.testSettingsRanges`, in the task that makes `StorageSettings`, and Task 7's `testHoldDaysRange` and Task 14's `testSettingsRanges` go — two tasks tested the same ranges — costs if wrong: nothing.
  - Ruling: the three storage seeds go right after `signin-settings`, before M5's `profile-message-id`, and `smoke-storage` right after `smoke-signin`, before `demo-fixture`; Task 11's start hook goes beside M5's email-index worker — master moved since the plan was checked — costs if wrong: nothing; the seeds don't depend on M5's.
- **Task 3:**
  - Ruling: `MemoryBackend` gains `refuse(Op... ops)` beside its other faults — Task 12's `BackendTestTest` refuses ranges or deletes alone — costs if wrong: nothing.
  - Ruling: `list`'s comment reads "S3's Test only" — Task 9 walks the folder itself — costs if wrong: nothing.
- **Task 4:** Ruling: of two concurrent `make`s, the second throws, and the test says so — `make` never overwrites — costs if wrong: nothing.
- **Task 5:**
  - Ruling: `S3Backend` takes `boolean create`, as `LocalBackend` does: it writes the marker object when there's none, and refuses a bucket and prefix whose marker names another install or backend; Tasks 14 and 16 use it — nothing else wrote an S3 marker — costs if wrong: nothing.
  - Ruling: `S3Settings.java` is in the task's files, and `Endpoints.check` takes a resolver, the JDK's in production — `EndpointsTest` would otherwise need real DNS — costs if wrong: nothing.
- **Tasks 6 and 7:**
  - Ruling: Task 6's saves and deletes hold copies through `Backend.hold` and `HeldCopies.hold` directly, and Task 7 adds `Held`, `Purge` and the `.held/` fallback, listing `HealdanStorage.java` (and `LocalBackend.java` if the fallback lives there) — Task 6 can't use Task 7's class — costs if wrong: Task 7 refactors Task 6's two call sites into `Held`.
  - Ruling: package-private `Backends.use(long, Backend)` registers a `MemoryBackend` as an S3-kind backend in tests, and until Task 12 `HealdanStorage.test()` runs each backend's `check()` — the plan named neither — costs if wrong: nothing.
  - Ruling: Task 7's step runs `-Dtest=HeldDeletesTest` — it named no command — costs if wrong: nothing.
- **Tasks 6, 10 and 11:** Ruling: Tasks 6 and 10 leave rows misplaced and their tests assert that, and Task 11 adds every `wake()`, listing `HealdanStorage.java`, `StoreTracking.java` and `NightlyCheck.java` — `Mover` arrives in Task 11 — costs if wrong: nothing.
- **Tasks 7 and 11:** Ruling: `Purge` takes an in-transfer test (`BiPredicate<Long, String>`, the doc id and resource), which is "never" until Task 11 passes `Mover.moving`, and `testPurgeWaitsForTransfer` and `testMovedSourceHeld` move to `MoverTest` — they need the mover — costs if wrong: nothing.
- **Task 8:**
  - Ruling: `ReadCache` takes its cap in bytes (`LongSupplier capBytes`), which production gives as `storage.cacheGb` × 2^30 — the tests' 10 and 20 MiB caps can't be put in GB — costs if wrong: nothing.
  - Ruling: `ReadCache` throws `BackendUnreachableException` on a miss, and `HealdanStorage` turns it into `StorageUnreachableException` naming the row's Store, so `testUnreachableUncached` runs through `HealdanStorage` — `ReadCache` knows no Store — costs if wrong: nothing.
- **Task 9:**
  - Ruling: the recording seed fails, and so stops startup, with a message saying Original's folder looks unmounted and how to write the marker by hand, when Original has no marker and no file and `ld_document` has documents not deleted — otherwise the first start with an unmounted share marks the empty mount point and §10.2's guard is gone for good (Review Focus 5) — costs if wrong: an install whose every document lost its file can't start until the admin writes the marker by hand.
  - Ruling: the seed takes no file's lock, and the plan drops its claim that a save made while it runs keeps its own; the lock rule narrows to per-file writes, naming `setStore` as a bulk update the nightly check backs up — seeds run while the Spring context starts, before Tomcat opens its connectors and before CE's scheduler starts, so no save runs beside them — costs if wrong: a duplicate key fails the seed and stops a start, and the next start resumes it, as only missing rows are inserted.
  - Ruling: `HideStoresSeed` takes the same `Supplier<Store>` seam as `RecordOriginalSeed` — its test needs it — costs if wrong: nothing.
- **Tasks 11 and 12:**
  - Ruling: `BackendHealth`'s state (unchecked, OK and failing; the pause and resume; the retest; the first-write check) moves into Task 11 with its unreachable cause, and Task 12 keeps the other causes, `BackendTest`, Test and the alert, which it adds to `StorageStartup` — Task 11's pause and resume need it — costs if wrong: the class's tests split across two tasks.
  - Ruling: an unchecked backend's first write runs its check itself, holding that backend's check lock, and is refused only if the check fails, while the mover and the purge still wait for `StorageStartup` (calls 5 and 24 changed) — tests and seeds, `demo-fixture` among them, run before `start()`, so refusing every write until then would fail every earlier test that writes and stop a fresh demo stack's start, and nothing is written under an unchecked key either way — costs if wrong: the first save after a start waits for one marker read and one check-object decrypt, milliseconds on a local backend.
  - Ruling: `testLocalStoreNeverToS3` names its orders: the Store turned local before the pass, during the upload, between the upload and the commit, and a `recordWrite` racing the commit — it named none, so its strength was unstated — costs if wrong: nothing.
- **Tasks 11, 12 and 14:** Ruling: the Conventions prefer one reactor (`-pl healdan-storage -am … package`) for `healdan-storage`'s tests once a task changes `healdan-core` or `logicaldoc-gui`, and those tasks' commands use it — another session's install of `healdan-core` into the shared `~/.m2` can swap the jar mid-run — costs if wrong: slower test runs.
- **Task 13:**
  - Ruling: `DownloadErrorFilterTest` drives the filter around stand-in servlets that repeat `ServletUtil.downloadDocument`'s order and `TicketDownload`'s, with no dependency on `logicaldoc-webapp`, and the real `/download` stays with smoke step 5f — CI's install leaves `logicaldoc-webapp` out, and depending on it would put it ahead of the plugin zip it packs — costs if wrong: a change in CE's download order isn't caught until the smoke test.
  - Ruling: `DownloadErrorFilter` passes every request through untouched unless `Store.get()` is a `HealdanStorage`, and the PRs section names Task 13's `PreviewWorker`, `ProblemMapper` and `DocumentResource` edits as the PR B changes that run on `FSStore` too, harmless there — "PRs A and B change nothing a running server does" must stay true — costs if wrong: nothing.
  - Ruling: the filter also covers `/download-ticket`, as `TicketDownload` opens the response's stream before it reads and closes it in `finally`, so a read failing on its first byte commits an empty `200` (and one failing while opening is answered `401`); `/download-attachment`, which reads the whole email before it answers, is left out, with `/zip-export` and the web page's thumbnails, for Known Limitations (Task 19); all checked at `a7961fac7` — §6.5's message should reach a shared link too — costs if wrong: a few lines of mapping.
- **Tasks 14 and 17:**
  - Ruling: `GUIStorageBackend` gains `newKey`, as M4's `GUISignInProvider` has, and `BackendDialog` handles `SecretKeyMissingException` as `ProviderDialog` does, asking, then saving again with `newKey` — a confirmed new key couldn't otherwise reach the server — costs if wrong: nothing.
  - Ruling: Test on an unsaved S3 backend runs `BackendTest.run(backend, false)`, which skips the marker check, rather than repeating its steps — the add writes the marker afterwards — costs if wrong: nothing.
  - Ruling: `testEnterRecoveryKey` and the tab's manual check press Test after deleting the key file, and nothing re-reads key files on a timer — a cached key keeps working and loses nothing, and only Test or a restart notices it's gone — costs if wrong: an admin who deletes a key file sees it only on Test or the next start.
  - Ruling: the manual check of a stuck file whose name holds `<b>x</b>` sets its row stuck with SQL — ten real failures take about four hours — costs if wrong: nothing.
- **Tasks 15 and 16:**
  - Ruling: Task 15's smoke check is the seed's marker row and Original's `.healdan-backend`, with every existing step passing, and the upgrade case stays with `RecordOriginalSeedTest` and Task 22 — a fresh smoke stack has no documents when the seed runs — costs if wrong: nothing.
  - Ruling: step 8 passes `GARAGE_CONTAINER`, `APP_CONTAINER` and `DB_CONTAINER` again after its `down` and `up`, and the checks that claim a read from the cache stop Garage first — the ids are new, and a read through a running Garage gives the same bytes, so those checks couldn't fail — costs if wrong: nothing.
- **Task 22:** Ruling: a step commits the walkthrough's results — none did — costs if wrong: nothing.

- **A check of these amendments** against the rulings found them applied, with one gap and some loose ends, and Task 1's build corrected one ruling:
  - Ruling: Test calls `Backends.forget(id)` before it runs, so it reads the key file as it now is and a deleted one fails the backend (Task 14) — 1.16 relies on Test noticing, and a cached cipher wouldn't — costs if wrong: nothing.
  - Ruling: `Purge` is a Spring bean with `setInTransfer`, which `StorageStartup` sets to `Mover.moving` (Tasks 7 and 11) — a predicate can't be injected — costs if wrong: nothing.
  - Ruling: no `software.amazon.eventstream:*` include — in SDK 2.57.8 the `eventstream` jar is `software.amazon.awssdk:eventstream`, which `software.amazon.awssdk:*` already takes (Task 1's zip lists it) — costs if wrong: nothing.
  - Ruling: Tasks 10 and 13 also test in one reactor (Conventions) — they change `healdan-core` too — costs if wrong: slower runs.

**The build (2026-11-07)**

- Task 6's review found that the update-then-insert the Global Constraints named takes a gap lock on MariaDB inside CE's long transactions. Ruling: `recordWrite` and `HeldCopies.hold` read the row under the file's lock, then insert or update — the read-first form is as safe under the lock and takes no gap lock — costs if wrong: none known; running MariaDB at READ COMMITTED would also fix it, but that's a server-wide change for the owner.
- Ruling: `S3Backend.hold` sends no request and returns the key, reversing the HEAD Task 5 first added to satisfy `BackendContract` — the held row is recorded whatever a HEAD says, and the HEAD held the file's lock for up to two minutes in an outage — costs if wrong: a held row for an object already gone, which the purge drops.
- Task 16 timed reads during an outage: an uncached read waited 20 s with Garage stopped and 120 s with it paused, every time. Ruling: reads of a backend whose outage is known fail at once with the same `503`. The outage is known while the backend is failing unreachable and was found less than `storage.retestSeconds` ago, or while a check of it is under way, and a retest round marks every backend it will check as under way when it starts. A refused read reports no new failure, and one whose finding is more than 5 s old asks the retest thread for an early retest of that backend. A law firm's users shouldn't wait two minutes per file to be told the bucket is down. Costs if wrong: reads stay refused for a few seconds after a backend comes back.
- Ruling: step 5f's `/convertpdf` check uses a PDF (file F), not the text file the brief named. CE has no text-to-PDF converter, so `/convertpdf` of a text file serves the placeholder without reading the file, and the check would prove nothing. Costs if wrong: nothing.
- Task 16 found that a `PUT` into a deleted Garage bucket fails as an `SdkClientException` ("Error writing request body": Garage answers before `100 Continue`), not the bare `404` Task 5 expected. The backend shows as unreachable until its next retest names "bucket not found". Ruling: leave it. The window is at most `retestSeconds`, files lose no tries, and the backend fails rather than its files. Costs if wrong: up to a minute of "can't be reached" before the real reason. The fix is in Follow-Ups.
- Ruling: Task 17's docs pictures move to Tasks 18 and 19, which write the pages that use them. Costs if wrong: one more stack started.
- The rest of the build's rulings are in each PR's description.

## Follow-Ups

Filled in from the build's reviews: each gap in a narrow case, or for later, that doesn't block the walkthrough. Three are known before the build:

- **A crash between a move's row update and its hold** leaves the source copy unrecorded: no file is lost, but the copy stays until someone deletes it. The nightly check could list such orphans on local backends. (Task 11)
- **A crash after the mover stages a local target's copy** leaves a `.healdan-tmp-` file beside the key, which nothing points at. The nightly check could delete those more than a day old. (Task 11)
- **`If-Match` on `S3Channel`'s ranged reads** (Task 5). A file read by range straight from the bucket (over a tenth of the cache) while the mover rewrites its key could mix two versions; an encrypted backend's AEAD refuses that read, an unencrypted one would serve it. `GetObject`'s `ifMatch` with the ETag from the `HEAD` fixes it, but the trial didn't try it on Garage or Wasabi.
- **A conditional create of an S3 backend's marker** (Task 5). Two creates of one bucket and prefix at once could both write the marker; `If-None-Match: *` on its `PUT` would stop the second. The tab's overlap check (call 23) makes it unlikely, and the trial didn't try the header.
- **A `PUT` into a deleted bucket reads as an outage** (Task 16). Garage answers before `100 Continue`, and the client throws an `SdkClientException`, which counts as unreachable. `S3Backend` could probe with a list on such an exception from a `PUT`, as the bare-`404` path does, so the backend shows "bucket not found" at once.
- **A check that never returns holds other backends' reads refused** (Task 16). A local share on a hard mount whose server stops answering has no timeout. While its check hangs, the round's marks, and an early retest queued behind it on the one retest thread, keep every failing, unreachable backend's reads refused, even one that's back. A bound would fix it: a mark lapses once it's older than `retestSeconds` plus the S3 client's longest call.
- **A Stores cell click right after a notification sometimes does nothing** (Task 17). The next click works. It's probably focus or the editor's first click; no data or rule is affected.
- **A marker whose `PUT` stored but lost its answer stays behind** (PR C's final re-review). `StorageAdmin.place` makes the `S3Backend`, which writes the marker, before `Placed` exists, so a failure there leaves nothing for `undoAdd` or `save`'s `catch` to remove. The place then holds a marker naming a backend that doesn't exist, and an add there is refused until someone deletes it by hand. Writing the marker after `Placed` exists, or removing it in the constructor's failure, would fix it. It needs a lost answer to a small `PUT`, so it's rare.
- **`HibernateVersionDAO.deleteVersion`** (upstream) deletes only a version's own file, never its derived files, so a deleted version's preview and thumbnail stay in the repository, recorded and moved with their Store like any other file. (Seen while planning.)
