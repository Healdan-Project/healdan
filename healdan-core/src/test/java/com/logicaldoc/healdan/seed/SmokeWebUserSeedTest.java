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

public class SmokeWebUserSeedTest extends AbstractHealdanTestCase {

	private static final String MARKER = "seed.smoke-web-user";

	private static SeedRunner runner() {
		return Context.get(SeedRunner.class);
	}

	@Test
	public void testMakesTheUser() throws Exception {
		SmokeWebUserSeed seed = new SmokeWebUserSeed(() -> "true");
		assertEquals("smoke-web-user", seed.name());
		assertTrue(seed.enabled());
		// The account the smoke-signin step makes before it doesn't count as someone else's
		new HealdanFixtures().user(SmokeSignInSeed.USERNAME);
		new HealdanFixtures().user("apiuser");

		runner().runPending(List.of(seed));
		assertEquals("done", HealdanSettings.get().get(MARKER));

		// The fields of an ordinary user
		User user = UserDAO.get().findByUsername("webuser", true);
		assertNotNull(user);
		assertEquals("Web", user.getName());
		assertEquals("User", user.getFirstName());
		assertEquals("webuser@example.com", user.getEmail());
		assertEquals("en", user.getLanguage());
		assertTrue(user.isEnabled());
		assertEquals(User.Type.DEFAULT, user.getType());
		assertEquals(UserSource.DEFAULT, user.getSource());
		assertEquals(Encrypter.encryptSHA256(SmokeWebUserSeed.PASSWORD), user.getPassword());
		assertFalse(user.isAdmin());
		assertTrue(UserHistoryDAO.get().findByUserId(user.getId()).stream()
				.anyMatch(h -> UserEvent.CREATED.toString().equals(h.getEvent())));

		// And never again
		runner().runPending(List.of(new SmokeWebUserSeed(() -> "true")));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'webuser'", null, null).size());
	}

	@Test
	public void testLeavesAnExistingWebUserAlone() throws Exception {
		// A stack that already has the user
		User before = new HealdanFixtures().user("webuser");
		long id = before.getId();
		String password = before.getPassword();
		assertNotNull(password);

		runner().runPending(List.of(new SmokeWebUserSeed(() -> "true")));

		// The step counts as done, so it never runs again
		assertEquals("done", HealdanSettings.get().get(MARKER));
		assertEquals(1, UserDAO.get().findByWhere("_entity.username = 'webuser'", null, null).size());
		User after = UserDAO.get().findByUsername("webuser", true);
		assertEquals(id, after.getId());
		assertEquals(password, after.getPassword());
		assertFalse(Encrypter.encryptSHA256(SmokeWebUserSeed.PASSWORD).equals(after.getPassword()));
	}

	/** The password is published, so a stack with people never gets the user, whoever set the variable */
	@Test
	public void testMakesNothingOnAStackWithOtherUsers() throws Exception {
		new HealdanFixtures().user("realuser");

		runner().runPending(List.of(new SmokeWebUserSeed(() -> "true")));

		assertNull(UserDAO.get().findByUsername("webuser"));
		// It counts as done, so it doesn't warn on every start
		assertEquals("done", HealdanSettings.get().get(MARKER));
	}

	/** After smoke-api-user, whose user it doesn't count as someone else's, and so after demo-fixture */
	@Test
	public void testRunsAfterTheDemoFixture() {
		assertTrue(SeedRunner.ORDER.indexOf("smoke-web-user") > SeedRunner.ORDER.indexOf("smoke-api-user"));
	}

	@Test
	public void testNothingWithoutTheFlag() throws Exception {
		for (String flag : Arrays.asList(null, "", "false", "TRUE")) {
			SmokeWebUserSeed seed = new SmokeWebUserSeed(() -> flag);
			assertFalse(String.valueOf(flag), seed.enabled());
			runner().runPending(List.of(seed));
		}

		assertNull(UserDAO.get().findByUsername("webuser"));
		// No marker, so a later start with the flag runs it
		assertNull(HealdanSettings.get().get(MARKER));
	}
}
