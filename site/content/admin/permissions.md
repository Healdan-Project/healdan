---
title: Permissions
weight: 6
---

Healdan uses LogicalDOC's permissions, and adds rules so a matter's permissions stay consistent. An administrator sets
the permissions of a matter's folders, and of a workspace template's, in the Admin app's
[Workspace Editor](../top-level-folders/#the-workspace-editor), under
[Who Can Use This Folder](#who-can-use-this-folder). A Store's and a document's are set on their **Security** tab,
where a folder's can be set too, and a Store made with the Admin app's New Store starts with the groups it names (see
[Stores](../stores/#creating-a-store)).

## Folders Narrow, Never Widen

A folder inside a matter that has its own permissions may list only groups its parent folder lists, each with the
same permissions or fewer. So a Secured Documents folder can shut out people who can read the rest of the matter,
but no folder can let in someone its parent doesn't.

- **Narrowing a parent narrows its children.** If you take a group's access away from a folder, Healdan takes it away
  from the restricted folders inside it too.
- **Moving, copying or restoring a folder** into a more restricted folder narrows it to fit, without asking.
- **Apply to Subfolders opens up a restricted folder.** LogicalDOC's option replaces every subfolder's permissions
  with the parent's, which widens a restricted folder below. So in a Store, its matters included, in the Templates Store
  and its templates, and from the root above them, Healdan allows it only to an admin, or to someone with the Security
  permission on every folder below with permissions of its own, those in the Trash included. See
  [Apply to Subfolders](#apply-to-subfolders).

## A Matter and Its Store

Every group a matter lets read it must be able to read its Store, so a matter can't be read through search by people
who can't see its Store. The matter's owner only needs to be able to read the Store themselves. See
[Stores](../stores/#who-can-see-a-store).

## Who Can Use This Folder

In the Admin app's [Workspace Editor](../top-level-folders/#the-workspace-editor), the folder chosen has **Who Can Use
This Folder**: each group and person the folder lets in, groups first and then people, each by name, with their
access. The matter's owner is marked **Owner**.

### Access Levels

Each row shows one of three levels, or **Custom**:

| Level | Permissions |
|---|---|
| **Read Only** | Read, Preview, Download, Print and Email |
| **Read/Write** | Read Only's, and Write, Add, Rename, Move and Delete |
| **Full Access** | all 25 of LogicalDOC's permissions, which a matter's owner has on its folder |
| **Custom** | any other set |

The match is exact: a row with Read Only's five and one more shows as **Custom**. A row's menu offers the three levels,
and **Custom** too on a row that already is. **Show Every Permission** shows each row's 25 permissions, in LogicalDOC's
order, as tick boxes, so a row can be given any set, which then shows as **Custom**; on a folder that follows another,
it lists them.

### A Folder's Own Permissions, or Another's

A folder either follows another folder's permissions, usually those of the folder it's in, or has its own, which the
tree marks with a lock.

- **A folder that follows** says whose, as in "It follows the permissions of Documents." or "It follows its Store's
  permissions." (in a template, "It follows the Templates Store's permissions."), and its rows can't be changed. **Set Own Permissions** makes them the folder's own, to change and
  save.
- **A folder with its own** has **Add Person or Group**, a menu of levels on each row, and a Remove button (an ×)
  beside each, but for the owner's row on a matter's own folder (see [The Rules It Keeps](#the-rules-it-keeps)).
  **Add Person or Group** finds groups by name, and people who can sign in by name or username, in one search, leaving
  out those the folder lists already. A new row starts at Read Only. Once a row is removed, the focus moves to the
  next row's Remove, or to **Add Person or Group** when no row follows.
- **Save** saves the rows, and **Discard Changes** takes back what you changed. A row left with no permission says so,
  as in "Partners has no permission left. Give it one, or remove it.", and **Save** waits until it's given one or
  removed. With no rows, it says "Nobody but administrators can use this folder." While the save is on its way, the
  rows and the buttons beside them are greyed out, so nothing changes under it.
- **Follow Parent** gives a folder's own permissions up for those of the folder it's in, after asking, as in "Follow
  the Permissions of Documents?" The question names the folders below that follow this one, as they take the new
  permissions too. A workspace's top folder has no **Follow Parent**.

Anything Healdan refuses shows beside **Save**, in the rule's own words, and your changes stay.

### Apply to Subfolders

**Apply to Subfolders** shows on a folder with a folder below it, not counting the Trash. It asks first, as in "Apply
to the Subfolders of Documents?", and says what it does: every folder and search folder below, at every level, gives
up permissions of its own and follows the same permissions as this folder from then on. It names the folders below
that have permissions of their own now, which are replaced, so they're opened up: a restricted folder, such as
Privileged, then lets in everyone this folder lets in. Folders below in the Trash are changed too, and come back that
way if they're restored. Nothing is moved or deleted, and the folder itself doesn't change. It's greyed out while the
folder has changes that aren't saved, and a note says to save them first.

### The Rules It Keeps

In a matter, Healdan checks each save against the rules on this page, and refuses one that breaks them:

- **A folder inside a matter can't let in more than the folder it's in** (see
  [Folders Narrow, Never Widen](#folders-narrow-never-widen)), as the note under **Save** says: "A folder inside can
  narrow this list, never widen it." When a folder narrows, the restricted folders inside it narrow with it.
- **Every group the matter's own folder lets read it must be able to read its Store** (see
  [A Matter and Its Store](#a-matter-and-its-store)).
- **The owner keeps Full Access to the matter's own folder.** There, the owner's row shows Full Access as text, with
  no menu and no Remove button, and its tick boxes are greyed out under **Show Every Permission**, as a note says:
  "The owner's row stays on the matter's own folder, with Full Access." **Save** sends it with Full Access whoever
  owns the matter, someone whose account is read-only included, whose row then holds what a read-only account can:
  Read, Preview, Download, Print, Email, Subscription and Store. Below that folder, the owner's row may go, as a
  restricted folder may keep anyone out.

In a matter or a template, the `guest` group and people whose accounts are read-only can't be given more than Read
Only: Healdan refuses the save. The owner's row on a matter's own folder is one exception, above. A row kept as the
folder already grants it is the other: so **Set Own Permissions** below a matter's own folder keeps a read-only
owner's row, as it was there, and saves.

In a template, the first two rules aren't checked as you change it: the save works, and the template's page lists
what New Matter would refuse in a matter made from it, as warnings, with each folder's own beside its **Save**, under
"A new matter made from this template would refuse this:". See
[What New Matter Would Refuse](../workspace-templates/#what-new-matter-would-refuse).

## Who Can Change Permissions

Changing a folder's or document's permissions, or making a folder follow its parent again, needs the **Security**
permission on that folder or document. Renaming needs **Rename**.

## One-Off Grants on a Document

A document can be given to someone who can't see its folder, as a one-off or for a while. They can reach it by its
doc # or a link, but not by browsing. The rule that folders only narrow doesn't apply to documents. See
[Document Permissions](../../user/working-with-matters/document-permissions/).

A row with nothing ticked on a document doesn't keep anyone out. LogicalDOC reads it as no row, so its group keeps
the folder's access, though LogicalDOC's folder list and search hide the document from them. Healdan
refuses to save such a row, on the **Security** tab, in **Bulk Update** and through LogicalDOC's REST API, and the
upgrade that brought this removed the ones already there (see
[Upgrading](../../install/upgrading/#document-permissions-with-nothing-ticked)). To keep someone out of a document,
move it to a folder they can't read.

A read-only user's row, and the guest group's, holds none of the permissions that change a document, such as Write,
Delete or Move. So a row for a read-only user with only those ticked would grant nothing, and is refused too. Making a
user read-only takes them off the user's rows already there, so a row that granted only those then keeps no one out, and
the next save of that document's **Security** tab asks for the row to be ticked or removed.

## What These Rules Don't Do

Permissions are checked when they're saved, so two changes aren't checked:

- **A Store's own permissions.** Taking a group off a Store leaves the matters in it readable by that group, if their
  own permissions let it in. Change the matters' permissions too.
- **Group membership.** If someone joins or leaves a group later, Healdan doesn't re-check the matters that group can
  read against their Stores.
