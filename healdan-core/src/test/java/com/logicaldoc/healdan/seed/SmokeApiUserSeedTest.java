// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.healdan.seed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserEvent;
import com.logicaldoc.core.security.user.UserHistoryDAO;
import com.logicaldoc.core.security.user.UserSource;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.setting.HealdanSettings;
import com.logicaldoc.healdan.support.HealdanFixtures;
import com.logicaldoc.util.crypt.Encrypter;
import com.logicaldoc.util.spring.Context;

public class SmokeApiUserSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.smoke-api-user";

	private static SeedRunner runner() {
		return Context.get(SeedRunner.class);
	}

	@Test
	public void testMakesTheUser() throws Exception {
		SmokeApiUserSeed seed = new SmokeApiUserSeed(() -> "true");
		assertEquals("smoke-api-user", seed.name());
		assertTrue(seed.enabled());
		// The account the smoke-signin step makes before it doesn't count as someone else's
		new HealdanFixtures().user(SmokeSignInSeed.USERNAME);

		runner().runPending(List.of(seed));
		assertEquals("done", HealdanSettings.get().get(MARKER));

		// The fields test-api.sh's old SOAP storeUser call set
		User user = UserDAO.get().findByUsername("apiuser", true);
		assertNotNull(user);
		assertEquals("Api", user.getName());
		assertEquals("User", user.getFirstName());
		assertEquals("apiuser@example.com", user.getEmail());
		assertEquals("en", user.getLanguage());
		assertTrue(user.isEnabled());
		assertEquals(User.Type.DEFAULT, user.getType());
		assertEquals(UserSource.DEFAULT, user.getSource());
		assertEquals(Encrypter.encryptSHA256(SmokeApiUserSeed.PASSWORD), user.getPassword());
		assertFalse(user.isAdmin());
		assertTrue(UserHistoryDAO.get().findByUserId(user.getId()).stream()
				.anyMatch(h -> UserEvent.CREATED.toString().equals(h.getEvent())));

		// And never again
		runner().runPending(List.of(new SmokeApiUserSeed(() -> "true")));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'apiuser'", null, null).size());
	}

	@Test
	public void testLeavesAnExistingApiUserAlone() throws Exception {
		// A stack upgraded in place already has the user, which test-api.sh made over SOAP
		User before = new HealdanFixtures().user("apiuser");
		long id = before.getId();
		String password = before.getPassword();
		assertNotNull(password);

		runner().runPending(List.of(new SmokeApiUserSeed(() -> "true")));

		// The step counts as done, so it never runs again
		assertEquals("done", HealdanSettings.get().get(MARKER));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'apiuser'", null, null).size());
		User after = UserDAO.get().findByUsername("apiuser", true);
		assertEquals(id, after.getId());
		assertEquals(password, after.getPassword());
		assertFalse(Encrypter.encryptSHA256(SmokeApiUserSeed.PASSWORD).equals(after.getPassword()));
	}

	/** The password is published, so a stack with people never gets the user, whoever set the variable */
	@Test
	public void testMakesNothingOnAStackWithOtherUsers() throws Exception {
		new HealdanFixtures().user("realuser");

		runner().runPending(List.of(new SmokeApiUserSeed(() -> "true")));

		assertNull(UserDAO.get().findByUsername("apiuser"));
		// It counts as done, so it doesn't warn on every start
		assertEquals("done", HealdanSettings.get().get(MARKER));
	}

	/** After demo-fixture, which refuses a stack with users, so the two overlays together still start */
	@Test
	public void testRunsAfterTheDemoFixture() {
		assertTrue(SeedRunner.ORDER.indexOf("smoke-api-user") > SeedRunner.ORDER.indexOf("demo-fixture"));
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : Arrays.asList(null, "", "false", "TRUE")) {
			SmokeApiUserSeed seed = new SmokeApiUserSeed(() -> flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			runner().runPending(List.of(seed));
		}

		assertNull(UserDAO.get().findByUsername("apiuser"));
		// No marker, so a later start with the flag runs it
		assertNull(HealdanSettings.get().get(MARKER));
	}
}
