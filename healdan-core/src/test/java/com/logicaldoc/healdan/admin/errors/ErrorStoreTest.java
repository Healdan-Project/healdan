// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.Page;
import com.logicaldoc.healdan.admin.errors.ErrorQuery.Window;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * The errors the server logged, by fault (Admin app spec section 9.4): one row
 * per fingerprint in {@code hd_error} with a count per hour in
 * {@code hd_error_hour}, each value capped, resolved and reopened, listed for
 * the Errors screen's windows and pruned.
 */
public class ErrorStoreTest extends AbstractHealdanTestCase {

	private static final String PREVIEWS = "com.logicaldoc.healdan.preview.PreviewWorker";

	private static final String STORAGE = "com.logicaldoc.healdan.storage.mover.Mover";

	private static final String SERVER = "com.logicaldoc.healdan.api.DocumentResource";

	private final HealdanFixtures fx = new HealdanFixtures();

	private ErrorStore store;

	private JdbcTemplate jdbc;

	private User admin;

	@Before
	public void prepare() throws Exception {
		store = Context.get(ErrorStore.class);
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		admin = fx.admin();
	}

	@Test
	public void testTheBean() {
		assertSame(store, ErrorStore.get());
	}

	@Test
	public void testAddTwiceGivesOneRowAndCountsByTheHour() {
		Instant first = Instant.parse("2026-11-19T10:15:30.123Z");
		Instant second = Instant.parse("2026-11-19T11:05:00.456Z");
		store.add(error(first, PREVIEWS, "The preview of document 10 failed", "java.lang.IllegalStateException: a"));
		store.add(error(second, PREVIEWS, "The preview of document 12 failed", "java.lang.IllegalStateException: b"));
		store.add(error(second.plusSeconds(60), PREVIEWS, "The preview of document 12 failed",
				"java.lang.IllegalStateException: b"));

		assertEquals(1, count("select count(*) from hd_error"));
		ErrorGroup group = store.find(count("select hd_id from hd_error")).orElseThrow();
		assertEquals(3, group.total());
		assertEquals(3, group.times());
		assertEquals("ERROR", group.level());
		assertEquals(ErrorAreas.PREVIEWS, group.area());
		assertEquals(PREVIEWS, group.logger());
		// The latest message, stack and documents
		assertEquals("The preview of document 12 failed", group.message());
		assertEquals("java.lang.IllegalStateException: b", group.stack());
		assertEquals(List.of(12L), group.documents());
		// To the millisecond
		assertEquals(first, group.firstSeen());
		assertEquals(second.plusSeconds(60), group.lastSeen());
		assertFalse(group.resolved());

		assertEquals(List.of("2026-11-19T10:00:00Z 1", "2026-11-19T11:00:00Z 2"),
				jdbc.query("select hd_hour, hd_count from hd_error_hour where hd_errorid = ? order by hd_hour",
						(rs, n) -> rs.getTimestamp(1).toInstant() + " " + rs.getInt(2), group.id()));
	}

	@Test
	public void testAnOlderErrorWrittenLateLeavesLastSeen() {
		Instant now = Instant.parse("2026-11-19T10:15:30.123Z");
		store.add(error(now, PREVIEWS, "It failed", null));
		store.add(error(now.minusMillis(5), PREVIEWS, "It failed", null));

		ErrorGroup group = store.find(idOf(PREVIEWS, "It failed")).orElseThrow();
		assertEquals(now, group.lastSeen());
		assertEquals(2, group.total());
	}

	@Test
	public void testDifferentFaultsGiveTwoRows() {
		Instant now = Instant.now();
		store.add(error(now, PREVIEWS, "It failed", "java.lang.IllegalStateException"));
		store.add(error(now, PREVIEWS, "It failed", "java.io.IOException"));
		store.add(error(now, STORAGE, "It failed", null));

		assertEquals(3, count("select count(*) from hd_error"));
		assertEquals(3, count("select count(*) from hd_error_hour"));
	}

