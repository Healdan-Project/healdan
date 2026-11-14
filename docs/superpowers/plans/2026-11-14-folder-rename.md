# Renaming Folders in the User App: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People rename folders in the User app in place (F2, the tree's ⋯, the open folder's heading, and later the
right-click menus), through a new `PATCH /v1/folders/{id}` that keeps M1's rules.

**Architecture:** `healdan-api` gains `FolderRenames` (the checks and CE's `FolderDAO.store`, as CE's own rename) behind
`PATCH /v1/folders/{id}`, and `canRename` on every `FolderView`. In `healdan-web` the document rename field becomes a
shared `RenameField`, keyed by row key and place (`row`, `tree`, `heading`), which folder rows, the My Matters tree and
the open folder's heading use.

**Tech Stack:** Java 25, JAX-RS (CXF), JUnit 4 with `ApiTestCase`; React, TypeScript, React Aria Components through
`@healdan/ui`, TanStack Query, Vitest and Testing Library; Playwright; Hugo.

**Spec:** [`docs/superpowers/specs/2026-11-14-folder-rename-design.md`](../specs/2026-11-14-folder-rename-design.md)
(approved by the owner on 2026-11-14). Read it first.

## Global Constraints

- Every new source file opens with `SPDX-FileCopyrightText: 2026 The Healdan Project` and
  `SPDX-License-Identifier: LGPL-3.0-only` in its comment syntax (CLAUDE.md, Licence headers).
- No upstream (LogicalDOC) file changes (spec §7.1).
- `openapi.yaml` changes in the same commit as the endpoint or field it describes; `ApiTestCase` checks every answer
  against it and `RouteCoverageTest` fails on a route it lacks.
- The app never shows the server's own text: every problem code maps to a catalogue string (`frame/errors.ts`), and
  `frame/errors.test.ts` fails on a code in `openapi.yaml`'s `examples` list without `problems.<code>`.
- Screens import React Aria only through `@healdan/ui`; no `dangerouslySetInnerHTML`.
- Labels are US English in Title Case; docs prose is British (Writing Docs page).
- `{matter}` and `{matterLower}` in the catalogue are the firm's own word for a matter.
- Run `healdan-api`'s tests in one reactor, as other sessions share `~/.m2`:
  `mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true`
  (add `-Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` for one class). It needs `~/.m2/settings.xml` (a copy
  of `docker/maven-settings.xml`) and `~/logicaldoc-dev.properties` (an empty file works). It takes several minutes:
  run it in the background.
- The web checks, from `healdan-web/web`: `npm ci --ignore-scripts`, `npm run generate`, `npm run typecheck`,
  `npm run lint`, `npm test`, `npm run build` (Node 24, `.nvmrc`).
- A click on a folder row opens the folder, so tests focus rows (`element.focus()`, `fireEvent.keyDown`, Playwright's
  `locator.focus()`) rather than click them.
- Keep real hostnames, internal addresses and personal names out of commits and the PR.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: `canRename` on every `FolderView`

**Files:**
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/model/FolderView.java`
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/ApiViews.java` (`folder`, `subfolder`, ~lines 317-357)
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (`FolderView`, ~line 3043)
- Create: `healdan-api/src/test/java/com/logicaldoc/healdan/api/FolderRenameTest.java`
- Modify: `healdan-web/web/apps/user/src/test/fakeApi.ts` (`folder()`'s defaults)
- Modify: `healdan-web/web/apps/user/src/organising/testing.tsx` (`matterTop`)

**Interfaces:**
- Produces: `FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store, List<FolderRef> path, boolean canFile, boolean canRename)`;
  `ApiViews.canRename(String kind, boolean topLevel, Set<Permission> allowed, User caller)`; the TypeScript
  `S['FolderView']['canRename']: boolean`; `FolderRenameTest`'s fixture (fields `clerk`, `reader`, `outsider`, `boss`,
  `store`, `workspace`, `documents`, `letters`, `drafts`, `search`) for Task 2.

- [ ] **Step 1: Write the failing test**

Create `FolderRenameTest.java`:

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.store.FSStore;
import com.logicaldoc.core.store.Store;

/**
 * Renaming a folder, {@code PATCH /v1/folders/{id}}, and {@code canRename} on
 * every folder (folder rename spec sections 4.1 and 4.2).
 * <p>
 * Clerk reads, writes and renames in matter 998.1; Reader reads only;
 * Outsider has no grant. Boss is an admin. Documents is the matter's
 * top-level folder, with Letters and Drafts inside it; Recent is a top-level
 * search folder.
 */
public class FolderRenameTest extends ApiTestCase {

	private User clerk;

	private User reader;

	private User outsider;

	private User boss;

	private Folder store;

	private Folder workspace;

	private Folder documents;

	private Folder letters;

	private Folder drafts;

	private Folder search;

	@Before
	public void createLayout() throws Exception {
		FileUtils.deleteDirectory(((FSStore) Store.get()).getRoot());

		clerk = fx.user("clerk");
		reader = fx.user("reader");
		outsider = fx.user("outsider");
		boss = fx.user("boss", "admin");
		store = fx.store("Client Work");
		fx.grant(store, clerk.getUserGroup(), Permission.READ, Permission.WRITE, Permission.ADD, Permission.DOWNLOAD,
				Permission.RENAME);
		fx.grant(store, reader.getUserGroup(), Permission.READ);
		workspace = FolderDAO.get()
				.findById(fx.matter(fx.client(998, "Healdan"), 1, "Demo", store, "Documents").getFolderId());
		documents = FolderDAO.get().findByName(workspace, "Documents", Tenant.DEFAULT_ID, true).get(0);
		letters = fx.folder(documents, "Letters");
		drafts = fx.folder(documents, "Drafts");
		search = fx.searchFolder(workspace, "Recent");
	}

	// canRename

	@Test
	public void testCanRenameSaysWhatTheRenameWouldDo() throws Exception {
		assertTrue(folder(letters, clerk).path("canRename").booleanValue());
		assertFalse("no Rename permission", folder(letters, reader).path("canRename").booleanValue());
		assertFalse("top level", folder(documents, clerk).path("canRename").booleanValue());
		assertTrue("top level, as an admin", folder(documents, boss).path("canRename").booleanValue());
		assertFalse("top-level search folder", folder(search, clerk).path("canRename").booleanValue());
		assertTrue("top-level search folder, as an admin", folder(search, boss).path("canRename").booleanValue());
		assertFalse("a matter's own folder", folder(workspace, boss).path("canRename").booleanValue());
		assertFalse("a Store", folder(store, boss).path("canRename").booleanValue());
	}

	@Test
	public void testCanRenameInAFoldersChildren() throws Exception {
		Map<String, Boolean> top = childrenCanRename(workspace, clerk);
		assertEquals(Boolean.FALSE, top.get("Documents"));
		assertEquals(Boolean.FALSE, top.get("Recent"));
		assertEquals(Boolean.TRUE, childrenCanRename(workspace, boss).get("Documents"));
		Map<String, Boolean> inside = childrenCanRename(documents, clerk);
		assertEquals(Boolean.TRUE, inside.get("Letters"));
		assertEquals(Boolean.FALSE, childrenCanRename(documents, reader).get("Letters"));
	}

	@Test
	public void testCanRenameInRecentFolders() throws Exception {
		// A folder is recent once the caller files into it, as ListsTest's recent folders are
		fx.upload(letters, "first.docx", "Healdan first", clerk);
		awaitAsyncWork();
		ApiResponse response = get("/api/v1/folders/recent").token(signIn(clerk)).send();
		assertEquals(response.toString(), 200, response.status());
		JsonNode first = response.json().path("items").path(0);
		assertEquals(letters.getId(), first.path("id").asLong());
		assertTrue(first.path("canRename").booleanValue());
	}

	@Test
	public void testCanRenameInFavorites() throws Exception {
		String token = signIn(clerk);
		ApiResponse added = post("/api/v1/me/favorites").token(token).json(Map.of("folderId", letters.getId())).send();
		assertEquals(added.toString(), 201, added.status());
		ApiResponse listed = get("/api/v1/me/favorites").token(token).send();
		assertEquals(listed.toString(), 200, listed.status());
		assertTrue(listed.json().path("items").path(0).path("item").path("canRename").booleanValue());
	}

	// Helpers

	private JsonNode folder(Folder f, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + f.getId()).token(signIn(as)).send();
		assertEquals(response.toString(), 200, response.status());
		return response.json();
	}

	/** The subfolders' canRename by name */
	private Map<String, Boolean> childrenCanRename(Folder parent, User as) throws Exception {
		ApiResponse response = get("/api/v1/folders/" + parent.getId() + "/children?type=folder").token(signIn(as))
				.send();
		assertEquals(response.toString(), 200, response.status());
		Map<String, Boolean> found = new java.util.HashMap<>();
		for (JsonNode item : response.json().path("items"))
			found.put(item.path("name").textValue(), item.path("canRename").booleanValue());
		return found;
	}
}
```

Check `FavoritesResourceTest` for the body `POST /v1/me/favorites` takes (`folderId` or another field name) and copy
it; the assertion stays the same.

- [ ] **Step 2: Run the test to make sure it fails**

Run (in the background): `mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true -Dtest=FolderRenameTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: `canRename` is missing, so `booleanValue()` is false where true is expected.

- [ ] **Step 3: Add the field to the model, the views and the contract**

`FolderView.java`: add the component last and its `@param`:

```java
 * @param canFile whether the caller may file documents into it
 * @param canRename whether {@code PATCH /v1/folders/{id}} would rename it for
 *        the caller (folder rename spec section 4.2)
 */
public record FolderView(String type, long id, String name, String kind, MatterRef matter, StoreRef store,
		List<FolderRef> path, boolean canFile, boolean canRename) {
}
```

`ApiViews.folder`: replace the `canFile` lines and the `return` (keep everything above them):

```java
		String kind = folderKind(position);
		path.add(new FolderRef(f.getId(), f.getName(), kind));

		// One lookup of the caller's permissions serves both flags; a Store and a matter's own folder need none
		Set<Permission> allowed = renamableKind(kind) ? folders.getAllowedPermissions(f.getId(), caller.getId())
				: Set.of();
		boolean canFile = "folder".equals(kind) && position.depth() >= 1 && allowed.contains(Permission.WRITE);
		boolean canRename = canRename(kind, position.isMatterTopLevel(), allowed, caller);
		return Optional.of(new FolderView("folder", f.getId(), f.getName(), kind, matterOf(position),
				storeRef(position.storeId()), List.copyOf(path), canFile, canRename));
```

`ApiViews.subfolder`: replace the `canFile` line and the `return`:

