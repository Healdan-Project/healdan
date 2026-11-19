// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.admin;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.logicaldoc.core.PersistenceException;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.security.AccessChecks;
import com.logicaldoc.util.spring.Context;

/**
 * Who may use each area of the Admin app (Admin app spec section 5). Today
 * that's members of the {@code admin} group, in every area. A later role, such
 * as a Matter Administrator, changes only {@link #allowed}.
 */
@Component
public class AdminAccess {

	/**
	 * @return the bean in the application context
	 */
	public static AdminAccess get() {
		return Context.get(AdminAccess.class);
	}

	/**
	 * Asks afresh each time, so someone taken out of {@code admin} is refused
	 * at their next request.
	 *
	 * @param user the user, or null for nobody
	 * @param area the area
	 * @return true if the user may use the area
	 * @throws PersistenceException error at data layer
	 */
	public boolean allowed(User user, AdminArea area) throws PersistenceException {
		Objects.requireNonNull(area, "area");
		return user != null && AccessChecks.isAdmin(user);
	}
}
