package com.logicaldoc.healdan.signin;

/**
 * The kinds of provider the Sign-In tab offers to add, each with the values
 * that suit it (M4, spec section 10.2). A preset only fills in the form: once a
 * provider is saved its own settings rule, so a preset can change in a later
 * release without moving anyone's provider.
 */
public enum Preset {

	/** Microsoft Entra ID, whose {@code oid} is stable where its username and email are not */
	ENTRA("entra", "Sign In with Microsoft", "oid", true),

	/** Authentik */
	AUTHENTIK("authentik", "Sign In with Authentik", "sub", false),

	/** Any other provider that speaks OpenID Connect */
	OTHER("other", "Sign In", "sub", false);

	private static final String USERNAME_CLAIM = "preferred_username";

	private static final String EMAIL_CLAIM = "email";

	private static final String GROUPS_CLAIM = "groups";

	private final String key;

	private final String label;

	private final String identityClaim;

	private final boolean linkByEmail;

	Preset(String key, String label, String identityClaim, boolean linkByEmail) {
		this.key = key;
		this.label = label;
		this.identityClaim = identityClaim;
		this.linkByEmail = linkByEmail;
	}

	/**
	 * @param key a preset's key, possibly null
	 * @return the preset with that key; {@link #OTHER} for anything else
	 */
	public static Preset of(String key) {
		for (Preset preset : values())
			if (preset.key.equals(key))
				return preset;
		return OTHER;
	}

	/**
	 * @return the key stored in {@code signin.<id>.preset}
	 */
	public String key() {
		return key;
	}

	/**
	 * @return the label of the provider's button
	 */
	public String label() {
		return label;
	}

	/**
	 * @return the claim that names the person at the provider, and never changes
	 */
	public String identityClaim() {
		return identityClaim;
	}

	/**
	 * @return the claim that holds the username
	 */
	public String usernameClaim() {
		return USERNAME_CLAIM;
	}

	/**
	 * @return the claim that holds the email address
	 */
	public String emailClaim() {
		return EMAIL_CLAIM;
	}

	/**
	 * @return the claim that holds the provider's groups
	 */
	public String groupsClaim() {
		return GROUPS_CLAIM;
	}

	/**
	 * @return whether a first sign-in is matched to an account by email
	 */
	public boolean linkByEmail() {
		return linkByEmail;
	}
}
