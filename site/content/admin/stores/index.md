---
title: Stores
weight: 1
---

A **Store** is a top-level container for matters: Personal and Client Work, say, or one per region. In LogicalDOC's
screens a Store is a *workspace*, and they show at the top of the Documents tab's folder tree.

## Creating a Store

Only admins create Stores. Right-click a Store in the folder tree and choose **New workspace**, then name it. Rename
a Store the same way, with **Rename**.

## Who Can See a Store

A Store's permissions decide who can see it, and they limit who can see the matters in it. Set them on the Store's
**Security** tab.

**Before creating matters, let the groups your templates use read the Store.** A new Store usually lets in only the
admin who created it, and New Matter refuses a matter that a group can read when the Store doesn't let that group
in: "Everyone can read this matter but not its Store".

- **A matter needs its Store.** Every group a matter lets read the matter must also be able to read its Store.
  Healdan checks this when a matter is created, when its permissions change and when it moves to another Store, so
  a matter in a private Store such as Personal can't be read by people who can't see the Store.
- **Changing a Store's own permissions doesn't re-check its matters.** If you take a group off a Store, the matters
  in it that let that group read them still do. To make a Store more private, change its matters' permissions too.
- **A matter's owner** is the one exception: they need only be able to read the Store themselves.

## Stores Healdan Treats Differently

- **Default** is LogicalDOC's built-in workspace. It can't be renamed or deleted, and it holds nothing in Healdan,
  because documents only go into matters.
- **Templates** holds the workspace templates new matters are copied from. Healdan creates it on its first start,
  and only admins can see it. See [Workspace Templates](../workspace-templates/).

## Where Files Are Kept

All Stores share one **storage backend**: the place on the server where file contents are kept, by each file's
internal number. So moving a matter to another Store changes only the database, and is quick.
