# Moving and Deleting Folders, and the Trash, in the User App: design

- **Date:** 2026-11-16
- **Status:** designed with the owner on 2026-11-16, question by question and then through mockups in the browser
  ([`2026-11-16-folders-and-trash-mockups/`](2026-11-16-folders-and-trash-mockups/README.md)). The owner approved this
  written spec the same day. Implementation plan:
  [`docs/superpowers/plans/2026-11-16-folders-and-trash.md`](../plans/2026-11-16-folders-and-trash.md); its "Spec
  corrections and calls this plan makes" lists where it departs from this spec. **Built** on 2026-11-16 and 2026-11-17,
  subagent-driven, each task reviewed against this spec before the next began, in three stacked pull requests:
  [Healdan-Project/healdan#261](https://github.com/Healdan-Project/healdan/pull/261) (A, Tasks 1 to 11: the record, the
  rules on every route, the upstream hooks, the job, the setting, the API and their docs), merged on 2026-11-17 after
  the owner's review, which added fixes of its own (marked "PR A's review" below);
  [#264](https://github.com/Healdan-Project/healdan/pull/264) (B, Tasks 12 to 17: the toast's action, the Trash, folder
  deletes and moves, Undo, the end-to-end specs and the User Guide, with a merge of master's Checked Out to Me); and PR
  C, the records. The plan's corrections and the build's rulings are folded in below, each marked "changed in the build"
  with its number (corrections 1 to 17 in the plan; Rulings 1 to 39 in its decision log).
- **Inputs:**
  - the [M6 spec](2026-11-10-m6-user-app-design.md) §16 (the later phases), §4 (the Daily Driver), §6.7 (organising)
    and §10.5 (keyboard); the roadmap's backlog row "User app: further enhancements", which names moving and deleting
    folders first;
  - the [folder rename spec](2026-11-14-folder-rename-design.md), whose ways in, `canRename` and refusals this follows;
  - the [M1 spec](2026-10-17-m1-move-in-design.md) §7 (who may move, delete and restore which folders) and its plan's
    R73, R74 and R78 (permission checks on whole trees);
  - the [S3 storage spec](2026-11-01-s3-storage-backends-design.md) (held deletes and the holding period);
  - the [mockups](2026-11-16-folders-and-trash-mockups/README.md) the owner reviewed;
  - a survey of this repository at `5704bdd34`. Line numbers drift.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal, which the owner heard
and approved. *Matter*, *Store*, *workspace* and *template* mean Healdan's things (roadmap glossary). "The app" is the
User app at `/app/`; "CE" is LogicalDOC Community Edition; "the Classic Interface" is CE's GWT UI.

## 1. Intent

The owner's working day in the app (M6 walkthrough row 12) found that F2 and a drag on a folder did nothing. Renaming
folders came forward on 2026-11-14; moving and deleting them still send people to the Classic Interface, and so does
getting back anything deleted, because the app has no Trash. Deleting a folder is the biggest delete there is, so it
comes with a way back in the app: a Trash, and Undo on the delete's toast (owner: "Folders and the Trash").

The Trash is tiered (owner), so that a mistake or a rogue employee can be put right by a Healdan administrator without a
backup restore:

1. **Deleted:** in the deleter's Trash, which they can restore from.
2. **Removed:** taken out of the Trash by Remove or Empty Trash. Gone from the person's sight, but an administrator can
   still restore it, for a number of days set in Healdan Settings (30 to begin with).
3. **Destroyed:** after those days, gone for good: its rows, its index entry, and, once storage's holding period has
   passed, its files. Healdan keeps a short record that outlives it.

This is the first of M6 §16's later phases to be built. It takes two of its items off the old UI: moving and deleting
folders, and the Trash (restore and purge, and an Undo after Delete).

## 2. Scope

### 2.1 Goals (done when)

- Folders move and delete in the app the ways documents do: the right-click and ⋯ menus, a drag onto the tree, the
  Delete key, and Move To… (owner).
- Moving or deleting a folder needs the permission on **every** folder and document inside it, hidden ones included,
  and the destination's Add for a move; the refusal names nothing hidden (owner). This holds on every route.
- The app has a Trash at the foot of the left pane, listing what you deleted, with Restore, Remove… and Empty Trash…
  (owner).
- Administrators switch the Trash between **Mine**, **Everyone's** and **Removed**, and restore from any of them
  (owner).
- Every Delete in the app ends in a toast with **Undo** (owner).
- Removed items are destroyed after the set days, by a nightly job, and Healdan keeps a record of each (owner).
- Each delete and each restore covers exactly what that delete took, and nothing anyone else deleted (call, §4.4).

### 2.2 Non-goals

- **Moving or deleting a matter's own folder or a Store** in the app. Those are the Admin app's (call). The Classic
  Interface keeps them, for admins, under M1's rules. So is **restoring a Store**: a Store's Trash item, which only an
  admin's delete in the Classic Interface makes, is restored there (changed in the build: Ruling 32, §6.5).
- **A screen for the record of destroyed items.** It waits for the Admin app (call, owner agreed).
- **A Trash per matter**, listing what anyone deleted in it (owner chose the person's own Trash; it can be added later
  without undoing this).
- **Undo for a move.** Moving back is the way.
- **Copying folders,** and the rest of M6 §16.
- **Changing CE's Destroy Documents** (the admin-only report action), which stays as it is: administrators are outside
  the tiers, as M7's walls treat them as trusted (owner agreed).

### 2.3 Anti-goals

- **No rule enforced only in the app** (roadmap principle 4). Every rule here holds on the Classic Interface, the web
  services, WebDAV, CMIS and the API.
- **Nothing hidden revealed.** A refusal never names, counts or describes an item the person can't read; that something
  blocks it is the most it says (§4.2).
- **No "removed for good" that isn't.** The words the person reads match what happens: Remove says an administrator can
  still restore it, for how long.
- **No lost data on a slip.** Nothing in the app destroys anything; only the nightly job does, and only what was removed
  more than the set days before.

## 3. What exists

Facts from the survey. "HFD" is `logicaldoc-core`'s `HibernateFolderDAO`, "HDD" its `HibernateDocumentDAO`, "DSI" and
"FSI" the webapp's `DocumentServiceImpl` and `FolderServiceImpl`.

### 3.1 CE's deleted states

- `ld_deleted` is 0 (live), 1 (in the Trash, `PersistentObject.DELETED_CODE_DEFAULT`) or 2 (removed,
  `DELETED_CODE_STRONG`). `ld_deleteuserid` and `ld_deleteuser` say who deleted it. Nothing records when an item was
  removed, or by whom.
- **Nothing ever destroys a row at 2.** The only destroy is `DocumentManager.destroyDocument` (documents only, menu -9,
  which only admins pass), reached from the Classic Interface's Deleted Documents report. **No code destroys a folder.**
- **Search** hides deleted documents (`Search` requires `ld_deleted = 0`). CE's daily `IndexOptimizer` removes the hits
  of documents at 1 only. **Restore never re-indexes:** a restored document whose hit the optimizer removed stays out
  of search, except after a folder restore that re-stamps it (`Propagation.finish`).

### 3.2 Deleting

- **A folder:** `HFD.deleteTree` (all routes end there). It calls `HealdanHooks.beforeFolderDeleteTree` first, refuses
  the root and the Default workspace, and writes one `DELETED` history row for the folder (none per subfolder or
  document). If the live tree holds an immutable document it stops, silently, having written that history row. Then it
  sets `ld_deleted`, `ld_deleteuserid` and `ld_deleteuser` on the live tree's folders, `ld_deleted` on every folder alias
  pointing into the tree (with no delete user), and `ld_deleted` and the delete user on **every** document in the tree's
  folders, **with no `ld_deleted` filter**: a document already in a colleague's Trash, or already removed, is reset to 1
  and handed to the person deleting the folder.
- **A document:** `HDD.delete` sets the code, the delete user and `customId` to `<customId>.<id>`, gives its versions,
  notes and links the code, and stores it with the caller's event, so the document listeners run on every route. There
  is no `HealdanHooks` method for it. Shortcuts are deleted by the caller (`DSI.deleteDocument`, the API's
  `DocumentChanges.delete`) with no delete user.
- **The rules:** `FilingRules.checkFolderDelete` (Stores and matter workspaces for admins only; a matter's top level for
  admins only; the Templates Store for admins only) and `PermissionRules.checkDeleteTree` (Delete on every live folder
  and document of the tree, hidden ones included, for anyone but an admin; R74) run on every route through
  `beforeFolderDeleteTree`. The owner's rule for deletes is **already enforced**.

### 3.3 Moving

- `FSI.move` checks only Move on the folder's parent and Add on the target. `SoapFolderService.move`, which REST's
  folder move calls, checks nothing on the tree: the check of Move and Delete on every folder of a tree
  (`HealdanTreeChecks.trees`) is its `merge`'s (changed in the build: the plan's correction 1; this survey had it on the
  move). `FilingRules.checkFolderMove` (M1's placement rules) runs on every route through `RulesFolderListener`. **No
  route checks Move on everything inside**, so the owner's rule for moves is new.
- Moving a folder into another matter re-stamps its documents (`Propagation`, after CE's JDBC move, through
  `HealdanHooksImpl` and `FolderTreeListener`).

### 3.4 Restoring

- **A folder:** `HFD.restore` calls `beforeFolderRestore`, sets the folder live under the parent asked for (with no
  `ld_deleted` condition, so it works on 1 and 2), then sets live **every** folder and document at 1 in its tree,
  **whoever deleted them and whenever**. A removed (2) subfolder stays at 2 while its children at 1 come back under it.
  It calls `afterFolderRestore`, which re-stamps the tree from its new place.
- **A document:** `HDD.restore` sets it live in the folder asked for, with its versions, and stores it with `RESTORED`,
  which `ProfileDocumentListener` treats as a move (re-stamping into another matter).
- **The rules:** `FilingRules.checkDocumentRestore` lets admins restore anything; a non-admin needs Delete on the
  document, needs it at 1 (a removed document stays removed), and needs Write on the target if it isn't where it was.
  `checkFolderRestore` needs Delete on the folder and Add on a new parent, **but doesn't check 1 against 2**, so a
  non-admin with Delete can restore a removed folder through `FSI.restore`. Once per matter is checked
  (`OncePerMatter.checkSubtreeRestore`).
- The Classic Interface restores into the folder open in its tree, not where the item was.

### 3.5 Removing

Only three routes set 2, all the Classic Interface's RPCs:

| Route | Who | What it writes |
|---|---|---|
| `DSI.deleteFromTrash` | anyone; `HealdanRpcChecks.trash` limits a non-admin to what they deleted | `ld_document` rows listed, 1 → 2 |
| `FSI.deleteFromTrash` | the same | the `ld_folder` rows listed, 1 → 2; their subfolders and documents stay at 1, in the Trash |
| `DSI.emptyTrash` | anyone, their own only | every `ld_document` and `ld_folder` row at 1 with the caller as deleter → 2 |

None records who removed, or when. The Classic Interface's Trash (`GarbageDataServlet`) lists every row at 1 that the
caller deleted, at most 100 documents and 100 folders, so a deleted folder's contents show as rows of their own there.
Its Deleted Documents and Deleted Folders reports list rows at 1 and 2, everyone's for admins, and restore them.

### 3.6 What else holds a document's or folder's id

`hd_matter.hd_folderid`, `hd_searchfolder`, `hd_local_value` (`D` and `F`), `hd_preview_job`, `hd_hold_device`,
`hd_email` (rows for destroyed documents stay, as lookups join `ld_document`), and `healdan-storage`'s `hd_storage_*`
tables, which `HealdanStorage.delete(docId)` handles: it holds the files for the holding period and drops the rows
(`CeFlowsTest.testDestroyDocument`). CE's tables that reference a folder are listed in the survey: `ld_document`,
`ld_version`, `ld_folder` (parent, security and alias references), `ld_folder_ext`, `ld_folder_store`, `ld_folder_acl`,
`ld_foldertag`, the three history tables, folder bookmarks (`ld_bookmark` type 1) and `ld_user.ld_defworkspace`.

### 3.7 The API and the app

- The API deletes a document (`DELETE /v1/documents/{n}`, `204`) and moves one (`POST /v1/documents/{n}/move`). It has
  no folder delete or move, and nothing about the Trash. `FolderView` carries `canFile` and `canRename`
  (`ApiViews.folder`, `subfolder`), from one `getAllowedPermissions` per folder.
- In the app, folder rows can't be selected (`listColumns.rowOf`, `Table`'s `disabledKeys`, `useRowSelection`,
  `useOrganisingTable`, `useListMenu`), the only drop target for rows is the My Matters tree, the toast has no action
  button (`packages/ui`'s `Toast`, 5 s, or 15 s with a link), and the delete confirmation says the Trash is in the
  Classic Interface (`delete.trashNote`).

## 4. The rules

### 4.1 Four states (owner)

| State | CE's `ld_deleted` | Who sees it | Who restores it |
|---|---|---|---|
| Live | 0 | as permissions allow | – |
| Deleted (in the Trash) | 1 | the deleter, and admins in Everyone's | the deleter, with Delete on it as now; an admin |
| Removed | 2 | admins, in Removed | an admin |
| Destroyed | no row | nobody; Healdan's record remains | nobody |

An item is **one delete**: a document deleted on its own, or a folder with everything that was live in it when it was
deleted. Each delete is recorded as one Trash item (§5.1), which lists what it took.

### 4.2 Deleting a folder (owner, M1)

- **Who:** Delete on the folder and on every live folder and document in it, hidden ones included (already the rule on
  every route, §3.2), and M1's position rules. A matter's own folder and a Store aren't deleted in the app (§2.2), for
  admins either; the API answers `409 not-deletable`.
- **Checked out inside:** a folder can't be deleted while any document in it is checked out, by anyone, the person
  deleting included (owner agreed, the folder form of today's document rule). This becomes a rule on every route, in
  `beforeFolderDeleteTree`; CE today deletes such a folder.
- **Immutable inside:** refused, with a reason, rather than CE's silent stop (call). Admins too, as CE does.
- **The refusal names nothing hidden:** "It holds items you don't have permission to delete." in the same words whether
  the items are hidden or not. For check-outs, only the documents the person can read are named, with who holds each and
  since when; "and items you can't see" is never said.
- **What it takes:** the folder, and everything **live** in it: its subfolders and documents at 0, and their shortcuts'
  rows as CE deletes them today. Items already in someone's Trash, or removed, stay where they are, with their own
  deleter and their own Trash item (owner, §4.4). This needs `deleteTree`'s document update to take only live rows
  (an upstream edit, §9.1), and its delete-user updates and its alias update too, or a colleague's deleted document
  would keep its row at 1 but change deleter (changed in the build: the plan's correction 2). The folder aliases outside
  the tree that point into it, which CE deletes with it, are the item's members too (changed in the build: correction
  4, settling §12's question), so a restore brings them back and a destroy takes them.

### 4.3 Moving a folder (owner)

- **Who:** Move on the folder and on every live folder and document in it, hidden ones included; Add on the destination;
  and M1's placement rules (only admins move a matter's top-level folders or into a matter's top level; nothing into a
  search folder, a Store or the Templates Store for non-admins). One refusal, naming nothing hidden: "It holds items you
  don't have permission to move."
- **On every route:** the tree check joins `RulesFolderListener`'s folder move and `HealdanRpcChecks`' `FSI.move`, as
  delete's did (R74), so the Classic Interface, WebDAV and CMIS refuse the same moves (§9.1). Changed in the build:
  - WebDAV's MOVE that also renames went round every folder-move rule, as it stored the new parent with no user; it now
    moves through `folderDAO.move`, then renames, so it meets the same rules and re-stamping (Ruling 9, §9.1).
  - A Classic Interface merge moves the merged folder's subfolders, so it needs Move on everything in them too, as the
    owner's rule asks of anything a folder move takes; the documents directly in the merged folder need nothing more,
    as in CE (Ruling 11). An admin can still merge what a non-admin can't.
  - A non-admin's `FSI.move` to target 0, which CE ignored, is refused, as the tree check finds no Add on a folder that
    doesn't exist (Task 3's review).
- **Checked out inside:** the API refuses the move, as its document move refuses a held document (`409 checked-out`,
  naming the readable ones only) (call). The other routes keep CE's behaviour for moves, as they do for documents.
- **Into another matter,** the app asks first (owner, §7.6); the server re-stamps as today.
- **A name already in the destination** (another live folder with the same name, without regard to case, that the
  caller can see) is refused as `409 name-taken`, as a rename is (call).
- **A known limit** (changed in the build: Ruling 27): a hidden folder doesn't make its name taken, but CE's move then
  numbers the moved folder ("Letters(1)"), as its create already numbers a new folder whose name a hidden one there has,
  which hints to someone with Add on the destination that a folder of that name is there. Refusing `name-taken`
  instead would say the same; the app's toast names the folder as the answer gives it.

### 4.4 Restoring (owner, from the playback)

- **Back where it was.** An item goes back to the folder it was deleted from (call). If that folder isn't live, or the
  rules or permissions now refuse it there, the app asks where, with the Move To… picker; the API takes a `folderId` for
  that.
- **Only what that delete took.** Restoring a folder restores the members of its Trash item that are still deleted
  (or, for an admin restoring from Removed, still removed), and nothing else. Items deleted inside it earlier, by the person or a colleague, stay in their deleters' Trash, and
  restore into the folder once it's back. Items removed since (2) stay removed. This changes CE's `HFD.restore` (§9.1),
  so the Classic Interface's restore follows it too. The item's folder aliases outside the tree that point into it come
  back with it (changed in the build: correction 4, Ruling 12).
- **A document item** comes back through `DocumentDAO.restore`, with its versions (correction 17). Its shortcut members
  come back with it, into their own folders when those are live, by JDBC (row and versions), marked restored and queued
  for indexing: Healdan's rules refuse a shortcut's restore through the DAO, and CE's folder restore brings shortcuts
  back the same way (changed in the build: Ruling 15).
- **What a restore leaves behind** (changed in the build: PR A's review): a shortcut or an outside alias whose folder
  isn't live stays deleted, and leaves the item for the item that holds that folder, or for an item of its own, so it
  never comes back live in a deleted folder and is still listed.
- **Who:** a person restores from their own Trash, with Delete on the item as now; an admin restores anything, from any
  view. **Removed items are admins' only** on every route: `checkFolderRestore` gains the 1-against-2 check that
  `checkDocumentRestore` already has (§9.2). A non-admin's restore of a live folder is refused too, as a document's is
  (changed in the build: Ruling 10): CE's restore of a live folder moves it, with no tree check, and no screen offers
  it. An admin's restore of a live folder still moves it, and brings back nothing deleted in its tree (Ruling 14).
- **Undo** restores the Trash items the Delete made, back where they were, as the person who deleted them.
- **Indexing:** a restored document is queued for indexing (`TO_INDEX`), on every route, so it is found again (call; CE
  forgets, §3.1).
- Once per matter, re-stamping into another matter and the filing rules apply as they do today. A folder's once-per-matter
  check counts the emails among what the restore brings back, not every email in the tree (changed in the build:
  Ruling 16).

### 4.5 Removing and emptying (owner)

- **Remove…** on one or several of the person's Trash items, and **Empty Trash…** for all of them, each after a
  confirmation ending "You won't be able to restore it. An administrator can, for 30 days." (the set days).
- **What it takes:** the item's members that are still deleted go to 2. A folder removed takes its contents with it
  (CE's `FSI.deleteFromTrash` left them at 1, in the Trash). A member removed on its own in the Classic Interface goes
  alone (§5.1), as an item of its own in Removed, so an administrator sees it there before it's destroyed (changed in the
  build).
- **Who:** the deleter, for their own; an admin, for anyone's, from Everyone's.
- **On every route:** the Classic Interface's three removal RPCs go through Healdan (§9.1), so their removals are
  recorded the same way and take a folder's contents too.

### 4.6 Destroying (owner)

- **The nightly job** destroys every member removed more than the set days before, each on its own removal time
  (§5.4): documents through CE's
  destroy (rows, versions, history, notes, links, bookmarks, tickets, the index hit, and the files, which storage holds
  for its holding period), then folders, deepest first, with their rows in CE's folder tables and Healdan's
  (`hd_local_value`, `hd_searchfolder`). A document's own `hd_local_value` rows and `hd_preview_job` rows go with it
  (changed in the build: Rulings 24 and 26), as the preview worker would otherwise spend a job's tries on a document
  with no row.
- **Pointers go with what they point to** (changed in the build: Ruling 22): a live shortcut keeps its document removed
  and a live alias its folder (§5.4), but a shortcut or alias that another item holds, deleted or removed, is destroyed
  with its target (CE's destroy deletes a document's shortcuts; the job deletes a folder's aliases), and its member in
  that other item is marked destroyed in the same transaction.
- **Never destroyed:** a matter's own folder and a Store (they can only be deleted by an admin in the Classic Interface;
  the matter's row and number stay reserved, as `MatterServiceTest` expects) (call). Such an item stays removed, and its
  documents and subfolders are destroyed with the rest.
- **The record stays** (§5.1): what the item was, who deleted, removed and restored it and when, and when it was
  destroyed.
- **A backup restore** still finds a destroyed item's files for storage's holding period after it's destroyed, so for
  about the set days plus the holding period after it was removed.

### 4.7 Items deleted before this ships

The first start on this release makes Trash items for what is already deleted, as a seed step: each top-most deleted
folder at 1 with what's at 1 in its tree, and each document at 1 in a live folder, with the deleter CE recorded and
`ld_lastmodified` as the time. Subfolders and documents still at 1 under a removed folder (what CE's folder removal
left in the Trash) join that folder's item and go to 2 with it (call). A folder's item also takes the folder aliases
outside its tree that point into it, at 1 into a deleted tree and at 1 or 2 into a removed one (going to 2 with it), as
CE's delete set them to the folder's state (changed in the build: Ruling 17). A folder alias or shortcut at 1 or 2 that
no folder or document item took becomes an item of its own, as a delete of it alone makes, so nothing deleted is left
that the Trash and the job can't see (changed in the build: Ruling 18).

**Rows already removed (2) count as removed at the upgrade** (owner): the same seed makes Removed items of them (each
top-most removed folder with its tree's rows at 1 and 2, and each removed document whose folder isn't removed), with the
seed's run as their removed time and "not recorded" as who removed them. Admins see them in Removed at once, can restore
any of them for the set days, and the job destroys the rest after that. The Install manual's Upgrading page says so, and
says how to raise the days first for more time.

## 5. The server

### 5.1 The record: `hd_trash` and `hd_trash_member` (call)

A new Flyway script, `V11__trash.sql`, in `mariadb/` and `hsqldb/`:

- **`hd_trash`**, one row per delete: id; tenant; kind (`document` or `folder`); the top item's id (`ld_document` or
  `ld_folder`); its name, and doc # for a document; the folder it was deleted from, by id and by its path's names at the
  time (at most 4,000 characters, a deeper path losing its top-most names, so a delete never fails on its path: changed
  in the build, Ruling 2); the matter it was in (`hd_matter` id, nullable); counts of the folders and documents it took;
  deleted by (user id and name) and at. Removed, restored and destroyed, with by whom and when, are kept on its members
  (below).
- **`hd_trash_member`**: the Trash item's id, the object's type (`D` or `F`) and id, one row per folder and document the
  delete took, the top item included.
- **State is kept per member** (call, from the builder check): each member row has its state (deleted, removed,
  restored, destroyed) and who changed it and when. The Classic Interface's Trash and reports list members one by one
  and act on them one by one, as CE does today; such an action changes that member alone. The item's own actions
  (restore, remove, Undo, destroy) take only the members in the state they act on, so a member restored on its own and
  deleted again, into a new item, is never swept up by the old one. The item's state is its top member's: it is in the
  Trash while its top member is deleted, and in Removed while its top member is removed.
- Indexes for the Trash's lists: by deleter and state, by state and removed time.
- The rows are never deleted: after a restore or a destruction they are the record. Names are kept so the record reads
  without CE's rows. Its SQL keeps to the PostgreSQL rules the storage plan set (its Global Constraints).

### 5.2 Recording every delete, on every route (call)

- **A folder:** `beforeFolderDeleteTree` already runs first on every route, in the delete's transaction; it records the
  Trash item and its members from the live tree it already reads for the permission check, with the folder aliases
  outside the tree that point into it (correction 4).
- **A folder deleted alone** (changed in the build: correction 3): CMIS's `deleteObject` on an empty folder, `HFD.merge`'s
  emptied source and `FolderTool.delete` go through `HFD.delete` with no `beforeFolderDeleteTree`, so a folder listener
  (`healdanTrashFolders`) records those. CE deletes the folder's aliases with it, so its item takes them too (Ruling 7).
- **CMIS's `deleteTree`** calls the hook, then deletes item by item, committing each, so the listeners find the members
  already recorded and record nothing. A delete that stops short then calls `afterFolderDeleteTree`, which drops what it
  didn't take (changed in the build: PR A's review; §9.1).
- **A document:** a document listener records the item when a store sets `ld_deleted` from 0 to 1, as `HDD.delete`
  does on every route. A folder delete's documents never reach it: `deleteTree` writes them with JDBC.
- **Shortcuts** deleted beside their document aren't items of their own; they're members of their document's item, so
  a restore brings them back (they come back today only through CE's folder restore). Changed in the build
  (correction 5): a shortcut stored at 1 with no transaction is one deleted beside its document, as only the routes
  that delete a document's shortcuts too pass none, and it joins its document's item; if that item isn't written yet,
  it records nothing, and the document's item takes every shortcut of it at 1 that no member row holds. A shortcut
  deleted on its own (`SHORTCUT_DELETED`, with a transaction) is an item of its own. So WebDAV, which deletes a
  document's legacy shortcuts first, each with a history, shows them as items beside the document's (Ruling 3): every
  delete stays recorded and restorable.
- **A failed delete records nothing:** the recording is in the delete's transaction. A delete whose item can't be
  written is refused, so it rolls back rather than commit a row at 1 that the Trash never shows and the job never
  destroys (changed in the build: Ruling 4).

### 5.3 Healdan's rule changes (fork-owned)

- `FilingRules.checkFolderRestore`: a non-admin restores only from 1, as for documents (`RESTORE_NOT_IN_TRASH`), a live
  folder refused as well as a removed one (changed in the build: Ruling 10, §4.4).
- `PermissionRules`: the move-tree check (Move on every live folder and document; §4.3), called from
  `RulesFolderListener`'s move and from a new `HealdanTreeChecks.movedTrees` that the Classic Interface's and the web
  services' moves call before they write.
- `beforeFolderDeleteTree`: the check-out and immutable refusals (§4.2).
- A document listener on `RESTORED` that sets `TO_INDEX` (§4.4).

### 5.4 The nightly job (call)

`TrashDestroyer` in `healdan-core`, a `HealdanStartListener` that schedules itself at 02:47 server time each night (after
CE's tasks, before storage's 03:17 check), with the storage timers' pattern (`StorageStartup`): a daemon scheduler, a
10-minute wait after a start, `safely()`, and `runOnce(Instant)` for tests.

- It takes removed items whose removed time is more than the set days before, oldest first, in batches, each item in a
  transaction of its own; one that fails is logged and left for the next night. Changed in the build (Ruling 25): each
  of an item's documents is destroyed in a transaction of its own, its member (and the members of the shortcuts that go
  with it) written destroyed before CE's destroy runs, so nothing but the commit follows storage's hold of its file;
  then the item's folders and aliases go in one transaction, as they have no files. A document that fails leaves the
  item's other documents destroyed and its folders removed for the next night, and the warning gives ids. One
  transaction for the whole item couldn't cover the files: `healdan-storage` moves a local copy aside inside the
  caller's transaction, and a rollback wouldn't bring it back.
- Documents go through `DocumentManager.destroyDocument`, which checks menu -9. The job must pass that check without
  depending on the `admin` account existing or being enabled (§12). Changed in the build (correction 9): it destroys as
  `_system`, whose group a seed, `trash-system-destroy`, gives read on menu -9 (an `ld_menu_acl` row, not a structural
  change). The Classic Interface's Destroy Documents is unchanged for people: `_system` can't sign in.
- Folders: no CE code destroys one, so the job deletes them, deepest first, and their rows in `ld_folder_ext`,
  `ld_foldertag`, `ld_folder_acl` and `ld_folder_store` (which cascade), folder bookmarks, `hd_local_value` and
  `hd_searchfolder`. `ld_folder_history`, `ld_history` and `ld_user_history` rows naming it stay (CE's destroy leaves the
  folder's history too). Rows elsewhere that still point to it (another folder's `ld_securityref`, an alias's
  `ld_foldref`, a user's default workspace) are cleared or the folder is kept removed, whichever keeps CE's reads working
  (§12). Changed in the build (correction 10):
  - `ld_folder_ext` and `ld_foldertag` don't cascade, so the job deletes them itself, with `ld_folder_acl`,
    `ld_folder_store`, folder bookmarks and the deleted aliases pointing to it.
  - A removed folder stays removed, and is tried again the next night, while a row that isn't destroyed still has it as
    its folder or parent (a colleague's deleted document, say), a folder that isn't destroyed, live or deleted, takes
    its security from it (Ruling 23: a deleted one's restore needs the rights), or a live alias points to it. A deeper
    folder of the same item goes first, so this holds only for others'.
  - A user's `ld_defworkspace` naming it is set to null.
- A member whose rows were destroyed outside the Trash (by Destroy Documents, say) is marked destroyed, by the job and
  by a removal (changed in the build: PR A's review).
- It writes the destroyed time, and logs the count at info and each item at debug, never names at info.

### 5.5 The setting (call)

`trash.removedDays` in `hd_setting`, from 1 to 365, 30 by default, read through `HealdanSettings`. The Classic
Interface's Healdan Settings general tab gets **Removed Items Kept** (days), beside the other day settings, validated in
`HealdanSettingsServiceImpl.check` with the others. A change applies to everything removed, past and future: it's the
job's cut-off, not a date written when an item is removed.

## 6. The API (call)

Every route below is in `openapi.yaml` in the same change, with `RouteCoverageTest` and the contract check covering it.
Problem codes are new where none fits; each gets a `problems.*` string in the app.

### 6.1 `DELETE /v1/folders/{id}`

Moves the folder and everything live in it to the caller's Trash. Answers `200` with the new `TrashItem` (§6.5), whose
id Undo uses. Checks, in order:

| Answer | When |
|---|---|
| `404 not-found` | unreadable, outside the Stores, an alias, or already deleted; anything in the Templates Store, admins' too, as every API route there answers (changed in the build: correction 13) |
| `409 not-deletable` | a Store or a matter's own folder (admins too) |
| `403 forbidden` | no Delete on the folder |
| `403 admins-only` | a folder directly in a matter's workspace, search folders included, for a non-admin |
| `403 items-not-allowed` | something inside without Delete, hidden or not; no names |
| `409 checked-out` | a document inside is checked out; `holds` lists the readable ones, with who and since when |
| `409 immutable` | an immutable document inside |
| `409 refused` | any other veto from Healdan's rules |

### 6.2 `POST /v1/folders/{id}/move`

Body `{ "folderId": n }`. Answers `200` with the folder's `FolderView` in its new place. Moving into the parent it's in
changes nothing (`200`). It refuses `400`; `404` (the Templates Store's folders too, as for a delete: correction 13);
`409 not-movable` (a Store or a matter's own folder); `409 filing-rule`
(M1's placement: into a Store, a search folder, the Templates Store, or into the folder itself or its own tree);
`403 forbidden` (no Move on the folder, or no Add on the destination); `403 admins-only`; `403 items-not-allowed`;
`409 checked-out` (§4.3); `409 name-taken`; `409 already-filed` (once per matter, with `document`); `409 refused`. It
holds `FolderRenames`' per-parent locks for both parents, in id order, so a rename and a move can't both take a name.

### 6.3 `canMove` and `canDelete` on every `FolderView`

Two new required booleans, worked out with `canRename` from the same `getAllowedPermissions` lookup: whether the folder
itself allows it, by the `not-movable`/`not-deletable`, `forbidden` and `admins-only` rows. What's inside isn't counted
(too costly for every row); the call says. The app greys Move To… and Delete out from them.

### 6.4 Deleting a document answers with its Trash item

`DELETE /v1/documents/{n}` answers `200` with the `TrashItem` in place of `204` (call; `2xx` either way, and no released
client reads the body). Its refusals are unchanged.

### 6.5 The Trash

- **`GET /v1/trash?view=mine|everyone|removed`**, a page of `TrashItem`s, newest first (deleted time; removed time for
  `removed`), with `removedDays`. `everyone` and `removed` are admins' (`403 admins-only`). Paged as the other lists.
  It sorts by `name`, `deleted` and `removed` (`sort=`, with `order=`); Doc # and Deleted From don't sort (changed in
  the build: correction 11).
- **`TrashItem`:** id; kind; name; `docNumber` for a document; `kind` of file for its icon; `holds` (counts of folders
  and documents) for a folder; `deletedFrom` (a `FolderRef` when live and readable, else the recorded path's names);
  `matter`; `deletedBy`, `deletedAt`; and for removed items `removedBy`, `removedAt` and `destroyAfter` (the night the
  job will take it). Changed in the build (correction 7): `type` is `document` or `folder`, and `kind` the file's kind,
  as `DocumentView.kind` is, or `folder`; the counts are `contents`, as `holds` is the check-out problem's list; a
  document's `size` is there, for the details (§7.2); and `deletedFrom` is `{ folder, path }`, the folder (a `FolderRef`)
  when it is live and readable, with its present path, else `null` and the path recorded at the delete. Counts take only
  what the caller can read.
- **`POST /v1/trash/{id}/restore`**, body `{ "folderId": n }` optional. Answers `200` with where it went (a `FolderRef`)
  and the item. Refuses `404` (not the caller's, unless admin; already restored, removed for a non-admin, or destroyed);
  `409 place-gone` when no `folderId` is given and the place isn't live or now refuses it, which the app answers with the
  picker; then the filing, permission and once-per-matter refusals a move would give. Changed in the build:
  - it answers `{ place, item }`, `place` being a `FolderView`, not a `FolderRef`, as the toast needs the matter number
    and the path (correction 8);
  - a Store's item, which only an admin's delete in the Classic Interface makes, is `409 refused`, with or without a
    `folderId`, before anything is written: CE puts a Store back under the root whatever is asked, which the API can't
    show (Ruling 32). It is restored in the Classic Interface. A shortcut deleted on its own is `409 refused` too, as
    Healdan keeps no shortcuts in matters (Task 4's review).
- **`DELETE /v1/trash/{id}`** removes it (to Removed); `204`. The caller's own, or anyone's for an admin. It answers
  `409 refused` when nothing the item took is in the Trash any more, though the item still says so, and records what
  was destroyed outside the Trash as destroyed (changed in the build: PR A's review).
- **`DELETE /v1/trash`** empties the caller's Trash; `204`.

### 6.6 `GET /v1/folders/{id}/contents` (changed in the build: correction 6)

A route this section didn't have: `{ folders, documents }`, the live folders and documents in the folder's tree, the
folder itself left out, that the caller can read, an admin's counting everything. §7.4's question ("It holds 3 folders
and 11 documents.") and §7.5's ("Its 11 documents will take…") need the counts. What the caller can't read isn't
counted, so the counts tell nothing of it. A folder the caller can't read, one outside the Stores, and an alias are
`404`, as `GET /v1/folders/{id}` answers.

## 7. The app

### 7.1 The Trash's place (owner: A)

A **Trash** entry at the foot of the left pane, below My Favorites, apart from the sections above it, at
`/app/trash`. Narrow windows keep it at the foot of the folded pane.

### 7.2 The Trash view (owner, from the mockup)

- **The list:** what you deleted, newest first, with Name, Doc #, Deleted From and Deleted; sortable as other lists. A
  deleted folder is one row, with "14 items" beside its name; its contents aren't rows of their own. Deleted From shows
  the matter number and folder path (`1001.1 › Drafts`). Name, Deleted and Removed sort from their headers; Doc # and
  Deleted From don't (changed in the build: correction 11).
- **The toolbar:** Restore (primary), Remove…, and Empty Trash… at the right, with the count ("4 items", changed in the
  build: Ruling 33).
- **The details** of the selected item: its icon, name, kind and size or count, Deleted From in full, Deleted, and what a
  folder holds ("3 folders, 11 documents"), with Restore and Remove….
- **Keyboard and menus:** rows select as in any list; a right-click menu has Restore and Remove…; the Delete key is
  Remove…; Enter opens nothing (call: a deleted item has no page).
- **Empty:** "Nothing in the Trash." with a line on where deleted items go.
- **Restore** sends each selected item back where it was, then a toast "Restored "Old Drafts" to 1001.1 › Drafts" with
  Open. One that answers `409 place-gone` opens the picker, titled "Restore "Old Drafts" to…". Changed in the build:
  - several items restored give a toast each, at most three showing at once, as the region allows (Ruling 34);
  - one answered `409 refused` (a Store's item, or a shortcut deleted on its own, §6.5) toasts ""Old Drafts" can't be
    restored from here.", as the app shows its own words, never the server's (Ruling 32).

### 7.3 Administrators (owner: A)

A switch beside the heading, **Mine · Everyone's · Removed**, for admins only (`/v1/me`'s `admin`); others never see
it. Everyone's adds a Deleted By column, with Restore and Remove…. Removed has Deleted By, Removed By, Removed and
Destroyed ("in 23 days", in the caution colour under a week), and Restore only, with the lede "What people removed from
their Trash. Restore puts an item back where it was deleted from; after 30 days it's destroyed." The switch is kept in the
address (`?view=`). It is a group of toggle buttons named **Show** (changed in the build: Ruling 33).

### 7.4 Confirmations and toasts (owner)

- **Delete:** the existing question, which now covers folders: "Move "Old Drafts" to the Trash?" with "It holds 3
  folders and 11 documents.", or "Move 4 items to the Trash?" listing them, and "You can restore it from the Trash, at
  the foot of the left pane." in place of `delete.trashNote`. One document is named too, "Move "Lease.pdf" to the
  Trash?", in place of "Move 1 document to the Trash?" (changed in the build: correction 14).
- **Remove:** "Remove "Old Drafts" from the Trash?", what it holds, and "You won't be able to restore it. An
  administrator can, for 30 days."; several: "Remove 3 items from the Trash?" listing them. **Empty Trash:** "Empty the
  Trash?", "This removes all 4 items you deleted.", and the same last line. The destructive button is red: `@healdan/ui`'s
  `danger` Button variant, which Checked Out to Me's Release uses too (changed in the build: Ruling 33 made a
  `destructive` variant, which the merge with master's Checked Out to Me replaced with its `danger` one).
- **The toast after a Delete:** "Moved "Old Drafts" to the Trash" with **Undo**. It stays 10 s, longer while the pointer
  or the keyboard's focus is on it (call). Undo restores the items that Delete made, then says "Restored …". One
  document is named as a folder is, "Moved "Lease.pdf" to the Trash", and several are counted, "Moved 4 items to the
  Trash" (changed in the build: correction 14).
- `packages/ui`'s `Toast` gains an action: a button with its label and callback, reachable by keyboard (F6 to the region,
  as React Aria's toast region allows), announced, and still the region's at most three.

### 7.5 Folders (owner)

- **Ways in:** Move To… and Delete join a folder's right-click menu (after Rename), the tree's ⋯ on folders and the open
  folder's heading ⋯; the Delete key on a focused or selected folder row and on a folder in the tree; and a drag of
  folder rows, or a folder in the tree, onto a folder in the tree, which outlines where it will land. A matter's own
  folder shows neither. Also the toolbar's More, for a selection with folders in it, and the Delete key in a **Folders**
  search (changed in the build: Ruling 35).
- **Greyed out** from `canMove` and `canDelete`, as Rename is from `canRename`.
- **Selectable folder rows:** a folder row selects like a document's, so folders and documents move or delete together,
  each its own call, with what's refused said per item while the rest is done. `?sel=` keeps one document as now; a
  folder selection isn't kept in the address (call). A drag carries both, in `ROWS_TYPE`'s payload. Changed in the
  build:
  - a plain click on a folder row still opens it; Shift-click, Command-click, the arrow keys, Space and a right-click
    select it (Ruling 35);
  - several documents alone keep the documents' right-click menu, and a selection with a folder in it has one of its own,
    named "Actions for 4 items", with Move To… and Delete, each enabled while anything selected allows it (Ruling 36,
    and Task 14's and Task 15's reviews); Release, from Checked Out to Me, is offered only for documents alone, on the
    toolbar as in the menu (Ruling 38);
  - a matter's own folder can't be selected or dragged where a list shows it (Task 14's review);
  - a selected folder shows no details: the right region's details stay a document's (correction 16);
  - the drag's payload carries each folder's kind and whether it's at a matter's top level, so a refused drop gives the
    right reason (Ruling 37).
- **Move To…:** the documents' picker. It greys out, with the reason, the folder itself and everything in it ("The
  folder itself"), where it already is ("Already there"), a matter's top level for anyone but an admin ("Administrators
  only"), search folders, and, for documents in the selection, a matter's top level as now. Move Here then calls each
  item's move. Changed in the build: search folders stay out of the picker, as they are today, since a row nobody can
  choose adds nothing (correction 12); and "Already there" also greys Move Here out when every selected document is
  already there (Ruling 37).
- **Leaving its matter (owner: A):** before a folder moves into another matter, by drag or by Move To…, the app asks
  "Move "Old Drafts" into 1003.2 – Licence Review?" with "Its 11 documents will take 1003.2's client and matter, and the
  properties its template sets." Moves within a matter, and documents moved on their own, go at once as now.
- **Refusals:** the Refusals dialog, as for documents, with each code's words: "It holds items you don't have permission
  to delete." (or "move"), "Documents in it are checked out:" with the readable ones listed and "They can be deleted once
  they're checked in or released.", and the existing words for the rest. A move's dialog is titled "Some Items Weren't
  Moved" once a folder is refused, as the documents' title no longer fits (changed in the build: Ruling 37).

### 7.6 Strings

Every new string in the catalogue, Title Case for titles and buttons, with the firm's word for a matter where one
appears.

## 8. The Classic Interface

What changes there, each through Healdan's code (§9.1):

- **Remove** and **Empty trash** record who removed what and when, and a folder's removal takes its contents (§4.5).
- **Restore** brings back only what that delete took (§4.4), and a removed folder only for an admin.
- **Delete** of a folder refuses one with a check-out inside, with the rule's message; **Move** of a folder refuses what
  the tree check refuses. Changed in the build: **Merge** needs Move on everything in the subfolders it moves
  (Ruling 11), and a document's delete takes the document before its shortcuts (PR A's review, §9.1). A Store's Trash
  item is restored here, as the app can't (Ruling 32).
- Its Trash still lists a deleted folder's contents as rows of their own (CE's list), and still restores into the folder
  open in its tree. Its Deleted Documents and Deleted Folders reports, and Destroy Documents, are unchanged.

## 9. Files

### 9.1 Upstream files (each a small hook into Healdan's code, listed in this spec)

- **`HibernateFolderDAO.deleteTree`:** the document update and the alias update take only rows at 0
  (`and ld_deleted = 0`), so a folder delete doesn't take items already deleted or removed, and an alias already removed
  stays removed (§4.2). The document's three updates, of its code and its delete user, become one, so the delete user
  too goes only on live rows (changed in the build: correction 2). Where it stops at an immutable document after
  Healdan's hook, it calls a new `HealdanHooks` method, `afterFolderDeleteTree`, so the Trash's item keeps only what it
  took (changed in the build: PR A's review).
- **`HibernateFolderDAO.restore`:** the two updates that set the tree's folders and documents live take only the ids a
  new `HealdanHooks` method names, whose default keeps CE's behaviour (§4.4), in lists of at most 1,000.
- **`LDRepository.deleteTree`** (CMIS): calls `afterFolderDeleteTree` once its item-by-item delete ends, however it ends.
  CMIS records the item before it deletes, and commits each row on its own, so a delete that stops short leaves the item
  holding live rows; the hook drops them, and when the folder itself stayed, each top-most row it did delete becomes an
  item of its own (changed in the build: PR A's review).
- **`DocumentServiceImpl.deleteDocument`:** deletes the document before its shortcuts, as the API does, so a refused
  delete leaves the shortcuts live instead of in the trash with no item and no deleter (changed in the build: PR A's
  review).
- **`DocumentServiceImpl.deleteFromTrash`, `emptyTrash`, and `FolderServiceImpl.deleteFromTrash`:** call a new
  `HealdanHooks` method each, whose default keeps CE's SQL (§4.5).
- **`FolderServiceImpl.move` and `SoapFolderService.move`** (which REST's calls): call
  `HealdanTreeChecks.movedTrees`, through `HealdanRpcChecks` for the first, before writing, as their deletes call
  `deletedTrees` (§4.3). `SoapFolderService.move` had no tree check before (correction 1, §3.3). WebDAV's and CMIS's
  moves reach the same check through `RulesFolderListener`, WebDAV's that also renames once `ResourceServiceImpl` moves
  it (below).
- **`ResourceServiceImpl.folderRenameOrMove` and `renameFolder`** (WebDAV): a MOVE that also renames stored the new
  parent in `renameFolder`, with no user, so no folder-move rule, check or re-stamping saw it (changed in the build:
  Ruling 9).
  `renameFolder` no longer sets the parent, and a folder that moves is renamed by `moveFolder` once `folderDAO.move`
  has moved it, with the user's history (§4.3), so a refused move renames nothing either. A rename in the same folder
  is unchanged.
- **`DocumentManager.destroyDocument`:** its first lookup, for the folder history's `DOCUMENT_DESTROYED` row, has no
  `where` clause, so each destroy writes another document's file name into another folder's history, which that
  folder's readers can see, across matters. It gains `where ld_id = ?` (call, from the builder check: a fix to a leak in
  upstream code, which the ground rules fix in Healdan; it fixes the Classic Interface's Destroy Documents too). CE's own
  info lines naming each file stay, as they are for Destroy Documents; the job's own lines name none. It also no longer
  deletes from `ld_readingrequest`, which no CE schema has: the failure, which CE swallowed, made the caller's
  transaction rollback-only, so a destroy inside one never committed (changed in the build: Ruling 21). A sync that
  brings the table puts the delete back, without the `try`.
- **`HealdanHooks`** (fork-owned in core) gains those methods and `afterFolderDeleteTree`; **`HealdanTreeChecks`**
  (fork-owned) gains `movedTrees`.

Each upstream edit adds its test class to CI's `UPSTREAM_TESTS` (`HibernateFolderDAOTest` methods for the delete and
restore, the RPC services' tests where they exist, and `ResourceServiceImplTest#testMoveFolder` for WebDAV's). As built,
the entries are `HibernateFolderDAOTest#testDeleteTreeTakesOnlyLiveRows` (the delete) and `#testRestore` (CE's own path
through the edited restore, which the hook's null answer keeps; changed in the build: Ruling 30),
`HealdanTrashHooksTest` (the RPC services' removals, and the document delete's order),
`ResourceServiceImplTest#testMoveFolder` (WebDAV's rename-and-move), and
`DocumentManagerTest#testDestroyDocumentNamesItsOwnFile` and `#testDestroyDocumentInsideATransaction` (`destroyDocument`,
Ruling 21). `HealdanHooksTest`, `RpcPermissionGapsTest` and `SoapPermissionGapsTest` were in the list already, and cover
the new hooks, `deleteTree`'s early return and the two folder moves. CMIS's `deleteTree` is covered by `healdan-core`'s
`TrashRecordingTest`, which every CI run takes.

### 9.2 Fork-owned files

- **`healdan-core`:** the V11 migration; `trash/` (the record's DAO, the recording listener and hooks, removal, restore
  scope, `TrashDestroyer`, the setting); `FilingRules`, `PermissionRules`, `HealdanHooksImpl`; `HealdanSettings` and
  `HealdanSettingsServiceImpl`; tests. As built: the seeds `trash-record-existing` (§4.7), `trash-system-destroy`
  (correction 9) and `smoke-second-matter` (correction 15). The demo firm's `DemoFixtureSeed` asks Spring for
  `HealdanHooksImpl` before its timeline: seeds run from `HealdanStartup`'s start, which Spring can call before that
  bean registers Healdan's hooks, so the demo's folder delete recorded no Trash item. Its `Backdater` dates each delete's
  item to the step's time, so the User Guide's Trash picture comes from the demo firm (changed in the build: Ruling 39).
- **`healdan-api`:** `FolderResource` (delete, move), `TrashResource`, `ApiViews` (`canMove`, `canDelete`,
  `TrashItem`), `DocumentChanges.delete`'s answer, models, `openapi.yaml`, tests. As built, `FolderResource`'s delete,
  move and contents (§6.6) hand their work to `FolderChanges`, and `TrashViews` reads the Trash's lists.
- **`healdan-web`:** `frame/LeftPane.tsx` (the entry), `views/TrashView.tsx` and its route, `organising/` (folder moves
  and deletes, the cross-matter question, Undo), `actions/` (folder calls, trash calls), `menus/ContextMenus.tsx`,
  `views/listColumns.tsx` and the selection code, `packages/ui`'s `Toast` (the action) and `Tree` (folders draggable),
  the string catalogue, the fake API, and tests. As built, `packages/ui` also gained `ToggleButtons` and the Trash's icon,
  and its Button the red variant that became `danger` (Ruling 33, §7.4).
- **`logicaldoc-gui`:** the Healdan Settings general tab's new field (`HealdanSettingsPanel`, `GUIHealdanSettings`).

## 10. Verification

### 10.1 Server (JUnit, in CI's shards)

- The record: a document delete and a folder delete on each route (the API, the Classic Interface's RPC, WebDAV's delete
  through the DAO) each make one item with the right members; a folder delete leaves a colleague's deleted document and
  a removed one where they were (the `deleteTree` fix).
- Restore: back where it was; only the item's members (a colleague's earlier delete inside stays in their Trash); a
  removed subfolder stays removed; `place-gone`; into another matter re-stamps; once per matter; queued for indexing; a
  non-admin can't restore a removed folder on any route.
- Remove and empty on the API and the Classic Interface's three RPCs: recorded; a folder takes its contents.
- The tree rules: delete and move refused for a hidden item without the permission, with no name in the problem; a
  check-out inside; immutable; the destination's Add; `name-taken`; admins' bypass; the Classic Interface's and SOAP's
  moves refused the same.
- `TrashDestroyer.runOnce`: removed more than the days before is destroyed (documents, folders deepest first, Healdan's
  rows), the record keeps it; within the days, nothing; a matter's own folder is kept removed; one failure leaves the rest
  done; storage holds the files (`healdan-storage`'s `CeFlowsTest` pattern).
- `canMove` and `canDelete` on every `FolderView`; `GET /v1/trash`'s three views and who may read them; the contract.

### 10.2 App (Vitest, in the `Web` check)

The Trash entry and view, its empty state, the admin switch shown only to admins, Restore with the picker after
`place-gone`, Remove and Empty with their words, Undo in the toast, folder rows selectable with documents, Move To… and
Delete greyed out from the flags, the picker's greyed places and reasons, the cross-matter question by drag and by
picker, the refusals' words, and the toast's action by keyboard.

### 10.3 End to end (Playwright, Chromium and WebKit, step 9 of the smoke test)

- **`trash.spec.ts`** (new), as `webuser` in a folder of its own inside `E2E`: delete a folder with a document, Undo,
  check it's back; delete again, restore from the Trash, check where it went; delete and Remove, check it's gone from the
  Trash and that `GET /v1/trash?view=removed` as admin lists it; Empty Trash. As built, the admin's call uses a device
  token of the smoke stack's `admin`, with the published password `test-web.sh` passes (`ADMIN_PASSWORD`), and checks
  that `webuser` is refused the view; each test has a folder of its own (Task 16's review).
- **`folders.spec.ts`:** move a folder by drag and by Move To…, once by keyboard alone; the cross-matter question
  (matter 1.1 into another matter the smoke fixture has; a new matter in the smoke seed if none suits, as a new seed
  step); Delete greyed out on matter 1.1's top-level folder for `webuser`. As built, a new seed step,
  `smoke-second-matter`, makes matter 1.4 "Second Matter" in Store Smoke from the smoke template, as matters 1.2 and 1.3
  belong to the storage overlay and step 5f moves 1.2 (changed in the build: correction 15). It also drags one tree
  folder onto another with a real pointer.
- **`keyboard.spec.ts`** and **`a11y.spec.ts`:** the Trash view, its dialogs and the toast's Undo. As built, the keyboard
  spec's rows 10 and 11, after M6's rows 3 to 9, walk Delete on a folder row, F6 to the toast's Undo, Restore from the
  Trash, and Delete on a folder of the left pane's tree, with where the focus lands asserted.

The smoke fixture's `smoke-organising` step gives Everyone Rename, Move and Delete in matter 1.1's template; a folder the
spec makes inherits them.

## 11. Docs (in the same pull requests)

- **User Guide:** The Trash (rewritten for the app, with the Classic Interface's Trash as a section); Copying, Moving and
  Deleting (folders, Undo, the opening line); Getting Around (the Delete key on folders, the Trash entry); My Matters
  and Recent Matters (the tree's ⋯ and drag).
- **Admin Guide:** Healdan Settings (Removed Items Kept); a new page, The Trash and Removed Items (the tiers, restoring as
  an admin, destruction and the record, the Classic Interface's reports and Destroy Documents); Held Deletes (a destroyed
  document's files); Top-Level Folders (move and delete greyed out).
- **Install manual:** Known Limitations (moving and deleting folders now in the app); Upgrading (what the first start
  does with what's already deleted, §4.7).
- **Developer Guide:** API (the routes, `TrashItem`, the codes, `canMove` and `canDelete`, the delete's new answer); The
  Web App (the toast's action, selectable folder rows); How Healdan Extends LogicalDOC (`hd_trash`, the hooks, the job);
  Tests (the new specs).
- **Records:** the M6 spec's §2.2, §4, §6.7 and §16; the roadmap's backlog row; CLAUDE.md's list of specs that edit
  upstream files.

As built, every page above shipped: the Admin Guide's, the Install manual's and the Developer Guide's API and How
Healdan Extends LogicalDOC in PR A, but for Top-Level Folders' and Known Limitations' lines on what the app does, which
waited for the app (changed in the build: Ruling 29); the User Guide's, those two lines, The Web App and the Tests
page's new specs in PR B; and the records in PR C, with the API inventory. The Admin Guide's Healdan Settings picture
was retaken from the demo firm in PR B, and the User Guide's Trash picture comes from the demo firm's own deleted
folder (Ruling 39).

## 12. Left to the builder

- Field names, problem codes beyond those above, and page sizes, within M2's conventions.
- How `TrashDestroyer` passes `destroyDocument`'s menu check (a transaction user that is an admin by group without the
  `admin` account, or Healdan's own copy of CE's deletes), and how a destroyed folder's remaining references are handled
  (§5.4), with a test of CE's reads afterwards. Settled in the build: as `_system`, through the `trash-system-destroy`
  seed (correction 9), and the references as §5.4 says (correction 10, Rulings 22 and 23).
- Whether a folder alias pointing into a deleted tree, which CE deletes with no delete user, joins the item's members.
  Settled in the build: it does (correction 4, Rulings 7, 12 and 17).
- The order of work inside each pull request.

## 13. Delivery (call)

Stacked pull requests, as M6's were, each with its docs:

- **PR A, the server:** the record, the rules on every route, the upstream hooks, the job, the setting, the API, and the
  Admin Guide's, Install manual's and Developer Guide's pages.
  [Healdan-Project/healdan#261](https://github.com/Healdan-Project/healdan/pull/261), merged on 2026-11-17.
- **PR B, the app:** the Trash, folder moves and deletes, Undo, and the User Guide.
  [#264](https://github.com/Healdan-Project/healdan/pull/264), with the end-to-end specs and a merge of master's
  Checked Out to Me.
- **PR C, the records:** this spec's status, the M6 spec and the roadmap.

## 14. Decisions made in this session

| # | Decision | By |
|---|---|---|
| 1 | This phase: moving and deleting folders, and the Trash with Undo | owner |
| 2 | The Trash lists what you deleted; admins can see everyone's | owner |
| 3 | Tiered: Remove hides it from the person, an admin can restore it for a set number of days, then it's destroyed | owner |
| 4 | Destroyed means rows, index and files go; Healdan keeps a record | owner |
| 5 | Deleting a folder needs Delete on everything in it, hidden items included | owner (already M1's rule) |
| 6 | Moving a folder needs Move on everything in it, and Add on the destination | owner |
| 7 | Refusals name nothing hidden | call |
| 8 | No folder deletes while a document in it is checked out | call |
| 9 | Restore puts an item back where it was, and asks only when it can't | call |
| 10 | Each delete and restore covers only what that delete took | call |
| 11 | The set days: 30 by default, in Healdan Settings | call |
| 12 | The confirmation says an administrator can still restore it | call |
| 13 | The Trash at the foot of the left pane | owner (mockup A) |
| 14 | Admins: one switch, Mine · Everyone's · Removed | owner (mockup A) |
| 15 | Moving a folder into another matter asks first | owner (mockup A) |
| 16 | Undo on every delete's toast, 10 s or longer while it has the pointer or focus | owner, call on the time |
| 17 | `canMove` and `canDelete` from the folder itself; the deep check on the call | call |
| 18 | Folder rows selectable, with documents | call |
| 19 | A matter's own folder and a Store aren't moved or deleted in the app, and are never destroyed | call |
| 20 | No screen for the destroyed record yet; it waits for the Admin app | call |
| 21 | Built on CE's own deleted states, with Healdan's record and a nightly job | call (approach 1) |
| 22 | State kept per member; the Classic Interface's actions on one member act on it alone | call (builder check) |
| 23 | Contents CE's folder removal left in the Trash join their removed folder's item | call (builder check) |
| 24 | `destroyDocument`'s lookup with no `where` clause fixed upstream | call (builder check) |
| 25 | Items removed before this ships count as removed at the upgrade, and are destroyed after the set days | owner |
