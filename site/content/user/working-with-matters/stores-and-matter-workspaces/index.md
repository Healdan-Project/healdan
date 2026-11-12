---
title: Stores and Matter Workspaces
weight: 1
---

Everything in Healdan lives in a **matter**, and every matter lives in a **Store**.

## Stores

A Store is a top-level container: Personal and Client Work, say, or one per region. Your administrator creates the
Stores and decides who can see each one. You don't open a Store to look for a matter: you reach matters through
[My Matters and Recent Matters](../../finding-documents/my-matters-and-recent-matters/). The Classic Interface's
folder tree still shows the Stores at its top.

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

## Opening a Matter

Click a matter in the left pane and the main area shows its top level: its folders and search folders, by name, then
any documents an administrator put there before Healdan kept documents out of it. Open a folder from the list or the
tree, and the main area shows the folder the same way.

![The top level of matter 1002.1 – Commercial Lease, under the breadcrumb for its client Brightwell Bakeries Ltd, with the Upload, Open, Preview, Download and Check Out buttons and a list of its folders, Correspondence, Drafts, Due Diligence, Emails and Executed, and its search folders Emails in This Matter and My Recent Docs Here](matter-top-level.png)

- **The breadcrumb** at the top says where you are: the client, the matter as `998.1 – Firm Policies`, then each folder
  down to this one. Click the matter or a folder in it to go there.
- **The star** beside a folder's name adds the folder to **My Favorites** in the left pane, and takes it off again.
  A matter's top level has no star: add the matter to
  [My Matters](../../finding-documents/my-matters-and-recent-matters/) instead.
- **The count** above the list says how many documents the folder holds, such as "7 documents". It counts every
  document you can read, however many the list has shown so far.
- **The list** shows the folder's own folders first, by name, then its documents, with their **Title** (the file
  name), **Doc #**, **Version**, **Author** and **Edit Date**. A whole version shows as its number, such as 3, and a
  snapshot as 3.1. **Edit Date** is when the document's file last changed: when it was filed, or when a new version of its file
  was saved. Changing its properties, renaming it or moving it doesn't change it. A long list shows 100 documents at a time
  and brings in the next 100 as you scroll near its end.
- **Sorting.** The documents come by title to begin with. Click a column's heading to sort by it, and click it again
  to sort the other way. The folders stay first, by name.
- **Selecting.** Click a document to select it, and Shift-click or ⌘-click (Ctrl-click on Windows) to select more.
  The up and down arrow keys move the selection. Click a folder, or press Enter on it, to open it.

The page's address keeps the sort and the selected document, so the link you copy, Back and Reload show the folder
the same way. A document you can't read never shows, in the list or in the count.

## Permissions

A matter's workspace decides who can see the matter. A folder inside it can be more restricted than the workspace,
for example a Secured Documents folder only some people can open, but it can never let in someone the workspace
doesn't. A single document can be shared more widely: see [Document Permissions](../document-permissions/).

## Words That Mean Something Else in LogicalDOC

The Classic Interface is LogicalDOC's screens, so some of them use LogicalDOC's words:

| Healdan says | LogicalDOC's screens say |
|---|---|
| Store | workspace |
| Matter workspace | folder |
