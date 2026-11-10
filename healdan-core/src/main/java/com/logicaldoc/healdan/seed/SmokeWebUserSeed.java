// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.logicaldoc.core.security.Tenant;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.util.crypt.Encrypter;

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * as {@code docker/tests/compose.smoke.yaml} sets it, creates the user
 * {@code webuser}, not an admin, that the User app's browser tests
 * ({@code docker/tests/test-web.sh}) sign in as. It is made as
 * {@code smoke-api-user} makes {@code apiuser}, with no groups of its own, and
 * it is a different user because {@code test-api.sh} leaves {@code apiuser}
 * blocked by the sign-in throttle for 15 minutes.
 * <p>
 * An existing {@code webuser} is left exactly as it is, and the step still
 * counts as done. It warns when that account is disabled or has another
 * password, as the tests can't sign in as it then.
 * <p>
 * Its password is published, so it makes the user only on a stack with no
 * other users than CE's own {@code admin} and {@code _system}, the
 * {@code sso-user} the {@code smoke-signin} step makes and {@code apiuser}:
 * the variable set by mistake on a stack with people warns, makes nothing and
 * counts as done. It runs after {@code smoke-api-user}.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
@Component
public class SmokeWebUserSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(SmokeWebUserSeed.class);

	static final String USERNAME = "webuser";

	/**
	 * A throwaway password for a throwaway user on a throwaway stack:
	 * {@code WEBUSER_PASSWORD} in {@code docker/tests/test-web.sh}, which signs in with
	 * it
	 */
	static final String PASSWORD = "Web-Smoke-Pw-42!x";

	private final Supplier<String> flag;

	@Autowired
	public SmokeWebUserSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeWebUserSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-web-user";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	@Override
	public void run() throws Exception {
		User existing = UserDAO.get().findByUsername(USERNAME);
		if (existing != null) {
			if (existing.isEnabled() && Encrypter.encryptSHA256(PASSWORD).equals(existing.getPassword()))
				log.info("The user {} already exists and is left as it is", USERNAME);
			else
				log.warn("The user {} already exists, {}, and is left as it is, so docker/tests/test-web.sh can't "
						+ "sign in as it", USERNAME, existing.isEnabled() ? "with another password" : "disabled");
			return;
		}

		long others = UserDAO.get().queryForLong("select count(*) from ld_user where ld_username not in "
				+ "('admin', '_system', '%s', 'apiuser')".formatted(SmokeSignInSeed.USERNAME));
		if (others > 0) {
			log.warn("{} is true, but this stack has {} other users, so it isn't a fresh smoke stack: the user {}, "
					+ "whose password is published, isn't made", SmokeFixtureSeed.ENV, others, USERNAME);
			return;
		}

		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(USERNAME);
		user.setName("Web");
		user.setFirstName("User");
		user.setEmail("webuser@example.com");
		user.setLanguage("en");
		user.setDecodedPassword(PASSWORD);
		user.setType(User.Type.DEFAULT);
		user.setSource(UserSource.DEFAULT);
		user.setEnabled(true);
		UserDAO.get().store(user);
	}
}
