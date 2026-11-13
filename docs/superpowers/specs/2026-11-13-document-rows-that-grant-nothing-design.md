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

> Nothing is ticked for Demo User, and a row with nothing ticked keeps no one out: they'd still have the folder's
> access. To keep someone out of a document, move it to a folder they can't read.

It doesn't say a document's permissions can only give access, as a row that grants something but not Read does take
Read away (§1).

It names each such row: a person's own group as the person (`User.getFullName`), any other group by its name, sorted
without regard to case and joined with commas and a final "and". The Classic Interface shows it as text
(`GuiLog.serverError` escapes the server's message).

- **Every other store passes,** whatever rows the document has, so a document that still has such a row can be checked
  out, renamed or moved as before.
- **Administrators aren't exempt:** the row would keep no one out whoever saves it.
- **A guest's row:** CE takes the permissions a guest may not have off a guest's row (`removeForbiddenPermissionsForGuests`)
  before the listeners run, so a guest's row with only those ticked is refused as one that grants nothing.

### 2.2 Rows already there are removed once (call)

The seed step `document-rows-granting-nothing` (`DocumentRowsGrantingNothingSeed`, in `SeedRunner.ORDER` after
`profile-message-id`) deletes every `ld_document_acl` row whose permission columns are all 0, once, at the first start
of the version that brings it, and logs how many. Who can open a document doesn't change, as
`getAllowedPermissions` read those rows as none. The Classic Interface's folder list and the searches then show those
documents to the rows' groups too, so every place agrees. On the owner's server it removes one row.

To keep someone out of a document, move it to a folder they can't read: a folder inside a matter can list fewer
groups than its parent (Admin Guide's Permissions). Walls (M7) are the planned way to keep people out of a client or
matter.

## 3. What isn't covered

CE's **apply the folder's permissions** on a document (`HibernateDocumentDAO.applyParentFolderSecurity`, from
`DocumentServiceImpl.applyParentFolderSecurity`) copies the folder's rows into the document's with JDBC, past the
listeners. It brings a row that grants nothing only if the folder has one: a folder's row with nothing ticked gives
that group nothing. None of the owner's server's 18 folder rows lacks Read. It's left as it is, rather than edit
LogicalDOC's code.

## 4. Tests

- `PermissionsDocumentListenerTest`: a save with a row that grants nothing is refused with the message, naming the
  person, and nothing is stored; two such rows are both named; rows that grant something, Preview alone included, are
  saved; any other store of a document that has such a row passes.
- `DocumentRowsGrantingNothingSeedTest`: the step deletes only the rows that grant nothing.
- `ReadableDocumentsTest` and `FolderListingTest` still check that such a row leaves the folder to decide, writing the
  row with JDBC (`HealdanFixtures.documentRow`), as a save would now refuse it.

## 5. Docs

- User Guide, Document Permissions: a row must grant something; to keep someone out, move the document.
- Admin Guide, Permissions (One-Off Grants on a Document): the same, and that the upgrade removes the rows already there.
- Install manual, Upgrading: what the step does at the first start.

## 6. Upstream files

None.
