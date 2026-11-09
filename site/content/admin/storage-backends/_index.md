---
title: Storage Backends
weight: 8
---

A **storage backend**, or **backend**, is a place where Healdan keeps documents' files: a folder on the server, or a
bucket at an S3 service such as Wasabi. Each [Store](../stores/) keeps its files on one backend, so Personal can stay
on the server's disk while Client Work goes to the cloud. Backends are set up on the **Storage** tab of
[Healdan Settings](../healdan-settings/), under **Administration → Healdan → Settings**, which only administrators
see. LogicalDOC calls a place for files a *store*, but in Healdan a Store is a top-level container for matters, so
these pages say *backend*.

![The Storage tab: Original, a local folder at /opt/logicaldoc/repository/docs holding 18 files, and Cloud Storage, an encrypted S3 backend on the demo stack's stand-in S3 server holding 186 files, both OK, Cloud Storage's recovery key last shown to sokafor when it was added; the Stores list with Client Work on Cloud Storage and Firm and Templates on Original, nothing waiting or stuck; and the settings, a 30-day holding period, a 20 GB read cache and 4 parallel transfers](storage-tab.png)

## What a Backend Holds

- **The files of the documents in its Stores:** each version's own file, and the preview, thumbnail and other files
  Healdan makes from it.
- **Nothing else.** The database, the search index (which holds every document's words), the logs and the
  configuration stay on the server. Searching, browsing and properties never wait on a backend.
- **Nothing of a Store on a local backend goes to S3,** not even a preview. A file goes only to its own Store's
  backend. A Store on S3 also uses two folders on the server: staging, where a save waits to be uploaded (see
  [Choosing a Store's Backend](choosing-a-stores-backend/#saves-into-a-store)), and the
  [read cache](the-read-cache/).

## Original

**Original** is the backend every install starts with: the folder Healdan has always kept files in,
`/opt/logicaldoc/repository/docs` in the repository volume. Every Store keeps its files there until you give it
another backend, and a new Store starts there too.

- **It can be renamed,** with **Edit**, and nothing else about it changes. Its folder comes from the configuration
  (`store.1.dir` in `context.properties`), so the tab shows it but can't change it.
- **It can't be removed,** as it keeps the files of every Store that has no other backend.
- **It isn't encrypted.**

## The Storage Tab

| Part | What it shows or does |
|---|---|
| Backends | each backend: its **Name**; its **Kind**, Local Folder or S3; **Where** it is, the folder or the bucket and prefix at the endpoint; whether it's **Encrypted**; the **Files** recorded on it and their **Size**; its **Status** (below); and **Key Last Shown**, when its recovery key was last shown and to whom |
| Add Backend | adds a backend. See [Adding a Backend](adding-a-backend/) |
| Edit, Test, Show Recovery Key, Enter Recovery Key, Remove | act on the selected backend. Double-clicking a backend edits it, and right-clicking it gives the same choices and **Key History** |
| Refresh | loads the tab again. It doesn't update by itself, so press **Refresh** to see a move's progress or a new status |
| Stores | each Store, with its **Backend**, the files **Waiting to Move** to that backend, and its **Stuck** files. Every Store is listed but LogicalDOC's Default workspace, which holds nothing, so Templates is listed too. See [Choosing a Store's Backend](choosing-a-stores-backend/) and [Waiting and Stuck Files](waiting-and-stuck-files/) |
| Settings | the **Holding Period** (see [Held Deletes and the Holding Period](held-deletes/)), the **Read Cache**'s size (see [The Read Cache](the-read-cache/)) and **Parallel Transfers**, how many files move between backends at once: 4 to begin with, from 1 to 16. **Save**, under them, saves all three |

### A Backend's Status

| Status | What it means |
|---|---|
| OK | its last check passed |
| Failing since *date and time*: *why* | something stops Healdan using it, such as an S3 service that can't be reached or a folder whose share didn't mount. See [When a Backend Fails](when-a-backend-fails/) |
| Not Checked Yet | the server hasn't checked it since it started. It checks every backend as it starts |

An encrypted backend whose recovery key isn't confirmed yet adds "Its recovery key isn't confirmed, so no Store can
use it yet": see [Encryption and Recovery Keys](encryption-and-recovery-keys/).

The status is what the backend's last use, Test or check found. A backend that's OK isn't checked again on a timer,
so one that stops answering shows Failing once a save, a read, a move or **Test** meets the problem, not before.

## In This Section

- [Adding a Backend](adding-a-backend/): a local folder or an S3 bucket, Test, and changing a backend's settings.
- [Encryption and Recovery Keys](encryption-and-recovery-keys/): what's encrypted, and the key you mustn't lose.
- [Choosing a Store's Backend](choosing-a-stores-backend/): giving a Store a backend, and how its files move.
- [Waiting and Stuck Files](waiting-and-stuck-files/): files that haven't moved yet, and **Retry**.
- [Held Deletes and the Holding Period](held-deletes/): why nothing is deleted at once.
- [The Read Cache](the-read-cache/): copies of S3 files on the server.
- [When a Backend Fails](when-a-backend-fails/): outages, and the 24-hour alert.
- [Removing a Backend](removing-a-backend/).

The Install manual's [Cloud Storage](../../install/cloud-storage/) says how to set up a bucket at Wasabi, and
[Backup and Restore](../../install/backup-and-restore/) what to keep safe.
