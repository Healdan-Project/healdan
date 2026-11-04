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

/**
 * For the smoke test only: when {@code HEALDAN_SMOKE_FIXTURE} is {@code true},
 * as {@code docker/tests/compose.smoke.yaml} sets it, creates the user
 * {@code apiuser}, not an admin, that {@code docker/tests/test-api.sh} signs in
 * as. The script made it with SOAP's {@code storeUser} until Healdan removed
 * LogicalDOC's SOAP services (SOAP removal spec, section 5), and this makes it
 * the same way: no groups of its own, and the {@code CREATED} history row that
 * storing a new user writes.
 * <p>
 * A stack upgraded in place may already have {@code apiuser}, made by that
 * script over SOAP. The step then leaves it exactly as it is, changing nothing
 * about it, and still counts as done, so startup goes on.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
@Component
public class SmokeApiUserSeed implements SeedStep {

	private static final Logger log = LoggerFactory.getLogger(SmokeApiUserSeed.class);

	static final String USERNAME = "apiuser";

	/**
	 * A throwaway password for a throwaway user on a throwaway stack:
	 * {@code USER_PW} in {@code docker/tests/test-api.sh}, which signs in with
	 * it
	 */
	static final String PASSWORD = "Api-Smoke-Pw-42!x";

	private final Supplier<String> flag;

	@Autowired
	public SmokeApiUserSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeApiUserSeed(Supplier<String> flag) {
		this.flag = flag;
	}

	@Override
	public String name() {
		return "smoke-api-user";
	}

	@Override
	public boolean enabled() {
		return "true".equals(flag.get());
	}

	@Override
	public void run() throws Exception {
		if (UserDAO.get().findByUsername(USERNAME) != null) {
			log.info("The user {} already exists and is left as it is", USERNAME);
			return;
		}

		User user = new User();
		user.setTenantId(Tenant.DEFAULT_ID);
		user.setUsername(USERNAME);
		user.setName("Api");
		user.setFirstName("User");
		user.setEmail("apiuser@example.com");
		user.setLanguage("en");
		user.setDecodedPassword(PASSWORD);
		user.setType(User.Type.DEFAULT);
		user.setSource(UserSource.DEFAULT);
		user.setEnabled(true);
		UserDAO.get().store(user);
	}
}
