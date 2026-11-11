---
title: "Finding a Document by Doc #"
linkTitle: "Finding by Doc #"
weight: 2
---

Every document in Healdan has a **doc #**: a whole number that's unique across all Stores. It's given when the
document is filed and never changes, through every version, move and rename. A deleted document's number is never
given to another.

## Going Straight to a Document

Healdan's web app has an address for each doc #, `/app/doc/` and the number. Anyone who can read the document can use
it, so it makes a good link in an email or a note.

| Address | Opens |
|---|---|
| `/app/doc/1234` | document 1234, at its current version |
| `/app/doc/1234.2` or `/app/doc/1234v2` | version 2 as it stands: its latest snapshot |
| `/app/doc/1234.2.1` or `/app/doc/1234v2.1` | exactly snapshot 2.1 |

The page shows a list with just that document in it, already selected, and its details at the right: its properties
and its versions. For a version or snapshot, that version is marked in **Versions**.

- **It's found the moment it's filed.** Doc # addresses don't wait for the search index.
- **If you can't read the document,** Healdan answers as if it didn't exist: "No document # 1234 that you can see."
  The words are the same whether the document exists or not, so they never tell you which. They're the same for a
  version that doesn't exist, such as `/app/doc/1234.9`.
- **If what follows `/doc/` isn't a doc #,** the page says "Not found, or you can't see it."

## Copying a Link

Select a document in a folder's list, and choose **Copy Link** in its details. Healdan puts a link such as
`https://dms.example.com/app/doc/1234` on your clipboard, with your own server's address in front, and says "Link
copied". Paste it into an email or a note. Whoever opens it signs in first if they need to, and then sees the document
if they may read it.

Healdan can only copy to your clipboard when the page is on a secure address (`https://`), or on your own computer. If
it can't, it says "The link couldn't be copied." Type the address by hand instead: it's your page's address up to
`/app/`, then `doc/` and the number.

## Legacy Doc \#

A document moved in from another document management system keeps its old number as its **Legacy doc #**
property. Healdan still gives it a doc # of its own, and `/app/doc/` takes only doc #s, never legacy ones.
