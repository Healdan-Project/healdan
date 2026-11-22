// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.logging.log4j.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentDAO;
import com.logicaldoc.core.document.DocumentManager;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.Device;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistoryDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminArea;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.admin.AdminEvent;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.admin.AdminEvents;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The Audit Trail's one feed (Admin app spec sections 8.8 and 9.3; rulings P5-R8 to P5-R14): the five sources merged
 * newest first, paged by a cursor that holds each source's place, every filter, the bounded reads, and the words.
 * Rows that need exact ids and times are written by JDBC; the rest by the writers that make them.
 */
public class AuditFeedTest extends AbstractHealdanTestCase {

	private static final Instant T0 = Instant.parse("2026-11-20T12:00:00Z");

	private static final String[] TABLES = { "ld_history", "ld_folder_history", "ld_user_history", "hd_admin_event",
			"hd_storage_event" };

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private AuditFeed feed;

	private User admin;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		feed = AuditFeed.get();
		admin = fx.admin();
	}

	@After
	public void putBack() {
		feed.scanPerSource = AuditFeed.SCAN_PER_SOURCE;
		AdminContext.clear();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(AuditFeed.class), AuditFeed.get());
	}

	@Test
	public void testMergesTheFiveSourcesNewestFirst() throws Exception {
		empty();
		history("ld_history", 1001, T0.plusSeconds(60), 1L, "admin", "event.downloaded");
		history("ld_history", 1002, T0.plusSeconds(300), 1L, "admin", "event.checkedin");
		history("ld_history", 1003, T0.plusSeconds(540), 1L, "admin", "event.viewed");
		history("ld_folder_history", 2001, T0.plusSeconds(120), 1L, "admin", "event.folder.created");
		history("ld_folder_history", 2002, T0.plusSeconds(360), 1L, "admin", "event.folder.renamed");
		history("ld_user_history", 3001, T0.plusSeconds(180), 1L, "admin", "event.user.login");
		history("ld_user_history", 3002, T0.plusSeconds(420), 1L, "admin", "event.user.passwordchanged");
		adminRow(11, T0.plusSeconds(240), 1L, "admin", "client.renamed", "client", 5L, "Ashcombe", null);
		adminRow(12, T0.plusSeconds(480), 1L, "admin", "matter.created", "matter", 6L, "1001.1 Charter", null);
		storage(100, T0.plusSeconds(600), 1L, 1L, "admin", "key-shown");

		AuditPage page = feed.page(AuditQuery.all(100), admin);

		assertEquals(List.of("s100", "d1003", "a12", "u3002", "f2002", "d1002", "a11", "u3001", "f2001", "d1001"),
				ids(page.items()));
		assertNull(page.nextCursor());
		assertEquals(List.of("administration", "document", "administration", "user", "folder", "document",
				"administration", "sign-in", "folder", "document"), page.items().stream().map(AuditRow::kind).toList());
		assertEquals(List.of("Showed a recovery key", "Viewed", "Created a matter", "Changed the password",
				"Renamed a folder", "Saved a new version", "Renamed a client", "Signed in", "Created a folder",
				"Downloaded"), page.items().stream().map(AuditRow::what).toList());
		assertEquals(T0.plusSeconds(600), page.items().get(0).when());
		assertEquals("key-shown", page.items().get(0).event());
	}

	@Test
	public void testPagesByCursorWithNoRepeatAndNoGapWhileRowsArrive() throws Exception {
		empty();
		Set<String> written = new HashSet<>();
		long minute = 0;
		for (int i = 0; i < 6; i++) {
			written.add("d" + history("ld_history", 1000 + i, T0.plusSeconds(60 * minute++), 1L, "admin", "event.viewed"));
			written.add("f" + history("ld_folder_history", 2000 + i, T0.plusSeconds(60 * minute++), 1L, "admin",
					"event.folder.created"));
			written.add("u" + history("ld_user_history", 3000 + i, T0.plusSeconds(60 * minute++), 1L, "admin",
					"event.user.login"));
			written.add("a" + adminRow(10 + i, T0.plusSeconds(60 * minute++), 1L, "admin", "client.renamed",
					"client", 1L, "C", null));
			written.add("s" + storage(100 + 50 * i, T0.plusSeconds(60 * minute++), 1L, 1L, "admin", "key-entered"));
		}

		List<AuditRow> seen = new ArrayList<>();
		AuditQuery query = AuditQuery.all(7);
		int arriving = 0;
		while (true) {
			AuditPage page = feed.page(query, admin);
			assertTrue(page.items().size() <= 7);
			seen.addAll(page.items());
			if (page.nextCursor() == null)
				break;
			// New rows arrive in every source between pages, newer than everything, with higher ids
			arriving++;
			Instant later = T0.plus(Duration.ofHours(1)).plusSeconds(arriving);
			history("ld_history", 5000 + arriving, later, 1L, "admin", "event.downloaded");
			history("ld_folder_history", 6000 + arriving, later, 1L, "admin", "event.folder.renamed");
			history("ld_user_history", 7000 + arriving, later, 1L, "admin", "event.user.logout");
			adminRow(500 + arriving, later, 1L, "admin", "client.created", "client", 2L, "D", null);
			storage(10000 + 50 * arriving, later, 1L, 1L, "admin", "key-shown");
			query = query.at(page.nextCursor());
			// No offset, no total: the cursor holds each source's place, and nothing else
			assertFalse(page.nextCursor().atEnd());
		}

		List<String> ids = ids(seen);
		assertEquals(written, Set.copyOf(ids));
		assertEquals("no repeat", ids.size(), Set.copyOf(ids).size());
		assertNewestFirst(seen);
		assertTrue(arriving >= 4);

		// A first page afresh starts with the rows that arrived
		AuditPage fresh = feed.page(AuditQuery.all(5), admin);
		assertTrue(fresh.items().stream().allMatch(row -> row.when().isAfter(T0.plus(Duration.ofMinutes(59)))));
	}

	@Test
	public void testASourceNoPageHasReachedKeepsItsPlace() throws Exception {
		empty();
		Set<String> written = new HashSet<>();
		for (int i = 0; i < 3; i++)
			written.add("d" + history("ld_history", 1000 + i, T0.plusSeconds(i), 1L, "admin", "event.viewed"));
		for (int i = 0; i < 10; i++)
			written.add("a" + adminRow(10 + i, T0.plusSeconds(100 + i), 1L, "admin", "client.renamed", "client", 1L,
					"C", null));

		AuditPage first = feed.page(AuditQuery.all(5), admin);
		assertEquals(List.of("a19", "a18", "a17", "a16", "a15"), ids(first.items()));
		// Newer than everything, in a source the first page took nothing from
		history("ld_history", 5000, T0.plusSeconds(1000), 1L, "admin", "event.downloaded");

		List<AuditRow> seen = new ArrayList<>(first.items());
		seen.addAll(all(AuditQuery.all(5).at(first.nextCursor())));

		assertEquals(written, Set.copyOf(ids(seen)));
		assertEquals(13, seen.size());
	}

	@Test
	public void testTheRealWritersRows() throws Exception {
		User alice = fx.user("alice");
		alice.setFirstName("Alice");
		alice.setName("Ng");
		UserDAO.get().store(alice);
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = child(matter.getFolderId(), "Documents");
		Document letter = fx.upload(documents, "Skeleton Argument.docx", "Dear Sir", alice);
		HealdanFixtures.awaitVersionSaves();
		Folder notes = fx.folder(documents, "Notes", alice);
		signIn(alice, "192.0.2.17");
		AdminContext.set("192.0.2.9");
		AdminEvents.get().record(new AdminEvent(AdminArea.PEOPLE, "user.changed", "user", alice.getId(),
				"Alice Ng (alice)", Map.of("email", new Change("a@example.com", "alice@example.com"),
						"password", Change.secret())),
				admin);
		AdminContext.clear();
		long backend = backend("Wasabi EU");
		storage(100, Instant.now(), backend, admin.getId(), "admin", "key-shown");
		storage(150, Instant.now(), 987654L, admin.getId(), "admin", "key-entered");

		List<AuditRow> rows = all(AuditQuery.all(200));

		AuditRow stored = only(rows, row -> row.kind().equals("document") && row.event().equals("event.stored")
				&& row.who() != null && row.who().equals("Alice Ng"));
		String docNumber = DocumentDAO.get().findById(letter.getId()).getCustomId();
		assertNotNull(docNumber);
		assertEquals(Long.valueOf(docNumber), stored.docNumber());
		assertEquals("# %s, Skeleton Argument.docx, version 1.0".formatted(docNumber), stored.detail());
		assertEquals(Long.valueOf(documents.getId()), stored.folderId());
		assertEquals("Filed", stored.what());
		assertNull(stored.changes());
		assertTrue(stored.id().startsWith("d"));

		AuditRow created = only(rows, row -> row.kind().equals("folder") && row.folderId() != null
				&& row.folderId() == notes.getId() && row.event().equals("event.folder.created"));
		assertEquals("Alice Ng", created.who());
		assertEquals(jdbc.queryForObject("select ld_path from ld_folder_history where ld_folderid = ?"
				+ " and ld_event = 'event.folder.created'", String.class, notes.getId()), created.detail());
		assertNull(created.docNumber());

		AuditRow login = only(rows, row -> row.kind().equals("sign-in"));
		assertEquals("Alice Ng", login.who());
		assertEquals("Signed in", login.what());
		assertEquals("192.0.2.17", login.from());
		assertEquals(jdbc.queryForObject("select ld_device from ld_user_history where ld_event = 'event.user.login'",
				String.class), login.detail());
		assertTrue(login.detail(), login.detail().startsWith("Safari v18 on macOS"));
		assertNull(login.folderId());

		AuditRow changed = only(rows, row -> row.event().equals("user.changed"));
		assertEquals("administration", changed.kind());
		assertEquals("Changed a user", changed.what());
		assertEquals(fullName(admin), changed.who());
		assertEquals("192.0.2.9", changed.from());
		assertEquals("Alice Ng (alice)", changed.detail());
		assertNull(changed.folderId());
		assertEquals(List.of("email", "password"), List.copyOf(changed.changes().keySet()));
		assertEquals(new Change("a@example.com", "alice@example.com"), changed.changes().get("email"));
		assertTrue(changed.changes().get("password").isSecret());

		AuditRow shown = only(rows, row -> row.id().equals("s100"));
		assertEquals("administration", shown.kind());
		assertEquals("Wasabi EU", shown.detail());
		assertNull(shown.from());
		assertNull(shown.changes());
		assertEquals("Backend 987654", only(rows, row -> row.id().equals("s150")).detail());

		AuditRow matterCreated = only(rows, row -> row.event().equals("matter.created"));
		assertEquals("administration", matterCreated.kind());
		assertNotNull(matterCreated.changes());
		assertNewestFirst(rows);
	}

	@Test
	public void testEachKind() throws Exception {
		empty();
		history("ld_history", 1001, T0.plusSeconds(1), 1L, "admin", "event.viewed");
		history("ld_folder_history", 2001, T0.plusSeconds(2), 1L, "admin", "event.folder.created");
		history("ld_user_history", 3001, T0.plusSeconds(3), 1L, "admin", "event.user.login");
		history("ld_user_history", 3002, T0.plusSeconds(4), 1L, "admin", "event.user.logout");
		history("ld_user_history", 3003, T0.plusSeconds(5), 1L, "admin", "event.user.timeout");
		history("ld_user_history", 3004, T0.plusSeconds(6), 1L, "admin", "event.user.login.failed");
		history("ld_user_history", 3005, T0.plusSeconds(7), 1L, "admin", "event.user.passwordchanged");
		history("ld_user_history", 3006, T0.plusSeconds(8), 1L, "admin", null);
		adminRow(11, T0.plusSeconds(9), 1L, "admin", "client.renamed", "client", 5L, "Ashcombe", null);
		storage(100, T0.plusSeconds(10), 1L, 1L, "admin", "key-shown");

		assertEquals(List.of("u3004", "u3003", "u3002", "u3001"), ids(all(kinds("sign-in"))));
		assertEquals(List.of("u3006", "u3005"), ids(all(kinds("user"))));
		assertEquals(List.of("d1001"), ids(all(kinds("document"))));
		assertEquals(List.of("f2001"), ids(all(kinds("folder"))));
		assertEquals(List.of("s100", "a11"), ids(all(kinds("administration"))));
		assertEquals(List.of("u3006", "u3005", "u3004", "u3003", "u3002", "u3001", "d1001"),
				ids(all(kinds("sign-in", "user", "document"))));
		AuditRow none = only(all(kinds("user")), row -> row.id().equals("u3006"));
		assertEquals("", none.event());
		assertEquals("", none.what());
		assertThrows(IllegalArgumentException.class, () -> kinds("everything"));
	}

	@Test
	public void testThePersonFilter() throws Exception {
		User alice = fx.user("alice", "admin");
		User bob = fx.user("bob", "admin");
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = child(matter.getFolderId(), "Documents");
		Folder aliceFolder = fx.folder(documents, "Alice's", alice);
		fx.folder(documents, "Bob's", bob);
		fx.upload(aliceFolder, "a.txt", "a", alice);
		fx.upload(aliceFolder, "b.txt", "b", bob);
		signIn(alice, "192.0.2.1");
		signIn(bob, "192.0.2.2");
		AdminEvents events = AdminEvents.get();
		events.record(new AdminEvent(AdminArea.PEOPLE, "user.disabled", "user", alice.getId(), "alice", null), admin);
		events.record(new AdminEvent(AdminArea.PEOPLE, "user.disabled", "user", bob.getId(), "bob", null), admin);
		events.record(new AdminEvent(AdminArea.MATTERS, "client.created", "client", 3L, "Done by Alice", null),
				alice);
		events.record(new AdminEvent(AdminArea.PEOPLE, "group.changed", "group", alice.getId(), "Same id", null),
				admin);
		storage(100, Instant.now(), 1L, alice.getId(), "alice", "key-shown");
		storage(150, Instant.now(), 1L, bob.getId(), "bob", "key-shown");

		List<AuditRow> rows = all(person(alice.getId()));

		assertFalse(rows.isEmpty());
		for (AuditRow row : rows) {
			boolean byAlice = "alice alice".equals(row.who());
			boolean aboutAlice = row.event().equals("user.disabled") && "alice".equals(row.detail());
			assertTrue(row.toString(), byAlice || aboutAlice);
		}
		assertTrue(rows.stream().anyMatch(row -> row.kind().equals("document") && row.event().equals("event.stored")));
		assertTrue(rows.stream().anyMatch(row -> row.kind().equals("folder")));
		assertTrue(rows.stream().anyMatch(row -> row.kind().equals("sign-in")));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("user.disabled")));
		assertTrue(rows.stream().anyMatch(row -> "Done by Alice".equals(row.detail())));
		assertTrue(rows.stream().anyMatch(row -> row.id().equals("s100")));
		assertFalse(rows.stream().anyMatch(row -> row.id().equals("s150")));
		assertFalse(rows.stream().anyMatch(row -> "Same id".equals(row.detail())));
		// The account's own history, as CE writes it at its creation, is about her
		assertTrue(rows.stream().anyMatch(row -> row.kind().equals("user")));

		assertEquals(List.of(), all(person(987654L)));
	}

	@Test
	public void testTheMatterFilter() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents", "Drafts");
		Matter other = fx.matter(fx.client(1002, "Brook"), 1, "Lease", store, "Documents");
		Folder documents = child(matter.getFolderId(), "Documents");
		Folder drafts = child(matter.getFolderId(), "Drafts");
		Folder old = fx.folder(drafts, "Old");
		Folder search = fx.searchFolder(documents, "Recent");
		Folder otherDocuments = child(other.getFolderId(), "Documents");
		Document kept = fx.upload(documents, "kept.txt", "kept", admin);
		Document trashed = fx.upload(old, "trashed.txt", "trashed", admin);
		Document leaving = fx.upload(documents, "leaving.txt", "leaving", admin);
		fx.upload(otherDocuments, "elsewhere.txt", "elsewhere", admin);
		HealdanFixtures.awaitVersionSaves();
		// A folder in the Trash is still the matter's
		FolderDAO.get().deleteTree(old.getId(), fx.folderHistory(admin));
		// A document that leaves keeps the rows it made while it was in the matter
		DocumentManager.get().moveToFolder(DocumentDAO.get().findById(leaving.getId()), otherDocuments,
				fx.documentHistory(admin));
		HealdanFixtures.awaitVersionSaves();
		AdminEvents events = AdminEvents.get();
		events.record(new AdminEvent(AdminArea.MATTERS, "folder.renamed", "folder", drafts.getId(), "Drafts", null),
				admin);
		events.record(new AdminEvent(AdminArea.MATTERS, "search-folder.rule-changed", "search-folder", search.getId(),
				"Recent", null), admin);
		events.record(new AdminEvent(AdminArea.MATTERS, "folder.renamed", "folder", otherDocuments.getId(), "Other",
				null), admin);
		events.record(new AdminEvent(AdminArea.MATTERS, "folder.renamed", "matter", other.getId(), "Wrong kind",
				null), admin);
		signIn(admin, "192.0.2.3");
		storage(100, Instant.now(), 1L, admin.getId(), "admin", "key-shown");

		List<AuditRow> rows = all(matter(matter.getId()));

		Set<Long> tree = Set.of(matter.getFolderId(), documents.getId(), drafts.getId(), old.getId(), search.getId());
		for (AuditRow row : rows) {
			assertTrue(row.toString(), Set.of("document", "folder", "administration").contains(row.kind()));
			if (!row.kind().equals("administration"))
				assertTrue(row.toString(), tree.contains(row.folderId()));
			assertFalse(row.toString(), row.id().startsWith("s"));
		}
		Set<Long> docRows = rows.stream().filter(row -> row.kind().equals("document")).map(AuditRow::docNumber)
				.collect(Collectors.toSet());
		assertTrue(docRows.contains(docNumber(kept)));
		assertTrue(docRows.contains(docNumber(trashed)));
		assertTrue(docRows.contains(docNumber(leaving)));
		assertFalse(rows.stream().anyMatch(row -> row.kind().equals("document")
				&& docNumber(leaving).equals(row.docNumber()) && row.event().equals("event.moved")));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("matter.created")));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("folder.renamed") && "Drafts".equals(row.detail())
				&& Long.valueOf(drafts.getId()).equals(row.folderId())));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("search-folder.rule-changed")
				&& Long.valueOf(search.getId()).equals(row.folderId())));
		assertFalse(rows.stream().anyMatch(row -> "Other".equals(row.detail()) || "Wrong kind".equals(row.detail())));
		assertTrue(rows.stream().anyMatch(row -> row.kind().equals("folder") && row.folderId() == old.getId()));

		// The other matter has the move
		assertTrue(all(matter(other.getId())).stream().anyMatch(row -> row.event().equals("event.moved")
				&& docNumber(leaving).equals(row.docNumber())));
		// A matter that doesn't exist keeps no row
		AuditPage none = feed.page(matter(987654L), admin);
		assertEquals(List.of(), none.items());
		assertNull(none.nextCursor());
	}

	@Test
	public void testTheDocNumberFilter() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = child(matter.getFolderId(), "Documents");
		Document one = fx.upload(documents, "one.txt", "one", admin);
		Document two = fx.upload(documents, "two.txt", "two", admin);
		HealdanFixtures.awaitVersionSaves();
		DocumentManager.get().rename(one.getId(), "first.txt", fx.documentHistory(admin));
		HealdanFixtures.awaitVersionSaves();

		List<AuditRow> rows = all(docNumber(docNumber(one)));

		assertTrue(rows.size() >= 2);
		assertTrue(rows.stream().allMatch(row -> row.kind().equals("document")
				&& docNumber(one).equals(row.docNumber())));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("event.renamed")));
		assertFalse(rows.stream().anyMatch(row -> docNumber(two).equals(row.docNumber())));

		AuditPage none = feed.page(docNumber(987654321L), admin);
		assertEquals(List.of(), none.items());
		assertNull(none.nextCursor());
	}

	@Test
	public void testTheTextFilter() throws Exception {
		User dora = fx.user("dvance");
		dora.setFirstName("Dorothy");
		dora.setName("Vance");
		UserDAO.get().store(dora);
		long backend = backend("Garage_Main");
		empty();
		history("ld_history", 1001, T0.plusSeconds(1), 1L, "admin", "event.viewed", "Skeleton_Argument.docx",
				"/Default/Client Work", "192.0.2.55");
		history("ld_history", 1002, T0.plusSeconds(2), 1L, "admin", "event.viewed", "SkeletonXArgument.docx", null,
				null);
		history("ld_folder_history", 2001, T0.plusSeconds(3), 1L, "admin", "event.folder.created", null,
				"/Default/Matters/100% Done", null);
		history("ld_user_history", 3001, T0.plusSeconds(4), 1L, "carol", "event.user.login", null, null,
				"198.51.100.4");
		history("ld_user_history", 3002, T0.plusSeconds(5), dora.getId(), "dvance", "event.user.login", null, null,
				null);
		adminRow(11, T0.plusSeconds(6), 1L, "admin", "backend.changed", "backend", 5L, "Wasabi 100%", "198.51.100.9");
		adminRow(12, T0.plusSeconds(7), 1L, "admin", "backend.changed", "backend", 6L, "Wasabi 1000", null);
		storage(100, T0.plusSeconds(8), backend, 1L, "admin", "key-shown");
		storage(150, T0.plusSeconds(9), 1L, 1L, "erin", "key-shown");

		assertEquals(List.of("d1001"), ids(all(text("_ARGUMENT"))));
		assertEquals(List.of("d1002", "d1001"), ids(all(text("argument"))));
		assertEquals(List.of("a11", "f2001"), ids(all(text("100%"))));
		assertEquals(List.of("u3001"), ids(all(text("  CAROL  "))));
		assertEquals(List.of("a11", "u3001"), ids(all(text("198.51.100."))));
		assertEquals(List.of("d1001"), ids(all(text("192.0.2.55"))));
		assertEquals(List.of("a11"), ids(all(text("198.51.100.9"))));
		assertEquals(List.of("u3001"), ids(all(text("198.51.100.4"))));
		assertEquals(List.of("f2001"), ids(all(text("matters/100"))));
		// The username the row kept, not the person's name now: the person filter finds a person
		assertEquals(List.of("u3002"), ids(all(text("dvance"))));
		assertEquals(List.of(), ids(all(text("dorothy"))));
		assertEquals(List.of("s100"), ids(all(text("garage_"))));
		assertEquals(List.of("s150"), ids(all(text("erin"))));
		assertEquals(List.of(), ids(all(text("nothing like it"))));
	}

	@Test
	public void testADateRangeBecomesAnIdRangeAndKeepsAFullScansRows() throws Exception {
		empty();
		Instant start = Instant.parse("2026-11-20T00:00:00Z");
		for (int i = 0; i < 100; i++) {
			Instant when = start.plus(Duration.ofHours(i));
			// Some rows up to three quarters of an hour out of step with their ids
			if (i % 7 == 3)
				when = when.minus(Duration.ofMinutes(45));
			if (i % 11 == 5)
				when = when.plus(Duration.ofMinutes(40));
			history("ld_history", 5000 + i, when, 1L, "admin", "event.viewed");
			adminRow(100 + i, when, 1L, "admin", "client.renamed", "client", 1L, "C", null);
			storage(100 + 50L * i, when, 1L, 1L, "admin", "key-shown");
		}
		LocalDate from = LocalDate.parse("2026-11-21");
		LocalDate to = LocalDate.parse("2026-11-22");

		AuditFeed.Prepared prepared = feed.prepare(dates(from, to, ZoneOffset.UTC).build(), admin);

		// The id range: from the first row of the first day less an hour (i = 23) to the last before the last day's
		// end plus an hour, which is i = 73, 45 minutes early
		long[] range = prepared.idRange("d");
		assertEquals(5023, range[0]);
		assertEquals(5073, range[1]);
		long[] admins = prepared.idRange("a");
		assertEquals(123, admins[0]);
		assertEquals(173, admins[1]);
		long[] stored = prepared.idRange("s");
		assertEquals(100 + 50 * 23, stored[0]);
		assertEquals(100 + 50 * 73, stored[1]);

		Timestamp low = Timestamp.from(Instant.parse("2026-11-21T00:00:00Z"));
		Timestamp high = Timestamp.from(Instant.parse("2026-11-23T00:00:00Z"));
		List<String> scanned = new ArrayList<>();
		scanned.addAll(jdbc.queryForList("select 'd' || ld_id from ld_history where ld_date >= ? and ld_date < ?",
				String.class, low, high));
		scanned.addAll(jdbc.queryForList("select 'a' || hd_id from hd_admin_event where hd_date >= ? and hd_date < ?",
				String.class, low, high));
		scanned.addAll(jdbc.queryForList(
				"select 's' || ld_id from hd_storage_event where hd_date >= ? and hd_date < ?", String.class, low, high));
		List<AuditRow> listed = all(dates(from, to, ZoneOffset.UTC));
		assertEquals(Set.copyOf(scanned), Set.copyOf(ids(listed)));
		assertEquals(scanned.size(), listed.size());
		assertTrue(listed.size() > 100);

		// From a day on, and to a day
		assertEquals(jdbc.queryForObject("select count(*) from ld_history where ld_date >= ?", Long.class, low),
				Long.valueOf(all(dates(from, null, ZoneOffset.UTC).kinds("document")).size()));
		assertEquals(jdbc.queryForObject("select count(*) from ld_history where ld_date < ?", Long.class, high),
				Long.valueOf(all(dates(null, to, ZoneOffset.UTC).kinds("document")).size()));
		// Days before the first row, or after the last, keep none, at once
		AuditPage before = feed.page(dates(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"),
				ZoneOffset.UTC).build(), admin);
		assertEquals(List.of(), before.items());
		assertNull(before.nextCursor());
		AuditPage after = feed.page(dates(LocalDate.parse("2027-01-01"), null, ZoneOffset.UTC).build(), admin);
		assertEquals(List.of(), after.items());
		assertNull(after.nextCursor());
		// A last day before the first keeps none
		assertEquals(List.of(), all(dates(to, from, ZoneOffset.UTC)));
	}

	@Test
	public void testTheDaysAreTheZones() throws Exception {
		empty();
		history("ld_history", 1001, Instant.parse("2026-11-20T23:30:00Z"), 1L, "admin", "event.viewed");
		history("ld_history", 1002, Instant.parse("2026-11-21T00:30:00Z"), 1L, "admin", "event.viewed");
		LocalDate day = LocalDate.parse("2026-11-21");

		assertEquals(List.of("d1002"), ids(all(dates(day, day, ZoneOffset.UTC))));
		assertEquals(List.of(), ids(all(dates(day, day, ZoneId.of("America/New_York")))));
		assertEquals(List.of("d1002", "d1001"), ids(all(dates(day, day, ZoneId.of("Asia/Tokyo")))));
	}

	@Test
	public void testAPageReadsOnlySoFarIntoEachSource() throws Exception {
		empty();
		feed.scanPerSource = 10;
		history("ld_history", 1000, T0, 1L, "admin", "event.viewed", "wanted.txt", null, null);
		for (int i = 1; i <= 35; i++)
			history("ld_history", 1000 + i, T0.plusSeconds(i), 1L, "admin", "event.viewed", "other.txt", null, null);

		AuditQuery query = text("wanted").build();
		List<Integer> sizes = new ArrayList<>();
		AuditPage page;
		do {
			page = feed.page(query, admin);
			sizes.add(page.items().size());
			if (page.nextCursor() != null)
				query = query.at(page.nextCursor());
		} while (page.nextCursor() != null);

		// Ten rows a page: three empty pages, each with a cursor, then the one row, at the end
		assertEquals(List.of(0, 0, 0, 1), sizes);

		// Unfiltered, a page reads only as many rows as it holds
		feed.scanPerSource = AuditFeed.SCAN_PER_SOURCE;
		assertEquals(List.of("d1035", "d1034", "d1033"), ids(feed.page(AuditQuery.all(3), admin).items()));
	}

	@Test
	public void testShortReadsKeepTheOrderAcrossSources() throws Exception {
		empty();
		long minute = 0;
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			expected.add(0, "d" + history("ld_history", 1000 + i, T0.plusSeconds(60 * minute++), 1L, "tom",
					"event.viewed"));
			expected.add(0, "u" + history("ld_user_history", 3000 + i, T0.plusSeconds(60 * minute++), 1L, "tom",
					"event.user.login"));
			if (i % 3 == 0)
				expected.add(0, "a" + adminRow(10 + i, T0.plusSeconds(60 * minute++), 1L, "tom", "client.renamed",
						"client", 1L, "C", null));
		}
		feed.scanPerSource = 2;

		List<AuditRow> rows = all(text("tom"));

		assertEquals(expected, ids(rows));
	}

	@Test
	public void testADocNumbersRowsComeInOnePageThroughItsIndex() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Document old = fx.upload(child(matter.getFolderId(), "Documents"), "old-letter.txt", "old", admin);
		HealdanFixtures.awaitVersionSaves();
		DocumentManager.get().rename(old.getId(), "old-letter-2.txt", fx.documentHistory(admin));
		HealdanFixtures.awaitVersionSaves();
		List<Long> its = jdbc.queryForList("select ld_id from ld_history where ld_docid = ? order by ld_id desc",
				Long.class, old.getId());
		assertTrue(its.size() >= 2);
		// Many more rows since, of other documents, than a bounded read looks through
		feed.scanPerSource = 10;
		unrelated("ld_history", 60);

		AuditPage page = feed.page(new AuditQuery(null, null, null, docNumber(old), null, null, null, null, 50, null),
				admin);

		assertEquals(its.stream().map(id -> "d" + id).toList(), ids(page.items()));
		assertNull(page.nextCursor());

		// The same document found by its file name, which no index serves: pages a window at a time
		AuditQuery byName = text("old-letter").build();
		AuditPage first = feed.page(byName, admin);
		assertEquals(List.of(), first.items());
		assertNotNull(first.nextCursor());
		assertTrue(ids(all(byName)).containsAll(its.stream().map(id -> "d" + id).toList()));
	}

	@Test
	public void testAPersonsRowsComeInOnePageThroughTheIndexes() throws Exception {
		User alice = fx.user("alice");
		empty();
		// Hers, old: a document's, the account's, one she made, one about her, a folder's and a key's
		history("ld_history", 1001, T0.plusSeconds(1), alice.getId(), "alice", "event.viewed");
		history("ld_user_history", 3001, T0.plusSeconds(2), alice.getId(), "alice", "event.user.login");
		adminRow(11, T0.plusSeconds(3), alice.getId(), "alice", "client.renamed", "client", 1L, "C", null);
		adminRow(12, T0.plusSeconds(4), 1L, "admin", "user.disabled", "user", alice.getId(), "alice", null);
		history("ld_folder_history", 2001, T0.plusSeconds(5), alice.getId(), "alice", "event.folder.created");
		storage(100, T0.plusSeconds(6), 1L, alice.getId(), "alice", "key-shown");
		// Many more since, by others, than a bounded read looks through, in each source an index serves
		feed.scanPerSource = 10;
		unrelated("ld_history", 60);
		unrelated("ld_user_history", 60);
		unrelated("hd_admin_event", 60);
		// Fewer than that in the two it doesn't: a person's folder rows and key rows are read a window at a time
		unrelated("ld_folder_history", 5);
		unrelated("hd_storage_event", 5);

		AuditQuery query = new AuditQuery(null, alice.getId(), null, null, null, null, null, null, 50, null);
		AuditPage page = feed.page(query, admin);

		assertEquals(List.of("s100", "f2001", "a12", "a11", "u3001", "d1001"), ids(page.items()));
		assertNull(page.nextCursor());

		// Where a source no index serves holds more than its window, a page waits for it, and the order holds
		unrelated("ld_folder_history", 60);
		AuditPage short1 = feed.page(query, admin);
		assertTrue(short1.items().size() < 6);
		assertNotNull(short1.nextCursor());
		List<AuditRow> rows = all(query);
		assertEquals(List.of("s100", "f2001", "a12", "a11", "u3001", "d1001"), ids(rows));
	}

	@Test
	public void testADocNumberAndAPersonAreReadThroughTheirIndexes() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Document letter = fx.upload(child(matter.getFolderId(), "Documents"), "letter.txt", "x", admin);
		HealdanFixtures.awaitVersionSaves();
		// Statistics that show each document and person holds few of the rows
		jdbc.update("insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_docid)"
				+ " select 100000 + n, current_timestamp, current_timestamp, 1, 0, 1, 0, current_timestamp,"
				+ " 10000 + n % 2000, 'someone', 'event.viewed', 10000 + n % 2000 from generate_series(1, 20000) n");
		jdbc.execute("analyze ld_history");
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));

		for (AuditQuery query : List.of(
				new AuditQuery(null, null, null, docNumber(letter), null, null, null, null, 50, null),
				new AuditQuery(null, 10007L, null, null, null, null, null, null, 50, null))) {
			AuditFeed.Sql sql = feed.prepare(query, admin).firstPageSql("d");
			String plan = transaction.execute(status -> {
				jdbc.execute("set local enable_seqscan = off");
				return String.join("\n", jdbc.query("explain (costs off) " + sql.text(),
						ps -> AuditFeed.bind(ps, sql.params()), (rs, n) -> rs.getString(1)));
			});
			String index = query.docNumber() != null ? "ld_hist_docid" : "ld_hist_uid";
			assertTrue(plan, plan.contains(index));
		}
	}

	@Test
	public void testTheUnfilteredPageUsesEachTablesPrimaryKey() throws Exception {
		// Rows enough for statistics, and sequential scans off: the test is that the plan can read the primary key
		// backwards, with no sort, not which plan a near-empty table would get
		for (String table : TABLES) {
			String insert = switch (table) {
			case "hd_admin_event" -> "insert into hd_admin_event (hd_id, hd_tenantid, hd_date, hd_userid, hd_username,"
					+ " hd_area, hd_action, hd_targetkind) select 100000 + n, 1, current_timestamp, 1, 'admin',"
					+ " 'MATTERS', 'client.renamed', 'client' from generate_series(1, 2000) n";
			case "hd_storage_event" -> "insert into hd_storage_event (ld_id, ld_lastmodified, ld_creation,"
					+ " ld_recordversion, ld_deleted, ld_tenantid, hd_backendid, hd_event, hd_date, hd_userid,"
					+ " hd_username) select 100000 + n, current_timestamp, current_timestamp, 1, 0, 1, 1, 'key-shown',"
					+ " current_timestamp, 1, 'admin' from generate_series(1, 2000) n";
			default -> "insert into " + table + " (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
					+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event) select 100000 + n,"
					+ " current_timestamp, current_timestamp, 1, 0, 1, 0, current_timestamp, 1, 'admin', 'event.x'"
					+ " from generate_series(1, 2000) n";
			};
			jdbc.update(insert);
			jdbc.execute("analyze " + table);
		}
		AuditFeed.Prepared prepared = feed.prepare(AuditQuery.all(50), admin);
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));

		Map<String, String> tables = Map.of("d", "ld_history", "f", "ld_folder_history", "u", "ld_user_history", "a",
				"hd_admin_event", "s", "hd_storage_event");
		tables.forEach((source, table) -> {
			AuditFeed.Sql sql = prepared.firstPageSql(source);
			String plan = transaction.execute(status -> {
				jdbc.execute("set local enable_seqscan = off");
				return String.join("\n", jdbc.queryForList("explain (costs off) " + sql.text(), String.class,
						sql.params()));
			});
			String key = jdbc.queryForObject("select c.conname from pg_constraint c join pg_class t on t.oid ="
					+ " c.conrelid where t.relname = ? and c.contype = 'p'", String.class, table);
			assertTrue(table + ":\n" + plan, plan.contains("Index Scan Backward using " + key + " on " + table));
			assertFalse(table + ":\n" + plan, plan.contains("Sort"));
		});
	}

	@Test
	public void testWhoIsReadNow() throws Exception {
		User erin = fx.user("erin");
		User blank = fx.user("blank");
		empty();
		jdbc.update("update ld_user set ld_firstname = ' ', ld_name = null where ld_id = ?", blank.getId());
		history("ld_history", 1001, T0.plusSeconds(1), erin.getId(), "erin", "event.viewed");
		history("ld_history", 1002, T0.plusSeconds(2), 987654L, "ghost", "event.viewed");
		history("ld_history", 1003, T0.plusSeconds(3), null, null, "event.viewed");
		history("ld_history", 1004, T0.plusSeconds(4), blank.getId(), "blank", "event.viewed");
		adminRow(11, T0.plusSeconds(5), erin.getId(), "erin", "client.renamed", "client", 1L, "C", null);
		// Renamed after the rows were written: the rows name her as she is now
		jdbc.update("update ld_user set ld_firstname = 'Erin', ld_name = 'Stone' where ld_id = ?", erin.getId());

		List<AuditRow> rows = all(AuditQuery.all(50));

		assertEquals("Erin Stone", only(rows, row -> row.id().equals("d1001")).who());
		assertEquals("Erin Stone", only(rows, row -> row.id().equals("a11")).who());
		assertEquals("ghost", only(rows, row -> row.id().equals("d1002")).who());
		assertNull(only(rows, row -> row.id().equals("d1003")).who());
		assertEquals("blank", only(rows, row -> row.id().equals("d1004")).who());
	}

	@Test
	public void testASecretChangeHasNoValue() throws Exception {
		AdminEvents.get().record(new AdminEvent(AdminArea.STORAGE, "backend.changed", "backend", 5L, "Wasabi EU",
				Map.of("secret", Change.secret(), "name", new Change("Wasabi", "Wasabi EU"))), admin);

		AuditRow row = only(all(kinds("administration")), r -> r.event().equals("backend.changed"));

		assertTrue(row.changes().get("secret").isSecret());
		assertNull(row.changes().get("secret").before());
		assertNull(row.changes().get("secret").after());
		assertEquals(new Change("Wasabi", "Wasabi EU"), row.changes().get("name"));
		assertEquals(List.of("name", "secret"), List.copyOf(row.changes().keySet()));
	}

	@Test
	public void testADetailThatIsntJsonHasNoChanges() throws Exception {
		empty();
		adminRow(11, T0, 1L, "admin", "client.renamed", "client", 1L, "C", null, "{not json");
		adminRow(12, T0.plusSeconds(1), 1L, "admin", "client.renamed", "client", 1L, "C", null, "[1, 2]");
		adminRow(13, T0.plusSeconds(2), 1L, "admin", "client.renamed", "client", 1L, "C", null,
				"{\"number\":{\"before\":1,\"after\":2},\"odd\":\"x\"}");

		List<AuditRow> rows;
		try (LogCapture log = LogCapture.of(AuditFeed.class, Level.WARN)) {
			rows = all(AuditQuery.all(50));
			assertEquals(log.lines().toString(), 2, log.lines().size());
			for (String line : log.lines())
				assertTrue(line, line.startsWith("WARN ") && !line.contains("not json"));
		}

		assertNull(only(rows, row -> row.id().equals("a11")).changes());
		assertNull(only(rows, row -> row.id().equals("a12")).changes());
		Map<String, Change> changes = only(rows, row -> row.id().equals("a13")).changes();
		assertEquals(new Change("1", "2"), changes.get("number"));
		assertEquals(new Change(null, null), changes.get("odd"));
		assertFalse(changes.get("odd").isSecret());
	}

	@Test
	public void testARecoveryKeyShownThroughTheAdminAppIsOneRow() throws Exception {
		User sam = fx.user("sam", "admin");
		empty();
		// Through the Admin app: the storage record's row, then the administration record's, seconds apart
		storage(100, T0, 7L, admin.getId(), "admin", "key-shown");
		adminRow(11, T0.plusSeconds(3), admin.getId(), "admin", "backend.recovery-key-shown", "backend", 7L,
				"Wasabi EU", "192.0.2.9");
		storage(150, T0.plusSeconds(60), 7L, admin.getId(), "admin", "key-entered");
		adminRow(12, T0.plusSeconds(60), admin.getId(), "admin", "backend.recovery-key-entered", "backend", 7L,
				"Wasabi EU", "192.0.2.9");
		// From the Classic Interface: no twin, so listed; nor are these twins
		storage(200, T0.plusSeconds(120), 7L, admin.getId(), "admin", "key-shown");
		adminRow(13, T0.plusSeconds(131), admin.getId(), "admin", "backend.recovery-key-shown", "backend", 7L,
				"Wasabi EU", null);
		storage(250, T0.plusSeconds(200), 7L, sam.getId(), "sam", "key-shown");
		adminRow(14, T0.plusSeconds(201), admin.getId(), "admin", "backend.recovery-key-shown", "backend", 7L,
				"Wasabi EU", null);
		storage(300, T0.plusSeconds(300), 7L, admin.getId(), "admin", "key-entered");
		adminRow(15, T0.plusSeconds(301), admin.getId(), "admin", "backend.recovery-key-shown", "backend", 7L,
				"Wasabi EU", null);
		storage(350, T0.plusSeconds(400), 8L, admin.getId(), "admin", "key-shown");
		adminRow(16, T0.plusSeconds(401), admin.getId(), "admin", "backend.recovery-key-shown", "backend", 7L,
				"Wasabi EU", null);

		assertEquals(List.of("a16", "s350", "a15", "s300", "a14", "s250", "a13", "s200", "a12", "a11"),
				ids(all(AuditQuery.all(50))));
	}

	@Test
	public void testOnlyTheActorsTenant() throws Exception {
		empty();
		history("ld_history", 1001, T0.plusSeconds(1), 1L, "admin", "event.viewed");
		history("ld_history", 1002, T0.plusSeconds(2), 1L, "admin", "event.viewed");
		jdbc.update("update ld_history set ld_tenantid = 2 where ld_id = 1002");
		history("ld_folder_history", 2001, T0.plusSeconds(3), 1L, "admin", "event.folder.created");
		jdbc.update("update ld_folder_history set ld_tenantid = 2");
		history("ld_user_history", 3001, T0.plusSeconds(4), 1L, "admin", "event.user.login");
		jdbc.update("update ld_user_history set ld_tenantid = 2");
		adminRow(11, T0.plusSeconds(5), 1L, "admin", "client.renamed", "client", 1L, "C", null);
		jdbc.update("update hd_admin_event set hd_tenantid = 2");
		storage(100, T0.plusSeconds(6), 1L, 1L, "admin", "key-shown");
		jdbc.update("update hd_storage_event set ld_tenantid = 2");

		assertEquals(List.of("d1001"), ids(all(AuditQuery.all(50))));
		assertEquals(List.of("d1001"), ids(all(text("admin"))));
		assertEquals(List.of("d1001"), ids(all(person(1L))));
		feed.scanPerSource = 1;
		assertEquals(List.of("d1001"), ids(all(text("admin"))));
	}

	@Test
	public void testOnlyAnAdministratorReads() throws Exception {
		User clerk = fx.user("clerk");

		HealdanRuleException page = assertThrows(HealdanRuleException.class,
				() -> feed.page(AuditQuery.all(10), clerk));
		assertEquals(AuditFeed.ONLY_ADMINS, page.getMessage());
		HealdanRuleException each = assertThrows(HealdanRuleException.class,
				() -> feed.forEach(AuditQuery.all(10), clerk, 10, row -> fail()));
		assertEquals(AuditFeed.ONLY_ADMINS, each.getMessage());
		assertThrows(HealdanRuleException.class, () -> feed.prepare(AuditQuery.all(10), null));
	}

	@Test
	public void testForEachStopsAtItsMost() throws Exception {
		empty();
		for (int i = 0; i < 8; i++)
			history("ld_history", 1000 + i, T0.plusSeconds(i), 1L, "admin", "event.viewed");
		List<String> given = new ArrayList<>();

		assertTrue(feed.forEach(AuditQuery.all(3), admin, 5, row -> given.add(row.id())));
		assertEquals(List.of("d1007", "d1006", "d1005", "d1004", "d1003"), given);

		given.clear();
		assertFalse(feed.forEach(AuditQuery.all(3), admin, 8, row -> given.add(row.id())));
		assertEquals(8, given.size());
		// Following short pages to the end
		feed.scanPerSource = 1;
		given.clear();
		assertFalse(feed.forEach(text("admin").build(), admin, 100, row -> given.add(row.id())));
		assertEquals(8, given.size());
	}

	@Test
	public void testADestroyedDocumentsAdministrationRowsAndTrashRecordRemain() throws Exception {
		Folder store = fx.store("Client Work");
		Matter matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", store, "Documents");
		Folder documents = child(matter.getFolderId(), "Documents");
		Document gone = fx.upload(documents, "gone.txt", "gone", admin);
		HealdanFixtures.awaitVersionSaves();
		Long docNumber = docNumber(gone);
		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "folder.properties-changed", "folder",
				documents.getId(), "Documents", Map.of("Note", new Change(null, "about gone.txt"))), admin);
		DocumentDAO.get().delete(gone.getId(), fx.documentHistory(admin));
		HealdanFixtures.awaitVersionSaves();
		assertFalse(all(docNumber(docNumber)).isEmpty());

		DocumentManager.get().destroyDocument(gone.getId(), fx.folderHistory(admin));
		HealdanFixtures.awaitVersionSaves();

		// The destroy takes the document's history; its store's deletes, written after it, name a document no row
		// holds now, and are listed with no doc #
		List<String> left = jdbc.queryForList("select ld_event from ld_history where ld_docid = ?", String.class,
				gone.getId());
		assertFalse(left.toString(), left.contains("event.stored"));
		assertFalse(left.toString(), left.contains("event.deleted"));
		List<Long> leftIds = jdbc.queryForList("select ld_id from ld_history where ld_docid = ?", Long.class,
				gone.getId());
		List<AuditRow> everything = all(AuditQuery.all(200));
		for (Long id : leftIds) {
			AuditRow row = only(everything, r -> r.id().equals("d" + id));
			assertNull(row.docNumber());
			assertFalse(row.detail(), row.detail() != null && row.detail().startsWith("# "));
		}
		List<AuditRow> rows = all(matter(matter.getId()));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("folder.properties-changed")));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("matter.created")));
		assertTrue(rows.stream().anyMatch(row -> row.event().equals("event.folder.document.destroyed")));
		assertFalse(rows.stream().anyMatch(row -> docNumber.equals(row.docNumber())));
		// The Trash's record of it stays
		assertTrue(jdbc.queryForObject("select count(*) from hd_trash_member where hd_objecttype = 'D'"
				+ " and hd_objectid = ?", Long.class, gone.getId()) > 0);
		assertEquals(List.of(), all(docNumber(docNumber)));
	}

	// What the tests write and read

	/** Writes rows of others, newer than the tests' own, with ids above theirs */
	private void unrelated(String table, int count) {
		long first = jdbc.queryForObject("select coalesce(max(" + (table.equals("hd_admin_event") ? "hd_id" : "ld_id")
				+ "), 0) + 1000 from " + table, Long.class);
		Instant later = Instant.now().plusSeconds(3600);
		for (int i = 0; i < count; i++) {
			Instant when = later.plusSeconds(i);
			switch (table) {
			case "hd_admin_event" -> adminRow(first + i, when, 987654L, "other", "client.renamed", "client", 1L,
					"Other", null);
			case "hd_storage_event" -> storage(first + 50L * i, when, 1L, 987654L, "other", "key-shown");
			default -> history(table, first + i, when, 987654L, "other", "event.viewed", "other.txt", null, null);
			}
		}
	}

	private void empty() {
		for (String table : TABLES)
			jdbc.update("delete from " + table);
	}

	/** Writes a history row, as CE writes it, with an exact id and time */
	private long history(String table, long id, Instant when, Long userId, String username, String event) {
		return history(table, id, when, userId, username, event, null, null, null);
	}

	private long history(String table, long id, Instant when, Long userId, String username, String event,
			String fileName, String path, String ip) {
		jdbc.update("insert into " + table + " (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_filename, ld_path, ld_ip)"
				+ " values (?, ?, ?, 1, 0, 1, 0, ?, ?, ?, ?, ?, ?, ?)", id, Timestamp.from(when), Timestamp.from(when),
				Timestamp.from(when), userId, username, event, fileName, path, ip);
		return id;
	}

	private long adminRow(long id, Instant when, long userId, String username, String action, String targetKind,
			Long targetId, String targetName, String address) {
		return adminRow(id, when, userId, username, action, targetKind, targetId, targetName, address, null);
	}

	private long adminRow(long id, Instant when, long userId, String username, String action, String targetKind,
			Long targetId, String targetName, String address, String detail) {
		jdbc.update("insert into hd_admin_event (hd_id, hd_tenantid, hd_date, hd_userid, hd_username, hd_address,"
				+ " hd_area, hd_action, hd_targetkind, hd_targetid, hd_targetname, hd_detail)"
				+ " values (?, 1, ?, ?, ?, ?, 'MATTERS', ?, ?, ?, ?, ?)", id, Timestamp.from(when), userId, username,
				address, action, targetKind, targetId, targetName, detail);
		return id;
	}

	/** Writes the storage record's row, as healdan-storage's StorageAdmin does, with an exact id and time */
	private long storage(long id, Instant when, long backendId, long userId, String username, String event) {
		jdbc.update("insert into hd_storage_event (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, hd_backendid, hd_event, hd_date, hd_userid, hd_username)"
				+ " values (?, ?, ?, 1, 0, 1, ?, ?, ?, ?, ?)", id, Timestamp.from(when), Timestamp.from(when), backendId,
				event, Timestamp.from(when), userId, username);
		return id;
	}

	private long backend(String name) {
		long id = jdbc.queryForObject("select coalesce(max(ld_id), 100) + 50 from hd_storage_backend", Long.class);
		jdbc.update("insert into hd_storage_backend (ld_id, ld_lastmodified, ld_creation, ld_recordversion,"
				+ " ld_deleted, ld_tenantid, hd_name, hd_kind, hd_pathstyle, hd_encrypted, hd_keyconfirmed)"
				+ " values (?, current_timestamp, current_timestamp, 1, 0, 1, ?, 's3', 0, 1, 1)", id, name);
		return id;
	}

	/** Signs in as CE's session does, writing the account's history */
	private static void signIn(User user, String address) throws Exception {
		Client client = new Client(null, address, "host");
		Device device = new Device();
		device.setBrowser("Safari");
		device.setBrowserVersion("18");
		device.setOperativeSystem("macOS");
		client.setDevice(device);
		UserHistoryDAO.get().createUserHistory(user, UserEvent.LOGIN, null, null, client);
	}

	private static Folder child(long parentId, String name) throws Exception {
		return FolderDAO.get().findByName(FolderDAO.get().findById(parentId), name, Tenant.DEFAULT_ID, true).get(0);
	}

	/** @return the document's doc #, read from its row, deleted or not */
	private Long docNumber(Document document) {
		return Long.valueOf(jdbc.queryForObject("select ld_customid from ld_document where ld_id = ?", String.class,
				document.getId()));
	}

	private static String fullName(User user) {
		return com.logicaldoc.healdan.admin.PersonNames.of(user);
	}

	/** @return every row the query keeps, following the cursor to the end */
	private List<AuditRow> all(AuditQuery query) throws Exception {
		List<AuditRow> rows = new ArrayList<>();
		AuditQuery next = query;
		for (int pages = 0; pages < 10_000; pages++) {
			AuditPage page = feed.page(next, admin);
			rows.addAll(page.items());
			if (page.nextCursor() == null)
				return rows;
			next = next.at(page.nextCursor());
		}
		throw new AssertionError("The pages didn't end");
	}

	private List<AuditRow> all(Query query) throws Exception {
		return all(query.build());
	}

	private static List<String> ids(List<AuditRow> rows) {
		return rows.stream().map(AuditRow::id).toList();
	}

	private static AuditRow only(List<AuditRow> rows, java.util.function.Predicate<AuditRow> which) {
		List<AuditRow> found = rows.stream().filter(which).toList();
		assertEquals(rows.toString(), 1, found.size());
		return found.get(0);
	}

	private static void assertNewestFirst(List<AuditRow> rows) {
		for (int i = 1; i < rows.size(); i++)
			assertFalse(rows.get(i - 1) + " before " + rows.get(i), rows.get(i - 1).when().isBefore(rows.get(i).when()));
	}

	private static AuditQuery kinds(String... kinds) {
		return new Query().kinds(kinds).build();
	}

	private static AuditQuery person(long userId) {
		Query query = new Query();
		query.userId = userId;
		return query.build();
	}

	private static AuditQuery matter(long matterId) {
		Query query = new Query();
		query.matterId = matterId;
		return query.build();
	}

	private static AuditQuery docNumber(long docNumber) {
		Query query = new Query();
		query.docNumber = docNumber;
		return query.build();
	}

	private static Query text(String text) {
		Query query = new Query();
		query.text = text;
		return query;
	}

	private static Query dates(LocalDate from, LocalDate to, ZoneId zone) {
		Query query = new Query();
		query.from = from;
		query.to = to;
		query.zone = zone;
		return query;
	}

	/** An {@link AuditQuery} put together a member at a time */
	private static class Query {

		Set<String> kinds;

		Long userId;

		Long matterId;

		Long docNumber;

		LocalDate from;

		LocalDate to;

		ZoneId zone;

		String text;

		int limit = 4;

		Query kinds(String... kinds) {
			this.kinds = Set.of(kinds);
			return this;
		}

		AuditQuery build() {
			return new AuditQuery(kinds, userId, matterId, docNumber, from, to, zone, text, limit, null);
		}
	}
}
