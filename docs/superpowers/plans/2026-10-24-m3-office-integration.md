# M3 "Office integration" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save to and open from Healdan inside LibreOffice on macOS, then on Linux, through a small helper app on each computer, with the doc # and version always in the title bar and footer, as the approved spec describes.

**Architecture:**
- **Server.** M2's API gains what the helper's windows and saves need: search within a folder, New Folder, favourites, device holds, save preconditions, doc ID stamping, thumbnails, the Comments property and the title's `#` form. All of it lives in `healdan-api` and `healdan-core`, with no server-side upstream edit.
- **The old web page.** CE's hidden "Edit with Office" becomes Open in App, which opens a `healdan://` link.
- **The helper.** A .NET 10 solution in `clients/helper/`:
  - a window-free core: API client, local copies, the open-save-close cycle, keychain, device id;
  - an Avalonia 12 menu-bar app: windows, links, the channel the extension calls.
- **The LibreOffice extension.** A Python UNO extension in `clients/libreoffice/` that takes over Open, Save and Save As and sets the title and footer.
- **CI.** A new workflow builds, tests and packages the clients. `code-changed.sh` keeps client-only and server-only pull requests from waiting on each other.

**Tech Stack:**
- **Server:** as M2: LogicalDOC CE 9.3.1 on JDK 25, Hibernate 7, Flyway, CXF 4.0 JAX-RS with Jackson 2.20, and JUnit 4 with HSQLDB and Spring's servlet mocks.
- **Helper:**
  - .NET SDK 10.0 (pinned in `clients/helper/global.json`) and Avalonia 12.1.4, the versions the trial built with;
  - xUnit 2.9 with `Microsoft.NET.Test.Sdk`;
  - `System.Text.Json` and `HttpClient`;
  - no other runtime packages without a decision-log entry.
- **Extension:** Python 3 as bundled with LibreOffice 26.2 and later, UNO, and `unittest` for its tests.
- **macOS packaging:** `codesign`, `notarytool`, `stapler` and `hdiutil`.
- **Linux packaging (phase 2):** `dpkg-deb` in an `ubuntu:26.04` container.

**Status:** written on 2026-10-24, for the owner's review.

**Spec:** [`docs/superpowers/specs/2026-10-24-m3-office-integration-design.md`](../specs/2026-10-24-m3-office-integration-design.md), approved by the owner on 2026-10-24. Every § below points into it.
- Where the two disagree, the spec wins, except for the corrections under "Spec corrections and calls this plan makes".
- §12.5 is the trial's evidence. The trial's code is gone, but its lessons are written into the tasks below.

## Global Constraints

Every task's requirements include these.

- **Scope.**
  - Phase 1 (Tasks 1–21) is LibreOffice on macOS. Phase 2 (Tasks 22–23) is LibreOffice on Ubuntu 26.04, and starts only once the owner's semi-production server is running (spec §2.1).
  - Microsoft Office is M3.5 and isn't in this plan.
- **Server code location.**
  - Endpoints are in `healdan-api` under `com.logicaldoc.healdan.api`, models in `.api.model`.
  - Domain code is in `healdan-core` under `com.logicaldoc.healdan.*`.
  - No server-side upstream file changes (spec §13.1).
- **GUI upstream edits, and only these** (spec §13.1). All are CRLF; use the Edit tool, and check with `file <path>` afterwards:
  - `logicaldoc-gui/.../common/client/util/Util.java`;
  - `logicaldoc-gui/.../frontend/client/document/grid/ContextMenu.java`;
  - `logicaldoc-gui/.../frontend/client/document/DocumentToolbar.java`;
  - `logicaldoc-i18n/src/main/resources/i18n/messages.properties` (ASCII, one line).
- **Java sources are ASCII.** Check with `LC_ALL=C grep -nP '[^\x00-\x7F]' <files>`.
- **Schema.**
  - One new Flyway script, `V4__devices.sql`, in `healdan-core/src/main/resources/healdan/db/migration/mariadb` and `.../hsqldb`, in V3's style.
  - Never edit V1–V3.
- **Seeds.** One new step, `profile-comments`, in `SeedRunner.ORDER` after `api-settings` and before `smoke-fixture`.
- **The API.**
  - **Contract.** Every endpoint and field added goes into `openapi.yaml` in the same task; `RouteCoverageTest` and the contract check enforce it.
  - **New problem codes,** all `409`: `held-elsewhere`, `not-held` and `version-moved`.
  - **Doc ID values** are the title's form: `#<docNumber> v<fileVersion>`, for example `#1234 v2.1`.
  - **The property's name** is exactly `Healdan Doc ID`.
- **Device ids** are 1–64 characters of `[A-Za-z0-9-]`. The helper makes a random UUID.
- **Comments.** The attribute is named `comments` and labelled `Comments`. It's a string of at most 4000 characters, at position 6, after Subclass; `legacydocnum` and the email attributes move down one.
- **Stamped formats:** `docx`, `docm`, `dotx`, `dotm`, `odt` and `ott`.
- **The helper's files.**

  | | macOS | Linux |
  |---|---|---|
  | Support folder | `~/Library/Application Support/Healdan Helper/` | `$XDG_DATA_HOME/healdan-helper/`, or `~/.local/share/healdan-helper/` |
  | Local copies | `<support>/Copies/`, one flat folder, mode 700 | the same |
  | Settings | `<support>/settings.json` | the same |
  | Device id | `<support>/device.json` | the same |
  | Channel info | `<support>/channel.json`, mode 600 | the same |
  | Log | `~/Library/Logs/Healdan Helper/helper.log` | `<support>/helper.log` |
- **Local copy names.**
  - **Form:** `<name> #<docNumber> v<fileVersion><ext>`, with ` (read-only)` before `<ext>` for a read-only copy.
  - **Characters:** `/`, `:`, `\` and control characters in `<name>` become `-`.
  - **Length:** `<name>` is cut so the whole file name is at most 255 bytes in UTF-8.
  - **Records:** the record beside a copy is `.<copy file name>.healdan.json`.
- **Keychain.** Service `Healdan Helper`, account the server's base URL (`https://host[:port]`).
- **The channel** (spec §10) is HTTP on `127.0.0.1`.
  - **Its info file:** `channel.json` holds `{"port": n, "secret": "<48 hex>", "pid": n}`.
  - **The secret:** every request but `GET /v1/ping` carries the header `X-Healdan-Secret`. A missing or wrong one is `403`.
  - **Bodies:** requests and answers are JSON. Its routes are in the table below.
- **Timings, from the trial:**
  - a save counts once the file has stopped changing for 1.2 s, its size checked every 300 ms;
  - the crash check polls the app's process every 5 s;
  - close detection polls lock files every 1 s, and a lock file must be gone twice in a row;
  - an Apple-event or hand-over link must open within 3 s, not counting the download.
- **The app's processes:** `soffice` on macOS, `soffice.bin` on Linux.
- **LibreOffice's lock file** is `.~lock.<copy file name>#`.
- **Python** for the extension uses only the standard library and UNO.
- **CI time.** State in each PR how many seconds it adds to `build`, to the `Test` jobs and to the new `Clients` job.

**The channel's routes** (Tasks 15, 18):

| Route | Body | Answer |
|---|---|---|
| `GET /v1/ping` | none | `{"ok": true}`; no secret needed |
| `POST /v1/link` | `{"url"}` | `{}` at once; the open runs in the background |
| `POST /v1/open-dialog` | `{"app"}` | `{"action": "opened" \| "local" \| "cancel"}` |
| `POST /v1/save-new` | `{"app", "name", "formats": ["odt","docx"], "path": null \| "<copy>"}` | `{"action": "save" \| "local" \| "cancel", "folderId", "name", "format", "properties", "tempPath"}`; `path` is set for Save as New Document |
| `POST /v1/file` | `{"tempPath", "folderId", "name", "format", "properties", "app", "replaces": null \| "<copy path>"}` | the copy's state |
| `POST /v1/saved` | `{"path"}` | the copy's state once the next confirmation arrives (at most 120 s), else its current state |
| `POST /v1/new-version` | `{"path"}` | `{}`; the next save of that copy goes up as `kind: version` |
| `POST /v1/properties` | `{"path"}` | `{"action": "saved" \| "cancel" \| "file"}`; `file` means the document isn't in Healdan, so the caller runs Save to Healdan |
| `POST /v1/closed` | `{"path"}` | `{}` |
| `POST /v1/state` | `{"path"}` | the copy's state, or `{"known": false}` |

A copy's state is `{"known": true, "docNumber", "title", "docRef", "readOnly", "path", "holdBy"}`, where `title` is the spec §5.7 title and `docRef` the doc ID value.

## Review Focus

These are the inputs most likely to hurt a person using M3 that no task's main tests reach. Each one has a test in the task named.

1. **A save whose answer is lost.** The server stores it, but the connection drops before the helper hears back, so the helper retries. The retry must not store a second version, nor be refused as `version-moved` and treated as someone else's change (Task 6's `saveId`, Task 13).
2. **Two saves close together.** Cmd+S twice within a second, or a save while the previous one is still going up. Each version reaches Healdan in order, or the newer one replaces a queued older one; nothing is lost, and the title ends on the last (Task 13).
3. **A save the app writes by write-then-rename,** or in pieces. The helper never sends a half-written file, and sends a write-then-rename save once (Task 13).
4. **A document name the file system can't hold,** such as `Re: A/B – Ünïcødé 📎` or a 300-character name. The copy's name is valid and at most 255 bytes, it's still found by its doc #, and saves never rename the document in Healdan (Task 12).
5. **Changes left on disk across a restart.** The user edits while the server is down and the computer restarts before the helper reaches Healdan. The helper finds the copy's record at start and sends the changes once the server answers; it never deletes them (Task 13).

