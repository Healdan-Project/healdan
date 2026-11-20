# A rename's version record after its write: design

- **Date:** 2026-11-20
- **Status:** proposed (Claude, 2026-11-20), as one small upstream edit and its tests. The owner decides on the pull
  request.

As in the roadmap, **(owner)** marks what the owner decided, and **(call)** marks Claude's proposal.

## 1. What broke

Two local runs of `docker/smoke-test.sh` on 2026-11-20 (one on the image of Healdan-Project/healdan#291, one on another
branch's) logged two `ERROR` lines in PostgreSQL's log during step 9, in WebKit's half of the User app's browser
tests, from one connection a few milliseconds apart:

```
ERROR:  duplicate key value violates unique constraint "ak_version" STATEMENT:  insert into ld_version (ld_color,…
ERROR:  current transaction is aborted, commands ignored until end of transaction block STATEMENT:  select ld_id from ld_folder where ld_deleted = 0 and ld_name like $1  and ld_tenantid = 1
```

CI's last twenty smoke runs (ten runs, both architectures) had none: it's a race. Since Healdan-Project/healdan#289,
`check_db_log` fails the run on such a line. Neither run kept the server's log, so the request can't be read back;
the cause below was found from the code and then reproduced by a test, whose PostgreSQL log has the same two lines,
to the statement.

## 2. The cause

**LogicalDOC saves a version record after its write, on a thread of its own.** `DocumentManager`'s check-in, move,
rename and create write the document with the next version number, then call `saveVersionAsync`, which waits a second
or more (a 100 ms delay, then a one-second sleep after it finds the document's row) and inserts the record.
`ak_version`, a unique index on `ld_version (ld_documentid, ld_version)`, refuses a second record with a number.

**Another thread's write can come first.** The Indexer reads a document, parses its file and then stores it, outside
`DocumentManager`'s monitor; in step 9 it runs every 5 seconds, and the specs rename and move documents they filed a
moment before. A write that lands between LogicalDOC's read and its store makes the store's merge fail
(`StaleObjectStateException`). The API asks LogicalDOC again (`DocumentChanges.write`, `PropertiesResource.change`,
`VersionResource.checkIn`), and drops the record of a write that was lost in its flush before it does.

**`rename` started its record before its write.** It's the one path that calls `saveVersionAsync` before
`documentDAO.store`; the check-in, the move and the create call it after, and the property change (`update`) saves its
record on the caller's thread after the store. So a rename whose store failed had already queued its record:

1. The rename reads the document at version N and gives it N+1, queues the record N+1, and its store fails.
2. `DocumentChanges.write` asks again at once. The second rename reads N, gives it N+1, is stored, and queues its own
   record N+1.
3. The first record is inserted. The second is refused on `ak_version`. `HibernatePersistentObjectDAO.flush` swallows
   the failure, `HibernateVersionDAO.store` goes on to `findWorkspace`, whose `findRoot` PostgreSQL refuses in the
   aborted transaction, and `saveVersionAsync` logs the `PersistenceException` at error level.

**What a person meets.**

- **In the User app and the API**, which ask again, the record kept is the first rename's: the same rename, person and
  number, made from the document as it was before the other write. The timeline looks right. What's left is two
  `ERROR` lines in PostgreSQL's log, which fail the smoke test, and an error in `dms.log`, which the Admin app's error
  capture counts as a fault.
- **On a route that doesn't ask again** (the Classic Interface's rename, WebDAV, the REST web service), the
  rename fails, the document stays at N, and the timeline shows a rename to N+1 that never happened. The document's
  next write, a save among them, takes N+1 too, and its own record is refused, so the timeline's N+1 is that rename,
  holding the old file, and the save's file has no record once a later version moves the document on. The API's save
  answers `201` meanwhile, as `confirmStored` finds a record at N+1.

## 3. The change

**`rename` queues its version record once its store has succeeded.** (call) One statement moves below
`documentDAO.store(document, transaction)`, as in `checkin` and `moveToFolder`. The record is still made from the
document before the store, as it was: only when it's saved changes. A rename whose store fails keeps no record; one
lost in its flush still keeps one, which `DocumentChanges.write` waits for through the returned future and drops
before it asks again, as before.

Nothing in Healdan's own code can stop a save LogicalDOC has queued, and every route reaches `rename`, so the edit
is in LogicalDOC's file. Healdan's API could instead wait for the stray record after a failed rename and drop it,
but it has no handle on that save, so it would have to poll for a record that may come late or never, and the
other routes would keep their phantom renames.

`docker/tests/db-log-expected.txt` is unchanged: the lines are a fault, now fixed, not something Healdan does in
the ordinary way.

## 4. Upstream edits

| File | Edit | Why |
|---|---|---|
| `logicaldoc-core/src/main/java/com/logicaldoc/core/document/DocumentManager.java` | In `rename`, the `DocumentFuture` that calls `saveVersionAsync` is made after `documentDAO.store`, with a `Healdan:` note | A rename whose write fails keeps no version record, so a rename asked again, or the document's next write, can take its number (§2) |

## 5. Tests

- **`DocumentManagerTest#testRenameWhoseWriteFailsKeepsNoVersion`** (in `logicaldoc-core`, on HSQLDB, added to
  `UPSTREAM_TESTS`): a document listener refuses the rename's store; once LogicalDOC's version saves are done, the
  document keeps its name and its version records are as they were. Before the change a `1.1` record appeared (and
  LogicalDOC's version quota moved two older records aside to make room for it).
- **`DocumentChangesTest#testRenamePastABackgroundWrite`** (in `healdan-api`, on PostgreSQL): `BackgroundWrite`
  stores the document from another thread, with a change of its colour, after the rename read it, so the rename's
  store fails and the API asks again. The rename is `200`, and the one `1.1` record is the rename as stored, with the
  colour. Before the change the record was the first attempt's, without it, and the test server's log had §1's two
  lines.
- **`#testRenamePastABackgroundWriteAtTheFlush`, `#testMovePastABackgroundWrite` and
  `#testMovePastABackgroundWriteAtTheFlush`** do the same for a rename lost in its flush and for a move, both ways.
  They passed before the change; they pin the paths the API already handled, which no test covered.
- **`BackgroundWrite.overtaking`** can now name a write outside the four it overtakes by default (a check-out, a
  check-in, a release and a property change), such as a rename or a move. The tests that name one of those four
  behave as before.

## 6. Later

- **Report it upstream.** (call) To LogicalDOC: `rename` saves a version record for a rename whose write failed.
- **`HibernatePersistentObjectDAO.flush` swallows every failure.** It's why the refused insert went on to a second
  statement, and why a write lost in its flush rolls back unseen, which the API works around in three places. Not
  changed here: callers count on it, and changing it is a larger upstream edit.
