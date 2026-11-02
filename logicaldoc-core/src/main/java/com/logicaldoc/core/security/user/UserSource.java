package com.logicaldoc.core.security.user;

public enum UserSource {
	
	DEFAULT, LDAP, SAML,
	// Healdan: an account linked to an OpenID Connect provider (M4 spec section 7.3); appended, so the others keep their numbers
	OIDC;
}
