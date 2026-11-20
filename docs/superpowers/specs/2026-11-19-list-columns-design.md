# The User app's list columns: choosing, ordering, resizing and sorting: design

- **Date:** 2026-11-19
- **Status:** design agreed with the owner on 2026-11-19, question by question and through two mockups in the browser
  ([where columns change](2026-11-19-list-columns-mockups/01-where-columns-change.html),
  [beside the reading pane](2026-11-19-list-columns-mockups/02-beside-reading-pane.html)), with a sample name in place of
  the owner's. Where a mockup and this spec differ, this spec wins.
- **Inputs:**
  - [Healdan-Project/healdan#234](https://github.com/Healdan-Project/healdan/issues/234), from the owner's dogfooding
    punch list of 2026-11-14: the document list's columns should be choosable, reorderable and resizable;
  - the owner's screenshot of 2026-11-19: on a maximised window, Recent Documents' Title column is far too wide and
    Matter too narrow to read the matter;
  - the [M6 spec](2026-11-10-m6-user-app-design.md) §4 (the Daily Driver), §6.1 (the frame, the list's columns, resizing
    the panes), §6.3 (search), §6.4 (the reading pane's narrow list), §9.5 (performance), §10.2 (preferences) and §16
    (later phases);
  - the [Admin app plan](../plans/2026-11-18-admin-app.md) Tasks 1 and 2 (`packages/shell`, and `Table`'s
    `selectionMode="none"`), and the [PostgreSQL plan](../plans/2026-11-18-postgresql.md) (PR B, the switch);
  - React Aria Components 1.22.1 as installed, read for resizing in a virtualised table;
  - a survey of this repository at `84c802acb`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard
and approved. "The app" is the User app at `/app/`; "a list" is one of its tables of rows; "CE" is LogicalDOC Community
Edition.

## 1. Intent

Every list in the app has fixed columns, fixed widths and, in Recent and Checked Out to Me, a fixed order. On a wide
window the spare room goes to Title and Author, so Recent Documents shows a long empty Title and a Matter cut to
"998.1 Healdan …" (owner's screenshot). The owner wants to see what they need without opening each row: to widen a column,
to add the ones they use (Client, Class, an email's sender), to put them in their own order, and to sort any list by any
column "just to help locate something in the list" (owner).

So the app's lists get resizable columns, a menu on each heading, a Columns panel, a layout remembered per kind of list,
and a sort on every column of every list.

## 2. Scope

**In** (owner, unless marked):

- the Matter column shows the matter's number and name, and takes a share of the spare width by default;
- resizing, by pointer and by keyboard, on every list (call: the lists that aren't of documents too);
- a menu on each heading and a **Columns** panel, both (owner: option C of the first mockup);
- choosing and reordering a document list's columns, Title always first;
- a layout remembered in this browser for each kind of list, and one of its own for the list beside the reading pane
  (owner: option B of the second mockup);
- every column of every list sorts, Recent and Checked Out to Me included;
- a **Last Used** column on Recent, which is its own order (owner: option A);
- eleven new document columns: Client, Location, Date Filed, Size, File Type, Class, Subclass, Typist, From, To and Sent
  (owner: all of them).

**Out:**

- **A layout per folder.** One layout per kind of list (owner).
- **Layouts that follow the person between browsers.** The browser keeps only preferences (M6 §10.2); a layout kept on
  the server would be a later change (call).
- **Fitting a column to its content.** The list draws only the rows on screen, so a fit would measure a few rows; a
  double-click puts the default width back instead, as the panes' dividers do (call).
- **Column choosing in the Admin app.** `Table`'s new abilities are options a list switches on; the Admin app's lists
  stay as they are until it chooses them (call).
- **Filters and facets.** Unchanged: the new columns add no filter (call).

**Anti-goals:**

- a sort or a layout that makes a document seem missing: every sort keeps every row, and rows with no value sort last
  either way, as today (M6 §9.5);
- a list that stops working when the browser refuses storage: it falls back to the defaults, as the panes' widths do;
- a column drag that moves documents, or a row drag that moves a column.

## 3. What exists

- **Columns** are defined in `healdan-web/web/apps/user/src/views/listColumns.tsx`: `listColumns` (a folder: Title,
  Doc #, Version, Author, Edit Date), `hitColumns(scope)` (search results and Recent: document scopes add Matter at a fixed
  130 pixels; Folders adds Matter; Matters and Clients show Matter and Client), `checkedOutColumns()` (adds Computer and
  Checked Out) and `narrowed()`, which keeps Title, Doc # and Edit Date while the reading pane is docked (M6 §6.4). Title
  is `3fr`, Author `1fr`, the rest fixed pixels.
- **The Matter cell** (`MatterCell`) shows the number, then the client muted; the matter's name is in the cell for a
  screen reader and replaces the client while the row is hovered or focused (`views.module.css`, `.matterName`).
- **`Table`** (`healdan-web/web/packages/ui/src/components/Table.tsx`) wraps React Aria's `Table` in a `Virtualizer`
  with `TableLayout`. It has no `ResizableTableContainer`. Rows drag with the browser's own drag and drop, not React
  Aria's (some 140 kB, kept out of the bundle). Rows have right-click menus through `ItemContextMenu`.
- **React Aria 1.22.1** resizes columns in a virtualised table: `ResizableTableContainer` measures the first scrollable
  element (the table, when virtualised), and its `TableLayout` reads the resize state's `columnWidths`. A column's render
  props give `startResize()`, after which the arrow keys resize and Enter or Esc end it: the keyboard route React Aria
  intends, from a menu on the heading. It has no column reordering.
- **Sorting.** Folders and document searches sort on the server (`ListSort`: `title`, `docNumber`, `version`, `author`,
  `edited`; `ListingQuery.Sort` and `FolderListing.order` in `healdan-core`; a search sorts a window of up to 10,000 hits
  with `FolderListing.sortedPage`). The sort is in the page's address (`useListing`, `SearchView`). The Folders, Matters
  and Clients scopes refuse a sort (`400`). Recent Documents and Emails (at most 50, one page), Recent Folders (at most
  20), Recent Clients (a plain array, by name) and Checked Out to Me (one page) don't sort. The Trash sorts by Name,
  Deleted and Removed.
- **Recent's own time.** `RecentDocuments` already reads each document's latest history date
  (`RecentDocument.lastAccess`, `max(ld_history.ld_date)`), and `RecentFolders` each folder's (`RecentFolder.lastChange`),
  to choose and order them; the API doesn't send it.
- **The rows** (`DocumentView`, built by `ApiViews`) carry `kind`, `size`, `folder` (`FolderRef`: id, name, kind),
  `matter` (`MatterRef`, with its client's name), `author`, `dateFiled` and `lastModified`, but not Class, Subclass,
  Typist or an email's From, To and Sent, which are the stamped properties `docclass`, `docsubclass`, `typist`,
  `emailfrom`, `emailto` and `emailsent` in `ld_document_ext` (`HealdanConstants`). `FolderListing` already joins
  Author, Class, Client and Matter for its filters.
- **Preferences.** `apps/user/src/preferences.ts` keeps numbers under `healdan.` names, each read and write in a
  try/catch. The Admin app's PR A moves it into `@healdan/shell` as `preferences(prefix)` (its plan's Task 1), the User
  app keeping `healdan.`.

## 4. The design

### 4.1 The Matter column and the default widths (owner; widths call)

- **The cell shows the number and the name,** as the left pane's Recent Matters does: `998.1 – Healdan General Demo`
  (owner: option B). The client is in the cell's tooltip, after the whole number and name, such as
  `1002.1 – Commercial Lease · Brightwell`, so a name the column cuts short can still be read by pointing at it (changed in
  the build, PR 1's review: the tooltip had held the client alone); the client is a column of its own too (§4.7). The
  hover swap goes. A matter with
  no name shows its number. A cell too narrow ends in an ellipsis, so the number and the name's start stay.
- **Matter shares the spare width** (call). Where Matter shows, it is a fraction like Title and Author, about Title `2fr`
  (at least 220 pixels), Matter `1.5fr` (at least 100) and Author `1fr`, so a wider window widens it. Matter's least
  width was 160 until the build found that, with React Aria giving every column at least 75 pixels, the list beside the
  details or Filters (684 pixels at 1,280) then scrolled sideways; at 100 the least widths come to 666 (changed in the
  build, PR 1). The test is the
  owner's screenshot: on a maximised 1,920-pixel window with the panes at their defaults, Recent Documents shows
  `998.1 – Healdan General Demo` whole, and at 1,280 pixels Title keeps at least 220. Checked Out to Me's and the Folders
  scope's Matter follow the same rule.

### 4.2 Every list sorts (owner)

- **Every column of every list sorts by a click on its heading** (owner), the lists that aren't of documents too: the
  Folders, Matters and Clients scopes, Recent Folders, Recent Clients and every column of the Trash's three views.
- **Where it sorts** (call). A list the server sends whole (Recent Documents and Emails, Recent Folders, Recent Clients,
  Checked Out to Me) sorts in the browser, by the server's rules: titles by file name ignoring case, doc #s as numbers,
  versions by major then minor number, text ignoring case, rows with no value last either way, and ties in the list's own
  order. One table of cases tests the browser's comparer against the server's rules (§8.2). A paged list (a folder, a
  search, the Trash) sorts on the server.
- **The sort is in the page's address** (call), as a folder's and a search's are, so a reload keeps it and opening a list
  from the left pane starts it in its own order.
- **A click** sorts by the column, then the other way. A date column (Edit Date, Date Filed, Sent, Last Used, Checked Out,
  and the Trash's dates) sorts newest first at its first click; any other column A to Z, smallest first (call; today Edit
  Date's first click is oldest first).
- **A list's own order is a column where it can be** (owner: option A):
  - **Recent Documents, Recent Emails and Recent Folders** get a **Last Used** column: when the person last filed,
    changed, checked in or out, or downloaded the document (for a folder, last filed or changed a document in it), from
    the time the server already reads (§3). They start sorted by it, newest first, its arrow showing, and a click on it
    puts them back.
  - **Checked Out to Me** starts sorted by its **Checked Out** column, newest first (its own order today).
  - **Recent Clients** starts sorted by Client, A to Z (its own order today).
  - **A document search** starts by relevance, which isn't a column. While sorted, its headings' menus have **Sort by
    Relevance** (call), and the line under the heading says which order it's in, as it does today.
  - **A folder** starts by Title, A to Z, as today; a search folder by its newest change, as today.

### 4.3 Resizing (owner; details call)

- **Every list's columns resize** (call: not only document lists). React Aria's `ResizableTableContainer` and
  `ColumnResizer` in `Table`, behind an option a list switches on.
- **By pointer:** drag a heading's right edge. The pointer becomes a resize arrow and a line shows where the edge is. The
  target is 24 pixels wide, centred on the edge (WCAG 2.5.8), as the panes' dividers are; it takes no click from the
  heading's sort.
- **By keyboard:** the heading's menu (§4.4) has **Resize Column**, then Left and Right resize, and Enter, Esc or Tab
  end it; the width is announced, as React Aria does.
- **A double-click on the edge** puts the column's default width back (call, §2).
- **Limits:** each column has a least width (about 48 pixels; Title 160). There's no most. When the columns are wider than
  the list, the list scrolls sideways rather than squeezing them (call), and Title doesn't stay put (call: a sticky first
  column isn't worth its cost in a virtualised grid now).
- **Resizing a column fixes it, and every column to its left, at their widths in pixels then,** as React Aria 1.22.1
  does (`TableColumnLayout.resizeColumnWidth` freezes the columns to the left, and `onResizeEnd` hands back the whole
  map) (call: keeping them fractions would make the edge and the line drift from the pointer while the columns to the
  left reflow). The columns to its right keep their defaults, so a fraction among them still takes a wider window's room;
  resizing Matter fixes Title's width too, which then no longer grows with the window. A double-click puts that column's
  default back; **Reset Columns** puts every column's back.

### 4.4 The heading menu (owner: option C)

A right-click on a heading, or Shift+F10 or the menu key while a heading has the focus (the arrow keys reach the headings
from the rows, React Aria's grid), opens a menu with its first item focused; Esc closes it and the focus goes back to the
heading, as the rows' menus do (M6 §10.5). There's no visible trigger on the heading (call): the **Columns** button
(§4.5) is the visible way in.

| Item | Shows |
|---|---|
| **Sort Ascending**, **Sort Descending** | always; the current one ticked |
| **Sort by Relevance** | on a document search while it's sorted |
| **Resize Column** | always |
| **Move Left**, **Move Right** | on a document list; not on Title, nor Move Left on the column after it, nor Move Right on the last |
| **Hide** *column* (such as **Hide Matter**) | on a document list; not on Title |
| **Choose Columns…** | on a document list; opens the panel (§4.5) |
| **Reset Columns** | always: the kind of list's default columns, order and widths (for a list that isn't of documents, its widths) |

Sorting by a click and by the menu are the same sort; "Ascending" for a date is oldest first.

### 4.5 The Columns panel (owner: option C)

- **The Columns button** is a small icon button at the end of the heading row, named **Columns**, on every document list.
  It and **Choose Columns…** open the same panel, a popover by the button (call).
- **The panel** is titled **Columns**, with the list's kind under it ("For Recent Documents"). Its first section,
  **Shown**, lists the shown columns in their order, each with a tick; Title is first, ticked, with **Always Shown** and no
  tick to change. Its second, **More Columns**, lists the rest the list offers, in the order of §4.7, each to tick. A
  column ticked goes to the end of Shown; one unticked goes back to More Columns.
- **Order:** a shown column can be dragged within Shown (not above Title), and **Move Up** and **Move Down** move the one
  with the focus; **Reset to Default** sits at the panel's foot.
- **Changes apply at once,** with no Save (call): the list behind changes as the person ticks and moves. Esc or a click
  outside closes the panel and the focus goes back to what opened it.
- **Keyboard:** Tab reaches the list of columns, Move Up, Move Down and Reset to Default; the arrows move through the
  columns, Space ticks one. Every control has its name; the panel passes axe in both themes.

### 4.6 Reordering by dragging a heading (owner; details call)

A heading of a document list can be dragged along the heading row, but Title, and dropped between two others; a line
shows where it will go. It's a drag of its own type, never a row drag: a heading drag can't drop on a row or a folder, and
a row drag can't drop on the headings. A drag that ends where it began changes nothing, and a press that doesn't move
sorts as a click does. The keyboard's way is **Move Left** and **Move Right** in the heading's menu, and the panel's
**Move Up** and **Move Down**. Like the row drag, it doesn't load React Aria's drag and drop code.

### 4.7 The columns (owner: all of them; display and sort calls)

Every document list offers every document column, besides any of its own (call). A folder's own rows (folders among
documents) and a non-email's email columns leave the cell blank.

| Column | Shows | Sorts by | Comes from |
|---|---|---|---|
| **Title** | as today | file name, ignoring case | as today |
| **Doc #**, **Version**, **Author**, **Edit Date** | as today | as today | as today |
| **Matter** | `998.1 – Healdan General Demo`; the client as tooltip (§4.1) | client number, then matter number, each as a number (998.9 before 998.10) | `matter` |
| **Client** | the client's name | name, ignoring case | `matter.client` |
| **Location** | the folder's name; tooltip "Documents · 998.1 – Healdan General Demo" | folder name, ignoring case, then matter | `folder`, `matter` |
| **Date Filed** | a date, as Edit Date | time | `dateFiled` |
| **Size** | `1.2 MB`, `350 KB` | bytes | `size` |
| **File Type** | the File Type filter's names: Word, Excel, PowerPoint, PDF, Email, OpenDocument, Picture, Other | that name, then the extension | `kind` |
| **Class**, **Subclass** | the value as stored | ignoring case | new (§5.2) |
| **Typist** | the person's name, as Author | name, as Author sorts | new |
| **From**, **To** | as stored; To ends in an ellipsis, the whole in its tooltip | ignoring case | new |
| **Sent** | a date | time | new |
| **Last Used** | a date and time (Recent only) | time | new (§5.1) |
| **Computer**, **Checked Out** | as today (Checked Out to Me only) | computer's name; time | as today |

Matter's heading is the firm's own word for a matter, as today (`setLabels`, from `GET /v1/server`'s labels); Client's is
the fixed **Client**, as the Matters scope's is today (`list.client`), since the server has no configurable word for a
client.

### 4.8 Layouts and where they're kept (owner: per kind of list, and one beside the reading pane)

- **Each kind of list has its own layout** (owner): which columns show, in what order, and the widths of those resized.
  The layouts and their defaults, which **Reset Columns** and **Reset to Default** put back (call):

| Kind | Lists | Default columns |
|---|---|---|
| `folder` | a folder's list, a search folder's | Title, Doc #, Version, Author, Edit Date |
| `documents` | Documents search, and the `#` result and a document's own page (call: it's found by number, wherever it is) | Title, Doc #, Version, Matter, Author, Edit Date |
| `all` | Docs & Emails search | the same |
| `emails` | Emails search | Title, Doc #, Matter, From, Sent |
| `recentDocuments` | Recent › Documents | Title, Doc #, Version, Matter, Author, Edit Date, Last Used |
| `recentEmails` | Recent › Emails | Title, Doc #, Matter, From, Sent, Last Used |
| `checkedOut` | Checked Out to Me | Title, Doc #, Version, Matter, Computer, Checked Out, Edit Date |
| `beside` | any document list while the reading pane is docked | Title, Doc #, Edit Date |

  Until PR 3 (§6), From and Sent don't exist, and the email kinds start as `documents` does, without Version.
- **Beside the reading pane** (owner: option B), every document list uses the one `beside` layout, chosen with the same
  menu and panel. A column it names that the list under it doesn't offer (Last Used outside Recent, Computer outside
  Checked Out) is skipped there. M6 §6.4's fixed three columns become its default.
- **Lists that aren't of documents** (the Folders, Matters and Clients scopes, Recent Folders, Recent Clients, the
  Trash's three views) keep their columns and order, and remember widths, each kind on its own.
- **Where:** this browser's `localStorage`, under `healdan.columns.<kind>`, as JSON with a version, the shown columns'
  ids in order and the resized widths, such as `{"v":1,"shown":["title","docNumber","matter","edited"],"widths":{"matter":240}}`
  (call). It's read and written through `@healdan/shell`'s preferences, which gain a JSON read and write, each in a
  try/catch. Storage refused, a value that isn't the shape, or another version reads as no layout; an id the kind doesn't
  offer is dropped; Title goes first whatever is kept. A layout is written when a change ends (a resize's end, a drop, a
  tick), not while a column is being dragged.
- **`shown` is kept only once the person has chosen columns** (ticked, unticked or moved one) (call). A layout of widths
  alone has no `shown`, so the kind's defaults apply, a later release's included: someone who widened a column on Recent ›
  Emails in PR 2 still gets From and Sent when PR 3 ships. In a layout with `shown`, a column added later starts hidden,
  and **Reset Columns** removes the whole layout.
- **Not kept:** the sort, which is in the address (§4.2).

### 4.9 The look

Headings keep today's style. The resize line and the drop line are the accent, 2 pixels. The Columns button is an icon
(Tabler's `columns-3`) with its name as tooltip and accessible name, drawn like the toolbar's icon buttons, in light and
dark. Menus and the panel are `packages/ui`'s, with its tokens; nothing new needs a colour token but the lines, which use
the accent.

## 5. The API

### 5.1 Last Used (PR 2)

`GET /v1/documents/recent` and `GET /v1/folders/recent` give each item `lastUsed`, the time `RecentDocuments` and
`RecentFolders` already order them by (§3), in UTC as every time is. It's a property of those two answers only: the
contract adds it to `DocumentView` and `FolderView` as an optional property, documented as present only in Recent's
lists (call), and no other answer sends it. No SQL changes.

### 5.2 The new columns (PR 3)

- **`DocumentView` gains** `docClass` and `docSubclass` (strings or null), `typist` (as `author`, or null) and `email`
  (an object `{ from, to, sent }`, or null for a document that isn't an email) (call). They're read for a page's documents
  in one query on `ld_document_ext`, as Author is, so a page still reads only its own rows (M6 §9.5).
- **`ListSort` gains** `matter`, `client`, `location`, `filed`, `size`, `type`, `class`, `subclass`, `typist`, `from`, `to`
  and `sent`, sorted as §4.7 says, with rows that have no value last either way and ties by the document's age, as today.
  `ListingQuery.Sort` and `FolderListing.order` follow, and the search's sorted window takes the same keys. Class reuses
  the filter's join. Client and Matter start from the filters' joins on the stamped `client` and `matter` properties,
  which hold numbers (such as `998` and `998.1`), not names: Client's order by name joins `hd_client` on the client's
  number, and Matter's numeric order casts both numbers behind a digits check (`ListingSql.digits`, as the doc # does).
  The stamps are what the filters already trust; the name a row shows comes from the folder's place (`MatterLocator`,
  `ApiViews.matterRef`), and the two agree while Healdan re-stamps documents on every move.
- **The Folders, Matters and Clients scopes take** `sort` and `order`: folders by `title` and `matter`, matters by
  `matter` and `client`, clients by `client` (call: the names follow the columns). Like a document search, a sorted search
  reads its hits until complete or 10,000, sorts the readable ones and pages them, with `totalAtLeast` past that. Without
  a sort they keep their order.
- **The Trash takes** `docNumber`, `deletedFrom`, `deletedBy`, `removedBy` and `destroyed` as sorts besides `name`,
  `deleted` and `removed`. Deleted By and Removed By sort by the names recorded at the time, and Destroyed as Removed
  does. Deleted From sorts by the path recorded at the delete (`hd_trash.hd_frompath`), ignoring case (call): the list
  shows a live, readable place's path as it is now (`TrashViews`' `livePlace`), so an item whose folder was renamed or
  moved since sorts by its old path. Sorting by the shown path would mean building every item's path in Java before
  paging the whole Trash.
- **The SQL** is PR 3's, after the PostgreSQL switch, so it's written for PostgreSQL alone, by the PostgreSQL spec's §7.3
  and §7.4 rules: `ListingSql.nullsLast` for where NULLs go, `lower()` for any order that ignores case (PostgreSQL in the
  `C` locale sorts by code point), no `::` in SQL given to CE's helpers, typed text trimmed in Java.
- **Speed:** M6's goal stands for each new sort: a matter folder of 2,000 documents opens in under a second sorted by any
  column, on the owner's server.

### 5.3 The contract

Every change goes into `openapi.yaml` in its pull request; `ApiTestCase` checks every answer against it and
`RouteCoverageTest` the routes, as always. No route is added.

## 6. Delivery (owner: three pull requests)

Each starts when the files it touches are free of the parallel work, and each leaves a working app.

| PR | Starts | Holds |
|---|---|---|
| **1. The Matter column** | now | §4.1: the cell, its tooltip and the default widths, in `listColumns.tsx` and `views.module.css` (neither parallel branch touches them), with its tests and the User Guide's sentence on the Matter column |
| **2. Columns in the browser** | after the Admin app's PR A merges (`packages/shell`, and its edits to `Table.tsx` and the User app's frame) | §4.2 to §4.6 and §4.8: resizing on every list, the heading menu, the Columns panel, reordering, the layouts, sorting in the browser for the one-page lists, Last Used (§5.1), Sort by Relevance, and Matter and Client in the panel, with the columns whose values the rows already carry (Client, Location, Date Filed, Size, File Type) shown but not yet sortable on a paged list; the docs |
| **3. The new columns and sorts** | after the PostgreSQL switch (PR B) merges | §5.2: the six new fields and the new columns from them, every new sort key (so the five of PR 2 sort everywhere), the scopes' and the Trash's sorts, and the email kinds' defaults with From and Sent; the docs |

**Why the split** (owner): PR 1 fixes the screenshot's problem at once; PR 2 waits only for the shared code it builds on;
PR 3's SQL is written once, for PostgreSQL, and tested there. If the Admin app's PR A hasn't merged when PR 1 is done,
nothing else waits on it.

**While PR 2 has a column that doesn't sort on the server** (call): Matter, Client, Location, Date Filed, Size and File
Type on a folder or a search have no sort key until PR 3, so their headings don't sort there (no arrow, no hover, no Sort
items) and sort in the one-page lists, where the browser sorts. PR 3 turns them on.

## 7. Files

### 7.1 Upstream files

None, in all three pull requests. Nothing in a `logicaldoc-*` module, the root `pom.xml` or `.gitignore` changes.

### 7.2 Fork-owned files

- **PR 1:** `healdan-web/web/apps/user/src/views/listColumns.tsx` and `views.module.css`, their tests.
- **PR 2:**
  - `healdan-web/web/packages/ui/src/components/Table.tsx` and its CSS and tests (resizing, the heading menu, the heading
    drag, the Columns button; each an option), a new `ColumnsPanel` with its CSS and tests, `strings/en-US.ts`;
  - `healdan-web/web/packages/shell/src/preferences.ts` (JSON read and write) and its test;
  - in `apps/user/src/views/`: `listColumns.tsx` (the columns' ids, the kinds and their defaults, the comparer for the
    browser's sorts), a layout hook, `FolderView`, `SearchView`, `DocResult`, `DocumentListPage`, `RecentView`,
    `CheckedOutView`, `TrashView`, and their tests;
  - `healdan-api`: `ApiViews`/`DocumentResource`/`FolderResource` for `lastUsed`, `model/DocumentView` and `FolderView`,
    `openapi.yaml`, and their tests; `healdan-web/web/e2e/` (§8.3).
- **PR 3:**
  - `healdan-core`: `listing/ListingQuery`, `FolderListing`, `ListingSql`, the Trash's listing, the scopes' search, and
    their tests;
  - `healdan-api`: `ApiViews`, `model/DocumentView`, `Filters` (the sort names), `SearchResource`, `TrashResource`,
    `openapi.yaml`, and their tests;
  - the app's `listColumns.tsx` and `useListing.ts` (`sortColumns`), the email kinds' defaults, and their tests; `e2e/`.

## 8. Verification

### 8.1 Server (JUnit, in CI's shards, on PostgreSQL)

- **PR 2:** Recent's documents and folders carry `lastUsed`, equal to the history's latest date, and no other answer
  does (contract checks both).
- **PR 3:** for each new sort key, on a folder and on a search's window: the order both ways, a row with no value last
  both ways, ties by age, and pages that neither repeat nor skip; Matter's numeric order (998.9, 998.10); the six fields
  read for a page in one query, and null where there's no value; the Folders, Matters and Clients scopes sorted across
  pages, with `totalAtLeast` past 10,000; the Trash's new sorts; an unknown sort is `400` as today.

### 8.2 App (Vitest, in the `Web` check)

- PR 1: the Matter cell's text and tooltip; a matter with no name; the widths' fractions.
- PR 2: resizing by keyboard (Resize Column, the arrows, Enter, Esc) and a double-click's reset; the heading menu's items
  for each kind of list, Title's limits, Sort by Relevance only while a search is sorted; the panel (tick, untick, Move
  Up, Move Down, Title locked, Reset to Default, changes at once, focus back on close); a layout kept and read back, with
  storage refused, a malformed value, another version and an unknown id; the `beside` layout skipping a column the list
  doesn't offer; the browser's comparer against one table of the server's rules; Recent starting by Last Used and coming
  back to it; a heading drag that doesn't start a row drag; axe on the menu and the panel in both themes.
- PR 3: the new cells (blank on a folder row and on a non-email), Size's and File Type's text, and each sort's name.

### 8.3 End to end (Playwright, Chromium and WebKit, the smoke test's step 9)

- **PR 2:** a new `columns.spec.ts`, in folders of its own inside the shared `E2E` folder: resize a column by drag and by
  keys and see it survive a reload; add Client from the panel and move it by drag and by Move Left; dock the reading pane
  and change the `beside` layout without changing the full one; sort Recent › Documents by Title and back by Last Used;
  Reset Columns. `a11y.spec.ts` gains the heading menu and the panel.
- **PR 3:** `columns.spec.ts` shows Class and sorts a folder by it.

### 8.4 By hand (owner)

The screenshot's case on the owner's server after PR 1; after PR 2, a working day's layouts set and kept; after PR 3, a
2,000-document folder sorted by each new column in under a second (`docker/tests/bulk-documents.sh` on a throwaway stack,
as M6's row 4 did).

## 9. Docs (in the same pull request)

- **PR 1:** the User Guide's Getting Around (`site/content/user/getting-started/getting-around/index.md`): the Matter
  column shows the matter's name, the client on hover.
- **PR 2:** Getting Around: a section on the list's columns (resizing, the heading menu, the Columns panel, reordering,
  sorting any list, Last Used, the layout beside the reading pane, Reset Columns, that layouts stay in this browser); the
  Developer Guide's The Web App page: the layouts' preference names and `Table`'s new options; the API page for
  `lastUsed`.
- **PR 3:** Getting Around: the new columns; the Developer Guide's API page: the new fields and sorts.
- **Records:** the M6 spec's §4 row "Choosing and reordering columns" and §16 point here, and §6.4's fixed columns become
  the `beside` default ("changed after M6"); the roadmap's "User app: further enhancements" row says #234 was designed
  here, and each pull request as it merges.

## 10. Decisions made in this session

1. The Matter cell shows number and name, with the client as tooltip and a column of its own (owner: option B).
2. All eleven candidate columns are in (owner).
3. Each kind of list remembers its own layout (owner, over one layout or one per folder).
4. A menu on each heading and a Columns panel, both (owner: option C, over a menu alone or a panel alone).
5. Beside the reading pane, one layout of its own shared by every list (owner: option B, over fixed or cut to fit).
6. The defaults and sorts of §4.7 and §4.8 (owner: "Looks correct").
7. Every column of every list sorts, Recent included (owner).
8. Recent's own order is a Last Used column (owner: option A, over a Newest First menu item).
9. Three pull requests, each when its files are free (owner, over two or one).
10. Calls the owner heard in the session: Matter's share of the spare width; resizing on every list, the lists that
    aren't of documents included; a double-click resets a width; sideways scrolling; Title always first; Reset Columns;
    a heading drag never a row drag; Sort by Relevance; the sort in the address; where each list sorts.
11. Calls made while writing this spec, which the owner checks in reading it: no sticky Title; no visible trigger on a
    heading; the panel's changes apply at once; dates sort newest first at the first click; every document list offers
    every document column; the `#` result shares the Documents search's layout; Last Used on Recent Folders too; the
    Trash's every column sorting; File Type shown by the filter's names; Location's tooltip; the storage format; `email`
    as one object; PR 2's columns without a server sort sorting only where the browser sorts until PR 3.
12. Calls made after the builder check: a resize fixes the columns to its left in pixels, as React Aria does (§4.3);
    a layout keeps `shown` only once columns have been chosen (§4.8); the Trash's Deleted From sorts by the recorded path
    (§5.2).

## 11. Left to the builder

- The exact fractions and least widths, against §4.1's test, and the default width of each new column.
- How `Table` takes the options (props or a small context) and how it holds React Aria's resize state, keeping its
  present behaviour for a list that switches nothing on, and its keys (Enter, Space, F2, Delete) and row drag unchanged.
- The heading drag's mechanics (pointer events or the browser's drag and drop with a type of its own) and the drop line.
- The panel's component (a `ListBox` or `GridList` with Move Up and Move Down) and whether it loads as a chunk of its own.
- The JSON's field names, and the layout hook's shape.
- The SQL for each sort, the File Type order from `FileKinds`, and how the scopes' sorted window reads its hits.
- The strings' keys, in `packages/ui`'s catalogue, US English in Title Case.
