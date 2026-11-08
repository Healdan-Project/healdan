---
title: Waiting and Stuck Files
weight: 4
---

The **Stores** list on the **Storage** tab shows, for each Store, what hasn't reached its backend yet:

| Column | What it shows |
|---|---|
| Waiting to Move | the files still to move to the Store's backend, and their size, such as "188 files (11.3 MB)": after the Store was given another backend, after a matter moved in from another Store, or saves still to be uploaded. **None** when every file is in place |
| Stuck | the files the mover gave up on, such as "1 file", underlined: click it for the list. **None** when there are none |

The tab doesn't update by itself: press **Refresh**.

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

Click a Store's **Stuck** count to open **Stuck Files of** the Store:

| Column | What it shows |
|---|---|
| Document | LogicalDOC's internal id for the document, not its doc # |
| File Name | the document's file name |
| Resource | which of the document's files it is: a version's own file is named by its version, such as `1.2`, and the files made from it add to that, such as `1.2-thumb.png` for its thumbnail or `1.2-conversion.pdf` for its preview |
| Tries | how many times the mover has tried it |
| Last Error | why its last try failed |

The list holds the files of the newest documents, a thousand at most, and says how many it shows when there are
more.

Put the cause right, such as the S3 user's permissions or a full disk, then press **Retry**. Every stuck file of the
Store goes back to waiting, with its tries counted from nought again, and moves with the others.

## Other Files

A line **Other Files** shows in the **Stores** list only when some files are waiting or stuck whose document isn't in
a Store, such as one saved before it had a folder. It has no backend to choose. Its stuck files open and retry as a
Store's do.

## Files That Wait Too Long

If a file has waited more than 24 hours to reach its Store's backend, Healdan emails the administrators: see
[The 24-Hour Alert](../when-a-backend-fails/#the-24-hour-alert).
