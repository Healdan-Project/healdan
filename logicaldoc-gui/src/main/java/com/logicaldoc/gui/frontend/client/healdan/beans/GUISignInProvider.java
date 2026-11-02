package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

/**
 * A sign-in provider, as the Sign-In tab shows and saves it (M4 spec section
 * 10.3). It never holds the client secret: {@link #isHasSecret()} says only
 * whether one is stored, and a save takes a new one beside it.
 */
public class GUISignInProvider implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The id in the sign-in address, which can't change */
	private String id;

	/** The preset's key: {@code entra}, {@code authentik} or {@code other} */
	private String preset;

	private String label;

	private boolean enabled;

	private String issuer;

	private String clientId;

	/** Whether a client secret is stored and can be read */
	private boolean hasSecret;

	private String identityClaim;

	private String usernameClaim;

	private String emailClaim;

	private String groupsClaim;

	private boolean linkByEmail;

	/** The redirect address to register at the provider */
	private String redirect;

	/** The group map, by provider group and then Healdan group */
	private GUIGroupMapping[] groups = new GUIGroupMapping[0];

	/** How many accounts are linked to it */
	private int linked;

	/**
	 * True for a provider the tab's Add started and that isn't saved yet: its
	 * save refuses an id another provider already has, rather than change that
	 * provider
	 */
	private boolean adding;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getPreset() {
		return preset;
	}

	public void setPreset(String preset) {
		this.preset = preset;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public boolean isHasSecret() {
		return hasSecret;
	}

	public void setHasSecret(boolean hasSecret) {
		this.hasSecret = hasSecret;
	}

	public String getIdentityClaim() {
		return identityClaim;
	}

	public void setIdentityClaim(String identityClaim) {
		this.identityClaim = identityClaim;
	}

	public String getUsernameClaim() {
		return usernameClaim;
	}

	public void setUsernameClaim(String usernameClaim) {
		this.usernameClaim = usernameClaim;
	}

	public String getEmailClaim() {
		return emailClaim;
	}

	public void setEmailClaim(String emailClaim) {
		this.emailClaim = emailClaim;
	}

	public String getGroupsClaim() {
		return groupsClaim;
	}

	public void setGroupsClaim(String groupsClaim) {
		this.groupsClaim = groupsClaim;
	}

	public boolean isLinkByEmail() {
		return linkByEmail;
	}

	public void setLinkByEmail(boolean linkByEmail) {
		this.linkByEmail = linkByEmail;
	}

	public String getRedirect() {
		return redirect;
	}

	public void setRedirect(String redirect) {
		this.redirect = redirect;
	}

	public GUIGroupMapping[] getGroups() {
		return groups;
	}

	public void setGroups(GUIGroupMapping[] groups) {
		this.groups = groups;
	}

	public int getLinked() {
		return linked;
	}

	public void setLinked(int linked) {
		this.linked = linked;
	}

	public boolean isAdding() {
		return adding;
	}

	public void setAdding(boolean adding) {
		this.adding = adding;
	}
}