```java
		Set<Permission> allowed = FolderDAO.get().getAllowedPermissions(f.getId(), caller.getId());
		boolean canFile = "folder".equals(kind) && allowed.contains(Permission.WRITE);
		// The parent is a matter's own folder: the subfolder is at the matter's top level
		boolean canRename = canRename(kind, "matter".equals(parent.kind()), allowed, caller);
		return new FolderView("folder", f.getId(), f.getName(), kind, parent.matter(), parent.store(),
				List.copyOf(path), canFile, canRename);
```

Add beside them (imports: `java.util.Set`, `com.logicaldoc.core.security.Permission`,
`com.logicaldoc.healdan.security.AccessChecks`, where missing):

```java
	private static boolean renamableKind(String kind) {
		return "folder".equals(kind) || "searchFolder".equals(kind);
	}

	/**
	 * @param kind the folder's kind, as its view has it
	 * @param topLevel whether it is directly in a matter's workspace
	 * @param allowed the caller's permissions on it
	 * @param caller the signed-in user, with its groups
	 * @return whether {@code PATCH /v1/folders/{id}} would rename it for the
	 *         caller (folder rename spec section 4.2): a folder or a search
	 *         folder, never a Store or a matter's own folder; with the Rename
	 *         permission; and directly in a matter's workspace, only for an
	 *         administrator
	 * @throws PersistenceException error at data layer
	 */
	static boolean canRename(String kind, boolean topLevel, Set<Permission> allowed, User caller)
			throws PersistenceException {
		if (!renamableKind(kind) || !allowed.contains(Permission.RENAME))
			return false;
		return !topLevel || AccessChecks.isAdmin(caller);
	}
```

`openapi.yaml`, `FolderView`: add `canRename` to `required` (after `canFile`) and the property after `canFile`:

```yaml
        canRename:
          type: boolean
          description: |
            Whether `renameFolder` would rename the folder for the caller: a
            folder or a search folder, never a Store or a matter's own folder;
            with the Rename permission; and, directly in a matter's workspace,
            only for an administrator.
```

(`renameFolder` arrives in Task 2; a description naming it is fine meanwhile.)

- [ ] **Step 4: Run the test to make sure it passes, then the rest of the module's**

Run: the same command as Step 2. Expected: PASS.
Then run the module's tests without `-Dtest` (in the background). Expected: PASS; a test that builds a `FolderView`
itself would fail to compile, so fix any by adding `canRename`.

- [ ] **Step 5: Keep the app's types and fakes in step**

`fakeApi.ts`, `folder()`: add `canRename: true,` after `canFile: true,`.
`organising/testing.tsx`: `matterTop` gets `canRename: false` beside `canFile: false`.

Run from `healdan-web/web`: `npm ci --ignore-scripts && npm run generate && npm run typecheck && npm test`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add healdan-api healdan-web/web/apps/user/src/test/fakeApi.ts healdan-web/web/apps/user/src/organising/testing.tsx
git commit -m "Say on every folder whether the caller may rename it"
```

---

### Task 2: `PATCH /v1/folders/{id}`

**Files:**
- Create: `healdan-api/src/main/java/com/logicaldoc/healdan/api/FolderRenames.java`
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/FolderResource.java`
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/model/RenameRequest.java` (its Javadoc)
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (the route, `RenameRequest`'s description, the
  `Problem` codes)
- Modify: `healdan-api/src/test/java/com/logicaldoc/healdan/api/FolderRenameTest.java`
- Modify: `healdan-web/web/packages/ui/src/strings/en-US.ts` (`problems.*` for the three new codes)

**Interfaces:**
- Consumes: Task 1's fixture in `FolderRenameTest`; `FolderResource.view(long, User)` (package-private static, exists);
  `DocumentLocks.withLock(long, String, Callable<T>)`; `StoredRows.get().children(long)` (`healdan-core`, gives
  `FolderRow(long id, long parentId, String name, …)`); `BasicPlane.holds(String)` (`com.logicaldoc.healdan.util`);
  `HTMLSanitizer.sanitizeSimpleText(String)` (`com.logicaldoc.util.html`); `Uploads.MAX_NAME_LENGTH` (255) and
  `Uploads.TOO_LONG` (`"too-long"`).
- Produces: `PATCH /api/v1/folders/{id}` with `{ "name": "…" }` → `200 FolderView`; problem codes `not-renamable`
  (409), `admins-only` (403), `name-taken` (409); `operationId: renameFolder`.

- [ ] **Step 1: Write the failing tests**

Add to `FolderRenameTest` (imports: `java.util.List`, `com.logicaldoc.core.folder.FolderEvent`,
`com.logicaldoc.healdan.support.DbSnapshot`, `static org.junit.Assert.assertNotNull`):

```java
	// Renaming

	@Test
	public void testRenamesAFolderInsideAMatter() throws Exception {
		ApiResponse response = rename(letters, "  Correspondence 2026  ", clerk);

		assertEquals(response.toString(), 200, response.status());
		assertEquals("Correspondence 2026", response.json().path("name").textValue());
		assertEquals("Correspondence 2026", FolderDAO.get().findById(letters.getId()).getName());
		awaitAsyncWork();
		Map<String, Object> history = jdbc().queryForMap(
				"select ld_event, ld_filenameold from ld_folder_history where ld_folderid = ? order by ld_id desc limit 1",
				letters.getId());
		assertEquals(FolderEvent.RENAMED.toString(), history.get("ld_event"));
		assertEquals("Letters", history.get("ld_filenameold"));
	}

	@Test
	public void testATopLevelFolderIsForAdmins() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(documents, "Files", clerk), 403, "admins-only");
		assertProblem(rename(search, "Latest", clerk), 403, "admins-only");
		before.assertUnchanged();

		assertEquals(200, rename(documents, "Files", boss).status());
		assertEquals(200, rename(search, "Latest", boss).status());
		assertEquals("Files", FolderDAO.get().findById(documents.getId()).getName());
	}

	@Test
	public void testAMattersOwnFolderAndAStoreAreNotRenamedHere() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(workspace, "Renamed", boss), 409, "not-renamable");
		assertProblem(rename(workspace, "Renamed", clerk), 409, "not-renamable");
		assertProblem(rename(store, "Renamed", boss), 409, "not-renamable");
		before.assertUnchanged();
	}

	@Test
	public void testWithoutTheRenamePermission() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(letters, "Post", reader), 403, "forbidden");
		before.assertUnchanged();
	}

	@Test
	public void testAFolderTheCallerCantReadIsNotFound() throws Exception {
		assertProblem(rename(letters, "Post", outsider), 404, "not-found");
		assertProblem(patch("/api/v1/folders/987654").token(signIn(clerk)).json(Map.of("name", "X")).send(), 404,
				"not-found");
	}

	@Test
	public void testNamesThatArentAllowed() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertField(rename(letters, "", clerk), "required");
		assertField(rename(letters, "   ", clerk), "required");
		assertField(rename(letters, "Smith/Jones", clerk), "invalid");
		assertField(rename(letters, "Smith\\Jones", clerk), "invalid");
		assertField(rename(letters, "Tab\there", clerk), "invalid");
		assertField(rename(letters, "Party 🎉", clerk), "invalid");
		// CE's folder DAO would take the tags out, so the name stored wouldn't be the name typed
		assertField(rename(letters, "Draft <v2>", clerk), "invalid");
		assertField(rename(letters, "<script>alert(1)</script>Notes", clerk), "invalid");
		assertField(rename(letters, "x".repeat(256), clerk), "too-long");
		before.assertUnchanged();
		assertProblem(patch("/api/v1/folders/" + letters.getId()).token(signIn(clerk)).json(Map.of()).send(), 400,
				"bad-request");
	}

	@Test
	public void testNamesThatAreKeptAsTyped() throws Exception {
		for (String name : List.of("Smith & Co", "Q&A 2026", "Notes (old)", "Lease – Draft", "x".repeat(255))) {
			ApiResponse response = rename(letters, name, clerk);
			assertEquals(name + ": " + response, 200, response.status());
			assertEquals(name, FolderDAO.get().findById(letters.getId()).getName());
		}
	}

	@Test
	public void testANameAnotherFolderHasIsRefused() throws Exception {
		DbSnapshot before = fx.snapshot();
		assertProblem(rename(letters, "Drafts", clerk), 409, "name-taken");
		assertProblem(rename(letters, "drafts", clerk), 409, "name-taken");
		before.assertUnchanged();
	}

	@Test
	public void testChangingOnlyTheCase() throws Exception {
		ApiResponse response = rename(letters, "LETTERS", clerk);
		assertEquals(response.toString(), 200, response.status());
		assertEquals("LETTERS", FolderDAO.get().findById(letters.getId()).getName());
	}

	@Test
	public void testTheSameNameWritesNothing() throws Exception {
		DbSnapshot before = fx.snapshot();
		ApiResponse response = rename(letters, " Letters ", clerk);
		assertEquals(response.toString(), 200, response.status());
		assertEquals("Letters", response.json().path("name").textValue());
		before.assertUnchanged();
	}

	@Test
	public void testANameOnlyADeletedFolderHadIsFree() throws Exception {
		FolderDAO.get().delete(drafts.getId(), fx.folderHistory(boss));
		ApiResponse response = rename(letters, "Drafts", clerk);
		assertEquals(response.toString(), 200, response.status());
	}

	@Test
	public void testTheWebPageRenamesOnItsSession() throws Exception {
		ApiResponse response = patch("/api/v1/folders/" + letters.getId()).webSession(signInWeb(clerk))
				.json(Map.of("name", "Post")).send();
		assertEquals(response.toString(), 200, response.status());
		assertNotNull(FolderDAO.get().findByName(documents, "Post", Tenant.DEFAULT_ID, true));
	}

	private ApiResponse rename(Folder f, String name, User as) {
		return patch("/api/v1/folders/" + f.getId()).token(signIn(as)).json(Map.of("name", name)).send();
	}

	private static void assertProblem(ApiResponse response, int status, String code) {
		assertEquals(response.toString(), status, response.status());
		assertEquals(code, response.json().path("code").textValue());
	}

	private static void assertField(ApiResponse response, String code) {
		assertEquals(response.toString(), 422, response.status());
		JsonNode first = response.json().path("fields").path(0);
		assertEquals("name", first.path("field").textValue());
		assertEquals(code, first.path("code").textValue());
	}
```

If `FolderDAO.delete(long, FolderHistory)` refuses under Healdan's rules or has another shape, delete Drafts as
`DocumentChangesTest` or `FolderResourceTest` deletes a folder. If `webSession` doesn't add `X-Healdan-Client: web`
itself, add `.header("X-Healdan-Client", "web")`, as `WebSessionRouteTest` does. If HSQLDB refuses `limit 1`, read
the newest row with `order by ld_id desc` and `queryForList(...).get(0)`.

- [ ] **Step 2: Run the tests to make sure they fail**

Run (in the background): `mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true -Dtest=FolderRenameTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: the Task 1 tests pass; the new ones FAIL with `405` (no `PATCH` on the route).

