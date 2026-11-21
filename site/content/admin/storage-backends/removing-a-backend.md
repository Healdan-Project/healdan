---
title: Removing a Backend
weight: 8
---

Only an empty backend can be removed: one that no Store uses, with no file recorded on it and no copy held there.
Original can't be removed, as it keeps the files of every Store that has no other backend, so its page has no
**Remove**.

1. **Give each of its Stores another backend,** on each Store's page in **Storage → Stores**: see
   [Choosing a Store's Backend](../choosing-a-stores-backend/). <!-- P3 docs pass 2: the Store's page --> The
   backend's page lists them under **Stores**.
2. **Wait for the files to move,** until the backend's **Files**, on the Backends list, is 0, as its page's **Holds**
   says too. Press **Refresh** on the list to see it fall.
3. **Wait for the holding period to end,** so the purge deletes the copies it holds: see
   [Held Deletes and the Holding Period](../held-deletes/). A shorter holding period applies only to copies held after
   it's saved.
4. **On the backend's page, press Remove.** Healdan asks first, "Remove Cloud Storage?", saying what removing does
   (below) and that it can't be undone. **Remove**, in that question, removes it, says "Removed Cloud Storage" and
   goes back to the Backends list.

Remove says why when it can't, such as "Store Client Work keeps its files on this backend: give it another backend
first.", "3 files are recorded on this backend: it can be removed once they have moved off it." or "12 copies are
held on this backend until the holding period ends: it can be removed once the purge has deleted them." Healdan
records each backend removed, with who removed it and when.

## What Removing Does

- **Its key file is deleted,** for an encrypted backend, and its settings leave the database, its sealed S3 secret
  among them.
- **Its marker and check object are deleted** from its folder or bucket, so another backend can have that place.
- **The folder or bucket itself stays,** with anything else in it.
- **The record of its recovery key's showings stays.**
