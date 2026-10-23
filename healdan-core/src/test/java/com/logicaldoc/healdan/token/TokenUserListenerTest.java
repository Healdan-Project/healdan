package com.logicaldoc.healdan.token;

import static org.junit.Assert.assertEquals;

import java.util.Date;

import org.junit.Test;

import com.logicaldoc.core.security.Client;
import com.logicaldoc.core.security.SessionManager;
import com.logicaldoc.core.security.user.User;
import com.logicaldoc.core.security.user.UserDAO;
import com.logicaldoc.core.security.user.UserHistory;
import com.logicaldoc.healdan.AbstractHealdanTestCase;
import com.logicaldoc.healdan.support.HealdanFixtures;

public class TokenUserListenerTest extends AbstractHealdanTestCase {

	private final HealdanFixtures fx = new HealdanFixtures();

	private User clerkWithTwoTokens() throws Exception {
		User clerk = fx.user("clerk");
		ApiTokens.get().issue(clerk, "Laptop", null);
		ApiTokens.get().issue(clerk, "Phone", null);
		assertEquals(2, ApiTokens.get().list(clerk.getId()).size());
		return clerk;
	}

	private void store(User user) throws Exception {
		UserDAO.get().store(user, new UserHistory());
	}

	@Test
	public void testPasswordChangeRevokes() throws Exception {
		User clerk = clerkWithTwoTokens();

		User loaded = UserDAO.get().findById(clerk.getId(), true);
		loaded.setDecodedPassword("N3w-Pass-word!");
		loaded.setPasswordChanged(new Date());
		store(loaded);

		assertEquals(0, ApiTokens.get().list(clerk.getId()).size());
	}

	@Test
	public void testDisableRevokes() throws Exception {
		User clerk = clerkWithTwoTokens();

		User loaded = UserDAO.get().findById(clerk.getId(), true);
		loaded.setEnabled(false);
		store(loaded);

		assertEquals(0, ApiTokens.get().list(clerk.getId()).size());
	}

	@Test
	public void testOtherSavesKeepTokens() throws Exception {
		User clerk = clerkWithTwoTokens();

		User loaded = UserDAO.get().findById(clerk.getId(), true);
		loaded.setEmail("new.address@example.com");
		store(loaded);

		assertEquals(2, ApiTokens.get().list(clerk.getId()).size());
	}

	@Test
	public void testSignInKeepsTokens() throws Exception {
		User clerk = clerkWithTwoTokens();

		SessionManager.get().newSession("clerk", HealdanFixtures.PASSWORD, (Client) null);

		assertEquals(2, ApiTokens.get().list(clerk.getId()).size());
	}
}