## Spec corrections and calls this plan makes

The owner hears about 1, 2 and 7 when this plan is handed over. Task 20 folds them into the spec.

1. **Saves carry a `saveId`** (Review Focus 1). `NewVersion` takes an optional `saveId` (a UUID). The server remembers the last `saveId` stored for each document, and a save repeating it answers `201` with the document as it stands, storing nothing. The spec's preconditions alone would refuse such a retry as `version-moved`.
2. **The filing number is reserved after the API's own checks** (spec §9.7 says no number is "taken for a filing that fails before it's stored"). The API takes the number from Healdan's counter only once every API check has passed, immediately before CE's create. A failure inside CE's create leaves a gap in the doc #s, as a deleted document does; numbers are never reused (M1).
3. **Favourites are routes on `MeResource`** (`/v1/me/favorites`), not a resource of their own. M2 found that CXF lets a shorter root path swallow a longer one.
4. **Comments' words are matched in Healdan's own documents search.** The full-text index stores text properties only as exact fields, so after the full-text hits, `DocumentsOnlySearch` adds the readable documents whose Comments contain every word of `q`, case-insensitively, by SQL on `ld_document_ext`. It builds their hits as `DocNumberSearch` builds its one. The capability map records the cost: a `LIKE` scan.
5. **`hold.device.app`** is what the helper sends as `app` in the check-out's body (`"LibreOffice"`). A check-out from the old web page has none.
6. **The Open in App hook lives in GWT's common module** (`common.client.healdan.HealdanOpenInApp`), because `Util` is in common and can't see the frontend's `DocumentService`. The frontend registers the code that finds the doc # from CE's id.
7. **The `Clients` check should be required.** The new workflow reports one `Clients` job, which passes trivially when nothing under `clients/` changed. The owner adds it to master's ruleset after the first green run.
8. **`code-changed.sh` gets a second output,** `clients`. `code` becomes false for a pull request that changes only `clients/`, docs or both. `clients` is true for any change under `clients/`, and on every push.

## Conventions

- **Running Maven:** the M2 plan's container wrapper (`maven:3.9-eclipse-temurin-25`, the repo at `/src`, a copy of the Maven volume for this branch, CI's `settings.xml`, `touch /root/logicaldoc-dev.properties`).
  - `healdan-core`: `mvn -B -ntp -pl healdan-core test -Dtest=<Class>`.
  - `healdan-api`: `mvn -B -ntp -pl healdan-core install -DskipTests`, then `mvn -B -ntp -pl healdan-api test -Dtest=<Class>`.
- **Running the GUI** (Task 9): `mvn -B -ntp -pl logicaldoc-gui -am -DskipTests install` compiles GWT, which is slow. Check the result on a throwaway stack (the "Stack GUI check" recipe in the memory notes).
- **Running the helper's tests:** `dotnet test clients/helper/Healdan.Helper.sln`. Tests against a real stack carry `[Trait("Category", "Stack")]` and need `HEALDAN_STACK_URL`; without it they're skipped.
- **Running the extension's tests:** `python3 -m unittest discover -s clients/libreoffice/tests -v`.
- **Users and matters in server tests:** as M2. Use `fx.user("clerk")`, a store, client and matter with a `Documents` folder, and documents in `Documents`. Each document costs about 1.1 s, so keep fixtures small.
- **Commits:** one per task, in the repo's style: an imperative subject, a body that says why, and the `Co-Authored-By` trailer.

## PRs

All target `master`; stack each on the one before, as the stacked-PR convention says.

- **PR A, Tasks 1–8:** the server additions and the Docker flow test. Usable on its own: every endpoint is in the API page.
- **PR B, Task 9:** Open in App in the web page.
- **PR C, Tasks 10–14:**
  - the clients workflow, `code-changed.sh` and the helper's core with its tests;
  - no app yet, but the core runs against the stack in CI.
- **PR D, Tasks 15–19:** the helper app, its windows, the LibreOffice extension and the signed macOS package.
- **PR E, Task 20:** docs, CLAUDE.md, the capability map, the inventory, the spec's corrections and the roadmap.
- **Task 21:** the owner's acceptance of phase 1.
- **Phase 2, after the owner's semi-production server:**
  - **PR F, Task 22:** the Linux package and the Linux specifics;
  - **Task 23:** the owner's acceptance of phase 2.

---

### Task 1: The title's `#` form

**Files:**
- Modify: `healdan-core/src/main/java/com/logicaldoc/healdan/docnumber/DocNumberQuery.java` (`NUMBER`, the Javadoc)
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/docnumber/DocNumberQueryTest.java` (add), `healdan-api/src/test/java/com/logicaldoc/healdan/api/LookupTest.java` (add)

**Interfaces:**
- Produces: `DocNumberQuery.parse("#1234 v2.1")` is `(1234, 2, 1)`.

- [ ] **Step 1: Write the failing tests**

`DocNumberQueryTest`:
- `testSpaceBeforeV`: `parse("#1234 v2.1")` is `(1234, 2, 1)`, `parse("#1234  V2")` is `(1234, 2, null)`, `parse("# 1234 v2.1 ")` is `(1234, 2, 1)`.
- `testSpaceBeforeDotStillRefused`: `parse("#1234 .2")` is empty.

`LookupTest.testTitleForm`: with a document saved to `1.1`, `GET /v1/lookup?q=%231234%20v1.1` (its real number) answers `{docNumber, version: "1.1"}`.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -B -ntp -pl healdan-core test -Dtest=DocNumberQueryTest`
Expected: FAIL in `testSpaceBeforeV`.

- [ ] **Step 3: Change `NUMBER`**

Make it `^#\s*(\d+)(?:(?:\s*[vV]|\.)(\d+)(?:\.(\d+))?)?\s*$`, and update the Javadoc's list of forms.

- [ ] **Step 4: Run `DocNumberQueryTest`, `DocNumberLookupTest`, then (after installing core) `LookupTest`**

Expected: PASS.

- [ ] **Step 5: Commit** — "Accept the title bar's #1234 v2.1 form in lookups"

---

### Task 2: The Comments property

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/seed/ProfileCommentsSeed.java`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/search/CommentsMatch.java`
- Modify: `healdan-core/.../seed/SeedRunner.java` (`ORDER`), `healdan-core/.../HealdanConstants.java` (`ATTR_COMMENTS = "comments"`), `healdan-core/.../search/DocumentsOnlySearch.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/seed/ProfileCommentsSeedTest.java`, `healdan-core/src/test/java/com/logicaldoc/healdan/search/CommentsSearchTest.java`, `healdan-api/.../FormResourceTest.java` (add)

**Interfaces:**
- Produces:
  - the `comments` attribute on Healdan's profile template;
  - `CommentsMatch.documentIds(String q, long tenantId, int max)` returns `List<Long>`, newest first.

- [ ] **Step 1: Write the failing tests**

`ProfileCommentsSeedTest`:
- `testAddsComments`: after the seeds, the profile template has `comments`, labelled `Comments`, of type string, not mandatory, at position 6. `legacydocnum` is at 7, and `emailreceived` at 13.
- `testRunsOnceAndKeepsAnAdminsLabel`:
  1. Rename the label to `Notes`.
  2. Delete no marker, and run `runPending()`.
  3. The label is still `Notes`.

`CommentsSearchTest`:
- `testFindsAWordInComments`: two documents in `Documents`, one with Comments `Draft for the Brexit clause review`. The old UI's documents search (`DocumentsOnlySearch` with expression `brexit`) returns that one only.
- `testAllWordsMustMatch`: `brexit annex` finds nothing.
- `testUnreadableLeftOut`: a user without READ on the folder gets no hit.

`FormResourceTest.testCommentsField`: `GET /v1/folders/{Documents}/form` has a field `comments`, `type: string`, `required: false`, `editable: true`.

- [ ] **Step 2: Run them to see them fail**

Expected: the seed test fails (no attribute), and the search test finds nothing.

- [ ] **Step 3: Implement `ProfileCommentsSeed implements SeedStep`**

`name()` is `"profile-comments"`. Add it to `ORDER` after `api-settings`. It inserts the attribute as `ProfileSeed.put` does, and shifts the later attributes' positions by one.

- [ ] **Step 4: Implement `CommentsMatch.documentIds`, and add its hits in `DocumentsOnlySearch.internalSearch`**

- Split `q` on whitespace and drop Lucene operators (`AND`, `OR`, `NOT`, `+`, `-`, quotes, `*`).
- Each remaining word adds an `and lower(B.ld_stringvalue) like ?` with `%word%` (escape `%` and `_`) on `ld_document_ext B` where `B.ld_name = 'comments'`, joined to non-deleted `ld_document` rows in the tenant.
- Return up to `max` ids, newest first.
- In the search, add those documents' hits after the full-text hits, skipping duplicates, through `AccessChecks.visible` and the extension filter, and stop at `maxHits`.

- [ ] **Step 5: Run the three tests, `SeedRunnerTest`, `PluginLoadTest` and `SearchFolderServiceTest`**

Expected: PASS.

- [ ] **Step 6: Commit** — "Add a searchable Comments property"

---

### Task 3: Favourites

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/navigation/Favorites.java`
- Modify: `healdan-api/.../MeResource.java`, `healdan-api/src/main/resources/healdan/api/openapi.yaml`
- Create: `healdan-api/.../model/FavoriteView.java`, `healdan-api/.../model/NewFavorite.java`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/navigation/FavoritesTest.java`, `healdan-api/.../FavoritesResourceTest.java`