- [ ] **Step 3: Write `FolderRenames`**

```java
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.api;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.folder.FolderEvent;
import com.logicaldoc.core.folder.FolderHistory;
import com.logicaldoc.core.security.Permission;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.FieldError;
import com.logicaldoc.healdan.api.model.FolderRef;
import com.logicaldoc.healdan.api.model.FolderView;
import com.logicaldoc.healdan.rules.StoredRows;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.healdan.util.BasicPlane;
import com.logicaldoc.util.html.HTMLSanitizer;

/**
 * Renaming a folder (folder rename spec section 4.1), through CE's folder DAO
 * as CE's own rename does, so Healdan's listeners apply M1's rules as on every
 * route. The refusals people meet are checked here first, to give them codes
 * of their own, in this order: a folder the caller can't read, outside the
 * Stores or an alias ({@code 404}); a Store or a matter's own folder
 * ({@code 409 not-renamable}); without the Rename permission
 * ({@code 403 forbidden}); directly in a matter's workspace, unless the caller
 * is an administrator ({@code 403 admins-only}); a name that isn't allowed
 * ({@code 422 validation}); a name another live folder in the parent has,
 * without regard to case ({@code 409 name-taken}). A veto of Healdan's
 * listeners is {@code 409 refused}, as the ProblemMapper answers one.
 */
final class FolderRenames {

	/**
	 * One lock per parent folder, so two renames to one name can't both pass
	 * the check. {@link DocumentLocks} is a lock per id; the ids here are the
	 * parents'. It is this server's alone, as Healdan runs one node, and CE's
	 * New Folder doesn't take it.
	 */
	private final DocumentLocks parents = new DocumentLocks();

	/**
	 * @param id the folder's id
	 * @param requested the name sent, null if none
	 * @param caller the signed-in caller
	 * @return the folder, renamed; as it was for its own name
	 */
	FolderView rename(long id, String requested, Caller caller) {
		if (requested == null)
			throw ApiProblem.badRequest("Say the folder's new name with name.");
		User user = caller.user();
		String failure = "Can't rename folder #" + id;
		try {
			FolderView view = FolderResource.view(id, user);
			refuse(view, user);
			String name = name(requested);
			Folder folder = FolderDAO.get().findById(id);
			if (name.equals(folder.getName()))
				return view;
			return parents.withLock(folder.getParentId(), failure, () -> {
				FolderDAO folders = FolderDAO.get();
				// Read again, initialised as CE's rename reads it: another request may have renamed it meanwhile
				Folder current = folders.findById(id, true);
				if (name.equals(current.getName()))
					return FolderResource.view(id, user);
				if (taken(current, name))
					throw ApiProblem.conflict("name-taken",
							"Another folder in folder %d is called %s.".formatted(current.getParentId(), name));

				FolderHistory history = new FolderHistory();
				history.setFilenameOld(current.getName());
				history.setPathOld(folders.computePathExtended(id));
				history.setEvent(FolderEvent.RENAMED);
				history.setSession(caller.session());
				current.setName(name);
				// A veto of Healdan's listeners is answered 409 refused by the ProblemMapper
				folders.store(current, history);
				return FolderResource.view(id, user);
			});
		} catch (PersistenceException e) {
			throw new IllegalStateException(failure, e);
		}
	}

	private static void refuse(FolderView view, User user) throws PersistenceException {
		if ("store".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable", "A Store isn't renamed here.");
		if ("matter".equals(view.kind()))
			throw ApiProblem.conflict("not-renamable",
					"A matter's own folder takes the matter's name: an administrator changes it with Edit Matter.");
		if (!AccessChecks.allowed(user, Permission.RENAME, FolderDAO.get().findById(view.id())))
			throw ApiProblem.forbidden("You may not rename folder %d.".formatted(view.id()));
		if (topLevel(view) && !AccessChecks.isAdmin(user))
			throw new ApiProblem(403, "admins-only", "Only administrators can rename a matter's top-level folders.");
	}

	/** Directly in a matter's workspace: the step above it in its path is the matter's own folder */
	private static boolean topLevel(FolderView view) {
		List<FolderRef> path = view.path();
		return path.size() >= 2 && "matter".equals(path.get(path.size() - 2).kind());
	}

	/**
	 * @param requested the name sent
	 * @return the name to store: the one sent, without spaces at either end
	 * @throws ApiProblem {@code 422 validation} for the field {@code name}:
	 *         {@code required}, {@code too-long}, {@code invalid} (a slash, a
	 *         backslash, a control character, one the database can't store,
	 *         or markup CE's folder DAO would take out)
	 */
	static String name(String requested) {
		String sent = StringUtils.strip(requested);
		if (StringUtils.isBlank(sent))
			throw ApiProblem.validation(List.of(new FieldError("name", "required", "Give the folder a name.")));

		List<FieldError> errors = new ArrayList<>();
		if (sent.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c)))
			errors.add(new FieldError("name", "invalid", "A name can't contain /, \\ or control characters."));
		// CE's tables hold only the Basic Multilingual Plane, and strict MariaDB refuses the rest at the write
		else if (!BasicPlane.holds(sent))
			errors.add(new FieldError("name", "invalid",
					"A name can't contain emoji or other characters the database can't store."));
		// CE's folder DAO cleans the name of markup as it stores it, so a name it would change isn't the name typed
		else if (!sent.equals(HTMLSanitizer.sanitizeSimpleText(sent)))
			errors.add(new FieldError("name", "invalid", "A name can't contain HTML tags."));
		if (sent.length() > Uploads.MAX_NAME_LENGTH)
			errors.add(new FieldError("name", Uploads.TOO_LONG,
					"A name can't be longer than %d characters.".formatted(Uploads.MAX_NAME_LENGTH)));
		if (!errors.isEmpty())
			throw ApiProblem.validation(errors);
		return sent;
	}

	/** Whether another live folder in the folder's parent, an alias included, has the name, without regard to case */
	private static boolean taken(Folder folder, String name) {
		return StoredRows.get().children(folder.getParentId()).stream()
				.anyMatch(row -> row.id() != folder.getId() && name.equalsIgnoreCase(row.name()));
	}
}
```

If `FolderRef` has no `kind()` accessor of that name, use the record's own. If a name in
`testNamesThatAreKeptAsTyped` comes back `invalid` because the sanitiser changes it (whitespace, an entity), say so in
the task's report rather than loosen the rule: the owner hears of it.

- [ ] **Step 4: Add the route to `FolderResource`**

Add `import jakarta.ws.rs.PATCH;` and `import com.logicaldoc.healdan.api.model.RenameRequest;`, a field
`private final FolderRenames renames = new FolderRenames();`, and after `get`:

```java
	/**
	 * Renames a folder inside a matter (folder rename spec section 4.1). See
	 * {@link FolderRenames} for the checks and their order.
	 *
	 * @param id the folder's id
	 * @param request the new name
	 * @return the folder, renamed
	 */
	@PATCH
	@Path("{id: [0-9]+}")
	@Consumes(MediaType.APPLICATION_JSON)
	public FolderView rename(@PathParam("id") long id, RenameRequest request) {
		return renames.rename(id, request == null ? null : request.name(), Caller.current());
	}
```

`RenameRequest.java`'s Javadoc: "The body of {@code PATCH /v1/documents/{docNumber}} and {@code PATCH
/v1/folders/{id}}." and `@param name the new name; a document's without its extension keeps it`.

- [ ] **Step 5: Add the route and codes to `openapi.yaml`**

Under `/v1/folders/{id}:`, after `get:`'s block:

```yaml
    patch:
      operationId: renameFolder
      summary: Rename a folder
      description: |
        Renames a folder inside a matter through the same call the old UI's
        Rename uses, and returns it. Spaces at either end are taken off, and
        the same name changes nothing. A folder's `canRename` says whether
        this would rename it.

        The checks run in this order:

        1. `400 bad-request` for a body that isn't the JSON expected.
        2. `404 not-found` for a folder the caller can't read, one outside
           the Stores, and an alias, as `getFolder` answers.
        3. `409 not-renamable` for a Store, and for a matter's own folder,
           which takes the matter's name through Edit Matter, administrators
           included.
        4. `403 forbidden` without the Rename permission on the folder.
        5. `403 admins-only` for a folder directly in a matter's workspace,
           search folders included, unless the caller is an administrator.
        6. `422 validation` for the field `name`: `required`, `too-long`
           (over 255 characters) and `invalid` (a `/`, a `\`, a control
           character, one the database can't store, such as an emoji, or
           HTML tags, which the server would take out).
        7. `409 name-taken` when another folder in the same folder has the
           name, compared without regard to case. Changing only the case of
           the folder's own name works.
        8. `409 refused` when one of Healdan's rules refuses.
      tags: [Folders]
      parameters:
        - $ref: '#/components/parameters/FolderId'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/RenameRequest'
      responses:
        '200':
          description: The folder, renamed
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/FolderView'
        default:
          $ref: '#/components/responses/Problem'
```

`RenameRequest`: `description: The body of \`renameDocument\` and \`renameFolder\`.`, and the `name` property's
description: "The new name, at most 255 characters. A document's without its extension keeps it."

`Problem.code`'s `examples`: add `not-renamable`, `admins-only` and `name-taken` after `filing-rule`.

- [ ] **Step 6: Give the new codes the app's words**

`en-US.ts`, beside the other `problems.*`:

```ts
  'problems.not-renamable': "That can't be renamed here.",
  'problems.admins-only': 'Only administrators can do that.',
  'problems.name-taken': "There's already one with that name here.",
```

- [ ] **Step 7: Run the tests to make sure they pass**

Run: the Step 2 command. Expected: PASS. Then the module's whole suite without `-Dtest` (in the background), which
includes `RouteCoverageTest` and `OpenApiFileTest`. Expected: PASS.
From `healdan-web/web`: `npm run generate && npm run typecheck && npm test`. Expected: PASS (`frame/errors.test.ts`
now checks the three new codes).

- [ ] **Step 8: Commit**

```bash
git add healdan-api healdan-web/web/packages/ui/src/strings/en-US.ts
git commit -m "Rename folders through the API, keeping M1's rules"
```

---

### Task 3: One rename field, and F2 on folder rows

