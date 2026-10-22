package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;
import com.logicaldoc.healdan.HealdanRuleException;

public class EveryoneUserListenerTest extends AbstractHealdanTestCase {

	private static User newUser(String username, User.Type type) {
		User user = new User();
		user.setUsername(username);
		user.setName(username);
		user.setFirstName(username);
		user.setEmail(username + "@example.com");
		user.setPassword("Secret-1234");
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setType(type);
		return user;
	}

	@Test
	public void testNewUserJoinsEveryone() throws Exception {
		UserDAO.get().store(newUser("newbie", User.Type.DEFAULT));

		User stored = UserDAO.get().findByUsername("newbie", true);
		assertTrue(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		assertTrue(stored.isMemberOf("_user_" + stored.getId()));
	}

	@Test
	public void testUserOfAnotherTenantStaysOutOfDefaultEveryone() throws Exception {
		User user = newUser("outsider", User.Type.DEFAULT);
		user.setTenantId(2L);
		UserDAO.get().store(user);

		User stored = UserDAO.get().findByUsername("outsider", true);
		assertFalse(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
	}

	@Test
	public void testNewReadOnlyUserStaysOutOfEveryone() throws Exception {
		// CE keeps a read-only user in its own group and guest only
		UserDAO.get().store(newUser("reader", User.Type.READONLY));

		User stored = UserDAO.get().findByUsername("reader", true);
		assertFalse(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		assertTrue(stored.isMemberOf("guest"));
		assertTrue(stored.isMemberOf("_user_" + stored.getId()));
	}

	@Test
	public void testAnEditCantTakeAUserOutOfEveryone() throws Exception {
		User admin = UserDAO.get().findByUsername("admin", true);
		admin.removeGroup(
				admin.getGroups().stream().filter(g -> HealdanConstants.EVERYONE_GROUP.equals(g.getName())).findFirst()
						.orElseThrow().getId());
		UserDAO.get().store(admin);

		assertTrue(UserDAO.get().findByUsername("admin", true).isMemberOf(HealdanConstants.EVERYONE_GROUP));
	}

	@Test
	public void testEveryoneCantBeDeleted() throws Exception {
		// Its members would stay members of the deleted group, which CE's permission checks still count
		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		HealdanRuleException refusal = assertThrows(HealdanRuleException.class,
				() -> GroupDAO.get().delete(everyone.getId()));
		assertEquals("The Everyone group can't be deleted.", refusal.getMessage());
		assertEquals(0, GroupDAO.get().findById(everyone.getId()).getDeleted());
		assertTrue(UserDAO.get().findByUsername("admin", true).isMemberOf(HealdanConstants.EVERYONE_GROUP));
	}

	@Test
	public void testReplacingAUsersGroupsKeepsEveryone() throws Exception {
		// What the GUI's user form and SOAP's storeUser do right after creating the user
		UserDAO.get().store(newUser("picked", User.Type.DEFAULT));
		User user = UserDAO.get().findByUsername("picked", true);
		user.removeGroupMemberships(null);
		user.addGroup(GroupDAO.get().findByName("author", Tenant.DEFAULT_ID));
		UserDAO.get().store(user);

		User stored = UserDAO.get().findByUsername("picked", true);
		assertTrue(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		assertTrue(stored.isMemberOf("author"));
	}
}
