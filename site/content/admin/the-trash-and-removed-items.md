---
title: The Trash and Removed Items
weight: 6
---

Deleting in Healdan isn't final, and it isn't one step. A deleted item passes through three places, so that a
mistake, or a rogue employee, can be put right by an administrator without restoring a backup:

1. **In the Trash.** The person who deleted it can still restore it.
2. **Removed.** The person took it out of the Trash, so it's gone from their sight, but an administrator can still
   restore it, for a number of days you set.
3. **Destroyed.** After those days a nightly job destroys it for good. Healdan keeps a short record that it existed.

This page is for administrators. It says what each place holds, who can restore from it, what the nightly job does and
what it never does, and how it all meets a backup.

## The Four States

| State | Who sees it | Who restores it |
|---|---|---|
| **Live** | whoever the permissions allow | – |
| **In the Trash** | the person who deleted it, and administrators | the person who deleted it, who needs Delete on it, and any administrator |
| **Removed** | administrators only | administrators only |
| **Destroyed** | nobody. Healdan keeps its record | nobody |

These rules hold on every route into Healdan: the Classic Interface, the web services, WebDAV, CMIS and Healdan's
[API](../../developer/api/). Removed items are administrators' only everywhere. A person with Delete on a folder that
was removed from the Trash can't restore it, even through a route that once let them.

## What an Item Is

An **item** is one delete: a document deleted on its own, or a folder with everything that was live inside it when it
was deleted. Healdan records each delete as one item, however it was made, and lists what it took: the folder, its
subfolders and documents, the shortcuts to those documents, and the folder shortcuts that pointed into it.

An item takes only what was live. A document that was already in a colleague's Trash, or already removed, when you
deleted the folder around it stays where it was, with its own deleter and its own item.

A person can't delete a folder while a document inside it is checked out, by anyone, themselves included. Nor can they
delete or move a folder with an immutable document inside. A delete or a move is refused when the person lacks the
permission on anything inside, hidden items included, and the refusal names nothing they can't read.

## Restoring

A restore brings back **only what that delete took**, back where it was. It doesn't bring back what a colleague
deleted inside the folder earlier, which stays in the colleague's Trash and can be restored into the folder once it's
back. It doesn't bring back what was removed since either. A shortcut that points into the restored tree comes back
with it. Every document that comes back is queued for indexing, so search finds it again.