**Files:**
- Modify: `healdan-web/web/apps/user/src/organising/OrganisingProvider.tsx`
- Modify: `healdan-web/web/apps/user/src/organising/RenameField.tsx`
- Modify: `healdan-web/web/apps/user/src/actions/rename.ts`
- Modify: `healdan-web/web/apps/user/src/organising/useOrganisingTable.ts`
- Modify: `healdan-web/web/apps/user/src/actions/MoreMenu.tsx`
- Modify: `healdan-web/web/apps/user/src/views/listColumns.tsx`
- Modify: `healdan-web/web/apps/user/src/views/SearchView.tsx`, `healdan-web/web/apps/user/src/views/RecentView.tsx`
- Modify: `healdan-web/web/packages/ui/src/strings/en-US.ts`
- Modify: `healdan-web/web/apps/user/src/organising/testing.tsx`
- Create: `healdan-web/web/apps/user/src/organising/FolderRename.test.tsx`
- Create: `healdan-web/web/apps/user/src/actions/rename.test.ts` (or add to it, if it exists)

**Interfaces:**
- Consumes: Task 2's `PATCH /v1/folders/{id}` and its codes; `S['FolderView']['canRename']`.
- Produces (later tasks use these exact names):
  - `organising/OrganisingProvider.tsx`: `export type RenamePlace = 'row' | 'tree' | 'heading'`;
    `export interface Renaming { key: string; place: RenamePlace }`; `Organising.renaming: Renaming | undefined`;
    `startRename(key: string, place?: RenamePlace): void` (place defaults to `'row'`); `stopRename(focusBack?: boolean): void`.
  - `organising/RenameField.tsx`: `DocumentTitle({ document, className })` (as now);
    `FolderName({ folder, place, className }: { folder: S['FolderView']; place: RenamePlace; className?: string })`.
  - `actions/rename.ts`: `renameFolder(api, id, name): Promise<S['FolderView']>`;
    `isTopLevel(folder: Pick<S['FolderView'], 'path'>): boolean`;
    `whyNotRename(folder: Pick<S['FolderView'], 'canRename' | 'kind' | 'path'>, admin: boolean | undefined): string | undefined`;
    `folderRenameRefusal(error: unknown, typed: string): string`.
  - Focus goes back by place: a row is `[role="grid"] [data-row-key="<key>"]`, a tree folder
    `[role="treegrid"] [data-node-id="<id>"]` (Task 4 sets the attribute), the heading's ⋯
    `[data-rename-menu="<key>"] button` (Task 5 sets it).
  - Strings: `rename.taken`, `rename.adminsOnly`, `rename.matterFolder`, `rename.noPermission`, `folder.more`.

- [ ] **Step 1: Write the failing tests**

`organising/testing.tsx`: make the subfolders renamable and recorded. Add to `Written`
`renamedFolders: { id: number; name: string }[]`, start it as `[]`, and in `routes`:

```tsx
  let subfolders: S['FolderView'][] = [drafts]
  // …
    'GET /v1/folders/7/children': () => children([...subfolders, ...docs]),
    'PATCH /v1/folders/*': ({ params, body }: { params: string[]; body?: unknown }) => {
      const id = Number(params[0])
      const name = (body as { name: string }).name
      written.renamedFolders.push({ id, name })
      subfolders = subfolders.map((one) => (one.id === id ? { ...one, name } : one))
      return subfolders.find((one) => one.id === id) ?? folder({ id, name })
    },
```

`actions/rename.test.ts`:

```ts
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { folder } from '../test/fakeApi'
import { folderRenameRefusal, isTopLevel, whyNotRename } from './rename'

const store = { id: 2, name: 'Smoke', kind: 'store' as const }
const workspace = { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' as const }
const top = folder({ id: 111, name: 'Documents', path: [store, workspace, { id: 111, name: 'Documents', kind: 'folder' }] })
const inside = folder({ id: 112, name: 'Letters', path: [store, workspace, { id: 111, name: 'Documents', kind: 'folder' }, { id: 112, name: 'Letters', kind: 'folder' }] })

describe('whyNotRename', () => {
  it('says nothing when the server says the folder can be renamed', () => {
    expect(whyNotRename(inside, false)).toBeUndefined()
  })
  it("says a matter's own folder takes the matter's name", () => {
    expect(whyNotRename(folder({ kind: 'matter', canRename: false, path: [store, workspace] }), true)).toBe(
      "A matter's own folder takes the matter's name: an administrator changes it with Edit Matter… in the Classic Interface.",
    )
  })
  it("says a matter's top-level folders are for administrators, to someone who isn't one", () => {
    expect(isTopLevel(top)).toBe(true)
    expect(isTopLevel(inside)).toBe(false)
    expect(whyNotRename({ ...top, canRename: false }, false)).toBe("Only administrators can rename a matter's top-level folders.")
  })
  it('says it is a permission otherwise', () => {
    expect(whyNotRename({ ...top, canRename: false }, true)).toBe("You don't have permission to rename this folder.")
    expect(whyNotRename({ ...inside, canRename: false }, false)).toBe("You don't have permission to rename this folder.")
  })
})

describe('folderRenameRefusal', () => {
  const refused = (status: number, code: string, body: object = {}) => new ApiError(status, code, 'A server sentence.', { status, code, ...body })
  it('words each refusal from the catalogue, never the server', () => {
    expect(folderRenameRefusal(refused(409, 'name-taken'), 'Letters')).toBe("There's already a folder called Letters here.")
    expect(folderRenameRefusal(refused(403, 'admins-only'), 'X')).toBe("Only administrators can rename a matter's top-level folders.")
    expect(folderRenameRefusal(refused(409, 'not-renamable'), 'X')).toContain('Edit Matter…')
    expect(folderRenameRefusal(refused(403, 'forbidden'), 'X')).toBe("You don't have permission to rename this folder.")
    const fields = [{ field: 'name', code: 'invalid', detail: 'A server sentence.' }]
    expect(folderRenameRefusal(refused(422, 'validation', { fields }), 'X')).toBe("That name isn't allowed.")
    expect(folderRenameRefusal(refused(409, 'refused'), 'X')).toBe('That was refused.')
  })
})
```

If `t()` doesn't fill `{matter}` and `{matterLower}` from the labels by itself, check how `filing.chooseFolder` is
called and match it.

`organising/FolderRename.test.tsx`:

```tsx
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ApiError } from '@healdan/api'
import { folder, page } from '../test/fakeApi'
import { renderApp } from '../test/renderApp'
import { drafts, grid, row, routes } from './testing'

const field = (name = 'Drafts') => screen.findByRole('textbox', { name: `New Name of ${name}` })

/** F2 on a row, as the keyboard sends it to the focused row (a click on a folder's row would open it) */
async function pressF2On(name: RegExp) {
  const target = await row(name)
  target.focus()
  fireEvent.keyDown(target, { key: 'F2' })
}

describe('Renaming a folder in a list', () => {
  it('F2 on a folder row edits its name in place, all of it selected, and Enter saves it', async () => {
    const { all, written } = routes()
    const { api } = await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)

    const input = (await field()) as HTMLInputElement
    expect(document.activeElement).toBe(input)
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, 'Drafts'.length])
    await userEvent.keyboard('Final Drafts{Enter}')

    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(await row(/Final Drafts/)).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('f8'))
    // What shows folder names is asked for again
    expect(api.callsTo('GET /v1/folders/7/children').length).toBeGreaterThan(1)
    await waitFor(() => expect(api.callsTo('GET /v1/me/favorites').length).toBeGreaterThan(1))
  })

  it('Esc leaves the name as it was, with no call', async () => {
    const { all, written } = routes()
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    await field()
    await userEvent.keyboard('Something else{Escape}')
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
    expect(written.renamedFolders).toEqual([])
    await waitFor(() => expect(document.activeElement?.getAttribute('data-row-key')).toBe('f8'))
  })

  it('keeps the field open with a taken name said beside it', async () => {
    const { all } = routes({
      'PATCH /v1/folders/*': () => {
        throw new ApiError(409, 'name-taken', 'A server sentence that must not be shown.', { status: 409, code: 'name-taken' })
      },
    })
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    await field()
    await userEvent.keyboard('Letters{Enter}')
    expect(await screen.findByRole('alert')).toHaveTextContent("There's already a folder called Letters here.")
    expect(await field()).toBeTruthy()
  })

  it("F2 on a folder the person can't rename says why, and opens no field", async () => {
    const { all } = routes({ 'GET /v1/folders/7/children': () => page([{ ...drafts, canRename: false }]) })
    await renderApp('/folders/7', all)
    await pressF2On(/Drafts/)
    expect(await screen.findByText("You don't have permission to rename this folder.")).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('F2 renames a folder in Recent › Folders', async () => {
    const { all, written } = routes({ 'GET /v1/folders/recent': () => page([drafts]) })
    await renderApp('/recent/folders', all)
    const target = within(await grid()).getAllByRole('row').find((one) => one.getAttribute('data-row-key') === 'f8')!
    target.focus()
    fireEvent.keyDown(target, { key: 'F2' })
    await field()
    await userEvent.keyboard('Final Drafts{Enter}')
    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
  })

  it('F2 renames a folder in a Folders search', async () => {
    const { all, written } = routes({ 'GET /v1/search': () => page([folder({ id: 8, name: 'Drafts' })]) })
    await renderApp('/search?scope=folders&q=drafts', all)
    const target = within(await grid()).getAllByRole('row').find((one) => one.getAttribute('data-row-key') === 'f8')!
    target.focus()
    fireEvent.keyDown(target, { key: 'F2' })
    await field()
    await userEvent.keyboard('Final Drafts{Enter}')
    await waitFor(() => expect(written.renamedFolders).toEqual([{ id: 8, name: 'Final Drafts' }]))
  })
})
```

`GET /v1/children` answers and `GET /v1/search` answers have their own page shapes: if `page()` doesn't fit one, use
the helper the existing tests use for it (`children()` in `fakeApi.ts`, `hits()` in `SearchView.test.tsx`). If
`toHaveTextContent` isn't set up, use `.textContent` and `toContain`.

- [ ] **Step 2: Run them to make sure they fail**

Run from `healdan-web/web`: `npx vitest run apps/user/src/organising/FolderRename.test.tsx apps/user/src/actions/rename.test.ts`
Expected: FAIL: no field opens on a folder row; `renameFolder`, `whyNotRename` and the others aren't exported.

- [ ] **Step 3: Add the strings**

`en-US.ts`, after `'rename.tooLong'`:

```ts
  'rename.taken': "There's already a folder called {name} here.",
  'rename.adminsOnly': "Only administrators can rename a {matterLower}'s top-level folders.",
  'rename.matterFolder': "A {matterLower}'s own folder takes the {matterLower}'s name: an administrator changes it with Edit {matter}… in the Classic Interface.",
  'rename.noPermission': "You don't have permission to rename this folder.",
```

