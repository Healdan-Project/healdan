package com.logicaldoc.core.security.user;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins each source's stored number: ld_user.ld_source holds the ordinal, so a value inserted before another moves
 * every account after it (Healdan's M4 spec section 15.1).
 */
public class UserSourceTest {

	@Test
	public void testStoredNumbersNeverMove() {
		assertEquals(0, UserSource.DEFAULT.ordinal());
		assertEquals(1, UserSource.LDAP.ordinal());
		assertEquals(2, UserSource.SAML.ordinal());
		assertEquals(3, UserSource.OIDC.ordinal());
		assertEquals(4, UserSource.values().length);
	}

	@Test
	public void testUserReadsOidcBackFromItsNumber() {
		User user = new User();
		user.setSource(3);
		assertEquals(UserSource.OIDC, user.getSource());
	}
}
