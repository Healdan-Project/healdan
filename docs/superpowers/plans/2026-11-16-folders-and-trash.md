# Moving and Deleting Folders, and the Trash: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Folders move and delete in the User app as documents do. Every delete lands in a tiered Trash (deleted, then removed, then destroyed), with Undo on the delete's toast. The rules hold on every route.

**Architecture:**
- **`healdan-core`** gains `com.logicaldoc.healdan.trash`:
  - the record (`hd_trash` and `hd_trash_member`, V11);
  - the recorders, fed from `beforeFolderDeleteTree` and from a document and a folder listener;
  - restore's scope, through a new `HealdanHooks.folderRestoreScope` that CE's `HibernateFolderDAO.restore` asks;
  - removal, through two new hooks the Classic Interface's three trash RPCs ask;
  - `TrashDestroyer`, at 02:47 each night;
  - the upgrade's seed.

  The tree rules for moves join `PermissionRules` and `HealdanTreeChecks`.
- **`healdan-api`** gains `FolderChanges` (`DELETE /v1/folders/{id}`, `POST /v1/folders/{id}/move`, `GET /v1/folders/{id}/contents`), `TrashResource` (`/v1/trash`), `canMove` and `canDelete` on `FolderView`, and a `TrashItem` answer for a document delete.
- **`healdan-web`** gains:
  - the Trash view at `/app/trash`, with the admin switch;
  - an action on `@healdan/ui`'s toast, for Undo;
  - folder rows that can be selected;
  - folder moves and deletes from every way in, with the cross-matter question.

**Tech Stack:** Java 25, Spring 6.2, Hibernate 7, Flyway, CXF 4 JAX-RS, JUnit 4 (`AbstractHealdanTestCase`, `ApiTestCase`), GWT 2.12 for one Classic Interface field. React 19, TypeScript, React Aria Components through `@healdan/ui`, TanStack Router and Query, Vitest and Testing Library, Playwright. Hugo.

**Spec:** [`docs/superpowers/specs/2026-11-16-folders-and-trash-design.md`](../specs/2026-11-16-folders-and-trash-design.md), with its [mockups](../specs/2026-11-16-folders-and-trash-mockups/README.md). It was approved by the owner on 2026-11-16. Read it with this plan: tasks cite its sections.

**Status:** written on 2026-11-16, after the spec's approval, and chosen by the owner the same day for subagent-driven building.

## Global Constraints

Every task's requirements include these.

