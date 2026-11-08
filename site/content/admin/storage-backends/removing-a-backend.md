---
title: Removing a Backend
weight: 8
---

Only an empty backend can be removed: one that no Store uses, with no file on it and no copy held there. Original
can't be removed, as it keeps the files of every Store that has no other backend.

1. **Give each of its Stores another backend,** in the **Stores** list: see
   [Choosing a Store's Backend](../choosing-a-stores-backend/).
2. **Wait for the files to move,** until the backend's **Files** is 0. Press **Refresh** to see it fall.
3. **Wait for the holding period to end,** so the purge deletes the copies it holds: see
   [Held Deletes and the Holding Period](../held-deletes/). A shorter holding period applies only to copies held after
   it's saved.
4. **Select the backend, and press Remove.** Healdan asks first.

Remove says why when it can't, such as "Store Client Work keeps its files on this backend: give it another backend
first", "3 files are recorded on this backend", or "12 copies are held on this backend until the holding period
ends".

## What Removing Does

- **Its key file is deleted,** for an encrypted backend, and its settings leave the database, its sealed S3 secret
  among them.
- **Its marker and check object are deleted** from its folder or bucket, so another backend can have that place.
- **The folder or bucket itself stays,** with anything else in it.
- **The record of its recovery key's showings stays.**
