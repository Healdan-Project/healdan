---
title: Stores
weight: 1
---

A **Store** is a top-level container for matters: Personal and Client Work, say, or one per region. In LogicalDOC's
screens a Store is a *workspace*, and they show at the top of the Documents tab's folder tree.

## Creating a Store

Only admins create Stores. In the Admin app, **New Store…**, beside the Store in
[New Matter](../clients-and-matters/#creating-a-matter), makes one, and New Matter then chooses it:

- **Name:** at most 255 characters, with no `/` or `\`. New Store says so before **Create Store** when it's too long
  ("That is too long.") or has one of them ("That name isn't allowed.").
- **Who Can See It:** the groups that can see the Store. **Add a Group** lists the first 50 groups by name, and finds
  the others as you type two characters or more. A group added leaves that list, and the Remove button (an ×) beside
  it takes it off again. With none added, New Store says only administrators can see the Store.

A new Store keeps its files on Original, the server's own storage, as New Store says, until you give it another
storage backend (see [Where Files Are Kept](#where-files-are-kept)). Anything Healdan refuses when you press **Create
Store** shows in the dialog, in Healdan's own words. While the Store is being made, **Cancel** is greyed out and Esc
does nothing; once it's made, Healdan says "Created" with its name.

In the Classic Interface, right-click a Store in the folder tree and choose **New workspace**, then name it. Rename a
Store the same way, with **Rename**.

## Who Can See a Store

A Store's permissions decide who can see it, and they limit who can see the matters in it. A Store made in the Admin
app starts with the groups its **Who Can See It** named, and New Matter's button that lets a group read the Store,
such as **Let Everyone Read the Store**, adds that group, keeping everyone who could see it already, as below.
Otherwise, change them on the Store's **Security** tab in the Classic Interface.

**Before creating matters, let the groups your templates use read the Store.** A Store made in the Classic Interface
usually lets in only the admin who created it, and a matter that a group can read is refused when the Store doesn't
let that group in. The Admin app's New Matter says so before you create the matter, as in "Everyone can't read the
Store Regional Office.", with a button that lets the group in there and then: see
[Letting a Group Read the Store](../clients-and-matters/#letting-a-group-read-the-store). The Classic Interface's New
Matter refuses it when you click **Create**: "Everyone can read this matter but not its Store".

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

Each Store keeps its documents' files on one **storage backend**: a folder on the server, or a bucket at an S3 service
such as Wasabi. So Personal can stay on the server's disk while Client Work goes to the cloud. Every Store starts on
**Original**, the server's own folder, and so does a new Store. To give a Store another backend, choose it in the
**Stores** list on the **Storage** tab of [Healdan Settings](../healdan-settings/#the-storage-tab): see
[Storage Backends](../storage-backends/).

- **Moving a matter to another Store,** with **Move to Another Store** on
  [its page](../clients-and-matters/#move-to-another-store), is as quick as before: it changes the database at once.
  When the other Store keeps its files on another backend, the matter's files then follow it there in the background,
  and until each has moved it's read from where it is. See
  [Choosing a Store's Backend](../storage-backends/choosing-a-stores-backend/#matters-moving-between-stores).
- **LogicalDOC's own Stores screen,** under **Administration → Settings**, is hidden, as Healdan's storage backends
  take its place. There, a *store* meant a place for files, not a Store.
