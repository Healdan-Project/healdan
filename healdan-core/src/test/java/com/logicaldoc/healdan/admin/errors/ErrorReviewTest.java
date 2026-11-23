// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.support.AdminRecord;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

/**
 * Marking a fault resolved and unresolved (Admin app spec section 8.8; rulings P5-R2 and P5-R3): administrators only,
 * each change recorded in its transaction, aimed at the fault and named by its message with any URL's credentials
 * taken out, and a change that changes nothing answered as it is and not recorded.
 */
public class ErrorReviewTest extends AbstractHealdanTestCase {

	private static final String LOGGER = "com.logicaldoc.healdan.storage.mover.Mover";

	private final HealdanFixtures fx = new HealdanFixtures();

	private ErrorStore store;

	private ErrorReview review;

	private JdbcTemplate jdbc;

	private User boss;

	@Before
	public void prepare() throws Exception {
		store = ErrorStore.get();
		review = Context.get(ErrorReview.class);
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		boss = fx.user("boss", "admin");
	}

	@Test
	public void testTheBean() {
		assertSame(review, ErrorReview.get());
	}

	@Test
	public void testResolveRecordsWho() throws Exception {
		long id = fault("Backend 'Cloud' can't be reached");

		ErrorGroup resolved = review.resolve(id, boss).orElseThrow();

		assertTrue(resolved.resolved());
		assertEquals("boss", resolved.resolvedBy());
		assertEquals(store.find(id).orElseThrow(), resolved);
		assertEquals(List.of(boss.getId() + " boss null MONITORING error.resolved error " + id
				+ " Backend 'Cloud' can't be reached null"), AdminRecord.events());
	}

	@Test
	public void testResolvingAResolvedFaultChangesAndRecordsNothing() throws Exception {
		long id = fault("It failed");
		review.resolve(id, boss);
		User other = fx.user("other", "admin");

		ErrorGroup again = review.resolve(id, other).orElseThrow();

		assertTrue(again.resolved());
		assertEquals("boss", again.resolvedBy());
		assertEquals(1, AdminRecord.events().size());
	}

	@Test
	public void testReopenRecordsWho() throws Exception {
		long id = fault("It failed");
		review.resolve(id, boss);
		User other = fx.user("other", "admin");

		ErrorGroup reopened = review.reopen(id, other).orElseThrow();

		assertFalse(reopened.resolved());
		assertNull(reopened.resolvedBy());
		assertNull(reopened.resolvedAt());
		assertEquals(other.getId() + " other null MONITORING error.reopened error " + id + " It failed null",
				AdminRecord.events().get(1));
	}

	@Test
	public void testReopeningAnUnresolvedFaultChangesAndRecordsNothing() throws Exception {
		long id = fault("It failed");

		ErrorGroup same = review.reopen(id, boss).orElseThrow();

		assertFalse(same.resolved());
		assertEquals(store.find(id).orElseThrow(), same);
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testNoSuchFault() throws Exception {
		long id = fault("It failed");

		assertTrue(review.resolve(id + 1000, boss).isEmpty());
		assertTrue(review.reopen(id + 1000, boss).isEmpty());
		assertEquals(List.of(), AdminRecord.events());
	}

	@Test
	public void testTheRecordsNameIsTheMessageWithoutCredentialsCutToFit() throws Exception {
		long secret = fault("Can't reach https://alice:s3cret@backup.example/files or ftp://bob@ftp.example/");
		String longest = "x".repeat(300);
		long longer = fault(longest);

		review.resolve(secret, boss);
		review.resolve(longer, boss);

		List<String> events = AdminRecord.events();
		assertEquals(boss.getId() + " boss null MONITORING error.resolved error " + secret
				+ " Can't reach https://backup.example/files or ftp://ftp.example/ null", events.get(0));
		assertEquals(boss.getId() + " boss null MONITORING error.resolved error " + longer + " " + "x".repeat(255)
				+ " null", events.get(1));
	}

	@Test
	public void testOnlyAnAdministratorFirst() throws Exception {
		long id = fault("It failed");
		User clerk = fx.user("clerk");

		HealdanRuleException refused = assertThrows(HealdanRuleException.class, () -> review.resolve(id, clerk));
		assertEquals(ErrorReview.ONLY_ADMINS, refused.getMessage());
		// Before anything else: a fault that isn't there is refused the same
		refused = assertThrows(HealdanRuleException.class, () -> review.reopen(id + 1000, clerk));
		assertEquals(ErrorReview.ONLY_ADMINS, refused.getMessage());

		review.resolve(id, boss);
		refused = assertThrows(HealdanRuleException.class, () -> review.reopen(id, clerk));
		assertEquals(ErrorReview.ONLY_ADMINS, refused.getMessage());
		assertTrue(store.find(id).orElseThrow().resolved());
		assertEquals(1, AdminRecord.events().size());
	}

	@Test
	public void testTheChangeAndItsRecordAreOneTransaction() throws Exception {
		long id = fault("It failed");
		// A record that can't be written
		jdbc.execute("alter table hd_admin_event add constraint test_no_resolved check (hd_action <> 'error.resolved')");
		jdbc.execute("alter table hd_admin_event add constraint test_no_reopened check (hd_action <> 'error.reopened')");

		assertThrows(DataAccessException.class, () -> review.resolve(id, boss));
		assertFalse("rolled back with its record", store.find(id).orElseThrow().resolved());

		jdbc.execute("alter table hd_admin_event drop constraint test_no_resolved");
		review.resolve(id, boss);
		assertThrows(DataAccessException.class, () -> review.reopen(id, boss));
		assertTrue("rolled back with its record", store.find(id).orElseThrow().resolved());
		assertEquals(1, AdminRecord.events().size());
	}

	/** @return the id of a new fault of {@link #LOGGER}'s with the message */
	private long fault(String message) {
		store.add(new CapturedError(Instant.now(), "ERROR", LOGGER, message, null));
		return jdbc.queryForObject("select max(hd_id) from hd_error", Long.class);
	}
}
