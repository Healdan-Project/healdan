# A document's permission rows that grant nothing: design

- **Date:** 2026-11-13
- **Status:** proposed, for the owner's review in its pull request.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. The fault

In the owner's walkthrough of the User app on 2026-11-13, an administrator opened a document's **Security** tab in the
Classic Interface, added a person's row and left every box unticked, to keep that person out of the document. The
person could still see and open it, through the matter folder's grant to Everyone.

LogicalDOC's permissions can only give access; nothing denies it (the roadmap's M7 says the same). A document's own
rows are read differently in different places:

| Where | What a row that grants nothing does |
|---|---|
| Opening, previewing, downloading, and the permission checks of every route (`HibernateDocumentDAO.getAllowedPermissions`) | Nothing: it gathers what the rows of the person's groups grant, and only when that is nothing does it use the folder's permissions. So the person has the folder's access. |
| The User app's lists and searches (`AccessChecks`, `FolderListing`) | The same, on purpose, so that a list shows what opens. |
| The Classic Interface's folder list (`DocumentsDataServlet.getForbiddenDocumentIds`) | Hides the document from the row's group, as it has `ld_read = 0`. |
| LogicalDOC's searches (`Search.getDeniedDocIds`, `TagSearch`), which the User app's search also runs | The same: hides it. |

So the row looks like a denial in the Classic Interface's list and in search, and isn't one. Healdan's docs already say
a document's own permissions only give access (the User Guide's Document Permissions, the Admin Guide's Permissions).

A row that grants something but not Read does decide for its group: the group then has what it grants and can't read
the document. That case isn't changed.

## 2. The change (owner: refuse it)

### 2.1 A save of a document's permissions refuses a row that grants nothing (call)

`healdan-core`'s new `PermissionsDocumentListener` (DocumentListener `healdanDocumentPermissions`, position 110, after
the rules at 100) refuses, with a `HealdanRuleException` through `Vetoes`, a store with the event `PERMISSION` in which a
row's `grantedPermissions()` is empty. Those are the stores of the Classic Interface's Security tab
(`DocumentServiceImpl.saveACL`) and of REST's `setAccessControlList`. The message:

> Nothing is ticked for Demo User. A row that grants nothing keeps no one out: they'd still have the folder's access.
> Tick what they may do, or remove the row. To keep someone out of a document, move it to a folder they can't read.

It gives both fixes: removing the row, for an admin withdrawing a one-off grant, and moving the document, for one
keeping someone out. It doesn't say a document's permissions can only give access, as a row that grants something but
not Read does take Read away (§1).

