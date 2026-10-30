---
title: Stores and Matter Workspaces
weight: 1
---

Everything in Healdan lives in a **matter**, and every matter lives in a **Store**.

## Stores

A Store is a top-level container: Personal and Client Work, say, or one per region. Your administrator creates the
Stores and decides who can see each one. Stores show at the top of the folder tree.

## Matters

- **A matter belongs to a client.** Both have numbers. A matter is shown as its client's number, a dot, its own
  number, an en dash and its name: `998.1 – Firm Policies` is matter 1 of client 998. Your install may call matters
  *projects* instead.
- **A matter's workspace** is its folder in a Store. It's built from a workspace template, so matters of the same
  kind start with the same folders, for example Documents, Secured Documents, Emails, and search folders such as
  Recent Work.
- **Client and matter pass down.** Every folder and document in a workspace carries its client and matter, and so
  do the other [properties](../properties/) set on its folders.
- **Admins create matters,** and only admins add, rename, move or delete the folders directly inside a workspace.
  Below that, you can create folders wherever your permissions allow.
- **A workspace can't be renamed directly.** Its name follows the matter's, which an admin edits.

## Permissions

A matter's workspace decides who can see the matter. A folder inside it can be more restricted than the workspace,
for example a Secured Documents folder only some people can open, but it can never let in someone the workspace
doesn't. A single document can be shared more widely: see [Document Permissions](../document-permissions/).

## Words That Mean Something Else in LogicalDOC

Healdan's interface is LogicalDOC's, so some of its screens use LogicalDOC's words:

| Healdan says | LogicalDOC's screens say |
|---|---|
| Store | workspace |
| Matter workspace | folder |
