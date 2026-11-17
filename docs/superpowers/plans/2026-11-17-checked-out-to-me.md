# Checked Out to Me in the User App: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A **Checked Out to Me** view in the User app, with its entry and count at the top of the left pane, and
**Release…** for a check-out another of the person's computers holds, behind a warning, through a new `elsewhere` flag
on `DELETE /v1/documents/{docNumber}/checkout`.

**Architecture:** `healdan-api`'s `CheckoutResource.release` skips its held-elsewhere refusal when the request says
`elsewhere=true`, and records which computer held the check-out. In `healdan-web`, Recent Documents' list page is shared
with a new `CheckedOutView` fed by `GET /v1/me/checkouts`; every release goes through a new `ReleaseProvider`, which
asks with `ConfirmRelease` (an alert dialog with a red button) before releasing a check-out held on a computer.

**Tech Stack:** Java 25, JAX-RS (CXF), JUnit 4 with `ApiTestCase`; React, TypeScript, React Aria Components through
`@healdan/ui`, TanStack Query, Vitest and Testing Library; Playwright; Hugo.

**Spec:** [`docs/superpowers/specs/2026-11-17-checked-out-to-me-design.md`](../specs/2026-11-17-checked-out-to-me-design.md)
(approved by the owner on 2026-11-17), with its [mockup](../specs/2026-11-17-checked-out-to-me-mockup.html). Read both
first. Where they differ, the spec wins.

## Global Constraints

- Every new source file opens with `SPDX-FileCopyrightText: 2026 The Healdan Project` and
  `SPDX-License-Identifier: LGPL-3.0-only` in its comment syntax (CLAUDE.md, Licence headers). The mockup and the docs
  don't carry them.
