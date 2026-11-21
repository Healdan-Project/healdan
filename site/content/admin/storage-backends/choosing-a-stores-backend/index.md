---
title: Choosing a Store's Backend
weight: 3
---

Each Store keeps its files on one backend: Original to begin with, unless
[New Store](../../stores/#creating-a-store) chose another. To give it another, press **Change Backend** on the
Store's page, in the Admin app's **Storage → Stores**.

**Change the Backend of Client Work** lists every backend, Original first. The one the Store is on says "Where its
files are kept now.", and an encrypted backend whose recovery key isn't confirmed is greyed out, with "Its recovery
key isn't confirmed, so no Store can use it yet." Choose another, and Healdan first counts the files that will move,
and says so before anything changes:

> 188 files (11.3 MB) will move to Cloud Storage in the background. Until each has moved, it's read from where it is
> now.

When none has to move, it says "No file has to move, so the change takes effect at once." **Change Backend**, in the
window, waits for the count, then gives the Store the backend, and Healdan says "Client Work now keeps its files on
Cloud Storage". While the change is on its way, **Cancel** is greyed out and Esc does nothing. Anything Healdan
refuses, such as a backend removed meanwhile, shows in the window in Healdan's own words.

From then on the Store keeps its files on the new backend, and new saves into it go there. Its files move there in
the background: the Store's **Files**, on its page and in the Stores list, and **Waiting to Move**, on its **Storage**
tab, show how many are left, and their size. They don't update while you watch: press **Refresh** on the Stores
list, or come back to the window, to see the number fall. Healdan records each change, with the backend before and
after.

## How Files Move

Healdan's **mover** moves files between backends in the background. For each file, it:

1. reads the file from where it is, decrypting it if that backend is encrypted;
2. writes it to the Store's backend, encrypting it if that one is, and checks the copy: an S3 service refuses an
   upload that doesn't match its checksum, and Healdan reads the uploaded size back; a copy in a folder is written to
   disk and its size checked;
3. switches the file over to the new copy, unless the file was saved again meanwhile, in which case it throws its
   copy away and moves the new one later;
4. holds the old copy for the holding period, rather than deleting it: see
   [Held Deletes and the Holding Period](../held-deletes/).

So a file is never removed from one place before its copy is safe in the other, and until it has moved, it's read
from where it is: documents open as usual throughout.

- **When:** at once, whenever something gives files a new place, and every 5 minutes besides.
- **In what order:** files waiting on the server to be uploaded first, then the rest, newest documents first.
- **How many at once:** **Parallel Transfers**, under **Settings** on the [Backends list](../#the-backends-list), 4
  to begin with, from 1 to 16. More moves a large Store sooner, if the server's connection has room. A new value
  applies as soon as it's saved.
- **When one fails:** it's tried again later, and after ten failures it's left where it is: see
  [Waiting and Stuck Files](../waiting-and-stuck-files/). A backend that can't be reached pauses its moves instead:
  see [When a Backend Fails](../when-a-backend-fails/).

## Saves into a Store

- **On a local backend,** a save is written straight to its folder, encrypted if the backend is.
- **On an S3 backend,** a save lands on the server first, in staging, so it's as quick as a save into a local Store.
  The mover uploads it in the background, usually within seconds. Previews and thumbnails are made from the copy on
  the server, at the server's speed too.
- **Once it's uploaded,** the copy on the server becomes the file's entry in the [read cache](../the-read-cache/), so
  a file saved recently opens from the server.
- **Staging is in the repository volume,** so the nightly backup takes any file that hasn't been uploaded yet.
- **During an outage,** saves into a Store on S3 still work, and wait in staging until it's back.

## Matters Moving Between Stores

Moving a matter to another Store keeps the rules in [Stores](../../stores/), and the move itself is as quick as
before. Its files then follow it to the other Store's backend in the background, as above, and so do those of a
document moved into a matter in another Store, or of a folder restored from the trash into another Store. Until each
file has moved, it's read from where it is.

Every night at 03:17, server time, Healdan works out every file's Store again from the folder tree, puts right any
file whose record says another Store, and logs each one it fixes, so no file stays on the wrong backend.

## Giving a Store Back to Original

Give the Store **Original**, with **Change Backend**. Its files come back to the server in the background, and their
copies on the old backend are held for the holding period, then deleted. A matter moved from a Store in the cloud to one
on the server does the same.

## In the Classic Interface

The Classic Interface's **Storage** tab does the same: click a Store's **Backend** in its **Stores** list, and choose
one. It offers the same backends, and asks first, saying how many files will move:

![The Storage tab's Change the Backend of Client Work question: 188 files (11.3 MB) will move to Cloud Storage in the background. Until each has moved, it's read from where it is now. Give Client Work this backend? With Yes and No](change-backend.png)

Choose **Yes**, and its files move in the background, as above. **Waiting to Move**, in the tab's **Stores** list,
shows how many are left, and their size. The tab doesn't update by itself: press **Refresh**.

![The Storage tab's Stores list just after the change: Client Work on Cloud Storage with 188 files (11.3 MB) waiting to move, and Firm and Templates on Original with nothing waiting and nothing stuck](stores-waiting.png)
