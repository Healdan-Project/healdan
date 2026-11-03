// SPDX-FileCopyrightText: 2026 The Healdan Project
// SPDX-License-Identifier: LGPL-3.0-only

package com.logicaldoc.gui.frontend.client.healdan.beans;

import java.io.Serializable;

import com.logicaldoc.gui.common.client.beans.GUIGroup;

/**
 * What the Sign-In tab shows (M4 spec section 10.3): the providers, and the
 * options that aren't a provider's
 */
public class GUISignInOptions implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The providers, in the order of their buttons */
	private GUISignInProvider[] providers = new GUISignInProvider[0];

	/** The label of the link to sign in with a password */
	private String passwordLabel;

	/** Whether the recipients are emailed when {@code admin} signs in with its password */
	private boolean adminAlert;

	/** The usernames the alert goes to */
	private String[] recipients = new String[0];

	/** Whether outgoing email is set up, which the alert needs */
	private boolean outgoingEmail;

	/** The Healdan groups a provider group can be mapped to, by name: never a person's own group */
	private GUIGroup[] groups = new GUIGroup[0];

	public GUISignInProvider[] getProviders() {
		return providers;
	}

	public void setProviders(GUISignInProvider[] providers) {
		this.providers = providers;
	}

	public String getPasswordLabel() {
		return passwordLabel;
	}

	public void setPasswordLabel(String passwordLabel) {
		this.passwordLabel = passwordLabel;
	}

	public boolean isAdminAlert() {
		return adminAlert;
	}

	public void setAdminAlert(boolean adminAlert) {
		this.adminAlert = adminAlert;
	}

	public String[] getRecipients() {
		return recipients;
	}

	public void setRecipients(String[] recipients) {
		this.recipients = recipients;
	}

	public boolean isOutgoingEmail() {
		return outgoingEmail;
	}

	public void setOutgoingEmail(boolean outgoingEmail) {
		this.outgoingEmail = outgoingEmail;
	}

	public GUIGroup[] getGroups() {
		return groups;
	}

	public void setGroups(GUIGroup[] groups) {
		this.groups = groups;
	}
}
