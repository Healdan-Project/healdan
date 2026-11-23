---
title: Search and Indexing
weight: 3
---

Full-text search reads from an index of the words in every document. **Search Index**, under **Search** in
[the Admin app](../../the-admin-app/)'s left pane, at `/admin/search-index`, shows how the index is doing: how many
documents are indexed, how many wait and how many couldn't be indexed, with those that couldn't and why, and the
reindexes, which it starts, follows and cancels. The index's settings stay in the Classic Interface, under
**Administration → Settings → Search and Indexing** (see [In the Classic Interface](#in-the-classic-interface)).

## How a Document Gets Indexed

The [Indexer](../scheduled-tasks/) reads the text of new and changed documents into the index every 30 minutes to
begin with, so a document's words can be searched for after its first run since the document was filed.
[Finding New Documents Sooner](../scheduled-tasks/#finding-new-documents-sooner) says how to run it more often.

| Files | How their text is read |
|---|---|
| Word, Excel and PowerPoint (`.docx`, `.docm`, `.dotx`, `.xlsx`, `.xlsm`, `.pptx`, `.ppsx`) | by Healdan's own readers: a document's text, a workbook's cells, a presentation's slides |
| Emails (`.eml`, `.msg`) | by Healdan's own reader: From, To, CC, Subject, the date, the message's text and the attachments' names. What's in an attachment isn't indexed |
| PDFs, OpenDocument files, older Office formats, text, HTML and others | by LogicalDOC's readers |
| Other Office files: `.dotm`, `.xltx`, `.xltm`, `.pptm`, `.potx` and `.odg` drawings | converted to PDF with LibreOffice, then read |

A file whose text can't be read, such as a password-protected one, is indexed without it, so it's found only by its
properties.

The documents the Search Index counts are the live ones: not shortcuts, and not in the Trash. Each is in one of three
states, or in none of them while it's set to Skip:

- **Indexed:** its entry has been written to the index, and it hasn't been marked to be indexed again since.
- **Waiting:** it's marked to be indexed, its file or only its properties, for the Indexer to take at one of its runs.
  An archived document isn't counted, as the Indexer leaves it alone.
- **Couldn't Be Indexed:** it's marked to have its file indexed, isn't archived, and the last time it was tried, it
  couldn't be. The Indexer goes on trying it at each run until it's indexed or set to Skip.

## The Search Index

At the top of the page are **Run Indexer Now** and **Reindex…**, then the three counts, **Indexed**, **Waiting** and
**Couldn't Be Indexed**, the last in the caution colour while it's above 0, and:

- **under Indexed,** how many entries the index holds, for documents and shortcuts alike, so it can differ from the
  count, and the size of the index's folder on the server's disk, each where it can be read;
- **the index's health,** as the Dashboard's Search Index tile gives it: **Index Healthy** while every document is
  indexed or waits to be, and **Needs Attention**, with how many wait and how many failed, while one or more couldn't
  be indexed, as in "5 waiting, 2 failed";
- **the Indexer's runs:** when it last ran and when it runs next, or that it's running and since when.

The tabs carry their counts, as in "Couldn't Be Indexed (2)", and the tab that's open is in the page's address. The
server keeps one count of the three for up to 10 seconds, shared by the page and its lists, and counts again at once
after **Try Again**, **Skip**, a reindex's end or a waiting one cancelled.

**Run Indexer Now** runs the Indexer at once, as **Run Now** on [Background Jobs](../../monitoring/background-jobs/)
does, and says "Asked the Indexer to run now". The run takes the next documents waiting, as many as one run takes,
those that couldn't be indexed among them. It's greyed out, with the reason under it, while the Indexer is running
already or is disabled, and while the server is set not to run scheduled tasks.

### Reindexes Running

A reindex that's running, or waiting its turn, shows above the tabs, in the order they were started.

- **Running,** as "Reindexing Matter 1001.1 Charter Dispute": who started it and when, a bar of how far it has got,
  and under it a line such as "212 of 412 documents · about 6 minutes left · search keeps working while it runs",
  with how many it skipped because their storage couldn't be reached, as "9 on storage that couldn't be reached",
  when there are any. While the index can't be written, the line says it's waiting for the index, since when, and
  that it tries again shortly (see [While It Runs](#while-it-runs)). While Healdan itself can't go on with it, the
  line says it's making no progress, since when, that it tries again every 30 seconds, and that
  [Background Jobs](../../monitoring/background-jobs/) says why. One that has been asked to stop says it's stopping
  once the documents it has started on are done, and has no **Cancel**.
- **Waiting,** as "Waiting to Reindex Store Client Work": who started it and when, and how many documents it will try
  once its turn comes.

Each has **Cancel** (see [Cancel](#cancel)). A reindex that ends while the page is open stays where it was, as
"Reindexed …" or "Cancelled Reindexing …", saying how it went, as in "408 reindexed, 3 couldn't be indexed, 12
skipped (9 on storage that couldn't be reached).", with **Close**.

While a reindex waits or runs, the page asks again every 5 seconds for its progress, and every 30 seconds for the
counts and the open tab, while the page is showing. While the Indexer runs, it asks for the heading every 5 seconds,
and once 2 seconds after **Run Indexer Now**, so the button is ready again once the run ends, when the open tab and
the left pane's count are asked for again. It asks again each time you come back to its window too. When a reindex
ends, the counts, the tabs and the left pane's count are asked for again at once. **History**'s events are asked for
again on the timer only until **Show More** has been pressed.

### Couldn't Be Indexed

Each document that couldn't be indexed, the latest error first: its **Doc #**, its file name under **Title**, its
matter, the **Reason** and **When** it was last tried.

- **Try Again** indexes the document now, while you wait, as the Indexer would, so a large or slow file takes a while.
  The row and a message say how it went. Indexed, it leaves the list. If it couldn't be indexed again, it stays, with
  the new reason, and the Indexer goes on trying it, unless **On error, mark unindexable** is on (see
  [The Settings](#the-settings)), which sets it to Skip, so it leaves the list. When the
  [storage backend](../../storage-backends/) that keeps its file can't be reached just then, Healdan says so, naming the
  Store, and changes nothing, and so it does while the index itself can't be written: "The search index can't be
  written just now. Try again later." Either way the document stays as it was, with no row on the **History** tab,
  and nothing is recorded. For the storage, LogicalDOC's reader still logs that it couldn't read the file, which
  [Errors](../../monitoring/errors/) shows. Try again once the backend, or the index, is back.
- **Skip** asks first, then sets the document not to be indexed, so the Indexer stops trying it, and it leaves the
  list. It's then counted in none of the three. Its entry in the index, if it has one, stays, and every reindex leaves
  it out from then on. Skip lasts until someone chooses **Mark indexable** for it on the Classic Interface's
  **Indexing History** tab (see [Indexing Again](#indexing-again)).

If the document has been indexed or set to Skip since the list was read, Healdan says "This document is no longer
waiting after an error." in its row, and the row goes at the list's next read.

A document with no doc #, filed before Healdan gave documents their numbers, is listed as "No doc #", with **Try Again**
and **Skip** greyed out: reindex its folder to try it again.

### Waiting

Each document waiting for the Indexer, those that couldn't be indexed left out, in the order they were filed in, with
its **Doc #**, **Title**, matter and **Last Changed**: when the document last changed, which is when most began to
wait.

### History

Two lists:

- **Reindexes,** the newest first, those waiting and running and those that ended: what each covered, who started it
  and when, how many documents it set out to try, and its outcome.
- **Index Events:** each time a document was indexed or couldn't be, newest first, with its doc #, file name and
  matter, **Indexed** or **Couldn't Be Indexed**, the reason when it couldn't, and who did it, the Indexer's runs
  included, and when. They read back as the [Audit Trail](../../monitoring/audit-trail/#reading-further-back) does, a
  stretch at a time, with **Show More**, so a page can hold few rows, or none, while there's more to read. A document
  destroyed for good takes its rows with it.

In a window too narrow for them, **Waiting**'s list and **History**'s two scroll sideways, and each is then a stop of
its own, which Tab reaches and the arrow keys scroll.

## Reindex

**Reindex…**, at the top of the Search Index, indexes again the documents you choose, straight through, on the server.
Its dialog opens on **Matter**. Choose what to reindex:

| Choice | What it covers |
|---|---|
| **Documents** | the documents whose doc #s you type or paste, with commas, spaces or new lines between them, up to 1,000; a doc # with its version, as `10388.2`, is read as the document's. Each is listed under **Doc #s Given**, the first 1,000 and then how many more, found, with its file name and matter; "Left out: it's set to Skip, archived or being changed"; or "No such document" |
| **Folder** | find the matter, then choose one of its folders, listed as they nest and filtered by what you type: the documents in that folder and in every folder inside it, at every level. **Choose Another Matter** goes back to the search. The matter's own folder isn't listed: choose **Matter** for that |
| **Matter** | a search that starts from your Recent Matters and finds a matter by its number, its name or its client's name: the documents of the matter's whole workspace |
| **Store** | a plain list of the Stores, each with its documents: the documents of every matter in the Store |
| **Everything** | every document |

A folder has no entry of its own in the index, so reindexing one means reindexing the documents in it. The dialog
shows how many documents the reindex will try, on its button, as in **Reindex 412 Documents** (**Reindex Everything**
for Everything), and about how long it will take once it runs, as in "About 12 minutes for 412 documents.": as long a
time each as the documents took in the latest reindex that ran to its end having tried 20 or more, or 1.75 seconds
each until one has, not counting any reindex that runs before it.

**What's left out** isn't on the button, and the dialog says how many, as in "3 more are left out: shortcuts, and
documents set to Skip, archived or being changed.". Shortcuts are left out as the Indexer indexes each again once the
document it leads to is done, and archived documents as the Indexer leaves them alone too.

Healdan says before the button when it won't start one, and greys the button out: "There's nothing here to reindex."
for a choice with no documents to try, and "This is already being reindexed." for a folder, matter, Store or
Everything that a waiting or running reindex covers already. Documents chosen by doc # are never refused as already
being reindexed. More than 1,000 different doc #s at once are refused. Enter in the matter search or the folder filter
picks the row the arrow keys are on, and never starts the reindex.

### While It Runs

- **Search keeps working.** Nothing is taken out of the index: each document's entry is replaced as it's done, so
  search finds it throughout. A document filed meanwhile is the Indexer's, as usual.
- **One reindex runs at a time,** across the server. One started while another waits or runs waits its turn, oldest
  first, and shows above the tabs as waiting.
- **It always reaches its end.** The documents it tries are fixed as it's started, and each is tried once, in the order
  they were filed in. A document deleted, in the Trash or destroyed by its turn, or set to Skip, archived or being
  changed at that moment, is skipped. A document moved or given a new version since is indexed as it is then.
- **A document whose file's storage can't be reached** at its turn is skipped too, keeping its entry and staying as it
  was, with no row on the **History** tab: a reindex once the [storage backend](../../storage-backends/) is back covers
  it. LogicalDOC's reader still logs an error for each such document, so [Errors](../../monitoring/errors/) shows a
  fault seen as many times as there were, which can be marked resolved once the backend is back.
- **A document it couldn't index** joins **Couldn't Be Indexed**, keeping the entry it had, and the Indexer goes on
  trying it at each run, unless **On error, mark unindexable** is on, which sets it to Skip, as **Try Again** does. A
  document whose save met another's at that moment, as when the Indexer took it too, is tried once more at the end of
  its stretch before it counts as one it couldn't index.
- **When the index can't be written,** so that nothing in a stretch of documents could be indexed and the index
  doesn't answer a check, the reindex waits and tries that stretch again, counting nothing meanwhile, not even a
  document that failed for its own reason beside them, and its card says it's waiting for the index. While the index
  answers, a document it refuses is that document's failure, so one such document never holds a reindex up.
- **When Healdan itself fails** part way, the reindex stays running, its card saying it's making no progress, and
  Healdan tries again every 30 seconds.
- **A restart** of the server lets the running reindex go on from where it was, and the waiting ones wait their turn
  as before.

A reindex that has ended is on the **History** tab, saying how many documents it indexed, how many it couldn't, which
are on **Couldn't Be Indexed**, and how many it skipped. Together they're the documents it set out to try.

### Cancel

**Cancel** asks first, as "Cancel the Reindex of Matter 1001.1 Charter Dispute?", and **Cancel Reindex** stops it. One
waiting its turn is cancelled at once, so nothing in the index changes. One that's running finishes the documents it's
working on, then stops. Documents it hadn't reached keep the entries they have, and stay indexed, waiting or failed as
they were. A reindex that has ended can't be cancelled: "This reindex has ended."

### Empty the Index First

**Empty the Index First**, a tick box shown only under **Everything**, beneath its warning and unticked, is for a
damaged index; ticked, the button reads **Empty the Index and Reindex Everything**. As the reindex's run begins, not
as it's started, so no other reindex is running then, it marks every document to be indexed, then empties the index
and checks that it's empty. An index that couldn't be emptied is waited for, as one that can't be written is, and
emptied again; a reindex cancelled meanwhile leaves the documents marked, with their entries, for the Indexer. Once
it's empty, search misses documents until each is done again, and the Indexer takes any the reindex doesn't cover,
such as those filed meanwhile. A document set to Skip loses its entry, and stays set to Skip. Without the tick,
nothing is taken out of the index.

### Reindex from Elsewhere

- **A matter's page,** in the Admin app, has **Reindex**, which opens the Search Index with Reindex open and the matter
  chosen (see [A Matter's Page](../../clients-and-matters/#reindex)).
- **A folder in the Workspace Editor,** on a matter's **Workspace** tab, has **Reindex** too, after **Delete** and on
  its menu in the tree, which opens it with the folder chosen (see
  [The Folder Chosen](../../top-level-folders/#the-folder-chosen)). The matter's own folder at the top has none, as the
  matter's page covers it, and nor have search folders or a template's folders.
- **In the User app,** an administrator's right-click menu of a document, of several selected documents, of a folder
  and of a matter has **Reindex**. It asks first, naming what will be reindexed and how many documents, and its message
  has **View Progress**, which opens the Search Index in a new tab, so you stay where you were. See
  [Right-Click Menus](../../../user/getting-started/getting-around/#right-click-menus) in the User Guide.

The Reindex dialog is in the page's address, so a link can open it with its choice made. Opened with **Reindex…**,
Back closes it. Opened from a matter's page or the Workspace Editor, Back goes back to that page. **Cancel**, Esc and
starting a reindex close it and leave you on the Search Index.

## What Healdan Records

Healdan records, in the [Audit Trail](../../monitoring/audit-trail/), with who did it, when and from where:

- **a reindex started,** as "Started a reindex", named by what it covers, with its kind, how many documents it will
  try and whether it empties the index first;
- **a reindex cancelled,** as "Cancelled a reindex";
- **Try Again,** as "Tried to index a document again", named by the document's doc # and file name, as in
  `# 10412 Witness Statement.pdf`, with whether it was indexed or failed;
- **Skip,** as "Set a document not to be indexed", named the same way.

**Run Indexer Now** is recorded as Background Jobs' **Run Now** is: "Asked a job to run". A reindex of a matter, or of
a folder in it, is on the matter's **Activity** tab too. A change that changes nothing isn't recorded, and nor is
anything that only reads, such as the count the Reindex dialog shows. **Try Again** that finds the file's storage
can't be reached records nothing, and adds no row to the **History** tab.

## In the Classic Interface

The Classic Interface's **Administration → Settings → Search and Indexing** keeps the index's settings, and its own
buttons and tabs still work beside the Admin app's Search Index.

![The Search and Indexing tab: the entries count and status, the index's folder, the include and exclude patterns, the sorting, threads, batch and timeouts, and the Save, Unlock, Purge, Reschedule all for indexing, Drop index and Check buttons](search-and-indexing.png)

### The Settings

| Setting | What it does |
|---|---|
| Include patterns, Exclude patterns | which files have their text indexed, by name, such as `*.iso`. `*.exe`, `*.bin` and `*.iso` are left out to begin with |
| Include patterns(metadata only), Exclude patterns(metadata only) | which files are indexed by their properties only, without their text |
| On error, mark unindexable | gives up on a file whose text can't be read, instead of trying again at each run |
| Ignore content error | indexes a file without its text when the text can't be read, instead of trying again at each run |
| Sorting | which documents the Indexer takes first, such as the most recent. **none** to begin with |
| Threads | how many documents are indexed at once. 2 to begin with |
| Batch | how many documents one run of the Indexer takes. 500 to begin with |
| Parsing Timeout | how long reading one file's text may take, in seconds. 120 to begin with |
| Max Text | the most characters of a document's text that are kept in the index. 0 means all |

Click **Save** after changing them.

### Indexing Again

The Admin app's [Reindex](#reindex) runs straight through, so a large reindex doesn't wait for the Indexer's runs, 500
documents every 30 minutes to begin with. These buttons mark documents for the Indexer instead:

- **Reschedule all for indexing** marks every indexed document to be indexed again, which the Indexer's next runs
  do. Use it after changing the patterns or the [languages](#the-other-tabs). It leaves documents marked
  unindexable as they are: select them on the **Indexing History** tab and choose **Mark indexable** from the
  right-click menu.
- **Drop index** empties the index and marks the same documents again. Until the Indexer has worked through them, a
  search finds less than it should.
- **Purge** removes deleted documents from the index, those in the Trash included.
- **Check** checks the index for damage, and **Unlock** frees an index left locked after a crash.

**Entries count** shows how many documents are in the index; click its icon to count them. The Search Index shows how
many entries the index holds too.

### The Other Tabs

| Tab | What it holds |
|---|---|
| Filters | the steps each word goes through before it's indexed |
| Languages | the languages whose word rules the index uses, so that "leases" finds "lease" |
| Parsers | which reader each kind of file goes to, Healdan's among them. A kind with no reader of its own can be sent to another kind's in **Aliases**: `tex` in `txt`'s Aliases would read `.tex` files as plain text |
| Indexing queue | the documents waiting to be indexed, which the Search Index's **Waiting** tab lists too |
| Entries | the index's entries, one per document |
| Indexing History | when each document was indexed, or failed to be, with the start of its text. The Search Index's **History** tab lists the same events, without the text |
| Search History | who searched for what, and when |
