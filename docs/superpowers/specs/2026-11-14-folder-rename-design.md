# Renaming folders in the User app: design

- **Date:** 2026-11-14
- **Status:** design agreed with the owner on 2026-11-14, question by question and then section by section; the owner
  approved this written spec the same day.
- **Inputs:**
  - the owner's dogfooding punch list after M6 (item 13, 2026-11-14): renaming is "a basic feature that really is
    important", so folder rename comes forward from M6's later phases;
  - the [M6 spec](2026-11-10-m6-user-app-design.md) §4 (the Daily Driver), §6.7 (document rename), §10.5 (keyboard) and
    §16 (later phases);
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §7.2 (who renames which folders) and its notes on CE's folder saves;
  - a survey of this repository at `ae8403a3f`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard
and approved. *Matter*, *Store*, *workspace* and *template* mean Healdan's things (roadmap glossary). "The app" is the
User app at `/app/`; "CE" is LogicalDOC Community Edition.

## 1. Intent

People can rename documents in the app (M6 §6.7) but not folders, which sends them to the Classic Interface for
something they do every week. This brings folder rename into the app, with the same in-place field as documents, and an
endpoint in the API that keeps M1's rules.

## 2. Scope (owner)

- **Renaming folders only.** M6 §16 groups renaming, moving and deleting folders as one later phase. Moving and deleting
  folders stay there, in the Classic Interface meanwhile (owner, 2026-11-14).
- **Folders inside matters.** Stores, the Templates Store and a matter's own folder aren't renamed here (§4.1).

## 3. What exists

- **The rules are already enforced on every route.** A folder rename goes through `HibernateFolderDAO.store`, which calls
  `RulesFolderListener.beforeStore`, which compares the folder with its stored row and calls
  `FilingRules.checkFolderRename` for a new name. That refuses a matter's own folder unless Edit Matter is renaming it
  ("Rename a %1$s with Edit %1$s."), a matter's top-level folder for anyone but an admin ("Only administrators can add,
  rename, move or delete a %s's top-level folders.", search folders included), and anything in the Templates Store for
  anyone but an admin. The refusal is a `HealdanRuleException`, which the API answers `409 refused`.
- **CE's own rename checks Rename now.** `FolderServiceImpl.rename` calls `checkPermission(Permission.RENAME, …)`. The M1
  spec's note that it doesn't is out of date. When another folder in the same parent has the name, it returns without a
  word and renames nothing.
