---
title: Storage Backends
weight: 8
---

A **storage backend**, or **backend**, is a place where Healdan keeps documents' files: a folder on the server, or a
bucket at an S3 service such as Wasabi. Each [Store](../stores/) keeps its files on one backend, so Personal can stay
on the server's disk while Client Work goes to the cloud. LogicalDOC calls a place for files a *store*, but in Healdan
a Store is a top-level container for matters, so these pages say *backend*.

Backends are set up in [the Admin app](../the-admin-app/): **Backends** is under **Storage** in its left pane. Each
Store's backend is chosen on the Store's page in **Storage → Stores**: see
[Choosing a Store's Backend](choosing-a-stores-backend/). <!-- P3 docs pass 2: the Stores screens (Task 24b) -->
The Classic Interface's **Storage** tab does the same, and stays for now: see
[The Storage Tab in the Classic Interface](#the-storage-tab-in-the-classic-interface).

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

- **It can be renamed,** with **Edit** on its page, and nothing else about it changes. Its folder comes from the
  configuration (`store.1.dir` in `context.properties`), so its page shows it but can't change it.
- **It can't be removed,** as it keeps the files of every Store that has no other backend.
- **It isn't encrypted.**

## The Backends List

**Backends**, at `/admin/backends`, lists every backend, Original first and then the others in the order they were
added. **Add Backend**, beside the title, adds one: see [Adding a Backend](adding-a-backend/). Select a backend to open
its page.

| Column | What it shows |
|---|---|
| Name | the backend's name |
| Kind | **Local Folder** or **S3** |
| Where | a folder's path, or an S3 bucket and its prefix at its endpoint, as `healdan-smoke/healdan/ at https://s3.eu-west-1.wasabisys.com`. The endpoint shows as its scheme, host and port alone |
| Encrypted | **Yes** or **No**: see [Encryption and Recovery Keys](encryption-and-recovery-keys/) |
| Files, Size | the files recorded on it, and their size |
| Status | whether Healdan can use it now (below) |
| Key Last Shown | for an encrypted backend, when its recovery key was last shown and to whom, or **Never**; empty for one that isn't encrypted |

The list asks the server again each time you come back to its window, and after your own changes, but not while
you watch it: press **Refresh** to see a move's progress or a new status.

Under the list are two more parts:

- **Files with No Store:** the files whose document isn't in a Store, waiting to move or stuck, with **Stuck Files**
  and **Retry** when any are stuck. See [Waiting and Stuck Files](waiting-and-stuck-files/#files-with-no-store).
- **Settings:** **Holding Period (Days)** (see [Held Deletes and the Holding Period](held-deletes/)),
  **Read Cache (GB)** (see [The Read Cache](the-read-cache/)) and **Parallel Transfers**, how many files move between
  backends at once: 4 to begin with, from 1 to 16. Each says its limits under it, and a value outside them, or one
  that isn't a whole number, says so at once, as "Enter a whole number from 1 to 16." **Save**, under them, saves
  all three, and stays greyed out until every value is within its limits and one has changed. A change takes effect
  at once, with no restart.

**Backends**, in the left pane, shows a count while any backend can't be reached or is failing, as the
[Dashboard](../the-admin-app/#needs-attention)'s Needs Attention counts them.

### A Backend's Status

| Status | What it means |
|---|---|
| OK | its last check passed |
| Can't Be Reached since *date and time*: *why* | an outage: the S3 service, or the network to it, doesn't answer, or a folder can't be read. See [When a Backend Fails](when-a-backend-fails/) |
| Failing since *date and time*: *why* | something else stops Healdan using it, such as a folder whose share didn't mount, a bucket that's gone or a key file that's missing |
| Not Checked Yet | the server hasn't checked it since it started. It checks every backend as it starts |

An encrypted backend whose recovery key isn't confirmed yet adds "Its recovery key isn't confirmed, so no Store can
use it yet.": see [Encryption and Recovery Keys](encryption-and-recovery-keys/#saving-the-recovery-key).

The status is what the backend's last use, Test or check found. A backend that's OK isn't checked again on a timer,
so one that stops answering shows Can't Be Reached once a save, a read, a move or **Test** meets the problem, not
before.

## A Backend's Page

A backend's page, at `/admin/backends/` and its number, shows its status under its name, and then:

| Fact | What it shows |
|---|---|
| Kind, Where | as in the list |
| Holds | the files recorded on it and their size, as "188 files · 11.3 MB" |
| Encrypted | **Yes** or **No** |
| Recovery Key | for an encrypted backend, **Confirmed** or **Not Confirmed** |
| Key Last Shown | for an encrypted backend, as in the list |
| Stores | the Stores that keep their files on it, or **None**. Original's are every Store that has no other backend, but the Templates Store |

Its actions are at the top:

- **Test** tests it in full, and says what it found under its status: see
  [Testing a Saved Backend](adding-a-backend/#testing-a-saved-backend).
- **Edit** changes its settings: see [Changing a Backend](adding-a-backend/#changing-a-backend).
- **Remove**, which Original's page doesn't have: see [Removing a Backend](removing-a-backend/).
- For an encrypted backend, **Show Recovery Key**, **Confirm Recovery Key** while its key isn't confirmed,
  **Enter Recovery Key** and **Key History**: see [Encryption and Recovery Keys](encryption-and-recovery-keys/).

**Backends**, in the crumb above the name, goes back to the list. An address that names no backend says "Backend Not
Found", with **Go to Backends**.

## When Storage Backends Are Off

On a server that keeps every file in LogicalDOC's own store, as one outside Healdan's Docker image does unless it's
set up for Healdan's storage, **Backends** says "Storage backends are off on this server: it keeps every file in
LogicalDOC's own store, so nothing here applies.", with no list, settings or buttons. A server without Healdan's
storage module says "The Backends screen can't be shown on this server, as it doesn't have Healdan's storage
module."

## What Healdan Records

Healdan records each change made on the Admin app's storage screens, with who made it, when and from where: a backend
added, changed or removed; a recovery key shown, confirmed or entered; a Store given another backend; stuck files put
back to waiting; and the settings saved. A change records what it changed, with the value before and after. An S3
access key or secret is recorded as changed, never its value, and a recovery key never. A change that changes
nothing, such as a save of what's already there, isn't recorded.

## The Storage Tab in the Classic Interface

The Classic Interface's **Storage** tab, under **Administration → Healdan → Settings**, does what the Admin app's
storage screens do, with the same rules, and stays for now. It shows the backends, with **Add Backend**, **Edit**,
**Test**, **Show Recovery Key**, **Enter Recovery Key** and **Remove** for the one selected (right-click for
**Key History**); the Stores, where each Store's **Backend** is chosen and its files waiting and stuck are shown; and
the settings. It doesn't update by itself: press **Refresh**.

![The Classic Interface's Storage tab: Original, a local folder at /opt/logicaldoc/repository/docs holding 18 files, and Cloud Storage, an encrypted S3 backend on the demo stack's stand-in S3 server holding 186 files, both OK, Cloud Storage's recovery key last shown to sokafor when it was added; the Stores list with Client Work on Cloud Storage and Firm and Templates on Original, nothing waiting or stuck; and the settings, a 30-day holding period, a 20 GB read cache and 4 parallel transfers](storage-tab.png)

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
