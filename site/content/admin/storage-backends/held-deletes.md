---
title: Held Deletes and the Holding Period
weight: 5
---

Healdan never deletes a file from a backend at once. Every removal waits out the **holding period**, 30 days to begin
with, so that a backup restored later still finds every file it names:

- a version that's deleted;
- a document purged from the trash;
- a preview or thumbnail that's made again;
- a file's old copy, once the mover has copied it to its Store's new backend;
- a file's old copy on another backend, when the file is saved again.

## Where Held Copies Wait

- **On an S3 backend,** nothing is sent to the bucket: the object stays where it is, and Healdan records it with the
  date it may be deleted.
- **On a local folder,** the file moves into the `.held` folder at the backend's root, under the same path, which is
  instant on the same disk. If a file is missing from its place, as after a database restored from a backup, Healdan
  looks for it there.

Once a day, the purge deletes the held copies whose date has come, but not in the first 10 minutes after the server
starts. It leaves an S3 object that Healdan is using again, as when the same file was uploaded to it since.

## The Holding Period

Set it under **Settings** on the **Storage** tab, as the **Holding Period**, in days from 1 to 365, and press
**Save**. A change applies to the copies held from then on: those already held keep their date.

**Keep it at least as long as the oldest backup you'd restore.** A database restored from a backup younger than the
holding period finds every file it names, on every backend: the Install manual's
[Backup and Restore](../../../install/backup-and-restore/) says more.

## Replace File

**Replace file**, on a version's right-click menu in a document's **Versions** tab, writes a new file under the old
file's name, so the old file isn't held as a deleted one is. The previews and thumbnails made from it are held as
usual.

- **In a Store on a local backend,** the new file overwrites the old one at once.
- **In a Store on an S3 backend,** the new file waits in staging, and the old one stays in the bucket, held, only
  until the mover uploads the new file to the same name, usually within seconds.

So a backup restored from before a Replace File gets the new file, unless it's restored while the new file is still
waiting to be uploaded.

## What It Costs

- **Space on the backend,** for the held copies, until they're purged.
- **On S3,** held objects are stored and billed until they're purged. A matter moved from a Store on S3 to one on
  the server leaves its encrypted copies in the bucket until the holding period ends. Wasabi's pay-as-you-go plan
  bills each object for at least 90 days however soon it's deleted, so holding a copy there costs nothing more while
  the copy is less than 90 days old: see the Install manual's Cloud Storage page.
- **Removing a backend** waits until its held copies are purged: see [Removing a Backend](../removing-a-backend/).