**Interfaces:**
- Produces:
  - `Favorites.get()`;
  - `List<Bookmark> list(long userId)`: the user's bookmarks minus matter workspaces, folders first, then by title;
  - `Bookmark addFolder(long userId, long folderId)` and `Bookmark addDocument(long userId, long documentId)`, which are idempotent and return the existing bookmark;
  - `void remove(long userId, long bookmarkId)`.
- Produces (API):
  - `GET /v1/me/favorites` answers a `Page` of `FavoriteView(long id, String type, Object item)`, where `type` is `"folder"` or `"document"` and `item` is a `FolderView` or a `DocumentView`;
  - `POST /v1/me/favorites` with `NewFavorite(Long folderId, Long docNumber)` answers `201` with a `FavoriteView`;
  - `DELETE /v1/me/favorites/{id}` answers `204`.

- [ ] **Step 1: Write the failing tests**

`FavoritesTest`:
- `testWorkspaceBookmarksAreMyMatters`: after `MyMatters.add(u, workspace)` and `addFolder(u, documents)`, `list(u)` has only `Documents`.
- `testAddIsIdempotent`: adding the same folder twice gives one bookmark.
- `testRemoveOnlyOwn`: removing another user's bookmark id throws `PermissionException`.

`FavoritesResourceTest`:
- `testListAddRemove`: as `clerk`, POST the folder, then a document by doc #. GET lists both, folder first. DELETE one, and GET lists one.
- `testUnreadableLeftOut`: a bookmark on a folder `clerk` loses READ on is missing from GET.
- `testAddUnreadable404`: POST a folder `clerk` can't read is `404`.
- `testSameAsOldUi`: the old UI's `/data/bookmarks.xml` for `clerk` (as `BookmarksDataServletTest` reads it) lists the same folder.

- [ ] **Step 2: Run them to see them fail**

Expected: compile errors.

- [ ] **Step 3: Implement `Favorites` on `BookmarkDAO`, beside `MyMatters`**

Use `MatterLocator` to tell a workspace bookmark apart. A document bookmark stores CE's document id, and the view maps it to the doc #.

- [ ] **Step 4: Add the three routes to `MeResource`, and the paths and schemas to `openapi.yaml`**

- [ ] **Step 5: Run the tests, `RouteCoverageTest` and `OpenApiFileTest`**

Expected: PASS.

- [ ] **Step 6: Commit** — "Add favourites to the API, on CE's bookmarks"

---

### Task 4: Search within a folder, and New Folder

**Files:**
- Modify: `healdan-api/.../SearchResource.java` (`within`), `healdan-api/.../FolderResource.java` (`@POST create`), `openapi.yaml`
- Create: `healdan-api/.../model/NewFolder.java`
- Test: `healdan-api/.../SearchResourceTest.java` (add), `healdan-api/.../FolderResourceTest.java` (add)

**Interfaces:**
- Produces:
  - `GET /v1/search?scope=folders|documents&q=…&within=<folderId>`;
  - `POST /v1/folders` with `NewFolder(Long parentId, String name)`, answering `201` with a `FolderView` and `Location: /api/v1/folders/{id}`.

- [ ] **Step 1: Write the failing tests**

`SearchResourceTest`:
- `testFoldersWithin`: matters A and B each have a folder `Drafts`. `scope=folders&q=draft&within=<A's workspace>` returns only A's.
- `testDocumentsWithin`: the same for a document's text.
- `testWithinUnreadable404`.
- `testWithinStore409`: a Store gives `not-browsable`.
- `testWithinIgnoredForMatters`: `scope=matters&within=…` is `400`.

`FolderResourceTest`:
- `testCreateSubfolder`: as `clerk` with ADD on `Documents`, POST `{parentId: Documents, name: "Board packs"}` is `201`. The folder inherits `Documents`' Class value and its permissions: `GET /v1/folders/{id}/form` shows Class with `source: inherited`.
- `testTopLevelRefusedForNonAdmin`: `parentId` the workspace is `409 refused`. As an admin it's `201`.
- `testSearchFolderRefused`: `409 filing-rule`.
- `testStoreRefused`: `409 not-browsable`.
- `testWithoutAdd403`.
- `testNameRules`: a 256-character name is `422 too-long`; `"<b>x</b>"` is stored cleaned, as `FolderServiceImpl` cleans it.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement `within`**

For `folders`, keep hits whose path runs through `within`. For `documents`, set CE's `SearchOptions.folderId` to `within` and `searchInSubPath` to true. Any other scope with `within` is `400`.

- [ ] **Step 4: Implement `create`**

Check ADD with `AccessChecks.allowed(user, Permission.ADD, parent)`. Then create the folder as `FolderServiceImpl.create` does: `FolderDAO.create(parent, vo, true, history)`, with a `FolderHistory` from the caller's session. Healdan's listeners refuse the structure rules; map their veto to the codes above.

- [ ] **Step 5: Run the tests and the contract tests**

Expected: PASS.

- [ ] **Step 6: Commit** — "Add search within a folder and New Folder to the API"

---

### Task 5: Devices

**Files:**
- Create: `healdan-core/src/main/resources/healdan/db/migration/{mariadb,hsqldb}/V4__devices.sql`
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/token/HoldDevice.java`, `HoldDeviceDAO.java`, `HibernateHoldDeviceDAO.java`, `DeviceHolds.java`
- Modify: `ApiToken.java` (`device`), `ApiTokens` (issue with a device), `healdan-api`'s `AuthResource`, `CheckoutResource`, `VersionResource`, `PropertiesResource`, `ApiViews` (hold), `model/SignIn.java`, `model/HoldView.java`, `openapi.yaml`
- Create: `healdan-api/.../model/DeviceView.java`, `model/CheckoutRequest.java`
- Test: `healdan-core/.../token/DeviceHoldsTest.java`, `healdan-api/.../DevicesTest.java`

**Interfaces:**
- Produces (schema):
  - `hd_api_token` gains `hd_device varchar(64)`, nullable;
  - new table `hd_hold_device`, with the `PersistentObject` columns and `hd_docid bigint not null unique`, `hd_device varchar(64) not null`, `hd_tokenid bigint`, `hd_app varchar(100)`, `hd_lastsaveid varchar(64)`, and a sequence as V3's.
- Produces (core): `DeviceHolds.get()` with:
  - `void record(Document doc, ApiToken token, String app)`;
  - `Optional<HoldDevice> current(Document doc)`, which is empty when the document isn't checked out or the record is older than its latest check-out by someone else;
  - `boolean heldHere(Document doc, ApiToken token)`, true for a hold with no device record;
  - `void move(Document doc, ApiToken token, String app)`;
  - `void clear(Document doc)`.
- Produces (API):
  - `SignIn(username, password, name, DeviceRef device)`, where `DeviceRef(String id)`;
  - `CheckoutRequest(Boolean move, String app)`, an optional JSON body;
  - `HoldView(kind, by, since, DeviceView device)`, where `DeviceView(String id, String name, String app)`, set only for the holder;
  - `409 held-elsewhere`, whose problem has `hold` and `lastSaved`.

- [ ] **Step 1: Write the failing tests**

`DeviceHoldsTest`:
- `testRecordAndHeldHere`;
- `testNewTokenSameDeviceStillHere`: token 2 with the same device id is `heldHere`;
- `testStaleRecordIgnored`: after release, then a check-out from the old UI, `current` is empty and `heldHere` is true.

`DevicesTest`, as `clerk`, signing in twice with devices `mac-1` and `linux-2`:
- `testCheckoutRecordsDevice`: a check-out from `mac-1` with `{"app": "LibreOffice"}`. GET as `clerk` from either device shows `hold.device` `{id: "mac-1", name: "Test device", app: "LibreOffice"}`. As another user, `hold.device` is null.
- `testOtherDeviceRefused`: from `linux-2`, each of these is `409 held-elsewhere`, with `hold.device.id` `mac-1`:
  - check-out;
  - save;
  - `DELETE …/checkout`;
  - `PATCH …/properties`.
- `testMove`: `POST …/checkout {"move": true}` from `linux-2` is `200`, the history has a check-out row, and now `mac-1` gets `held-elsewhere`.
- `testSameDeviceAfterSignInAgain`: revoke `mac-1`'s token, sign in again with device `mac-1`, and save: `201`.
- `testWebPageCheckoutAnyDevice`: a check-out by `DocumentManager.checkout` with no device lets both devices save. After the first save, the device is recorded.
- `testBadDeviceId`: device `"a b"` at sign-in is `422 invalid`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Write `V4__devices.sql` for both databases, and the entity, DAO and `DeviceHolds`**

- [ ] **Step 4: Wire the API**

- `AuthResource` validates the device id and stores it on the token.
- `CheckoutResource.checkout` reads the optional body and records the device. `move` checks that the caller holds the check-out from another device, then moves it and writes a `CHECKEDOUT` history row.
- Every write route answers `held-elsewhere` when `!heldHere`.
- Release clears the record. A check-in (`keepCheckedOut: false`) clears it.

- [ ] **Step 5: Run the tests, then `CheckoutResourceTest`, `SaveTest`, `PropertiesResourceTest` and the contract tests**

Expected: PASS; M2's tests are unchanged.

- [ ] **Step 6: Commit** — "Record which computer holds a check-out"

---

### Task 6: Save preconditions and `saveId`

**Files:**
- Modify: `healdan-api/.../model/NewVersion.java` (`ifVersion`, `ifHeld`, `saveId`), `VersionResource.save`, `DeviceHolds` (`lastSaveId`), `openapi.yaml`
- Test: `healdan-api/.../SavePreconditionsTest.java`

**Interfaces:**
- Produces:
  - `NewVersion(kind, name, comment, keepCheckedOut, String ifVersion, Boolean ifHeld, String saveId)`;
  - `409 version-moved`, whose problem carries the document;
  - `409 not-held`;
  - a repeated `saveId` answers `201` with the current document and stores nothing.

- [ ] **Step 1: Write the failing tests**

`SavePreconditionsTest`, as `clerk` with the document checked out:
- `testIfVersionMatches`: `201`.
- `testIfVersionStale`: a PATCH of a property moves `version` to `1.1`. A save with `ifVersion: "1.0"` is `409 version-moved`, and the problem's document has `version` `1.1`.
- `testIfHeldAfterRelease`: `DELETE …/checkout`, then a save with `ifHeld: true`, is `409 not-held`, and the document is still free.
- `testIfHeldCheckedInFromWebPage`: `DocumentManager.checkin(...)` by the old UI's path, then a save with `ifHeld`, is `409 not-held`.
- `testIfHeldSomeoneElse`: `409 checked-out` (M2's code wins for another user's hold).
- `testWithoutIfHeldM2Behaviour`: on a free document, a save without `ifHeld` checks it out and saves (`201`).
- `testSaveIdRepeated` (Review Focus 1):
  1. Save with `saveId` X: `fileVersion` `1.1`.
  2. Repeat the identical request: `201`, `fileVersion` still `1.1`, and `GET …/versions` has one `1.1` row.
- `testSaveIdNewAfterRepeat`: a save with `saveId` Y is `1.2`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement, under the document's `DocumentLocks` lock**

Check in this order, before any other work:
1. the `saveId` repeat;
2. `ifHeld`;
3. `ifVersion`.

Store the `saveId` in `hd_hold_device.hd_lastsaveid` after the save's version row is confirmed.

- [ ] **Step 4: Run the tests and `SaveTest`**

Expected: PASS.

- [ ] **Step 5: Commit** — "Add save preconditions and repeat-safe saves"

---

### Task 7: Stamping the doc ID, and thumbnails

**Files:**
- Create: `healdan-core/src/main/java/com/logicaldoc/healdan/stamp/DocIdStamper.java`
- Modify: `healdan-core/.../docnumber/DocNumberListener.java` (a reserved number), `healdan-api/.../DocumentResource.java` (`create`, `thumbnail`), `VersionResource.save`, `openapi.yaml`
- Test: `healdan-core/src/test/java/com/logicaldoc/healdan/stamp/DocIdStamperTest.java`, `healdan-api/.../StampingTest.java`, `healdan-api/.../ThumbnailTest.java`
- Test fixtures: `healdan-core/src/test/java/com/logicaldoc/healdan/stamp/StampFixtures.java`, which builds a `.docx` (a `DOCPROPERTY` field, both the simple and the complex form, in a footer part) and an `.odt` (a `text:user-defined` field in `styles.xml`'s footer) carrying the property, and copies without it

**Interfaces:**
- Produces:
  - `DocIdStamper.stamp(Path file, String extension, String value)` returns `boolean`, true when it changed the file;
  - `DocIdStamper.carriesProperty(Path file, String extension)` returns `boolean`;
  - `DocNumberListener.reserveNext()` returns `long`, and `DocNumberListener.useReserved(long number)` sets a thread-local that the next new document's `beforeStore` takes and clears;
  - `GET /v1/documents/{docNumber}/thumbnail` answers `image/png`.

- [ ] **Step 1: Write the failing tests**

`DocIdStamperTest`:
- `testDocxPropertyAndFields`: stamping `#12 v1.0` sets `docProps/custom.xml`'s `Healdan Doc ID` to `#12 v1.0`. The result text of both fields reads `#12 v1.0`, and every other zip entry is byte-identical.
- `testOdtPropertyAndField`: `meta.xml`'s `meta:user-defined` and the footer's `text:user-defined` read the value.
- `testWithoutPropertyUntouched`: `stamp` returns false, and the file is byte-identical.
- `testOtherFormat`: `.xlsx` returns false.

