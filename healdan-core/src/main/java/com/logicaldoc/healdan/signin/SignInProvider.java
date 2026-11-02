package com.logicaldoc.healdan.signin;

/**
 * A provider as the admin set it up. It never holds the client secret, which
 * only {@link SignInProviders#secret} gives, to the code that calls the
 * provider.
 * 
 * @param id the id in the sign-in address, which can't change
 * @param preset the kind of provider
 * @param label the label of its button
 * @param enabled whether people can sign in through it
 * @param issuer the provider's issuer address; null until set
 * @param clientId the client id of Healdan's app registration; null until set
 * @param hasSecret whether a client secret is stored and can be opened
 * @param identityClaim the claim that names the person
 * @param usernameClaim the claim that holds the username
 * @param emailClaim the claim that holds the email address
 * @param groupsClaim the claim that holds the person's groups
 * @param linkByEmail whether a first sign-in is matched to an account by email
 */
public record SignInProvider(String id, Preset preset, String label, boolean enabled, String issuer, String clientId,
		boolean hasSecret, String identityClaim, String usernameClaim, String emailClaim, String groupsClaim,
		boolean linkByEmail) {

	/**
	 * @param id the id of the new provider
	 * @param preset the kind of provider
	 * @return the preset's values, on, with no issuer, client id or secret yet
	 */
	public static SignInProvider fromPreset(String id, Preset preset) {
		return new SignInProvider(id, preset, preset.label(), true, null, null, false, preset.identityClaim(),
				preset.usernameClaim(), preset.emailClaim(), preset.groupsClaim(), preset.linkByEmail());
	}
}