and after `'folder.empty'`: `'folder.more': 'More for {name}',` (Task 5 uses it).

- [ ] **Step 4: Add the folder's calls to `actions/rename.ts`**

```ts
export function renameFolder(api: Api, id: number, name: string): Promise<S['FolderView']> {
  return api.send('PATCH', apiPath`/v1/folders/${id}`, { name })
}

/** Directly in a matter's workspace: the step above it in its path is the matter's own folder */
export function isTopLevel(folder: Pick<S['FolderView'], 'path'>): boolean {
  return folder.path.at(-2)?.kind === 'matter'
}

/**
 * Why the person can't rename the folder, for F2 to say (folder rename spec 5.2); undefined when they can. The server
 * decides, in `canRename`; this says which of its reasons it is, from what the app already knows.
 */
export function whyNotRename(folder: Pick<S['FolderView'], 'canRename' | 'kind' | 'path'>, admin: boolean | undefined): string | undefined {
  if (folder.canRename) return undefined
  if (folder.kind === 'matter') return t('rename.matterFolder')
  if (isTopLevel(folder) && !admin) return t('rename.adminsOnly')
  return t('rename.noPermission')
}

/** What to tell the person of a refused folder rename, from the catalogue: `typed` is the name that was sent */
export function folderRenameRefusal(error: unknown, typed: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'name-taken') return t('rename.taken', { name: typed })
    if (error.code === 'admins-only') return t('rename.adminsOnly')
    if (error.code === 'not-renamable') return t('rename.matterFolder')
    if (error.code === 'forbidden') return t('rename.noPermission')
    const named = fieldErrors(error).get('name')
    if (named) return named
  }
  return messageFor(error)
}
```

- [ ] **Step 5: Key the provider's rename by row key and place**

In `OrganisingProvider.tsx`, replace the rename members of `Organising`:

```ts
/** Where a rename's field is: a list's row, the My Matters tree, or the open folder's heading */
export type RenamePlace = 'row' | 'tree' | 'heading'

/** What is being renamed: its row key (`d10388` for a document, `f45` for a folder) and where its field is */
export interface Renaming {
  key: string
  place: RenamePlace
}

  /** What is a field now, and where */
  renaming: Renaming | undefined
  startRename: (key: string, place?: RenamePlace) => void
  /** Ends the rename (saved or not) and gives the focus back where it began, unless `focusBack` is false (the person left the field) */
  stopRename: (focusBack?: boolean) => void
```

Replace `rowOf` with:

```ts
/** Where the focus goes back to once a rename is over: the row, the tree's folder, or the heading's ⋯ */
function renameOrigin({ key, place }: Renaming): HTMLElement | null {
  const found = globalThis.document
  if (place === 'row') return found.querySelector<HTMLElement>(`[role="grid"] [data-row-key="${key}"]`)
  if (place === 'tree') return found.querySelector<HTMLElement>(`[role="treegrid"] [data-node-id="${key.slice(1)}"]`)
  return found.querySelector<HTMLElement>(`[data-rename-menu="${key}"] button`)
}
```

and in the provider: `const [renaming, setRenaming] = useState<Renaming | undefined>()`, and:

```ts
    renaming,
    startRename: (key, place = 'row') => setRenaming({ key, place }),
    stopRename: (focusBack = true) => {
      const done = renaming
      setRenaming(undefined)
      // The field is gone once this has rendered; where the rename began is where the focus belongs, unless the person put it elsewhere
      if (focusBack && done) requestAnimationFrame(() => renameOrigin(done)?.focus())
    },
```

Update the doc comment above `OrganisingProvider`: "Renaming documents and folders, moving and deleting documents,
and making folders".

- [ ] **Step 6: Make the field shared**

Rewrite `RenameField.tsx` (keep the header):

```tsx
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import type { components } from '@healdan/api'
import { t } from '@healdan/ui'
import { invalidateDocumentLists } from '../actions/refresh'
import { extensionOf, folderRenameRefusal, renameDocument, renameFolder, renameRefusal } from '../actions/rename'
import type { Movable } from '../actions/batch'
import { useMe } from '../queries'
import { useApi } from '../services'
import { useOrganising, type RenamePlace } from './OrganisingProvider'
import styles from './organising.module.css'

type S = components['schemas']

/**
 * A document's title in a list: its name, or while it's being renamed (F2, or More › Rename) a field in its place
 * (spec 6.7). It keeps its extension, so the name without it is selected.
 */
export function DocumentTitle({ document, className }: { document: Movable; className?: string }) {
  const { renaming, stopRename } = useOrganising()
  const api = useApi()
  const me = useMe()
  if (renaming?.key !== `d${document.docNumber}` || renaming.place !== 'row') return <span className={className}>{document.name}</span>
  const extension = extensionOf(document.name)
  return (
    <RenameField
      name={document.name}
      selected={extension ? document.name.length - extension.length - 1 : document.name.length}
      save={(name) => renameDocument(api, document.docNumber, name)}
      refusal={(error) => renameRefusal(error, document.name, me.data?.id)}
      onDone={stopRename}
    />
  )
}

/**
 * A folder's name where it shows (a list's row, the My Matters tree, the open folder's heading), or while it's being
 * renamed there a field in its place (folder rename spec 5.3), with all of the name selected.
 */
export function FolderName({ folder, place, className }: { folder: S['FolderView']; place: RenamePlace; className?: string }) {
  const { renaming, stopRename } = useOrganising()
  const api = useApi()
  if (renaming?.key !== `f${folder.id}` || renaming.place !== place) return <span className={className}>{folder.name}</span>
  return <RenameField name={folder.name} selected={folder.name.length} save={(name) => renameFolder(api, folder.id, name)} refusal={folderRenameRefusal} onDone={stopRename} />
}

interface RenameFieldProps {
  /** The name as it is: the field's first text, and its label's */
  name: string
  /** How much of the name, from its start, is selected when the field opens */
  selected: number
  /** Saves the new name; a refusal throws */
  save: (name: string) => Promise<unknown>
  /** What to say of a refusal; `typed` is the name sent */
  refusal: (error: unknown, typed: string) => string
  /** The rename is over; `focusBack`: the field had the focus, which goes back where the rename began */
  onDone: (focusBack: boolean) => void
}

/**
 * Enter saves, Esc cancels, and leaving the field saves what was changed. A refusal keeps the field open with what is
 * wrong beside it. Whether it worked or not, what shows names is asked for again.
 */
function RenameField({ name, selected, save, refusal, onDone }: RenameFieldProps) {
  const queryClient = useQueryClient()
  const [text, setText] = useState(name)
  const [error, setError] = useState<string>()
  const input = useRef<HTMLInputElement>(null)
  const errorId = useId()
  // Enter saves and the field goes, which makes it lose the focus: that must not save again
  const finished = useRef(false)
  const saving = useRef(false)

  useEffect(() => {
    const field = input.current
    if (!field) return
    field.focus()
    field.setSelectionRange(0, selected)
  }, [selected])

  /** `fromKey`: Enter saved, so the focus is the field's to give back; a save on leaving leaves it where the person put it */
  async function submit(fromKey: boolean) {
    if (finished.current || saving.current) return
    const typed = text.trim()
    if (typed === name) return stop(fromKey)
    saving.current = true
    try {
      await save(typed)
      stop(fromKey)
    } catch (failure) {
      setError(refusal(failure, typed))
      if (fromKey) input.current?.focus()
    } finally {
      saving.current = false
      void invalidateDocumentLists(queryClient)
    }
  }

  function stop(focusBack = true) {
    finished.current = true
    onDone(focusBack)
  }

  return (
    <span className={styles.rename}>
      <input
        ref={input}
        className={styles.renameInput}
        value={text}
        aria-label={t('rename.field', { name })}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={(event) => {
          setText(event.target.value)
          setError(undefined)
        }}
        onKeyDown={(event) => {
          // Not the list's or the tree's: Space, Delete, the arrows and typing are the field's own while it has the focus
          event.stopPropagation()
          if (event.key === 'Enter') {
            event.preventDefault()
            if (text.trim() === '') setError(t('fields.required'))
            else void submit(true)
          } else if (event.key === 'Escape') {
            event.preventDefault()
            stop()
          }
        }}
        onBlur={() => {
          // Leaving the field with nothing changed, nothing in it, or a refusal showing is a cancel; with a new name, a save
          if (text.trim() === '' || error) stop(false)
          else void submit(false)
        }}
        // A press or a double-click in the field is the field's: it doesn't select the row, open it or open the tree's folder
        onPointerDown={(event) => event.stopPropagation()}
        onMouseDown={(event) => event.stopPropagation()}
        onClick={(event) => event.stopPropagation()}
        onDoubleClick={(event) => event.stopPropagation()}
      />
      {error && (
        <span id={errorId} role="alert" className={styles.renameError}>
          {error}
        </span>
      )}
    </span>
  )
}
```

`invalidateDocumentLists` asks again for every `['folders', …]`, `['search', …]`, `['favorites']`, `['documents', …]`
and `['matters', …]` query: the folder, its parent's list, the tree's open folders, breadcrumbs and Locations, Recent ›
Folders and My Favorites (spec §5.3).

- [ ] **Step 7: F2 on folder rows**

`useOrganisingTable.ts`: import `useMe` from `'../queries'` and `whyNotRename` from `'../actions/rename'`; add the
folders by key and change `undraggable` and `onRename`:

```ts
type Folder = components['schemas']['FolderView']
  // …
  const me = useMe()
  const folders = useMemo(() => new Map(rows.flatMap((row): [string, Folder][] => (row.type === 'folder' ? [[`f${row.id}`, row]] : []))), [rows])
  // …
      undraggable: organising.renaming?.place === 'row' ? organising.renaming.key : undefined,
      onRename: (key: string) => {
        const folder = folders.get(key)
        if (folder) {
          // A folder row is never selected: the focused one is renamed, whatever documents are selected
          const why = whyNotRename(folder, me.data?.admin)
          if (why) toast(why)
          else organising.startRename(key)
          return
        }
        const document = byKey.get(key)
        if (!document) return
        // Several selected means no one to rename; the focused row is the one when the selection is it or empty
        if (selection.size > 1) toast(t('rename.onlyOne'))
        else organising.startRename(key)
      },
```

Update the hook's doc comment: "F2 renames the row, a document or a folder".

`MoreMenu.tsx`: `organising.startRename(\`d${one.docNumber}\`)`.

`listColumns.tsx`: import `FolderName` beside `DocumentTitle`, and in `title.cell`:

```tsx
      {item.type === 'document' ? (
        <DocumentTitle document={item} className={styles.name} />
      ) : item.type === 'folder' ? (
        <FolderName folder={item} place="row" className={styles.name} />
      ) : (
        <span className={styles.name}>{item.display}</span>
      )}
```

