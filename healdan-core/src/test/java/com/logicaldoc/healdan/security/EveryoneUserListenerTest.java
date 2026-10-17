package com.logicaldoc.healdan.security;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.HealdanConstants;

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
	public void testNewReadOnlyUserStaysOutOfEveryone() throws Exception {
		// CE keeps a read-only user in its own group and guest only
		UserDAO.get().store(newUser("reader", User.Type.READONLY));

		User stored = UserDAO.get().findByUsername("reader", true);
		assertFalse(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
		assertTrue(stored.isMemberOf("guest"));
		assertTrue(stored.isMemberOf("_user_" + stored.getId()));
	}

	@Test
	public void testExistingUserIsNotAddedAgainByAnEdit() throws Exception {
		User admin = UserDAO.get().findByUsername("admin", true);
		admin.removeGroup(
				admin.getGroups().stream().filter(g -> HealdanConstants.EVERYONE_GROUP.equals(g.getName())).findFirst()
						.orElseThrow().getId());
		UserDAO.get().store(admin);

		assertFalse(UserDAO.get().findByUsername("admin", true).isMemberOf(HealdanConstants.EVERYONE_GROUP));
	}
}
