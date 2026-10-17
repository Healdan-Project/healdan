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

	@Test
	public void testNewUserJoinsEveryone() throws Exception {
		User user = new User();
		user.setUsername("newbie");
		user.setName("Newbie");
		user.setFirstName("New");
		user.setEmail("newbie@example.com");
		user.setPassword("Secret-1234");
		user.setTenantId(Tenant.DEFAULT_ID);
		UserDAO.get().store(user);

		User stored = UserDAO.get().findByUsername("newbie", true);
		assertTrue(stored.isMemberOf(HealdanConstants.EVERYONE_GROUP));
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