`StampingTest`:
- `testFilingStampsItsOwnNumber`: file a stamped `.docx` whose property says `#999 v9.9`. The stored file's property is `#<new docNumber> v1.0`, and so is the field text.
- `testSaveStampsTheNewVersion`: a save stores `#n v1.1`, and Save as New Version stores `#n v2.0`.
- `testSaveAsNewDocument`: filing the content of `#n` gives `#m v1.0`.
- `testNoPropertyByteForByte`: the content downloaded equals the bytes sent.
- `testRefusedFilingTakesNoNumber`: a filing refused with `422` (a missing required field) leaves the counter where it was.

`ThumbnailTest`:
- `testPng`: put a `thumb.png` resource for the current file version in the store, as `ThumbnailManager` names it. GET is `200 image/png` with those bytes.
- `testMissing404`.
- `testUnreadable404`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement `DocIdStamper`**

- Rewrite the zip entry by entry, keeping each entry's order and compression.
- `.docx`: change `docProps/custom.xml` (with the `vt:lpwstr` value) and the result runs of `DOCPROPERTY "Healdan Doc ID"` fields in `word/*.xml`.
- `.odt`: change `meta.xml`, `content.xml` and `styles.xml`.
- Parse with the JDK's DOM, with external entities off.

- [ ] **Step 4: Stamp in the API**

- **Filing:** after every API check, `reserveNext()`, stamp the temporary upload with `#<reserved> v1.0`, then `useReserved` and call CE's create.
- **Saves:** stamp with the number the save produces, from `version` (spec §9.7; M2 §6.6: a snapshot is minor + 1, a version is next major .0).
- **Thumbnails:** read `Store.getResourceName(doc, fileVersion, "thumb.png")` through `AccessChecks.readableDocument`.

- [ ] **Step 5: Run the tests, `FilingTest`, `SaveTest` and `DocNumberListenerTest`**

Expected: PASS.

- [ ] **Step 6: Commit** — "Stamp the doc ID into stored Word and LibreOffice files; serve thumbnails"

---

### Task 8: The Docker flow test

**Files:**
- Modify: `docker/tests/test-api.sh` (new steps after the existing save steps)

**Interfaces:**
- Consumes: Tasks 1–7's routes.

- [ ] **Step 1: Add the steps** (spec §14.1, in this order)

1. `GET /v1/search?scope=folders&q=Documents&within=<workspace>` finds `Documents`.
2. `POST /v1/folders` `{parentId: Documents, name: "M3 flow"}` is `201`.
3. `POST /v1/me/favorites {folderId}` is `201`, and GET lists it.
4. File a `.docx` whose `docProps/custom.xml` carries `Healdan Doc ID`, built with the script's Python helper. Unzip the downloaded content: the property is `#<n> v1.0`.
5. Sign in a second time with device `ci-2`. Check the document out with `{"app":"LibreOffice"}` from the first token; from the second, check-out is `409 held-elsewhere`.
6. Move the check-out from the second token. A save from the first, with `ifHeld: true`, is `409 held-elsewhere`.
7. A save from the second with `ifVersion` `"0.9"` is `409 version-moved`. With the right `ifVersion` and a `saveId` it's `201`; repeated, it's `201` with the same `fileVersion`.
8. `GET /v1/lookup?q=%23<n>%20v<fileVersion>` finds it.

- [ ] **Step 2: Run it on a fresh smoke stack**

Run: `docker/smoke-test.sh`
Expected: step 5c passes with the new steps listed.

- [ ] **Step 3: Commit** — "Prove M3's API additions through the real image"

---

### Task 9: Open in App in the web page

**Files:**
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/common/client/healdan/HealdanOpenInApp.java`
- Create: `logicaldoc-gui/src/main/java/com/logicaldoc/gui/frontend/client/healdan/HealdanDocumentOpener.java`
- Modify (upstream, CRLF): `Util.java` (`openEditWithOffice` ~827), `ContextMenu.java` (`applyOfficeSecurity` ~384), `DocumentToolbar.java` (~480, ~696); `messages.properties` (`editwithoffice`)
- Modify: `healdan-core/src/main/resources/plugin.xml` (a `Feature` extension `feature20`, `Feature_20`)
- Modify: wherever the frontend's Healdan code starts, to register the opener (the same place `HealdanLabels.load` is first called)
- Test: `healdan-core/.../PluginLoadTest` (still passes), plus a stack check

**Interfaces:**
- Produces:
  - `HealdanOpenInApp.handles(String fileName)` returns `boolean`, for spec §5.11's kinds, case-insensitive;
  - `HealdanOpenInApp.link(String docNumber, String host)` returns `String`, giving `healdan://open/<docNumber>?server=<host>`;
  - `HealdanOpenInApp.setOpener(java.util.function.LongConsumer opener)` and `HealdanOpenInApp.open(long docId)`;
  - `HealdanDocumentOpener.register()` finds the document with `DocumentService.getById`, opens the link with `WindowUtils.openUrl`, and shows an English notice: "If nothing opened, install the Healdan Helper", linked to `https://docs.healdan.com/user/office/installing-the-helper/`.

- [ ] **Step 1: Make the edits**

