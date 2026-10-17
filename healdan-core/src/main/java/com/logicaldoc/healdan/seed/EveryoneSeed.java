package com.logicaldoc.healdan.seed;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * Creates the Everyone group, unless it exists, and adds every user to it
 * except the system user and read-only users.
 */
@Component
public class EveryoneSeed implements SeedStep {

	@Override
	public String name() {
		return "everyone";
	}

	@Override
	public void run() throws Exception {
		GroupDAO groupDao = GroupDAO.get();
		Group everyone = groupDao.findByName(HealdanConstants.EVERYONE_GROUP, Tenant.DEFAULT_ID);
		if (everyone == null) {
			everyone = new Group();
			everyone.setName(HealdanConstants.EVERYONE_GROUP);
			everyone.setDescription("Every user of the install");
			everyone.setTenantId(Tenant.DEFAULT_ID);
			groupDao.insert(everyone, 0);
		}

		UserDAO userDao = UserDAO.get();
		for (Long id : userDao.findAllIds(Tenant.DEFAULT_ID)) {
			if (id == User.USERID_SYSTEM)
				continue;
			// Initialize first: storing rewrites all the user's group memberships
			User user = userDao.findById(id, true);
			/*
			 * Read-only users follow CE, which keeps them in their own group and
			 * guest only: HibernateUserDAO.store would strip Everyone again.
			 */
			if (user == null || user.isReadonly() || user.isMemberOf(HealdanConstants.EVERYONE_GROUP))
				continue;
			user.addGroup(everyone);
			userDao.store(user);
		}
	}
}
