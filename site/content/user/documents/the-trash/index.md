---
title: The Trash
weight: 7
---

A document or folder you delete goes to the Trash, where you can restore it. Healdan has **Undo** on the message
that follows a delete, for the first moments, and the **Trash** at the foot of the left pane for as long as you need.
Taking an item out of the Trash is the only step that can't be undone by you: even then an administrator can still
restore it for a while.

## Where a Deleted Item Goes

1. **In the Trash.** You can restore anything you deleted, from the Trash.
2. **Removed.** You took it out of the Trash with **Remove…** or **Empty Trash…**. It's gone from your sight, and you
   can't restore it, but an administrator can, for a number of days: 30 by default, which an administrator sets.
3. **Destroyed.** After those days it's gone for good.

So a mistake that you notice late is still worth reporting to your administrator.

## Deleting, and Undo

Select a document or a folder and press **Delete** (on a Mac, the delete key), or choose **Delete** from its menu.
Healdan asks first, for example "Move "Old Drafts" to the Trash?", and for a folder says what it holds: "It holds 3
folders and 11 documents." The question ends "You can restore it from the Trash, at the foot of the left pane." Choose
**Move to Trash**. See [Copying, Moving and Deleting](../copying-moving-and-deleting/) for who can delete what.

Healdan then says "Moved "Old Drafts" to the Trash", with an **Undo** button. The message stays for about 10 seconds,
longer while the pointer or the keyboard's focus is on it. **Undo** puts back what that delete took, where it was, and
says "Restored "Old Drafts"". From the keyboard, press **F6** to move to the message, then Tab to **Undo** and press
Enter.

A delete takes a folder with everything in it that was in it, and its trash entry is that folder as one item. It
doesn't take what was already in the Trash, yours or a colleague's, or already removed.

## Finding It

Choose **Trash**, at the foot of the left pane, below **My Favorites**. In a narrow window it's at the foot of the
left pane too, which the **☰** (Navigation) button opens. It lists what **you** deleted, newest first. Others'
deletions are in their own Trash.

![The Trash listing two deleted documents and a deleted folder, Superseded Drafts, with "2 items" beside its name. The folder is selected and its details are at the right: Folder, 2 items, Deleted From 1003.1 › Drafts, Deleted, Holds 1 folder, 1 document, with Restore and Remove… buttons](trash.png)

- **A deleted folder is one row,** with how many items it holds, such as "14 items", beside its name. What's inside it
  isn't a row of its own.
- **The columns** are **Name**, **Doc #**, **Deleted From** and **Deleted**. **Deleted From** gives the matter number
  and the folder path. Choose the **Name** or **Deleted** header to sort by it.
- **The count** at the top of the list says how many items you have.
- **Select an item** and its details open at the right: its kind, its size or, for a folder, what it **Holds**, such
  as "3 folders, 11 documents", **Deleted From** in full, and **Deleted**, with **Restore** and **Remove…**.
- **Enter opens nothing,** as a deleted item has no page to open.

With nothing in it, the Trash says "Nothing in the Trash." and where deleted items come.

## Restoring

Select one or more items and choose **Restore** in the toolbar or the details, or right-click one and choose
**Restore**. Each goes **back to the folder it was deleted from**, and Healdan says, for example, "Restored "Old
Drafts" to 1001.1 › Drafts", with **Open**, which takes you to that folder.

- **A restored document keeps its doc #,** and its versions come back with it.
- **A restored folder brings back what was deleted with it,** and only that. A document someone deleted from the
  folder earlier stays in its deleter's Trash, and can be restored into the folder once it's back.
- **If that folder is gone,** or you can't put anything back into it, Healdan opens a window, "Restore "Old Drafts"
  to…", where you choose another folder as you do for **Move To…**. The reason is "The folder it was deleted from is
  gone, or you can't restore into it. Choose another."
- **You need the Delete permission** on what you restore, as you did to delete it. The usual filing rules apply: a
  document can only come back into a folder inside a matter's workspace, and a restore into another matter gives its
  documents that matter's client and matter. A matter that already holds a copy of an email refuses a second one.
- **Some items can't be restored here.** Healdan says ""Lease.pdf" can't be restored from here." An administrator
  restores a Store in the Classic Interface.

## Removing for Good

**Remove…**, in the toolbar, the details, an item's right-click menu and on the **Delete** key, takes the selected
items out of your Trash after you confirm. Healdan asks "Remove "Old Drafts" from the Trash?", and for a folder what it
holds. Choose **Remove**, in red, or **Cancel**, which has the focus.

> You won't be able to restore it. An administrator can, for 30 days.

The days are the number your administrator has set. **Empty Trash…**, at the right of the toolbar, asks "Empty the
Trash?", says "This removes all 4 items you deleted." and ends the same way. Choose **Empty Trash**.

Nothing in the app destroys anything. After the days an administrator sets, a nightly job destroys what was removed.

## For Administrators

An administrator sees **Mine**, **Everyone's** and **Removed** beside the heading. **Everyone's** adds who deleted each
item, and restores anyone's. **Removed** lists what people took out of their Trash, with who removed it and when, and
how long is left before it's destroyed, and has **Restore** only. See [The Trash and Removed
Items](../../../admin/the-trash-and-removed-items/) in the Admin Guide.

## In the Classic Interface

The [Classic Interface](../../the-classic-interface/) has a Trash too, in the left pane's **Trash** section, which
lists what **you** deleted, a deleted folder's documents and subfolders as rows of their own. It's the way to restore a
Store.

1. Open the **Trash** section, and open the folder to restore into in the **Browser** section. The Classic Interface
   restores into the folder that's open in its folder tree, which isn't always where the item was.
2. Right-click the document or folder in the **Trash** section and choose **Restore**.

Healdan says "Document restored into the current folder", or "Folder restored into the current folder". A document
restored at the top level of a matter is refused with "Documents can't be saved at the top level of a matter. Choose
one of its folders." **Remove** and **Empty trash**, on the same menu, take items out of your Trash after a question, as the app's
**Remove…** and **Empty Trash…** do. It follows the same rules otherwise.
