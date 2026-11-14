---
title: "Finding a Document by Doc #"
linkTitle: "Finding by Doc #"
weight: 2
---

Every document in Healdan has a **doc #**: a whole number that's unique across all Stores. It's given when the
document is filed and never changes, through every version, move and rename. A deleted document's number is never
given to another.

## Going Straight to a Document

Type a `#` and a doc # in the app's search box and press Enter, as in `#1234`, and the page for that document opens.
Any scope will do. See [Searching](../searching/#in-the-box).

Behind that is an address for each doc #, `/app/doc/` and the number. Anyone who can read the document can use it, so
it makes a good link in an email or a note.

| Address | Opens |
|---|---|
| `/app/doc/1234` | document 1234, at its current version |
| `/app/doc/1234.2` or `/app/doc/1234v2` | version 2 as it stands: its latest snapshot |
| `/app/doc/1234.2.1` or `/app/doc/1234v2.1` | exactly snapshot 2.1 |

The page shows a list with just that document in it, already selected, and its details at the right: its properties
and its versions. For a version or snapshot, the details open on the **Versions** tab, with that version marked.

![The page for document # 31 in the web page: the toolbar, with Open, Preview, Download, Check Out and the ⋯ (More) button, above a list with just Subscription Agreement – Draft.docx, version 2, by Helen Ashcombe, selected, and its details at the right with the foot of its thumbnail, its name with the filled Favorites star, its properties in matter 1003.1 – Series A Investment, and its versions, where Version 1.1, saved on 25 Oct 2026 at 15:30 by Priya Nair, is marked](doc-page.png)

- **It's found the moment it's filed.** Doc # addresses don't wait for the search index.
- **If you can't read the document,** Healdan answers as if it didn't exist: "No document # 1234 that you can see."
  The words are the same whether the document exists or not, so they never tell you which. They're the same for a
  version that doesn't exist, such as `/app/doc/1234.9`.
- **If what follows `/doc/` isn't a doc #,** the page says "Not found, or you can't see it."

## The Doc ID

`#1234 v2.1` is the doc ID: the form LibreOffice's title bar shows (`Lease agreement #1234 v2.1`) and the [doc ID
footer](../../office/doc-id-footer/) prints, so you can copy it from either. The Healdan Helper's Save and Open windows
take the same forms as the table above, with the `#`, and a space after the `#` or before the `v`, and so does the web
app's search box. In the web app's addresses, leave the `#` and the spaces out, as in the table.

## Copying a Link

Select a document in a folder's list, and choose **Copy Link** in its details. Healdan puts a link such as
`https://dms.example.com/app/doc/1234` on your clipboard, with your own server's address in front, and says "Link
copied". Paste it into an email or a note. Whoever opens it signs in first if they need to, and then sees the document
if they may read it.

If your browser won't let Healdan use the clipboard, it says "The link couldn't be copied." Type the address by hand
instead: it's your page's address up to `/app/`, then `doc/` and the number. The app itself only opens at a secure
address (`https://`), or on the computer Healdan runs on, so the clipboard is there for it in a current browser.

## In the Classic Interface

The [Classic Interface](../../the-classic-interface/)'s search box takes the same forms with the `#` in front, with its
scope on **Documents**: `#1234` lists just that document, and `#1234.2.1` opens a window for that snapshot. Without the
`#`, `1234` is an ordinary full-text search, because numbers appear inside documents too.

## Legacy Doc \#

A document moved in from another document management system keeps its old number as its **Legacy doc #**
property. Healdan still gives it a doc # of its own, and `/app/doc/` takes only doc #s, never legacy ones.