- **`Util.openEditWithOffice`** becomes `HealdanOpenInApp.open(docId);`. Remove the `ldedit:` URL, which carried the session id.
- **`ContextMenu` and `DocumentToolbar`** enable the item for one document with `Feature.enabled(Feature.OFFICE)`, the Download permission and `HealdanOpenInApp.handles(fileName)`. Drop the Write and unlocked conditions, and drop the Write check in the click handler.
- **`messages.properties`:** `editwithoffice=Open in App`.
- **`plugin.xml`:** the `feature20` extension.

- [ ] **Step 2: Check the encodings, and that nothing else changed in the upstream files**

Run: `file` on each upstream file, `git diff --stat`, and the ASCII check.
Expected: CRLF kept, and only the lines above changed.

- [ ] **Step 3: Compile the GUI, and run `PluginLoadTest` and `ApiPluginXmlTest`**

Run: `mvn -B -ntp -pl logicaldoc-gui -am -DskipTests install`, then the tests.
Expected: BUILD SUCCESS, and PASS.

- [ ] **Step 4: Check on a throwaway stack**

Right-click a `.docx` that `clerk` can only download. Open in App is enabled, the browser offers to open a `healdan:` link, and the page's address has no session id. On a `.pdf`, Open in App is disabled.

- [ ] **Step 5: Commit** — "Open documents in their app from the web page"

---

### Task 10: The clients workflow and the helper's solution

**Files:**
- Create: `clients/helper/global.json` (the .NET 10 SDK), `clients/helper/Directory.Build.props` (`Nullable` on, `TreatWarningsAsErrors` on), `clients/helper/Directory.Packages.props` (central package versions)
- Create: `clients/helper/Healdan.Helper.sln`, `clients/helper/Healdan.Helper.Core/Healdan.Helper.Core.csproj` (`net10.0`), `clients/helper/Healdan.Helper.Core.Tests/Healdan.Helper.Core.Tests.csproj`
- Create: `clients/libreoffice/tests/__init__.py` (empty, so discovery works)
- Create: `.github/workflows/clients.yml`
- Modify: `.github/scripts/code-changed.sh` (the `clients` output; correction 8), `.github/scripts/tests/test_code_changed.py`, `.github/workflows/build-logicaldoc.yml` and `docker-image.yml` (no change in logic: they already gate on `code`)
- Test: `.github/scripts/tests/test_code_changed.py`

**Interfaces:**
- Produces:
  - `code-changed.sh` writes `code=` and `clients=`;
  - `clients.yml` has a job named `Clients`.
    - It runs `dotnet test clients/helper/Healdan.Helper.sln` on `macos-15` and `ubuntu-24.04`, and the extension's `unittest` on `ubuntu-24.04`.
    - It passes without running when `clients` is false.

- [ ] **Step 1: Write the failing tests in `test_code_changed.py`**

- `test_clients_only_is_not_code`: `clients/helper/x.cs` gives `code=false`, `clients=true`.
- `test_clients_and_java_is_both`.
- `test_java_only_is_not_clients`.
- `test_push_is_both`.

- [ ] **Step 2: Run them to see them fail**

Run: `python3 -m unittest discover -s .github/scripts/tests -v`

- [ ] **Step 3: Update `code-changed.sh`, keeping its no-rename diff and its `src/` Markdown rule**

- [ ] **Step 4: Create the solution, the projects and `clients.yml`**

The core project has one placeholder test, `SolutionBuilds`, so CI has something to run until Task 11. In `clients.yml`, `actions/setup-dotnet` reads `global.json`.

- [ ] **Step 5: Run everything locally**

Run: `python3 -m unittest discover -s .github/scripts/tests -v`, `dotnet test clients/helper/Healdan.Helper.sln`, `python3 -m unittest discover -s clients/libreoffice/tests -v`
Expected: PASS.

- [ ] **Step 6: Commit** — "Add the clients workflow, and split CI by client and server changes"

---

### Task 11: The API client

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Api/HealdanApi.cs`, `Api/ApiException.cs`, `Api/Models.cs` (records for the JSON the helper reads), `Api/Problem.cs`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Api/HealdanApiTests.cs` (against an in-process fake `HttpMessageHandler`), `Api/ContractTests.cs`

**Interfaces:**
- Produces: `HealdanApi(Uri server, HttpMessageHandler? handler = null)`, with a `Token` property and these methods:
  - `Task<ServerInfo> ServerAsync()`;
  - `Task<string> SignInAsync(string user, string password, string deviceName, string deviceId)`;
  - `Task SignOutAsync()`;
  - `Task<DocumentView> DocumentAsync(long n)`;
  - `Task<DocumentView> CheckOutAsync(long n, string app, bool move = false)`;
  - `Task<DocumentView> ReleaseAsync(long n)`;
  - `Task DownloadAsync(long n, string path, string? version = null)`;
  - `Task<DocumentView> SaveAsync(long n, string path, SaveRequest request)`, where `SaveRequest(string Kind, bool KeepCheckedOut, string Name, string IfVersion, bool IfHeld, string SaveId)`;
  - `Task<DocumentView> FileAsync(string path, NewDocument request)`, where `NewDocument(long FolderId, string Name, JsonObject Properties, bool CheckOut)`;
  - `Task<Page<T>> GetPageAsync<T>(string relativePath)`;
  - `Task<JsonElement> GetJsonAsync(string relativePath)`;
  - `Task<JsonElement> PatchPropertiesAsync(long n, JsonElement values)`.
- Errors: a problem answer throws `ApiException(int Status, string Code, string Detail, JsonElement Body)`.
- Produces: `HealdanApi.RequireHttps(Uri server)`, which throws for `http` unless the host is `localhost`, `127.0.0.1` or `::1` (spec §3.3).

- [ ] **Step 1: Write the failing tests**

`HealdanApiTests`:
- `SendsBearerToken`.
- `SaveSendsMultipartWithNameAndPreconditions`: a `version` JSON part with `ifVersion`, `ifHeld`, `saveId` and `name`, and a `file` part.
- `ProblemBecomesApiException`: `409` `held-elsewhere` gives `Code == "held-elsewhere"`.
- `RefusesPlainHttp`: `http://healdan.example` throws, and `http://localhost:8093` doesn't.

`ContractTests`:
- `EveryCallIsInTheContract`: each route the client calls (a table in the test) exists in `healdan-api/src/main/resources/healdan/api/openapi.yaml`, read from the repository with `YamlDotNet` as a test-only dependency.
- `EveryFieldReadIsInTheSchema`: each record property in `Models.cs` matches a property of the schema named in its `[Schema("…")]` attribute.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement `HealdanApi`** with `System.Text.Json`'s camelCase options; the file part is read into memory before sending (the app may write the file meanwhile).

- [ ] **Step 4: Run them**

Expected: PASS.

- [ ] **Step 5: Commit** — "Add the helper's API client, checked against the contract"

---

### Task 12: Local copies

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Copies/CopyNames.cs`, `Copies/CopyRecord.cs`, `Copies/CopyStore.cs`, `HelperPaths.cs`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Copies/CopyNamesTests.cs`, `CopyStoreTests.cs`

**Interfaces:**
- Produces:
  - `HelperPaths.For(string? root = null)`, which gives `Support`, `Copies`, `Settings`, `Device`, `Channel` and `Log` per Global Constraints, and takes a `root` for tests;
  - `CopyNames.FileName(string documentName, long docNumber, string fileVersion, bool readOnly)` returns `string`;
  - `CopyNames.Title(string documentName, long docNumber, string fileVersion, bool readOnly)` returns `string`, giving `Lease agreement #1234 v2.1` and ` (read-only)`;
  - `CopyNames.DocRef(long docNumber, string fileVersion)` returns `string`, giving `#1234 v2.1`;
  - `CopyRecord`, with `DocNumber`, `DocumentName`, `Version`, `FileVersion`, `ReadOnly`, `CheckedOutHere`, `ConfirmedHash` (SHA-256 hex), `PendingKind`, `App` and `LastSaveId`;
  - `CopyStore(HelperPaths paths)`, with:
    - `(string Path, CopyRecord Record) Create(DocumentView doc, bool readOnly, string app)`;
    - `void Save(CopyRecord r)`;
    - `IReadOnlyList<(string Path, CopyRecord Record)> All()`;
    - `(string, CopyRecord)? Find(string path)`;
    - `string Rename(string path, string newFileVersion)`, which moves the copy and its record;
    - `void Delete(string path)`, which removes only that copy, its record and its lock files;
    - `bool HasUnconfirmedChanges(string path)`.

- [ ] **Step 1: Write the failing tests**

`CopyNamesTests`:
- `PlainName`: `("Lease agreement.docx", 1234, "2.1", false)` gives `Lease agreement #1234 v2.1.docx`.
- `ReadOnly`: `… #1234 v2.1 (read-only).docx`.
- `UnsafeCharacters` (Review Focus 4): `Re: A/B – Ünïcødé 📎.odt` gives `Re- A-B – Ünïcødé 📎 #7 v1.0.odt`.
- `TooLong` (Review Focus 4): a 300-character name gives a file name of at most 255 UTF-8 bytes that still ends ` #7 v1.0.odt`, without cutting a surrogate pair.
- `TitleAndDocRef`.

`CopyStoreTests`:
- `RecordSurvivesReload`: `Create`, then a new `CopyStore` on the same root finds it.
- `FolderIsPrivate`: on Unix, `Copies` is mode 700.
- `DeleteLeavesOthers`: two copies; deleting one leaves the other and its record.
- `UnconfirmedChanges`: write to the copy, and `HasUnconfirmedChanges` is true; after `ConfirmedHash` is updated, it's false.
- `RenameMovesRecord`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement**

