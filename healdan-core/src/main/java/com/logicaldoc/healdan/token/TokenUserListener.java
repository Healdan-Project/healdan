// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.token;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.core.security.user.UserListener;
import com.logicaldoc.healdan.security.StoredPassword;

/**
 * Revokes a user's device tokens when their password changes or they are
 * disabled. A failure here is logged and never stops the user's save. CE creates
 * it by reflection, hence the public no-arg constructor.
 */
public class TokenUserListener implements UserListener {

	static final String PASSWORD_CHANGED = "healdan.tokens.passwordChanged";

	private static final Logger log = LoggerFactory.getLogger(TokenUserListener.class);

	public TokenUserListener() {
		// Instantiated by CE
	}

	@Override
	public void afterLoad(User user, Map<String, Object> dictionary) throws PersistenceException {
		// Nothing to do
	}

	@Override
	public void beforeSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		if (user.getId() == 0)
			return;
		try {
			String stored = StoredPassword.before(user, dictionary);
			if (stored != null && !stored.equals(user.getPassword()))
				dictionary.put(PASSWORD_CHANGED, Boolean.TRUE);
		} catch (RuntimeException | PersistenceException e) {
			log.warn("Couldn't check whether the password of user {} changed", user.getId(), e);
		}
	}

	@Override
	public void afterSave(User user, UserHistory transaction, Map<String, Object> dictionary)
			throws PersistenceException {
		try {
			if (Boolean.TRUE.equals(dictionary.get(PASSWORD_CHANGED)) || !user.isEnabled())
				ApiTokens.get().revokeAll(user.getId());
		} catch (RuntimeException e) {
			log.warn("Couldn't revoke the device tokens of user {}", user.getId(), e);
		}
	}
}