- **Upstream edits** are spec §9.1's, as this plan's corrections 1 to 3 widen them, and no others:
  - `HibernateFolderDAO.deleteTree` (its document, delete-user and alias updates take only live rows);
  - `HibernateFolderDAO.restore` (the two tree updates take the hook's scope);
  - `DocumentServiceImpl.deleteFromTrash` and `emptyTrash`, and `FolderServiceImpl.deleteFromTrash` (each asks a hook first);
  - `FolderServiceImpl.move` (`HealdanRpcChecks.movedTrees`) and `SoapFolderService.move` (`HealdanTreeChecks.movedTrees`);
  - `DocumentManager.destroyDocument` (`where ld_id = %d`);
  - fork-owned `HealdanHooks`, `HealdanTreeChecks` and `HealdanRpcChecks`.

  Each upstream edit carries a `// Healdan:` comment saying why, and its test joins `UPSTREAM_TESTS` in `.github/workflows/build-logicaldoc.yml` (one per line, `Class` or `Class#method`).
- **Line endings:** `HibernateFolderDAOTest`, `DocumentServiceImpl`, `FolderServiceImpl`, `SoapFolderService`, `FolderDAO` and `DocumentDAO` are CRLF; keep them CRLF (scripted edits use `newline=''`). `HibernateFolderDAO`, `HibernateDocumentDAO`, `DocumentManager`, `HealdanHooks`, `HealdanTreeChecks` and `HealdanRpcChecks` are LF. CE code is indented with 4 spaces, Healdan's with tabs.
- **Names:**

  | What | Name |
  |---|---|
  | The migration | `V11__trash.sql` in `healdan-core/src/main/resources/healdan/db/migration/mariadb/` and `hsqldb/` |
  | The tables | `hd_trash` (one row per delete), `hd_trash_member` (one row per folder or document it took) |
  | Member states | `deleted`, `removed`, `restored`, `destroyed` (`TrashState`, stored lower case) |
  | The setting | `trash.removedDays` in `hd_setting`, 1 to 365, default 30; `HealdanSettings.TRASH_REMOVED_DAYS`, `trashRemovedDays()` |
  | The job | `com.logicaldoc.healdan.trash.TrashDestroyer`, at `LocalTime.of(2, 47)`, 10 minutes after a start at the earliest |
  | The seeds | `trash-record-existing` (spec §4.7), `trash-system-destroy` (correction 9), `smoke-second-matter` (correction 15) |
  | Listeners | `healdanTrash` (DocumentListener, position 170), `healdanTrashFolders` (FolderListener, position 140) |
  | Hooks | `HealdanHooks.folderRestoreScope`, `removeFromTrash`, `emptyTrash`; `HealdanTreeChecks.movedTrees`; `HealdanRpcChecks.movedTrees` |
  | API classes | `FolderChanges`, `TrashResource`, `TrashViews`; models `TrashItem`, `TrashPage`, `TrashContents`, `DeletedFrom`, `RestoreRequest`, `RestoredItem`, `HeldDocument` |
  | Problem codes (new) | `not-deletable`, `not-movable`, `items-not-allowed`, `immutable`, `place-gone` |
  | App | route `/app/trash`, with `?view=` `mine`, `everyone` or `removed`; `views/TrashView.tsx`, `views/TrashDetails.tsx`, `actions/trash.ts`, `actions/folders.ts`, `organising/ConfirmRemove.tsx`, `organising/CrossMatter.tsx`, `organising/undo.ts` |
  | End-to-end | `healdan-web/web/e2e/trash.spec.ts` (new); `folders.spec.ts`, `keyboard.spec.ts`, `a11y.spec.ts`, `organising.spec.ts` changed |

- **The tiers' words** (spec §2.3): Remove and Empty Trash say "You won't be able to restore it. An administrator can, for {days} days." with `removedDays` from the API, never a constant.
- **Nothing hidden revealed** (spec §4.2). A refusal, a count (`contents`, `TrashItem.contents`, the questions' lines) or a `holds` list counts and names only what the caller can read. `items-not-allowed` names nothing.
- **Every route** (spec §2.3): the rules live in `healdan-core`, reached from CE's DAO, listeners and hooks. The API's pre-checks only shape the answer; the hook checks again.
- **SQL** keeps to the PostgreSQL rules in the S3 plan's Global Constraints (`docs/superpowers/plans/2026-11-03-s3-storage-backends.md`). Identity columns are `generated by default as identity` (HSQLDB) and `auto_increment` (MariaDB). Times are written with `Timestamp.from(instant)` and read with `getTimestamp(...).toInstant()`, as `hd_api_token`'s are.
- **Logging:** never names, paths or file names at info or above from Healdan's code; ids at debug. CE's own destroy lines stay (spec §9.1).
- **`openapi.yaml`** changes in the same commit as the route or field; every new problem code joins `Problem.code`'s `examples` list, and `apps/user/src/frame/errors.test.ts` then needs its `problems.<code>` string.
- **App:** every string is in `packages/ui/src/strings/en-US.ts`, US English, Title Case for titles, buttons and column labels, with `{matter}` and `{matterLower}` for the firm's word. React Aria only through `@healdan/ui`; no `dangerouslySetInnerHTML`. A click on a folder row opens it, so tests focus rows rather than click them.
- **Docs ship in the task that changes the behaviour**, British spelling in prose, app labels quoted as the app shows them.
- **Licence headers** on every new source file (CLAUDE.md), with 2026; none on the Flyway scripts.
- **CI time:** each PR's description says how many seconds it adds to `build`'s shards, `Web` and the Docker smoke test.
- Keep real hostnames, internal addresses and personal names out of commits and PRs. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

These inputs are the most likely to hurt someone, and a task's main tests could miss them. Each has a test in the task named.

1. **A restore that sweeps up what it didn't take:** a colleague's document deleted inside the folder earlier, a subfolder removed since, a member restored on its own and deleted again into a new item. Each stays where it is (Task 4).
2. **The nightly job destroying the wrong thing:** an item removed 29 days ago with the setting at 30; a matter's own folder; a removed folder that still holds a colleague's deleted document; a folder that a live folder takes its security from. And one item failing while others succeed (Task 7).
3. **A count or refusal revealing a hidden item:** `GET /v1/folders/{id}/contents`, a `TrashItem`'s `contents` and `409 checked-out`'s `holds`, when the folder holds a hidden subfolder and a document checked out by someone in it (Tasks 9 and 10).
4. **A delete recorded twice or not at all:**
   - CMIS's `deleteTree`, which deletes item by item after the hook;
   - a shortcut deleted beside its document, in both orders (the Classic Interface deletes shortcuts first, the API after);
   - an empty folder deleted through `HFD.delete`;
   - a delete a later listener vetoes.

   (Task 2.)
5. **Undo when the world moved on:** Undo pressed twice, Undo after someone else already restored the item, Undo after the place was deleted. Each says what happened and leaves the rest restored (Tasks 10 and 14).

## Spec corrections and calls this plan makes

The owner hears about these when the plan is handed over. Task 18 folds them into the spec, each marked "changed in the build".

1. **`SoapFolderService.move` has no tree check today** (spec §3.3 said it checks Move and Delete on the tree's folders; that is `merge`). Task 3 adds `HealdanTreeChecks.movedTrees` there, as spec §9.1 plans.
2. **`deleteTree`'s delete-user updates and its alias update** also take only live rows, not only its document update. Otherwise a colleague's deleted document keeps its row at 1 but changes deleter.
   - The document's three updates become one: `update ld_document set ld_deleted = %d, ld_deleteuserid = %d, ld_deleteuser = :user where ld_deleted = 0 and ld_folderid in (%s)`.
   - The alias update gains `and ld_deleted = 0`.
3. **Some routes delete a folder without `beforeFolderDeleteTree`:** CMIS's `deleteObject` on an empty folder, `HFD.merge`'s emptied source and `FolderTool.delete`, all through `HFD.delete(long, FolderHistory)`. A folder listener (`healdanTrashFolders`) records those. CMIS's `deleteTree` calls the hook, then deletes item by item, so the listeners find the members already recorded and record nothing.
4. **Folder aliases pointing into a deleted tree** (spec §12's open question) join the item as members, so a restore brings them back and a destroy takes them. CE's restore never brought them back.
5. **Shortcuts** (spec §5.2):
   - A shortcut stored at 1 with no transaction is one deleted beside its document, as only the "delete its shortcuts too" routes pass none. It joins its target's open item when there is one.
   - When there isn't one yet (the Classic Interface deletes shortcuts first), it records nothing, and its target's item takes every shortcut of it at 1 that has no member row.
   - A shortcut deleted on its own (`SHORTCUT_DELETED`, with a transaction) is an item of its own.
6. **`GET /v1/folders/{id}/contents`** is new. It answers `{ folders, documents }`, the live folders and documents in the tree that the caller can read. Spec §7.4's question ("It holds 3 folders and 11 documents.") and §7.5's ("Its 11 documents will take…") need the counts, and §6 had no route for them.
7. **`TrashItem`'s fields:**
   - `type` is `document` or `folder`, and `kind` is the file kind for a document's icon, as `DocumentView.kind` is, or `folder`.
   - The counts are `contents`, not `holds`, which the check-out problem uses.
   - `size` is a document's, which spec §7.2's details show and §6.5 left out.
   - `deletedFrom` is `{ folder: FolderRef | null, path: string[] }`: the folder when it is live and readable, and its present path; otherwise the path recorded at the delete.
8. **A restore answers `{ place: FolderView, item: TrashItem }`.** It gives a `FolderView`, not a `FolderRef`, because the toast needs the matter number and the path.
9. **`TrashDestroyer` destroys documents as `_system`** (spec §12). A seed, `trash-system-destroy`, gives `_system`'s group (-1010) read on menu -9 (`ld_menu_acl`; a row, not a structural change), so CE's `destroyDocument` passes its menu check whether or not the `admin` account exists or is enabled. The Classic Interface's Destroy Documents is unchanged for people: `_system` can't sign in.
10. **Destroying a folder** (spec §5.4):
    - `ld_folder_ext` and `ld_foldertag` don't cascade (spec §5.4 said they do), so the job deletes them, and also `ld_folder_acl`, `ld_folder_store`, folder bookmarks and the deleted aliases pointing to it.
    - A removed folder stays removed, and is tried again the next night, while any of these holds:
      - a row that isn't destroyed still has it as its folder or parent (a colleague's deleted document, say);
      - a live folder takes its security from it (`ld_securityref`);
      - a live alias points to it.
    - `ld_user.ld_defworkspace` naming it is set to null.
11. **The Trash list sorts by Name, Deleted and Removed** (`sort=name|deleted|removed`). Doc # and Deleted From don't sort.
12. **Search folders stay out of Move To…** as they are today (spec §7.5 listed them among the greyed-out places): a row nobody can choose adds nothing.
13. **The Templates Store answers `404`** to folder delete and move, admins included, as every API route there does (spec §6.1 said `403 admins-only` for a non-admin).
14. **The one-item delete question and toast name the item for documents too**: "Move "Lease.pdf" to the Trash?" and "Moved "Lease.pdf" to the Trash". Several items: "Move 4 items to the Trash?" and "Moved 4 items to the Trash". These replace "Move 1 document to the Trash?" and `plural('deleted')`.
15. **A seed step, `smoke-second-matter`,** makes matter 1.4 "Second Matter" in Store Smoke from `Smoke template`, so `folders.spec.ts`'s cross-matter move needs nothing another smoke step leaves. Matters 1.2 and 1.3 belong to the storage overlay, and step 5f moves 1.2.
16. **A selected folder shows no details:** the right region's details stay a document's (spec §7.5 is silent).
17. **A document item is restored through `DocumentDAO.restore`,** which also restores its versions, and its shortcut members are restored with it. `HFD.restore` restores no `ld_version` rows, which only matters for a document deleted on its own, and that never goes through it.

---

## Conventions

- **Shorthands:**
  - `HC/` = `healdan-core/src/main/java/com/logicaldoc/healdan/` and `HCT/` = its tests.
  - `API/` = `healdan-api/src/main/java/com/logicaldoc/healdan/api/` and `APIT/` = its tests.
  - `YAML` = `healdan-api/src/main/resources/healdan/api/openapi.yaml`.
  - `U/` = `healdan-web/web/apps/user/src/` and `UI/` = `healdan-web/web/packages/ui/src/`.
- **Running Maven** (other sessions share `~/.m2`; run long builds in the background). `~/.m2/settings.xml` is a copy of `docker/maven-settings.xml`, and `~/logicaldoc-dev.properties` exists (an empty file works).
  - Upstream test: `mvn -B -ntp -pl logicaldoc-core test -Dtest='HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows'`, then re-install the module: `mvn -B -ntp -pl logicaldoc-core -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install`. Do the same for `logicaldoc-webapp` and `logicaldoc-webservice` after editing them.
  - `healdan-core`: `mvn -B -ntp -pl healdan-core test -Dtest=<Class>`, then `mvn -B -ntp -pl healdan-core -DskipTests install` (with its test-jar) before `healdan-api`'s or `healdan-storage`'s tests.
  - `healdan-api` in one reactor: `mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- **The app**, from `healdan-web/web`: `npm ci --ignore-scripts`, `npm run generate` (after any `openapi.yaml` change), `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`. One file: `npx vitest run apps/user/src/views/TrashView.test.tsx`.
- **The smoke test:** `docker/smoke-test.sh` on a fresh stack; step 9 alone against a running one as the Developer Guide's Tests page says (`HEALDAN_INDEXER_FAST=1`).
- **Fixtures:**
  - `healdan-core`'s `HealdanFixtures` (`fx`): `user`, `store`, `client`, `matter`, `folder`, `searchFolder`, `legacyShortcut`, `legacyAlias`, `grant`, `documentRow`, `upload`, `snapshot`, `admin`, `folderHistory`, `documentHistory`.
  - There's no check-out fixture: call `DocumentManager.get().checkout(docId, fx.documentHistory(user))`.
  - `AbstractRulesTestCase` (in `HCT/rules/`) has a ready layout (`store`, matter 998.1, `documents`, `correspondence`, `alice`, `bob`, `admin`) and `assertRefused`.
  - `FolderRenameTest`'s `createLayout` is the API's pattern.
- **Commits:** one per task, imperative subject, a body saying why, the `Co-Authored-By` trailer.

## PRs

All target `master`, each stacked on the one before ([[stacked-prs]]: CI runs only for PRs to master).

- **PR A · The server, Tasks 1–11.** The record, the rules on every route, the hooks, the job, the setting, the API, and the Admin Guide's, Install manual's and Developer Guide's pages.
- **PR B · The app, Tasks 12–17.** The toast's action, the Trash, folder deletes and moves, Undo, the end-to-end specs and the User Guide.
- **PR C · The records, Task 18.**

---

### Task 1: The record, `TrashStore`, and the setting

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/mariadb/V11__trash.sql` and `hsqldb/V11__trash.sql`
- Create: `HC/trash/TrashState.java`, `TrashItemRow.java`, `TrashMemberRow.java`, `TrashMember.java`, `TrashQuery.java`, `TrashStore.java`
- Modify: `HC/setting/HealdanSettings.java` (`TRASH_REMOVED_DAYS`, `DEFAULT_TRASH_REMOVED_DAYS = 30`, `trashRemovedDays()`)
- Modify: `HCT/db/HealdanMigratorTest.java` (version 10 → 11 at L50 and L93)
- Test: `HCT/trash/TrashStoreTest.java`

**Interfaces:**
- Produces:

```java
public enum TrashState { DELETED, REMOVED, RESTORED, DESTROYED; String code(); static TrashState of(String code); }
/** A folder or document a delete took: type 'F' or 'D' */
public record TrashMember(char type, long objectId) {}
public record TrashItemRow(long id, long tenantId, String kind /* document|folder */, long objectId, String name,
		String docNumber, Long fromFolderId, List<String> fromPath, Long matterId, int folders, int documents,
		Long deletedById, String deletedByName, Instant deletedAt) {}
public record TrashMemberRow(long trashId, TrashMember member, TrashState state, Long removedById,
		String removedByName, Instant removedAt, Long restoredById, String restoredByName, Instant restoredAt,
		Instant destroyedAt) {}
/** view: mine (deleted, deleter = userId), everyone (deleted), removed; sort: name|deleted|removed */
public record TrashQuery(String view, long userId, long tenantId, String sort, boolean descending, int offset, int limit) {}

@Component
public class TrashStore {
	public static TrashStore get();
	/** Inserts the item and its members, all deleted; returns the item's id */
	public long insert(TrashItemRow item, Collection<TrashMember> members);
	public Optional<TrashItemRow> item(long id);
	/** The item's state: its top member's */
	public TrashState state(long id);
	/** The newest item holding the object in that state */
	public Optional<Long> itemHolding(TrashMember member, TrashState state);
	public List<TrashMember> members(long id, TrashState state);
	/** Moves the listed members from one state to another, stamping who and when; returns how many changed */
	public int change(long id, Collection<TrashMember> members, TrashState from, TrashState to, Long byId, String byName, Instant at);
	public void addMember(long id, TrashMember member);
	public List<TrashItemRow> page(TrashQuery query);
	public long count(TrashQuery query);
	/** Items with members removed before the cut-off, oldest removal first */
	public List<Long> removedBefore(Instant cutoff, int limit);
	public Optional<TrashMemberRow> member(long id, TrashMember member);
}
```

- `HealdanSettings.trashRemovedDays()` returns the stored value clamped to 1 to 365, or 30 when unset or unreadable.

- [ ] **Step 1: Write the failing tests** (`TrashStoreTest extends AbstractHealdanTestCase`)
  - `testInsertKeepsTheItemAndItsMembers`: an item with three members reads back field for field. `fromPath` `["Client Work", "998.1 – Demo", "Documents"]` comes back as a list. Every member is `DELETED`.
  - `testStateIsTheTopMembers`: the top member removed and a child still deleted makes `state` `REMOVED`.
  - `testChangeTakesOnlyMembersInTheFromState`: a member already restored isn't moved to removed. `change` answers 2 of 3.
  - `testRemovedStampsByAndAt` and `testRestoredStampsByAndAt`: the right columns are set and the others kept. A member removed and then restored keeps its removed by and at.
  - `testMineListsOnlyTheCallersDeletedItems`, `testEveryoneListsEveryDeletedItem`, `testRemovedListsItemsWhoseTopMemberIsRemoved`, each paged (`offset` 1, `limit` 1) and counted.
  - `testSortByNameDeletedAndRemoved`, both orders. Newest first is the default.
  - `testRemovedBeforeTakesTheOldestFirst`.
  - `testItemHoldingFindsTheNewest`: an object deleted, restored and deleted again is held by the second item.
  - `testSettingDefaultsAndClamps`: unset → 30; `"0"` → 1; `"999"` → 365; `"abc"` → 30.
  - `HealdanMigratorTest`: version 11, "11 migrations applied, schema version 11".

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest='TrashStoreTest,HealdanMigratorTest'`
Expected: FAIL to compile (no `com.logicaldoc.healdan.trash`).

- [ ] **Step 3: Write the migration** (both dialects; MariaDB `datetime(3)` and `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_bin`, HSQLDB `timestamp`; lower-case SQL, tab-indented, a `--` comment at the top saying what the tables are for)

```
hd_trash: hd_id bigint identity primary key, hd_tenantid bigint not null, hd_kind varchar(10) not null,
	hd_objectid bigint not null, hd_name varchar(255) not null, hd_docnumber varchar(255),
	hd_fromfolderid bigint, hd_frompath varchar(4000), hd_matterid bigint,
	hd_folders int not null, hd_documents int not null,
	hd_deletedby bigint, hd_deletedbyname varchar(255), hd_deletedat <time> not null
	index hd_trash_deleter (hd_tenantid, hd_deletedby)
hd_trash_member: hd_trashid bigint not null, hd_objecttype char(1) not null, hd_objectid bigint not null,
	hd_state varchar(10) not null,
	hd_removedby bigint, hd_removedbyname varchar(255), hd_removedat <time>,
	hd_restoredby bigint, hd_restoredbyname varchar(255), hd_restoredat <time>, hd_destroyedat <time>,
	primary key (hd_trashid, hd_objecttype, hd_objectid)
	index hd_trash_member_object (hd_objecttype, hd_objectid, hd_state)
	index hd_trash_member_removed (hd_state, hd_removedat)
```

`hd_frompath` holds a JSON array of the names, written and read with Jackson (`healdan-core` already uses it in `DemoManifest`), because a name may hold any character.

- [ ] **Step 4: Write `TrashStore`** over `JdbcTemplate`, as `HealdanSettings` is. Inserts take the generated key with a `KeyHolder`, and `in (...)` lists go in chunks of 500, as `StoredRows.documentIds` does. Sorting is a fixed map from `sort` to a column (`hd_name`, `hd_deletedat`, `hd_removedat` of the top member), never the text sent. Add the setting.

- [ ] **Step 5: Run the tests**

Expected: PASS.

- [ ] **Step 6: Commit:** "Add the Trash's record: hd_trash and its members, and the days removed items are kept"

---

### Task 2: Recording every delete, on every route

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/folder/HibernateFolderDAO.java` (`deleteTree`, L1772-1785: correction 2)
- Modify: `logicaldoc-core/src/test/java/com/logicaldoc/core/folder/HibernateFolderDAOTest.java` (CRLF)
- Create: `HC/trash/TrashRecorder.java`, `TrashDocumentListener.java`, `TrashFolderListener.java`
- Modify: `HC/rules/HealdanHooksImpl.java` (`beforeFolderDeleteTree` calls the recorder after its checks pass)
- Modify: `healdan-core/src/main/resources/plugin.xml` (extensions `healdanTrash`, DocumentListener 170; `healdanTrashFolders`, FolderListener 140)
- Modify: `.github/workflows/build-logicaldoc.yml` (`HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows`)
- Test: `HCT/trash/TrashRecordingTest.java`

**Interfaces:**
- Consumes: Task 1's `TrashStore`; `StoredRows.tree(long)` (live folders), `StoredRows.folder(id, dictionary)` (a folder's stored `deleted`); `MatterLocator.get().locate(Folder)`; `FilingRules.actor(History)`.
- Produces:

```java
@Component
public class TrashRecorder {
	public static TrashRecorder get();
	/** From beforeFolderDeleteTree, before CE writes: the folder, its live tree, the live documents in it, their
	 *  shortcuts' rows and the live aliases pointing into it (correction 4). Returns the item's id. */
	public long treeDeleting(long folderId, User actor) throws PersistenceException;
	/** From the folder listener: a folder that went from live to deleted and isn't yet a deleted member */
	public void folderDeleted(Folder folder, FolderHistory transaction) throws PersistenceException;
	/** From the document listener: a document stored at 1 that isn't yet a deleted member (correction 5) */
	public void documentDeleted(Document document, DocumentHistory transaction) throws PersistenceException;
}
```

- [ ] **Step 1: Write the failing upstream test** `HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows`. Use the fixture data's tree 1200, or make one with `createAlias`.
  - Before `deleteTree`, set one of its documents to `ld_deleted = 1` with `ld_deleteuserid = 3`, and another to 2.
  - Afterwards the first still has deleter 3 and is at 1, the second is at 2, and the live ones are at 1 with the transaction's user.
  - An alias already at 2 that points into the tree stays at 2.

- [ ] **Step 2: Write the failing Healdan tests** (`TrashRecordingTest extends AbstractRulesTestCase`)
  - `testFolderDeleteMakesOneItem`: delete `correspondence` (with a subfolder and two documents) through `folderDao.deleteTree`.
    - One item of kind `folder` is made, holding `correspondence`'s name.
    - `fromFolderId` is `documents`; `fromPath` ends in `Documents`; `matterId` is matter 998.1's.
    - `folders` = 1, `documents` = 2; the deleter is the actor.
    - The members are the two folders and the two documents.
  - `testFolderDeleteLeavesOthersDeletes`:
    - Bob's document deleted in `correspondence` first stays in Bob's item, with Bob as deleter, at 1.
    - A removed document stays at 2.
    - Neither is a member of Alice's item.
  - `testDocumentDeleteMakesOneItem`, through `documentDao.delete(id, history)`: kind `document`, `docNumber` set, the shortcut's row a member (both orders: shortcut first with `documentDao.delete(shortcutId)`, then the document; and the document, then the shortcut).
  - `testShortcutDeletedOnItsOwnIsAnItem`: a shortcut stored with `SHORTCUT_DELETED` and a transaction is its own item.
  - `testEachRouteRecordsOnce`, one item per delete on each route. `healdan-core` doesn't depend on the webapp, so the Classic Interface's RPCs are covered by the DAO calls they make (`deleteTree`, `HDD.delete`):
    - the DAO;
    - WebDAV's delete (as `ChannelTreePermissionsTest` drives it);
    - CMIS `deleteTree`, which deletes item by item after the hook: one item, not one per document;
    - CMIS `deleteObject` on an empty folder (the folder listener);
    - SOAP's folder delete.
  - `testAVetoedDeleteRecordsNothing`: a test `DocumentListener` that throws `HealdanVetoException` in `beforeStore` (as `DocumentChangesTest`'s `DeleteVeto`). After a refused document delete and a refused folder delete, `hd_trash` is empty.
  - `testAliasesIntoTheTreeAreMembers`: a `fx.legacyAlias` elsewhere pointing at a subfolder joins the item.

- [ ] **Step 3: Run them to see them fail**

Run: `mvn -B -ntp -pl logicaldoc-core test -Dtest='HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows'`, then `mvn -B -ntp -pl healdan-core test -Dtest=TrashRecordingTest`
Expected: FAIL. The upstream test sees the colleague's document handed to the deleter, and the Healdan test finds no `hd_trash` rows.

- [ ] **Step 4: Make the upstream edit** (correction 2), then re-install `logicaldoc-core`.

- [ ] **Step 5: Write the recorder and the listeners**
  - `beforeFolderDeleteTree` calls `TrashRecorder.get().treeDeleting(folderId, actor)` inside the same `Vetoes.check` block, after `checkDeleteTree`, so a refusal records nothing.
  - `TrashDocumentListener.afterStore` calls `documentDeleted` when `document.getDeleted() == 1`. `TrashFolderListener.afterStore` calls `folderDeleted` when the stored row was live and the folder's `deleted` isn't 0. Each listener has a public no-arg constructor and empty bodies for the other methods.
  - A member already `deleted` in an item records nothing, so the hook's members cover CMIS's item-by-item deletes.
  - The deleter is the transaction's user, else `ld_deleteuserid` and `ld_deleteuser`.
  - The time is the database's `CURRENT_TIMESTAMP` read once per item.
  - The path holds the parents' names from the Store down, as `ApiViews.folder` builds `path`.

- [ ] **Step 6: Run the tests**

Expected: PASS, and `ChannelTreePermissionsTest`, `DeleteTreeCacheTest`, `VetoAtomicityTest` and `HealdanHooksTest` still pass.

- [ ] **Step 7: Commit:** "Record each delete as one Trash item on every route, and let a folder delete take only live rows"

---

### Task 3: The tree rules for deleting and moving, on every route

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/HealdanTreeChecks.java` (`movedTrees`)
- Modify: `logicaldoc-webapp/src/main/java/com/logicaldoc/web/service/HealdanRpcChecks.java` (`movedTrees`), `FolderServiceImpl.java` (`move(List<Long>, long)`, L533: call it first)
- Modify: `logicaldoc-webservice/src/main/java/com/logicaldoc/webservice/soap/endpoint/SoapFolderService.java` (`move`, L241: call it before `folderDao.move`)
- Modify: `HC/security/PermissionRules.java` (`checkMoveTree`, `MOVE_TREE`), `HC/rules/RulesFolderListener.java` (call it on a move, after `checkFolderMove`)
- Modify: `HC/rules/HealdanHooksImpl.java` (`beforeFolderDeleteTree`: the check-out and immutable refusals before `checkDeleteTree`)
- Create: `HC/trash/TrashRules.java`
- Modify: `HC/rules/FilingRules.java` (`checkFolderRestore`: a non-admin restores only from 1)
- Test: `HCT/trash/TrashRulesTest.java`; `logicaldoc-webapp/src/test/java/com/logicaldoc/web/service/RpcPermissionGapsTest.java` and `logicaldoc-webservice/src/test/java/com/logicaldoc/webservice/soap/endpoint/SoapPermissionGapsTest.java` (whole classes already in `UPSTREAM_TESTS`)

**Interfaces:**
- Produces:

```java
// HealdanTreeChecks (core, fork-owned)
/** MOVE on every live folder and document of each tree, and ADD on the target; nothing for an admin */
public static void movedTrees(User user, Collection<Long> folderIds, long targetId) throws PersistenceException, PermissionException;
// HealdanRpcChecks (webapp)
public static void movedTrees(Session session, Collection<Long> folderIds, long targetId) throws ServerException;
// PermissionRules
static final String MOVE_TREE = "You need the move permission on everything in this folder to move it.";
public void checkMoveTree(long folderId, long targetId, User user) throws PersistenceException;
// TrashRules
@Component public class TrashRules {
	static final String CHECKED_OUT = "Documents in this folder are checked out. It can be deleted once they're checked in.";
	static final String IMMUTABLE = "This folder holds an immutable document, which can't be deleted.";
	public static TrashRules get();
	/** Every live document in the folder's live tree that is checked out, by anyone */
	public List<Long> checkedOutIn(long folderId);
	public boolean holdsImmutable(long folderId);
	/** Throws HealdanRuleException with CHECKED_OUT or IMMUTABLE; admins too */
	public void checkDeletable(long folderId) throws PersistenceException;
}
```

- [ ] **Step 1: Write the failing tests**
  - `TrashRulesTest extends AbstractRulesTestCase`:
    - `testCheckedOutInsideRefusesTheDelete`: Bob checks out a document in a subfolder of `correspondence`. Alice's `deleteTree`, the admin's too, is refused with `CHECKED_OUT` on the DAO (which the Classic Interface's delete calls), WebDAV, CMIS and SOAP routes.
    - `testImmutableInsideRefusesTheDelete`: CE's silent stop becomes a refusal, and no `DELETED` folder history row is written (`assertRefused` checks the snapshot).
    - `testMoveNeedsMoveOnEverythingInside`:
      - A subfolder Alice can't see, or a document with its own ACL denying Move, refuses her move with `MOVE_TREE` on the DAO (`folderDao.move`), WebDAV and CMIS routes, through `RulesFolderListener`.
      - The message names nothing inside.
      - The admin's move passes.
    - `testMoveNeedsAddOnTheTarget`.
    - `testRemovedFolderRestoresOnlyForAnAdmin`: a folder at 2 is refused to Alice with `RESTORE_NOT_IN_TRASH` through `folderDao.restore`, and restored for the admin.
  - `RpcPermissionGapsTest#testFolderMoveNeedsMoveOnTheTree`: `FolderServiceImpl.move` throws a `ServerException` before writing (the folder's parent unchanged).
  - `SoapPermissionGapsTest#testFolderMoveNeedsMoveOnTheTree`: `SoapFolderService.move` throws before writing.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL. Today the deletes go through, and the moves pass with only CE's checks.

- [ ] **Step 3: Write the checks**
  - `movedTrees` reuses `trees`' folder walk and `documentsIn(user, MOVE, treeIds)`, then `folder(user, ADD, targetId)`. It does nothing for an admin, as `deletedTrees` does.
  - `checkMoveTree` catches the `PermissionException` and throws `refuse(MOVE_TREE, folderId)`, as `checkDeleteTree` does.
  - The check-out query is `ld_deleted = 0 and ld_status = DocumentStatus.CHECKEDOUT.ordinal()` over `StoredRows.tree(folderId)`, in chunks of 1,000. The immutable query is `ld_immutable = 1`.
  - Re-install `logicaldoc-core`, `logicaldoc-webapp` and `logicaldoc-webservice` before running `healdan-core`'s tests.

- [ ] **Step 4: Run the tests**

Expected: PASS, with `ChannelTreePermissionsTest`, `PermissionRulesTest`, `StructureRulesTest` and `FilingRulesTest` still passing.

- [ ] **Step 5: Commit:** "Refuse folder moves without Move on everything inside, and folder deletes with a check-out or an immutable document inside"

---

### Task 4: Restoring only what a delete took, and queuing it for indexing

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/HealdanHooks.java` (`RestoreScope`, `folderRestoreScope`)
- Modify: `HibernateFolderDAO.java` (`restore`, L880-891: ask the hook; its `null` keeps CE's two updates)
- Modify: `logicaldoc-core/src/test/java/com/logicaldoc/core/HealdanHooksTest.java` (`testFolderRestoreTakesTheScope`)
- Create: `HC/trash/TrashRestore.java`
- Modify: `HC/rules/HealdanHooksImpl.java` (`folderRestoreScope` → `TrashRestore`)
- Modify: `HC/trash/TrashDocumentListener.java` (on `RESTORED`: the member restored, and `TO_INDEX`)
- Test: `HCT/trash/TrashRestoreTest.java`

**Interfaces:**
- Produces:

```java
// HealdanHooks
record RestoreScope(Collection<Long> folderIds, Collection<Long> documentIds) {}
/** The folders and documents a folder restore sets live; null keeps CE's (every row at 1 in the tree).
 *  CE then runs "update ... set ld_deleted = 0 ... where ld_deleted <> 0 and ld_id in (...)" for each list. */
default RestoreScope folderRestoreScope(long folderId, Collection<Long> treeIds, FolderHistory transaction) throws PersistenceException { return null; }

// TrashRestore
@Component public class TrashRestore {
	public static TrashRestore get();
	/** The members of the folder's item, in the folder's tree, in the folder's own state (1 → deleted, 2 → removed);
	 *  marks them restored and queues the documents for indexing. Null, with a warning, when the folder is in no item. */
	public HealdanHooks.RestoreScope scope(long folderId, Collection<Long> treeIds, User actor) throws PersistenceException;
	/** Restores an item back where it was (folderId null) or into folderId; returns the folder it went to */
	public long restore(long trashId, Long folderId, User actor, Session session) throws PersistenceException;
	/** Where the item would go back to, if it can: the folder is live, under a Store, and readable by the actor */
	public Optional<Long> placeOf(long trashId, User actor) throws PersistenceException;
}
```

- `restore` restores a folder item through `FolderDAO.get().restore(top, parent, history)`, so the scope, the rules and the hooks run. A document item goes through `DocumentDAO.get().restore(docId, folderId, history)`, with each shortcut member restored into its own folder when that is live (correction 17). The history carries the actor and the session.

- [ ] **Step 1: Write the failing upstream test** `HealdanHooksTest#testFolderRestoreTakesTheScope`. A registered hook names one subfolder and one document of tree 1200. After `restore`, only those, and the folder itself, are at 0. Without the hook, CE's behaviour holds (the existing `testFolderRestore`).

- [ ] **Step 2: Write the failing Healdan tests** (`TrashRestoreTest extends AbstractRulesTestCase`)
  - `testRestoreGoesBackWhereItWas`: through `TrashRestore.restore(id, null, ...)`. The item's members are restored and marked `restored` with by and at.
  - `testRestoreTakesOnlyTheItemsMembers`, Review Focus 1:
    - Bob's document deleted inside earlier stays at 1 in Bob's item.
    - A subfolder removed since stays at 2.
    - A member restored on its own (through `DocumentDAO.restore`) and deleted again into a new item isn't brought back by the old item.
  - `testDaoRestoreFollowsTheScope`: `folderDao.restore`, which the Classic Interface's restore calls, restores the same members as `TrashRestore.restore`.
  - `testRestoringAMemberFolderOnItsOwn`: a subfolder in Alice's item, restored through `folderDao.restore`, brings back its members in its tree only. The item's top stays deleted.
  - `testAdminRestoresFromRemoved`: a folder item removed (top at 2) restores its removed members, and members still deleted under it come back too.
  - `testRestoreIntoAnotherMatterRestamps`: Client and Matter of the restored documents become the new matter's.
  - `testRestoreRespectsOncePerMatter`: `AlreadyFiledException`.
  - `testRestoredDocumentsAreQueuedForIndexing`: an `INDEXED` document restored on its own (`DocumentDAO.restore`) and one restored in a folder are `TO_INDEX` afterwards.
  - `testPlaceOfIsEmptyWhenTheFolderIsGone`: the place deleted, or unreadable to the actor.

- [ ] **Step 3: Run them to see them fail**

Expected: FAIL. CE's restore brings back every row at 1, and nothing is re-indexed.

- [ ] **Step 4: Make the hook and the upstream edit, write `TrashRestore`, then re-install `logicaldoc-core`.**
  - The document listener's `beforeStore`, on `RESTORED` (`DocumentEvent.RESTORED.toString().equals(transaction.getEvent())`): an `INDEXED` document becomes `TO_INDEX`. Its `afterStore` marks the document's newest `deleted` or `removed` member `restored`.
  - `scope` sets `ld_indexed` to `TO_INDEX` where it is `INDEXED`, for its documents, as `Propagation.finish` does.

- [ ] **Step 5: Run the tests**

Expected: PASS, and `HibernateFolderDAOTest#testRestore`, `StructureRulesTest#testRestore` and `HealdanHooksTest` still pass.

- [ ] **Step 6: Commit** (`HealdanHooksTest` is already in `UPSTREAM_TESTS`): "Restore only what a delete took, back where it was, and queue it for indexing"

---

### Task 5: Removing and emptying, on every route

**Files:**
- Modify: `HealdanHooks.java` (`removeFromTrash`, `emptyTrash`)
- Modify: `DocumentServiceImpl.java` (`deleteFromTrash`, L2349; `emptyTrash`, L2366), `FolderServiceImpl.java` (`deleteFromTrash`, L1128): each asks its hook first, keeping CE's update for `false`
- Create: `HC/trash/TrashRemoval.java`
- Modify: `HC/rules/HealdanHooksImpl.java`
- Test: `HCT/trash/TrashRemovalTest.java`; `logicaldoc-webapp/src/test/java/com/logicaldoc/web/service/HealdanTrashHooksTest.java` (new, in `UPSTREAM_TESTS`)

**Interfaces:**
- Produces:

```java
// HealdanHooks
/** Removes the listed rows of table (ld_document or ld_folder) from the trash; true when Healdan did, so CE's own update is skipped */
default boolean removeFromTrash(String table, Collection<Long> ids, User user) throws PersistenceException { return false; }
/** Empties the user's trash; true when Healdan did */
default boolean emptyTrash(User user) throws PersistenceException { return false; }

// TrashRemoval, each in a transaction of its own (TransactionTemplate on CE's transaction manager)
@Component public class TrashRemoval {
	public static TrashRemoval get();
	/** The item's deleted members go to 2 and are marked removed */
	public void removeItem(long trashId, User actor) throws PersistenceException;
	/** The Classic Interface's members, one by one: a folder member takes the item's deleted members in its tree, a
	 *  document member goes alone, the top member takes the whole item */
	public void removeMembers(char type, Collection<Long> ids, User actor) throws PersistenceException;
	/** Every item of the user's in the Trash */
	public int empty(User user) throws PersistenceException;
}
```

- The SQL sets `ld_deleted = 2 where ld_deleted = 1 and ld_id in (...)`, so a member that isn't in the Trash in CE's rows is never touched.

- [ ] **Step 1: Write the failing tests**
  - `TrashRemovalTest extends AbstractRulesTestCase`:
    - `testRemoveTakesTheFoldersContents`: CE's `FSI.deleteFromTrash` left them at 1; now they go to 2, marked removed with by and at.
    - `testRemoveMembersOneByOne`: a document member removed in the Classic Interface goes alone, and the item stays in the Trash. A subfolder member takes its tree's members.
    - `testEmptyTakesOnlyTheUsersDeletedItems`: Bob's items stay.
    - `testRemoveSkipsMembersAlreadyRestored`.
    - Each is driven through `HealdanHooks.get().removeFromTrash(...)` and `emptyTrash(...)`, as the RPCs call them. `HealdanTrashHooksTest` checks that the RPCs do.
  - `HealdanTrashHooksTest`: a registered hook that answers `true` is called with the RPC's table and ids, and CE's update doesn't run. With the default, CE's update runs as today. `HealdanRpcChecks.trash` still refuses a non-admin's purge of another's row first.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 3: Write the hooks, the three RPC edits and `TrashRemoval`, then re-install `logicaldoc-core` and `logicaldoc-webapp`.**

- [ ] **Step 4: Run the tests**

Expected: PASS, and `DocumentServiceImplTest#testDeleteFromTrash`, `#testEmptyTrash` and `FolderServiceImplTest#testDeleteFromTrash` still pass.

- [ ] **Step 5: Add `HealdanTrashHooksTest` to `UPSTREAM_TESTS`; commit:** "Record who removed what from the Trash, and let a folder's removal take its contents"

---

### Task 6: What's already deleted when this ships

**Files:**
- Create: `HC/trash/TrashExistingSeed.java` (`trash-record-existing`)
- Modify: `HC/seed/SeedRunner.java` (`ORDER`: after `document-rows-granting-nothing`)
- Test: `HCT/trash/TrashExistingSeedTest.java`

**Interfaces:**
- Consumes: `TrashStore.insert`, `change`.
- Produces: `public class TrashExistingSeed implements SeedStep { name() = "trash-record-existing"; run(); }`. Its constructor is `(DataSource, TrashStore, CacheEvictor)`, as `ReindexOfficeSeed`'s is.

- [ ] **Step 1: Write the failing tests** (`TrashExistingSeedTest`). Each sets up rows at 1 and 2 with JDBC, deletes the seed's marker, and runs `SeedRunner`.
  - `testEachTopMostDeletedFolderIsOneItem`: its tree's rows at 1, through folders at 1. The deleter is CE's, and the time is `ld_lastmodified`.
  - `testEachDeletedDocumentInALiveFolderIsOneItem`, with its shortcuts at 1.
  - `testRowsAtOneUnderARemovedFolderJoinItAndGoToTwo` (spec §4.7).
  - `testRemovedRowsAreRemovedAtTheUpgrade`:
    - A top-most folder at 2, with its tree's rows at 1 and 2, is one item; so is a document at 2 in a live folder.
    - Their members are `removed`, with the seed's run as `removedAt` and no remover.
  - `testRunsOnceAndOnAnEmptyRepositoryDoesNothing`.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL (unknown seed name).

- [ ] **Step 3: Write the seed.** Passes, in order:
  1. top-most folders at 1, with live parents;
  2. documents at 1 in live folders;
  3. top-most folders at 2, with parents not at 2, taking their trees' rows at 1 to 2;
  4. documents at 2 whose folders aren't at 2.

  Rows already in an item are skipped. Evict the changed rows from Hibernate's cache after.

- [ ] **Step 4: Run the tests**

Expected: PASS.

- [ ] **Step 5: Docs:** `site/content/install/upgrading.md`:
  - what the first start does with what's already deleted, and that removed items count as removed at the upgrade and are destroyed after the set days;
  - how to raise Removed Items Kept first for more time (Healdan Settings, before the upgrade's first night).
- [ ] **Step 6: Commit:** "Make Trash items of what was deleted before this release"

---

### Task 7: Destroying, each night

**Files:**
- Modify: `logicaldoc-core/src/main/java/com/logicaldoc/core/document/DocumentManager.java` (`destroyDocument`, L2154: `where ld_id = %d`)
- Modify: `logicaldoc-core/src/test/java/com/logicaldoc/core/document/DocumentManagerTest.java` (`testDestroyDocumentNamesItsOwnFile`)
- Create: `HC/trash/TrashDestroyer.java`, `TrashStartup.java`, `TrashSystemDestroySeed.java` (`trash-system-destroy`)
- Modify: `HC/seed/SeedRunner.java` (`ORDER`: after `trash-record-existing`)
- Modify: `.github/workflows/build-logicaldoc.yml` (`DocumentManagerTest#testDestroyDocumentNamesItsOwnFile`)
- Test: `HCT/trash/TrashDestroyerTest.java`, `HCT/trash/TrashStartupTest.java`; `healdan-storage/src/test/java/com/logicaldoc/healdan/storage/CeFlowsTest.java` (`testDestroyedByTheTrash`)

**Interfaces:**
- Consumes: `TrashStore.removedBefore`, `members`, `change`; `HealdanSettings.trashRemovedDays()`; `DocumentManager.destroyDocument(long, FolderHistory)`.
- Produces:

```java
@Component public class TrashDestroyer {
	/** Destroys every member removed more than trashRemovedDays before now; returns how many members it destroyed */
	public int runOnce(Instant now);
}
@Component public class TrashStartup implements HealdanStartListener {
	static final LocalTime NIGHTLY = LocalTime.of(2, 47);
	static final Duration AFTER_START = Duration.ofMinutes(10);
	volatile Duration afterStart = AFTER_START;  // tests shorten it
	static ZonedDateTime nightlyAfter(ZonedDateTime earliest);
	/** The night the job will take something removed at removedAt, for TrashItem.destroyAfter */
	public static ZonedDateTime destroyAfter(Instant removedAt, int days, ZoneId zone);
}
```

- `TrashStartup` follows `healdan-storage`'s `StorageStartup`:
  - one daemon thread, named `healdan-trash-`;
  - `safely()`, rethrowing only `VirtualMachineError`;
  - a one-shot `schedule` that reschedules itself in `finally`;
  - `@PreDestroy` stop.

- [ ] **Step 1: Write the failing upstream test** `DocumentManagerTest#testDestroyDocumentNamesItsOwnFile`. Destroy a document that isn't the table's first. The `DOCUMENT_DESTROYED` folder history row has its own folder and file name, not the first row's.

- [ ] **Step 2: Write the failing Healdan tests**
  - `TrashDestroyerTest extends AbstractRulesTestCase`, with the setting at 30. Items are removed through `TrashRemoval`, then their `hd_removedat` is moved back with JDBC.
    - `testDestroysWhatWasRemovedMoreThanTheDaysBefore`, at 31 days:
      - the documents' rows, versions, notes, links, bookmarks and the index hit are gone;
      - the folders are gone deepest first, with `ld_folder_ext`, `ld_foldertag`, `ld_folder_acl`, `ld_folder_store`, folder bookmarks, `hd_local_value` (`F`) and `hd_searchfolder`;
      - the members are `destroyed` with `destroyedAt`, and the item's record reads as before.
    - `testKeepsWhatWasRemovedWithinTheDays`, at 29 days, Review Focus 2.
    - `testNeverDestroysAMattersFolderOrAStore`: they stay removed, and their contents are destroyed.
    - `testKeepsAFolderThatStillHoldsARow`: Bob's deleted document still in it. Also a folder that a live folder's `ld_securityref` names, and one a live alias points to. Each stays removed, and the next run after Bob's document is destroyed takes it.
    - `testOneFailureLeavesTheRestDone`: one item's document is made undestroyable with a test listener. That item stays removed; the others are destroyed; a warning is logged with ids only.
    - `testRunsWithoutTheAdminAccount`: the `admin` user disabled. The seed's `ld_menu_acl` row lets `_system` destroy.
    - `testCesReadsWorkAfterward`: `FolderDAO.findChildren` of the parent, `findFolderIdInTree` and `DeletedFoldersDataServlet`'s query.
  - `TrashStartupTest`:
    - `testNightlyAt0247`;
    - `testWaitsOutTheStartsDelay`;
    - `testAcrossTheClockChange` (Europe/London, the last Sunday of March);
    - `testDestroyAfterIsTheNightAfterTheDays`.

    Each is written as `StorageStartupTest`'s are.
  - `CeFlowsTest#testDestroyedByTheTrash`, on each side: a document deleted, removed and destroyed by `runOnce` has its files held for the holding period, as `testDestroyDocument` asserts.

- [ ] **Step 3: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 4: Make the upstream fix, write the seed, the job and the scheduler, then re-install `logicaldoc-core`.**
  - Each item gets a transaction of its own; batches are 100 items.
  - Documents go first, through `destroyDocument` with a `FolderHistory` whose user is `_system` (`User.USERID_SYSTEM`), then the folders as correction 10 says.
  - Log the count destroyed at info, and each item's id at debug.

- [ ] **Step 5: Run the tests**

Expected: PASS.

- [ ] **Step 6: Commit:** "Destroy what was removed more than the set days before, each night, and fix CE's destroy writing another document's name into folder history"

---

### Task 8: Removed Items Kept, in Healdan Settings

**Files:**
- Modify: `HC/web/HealdanSettingsServiceImpl.java` (`MAX_TRASH_DAYS = 365`; `Values` gains `trashRemovedDays`; `check`, `write`, `current`)
- Modify: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/beans/GUIHealdanSettings.java` (`trashRemovedDays`), `.../healdan/settings/HealdanSettingsPanel.java` (`TRASH_DAYS = "trashremoveddays"`, a `SpinnerItem` 1 to 365 after `apiMax`, hint `days`, in `form.setItems` and `save()`)
- Test: `HCT/web/HealdanSettingsServiceImplTest.java`

- [ ] **Step 1: Write the failing tests** `testSavesRemovedItemsKept` (30 → 60, read back) and `testRefusesRemovedItemsKeptOutsideOneTo365` (0 and 366). The message is "Removed items must be kept for between 1 and 365 days."

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL to compile.

- [ ] **Step 3: Write it.** The panel's label is "Removed Items Kept".

- [ ] **Step 4: Run the tests and compile the GUI's Java** (`mvn -B -ntp -pl logicaldoc-gui -DskipTests -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true install`)

Expected: PASS.

- [ ] **Step 5: Docs:** `site/content/admin/healdan-settings/index.md` (Removed Items Kept: what it does, 30 by default, that a change applies to everything already removed).
- [ ] **Step 6: Commit:** "Add Removed Items Kept to Healdan Settings"

---

### Task 9: `canMove`, `canDelete`, and folder delete, move and contents in the API

**Files:**
- Modify: `API/model/FolderView.java` (`canMove`, `canDelete` after `canRename`), `API/ApiViews.java` (`folder` L371-376, `subfolder` L397-401: one `may(...)` for the three flags)
- Create: `API/FolderChanges.java`, `API/TrashViews.java`; models `API/model/TrashItem.java`, `DeletedFrom.java`, `TrashContents.java`, `HeldDocument.java`
- Modify: `API/FolderResource.java` (`DELETE {id}`, `POST {id}/move`, `GET {id}/contents`; `final FolderChanges changes = new FolderChanges(renames.parents)`)
- Modify: `API/FolderRenames.java` (`taken` becomes `static boolean taken(long parentId, long exceptId, String name, User user)`, package-private)
- Modify: `YAML` (`FolderView`; the three routes; `TrashItem`, `DeletedFrom`, `TrashContents`, `HeldDocument`; `Problem` gains `holds`; `not-deletable`, `not-movable`, `items-not-allowed`, `immutable` in `examples`)
- Modify: `healdan-web/web/apps/user/src/test/fakeApi.ts` (`folder()`: `canMove: true, canDelete: true`), `U/organising/testing.tsx` (`matterTop`: both false), and any other `FolderView` literal `npm run typecheck` finds
- Modify: `UI/strings/en-US.ts` (the four codes' `problems.*` strings, below, which `frame/errors.test.ts` requires once the codes are in `examples`)
- Test: `APIT/FolderChangesTest.java`

**Interfaces:**
- Consumes: Task 2's recorder (the delete's item, found with `TrashStore.itemHolding(new TrashMember('F', id), TrashState.DELETED)`), Task 3's checks; `FolderRenames.parents` (`DocumentLocks`); `TrashStartup.destroyAfter`.
- Produces:

```java
public record FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store,
		List<FolderRef> path, boolean canFile, boolean canRename, boolean canMove, boolean canDelete) {}
public record TrashContents(long folders, long documents) {}
public record HeldDocument(long docNumber, String name, HoldView hold) {}
public record DeletedFrom(FolderRef folder, List<String> path) {}
/** type document|folder; kind as DocumentView.kind, or folder; size for a document; contents for a folder */
public record TrashItem(long id, String type, String name, Long docNumber, String kind, Long size,
		TrashContents contents, DeletedFrom deletedFrom, MatterRef matter, UserRef deletedBy, Date deletedAt,
		UserRef removedBy, Date removedAt, Date destroyAfter) {}
final class TrashViews {
	/** contents counts the members in the item's state that the caller can read; an admin's, every one */
	static TrashItem item(TrashItemRow row, User caller) throws PersistenceException;
}
final class FolderChanges {
	FolderChanges(DocumentLocks parents);
	TrashItem delete(long id, Caller caller);
	FolderView move(long id, Long folderId, Caller caller);
	TrashContents contents(long id, User caller);
}
```

- The strings (spec §7.6):
  - `problems.not-deletable` "A Store or a {matterLower}'s own folder can't be deleted here."
  - `problems.not-movable` "A Store or a {matterLower}'s own folder can't be moved here."
  - `problems.items-not-allowed` "It holds items you don't have permission to change."
  - `problems.immutable` "It holds an immutable document, which can't be deleted."

- [ ] **Step 1: Write the failing tests** (`FolderChangesTest extends ApiTestCase`, with `FolderRenameTest`'s layout plus Move and Delete for Clerk, and a second matter 998.2 "Other")
  - **Flags:** `canMove` and `canDelete` match `canRename`'s rows: the permission, a matter's top level (admins only), and a matter or a Store (false). They appear in `GET /v1/folders/{id}`, its children, recent folders and favorites.
  - **Delete,** each check in the order of spec §6.1:
    - `404` unreadable, alias, already deleted, and the Templates Store (correction 13);
    - `409 not-deletable` a matter's own folder and a Store, as the admin;
    - `403 forbidden`; `403 admins-only` a top-level folder as Clerk;
    - `403 items-not-allowed`: a subfolder Clerk can't see, and the problem's text and extras name nothing inside;
    - `409 checked-out` with `holds` listing only the readable documents (one Clerk can read, one hidden; Review Focus 3);
    - `409 immutable`;
    - `409 refused` from a test veto.

    Success answers `200` with a `TrashItem` whose `contents` counts only what Clerk can read.
  - **Move,** each check in the order of spec §6.2:
    - `400` with no body;
    - `404` folder or target unreadable;
    - `409 not-movable`;
    - `409 filing-rule` into a Store, a search folder, itself or its own subfolder;
    - `403 forbidden` without Move, and without Add on the target;
    - `403 admins-only` into a matter's top level as Clerk;
    - `403 items-not-allowed`;
    - `409 checked-out`;
    - `409 name-taken` (another folder in the target with the name in another case; a hidden one doesn't count);
    - `409 already-filed` with `document` (an email filed in 998.2 already);
    - into the parent it's in: `200` and nothing changes (`fx.snapshot()`).

    A move into 998.2 re-stamps its documents' Client and Matter. The answer is the folder's `FolderView` in its new place.
  - **Locks:** a rename of a sibling waiting on the target's parent lock blocks the move until it's done, as `FolderRenameTest.whileTheParentIsLocked` does.
  - **Contents:** counts of live readable folders and documents in the tree; a hidden subfolder and its documents don't count; `404` as `GET /v1/folders/{id}`.
  - `RouteCoverageTest` passes.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL. No routes yet, and the flags are missing.

- [ ] **Step 3: Write `FolderChanges` and the contract**
  - The pre-checks only shape the answer. The work is CE's own `FolderDAO.deleteTree(id, DELETED_CODE_DEFAULT, history)` and `FolderDAO.move(folder, target, history)`, with the history from `caller.session()`, so the hooks and listeners decide on every route.
  - `items-not-allowed` catches `HealdanTreeChecks.deletedTrees` / `movedTrees`' `PermissionException`.
  - The move takes both parents' locks in id order (`parents.withLock(low, …, () -> parents.withLock(high, …, work))`) and checks inside them again, as the rename does.
  - Each `operationId`'s description lists its checks in order, as `renameFolder`'s does.

- [ ] **Step 4: Run the tests, `RouteCoverageTest`, `FolderRenameTest` and the web checks** (`npm run generate`, `typecheck`, `test`)

Expected: PASS.

- [ ] **Step 5: Commit:** "Delete, move and count a folder through the API, with canMove and canDelete on every folder"

---

### Task 10: The Trash in the API, and a document delete's answer

**Files:**
- Create: `API/TrashResource.java` (`@Component @Path("/v1/trash")`), `API/model/TrashPage.java`, `RestoreRequest.java`, `RestoredItem.java`
- Modify: `API/DocumentChanges.java` (`delete` returns the item), `API/DocumentResource.java` (`delete` answers `200` with it)
- Modify: `YAML` (the four routes; `deleteDocument`'s `200`; the schemas; `place-gone`)
- Modify: `APIT/DocumentChangesTest.java` (`testDeleteMovesToTheTrash`, `testDeleteTakesTheShortcutsToTheTrash`: `200` with the item)
- Modify: `healdan-web/web/apps/user/src/actions/remove.ts` (`removeDocument` returns `Promise<S['TrashItem']>`); `UI/strings/en-US.ts` (`problems.place-gone` "The folder it was deleted from is gone, or you can't restore into it. Choose another.")
- Test: `APIT/TrashResourceTest.java`

**Interfaces:**
- Consumes: Task 9's `TrashItem` and `TrashViews.item`; `TrashStore`, `TrashRestore`, `TrashRemoval`, `HealdanSettings.trashRemovedDays()`; `FolderResource.view`.
- Produces:

```java
public record TrashPage(List<TrashItem> items, int offset, int limit, Long total, int removedDays) {}
public record RestoreRequest(Long folderId) {}
public record RestoredItem(FolderView place, TrashItem item) {}
```

- `GET /v1/trash?view=mine|everyone|removed&sort=name|deleted|removed&order=asc|desc&offset&limit` (`Paging.of`, paged in SQL with `count`). `everyone` and `removed` are `403 admins-only` for others; an unknown view or sort is `400`.
- `POST /v1/trash/{id}/restore` → `200 RestoredItem`.
- `DELETE /v1/trash/{id}` → `204`.
- `DELETE /v1/trash` → `204`.

- [ ] **Step 1: Write the failing tests** (`TrashResourceTest extends ApiTestCase`)
  - **The lists:**
    - `mine` lists Clerk's items newest first and none of Reader's;
    - `everyone` and `removed` are `403 admins-only` to Clerk and list everyone's for Boss;
    - `removed` has `removedBy`, `removedAt` and `destroyAfter` (the night after `removedDays`);
    - `removedDays` follows the setting;
    - paging and the three sorts work.
  - **`deletedFrom`:** a live, readable place gives `folder` and its present path; a place since deleted gives `folder: null` and the recorded names; a place since moved gives the new path.
  - **Restore:**
    - back where it was gives `200` with `place`;
    - the place deleted, or no longer readable, with no `folderId` gives `409 place-gone`; with `folderId` it restores there;
    - into a matter's top level as Clerk gives `403 admins-only`; without Add (folder) or Write (document) on the target, `403 forbidden`;
    - once per matter gives `409 already-filed`;
    - `404` for another's item, for Clerk on a removed item, and for a restored or destroyed item; Boss restores from Removed;
    - Undo twice: the second restore is `404`, and nothing changes (Review Focus 5).
  - **Remove:** `DELETE /v1/trash/{id}` moves the item to Removed. It's `404` for another's item unless the caller is an admin, and for an item already removed.
  - **Empty:** `DELETE /v1/trash` removes only the caller's items.
  - **A document delete:** `DELETE /v1/documents/{n}` answers `200` with the item: `size` is the file's, `contents` is null, and its shortcut rows are members.
  - `RouteCoverageTest` passes.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 3: Write it.**
  - Restore, remove and empty go through `TrashRestore` and `TrashRemoval`. A `HealdanRuleException` maps through `ProblemMapper` as today, except place checks with no `folderId`, which are `place-gone`. Those checks are `placeOf` empty, or `FilingRules` refusing the place.
  - `deleteDocument`'s description says the answer changed from `204` to `200` and that the item's id is what `restoreTrashItem` takes.

- [ ] **Step 4: Run the tests, `RouteCoverageTest`, `DocumentChangesTest`, and the web checks** (the new `problems.place-gone` string)

Expected: PASS.

- [ ] **Step 5: Commit:** "List, restore, remove and empty the Trash through the API, and answer a delete with its Trash item"

---

### Task 11: PR A's docs, and the pull request

**Files:**
- Create: `site/content/admin/the-trash-and-removed-items.md` (title "The Trash and Removed Items")
- Modify: `site/content/admin/storage-backends/held-deletes.md` (a destroyed document's files), `site/content/admin/top-level-folders.md` (move and delete greyed out in the app), `site/content/install/known-limitations.md` (moving and deleting folders now in the app; the Classic Interface's Trash lists a folder's contents as rows), `site/content/developer/api.md` (the routes, `TrashItem`, the codes in the table with `not-renamable`, `admins-only` and `name-taken` added, `canMove` and `canDelete`, the delete's `200`), `site/content/developer/how-healdan-extends-logicaldoc.md` (`hd_trash`, the hooks, the listeners, the job, the seeds), `site/content/developer/tests.md` (the new test classes and `UPSTREAM_TESTS` entries)

- [ ] **Step 1: Write the pages.** The new Admin page covers:
  - the four states (spec §4.1) and what an item is;
  - restoring as an administrator (Everyone's and Removed), and the record that outlives a destroyed item;
  - the nightly job at 02:47, and what is never destroyed;
  - the Classic Interface's Deleted Documents and Deleted Folders reports, and that its Destroy Documents stays outside the tiers;
  - a backup's reach (spec §4.6).
- [ ] **Step 2:** Run `hugo --source site --panicOnWarning`, `python3 .github/scripts/check-site-sources.py`, `python3 .github/scripts/check-site-links.py`, the quick checks (`check-fontawesome-icons.py`, `check-logicaldoc-doc-links.py`, `check-logicaldoc-hosts.py`), and every `healdan-core`, `healdan-api` and `healdan-storage` test class this PR touched.

Expected: PASS.
- [ ] **Step 3: Commit:** "Document the Trash's tiers, the routes and the job". Open PR A, stating the CI seconds it adds.

PR A ends here.

---

### Task 12: An action on the toast

**Files:**
- Modify: `UI/components/Toast.tsx` (`toastWithAction`; `Toast` draws the action), `Toast.module.css`, `UI/index.ts`
- Test: `UI/components/Toast.test.tsx`

**Interfaces:**
- Produces:

```ts
export interface ToastAction { label: string; onAction: () => void }
/** A toast with one action, such as Undo: 10 s, paused while the pointer or focus is on the region */
export function toastWithAction(message: string, action: ToastAction): void
```

- [ ] **Step 1: Write the failing tests**
  - The action is a button with its label inside the toast, after the message. Pressing it calls `onAction` once and closes the toast.
  - The toast is reachable by F6 from the page (React Aria's landmark), then Tab to the action, then Enter.
  - It closes itself after 10 s (fake timers). While the region is hovered or focused, it doesn't.
  - Still at most three visible.
  - Axe passes.

- [ ] **Step 2: Run them to see them fail**

Run: `npx vitest run packages/ui/src/components/Toast.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Write it.** React Aria's toast region already pauses its timers on hover and focus: check it in the test rather than adding code. The action uses `@healdan/ui`'s `Button` (`variant="quiet"`).

- [ ] **Step 4: Run the tests**

Expected: PASS.

- [ ] **Step 5: Commit:** "Let a toast carry one action, for Undo"

---

### Task 13: The Trash view

**Files:**
- Modify: `U/router.tsx` (`trashRoute`, path `trash`, `validateSearch`: `view` of `mine|everyone|removed`, else `mine`; `sort` of `name|deleted|removed`; `order`)
- Modify: `U/frame/LeftPane.tsx` (a `TrashEntry` at the foot of the `<nav>`, after `FavoritesSection`, apart from the sections), `LeftPane.module.css`
- Create: `U/views/TrashView.tsx`, `U/views/TrashDetails.tsx`, `U/actions/trash.ts`, `U/organising/ConfirmRemove.tsx`
- Modify: `U/organising/MovePicker.tsx` (an optional `title`, which the restore picker passes; Task 15 changes the rest of its props)
- Modify: `U/queries.ts` (`useTrash(view, sort, order)`, key `['trash', view, sort, order]`); `U/actions/refresh.ts` (`'trash'` among the roots `invalidateDocumentLists` covers)
- Modify: `UI/strings/en-US.ts` (below)
- Modify: `U/test/fakeApi.ts` (`trashItem()` factory; `GET /v1/trash` default route answering an empty page with `removedDays: 30`)
- Test: `U/views/TrashView.test.tsx`, `U/frame/LeftPane.test.tsx`

**Interfaces:**
- Consumes: Task 10's routes; `MovePicker`; `Table`, `Toolbar`, `RightRegionView`.
- Produces:

```ts
// actions/trash.ts
export function listTrash(api: Api, query: { view: TrashViewName; sort?: string; order?: string; offset?: number }): Promise<S['TrashPage']>
export function restoreTrashItem(api: Api, id: number, folderId?: number): Promise<S['RestoredItem']>
export function removeTrashItem(api: Api, id: number): Promise<void>
export function emptyTrash(api: Api): Promise<void>
export type TrashViewName = 'mine' | 'everyone' | 'removed'
/** Restores each, back where it was; one that answers place-gone is returned for the picker */
export function restoreItems(api: Api, items: readonly S['TrashItem'][]): Promise<{
  done: S['RestoredItem'][]; placeGone: S['TrashItem'][]; refused: { item: S['TrashItem']; error: unknown }[]
}>
```

- The strings (exact copy; spec §7.2 to §7.4):
  - `trash.title` "Trash"
  - `trash.restore` "Restore"
  - `trash.remove` "Remove…"
  - `trash.empty` "Empty Trash…"
  - `trash.view.mine` "Mine"
  - `trash.view.everyone` "Everyone's"
  - `trash.view.removed` "Removed"
  - the columns `trash.name` "Name", `trash.docNumber` "Doc #", `trash.deletedFrom` "Deleted From", `trash.deleted` "Deleted", `trash.deletedBy` "Deleted By", `trash.removedBy` "Removed By", `trash.removed` "Removed", `trash.destroyed` "Destroyed"
  - `trash.emptyTitle` "Nothing in the Trash."
  - `trash.emptyLine` "What you delete comes here, and you can restore it from here."
  - `trash.removedLede` "What people removed from their Trash. Restore puts an item back where it was deleted from; after {days} days it's destroyed."
  - `trash.destroyIn` plural "in {n} day" / "in {n} days"
  - `trash.notRecorded` "Not recorded" (a Removed By the upgrade didn't record)
  - `trash.restored` "Restored "{name}" to {place}"
  - `trash.open` "Open"
  - `trash.restoreTo` "Restore "{name}" to…"
  - `remove.oneTitle` "Remove "{name}" from the Trash?"
  - `remove.manyTitle` plural "Remove {n} items from the Trash?"
  - `remove.emptyTitle` "Empty the Trash?"
  - `remove.emptyLine` plural "This removes all {n} items you deleted."
  - `remove.adminCan` "You won't be able to restore it. An administrator can, for {days} days."
  - `remove.confirm` "Remove"
  - `remove.emptyConfirm` "Empty Trash"
  - `holds.line` "It holds {folders} and {documents}." (the questions)
  - `holds.list` "{folders}, {documents}" (the details)
  - plurals `holds.folders` "{n} folder" / "{n} folders" and `holds.documents` "{n} document" / "{n} documents"
  - `trash.items` plural "{n} item" / "{n} items" (beside a folder's name)

- [ ] **Step 1: Write the failing tests** (`TrashView.test.tsx`, through `renderApp('/trash')`)
  - The left pane's Trash entry is the last in the navigation, links to `/trash` and is `aria-current` there.
  - The list shows Name (with "14 items" beside a folder), Doc #, Deleted From (`1001.1 › Drafts`) and Deleted, newest first. Sorting by Name asks `sort=name`.
  - The details show the icon, name, kind and size or count, Deleted From in full, Deleted, and a folder's "3 folders, 11 documents", with Restore and Remove….
  - The empty state.
  - Restore on two selected items calls each and toasts "Restored "Old Drafts" to 1001.1 › Drafts" with Open. One answering `409 place-gone` opens `MovePicker` titled "Restore "Old Drafts" to…", whose Move Here restores with `folderId`.
  - Remove… asks with the one-item, several-item and Empty Trash wordings, `{days}` from `removedDays` (a page with 45 says 45). The confirm button is the destructive variant. Cancel calls nothing.
  - The keyboard: rows select as in any list; Delete on a row is Remove…; Enter opens nothing; the right-click menu has Restore and Remove….
  - The switch is absent for a non-admin (`me.admin` false), and `/trash?view=everyone` shows Mine for one.
  - For an admin:
    - the switch shows Mine · Everyone's · Removed and keeps `?view=`;
    - Everyone's adds Deleted By;
    - Removed has Deleted By, Removed By, Removed and Destroyed ("in 23 days", with the caution style under 7), Restore only, and the lede.
  - Axe passes for each view, both dialogs and the picker.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 3: Write it.**
  - The view follows `FolderView`'s structure: `Toolbar`, `Table` with `rowOf`-style rows keyed `t{id}`, every row selectable, and `RightRegionView` with `details={<TrashDetails …/>}`.
  - It pages as `useListing` does.
  - Restore and Remove refresh `['trash']` and the document lists.
  - The switch is a React Aria `ToggleButtonGroup` from `@healdan/ui` (add a thin wrapper there if none exists), labelled "Show".

- [ ] **Step 4: Run the tests and the web checks**

Expected: PASS.

- [ ] **Step 5: Commit:** "Add the Trash to the User app, with Restore, Remove and Empty Trash, and the admin switch"

---

### Task 14: Folder rows selectable, folder deletes, and Undo

**Files:**
- Modify: `U/views/listColumns.tsx` (`rowOf`: folders selectable, matters not), `U/views/useRowSelection.ts` (keeps `f…` keys in its own state; only one document reaches `?sel=`), `U/menus/useListMenu.tsx` (a selection of several, or with a folder, gets `SelectionMenuItems`), `U/organising/useOrganisingTable.ts` (`onDelete` and `drag` take folders), `U/views/RecentView.tsx` (`RecentFolders` passes a real selection)
- Modify: `U/organising/OrganisingProvider.tsx` (`askDelete(items: OrganisingItems)`; `remove` deletes documents and folders, toasts with Undo), `U/organising/ConfirmDelete.tsx` (items; the one-item and several-item titles; the folder's contents line; the new note)
- Create: `U/actions/folders.ts`, `U/organising/undo.ts`
- Modify: `U/actions/batch.ts` (`eachItem`), `U/menus/ContextMenus.tsx` (`FolderMenuItems`: Move To… and Delete after Rename, greyed out from `canMove` and `canDelete`; `SelectionMenuItems`), `U/frame/LeftPane.tsx` (the tree's ⋯ on folders: Move To… and Delete; `Tree`'s new `onDelete`), `U/views/FolderView.tsx` (the heading ⋯: Move To… and Delete; not for a matter's own folder or a Store)
- Modify: `UI/components/Tree.tsx` (`onDelete?: (id: string) => void`: Delete and Backspace on a row, as F2 is handled)
- Modify: `UI/strings/en-US.ts`
- Test: `U/organising/Delete.test.tsx` (rewritten for the new words), `U/organising/FolderDelete.test.tsx` (new), `U/organising/Undo.test.tsx` (new), `UI/components/Tree.test.tsx`, `U/views/selection.test.tsx` (or where `useRowSelection` is tested today)

**Interfaces:**
- Consumes: Task 9's routes, Task 12's `toastWithAction`, Task 13's `restoreItems`.
- Produces:

```ts
// actions/folders.ts
export function deleteFolder(api: Api, id: number): Promise<S['TrashItem']>
export function moveFolder(api: Api, id: number, folderId: number): Promise<S['FolderView']>
export function folderContents(api: Api, id: number): Promise<S['TrashContents']>
/** A folder being moved or deleted, from a row, the tree, the heading or a drag */
export interface MovingFolder { id: number; name: string; parentId: number; matterId: number | null; canMove: boolean; canDelete: boolean }
export function movingFolder(view: S['FolderView']): MovingFolder
export function whyNotDelete(folder: S['FolderView'], admin: boolean | undefined): StringKey   // as whyNotRename
export function whyNotMove(folder: S['FolderView'], admin: boolean | undefined): StringKey
// organising
export interface OrganisingItems { documents: readonly Movable[]; folders: readonly MovingFolder[] }
// Organising: askDelete(items: OrganisingItems), askMove(items: OrganisingItems) (Task 15 uses the second)
// undo.ts
export function undoDeletes(api: Api, items: readonly S['TrashItem'][]): Promise<void>  // restores each, then toasts or shows Refusals
```

- The strings:
  - `delete.oneTitle` "Move "{name}" to the Trash?"
  - `delete.manyTitle` plural "Move {n} items to the Trash?"
  - `delete.trashNote` "You can restore it from the Trash, at the foot of the left pane."
  - `delete.trashNoteMany` "You can restore them from the Trash, at the foot of the left pane."
  - `delete.done` "Moved "{name}" to the Trash"
  - `delete.doneMany` plural "Moved {n} items to the Trash"
  - `undo.label` "Undo"
  - `undo.done` "Restored "{name}""
  - `undo.doneMany` plural "Restored {n} items"
  - `undo.refusedTitle` "Some Items Weren't Restored"
  - `delete.refusedTitle` "Some Items Weren't Moved to the Trash"
  - `delete.notAllowed` "It holds items you don't have permission to delete."
  - `delete.checkedOut` "Documents in it are checked out:"
  - `delete.checkedOutAfter` "They can be deleted once they're checked in or released."
  - `delete.matterFolder` "A {matterLower}'s own folder is deleted by an administrator in the Classic Interface."
  - `delete.adminsOnly` "Only administrators can delete a {matterLower}'s top-level folders."
  - `delete.noPermission` "You don't have permission to delete this folder."

- [ ] **Step 1: Write the failing tests**
  - **Selection:**
    - A folder row selects with Space or Shift-click as a document's does, and folders and documents select together.
    - `?sel=` holds one document as now, and nothing when a folder is in the selection.
    - The details stay a document's (correction 16).
    - A matter row still doesn't select.
  - **Ways in:**
    - The Delete key on a focused or selected folder row, the folder's right-click and ⋯ menus, and the heading's ⋯ all ask to delete the folder.
    - In the tree, Delete on a folder asks; on a matter it toasts `delete.matterFolder`.
    - Greyed out from `canDelete`, and Delete on a folder that can't be deleted toasts `whyNotDelete`'s reason.
    - A matter's own folder shows neither Move To… nor Delete.
  - **The question:**
    - One folder: "Move "Old Drafts" to the Trash?" and "It holds 3 folders and 11 documents." (from `GET /v1/folders/{id}/contents`), then the note.
    - One document: "Move "Lease.pdf" to the Trash?" (correction 14).
    - Several: "Move 4 items to the Trash?" listing them, and the plural note.
  - **The delete:**
    - Each item's own call, in order. The toast says "Moved "Old Drafts" to the Trash" with Undo.
    - A refusal goes per item to Refusals: `items-not-allowed` as `delete.notAllowed`, and `checked-out` with each readable `holds` line (name, who, since when) and `delete.checkedOutAfter`. The rest is done.
  - **Undo,** Review Focus 5:
    - Undo restores each `TrashItem` the delete made, with no `folderId`, then toasts "Restored "Old Drafts"".
    - Undo pressed twice calls restore once.
    - An item answering `404` (already restored) or `409 place-gone` goes to Refusals with `undo.refusedTitle`, and the rest is restored.
  - **Tree:** `Tree.test.tsx` covers `onDelete` on Delete and Backspace, never on the loading child.
  - Axe on the question with a folder's line.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 3: Write it.**
  - `Table`'s `disabledKeys` already follows `selectable`. Dragging is tied to it too (`canDragNow`), which Task 15 needs.
  - Update the copy where Tasks 9 to 13 left the old words: `organising.spec.ts:177`, `keyboard.spec.ts`' row 9 and `Delete.test.tsx`.

- [ ] **Step 4: Run the tests and the web checks**

Expected: PASS.

- [ ] **Step 5: Commit:** "Delete folders from the User app, with folder rows that select, and Undo on every delete"

---

### Task 15: Moving folders, by Move To… and by drag

**Files:**
- Modify: `U/organising/MovePicker.tsx` (items; greyed choices with reasons; Move Here's rules for folders), `U/organising/OrganisingProvider.tsx` (`askMove(items)`, `move` per item, `dropRows` reading folders, the cross-matter question before a folder leaves its matter)
- Create: `U/organising/CrossMatter.tsx`
- Modify: `U/organising/rows.ts` (the payload gains `folders: { id, name, parentId, matterId }[]`; `readRows` returns `{ documents, folders }`), `U/filing/dropTargets.ts` (`Place` gains `matterId: number | null`; `placeOf` fills it)
- Modify: `UI/components/Tree.tsx` (`drag?: { type: string; data: (id: string) => string | undefined }`: rows draggable when it returns a payload, `effectAllowed = 'move'`), `U/frame/LeftPane.tsx` (folders in the tree draggable; drops of folders on tree folders), `U/actions/useDocumentActions.ts` and `U/actions/SelectionActions.tsx` (Move To… and Delete act on the selection's folders too)
- Modify: `UI/strings/en-US.ts`
- Test: `U/organising/FolderMove.test.tsx` (new), `U/organising/MovePicker.test.tsx`, `U/organising/RowDrag.test.tsx` ("Dragging a folder › carries nothing" becomes "carries the folder"), `UI/components/Tree.test.tsx`

**Interfaces:**
- Consumes: Task 9's `moveFolder` and `folderContents`; Task 14's `OrganisingItems`, `MovingFolder`.
- Produces: `MovePickerProps { items: OrganisingItems; title?: string; onChoose; onCancel }`. `items` replaces `documents`, and the `title` Task 13 added stays. The restore picker passes empty `items` and its title.
- The strings:
  - `move.oneTitle` "Move "{name}""
  - `move.manyTitle` plural "Move {n} Items"
  - `move.itself` "The folder itself"
  - `move.already` "Already there"
  - `move.adminsOnly` "Administrators only"
  - `move.notAllowed` "It holds items you don't have permission to move."
  - `move.doneFolder` "Moved "{name}" to {folder}"
  - `move.doneMany` plural "Moved {n} items to {folder}"
  - `crossMatter.title` "Move "{name}" into {matter}?"
  - `crossMatter.line` plural "Its {n} document will take {number}'s client and {matterLower}, and the properties its template sets." / "Its {n} documents will take {number}'s client and {matterLower}, and the properties its template sets."
  - `crossMatter.confirm` "Move"
  - `move.matterFolder` "A {matterLower}'s own folder is moved by an administrator in the Classic Interface."
  - `move.adminsOnlyFolder` "Only administrators can move a {matterLower}'s top-level folders."
  - `move.noPermission` "You don't have permission to move this folder."

- [ ] **Step 1: Write the failing tests**
  - **The picker, moving a folder:**
    - The folder itself is a greyed-out choice with "The folder itself", and it can't be opened, so nothing in it can be chosen.
    - The folder's own parent shows Move Here greyed out with "Already there".
    - A matter's top level shows Move Here greyed out with "Administrators only" for a non-admin, and enabled for an admin.
    - Search folders aren't listed (correction 12).
    - With documents in the selection, a matter's top level is greyed out for them as now.
    - Move Here calls each item's move.
  - **Drag:**
    - A drag of folder rows, or of a folder in the tree, carries them in `ROWS_TYPE`'s payload with this page's token.
    - A drop on a tree folder outlines it and moves them.
    - A drop of a folder onto itself or its own subfolder does nothing but say "The folder itself".
  - **Leaving its matter,** by drag and by picker: "Move "Old Drafts" into 1003.2 – Licence Review?" with "Its 11 documents will take 1003.2's client and matter, and the properties its template sets."
    - Cancel moves nothing; Move moves.
    - A move within the matter, and documents moved on their own, go at once.
  - **Refusals:** `items-not-allowed` as `move.notAllowed`, `checked-out` with its holds, `name-taken`, `admins-only`, `already-filed`, each per item, with the rest done.
  - Greyed out from `canMove`; Move To… on one that can't toasts `whyNotMove`.
  - The toolbar's Move To… and Delete act on a selection holding folders, as the menus do.
  - Axe on the picker with greyed choices, and on the question.

- [ ] **Step 2: Run them to see them fail**

Expected: FAIL.

- [ ] **Step 3: Write it.**
  - The cross-matter question compares each folder's `matterId` with the target place's. It asks once per folder leaving its matter, in order, and counts with `folderContents`.
  - Folders in a selection move after its documents, so a document moved into a folder that is also moving goes where the person saw it go.

- [ ] **Step 4: Run the tests and the web checks**

Expected: PASS.

- [ ] **Step 5: Commit:** "Move folders from the User app, by Move To… and by drag, asking before one leaves its matter"

---

### Task 16: The end-to-end specs

**Files:**
- Create: `healdan-web/web/e2e/trash.spec.ts`
- Modify: `healdan-web/web/e2e/folders.spec.ts`, `keyboard.spec.ts`, `a11y.spec.ts`, `helpers.ts` (`deviceToken(api, run, who?)`, where `who` is `{ username, password }`)
- Create: `HC/seed/SmokeSecondMatterSeed.java` (`smoke-second-matter`, needs `HEALDAN_SMOKE_FIXTURE=true`; matter 1.4 "Second Matter" in Store Smoke from `Smoke template`, if missing); `HC/seed/SeedRunner.java` (`ORDER`: after `smoke-web-user`); `HCT/seed/SmokeSecondMatterSeedTest.java`
- Modify: `docker/tests/test-web.sh` (passes `ADMIN_PASSWORD=admin`, the smoke stack's published one)

- [ ] **Step 1: Write the seed's test and the seed** (as `SmokeFixtureSeedTest` tests `smoke-fixture`). It does nothing without the variable, makes 1.4 once, and gives Everyone the template's permissions there (Rename, Move and Delete included, as `smoke-organising` set them on the template).

- [ ] **Step 2: Write the specs**
  - **`trash.spec.ts`,** as `webuser`, in `Trash ${RUN}` inside `E2E`:
    - delete a folder holding a document from its right-click menu, press Undo in the toast, and check the folder and document are back;
    - delete it again, open Trash from the left pane, Restore, and check where it went (the toast's Open);
    - delete it and Remove it, then check it's gone from the Trash and that `GET /api/v1/trash?view=removed` with an admin token lists it;
    - delete two documents and Empty Trash, then check the Trash is empty.
  - **`folders.spec.ts`:**
    - move a folder by drag onto the tree, by Move To…, and once by keyboard alone (`tabTo`, the picker by Tab and Enter);
    - move a folder from matter 1.1 into 1.4, answer the cross-matter question, and check a document in it now shows matter 1.4 through the API;
    - Delete and Move To… are greyed out on matter 1.1's top-level `Documents` for `webuser`.
  - **`keyboard.spec.ts`:** a new test, "folders and the Trash are all keys":
    - Delete on a folder row, Enter in the question;
    - F6 to the toast and Undo by Enter;
    - then Delete again, the Trash entry by `tabTo`, a row by the arrows, and Restore.
  - **`a11y.spec.ts`:** the Trash with items and empty, its Remove and Empty Trash questions, the delete question with a folder's line, the toast with Undo, the picker with greyed choices, and the cross-matter question, in both themes.
  - Every spec makes what it needs in its `beforeAll` and cleans up in `afterAll`, as `folders.spec.ts` does; none relies on another.

- [ ] **Step 3: Run the smoke test** (`docker/smoke-test.sh` on a fresh stack, or step 9 against a running one)

Expected: PASS in Chromium and WebKit.

- [ ] **Step 4: Commit:** "Test folders and the Trash end to end, with a second matter on the smoke stack"

---

### Task 17: The User Guide, and PR B

**Files:**
- Modify:
  - `site/content/user/documents/the-trash/index.md`: rewritten for the app, with the Classic Interface's Trash as a section, and a new `trash.png` from the demo firm;
  - `site/content/user/documents/copying-moving-and-deleting/index.md`: folders, Undo, the opening line;
  - `site/content/user/getting-started/getting-around/index.md`: the Delete key on folders, the Trash entry;
  - `site/content/user/finding-documents/my-matters-and-recent-matters/index.md`: the tree's ⋯ and drag;
  - `site/content/developer/the-web-app.md`: the toast's action, selectable folder rows, the drag payload's folders;
  - `site/content/developer/tests.md`: the new specs and the seed;
  - `site/content/install/known-limitations.md`, if Task 11 didn't already cover what the app now does.

- [ ] **Step 1: Write the pages.** Screenshots come from the demo firm (`docker/demo/compose.demo.yaml`), in the light theme.
- [ ] **Step 2:** Run the site checks (Task 11's Step 2) and the web checks.

Expected: PASS.
- [ ] **Step 3: Commit:** "Document folders and the Trash in the User Guide". Open PR B, stacked on PR A, stating the CI seconds it adds.

PR B ends here.

---

### Task 18: Records

**Files:**
- Modify:
  - `docs/superpowers/specs/2026-11-16-folders-and-trash-design.md`: the status; this plan's corrections and the build's rulings, each marked "changed in the build";
  - `docs/superpowers/specs/2026-11-10-m6-user-app-design.md`: §2.2, §4, §6.7 and §16, which say moving and deleting folders and the Trash are in the app now;
  - `docs/ROADMAP.md`: the backlog row "User app: further enhancements";
  - `docs/reference/healdan-api-inventory.md`: the new routes and the totals;
  - `CLAUDE.md`, three places:
    - the list of specs that edit upstream files, with this spec's §9.1 and the edits it lists;
    - the `healdan-core` paragraph: the Trash, its tables, hooks, listeners and job;
    - Tests: the new `UPSTREAM_TESTS` entries;
  - this plan: its status and a decision log.

- [ ] **Step 1:** Fold the corrections in, and check every docs page in spec §11 shipped.
- [ ] **Step 2:** Run `python3 -m unittest discover -s .github/scripts/tests -v`, the quick checks and `hugo --source site --panicOnWarning`.

Expected: PASS.
- [ ] **Step 3: Commit:** "Record the folders and Trash build". Open PR C, stacked on PR B.
