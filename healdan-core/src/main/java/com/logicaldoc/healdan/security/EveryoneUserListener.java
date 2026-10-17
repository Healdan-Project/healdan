package com.logicaldoc.healdan.security;

import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.Group;
import com.logicaldoc.core.security.user.GroupDAO;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserListener;
import com.logicaldoc.healdan.HealdanConstants;

/**
 * Puts each new user, except read-only users, into the Everyone group. CE
 * creates it by reflection, hence the public no-arg constructor.
 */
public class EveryoneUserListener implements UserListener {

	public EveryoneUserListener() {
		// Instantiated by CE
	}

	@Override
	public void afterLoad(User user, Map<String, Object> dictionary) throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		/*
		 * Read-only users follow CE: HibernateUserDAO.store strips every group
		 * from them except their own and guest, before the listeners run, so they
		 * can't gain rights through a shared group. Adding Everyone here would
		 * undo that. No SYSTEM check: store() has already turned SYSTEM into
		 * DEFAULT by now.
		 */
		if (user.getId() != 0 || user.isReadonly())
			return;

		Group everyone = GroupDAO.get().findByName(HealdanConstants.EVERYONE_GROUP, user.getTenantId());
		if (everyone != null)
			user.addGroup(everyone);
	}

	@Override
	public void afterSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		// Nothing to do
	}
}