`SearchView.tsx`: the Folders scope gets F2 too:

```tsx
              {...(documents ? organising.tableProps : scope === 'folders' ? { onRename: organising.tableProps.onRename } : {})}
```

`RecentView.tsx`, `RecentFolders`: add `const organising = useOrganisingTable(items ?? [], NO_SELECTION)` (with
`const NO_SELECTION: ReadonlySet<string> = new Set()` at module level, and `items` as that component names its list)
and pass `organising={{ onRename: organising.tableProps.onRename }}` to `RecentList`.

- [ ] **Step 8: Run the tests to make sure they pass**

Run: `npx vitest run apps/user/src/organising apps/user/src/actions apps/user/src/views` from `healdan-web/web`.
Expected: PASS, the existing `Rename.test.tsx` included.
Then `npm run typecheck && npm run lint && npm test`. Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add healdan-web/web
git commit -m "Rename folders in place from the User app's lists"
```

---

### Task 4: Renaming in the My Matters tree

**Files:**
- Modify: `healdan-web/web/packages/ui/src/components/Tree.tsx`
- Modify: `healdan-web/web/apps/user/src/frame/LeftPane.tsx` (`folderLabel`, `MyMattersSection`)
- Test: `healdan-web/web/packages/ui/src/components/Tree.test.tsx` (if it exists; else in `LeftPane.test.tsx`)
- Test: `healdan-web/web/apps/user/src/frame/LeftPane.test.tsx`

**Interfaces:**
- Consumes: Task 3's `FolderName`, `startRename(key, 'tree')`, `whyNotRename`, `t('rename.matterFolder')`, the focus
  target `[role="treegrid"] [data-node-id="<id>"]`.
- Produces: `TreeProps.onRename?: (id: string) => void`; every tree row carries `data-node-id="<node id>"`.

- [ ] **Step 1: Write the failing tests**

In `LeftPane.test.tsx` (imports: `userEvent`, and `folder` already imported), after the menu test:

```tsx
  it('renames a folder of the tree from its ⋯, in place', async () => {
    let name = 'Correspondence'
    const { api } = await renderApp('/folders/1', {
      'GET /v1/folders/110/children': () => page([folder({ id: 111, name })]),
      'PATCH /v1/folders/*': ({ body }: { body?: unknown }) => {
        name = (body as { name: string }).name
        return folder({ id: 111, name })
      },
    })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    await within(tree).findByRole('row', { name: /Correspondence/ })
    fireEvent.click(within(tree).getByRole('button', { name: 'More for Correspondence' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Rename' }))
    const input = (await screen.findByRole('textbox', { name: 'New Name of Correspondence' })) as HTMLInputElement
    await waitFor(() => expect(document.activeElement).toBe(input))
    await userEvent.keyboard('Letters{Enter}')
    await waitFor(() => expect(api.callsTo('PATCH /v1/folders/111')).toHaveLength(1))
    expect(await within(tree).findByRole('row', { name: /Letters/ })).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.getAttribute('data-node-id')).toBe('111'))
  })

  it('renames a folder of the tree with F2', async () => {
    const { api } = await renderApp('/folders/1', {
      'PATCH /v1/folders/*': () => folder({ id: 111, name: 'Letters' }),
    })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    const target = await within(tree).findByRole('row', { name: /Correspondence/ })
    target.focus()
    fireEvent.keyDown(target, { key: 'F2' })
    await screen.findByRole('textbox', { name: 'New Name of Correspondence' })
    await userEvent.keyboard('Letters{Enter}')
    await waitFor(() => expect(api.callsTo('PATCH /v1/folders/111')).toHaveLength(1))
  })

  it("greys Rename out on a matter, and F2 there says the matter's folder takes its name", async () => {
    await renderApp('/folders/1')
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: 'More for 1001.1 – Charter Dispute' }))
    expect((await screen.findByRole('menuitem', { name: 'Rename' })).getAttribute('aria-disabled')).toBe('true')
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    const matterRow = within(tree).getByRole('row', { name: /1001\.1/ })
    matterRow.focus()
    fireEvent.keyDown(matterRow, { key: 'F2' })
    expect(await screen.findByText(/own folder takes the matter's name/)).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it("greys Rename out on a folder the person can't rename, and F2 says why", async () => {
    await renderApp('/folders/1', {
      'GET /v1/folders/110/children': () =>
        page([folder({ id: 111, name: 'Correspondence', canRename: false, path: [{ id: 2, name: 'Smoke', kind: 'store' }, { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }, { id: 111, name: 'Correspondence', kind: 'folder' }] })]),
    })
    const tree = await screen.findByRole('treegrid', { name: 'My Matters' })
    fireEvent.click(within(tree).getByRole('button', { name: /expand/i }))
    const target = await within(tree).findByRole('row', { name: /Correspondence/ })
    fireEvent.click(within(tree).getByRole('button', { name: 'More for Correspondence' }))
    expect((await screen.findByRole('menuitem', { name: 'Rename' })).getAttribute('aria-disabled')).toBe('true')
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
    target.focus()
    fireEvent.keyDown(target, { key: 'F2' })
    // The fake /v1/me isn't an admin, and Correspondence is at the matter's top level
    expect(await screen.findByText("Only administrators can rename a matter's top-level folders.")).toBeTruthy()
  })
```

If the fake API's default `/v1/folders/110/children` answer or `/v1/me` differs from these assumptions (the test file's
other tests show them), adjust the routes, not the expectations. If React Aria's menu is closed differently in these
tests, close it as the file's own menu test does, or skip closing it and focus the row directly.

- [ ] **Step 2: Run them to make sure they fail**

Run: `npx vitest run apps/user/src/frame/LeftPane.test.tsx` from `healdan-web/web`.
Expected: FAIL: no "More for Correspondence" button, no Rename item, F2 does nothing.

- [ ] **Step 3: F2 and the node's id in `Tree`**

`Tree.tsx`: add to `TreeProps`:

```ts
  /** F2 on a row: rename what it stands for */
  onRename?: (id: string) => void
```

In `item(node)`'s `ref` callback, also mark the row with its node's id, which a rename gives the focus back to:

```tsx
        ref={(row) => {
          row?.setAttribute('data-node-id', node.id)
          if (current === node.id) row?.setAttribute('aria-current', 'page')
          else row?.removeAttribute('aria-current')
        }}
```

and wrap the `AriaTree` so F2 on a row reaches `onRename` ahead of React Aria's keys, as `Table` does (a field inside
the row stops its own keys, so typing in it never gets here):

```tsx
  function onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
    const target = event.target instanceof Element ? event.target : null
    if (!onRename || event.key !== 'F2' || target?.getAttribute('role') !== 'row') return
    const id = target.getAttribute('data-node-id')
    if (!id || id.endsWith(loadingSuffix)) return
    event.preventDefault()
    event.stopPropagation()
    onRename(id)
  }

  return (
    <div className={styles.frame} onKeyDownCapture={onKeyDownCapture}>
      <AriaTree … as now …>{nodes.map(item)}</AriaTree>
    </div>
  )
```

Add `.frame { display: contents; }` to `Tree.module.css` so the wrapper changes no layout, and `KeyboardEvent` to the
`react` import. Destructure `onRename` out of the props so it isn't spread onto `AriaTree`.

- [ ] **Step 4: The tree's folders: the field, the ⋯ and F2**

`LeftPane.tsx`: imports `FolderName` from `'../organising/RenameField'`, `whyNotRename` from `'../actions/rename'` and
`useMe` from `'../queries'`.

`folderLabel` draws the name through `FolderName`:

```tsx
function folderLabel(folder: S['FolderView']): ReactNode {
  const icon: IconName = folder.kind === 'searchFolder' ? 'folderSearch' : 'folder'
  return (
    <>
      <Icon name={icon} />
      <FolderName folder={folder} place="tree" className={styles.text} />
    </>
  )
}
```

In `MyMattersSection`: `const me = useMe()`, a map `const folders = new Map<string, S['FolderView']>()` beside
`places`, and in `folderNode` `folders.set(id, folder)` and a menu:

```tsx
      menu: (
        <Menu variant="quiet" label={<Icon name="dots" />} aria-label={t('tree.more', { name: folder.name })}>
          <MenuItem id="rename" isDisabled={!folder.canRename} onAction={() => organising.startRename(`f${folder.id}`, 'tree')}>
            {t('rename.menu')}
          </MenuItem>
        </Menu>
      ),
```

The matter node's menu gets Rename, greyed out, ahead of Remove (a matter's own folder takes the matter's name):

```tsx
          <MenuItem id="rename" isDisabled>
            {t('rename.menu')}
          </MenuItem>
          <MenuItem id="remove" …as now…>
```

And the `Tree` gets:

```tsx
          onRename={(id) => {
            if (matters.some((matter) => String(matter.folderId) === id)) return toast(t('rename.matterFolder'))
            const folder = folders.get(id)
            if (!folder) return
            const why = whyNotRename(folder, me.data?.admin)
            if (why) toast(why)
            else organising.startRename(`f${folder.id}`, 'tree')
          }}
```

The ⋯ shows only while its row is hovered or has the keyboard's focus, or its menu is open: `Tree.module.css`
already does that for every node's `menu`.

- [ ] **Step 5: Run the tests to make sure they pass**

Run: `npx vitest run apps/user/src/frame packages/ui` from `healdan-web/web`. Expected: PASS, the existing tree tests
and axe checks included. If the field loses the focus to its row (React Aria taking it back when the tree draws its
nodes again), find what redraws (`folderNode` makes new node objects at each render) and keep the renamed node's
`label` stable while it's being renamed, or focus the field again in `RenameField`'s effect after a frame
(`requestAnimationFrame`); say which in the report.
Then `npm run typecheck && npm run lint && npm test`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add healdan-web/web
git commit -m "Rename folders from the My Matters tree"
```

---

### Task 5: Renaming the open folder from its heading