It names each such row: a person's own group as the person (`User.getFullName`), any other group by its name, sorted
without regard to case and joined with commas and a final "and". The Classic Interface shows it as text
(`GuiLog.serverError` escapes the server's message).

- **Every other store passes,** whatever rows the document has, so a document that still has such a row can be checked
  out, renamed or moved as before.
- **Administrators aren't exempt:** the row would keep no one out whoever saves it.
- **A guest's row** (the guest group's, or a read-only user's, `Group.isGuest`): CE takes the permissions a guest may
  not have off it (`removeForbiddenPermissionsForGuests`) before the listeners run, so a guest's row with only those
  ticked grants nothing. The listener can't see what was ticked before that, so a guest's row that grants nothing is
  named in a sentence of its own: "Nothing is ticked for Jane Doe that a read-only account can have: Write, Delete,
  Move and the other changes don't count for one." The check takes those permissions off a guest's row itself
  (`NOT_FOR_GUESTS`, CE's list), so it gives the same answer for a row CE hasn't stripped yet.
- **Bulk Update:** the Classic Interface's Bulk Update (`DocumentServiceImpl.bulkUpdate`) gives every selected document
  the same rows through `saveACL`, and its loop logs a refusal and goes on, so the listener's refusal would reach no
  one, after each document's other changes were saved. So `bulkUpdate` first calls `HealdanRpcChecks.documentPermissions`,
  which builds the rows as `saveACL` does and calls the new `HealdanHooks.beforeDocumentPermissions`, which
  `HealdanHooksImpl` answers with the listener's check (`PermissionsDocumentListener.check`). A refusal reaches the user
  before any document is changed (§6).

### 2.2 Rows already there are removed once (call)

The seed step `document-rows-granting-nothing` (`DocumentRowsGrantingNothingSeed`, in `SeedRunner.ORDER` after
`profile-message-id`) deletes every `ld_document_acl` row whose permission columns are all 0, once, at the first start
of the version that brings it. It logs each row first, with its document's id and doc # and its group's id and name,
since the rows are gone afterwards and an admin may want to review the documents they hid, then how many. Who can
open a document doesn't change, as `getAllowedPermissions` read those rows as none. The Classic Interface's folder
list and the searches then show those documents to the rows' groups too, so every place agrees. On the owner's server
it removes one row.

To keep someone out of a document, move it to a folder they can't read: a folder inside a matter can list fewer
groups than its parent (Admin Guide's Permissions). Walls (M7) are the planned way to keep people out of a client or
matter.

## 3. What isn't covered

CE writes document rows with JDBC, past the listeners, in three places that can still leave a row that grants nothing
after the step has run. Each is left as it is, rather than edit LogicalDOC's code, and the step doesn't run again:

- **Apply the folder's permissions** on a document (`HibernateDocumentDAO.applyParentFolderSecurity`, from
  `DocumentServiceImpl.applyParentFolderSecurity`) copies the folder's rows into the document's. It brings a row that
  grants nothing only if the folder has one: a folder's row with nothing ticked gives that group nothing. None of the
  owner's server's 18 folder rows lacks Read.
- **Making a user read-only, or storing the guest group** (`HibernateGroupDAO.fixGuestPermissions`, from
  `HibernateUserDAO`'s store of a read-only user, `HibernateGroupDAO.store` and `inheritACLs`) sets Write, Delete,
  Move and the other permissions a guest can't have to 0 on the group's document rows. A row that granted only those
  then grants nothing: the person gets the folder's access to that document, and the Classic list and search hide it.
  The next save of that document's Security tab is refused, naming the person, until the row is ticked or removed.
- **A group that inherits another's permissions** (`HibernateGroupDAO.inheritACLs`) gets a copy of each of that
  group's document rows, so it copies one that grants nothing only from a group that has one.

The Admin Guide's Permissions page says what making a user read-only does to their rows. A sweep at every start, or
making CE's folder list and searches read such a row as `getAllowedPermissions` does, would close all three; neither is
built.

## 4. Tests

- `PermissionsDocumentListenerTest`: a save with a row that grants nothing is refused with the message, naming the
  person, and the document's rows stay as they were; two such rows are both named; a read-only user's row with only
  Write and Move ticked is refused with its own sentence, and one with Read saved without Write; rows that grant
  something, Preview alone included, are saved; any other store of a document that has such a row passes; the hook
  Bulk Update calls refuses such rows, a read-only user's included before CE has stripped it.
- `DocumentServiceImplTest#testBulkUpdateChecksThePermissionsFirst` (webapp, with a stand-in hook): Bulk Update hands
  the hook its rows as `saveACL` would make them, and a refusal reaches the caller before any document is changed.
- `DocumentRowsGrantingNothingSeedTest`: the step deletes only the rows that grant nothing, and keeps a row granting
  any one of the 21 permissions a document's row has (taken from CE's `DocumentAccessControlEntry`, not from
  `AccessChecks.PERMISSION_COLUMNS`, the list the step uses).
- `ReadableDocumentsTest` and `FolderListingTest` still check that such a row leaves the folder to decide, writing the
  row with JDBC (`HealdanFixtures.documentRow`), as a save would now refuse it.

## 5. Docs

- User Guide, Document Permissions: a row must grant something; to keep someone out, move the document.
- Admin Guide, Permissions (One-Off Grants on a Document): the same, Bulk Update, a read-only user's rows, and that the
  upgrade removes the rows already there.
- Install manual, Upgrading: what the step does at the first start, and the lines it logs.

## 6. Upstream files

- `logicaldoc-webapp/.../web/service/DocumentServiceImpl.java`: one line at the top of `bulkUpdate`, which calls
  `HealdanRpcChecks.documentPermissions` before the loop (§2.1). `DocumentServiceImplTest`, already in CI's
  `UPSTREAM_TESTS`, covers the call, and `PermissionsDocumentListenerTest` the real check behind the hook.

`HealdanRpcChecks` (webapp) and `HealdanHooks` (core), which gain a method each, are Healdan's own files.