- No upstream (LogicalDOC) file changes (spec §6.1). Everything is in `healdan-api`, `healdan-web`, `site/` and `docs/`.
- `openapi.yaml` changes in the same commit as the behaviour it describes; `ApiTestCase` checks every answer against it.
  After changing it, run `npm run generate` in `healdan-web/web` (the generated `schema.d.ts` isn't committed).
- The app never shows the server's own text: refusals go through `messageFor` and `refusalLines`, as Move's and Delete's
  do.
- Screens import React Aria only through `@healdan/ui`; no `dangerouslySetInnerHTML`.
- App strings go in `healdan-web/web/packages/ui/src/strings/en-US.ts`, in US English, labels and titles in Title Case;
  the docs are British (the Developer Guide's Writing Docs page). The copy this plan quotes is exact.
- The computer's short name drops a leading `Healdan Helper on ` (exact, case-sensitive) and trims, as the Helper's
  `DeviceNames.Short` does (spec §5.2).
- The flag is the query parameter `elsewhere`, a boolean, `false` when absent (spec §4.1).
- Java tests, locally, in the JDK 25 container (the owner's Mac has no Maven), from the repository root:
  `docker run --rm -v "$PWD":/src -w /src -v healdan-m2-checked-out:/root/.m2/repository -v "$PWD/docker/maven-settings.xml":/root/.m2/settings.xml:ro maven:3.9-eclipse-temurin-25 bash -c 'touch /root/logicaldoc-dev.properties && exec mvn "$@"' mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
  Seed the volume from `healdan-m2` first (`docker run --rm -v healdan-m2:/from:ro -v healdan-m2-checked-out:/to alpine cp -a /from/. /to/`),
  and remove it once the PR merges. It takes several minutes: run it in the background.
- The web checks, from `healdan-web/web`: `npm ci --ignore-scripts` (once), `npm run generate`, `npm run typecheck`,
  `npm run lint`, `npm test`, `npm run build` (Node 24, `.nvmrc`). One file: `npx vitest run <path>`.
- Keep real hostnames, internal addresses and personal names out of commits and the PR; the demo firm's names are fine.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A selection that mixes kinds of check-out:** one made in the browser, two on MacBook and one on the iMac. One
   warning, a list per computer, only the computer-held documents listed, and the red button counting all four. Pinned in
   Task 4 (`ConfirmRelease.test.tsx`, "groups by computer and counts every document").
2. **A check-out that changed between the warning and Release:** the Helper checked it in meanwhile, or someone else
   holds it now. The free one counts as released, the other is named in the refusals dialog, and the rest still go.
   Pinned in Task 4 (`Release.test.tsx`, "names what was refused and releases the rest").
3. **The focus once the rows go:** in Checked Out to Me the released rows leave the list, and the focus must land on the
   next row (or the list), never on the page's body. Pinned in Task 4 (`Release.test.tsx`, "puts the focus on the next
   row").
4. **The new entry above Recent shifts the pane:** Vitest tests and Playwright specs that walk the left pane by Tab or
   take its first link may now start one stop early. Pinned in Task 3 (the whole Vitest suite) and Task 5 (the whole
   Playwright run).
5. **Computer names the Helper didn't make:** a token named `e2e 1234`, a `heldOn` with a null name, a name that is
   only the Helper's prefix. Each gets a readable label, never an empty cell. Pinned in Task 3 (`checkout.test.ts`,
   "computerLabel").

---

### Task 1: `elsewhere=true` on the release

**Files:**
- Modify: `healdan-api/src/main/java/com/logicaldoc/healdan/api/CheckoutResource.java` (`release`, ~line 131; `history`, ~line 414)
- Modify: `healdan-api/src/main/resources/healdan/api/openapi.yaml` (`releaseDocument`, ~line 1588)
- Test: `healdan-api/src/test/java/com/logicaldoc/healdan/api/DevicesTest.java`

**Interfaces:**
- Produces: `DELETE /v1/documents/{docNumber}/checkout?elsewhere=true`; `CheckoutResource.RELEASED_ELSEWHERE`, the
  history comment's format, `"Released while checked out on %s"` with `" (%s)"` for the app when there is one.

- [ ] **Step 1: Write the failing tests** in `DevicesTest`, using its `signIn(device, name)`, `file()`, `checkout(d)`
  and `assertHeldElsewhere`, plus `signInWeb(clerk)` and `.webSession(http)` from `ApiTestCase`. Add a helper
  `saveIfHeld(String token, Document d)` that posts `{"kind": "snapshot", "keepCheckedOut": true, "ifHeld": true}` as
  `save` does, and `lastUnlock(Document d)` that reads the newest `event.unlocked` row's `ld_comment` from `ld_history`.

```java
@Test
public void testReleaseElsewhereFromTheWebPage() throws Exception {
	Document d = file();
	String mac = signIn("mac-1", "Healdan Helper on MacBook");
	assertEquals(200, post(checkout(d)).token(mac).json(Map.of("app", "Excel")).send().status());
	MockHttpSession web = signInWeb(clerk);

	assertHeldElsewhere(delete(checkout(d)).webSession(web).send(), d);
	assertHeldElsewhere(delete(checkout(d) + "?elsewhere=false").webSession(web).send(), d);

	ApiResponse released = delete(checkout(d) + "?elsewhere=true").webSession(web).send();
	assertEquals(released.toString(), 200, released.status());
	assertTrue(released.json().path("hold").isNull());
	assertEquals(DocumentStatus.UNLOCKED, DocumentDAO.get().findById(d.getId()).getStatus());
	assertEquals(0, (int) jdbc().queryForObject(
			"select count(*) from hd_hold_device where hd_docid = ? and hd_device is not null", Integer.class, d.getId()));
	assertEquals("Released while checked out on Healdan Helper on MacBook (Excel)", lastUnlock(d));

	// The computer that had it finds out at its next save
	ApiResponse late = saveIfHeld(mac, d);
	assertEquals(late.toString(), 409, late.status());
	assertEquals("not-held", late.code());
}

@Test
public void testReleaseElsewhereFromAnotherDevice() throws Exception {
	// mac-1 named "MacBook" holds it with no app; linux-2 releases it with the flag:
	// 200, and lastUnlock(d) is "Released while checked out on MacBook"
}

@Test
public void testReleaseElsewhereChangesNothingElse() throws Exception {
	// mac-1 holds it. colleague with ?elsewhere=true: 409 checked-out, fx.snapshot() unchanged.
	// mac-1 itself with ?elsewhere=true: 200, and lastUnlock(d) is null (its own device: no comment).
	// A free document with ?elsewhere=true: 200, hold null, fx.snapshot() unchanged.
	// mac-1 checks out again; boss (admin) with ?elsewhere=true: 200, and the comment names mac-1's computer.
}
```

- [ ] **Step 2: Run them and see them fail**

Run: the Java test command (Global Constraints) with `-Dtest=DevicesTest`.
Expected: the three new tests FAIL, `testReleaseElsewhereFromTheWebPage` with `409 held-elsewhere` where `200` was
expected; the others PASS.

- [ ] **Step 3: Implement it** in `CheckoutResource.release(@PathParam("docNumber") long docNumber, @QueryParam("elsewhere") @DefaultValue("false") boolean elsewhere)`:
  call `refuseElsewhere` only when `!elsewhere`. Before `unlock`, read `DeviceHolds.get().held(document)`; when it is
  present and `!DeviceHolds.get().heldHere(document, caller.token())`, pass the comment to the history through a new
  `history(Session session, DocumentEvent event, String comment)` overload (`setComment`), made from
  `static final String RELEASED_ELSEWHERE = "Released while checked out on %s"` with the device's name (`Held.name()`,
  or `"another device"` when null), and `" (%s)"` with `Held.row().getApp()` when it isn't null. `unlock` takes the
  comment through to its `history` call. Leave `refuseElsewhere` itself and its other callers (saves, properties) as
  they are.

- [ ] **Step 4: Describe it in `openapi.yaml`:** add the parameter to `releaseDocument`

```yaml
        - name: elsewhere
          in: query
          required: false
          description: |
            `true` to release the caller's own check-out while another of
            their devices holds it, after the person has been warned that
            what that device hasn't saved to Healdan is lost. `false` by
            default, which is `409 held-elsewhere` for such a check-out.
          schema:
            type: boolean
            default: false
```

  and in the description change "(`409 held-elsewhere` from another)" to "(`409 held-elsewhere` from another, unless
  `elsewhere=true`)", adding: "Such a release is recorded with the device and app that held it."

- [ ] **Step 5: Run the tests again**

Run: the Java test command with `-Dtest='DevicesTest,CheckoutResourceTest,RouteCoverageTest'`.
Expected: PASS, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add healdan-api/src/main/java/com/logicaldoc/healdan/api/CheckoutResource.java healdan-api/src/main/resources/healdan/api/openapi.yaml healdan-api/src/test/java/com/logicaldoc/healdan/api/DevicesTest.java
git commit -m "Let the holder release a check-out another of their devices holds"
```

---

### Task 2: A red button, an alert dialog and two icons in `@healdan/ui`

**Files:**
- Modify: `healdan-web/web/packages/ui/src/tokens.css` (light `:root` and the two dark blocks)
- Modify: `healdan-web/web/packages/ui/src/tokens.test.ts` (`PAIRS`, and one non-text check)
- Modify: `healdan-web/web/packages/ui/src/components/Button.tsx`, `Button.module.css`, `Button.test.tsx`
- Modify: `healdan-web/web/packages/ui/src/components/Dialog.tsx`, `Dialog.test.tsx`
- Modify: `healdan-web/web/packages/ui/src/icons.ts` (and its test if it lists names)

**Interfaces:**
- Produces: `ButtonProps['variant']` gains `'danger'`; `DialogProps['role']?: 'dialog' | 'alertdialog'` (default
  `'dialog'`); `IconName` gains `'lockOpen'` (Tabler's `IconLockOpen`) and `'alertTriangle'` (`IconAlertTriangle`);
  tokens `--hd-danger-fill`, `--hd-danger-on-fill`, `--hd-danger-tint`, `--hd-danger-text`.

- [ ] **Step 1: Write the failing tests**
  - `tokens.test.ts`: add `['--hd-danger-on-fill', '--hd-danger-fill']`, `['--hd-danger-text', '--hd-danger-tint']` and
    `['--hd-text', '--hd-danger-tint']` to `PAIRS` (4.5:1 in both schemes), and a check that `--hd-danger-fill` against
    `--hd-window` is at least 3:1 in both.
  - `Button.test.tsx`: "the danger button has its own face": `variant="danger"` renders with the `danger` class, and
    never takes the pressed face, as the accent one doesn't.
  - `Dialog.test.tsx`: "an alert dialog says so": `role="alertdialog"` gives `getByRole('alertdialog', { name: <title> })`.

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run packages/ui` (from `healdan-web/web`).
Expected: FAIL on the new tokens, the `danger` variant and the `alertdialog` role.

- [ ] **Step 3: Implement**
  - Tokens, light: `--hd-danger-fill: #c42b1c; --hd-danger-on-fill: #ffffff; --hd-danger-tint: #fdf0ee; --hd-danger-text: #9b1c11;`
    dark (both blocks): `--hd-danger-fill: #d0392b; --hd-danger-on-fill: #ffffff; --hd-danger-tint: #3a1d1a; --hd-danger-text: #ff8a80;`.
  - `.button.danger` as `.button.default`, on the danger tokens, and excluded from the pressed face as `.default` is.
  - `Dialog` passes `role` to React Aria's `Dialog`.
  - The two icons in `icons.ts`.

- [ ] **Step 4: Run them again**

Run: `npx vitest run packages/ui`, then `npm run typecheck` and `npm run lint`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-web/web/packages/ui/src
git commit -m "Add a danger button, an alert dialog and two icons to the app's components"
```

---

### Task 3: Checked Out to Me: the view and its entry

**Files:**
- Modify: `healdan-web/web/apps/user/src/queries.ts` (`keys.checkouts`, `useCheckouts`)
- Modify: `healdan-web/web/apps/user/src/actions/checkout.ts` (`computerLabel`), `actions/checkout.test.ts`
- Create: `healdan-web/web/apps/user/src/views/DocumentListPage.tsx` (what `RecentView.tsx`'s `RecentList` and
  `RecentDocuments` were, shared)
- Modify: `healdan-web/web/apps/user/src/views/RecentView.tsx` (uses it; its behaviour unchanged)
- Create: `healdan-web/web/apps/user/src/views/CheckedOutView.tsx`, `views/CheckedOutView.test.tsx`
- Modify: `healdan-web/web/apps/user/src/views/listColumns.tsx` (`checkedOutColumns`), `views/views.module.css`
- Modify: `healdan-web/web/apps/user/src/router.tsx` (`checkedOutRoute`)
- Modify: `healdan-web/web/apps/user/src/frame/LeftPane.tsx`, `frame/LeftPane.module.css`, `frame/LeftPane.test.tsx`
- Modify: `healdan-web/web/apps/user/src/test/fakeApi.ts` (a default route)
- Modify: `healdan-web/web/packages/ui/src/strings/en-US.ts`

**Interfaces:**
- Consumes: `GET /v1/me/checkouts` (`PageOf<DocumentView>`), `hold.since`, `hold.heldOn`.
- Produces:
  - `keys.checkouts = ['documents', 'checkouts'] as const`, under `documents` so `invalidateDocumentLists` refreshes it;
  - `useCheckouts(): UseQueryResult<PageOf<S['DocumentView']>>`;
  - `computerLabel(hold: S['HoldView'] | null): { computer: string; app: string | null }`;
  - `checkedOutColumns(): TableColumn<ListRow>[]`;
  - `DocumentListPage({ heading, empty, actionsLabel, query, columns }: { heading: string; empty: string; actionsLabel: string; query: UseQueryResult<PageOf<S['DocumentView']>>; columns: TableColumn<ListRow>[] })`;
  - `ListPage`, `RecentList` renamed, taking `heading` and `empty` strings instead of `kind`;
  - the address `/checked-out`.

- [ ] **Step 1: Write the failing tests**
  - `checkout.test.ts`, "computerLabel": `heldOn: { name: 'Healdan Helper on MacBook', app: 'Excel' }` gives
    `{ computer: 'MacBook', app: 'Excel' }`; `{ name: 'e2e 1234', app: null }` gives `{ computer: 'e2e 1234', app: null }`;
    `{ name: 'Healdan Helper on ', app: null }` and `{ name: null, app: 'Word' }` give `computer: 'Another Computer'`;
    a hold with `heldOn: null` gives `{ computer: 'Web Page', app: null }`.
  - `CheckedOutView.test.tsx`, with `'GET /v1/me/checkouts'` answering two documents, one held on MacBook in Excel and
    one checked out in the browser:
    - "lists your check-outs in the server's order": heading level 1 `Checked Out to Me`; column headers
      `['Title', 'Doc #', 'Version', 'Matter', 'Computer', 'Checked Out', 'Edit Date']`; no header has `aria-sort`;
      the first row's Computer cell has `MacBook` and `Excel`, the second's `Web Page`.
    - "says so when there are none": `Nothing is checked out to you.`
    - "shows the selected document's details", as `RecentView.test.tsx` does for Recent Documents.
  - `LeftPane.test.tsx`:
    - "puts Checked Out to Me first, with its count": the navigation's first link is named
      `Checked Out to Me, 2 documents`, shows `2`, and goes to `/app/checked-out`; it has `aria-current="page"` there.
    - "shows no count with none checked out": named `Checked Out to Me`, no badge.

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run apps/user/src/actions/checkout.test.ts apps/user/src/views/CheckedOutView.test.tsx apps/user/src/frame/LeftPane.test.tsx`
Expected: FAIL (`computerLabel` and the view don't exist; no such link).

- [ ] **Step 3: Implement**
  - Strings: `'checkedOut.title': 'Checked Out to Me'`, `'checkedOut.actions': 'Checked Out Actions'`,
    `'checkedOut.empty': 'Nothing is checked out to you.'`, `'checkedOut.webPage': 'Web Page'`,
    `'checkedOut.unknownComputer': 'Another Computer'`, `'list.computer': 'Computer'`, `'list.checkedOut': 'Checked Out'`,
    and the plural `checkedOutEntry: { one: 'Checked Out to Me, {n} document', other: 'Checked Out to Me, {n} documents' }`.
  - `computerLabel` in `checkout.ts`, by the rule in Global Constraints.
  - `checkedOutColumns()`: Title (`minWidth: 220`), Doc # (80), Version (70), Matter (130), Computer (130: the short
    name, the app under it in the muted style), Checked Out (116, `formatDate(hold.since)`), Edit Date (116); none sorts.
  - Move `RecentList` and `RecentDocuments`' body into `DocumentListPage.tsx` as `ListPage` and `DocumentListPage`;
    `RecentView` keeps its four kinds on them. `CheckedOutView` is
    `<DocumentListPage heading={t('checkedOut.title')} empty={t('checkedOut.empty')} actionsLabel={t('checkedOut.actions')} query={useCheckouts()} columns={checkedOutColumns()} />`.
  - `checkedOutRoute` at `checked-out` in `router.tsx`.
  - `LeftPane`: a `CheckedOutEntry` above `RecentSection`, outside any section, with the `pencil` icon, the label, and
    a badge with `total` from `useCheckouts()` when it is 1 or more. The badge is `--hd-pill` with `--hd-text`, and on
    the current entry `--hd-accent-fill` with `--hd-accent-on-fill`. The link's accessible name is `plural('checkedOutEntry', total)`
    with a count, `t('checkedOut.title')` without one.
  - `fakeApi.ts`'s `defaultRoutes`: `'GET /v1/me/checkouts': page([])`.

- [ ] **Step 4: Run the whole app suite**

Run: `npm test`, `npm run typecheck`, `npm run lint` (from `healdan-web/web`).
Expected: PASS. A test that took the pane's first link for Recent's, or counted calls per page load, is updated to the
new entry, never deleted (Review Focus 4).

- [ ] **Step 5: Commit**

```bash
git add healdan-web/web/apps/user/src healdan-web/web/packages/ui/src/strings/en-US.ts
git commit -m "Add Checked Out to Me to the User app"
```

---

### Task 4: Release… everywhere, with the warning

**Files:**
- Modify: `healdan-web/web/packages/api/src/client.ts`, `client.test.ts` (`send` takes a query)
- Modify: `healdan-web/web/apps/user/src/test/fakeApi.ts` (`send` records it)
- Modify: `healdan-web/web/apps/user/src/actions/checkout.ts` (`release`, `holdKey`, `RELEASE_KEY`)
- Modify: `healdan-web/web/apps/user/src/actions/useCheckout.ts` (busy; `release` goes)
- Create: `healdan-web/web/apps/user/src/actions/ReleaseProvider.tsx`
- Create: `healdan-web/web/apps/user/src/actions/ConfirmRelease.tsx`, `actions/ConfirmRelease.test.tsx`, `actions/actions.module.css` additions
- Create: `healdan-web/web/apps/user/src/actions/Release.test.tsx`
- Modify: `healdan-web/web/apps/user/src/actions/useDocumentActions.ts`, `actions/SelectionActions.tsx`, `actions/CheckoutControls.tsx`
- Modify: `healdan-web/web/apps/user/src/menus/ContextMenus.tsx`
- Modify: `healdan-web/web/apps/user/src/organising/OrganisingProvider.tsx`, `organising/rows.ts` (`rowAfter` and
  `focusLater` move, exported)
- Modify: `healdan-web/web/apps/user/src/frame/Frame.tsx` (mounts `ReleaseProvider`), and any test harness that mounts
  `OrganisingProvider` on its own
- Modify: `healdan-web/web/packages/ui/src/strings/en-US.ts`

**Interfaces:**
- Consumes: Task 1's `elsewhere`; Task 2's `variant="danger"`, `role="alertdialog"`, `lockOpen` and `alertTriangle`;
  Task 3's `computerLabel`, `keys.checkouts`.
- Produces:
  - `Api.send<T>(method, path, body?, query?: Query): Promise<T>`;
  - `release(api: Api, docNumber: number, options?: { elsewhere?: boolean }): Promise<S['DocumentView']>`, which sends
    `{ elsewhere: 'true' }` only when asked;
  - `holdKey(docNumber)` and `RELEASE_KEY = ['hold', 'release'] as const`, exported from `checkout.ts`;
  - `useReleasing(): { askRelease: (documents: S['DocumentView'][]) => void }` and `ReleaseProvider`;
  - `ConfirmRelease({ documents, onConfirm, onCancel, onShowDevices }: { documents: S['DocumentView'][]; onConfirm: () => void; onCancel: () => void; onShowDevices: () => void })`;
  - `DocumentActions['release']`: defined when the selection is one or more documents all checked out to the person
    (`holdState` `yours` or `yourDevice`), labelled `Release…` when any is `yourDevice` and `Release` otherwise, icon
    `lockOpen`, disabled while any of them is busy, performing `askRelease(documents)`.

- [ ] **Step 1: Write the failing tests**
  - `client.test.ts`: "send carries a query": `send('DELETE', '/v1/documents/5/checkout', undefined, { elsewhere: 'true' })`
    fetches `/api/v1/documents/5/checkout?elsewhere=true`.
  - `ConfirmRelease.test.tsx`:
    - "warns about one computer": two documents on `Healdan Helper on MacBook` give the alert dialog
      `Release 2 Documents from MacBook?`, `They're checked out to you on MacBook:`, each name, doc # (`# 10405`),
      app and `Saved <formatDateTime(lastModified)>`; the texts `Changes on MacBook that haven't reached Healdan will be lost.`,
      `Once released, MacBook can't save to these documents again, and the work in its copies can't be carried on there.`,
      `If you get its copies back, upload each one as a new version of its document to rescue those changes.` and
      `If MacBook was lost or stolen, also revoke its sign-in in Signed-in Devices.`; the buttons `Cancel`, which has
      the focus, and `Release 2 Documents`.
    - "names one document": one document gives `Release Grant Agreement – Draft.docx from MacBook?`,
      `It's checked out to you on MacBook:`, `can't save to this document again, and the work in its copy` and the
      button `Release Document`.
    - "groups by computer and counts every document" (Review Focus 1): one in the browser, two on MacBook, one on iMac
      give `Release 4 Documents from Other Computers?`, a group headed `On MacBook` with two names and `On iMac` with
      one, the browser's document unlisted, `Changes on those computers that haven't reached Healdan will be lost.`,
      and the button `Release 4 Documents`.
    - "Esc and Cancel release nothing"; "Signed-in Devices calls onShowDevices".
  - `Release.test.tsx`, through `renderApp('/checked-out', …)`:
    - "releases a browser's check-out at once": `DELETE /v1/documents/10420/checkout` with no query, no dialog, the
      toast `Released 1 document`, and `GET /v1/me/checkouts` asked again.
    - "asks first for another computer's": Release… opens the alert dialog; Cancel sends nothing; Release Document
      sends the query `{ elsewhere: 'true' }`.
    - "names what was refused and releases the rest" (Review Focus 2): of two on MacBook, one answers `409 checked-out`;
      the toast says `Released 1 document`, and the dialog `Some Documents Weren't Released` names the other.
    - "puts the focus on the next row" (Review Focus 3): after releasing the first row, the focus is on the row that
      followed it.
    - "offers Release… in the right-click menu, the toolbar and Check-Out Info", and "offers no Release for someone
      else's check-out or a lock".
    - "opens Signed-in Devices from the warning, over it".

- [ ] **Step 2: Run them and see them fail**

Run: `npx vitest run packages/api/src/client.test.ts apps/user/src/actions/ConfirmRelease.test.tsx apps/user/src/actions/Release.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement**
  - Strings (exact): `'checkout.releaseAsk': 'Release…'`, `'release.otherComputers': 'Other Computers'`,
    `'release.on': 'On {computer}'`, `'release.saved': 'Saved {when}'`, `'release.devices': 'Signed-in Devices'`,
    `'release.refusedTitle': "Some Documents Weren't Released"`, `'release.lostOne': "Changes on {computer} that haven't reached Healdan will be lost."`,
    `'release.lostMany': "Changes on those computers that haven't reached Healdan will be lost."`,
    `'release.rescueOne': 'If you get its copies back, upload each one as a new version of its document to rescue those changes.'`,
    `'release.rescueMany': 'If you get their copies back, upload each one as a new version of its document to rescue those changes.'`,
    `'release.stolenOne': 'If {computer} was lost or stolen, also revoke its sign-in in {devices}.'`,
    `'release.stolenMany': 'If one of them was lost or stolen, also revoke its sign-in in {devices}.'`,
    `'release.titleOne': 'Release {name} from {computer}?'`; and the plurals
    `releaseTitle: { one: 'Release {n} Document from {computer}?', other: 'Release {n} Documents from {computer}?' }`,
    `releaseHeldOn: { one: "It's checked out to you on {computer}:", other: "They're checked out to you on {computer}:" }`,
    `releaseNoMore: { one: "Once released, {computer} can't save to this document again, and the work in its copy can't be carried on there.", other: "Once released, {computer} can't save to these documents again, and the work in its copies can't be carried on there." }`,
    `releaseNoMoreMany: { one: "Once released, they can't save to this document again, and the work in their copies can't be carried on there.", other: "Once released, they can't save to these documents again, and the work in their copies can't be carried on there." }`,
    `releaseConfirm: { one: 'Release Document', other: 'Release {n} Documents' }`,
    `released: { one: 'Released {n} document', other: 'Released {n} documents' }`.
    `{devices}` is drawn as a link-styled button, so the sentence reads whole. For two or more computers the title is
    `plural('releaseTitle', n, { computer: t('release.otherComputers') })`.
  - `ConfirmRelease`: `Dialog` with `role="alertdialog"`, the `alertTriangle` icon in `--hd-danger-fill` beside the
    title, the lists (one per computer, headed `On …` only when there are two or more), the red box on
    `--hd-danger-tint` with a `--hd-danger-fill` edge and the first sentence bold in `--hd-danger-text`, the note, then
    Cancel with `autoFocus` and the `danger` button. Only `yourDevice` documents are listed, grouped by
    `hold.heldOn.name` in the selection's order; the button counts them all.
  - `ReleaseProvider`, mounted in `Frame` beside `OrganisingProvider`: `askRelease` releases at once when no document is
    `yourDevice`, and otherwise shows `ConfirmRelease`. Releasing runs one `useMutation` with `mutationKey: RELEASE_KEY`
    and the doc #s as its variables: `eachDocument` with `release(api, n, { elsewhere: state === 'yourDevice' })`, then
    `invalidateDocumentLists(queryClient, [])`, `toast(plural('released', done))` when any were, and `Refusals` with
    `t('release.refusedTitle')` and `refusalLines` when any were refused. The focus goes as Delete's does when released
    rows leave the list: move `rowAfter` and `focusLater` out of `OrganisingProvider.tsx` into `organising/rows.ts`,
    exported, and use them from both providers. It renders `SignedInDevices` when the warning asks for it, over the
    warning.
  - `useCheckout`: `busy` is also true while a `RELEASE_KEY` mutation's variables include the document; its `release`
    goes, so every release is the provider's.
  - `useDocumentActions.release` as in Interfaces; `checkIn` stays as it is.
  - `SelectionActions` shows `actions.release` in Check Out's place when it is defined. `DocumentMenuItems` shows
    `actions.checkIn` when defined and `actions.release` when defined, else `actions.checkOut`. `CheckoutControls`
    shows Check In and Release for `yours` as now, and `Release…` for `yourDevice`, both through `askRelease([document])`.

- [ ] **Step 4: Run the whole web check**

Run: `npm run generate`, `npm run typecheck`, `npm run lint`, `npm test`, `npm run build` (from `healdan-web/web`).
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add healdan-web/web/packages healdan-web/web/apps/user/src
git commit -m "Release a check-out another of your computers holds, after a warning"
```

---

### Task 5: End to end, and accessibility

**Files:**
- Create: `healdan-web/web/e2e/checked-out.spec.ts`
- Modify: `healdan-web/web/e2e/a11y.spec.ts`
- Modify: any spec the run shows walking the left pane from its first stop (Review Focus 4)

**Interfaces:**
- Consumes: `e2e/helpers.ts`' `signIn`, `apiContext`, `deviceToken`, `authorized`, `call`, `matterFolders`,
  `specsFolder`, `fileDocument`.

- [ ] **Step 1: Write the spec.** `checked-out.spec.ts`, in a folder of its own from `specsFolder`: file a `.docx`
  through the API with `deviceToken(api, run)`, check it out with that token and `{ app: 'Word' }`, sign the page in,
  go to `/app/checked-out`.
  - The row named after the file shows `e2e <run>` and `Word` in its Computer cell, and the entry's badge equals the
    list's row count.
  - Select it, choose `Release…`: the alert dialog is named `Release <file> from e2e <run>?`; Cancel leaves the row.
  - Choose `Release…` again, then `Release Document`: the row goes, `GET /api/v1/documents/<n>` (the token) has
    `hold: null`, and a save from the token with `ifHeld: true` is `409` with code `not-held`.
  - Sign the token out (`DELETE /api/v1/auth/tokens/current`) in `afterAll`.
- [ ] **Step 2: Extend `a11y.spec.ts`:** run axe on `/app/checked-out` with a document checked out on a device token,
  and on the warning, in the light and the dark theme, as the Recent views are checked.
- [ ] **Step 3: Run the browser tests** against a smoke stack: `docker/smoke-test.sh` (it builds the image), or, on a
  running smoke stack, `BASE=http://127.0.0.1:<port> WEB_NETWORK=bridge HEALDAN_INDEXER_FAST=1 docker/tests/test-web.sh`
  after the Developer Guide's Tests page's Indexer change.
  Expected: every spec passes in Chromium and WebKit; fix any spec the new entry shifted.
- [ ] **Step 4: Commit**

```bash
git add healdan-web/web/e2e
git commit -m "Test Checked Out to Me and its warning in the browsers"
```

---

### Task 6: Docs and records

**Files:**
- Modify: `site/content/user/documents/checking-out-and-checking-in/index.md` (and two new pictures beside it)
- Modify: `site/content/user/getting-started/getting-around/index.md`
- Modify: `site/content/user/getting-started/signed-in-devices/index.md`
- Modify: `site/content/user/office/when-things-go-wrong/index.md`
- Modify: `site/content/developer/api.md`
- Modify: `docs/superpowers/specs/2026-11-10-m6-user-app-design.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: The User Guide**, as spec §8 lists:
  - Checking Out and Checking In: a section **Checked Out to Me** (the entry, its count, the columns, and that it's
    check-outs only); in "On Which Computer" and "When Someone Else Has It", releasing a check-out that's yours on
    another computer, the warning, and the two rescues (on that computer, the Helper's Save as New Document; or the
    copy dropped on the document's row as a new version). Pictures `checked-out-to-me.png` and `release-warning.png`
    from the demo stack (the Developer Guide's "A Demo Firm for Screenshots"), with alt text that says what they show.
  - Getting Around, The Left Pane: the entry above Recent.
  - Signed-in Devices: a section **A Lost or Stolen Computer**: revoke its sign-in here, and release its check-outs
    from Checked Out to Me.
  - When Things Go Wrong, Your Check-Out Ended: "you released it from Checked Out to Me in the app".
- [ ] **Step 2: The Developer Guide's API page:** `elsewhere=true` beside `DELETE /documents/{docNumber}/checkout`.
- [ ] **Step 3: The records:** "changed after M6" notes in the M6 spec's §6.1, §6.2, §6.7 and §8.1, each pointing to
  this spec; a line for Checked Out to Me in the roadmap's "User app: further enhancements" row.
- [ ] **Step 4: Check the site**

Run: `python3 -m unittest discover -s .github/scripts/tests`, `hugo --source site --panicOnWarning` (Hugo 0.168.0, if
installed; CI's `Docs site` check runs it otherwise), `python3 .github/scripts/check-site-sources.py`,
`python3 .github/scripts/check-site-links.py`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add site docs
git commit -m "Document Checked Out to Me and releasing another computer's check-out"
```

---

### Task 7: Every check, and the pull request

- [ ] **Step 1: Bring the branch up to date:** `git fetch origin && git merge origin/master`, then
  `npm run generate` again.
- [ ] **Step 2: Run the quick checks:** `python3 .github/scripts/check-fontawesome-icons.py`,
  `python3 .github/scripts/check-logicaldoc-doc-links.py`, `python3 .github/scripts/check-logicaldoc-hosts.py`,
  `python3 .github/scripts/check-web-licences.py`. Expected: each passes.
- [ ] **Step 3: Run `healdan-api`'s whole suite** with the Java test command, without `-Dtest`. Expected: 0 failures.
- [ ] **Step 4: Run the whole web check** (Global Constraints). Expected: PASS.
- [ ] **Step 5: Open the pull request** to `master` with the spec, the plan and the mockup in it, a summary, and the
  test results, ending with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`; then read its CI.
