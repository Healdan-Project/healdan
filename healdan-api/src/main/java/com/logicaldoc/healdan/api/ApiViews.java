package com.logicaldoc.healdan.api;

import org.apache.commons.lang.StringUtils;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.healdan.api.model.UserRef;
import com.logicaldoc.healdan.api.model.UserView;

/**
 * Turns CE's and Healdan's objects into the API's views.
 */
public final class ApiViews {

	private ApiViews() {
	}

	/**
	 * @param user a user, with its groups
	 * @return the user's view; a blank email or language is null
	 */
	public static UserView user(User user) {
		return new UserView(user.getId(), user.getUsername(), user.getFullName(),
				StringUtils.defaultIfBlank(user.getEmail(), null), StringUtils.defaultIfBlank(user.getLanguage(), null),
				user.isAdmin());
	}

	/**
	 * @param id a user's id, or null
	 * @param name the name recorded with it
	 * @return a reference to the user, or null when there's no id
	 */
	public static UserRef userRef(Long id, String name) {
		return id == null ? null : new UserRef(id, name);
	}
}
