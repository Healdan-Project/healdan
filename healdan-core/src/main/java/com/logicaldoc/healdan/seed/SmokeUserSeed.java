// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.util.crypt.Encrypter;

/**
 * A smoke-test user that one of {@code docker/tests}' scripts signs in as, for
 * the smoke test only: made when {@code HEALDAN_SMOKE_FIXTURE} is
 * {@code true}, not an admin, with no groups of its own and the
 * {@code CREATED} history row that storing a new user writes.
 * <p>
 * An existing user of that name is left exactly as it is, and the step still
 * counts as done. It warns when that account is disabled or has another
 * password, as the script can't sign in as it then.
 * <p>
 * Its password is published, so it makes the user only on a stack with no
 * other users than CE's own {@code admin} and {@code _system} and the ones
 * the subclass names: the variable set by mistake on a stack with people
 * warns, makes nothing and counts as done.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
abstract class SmokeUserSeed implements SeedStep {

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final Supplier<String> flag;

	private final String username;

	private final String password;

	private final String name;

	private final String script;

	private final List<String> tolerated;

	/**
	 * @param flag reads {@code HEALDAN_SMOKE_FIXTURE}
	 * @param username the user's username
	 * @param password its published password
	 * @param name its last name; the first name is User
	 * @param script the script that signs in as it, for the warnings
	 * @param tolerated the users besides {@code admin} and {@code _system} a
	 *        fresh smoke stack may already have
	 */
	SmokeUserSeed(Supplier<String> flag, String username, String password, String name, String script,
			List<String> tolerated) {
		this.flag = flag;
		this.username = username;
		this.password = password;
		this.name = name;
		this.script = script;
		this.tolerated = tolerated;
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	@Override
	public void run() throws Exception {
		User existing = UserDAO.get().findByUsername(username);
		if (existing != null) {
			if (existing.isEnabled() && Encrypter.encryptSHA256(password).equals(existing.getPassword()))
				log.info("The user {} already exists and is left as it is", username);
			else
				log.warn("The user {} already exists, {}, and is left as it is, so {} can't sign in as it", username,
						existing.isEnabled() ? "with another password" : "disabled", script);
			return;
		}

		// The names are this class's own constants, never input
		String names = Stream.concat(Stream.of("admin", "_system"), tolerated.stream()).map(n -> "'" + n + "'")
				.collect(Collectors.joining(", "));
		long others = UserDAO.get().queryForLong("select count(*) from ld_user where ld_username not in (" + names + ")");
		if (others > 0) {
			log.warn("{} is true, but this stack has {} other users, so it isn't a fresh smoke stack: the user {}, "
					+ "whose password is published, isn't made", SmokeFixtureSeed.ENV, others, username);
			return;
		}

		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(username);
		user.setName(name);
		user.setFirstName("User");
		user.setEmail(username + "@example.com");
		user.setLanguage("en");
		user.setDecodedPassword(password);
		user.setType(User.Type.DEFAULT);
		user.setSource(UserSource.DEFAULT);
		user.setEnabled(true);
		UserDAO.get().store(user);
	}
}