Records are written to a temporary file in the same folder and renamed over the old one.

- [ ] **Step 4: Run them**

Expected: PASS.

- [ ] **Step 5: Commit** — "Add the helper's local copies"

---

### Task 13: The open-save-close cycle

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Cycle/IOfficeApp.cs`, `Cycle/LibreOfficeApp.cs`, `Cycle/CopyCycle.cs`, `Cycle/SaveQueue.cs`, `Cycle/SaveWatcher.cs`, `Cycle/CloseWatcher.cs`, `Cycle/CycleEvents.cs`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Cycle/CopyCycleTests.cs` (with `FakeApi`, a `HttpMessageHandler` faking the server's state, and `FakeApp`, writing files and lock files), `Cycle/StackCycleTests.cs` (`[Trait("Category", "Stack")]`)
- Modify: `.github/workflows/docker-image.yml` (after the smoke test: `actions/setup-dotnet`, then `dotnet test … --filter Category=Stack` with `HEALDAN_STACK_URL` and the smoke user's credentials)

**Interfaces:**
- Produces: `IOfficeApp`, with these members:
  - `string Name` (`"LibreOffice"`);
  - `void Open(string path)`;
  - `bool IsRunning()`;
  - `bool LockExists(string path)`.

  `LibreOfficeApp` implements it with `open -a LibreOffice` or `libreoffice`, and the processes and lock file in Global Constraints.
- Produces: `CopyState(bool Known, long DocNumber, string Title, string DocRef, bool ReadOnly, string Path, string? HoldBy)`, the channel's copy state.
- Produces: `CopyCycle(HealdanApi api, CopyStore store, IOfficeApp app, string deviceName, ICycleEvents events)`, with:
  - `Task OpenAsync(long docNumber, bool readOnly, string? version = null)`, where a `version` always opens read-only;
  - `Task MoveHereAsync(long docNumber)`, for Edit Here Instead (`CheckOutAsync(n, app, move: true)`, then as `OpenAsync`);
  - `Task<CopyState> AdoptNewAsync(string tempPath, NewDocument request, string? replaces)`, where the next write counts as the starting point, not a save. With `replaces`, the original copy's check-out is released without saving, and its copy is deleted once the app has adopted the new file (spec §5.5);
  - `void MarkNextVersion(string path)`;
  - `Task<CopyState> WaitForConfirmationAsync(string path, TimeSpan max)`;
  - `Task ClosedAsync(string path)`;
  - `Task ResumeAsync()`, which runs at start to resend unconfirmed changes and offer crashed copies.
- Produces: `ICycleEvents`, with these members:
  - `Confirmed(string path, CopyState state)`;
  - `HeldElsewhere(…)`;
  - `CheckoutEnded(string path)`;
  - `Crashed(string path)`;
  - `Waiting(int pending)`;
  - `SignInNeeded()`;
  - `Error(string message)`.

  The app (Task 16) shows them.

- [ ] **Step 1: Write the failing tests** (`CopyCycleTests`, each against `FakeApi` and `FakeApp`)

- `OpenChecksOutDownloadsAndLaunches`: the copy's name and record are right, and the app was asked to open it.
- `OpenReadOnlyWhenHeldByOther`: the hold is someone else's, so there's no check-out, the copy is read-only (mode 400), and `holdBy` is set.
- `OpenFailureReleases`: the download fails after the check-out, so the check-out is released and there's no copy left.
- `SaveSendsSnapshotWithPreconditions`: a write is sent once, with `kind: snapshot`, `keepCheckedOut: true`, `ifVersion` the record's `Version`, `ifHeld: true`, `name` the document's name and a new `saveId`. `Confirmed` fires with title `v1.1`.
- `WriteThenRenameSentOnce` (Review Focus 3): `FakeApp` writes a temporary file and renames it over the copy, and that's one upload.
- `PartialWriteNotSent` (Review Focus 3): a file growing every 200 ms for 2 s is sent once, after it stops.
- `TwoSavesInOrder` (Review Focus 2): write A, then B while A's upload is held open. The server receives A then B, or only B if A hadn't started, and never B then A. The final title is the last.
- `LostAnswerRetriedWithSameSaveId` (Review Focus 1): `FakeApi` stores the save but drops the answer. The retry carries the same `saveId`, and one version is created.
- `ServerDownKeepsCopy`: the server fails, `Waiting(1)` fires, and the copy isn't deleted. When the server comes back, it's sent.
- `RestartResends` (Review Focus 5): write while the server is down, dispose the cycle, then a new cycle's `ResumeAsync` sends it once the server answers.
- `AdoptIsNotASave`: after `AdoptNewAsync`, the app's first write sends nothing.
- `SaveAsNewDocumentReleasesOriginal`: `AdoptNewAsync(…, replaces: <copy of #5>)` files the new document, releases #5 with no save, and deletes #5's copy, leaving the new one.
- `OpenHeldElsewhereAsks`: the document is checked out to the user on another device, so `HeldElsewhere` fires with the device's name and when it was last saved, and nothing is checked out. `MoveHereAsync` then checks it out with `move: true` and opens it.
- `OlderVersionReadOnly`: `OpenAsync(n, false, "1.1")` makes no check-out, and downloads version 1.1 as a read-only copy.
- `NextVersion`: after `MarkNextVersion`, the save is sent as `kind: version`.
- `CloseChecksIn`: lock file gone twice gives a save with `keepCheckedOut: false` if there are changes, otherwise a release, then the copy is deleted.
- `CrashKeepsCopy`: the app isn't running and the lock is left, so `Crashed` fires. The copy and the check-out stay. `OpenAsync` of the same document reopens the kept copy, with no download.
- `HeldElsewhereKeepsCopy`: a save refused `held-elsewhere` or `not-held` raises `CheckoutEnded`, and the copy stays.
- `VersionMovedResentOnce`: refused `version-moved` with the same `fileVersion` and our hold, so it's re-read and resent once. With a changed `fileVersion`, it's `CheckoutEnded`.
- `UnauthorizedAsksSignIn`: a `401` raises `SignInNeeded`, and the save waits.

`StackCycleTests.OpenSaveCloseAgainstStack`:
1. Sign in as the smoke user.
2. Open the smoke matter's document, with `FakeApp`.
3. Save twice, then close.
4. Expect versions 1.1 and 1.2 and a free document.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement `SaveWatcher`, `SaveQueue`, `CloseWatcher` and `CopyCycle`**

- **`SaveWatcher`:** one `FileSystemWatcher` on the copies folder. It routes each event to its copy by the copy's file name, and treats a rename onto the copy's name as a write.
- **Timings:** as Global Constraints.
- **`SaveQueue`:** one queue per copy. An older save that hasn't started is replaced by a newer one.

- [ ] **Step 4: Run the tests, then the stack test against a local smoke stack**

Run: `dotnet test clients/helper/Healdan.Helper.sln`, then `HEALDAN_STACK_URL=http://localhost:8080 dotnet test … --filter Category=Stack`
Expected: PASS.

- [ ] **Step 5: Commit** — "Add the helper's open-save-close cycle"

---

### Task 14: Sign-in, keychain and device id

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Security/IKeychain.cs`, `Security/MacKeychain.cs` (Security.framework through P/Invoke, as the trial: `SecKeychainAddGenericPassword`, `SecKeychainFindGenericPassword`, `SecKeychainItemDelete`), `Security/SecretToolKeychain.cs`, `Security/DeviceIdentity.cs`, `Settings/HelperSettings.cs`, `Session/SignIn.cs`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Security/DeviceIdentityTests.cs`, `Session/SignInTests.cs` (with an in-memory `IKeychain`), `Security/MacKeychainTests.cs` (`[Trait("Category", "MacKeychain")]`, run by hand on a Mac only)

**Interfaces:**
- Produces:
  - `IKeychain`, with `string? Load(string account)`, `void Save(string account, string secret)` and `void Delete(string account)`;
  - `DeviceIdentity.Load(HelperPaths p)` returns `(string Id, string Name)`: the id is made once, and the name is `Healdan Helper on <computer name>`;
  - `HelperSettings`, with `Server`, `AppFor(string extension)` and `Load`/`Save`;
  - `SignIn(HealdanApi api, IKeychain keychain, DeviceIdentity device)`, with:
    - `Task<bool> TryResumeAsync()`, which reads the token and checks `/me`;
    - `Task SignInAsync(string user, string password)`;
    - `Task SignOutAsync(Func<Task> flush)`.

- [ ] **Step 1: Write the failing tests**

- `DeviceIdIsStable`: two loads give the same id, which matches `^[A-Za-z0-9-]{1,64}$`.
- `SignInSendsDeviceAndStoresOnlyToken`: the request body has `device.id` and the name. The keychain holds the token. No file under the root contains the password.
- `ResumeWith401ClearsToken`.
- `SignOutFlushesThenRevokes`: the flush runs before `DELETE /auth/tokens/current`, and the keychain entry is gone.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run them, and `MacKeychainTests` on a Mac**

Run: `dotnet test … --filter "Category=MacKeychain"`
Expected: PASS.

- [ ] **Step 5: Commit** — "Add the helper's sign-in, keychain and device id"

---

### Task 15: The channel

**Files:**
- Create: `clients/helper/Healdan.Helper.Core/Channel/ChannelServer.cs`, `Channel/ChannelInfo.cs`, `Channel/IChannelHandler.cs`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Channel/ChannelServerTests.cs`

**Interfaces:**
- Produces:
  - `ChannelServer(HelperPaths p, IChannelHandler handler)`, with `Start()`, which binds `127.0.0.1` on a free port and writes `channel.json` (mode 600), and `Stop()`, which deletes it;
  - `IChannelHandler`, with `Task<JsonNode?> HandleAsync(string route, JsonObject body)`;
  - `ChannelInfo.TryForward(HelperPaths p, string route, JsonObject body, TimeSpan timeout)` returns `bool`.
- The routes and their bodies are the Global Constraints table.

- [ ] **Step 1: Write the failing tests**

- `PingNeedsNoSecret`.
- `MissingSecret403`.
- `WrongSecret403`.
- `InfoFileIsPrivate`: mode 600, holding the port, secret and pid.
- `UnknownRoute404`.
- `ForwardReachesRunningServer`.
- `ForwardFailsWhenNoneRunning`: false within the timeout.
- `LinkAnswersAtOnce`: `/v1/link` answers in under 200 ms even when the handler's open takes 10 s, which the trial needed for the Linux hand-over.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Implement with `HttpListener`**

- [ ] **Step 4: Run them**

Expected: PASS.

- [ ] **Step 5: Commit** — "Add the channel the add-ins call"

---

### Task 16: The helper app

**Files:**
- Create: `clients/helper/Healdan.Helper/Healdan.Helper.csproj` (Avalonia 12.1.4, `net10.0`, `OutputType` `WinExe`), `Program.cs`, `App.axaml`, `App.axaml.cs`, `Tray/TrayMenu.cs`, `Platform/MacPlatform.cs`, `Platform/Notifier.cs`, `Links/LinkHandler.cs`, `Handler/ChannelHandler.cs`, `Assets/tray.png`, `Assets/tray-light.png`
- Test: `clients/helper/Healdan.Helper.Core.Tests/Links/LinkParserTests.cs` (the parsing is in core: `Links/HealdanLink.cs`)

**Interfaces:**
- Consumes: Tasks 12–15.
- Produces:
  - `HealdanLink.TryParse(string url, out long docNumber, out bool readOnly, out string? server)` returns `bool`;
  - `LinkHandler.Handle(string url)`, which refuses a link whose `server` isn't the signed-in server's host and port, and drops links while a window is waiting;
  - `ChannelHandler : IChannelHandler`, implementing the routes on `CopyCycle` and the windows of Task 17.

- [ ] **Step 1: Write the failing test**

`LinkParserTests`:
- `ParsesOpen`;
- `ParsesReadOnly`;
- `RejectsOtherVerbs` (`healdan://save/1`);
- `RejectsNonNumbers`.

- [ ] **Step 2: Run it to see it fail**

- [ ] **Step 3: Build the app**

- **macOS:** `MacOSPlatformOptions { ShowInDock = false }`. Links arrive through `IActivatableLifetime.Activated` with `ProtocolActivatedEventArgs`.
- **Focus:** after each window closes, `MacPlatform.GiveBackFocus()` sends `[NSApp hide:nil]`. When a window opens, `MacPlatform.Activate()` sends `activateIgnoringOtherApps:`.
- **The tray menu:** spec §5.1's items, with the documents open and any waiting saves from `CopyCycle`.
- **`Notifier`:** native notifications. On macOS that's `UNUserNotificationCenter` through a small Objective-C shim compiled into the bundle, or Avalonia's, if it has one by then; never AppleScript (the trial). A window when an answer is needed.
- **At start:** `SignIn.TryResumeAsync`, or the Sign In window; then `CopyCycle.ResumeAsync`, then the channel.

- [ ] **Step 4: Run the test, build the app, and run it by hand on the Mac against a local stack**

Check the tray menu, the sign-in, and a link from Safari.
Expected: PASS; the menu-bar icon shows, nothing appears in the Dock, and the link opens the document.

- [ ] **Step 5: Commit** — "Add the helper app: menu bar, links and channel"

---

### Task 17: The windows

**Files:**
- Create: `clients/helper/Healdan.Helper/Windows/SaveWindow.axaml(.cs)`, `OpenWindow.axaml(.cs)`, `PropertiesWindow.axaml(.cs)`, `HeldElsewhereWindow.axaml(.cs)`, `CrashWindow.axaml(.cs)`, `SignInWindow.axaml(.cs)`, `SettingsWindow.axaml(.cs)`, `Windows/PropertiesForm.cs` (builds controls from `/folders/{id}/form` fields), `Windows/PlacesPane.cs`
- Create: `clients/helper/Healdan.Helper.Core/Forms/FormModel.cs` (the form's rules, free of windows)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Forms/FormModelTests.cs`

**Interfaces:**
- Consumes: `HealdanApi` (Task 11), `CopyCycle` (Task 13).
- Produces: `FormModel(JsonElement form)`, with `Fields`, `Set(string name, JsonElement value)`, `bool CanSave(bool folderCanFile)`, and `JsonObject Values()`, holding only changed values (M2 ignores echoed ones).

- [ ] **Step 1: Write the failing tests** (`FormModelTests`)

- `RequiredBlocksSave`: a required field without a value means `CanSave` is false; with a value, and `canFile` true, it's true.
- `ReadOnlyFieldsNotSent`: Client and Matter are never in `Values()`.
- `SubclassNarrowedByClass`: choosing a Class leaves only that Class's Subclass options.
- `DateIsCalendarDate`: setting `2026-10-24` sends `2026-10-24T00:00:00Z`, and reading `2026-10-24T00:00:00Z` shows `2026-10-24` in any time zone (spec §9.10).
- `CommentsUpTo4000`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Build the windows to the mockups the owner reviewed** (spec §5.8)

**Save to Healdan:**
- the scope picker, with search `within`;
- the left pane;
- the breadcrumb, children and New Folder;
- the favourite star;
- the form, the format choice, and Save enabled by `CanSave`.

**Open from Healdan:**
- Recent Documents, Checked Out to Me and Favorites;
- `#` lookup on Enter;
- who holds each document, and when it was last saved, from the versions; never `hold.since` (spec §9.10);
- the thumbnail, properties and versions, with an older version opening read-only.

**The other windows** follow spec §5.8 and §5.10's wording.

- [ ] **Step 4: Run the tests, and check each window by hand against the mockups on the Mac**

Expected: PASS. Each window comes to the front over LibreOffice and gives the focus back when it closes.

- [ ] **Step 5: Commit** — "Add the helper's Save, Open, Properties and warning windows"

---

### Task 18: The LibreOffice extension

**Files:**
- Create: `clients/libreoffice/healdan/` (`description.xml` with the 2011 LibreOffice namespace and minimal version 26.2, `META-INF/manifest.xml`, `Addons.xcu`, `ProtocolHandler.xcu`, `Jobs.xcu`, `healdan.py`)
- Create: `clients/libreoffice/healdan_logic.py`, holding everything that doesn't need UNO (commands, the channel client, decisions)
- Create: `clients/libreoffice/build.sh`, which zips `healdan.oxt`
- Test: `clients/libreoffice/tests/test_logic.py`

**Interfaces:**
- Consumes: the channel (Task 15).
- Produces: `healdan_logic.py`, with:
  - `OPENS`, the tuple `(".uno:Open", ".uno:OpenFromWriter", ".uno:OpenFromCalc", ".uno:OpenFromImpress", ".uno:OpenFromDraw")`;
  - `intercepted(command) -> bool`;
  - `action(command, is_copy, has_location) -> str`, one of `"open-dialog"`, `"store-copy"`, `"local"` and `"save-new"`;
  - `formats(kind) -> list`;
  - `FILTERS`, the dict mapping each format to LibreOffice's filter name;
  - `Channel(support_dir)`, with `.call(route, body, timeout)`;
  - `is_copy(url, copies_dir) -> bool`;
  - `recent_entries_to_forget(urls, copies_dir) -> list`.

- [ ] **Step 1: Write the failing tests** (`test_logic.py`)

- `test_every_open_variant_intercepted`.
- `test_save_on_copy_stores`.
- `test_save_on_local_file_stays_local`.
- `test_save_on_new_document_saves_new`.
- `test_save_as_on_copy_is_new_document`.
- `test_formats_writer`: `["odt", "docx"]`.
- `test_formats_calc`.
- `test_is_copy_by_folder_not_name`: a file named `x #1 v1.0.odt` outside the copies folder isn't a copy.
- `test_recent_entries`: keeps only the URLs under the copies folder, with URL-encoded spaces.
- `test_channel_sends_secret`: against a local `http.server`.

- [ ] **Step 2: Run them to see them fail**

- [ ] **Step 3: Write `healdan.py` on top of `healdan_logic`**

**Attaching:**
- One dispatch interceptor per frame, compared with UNO `==`, never `id()`: the trial's double window.
- Attach on `OnNew`, `OnLoad`, `OnViewCreated`, `OnStartApp` and `onFirstVisibleTask`. Also find the Start Center's frame, by a desktop frame listener if LibreOffice has one, else by the trial's 1 s scan.
- The Healdan menu's context includes `com.sun.star.frame.StartModule`.

**Saving:**
- Save on a copy calls `model.store()`, which keeps the format and asks no "Keep current format" question, then waits on `/v1/saved` in a background thread, which sets the title and refreshes the footer.
- Save on a new document:
  1. `/v1/save-new`;
  2. `storeToURL` of the temporary file with the chosen filter;
  3. `/v1/file`;
  4. set the property;
  5. `storeAsURL` of the copy's path;
  6. set the title.

**Title, footer and recent list:**
- **Title:** `model.setTitle(title)`.
- **Footer refresh:** set the property, `getTextFields().refresh()`, then `setModified(False)` if the document had no unsaved changes before.
- **Insert Doc ID:** as the trial (a `docinfo.Custom` field in the current page style's footer).
- **Recent Documents:** on `OnUnload`, report `/v1/closed`, then remove the copies' entries from `/org.openoffice.Office.Histories/Histories/…['PickList']`, on that same event thread.

- [ ] **Step 4: Run the tests, build the `.oxt`, and install it by opening it in LibreOffice on the Mac**

Run: `python3 -m unittest discover -s clients/libreoffice/tests -v`, `clients/libreoffice/build.sh`
Expected: PASS, and the extension installs.

- [ ] **Step 5: Check by hand on the Mac** (spec §12.2's T2, T5 and T7)

- Every Open variant shows Open from Healdan, the Start Center's included.
- The way back to local takes one click.
- A `.docx` saves with no question.
- The title and footer move on.
- **A Healdan copy is gone from Recent Documents after closing.** This row was left open by the trial: check it in both the Start Center and File → Recent Documents.

If it fails, switch to opening copies through the extension with `PickListEntry=False` (spec §6.2) and record that in the decision log.

- [ ] **Step 6: Commit** — "Add the LibreOffice extension"

---

### Task 19: The macOS package and releases

**Files:**
- Create: `clients/helper/packaging/mac/Info.plist.in` (`LSUIElement`, `CFBundleURLTypes` `healdan`, `LSMinimumSystemVersion` 26.0, the version from the tag), `clients/helper/packaging/mac/entitlements.plist` (`allow-jit`, `allow-unsigned-executable-memory`, `disable-library-validation`), `clients/helper/packaging/mac/package.sh`
- Create: `clients/helper/Healdan.Helper/Platform/ExtensionInstaller.cs`
- Modify: `.github/workflows/clients.yml` (a `package-mac` job on `helper-v*` tags and on `workflow_dispatch`)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Platform/ExtensionVersionTests.cs` (the version compare is in core: `Platform/ExtensionVersion.cs`)

**Interfaces:**
- Produces:
  - `package.sh <version>`:
    1. a single-file `osx-arm64` publish with no `.pdb` files (the trial: any other file in `Contents/MacOS` breaks the signature);
    2. builds `Healdan Helper.app`, carrying `healdan.oxt` in `Contents/Resources`;
    3. signs every native library, then the binary, then the bundle (hardened runtime, timestamped);
    4. notarises and staples when `NOTARY_*` is set;
    5. makes `Healdan-Helper-<version>.dmg` with `hdiutil`.
  - `ExtensionInstaller.EnsureCurrent()`:
    1. reads the installed version (`unopkg list`);
    2. if it's older, tries `unopkg add --force` with `TMPDIR=/tmp` from the helper's own process;
    3. if that fails, opens the `.oxt` in LibreOffice for the user to accept, and says so.

    It records which way worked.
- Repository secrets, which the owner adds: `APPLE_DEVELOPER_ID_P12` (base64), `APPLE_DEVELOPER_ID_PASSWORD`, `APPLE_TEAM_ID` (`Z9G64Y4827`), `NOTARY_KEY` (base64 `.p8`), `NOTARY_KEY_ID` and `NOTARY_ISSUER`.

- [ ] **Step 1: Write the failing test**

`ExtensionVersionTests`:
- `OlderInstalledNeedsUpdate`;
- `ParsesUnopkgList`: from a sample of the trial's `unopkg list` output.

- [ ] **Step 2: Run it to see it fail, then implement it**

- [ ] **Step 3: Write `package.sh`, and run it on the Mac with the owner's Developer ID certificate**

Run: `clients/helper/packaging/mac/package.sh 0.1.0`
Expected: `codesign --verify --strict` passes on the app, and `spctl -a -vv` accepts it once notarised.

- [ ] **Step 4: Add `package-mac` to `clients.yml`**

It imports the certificate into a temporary keychain and attaches the `.dmg` to the tag's release. Without secrets it builds unsigned and uploads an artifact.

- [ ] **Step 5: Install from the `.dmg` on the Mac, and check `unopkg` from the helper**

Expected: the app runs from Applications. The extension is installed by `unopkg`, or by the one-click fallback, and the decision log records which.

- [ ] **Step 6: Owner: add the six secrets, and push a test tag `helper-v0.1.0-rc1`**

Expected: the release has a notarised `.dmg`.

- [ ] **Step 7: Commit** — "Package, sign and release the macOS helper"

---

### Task 20: Docs, records and the roadmap

**Files:**
- Create (spec §13.2):
  - `site/content/install/office-integration.md`;
  - `site/content/user/office/_index.md`, `installing-the-helper.md`, `saving-to-healdan.md`, `opening-documents.md`, `editing-and-versions.md`, `doc-id-footer.md` and `when-things-go-wrong.md`;
  - `site/content/developer/desktop-clients.md`.
- Modify (spec §13.2):
  - `site/content/developer/api.md`, `continuous-integration.md` and `building-from-source.md`;
  - `site/content/install/reverse-proxy.md` and `known-limitations.md`;
  - `site/content/user/documents/checking-out-and-checking-in.md` and `bookmarks.md`;
  - `site/content/user/finding-documents/finding-by-doc-number/index.md`;
  - `site/content/user/working-with-matters/properties/`.
- Modify: `CLAUDE.md` (the clients, their build and test commands, `code-changed.sh`'s outputs), `docs/reference/logicaldoc-ce-capability-map.md` (§2.3 Comments, §5.1 Open in App), `docs/reference/healdan-api-inventory.md` (bookmarks and `createFolder` to M3, with their endpoints), the spec (corrections 1–8; "changed in the build" notes), and `docs/ROADMAP.md` (M3's row and section: built, acceptance pending)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write the pages**

Follow the Writing Docs rules: Title Case, British spelling and Healdan's glossary. Screenshots come from the Mac: the Save and Open windows, and the prompts to expect.

- [ ] **Step 2: Run the docs checks**

Run: `python3 -m unittest discover -s .github/scripts/tests -v`, `hugo --source site --gc --minify --panicOnWarning`, `python3 .github/scripts/check-site-sources.py`, `python3 .github/scripts/check-site-links.py`
Expected: PASS.

- [ ] **Step 3: Commit** — "Document the helper and the LibreOffice extension"

---

### Task 21: The owner's acceptance, phase 1

- [ ] **Step 1: The owner installs the released `.dmg` on the Mac and runs spec §14.2 against their server over HTTPS**

The steps are: install; Save; edit and save; Open in App and the footer; Open by `#`; two computers; offline and crash; a `.docx` and Comments; Calc and Impress.

- [ ] **Step 2: Record the result in the roadmap and the spec, and list the punch list under Follow-ups**

---

### Task 22: Linux (phase 2: after the owner's semi-production server)

**Files:**
- Create: `clients/helper/packaging/linux/package.sh`, `clients/helper/packaging/linux/control.in` (`Depends: libsecret-tools, libnotify-bin, libreoffice-script-provider-python, libharfbuzz0b, libice6, libsm6, libfontconfig1, libx11-6`), `clients/helper/packaging/linux/healdan-helper.desktop` (`MimeType=x-scheme-handler/healdan;`, `Exec=/opt/healdan-helper/HealdanHelper %u`), `clients/helper/packaging/linux/autostart.desktop`, `clients/helper/packaging/linux/postinst`
- Modify: `clients/helper/Healdan.Helper/Program.cs` (`UseSystemHarfBuzz()` on Linux, the trial's crash fix), `Tray/TrayMenu.cs` (`tray-light.png` on Linux), `Links/LinkHandler.cs` (one helper: a second process forwards with `ChannelInfo.TryForward`, and waits up to 30 s while the first one is still starting, by the pid in `channel.json`), `Platform/Notifier.cs` (`notify-send`), `.github/workflows/clients.yml` (`package-linux`: amd64 and arm64 `.deb`s built in `ubuntu:26.04`)
- Test: `clients/helper/Healdan.Helper.Core.Tests/Links/SingleInstanceTests.cs`

- [ ] **Step 1: Write the failing test**

`SingleInstanceTests.SecondStartWhileFirstStartingForwards`: a `channel.json` whose pid is alive but whose port isn't listening yet makes `TryForward` retry until the port answers.

- [ ] **Step 2: Implement it and the packaging, and build both `.deb`s**

Expected: `dpkg-deb -I` and `-c` are right, and `desktop-file-validate` passes.

- [ ] **Step 3: On the owner's Ubuntu 26.04 machine, run spec §12.2's rows T1–T12 for U, against the semi-production server**

These are the rows the trial deferred.

**Watch for:** GNOME on Wayland not raising the windows (T9). If it doesn't, the helper shows a notification whose click brings the window up, as spec §12.4 says; record it.

- [ ] **Step 4: Update spec §12.5's U column, the Install page's Ubuntu part and Known Limitations**

- [ ] **Step 5: Commit** — "Package the helper for Ubuntu"

---

### Task 23: The owner's acceptance, phase 2

- [ ] **Step 1: The owner runs spec §14.2 on Ubuntu 26.04, against the semi-production server, with the released `.deb`**

- [ ] **Step 2: Mark M3 done in the roadmap, with the date, and move what's left to Follow-ups**

---

## Decision log (filled in while executing)

Every call made while building, in order. "Where" is the task or review that raised it; "Cost if wrong" is what undoing it would take.

| Date | Where | Decision | Why | Cost if wrong |
|---|---|---|---|---|

## Follow-ups

Filled in as the build finds them, under the same headings as M2's.
