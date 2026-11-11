// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 * about it, and still counts as done, so startup goes on. It warns when that
 * account is disabled or has another password, as the script can't sign in
 * as it then.
 * <p>
 * Its password is published, so it makes the user only on a stack with no
 * other users than CE's own {@code admin} and {@code _system} and the
 * {@code sso-user} the {@code smoke-signin} step makes: the variable set by
 * mistake on a stack with people warns, makes nothing and counts as done. It
 * runs after {@code demo-fixture}, which refuses a stack with users.
 * <p>
 * Without the variable the step does nothing and writes no marker, so it can
 * run on a later start.
 */
@Component
public class SmokeApiUserSeed extends SmokeUserSeed {

	static final String USERNAME = "apiuser";

	/**
	 * A throwaway password for a throwaway user on a throwaway stack:
	 * {@code USER_PW} in {@code docker/tests/test-api.sh}, which signs in with
	 * it
	 */
	static final String PASSWORD = "Api-Smoke-Pw-42!x";

	@Autowired
	public SmokeApiUserSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeApiUserSeed(Supplier<String> flag) {
		super(flag, USERNAME, PASSWORD, "Api", "docker/tests/test-api.sh", List.of(SmokeSignInSeed.USERNAME));
	}

	@Override
	public String name() {
		return "smoke-api-user";
	}
}
