---
title: Permissions
weight: 6
---

Healdan uses LogicalDOC's permissions, set on each Store's, folder's and document's **Security** tab, and adds
rules so a matter's permissions stay consistent.

## Folders Narrow, Never Widen

A folder inside a matter that has its own permissions may list only groups its parent folder lists, each with the
same permissions or fewer. So a Secured Documents folder can shut out people who can read the rest of the matter,
but no folder can let in someone its parent doesn't.

- **Narrowing a parent narrows its children.** If you take a group's access away from a folder, Healdan takes it away
  from the restricted folders inside it too.
- **Moving, copying or restoring a folder** into a more restricted folder narrows it to fit, without asking.
- **"Apply to subfolders" can't open a restricted folder.** LogicalDOC's option replaces every subfolder's
  permissions with the parent's, which would widen a restricted folder. Inside Stores, Healdan allows it only to
  someone with the Security permission on every restricted folder below, or to an admin.

## A Matter and Its Store

Every group a matter lets read it must be able to read its Store, so a matter can't be read through search by people
who can't see its Store. The matter's owner only needs to be able to read the Store themselves. See
[Stores](../stores/#who-can-see-a-store).

## Who Can Change Permissions

Changing a folder's or document's permissions, or making a folder follow its parent again, needs the **Security**
permission on that folder or document. Renaming needs **Rename**.

## One-Off Grants on a Document

A document can be given to someone who can't see its folder, as a one-off or for a while. They can reach it by its
doc # or a link, but not by browsing. The rule that folders only narrow doesn't apply to documents. See
[Document Permissions](../../user/working-with-matters/document-permissions/).

## What These Rules Don't Do

Permissions are checked when they're saved, so two changes aren't checked:

- **A Store's own permissions.** Taking a group off a Store leaves the matters in it readable by that group, if their
  own permissions let it in. Change the matters' permissions too.
- **Group membership.** If someone joins or leaves a group later, Healdan doesn't re-check the matters that group can
  read against their Stores.
