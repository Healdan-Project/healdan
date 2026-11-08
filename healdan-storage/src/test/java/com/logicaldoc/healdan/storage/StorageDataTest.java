// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.util.spring.Context;

public class StorageDataTest extends AbstractStorageTestCase {

	private static final long DAY = 24L * 3600 * 1000;

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(Context.get(DataSource.class));
	}

	private StoredFiles files() {
		return StoredFiles.get();
	}

	private long backend(String name) throws PersistenceException {
		StorageBackend backend = new StorageBackend();
		backend.setTenantId(1);
		backend.setName(name);
		backend.setKind(StorageBackend.LOCAL);
		backend.setPath("/tmp/" + name);
		StorageBackendDAO.get().store(backend);
		return backend.getId();
	}

	private void useBackend(long storeId, long backendId) throws PersistenceException {
		StoreBackend row = new StoreBackend();
		row.setTenantId(1);
		row.setStoreId(storeId);
		row.setBackendId(backendId);
		StoreBackendDAO.get().store(row);
	}

	private Date waiting(long docId, String resource) {
		return files().find(docId, resource).orElseThrow().waiting();
	}

	private void setWaiting(long docId, String resource, Date when) {
		jdbc().update("update hd_storage_file set hd_waiting = ? where hd_docid = ? and hd_resource = ?",
				new Timestamp(when.getTime()), docId, resource);
	}

	@Test
	public void testMigrationMadeTheTables() throws Exception {
		for (String table : List.of("HD_STORAGE_BACKEND", "HD_STORAGE_STORE", "HD_STORAGE_FILE", "HD_STORAGE_HELD",
				"HD_STORAGE_EVENT"))
			assertEquals(table, Integer.valueOf(1), jdbc().queryForObject(
					"select count(*) from INFORMATION_SCHEMA.TABLES where TABLE_NAME = ?", Integer.class, table));
		assertEquals(Integer.valueOf(1), jdbc().queryForObject(
				"select count(*) from \"hd_schema_history\" where \"version\" = '9' and \"success\" = true",
				Integer.class));

		StorageBackend original = StorageBackendDAO.get().findById(StorageBackend.ORIGINAL);
		assertNotNull(original);
		assertEquals("Original", original.getName());
		assertEquals(StorageBackend.LOCAL, original.getKind());
		assertFalse(original.isEncryptedOn());
		assertNull(original.getPath());
	}

	@Test
	public void testOneRowPerFile() throws Exception {
		long first = files().recordWrite(100, "1.0", 1, null, 10);
		assertThrows(DuplicateKeyException.class,
				() -> jdbc().update("insert into hd_storage_file (hd_docid, hd_resource, hd_backendid, hd_size, "
						+ "hd_write, hd_stored, hd_attempts, hd_stuck) values (100, '1.0', 1, 1, 1, current_timestamp, 0, 0)"));

		long second = files().recordWrite(100, "1.0", 1, null, 20);
		assertNotEquals(first, second);
		assertEquals(Integer.valueOf(1), jdbc().queryForObject(
				"select count(*) from hd_storage_file where hd_docid = 100 and hd_resource = '1.0'", Integer.class));
		StoredFile row = files().find(100, "1.0").orElseThrow();
		assertEquals(second, row.write());
		assertEquals(20, row.size());
		assertEquals(1, files().list(100).size());
	}

	@Test
	public void testMovedOnlyIfUnchanged() throws Exception {
		long w1 = files().recordWrite(101, "1.0", 0, null, 10);
		long w2 = files().recordWrite(101, "1.0", 0, null, 11);
		assertFalse(files().moved(101, "1.0", w1, 1));
		assertEquals(0, files().find(101, "1.0").orElseThrow().backendId());

		files().delete(101, "1.0");
		long w3 = files().recordWrite(101, "1.0", 0, null, 12);
		assertNotEquals(w1, w3);
		assertNotEquals(w2, w3);
		assertFalse(files().moved(101, "1.0", w1, 1));
		assertEquals(0, files().find(101, "1.0").orElseThrow().backendId());

		assertTrue(files().moved(101, "1.0", w3, 1));
		assertEquals(1, files().find(101, "1.0").orElseThrow().backendId());
	}

	@Test
	public void testWaiting() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		Date earlier = new Date(System.currentTimeMillis() - 2 * DAY);

		// A staged write waits, and a second keeps the earlier value
		files().recordWrite(1, "1.0", 0, null, 5);
		assertNotNull(waiting(1, "1.0"));
		setWaiting(1, "1.0", earlier);
		files().recordWrite(1, "1.0", 0, null, 6);
		assertEquals(earlier.getTime(), waiting(1, "1.0").getTime());

		// A write that lands on a backend other than its Store's
		files().recordWrite(2, "1.0", 1, 10L, 5);
		assertNotNull(waiting(2, "1.0"));
		setWaiting(2, "1.0", earlier);
		files().recordWrite(2, "1.0", 1, 10L, 6);
		assertEquals(earlier.getTime(), waiting(2, "1.0").getTime());

		// A save that lands on its Store's backend, as during a move home, clears it
		files().recordWrite(2, "1.0", other, 10L, 7);
		assertNull(waiting(2, "1.0"));

		// A write in place doesn't wait; a setStore that leaves it misplaced does, and keeps an earlier value
		files().recordWrite(3, "1.0", 1, null, 5);
		assertNull(waiting(3, "1.0"));
		files().setStore(List.of(3L), 10L);
		assertEquals(Long.valueOf(10), files().find(3, "1.0").orElseThrow().storeId());
		assertNotNull(waiting(3, "1.0"));
		setWaiting(3, "1.0", earlier);
		files().setStore(List.of(3L), 10L);
		assertEquals(earlier.getTime(), waiting(3, "1.0").getTime());
		// Back to a Store that uses Original, the row is in place again
		files().setStore(List.of(3L), null);
		assertNull(files().find(3, "1.0").orElseThrow().storeId());
		assertNull(waiting(3, "1.0"));

		// moved clears it
		long write = files().recordWrite(4, "1.0", 0, null, 5);
		assertNotNull(waiting(4, "1.0"));
		assertTrue(files().moved(4, "1.0", write, 1));
		assertNull(waiting(4, "1.0"));
	}

	@Test
	public void testOneBackendPerStore() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		assertEquals(other, StoreBackendDAO.get().findByStore(10).orElseThrow().getBackendId());
		assertTrue(StoreBackendDAO.get().findByStore(11).isEmpty());
		assertThrows(ConstraintViolationException.class, () -> useBackend(10, 1));
	}

	@Test
	public void testRemoveForGood() throws Exception {
		long id = backend("gone");
		StorageBackend backend = StorageBackendDAO.get().findById(id);
		backend.setKind(StorageBackend.S3);
		backend.setSecret("v1:sealed");
		StorageBackendDAO.get().store(backend);
		assertEquals(Integer.valueOf(1), jdbc().queryForObject(
				"select count(*) from hd_storage_backend where hd_secret is not null", Integer.class));

		StorageBackendDAO.get().remove(id);

		assertEquals(Integer.valueOf(0),
				jdbc().queryForObject("select count(*) from hd_storage_backend where ld_id = ?", Integer.class, id));
		assertEquals(Integer.valueOf(0), jdbc().queryForObject(
				"select count(*) from hd_storage_backend where hd_secret is not null", Integer.class));
		assertEquals(Integer.valueOf(1),
				jdbc().queryForObject("select count(*) from hd_storage_backend", Integer.class));
	}

	@Test
	public void testMisplacedOrder() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		files().recordWrite(5, "1.0", 0, 10L, 1);
		files().recordWrite(6, "1.0", 1, 10L, 1);
		files().recordWrite(9, "1.0", 1, 10L, 1);
		files().recordWrite(7, "1.0", other, 10L, 1);
		files().recordWrite(8, "1.0", 1, null, 1);

		List<Long> docs = files().misplaced(10, Set.of()).stream().map(StoredFile::docId).toList();

		// Staged first, then the newest document; docs 7 and 8 are in place
		assertEquals(List.of(5L, 9L, 6L), docs);
	}

	@Test
	public void testMisplacedLeavesOut() throws Exception {
		long two = backend("two");
		long three = backend("three");
		useBackend(10, two);
		useBackend(11, three);
		files().recordWrite(20, "1.0", 1, 11L, 1);
		files().recordWrite(21, "1.0", 1, 10L, 1);
		files().recordWrite(22, "1.0", three, null, 1);
		files().recordWrite(23, "1.0", 1, 11L, 1);
		files().recordWrite(24, "1.0", 1, 11L, 1);
		files().recordWrite(25, "1.0", 1, 11L, 1);
		files().recordWrite(26, "1.0", 1, 11L, 1);
		jdbc().update("update hd_storage_file set hd_stuck = 1 where hd_docid = 23");
		jdbc().update("update hd_storage_file set hd_nexttry = ? where hd_docid = 24",
				new Timestamp(System.currentTimeMillis() + DAY));
		jdbc().update("update hd_storage_file set hd_nexttry = ? where hd_docid = 25",
				new Timestamp(System.currentTimeMillis() - DAY));

		// Stuck and not yet due are out; no next try and a due one are listed; an empty set runs
		assertEquals(List.of(26L, 25L, 22L, 21L, 20L), docs(10, Set.of()));
		// A source in the set, or a target in it
		assertEquals(List.of(26L, 25L, 22L, 20L), docs(10, Set.of(two)));
		assertEquals(List.of(21L), docs(10, Set.of(three)));

		// One unavailable backend's rows first in order can't fill the page
		files().recordWrite(30, "1.0", 1, 10L, 1);
		assertEquals(List.of(26L, 25L), docs(2, Set.of(two)));
	}

	@Test
	public void testMisplacedListsOnlyRowsThatWait() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		files().recordWrite(40, "1.0", 0, 10L, 1);
		files().recordWrite(41, "1.0", 1, 10L, 1);
		files().recordWrite(42, "1.0", 1, 10L, 1);
		// Misplaced, but without the mark, as only a write that skipped StoredFiles leaves a row
		jdbc().update("update hd_storage_file set hd_waiting = null where hd_docid = 41");

		assertEquals(List.of(40L, 42L), docs(10, Set.of()));
	}

	@Test
	public void testMarkWaitingMendsOnlyItsStore() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		useBackend(11, other);
		Date earlier = new Date(System.currentTimeMillis() - 2 * DAY);
		files().recordWrite(50, "1.0", 1, 10L, 1);
		files().recordWrite(51, "1.0", 1, 11L, 1);
		files().recordWrite(52, "1.0", other, 10L, 1);
		files().recordWrite(53, "1.0", 1, 10L, 1);
		files().recordWrite(54, "1.0", 1, null, 1);
		setWaiting(53, "1.0", earlier);
		jdbc().update("update hd_storage_file set hd_waiting = null where hd_docid in (50, 51)");
		assertEquals(List.of(53L), docs(10, Set.of()));

		assertEquals(1, files().markWaiting(10L));

		assertNotNull("Store 10's misplaced row waits", waiting(50, "1.0"));
		assertNull("Store 11's is left to a check of all", waiting(51, "1.0"));
		assertNull("one in place doesn't wait", waiting(52, "1.0"));
		assertEquals("one waiting keeps its time", earlier.getTime(), waiting(53, "1.0").getTime());
		assertNull("no Store, on Original: in place", waiting(54, "1.0"));
		assertEquals(List.of(53L, 50L), docs(10, Set.of()));

		assertEquals(1, files().markWaiting(null));
		assertNotNull(waiting(51, "1.0"));
		assertEquals(0, files().markWaiting(null));
	}

	@Test
	public void testStoreBackendChangedKeepsTheMarks() throws Exception {
		long one = backend("one");
		long two = backend("two");
		useBackend(10, one);
		useBackend(11, one);
		Date earlier = new Date(System.currentTimeMillis() - 2 * DAY);
		files().recordWrite(60, "1.0", one, 10L, 1);
		files().recordWrite(61, "1.0", two, 10L, 1);
		files().recordWrite(62, "1.0", 0, 10L, 1);
		files().recordWrite(63, "1.0", one, 11L, 1);
		files().recordWrite(64, "1.0", two, 11L, 1);
		setWaiting(61, "1.0", earlier);
		setWaiting(62, "1.0", earlier);
		setWaiting(64, "1.0", earlier);
		assertNull(waiting(60, "1.0"));
		assertNull(waiting(63, "1.0"));

		// Store 10 switches to backend two
		StoreBackend row = StoreBackendDAO.get().findByStore(10).orElseThrow();
		row.setBackendId(two);
		StoreBackendDAO.get().store(row);
		files().storeBackendChanged(10);

		assertNotNull("left on the old backend, it waits now", waiting(60, "1.0"));
		assertNull("already on the new one, it waits no more", waiting(61, "1.0"));
		assertEquals("one that waited keeps its time", earlier.getTime(), waiting(62, "1.0").getTime());
		assertNull("another Store's rows are left alone", waiting(63, "1.0"));
		assertEquals(earlier.getTime(), waiting(64, "1.0").getTime());
		assertEquals(List.of(62L, 64L, 60L), docs(10, Set.of()));
	}

	@Test
	public void testFailureRecord() throws Exception {
		long write = files().recordWrite(40, "1.0", 0, null, 5);
		Date next = new Date(System.currentTimeMillis() + 60_000);

		// A failure of an old write counts against nothing
		assertEquals(0, files().recordFailure(40, "1.0", write + 1, "old", n -> next));
		assertEquals(0, files().find(40, "1.0").orElseThrow().attempts());
		assertEquals(0, files().recordFailure(41, "1.0", write, "none", n -> next));

		for (int attempt = 1; attempt < StoredFiles.STUCK_AT; attempt++) {
			int counted = attempt;
			// The next try is worked out from the count it writes
			assertEquals(attempt, files().recordFailure(40, "1.0", write, "boom " + attempt, n -> {
				assertEquals(counted, n);
				return next;
			}));
			StoredFile row = files().find(40, "1.0").orElseThrow();
			assertEquals(attempt, row.attempts());
			assertEquals("boom " + attempt, row.lastError());
			assertFalse(row.stuck());
			assertEquals(next.getTime() / 1000, row.nextTry().getTime() / 1000);
		}
		assertEquals(StoredFiles.STUCK_AT, files().recordFailure(40, "1.0", write, "x".repeat(5000), n -> null));
		StoredFile stuck = files().find(40, "1.0").orElseThrow();
		assertEquals(StoredFiles.STUCK_AT, stuck.attempts());
		assertTrue(stuck.stuck());
		assertNull(stuck.nextTry());
		assertEquals(4000, stuck.lastError().length());

		// A new write supersedes it all
		files().recordWrite(40, "1.0", 0, null, 5);
		StoredFile fresh = files().find(40, "1.0").orElseThrow();
		assertFalse(fresh.stuck());
		assertEquals(0, fresh.attempts());
		assertNull(fresh.lastError());
		assertNull(fresh.nextTry());
	}

	@Test
	public void testRetryPutsOneStoresStuckFilesBack() throws Exception {
		Date next = new Date(System.currentTimeMillis() + 60_000);
		long[] writes = new long[4];
		Long[] stores = { 10L, 10L, 11L, null };
		for (int i = 0; i < 4; i++) {
			writes[i] = files().recordWrite(50 + i, "1.0", 0, stores[i], 5);
			for (int n = 0; n < StoredFiles.STUCK_AT; n++)
				files().recordFailure(50 + i, "1.0", writes[i], "boom", f -> next);
		}
		// Doc 51 is not stuck, only failing
		files().recordWrite(51, "1.0", 0, 10L, 5);
		files().recordFailure(51, "1.0", files().find(51, "1.0").orElseThrow().write(), "boom", f -> next);

		assertEquals(1, files().retry(10L));
		StoredFile back = files().find(50, "1.0").orElseThrow();
		assertFalse(back.stuck());
		assertEquals(0, back.attempts());
		assertNull(back.lastError());
		assertNull(back.nextTry());
		assertEquals(1, files().find(51, "1.0").orElseThrow().attempts());
		assertTrue(files().find(52, "1.0").orElseThrow().stuck());
		assertTrue(files().find(53, "1.0").orElseThrow().stuck());

		assertEquals(1, files().retry(null));
		assertFalse(files().find(53, "1.0").orElseThrow().stuck());
		assertTrue(files().find(52, "1.0").orElseThrow().stuck());
	}

	@Test
	public void testStatsByStore() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		files().recordWrite(60, "1.0", 0, 10L, 100);
		files().recordWrite(60, "1.1", other, 10L, 50);
		long w = files().recordWrite(61, "1.0", 0, 10L, 7);
		files().recordWrite(62, "1.0", 1, null, 3);
		files().recordWrite(63, "1.0", 1, null, 4);
		for (int n = 0; n < StoredFiles.STUCK_AT; n++)
			files().recordFailure(61, "1.0", w, "boom", f -> new Date());

		List<StoreStats> stats = files().statsByStore();

		assertEquals(List.of(new StoreStats(null, 2, 0, 0, 7), new StoreStats(10L, 3, 2, 1, 157)), stats);
	}

	@Test
	public void testDeleteIsForGood() throws Exception {
		long other = backend("other");
		useBackend(10, other);
		long rowId = StoreBackendDAO.get().findByStore(10).orElseThrow().getId();
		StoreBackendDAO.get().delete(rowId);
		assertEquals(Integer.valueOf(0), jdbc().queryForObject("select count(*) from hd_storage_store", Integer.class));
		// The Store can have another
		useBackend(10, 1);
		assertEquals(1L, StoreBackendDAO.get().findByStore(10).orElseThrow().getBackendId());

		StorageBackend backend = StorageBackendDAO.get().findById(other);
		backend.setSecret("v1:sealed");
		StorageBackendDAO.get().store(backend);
		StorageBackendDAO.get().delete(other);
		assertEquals(Integer.valueOf(0), jdbc().queryForObject(
				"select count(*) from hd_storage_backend where hd_secret is not null", Integer.class));
		assertEquals(Integer.valueOf(0), jdbc().queryForObject(
				"select count(*) from hd_storage_backend where ld_id = ?", Integer.class, other));
	}

	@Test
	public void testDeleteAllIsForGood() throws Exception {
		long other = backend("other");
		long third = backend("third");
		useBackend(10, other);
		useBackend(11, third);
		StorageBackend backend = StorageBackendDAO.get().findById(other);
		backend.setSecret("v1:sealed");
		StorageBackendDAO.get().store(backend);

		StoreBackendDAO.get().deleteAll(List.of(StoreBackendDAO.get().findByStore(10).orElseThrow(),
				StoreBackendDAO.get().findByStore(11).orElseThrow()));
		assertEquals(Integer.valueOf(0), jdbc().queryForObject("select count(*) from hd_storage_store", Integer.class));
		useBackend(10, 1);

		StorageBackendDAO.get().deleteAll(List.of(StorageBackendDAO.get().findById(other),
				StorageBackendDAO.get().findById(third)));
		assertEquals(Integer.valueOf(1), jdbc().queryForObject("select count(*) from hd_storage_backend", Integer.class));
		assertEquals(Integer.valueOf(0), jdbc().queryForObject(
				"select count(*) from hd_storage_backend where hd_secret is not null", Integer.class));

		// Original is refused
		assertThrows(PersistenceException.class, () -> StorageBackendDAO.get()
				.deleteAll(List.of(StorageBackendDAO.get().findById(StorageBackend.ORIGINAL))));
		assertNotNull(StorageBackendDAO.get().findById(StorageBackend.ORIGINAL));
	}

	/**
	 * On MariaDB, an UPDATE that matches no row takes a gap lock until its transaction commits, and CE's folder copy
	 * stores every new file in one transaction: a new file's row is inserted without one, and an existing row only
	 * updated.
	 */
	@Test
	public void testNewRowsAreInsertedWithoutAnUpdate() throws Exception {
		RecordingDataSource recording = new RecordingDataSource(Context.get(DataSource.class));
		StoredFiles files = new StoredFiles(recording.dataSource());
		HeldCopies held = new HeldCopies(recording.dataSource());

		long first = files.recordWrite(100L, "1.0", 0L, null, 10L);
		assertFalse(recording.verbs().toString(), recording.verbs().contains("update"));
		assertTrue(recording.verbs().contains("insert"));
		recording.clear();
		long second = files.recordWrite(100L, "1.0", 0L, null, 11L);
		assertTrue(recording.verbs().contains("update"));
		assertFalse(recording.verbs().toString(), recording.verbs().contains("insert"));
		assertNotEquals(first, second);
		assertEquals(11L, files.find(100L, "1.0").orElseThrow().size());

		// The caller that read the row already says whether there is one
		recording.clear();
		files.recordWrite(100L, "1.1", 0L, null, 5L, false, false);
		assertEquals(List.of("insert"), recording.verbs());
		recording.clear();
		files.recordWrite(100L, "1.1", 0L, null, 6L, true, false);
		assertEquals(List.of("update"), recording.verbs());
		assertEquals(6L, files.find(100L, "1.1").orElseThrow().size());

		// A row another transaction inserted after the caller's read: the insert finds its key taken, and updates it
		recording.clear();
		files.recordWrite(100L, "1.1", 0L, null, 7L, false, false);
		assertEquals(List.of("insert", "update"), recording.verbs());
		assertEquals(7L, files.find(100L, "1.1").orElseThrow().size());

		recording.clear();
		Date until = new Date(System.currentTimeMillis() + DAY);
		held.hold(1L, ".held/100/doc/1.0", 100L, "1.0", until);
		assertFalse(recording.verbs().toString(), recording.verbs().contains("update"));
		assertTrue(recording.verbs().contains("insert"));
		recording.clear();
		held.hold(1L, ".held/100/doc/1.0", 100L, "1.0", new Date(until.getTime() + DAY));
		assertTrue(recording.verbs().contains("update"));
		assertFalse(recording.verbs().toString(), recording.verbs().contains("insert"));
		assertEquals(1, held.due(new Date(until.getTime() + 2 * DAY)).size());
	}

	/**
	 * Inside CE's transaction, the storage rows' JDBC shares its connection, and a statement that fails rolls back only
	 * to its savepoint: what ran before it stands and the transaction commits, which PostgreSQL wouldn't allow without
	 * the savepoint. A taken key in {@code recordWrite} is such a statement.
	 */
	@Test
	public void testSavepointsKeepTheTransaction() {
		JdbcTemplate jdbc = jdbc();
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		transaction.executeWithoutResult(status -> {
			files().recordWrite(200L, "1.0", 0L, null, 5L, false, false);
			assertThrows(IllegalStateException.class, () -> Savepoints.run(jdbc, () -> {
				files().recordWrite(200L, "1.1", 0L, null, 5L, false, false);
				assertTrue(files().find(200L, "1.1").isPresent());
				throw new IllegalStateException("Fails after its statement");
			}));
			// Rolled back to the savepoint: 1.1's row went, and 1.0's stays
			assertTrue(files().find(200L, "1.1").isEmpty());
			assertTrue(files().find(200L, "1.0").isPresent());

			// The key taken, as by another transaction since the read: the insert fails in its savepoint, then updates
			files().recordWrite(200L, "1.0", 0L, null, 6L, false, false);
		});
		assertEquals(6L, files().find(200L, "1.0").orElseThrow().size());
		assertTrue(files().find(200L, "1.1").isEmpty());

		// The rows are the transaction's own: a rollback takes them away
		transaction.executeWithoutResult(status -> {
			files().recordWrite(201L, "1.0", 0L, null, 5L, false, false);
			status.setRollbackOnly();
		});
		assertTrue(files().find(201L, "1.0").isEmpty());
	}

	@Test
	public void testOriginalCantBeRemoved() throws Exception {
		assertThrows(PersistenceException.class, () -> StorageBackendDAO.get().remove(StorageBackend.ORIGINAL));
		assertThrows(PersistenceException.class, () -> StorageBackendDAO.get().delete(StorageBackend.ORIGINAL));
		assertNotNull(StorageBackendDAO.get().findById(StorageBackend.ORIGINAL));
	}

	private List<Long> docs(int limit, Set<Long> unavailable) {
		return files().misplaced(limit, unavailable).stream().map(StoredFile::docId).toList();
	}
}
