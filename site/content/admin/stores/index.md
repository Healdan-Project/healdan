---
title: Stores
weight: 1
---

A **Store** is a top-level container for matters: Personal and Client Work, say, or one per region. In LogicalDOC's
screens a Store is a *workspace*, and they show at the top of the Documents tab's folder tree.

Stores are made and managed in [the Admin app](../the-admin-app/): **Stores** is under **Storage** in its left pane, at
`/admin/stores`. Only administrators see it.

## The Stores List

The list has every Store, by name, with how many there are beside its title, such as "3 Stores". Select a Store to
open [its page](#a-stores-page). **New Store**, beside the title, makes one (see [Creating a Store](#creating-a-store)),
and **Refresh** asks again at once, to see files waiting to move fall. The list also asks again each time you come back
to its window.

| Column | What it shows |
|---|---|
| Store | its name |
| Backend | the [storage backend](../storage-backends/) it keeps its files on, with "(Encrypted)" after an encrypted one's name, as **Wasabi EU (Encrypted)** |
| Matters | its matters that aren't deleted, headed with your install's word for matters |
| Documents, Size | its documents and the size of their files |
| Who Can Read | the groups and people who can see it, as "Everyone, Helen Ashcombe", or **Administrators Only** |
| Files | **Up to Date** when every file is on its backend; otherwise how many wait to move, with their size, and how many are stuck, as "188 files waiting (11.3 MB)", "3 files stuck" or "1 file stuck, 12 files waiting". See [Waiting and Stuck Files](../storage-backends/waiting-and-stuck-files/) |

**Documents** and **Size** come from the figures Healdan works out in the background, as the Dashboard's Usage does,
and "Documents and Size as of" and a time under the title say when. Just after the server starts, they're empty, and a
line says they show within a few minutes.

On a server that doesn't use storage backends, **Backend** and **Files** are left out, and a line says every Store
keeps its files in LogicalDOC's own store.

LogicalDOC's Default workspace, the Templates Store and the root above the Stores are never listed: see
[Stores Healdan Treats Differently](#stores-healdan-treats-differently). Under the list is
[Deleted Stores](#deleted-stores).

## Creating a Store

Only admins create Stores. **New Store**, on the Stores list, makes one and then opens its page. **New Store…**,
beside the Store in [New Matter](../clients-and-matters/#creating-a-matter), makes one too, and New Matter then
chooses it. Both ask for:

- **Name:** at most 255 characters, with no `/` or `\`. New Store says so before **Create Store** when it's too long
  ("That is too long.") or has one of them ("A name can't have / or \ in it.", as every folder name's check says).
  A name that another Store, or any other folder directly under the root, already has, in any case, is refused.
- **Who Can See It:** the groups that can see the Store. **Add a Group** lists the first 50 groups by name, and finds
  the others as you type two characters or more. A group added leaves that list, and the Remove button (an ×) beside it
  takes it off again, the focus moving to the next group's Remove, or to **Add a Group** after the last. With none
  added, New Store says only administrators can see the Store.
- **Backend:** the storage backend the Store keeps its files on, **Original**, the server's own storage, to begin with.
  The menu offers the backends a Store can be given: those that aren't encrypted, and the encrypted ones whose
  recovery key is confirmed. It can be changed later, on the Store's page. On a server that doesn't use storage
  backends, New Store doesn't ask, and the Store keeps its files in LogicalDOC's own store. If the backends can't be
  read, New Store says "The backends couldn't be read, so the Store will be made on Original. Its backend can be
  changed later, on the Store's page."

Anything Healdan refuses when you press **Create Store** shows in the dialog, in Healdan's own words, such as a
backend someone removed meanwhile. While the Store is being made, **Cancel** is greyed out and Esc does nothing; once
it's made, Healdan says "Created" with its name.

## A Store's Page

A Store's page, at `/admin/stores/` and its number, has its name, with **Stores**, in the crumb above it, going back
to the list. Under the name are its facts: **Backend** and **Files**, as in the list, then **Matters**, **Documents**,
**Size** ("Not worked out yet" just after a start) and **Who Can Read**.

Its actions are beside the name:

- **Rename**, which takes the Store's name alone: its matters keep theirs. The name is checked as New Store checks one,
  and "There's already a Store called Firm." shows before **Rename** when another Store has it, in any case. **Rename**
  stays greyed out until the name changes; changing only its case renames it. Once it's renamed, Healdan says
  "Renamed Ledger to Archive".
- **Change Backend**, on a server that uses storage backends: see [Where Files Are Kept](#where-files-are-kept).
- **Delete**: see [Deleting a Store](#deleting-a-store).
- **New Matter**, in your install's word for a matter, opens [New Matter](../clients-and-matters/#creating-a-matter)
  with this Store chosen.

Below them are three tabs:

- **Matters:** the Store's matters, with the Matters list's columns but Store. Select a column's heading to sort by it,
  and a matter to open [its page](../clients-and-matters/#a-matters-page).
- **Who Can See It:** see [Who Can See a Store](#who-can-see-a-store).
- **Storage**, on a server that uses storage backends: the Store's **Backend**, what it **Holds** there, as
  "3,902 files · 11.8 GB", what's **Waiting to Move** and what's **Stuck**, each "None" when there's nothing. While
  any file is stuck, **Stuck Files** lists them, with **Retry**: see
  [Stuck Files and Retry](../storage-backends/waiting-and-stuck-files/#stuck-files-and-retry). Once a retry leaves
  none stuck, **Stuck Files** says "No stuck files." until you leave the tab, and the focus moves to its heading.

An address that names no Store, or a Store in the Trash, says "Store Not Found", with **Go to Stores**.

## Who Can See a Store

A Store's permissions decide who can see it, and they limit who can see the matters in it. A Store has one level,
**Can See**, which is Read, the only permission that matters on a Store. **Who Can See It**, on the Store's page, lists
each group and person who can see the Store. One whose row gives more than Read on the Store is marked **Custom**, and
saving keeps its other permissions. With nobody listed, only administrators can see it.

A group or person whose row gives other permissions on the Store but not Read can't see it, so isn't listed. A line
under the list names them, as "Partners isn't listed, as it can't see the Store, but it has other permissions on it,
which Save removes." Adding a group back with **Add a Group** gives it Read and keeps its other permissions.

- **Add a Group** finds a group, as New Store does, and the Remove button (an ×) beside a group or a person takes them
  off. Groups are added here, not people: to let one person see a Store, add a group they're in.
- **Save** saves who can see the Store from then on, and stays greyed out until something has changed. **Discard
  Changes** puts back what's saved, and the focus moves to **Add a Group**. While the save is on its way, the list is
  held; once it's saved, Healdan says "Saved who can see Client Work".
- **Leaving the page** with a change not saved, or while it's being saved, asks first, as the Workspace Editor does:
  "Leave the Changes to Client Work?", with **Discard Changes**, or, while it's saving, **Leave Page**. Choosing
  another tab keeps the change.

**Every group a matter lets read the matter must also be able to read its Store,** so a matter in a private Store such
as Personal can't be read by people who can't see the Store. Healdan checks this when a matter is created, when its
permissions change and when it moves to another Store, and here, when who can see the Store changes:

- **Taking a group off a Store is refused while that group can read one of the Store's matters.** A matter that follows
  the Store's permissions loses the group with it, so it's the matters with permissions of their own that count.
  **Save** then says why, beside it, as "Group Partners can read the matter 1001.1 – Charter Dispute but would no longer
  read its Store.", or how many of the Store's matters the group can read and the first by name. Nothing is saved, and
  your change stays, to put right. To make the Store more private, first take the group off those matters, in
  [the Workspace Editor](../top-level-folders/#the-workspace-editor) on each matter's page, then save the Store's.
- **Only what the change breaks is refused.** A matter that already lets in a group the Store doesn't stops no other
  change, so the change that lets that group see the Store goes through.
- **A matter's owner** is the one exception: they need only be able to read the Store themselves.

**Before creating matters, let the groups your templates use read the Store.** A matter that a group can read is
refused when the Store doesn't let that group in. The Admin app's New Matter says so before you create the matter, as
in "Everyone can't read the Store Regional Office.", with a button that lets the group in there and then, such as
**Let Everyone Read the Store**: see
[Letting a Group Read the Store](../clients-and-matters/#letting-a-group-read-the-store). It keeps everyone who could
see the Store already, with whatever else their rows give them, and takes away a row that doesn't give Read, which
lets nobody see a Store. The Classic Interface's New Matter refuses it when you click **Create**: "Everyone can read
this matter but not its Store".

## Where Files Are Kept

Each Store keeps its documents' files on one **storage backend**: a folder on the server, or a bucket at an S3 service
such as Wasabi. So Personal can stay on the server's disk while Client Work goes to the cloud. Every Store starts on
**Original**, the server's own folder, unless New Store chose another.

**Change Backend**, on the Store's page, gives it another. Healdan counts first how many files will move, and says so
before the change, and the files then move in the background, each read from where it is until it has moved: see
[Choosing a Store's Backend](../storage-backends/choosing-a-stores-backend/).

- **Moving a matter to another Store,** with **Move to Another Store** on
  [its page](../clients-and-matters/#move-to-another-store), is as quick as before: it changes the database at once.
  When the other Store keeps its files on another backend, the matter's files then follow it there in the background,
  and until each has moved it's read from where it is. See
  [Choosing a Store's Backend](../storage-backends/choosing-a-stores-backend/#matters-moving-between-stores).
- **LogicalDOC's own Stores screen,** under **Administration → Settings**, is hidden, as Healdan's storage backends
  take its place. There, a *store* meant a place for files, not a Store.

## Deleting a Store

**Delete**, on the Store's page, moves the Store to the Trash with everything in it, its matters and everything in
them, as one item. It asks first, "Move Client Work to the Trash?", saying how many matters go with it, that their
numbers stay taken, and that it can be restored from **Deleted Stores** on the Stores list. For a Store on a backend
other than Original, it also says where its files stay, as "Its files stay on Wasabi EU while it's in the Trash, so
Wasabi EU can't be removed until the Store is restored and given another backend." **Move to Trash**, in that
question, deletes it, says "Moved Client Work to the Trash" and goes back to the list.

- **It's refused while a document inside is checked out,** by anyone, **or is immutable,** as a matter's delete is,
  with a sentence that says so, and nothing is deleted. Check the document in, or have it released, then delete
  again.
- **Its matters leave the Matters list,** as matters in the Trash do. Their numbers stay taken, so a new matter never
  gets one of them.
- **Its files stay on its backend** while it's in the Trash or removed from it, as a restore needs them. The
  backend's page lists it under **Stores in the Trash**, and the backend can't be removed, nor its bucket, prefix or
  path changed, until the Store is restored, given another backend and deleted again: see
  [Removing a Backend](../storage-backends/removing-a-backend/).
- **The nightly job never destroys a Store, or a matter's own folder.** Once the Store is removed from the Trash, the
  job destroys what's inside its matters after the days set in Healdan Settings, and keeps the Store and its matters'
  own folders: see [The Trash and Removed Items](../the-trash-and-removed-items/#what-it-never-destroys).

## Deleted Stores

**Deleted Stores**, under the Stores list, lists the Stores in the Trash, the most recently deleted first, those
removed from the Trash among them:

| Column | What it shows |
|---|---|
| Store | its name |
| Deleted | when it was deleted |
| Deleted By | who deleted it, or **Not Known** when Healdan didn't record who, or their account has been deleted |
| Matters | the matters its delete took that are still with it |
| Where It Is | **In the Trash**, or "Removed from the Trash; what's in it is destroyed for good after" a date: the night the job destroys the documents and folders inside its matters, the Store and its matters' own folders staying removed |

**Restore** brings a Store back directly under the root, with everything its delete took, whether it's in the Trash
or removed from it. Restored after that date, a removed Store comes back with its matters, empty: their documents and
folders have been destroyed. **Restore** asks first, "Restore Client Work?", saying it comes back with its matters and
everything else its delete took, and that anything deleted on its own before the Store was stays in the Trash. Once
it's back, Healdan says "Restored Client Work", its matters are in the Matters list again, and the focus moves to the
next Store's **Restore**, or to the **Deleted Stores** heading when it was the last.

- **A name another folder under the root has now,** in any case, refuses the restore, as in "Another Store is already
  called Client Work.": rename that Store first, then restore.
- **If someone restored it first,** Healdan says "That Store isn't in the Trash." in the question.
- **Healdan's rules hold,** as on every restore: see [The Trash and Removed Items](../the-trash-and-removed-items/).

The User app's Trash lists a deleted Store's item for whoever deleted it, and for administrators under
**Everyone's**, but can't restore it: Restore says it can't be restored from there. Restore it here, or in the Classic
Interface's Trash.

## Stores Healdan Treats Differently

- **Default** is LogicalDOC's built-in workspace. It isn't a Store, it holds nothing in Healdan, because documents only
  go into matters, and the Admin app never renames or deletes it.
- **Templates** holds the workspace templates new matters are copied from. Healdan creates it on its first start,
  and only admins can see it. It isn't renamed or deleted here: see [Workspace Templates](../workspace-templates/).
- **The root**, the folder above every Store, isn't renamed or deleted either.

## What Healdan Records

Healdan records each change made to a Store in the Admin app, with who made it, when and from where: a Store made,
with who could see it; renamed, with its name before and after; deleted; restored; who can see it changed, with each
row before and after; and its backend changed, with the backend before and after. A change that changes nothing, such
as a save of who can see it with nothing changed, isn't recorded.

## In the Classic Interface

Right-click a Store in the Classic Interface's folder tree and choose **New workspace** to make one, then name it, or
**Rename** to rename it. Give a Store made there the groups that should see it in **Who Can See It**, on its page
here, before matters are made in it.