	@Test
	public void testCapsWhatItStores() {
		String documents = LongStream.rangeClosed(1_000_000_000L, 1_000_000_200L).mapToObj(id -> "document " + id)
				.collect(Collectors.joining(", "));
		store.add(error(Instant.now(), PREVIEWS, "m".repeat(5000), "s".repeat(20000)));
		store.add(error(Instant.now(), SERVER, documents, null));

		ErrorGroup capped = store.find(idOf(PREVIEWS, "m".repeat(5000))).orElseThrow();
		assertEquals(CapturedError.MESSAGE_LIMIT, capped.message().length());
		assertEquals(CapturedError.STACK_LIMIT, capped.stack().length());
		assertEquals(4000, CapturedError.MESSAGE_LIMIT);
		assertEquals(16000, CapturedError.STACK_LIMIT);

		// Whole ids only, as many as the column holds
		String stored = jdbc.queryForObject("select hd_documents from hd_error where hd_logger = ?", String.class,
				SERVER);
		assertTrue(stored.length() + " characters", stored.length() <= ErrorStore.DOCUMENTS_LIMIT);
		assertTrue(stored, stored.matches("\\d{10}(,\\d{10})*"));
		assertEquals(1000, ErrorStore.DOCUMENTS_LIMIT);
		ErrorGroup named = store.find(idOf(SERVER, documents)).orElseThrow();
		assertEquals(Long.valueOf(1_000_000_000L), named.documents().get(0));
		assertEquals(stored.split(",").length, named.documents().size());
	}

	@Test
	public void testTextTheDatabaseWouldRefuseIsTakenOut() {
		// PostgreSQL refuses a NUL in text; a logger's name has 255 characters
		String logger = "com.logicaldoc." + "x".repeat(300);
		store.add(error(Instant.now(), logger, "It\0 failed", "java.lang.IllegalStateException: \0"));

		ErrorGroup group = store.find(count("select hd_id from hd_error")).orElseThrow();
		assertEquals("It failed", group.message());
		assertEquals("java.lang.IllegalStateException: ", group.stack());
		assertEquals(logger.substring(0, 255), group.logger());
	}

	@Test
	public void testAResolvedFaultSeenAgainIsUnresolved() {
		store.add(error(Instant.now().minusSeconds(60), STORAGE, "Backend 'Cloud' can't be reached", null));
		long id = idOf(STORAGE, "Backend 'Cloud' can't be reached");

		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		store.resolve(id, admin);
		ErrorGroup resolved = store.find(id).orElseThrow();
		assertTrue(resolved.resolved());
		assertEquals("admin", resolved.resolvedBy());
		assertFalse(resolved.resolvedAt().isBefore(before));
		assertEquals(0, store.unresolvedSince(Instant.now().minus(Duration.ofDays(1))));

		store.add(error(Instant.now(), STORAGE, "Backend 'Other' can't be reached", null));
		ErrorGroup again = store.find(id).orElseThrow();
		assertFalse(again.resolved());
		assertNull(again.resolvedBy());
		assertNull(again.resolvedAt());
		assertEquals(2, again.total());
		assertEquals(1, store.unresolvedSince(Instant.now().minus(Duration.ofDays(1))));
	}

	@Test
	public void testReopen() {
		store.add(error(Instant.now(), STORAGE, "It failed", null));
		long id = idOf(STORAGE, "It failed");
		store.resolve(id, admin);

		store.reopen(id);

		ErrorGroup reopened = store.find(id).orElseThrow();
		assertFalse(reopened.resolved());
		assertNull(reopened.resolvedBy());
		assertNull(reopened.resolvedAt());
		assertTrue(store.find(id + 1000).isEmpty());
	}

