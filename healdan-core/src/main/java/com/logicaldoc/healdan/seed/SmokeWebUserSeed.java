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
public class SmokeWebUserSeed extends SmokeUserSeed {

	static final String USERNAME = "webuser";

	/**
	 * A throwaway password for a throwaway user on a throwaway stack:
	 * {@code WEBUSER_PASSWORD} in {@code docker/tests/test-web.sh}, which signs in with
	 * it
	 */
	static final String PASSWORD = "Web-Smoke-Pw-42!x";

	@Autowired
	public SmokeWebUserSeed() {
		this(() -> System.getenv(SmokeFixtureSeed.ENV));
	}

	SmokeWebUserSeed(Supplier<String> flag) {
		super(flag, USERNAME, PASSWORD, "Web", "docker/tests/test-web.sh",
				List.of(SmokeSignInSeed.USERNAME, SmokeApiUserSeed.USERNAME));
	}

	@Override
	public String name() {
		return "smoke-web-user";
	}
}
