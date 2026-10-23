---
title: Users and Groups
weight: 5
---

Users and groups are managed under **Administration → Access Control**: **Users**, **Groups** and **Security**.

## Day-to-Day Accounts Shouldn't Be Admins

{{< callout type="warning" >}}
An admin account skips every permission check, so it can see and change everything. Give each person, admins
included, an ordinary account for their everyday work, and keep an admin account for administration.
{{< /callout >}}

Healdan's own rules, such as where documents can go, still apply to admins.

## The Everyone Group

LogicalDOC has no group of every user, so Healdan adds one, **Everyone**, on its first start.

- **Every user is in it,** except read-only users, and can't be taken out. Healdan puts a user back if a screen or
  the API drops them.
- **It can't be renamed or deleted.**
- **Use it in templates** for access that's open to everyone at your firm, such as reading a matter's Documents
  folder.

## Personal Groups

Each user also has a personal group that holds only them. It's how a permission names one person: a matter's owner,
for example, gets every permission on the workspace through theirs.
