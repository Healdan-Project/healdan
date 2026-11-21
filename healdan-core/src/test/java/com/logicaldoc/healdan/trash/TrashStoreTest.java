// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.trash;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.util.plugin.PluginException;
import com.logicaldoc.util.spring.Context;

public class TrashStoreTest extends AbstractHealdanTestCase {

	private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");

	private TrashStore store;

	@Override
	public void setUp() throws IOException, SQLException, PluginException {
		super.setUp();
		store = TrashStore.get();
	}

	private static TrashMember folder(long id) {
		return new TrashMember('F', id);
	}

	private static TrashMember doc(long id) {
		return new TrashMember('D', id);
	}

	private static TrashItemRow item(String name, long objectId, Long deletedBy, Instant at) {
		return new TrashItemRow(0, 1, "folder", objectId, name, null, 50L, List.of("Client Work", "998.1 – Demo", "Documents"),
				7L, 1, 2, deletedBy, "User " + deletedBy, at);
	}

	private long insertFolder(String name, long folderId, Long deletedBy, Instant at) {
		return store.insert(item(name, folderId, deletedBy, at), List.of(folder(folderId), doc(folderId * 10), doc(folderId * 10 + 1)));
	}

	@Test
	public void testInsertKeepsTheItemAndItsMembers() {
		TrashItemRow in = new TrashItemRow(0, 1, "folder", 100, "Drafts \"1\"", "998.1-0003", 50L,
				List.of("Client Work", "998.1 – Demo", "Documents"), 7L, 1, 2, 3L, "Alice", T0);
		long id = store.insert(in, List.of(folder(100), doc(1001), doc(1002)));

		TrashItemRow out = store.item(id).orElseThrow();
		assertEquals(new TrashItemRow(id, 1, "folder", 100, "Drafts \"1\"", "998.1-0003", 50L,
				List.of("Client Work", "998.1 – Demo", "Documents"), 7L, 1, 2, 3L, "Alice", T0), out);
		assertEquals(List.of(doc(1001), doc(1002), folder(100)), store.members(id, TrashState.DELETED));
		assertTrue(store.members(id, TrashState.REMOVED).isEmpty());
		assertEquals(TrashState.DELETED, store.state(id));
		assertFalse(store.item(id + 1000).isPresent());
	}

	@Test
	public void testNullablesComeBackNull() {
		TrashItemRow in = new TrashItemRow(0, 1, "document", 5, "a.pdf", null, null, List.of(), null, 0, 1, null, null, T0);
		long id = store.insert(in, List.of(doc(5)));
		TrashItemRow out = store.item(id).orElseThrow();
		assertNull(out.fromFolderId());
		assertNull(out.matterId());
		assertNull(out.deletedById());
		assertEquals(List.of(), out.fromPath());
	}