- **A person restores from their own Trash,** with the Delete permission on the item.
- **An administrator restores anyone's, from the Trash or from Removed.** The API's `GET /v1/trash` lists them with
  `view=everyone` for what is in the Trash, and `view=removed` for what was removed, with who removed each, when, and the
  night the nightly job will destroy it: see [Listing the Trash](../../developer/api/#the-trash) in the Developer
  Guide.
- **If the folder an item was deleted from isn't there,** because it was deleted too, or the rules or the person's
  permissions no longer let the item in, the API answers `409 place-gone`, and the item is restored into a folder you
  choose.
- **Filing rules still apply.** A restore into another matter gives the documents that matter's Client and Matter,
  and a matter that already holds a copy of an email refuses a second one.
- **A Store deleted in the Classic Interface is restored there,** not through the API.

### In the Classic Interface

The Classic Interface keeps its **Trash** section in the Documents screen's left pane, and its **Deleted documents** and
**Deleted folders** reports under **Administration → Reports** (see [Reports](../system/reports/)). Two things differ
from the Trash above:

- **Its Trash lists a deleted folder's contents as rows of their own,** as it always has, with the folder.
- **It restores into the folder that is open in its tree,** not necessarily where the item was deleted from.

What it restores follows the same rules as the rest: a restore of a folder brings back only what that delete took, and
a folder that has been removed from the Trash comes back only for an administrator. The reports list what is in the
Trash and what was removed, one row at a time, everyone's for administrators, and **Restore** them. Restoring or removing
one row changes that row alone. A row removed on its own, such as one subfolder of a deleted folder, leaves that folder's
item for an item of its own in Removed, where you can restore it before the nightly job destroys it.

**Removing** takes a folder's contents with it, whichever way you do it in the Classic Interface: **Remove** on a
folder in its Trash, **Remove** on a document there, or **Empty trash**, each after a question ("Do you want to delete
the selected elements?", or "Do you want to clear the trash can?"). Each records who removed what and when.

The **Deleted documents** report's **Permanently Delete** action, which also needs the **Destroy documents** menu (see
[Reports](../system/reports/)), stays outside the tiers. It destroys a document at once, whatever its state and
however recently it was deleted, and only people with those menus have it. Use it with care: there's nothing to restore
afterwards.

## The Nightly Job

Every night at 02:47, server time, a job destroys what has been **removed for longer than Removed Items Kept** days. It
never runs sooner than 10 minutes after the server starts. Set the days on the **Healdan Settings** page, from 1 to
365, 30 to begin with: see [Healdan Settings](../healdan-settings/). A change applies to everything already removed, not
only to what is removed after it, because the days are the job's cut-off, not a date written when an item is removed.

Each member of an item has its own removal time, and the job takes it on that time. Documents go first, through
LogicalDOC's own destroy, as the system user: their rows, versions, history, notes, links, bookmarks, tickets, search
index entry and files. Then it destroys the folders, the deepest first, with their rows in LogicalDOC's folder tables
and Healdan's own.

- **Each document is destroyed in a transaction of its own.** A document that fails waits for the next night, with the
  rest of its item, and the other items go on. The item's record shows which of its members were destroyed.
- **Storage keeps a destroyed document's files** for the holding period: see
  [Held Deletes and the Holding Period](../storage-backends/held-deletes/).
- **The job logs how many it destroyed** at info level, and never the names of what it destroyed.

### What It Never Destroys

- **A matter's own folder, and a Store.** Only an administrator deletes them: a matter with **Delete** on
  [its page](../clients-and-matters/#delete) in the Admin app, and a Store in the Classic Interface. They stay removed,
  and their contents are destroyed. The matter's number stays reserved.
- **A folder something still needs.** The job keeps a removed folder for another night, and tries again, while any of
  these holds:
  - a document or folder that isn't destroyed yet still has it as its folder or its parent, such as a document a
    colleague deleted into it;
  - a folder that isn't destroyed takes its permissions from it, so that restoring that folder keeps working;
  - a live folder alias (a shortcut to the folder) points to it.
- **A document a live shortcut points to.** It stays removed. A shortcut that is in the Trash or removed itself doesn't
  keep it: it goes with what it points to.

## The Record That Outlives an Item

Destroying an item deletes its rows, not its record. Healdan keeps, in its own tables, what the item was, its name,
doc # and where it was deleted from, who deleted it, who removed it and who restored it, each with the time, and when
each member was destroyed. Nothing in Healdan shows these records yet. They're for the day someone asks what became of
a document, and Healdan's developers can read them in `hd_trash` and `hd_trash_member`: see
[How Healdan Extends LogicalDOC](../../developer/how-healdan-extends-logicaldoc/).

LogicalDOC's own history rows (**Last Changes**) for a destroyed document or folder stay too.

## A Backup's Reach

A destroyed item's rows are gone for good, but its files aren't, at once. Storage holds a destroyed document's files for
the holding period, 30 days to begin with. So a database restored from a backup finds a destroyed item's files for
about **Removed Items Kept** days plus the holding period after the item was removed: 60 days with both at their
defaults.

The reverse holds too: **keep the backups you'd restore from within that reach**, as the Install manual's
[Backup and Restore](../../install/backup-and-restore/) says, and raise the days or the holding period if you need
longer.

Items deleted before the Trash's places existed became items, once, at the upgrade: see the Install manual's
[Upgrading](../../install/upgrading/#what-was-already-in-the-trash).
