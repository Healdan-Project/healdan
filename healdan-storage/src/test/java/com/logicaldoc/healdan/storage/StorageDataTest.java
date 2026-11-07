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

	private List<Long> docs(int limit, Set<Long> unavailable) {
		return files().misplaced(limit, unavailable).stream().map(StoredFile::docId).toList();
	}
}