	@Test
	public void testStateIsTheTopMembers() {
		long id = insertFolder("A", 100, 3L, T0);
		assertEquals(1, store.change(id, List.of(doc(1000)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0));
		assertEquals(TrashState.DELETED, store.state(id));
		assertEquals(1, store.change(id, List.of(folder(100)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0));
		assertEquals(TrashState.REMOVED, store.state(id));
		assertEquals(List.of(doc(1001)), store.members(id, TrashState.DELETED));
	}

	@Test
	public void testChangeTakesOnlyMembersInTheFromState() {
		long id = insertFolder("A", 100, 3L, T0);
		assertEquals(1, store.change(id, List.of(doc(1001)), TrashState.DELETED, TrashState.RESTORED, 3L, "Alice", T0));
		int changed = store.change(id, List.of(folder(100), doc(1000), doc(1001)), TrashState.DELETED, TrashState.REMOVED,
				9L, "Admin", T0.plusSeconds(5));
		assertEquals(2, changed);
		assertEquals(TrashState.RESTORED, store.member(id, doc(1001)).orElseThrow().state());
	}

	@Test
	public void testRemovedStampsByAndAt() {
		long id = insertFolder("A", 100, 3L, T0);
		store.change(id, List.of(doc(1000)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(5));
		TrashMemberRow row = store.member(id, doc(1000)).orElseThrow();
		assertEquals(TrashState.REMOVED, row.state());
		assertEquals(Long.valueOf(9), row.removedById());
		assertEquals("Admin", row.removedByName());
		assertEquals(T0.plusSeconds(5), row.removedAt());
		assertNull(row.restoredById());
		assertNull(row.restoredByName());
		assertNull(row.restoredAt());
		assertNull(row.destroyedAt());
		assertEquals(id, row.trashId());
		assertEquals(doc(1000), row.member());
	}

	@Test
	public void testRestoredStampsByAndAt() {
		long id = insertFolder("A", 100, 3L, T0);
		store.change(id, List.of(doc(1000)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(5));
		store.change(id, List.of(doc(1000)), TrashState.REMOVED, TrashState.RESTORED, 4L, "Bob", T0.plusSeconds(60));
		TrashMemberRow row = store.member(id, doc(1000)).orElseThrow();
		assertEquals(TrashState.RESTORED, row.state());
		assertEquals(Long.valueOf(4), row.restoredById());
		assertEquals("Bob", row.restoredByName());
		assertEquals(T0.plusSeconds(60), row.restoredAt());
		// Removed by and at are kept
		assertEquals(Long.valueOf(9), row.removedById());
		assertEquals("Admin", row.removedByName());
		assertEquals(T0.plusSeconds(5), row.removedAt());
		assertNull(row.destroyedAt());
		assertFalse(store.member(id, doc(77)).isPresent());
	}

	@Test
	public void testDestroyedStampsAt() {
		long id = insertFolder("A", 100, 3L, T0);
		store.change(id, List.of(doc(1000)), TrashState.DELETED, TrashState.DESTROYED, null, null, T0.plusSeconds(9));
		assertEquals(T0.plusSeconds(9), store.member(id, doc(1000)).orElseThrow().destroyedAt());
	}

	@Test
	public void testADeepPathKeepsTheNearestNames() throws Exception {
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 40; i++)
			names.add("Folder %02d ".formatted(i) + "x".repeat(190));
		TrashItemRow in = new TrashItemRow(0, 1, "folder", 100, "Deep", null, 50L, names, 7L, 0, 0, 3L, "Alice", T0);
		long id = store.insert(in, List.of(folder(100)));

		List<String> kept = store.item(id).orElseThrow().fromPath();
		assertFalse(kept.isEmpty());
		assertTrue(kept.size() < names.size());
		// The folder's nearest ancestors stay; the topmost names go
		assertEquals(names.subList(names.size() - kept.size(), names.size()), kept);
		String stored = new JdbcTemplate(Context.get(DataSource.class))
				.queryForObject("select hd_frompath from hd_trash where hd_id = ?", String.class, id);
		assertTrue(stored.length() <= 4000);
		// As many as fit: one more wouldn't
		ObjectMapper json = new ObjectMapper();
		assertTrue(json.writeValueAsString(names.subList(names.size() - kept.size() - 1, names.size())).length() > 4000);
	}

	@Test
	public void testAddMemberIsDeleted() {
		long id = insertFolder("A", 100, 3L, T0);
		store.addMember(id, doc(555));
		assertTrue(store.members(id, TrashState.DELETED).contains(doc(555)));
	}

	private TrashQuery query(String view, long userId, String sort, boolean descending, int offset, int limit) {
		return new TrashQuery(view, userId, 1, sort, descending, offset, limit);
	}

	@Test
	public void testMineListsOnlyTheCallersDeletedItems() {
		long a1 = insertFolder("A1", 100, 3L, T0);
		long a2 = insertFolder("A2", 200, 3L, T0.plusSeconds(10));
		insertFolder("B", 300, 4L, T0.plusSeconds(20));
		long removed = insertFolder("R", 400, 3L, T0.plusSeconds(30));
		store.change(removed, List.of(folder(400)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0);

		TrashQuery all = query("mine", 3, "deleted", true, 0, 50);
		assertEquals(List.of(a2, a1), store.page(all).stream().map(TrashItemRow::id).toList());
		assertEquals(2, store.count(all));
		assertEquals(List.of(a1), store.page(query("mine", 3, "deleted", true, 1, 1)).stream().map(TrashItemRow::id).toList());
		assertEquals(2, store.count(query("mine", 3, "deleted", true, 1, 1)));
		assertEquals(1, store.count(query("mine", 4, "deleted", true, 0, 50)));
		assertEquals(0, store.count(query("mine", 5, "deleted", true, 0, 50)));
	}

	@Test
	public void testEveryoneListsEveryDeletedItem() {
		long a = insertFolder("A", 100, 3L, T0);
		long b = insertFolder("B", 200, 4L, T0.plusSeconds(10));
		long gone = insertFolder("G", 300, 3L, T0.plusSeconds(20));
		store.change(gone, List.of(folder(300)), TrashState.DELETED, TrashState.RESTORED, 3L, "A", T0);
		long destroyed = insertFolder("D", 400, 3L, T0.plusSeconds(30));
		store.change(destroyed, List.of(folder(400)), TrashState.DELETED, TrashState.DESTROYED, null, null, T0);

		TrashQuery all = query("everyone", 3, "deleted", false, 0, 50);
		assertEquals(List.of(a, b), store.page(all).stream().map(TrashItemRow::id).toList());
		assertEquals(2, store.count(all));
		assertEquals(List.of(b), store.page(query("everyone", 3, "deleted", false, 1, 1)).stream().map(TrashItemRow::id).toList());
		assertEquals(0, store.count(new TrashQuery("everyone", 3, 2, "deleted", false, 0, 50)));
	}

	@Test
	public void testRemovedListsItemsWhoseTopMemberIsRemoved() {
		long a = insertFolder("A", 100, 3L, T0);
		long b = insertFolder("B", 200, 3L, T0.plusSeconds(10));
		long c = insertFolder("C", 300, 3L, T0.plusSeconds(20));
		// Only a child removed: not in the Removed list, and still in Everyone
		store.change(c, List.of(doc(3000)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0);
		store.change(a, List.of(folder(100)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(100));
		store.change(b, List.of(folder(200)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(200));

		TrashQuery all = query("removed", 3, "removed", true, 0, 50);
		assertEquals(List.of(b, a), store.page(all).stream().map(TrashItemRow::id).toList());
		assertEquals(2, store.count(all));
		assertEquals(List.of(a), store.page(query("removed", 3, "removed", true, 1, 1)).stream().map(TrashItemRow::id).toList());
		assertEquals(1, store.count(query("everyone", 3, "deleted", true, 0, 50)));
	}

	@Test
	public void testSortByNameDeletedAndRemoved() {
		long b = insertFolder("banana", 100, 3L, T0);
		long a = insertFolder("Apple", 200, 3L, T0.plusSeconds(10));
		long c = insertFolder("cherry", 300, 3L, T0.plusSeconds(20));
		store.change(b, List.of(folder(100)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(300));
		store.change(a, List.of(folder(200)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(100));
		store.change(c, List.of(folder(300)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(200));

		assertEquals(List.of(a, b, c), ids(query("removed", 3, "name", false, 0, 50)));
		assertEquals(List.of(c, b, a), ids(query("removed", 3, "name", true, 0, 50)));
		assertEquals(List.of(b, a, c), ids(query("removed", 3, "deleted", false, 0, 50)));
		assertEquals(List.of(c, a, b), ids(query("removed", 3, "deleted", true, 0, 50)));
		assertEquals(List.of(a, c, b), ids(query("removed", 3, "removed", false, 0, 50)));
		assertEquals(List.of(b, c, a), ids(query("removed", 3, "removed", true, 0, 50)));
	}

	private List<Long> ids(TrashQuery q) {
		return store.page(q).stream().map(TrashItemRow::id).toList();
	}

	@Test
	public void testEveryColumnSorts() {
		// Inserted in an order that is no column's
		long a = insertItem(100, "10", List.of("A", "B"), "bob");
		long b = insertItem(200, "9", List.of("A B"), "Alice");
		long c = insertItem(300, null, List.of("A"), "carol");
		long d = insertItem(400, "100", null, null);
		long e = insertItem(500, "2", List.of("a", "c"), "Dave");
		removeItem(a, "Zed", 300);
		removeItem(b, null, 100);
		removeItem(c, "amy", 200);
		removeItem(d, "Bea", 400);
		removeItem(e, "cy", 500);

		// Doc #s as numbers, a folder's none last either way
		assertEquals(List.of(e, b, a, d, c), ids(query("removed", 3, "docNumber", false, 0, 50)));
		assertEquals(List.of(d, a, b, e, c), ids(query("removed", 3, "docNumber", true, 0, 50)));
		// The recorded path's steps, ignoring case, a folder before what it holds; no path last
		assertEquals(List.of(c, a, e, b, d), ids(query("removed", 3, "deletedFrom", false, 0, 50)));
		assertEquals(List.of(b, e, a, c, d), ids(query("removed", 3, "deletedFrom", true, 0, 50)));
		// The names recorded, ignoring case; none last
		assertEquals(List.of(b, a, c, e, d), ids(query("removed", 3, "deletedBy", false, 0, 50)));
		assertEquals(List.of(e, c, a, b, d), ids(query("removed", 3, "deletedBy", true, 0, 50)));
		assertEquals(List.of(c, d, e, a, b), ids(query("removed", 3, "removedBy", false, 0, 50)));
		assertEquals(List.of(a, e, d, c, b), ids(query("removed", 3, "removedBy", true, 0, 50)));
		// The night it's destroyed follows its removal
		assertEquals(List.of(b, c, a, d, e), ids(query("removed", 3, "destroyed", false, 0, 50)));
		assertEquals(List.of(e, d, a, c, b), ids(query("removed", 3, "destroyed", true, 0, 50)));
	}

	@Test
	public void testASortWithNoValueKeepsTheItemsInIdOrder() {
		// In the Trash, nothing is removed yet, so no item has a Removed By, Removed or Destroyed: each sort leaves the
		// items in id order, in its own direction, whatever their deletes' order
		long a = insertFolder("A", 100, 3L, T0.plusSeconds(20));
		long b = insertFolder("B", 200, 3L, T0);
		long c = insertFolder("C", 300, 3L, T0.plusSeconds(10));
		for (String view : List.of("mine", "everyone")) {
			for (String sort : List.of("removedBy", "removed", "destroyed")) {
				assertEquals(view + " " + sort, List.of(a, b, c), ids(query(view, 3, sort, false, 0, 50)));
				assertEquals(view + " " + sort, List.of(c, b, a), ids(query(view, 3, sort, true, 0, 50)));
			}
		}
	}

	@Test
	public void testRefusesASortItDoesntKnow() {
		insertFolder("A", 100, 3L, T0);
		// The resource refuses one first, so this is a mistake, not a sort by Deleted
		assertThrows(IllegalArgumentException.class, () -> store.page(query("mine", 3, "size", false, 0, 50)));
	}

	@Test
	public void testDeletedFromReadsTheNamesInThePath() {
		// A quote or a backslash in a name sorts as itself, and only the commas between the names part them. An empty
		// path, outside the Stores, shows no place, so it comes last either way.
		long quoteComma = insertItem(1, "1", List.of("x\",", "y"), "x");
		long hash = insertItem(2, "2", List.of("a#b"), "x");
		long backslashB = insertItem(3, "3", List.of("a\\b"), "x");
		long empty = insertItem(4, "4", List.of(), "x");
		long quote = insertItem(5, "5", List.of("a\"b"), "x");
		long quoteThenZ = insertItem(6, "6", List.of("x\"", "z"), "x");
		long bang = insertItem(7, "7", List.of("a!b"), "x");
		long backslashThenB = insertItem(8, "8", List.of("a\\", "b"), "x");
		long quoteEnd = insertItem(9, "9", List.of("x\""), "x");
		long backslash = insertItem(10, "10", List.of("a\\"), "x");

		assertEquals(List.of(bang, quote, hash, backslash, backslashThenB, backslashB, quoteEnd, quoteThenZ, quoteComma,
				empty), ids(query("everyone", 3, "deletedFrom", false, 0, 50)));
		assertEquals(List.of(quoteComma, quoteThenZ, quoteEnd, backslashB, backslashThenB, backslash, hash, quote, bang,
				empty), ids(query("everyone", 3, "deletedFrom", true, 0, 50)));
	}

	/**
	 * Inserts a delete's item with the values the Trash's columns show, its
	 * top member deleted
	 *
	 * @param docNumber the doc #; null for a folder
	 * @param path the path recorded; null for none, which the column allows
	 *        though the store always writes one
	 */
	private long insertItem(long objectId, String docNumber, List<String> path, String deletedBy) {
		boolean isFolder = docNumber == null;
		TrashItemRow row = new TrashItemRow(0, 1, isFolder ? "folder" : "document", objectId, "Item " + objectId,
				docNumber, 50L, path, 7L, 0, 0, 3L, deletedBy, T0);
		long id = store.insert(row, List.of(isFolder ? folder(objectId) : doc(objectId)));
		if (path == null)
			new JdbcTemplate(Context.get(DataSource.class))
					.update("update hd_trash set hd_frompath = null where hd_id = ?", id);
		return id;
	}

	/** Moves the item's top member to Removed, by the name given, the seconds given after {@link #T0} */
	private void removeItem(long id, String by, long seconds) {
		TrashItemRow row = store.item(id).orElseThrow();
		TrashMember top = "folder".equals(row.kind()) ? folder(row.objectId()) : doc(row.objectId());
		assertEquals(1,
				store.change(id, List.of(top), TrashState.DELETED, TrashState.REMOVED, 9L, by, T0.plusSeconds(seconds)));
	}

	@Test
	public void testRemovedBeforePagesOnFromTheLastItem() {
		long a = insertFolder("A", 100, 3L, T0);
		long b = insertFolder("B", 200, 3L, T0);
		long c = insertFolder("C", 300, 3L, T0);
		long d = insertFolder("D", 400, 3L, T0);
		store.change(a, List.of(doc(1000)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(300));
		store.change(a, List.of(doc(1001)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(50));
		store.change(b, List.of(folder(200)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(10));
		// The same removal as a's, one id later
		store.change(c, List.of(folder(300)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(50));
		// Removed after the cut-off
		store.change(d, List.of(folder(400)), TrashState.DELETED, TrashState.REMOVED, 9L, "Admin", T0.plusSeconds(900));
		Instant cutoff = T0.plusSeconds(500);

		List<TrashStore.RemovedItem> first = store.removedBefore(cutoff, null, 2);
		assertEquals(List.of(new TrashStore.RemovedItem(b, T0.plusSeconds(10)),
				new TrashStore.RemovedItem(a, T0.plusSeconds(50))), first);
		// a's earliest member is destroyed meanwhile: its first removal stays, so the next page carries on after it
		store.change(a, List.of(doc(1001)), TrashState.REMOVED, TrashState.DESTROYED, null, null, T0.plusSeconds(600));
		assertEquals(List.of(new TrashStore.RemovedItem(c, T0.plusSeconds(50))),
				store.removedBefore(cutoff, first.get(1), 2));
		assertTrue(store.removedBefore(cutoff, new TrashStore.RemovedItem(c, T0.plusSeconds(50)), 2).isEmpty());

		assertEquals(List.of(doc(1000)), store.membersRemovedBefore(a, cutoff));
		assertTrue(store.membersRemovedBefore(a, T0.plusSeconds(300)).isEmpty());
		assertTrue(store.membersRemovedBefore(d, cutoff).isEmpty());
	}

	@Test
	public void testItemHoldingFindsTheNewest() {
		long first = insertFolder("A", 100, 3L, T0);
		store.change(first, List.of(folder(100)), TrashState.DELETED, TrashState.RESTORED, 3L, "A", T0);
		long second = insertFolder("A", 100, 3L, T0.plusSeconds(60));

		assertEquals(Optional.of(second), store.itemHolding(folder(100), TrashState.DELETED));
		assertEquals(Optional.of(first), store.itemHolding(folder(100), TrashState.RESTORED));
		assertEquals(Optional.empty(), store.itemHolding(folder(100), TrashState.REMOVED));
		assertEquals(Optional.empty(), store.itemHolding(doc(100), TrashState.DELETED));
	}

	@Test
	public void testABigItemChangesInAFewStatements() {
		// A folder of thousands: 2,000 documents and 500 folders, one of them restored already
		List<TrashMember> members = new ArrayList<>();
		for (long i = 1; i <= 2000; i++)
			members.add(doc(10_000 + i));
		for (long i = 1; i <= 500; i++)
			members.add(folder(20_000 + i));
		long id = store.insert(item("Big", 20_001, 3L, T0), members);
		long other = store.insert(item("Other", 30_000, 3L, T0), List.of(folder(30_000)));
		store.change(id, List.of(doc(10_001)), TrashState.DELETED, TrashState.RESTORED, 3L, "Alice", T0);
		List<String> sent = new ArrayList<>();
		TrashStore counted = new TrashStore(counting(Context.get(DataSource.class), sent));

		// At most a statement per thousand members of a type, with the state they must be in
		assertEquals(2499, counted.change(id, members, TrashState.DELETED, TrashState.REMOVED, 9L, "Admin",
				T0.plusSeconds(5)));
		assertTrue(sent.size() + " statements", sent.size() <= 3);
		assertEquals(TrashState.RESTORED, store.member(id, doc(10_001)).orElseThrow().state());
		TrashMemberRow removed = store.member(id, folder(20_500)).orElseThrow();
		assertEquals(TrashState.REMOVED, removed.state());
		assertEquals(Long.valueOf(9), removed.removedById());
		assertEquals("Admin", removed.removedByName());
		assertEquals(T0.plusSeconds(5), removed.removedAt());
		assertEquals(2499, store.members(id, TrashState.REMOVED).size());

		// And moving them into another item
		sent.clear();
		assertEquals(2500, counted.moveMembers(id, members, other));
		assertTrue(sent.size() + " statements", sent.size() <= 3);
		assertEquals(2499, store.members(other, TrashState.REMOVED).size());
		assertEquals(List.of(doc(10_001)), store.members(other, TrashState.RESTORED));
		assertTrue(store.members(id, TrashState.REMOVED).isEmpty());
	}

	/**
	 * @param sent gets each update statement prepared on the data source's
	 *        connections
	 * @return the data source, counting
	 */
	private static DataSource counting(DataSource dataSource, List<String> sent) {
		return (DataSource) Proxy.newProxyInstance(TrashStoreTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					Object result = invoke(dataSource, method, args);
					if (!(result instanceof Connection connection))
						return result;
					return Proxy.newProxyInstance(TrashStoreTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (p, m, a) -> {
								if (m.getName().equals("prepareStatement") && a[0] instanceof String sql
										&& sql.startsWith("update"))
									sent.add(sql);
								return invoke(connection, m, a);
							});
				});
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test
	public void testSettingDefaultsAndClamps() {
		HealdanSettings settings = HealdanSettings.get();
		settings.delete(HealdanSettings.TRASH_REMOVED_DAYS);
		assertEquals(30, settings.trashRemovedDays());
		settings.set(HealdanSettings.TRASH_REMOVED_DAYS, "0");
		assertEquals(1, settings.trashRemovedDays());
		settings.set(HealdanSettings.TRASH_REMOVED_DAYS, "999");
		assertEquals(365, settings.trashRemovedDays());
		settings.set(HealdanSettings.TRASH_REMOVED_DAYS, "abc");
		assertEquals(30, settings.trashRemovedDays());
		settings.set(HealdanSettings.TRASH_REMOVED_DAYS, "45");
		assertEquals(45, settings.trashRemovedDays());
	}
}
