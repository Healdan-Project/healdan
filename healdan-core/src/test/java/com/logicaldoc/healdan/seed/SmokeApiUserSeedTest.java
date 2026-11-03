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

		runner().runPending(List.of(seed));
		assertEquals("done", HealdanSettings.get().get(MARKER));

		// As SOAP's storeUser made it in docker/tests/test-api.sh
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