**Files:**
- Modify: `healdan-web/web/apps/user/src/views/FolderView.tsx` (the heading)
- Modify: `healdan-web/web/apps/user/src/views/views.module.css` (if the field needs the heading's size)
- Test: `healdan-web/web/apps/user/src/views/FolderView.test.tsx`

**Interfaces:**
- Consumes: Task 3's `FolderName`, `startRename(key, 'heading')`, `t('folder.more')`, the focus target
  `[data-rename-menu="<key>"] button`.

- [ ] **Step 1: Write the failing tests**

In `FolderView.test.tsx` (check its imports for `renderApp`, `folder`, `screen`, `within`, `waitFor`, `fireEvent`,
`userEvent`; its routes for folder 111):

```tsx
describe("The open folder's heading", () => {
  it('renames the open folder from the ⋯ beside its name', async () => {
    let name = 'Correspondence'
    const correspondence = () => folder({ id: 111, name, path: [{ id: 2, name: 'Smoke', kind: 'store' }, { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }, { id: 111, name, kind: 'folder' }] })
    const { api } = await renderApp('/folders/111', {
      'GET /v1/folders/111': () => correspondence(),
      'PATCH /v1/folders/*': ({ body }: { body?: unknown }) => {
        name = (body as { name: string }).name
        return correspondence()
      },
    })
    const main = screen.getByRole('main')
    fireEvent.click(await within(main).findByRole('button', { name: 'More for Correspondence' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Rename' }))
    await screen.findByRole('textbox', { name: 'New Name of Correspondence' })
    await userEvent.keyboard('Letters{Enter}')
    await waitFor(() => expect(api.callsTo('PATCH /v1/folders/111')).toHaveLength(1))
    expect(await within(main).findByRole('heading', { level: 1, name: 'Letters' })).toBeTruthy()
    await waitFor(() => expect(document.activeElement?.closest('[data-rename-menu]')?.getAttribute('data-rename-menu')).toBe('f111'))
  })

  it("greys Rename out on a matter's own heading", async () => {
    await renderApp('/folders/110', {
      'GET /v1/folders/110': folder({ id: 110, name: '1001.1 – Charter Dispute', kind: 'matter', canRename: false, canFile: false, path: [{ id: 2, name: 'Smoke', kind: 'store' }, { id: 110, name: '1001.1 – Charter Dispute', kind: 'matter' }] }),
    })
    fireEvent.click(await within(screen.getByRole('main')).findByRole('button', { name: 'More for 1001.1 – Charter Dispute' }))
    expect((await screen.findByRole('menuitem', { name: 'Rename' })).getAttribute('aria-disabled')).toBe('true')
  })

  it('has no ⋯ on a Store, which opens as no folder', async () => {
    await renderApp('/folders/2', { 'GET /v1/folders/2': folder({ id: 2, name: 'Smoke', kind: 'store', matter: null, canRename: false, canFile: false, path: [{ id: 2, name: 'Smoke', kind: 'store' }] }) })
    await within(screen.getByRole('main')).findByRole('heading', { level: 1, name: 'Smoke' })
    expect(within(screen.getByRole('main')).queryByRole('button', { name: 'More for Smoke' })).toBeNull()
  })
})
```

If the fake API's routes give folder 111 or 110 differently, adjust the routes, not the expectations.

- [ ] **Step 2: Run them to make sure they fail**

Run: `npx vitest run apps/user/src/views/FolderView.test.tsx`. Expected: FAIL: no "More for Correspondence" button.

- [ ] **Step 3: The heading's ⋯ and field**

`FolderView.tsx`: import `Icon`, `Menu`, `MenuItem` from `@healdan/ui`, `FolderName` from
`'../organising/RenameField'` and `useOrganising` from `'../organising/OrganisingProvider'`; add
`const organising = useOrganising()` with the other hooks (above the early returns). Replace the heading block:

```tsx
        <div className={styles.heading}>
          <h1 id={headingId} className={styles.title}>
            <FolderName folder={view} place="heading" />
          </h1>
          {/* A matter goes in My Matters (the + in the left pane), not in Favorites */}
          {view.kind !== 'matter' && !store && <FavoriteStar what={{ folderId }} add={t('favorites.add')} remove={t('favorites.remove')} />}
          {/* Rename is greyed out where the server says it can't be done: a matter's own folder, a top-level folder for someone who isn't an admin */}
          {!store && (
            <span data-rename-menu={`f${view.id}`}>
              <Menu variant="quiet" label={<Icon name="dots" />} aria-label={t('folder.more', { name: view.name })}>
                <MenuItem id="rename" isDisabled={!view.canRename} onAction={() => organising.startRename(`f${view.id}`, 'heading')}>
                  {t('rename.menu')}
                </MenuItem>
              </Menu>
            </span>
          )}
        </div>
```

If the field inside the `h1` takes the body's text size, give `.title .renameInput`'s font `inherit` in
`views.module.css` (or `organising.module.css`'s `.renameInput`, if it doesn't already inherit), so the heading
doesn't jump while it's edited.

- [ ] **Step 4: Run the tests to make sure they pass**

Run: `npx vitest run apps/user/src/views` then `npm run typecheck && npm run lint && npm test`. Expected: PASS,
`FolderView`'s axe checks included.

- [ ] **Step 5: Commit**

```bash
git add healdan-web/web
git commit -m "Rename the open folder from the ⋯ beside its name"
```

---

### Task 6: The end-to-end spec

**Files:**
- Create: `healdan-web/web/e2e/folders.spec.ts`

**Interfaces:**
- Consumes: `e2e/helpers.ts`' `PASSWORD`, `authorized`, `call`, `deviceToken`, `escape`, `matterFolders`, `signIn`,
  `specsFolder`, `tabTo`; Tasks 3 to 5's labels ("New Name of …", "More for …", "Rename").

- [ ] **Step 1: Write the spec**

```ts
// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import { PASSWORD, authorized, call, deviceToken, escape, matterFolders, signIn, specsFolder, tabTo } from './helpers'

// Renaming folders in the User app (folder rename spec): by F2 in the list, from the My Matters tree's ⋯ and from the
// open folder's heading, once by keyboard alone, with a taken name refused beside the field; and, for webuser, who isn't
// an administrator, Rename greyed out on matter 1.1's top-level Documents folder. The folders are this run's own, in a
// folder of its own inside the specs' E2E folder (helpers' specsFolder), so nothing earlier runs or smoke steps left
// is in their lists.
const RUN = `fld${Date.now().toString(36)}`
const NAMES = { home: `Folders ${RUN}`, byF2: `By F2 ${RUN}`, byTree: `By Tree ${RUN}`, byHeading: `By Heading ${RUN}`, keys: `Keys ${RUN}`, keyTarget: `Key Target ${RUN}`, taken: `Taken ${RUN}` }

let api: APIRequestContext | undefined
let token = ''
let matterFolder = 0
let documentsFolder = 0
const ids: Record<keyof typeof NAMES, number> = { home: 0, byF2: 0, byTree: 0, byHeading: 0, keys: 0, keyTarget: 0, taken: 0 }

test.beforeAll(async ({ playwright }, testInfo) => {
  expect(PASSWORD, 'WEBUSER_PASSWORD is set by docker/tests/test-web.sh').not.toBe('')
  api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL })
  token = await deviceToken(api, RUN)
  const where = await matterFolders(api, token)
  matterFolder = where.matter
  documentsFolder = where.documents
  const makeFolder = async (parentId: number, name: string) =>
    (await call<{ id: number }>(api!.post('/api/v1/folders', { ...authorized(token), data: { parentId, name } }), `making ${name}`)).id
  ids.home = await makeFolder(await specsFolder(api, token, documentsFolder), NAMES.home)
  for (const key of ['byF2', 'byTree', 'byHeading', 'keys', 'taken'] as const) ids[key] = await makeFolder(ids.home, NAMES[key])
  ids.keyTarget = await makeFolder(ids.keys, NAMES.keyTarget)
  // The tree is My Matters: it opens to the folder shown
  await call(api.put(`/api/v1/me/matters/${matterFolder}`, authorized(token)), 'adding the matter to My Matters')
})

test.afterAll(async () => {
  if (!api) return
  if (token) {
    await api.delete(`/api/v1/me/matters/${matterFolder}`, authorized(token))
    await api.delete('/api/v1/auth/tokens/current', authorized(token))
  }
  await api.dispose()
})

const main = (page: Page) => page.getByRole('main')
const tree = (page: Page) => page.getByRole('treegrid', { name: 'My Matters' })
const field = (page: Page, name: string) => page.getByRole('textbox', { name: `New Name of ${name}` })
const nameOf = async (id: number) => (await call<{ name: string }>(api!.get(`/api/v1/folders/${id}`, authorized(token)), `folder ${id}`)).name

async function open(page: Page, folderId: number) {
  await page.goto('/login.jsp')
  await signIn(page)
  await expect(page.getByRole('heading', { name: 'Recent Documents' })).toBeVisible()
  await page.goto(`/app/folders/${folderId}`)
}

test('F2 renames a folder row in place, and a taken name is refused beside the field', async ({ page }) => {
  await open(page, ids.home)
  const row: Locator = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.byF2) })
  // A click on a folder's row would open it
  await row.focus()
  await page.keyboard.press('F2')
  await field(page, NAMES.byF2).fill(NAMES.taken)
  await page.keyboard.press('Enter')
  await expect(page.getByRole('alert')).toHaveText(`There's already a folder called ${NAMES.taken} here.`)
  const renamed = `Renamed by F2 ${RUN}`
  await field(page, NAMES.byF2).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeVisible()
  expect(await nameOf(ids.byF2)).toBe(renamed)
})