	@Test
	public void testPrune() {
		Instant now = Instant.parse("2026-11-19T12:00:00Z");
		// Unresolved: its old hour goes, its recent one stays
		store.add(error(now.minus(Duration.ofDays(40)), PREVIEWS, "A", null));
		store.add(error(now.minus(Duration.ofDays(1)), PREVIEWS, "A", null));
		// Resolved and unseen for 100 days: it goes
		store.add(error(now.minus(Duration.ofDays(100)), PREVIEWS, "B", null));
		store.resolve(idOf(PREVIEWS, "B"), admin);
		// Resolved, but seen 60 days ago: it stays, without its hour
		store.add(error(now.minus(Duration.ofDays(60)), PREVIEWS, "C", null));
		store.resolve(idOf(PREVIEWS, "C"), admin);
		// Unresolved and unseen for 200 days: it stays until someone resolves it
		store.add(error(now.minus(Duration.ofDays(200)), PREVIEWS, "D", null));

		assertEquals(5, store.prune(now));

		assertEquals(List.of("A", "C", "D"),
				jdbc.queryForList("select hd_message from hd_error order by hd_message", String.class));
		assertEquals(List.of(now.minus(Duration.ofDays(1)).toString()),
				jdbc.query("select hd_hour from hd_error_hour", (rs, n) -> rs.getTimestamp(1).toInstant().toString()));
		assertEquals(0, store.prune(now));
	}

	@Test
	public void testList() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		store.add(error(now.minus(Duration.ofHours(3)), PREVIEWS, "The preview of document 5 failed", "stack"));
		store.add(error(now.minus(Duration.ofMinutes(31)), PREVIEWS, "The preview of document 5 failed", "stack"));
		store.add(error(now.minus(Duration.ofMinutes(30)), PREVIEWS, "The preview of document 5 failed", "stack"));
		store.add(error(now.minus(Duration.ofHours(5)), STORAGE, "Backend 'Cloud' isn't 100% there", null));
		store.resolve(idOf(STORAGE, "Backend 'Cloud' isn't 100% there"), admin);
		store.add(error(now.minus(Duration.ofDays(3)), SERVER, "The listing failed", null));

		assertEquals(List.of("The preview of document 5 failed"), messages(query(Window.HOUR), now));
		Page<ErrorGroup> day = store.list(query(Window.DAY), now);
		assertEquals(List.of("The preview of document 5 failed", "Backend 'Cloud' isn't 100% there"),
				day.items().stream().map(ErrorGroup::message).toList());
		assertEquals(2, day.total());
		assertEquals(List.of("The preview of document 5 failed", "Backend 'Cloud' isn't 100% there",
				"The listing failed"), messages(query(Window.WEEK), now));
		assertEquals(3, store.list(query(Window.MONTH), now).total());

		// Times counts the hours in the window; Total, every time
		ErrorGroup preview = store.list(query(Window.HOUR), now).items().get(0);
		assertEquals(2, preview.times());
		assertEquals(3, preview.total());
		assertEquals(3, day.items().get(0).times());
		assertEquals(List.of(5L), preview.documents());
		// The list leaves the stack out, which find has
		assertNull(preview.stack());
		assertEquals("stack", store.find(preview.id()).orElseThrow().stack());
		ErrorGroup backend = day.items().get(1);
		assertTrue(backend.resolved());
		assertEquals("admin", backend.resolvedBy());
		assertNotNull(backend.resolvedAt());

		// Filters
		assertEquals(List.of("Backend 'Cloud' isn't 100% there"),
				messages(new ErrorQuery(Window.DAY, "  BACKEND ", null, false, 0, 50), now));
		assertEquals(List.of("Backend 'Cloud' isn't 100% there"),
				messages(new ErrorQuery(Window.DAY, "100%", null, false, 0, 50), now));
		assertEquals(List.of(), messages(new ErrorQuery(Window.DAY, "_", null, false, 0, 50), now));
		assertEquals(List.of("Backend 'Cloud' isn't 100% there"),
				messages(new ErrorQuery(Window.WEEK, null, " Storage ", false, 0, 50), now));
		assertEquals(List.of("The preview of document 5 failed", "The listing failed"),
				messages(new ErrorQuery(Window.WEEK, null, null, true, 0, 50), now));
		// The logger's name matches too
		assertEquals(List.of("The listing failed"),
				messages(new ErrorQuery(Window.WEEK, "documentresource", "", false, 0, 50), now));

