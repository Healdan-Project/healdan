---
title: Waiting and Stuck Files
weight: 4
---

A file that hasn't reached its Store's backend yet is **waiting to move**: after the Store was given another
backend, after a matter moved in from another Store, or a save still to be uploaded. One the mover gave up on is
**stuck**. The Stores list, in **Storage → Stores**, says each Store's in its **Files** column, as "188 files waiting
(11.3 MB)" or "3 files stuck", and **Up to Date** when there are none; a Store's page says the same under **Files**,
and its **Storage** tab has **Waiting to Move** and **Stuck**, with the stuck files listed. The
[Backends list](../#the-backends-list) shows those of the files with no Store. None of them updates while you watch
it: they're asked again when you come back to the window, and **Refresh**, on the Stores list or the Backends list,
asks at once.

## Retries

When a file's move fails, the mover tries it again later: after 1, 2, 4, 8, 16 and 32 minutes, then every hour.
After ten failures in a row, about four hours after the first, the file is **stuck**: the mover leaves it where it
is, where it still opens, until you retry it.

- **A backend that can't be reached** doesn't use up any tries. Its moves pause until it's back, then carry on: see
  [When a Backend Fails](../when-a-backend-fails/). An outage never makes a file stuck.
- **An S3 service that refuses one file,** say with "access denied", makes the mover check the backend at once. If the
  whole backend fails the check, the backend is failing, and its moves pause. If it passes, the refusal counts as one
  failed try for that file.

## Stuck Files and Retry

A Store's stuck files are listed on its page's **Storage** tab, under **Stuck Files**, with **Retry** below them. For
the files with no Store, **Stuck Files**, on the Backends list, opens the list in a window, **Stuck Files with No
Store**. Each list has:

| Column | What it shows |
|---|---|
| Document ID | LogicalDOC's internal id for the document, not its doc # |
| File Name | the document's file name, or "Document deleted" for one deleted for good since |
| Resource | which of the document's files it is: a version's own file is named by its version, such as `1.2`, and the files made from it add to that, such as `1.2-thumb.png` for its thumbnail or `1.2-conversion.pdf` for its preview |
| Tries | how many times the mover has tried it |
| Last Error | why its last try failed |

The list holds the files of the newest documents, a thousand at most, and says how many it shows when there are
more, as "Listed: the files of the newest documents, 1,000 of 1,500."

Put the cause right, such as the S3 user's permissions or a full disk, then press **Retry**: under the Store's list,
or, for the files with no Store, in their window or beside **Stuck Files**. Every stuck file goes back to waiting,
with its tries counted from nought again, and moves with the others. Healdan says so, as "The stuck files of Client
Work wait to move again" or "The stuck files with no Store wait to move again", and the window closes. While the retry
is on its way, the window's **Close** is greyed out. Once a retry leaves none stuck, the focus moves to the heading
of the part it was in: **Files with No Store** on the Backends list, or **Stuck Files** on the Store's **Storage**
tab, which then says "No stuck files." until you leave the tab. Healdan records each retry that puts stuck files
back to waiting, with who and when.

## Files with No Store

**Files with No Store**, under the Backends list, counts the files whose document isn't in a Store, such as one saved
before it had a folder. They have no backend to choose.

| Line | What it shows |
|---|---|
| Waiting to Move | how many are still to move, and their size, such as "3 files (1.2 MB)", or **None** |
| Stuck | how many the mover gave up on, such as "1 file", or **None** |

**Stuck Files** and **Retry** show beside them while any are stuck, and work as above.

## Files That Wait Too Long

If a file has waited more than 24 hours to reach its Store's backend, Healdan emails the administrators: see
[The 24-Hour Alert](../when-a-backend-fails/#the-24-hour-alert).