test("renames a folder from the tree's ⋯, and the heading and the breadcrumb follow", async ({ page }) => {
  await open(page, ids.byTree)
  const node = tree(page).getByRole('row', { name: escape(NAMES.byTree) })
  await node.hover()
  await node.getByRole('button', { name: `More for ${NAMES.byTree}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed in the Tree ${RUN}`
  await field(page, NAMES.byTree).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  expect(await nameOf(ids.byTree)).toBe(renamed)
})

test("renames the open folder from the ⋯ beside its name", async ({ page }) => {
  await open(page, ids.byHeading)
  await main(page).getByRole('button', { name: `More for ${NAMES.byHeading}` }).click()
  await page.getByRole('menuitem', { name: 'Rename' }).click()
  const renamed = `Renamed by Its Heading ${RUN}`
  await field(page, NAMES.byHeading).fill(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('heading', { level: 1, name: renamed })).toBeVisible()
  await expect(tree(page).getByRole('row', { name: escape(renamed) })).toBeVisible()
  expect(await nameOf(ids.byHeading)).toBe(renamed)
})

test('renames a folder by keyboard alone', async ({ page }) => {
  await open(page, ids.keys)
  const row = main(page).getByRole('grid').getByRole('row', { name: escape(NAMES.keyTarget) })
  await tabTo(page, row)
  await page.keyboard.press('F2')
  const renamed = `Renamed by Keys ${RUN}`
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.type(renamed)
  await page.keyboard.press('Enter')
  await expect(main(page).getByRole('grid').getByRole('row', { name: escape(renamed) })).toBeFocused()
  expect(await nameOf(ids.keyTarget)).toBe(renamed)
})

test("greys Rename out on a matter's top-level folder for someone who isn't an administrator", async ({ page }) => {
  await open(page, documentsFolder)
  await main(page).getByRole('button', { name: 'More for Documents' }).click()
  await expect(page.getByRole('menuitem', { name: 'Rename' })).toBeDisabled()
  await page.keyboard.press('Escape')
  const node = tree(page).getByRole('row', { name: /^Documents/ })
  await node.focus()
  await page.keyboard.press('F2')
  await expect(page.getByText("Only administrators can rename a matter's top-level folders.")).toBeVisible()
  await expect(page.getByRole('textbox')).toHaveCount(0)
})
```

If `tabTo`'s first Tab lands elsewhere, give it `{ from: … }` as `keyboard.spec.ts` does for rows. If the tree's
Documents node isn't drawn (the tree opens only to the folder shown, and Documents is a matter's top level, so it is),
expand the matter first by its chevron. The search box's own select-all differs between engines; the field selects the
whole name when it opens, so `ControlOrMeta+a` only makes sure.

- [ ] **Step 2: Typecheck and lint it**

Run from `healdan-web/web`: `npm run typecheck && npm run lint`. Expected: PASS.

- [ ] **Step 3: Run it against a smoke stack, if one can be built here**

Playwright's specs run only in `docker/tests/test-web.sh` (smoke step 9) against a stack with the smoke, sign-in and
storage overlays. If Docker is available and the image builds in reasonable time, run `docker/smoke-test.sh` in the
background and read step 9's result. Otherwise say in the report that the spec runs first in CI's Docker image job on
the pull request.

- [ ] **Step 4: Commit**

```bash
git add healdan-web/web/e2e/folders.spec.ts
git commit -m "Check renaming folders in Chromium and WebKit"
```

---

### Task 7: Docs and records

**Files:**
- Modify: `site/content/user/documents/copying-moving-and-deleting/index.md`
- Modify: `site/content/user/finding-documents/my-matters-and-recent-matters/index.md`
- Modify: `site/content/admin/top-level-folders.md`
- Modify: `site/content/install/known-limitations.md` (~line 135)
- Modify: `site/content/developer/api.md` (the Renaming, Moving and Deleting section, ~line 440)
- Modify: `site/content/developer/the-web-app.md` (`organising/`, ~line 255)
- Modify: `site/content/developer/tests.md` (the specs' list, beside `organising.spec.ts`)
- Modify: `CLAUDE.md` (the smoke test's step 9 list of specs)
- Modify: `docs/superpowers/specs/2026-11-10-m6-user-app-design.md` (§2.2, §4, §6.7, §10.5, §16)
- Modify: `docs/ROADMAP.md` (the M6 line, ~line 616)
- Modify: `docs/superpowers/specs/2026-10-17-m1-move-in-design.md` (the note on `FolderServiceImpl.rename`, ~line 116)

- [ ] **Step 1: The User Guide**

`copying-moving-and-deleting/index.md`: the opening becomes "In the app you can rename, move and delete documents,
and make and rename folders. Copying a document, and moving or deleting a folder, are in the [Classic
Interface](../../the-classic-interface/)." (keep the rest of the paragraph). Add after Making a Folder in the App:

```markdown
## Renaming a Folder in the App

Its name becomes a field, with all of it selected. **Enter** saves, and so does clicking away after you've changed it;
**Esc** leaves it as it was. There are three ways in:

- **In a list,** move to the folder's row with the arrow keys and press **F2**. That works in a folder, in a
  **Folders** search and in **Recent › Folders**.
- **In My Matters** in the left pane, point at the folder and choose its **⋯** button, then **Rename**, or move to
  it with the arrow keys and press **F2**.
- **The folder you have open:** choose the **⋯** button beside its name above the list, then **Rename**.

- **You need the Rename permission** on the folder.
- **Only administrators rename the folders at the top of a matter,** such as Correspondence and Drafts, search
  folders included. For anyone else **Rename** is greyed out there, and **F2** says "Only administrators can rename a
  matter's top-level folders."
- **A matter's own folder isn't renamed here.** It takes the matter's name, which an administrator changes with
  **Edit Matter…** in the Classic Interface, so **Rename** is greyed out on it.
- **Two folders side by side can't share a name.** Healdan says "There's already a folder called Drafts here." beside
  the field, which stays open for you to choose another. Changing only the case of the folder's own name, such as
  `drafts` to `Drafts`, works.
- **A name can't have** `/`, `\`, emoji or HTML tags in it.

The rename is in the folder's history. Its documents keep their doc #s and properties.
```

`my-matters-and-recent-matters/index.md`: where the tree is described, add a bullet: "**Rename a folder** with its
**⋯** button, shown while you point at it or move to it with the arrow keys, or with **F2**. See [Renaming a Folder in
the App](../../documents/copying-moving-and-deleting/#renaming-a-folder-in-the-app)." Check the anchor's spelling
against Hugo's for that heading.

- [ ] **Step 2: The Admin Guide and the Install manual**

`top-level-folders.md`, after the first paragraph: "In the app, **Rename** is greyed out on them for anyone who isn't
an administrator, and on the workspace folder for everyone."

`known-limitations.md`: the bullet becomes "moving and deleting folders, copying documents, and locking them;".

- [ ] **Step 3: The Developer Guide**

`api.md`, in Renaming, Moving and Deleting, after the documents' paragraphs:

```markdown
`PATCH /api/v1/folders/{id}` with `{ "name": "…" }` renames a folder inside a matter and answers it. Its checks run in
this order: `404` for a folder you can't read; `409 not-renamable` for a Store or a matter's own folder, which takes
the matter's name through Edit Matter; `403 forbidden` without the Rename permission; `403 admins-only` for a folder
directly in a matter's workspace, search folders included, unless you're an administrator; `422 validation` for the
name (`required`, `too-long`, `invalid`: `/`, `\`, a control character, an emoji, or HTML tags); and `409 name-taken`
when another folder beside it has the name, without regard to case. Every folder carries `canRename`, which says
whether the call would rename it for you.
```

`the-web-app.md`: in the `organising/` paragraph, say that `RenameField.tsx` holds one field for documents and
folders (`DocumentTitle`, `FolderName`), and that `OrganisingProvider`'s `renaming` is a row key and a place (`row`,
`tree`, `heading`), where the focus goes back once it's done.

`tests.md`: after `organising.spec.ts`'s description, add: "`folders.spec.ts` makes its own folders inside `E2E` through
the API and renames them by F2 in the list, from the tree's ⋯ and from the open folder's heading, once by keyboard
alone, with a taken name refused beside the field, and checks that `webuser`, who isn't an administrator, sees Rename
greyed out on matter 1.1's top-level Documents folder."

`CLAUDE.md`, the smoke test's step 9: after the `organising.spec.ts` clause, add "`folders.spec.ts` renames its own
folders by F2, from the tree's ⋯ and from the heading, and checks Rename is greyed out on a top-level folder for a
non-admin," in the same style.

- [ ] **Step 4: The records**

M6 spec:
- §2.2: "folder rename, move and delete" → "moving and deleting folders (renaming them came forward on 2026-11-14:
  the [folder rename spec](2026-11-14-folder-rename-design.md))".
- §4's Organising rows: add "| | Renaming folders | new | yes (owner, 2026-11-14: brought forward; [folder rename
  spec](2026-11-14-folder-rename-design.md)) |" and change "Renaming, moving and deleting folders" to "Moving and
  deleting folders".
- §6.7: a bullet after Rename: "**Renaming a folder** (brought forward on 2026-11-14) works the same way, in a list's
  row with F2, in the My Matters tree from its ⋯ or F2, and from the open folder's heading; see the [folder rename
  spec](2026-11-14-folder-rename-design.md)."
- §10.5: "F2 renames." → "F2 renames a document, or a folder in a list or the tree."
- §16: "renaming, moving and deleting folders" → "moving and deleting folders".

`ROADMAP.md`, the M6 line: "folder changes" → "moving and deleting folders", and "New Folder, rename" → "New Folder,
rename (documents, and folders since 2026-11-14)".

M1 spec, the `FolderServiceImpl.rename doesn't check RENAME` bullet: append " (since fixed upstream: it checks it now,
as the folder rename spec §3 found on 2026-11-14)".

- [ ] **Step 5: Build the docs site and run its checks**

Run from the repository's root:

```bash
python3 -m unittest discover -s .github/scripts/tests
hugo --source site --gc --minify --panicOnWarning
python3 .github/scripts/check-site-sources.py site
python3 .github/scripts/check-site-links.py site/public
```

Expected: all pass. A broken anchor shows in `check-site-links.py`.

- [ ] **Step 6: Commit**

```bash
git add site CLAUDE.md docs
git commit -m "Document renaming folders in the User app"
```

---

### Task 8: Right-click › Rename, the rebase and the pull request

**Files:**
- Modify: whatever files `claude/m6-context-menus` builds the folder rows' and the tree's right-click menus in, if it
  has merged.

- [ ] **Step 1: Rebase on master**

```bash
git fetch origin
git rebase origin/master
```

Resolve conflicts in `LeftPane.tsx` and `listColumns.tsx` (the icon-colour and context-menu branches change them)
keeping both sides: their icons and menus, this work's `FolderName` and ⋯.

- [ ] **Step 2: Add Rename to the context menus, if they have merged**

`gh pr list --repo Healdan-Project/healdan --state merged --search "head:claude/m6-context-menus"`. If it has merged,
find the folder rows' and the tree's right-click menus (`git log origin/master --stat --grep "context menu" -i`) and
add a Rename item to each folder menu, as the ⋯ menus have it:

```tsx
<MenuItem id="rename" isDisabled={!folder.canRename} onAction={() => organising.startRename(`f${folder.id}`, place)}>
  {t('rename.menu')}
</MenuItem>
```

with `place` `'row'` for a list's row and `'tree'` for the tree, and greyed out (`isDisabled`) on a matter's menu.
Add a test beside theirs that right-click › Rename opens the field. Update the User Guide's three ways in to four,
with "right-click the folder and choose **Rename**". If it hasn't merged, leave this step for after it lands, and say
so in the PR's description.

- [ ] **Step 3: Run every check**

```bash
cd healdan-web/web && npm ci --ignore-scripts && npm run generate && npm run typecheck && npm run lint && npm test && npm run build
```

```bash
mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true
```

and the docs site's four commands (Task 7, Step 5). Expected: all pass. Run Maven in the background.

- [ ] **Step 4: Open the pull request**

Push the branch and open a PR to `master` with `gh pr create --repo Healdan-Project/healdan`, not merged. Its
description says what changes (the endpoint, `canRename`, the app's four ways in), that no upstream file changes, the
tests, and whether right-click › Rename is in or waits for the context menus. It ends with
`🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
