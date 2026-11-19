// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.Before;
import org.junit.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.admin.AdminContext;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.spring.Context;

public class ClientServiceTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private ClientService service;

	private User admin;

	@Before
	public void prepare() throws Exception {
		service = ClientService.get();
		admin = UserDAO.get().findByUsername("admin", true);
	}

	@Test
	public void testAdminCreatesAClientWithATrimmedName() throws Exception {
		Client client = service.create(998, "  Healdan / Co  ", admin);

		Client stored = ClientDAO.get().findByNumber(998);
		assertEquals(client.getId(), stored.getId());
		assertEquals("Healdan / Co", stored.getName());
	}

	@Test
	public void testNonAdminCannotCreateOrRename() throws Exception {
		User user = fx.user("clerk");
		Client existing = fx.client(1, "One");

		assertThrows(HealdanRuleException.class, () -> service.create(2, "Two", user));
		assertThrows(HealdanRuleException.class, () -> service.rename(existing.getId(), "Changed", user));
		assertNull(ClientDAO.get().findByNumber(2));
		assertEquals("One", ClientDAO.get().findByNumber(1).getName());
	}

	@Test
	public void testNameMustBeNonEmptyAndShortEnough() throws Exception {
		assertThrows(HealdanRuleException.class, () -> service.create(1, "   ", admin));
		assertThrows(HealdanRuleException.class, () -> service.create(1, null, admin));
		assertThrows(HealdanRuleException.class, () -> service.create(1, "x".repeat(256), admin));
		assertNotNull(service.create(1, "x".repeat(255), admin));
	}

	@Test
	public void testNameMustFitTheDatabase() throws Exception {
		// MariaDB's utf8 tables hold no character beyond the Basic Multilingual Plane, such as an emoji (R33)
		String message = "The client name cannot contain emoji or other characters the database can't store.";
		HealdanRuleException refused = assertThrows(HealdanRuleException.class,
				() -> service.create(1, "Party \uD83C\uDF89", admin));
		assertEquals(message, refused.getMessage());
		assertNull(ClientDAO.get().findByNumber(1));
		// A lone surrogate too
		assertThrows(HealdanRuleException.class, () -> service.create(1, "Half \uD83C", admin));

		Client client = service.create(1, "Caf\u00e9 \u2013 \u4e2d\u6587", admin);
		refused = assertThrows(HealdanRuleException.class,
				() -> service.rename(client.getId(), "\uD83C\uDF89", admin));
		assertEquals(message, refused.getMessage());
		assertEquals("Caf\u00e9 \u2013 \u4e2d\u6587", ClientDAO.get().findByNumber(1).getName());
	}

	@Test
	public void testAdminLoadedWithoutGroupsIsAnAdmin() throws Exception {
		// A user's groups are transient: empty until the DAO initializes the user
		User bare = UserDAO.get().findById(User.USERID_ADMIN);
		assertFalse(bare.isAdmin());

		Client client = service.create(7, "Seven", bare);
		service.rename(client.getId(), "Seventh", bare);
		assertEquals("Seventh", ClientDAO.get().findByNumber(7).getName());
	}

	@Test
	public void testNumberMustBePositiveAndFree() throws Exception {
		service.create(5, "Five", admin);

		assertThrows(HealdanRuleException.class, () -> service.create(5, "Again", admin));
		assertThrows(HealdanRuleException.class, () -> service.create(0, "Zero", admin));
	}

	@Test
	public void testRenameKeepsTheNumber() throws Exception {
		Client client = service.create(5, "Five", admin);

		service.rename(client.getId(), " Fifth ", admin);

		Client stored = ClientDAO.get().findById(client.getId());
		assertEquals("Fifth", stored.getName());
		assertEquals(5, stored.getNumber());
		assertThrows(HealdanRuleException.class, () -> service.rename(client.getId(), "", admin));
		assertThrows(HealdanRuleException.class, () -> service.rename(99999, "Nobody", admin));
	}

	@Test
	public void testCreateAndRenameAreRecorded() throws Exception {
		Client client = service.create(998, " Healdan ", admin);
		AdminContext.set("192.0.2.20");
		try {
			service.rename(client.getId(), "Healdan Ltd", admin);
			// No change, no record
			service.rename(client.getId(), " Healdan Ltd ", admin);
		} finally {
			AdminContext.clear();
		}
		// A refusal records nothing
		assertThrows(HealdanRuleException.class, () -> service.create(998, "Again", admin));

		// From the Classic Interface, with no address; then from the Admin app's
		assertEquals(List.of(
				"%d admin null MATTERS client.created client %d Healdan {\"name\":{\"before\":null,\"after\":\"Healdan\"},\"number\":{\"before\":null,\"after\":\"998\"}}"
						.formatted(admin.getId(), client.getId()),
				"%d admin 192.0.2.20 MATTERS client.renamed client %d Healdan Ltd {\"name\":{\"before\":\"Healdan\",\"after\":\"Healdan Ltd\"}}"
						.formatted(admin.getId(), client.getId())),
				events());
	}

	@Test
	public void testRenameRollsBackWithAFailedRecord() throws Exception {
		Client client = service.create(5, "Five", admin);
		JdbcTemplate jdbc = new JdbcTemplate(Context.get(DataSource.class));

		// The record's insert fails, inside the change's transaction
		jdbc.execute("alter table hd_admin_event rename to hd_admin_event_away");
		try {
			assertThrows(DataAccessException.class, () -> service.rename(client.getId(), "Fifth", admin));
			assertThrows(DataAccessException.class, () -> service.create(6, "Six", admin));
		} finally {
			jdbc.execute("alter table hd_admin_event_away rename to hd_admin_event");
		}

		// The changes are gone with it
		assertEquals("Five",
				jdbc.queryForObject("select hd_name from hd_client where ld_id = ?", String.class, client.getId()));
		assertEquals("Five", ClientDAO.get().findById(client.getId()).getName());
		assertNull(ClientDAO.get().findByNumber(6));
		assertEquals(1, events().size());
	}

	@Test
	public void testSuggestedNumberIsTheHighestPlusOne() throws Exception {
		assertEquals(1, service.suggestNumber());
		fx.client(998, "A");
		fx.client(7, "B");

		assertEquals(999, service.suggestNumber());
		assertEquals(List.of(7L, 998L), ClientDAO.get().findAllByNumber().stream().map(Client::getNumber).toList());
	}

	/**
	 * @return each row of the administration record, oldest first, as its
	 *         user, username, address, area, action, target and detail
	 */
	static List<String> events() {
		return new JdbcTemplate(Context.get(DataSource.class)).query(
				"select hd_userid, hd_username, hd_address, hd_area, hd_action, hd_targetkind, hd_targetid,"
						+ " hd_targetname, hd_detail from hd_admin_event order by hd_id",
				(rs, n) -> IntStream.rangeClosed(1, 9).mapToObj(i -> {
					try {
						return String.valueOf(rs.getObject(i));
					} catch (SQLException e) {
						throw new IllegalStateException(e);
					}
				}).collect(Collectors.joining(" ")));
	}
}
