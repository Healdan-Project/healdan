// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.matter.Matter;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The search index's history (Admin app spec section 8.9; ruling P6-R13): the tenant's index events, newest first, by
 * a cursor, each page looking through a bounded window of {@code ld_history} by its key, under a custom plan and the
 * generic one alike.
 */
public class IndexHistoryTest extends AbstractHealdanTestCase {

	private static final String INDEXED = "event.indexed";

	private static final String ERROR = "event.indexed.error";

	private final HealdanFixtures fx = new HealdanFixtures();

	private final Instant t0 = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

	private JdbcTemplate jdbc;

	private IndexHistory history;

	private User boss;

	private Matter matter;

	private Folder folder;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		history = IndexHistory.get();
		boss = fx.user("boss", "admin");
		boss.setFirstName("Ann");
		boss.setName("Lee");
		UserDAO.get().store(boss);
		matter = fx.matter(fx.client(1001, "Ashcombe"), 1, "Charter Dispute", fx.store("Client Work"), "Documents");
		folder = FolderDAO.get().findByName(FolderDAO.get().findById(matter.getFolderId()), "Documents",
				Tenant.DEFAULT_ID, true).get(0);
	}

	@After
	public void putBack() {
		history.scan = IndexHistory.SCAN;
		history.sent = sql -> {
		};
	}

	private Document document(String name) throws Exception {
		Document document = fx.upload(folder, name, "text of " + name, fx.admin());
		HealdanFixtures.awaitVersionSaves();
		return document;
	}

	/** Writes a history row with an exact id and time, as CE writes it */
	private void row(long id, Instant when, Long userId, String username, String event, long docId, String fileName,
			String comment, int deleted, long tenantId) {
		jdbc.update("insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_docid, ld_filename,"
				+ " ld_comment) values (?, ?, ?, 1, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?)", id, Timestamp.from(when),
				Timestamp.from(when), deleted, tenantId, Timestamp.from(when), userId, username, event, docId,
				fileName, comment);
	}

	private void row(long id, Instant when, Long userId, String username, String event, long docId, String comment) {
		row(id, when, userId, username, event, docId, "kept.txt", comment, 0, Tenant.DEFAULT_ID);
	}

	private static List<Long> ids(List<IndexEvent> events) {
		return events.stream().map(IndexEvent::id).toList();
	}

	/** @return every event, page by page */
	private List<IndexEvent> all(int limit) throws Exception {
		List<IndexEvent> all = new ArrayList<>();
		IndexHistoryCursor cursor = IndexHistoryCursor.START;
		for (int pages = 0; cursor != null; pages++) {
			assertTrue("too many pages", pages < 1000);
			IndexHistoryPage page = history.page(cursor, limit, boss);
			all.addAll(page.items());
			assertEquals(page.nextCursor() == null, page.readBackTo() == null);
			cursor = page.nextCursor();
		}
		return all;
	}

	@Test
	public void testTheIndexEventsNewestFirst() throws Exception {
		Document letter = document("letter.txt");
		Document other = document("other.txt");
		jdbc.update("delete from ld_document_ext where ld_docid = ? and ld_name in ('client', 'matter')",
				other.getId());
		jdbc.update("delete from ld_history");
		row(1001, t0, boss.getId(), "Someone Then", INDEXED, letter.getId(), "Notes on the kestrel and its nest");
		row(1002, t0.plusSeconds(1), null, "", ERROR, other.getId(), "The file can't be read");
		// Not an index event, a row marked deleted and another tenant's: none of them
		row(1003, t0.plusSeconds(2), boss.getId(), "Ann Lee", "event.viewed", letter.getId(), null);
		row(1004, t0.plusSeconds(3), boss.getId(), "Ann Lee", ERROR, letter.getId(), "kept.txt", "Gone", 1,
				Tenant.DEFAULT_ID);
		row(1005, t0.plusSeconds(4), boss.getId(), "Ann Lee", ERROR, letter.getId(), "kept.txt", "Elsewhere", 0,
				Tenant.DEFAULT_ID + 1000);
		// An account the tenant no longer has: the name the row kept; a document no longer kept: the name too
		row(1006, t0.plusSeconds(5), 987654L, "Former Clerk", ERROR, 876543L, "lost.txt", "Encrypted", 0,
				Tenant.DEFAULT_ID);

		IndexHistoryPage page = history.page(IndexHistoryCursor.START, 50, boss);

		assertEquals(List.of(1006L, 1002L, 1001L), ids(page.items()));
		assertNull(page.nextCursor());
		assertNull(page.readBackTo());

		IndexEvent lost = page.items().get(0);
		assertTrue(lost.failed());
		assertEquals("Encrypted", lost.reason());
		assertEquals("Former Clerk", lost.who());
		assertEquals(876543L, lost.docId());
		assertNull(lost.docNumber());
		assertEquals("lost.txt", lost.fileName());
		assertNull(lost.matter());

		IndexEvent failed = page.items().get(1);
		assertEquals("The file can't be read", failed.reason());
		assertNull(failed.who());
		assertEquals(other.getCustomId(), failed.docNumber());
		assertEquals("other.txt", failed.fileName());
		assertNull(failed.matter());

		IndexEvent indexed = page.items().get(2);
		assertFalse(indexed.failed());
		// An indexed event's comment is the start of the document's text: not given
		assertNull(indexed.reason());
		// The person's name as it is now
		assertEquals("Ann Lee", indexed.who());
		assertEquals(t0, indexed.when());
		assertEquals(letter.getCustomId(), indexed.docNumber());
		assertEquals("letter.txt", indexed.fileName());
		assertEquals(new FailedDocument.Matter(matter.getId(), "1001.1", "Charter Dispute"), indexed.matter());

		// Paged by the cursor
		IndexHistoryPage first = history.page(IndexHistoryCursor.START, 2, boss);
		assertEquals(List.of(1006L, 1002L), ids(first.items()));
		assertEquals(new IndexHistoryCursor(1002), first.nextCursor());
		assertEquals(t0.plusSeconds(1), first.readBackTo());
		assertEquals(List.of(1001L), ids(history.page(first.nextCursor(), 2, boss).items()));
	}

	// With more rows than a window, a page looks through a window and may be short, even empty, with a cursor
	@Test
	public void testAPageLooksThroughAWindow() throws Exception {
		Document letter = document("letter.txt");
		jdbc.update("delete from ld_history");
		history.scan = 10;
		List<Long> events = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			boolean index = i % 30 == 0 || i == 99;
			row(1000 + i, t0.plusSeconds(i), boss.getId(), "Ann Lee", index ? (i % 60 == 0 ? INDEXED : ERROR)
					: "event.viewed", letter.getId(), "x");
			if (index)
				events.add(0, 1000L + i);
		}

		IndexHistoryPage first = history.page(IndexHistoryCursor.START, 50, boss);
		// The newest ten rows, of which two are index events; the window's end is where the next starts
		assertEquals(List.of(1099L, 1090L), ids(first.items()));
		assertEquals(new IndexHistoryCursor(1090), first.nextCursor());
		assertEquals(t0.plusSeconds(90), first.readBackTo());
		IndexHistoryPage empty = history.page(first.nextCursor(), 50, boss);
		assertEquals(List.of(), empty.items());
		assertEquals(new IndexHistoryCursor(1080), empty.nextCursor());

		// Page by page, every event once, newest first
		assertEquals(events, ids(all(50)));
		assertEquals(events, ids(all(1)));
	}

	@Test
	public void testOnlyAnAdministratorAndAPageOfOneToTwoHundred() throws Exception {
		User clerk = fx.user("clerk");
		assertEquals(IndexReview.ONLY_ADMINS, assertThrows(HealdanRuleException.class,
				() -> history.page(IndexHistoryCursor.START, 50, clerk)).getMessage());
		assertThrows(IllegalArgumentException.class, () -> history.page(IndexHistoryCursor.START, 0, boss));
		assertThrows(IllegalArgumentException.class, () -> history.page(IndexHistoryCursor.START, 201, boss));
		history.page(IndexHistoryCursor.START, 200, boss);
	}

	@Test
	public void testTheCursor() {
		for (long place : List.of(1L, 1002L, Long.MAX_VALUE)) {
			IndexHistoryCursor cursor = new IndexHistoryCursor(place);
			assertEquals(cursor, IndexHistoryCursor.parse(cursor.text()));
		}
		assertEquals("aDEwMDI", new IndexHistoryCursor(1002).text());
		for (String text : Arrays.asList(null, "", "aDA", "aDAxMDAy", "aA", "h1002", "aDEwMDI=", "aDEwMDI+",
				"aDkyMjMzNzIwMzY4NTQ3NzU4MDg", "x".repeat(29)))
			assertThrows(String.valueOf(text), IllegalArgumentException.class, () -> IndexHistoryCursor.parse(text));
		assertThrows(IllegalArgumentException.class, () -> new IndexHistoryCursor(0));
	}

	@Test
	public void testEveryStatementKeepsItsBoundUnderACustomPlanAndAGenericOne() throws Exception {
		Document letter = document("letter.txt");
		jdbc.update("delete from ld_history");
		// 40,000 rows, each about as wide as a real one; the index events (1%) are the oldest, as on a server whose
		// documents were indexed once, long ago, and have been read since
		jdbc.update("insert into ld_history (ld_id, ld_lastmodified, ld_creation, ld_recordversion, ld_deleted,"
				+ " ld_tenantid, ld_notified, ld_date, ld_userid, ld_username, ld_event, ld_docid, ld_folderid,"
				+ " ld_filename, ld_path, ld_comment, ld_sessionid, ld_ip, ld_device)"
				+ " select 1000000 + n, current_timestamp, current_timestamp, 1, 0, 1, 0,"
				+ " timestamp '2024-01-01 00:00:00' + n * interval '1 minute', 20000 + n % 40, 'someone',"
				+ " case when n <= 400 then case when n % 2 = 0 then 'event.indexed' else 'event.indexed.error' end"
				+ " else 'event.viewed' end, case when n <= 400 then ? else 40000 + n % 20000 end, 30000 + n % 3000,"
				+ " 'Skeleton Argument for the Hearing ' || n || '.docx',"
				+ " '/Default/Client Work/1001.' || (n % 1000) || ' Charter Dispute with a Long Name/Documents',"
				+ " 'Saved from the Healdan Helper on a laptop, version ' || n, 'session-' || md5(cast(n as text)),"
				+ " '192.0.2.' || (n % 250), 'Safari v18 on macOS (computer)' from generate_series(1, 40000) n",
				letter.getId());
		jdbc.execute("analyze ld_history");
		int window = 2_000;
		history.scan = window;

		for (IndexHistoryCursor cursor : List.of(IndexHistoryCursor.START, new IndexHistoryCursor(1_000_401))) {
			List<IndexHistory.Sql> statements = new ArrayList<>();
			history.sent = statements::add;
			IndexHistoryPage page = history.page(cursor, 50, boss);
			history.sent = sql -> {
			};
			assertEquals(2, statements.size());
			if (cursor == IndexHistoryCursor.START)
				assertEquals(List.of(), page.items());
			else
				assertEquals(50, page.items().size());
			assertNotNull(page.nextCursor());

			for (boolean generic : List.of(false, true))
				for (IndexHistory.Sql sql : statements) {
					List<String> plan = generic ? analysedGeneric(sql) : analysed(sql);
					String shown = (generic ? "generic: " : "custom: ") + sql.text() + "\n" + String.join("\n", plan);
					// A range of the key, never the event's index, which holds every index event below the place
					assertFalse(shown, shown.toLowerCase().contains("ld_hist_event"));
					assertTrue(shown, examined(plan) <= window);
				}
		}
	}

	/** @return the statement's plan as it ran, with each node's rows */
	private List<String> analysed(IndexHistory.Sql sql) {
		return jdbc.query("explain (analyze, costs off, timing off, summary off) " + sql.text(), ps -> {
			for (int i = 0; i < sql.params().length; i++)
				ps.setObject(i + 1, sql.params()[i]);
		}, (rs, n) -> rs.getString(1));
	}

	/**
	 * @return the statement's plan as it ran under a generic plan: prepared on one connection with
	 *         {@code plan_cache_mode = force_generic_plan}, then executed with the statement's values
	 */
	private List<String> analysedGeneric(IndexHistory.Sql sql) {
		StringBuilder numbered = new StringBuilder();
		int n = 0;
		for (char c : sql.text().toCharArray())
			if (c == '?')
				numbered.append('$').append(++n);
			else
				numbered.append(c);
		String values = Arrays.stream(sql.params())
				.map(value -> value instanceof String text ? "'" + text.replace("'", "''") + "'" : value.toString())
				.collect(Collectors.joining(", "));
		TransactionTemplate transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		int count = n;
		return transaction.execute(status -> {
			jdbc.execute("set local plan_cache_mode = force_generic_plan");
			jdbc.execute("prepare index_generic as " + numbered);
			try {
				return jdbc.queryForList("explain (analyze, costs off, timing off, summary off) execute index_generic"
						+ (count > 0 ? "(" + values + ")" : ""), String.class);
			} finally {
				jdbc.execute("deallocate index_generic");
			}
		});
	}

	/**
	 * @return how many rows the plan's scans looked at: each scan's rows and the rows its filter left out, but for a
	 *         bitmap index scan's entries, which its heap scan's rows count
	 */
	private static long examined(List<String> plan) {
		Pattern rows = Pattern.compile("actual rows=([0-9.]+) loops=([0-9]+)");
		long examined = 0;
		boolean scan = false;
		for (String line : plan) {
			boolean node = line.contains("->") || line.strip().startsWith("InitPlan") || !line.startsWith(" ");
			if (node) {
				scan = (line.contains(" Scan ") || line.contains("Scan on") || line.contains("Scan using"))
						&& !line.contains("Bitmap Index Scan");
				Matcher matcher = rows.matcher(line);
				if (scan && matcher.find())
					examined += Math.round(Double.parseDouble(matcher.group(1)) * Long.parseLong(matcher.group(2)));
			} else if (scan && line.contains("Rows Removed by Filter: ")) {
				examined += Long.parseLong(line.substring(line.indexOf("Rows Removed by Filter: ")
						+ "Rows Removed by Filter: ".length()).strip());
			}
		}
		return examined;
	}
}