- **CE cleans a folder's name when it stores it:** `HTMLSanitizer.sanitizeSimpleText` takes out markup it doesn't keep,
  and `/` and `\` are removed.
- **The API has no folder rename.** It has `GET /v1/folders/{id}`, `/children`, `/form` and `/recent`, and
  `POST /v1/folders` (New Folder).
- **The app never shows the server's own text** (`frame/errors.ts`): a `409 refused` reads "That was refused." So the
  refusals people will meet need codes of their own.
- **Folder rows can't be selected** in the app's lists (only documents can), so the toolbar's **More**, which acts on the
  selection, doesn't reach them. Folder rows appear in a folder's list, in the Folders search scope and in Recent ›
  Folders, each a `FolderView`.
- **The My Matters tree** has a ⋯ menu only on matters (Remove from My Matters), shown while its row is hovered or has the
  keyboard's focus.
- **The helper reads `FolderView`** (its `canFile`) with System.Text.Json's web defaults, which ignore fields they don't
  know, so a new field doesn't break a released helper.

## 4. The API (call)

### 4.1 `PATCH /v1/folders/{id}`

The body is `{ "name": "…" }`. It answers `200` with the folder's `FolderView`. The name is saved through CE's
`FolderDAO.store` with a `RENAMED` history entry holding the old name and path and the caller's session, as CE's own
rename does, so `RulesFolderListener` applies M1's rules as on every other route. Spaces at either end are trimmed.

It refuses, checking in this order:

| Answer | When |
|---|---|
| `400 bad-request` | No `name` |
| `404 not-found` | The caller can't read the folder, it isn't in a Store, or it's an alias (as `GET /v1/folders/{id}` answers) |
| `409 not-renamable` | A Store, or a matter's own folder, which takes the matter's name through Edit Matter. Admins get it too |
| `403 forbidden` | No Rename permission on the folder |
| `403 admins-only` | A folder directly in a matter's workspace, search folders included, when the caller isn't an admin |
| `422 validation` on `name` | `required`; `too-long`, over 255 characters (`Uploads.MAX_NAME_LENGTH`); `invalid`, for `/`, `\`, a control character, a character the database can't store (`BasicPlane`), or markup that CE's sanitiser would change, so the name stored is the name typed (changed in the build: or any HTML tag, the ones CE's sanitiser keeps included) |
| `409 name-taken` | Another live folder in the same parent, an alias included, has the name, compared without regard to case. The folder itself doesn't count, so changing only the case of its own name works |
| `409 refused` | A veto from Healdan's listeners that the checks above didn't foresee, as New Folder answers one (changed in the build: or the folder moved to another parent while it was being renamed, which the lock below finds) |

- **The same name** answers `200` and writes nothing.
- **The name check and the write** run under a lock in the JVM, one per parent folder, so two renames at the same moment
  can't both take a name. Healdan runs one node. CE's own New Folder, which adds a counter to a taken name, isn't under
  that lock, so a rename and a New Folder of the same name at the same moment could still make two. (changed in the build:
  inside the lock the folder is read again, and refused as `409 refused` if it moved out of the parent locked, and the
  `not-renamable`, `forbidden` and `admins-only` checks run again on it as stored then.)
- **Where the rule's text and the API's codes meet:** the API checks the three refusals of M1's rules (`not-renamable`,
  `admins-only` and the Templates Store, which isn't in a Store and so is `404`) itself, before writing, to give them codes.
  The listener stays the rule on every route.

### 4.2 `canRename` on every `FolderView`

A new required boolean: whether `PATCH /v1/folders/{id}` would rename the folder for the caller, by the `not-renamable`,
`forbidden` and `admins-only` rows. The app greys Rename out from it. It is worked out in the same lookup of the caller's
permissions on the folder as `canFile` (one `getAllowedPermissions` in place of `isWriteAllowed`). (changed in the build:
that isn't quite "no more queries than today". It costs one permission lookup per folder, search folders included, whose
`canFile` used to be false without one, and an admin check for a folder directly in a matter's workspace that the caller
has Rename on.) Every `FolderView` carries it: a folder, its children, Recent › Folders, My Favorites, and the folders in
search results.

## 5. The app

### 5.1 Ways in (owner)

Each opens the same in-place field.

- **A folder row** in any list that has one (a folder's list, the Folders search scope, Recent › Folders): **F2** on the
  focused row, and **Rename** in its right-click menu once the context menus land (§6).
- **A folder in the My Matters tree:** **F2** on the focused folder; a ⋯ button at the row's end, shown only while the
  row is hovered or has the keyboard's focus, as matters' ⋯ is (owner: "keep the UI clean"), with **Rename**; and
  **Rename** in its right-click menu once the context menus land. The field takes the label's place in the tree.
- **The open folder's heading:** a ⋯ button beside its name and star, with **Rename**, which turns the heading into the
  field.

### 5.2 Where it can't be renamed (owner: greyed out, F2 says why)

Where `canRename` is false, **Rename** is greyed out in every menu: a matter's ⋯ in the tree (beside Remove from My
Matters), a matter's own heading, a top-level folder for someone who isn't an admin, and any folder without the Rename
permission. **F2** there shows a note, from what the app already knows (the folder's `kind`, its `path` and `/v1/me`'s
`admin`):

- a matter's own folder: "A {matter}'s own folder takes the {matter}'s name: an administrator changes it with Edit
  {Matter}… in the Classic Interface.";
- a folder directly in a matter's workspace, for someone who isn't an admin: "Only administrators can rename a
  {matter}'s top-level folders.";
- otherwise: "You don't have permission to rename this folder."

`{matter}` and `{Matter}` are the firm's own word for a matter, as the catalogue's other strings take it.

### 5.3 The field

- **One field for documents and folders.** What's being renamed becomes a row key (`d10388` for a document, `f45` for a
  folder) in place of a doc #, and the field takes either. A folder's whole name is selected when the field opens, as
  there's no extension to keep.
- **Enter saves, Esc cancels,** and leaving the field with a new name saves it, as for documents (M6 §6.7).
- **A refusal keeps the field open** with the message beside it: "There's already a folder called {name} here." for
  `name-taken`; the catalogue's own messages for `admins-only`, `not-renamable` and `forbidden`; and the `422` codes'
  messages for the name (`required`, `too-long`, `invalid`).
- **Afterwards** the app asks again for everything that shows a folder's name: the folder, its parent's list, the tree's
  open folders, the breadcrumb and Location of anything below it, Recent › Folders and My Favorites. The focus goes back
  where it came from: the row, the tree's folder, or the heading's ⋯.

### 5.4 The name taken (owner: refuse)

A name another folder in the parent has is refused and said (§4.1, §5.3), where CE's rename does nothing and its New
Folder adds a counter.

## 6. Order with the work in flight (owner)

Four branches from the owner's punch list are being built at the same time. `claude/m6-context-menus` adds right-click
menus to folder rows and the tree, and `claude/m6-kind-icon-colours` changes `LeftPane.tsx`'s and `listColumns.tsx`'s
icons. This work is built from master now, everything but the right-click item. Before its pull request it is rebased on
master: if the context menus have merged by then, Rename joins their folder menus in this pull request; if not, it opens
without, and the item is added once they land (owner, 2026-11-14). Edits to the files the other branches change stay
small.

## 7. Files

### 7.1 Upstream files

None. The endpoint uses CE's `FolderDAO.store`, as CE's own rename does, and Healdan's listener and `ApiViews` already sit
where they need to.

### 7.2 Fork-owned files

- **`healdan-api`:** `FolderResource` (the endpoint), `ApiViews` (`canRename`), `model/FolderView` and a request record,
  `openapi.yaml` (the route, its problems and `canRename`), and the tests (§8.1).
- **`healdan-web`:** `organising/` (the shared field and the provider's key), `views/listColumns.tsx` and the lists' F2,
  `views/FolderView.tsx` (the heading's ⋯), `frame/LeftPane.tsx` (the tree's F2 and ⋯), `packages/ui`'s `Tree` (an F2
  callback and a node in edit), the string catalogue, the fake API, and the tests (§8.2, §8.3).

## 8. Verification

### 8.1 Server (JUnit, in CI's shards)

`FolderRenameTest`, an `ApiTestCase`, with each row of §4.1's table, as an admin and as someone who isn't where it
matters:

- a folder inside a matter renamed, with its history entry;
- a matter's top-level folder and a top-level search folder (refused for a non-admin, renamed for an admin);
- a matter's own folder and a Store (refused for both);
- no Rename permission;
- each invalid name, a taken name, a change of case alone, and the same name writing nothing;
- `canRename` on `GET /v1/folders/{id}`, `/children`, `/recent` and `GET /v1/me/favorites`.

`RouteCoverageTest` and the contract check cover the new route as for every other.

### 8.2 App (Vitest, in the `Web` check)

The field on a folder row, a tree folder and the heading; F2's three notes; Rename greyed out from `canRename`; each
refusal's text; what's asked for again afterwards; and document rename still working through the shared field.

### 8.3 End to end (Playwright, in Chromium and WebKit, step 9 of the smoke test)

A new `e2e/folders.spec.ts`, signed in as `webuser`, who isn't an admin. It makes its own folder inside the shared `E2E`
folder through the API, then renames it by F2 in the list, by the tree's ⋯ and by the heading's ⋯, checking the
breadcrumb and the tree after each, once by keyboard alone; a taken name is refused beside the field; and Rename is
greyed out on matter 1.1's top-level Documents folder. It depends on nothing earlier smoke steps leave. The smoke
fixture's Everyone group already holds the Rename permission in matter 1.1 (`smoke-organising`).

## 9. Docs (in the same pull request)

- **User Guide:** Copying, Moving and Deleting gets a section, Renaming a Folder in the App, and its opening line
  changes; My Matters and Recent Matters, for the tree's ⋯.
- **Admin Guide:** Top-Level Folders, where the app greys Rename out.
- **Install manual:** Known Limitations, which lists folder rename as still in the Classic Interface.
- **Developer Guide:** API (the endpoint, its problems and `canRename`), The Web App (the shared field) and Tests (the
  new spec).
- **Records:** the M6 spec's §2.2, §4, §6.7, §10.5 and §16, and the roadmap's M6 line, so that moving and deleting
  folders are the later phase; the M1 spec's note on CE's rename, marked as out of date.

## 10. Decisions made in this session

| # | Decision | By |
|---|---|---|
| 1 | Rename only; moving and deleting folders stay a later phase | owner |
| 2 | Four ways in: F2 on a folder row or tree folder, right-click › Rename, the tree's ⋯ on folders, and the open folder's heading | owner |
| 3 | The tree's ⋯ shows only while its row is hovered or focused | owner |
| 4 | Where it can't be renamed, Rename is greyed out and F2 says why, from a new `canRename` | owner |
| 5 | A name taken in the parent is refused and said | owner |
| 6 | Built from master now; the right-click item joins the context menus' folder menus once they land | owner |
| 7 | `PATCH /v1/folders/{id}`, its refusals and their codes (§4.1) | call |
| 8 | `canRename` on every `FolderView`, from the same permission lookup as `canFile` | call |
| 9 | One in-place field for documents and folders | call |
