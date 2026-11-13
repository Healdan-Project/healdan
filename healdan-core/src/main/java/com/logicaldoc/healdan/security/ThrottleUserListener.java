// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.HealdanThrottle;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserListener;

/**
 * Clears an account's username count in the sign-in throttle when it gets a
 * new password, whatever gives it: an administrator's Change Password, the
 * person's own, the sign-in page's, the lost-password link or a new account
 * (the spec of 2026-11-13 on passwords that have to change, section 3.2). The
 * failures counted were guesses at a password the account no longer has, so
 * an administrator's reset lifts a block. The address's count stays. A failure
 * here is logged and never stops the save. CE creates it by reflection, hence
 * the public no-arg constructor.
 */
public class ThrottleUserListener implements UserListener {

	static final String PASSWORD_SET = "healdan.throttle.passwordSet";

	private static final Logger log = LoggerFactory.getLogger(ThrottleUserListener.class);

	public ThrottleUserListener() {
		// Instantiated by CE
	}

	@Override
	public void afterLoad(User user, Map<String, Object> dictionary) throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (StringUtils.isEmpty(user.getPassword()))
			return;
		if (user.getId() == 0) {
			dictionary.put(PASSWORD_SET, Boolean.TRUE);
			return;
		}
		try {
			List<String> stored = UserDAO.get().queryForList("select ld_password from ld_user where ld_id = :id",
					Map.of("id", user.getId()), String.class, null);
			if (stored.isEmpty() || !user.getPassword().equals(stored.get(0)))
				dictionary.put(PASSWORD_SET, Boolean.TRUE);
		} catch (RuntimeException | PersistenceException e) {
			log.warn("Couldn't check whether the password of user {} changed", user.getId(), e);
		}
	}

	@Override
	public void afterSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (!Boolean.TRUE.equals(dictionary.get(PASSWORD_SET)))
			return;
		try {
			HealdanThrottle.passwordSet(user.getUsername());
		} catch (RuntimeException e) {
			log.warn("Couldn't clear the sign-in failures of user {}", user.getId(), e);
		}
	}
}
