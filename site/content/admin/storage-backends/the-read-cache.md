---
title: The Read Cache
weight: 6
---

Healdan keeps copies of the files it reads from S3 backends on the server, in the **read cache**, so the next read
of a file costs no trip to the bucket, and an encrypted file is decrypted only once.

- **What it holds:** plain, decrypted copies of S3 backends' files: documents' files, previews and thumbnails alike.
  It never holds a local backend's files, which are read from their folder.
- **Where:** the `logicaldoc-cache` volume, at `/opt/logicaldoc/cache` in the app's container, on the server.
- **New saves are in it.** A save into a Store on S3 lands on the server first, and once it's uploaded, that copy
  becomes its entry in the cache. So a file saved recently opens from the server, during an outage too.
- **A file saved again** is never read from its old copy.
- **It survives a restart,** as the server reads what the folder holds when it starts.
- **Backups leave it out.** It holds only copies, so losing it costs nothing but the time the next reads take to come
  from the buckets.

## Its Size

Set it under **Settings** on the **Storage** tab, as the **Read Cache**, in GB from 1 to 10,000, 20 to begin with,
and press **Save**. When it's full, the files read least recently go first. A smaller size takes effect as files are
next added to the cache. The bigger it is, the more of the files people use open from the server, and the more of
them open during an outage.

## Large Files

A file larger than a tenth of the cache, 2 GB with the cache at 20 GB, isn't cached, so a few large files can't push
everything else out. It's read straight from the bucket, only the parts that are asked for, so a large video starts
playing at once. If the bucket stops answering in the middle of such a read, the file can arrive cut short: see the
Install manual's [Known Limitations](../../../install/known-limitations/).