		// Paging
		Page<ErrorGroup> second = store.list(new ErrorQuery(Window.WEEK, null, null, false, 1, 1), now);
		assertEquals(List.of("Backend 'Cloud' isn't 100% there"),
				second.items().stream().map(ErrorGroup::message).toList());
		assertEquals(1, second.offset());
		assertEquals(1, second.limit());
		assertEquals(3, second.total());
	}

	@Test
	public void testUnresolvedSince() {
		Instant now = Instant.now();
		store.add(error(now.minus(Duration.ofHours(1)), PREVIEWS, "A", null));
		store.add(error(now.minus(Duration.ofHours(2)), STORAGE, "B", null));
		store.resolve(idOf(STORAGE, "B"), admin);
		store.add(error(now.minus(Duration.ofDays(3)), SERVER, "C", null));

		assertEquals(1, store.unresolvedSince(now.minus(Duration.ofDays(1))));
		assertEquals(2, store.unresolvedSince(now.minus(Duration.ofDays(7))));
		assertEquals(0, store.unresolvedSince(now));
	}

	@Test
	public void testLatest() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		store.add(error(now.minus(Duration.ofHours(2)), PREVIEWS, "Too early", null));
		store.add(error(now.minus(Duration.ofMinutes(30)), PREVIEWS, "The older", null));
		store.add(error(now.minus(Duration.ofMinutes(10)), STORAGE, "The newer", "stack"));
		store.add(error(now.minus(Duration.ofMinutes(5)), SERVER, "Another logger's", null));
		// Seen in the window again, though first seen before it
		store.add(error(now.minus(Duration.ofDays(2)), PREVIEWS, "Seen again", null));
		store.add(error(now.minus(Duration.ofMinutes(20)), PREVIEWS, "Seen again", null));

		Instant since = now.minus(Duration.ofHours(1));
		List<ErrorGroup> latest = store.latest(List.of(PREVIEWS, STORAGE), since, 10);
		assertEquals(List.of("The newer", "Seen again", "The older"), latest.stream().map(ErrorGroup::message).toList());
		assertEquals(STORAGE, latest.get(0).logger());
		assertNull("no stack", latest.get(0).stack());
		assertEquals(2, latest.get(1).total());
		assertEquals(List.of("The newer"),
				store.latest(List.of(PREVIEWS, STORAGE), since, 1).stream().map(ErrorGroup::message).toList());
		// At the time itself, to the millisecond
		assertEquals(List.of("The newer"), store.latest(List.of(STORAGE), now.minus(Duration.ofMinutes(10)), 10)
				.stream().map(ErrorGroup::message).toList());
		assertEquals(List.of(), store.latest(List.of(STORAGE), now.minus(Duration.ofMinutes(10)).plusMillis(1), 10));
		assertEquals(List.of(), store.latest(List.of(), since, 10));
	}

	private List<String> messages(ErrorQuery query, Instant now) {
		return store.list(query, now).items().stream().map(ErrorGroup::message).toList();
	}

	private static ErrorQuery query(Window window) {
		return new ErrorQuery(window, null, null, false, 0, 50);
	}

	private static CapturedError error(Instant when, String logger, String message, String stack) {
		return new CapturedError(when, "ERROR", logger, message, stack);
	}

	/** @return the id of the fault an error of the logger's with that message, and no stack, belongs to */
	private long idOf(String logger, String message) {
		String fingerprint = ErrorFingerprint.of(error(Instant.now(), logger, message, null));
		return jdbc.queryForObject("select hd_id from hd_error where hd_fingerprint = ?", Long.class, fingerprint);
	}

	private long count(String sql) {
		return jdbc.queryForObject(sql, Long.class);
	}
}
