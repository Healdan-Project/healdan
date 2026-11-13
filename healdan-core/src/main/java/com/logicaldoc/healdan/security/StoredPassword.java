// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.security;

import java.util.List;
import java.util.Map;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;

/**
 * The password a user's row holds before a save, for the user listeners that
 * ask whether the save changes it ({@code TokenUserListener},
 * {@link ThrottleUserListener}). It's read once per save and kept in the
 * save's dictionary, which every listener of the save shares; each listener
 * decides for itself what counts as a change.
 */
public final class StoredPassword {

	private static final String KEY = "healdan.storedPassword";

	private StoredPassword() {
	}

	/**
	 * @param user the user being saved
	 * @param dictionary the save's dictionary
	 * @return the password the user's row holds, or null for a new account, a
	 *         row with none, or no row
	 * @throws PersistenceException if it can't be read
	 */
	public static String before(User user, Map<String, Object> dictionary) throws PersistenceException {
		if (user.getId() == 0)
			return null;
		if (dictionary.containsKey(KEY))
			return (String) dictionary.get(KEY);
		List<String> stored = UserDAO.get().queryForList("select ld_password from ld_user where ld_id = :id",
				Map.of("id", user.getId()), String.class, null);
		String password = stored.isEmpty() ? null : stored.get(0);
		dictionary.put(KEY, password);
		return password;
	}
}
