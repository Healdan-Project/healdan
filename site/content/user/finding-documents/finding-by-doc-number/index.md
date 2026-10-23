---
title: "Finding a Document by Doc #"
linkTitle: "Finding by Doc #"
weight: 2
---

Every document in Healdan has a **doc #**: a whole number that's unique across all Stores. It's given when the
document is filed and never changes, through every version, move and rename. A deleted document's number is never
given to another.

## Going Straight to a Document

With the search box's scope on **Documents**, type the doc # with a `#` in front and press Enter:

| Type | Finds |
|---|---|
| `#1234` | document 1234, at its current version |
| `#1234.2` or `#1234v2` | version 2 as it stands: its latest snapshot |
| `#1234.2.1` or `#1234v2.1` | exactly snapshot 2.1 |

![The search box with #3 typed in, and the Search tab listing one result: Charterparty – MV Northern Star.docx, doc # 3](doc-number-lookup.png)

A space after the `#` is fine. The result is just that document, ready to open, preview, download, or to look at
its properties and versions. For a version or snapshot, its row in the Versions tab is a click away. See
[Versions](../../documents/versions/).

- **It's found the moment it's filed.** Doc # lookups don't wait for the search index.
- **If you can't read the document,** Healdan answers as if it didn't exist: "No document #1234".
- **Without the `#`,** `1234` is an ordinary full-text search, because numbers appear inside documents too.

## Legacy Doc \#

A document moved in from another document management system keeps its old number as its **Legacy doc #**
property. Healdan still gives it a doc # of its own, and `#` searches only doc #s, never legacy ones.
