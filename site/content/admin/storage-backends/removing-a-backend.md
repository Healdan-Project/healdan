---
title: Removing a Backend
weight: 8
---

Only an empty backend can be removed: one that no Store keeps its files on, whether the Store is live or in the
Trash, with no file recorded on it and no copy held there. Original can't be removed, as it keeps the files of every
Store that has no other backend, so its page has no **Remove**.

1. **Give each of its Stores another backend,** with **Change Backend** on each Store's page in
   **Storage → Stores**: see [Choosing a Store's Backend](../choosing-a-stores-backend/). The backend's page lists
   them under **Stores**.
2. **Bring back each of its Stores in the Trash, give it another backend, then delete it again.** A deleted Store's
   files stay on its backend while it's in the Trash or removed from it, as its restore needs them. The backend's
   page lists those Stores under **Stores in the Trash**. Restore each from **Deleted Stores** on the Stores list (see
   [Deleted Stores](../../stores/#deleted-stores)), give it another backend with **Change Backend** on its page, then
   delete it again.
3. **Wait for the files to move,** until the backend's **Files**, on the Backends list, is 0, as its page's **Holds**
   says too. Press **Refresh** on the list to see it fall.
4. **Wait for the holding period to end,** so the purge deletes the copies it holds: see
   [Held Deletes and the Holding Period](../held-deletes/). A shorter holding period applies only to copies held after
   it's saved.
5. **On the backend's page, press Remove.** Healdan asks first, "Remove Cloud Storage?", saying what removing does
   (below) and that it can't be undone. **Remove**, in that question, removes it, says "Removed Cloud Storage" and
   goes back to the Backends list.

## When Something Still Keeps It

While anything keeps the backend, **Remove** doesn't ask: it opens "Cloud Storage Can't Be Removed Yet", which says
what keeps it and how to free it, with only **Close**, and nothing is removed:

- **Its Stores,** listed by name under "A Store keeps its files on it. Give it another backend first, with Change
  Backend on its page:", or, for several, under how many there are, as "2 Stores keep their files on it. …".
- **Its Stores in the Trash,** listed by name under "A Store in the Trash keeps its files on it, as its restore needs
  them. Restore it from Deleted Stores on the Stores page, give it another backend, then delete it again:", or, for
  several, under how many there are.
- **Its files and held copies:** "Files are recorded on it, or old copies held there. It can be removed once every
  file has moved off it and the holding period has ended."

The page knows only what it last read, so Healdan checks again when you press **Remove** in the question. If
something changed meanwhile, such as a Store given the backend, the question says why it wasn't removed, in Healdan's
words: "Store Client Work keeps its files on this backend: give it another backend first.", "Store Archive is in the
Trash, and its files stay on this backend while it's there, so the backend can't be removed: restore the Store, give
it another backend, then delete it again.", "3 files are recorded on this backend: it can be removed once they have
moved off it.", "12 copies are held on this backend until the holding period ends: it can be removed once the purge
has deleted them." or "That backend is still being added: try again once its add has finished."

Healdan records each backend removed, with who removed it and when.

## What Removing Does

- **Its key file is deleted,** for an encrypted backend, and its settings leave the database, its sealed S3 secret
  among them.
- **Its marker and check object are deleted** from its folder or bucket, so another backend can have that place.
- **The folder or bucket itself stays,** with anything else in it.
- **The record of its recovery key's showings stays** in Healdan's records and the administration record, though no
  screen shows it once the backend's page has gone.
