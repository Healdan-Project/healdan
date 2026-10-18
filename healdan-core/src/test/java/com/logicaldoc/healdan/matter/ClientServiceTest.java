package com.logicaldoc.healdan.matter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanRuleException;
import com.logicaldoc.healdan.support.HealdanFixtures;

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
	public void testSuggestedNumberIsTheHighestPlusOne() throws Exception {
		assertEquals(1, service.suggestNumber());
		fx.client(998, "A");
		fx.client(7, "B");

		assertEquals(999, service.suggestNumber());
		assertEquals(List.of(7L, 998L), ClientDAO.get().findAllByNumber().stream().map(Client::getNumber).toList());
	}
}
