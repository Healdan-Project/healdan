# The User App's List Columns: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The User app's lists get resizable columns, a menu on each heading, a Columns panel, a layout remembered per
kind of list (and one beside the reading pane), a sort on every column of every list, a Last Used column on Recent, and
eleven new document columns, in three pull requests ([Healdan-Project/healdan#234](https://github.com/Healdan-Project/healdan/issues/234)).

**Architecture:** `@healdan/ui`'s `Table` gains options a list switches on: React Aria's `ResizableTableContainer` and
`ColumnResizer`, a heading menu sharing the rows' `ContextMenu`, a heading drag of its own type, and a Columns button
with a `ColumnsPanel` popover. The User app keeps one `ColumnLayout` per kind of list in `localStorage` through
`@healdan/shell`'s preferences, sorts the lists the server sends whole in the browser with the server's rules, and asks
the server to sort the paged ones. `healdan-api` sends Recent's `lastUsed` (PR 2), then six new document fields and new
sort keys, which `healdan-core`'s `FolderListing`, the scopes' search and `TrashStore` sort in PostgreSQL (PR 3).

**Tech Stack:** React, TypeScript, React Aria Components 1.22.1 through `@healdan/ui`, TanStack Router and Query, Vitest
and Testing Library, Playwright; Java 25, JAX-RS (CXF), JUnit 4 with `AbstractHealdanTestCase` and `ApiTestCase` on
PostgreSQL 18; Hugo.

**Spec:** [`docs/superpowers/specs/2026-11-19-list-columns-design.md`](../specs/2026-11-19-list-columns-design.md)
(approved by the owner on 2026-11-19), with its
[mockups](../specs/2026-11-19-list-columns-mockups/01-where-columns-change.html). Read the spec first. Where the mockups
and the spec differ, the spec wins.

## Global Constraints

- **Start conditions.** PR 1 (Tasks 1–2) starts now. PR 2 (Tasks 3–12) starts once the Admin app's PR A has merged:
  `git fetch origin && git show origin/master:healdan-web/web/packages/shell/src/preferences.ts | head -3` prints the
  file's header. PR 3 (Tasks 13–18) starts once the PostgreSQL switch (its PR B) has merged:
  `git ls-tree origin/master healdan-core/src/main/resources/healdan/db/migration/mariadb` prints nothing. If a check
  fails, stop and tell the owner. Each PR branches from `master` after the previous one merged.
- Every new source file opens with `SPDX-FileCopyrightText: 2026 The Healdan Project` and
  `SPDX-License-Identifier: LGPL-3.0-only` in its comment syntax. Docs and mockups don't.
- No upstream (LogicalDOC) file changes, in any of the three PRs (spec §7.1).
- `Table`'s new abilities are options; a list that passes none of them renders and behaves exactly as before, including
  the Admin app's lists (spec §2).
- No new npm package. React Aria's drag and drop (`useDragAndDrop`, `DropIndicator`) is never imported: the heading drag
  uses the browser's own, as the row drag does (spec §4.6).
- Screens import React Aria only through `@healdan/ui`; no `dangerouslySetInnerHTML`.
- App strings go in `healdan-web/web/packages/ui/src/strings/en-US.ts`, US English, labels in Title Case; this plan's
  copy is exact. Docs are British (the Developer Guide's Writing Docs page).
- Column ids are the sort names: `title`, `docNumber`, `version`, `author`, `edited`, `matter`, `client`, `location`,
  `filed`, `size`, `type`, `class`, `subclass`, `typist`, `from`, `to`, `sent`, `lastUsed`, `computer`, `checkedOut`.
- A layout is kept under `healdan.columns.<kind>` as `{"v":1,"shown"?:string[],"widths":{[id]:number}}`; `shown` only
  once columns were chosen (spec §4.8).
- Rows with no value sort last in both directions; ties keep the list's own order (server: `d.ld_id` in the sort's
  direction, as `FolderListing.order` does).
- `openapi.yaml` changes in the same commit as the behaviour; then run `npm run generate` in `healdan-web/web`.
- PR 3's SQL is PostgreSQL only, by the PostgreSQL spec's §7.3–§7.4: `ListingSql.nullsLast` for NULLs, `lower()` for
  case-insensitive order, numbers cast only behind `ListingSql.digits`, no `::` in SQL given to CE's helpers.
- Java tests, locally, in the JDK 25 container (the owner's Mac has no Maven), from the repository root, with the test
  database up (`docker/tests/test-db.sh start`):
  `docker run --rm --network healdan-test -e HEALDAN_TEST_DB_URL=jdbc:postgresql://healdan-test-db:5432/ -v "$PWD":/src -w /src -v healdan-m2-columns:/root/.m2/repository -v "$PWD/docker/maven-settings.xml":/root/.m2/settings.xml:ro maven:3.9-eclipse-temurin-25 bash -c 'touch /root/logicaldoc-dev.properties && exec mvn "$@"' mvn -B -ntp -pl healdan-api -am package -Dgwt.skipCompilation=true -Dmaven.antrun.skip=true -Dhealdan.web.skip=true -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
  (`-pl healdan-core` for core's tests). Seed the volume from `healdan-m2` first
  (`docker run --rm -v healdan-m2:/from:ro -v healdan-m2-columns:/to alpine cp -a /from/. /to/`). It takes several
  minutes: run it in the background.
- The web checks, from `healdan-web/web`: `npm ci --ignore-scripts` (once), `npm run generate`, `npm run typecheck`,
  `npm run lint`, `npm test`, `npm run build` (Node 24). One file: `npx vitest run <path>`.
- Keep real hostnames, internal addresses and personal names out of commits, PRs, fixtures and docs; the demo firm's and
  the fixtures' names are fine.
- Commit messages are in the repository's style and end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A layout kept on a wide screen, opened on a narrow one.** Widths kept at 1,920 pixels sum past a 1,280 window's
   list. A person expects the list to scroll sideways, every column at least its least width, rows still 34 pixels, and
   nothing kept changed. Pinned in Task 6 ("a kept layout wider than the list scrolls sideways and keeps each column's
   width") and Task 12 (`columns.spec.ts`, "a wide layout scrolls sideways in a narrow window").
2. **A refetch while the panel is open or a column is being resized.** The app refetches when the window regains focus
   (M6 §10.2). A person expects the panel to stay open with its focus, and no width to jump back. Pinned in Task 11
   ("a refetch while the Columns panel is open keeps it open and its focus").
3. **A matter that isn't `client.matter – name`.** A legacy number with letters, a matter with no name, a display the
   server formats otherwise. A person expects the cell to show what the server sends, and such a matter to sort last
   both ways, never to break the list. Pinned in Task 1 ("a matter with no name shows its number"), Task 10 (the
   comparer's "a matter number without digits sorts last both ways") and Task 14
   (`testMatterSortPutsANumberWithoutDigitsLast`).
4. **Long values in the new columns.** An email's To with forty recipients, a Class of 4,000 characters. A person
   expects one line ending in an ellipsis, the whole value in the tooltip, and the row 34 pixels high. Pinned in Task 17
   ("a long To ends in an ellipsis and its tooltip holds it all").
5. **Rows equal under the sort.** Two Recent documents with the same title, or the same Last Used to the second. A person
   expects the order not to shuffle when the list refetches. Pinned in Task 10 ("ties keep the list's own order in both
   directions").

## Pull Requests

| PR | Tasks | Starts |
|---|---|---|
| 1. The Matter column | 1–2 | now |
| 2. Columns in the browser | 3–12 | after the Admin app's PR A merges |
| 3. The new columns and sorts | 13–18 | after the PostgreSQL switch merges |

Paths: `WEB` = `healdan-web/web`, `UI` = `WEB/packages/ui/src`, `SH` = `WEB/packages/shell/src`, `US` =
`WEB/apps/user/src`, `HA` = `healdan-api/src/main/java/com/logicaldoc/healdan/api`, `HAT` = its tests, `HC` =
`healdan-core/src/main/java/com/logicaldoc/healdan`, `HCT` = its tests, `YAML` =
`healdan-api/src/main/resources/healdan/api/openapi.yaml`.

---

# PR 1: The Matter column

### Task 1: The Matter cell shows number and name, and Matter shares the spare width

**Files:**
- Modify: `US/views/listColumns.tsx` (`MatterCell`, `matterColumn`, `hitColumns`, `checkedOutColumns`),
  `US/views/views.module.css` (`.matterClient`, `.matterName` and their `:hover`/`:focus-within` rules, lines ~172–211;
  keep `.matterCell` and `.muted`)
- Create: `US/views/listColumns.test.tsx`
- Modify: `US/views/SearchView.test.tsx` (the test at lines ~82–99 that asserts the hover swap)

**Interfaces:**
- Produces: `MatterCell({ matter }: { matter: S['MatterRef'] })` renders `matter.display` as its text and
  `` `${matter.display} · ${matter.client.name}` `` as its `title` (changed in PR 1's review: the client alone at first); `matterColumn()` keeps its signature.

- [ ] **Step 1: Write the failing tests**
  - `SearchView.test.tsx`: replace the hover-swap test with "shows the matter's number and name in the Matter column,
    with the client as its tooltip": the Matter cell's text is the fixture's `display` (`'1001.1 – Commercial Lease'` or
    whatever that test's fixture holds), an element in it has `title` equal to the client's name (`'Brightwell'`), no
    element in it has a class matching `/matterName|matterClient/`, and `viewsCss` doesn't match `/\.matterName/`.
  - `listColumns.test.tsx`:
    - "a matter with no name shows its number": render `MatterCell` with `display: '1001.7'`; text is `'1001.7'`.
    - "gives Matter a share of the spare width beside Title and Author": in `hitColumns('documents')`, Title is
      `width: '2fr', minWidth: 220`, Matter `width: '1.5fr', minWidth: 100`, Author `width: '1fr'`; Doc #, Version and
      Edit Date keep numbers.
    - "gives Checked Out to Me's and the Folders scope's Matter the same share": `checkedOutColumns()`'s Matter is
      `'1.5fr'`/100 with Title `'2fr'`/220; `hitColumns('folders')`'s Matter is `'1.5fr'`/100 beside Title `'2fr'`.

- [ ] **Step 2: Run them to see them fail**

Run: `cd healdan-web/web && npx vitest run apps/user/src/views/listColumns.test.tsx apps/user/src/views/SearchView.test.tsx`
Expected: FAIL on the new tests.

- [ ] **Step 3: Implement.** `MatterCell` is one `span.matterCell` with the display as text and the client's name as
  `title`. Drop the hover-swap CSS. Set the widths above (spec §4.1). The left pane's `MatterLabel` doesn't change.

- [ ] **Step 4: Run the web checks**

Run: `npm run typecheck && npm run lint && npm test`
Expected: all pass. Header-order tests are unchanged (no column moved).

- [ ] **Step 5: Commit**: `List columns: the matter's name in the Matter column, which shares the spare width`

### Task 2: PR 1's docs, every check, and the pull request

**Files:**
- Modify: `site/content/user/getting-started/getting-around/index.md` (the list's description, ~line 76: the Matter
  column shows the matter's number and name, the client on hover), `docs/ROADMAP.md` (the "User app: further
  enhancements" row: PR 1 built)

- [ ] **Step 1: Edit the docs** in British English, labels in bold as the app shows them.
- [ ] **Step 2: Run every check**: the web checks; `python3 .github/scripts/check-fontawesome-icons.py`,
  `check-logicaldoc-doc-links.py`, `check-logicaldoc-hosts.py`; `hugo --source site --panicOnWarning` and
  `python3 .github/scripts/check-site-sources.py && python3 .github/scripts/check-site-links.py`.
  Expected: all pass.
- [ ] **Step 3: Check it by hand** against the spec's §4.1 test: a stack (`docker compose up -d --build` with the smoke
  overlay) at 1,920 and 1,280 pixels: Recent Documents shows a matter's name whole at 1,920; Title is at least 220 at
  1,280.
- [ ] **Step 4: Commit** (`List columns: the User Guide on the Matter column`), push, and open the pull request
  "User app: the matter's name in the list, and the list columns' spec and plan (#234, PR 1)". PR 1 carries the spec,
  its mockups and this plan, committed on the design branch before Task 1. Name the screenshot's problem and what PRs 2
  and 3 hold. Add its number to the roadmap row in a follow-up commit.

---

# PR 2: Columns in the browser

### Task 3: Recent sends Last Used

**Files:**
- Modify: `HA/model/DocumentView.java`, `HA/model/FolderView.java` (a last component `@JsonInclude(NON_NULL) Date
  lastUsed`, and `withLastUsed(Date)`), `HA/ApiViews.java` (every constructor call passes `null`),
  `HA/DocumentResource.java` (`recent`, ~L231–258, keeps `recent.lastAccess()`), `HA/FolderResource.java` (`recent`,
  ~L96–111, keeps `lastChange()`), `YAML` (`DocumentView`, `FolderView`: optional `lastUsed`, `date-time`, "Only in
  Recent's lists: when the caller last used it")
- Test: `HAT/ListsTest.java`

**Interfaces:**
- Produces: `DocumentView.lastUsed` and `FolderView.lastUsed` (`string`, date-time, optional) in the generated types;
  `DocumentView withLastUsed(Date lastUsed)`, `FolderView withLastUsed(Date lastUsed)`.

- [ ] **Step 1: Write the failing tests** in `ListsTest`:
  - `testRecentCarriesLastUsed`: after the fixture's events, each item of `GET /v1/documents/recent` has `lastUsed`
    equal to `RecentDocuments.get().list(user, Set.of(), d -> true)`'s `lastAccess` for its doc (to the millisecond), and
    each of `GET /v1/folders/recent` equal to `RecentFolders.get().list(user)`'s `lastChange`.
  - `testOnlyRecentCarriesLastUsed`: `GET /v1/documents/{n}`, a folder's `children` and a search answer have no
    `lastUsed` key.
- [ ] **Step 2: Run them to see them fail** (`-Dtest=ListsTest`). Expected: FAIL, no `lastUsed`.
- [ ] **Step 3: Implement** as listed. No SQL changes.
- [ ] **Step 4: Run `ListsTest`, `DocumentResourceTest`, `FolderResourceTest` and `RouteCoverageTest`.** Expected: PASS
  (the contract check covers the new property).
- [ ] **Step 5: Commit**: `List columns: Recent says when each item was last used`

### Task 4: JSON preferences

**Files:**
- Modify: `SH/preferences.ts` and `SH/preferences.test.ts`; `US/preferences.ts` (export the two new functions)

**Interfaces:**
- Produces: on `Preferences`: `readJson(name: string): unknown` (undefined when nothing is kept, storage is refused or
  the text isn't JSON) and `keepJson(name: string, value: unknown): void` (does nothing when refused or full). The User
  app exports `readJson` and `keepJson` beside `readNumber`, `keepNumber` and `forget`.

- [ ] **Step 1: Write the failing tests** in `SH/preferences.test.ts`: "keeps and reads JSON under its prefix"
  (`keepJson('columns.folder', { v: 1 })` → `localStorage['healdan.columns.folder']` is `'{"v":1}'`, read back equal);
  "reads nothing from text that isn't JSON" (`'{v:'` → undefined); "reads and keeps nothing when storage is refused"
  (a throwing getter, and `setItem` throwing a quota error, both without throwing).
- [ ] **Step 2: Run** `npx vitest run packages/shell/src/preferences.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** with `JSON.parse`/`JSON.stringify` inside the existing try/catch pattern.
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: preferences keep JSON`

### Task 5: Column layouts, kept per kind of list

**Files:**
- Create: `US/views/columnLayouts.ts`, `US/views/columnLayouts.test.ts`

**Interfaces:**
- Consumes: `readJson`, `keepJson`, `forget` (Task 4).
- Produces:

```ts
export type DocumentListKind = 'folder' | 'documents' | 'all' | 'emails' | 'recentDocuments' | 'recentEmails' | 'checkedOut' | 'beside'
export type ListKind = DocumentListKind | 'folders' | 'matters' | 'clients' | 'recentFolders' | 'recentClients' | 'trashMine' | 'trashEveryone' | 'trashRemoved'
export interface ColumnLayout { shown?: string[]; widths: Record<string, number> }
export const DEFAULT_SHOWN: Record<DocumentListKind, string[]>
export function readLayout(kind: ListKind): ColumnLayout
export function keepLayout(kind: ListKind, layout: ColumnLayout): void
export function forgetLayout(kind: ListKind): void
/** The ids to show, in order: Title first; unknown or unoffered ids dropped; the kind's defaults when `shown` is absent */
export function shownIds(kind: ListKind, offered: string[], layout: ColumnLayout): string[]
export function useColumnLayout(kind: ListKind, offered: string[]): {
  shown: string[]; widths: Record<string, number>
  resized(widths: Record<string, number>): void; resetWidth(id: string): void
  show(id: string): void; hide(id: string): void; move(id: string, to: number): void; reset(): void
}
```

`DEFAULT_SHOWN` (spec §4.8, PR 2's values): `folder` title, docNumber, version, author, edited; `documents` and `all`
title, docNumber, version, matter, author, edited; `emails` and `recentEmails` title, docNumber, matter, author, edited
(`recentEmails` then lastUsed); `recentDocuments` title, docNumber, version, matter, author, edited, lastUsed;
`checkedOut` title, docNumber, version, matter, computer, checkedOut, edited; `beside` title, docNumber, edited.

- [ ] **Step 1: Write the failing tests** (`columnLayouts.test.ts`):
  - "reads the kind's defaults when nothing is kept, when storage is refused, and from a value of another shape or
    version" (`{"v":2,...}`, `'[]'`, widths with a string).
  - "puts Title first and drops ids the kind doesn't offer" (`shown: ['author','title','nope']` → `['title','author']`).
  - "keeps no `shown` for a resize alone, so a later release's defaults still apply": `resized({ matter: 240 })` keeps
    `{"v":1,"widths":{"matter":240}}`; with `DEFAULT_SHOWN.emails` changed in the test, `shown` follows it.
  - "keeps `shown` once a column is shown, hidden or moved", with a column added later starting hidden.
  - "`resetWidth` forgets one width; `reset` forgets the whole layout".
  - "beside skips a column the list doesn't offer": `shownIds('beside', ['title','docNumber','edited'], { shown:
    ['title','lastUsed','edited'], widths: {} })` → `['title','edited']`.
  - "moves within the shown columns, never before Title": `move('author', 0)` puts it at index 1.
- [ ] **Step 2: Run** `npx vitest run apps/user/src/views/columnLayouts.test.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.** The hook holds the layout in state, writes through `keepLayout` when a change ends, and
  reads once per kind.
- [ ] **Step 4: Run it.** Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: a layout per kind of list, kept in this browser`

### Task 6: `Table` resizes its columns

**Files:**
- Modify: `UI/components/Table.tsx`, `Table.module.css`, `Table.test.tsx`; `UI/strings/en-US.ts`

**Interfaces:**
- Produces, on `TableProps`:

```ts
/** Columns resize by their edges; widths in pixels by column id, as kept */
resizing?: { widths: Record<string, number>; onResizeEnd: (widths: Record<string, number>) => void; onReset: (column: string) => void }
```

  and on `TableColumn`: `minWidth` (already there) becomes each column's least width when resizing (48 by default; the
  views give Title 160). Internally, `Table` keeps each column's `startResize` from its render props, for Task 7's
  **Resize Column**.

- [ ] **Step 1: Write the failing tests** (`describe('Table resizing')`):
  - "draws no resizer without `resizing`, as before" (no `.react-aria-ColumnResizer`, no `input[type=range]` in the
    header).
  - "draws a resizer on every heading's edge, 24 pixels wide" (each columnheader holds one; its class has the target's
    width).
  - "draws a kept width on its column" (`widths: { docNumber: 140 }` → the Doc # columnheader's style width is `140px`).
  - "puts a column's default back on a double-click on its edge" (`onReset` called with `'docNumber'`).
  - "a kept layout wider than the list scrolls sideways and keeps each column's width" (widths summing to 3,000: the
    frame's scroll container allows horizontal scrolling, each width as kept).
- [ ] **Step 2: Run** `npx vitest run packages/ui/src/components/Table.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement.** With `resizing`, wrap the `Virtualizer` in `ResizableTableContainer` (`onResizeEnd` hands
  React Aria's whole map, converted to a record of pixel widths: the resized column and those it froze, spec §4.3); each
  `Column` gets the kept width or its default; the heading's render prop draws a `ColumnResizer` (a 24-pixel target, the
  accent line on hover and while resizing, `onDoubleClick` → `onReset`). Strings: `'columns.resizer': 'Resize {column}'`.
- [ ] **Step 4: Run** the file, then `npm run typecheck && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: Table resizes its columns`

### Task 7: The heading menu

**Files:**
- Modify: `UI/components/Table.tsx`, `UI/components/ContextMenu.tsx` (only if the subject union needs it),
  `Table.test.tsx` (the test at ~L429–431 that a header keeps the browser's menu), `UI/strings/en-US.ts`

**Interfaces:**
- Consumes: Task 6's resize starters.
- Produces, on `TableProps`:

```ts
/** A document list's columns: moved, hidden, and chosen in `panel` (Task 9 draws it) */
choice?: { onMove: (column: string, to: number) => void; onHide: (column: string) => void; panel: ReactNode }
/** Reset Columns */
onResetColumns?: () => void
/** Sort by Relevance, offered while `sort` is set */
relevance?: { onSelect: () => void }
```

  The heading menu opens when any of `onSort`, `resizing`, `choice` or `onResetColumns` is given; otherwise a heading
  keeps the browser's menu, as today.

- [ ] **Step 1: Write the failing tests** (`describe('Table heading menu')`):
  - "opens a heading's menu on a right-click and on Shift+F10, its first item focused, and gives the focus back on Esc".
  - "offers Sort Ascending, Sort Descending, Resize Column and Reset Columns on every list, and Move Left, Move Right,
    Hide Doc #, Choose Columns… on a document list" (item names exact).
  - "leaves Move and Hide off Title, Move Left off the column after Title, and Move Right off the last".
  - "offers Sort by Relevance only while sorted" and calls `relevance.onSelect`.
  - "Sort Descending on a heading sorts by it" (`onSort({ column: 'docNumber', direction: 'descending' })`).
  - "keeps the browser's menu on a heading when no option is given" (the old assertion, kept for a bare Table).
- [ ] **Step 2: Run** the file. Expected: FAIL.
- [ ] **Step 3: Implement.** Headings carry `data-column-key`. Table's one `ContextMenu` targets
  `[data-row-key], [data-column-key]` with a subject `{ row: string } | { column: string }`, since a nested menu can't
  see inside Table's (`ContextMenu.find`). **Resize Column** calls the column's `startResize`. **Choose Columns…** sets
  `Table`'s own `panelOpen` state, which Task 9's popover reads. Strings:
  `'columns.menu': '{column} Column'`, `'columns.sortAscending': 'Sort Ascending'`, `'columns.sortDescending': 'Sort
  Descending'`, `'columns.sortByRelevance': 'Sort by Relevance'`, `'columns.resize': 'Resize Column'`,
  `'columns.moveLeft': 'Move Left'`, `'columns.moveRight': 'Move Right'`, `'columns.hide': 'Hide {column}'`,
  `'columns.choose': 'Choose Columns…'`, `'columns.reset': 'Reset Columns'`.
- [ ] **Step 4: Run** the file and `npm run typecheck && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: a menu on each heading`

### Task 8: Dragging a heading

**Files:**
- Modify: `UI/components/Table.tsx`, `Table.module.css`, `Table.test.tsx`

**Interfaces:**
- Consumes: `choice.onMove` (Task 7).
- Produces: `export const COLUMN_TYPE = 'application/x-healdan-column'` from `@healdan/ui`.

- [ ] **Step 1: Write the failing tests** (`describe('Table heading drag')`):
  - "drags a heading between two others and reports the move" (dispatch `dragstart` on Author's heading, `dragover` and
    `drop` past Doc #'s midpoint → `onMove('author', 2)`; a `DataTransfer` built in the test, as `filing.spec.ts` does).
  - "carries only the column's type, never the rows'" (the drag's `types` is `[COLUMN_TYPE]`).
  - "can't drag Title, nor drop before it" (no `draggable` on Title; a drop before it → index 1).
  - "a drag that ends where it began moves nothing", and "a press that doesn't move sorts as a click does".
- [ ] **Step 2: Run** the file. Expected: FAIL.
- [ ] **Step 3: Implement** in `Table`'s existing `onDragStart` (a heading's start sets `COLUMN_TYPE` and the column id;
  a row's is unchanged), with `dragover`/`drop` on the heading row only, a 2-pixel accent drop line, and only when
  `choice` is given. Check that the app's drop targets (`FolderView` and the tree accept `Files` or `ROWS_TYPE` only)
  ignore it.
- [ ] **Step 4: Run** the file. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: drag a heading to move its column`

### Task 9: The Columns button and panel

**Files:**
- Create: `UI/components/ColumnsPanel.tsx`, `ColumnsPanel.module.css`, `ColumnsPanel.test.tsx`
- Modify: `UI/components/Table.tsx` (the button at the heading row's end, and the popover), `UI/icons.ts` (Tabler's
  `columns-3` as `columns`), `UI/index.ts`, `UI/strings/en-US.ts`

**Interfaces:**
- Produces:

```ts
export interface ColumnsPanelProps {
  /** "For Recent Documents" */
  forLabel: string
  /** Every column the list offers, in spec §4.7's order */
  offered: { id: string; name: string }[]
  shown: string[]
  onShow: (id: string) => void; onHide: (id: string) => void; onMove: (id: string, to: number) => void; onReset: () => void
}
export function ColumnsPanel(props: ColumnsPanelProps): JSX.Element
```

  `Table` draws Task 7's `choice.panel` (the `ColumnsPanel` the view builds) in a popover, opened by its **Columns**
  button and by **Choose Columns…** through the one `panelOpen` state.

- [ ] **Step 1: Write the failing tests**:
  - `ColumnsPanel.test.tsx`: "titles itself Columns, with its list under it"; "lists the shown columns in order, Title
    first with Always Shown and no tick to change, then More Columns"; "ticking shows a column at the end of Shown, and
    unticking hides it"; "Move Up and Move Down move the focused column, never above Title"; "Reset to Default calls
    onReset"; "passes axe in light and dark".
  - `Table.test.tsx`: "the Columns button and Choose Columns… open the same panel, and Esc gives the focus back to what
    opened it"; "draws no Columns button without `choice`".
- [ ] **Step 2: Run** both files. Expected: FAIL.
- [ ] **Step 3: Implement.** The panel is `@healdan/ui`'s Dialog content in a Popover; Shown is a list whose rows also
  drag with the browser's drag and drop (Move Up and Move Down are the keyboard's way). Strings: `'columns.button':
  'Columns'`, `'columns.title': 'Columns'`, `'columns.for': 'For {list}'`, `'columns.shown': 'Shown'`,
  `'columns.more': 'More Columns'`, `'columns.alwaysShown': 'Always Shown'`, `'columns.moveUp': 'Move Up'`,
  `'columns.moveDown': 'Move Down'`, `'columns.resetToDefault': 'Reset to Default'`.
- [ ] **Step 4: Run** both files and `npm run typecheck && npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: the Columns button and panel`

### Task 10: The columns on offer, and sorting in the browser

**Files:**
- Modify: `US/views/listColumns.tsx`, `US/views/listColumns.test.tsx`, `UI/strings/en-US.ts`, `UI/components/Table.tsx`
  (`TableColumn.newestFirst`)
- Create: `US/views/compareRows.ts`, `US/views/compareRows.test.ts`

**Interfaces:**
- Produces:

```ts
// listColumns.tsx
export const documentColumns: Record<string, TableColumn<ListRow>>   // title, docNumber, version, matter, client, location, author, filed, size, type, edited, lastUsed, computer, checkedOut
export function offeredFor(kind: DocumentListKind): string[]          // §4.7's order; lastUsed only on recent*, computer and checkedOut only on checkedOut
export const SERVER_SORTS: ReadonlySet<string>                        // PR 2: title, docNumber, version, author, edited
export function columnsFor(kind: ListKind, shown: string[], serverSorted: boolean): TableColumn<ListRow>[]
// compareRows.ts
export function compareRows(column: string, a: ListRow, b: ListRow): number   // ascending; values missing → 0 here, last by sortRows
export function sortRows(rows: ListRow[], sort: TableSort): ListRow[]          // stable: ties and missing values keep the list's order
// Table.tsx
TableColumn.newestFirst?: boolean   // the first click sorts descending
```

  New cells (spec §4.7): Client `matter.client.name`; Location the folder's name with title `"{folder} · {display}"`;
  Date Filed `formatDate(dateFiled)`; Size `formatSize(size)`; File Type `typeLabel(kind)`; Last Used
  `formatDateTime(lastUsed)`; a folder row's document columns blank. A column not in `SERVER_SORTS` has
  `allowsSorting: false` when `serverSorted` (spec §6).

- [ ] **Step 1: Write the failing tests**:
  - `compareRows.test.ts`, one table of cases, by the server's rules: titles ignoring case (`'apple' < 'Banana'`); doc #s
    as numbers (`9 < 10`); versions major then minor (`'1.9' < '1.10'`); Matter by client then matter number (`998.9` <
    `998.10` < `999.1`); "a matter number without digits sorts last both ways"; Client and Location by name ignoring
    case; Size by bytes; dates by time; "missing values last both ways"; "ties keep the list's own order in both
    directions".
  - `listColumns.test.tsx`: "offers every document column, Last Used only on Recent and Computer and Checked Out only on
    Checked Out to Me"; "blank document cells on a folder row"; "Size, File Type and Location's text and tooltips"; "a
    column the server can't sort yet doesn't sort on a paged list"; "date columns sort newest first at their first click".
  - `Table.test.tsx`: "a newest-first column's first click sorts descending".
- [ ] **Step 2: Run** the three files. Expected: FAIL.
- [ ] **Step 3: Implement.** Strings: `'list.location': 'Location'`, `'list.filed': 'Date Filed'`, `'list.size':
  'Size'`, `'list.type': 'File Type'`, `'list.lastUsed': 'Last Used'`; `'columns.kind.folder': 'Folders'`,
  `documents` 'Documents Search', `all` 'Docs & Emails Search', `emails` 'Emails Search', `recentDocuments` 'Recent
  Documents', `recentEmails` 'Recent Emails', `checkedOut` 'Checked Out to Me', `beside` 'Lists Beside the Reading Pane'.
- [ ] **Step 4: Run** them. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: every document column, and sorting in the browser`

### Task 11: The views use the layouts

**Files:**
- Modify: `US/views/FolderView.tsx`, `SearchView.tsx`, `DocResult.tsx`, `DocumentListPage.tsx`, `RecentView.tsx`,
  `CheckedOutView.tsx`, `TrashView.tsx`, `US/router.tsx` (`validateSearch` with `sort`/`order` on `/`, `recent/$kind`
  and `checked-out`), and the views' tests (`RecentView.test.tsx`, `SearchView.test.tsx`, `CheckedOutView.test.tsx`,
  `FolderView.test.tsx`, `TrashView.test.tsx`)

**Interfaces:**
- Consumes: Tasks 5–10.
- Produces: each list's kind: `FolderView` `folder` (a search folder too); `SearchView` its scope (`documents`, `all`,
  `emails`, and widths only for `folders`, `matters`, `clients`); `DocResult` `documents`; Recent `recentDocuments`,
  `recentEmails`, widths only for `recentFolders` and `recentClients`; `checkedOut`; the Trash `trashMine`,
  `trashEveryone`, `trashRemoved` (widths only); every document list `beside` while `useRegionKind(...) === 'preview'`.

- [ ] **Step 1: Write the failing tests**:
  - `RecentView.test.tsx`: "Recent Documents shows Last Used and starts sorted by it, newest first" (headers
    `['Title','Doc #','Version','Matter','Author','Edit Date','Last Used']`, Last Used `aria-sort="descending"`); "sorts
    by Title in the browser and comes back by Last Used" (the address gets `sort=title&order=asc`); "Recent Folders shows
    Last Used and starts sorted by it, newest first"; "Recent Clients starts by Client, A to Z, and sorts by any column".
  - `CheckedOutView.test.tsx`: "starts sorted by Checked Out, newest first, and sorts by any column".
  - `SearchView.test.tsx`: "Sort by Relevance takes a sorted search back to relevance" (the address loses `sort`); the
    Edit Date test now expects `order: 'desc'` at the first click.
  - `FolderView.test.tsx`: "hiding Author from its heading menu keeps the layout for every folder" (another folder's
    headers lack Author); "Choose Columns… shows Client".
  - `DocumentListPage` (through `RecentView.test.tsx`): "the reading pane's list uses its own layout" (dock Preview,
    hide Doc #, close Preview: the full list still has Doc #).
  - "a refetch while the Columns panel is open keeps it open and its focus" (open the panel, fire `focus` on the window,
    resolve the refetch: the dialog and its focused checkbox remain).
  - `TrashView.test.tsx`: "the Trash's columns resize, and keep their sorts".
- [ ] **Step 2: Run** the views' tests. Expected: FAIL.
- [ ] **Step 3: Implement.** Replace `narrowed()` with the `beside` layout; DocResult moves to the `documents` columns.
  One-page lists sort with `sortRows`; paged lists keep the server's sort. Update the existing header-order and
  sort-direction assertions the change moves (RecentView, SearchView, CheckedOutView, FolderView, TrashView) to the new
  defaults and to newest-first dates.
- [ ] **Step 4: Run** `npm run typecheck && npm run lint && npm test`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: every list resizes and sorts, and the document lists choose their columns`

### Task 12: PR 2's browser tests, docs, every check, and the pull request

**Files:**
- Create: `WEB/e2e/columns.spec.ts`
- Modify: `WEB/e2e/a11y.spec.ts` (the heading menu and the panel, both themes), `WEB/e2e/keyboard.spec.ts` (~L224–243,
  if the headers' order or the first click's direction moved), `site/content/user/getting-started/getting-around/index.md`,
  `site/content/developer/the-web-app.md` (the layouts' preference names, `Table`'s options), `site/content/developer/api.md`
  (`lastUsed`), `docs/superpowers/specs/2026-11-10-m6-user-app-design.md` (§6.4's fixed columns become the `beside`
  default, "changed after M6"), `docs/ROADMAP.md`

- [ ] **Step 1: Write `columns.spec.ts`**, on `resizing.spec.ts`'s pattern (`RUN`, a folder of its own in `E2E`, a
  device token for filing, a fresh context per test):
  - "resizes a column by its edge and by keys, and keeps it after a reload" (drag Matter's edge by 80 pixels;
    **Resize Column**, ArrowRight ×3, Enter; reload; widths as left).
  - "adds Client from the Columns panel and moves it by drag and by Move Left".
  - "the reading pane's list keeps its own layout" (dock Preview, hide Doc #, close: the full list has Doc #).
  - "sorts Recent › Documents by Title and back by Last Used".
  - "Reset Columns puts the defaults back".
  - "a wide layout scrolls sideways in a narrow window" (keep widths at 1,920; reload at 1,280: the grid's `scrollWidth`
    exceeds its `clientWidth`, rows 34 pixels high).
- [ ] **Step 2: Docs** (British, labels bold): Getting Around's new section on the list's columns (resizing, the heading
  menu, the Columns panel, moving, sorting any list, Last Used, the list beside the reading pane, Reset Columns, kept in
  this browser); the Developer Guide pages; the M6 spec's §6.4 note; the roadmap row (PR 2 built).
- [ ] **Step 3: Run every check** (Task 2's list), and `docker/smoke-test.sh` for step 9 (`columns.spec.ts`,
  `a11y.spec.ts`, `keyboard.spec.ts`). Expected: all pass.
- [ ] **Step 4: Commit** (`List columns: browser tests and docs for columns in the browser`), push, open "User app:
  choose, move, resize and sort the list's columns (#234, PR 2)", and add its number to the roadmap row.

---

# PR 3: The new columns and sorts

### Task 13: Six new fields on a document

**Files:**
- Create: `HC/listing/DocumentExtras.java`, `HCT/listing/DocumentExtrasTest.java`
- Modify: `HA/model/DocumentView.java` (components `String docClass, String docSubclass, AuthorRef typist, EmailView
  email` before `lastUsed`), create `HA/model/EmailView.java` (`record EmailView(String from, String to, Date sent)`;
  not `EmailFields`, which is `healdan-core`'s reader of a message's fields),
  `HA/ApiViews.java` (`authors(...)` becomes `rows(...)` returning `RowReads`), its callers (`DocumentResource` ~L250,
  `FolderResource` ~L311/L339, `EmailResource` ~L124/L249, `MeResource` ~L87, `DocumentSearch` ~L125), `YAML`
  (`DocumentView`), and their tests

**Interfaces:**
- Produces:

```java
// HC/listing/DocumentExtras.java, a @Component like DocumentAuthors
public record Extras(String docClass, String docSubclass, DocumentAuthors.Author typist, String emailFrom, String emailTo, Date emailSent) {}
public Map<Long, Extras> of(Collection<Long> docIds) // one query per 500 ids on ld_document_ext, ld_name in (docclass, docsubclass, typist, emailfrom, emailto, emailsent); typist's user joined as DocumentAuthors does; emailsent from ld_datevalue
// HA/ApiViews.java
record RowReads(Map<Long, Author> authors, Map<Long, Extras> extras) {}
static RowReads rows(Collection<Document> documents)
```

  `email` is null unless the document's kind is `email`.

- [ ] **Step 1: Write the failing tests**: `DocumentExtrasTest.testReadsAPageInOneQueryPerBatch` (501 documents: two
  statements), `testTypistNamedAsAuthorIs`, `testNothingStoredIsNull`; in `DocumentResourceTest`,
  `testDocumentCarriesClassSubclassTypistAndEmail` (an `.eml` filed with From, To and Sent; a `.docx` with Class and
  Typist: `email` null).
- [ ] **Step 2: Run them** (`-pl healdan-core -Dtest=DocumentExtrasTest`, then `-Dtest=DocumentResourceTest`). Expected:
  FAIL.
- [ ] **Step 3: Implement** as listed; `ApiTestCase`'s contract check holds the new fields.
- [ ] **Step 4: Run** `DocumentExtrasTest`, `DocumentResourceTest`, `FolderResourceTest`, `SearchResourceTest`,
  `EmailResourceTest`, `RouteCoverageTest`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: a document's class, subclass, typist and email fields`

### Task 14: Twelve new sorts for folders and searches

**Files:**
- Modify: `HC/listing/ListingQuery.java` (`Sort`), `HC/listing/FolderListing.java` (`order`, the joins),
  `HC/listing/ListingSql.java` (`digits` public), `HA/Filters.java` (`SORTS`, the 400's text), `YAML` (`ListSort`)
- Test: `HCT/listing/FolderListingTest.java`, `HAT/FolderResourceTest.java` (`testChildrenRefusals` sends `sort=colour`
  instead of `sort=size`), `HAT/SearchResourceTest.java` (`testFiltersAndFacetsRefused` likewise)

**Interfaces:**
- Produces: `ListingQuery.Sort` gains `MATTER, CLIENT, LOCATION, FILED, SIZE, TYPE, CLASS, SUBCLASS, TYPIST, FROM, TO,
  SENT`, and `Filters.SORTS` the names `matter`, `client`, `location`, `filed`, `size`, `type`, `class`, `subclass`,
  `typist`, `from`, `to`, `sent`.

  Each key's expressions (every one preceded by `nullsLast`, then `d.ld_id` in the same direction, as today):
  MATTER the client number then the matter number (`clx`, `mx`), each `case when digits(x, 18) then cast(x as
  decimal(18,0)) end`; CLIENT `lower(c.hd_name)`, joining `hd_client c` on `c.hd_number` = the client number cast behind
  `digits` and `c.ld_deleted = 0`; LOCATION `lower(f.ld_name)` (`ld_folder f` on `d.ld_folderid`), then MATTER's;
  FILED `d.ld_creation`; SIZE `d.ld_filesize`; TYPE a `case` on `lower(d.ld_type)` giving the File Type names' order
  from `FileKinds.extensions(kind)`, then `lower(d.ld_type)`; CLASS and SUBCLASS `lower(...ld_stringvalue)`; TYPIST as
  `authorName` with its own aliases; FROM and TO `lower(...ld_stringvalue)`; SENT `ld_datevalue`.

- [ ] **Step 1: Write the failing tests**: `FolderListingTest.testEachNewSort` (one fixture with every property, some
  missing: each key both ways, missing last both ways, ties by id); `testMatterSortIsNumeric` (998.9 before 998.10);
  `testMatterSortPutsANumberWithoutDigitsLast`; `testClientSortsByName`; `testSortedPagesNeitherRepeatNorSkip` (3 pages of
  2 by Class); `SearchResourceTest.testSortedAsAFolderIs` covers the new keys through its loop over `Filters.SORTS`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement** as listed.
- [ ] **Step 4: Run** `FolderListingTest`, `FolderResourceTest`, `SearchResourceTest`, `RouteCoverageTest`. Expected:
  PASS. The speed goal is checked by hand in Task 18, not by a timed test: CI's runners differ too much to time.
- [ ] **Step 5: Commit**: `List columns: twelve new sorts for folders and searches`

### Task 15: Sorting the Folders, Matters and Clients scopes

**Files:**
- Modify: `HA/SearchResource.java` (`search`'s refusal ~L124–127, `others` ~L167–189), `YAML` (search's `sort`: per
  scope)
- Test: `HAT/SearchResourceTest.java`

**Interfaces:**
- Produces: `scope=folders` takes `sort` `title` or `matter`; `matters` takes `matter` or `client`; `clients` takes
  `client`; any other is `400`. Filters and facets stay refused there.

- [ ] **Step 1: Write the failing tests**: `testFoldersMattersAndClientsSort` (each scope, each key, both ways, pages of 2
  that neither repeat nor skip); `testAScopesSortOverTenThousandSaysAtLeast` (a stub search that never completes:
  `totalAtLeast` set, `total` absent); `testFiltersAndFacetsRefused` keeps `scope=matters&sort=docNumber` → 400.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** With a sort, `others` reads windows until the search is complete or `MAX_WINDOW`, checks
  every hit as today, sorts the shown ones in Java (folders by name ignoring case then matter number; matters by client
  then matter number; clients by name ignoring case; ties by id), pages them, and answers `total` when complete or
  `totalAtLeast` otherwise.
- [ ] **Step 4: Run** `SearchResourceTest`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: the Folders, Matters and Clients searches sort`

### Task 16: Every Trash column sorts

**Files:**
- Modify: `HC/trash/TrashStore.java` (`SORTS` ~L48, `page` ~L321: `nullsLast` on every key), `HA/TrashResource.java`
  (`SORTS` ~L99, the default order ~L131), `YAML` (the Trash's `sort`)
- Test: `HCT/trash/TrashStoreTest.java`, `HAT/TrashResourceTest.java`

**Interfaces:**
- Produces: Trash sorts `docNumber` (`hd_docnumber` cast behind `digits`, folders last), `deletedFrom`
  (`lower(t.hd_frompath)`: the recorded JSON array of names, so items order by their recorded path's steps, spec §5.2),
  `deletedBy` (`lower(t.hd_deletedbyname)`), `removedBy` (`lower(m.hd_removedbyname)`), `destroyed` (as `removed`).
  The first order is ascending for names and numbers, descending for times.

- [ ] **Step 1: Write the failing tests**: `TrashStoreTest.testEveryColumnSorts` (each key both ways, missing last),
  `TrashResourceTest.testPagingAndTheSorts` extended, and `testAnUnknownViewSortOrOrderIsABadRequest` with `sort=colour`.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** both. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: every Trash column sorts`

### Task 17: The new columns in the app

**Files:**
- Modify: `US/views/listColumns.tsx` (Class, Subclass, Typist, From, To, Sent; `SERVER_SORTS` takes every column),
  `US/views/columnLayouts.ts` (`DEFAULT_SHOWN.emails`: title, docNumber, matter, from, sent; `recentEmails` the same then
  lastUsed), `US/views/compareRows.ts`, `US/views/useListing.ts` (`sortColumns`), `US/actions/trash.ts` (`trashSorts`),
  `US/router.tsx`, `US/views/SearchView.tsx` (sorts for the three scopes), `US/views/TrashView.tsx`, `UI/strings/en-US.ts`
  (`'list.class': 'Class'`, `'list.subclass': 'Subclass'`, `'list.typist': 'Typist'`, `'list.from': 'From'`,
  `'list.to': 'To'`, `'list.sent': 'Sent'`), and the tests

- [ ] **Step 1: Write the failing tests**: "every document column sorts on a folder and a search"; "Emails search and
  Recent › Emails start with From and Sent"; "a widened column kept in PR 2 still gets From and Sent" (a kept
  `{"v":1,"widths":{"matter":240}}`); "a long To ends in an ellipsis and its tooltip holds it all" (40 recipients: one
  line, `title` the whole); "the Matters search sorts by Matter and Client; the Trash by every column".
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm run generate && npm run typecheck && npm run lint && npm test`. Expected: PASS.
- [ ] **Step 5: Commit**: `List columns: class, subclass, typist and an email's fields as columns`

### Task 18: PR 3's browser tests, docs, every check, the pull request and the records

**Files:**
- Modify: `WEB/e2e/columns.spec.ts` ("shows Class and sorts a folder by it"), Getting Around (the new columns),
  `site/content/developer/api.md` (the new fields and sorts), `docs/ROADMAP.md` (PR 3 built; #234 done once the owner
  accepts), the M6 spec's §4 row and §16 item (built)

- [ ] **Step 1: Write the spec test and the docs.**
- [ ] **Step 2: Run every check** (Task 2's list), the touched modules' Java tests, and `docker/smoke-test.sh`. Expected:
  all pass.
- [ ] **Step 3: By hand, with the owner** (spec §8.4): a 2,000-document folder (`docker/tests/bulk-documents.sh` on a
  throwaway stack) sorted by each new column in under a second.
- [ ] **Step 4: Commit** (`List columns: browser tests, docs and records for the new columns`), push, open "User app:
  eleven new columns, and every list sorts on the server (#234, PR 3)", and add its number to the roadmap row.
