package com.logicaldoc.healdan.signin;

import java.util.List;

/**
 * What a provider's ID token says about the person, once every check passed
 * (M4, spec sections 7 and 13). The provider's own claim names, as its
 * settings give them, are already applied.
 *
 * @param issuer the token's {@code iss}, which is the provider's configured
 *        issuer
 * @param identity the provider's identity claim ({@code oid} for Entra ID,
 *        {@code sub} otherwise), which names the person and never changes;
 *        never null or blank
 * @param username the username claim; null if it isn't there or isn't a
 *        string
 * @param email the email claim; null if it isn't there or isn't a string
 * @param givenName the standard {@code given_name}; null if it isn't there
 * @param familyName the standard {@code family_name}; null if it isn't there
 * @param name the standard {@code name}; null if it isn't there
 * @param groups the groups claim's values, from a list or a single string;
 *        empty when the claim isn't there. Values that aren't strings are left
 *        out.
 * @param groupsOverage true if the provider left the groups out because there
 *        were too many, naming the groups claim in {@code _claim_names} (Entra
 *        ID's "overage", spec section 3.2)
 */
public record IdClaims(String issuer, String identity, String username, String email, String givenName,
		String familyName, String name, List<String> groups, boolean groupsOverage) {

	public IdClaims {
		groups = groups == null ? List.of() : List.copyOf(groups);
	}
}
