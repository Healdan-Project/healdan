package com.logicaldoc.gui.common.client.healdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * What the sign-in page says when a provider sign-in sent the person back
 * (M4 spec section 5.3). The messages don't name the provider: the page
 * already shows which button was clicked.
 */
public class HealdanSignInButtonsTest {

	@Test
	public void testSaysWhatEachRefusalMeans() {
		assertEquals("Your account doesn't give you access to Healdan. Ask an administrator.",
				HealdanSignInButtons.refusal("no-group"));
		assertEquals("Healdan can't tell which account is yours. Ask an administrator.",
				HealdanSignInButtons.refusal("email-taken"));
		assertEquals("Healdan already has an account with your username. Ask an administrator.",
				HealdanSignInButtons.refusal("username-taken"));
		assertEquals("This account can't sign in now. Ask an administrator.",
				HealdanSignInButtons.refusal("account"));
		assertEquals("The sign-in provider couldn't sign you in. Try again, or ask an administrator.",
				HealdanSignInButtons.refusal("provider"));
		assertEquals("That sign-in took too long or was already used. Start again.",
				HealdanSignInButtons.refusal("expired"));
	}

	@Test
	public void testSaysNothingForACodeItDoesNotKnow() {
		assertNull(HealdanSignInButtons.refusal(null));
		assertNull(HealdanSignInButtons.refusal(""));
		assertNull(HealdanSignInButtons.refusal("No-Group"));
		assertNull(HealdanSignInButtons.refusal("<script>alert(1)</script>"));
	}
}
