// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.admin.AdminEvent.Change;
import com.logicaldoc.healdan.matter.ClientDAO;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.healdan.support.LogCapture;
import com.logicaldoc.util.spring.Context;

/**
 * The administration record (Admin app spec section 9.3): who changed what,
 * when and from where, with the old and new values, never a secret's; inside
 * the change's transaction, or straight after a change that isn't one.
 */
public class AdminEventsTest extends AbstractHealdanTestCase {

	private static final String SETTING = "insert into hd_setting (name, value) values (?, ?)";

	private final HealdanFixtures fx = new HealdanFixtures();

	private JdbcTemplate jdbc;

	private TransactionTemplate transaction;

	private User admin;

	@Before
	public void prepare() throws Exception {
		jdbc = new JdbcTemplate(Context.get(DataSource.class));
		transaction = new TransactionTemplate(Context.get(PlatformTransactionManager.class));
		admin = fx.admin();
	}

	@After
	public void forgetTheAddress() {
		AdminContext.clear();
	}

	@Test
	public void testTheBean() {
		assertSame(Context.get(AdminEvents.class), AdminEvents.get());
	}

	@Test
	public void testRecordsWhoWhatAndChanges() {
		AdminContext.set("192.0.2.10");
		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", 42L, "B",
				Map.of("name", new Change("A", "B"))), admin);

		Instant after = Instant.now();
		List<Map<String, Object>> rows = jdbc.queryForList("select * from hd_admin_event");
		assertEquals(1, rows.size());
		Map<String, Object> row = rows.get(0);
		assertEquals(admin.getTenantId(), ((Number) row.get("hd_tenantid")).longValue());
		assertEquals(admin.getId(), ((Number) row.get("hd_userid")).longValue());
		assertEquals("admin", row.get("hd_username"));
		assertEquals("192.0.2.10", row.get("hd_address"));
		assertEquals("MATTERS", row.get("hd_area"));
		assertEquals("client.renamed", row.get("hd_action"));
		assertEquals("client", row.get("hd_targetkind"));
		assertEquals(42L, ((Number) row.get("hd_targetid")).longValue());
		assertEquals("B", row.get("hd_targetname"));
		assertEquals("{\"name\":{\"before\":\"A\",\"after\":\"B\"}}", row.get("hd_detail"));
		// The application's clock, to the millisecond
		Instant date = ((Timestamp) row.get("hd_date")).toInstant();
		assertFalse(date + " before " + before, date.isBefore(before));
		assertFalse(date + " after " + after, date.isAfter(after));
	}

	@Test
	public void testDetailKeysInAStableOrder() {
		record(Map.of("number", new Change(null, "998"), "name", new Change(null, "Healdan"), "description",
				new Change("Old", null)));

		assertEquals("{\"description\":{\"before\":\"Old\",\"after\":null},"
				+ "\"name\":{\"before\":null,\"after\":\"Healdan\"},\"number\":{\"before\":null,\"after\":\"998\"}}",
				detail());
	}

	@Test
	public void testNoAddressOutsideAnAdminRequestAndNoDetailWithoutChanges() {
		AdminEvents.get().record(new AdminEvent(AdminArea.STORAGE, "recoverykey.shown", "backend", 3L, "Cloud",
				Map.of()), admin);
		AdminEvents.get().record(new AdminEvent(AdminArea.STORAGE, "recoverykey.shown", "backend", null, null,
				null), admin);

		assertEquals(List.of("null null Cloud 3", "null null null null"),
				jdbc.query("select hd_address, hd_detail, hd_targetname, hd_targetid from hd_admin_event order by hd_id",
						(rs, n) -> rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " "
								+ rs.getObject(4)));
	}

	@Test
	public void testRolledBackWithItsChange() throws Exception {
		IllegalStateException failure = new IllegalStateException("The change fails after its record");

		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> transaction.executeWithoutResult(status -> {
					try {
						// The change through Hibernate, the record through JDBC: one transaction
						fx.client(5, "Five");
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
					record(Map.of("name", new Change(null, "Five")));
					assertEquals(1, events());
					throw failure;
				}));

		assertSame(failure, thrown);
		assertEquals(0, events());
		assertNull(ClientDAO.get().findByNumber(5));
	}

	@Test
	public void testCommittedWithItsChange() throws Exception {
		transaction.executeWithoutResult(status -> {
			jdbc.update(SETTING, "admin.test", "changed");
			record(Map.of("name", new Change(null, "Five")));
		});

		assertEquals(1, events());
		assertEquals("changed", jdbc.queryForObject("select value from hd_setting where name = ?", String.class,
				"admin.test"));
	}

	@Test
	public void testRecordAfterNeverThrows() {
		AdminEvent event = new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", 77L, "Gamma Partners",
				Map.of("name", new Change("Beta Partners", "Gamma Partners")));
		jdbc.execute("alter table hd_admin_event rename to hd_admin_event_away");
		try (LogCapture log = LogCapture.of(AdminEvents.class)) {
			// On its own
			AdminEvents.get().recordAfter(event, admin);

			// And inside a caller's transaction, which goes on after the failed insert and commits
			transaction.executeWithoutResult(status -> {
				jdbc.update(SETTING, "admin.before", "1");
				AdminEvents.get().recordAfter(event, admin);
				jdbc.update(SETTING, "admin.after", "2");
			});

			List<String> lines = log.lines();
			assertEquals(lines.toString(), 2, lines.size());
			for (String line : lines) {
				assertTrue(line, line.startsWith("ERROR "));
				assertTrue(line, line.contains("client.renamed") && line.contains("client 77"));
				// Never a value
				assertFalse(line, line.contains("Partners"));
			}
		} finally {
			jdbc.execute("alter table hd_admin_event_away rename to hd_admin_event");
		}

		assertEquals(0, events());
		assertEquals(List.of("1", "2"), jdbc.queryForList(
				"select value from hd_setting where name in ('admin.before', 'admin.after') order by value",
				String.class));

		// With the table back, it records
		AdminEvents.get().recordAfter(event, admin);
		assertEquals(1, events());
	}

	@Test
	public void testRecordFailsItsCaller() {
		jdbc.execute("alter table hd_admin_event rename to hd_admin_event_away");
		try {
			assertThrows(RuntimeException.class, () -> record(Map.of("name", new Change("A", "B"))));
		} finally {
			jdbc.execute("alter table hd_admin_event_away rename to hd_admin_event");
		}
	}

	/**
	 * Review Focus 3: a changed secret is recorded as changed, with no value
	 */
	@Test
	public void testSecretHasNoValue() {
		String oldSecret = "old-s3-secret-Vq81";
		String newSecret = "new-s3-secret-Zr42";

		Change secret = Change.secret();
		assertTrue(secret.isSecret());
		assertNull(secret.before());
		assertNull(secret.after());
		assertFalse(new Change(oldSecret, newSecret).isSecret());
		assertFalse(new Change(null, null).isSecret());
		assertFalse(secret.equals(new Change(null, null)));

		record(Map.of("secret", Change.secret(), "name", new Change("Cloud", "Cloud 2")));

		assertEquals("{\"name\":{\"before\":\"Cloud\",\"after\":\"Cloud 2\"},\"secret\":{\"secret\":true}}", detail());
		String row = jdbc.queryForMap("select * from hd_admin_event").toString();
		assertFalse(row, row.contains(oldSecret));
		assertFalse(row, row.contains(newSecret));
	}

	@Test
	public void testLongTextIsCutToFit() {
		AdminContext.set("a".repeat(300));
		// A cut never leaves half of a character beyond the Basic Multilingual Plane
		String name = "x".repeat(254) + "\uD83C\uDF89" + "y".repeat(20);

		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", 1L, name,
				Map.of("name", new Change(null, name))), admin);

		Map<String, Object> row = jdbc.queryForMap("select hd_address, hd_targetname, hd_detail from hd_admin_event");
		assertEquals("a".repeat(255), row.get("hd_address"));
		assertEquals("x".repeat(254), row.get("hd_targetname"));
		// The detail is kept whole
		assertTrue(((String) row.get("hd_detail")).contains(name));
	}

	private void record(Map<String, Change> changes) {
		AdminEvents.get().record(new AdminEvent(AdminArea.MATTERS, "client.renamed", "client", 9L, "Nine", changes),
				admin);
	}

	private String detail() {
		return jdbc.queryForObject("select hd_detail from hd_admin_event", String.class);
	}

	private int events() {
		return jdbc.queryForObject("select count(*) from hd_admin_event", Integer.class);
	}
}
